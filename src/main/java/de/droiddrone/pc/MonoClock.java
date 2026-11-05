package de.droiddrone.pc;

/**
 * Milliseconds that only ever go forward: the wall clock at start plus System.nanoTime since. Timeouts and "how
 * fresh is this" checks on the link use it, so Windows setting its clock (a time sync after resume, a time zone
 * fix) can't make a dead link look alive or a stale "armed" look fresh. Close to System.currentTimeMillis until the
 * wall clock is changed, so log lines and old readers that print it stay readable.
 */
public final class MonoClock {
    private static final long START_WALL_MS = System.currentTimeMillis();
    private static final long START_NS = System.nanoTime();

    private MonoClock() {
    }

    public static long ms() {
        return START_WALL_MS + (System.nanoTime() - START_NS) / 1_000_000;
    }
}
