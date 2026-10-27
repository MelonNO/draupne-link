package de.droiddrone.pc;

/**
 * Fixed-rate loop timing that holds its rate on Windows. The default Windows timer tick is 15.6 ms, and HotSpot only
 * switches to the 1 ms high-resolution timer for sleeps that are not a multiple of 10 ms: measured on this project's
 * PC, sleep(10) and parkNanos(4 ms) both took ~15.6 ms, while sleep(1) took ~2 ms. So we wait in 1 ms steps towards
 * an absolute deadline (max ~1-2 ms late, no CPU spent spinning).
 */
public final class Pacer {
    private final long periodNs;
    private long next;

    public Pacer(int hz) {
        periodNs = 1_000_000_000L / hz;
        next = System.nanoTime();
    }

    /** Waits until the next tick. If the loop fell more than a period behind, it resynchronises instead of bursting. */
    public void await() throws InterruptedException {
        next += periodNs;
        long now = System.nanoTime();
        if (now - next > periodNs) {
            next = now;
            return;
        }
        while (next - System.nanoTime() > 500_000) Thread.sleep(1);
    }
}
