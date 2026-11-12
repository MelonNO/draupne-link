package de.droiddrone.pc.gui;

import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.common.PowerFloor;
import de.droiddrone.common.SleepCheck;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.gui.PowerController.Asleep;
import de.droiddrone.pc.overwatch.AlertCard;
import de.droiddrone.pc.overwatch.OverwatchHub;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The review round of 1.0.64 on the LINK card: when LOST DRONE shows and what it asks, which alert PILOT UAV answers,
 * the alerts that came while flying, a blind or dropped overwatch, a mode asked and not taken, and the texts. The
 * rules as the window's pure helpers, without a window.
 */
class LinkReviewRoundTest {
    private static final long S = 1000;

    // ------------------------------------------------------------------ LOST DRONE

    /** A stall of 0.3 s brought the red button up, the next packet took it away: in weak coverage it popped in and out. */
    @Test
    void lostDroneComesOnlyAfterTheLinkHasBeenDownFiveSeconds() {
        MainWindow.LostDroneShow s = new MainWindow.LostDroneShow();
        long t = 100 * S;
        assertFalse(s.update(true, true, t), "not at the first stalled look");
        assertFalse(s.update(true, true, t + MainWindow.LostDroneShow.SHOW_AFTER_MS - 1));
        assertTrue(s.update(true, true, t + MainWindow.LostDroneShow.SHOW_AFTER_MS), "down five seconds on end");

        // weak coverage: stalled 0.4 s, heard 2 s, again and again - never five seconds down: never shown
        MainWindow.LostDroneShow flap = new MainWindow.LostDroneShow();
        boolean ever = false;
        for (long at = 0; at < 60 * S; at += 33) ever |= flap.update(at % 2400 < 400, true, at);
        assertFalse(ever, "a link that comes back every time is not a lost drone");
    }

    /** One packet through a dead link doesn't start the five seconds over: a good moment under a second is a blip. */
    @Test
    void aBlipDoesNotStartTheCountAgain() {
        MainWindow.LostDroneShow s = new MainWindow.LostDroneShow();
        boolean shown = false;
        // down, with 0.3 s of "heard" every 2 s (one packet a time)
        for (long at = 0; at <= 6 * S; at += 33) shown = s.update(at % 2000 >= 300, true, at);
        assertTrue(shown, "five seconds of a link that is as good as gone");
    }

    /** Once up it stays through a link that flickers back, until the link has been good ten seconds on end. */
    @Test
    void lostDroneStaysUntilTheLinkHasBeenGoodTenSeconds() {
        MainWindow.LostDroneShow s = new MainWindow.LostDroneShow();
        s.update(true, true, 0);
        assertTrue(s.update(true, true, 6 * S));
        assertTrue(s.update(false, true, 7 * S), "back for a moment: it stays where it is");
        assertTrue(s.update(false, true, 12 * S));
        assertTrue(s.update(true, true, 13 * S), "and gone again");
        assertTrue(s.update(false, true, 14 * S));
        assertTrue(s.update(false, true, 14 * S + MainWindow.LostDroneShow.HIDE_AFTER_MS - 1));
        assertFalse(s.update(false, true, 14 * S + MainWindow.LostDroneShow.HIDE_AFTER_MS), "good ten seconds: it goes");
        assertFalse(s.update(true, true, 30 * S), "the next loss counts its own five seconds");
        assertTrue(s.update(true, true, 35 * S));
        assertFalse(s.update(true, false, 36 * S), "the lost-drone window over (or no position to show): gone");
        assertFalse(s.update(true, true, 37 * S), "and from nothing again");
    }

    /** The button ends the link at one click (the pilot's rule for a lost drone) - but not one that is back, unasked. */
    @Test
    void lostDroneAsksFirstWhenTheLinkIsBack() {
        assertTrue(MainWindow.linkBack(ControlLink.State.READY, false, false, 300), "ready, heard, the FC's status fresh: ask");
        assertFalse(MainWindow.linkBack(ControlLink.State.CONNECTING, false, false, 300), "lost: ends at once, as before");
        assertFalse(MainWindow.linkBack(ControlLink.State.READY, true, false, 300), "stalled");
        assertFalse(MainWindow.linkBack(ControlLink.State.READY, false, true, 300), "the FC gone");
        assertFalse(MainWindow.linkBack(ControlLink.State.READY, false, false, 2500), "no fresh status");
        assertFalse(MainWindow.linkBack(ControlLink.State.READY, false, false, -1), "no status at all");
    }

    // ------------------------------------------------------------------ PILOT UAV and the alerts

