package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static de.droiddrone.common.ArmedTouch.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Q-004: the phone's screen while the drone is armed - the touch lock on by itself, Stop only when held 3 s. Before,
 * the lock was by hand only and one touch on Stop ended the service in flight: the tests marked "before" fail on that.
 */
class ArmedTouchTest {
    private static final boolean BOARD = true, NO_BOARD = false, PC = true, NO_PC = false;

    @Test
    void armedIsWhatTheFlightControllerReadsOrNotKnownWhileAPcFliesIt() {
        assertTrue(armed(ARMED, BOARD, PC));
        assertTrue(armed(ARMED, BOARD, NO_PC), "armed, the link lost: the drone flies on its failsafe");
        assertFalse(armed(DISARMED, BOARD, PC));
        assertTrue(armed(UNKNOWN, BOARD, PC), "an app just started in the air, its board not read yet, the pilot connected again");
        assertFalse(armed(UNKNOWN, BOARD, NO_PC), "a board that does not answer on the bench: the phone stays the user's");
        assertFalse(armed(UNKNOWN, NO_BOARD, PC), "no flight controller on USB: nothing changes");
        assertFalse(armed(DISARMED, NO_BOARD, PC));
    }

    @Test
    void whileArmedTheLockIsOnByItself() {
        ArmedTouch t = new ArmedTouch();
        assertEquals(NOTHING, t.look(DISARMED, BOARD, PC, 0), "at rest: the screen as the user has it");
        assertFalse(t.locked());
        assertEquals(LOCK, t.look(ARMED, BOARD, PC, 1000), "before: nothing - the lock was by hand only");
        assertTrue(t.locked());
        assertTrue(t.byItself());
        assertEquals(NOTHING, t.look(ARMED, BOARD, PC, 2000), "asked once");
        assertEquals(NOTHING, t.look(ARMED, BOARD, NO_PC, 3000));
    }

    @Test
    void anAppThatHasJustStartedAnewLocksAtItsFirstLookAtAnArmedDrone() {
        // the app opened itself again after an error, the drone in the air: what it knew is gone, the drone is not
        assertEquals(LOCK, new ArmedTouch().look(ARMED, BOARD, NO_PC, 0), "before: unlocked for the rest of the flight");
        // its board not read yet, the pilot's PC connected again - and read ARMED a moment later
        ArmedTouch t = new ArmedTouch();
        assertEquals(NOTHING, t.look(UNKNOWN, BOARD, NO_PC, 0));
        assertEquals(NOTHING, t.look(UNKNOWN, BOARD, PC, 1000), "a second of not knowing locks nothing yet");
        assertEquals(LOCK, t.look(ARMED, BOARD, PC, 2000), "read: at once");
        // a board that stays unread (its USB permission not given again) while the PC flies it
        ArmedTouch u = new ArmedTouch();
        assertEquals(NOTHING, u.look(UNKNOWN, BOARD, PC, 1000));
        assertEquals(NOTHING, u.look(UNKNOWN, BOARD, PC, 1000 + UNKNOWN_LOCK_AFTER_MS - 1));
        assertEquals(LOCK, u.look(UNKNOWN, BOARD, PC, 1000 + UNKNOWN_LOCK_AFTER_MS), "not known for 3 s with a PC flying: locked");
        assertTrue(u.byItself());
    }

    @Test
    void anAppsStartOrAWakeWithThePcAlreadyThereLocksNothing() {
        // the PC connects within a second, the board's first answers take two: "not known" for a moment, then disarmed
        ArmedTouch t = new ArmedTouch();
        assertEquals(NOTHING, t.look(UNKNOWN, BOARD, PC, 0));
        assertEquals(NOTHING, t.look(UNKNOWN, BOARD, PC, 1000));
        assertEquals(NOTHING, t.look(UNKNOWN, BOARD, PC, 2000));
        for (long now = 3000; now <= 20_000; now += 1000) assertEquals(NOTHING, t.look(DISARMED, BOARD, PC, now));
        assertFalse(t.locked(), "no lock flashed up on a drone that rests");
        // a status missed now and then in between (a slow USB link) does not add up to three seconds
        ArmedTouch u = new ArmedTouch();
        for (long now = 0; now <= 30_000; now += 1000) assertEquals(NOTHING, u.look(now % 3000 == 0 ? UNKNOWN : DISARMED, BOARD, PC, now));
        // while Stop's hold never waits: not known with a PC flying is armed for the press at once
        assertFalse(pressCounts(armed(UNKNOWN, BOARD, PC), 0));
    }

