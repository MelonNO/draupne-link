package de.droiddrone.pc.gui;

/**
 * I-011: the link's last 30 s for the OSD's LINK HISTORY strip - the ping, and the video frames lost or damaged, as the
 * link log reads them at its tick (MainWindow.sampleLinkLog, 10 a second): nothing measured anew, nothing on the
 * stick path. A ring of fixed arrays: a sample writes a few numbers, a paint reads them; neither allocates.
 * <p>
 * Written on the Swing thread. A paint elsewhere (the OSD copy of a recording, a STANAG stream's OSD) reads the
 * samples published before its {@link #written()}; the slot the next sample goes to is never read.
 */
final class LinkHistory {
    /** What the strip shows. */
    static final long WINDOW_MS = 30_000;
    /** 30 s of the link log's 100 ms tick, and room for ticks that come early. */
    static final int SIZE = 320;

    /** A sample's mark under the line: nothing, frames damaged, frames lost, the link down (no ping then). */
    static final byte NONE = 0, DAMAGED = 1, LOST = 2, DOWN = 3;

    private final long[] at = new long[SIZE];
    private final int[] ping = new int[SIZE];
    private final int[] baseline = new int[SIZE];
    private final byte[] mark = new byte[SIZE];
    /** Samples written since the last clear; the newest is in slot (written - 1) % SIZE. */
    private volatile int written;
    /** The video's counts at the sample before; -1: none yet (a new pipeline counts from 0 again). */
    private long lastDamaged = -1, lastLost = -1;

    /** A new connection: nothing of the one before. */
    void clear() {
        lastDamaged = -1;
        lastLost = -1;
        written = 0;
    }

    /**
     * One sample.
     * @param now        MonoClock ms
     * @param pingMs     the ping now (ControlLink.getPing), -1 without a fresh one
     * @param baselineMs the link's usual ping then (VideoPipeline.Stats.pingBaselineMs), -1 unknown: the line's colour
     * @param damaged    the video's damaged frames so far, -1 without video
     * @param lost       the video's lost frames so far, -1 without video
     * @param down       the link is not up, or stalled: no ping, a red mark
     */
    void add(long now, int pingMs, int baselineMs, long damaged, long lost, boolean down) {
        int n = written, i = n % SIZE;
        boolean lostNow = lost >= 0 && lastLost >= 0 && lost > lastLost;
        boolean damagedNow = damaged >= 0 && lastDamaged >= 0 && damaged > lastDamaged;
        at[i] = now;
        ping[i] = down ? -1 : pingMs;
        baseline[i] = baselineMs;
        mark[i] = down ? DOWN : lostNow ? LOST : damagedNow ? DAMAGED : NONE;
        lastDamaged = damaged;
        lastLost = lost;
        written = n + 1; // published last: a reader that sees n + 1 sees the slot whole
    }

    /** The samples written so far: read once by a paint and handed to the others, so they all see the same samples. */
    int written() {
        return written;
    }

    /** How many of them are kept: all but the slot the next sample overwrites. */
    static int kept(int written) {
        return Math.min(written, SIZE - 1);
    }

    /** How many of the newest samples are within the window before now (k = 0 the newest). */
    int within(int written, long now) {
        int n = kept(written), k = 0;
        while (k < n && now - at(written, k) <= WINDOW_MS) k++;
        return k;
    }

    long at(int written, int k) {
        return at[slot(written, k)];
    }

    int ping(int written, int k) {
        return ping[slot(written, k)];
    }

    int baseline(int written, int k) {
        return baseline[slot(written, k)];
    }

    byte mark(int written, int k) {
        return mark[slot(written, k)];
    }

    private static int slot(int written, int k) {
        return Math.floorMod(written - 1 - k, SIZE);
    }
}
