package de.droiddrone.pc.launch;

import de.droiddrone.common.ArduLinkLossRule;
import de.droiddrone.common.launch.LaunchSequence;
import org.junit.jupiter.api.Test;

import static de.droiddrone.common.launch.LaunchSequence.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A reserve's launch with its commander silent, as the phone's ArduLinkLoss runs it: the climb goes on to its approach
 * layer with the FC's ground station heartbeat kept going (AL-14: the phone decides, no 4G round trip; before, the
 * heartbeat stopped 3 s into the silence and ArduPilot's GCS failsafe broke the climb off); on its way to the group the
 * silence is a follower's link loss (AC-37): LOITER, then home by RTL after 2 min. A made-up clock.
 */
class LaunchLinkLossTest {
    static final long T = 1_000_000;
    static final int LOITER = 5, GUIDED = 4;

    /** The phone's step: the launch rule on the commander's last word while it applies, as ArduLinkLoss feeds it. */
    static int step(ArduLinkLossRule r, long now, int state, long commanderHeardAt, int mode) {
        long heard = LaunchSequence.linkLossApplies(state) ? commanderHeardAt : now;
        return r.tick(now, heard, true, mode, true, true);
    }

    static ArduLinkLossRule rule() {
        return new ArduLinkLossRule(LOST_BRAKE_AFTER_MS, LOST_HOME_AFTER_MS);
    }

    @Test
    void theClimbGoesOnWithTheHeartbeatWhateverTheCommander() {
        ArduLinkLossRule r = rule();
        for (int s : new int[]{CALLED, WAKING, CHECKING, BEEPING, ARMING, CLIMBING}) {
            assertTrue(keepsGcsAlive(s), stateWord(s) + ": the FC hears its ground station");
            assertFalse(linkLossApplies(s), stateWord(s) + ": the phone's own (AL-14)");
            for (long t = T; t < T + 90_000; t += 100) assertEquals(ArduLinkLossRule.NONE, step(r, t, s, T, GUIDED), stateWord(s));
        }
        for (int s : new int[]{IDLE, NOT_LAUNCHED, LAUNCH_FAILED, CANCELLED, IN_GROUP}) {
            assertFalse(keepsGcsAlive(s), stateWord(s) + ": not a launch any more (a follower's own rules in a group)");
            assertFalse(linkLossApplies(s));
        }
    }

    @Test
    void onItsWayTheSilenceIsAFollowersLinkLoss() {
        for (int s : new int[]{ON_THE_WAY, WAITING_FOR_TRANSIT, IN_TRANSIT}) {
            assertTrue(keepsGcsAlive(s));
            assertTrue(linkLossApplies(s));
            ArduLinkLossRule r = rule();
            // the commander's TRACK every 5 s: never a brake
            long heard = T;
            for (long t = T; t < T + 60_000; t += 100) {
                if ((t - T) % 5000 == 0) heard = t;
                assertEquals(ArduLinkLossRule.NONE, step(r, t, s, heard, GUIDED), "heard every 5 s, at " + (t - T));
            }
            // then silent: LOITER after 12 s, RTL after 2 min (its home: its launch spot)
            long last = heard;
            int mode = GUIDED;
            boolean braked = false, home = false;
            for (long t = last; t < last + 130_000; t += 100) {
                int a = step(r, t, s, last, mode);
                if (a == ArduLinkLossRule.BRAKE) {
                    assertEquals(LOST_BRAKE_AFTER_MS, t - last, "LOITER at 12 s");
                    braked = true;
                    mode = LOITER;
                }
                if (a == ArduLinkLossRule.RTL) {
                    assertEquals(LOST_HOME_AFTER_MS, t - last, "home at 2 min");
                    home = true;
                    mode = ArduLinkLossRule.MODE_RTL;
                }
                assertNotEquals(ArduLinkLossRule.SMART_RTL, a, "plain RTL: Smart RTL would fly back up its climb column");
            }
            assertTrue(braked && home, stateWord(s));
        }
    }

    @Test
    void heardAgainBeforeTheReturnItRejoins() {
        ArduLinkLossRule r = rule();
        assertEquals(ArduLinkLossRule.BRAKE, step(r, T + LOST_BRAKE_AFTER_MS, ON_THE_WAY, T, GUIDED));
        assertTrue(r.running());
        assertEquals(ArduLinkLossRule.NONE, step(r, T + 30_000, ON_THE_WAY, T + 29_900, LOITER));
        assertFalse(r.running(), "the commander back: it rejoins like a reserve (AC-38)");
    }

    /** The climb ends with the commander already silent: the count starts from its last word, not the climb's end. */
    @Test
    void silentThroughTheClimbBrakesAtTheTopIfOverdue() {
        ArduLinkLossRule r = rule();
        assertEquals(ArduLinkLossRule.NONE, step(r, T + 20_000, CLIMBING, T, GUIDED));
        assertEquals(ArduLinkLossRule.BRAKE, step(r, T + 20_100, ON_THE_WAY, T, GUIDED), "at the top, 20 s silent: LOITER");
    }
}
