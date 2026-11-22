package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static de.droiddrone.common.FcUnread.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * F-068: what the phone's armed guards are told while no flight controller has answered. Before, that was DISARMED
 * unless this app had seen it armed within the minute - whatever the port said; the first test of each case below
 * fails on that rule.
 */
class FcUnreadTest {
    /** This app has never read the flight controller ARMED: it has just started. */
    private static final long NEVER = -1;

    @Test
    void aBoardThatHasNotAnsweredYetIsNotKnown() {
        // the app started anew with the drone in the air: the board is on USB, its first answers a second or two away
        assertEquals(UNKNOWN, state(PORT_BOARD, 0, false, NEVER), "not DISARMED: another PC took the session, a FLEET mode dropped the sticks");
        assertEquals(UNKNOWN, state(PORT_BOARD, 0, false, 10 * 60_000L), "nor because it was last seen armed long ago");
        assertEquals(UNKNOWN, state(PORT_BOARD, 0, false, 0));
    }

    @Test
    void aBoardThatNeverAnswersStaysNotKnown() {
        // found on USB and its permission not given again, opened and silent, the port failing: there is a board
        assertEquals(UNKNOWN, state(PORT_BOARD, 3_600_000, false, NEVER), "for good: nobody knows what it flies");
        assertEquals(UNKNOWN, state(PORT_BOARD, 3_600_000, true, NEVER));
    }

    @Test
    void noBoardOnUsbAtAllIsDisarmedOnceTheAppHasLooked() {
        assertEquals(UNKNOWN, state(PORT_NOTHING, 0, false, NEVER), "the port says \"nothing\" a moment before its first look: not yet");
        assertEquals(UNKNOWN, state(PORT_NOTHING, NOTHING_AFTER_MS - 1, false, NEVER));
        assertEquals(DISARMED, state(PORT_NOTHING, NOTHING_AFTER_MS, false, NEVER), "a phone on the bench with no board: standby, a FLEET mode, an update, as before");
        assertEquals(DISARMED, state(PORT_NOTHING, 3_600_000, false, NEVER));
    }

    @Test
    void aBoardThatFellOffAfterItWasSeenArmedStaysNotKnownForAMinute() {
        assertEquals(UNKNOWN, state(PORT_NOTHING, NOTHING_AFTER_MS, false, 0), "its USB link fell off in the air (pc.31)");
        assertEquals(UNKNOWN, state(PORT_NOTHING, 30_000, false, ARMED_MEMORY_MS - 1));
        assertEquals(DISARMED, state(PORT_NOTHING, 61_000, false, ARMED_MEMORY_MS), "a minute on: as before");
    }

    @Test
    void thePortClosedForASleepIsDisarmedAndOneNotLookedForYetIsNotKnown() {
        assertEquals(DISARMED, state(PORT_CLOSED, 0, true, NEVER), "deep sleep and overwatch close it right after a DISARMED read: overwatch's motion detection goes on (pc.50)");
        assertEquals(UNKNOWN, state(PORT_CLOSED, 0, false, NEVER), "the app's first moments, the moment of a wake: nobody has looked");
        assertEquals(UNKNOWN, state(PORT_CLOSED, 0, false, 5000));
    }

    @Test
    void itsAnswersAreThePhonesOwnNumbers() {
        // FcArmedState.DISARMED / UNKNOWN in the phone app (its ARMED is 1): the answer is handed on as it is
        assertEquals(0, DISARMED);
        assertEquals(2, UNKNOWN);
    }
}
