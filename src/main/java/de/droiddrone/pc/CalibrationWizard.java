package de.droiddrone.pc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Step-by-step controller calibration: sweep the stick ranges, capture the centers, then find each stick and switch
 * by asking the pilot to move it (detected axis + direction, so inverted channels are fixed automatically).
 * Pure logic with no UI or GLFW, so it can be unit-tested; the GUI feeds it joystick states.
 */
public class CalibrationWizard {
    public enum StepKind { RANGE, CENTER, ASSIGN, SWITCH, SUMMARY }

    public enum Direction { NONE, UP, RIGHT }

    /** @param channel AETR+AUX channel index this step assigns, or -1 */
    public record Step(StepKind kind, int channel, Direction direction, String title, String hint) {}

    /**
     * An axis counts as swept once max - min reaches this (nominal full range is 2.0): half of it, so a radio set to
     * send less (1800 at most, say) still gets through - however far a stick goes is its end, saved and sent as
     * 1000 / 2000 (1.0.39, the pilot: "if the user does not use 2000 but only 1800 that is then max"). Up to 1.0.38 it
     * took 1.5, and such a radio never got past the first step.
     */
    public static final float FULL_RANGE = 1.0f;
    public static final int STICK_AXES = 4;
    /** How far (in raw units) a stick or switch must move from rest to be detected. */
    public static final float MOVE_THRESHOLD = 0.5f;
    /** How long a detected movement must be held before it is accepted. */
    public static final long HOLD_MS = 400;
    public static final int SWITCH_STEPS = 4;
    /** AUX switches asked for: SWITCH_STEPS, or more when a chosen switch is higher up. */
    private final int switchSteps;
    /**
     * The centre is taken once every axis has rested this long within STILL_TOLERANCE, as the average over that time:
     * not the one reading at the moment Next is pressed, which caught a thumb still on the stick (a pitch centre 18 us
     * off made the drone lean forward when landed and armed).
     */
    public static final long STILL_MS = 500;
    static final float STILL_TOLERANCE = 0.01f;
    /** A roll / pitch / yaw centre this far (in us of the channel) from the middle of its range gets a warning. */
    public static final int OFF_CENTER_US = 10;
    /**
     * The range step goes on by itself this long after all four stick axes are swept and their limits stopped growing
     * (1.0.39, the pilot): a little time to push into the last corners, then the centre step - no Next needed. Any
     * limit still moving out starts the wait again, so it never goes on before the sticks' real ends are in.
     */
    public static final long RANGE_RESERVE_MS = 2000;
    /** A limit growing by more than this (raw) counts as still moving out (noise stays under it). */
    private static final float GROWTH = 0.01f;
    /** Ranges smaller than this are not trusted as a calibration; the nominal -1..1 is saved instead. */
    private static final float MIN_CALIBRATED_RANGE = 1.0f;

    private final List<Step> steps = new ArrayList<>();
    private final int axisCount;
    private final int buttonCount;
    private final float[] min;
    private final float[] max;
    private float[] center;
    private float[] stillRef;
    private long stillSince;
    private double[] stillSum;
    private int stillCount;
    private boolean centerReady;
    private float[] baseAxes;
    private boolean[] baseButtons;
    private final String[] specs = new String[PcConfig.CHANNEL_NAMES.length];
    private int index;
    private Joystick.State last;
    /** Range step: since when all four stick axes have reached their limits (-1: not yet). */
    private long sweptSince = -1;
    /**
     * Range step: each axis's smooth travel - the sum of its small steps. A stick sweeping round goes through every value
     * on the way; a switch flipped meanwhile jumps, and adds next to nothing (1.0.39, the pilot: "detect only four
     * channels as the joysticks ... so no other channel can use it").
     */
    private final float[] smoothTravel;
    private float[] previousAxes;
    /** The four stick axes, fixed when the range step ends; null before. Only they can be throttle, yaw, pitch, roll. */
    private int[] stickAxes;
    private String candidate;
    private long candidateSince;

    public CalibrationWizard(int axisCount, int buttonCount) {
        this(axisCount, buttonCount, new de.droiddrone.pc.setup.ModeSwitch.Purpose[0]);
    }

