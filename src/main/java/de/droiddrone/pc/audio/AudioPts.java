package de.droiddrone.pc.audio;

/**
 * Timestamps for the sound in a recording. The frames are counted (each plays SAMPLES_PER_FRAME samples), not stamped
 * with their arrival: over 4G they arrive bunched, and arrival times would squeeze and stretch the sound. The count
 * starts at a frame's arrival (the video's clock) and starts there again when the frames have fallen RESYNC_MS behind
 * it - a pause (the drone armed, the sound muted) or frames lost. Frames that come early (a bunch after a stall) keep
 * their count. Strictly rising, as muxers want.
 */
public final class AudioPts {
    static final double RESYNC_MS = 250;
    private final double frameMs;
    private double next = Double.NaN;
    private long last = -1;

    public AudioPts(double frameMs) {
        this.frameMs = frameMs;
    }

    /** @param arrivedMs the frame's arrival on the file's clock (ms since its first key frame) */
    public long next(long arrivedMs) {
        if (Double.isNaN(next) || arrivedMs - next > RESYNC_MS) next = arrivedMs;
        long pts = Math.max(Math.round(next), last + 1);
        last = pts;
        next += frameMs;
        return pts;
    }
}
