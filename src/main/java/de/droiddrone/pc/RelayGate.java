package de.droiddrone.pc;

/**
 * When the spectators' stream may run on the pilot PC (see {@link SpectatorHost}). Latency first: relaying the video
 * goes out through the pilot PC's own upload, and on a weak one the pilot's ping to the drone - and so the sticks -
 * suffers (2026-11-05: 50 ms alone, 300-700 ms within 7 s of a spectator at 8 Mbit/s).
 *
 * The stream pauses when the ping has been more than {@link #CONGESTION_MS} over the usual for
 * {@link #CONGESTION_HOLD_MS}, and goes on only once it has been back near the usual (within {@link #RECOVERED_MS}) for
 * {@link #RECOVER_HOLD_MS} and at least the pause's minimum has passed. Before 1.0.41 it went on after 3 s whatever the
 * ping was, and paused again a second later: the spectators got 1 s of picture in every 4. A pause soon after the last
 * one doubles the minimum (3 s up to 30 s). The usual is the lowest ping over the last minute, so a long spell of
 * congestion doesn't become the usual (it was the last 10 s: "usually 406 ms").
 */
final class RelayGate {
    static final int CONGESTION_MS = 80;
    static final long CONGESTION_HOLD_MS = 1000;
    static final int RECOVERED_MS = 40;
    static final long RECOVER_HOLD_MS = 3000;
    static final long MIN_PAUSE_MS = 3000;
    static final long MAX_PAUSE_MS = 30_000;
    /** A pause starting this soon after the last one ended doubles the minimum. */
    static final long REPEAT_WINDOW_MS = 30_000;
    /** The usual: the lowest ping per slot, over SLOTS slots of SLOT_MS. */
    static final long SLOT_MS = 5000;
    static final int SLOTS = 12;

    /** What update did. */
    enum Change { NONE, PAUSED, RESUMED }

    private final long[] lowest = new long[SLOTS];
    private int slot;
    private long slotStart = -1;
    private boolean paused;
    private long highSince;
    private long lowSince;
    private long pausedAt;
    private long lastResume = Long.MIN_VALUE / 2;
    private long minPauseMs = MIN_PAUSE_MS;
    private int lastPing = -1;

    RelayGate() {
        java.util.Arrays.fill(lowest, Long.MAX_VALUE);
    }

    /**
     * @param pingMs  the pilot's ping to the drone now, -1 if not known
     * @param watched someone watches (no spectator: never paused)
     */
    Change update(long now, int pingMs, boolean watched) {
        lastPing = pingMs;
        if (slotStart < 0) slotStart = now;
        while (now - slotStart >= SLOT_MS) {
            slot = (slot + 1) % SLOTS;
            lowest[slot] = Long.MAX_VALUE;
            slotStart += SLOT_MS;
        }
        if (pingMs >= 0) lowest[slot] = Math.min(lowest[slot], pingMs);
        long usual = usual();
        boolean known = pingMs >= 0 && usual != Long.MAX_VALUE;
        boolean high = known && pingMs > usual + CONGESTION_MS;
        boolean low = known && pingMs <= usual + RECOVERED_MS;
        highSince = high ? (highSince == 0 ? now : highSince) : 0;
        lowSince = low ? (lowSince == 0 ? now : lowSince) : 0;
        if (!watched) {
            boolean was = paused;
            paused = false;
            return was ? Change.RESUMED : Change.NONE;
        }
        if (!paused && highSince != 0 && now - highSince >= CONGESTION_HOLD_MS) {
            minPauseMs = now - lastResume < REPEAT_WINDOW_MS ? Math.min(MAX_PAUSE_MS, minPauseMs * 2) : MIN_PAUSE_MS;
            paused = true;
            pausedAt = now;
            return Change.PAUSED;
        }
        if (paused && now - pausedAt >= minPauseMs && lowSince != 0 && now - lowSince >= RECOVER_HOLD_MS) {
            paused = false;
            lastResume = now;
            highSince = 0;
            return Change.RESUMED;
        }
        return Change.NONE;
    }

    boolean paused() {
        return paused;
    }

    /** The lowest ping of the last minute, or Long.MAX_VALUE before the first. */
    long usual() {
        long u = Long.MAX_VALUE;
        for (long l : lowest) u = Math.min(u, l);
        return u;
    }

    /** The current (or last) pause's minimum. */
    long minPauseMs() {
        return minPauseMs;
    }

    int lastPing() {
        return lastPing;
    }
}