    /**
     * @param purposes what each AUX switch is for (index 0 = AUX1, null = nothing chosen), from Drone setup: its step
     *                 is named after it ("AUX2 - flight modes"). At least AUX1-AUX4 are asked for, and any switch above
     *                 those that has a purpose.
     */
    public CalibrationWizard(int axisCount, int buttonCount, de.droiddrone.pc.setup.ModeSwitch.Purpose[] purposes) {
        int switches = SWITCH_STEPS;
        for (int i = 0; i < purposes.length; i++) if (purposes[i] != null) switches = Math.max(switches, i + 1);
        this.switchSteps = Math.min(switches, PcConfig.CHANNEL_NAMES.length - STICK_AXES);
        this.axisCount = axisCount;
        this.buttonCount = buttonCount;
        min = new float[axisCount];
        max = new float[axisCount];
        smoothTravel = new float[axisCount];
        Arrays.fill(min, Float.MAX_VALUE);
        Arrays.fill(max, -Float.MAX_VALUE);
        steps.add(new Step(StepKind.RANGE, -1, Direction.NONE, "Move all sticks to their limits",
                "Rotate both sticks in full circles a few times, pushing firmly into every corner. However far they go is their end (1000 / 2000)."));
        steps.add(new Step(StepKind.CENTER, -1, Direction.NONE, "Center the sticks",
                "Put all four sticks in the center - the throttle too - and take your thumbs off. This center is what the "
                        + "flight controller gets as the middle (1500). Then press Next."));
        steps.add(new Step(StepKind.ASSIGN, 3, Direction.UP, "Throttle up",
                "Push the THROTTLE stick fully UP and hold it there."));
        steps.add(new Step(StepKind.ASSIGN, 2, Direction.RIGHT, "Yaw right",
                "Push the YAW stick fully RIGHT and hold it there."));
        steps.add(new Step(StepKind.ASSIGN, 1, Direction.UP, "Pitch up",
                "Push the PITCH stick fully UP (forward) and hold it there."));
        steps.add(new Step(StepKind.ASSIGN, 0, Direction.RIGHT, "Roll right",
                "Push the ROLL stick fully RIGHT and hold it there."));
        for (int i = 0; i < switchSteps; i++) {
            de.droiddrone.pc.setup.ModeSwitch.Purpose p = i < purposes.length ? purposes[i] : null;
            if (p != null) {
                steps.add(new Step(StepKind.SWITCH, 4 + i, Direction.NONE, "AUX" + (i + 1) + " - " + p.title(), p.hint()));
            } else if (i == 0 && purposes.length == 0) {
                steps.add(new Step(StepKind.SWITCH, 4, Direction.NONE, "AUX1 switch",
                        "Flip the switch you use to ARM to its ON position. Press Skip if you don't need it."));
            } else {
                // nothing chosen for it in Drone setup: a spare, for whatever the pilot sets in Betaflight later
                steps.add(new Step(StepKind.SWITCH, 4 + i, Direction.NONE, "AUX" + (i + 1) + " - reserve",
                        "Nothing is set for AUX" + (i + 1) + " yet. Flip a spare switch to ON to keep it for later, or press Skip."));
            }
        }
        steps.add(new Step(StepKind.SUMMARY, -1, Direction.NONE, "Check and save",
                "Move every stick and switch and check its channel follows. Tick Invert for anything backwards, then press Finish."));
    }

    /** How many AUX switches are asked for. */
    public int switchCount() {
        return switchSteps;
    }

