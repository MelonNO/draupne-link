package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The spectator relay's pause, against the pilot PC's ping as the 2026-11-05 link log had it. */
class RelayGateTest {
    private long now;

    /** Feeds this ping every 100 ms for ms; @return the changes seen, PAUSED / RESUMED counted. */
    private int[] feed(RelayGate g, int ping, long ms, boolean watched) {
        int[] seen = new int[2];
        for (long end = now + ms; now < end; now += 100) {
            RelayGate.Change c = g.update(now, ping, watched);
            if (c == RelayGate.Change.PAUSED) seen[0]++;
            if (c == RelayGate.Change.RESUMED) seen[1]++;
        }
        return seen;
    }

    @Test
    void theStreamStaysPausedWhileThePingStaysHigh() {
        // the log: 50 ms, then a spectator at 8 Mbit/s and 300 - 700 ms for twenty seconds
        RelayGate g = new RelayGate();
        feed(g, 50, 20_000, true);
        assertFalse(g.paused());
        int[] seen = feed(g, 600, 1500, true);
        assertEquals(1, seen[0], "paused a second into the high ping");
        assertTrue(g.paused());
        seen = feed(g, 450, 20_000, true);
        assertEquals(0, seen[1], "never on again while the ping is high (before 1.0.41: on after 3 s, paused a second later)");
        assertTrue(g.paused());
        assertEquals(50, g.usual(), "twenty seconds of congestion is not the usual");
    }

    @Test
    void itGoesOnOnceThePingHasRecoveredAndAPauseSoonAfterWaitsLonger() {
        RelayGate g = new RelayGate();
        feed(g, 50, 10_000, true);
        feed(g, 500, 1200, true);
        assertTrue(g.paused());
        assertEquals(RelayGate.MIN_PAUSE_MS, g.minPauseMs());
        int[] seen = feed(g, 60, RelayGate.RECOVER_HOLD_MS - 200, true);
        assertEquals(0, seen[1], "not before the ping has been back for RECOVER_HOLD_MS");
        seen = feed(g, 60, 600, true);
        assertEquals(1, seen[1], "on again");
        // high again at once: paused again, and this time for at least twice as long
        feed(g, 500, 1200, true);
        assertTrue(g.paused());
        assertEquals(2 * RelayGate.MIN_PAUSE_MS, g.minPauseMs());
        seen = feed(g, 50, 2 * RelayGate.MIN_PAUSE_MS - 300, true);
        assertEquals(0, seen[1], "the doubled minimum holds even with the ping back");
        seen = feed(g, 50, 600, true);
        assertEquals(1, seen[1]);
        // a quiet half minute: the next pause is the short one again
        feed(g, 50, RelayGate.REPEAT_WINDOW_MS + 1000, true);
        feed(g, 500, 1200, true);
        assertEquals(RelayGate.MIN_PAUSE_MS, g.minPauseMs());
    }

    @Test
    void nobodyWatchingMeansNoPauseAndAnUnknownPingChangesNothing() {
        RelayGate g = new RelayGate();
        feed(g, 50, 5000, false);
        assertArrayEquals(new int[]{0, 0}, feed(g, 600, 5000, false), "no spectator: nothing to pause");
        feed(g, 50, 5000, true);
        feed(g, 600, 1200, true);
        assertTrue(g.paused());
        assertArrayEquals(new int[]{0, 0}, feed(g, -1, 10_000, true), "no ping known: neither on nor paused again");
        assertEquals(1, feed(g, 600, 200, false)[1], "the spectator gone: not paused any more");
    }
}
