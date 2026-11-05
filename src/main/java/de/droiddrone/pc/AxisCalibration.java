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

    /**
     * Throttle (1.0.39, the pilot: "make the centered location in step 2 be the centered location sent to the flight
     * controller"): the centre taken in the centre step goes out as 1500, like roll, pitch and yaw. Only when that
     * centre is well inside the range - a calibration from before 1.0.39 took the throttle's centre at the bottom, and
     * mapping that to 1500 would idle the motors at half throttle - else min -> 1000, max -> 2000 as before.
     */
    public int toThrottle(float v) {
        float span = max - min;
        if (span < MIN_SPAN) return toLinear(v);
        float at = (center - min) / span;
        return at >= 0.25f && at <= 0.75f ? toCentered(v) : toLinear(v);
    }

    /** Switches: min -> 1000, max -> 2000. */
    public int toLinear(float v) {
        float span = max - min;
        if (span < MIN_SPAN) return RcMapper.axisToChannel(v);
        return clamp(Math.round(MIN_CHANNEL_LEVEL + (v - min) / span * (MAX_CHANNEL_LEVEL - MIN_CHANNEL_LEVEL)));
    }

    private static int clamp(int value) {
        return Math.max(MIN_CHANNEL_LEVEL, Math.min(MAX_CHANNEL_LEVEL, value));
    }
}