    /** Feed the latest joystick state (null when the controller is gone). Auto-advances once a movement is held. */
    public void update(Joystick.State state, long nowMs) {
        if (state == null) {
            candidate = null;
            return;
        }
        last = state;
        float[] axes = state.axes();
        boolean grew = false;
        for (int i = 0; i < Math.min(axisCount, axes.length); i++) {
            if (axes[i] < min[i] - GROWTH || axes[i] > max[i] + GROWTH) grew = min[i] != Float.MAX_VALUE;
            min[i] = Math.min(min[i], axes[i]);
            max[i] = Math.max(max[i], axes[i]);
        }
        Step step = getStep();
        if (step.kind() == StepKind.RANGE) {
            if (previousAxes != null) {
                for (int i = 0; i < Math.min(axisCount, Math.min(axes.length, previousAxes.length)); i++) {
                    float d = Math.abs(axes[i] - previousAxes[i]);
                    if (d < SMOOTH_STEP) smoothTravel[i] += d;
                }
            }
            previousAxes = Arrays.copyOf(axes, axes.length);
            if (getFullySweptAxisCount() < STICK_AXES) {
                sweptSince = -1;
            } else if (sweptSince < 0 || grew) { // a limit still moving out: wait from now
                sweptSince = nowMs;
            } else if (nowMs - sweptSince >= RANGE_RESERVE_MS) {
                advance(); // on to centring, by itself
                return;
            }
        }
        if (step.kind() == StepKind.CENTER) trackStillness(axes, nowMs);
        String detected = switch (step.kind()) {
            case ASSIGN -> detectAxis(axes, center, true);
            case SWITCH -> detectSwitch(state);
            default -> null;
        };
        if (detected == null) {
            candidate = null;
            return;
        }
        if (!detected.equals(candidate)) {
            candidate = detected;
            candidateSince = nowMs;
            return;
        }
        if (nowMs - candidateSince >= HOLD_MS) {
            specs[step.channel()] = detected;
            advance();
        }
    }

    /** Centre step: how long the sticks have rested, and their average over that time. */
    private void trackStillness(float[] axes, long nowMs) {
        boolean moved = stillRef == null;
        for (int i = 0; !moved && i < Math.min(axisCount, axes.length); i++) {
            moved = Math.abs(axes[i] - stillRef[i]) > STILL_TOLERANCE;
        }
        if (moved) {
            stillRef = Arrays.copyOf(axes, axisCount);
            stillSince = nowMs;
            stillSum = new double[axisCount];
            stillCount = 0;
        }
        for (int i = 0; i < Math.min(axisCount, axes.length); i++) stillSum[i] += axes[i];
        stillCount++;
        centerReady = nowMs - stillSince >= STILL_MS;
    }

    private void resetStillness() {
        stillRef = null;
        centerReady = false;
    }

    /** Range step: ms until it goes on by itself, or -1 while not every stick axis has reached its limits. */
    public long rangeAutoNextInMs(long nowMs) {
        if (getStep().kind() != StepKind.RANGE || sweptSince < 0) return -1;
        return Math.max(0, RANGE_RESERVE_MS - (nowMs - sweptSince));
    }

    /** Centre step: the sticks have rested long enough for Next. */
    public boolean isCenterReady() {
        return centerReady;
    }

    /**
     * Roll, pitch and yaw whose captured centre is well off the middle of their range, e.g. "PITCH 17 us": sent at rest,
     * that is a constant stick input. Self-centring gimbals rest in the middle, so this usually means the stick was
     * touched (or a trim was off) at the centre step.
     */
    public List<String> offCenterSticks() {
        List<String> off = new ArrayList<>();
        if (center == null) return off;
        for (int ch = 0; ch < PcConfig.THROTTLE_INDEX; ch++) {
            String spec = specs[ch];
            if (spec == null || !spec.replace("-", "").startsWith("axis")) continue;
            int axis = Integer.parseInt(spec.replace("-", "").substring(4));
            if (axis >= axisCount || max[axis] - min[axis] < MIN_CALIBRATED_RANGE) continue;
            float mid = (min[axis] + max[axis]) / 2, half = (max[axis] - min[axis]) / 2;
            int us = Math.round(Math.abs(center[axis] - mid) / half * 500);
            if (us >= OFF_CENTER_US) off.add(PcConfig.CHANNEL_NAMES[ch].toUpperCase(Locale.ROOT) + " " + us + " us");
        }
        return off;
    }