    private static OverwatchHub.View view(String drone, AlertCard.Shown shown, boolean ended, long endedAtWall) {
        return new OverwatchHub.View(drone, shown, ended, 1_000_000, endedAtWall, null, null, null, null, false, null, 0);
    }

    /**
     * Every view the hub keeps counted as "alerting": PILOT UAV on a phone whose alert was dismissed, or ended hours ago,
     * took the alert's path - CMD_CONNECT to an old address, its asleep record dropped, no REGAINING CONTROL countdown.
     */
    @Test
    void onlyAnAlertOnScreenAndRunningIsAnsweredByPilotUav() {
        long now = 10_000_000;
        List<OverwatchHub.View> views = List.of(
                view("MobileAlpha", AlertCard.Shown.CARD, false, -1),                                   // motion now
                view("MobileBravo", AlertCard.Shown.HIDDEN, false, -1),                                 // dismissed
                view("MobileCharlie", AlertCard.Shown.BADGE, true, now - 3 * 3600_000L),               // hours old
                view("MobileDelta", AlertCard.Shown.CARD, true, now - MainWindow.ALERT_RECENT_MS + 1), // just ended
                view("MobileEcho", AlertCard.Shown.CARD, true, now - MainWindow.ALERT_RECENT_MS),      // ended a while ago
                view("MobileFoxtrot", AlertCard.Shown.CARD, true, -1));
        assertEquals(List.of("MobileAlpha", "MobileDelta"), MainWindow.alertingNow(views, now));
        assertEquals("MobileAlpha", MainWindow.alertingDrone("mobilealpha", MainWindow.alertingNow(views, now)));
        assertNull(MainWindow.alertingDrone("MobileBravo", MainWindow.alertingNow(views, now)), "dismissed: an ordinary PILOT UAV");
        assertNull(MainWindow.alertingDrone("MobileCharlie", MainWindow.alertingNow(views, now)), "hours old: it sleeps on its timer again");
        assertNull(MainWindow.alertingDrone("MobileEcho", MainWindow.alertingNow(views, now)));
        assertTrue(MainWindow.alertingNow(List.of(), now).isEmpty());
    }

    /** An alert while a drone here may fly is silent, as it must be - and nothing rang for it after the landing. */
    @Test
    void anAlertThatCameWhileFlyingIsRungForAfterTheFlightIfStillUnanswered() {
        List<OverwatchHub.View> views = List.of(
                view("MobileAlpha", AlertCard.Shown.CARD, false, -1),
                view("MobileBravo", AlertCard.Shown.HIDDEN, true, 5),   // dismissed, or connected to, meanwhile
                view("MobileCharlie", AlertCard.Shown.BADGE, true, 5)); // its motion ended long before the landing
        assertEquals(List.of("MobileAlpha", "MobileCharlie"),
                MainWindow.unanswered(List.of("mobilealpha", "MobileBravo", "MobileCharlie", "MobileGone"), views));
        assertTrue(MainWindow.unanswered(List.of(), views).isEmpty(), "none came quietly: nothing to ring for");
    }

    // ------------------------------------------------------------------ an overwatch that no longer watches

    private static ControlLink.SleepHello hello(int flags, int floorReason) {
        return new ControlLink.SleepHello(60, false, 5, 3900, 1000, flags, floorReason, null);
    }

    /** "OVERWATCH SEES NOTHING" was a log line; the badge stayed OVERWATCH and the map's sign the eye. */
    @Test
    void aBlindOverwatchIsKnownFromItsCheckIn() {
        Asleep watching = new Asleep("MobileAlpha", "100.64.0.9", 0, 5, true), asleep = new Asleep("MobileAlpha", "100.64.0.9", 0, 5, false);
        ControlLink.SleepHello blind = hello(OverwatchProtocol.HELLO_OVERWATCH | OverwatchProtocol.HELLO_PICTURES, 0);
        ControlLink.SleepHello sees = hello(OverwatchProtocol.HELLO_OVERWATCH | OverwatchProtocol.HELLO_PICTURES | OverwatchProtocol.HELLO_ANALYSING, 0);
        assertTrue(MainWindow.watchesBlind(watching, blind, 0, false));
        assertFalse(MainWindow.watchesBlind(watching, sees, 0, false));
        assertFalse(MainWindow.watchesBlind(watching, null, 0, false), "no check-in heard yet: not called blind");
        assertFalse(MainWindow.watchesBlind(watching, blind, 1, false), "its check-in overdue: UNKNOWN, not blind");
        assertFalse(MainWindow.watchesBlind(asleep, blind, 0, false), "in deep sleep: nothing to be blind");
        assertFalse(MainWindow.watchesBlind(null, blind, 0, false));
        assertTrue(MainWindow.watchesBlind(watching, sees, 0, true), "the hub heard its camera stop");
    }

