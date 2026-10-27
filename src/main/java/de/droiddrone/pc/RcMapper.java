package de.droiddrone.pc;

import java.util.Map;

/**
 * Turns raw joystick axes/buttons into DroidDrone RC channel values (1000..2000 µs, AETR+AUX order).
 * Mirrors upstream Control Rc.convertAxisValue for axes and Rc.setKeyValue for buttons.
 */
public class RcMapper {
    public static final int MIN_CHANNEL_LEVEL = 1000;
    public static final int MAX_CHANNEL_LEVEL = 2000;
    public static final int MID_CHANNEL_LEVEL = (MIN_CHANNEL_LEVEL + MAX_CHANNEL_LEVEL) / 2;

    public enum Kind { AXIS, BUTTON, FIXED }

    public record Source(Kind kind, int index, boolean invert, int fixedValue) {
        public static Source parse(String spec) {
            String s = spec.trim().toLowerCase();
            boolean invert = s.startsWith("-");
            if (invert) s = s.substring(1);
            try {
                if (s.startsWith("axis")) return new Source(Kind.AXIS, Integer.parseInt(s.substring(4)), invert, 0);
                if (s.startsWith("button")) return new Source(Kind.BUTTON, Integer.parseInt(s.substring(6)), invert, 0);
                if (!invert) {
                    int value = Integer.parseInt(s);
                    if (value >= MIN_CHANNEL_LEVEL && value <= MAX_CHANNEL_LEVEL) return new Source(Kind.FIXED, -1, false, value);
                }
            } catch (NumberFormatException ignored) {
            }
            throw new IllegalArgumentException("Bad channel source '" + spec + "' (use axisN, buttonN, -axisN or 1000..2000)");
        }
    }

    private final Source[] sources;
    private final Map<Integer, AxisCalibration> calibrations;
    private final int channelCount;

    /** @param specs source spec per channel in AETR+AUX order, null for unused channels */
    public RcMapper(String[] specs) {
        this(specs, Map.of());
    }

    /**
     * @param calibrations measured range per axis index; axes without one use the nominal -1..1 range.
     *                     Roll/pitch/yaw map around the measured center, throttle and AUX map linearly.
     */
    public RcMapper(String[] specs, Map<Integer, AxisCalibration> calibrations) {
        this.calibrations = Map.copyOf(calibrations);
        sources = new Source[specs.length];
        int last = -1;
        for (int i = 0; i < specs.length; i++) {
            if (specs[i] == null) continue;
            sources[i] = Source.parse(specs[i]);
            last = i;
        }
        if (last < PcConfig.THROTTLE_INDEX) {
            throw new IllegalArgumentException("roll, pitch, yaw and throttle must all be mapped");
        }
        for (int i = 0; i <= PcConfig.THROTTLE_INDEX; i++) {
            if (sources[i] == null) throw new IllegalArgumentException(PcConfig.CHANNEL_NAMES[i] + " must be mapped");
        }
        channelCount = last + 1;
    }

    public int getChannelCount() {
        return channelCount;
    }

    /**
     * @return channel values, or null if the joystick lacks an axis/button the mapping needs
     *         (fail closed: never send a frame with a guessed channel).
     */
    public short[] map(float[] axes, boolean[] buttons) {
        short[] channels = new short[channelCount];
        for (int i = 0; i < channelCount; i++) {
            Source src = sources[i];
            int value;
            if (src == null) {
                // same idle values as upstream Rc.resetChannel
                value = i < 3 ? MID_CHANNEL_LEVEL : MIN_CHANNEL_LEVEL;
            } else {
                switch (src.kind()) {
                    case AXIS -> {
                        if (axes == null || src.index() >= axes.length) return null;
                        float raw = axes[src.index()];
                        AxisCalibration cal = calibrations.get(src.index());
                        if (cal == null) {
                            value = axisToChannel(raw);
                        } else {
                            value = i < PcConfig.THROTTLE_INDEX ? cal.toCentered(raw) : cal.toLinear(raw);
                        }
                        if (src.invert()) value = MIN_CHANNEL_LEVEL + MAX_CHANNEL_LEVEL - value;
                    }
                    case BUTTON -> {
                        if (buttons == null || src.index() >= buttons.length) return null;
                        boolean down = buttons[src.index()] != src.invert();
                        value = down ? MAX_CHANNEL_LEVEL : MIN_CHANNEL_LEVEL;
                    }
                    default -> value = src.fixedValue();
                }
            }
            channels[i] = (short) value;
        }
        return channels;
    }

    public static int axisToChannel(float axis) {
        int value = Math.round((axis + 1) * ((MAX_CHANNEL_LEVEL - MIN_CHANNEL_LEVEL) / 2f) + MIN_CHANNEL_LEVEL);
        return Math.max(MIN_CHANNEL_LEVEL, Math.min(MAX_CHANNEL_LEVEL, value));
    }
}