    /** @param sticks the stick steps: only the four stick axes; the switch steps: anything but them */
    private String detectAxis(float[] axes, float[] reference, boolean sticks) {
        int best = -1;
        float bestDelta = 0;
        for (int i = 0; i < Math.min(axisCount, axes.length); i++) {
            if (isAxisAssigned(i)) continue;
            if (stickAxes != null && isStickAxis(i) != sticks) continue;
            float delta = axes[i] - reference[i];
            if (Math.abs(delta) > Math.abs(bestDelta)) {
                best = i;
                bestDelta = delta;
            }
        }
        if (best == -1 || Math.abs(bestDelta) < MOVE_THRESHOLD) return null;
        // the pilot moved the control towards "high" (up/right/on): if the raw value fell, the channel is inverted
        return (bestDelta < 0 ? "-" : "") + "axis" + best;
    }

    private String detectSwitch(Joystick.State state) {
        String axis = detectAxis(state.axes(), baseAxes, false);
        if (axis != null) return axis;
        boolean[] buttons = state.buttons();
        for (int i = 0; i < Math.min(buttonCount, buttons.length); i++) {
            if (isButtonAssigned(i) || buttons[i] == baseButtons[i]) continue;
            return (buttons[i] ? "" : "-") + "button" + i;
        }
        return null;
    }

    private void advance() {
        if (getStep().kind() == StepKind.RANGE) stickAxes = pickStickAxes(); // locked from here on
        index = Math.min(index + 1, steps.size() - 1);
        candidate = null;
        sweptSince = -1;
        resetStillness();
        if (getStep().kind() == StepKind.SWITCH) captureSwitchBaseline();
    }

    public boolean canNext() {
        return switch (getStep().kind()) {
            case RANGE -> getFullySweptAxisCount() >= STICK_AXES;
            case CENTER -> last != null && centerReady;
            case SUMMARY -> true;
            default -> false;
        };
    }

    /** Manual "Next" for the range and center steps (the center is captured here). */
    public void next() {
        if (!canNext() || getStep().kind() == StepKind.SUMMARY) return;
        if (getStep().kind() == StepKind.CENTER) {
            center = new float[axisCount];
            for (int i = 0; i < axisCount; i++) center[i] = (float) (stillSum[i] / stillCount);
        }
        advance();
    }

    public boolean canSkip() {
        return getStep().kind() == StepKind.SWITCH;
    }

    public void skip() {
        if (!canSkip()) return;
        specs[getStep().channel()] = null;
        advance();
    }

    /** Steps back one step and forgets what that step and every later step assigned. */
    public void back() {
        if (index == 0) return;
        index--;
        resetStillness();
        sweptSince = -1;
        if (getStep().kind() == StepKind.RANGE) stickAxes = null; // found again when it ends
        for (int i = index; i < steps.size(); i++) {
            if (steps.get(i).channel() >= 0) specs[steps.get(i).channel()] = null;
        }
        candidate = null;
        if (getStep().kind() == StepKind.SWITCH) captureSwitchBaseline();
    }

    /** Switch steps detect changes relative to the controls' positions when the step starts. */
    private void captureSwitchBaseline() {
        baseAxes = last == null ? new float[axisCount] : Arrays.copyOf(last.axes(), axisCount);
        baseButtons = last == null ? new boolean[buttonCount] : Arrays.copyOf(last.buttons(), buttonCount);
    }

    public Step getStep() {
        return steps.get(index);
    }

    public int getStepIndex() {
        return index;
    }

    /** Every step, in order (tests). */
    List<Step> steps() {
        return List.copyOf(steps);
    }

    public int getStepCount() {
        return steps.size();
    }

    public String getCandidate() {
        return candidate;
    }

    /** 0..1 of the hold time for the current candidate. */
    public float getCandidateProgress(long nowMs) {
        if (candidate == null) return 0;
        return Math.min(1f, (nowMs - candidateSince) / (float) HOLD_MS);
    }

    public float[] getMin() {
        return min;
    }

    public float[] getMax() {
        return max;
    }

    /** null until the center step is done */
    public float[] getCenter() {
        return center;
    }

    /** A step this small counts as a stick moving smoothly (raw); a switch's flip is one big jump. */
    static final float SMOOTH_STEP = 0.25f;
    /** Smooth travel a stick has done in the range step at the least: one sweep from end to end and back. */
    static final float SMOOTH_MIN = 1.5f;

    /** Swept as a stick: its range there, and got there smoothly (not a switch flipped meanwhile). */
    public boolean isFullySwept(int axis) {
        return max[axis] - min[axis] >= FULL_RANGE && smoothTravel[axis] >= SMOOTH_MIN;
    }

