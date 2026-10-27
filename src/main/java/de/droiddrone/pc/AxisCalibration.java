package de.droiddrone.pc;

import static de.droiddrone.pc.RcMapper.MAX_CHANNEL_LEVEL;
import static de.droiddrone.pc.RcMapper.MID_CHANNEL_LEVEL;
import static de.droiddrone.pc.RcMapper.MIN_CHANNEL_LEVEL;

/** Measured raw range of one joystick axis (GLFW units, nominally -1..1). */
public record AxisCalibration(float min, float center, float max) {
    private static final float MIN_SPAN = 0.05f;

    /** Self-centering sticks (roll, pitch, yaw): min -> 1000, center -> 1500, max -> 2000. */
    public int toCentered(float v) {
        float c = Math.max(min, Math.min(max, center));
        float value;
        if (v >= c) {
            float span = max - c;
            if (span < MIN_SPAN) return toLinear(v);
            value = MID_CHANNEL_LEVEL + (v - c) / span * (MAX_CHANNEL_LEVEL - MID_CHANNEL_LEVEL);
        } else {
            float span = c - min;
            if (span < MIN_SPAN) return toLinear(v);
            value = MID_CHANNEL_LEVEL - (c - v) / span * (MID_CHANNEL_LEVEL - MIN_CHANNEL_LEVEL);
        }
        return clamp(Math.round(value));
    }

    /** Throttle and switches: min -> 1000, max -> 2000. */
    public int toLinear(float v) {
        float span = max - min;
        if (span < MIN_SPAN) return RcMapper.axisToChannel(v);
        return clamp(Math.round(MIN_CHANNEL_LEVEL + (v - min) / span * (MAX_CHANNEL_LEVEL - MIN_CHANNEL_LEVEL)));
    }

    private static int clamp(int value) {
        return Math.max(MIN_CHANNEL_LEVEL, Math.min(MAX_CHANNEL_LEVEL, value));
    }
}
