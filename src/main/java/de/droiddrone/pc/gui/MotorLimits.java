package de.droiddrone.pc.gui;

import de.droiddrone.pc.setup.MotorTest;

/**
 * What the motor test's sending thread may send, by its own clock (F-061). Every stop the window has - the 20 s
 * auto-stop, a click's 3 s, SPACE, STOP ALL, the untick, leaving the window - is a Swing event, and a window that
 * hangs brings none, while Betaflight keeps the last value it was sent. So the thread that sends asks here on every
 * round, and three limits hold with no Swing event at all: a click's motor stops after {@link #PULSE_MS}, every motor
 * after {@link #AUTO_STOP_MS} of running, and every motor once the Swing thread has been silent for
 * {@link #SWING_SILENT_MS} - then none of the pilot's own stops could be heard.
 * <p>
 * The Swing thread asks ({@link #ask}, {@link #pulse}, {@link #stop}) and says it is there ({@link #alive}); the motor
 * thread reads ({@link #allowed}). What the motor thread stopped stays stopped until the window has taken it
 * ({@link #takeCut}) and the pilot asks anew: clicks that queued up while the window hung start nothing.
 * Times are milliseconds of {@link #now()}, a clock that only runs forward.
 */
final class MotorLimits {
    /** Any motor running this long stops by itself. Enough to see each one turn. */
    static final long AUTO_STOP_MS = 20_000;
    /** A click on a motor in the picture: it runs this long. */
    static final long PULSE_MS = 3000;
    /**
     * The Swing thread silent this long while a motor runs: every motor stops. One second - the window says it is
     * there ten times a second ({@link #BEAT_MS}), so ten in a row are missed before this trips, more than a garbage
     * collection or a slow repaint takes; and five times sooner than AppLog's freeze watch (5 s) calls the window
     * frozen, because here a motor turns meanwhile. A stop too many costs the pilot one more move of a slider.
     */
    static final long SWING_SILENT_MS = 1000;
    /** How often the window says it is there. */
    static final int BEAT_MS = 100;

    /** Why the motor thread stopped the motors by itself. */
    enum Cut { AUTO_STOP, WINDOW_SILENT }

    final long autoStopMs, pulseMs, silentMs;
    private static final int[] NONE = new int[0];
    private int[] asked = NONE;
    /** Per motor: when its click's run ends (0: not a click's), and the value the click gave it. */
    private final long[] pulseEnd = new long[MotorTest.MAX_MOTORS];
    private final int[] pulseValue = new int[MotorTest.MAX_MOTORS];
    /** A motor runs, and since when without a break (the clock's numbers may be negative: no number stands for "none"). */
    private boolean runs;
    private long runningSince;
    private long swingSeen;
    private Cut cut;

    MotorLimits(long now) {
        this(AUTO_STOP_MS, PULSE_MS, SWING_SILENT_MS, now);
    }

    /** Tests: shorter limits, so none waits 20 s. */
    MotorLimits(long autoStopMs, long pulseMs, long silentMs, long now) {
        this.autoStopMs = autoStopMs;
        this.pulseMs = pulseMs;
        this.silentMs = silentMs;
        swingSeen = now;
    }

    /** Milliseconds that only run forward (the wall clock can be set back, and a limit with it). */
    static long now() {
        return System.nanoTime() / 1_000_000;
    }

    /** The Swing thread: it is there. */
    synchronized void alive(long now) {
        swingSeen = now;
    }

    /** The Swing thread: what the sliders ask for (motor 1 first; missing ones stop). */
    synchronized void ask(int[] values, long now) {
        swingSeen = now;
        if (cut != null) return; // stopped by the motor thread: nothing starts again before the window has taken that
        for (int i = 0; i < pulseEnd.length; i++) {
            int v = i < values.length ? values[i] : MotorTest.STOP;
            if (pulseEnd[i] != 0 && v != pulseValue[i]) pulseEnd[i] = 0; // the pilot moved it: no longer the click's
        }
        asked = values.clone();
    }

    /** The Swing thread: this motor's value, as just asked, is a click's - it ends by itself after pulseMs. */
    synchronized void pulse(int motor, long now) {
        swingSeen = now;
        int i = motor - 1;
        if (cut != null || i < 0 || i >= asked.length || i >= pulseEnd.length) return;
        pulseValue[i] = asked[i];
        pulseEnd[i] = now + pulseMs == 0 ? 1 : now + pulseMs;
    }

    /** Everything to STOP (either thread). */
    synchronized void stop() {
        asked = NONE;
        java.util.Arrays.fill(pulseEnd, 0);
        runs = false;
    }

    /**
     * The motor thread, every round: what to send now. A click's motor past its time reads STOP; a limit reached
     * stops them all and stays so until {@link #takeCut}.
     */
    synchronized int[] allowed(long now) {
        if (cut != null) return NONE;
        int[] out = asked.clone();
        boolean running = false;
        for (int i = 0; i < out.length; i++) {
            if (i < pulseEnd.length && pulseEnd[i] != 0 && now - pulseEnd[i] >= 0) out[i] = MotorTest.STOP;
            running |= out[i] > MotorTest.STOP;
        }
        if (!running) {
            runs = false;
            return out;
        }
        if (!runs) {
            runs = true;
            runningSince = now;
        }
        if (now - swingSeen >= silentMs) return cut(Cut.WINDOW_SILENT);
        if (now - runningSince >= autoStopMs) return cut(Cut.AUTO_STOP);
        return out;
    }

    private int[] cut(Cut why) {
        stop();
        cut = why;
        return NONE;
    }

    /** The Swing thread: why the motor thread stopped the motors since the last look, or null. Asking is open again. */
    synchronized Cut takeCut() {
        Cut c = cut;
        cut = null;
        return c;
    }
}
