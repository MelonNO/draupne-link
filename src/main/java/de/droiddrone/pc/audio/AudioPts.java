package de.droiddrone.pc.audio;

/**
 * Timestamps for the sound in a recording. The frames are counted (each plays SAMPLES_PER_FRAME samples), not stamped
 * with their arrival: over 4G they arrive bunched, and arrival times would squeeze and stretch the sound. The count
 * starts at a frame's arrival (the video's clock) and starts there again when the frames have fallen RESYNC_MS behind
 * it - a pause (the drone armed, the sound muted) or frames lost. Frames that come early (a bunch after a stall) keep
 * their count. Strictly rising, as muxers want.
 * <p>
 * 1.0.49: the count is also kept from running ahead of the video's clock. A phone whose sound clock runs a little
 * fast sends more frames than the PC's clock allows for, and the count crept ahead of the pictures - seconds over
 * hours. When every frame of a window of WINDOW frames was counted more than AHEAD_MS ahead of its arrival (the
 * latest of a bunch arrives at its place or later, so jitter and bunches never do that), one frame is left out and
 * the count stays: back towards the video's clock a frame (21 ms) at a time.
 */
public final class AudioPts {
    static final double RESYNC_MS = 250;
    /** About 2 s of sound at 48 kHz. */
    static final int WINDOW = 100;
    static final double AHEAD_MS = 60;
    private final double frameMs;
    private double next = Double.NaN;
    private long last = -1;
    /** The least lead of the count over the arrivals in this window, and how many frames it has had. */
    private double leastLead = Double.MAX_VALUE;
    private int inWindow;

    public AudioPts(double frameMs) {
        this.frameMs = frameMs;
    }

    /**
     * @param arrivedMs the frame's arrival on the file's clock (ms since its first key frame)
     * @return its timestamp; -1: left out, the sound was running ahead of the video
     */
    public long next(long arrivedMs) {
        if (Double.isNaN(next) || arrivedMs - next > RESYNC_MS) {
            next = arrivedMs;
            leastLead = Double.MAX_VALUE;
            inWindow = 0;
        }
        leastLead = Math.min(leastLead, next - arrivedMs);
        if (++inWindow >= WINDOW) {
            boolean ahead = leastLead > AHEAD_MS;
            leastLead = Double.MAX_VALUE;
            inWindow = 0;
            if (ahead) return -1;
        }
        long pts = Math.max(Math.round(next), last + 1);
        last = pts;
        next += frameMs;
        return pts;
    }
}
