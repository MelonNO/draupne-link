package de.droiddrone.pc.overwatch;

/**
 * An overwatch clip as one video (2026-11-10, the pilot: "Make the entire clip including the pictures before detection
 * into one continuous video ... time elapsed and time of entire clip including preview pictures"): the pictures from
 * before the alert first, {@link #PRE_MS} each, then the video's parts one after the other. Where a moment of it is,
 * and how long it all is.
 */
public final class ClipTimeline {
    /** Each picture from before the alert shows this long. */
    public static final long PRE_MS = 500;

    private final int pictures;
    private final long[] partStart;

    /** @param pictures the pictures before the alert; partMs each part's length (ms) */
    public ClipTimeline(int pictures, long[] partMs) {
        this.pictures = Math.max(0, pictures);
        partStart = new long[partMs.length + 1];
        partStart[0] = this.pictures * PRE_MS;
        for (int i = 0; i < partMs.length; i++) partStart[i + 1] = partStart[i] + Math.max(0, partMs[i]);
    }

    /** All of it: the pictures and the parts. */
    public long totalMs() {
        return partStart[partStart.length - 1];
    }

    /** Where the video begins (the pictures before it end). */
    public long videoStartMs() {
        return partStart[0];
    }

    public int parts() {
        return partStart.length - 1;
    }

    /** Where part k begins on the one timeline (k = parts(): the end). */
    public long partStartMs(int k) {
        return partStart[Math.max(0, Math.min(partStart.length - 1, k))];
    }

    /** A moment: one of the pictures (picture >= 0) or a part and how far into it (part >= 0); end past it all. */
    public record At(int picture, int part, long offsetMs, boolean end) {}

    /** What shows at ms (clamped to the start). */
    public At at(long ms) {
        long t = Math.max(0, ms);
        if (t >= totalMs()) return new At(-1, -1, 0, true);
        if (t < partStart[0]) return new At((int) Math.min(pictures - 1, t / PRE_MS), -1, t % PRE_MS, false);
        int k = 0;
        while (k < parts() - 1 && t >= partStart[k + 1]) k++;
        return new At(-1, k, t - partStart[k], false);
    }
}
