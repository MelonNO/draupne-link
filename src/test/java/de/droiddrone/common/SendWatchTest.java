package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** When the phone opens its link socket anew because nothing leaves it (pc.55). */
class SendWatchTest {
    /** Looks twice a second from t0 for that long, the sends standing still since stuckSince; when it said "reopen" first, or -1. */
    private static long firstReopen(SendWatch w, long t0, long forMs, long stuckSince, long heardAgoMs) {
        for (long t = t0; t <= t0 + forMs; t += 500) if (w.tick(t, t - stuckSince, heardAgoMs, false)) return t;
        return -1;
    }

    @Test
    void sendsThatGoOutNeverOpenItAnew() {
        SendWatch w = new SendWatch();
        for (long t = 0; t < 600_000; t += 500) assertFalse(w.tick(t, t % 3000 == 0 ? 400 : 0, 50, false));
        assertEquals(SendWatch.STUCK_MS, w.waitMs());
    }

    @Test
    void fiveSecondsOfNothingOutWhileThePcIsHeardOpensItAnew() {
        SendWatch w = new SendWatch();
        assertEquals(5000, firstReopen(w, 0, 20_000, 0, 20), "at five seconds, not before (a weak uplink may stand for seconds) and not later");
    }

    @Test
    void withNothingHeardEitherItIsTheNetworkAndTheSocketStays() {
        SendWatch w = new SendWatch();
        assertEquals(-1, firstReopen(w, 0, 120_000, 0, 5000), "no coverage at all for two minutes: waited out");
    }

    /**
     * The review's catch: after a hole in the coverage the first packet heard made "stood still for long, and heard" true
     * at once - a reopen in the very moment the link came back, though the way up would have moved a moment later.
     */
    @Test
    void whenTheCoverageReturnsTheWayUpGetsThreeSecondsBeforeTheSocketIsTakenForTheTrouble() {
        SendWatch w = new SendWatch();
        assertEquals(-1, firstReopen(w, 0, 60_000, 0, 5000));
        // heard again from 60.5 s on, the sends still standing: not at the first packet, three seconds after it
        assertEquals(63_500, firstReopen(w, 60_500, 20_000, 0, 100));

        // the same hole, but the way up moves a second after the way down: nothing is opened anew
        SendWatch moved = new SendWatch();
        assertEquals(-1, firstReopen(moved, 0, 60_000, 0, 5000));
        assertFalse(moved.tick(60_500, 60_500, 100, false));
        assertFalse(moved.tick(61_000, 61_000, 100, false));
        for (long t = 61_500; t < 120_000; t += 500) assertFalse(moved.tick(t, 0, 100, false), "the sends go out again: " + t);
    }

    @Test
    void aPacketNowAndThenIsNotHeardAllAlong() {
        // sticks through a bad downlink: one packet every four seconds - never "the PC is heard" for three seconds on end
        SendWatch w = new SendWatch();
        for (long t = 0; t <= 120_000; t += 500) assertFalse(w.tick(t, t, t % 4000 < 1000 ? 100 : 2500, false), "at " + t);
    }

    @Test
    void aReopenThatDidNotHelpIsNotRepeatedForHalfAMinute() {
        // the way up alone is gone for two minutes: the new socket fills in a moment and stands still like the old one
        SendWatch w = new SendWatch();
        int reopens = 0;
        long stuckSince = 0;
        for (long t = 500; t <= 120_000; t += 500) {
            if (w.tick(t, t - stuckSince, 100, false)) {
                reopens++;
                stuckSince = t; // the count starts again after a reopen
            }
        }
        // at 5 s, then every 30 s: 35, 65, 95 s
        assertEquals(4, reopens, "one more socket's worth of old video per half minute at most");
        assertEquals(SendWatch.AGAIN_MS, w.waitMs());
    }

    @Test
    void aSocketThatWasTheTroubleFlowsAgainAndTheWaitIsFiveSecondsAgain() {
        SendWatch w = new SendWatch();
        assertEquals(5000, firstReopen(w, 0, 10_000, 0, 100));
        assertEquals(SendWatch.AGAIN_MS, w.waitMs());
        for (long t = 5500; t < 14_000; t += 500) w.tick(t, 0, 100, false);
        assertEquals(SendWatch.AGAIN_MS, w.waitMs(), "not yet: flowing for 8.5 s");
        // a short hiccup starts the calm time anew
        w.tick(14_000, 800, 100, false);
        for (long t = 14_500; t <= 24_000; t += 500) w.tick(t, 0, 100, false);
        assertEquals(SendWatch.AGAIN_MS, w.waitMs(), "flowing for 9.5 s since the hiccup");
        w.tick(24_500, 0, 100, false);
        assertEquals(SendWatch.STUCK_MS, w.waitMs());
        // the emulator's kind: full again a minute later - five seconds again, not thirty
        assertEquals(85_000, firstReopen(w, 80_000, 20_000, 80_000, 100));
    }

    @Test
    void aSocketFoundClosedIsOpenedAtOnceThenTriedOnceASecond() {
        SendWatch w = new SendWatch();
        assertTrue(w.tick(0, 0, 100_000, true), "nothing to wait for: there is no socket");
        assertFalse(w.tick(500, 0, 100_000, true));
        assertTrue(w.tick(1000, 0, 100_000, true));
        assertFalse(w.tick(1500, 0, 100_000, false), "open again: nothing more to do");
        assertTrue(w.tick(1600, 0, 100_000, true), "closed again later: at once again");
    }
}
