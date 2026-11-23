package de.droiddrone.testing;

import javax.swing.SwingUtilities;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The tests' waiting for something another thread does: looked at again and again until it is so, the test failed
 * when the time is up. One copy for all tests (each had its own before; TestHygieneTest keeps it that way). The
 * condition is always looked at once more after the time is up, so a slow machine's last moment still counts.
 */
public final class Waits {
    /** Between two looks. Two steps, because the tests' own copies had two: each test keeps the pace it was written with. */
    private static final long STEP_MS = 20, FINE_STEP_MS = 10;

    private Waits() {
    }

    /** Waits until it is so, looking every 20 ms; the test fails when it isn't after {@code ms}. */
    public static void waitFor(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        if (!until(ok, ms, STEP_MS)) fail("Timed out after " + ms + " ms waiting for " + what);
    }

    /** The same, the failure followed by the test's log: what the drones and links said until then. */
    public static void waitFor(BooleanSupplier ok, long ms, String what, Iterable<String> log) throws InterruptedException {
        if (!until(ok, ms, STEP_MS)) fail("Timed out after " + ms + " ms waiting for " + what + "\n" + String.join("\n", log));
    }

    /** As {@link #waitFor}, looking every 10 ms. */
    public static void await(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        if (!until(ok, ms, FINE_STEP_MS)) fail("Timed out after " + ms + " ms waiting for " + what);
    }

    /** No failure of its own: true when it was so within {@code ms}, looking every 10 ms. */
    public static boolean until(BooleanSupplier ok, long ms) throws InterruptedException {
        return until(ok, ms, FINE_STEP_MS);
    }

    /** As {@link #waitFor}, the condition read on the Swing thread: for what only that thread may touch. */
    public static void waitForOnSwing(BooleanSupplier ok, long ms, String what) throws Exception {
        long end = System.currentTimeMillis() + ms;
        boolean[] r = new boolean[1];
        while (true) {
            SwingUtilities.invokeAndWait(() -> r[0] = ok.getAsBoolean());
            if (r[0]) return;
            if (System.currentTimeMillis() > end) fail("Timed out after " + ms + " ms waiting for " + what);
            Thread.sleep(STEP_MS);
        }
    }

    /**
     * No failure of its own: true when it was so within {@code ms}, looking every {@code stepMs} - for a condition that
     * is dear to look at (a window's texts on the Swing thread, Betaflight's answers): each test keeps its pace.
     */
    public static boolean until(BooleanSupplier ok, long ms, long stepMs) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) return false;
            Thread.sleep(stepMs);
        }
        return true;
    }
}