    @Test
    void aDropToDeepSleepAtTheFloorIsToldFromOneThePilotAsked() {
        Asleep now = new Asleep("MobileAlpha", "100.64.0.9", 0, 5, false);
        assertTrue(MainWindow.droppedAtFloor(true, now, hello(0, PowerFloor.REASON_BATTERY)));
        assertTrue(MainWindow.droppedAtFloor(true, now, hello(0, PowerFloor.REASON_HEAT)));
        assertFalse(MainWindow.droppedAtFloor(true, now, hello(0, PowerFloor.REASON_NONE)), "DEEP SLEEP asked from FLEET: no ring");
        assertFalse(MainWindow.droppedAtFloor(false, now, hello(0, PowerFloor.REASON_BATTERY)), "it wasn't overwatching");
        assertFalse(MainWindow.droppedAtFloor(true, new Asleep("MobileAlpha", "100.64.0.9", 0, 5, true), hello(1, 0)), "still watching");
        assertFalse(MainWindow.droppedAtFloor(true, now, null));
    }

    // ------------------------------------------------------------------ a mode asked while connected

    /** DEEP SLEEP pressed: nothing changed until the phone reported, and a phone that never did kept the sticks paused. */
    @Test
    void aModeAskedShowsOnItsButtonAndIsTakenBackAfterFifteenSeconds() {
        int sleep = ControlLink.POWER_DEEP_SLEEP, watch = ControlLink.POWER_OVERWATCH, normal = ControlLink.POWER_NORMAL;
        assertEquals("DEEP SLEEP...", MainWindow.modeButtonText(sleep, sleep));
        assertEquals("OVERWATCH", MainWindow.modeButtonText(watch, sleep), "only the one pressed");
        assertEquals("DEEP SLEEP", MainWindow.modeButtonText(sleep, normal));
        assertEquals("OVERWATCH...", MainWindow.modeButtonText(watch, watch));

        assertFalse(MainWindow.modeNotTaken(sleep, sleep, -1, false, MainWindow.MODE_TAKE_MS - 1), "it has its time");
        assertTrue(MainWindow.modeNotTaken(sleep, sleep, -1, false, MainWindow.MODE_TAKE_MS), "never reported: taken back");
        assertTrue(MainWindow.modeNotTaken(sleep, sleep, normal, false, MainWindow.MODE_TAKE_MS), "still in NORMAL");
        assertFalse(MainWindow.modeNotTaken(sleep, sleep, sleep, false, 60_000), "it reported the mode: it is going there");
        assertFalse(MainWindow.modeNotTaken(sleep, sleep, -1, true, 60_000), "asleep: the link went as asked");
        assertFalse(MainWindow.modeNotTaken(sleep, normal, -1, false, 60_000), "taken back already (armed)");
        assertFalse(MainWindow.modeNotTaken(normal, normal, -1, false, 60_000), "nothing asked");
    }

    // ------------------------------------------------------------------ texts

    @Test
    void atCheckInIsSaidOnlyForAPhoneAsleepOnATimer() {
        assertEquals("QUEUED: OVERWATCH", MainWindow.queuedText(ControlLink.POWER_OVERWATCH, null), "awake: it takes it at the next scan");
        assertEquals("QUEUED: STANDBY (AT CHECK-IN)", MainWindow.queuedText(ControlLink.POWER_STANDBY, new Asleep("MobileAlpha", "1.2.3.4", 0, 5, false)));
        assertEquals("QUEUED: DEEP SLEEP", MainWindow.queuedText(ControlLink.POWER_DEEP_SLEEP,
                new Asleep("MobileAlpha", "1.2.3.4", 0, SleepCheck.ALWAYS, true)), "listening all along: no check-in to wait for");
    }

    @Test
    void theButtonThatEndsALinkIsNamedAsOnTheCard() {
        assertEquals("STANDBY MODE", MainWindow.endLinkButton(false));
        assertEquals("STOP WATCHING", MainWindow.endLinkButton(true));
    }

    /** CONNECTING all through the countdown to a sleeper's check-in: WAKING. */
    @Test
    void thePillSaysWakingWhileASleeperIsWaitedFor() {
        assertEquals("WAKING", MainWindow.waitingPill(ControlLink.State.CONNECTING, true));
        assertEquals("WAKING", MainWindow.waitingPill(ControlLink.State.CONFIGURING, true));
        assertEquals("CONNECTING", MainWindow.waitingPill(ControlLink.State.CONNECTING, false));
        assertEquals("WAITING FOR PHONE", MainWindow.waitingPill(ControlLink.State.CONFIGURING, false));
    }