    @Test
    void onByItselfItGoesOffByItselfFiveSecondsAfterDisarmedIsRead() {
        ArmedTouch t = new ArmedTouch();
        assertEquals(LOCK, t.look(ARMED, BOARD, PC, 0));
        assertEquals(NOTHING, t.look(DISARMED, BOARD, PC, 10_000), "landed and disarmed: not yet");
        assertEquals(NOTHING, t.look(DISARMED, BOARD, PC, 10_000 + UNLOCK_AFTER_MS - 1));
        assertTrue(t.locked());
        assertEquals(UNLOCK, t.look(DISARMED, BOARD, PC, 10_000 + UNLOCK_AFTER_MS));
        assertFalse(t.locked());
        assertEquals(NOTHING, t.look(DISARMED, BOARD, PC, 20_000));
        assertEquals(LOCK, t.look(ARMED, BOARD, PC, 30_000), "armed again: on again");
    }

    @Test
    void notKnownIsNotDisarmedAndABreakStartsTheFiveSecondsAgain() {
        ArmedTouch t = new ArmedTouch();
        t.look(ARMED, BOARD, PC, 0);
        // the link lost and the board's USB out for a moment: nobody knows - the lock stays
        for (long now = 1000; now <= 60_000; now += 1000) assertEquals(NOTHING, t.look(UNKNOWN, NO_BOARD, NO_PC, now));
        assertTrue(t.locked());
        assertEquals(NOTHING, t.look(DISARMED, BOARD, NO_PC, 61_000));
        assertEquals(NOTHING, t.look(DISARMED, BOARD, NO_PC, 64_000));
        assertEquals(NOTHING, t.look(UNKNOWN, BOARD, NO_PC, 65_000), "one status missed");
        assertEquals(NOTHING, t.look(DISARMED, BOARD, NO_PC, 66_000), "5 s from here");
        assertEquals(NOTHING, t.look(DISARMED, BOARD, NO_PC, 70_999));
        assertEquals(UNLOCK, t.look(DISARMED, BOARD, NO_PC, 71_000));
        // armed for a moment in between: the count starts over too
        ArmedTouch u = new ArmedTouch();
        u.look(ARMED, BOARD, PC, 0);
        u.look(DISARMED, BOARD, PC, 1000);
        u.look(ARMED, BOARD, PC, 4000);
        assertEquals(NOTHING, u.look(DISARMED, BOARD, PC, 6000));
        assertEquals(NOTHING, u.look(DISARMED, BOARD, PC, 10_999));
        assertEquals(UNLOCK, u.look(DISARMED, BOARD, PC, 11_000));
    }

    @Test
    void aLockPutOnByHandStaysAsTheUserLeftIt() {
        ArmedTouch t = new ArmedTouch();
        t.lockedByHand();
        assertTrue(t.locked());
        assertFalse(t.byItself());
        assertEquals(NOTHING, t.look(ARMED, BOARD, PC, 0), "on already");
        for (long now = 1000; now <= 60_000; now += 1000) assertEquals(NOTHING, t.look(DISARMED, BOARD, PC, now), "disarmed: the user's lock stays");
        assertTrue(t.locked());
        t.unlockedByHand(61_000);
        assertFalse(t.locked());
        assertEquals(NOTHING, t.look(DISARMED, BOARD, PC, 62_000));
    }

    @Test
    void unlockedByHandWhileArmedTheScreenIsTheUsersForAWhileThenLockedAgain() {
        ArmedTouch t = new ArmedTouch();
        assertEquals(LOCK, t.look(ARMED, BOARD, PC, 0));
        t.unlockedByHand(10_000); // the box held 2 s: to get at Stop
        assertFalse(t.locked());
        assertEquals(NOTHING, t.look(ARMED, BOARD, PC, 11_000), "not snapped shut under the finger");
        assertEquals(NOTHING, t.look(ARMED, BOARD, PC, 10_000 + RELOCK_AFTER_MS - 1));
        assertEquals(LOCK, t.look(ARMED, BOARD, PC, 10_000 + RELOCK_AFTER_MS), "still armed: on by itself again");
        assertTrue(t.byItself());
        // unlocked, then disarmed and armed again: locked at once
        t.unlockedByHand(40_000);
        assertEquals(NOTHING, t.look(DISARMED, BOARD, PC, 41_000));
        assertEquals(LOCK, t.look(ARMED, BOARD, PC, 42_000));
    }

    @Test
    void theScreenBuiltAgainKeepsTheLockAndWhoPutItOn() {
        ArmedTouch byHand = new ArmedTouch();
        byHand.restore(false);
        assertTrue(byHand.locked());
        for (long now = 0; now <= 10_000; now += 1000) assertEquals(NOTHING, byHand.look(DISARMED, BOARD, PC, now));
        ArmedTouch itself = new ArmedTouch();
        itself.restore(true);
        assertTrue(itself.byItself());
        assertEquals(NOTHING, itself.look(DISARMED, BOARD, PC, 0));
        assertEquals(UNLOCK, itself.look(DISARMED, BOARD, PC, UNLOCK_AFTER_MS));
    }

