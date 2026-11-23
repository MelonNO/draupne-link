package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static de.droiddrone.common.ArduLinkLossRule.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ArduLinkLossRule against what a real ArduPilot does meanwhile: refuses a mode, loses a command, chooses its own LAND
 * or RTL after the brake; and the link lost exactly at the rule's boundaries. A made-up monotonic clock.
 */
class ArduLinkLossEdgeTest {
    static final long T = 5_000_000;
    static final int STABILIZE = 0;

    /** ArduPilot's battery failsafe lands it 10 s into the loss: the rule's Smart RTL at 20 s must not override the LAND. */
    @Test
    void aLandArduPilotChoseAfterTheBrakeIsNeverOverridden() {
        for (int own : new int[]{MODE_LAND, MODE_RTL, MODE_AUTO_RTL, MODE_SMART_RTL}) {
            ArduLinkLossRule r = new ArduLinkLossRule();
            assertEquals(BRAKE, r.tick(T + 1000, T, true, STABILIZE, true, false));
            assertEquals(NONE, r.tick(T + 3000, T, true, MODE_LOITER, true, false));
            assertEquals(NONE, r.tick(T + 10_000, T, true, own, true, false), "ArduPilot's own " + own);
            assertEquals(NONE, r.tick(T + 20_000, T, true, own, true, false), "no Smart RTL over ArduPilot's own " + own);
            assertEquals(NONE, r.tick(T + 25_000, T, true, own, false, false), "nor ALT_HOLD on a lost GNSS");
            assertEquals(NONE, r.tick(T + 40_000, T, true, own, true, true), "nor RTL as a lead");
            assertTrue(r.running(), "the held sticks go on");
        }
    }

    @Test
    void aLostOrRefusedModeIsAskedAgainThenGivesWay() {
        ArduLinkLossRule r = new ArduLinkLossRule();
        assertEquals(BRAKE, r.tick(T + 1000, T, true, STABILIZE, true, false));
        assertEquals(NONE, r.tick(T + 1000 + RETRY_MS - 1, T, true, STABILIZE, true, false), "the heartbeat needs its time");
        assertEquals(BRAKE, r.tick(T + 1000 + RETRY_MS, T, true, STABILIZE, true, false), "asked again");
        assertEquals(BRAKE, r.tick(T + 1000 + 2 * RETRY_MS, T, true, STABILIZE, true, false), "a third time");
        assertEquals(HOLD_HEIGHT, r.tick(T + 1000 + 3 * RETRY_MS, T, true, STABILIZE, true, false), "LOITER refused: ALT_HOLD");
        assertEquals(NONE, r.tick(T + 1000 + 3 * RETRY_MS + 200, T, true, MODE_ALT_HOLD, true, false));
        assertEquals(NONE, r.tick(T + 10_000, T, true, MODE_ALT_HOLD, true, false), "no LOITER again");
        // Smart RTL refused (no path): RTL
        assertEquals(SMART_RTL, r.tick(T + 20_000, T, true, MODE_ALT_HOLD, true, false));
        assertEquals(SMART_RTL, r.tick(T + 20_000 + RETRY_MS, T, true, MODE_ALT_HOLD, true, false));
        assertEquals(SMART_RTL, r.tick(T + 20_000 + 2 * RETRY_MS, T, true, MODE_ALT_HOLD, true, false));
        assertEquals(RTL, r.tick(T + 20_000 + 3 * RETRY_MS, T, true, MODE_ALT_HOLD, true, false), "no Smart RTL path: RTL");
        assertEquals(NONE, r.tick(T + 30_000, T, true, MODE_RTL, true, false));
        assertEquals(NONE, r.tick(T + 31_000, T, true, MODE_RTL, false, false), "on its way home: left to ArduPilot");
    }

    @Test
    void aModeTakenIsNotAskedAgainAndAnUnknownModeIsNotCompared() {
        ArduLinkLossRule r = new ArduLinkLossRule();
        assertEquals(BRAKE, r.tick(T + 1000, T, true, STABILIZE, true, false));
        for (long t = T + 1100; t < T + 19_000; t += 100) assertEquals(NONE, r.tick(t, T, true, MODE_LOITER, true, false));
        ArduLinkLossRule u = new ArduLinkLossRule();
        assertEquals(BRAKE, u.tick(T + 1000, T, true, -1, true, false));
        for (long t = T + 1100; t < T + 19_000; t += 100) assertEquals(NONE, u.tick(t, T, true, -1, true, false));
    }

    @Test
    void theBoundariesToTheMillisecond() {
        ArduLinkLossRule r = new ArduLinkLossRule();
        assertEquals(NONE, r.tick(T + BRAKE_AFTER_MS - 1, T, true, STABILIZE, true, false));
        assertEquals(BRAKE, r.tick(T + BRAKE_AFTER_MS, T, true, STABILIZE, true, false));
        assertEquals(NONE, r.tick(T + HOME_AFTER_MS - 1, T, true, MODE_LOITER, true, false));
        assertEquals(SMART_RTL, r.tick(T + HOME_AFTER_MS, T, true, MODE_LOITER, true, false));
        // heard again exactly then: lets go
        assertEquals(NONE, r.tick(T + HOME_AFTER_MS + 1, T + HOME_AFTER_MS + 1, true, MODE_SMART_RTL, true, false));
        assertFalse(r.running());
        // a packet stamped after now (the caller's clock must be monotonic; a jump back never starts the rule)
        assertEquals(NONE, r.tick(T, T + 60_000, true, STABILIZE, true, false));
        assertFalse(r.running());
    }

    @Test
    void disarmedOrHeartbeatLostMidPlanStartsOverFromWhereTheSilenceIs() {
        ArduLinkLossRule r = new ArduLinkLossRule();
        assertEquals(BRAKE, r.tick(T + 1000, T, true, STABILIZE, true, false));
        assertEquals(NONE, r.tick(T + 5000, T, false, MODE_LOITER, true, false), "no fresh armed word: the rule lets go");
        assertFalse(r.running());
        // the armed word back at 25 s of silence: straight to the return
        assertEquals(SMART_RTL, r.tick(T + 25_000, T, true, MODE_LOITER, true, false));
    }

    /** A lead named while the rule runs (a LEAD order to a drone in link loss): its return is plain RTL. */
    @Test
    void madeLeadDuringTheLoss() {
        ArduLinkLossRule r = new ArduLinkLossRule();
        assertEquals(BRAKE, r.tick(T + 1000, T, true, STABILIZE, true, false));
        assertEquals(RTL, r.tick(T + 20_000, T, true, MODE_LOITER, true, true));
        // made lead after its Smart RTL began: the return under way is left alone
        ArduLinkLossRule s = new ArduLinkLossRule();
        s.tick(T + 1000, T, true, STABILIZE, true, false);
        assertEquals(SMART_RTL, s.tick(T + 20_000, T, true, MODE_LOITER, true, false));
        assertEquals(NONE, s.tick(T + 21_000, T, true, MODE_SMART_RTL, true, true));
    }
}