    /** "Waiting for the phone... (DRAUPNE Flight open? On Tailscale? Same key?)" in the log, of a phone that sleeps as asked. */
    @Test
    void theLogDoesNotAskAfterAPhoneThatSleepsAsMeant() {
        int sleep = ControlLink.POWER_DEEP_SLEEP, watch = ControlLink.POWER_OVERWATCH, normal = ControlLink.POWER_NORMAL;
        assertTrue(MainWindow.sleepsAsMeant(ControlLink.State.CONNECTING, true, normal, -1), "PILOT UAV waits for its check-in");
        assertTrue(MainWindow.sleepsAsMeant(ControlLink.State.CONNECTING, false, sleep, sleep), "it said DEEP SLEEP, then its link went");
        assertTrue(MainWindow.sleepsAsMeant(ControlLink.State.CONNECTING, false, watch, watch));
        assertFalse(MainWindow.sleepsAsMeant(ControlLink.State.CONNECTING, false, normal, 0), "an ordinary PILOT UAV: the question stands");
        assertFalse(MainWindow.sleepsAsMeant(ControlLink.State.CONNECTING, false, sleep, 0), "asked, never taken: a real loss");
        assertFalse(MainWindow.sleepsAsMeant(ControlLink.State.CONNECTING, false, ControlLink.POWER_STANDBY, ControlLink.POWER_STANDBY),
                "standby keeps its link: one lost there is a loss");
        assertFalse(MainWindow.sleepsAsMeant(ControlLink.State.READY, true, normal, -1), "the other lines are said as ever");
        assertFalse(MainWindow.sleepsAsMeant(ControlLink.State.CONFIGURING, true, normal, -1));
    }

    // ------------------------------------------------------------------ the link's Connects, the refresh

    /** A sleeper that missed its check-in: its windows are no longer where they were counted - the Connects go all along. */
    @Test
    void connectsAreNotHeldOnceACheckInWasMissed() {
        assertTrue(MainWindow.holdsConnects(true, false, true), "its window known, outside it: held");
        assertFalse(MainWindow.holdsConnects(true, true, true), "a check-in missed: never held");
        assertFalse(MainWindow.holdsConnects(false, false, true), "its window not known yet");
        assertFalse(MainWindow.holdsConnects(true, false, false), "round its check-in");
    }

    @Test
    void theCardsRefreshAFewTimesASecondAndAtOnceWhenAsked() {
        assertTrue(MainWindow.slowRefreshDue(50_000, 0), "asked for (a connect, a disconnect): the next refresh");
        assertFalse(MainWindow.slowRefreshDue(50_033, 50_000));
        assertFalse(MainWindow.slowRefreshDue(50_000 + MainWindow.SLOW_REFRESH_MS - 1, 50_000));
        assertTrue(MainWindow.slowRefreshDue(50_000 + MainWindow.SLOW_REFRESH_MS, 50_000));
        int slow = 0;
        long last = 0;
        for (long t = 1000; t < 11_000; t += 33) {
            if (MainWindow.slowRefreshDue(t, last)) {
                last = t;
                slow++;
            }
        }
        assertTrue(slow >= 45 && slow <= 65, slow + " slow rounds in 10 s of the 33 ms timer (it was 303 of everything)");
        assertTrue(MainWindow.SLOW_REFRESH_MS + 33 < de.droiddrone.pc.MotionBoxes.SOLID_MS, "the map's marks still take every record");
    }

    /** The PHONE list was emptied and filled every second while any phone slept. */
    @Test
    void thePhoneListIsLeftAloneWhileItsLinesAreTheSame() {
        JComboBox<String> combo = new JComboBox<>(new String[]{"MobileAlpha   online", "MobileBravo   OVERWATCH listening"});
        assertTrue(MainWindow.sameItems(combo, List.of("MobileAlpha   online", "MobileBravo   OVERWATCH listening")));
        assertFalse(MainWindow.sameItems(combo, List.of("MobileAlpha   online", "MobileBravo   DEEP SLEEP 4:59")), "a line changed");
        assertFalse(MainWindow.sameItems(combo, List.of("MobileAlpha   online")), "one fewer");
        assertFalse(MainWindow.sameItems(combo, List.of("MobileBravo   OVERWATCH listening", "MobileAlpha   online")), "another order");
    }
}