    @Test
    void withNoFlightControllerOnUsbNothingChanges() {
        ArmedTouch t = new ArmedTouch();
        for (long now = 0; now <= 20_000; now += 1000) {
            // the port's first seconds say "not known", then "disarmed" (FcUnread): a PC connected all the while
            assertEquals(NOTHING, t.look(now < 3000 ? UNKNOWN : DISARMED, NO_BOARD, PC, now));
        }
        assertFalse(t.locked());
        assertTrue(pressCounts(armed(UNKNOWN, NO_BOARD, PC), 0), "and Stop is a tap, as ever");
    }

    @Test
    void whileArmedStopCountsOnlyWhenHeldThreeSeconds() {
        assertFalse(pressCounts(true, 0), "before: one touch ended the service, the flight controller's port with it");
        assertFalse(pressCounts(true, 150), "a tap");
        assertFalse(pressCounts(true, HOLD_MS - 1));
        assertTrue(pressCounts(true, HOLD_MS));
        assertTrue(pressCounts(true, 10_000));
        assertTrue(pressCounts(false, 0), "disarmed: a tap, as ever");
        assertEquals(3000, HOLD_MS);
    }

    @Test
    void theButtonCountsTheSecondsDown() {
        assertEquals(3, holdSecondsLeft(0));
        assertEquals(3, holdSecondsLeft(999));
        assertEquals(2, holdSecondsLeft(1000));
        assertEquals(1, holdSecondsLeft(2000));
        assertEquals(1, holdSecondsLeft(2999));
        assertEquals(0, holdSecondsLeft(3000));
        assertEquals(3, holdSecondsLeft(-5), "a clock that hiccups never shows more than three");
    }

    @Test
    void itsStatesAreThePhonesOwnNumbers() {
        // FcArmedState.DISARMED / ARMED / UNKNOWN in the phone app: handed on as they are
        assertEquals(0, DISARMED);
        assertEquals(1, ARMED);
        assertEquals(2, UNKNOWN);
    }

    /** The phone app cannot be run here: its sources are read, as PacketIdsTest and UpdateWithdrawTest do. */
    @Test
    void thePhonesScreenAsksTheRuleAndEndsTheServiceNowhereElse() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        java.nio.file.Path app = root.resolve("flight-app/overlay/Flight/src/main");
        String activity = text(app.resolve("java/de/droiddrone/flight/MainActivity.java"));
        String state = text(app.resolve("java/de/droiddrone/flight/FcArmedState.java"));
        String strings = text(app.resolve("res/values/strings.xml"));
        // armed, as the rule has it, from what the service reads now - at every look and at every touch of Stop
        assertTrue(activity.contains("ArmedTouch.armed(s.fcArmedState(), s.isFcBoardOnPort(), s.isPcConnected())"));
        assertTrue(activity.contains("armedTouch.look(s.fcArmedState(), s.isFcBoardOnPort(), s.isPcConnected(), now)"));
        assertTrue(state.contains("return port(msp) == de.droiddrone.common.FcUnread.PORT_BOARD;"), "a board on the port: FcUnread's own word for it");
        // looked at once a second and as the screen comes up - so also in an app that has just opened itself again
        String resume = activity.substring(activity.indexOf("protected void onResume(){"), activity.indexOf("protected void onPause(){"));
        assertEquals(2, resume.split("updateArmedTouch\\(\\);", -1).length - 1, "onResume itself, and its timer's tick");
        // the lock by hand, and its unlocking, are told to the rule
        assertTrue(activity.contains("armedTouch.lockedByHand();") && activity.contains("armedTouch.unlockedByHand("));
        // the service is ended from the screen in two places: the tap's, behind the rule, and the hold's
        assertEquals(2, activity.split("stopService\\(", -1).length - 1, "no third way to end the service from the screen");
        String tap = activity.substring(activity.indexOf("private void startStopService(){"), activity.indexOf("private boolean armedForTouch() {"));
        assertTrue(tap.contains("if (armedForTouch()) {") && tap.indexOf("if (armedForTouch()) {") < tap.indexOf("stopService(intent);"),
                "a tap, or anything else that gets there: refused while armed before the service is touched");
        String hold = activity.substring(activity.indexOf("private final Runnable stopHoldTick"), activity.indexOf("public static final String ACTION_WAKE"));
        assertTrue(hold.contains("if (ArmedTouch.pressCounts(true, held)) {") && hold.indexOf("pressCounts(true, held)") < hold.indexOf("stopHeld();"),
                "the hold's own timer asks the rule before it ends the service");
        assertTrue(activity.contains("bStartStopService.setOnTouchListener(this::stopTouched);"));
        // the words, short
        assertTrue(strings.contains("<string name=\"dd_armed_hold\">Armed: hold 3 s</string>"));
        assertTrue(strings.contains("<string name=\"dd_locked_armed\">Armed: locked by itself. Unlocks after disarm.</string>"));
    }

    private static String text(java.nio.file.Path file) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
    }
}