    /** The stick axes found so far, at most four (the range step's "n of 4"). */
    public int getFullySweptAxisCount() {
        int count = 0;
        for (int i = 0; i < axisCount; i++) if (isFullySwept(i)) count++;
        return Math.min(STICK_AXES, count);
    }

    /** The four that swept smoothest are the sticks (more than four: a slider moved too - the most travel wins). */
    private int[] pickStickAxes() {
        Integer[] order = new Integer[axisCount];
        for (int i = 0; i < axisCount; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(smoothTravel[b], smoothTravel[a]));
        List<Integer> picked = new ArrayList<>();
        for (Integer i : order) if (picked.size() < STICK_AXES && isFullySwept(i)) picked.add(i);
        int[] out = new int[picked.size()];
        for (int i = 0; i < out.length; i++) out[i] = picked.get(i);
        Arrays.sort(out);
        return out;
    }

    /** One of the four sticks (only after the range step). */
    public boolean isStickAxis(int axis) {
        if (stickAxes == null) return false;
        for (int a : stickAxes) if (a == axis) return true;
        return false;
    }

    /** The four stick axes, locked when the range step ended; null before. */
    public int[] getStickAxes() {
        return stickAxes == null ? null : stickAxes.clone();
    }

    /** @return the channel name an axis is assigned to (e.g. "throttle"), or null */
    public String getAxisRole(int axis) {
        return roleOf("axis" + axis);
    }

    public String getButtonRole(int button) {
        return roleOf("button" + button);
    }

    private String roleOf(String source) {
        for (int i = 0; i < specs.length; i++) {
            if (specs[i] != null && specs[i].replace("-", "").equals(source)) return PcConfig.CHANNEL_NAMES[i];
        }
        return null;
    }

    private boolean isAxisAssigned(int axis) {
        return getAxisRole(axis) != null;
    }

    private boolean isButtonAssigned(int button) {
        return getButtonRole(button) != null;
    }

    public String getSpec(int channel) {
        return specs[channel];
    }

    public boolean isInverted(int channel) {
        return specs[channel] != null && specs[channel].startsWith("-");
    }

    public void setInverted(int channel, boolean inverted) {
        String spec = specs[channel];
        if (spec == null) return;
        String plain = spec.replace("-", "");
        specs[channel] = inverted ? "-" + plain : plain;
    }

    /** Measured ranges for every axis the mapping uses. */
    public Map<Integer, AxisCalibration> getCalibrations() {
        Map<Integer, AxisCalibration> calibrations = new HashMap<>();
        for (String spec : specs) {
            if (spec == null || !spec.replace("-", "").startsWith("axis")) continue;
            int axis = Integer.parseInt(spec.replace("-", "").substring(4));
            if (axis >= axisCount) continue;
            if (max[axis] - min[axis] < MIN_CALIBRATED_RANGE) {
                calibrations.put(axis, new AxisCalibration(-1f, 0f, 1f));
            } else {
                float c = center == null ? (min[axis] + max[axis]) / 2 : center[axis];
                calibrations.put(axis, new AxisCalibration(min[axis], c, max[axis]));
            }
        }
        return calibrations;
    }

    /** Mapper for the live preview on the summary step (sticks must all be assigned). */
    public RcMapper buildMapper() {
        return new RcMapper(Arrays.copyOf(specs, PcConfig.THROTTLE_INDEX + 1 + switchSteps), getCalibrations());
    }

    /** Settings to write to draupne-link.properties. Skipped AUX switches are written empty (unmapped). */
    public Map<String, String> result() {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < PcConfig.THROTTLE_INDEX + 1 + switchSteps; i++) {
            result.put(PcConfig.CHANNEL_NAMES[i], specs[i] == null ? "" : specs[i]);
        }
        getCalibrations().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            AxisCalibration cal = e.getValue();
            result.put("axis" + e.getKey() + ".min", format(cal.min()));
            result.put("axis" + e.getKey() + ".center", format(cal.center()));
            result.put("axis" + e.getKey() + ".max", format(cal.max()));
        });
        return result;
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
