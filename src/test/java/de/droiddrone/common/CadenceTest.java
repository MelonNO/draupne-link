package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The phone's MSP poll on the clock (Msp.mspRun): the status and the rest at their rates, whatever the loop does. */
class CadenceTest {
    /** How often a cadence fires over seconds of a loop ticking every tickMs plus workMs. */
    private static int fires(Cadence c, long seconds, long tickMs, long workMs, long periodMs) {
        int n = 0;
        for (long t = 0; t < seconds * 1000; t += tickMs + workMs) {
            if (c.due(t, periodMs, tickMs / 2)) n++;
        }
        return n;
    }

    @Test
    void theUsualRateIsKept() {
        // 10 Hz: the status every 5 ticks (2 a second), the rest every 10 (1 a second)
        assertEquals(20, fires(new Cadence(), 10, 100, 0, 500));
        assertEquals(10, fires(new Cadence(), 10, 100, 0, 1000));
    }

    @Test
    void aSlowLoopDoesNotSlowThePoll() {
        // each tick 30 ms late (a busy phone): a count of 5 ticks came every 650 ms, the clock still says 500
        int n = fires(new Cadence(), 60, 100, 30, 500);
        assertTrue(n >= 115 && n <= 121, "about 2 a second: " + n);
    }

    @Test
    void standbyPollsTheStatusEverySecondAndTheRestEveryOther() {
        Cadence status = new Cadence(), rest = new Cadence();
        int s = 0, r = 0;
        for (long t = 0; t < 20_000; t += 1000 + 3) {
            if (status.due(t, 1000, 500)) s++;
            if (rest.due(t, 2000, 500)) r++;
        }
        assertEquals(20, s, "every tick");
        assertEquals(10, r, "every other");
    }

    @Test
    void goingToStandbyMidCountStillPollsTheStatus() {
        // 1.0.53's bug: standby began on a count of 3, the count went 8, 13, 18 ... and the status never came again
        Cadence status = new Cadence();
        long t = 0;
        for (int i = 0; i < 3; i++, t += 100) status.due(t, 500, 50);
        int s = 0;
        for (int i = 0; i < 10; i++, t += 1000) if (status.due(t, 1000, 500)) s++;
        assertEquals(10, s);
    }

    @Test
    void aStallFiresOnceNotTheMissedOnesInARow() {
        Cadence c = new Cadence();
        assertTrue(c.due(0, 500, 50), "due at the first call");
        assertFalse(c.due(100, 500, 50));
        assertTrue(c.due(5000, 500, 50), "after a stall");
        assertFalse(c.due(5100, 500, 50), "no backlog");
        assertTrue(c.due(5500, 500, 50));
    }

    @Test
    void halfATickEarlyCounts() {
        Cadence c = new Cadence();
        c.due(0, 500, 50);
        assertFalse(c.due(449, 500, 50));
        assertTrue(c.due(450, 500, 50));
        assertTrue(c.due(950, 500, 50), "the phase is kept: due at 1000");
    }

    @Test
    void aRestartIsDueAtOnce() {
        Cadence c = new Cadence();
        c.due(0, 1000, 0);
        c.restart();
        assertTrue(c.due(10, 1000, 0));
    }
}
