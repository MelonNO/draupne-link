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

    /** An axis counts as fully swept once max - min reaches this (nominal full range is 2.0). */
    public static final float FULL_RANGE = 1.5f;
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
        Arrays.fill(min, Float.MAX_VALUE);
        Arrays.fill(max, -Float.MAX_VALUE);
        steps.add(new Step(StepKind.RANGE, -1, Direction.NONE, "Move all sticks to their limits",
                "Rotate both sticks in full circles a few times, pushing firmly into every corner."));
        steps.add(new Step(StepKind.CENTER, -1, Direction.NONE, "Center the sticks",
                "Let roll, pitch and yaw rest in the center and pull the throttle fully DOWN. Then press Next."));
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
            } else {
                String what = i == 0 && purposes.length == 0 ? "the switch you use to ARM" : "the switch you want on AUX" + (i + 1);
                steps.add(new Step(StepKind.SWITCH, 4 + i, Direction.NONE, "AUX" + (i + 1) + " switch",
                        "Flip " + what + " to its ON position. Press Skip if you don't need it."));
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
        for (int i = 0; i < Math.min(axisCount, axes.length); i++) {
            min[i] = Math.min(min[i], axes[i]);
            max[i] = Math.max(max[i], axes[i]);
        }
        Step step = getStep();
        if (step.kind() == StepKind.CENTER) trackStillness(axes, nowMs);
        String detected = switch (step.kind()) {
            case ASSIGN -> detectAxis(axes, center);
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

    private String detectAxis(float[] axes, float[] reference) {
        int best = -1;
        float bestDelta = 0;
        for (int i = 0; i < Math.min(axisCount, axes.length); i++) {
            if (isAxisAssigned(i)) continue;
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
        String axis = detectAxis(state.axes(), baseAxes);
        if (axis != null) return axis;
        boolean[] buttons = state.buttons();
        for (int i = 0; i < Math.min(buttonCount, buttons.length); i++) {
            if (isButtonAssigned(i) || buttons[i] == baseButtons[i]) continue;
            return (buttons[i] ? "" : "-") + "button" + i;
        }
        return null;
    }

    private void advance() {
        index = Math.min(index + 1, steps.size() - 1);
        candidate = null;
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

    public boolean isFullySwept(int axis) {
        return max[axis] - min[axis] >= FULL_RANGE;
    }

    public int getFullySweptAxisCount() {
        int count = 0;
        for (int i = 0; i < axisCount; i++) if (isFullySwept(i)) count++;
        return count;
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
