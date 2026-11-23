package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static de.droiddrone.common.FleetCommandGate.*;
import static org.junit.jupiter.api.Assertions.*;

/** Who may command a drone, at the rule's edges: two PCs racing, a stale commander back, the 10 s and 5 s windows. */
class FleetCommandGateEdgeTest {
    @Test
    void inASessionOnlyThePilot() {
        for (boolean known : new boolean[]{true, false}) {
            for (boolean fromCommander : new boolean[]{true, false}) {
                for (boolean disarmed : new boolean[]{true, false}) {
                    assertEquals(ACCEPT, decide(true, true, known, fromCommander, Long.MAX_VALUE, disarmed));
                    assertEquals(REFUSE, decide(true, false, known, fromCommander, Long.MAX_VALUE, disarmed),
                            "another PC in the pilot's session, even its old commander");
                }
            }
        }
    }

    @Test
    void theTenSecondsToTheMillisecondAndOnlyDisarmed() {
        assertEquals(REFUSE, decide(false, false, true, false, TAKEOVER_SILENT_MS - 1, true));
        assertEquals(NEW_COMMANDER, decide(false, false, true, false, TAKEOVER_SILENT_MS, true));
        assertEquals(REFUSE, decide(false, false, true, false, Long.MAX_VALUE, false), "never of a drone not known disarmed");
        assertEquals(NEW_COMMANDER, decide(false, false, false, false, 0, true), "no commander yet: the first, disarmed");
        assertEquals(REFUSE, decide(false, false, false, false, 0, false));
        // a clock that jumped back: the commander's silence reads negative - never a take-over
        assertEquals(REFUSE, decide(false, false, true, false, -5_000, true));
    }

    /** Two PCs racing for a disarmed drone with no commander: the first one in is the commander, the second is refused. */
    @Test
    void twoPcsRacing() {
        assertEquals(NEW_COMMANDER, decide(false, false, false, false, 0, true)); // PC A, first
        // now A is the commander, heard just now: B comes 10 ms later
        assertEquals(REFUSE, decide(false, false, true, false, 10, true));
        assertEquals(ACCEPT, decide(false, false, true, true, 10, true));
    }

    /** The old commander back after another took over: it is not the commander any more, and the new one is fresh. */
    @Test
    void aStaleCommanderComingBack() {
        assertEquals(REFUSE, decide(false, false, true, false, 1000, true));
        assertEquals(REFUSE, decide(false, false, true, false, 1000, false), "armed in between: no take-over at all");
        assertEquals(ACCEPT, decide(false, false, true, true, 60_000, false), "its own commander, silent a minute: yes, armed or not");
    }

    @Test
    void leadConnectOnceFromTheCommanderWithinFiveSeconds() {
        assertTrue(leadConnect(true, true, 0));
        assertTrue(leadConnect(true, true, LEAD_CONNECT_MS));
        assertFalse(leadConnect(true, true, LEAD_CONNECT_MS + 1));
        assertFalse(leadConnect(true, true, -1), "a clock that jumped back");
        assertFalse(leadConnect(true, false, 100), "anyone else");
        assertFalse(leadConnect(false, true, 100), "not ordered as a follower");
    }
}
