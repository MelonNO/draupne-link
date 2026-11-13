package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.65 (the pilot: one STANAG stream per drone - the video only while it is piloted or observed, "else there should be
 * a black screen with the DRAUPNE logo and an informational text about what mode it is in"; an alert: "live picture then
 * clip", and "account for if there comes a new detection or the user changes mode while the clip is playing").
 */
class StanagShowTest {
    static FleetCards.Card card(String name, String badge, MapView.Kind kind) {
        return card(name, badge, kind, -1, 0, 5000, false);
    }

    static FleetCards.Card card(String name, String badge, MapView.Kind kind, long nextCheckMs, int intervalMin, long heardAgoMs, boolean blind) {
        return new FleetCards.Card(name, false, kind, badge, Double.NaN, -1, -1, false, nextCheckMs, intervalMin, heardAgoMs, -1, -1, -1,
                0, 0, FleetCards.NO_TEMP, FleetCards.NO_TEMP, null, null, -1, blind);
    }

    // ------------------------------------------------------------------ what goes out

    @Test
    void theVideoThenAnAlertsPictureThenAClipThenThePage() {
        assertEquals(StanagShow.Kind.VIDEO, StanagShow.pick(true, true, true));
        assertEquals(StanagShow.Kind.ALERT, StanagShow.pick(false, true, true));
        assertEquals(StanagShow.Kind.CLIP, StanagShow.pick(false, false, true));
        assertEquals(StanagShow.Kind.PAGE, StanagShow.pick(false, false, false));
    }

    // ------------------------------------------------------------------ the pages

    @Test
    void everyModeHasItsPage() {
        assertEquals("STANDBY", StanagShow.page(card("MobileAlpha", "STANDBY", MapView.Kind.STANDBY), false, null, false).title());
        assertEquals("OVERWATCH", StanagShow.page(card("MobileAlpha", "OVERWATCH", MapView.Kind.OVERWATCH), false, null, false).title());
        assertEquals("IDLE", StanagShow.page(card("MobileAlpha", "ACTIVE", MapView.Kind.ACTIVE), false, null, false).title());
        StanagShow.Page sleep = StanagShow.page(card("MobileAlpha", "ASLEEP", MapView.Kind.ASLEEP, 120_000, 5, 5000, false), false, null, false);
        assertEquals("DEEP SLEEP", sleep.title());
        assertEquals("Asleep. Checks in every 5 min.", sleep.text());
        assertEquals("Asleep. Always listening.",
                StanagShow.page(card("MobileAlpha", "ASLEEP", MapView.Kind.ASLEEP, FleetCards.LISTENING, 255, 5000, false), false, null, false).text());
    }

    @Test
    void noPageIsWithoutAnExplanationAndNoneIsLong() {
        Set<String> titles = new HashSet<>();
        FleetCards.Card[] cards = {
                card("MobileAlpha", "CRASHED", MapView.Kind.CRASHED), card("MobileAlpha", "LOST", MapView.Kind.LOST),
                card("MobileAlpha", "CONNECTED", MapView.Kind.ACTIVE), card("MobileAlpha", "OVERWATCH", MapView.Kind.OVERWATCH),
                card("MobileAlpha", "OVERWATCH", MapView.Kind.OVERWATCH, 60_000, 5, 5000, true),
                card("MobileAlpha", "ASLEEP", MapView.Kind.ASLEEP, 60_000, 2, 5000, false), card("MobileAlpha", "STANDBY", MapView.Kind.STANDBY),
                card("MobileAlpha", "ACTIVE", MapView.Kind.ACTIVE), card("MobileAlpha", "UNKNOWN", MapView.Kind.UNKNOWN),
                card("MobileAlpha", "UNKNOWN", MapView.Kind.UNKNOWN, -1, 0, -1, false)};
        for (FleetCards.Card c : cards) {
            StanagShow.Page p = StanagShow.page(c, false, null, false);
            titles.add(p.title());
            assertFalse(p.text().isBlank(), p.title() + " says what it means");
            assertTrue(p.text().length() <= 48, "short text (the pilot's rule): " + p.text());
        }
        assertEquals(Set.of("CRASHED", "LOST", "PILOTED", "OVERWATCH", "OVERWATCH - BLIND", "DEEP SLEEP", "STANDBY", "IDLE", "UNKNOWN", "OFFLINE"), titles);
    }

    private static FleetCards.Card charging0() {
        return new FleetCards.Card("MobileAlpha", false, MapView.Kind.STANDBY, "STANDBY", Double.NaN, -1, 12, true, -1, 0, 2000,
                3, 1, 9, 0, 0, FleetCards.NO_TEMP, FleetCards.NO_TEMP, null, null, -1, false);
    }

    /** The pilot: "include some stats in the screen shown when no video is streamed, specifically battery level". */
    @Test
    void thePageCarriesTheBatteriesAndWhenTheDroneWasHeardAndChecksInNext() {
        long now = java.time.LocalDateTime.of(2026, 11, 12, 21, 4, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        // asleep: phone 87 %, the pack 3.92 V a cell read 10 min ago, heard 90 s ago, next check in 3.5 min
        FleetCards.Card c = new FleetCards.Card("MobileAlpha", false, MapView.Kind.ASLEEP, "ASLEEP", 3.92, 600_000, 87, false, 210_000, 5, 90_000,
                3, 1, 9, 0, 0, FleetCards.NO_TEMP, FleetCards.NO_TEMP, null, null, -1, false);
        StanagShow.Page p = StanagShow.page(c, false, null, false, now);
        assertEquals("DEEP SLEEP", p.title());
        assertEquals(java.util.List.of(new StanagShow.Stat("PHONE", "87 %", false), new StanagShow.Stat("DRONE", "69 % AT 20:54", false),
                new StanagShow.Stat("FLIGHT", "16 MIN", false), new StanagShow.Stat("RANGE", "9.6 KM", false),
                new StanagShow.Stat("HEARD", "21:03", false), new StanagShow.Stat("NEXT CHECK", "21:08", false)), p.stats());
        // a second later the same page: a still picture stays one (clock times to the minute, no countdown)
        FleetCards.Card later = new FleetCards.Card("MobileAlpha", false, MapView.Kind.ASLEEP, "ASLEEP", 3.92, 601_000, 87, false, 209_000, 5, 91_000,
                3, 1, 9, 0, 0, FleetCards.NO_TEMP, FleetCards.NO_TEMP, null, null, -1, false);
        assertEquals(p, StanagShow.page(later, false, null, false, now + 1000));
        // low batteries are marked; a charging phone is not low; a fresh reading has no time; an always-listening sleeper no next check
        FleetCards.Card low = new FleetCards.Card("MobileAlpha", false, MapView.Kind.STANDBY, "STANDBY", 3.41, 0, 12, false, FleetCards.LISTENING, 255, 2000,
                3, 1, 9, 0, 0, FleetCards.NO_TEMP, FleetCards.NO_TEMP, null, null, -1, false);
        assertEquals(java.util.List.of(new StanagShow.Stat("PHONE", "12 %", true), new StanagShow.Stat("DRONE", "12 %", true),
                new StanagShow.Stat("FLIGHT", "3 MIN", true), new StanagShow.Stat("RANGE", "1.7 KM", true),
                new StanagShow.Stat("HEARD", "21:04", false)), StanagShow.stats(low, now));
        // the pilot: "flight time remaining and estimated range left" - none without a pack reading
        for (StanagShow.Stat s : StanagShow.stats(charging0(), now)) assertFalse(s.label().equals("FLIGHT") || s.label().equals("RANGE"), s.label());
        FleetCards.Card charging = new FleetCards.Card("MobileAlpha", false, MapView.Kind.STANDBY, "STANDBY", Double.NaN, -1, 12, true, -1, 0, 2000,
                3, 1, 9, 0, 0, FleetCards.NO_TEMP, FleetCards.NO_TEMP, null, null, -1, false);
        assertEquals(new StanagShow.Stat("PHONE", "12 % CHARGING", false), StanagShow.stats(charging, now).get(0));
        // the pilot: "give a percentage of the power in the drone battery, don't give the cell value" - as FLEET's gauge fills
        assertEquals(100, StanagShow.dronePercent(4.2));
        assertEquals(100, StanagShow.dronePercent(4.35), "never over");
        assertEquals(50, StanagShow.dronePercent(3.75));
        assertEquals(0, StanagShow.dronePercent(3.3));
        assertEquals(0, StanagShow.dronePercent(2.9), "never under");
        for (StanagShow.Stat s : p.stats()) assertFalse(s.value().contains("V"), "no voltage on the page: " + s.value());
        // nothing known: no figures, never a made-up one
        assertTrue(StanagShow.stats(card("MobileAlpha", "UNKNOWN", MapView.Kind.UNKNOWN, -1, 0, -1, false), now).isEmpty());
    }

    @Test
    void aDroneFlownFromAnotherPcSaysWhichAndThatItsVideoIsThere() {
        StanagShow.Page p = StanagShow.page(card("MobileAlpha", "ACTIVE", MapView.Kind.ACTIVE), false, "PCBravo", false);
        assertEquals("PILOTED", p.title());
        assertEquals("Flown from PCBravo. Video on that PC.", p.text());
        assertEquals("Flown from another PC. Video on that PC.",
                StanagShow.page(card("MobileAlpha", "ACTIVE", MapView.Kind.ACTIVE), false, "", false).text());
    }

    /**
     * The pilot: "when I entered deep sleep the STANAG stream went to piloted before entering as deep sleep" - the
     * phone's last answer still said "flown", and that went before what this PC knew: that it sleeps.
     */
    @Test
    void aRestingDroneIsNeverPilotedOnAnOldWordThatItIsFlown() {
        assertEquals("DEEP SLEEP", StanagShow.page(card("MobileAlpha", "ASLEEP", MapView.Kind.ASLEEP, 60_000, 5, 1000, false), false, "PCAlpha", false).title());
        assertEquals("STANDBY", StanagShow.page(card("MobileAlpha", "STANDBY", MapView.Kind.STANDBY), false, "", false).title());
        assertEquals("OVERWATCH", StanagShow.page(card("MobileAlpha", "OVERWATCH", MapView.Kind.OVERWATCH), false, "PCBravo", false).title());
        assertEquals("PILOTED", StanagShow.page(card("MobileAlpha", "ACTIVE", MapView.Kind.ACTIVE), false, "PCBravo", false).title(), "an awake one flown elsewhere is");
    }

    /** The pilot: "change the standby text to not say that the pilot is back in a few". */
    @Test
    void standbySaysWhatItIsNotHowSoonItIsBack() {
        String text = StanagShow.page(card("MobileAlpha", "STANDBY", MapView.Kind.STANDBY), false, null, false).text();
        assertEquals("Camera off. Ready for PILOT UAV.", text);
        assertFalse(text.contains("back") || text.matches(".*\\d.*"), text);
    }

    @Test
    void pilotedHereWithoutAPictureSaysItWaitsAlsoForASpectator() {
        assertEquals("Waiting for the picture.", StanagShow.page(card("MobileAlpha", "CONNECTED", MapView.Kind.ACTIVE), false, null, false).text());
        // a spectator PC's card of the watched drone is not CONNECTED
        assertEquals("PILOTED", StanagShow.page(card("MobileAlpha", "ACTIVE", MapView.Kind.ACTIVE), true, "PCAlpha", false).title());
    }

    @Test
    void anAlertWithoutAPictureIsAnAlarmPageAndCrashedOrLostStaySo() {
        StanagShow.Page alert = StanagShow.page(card("MobileAlpha", "OVERWATCH", MapView.Kind.OVERWATCH), false, null, true);
        assertEquals("OVERWATCH - ALERT", alert.title());
        assertEquals(StanagShow.Tone.ALARM, alert.tone());
        assertEquals("CRASHED", StanagShow.page(card("MobileAlpha", "CRASHED", MapView.Kind.CRASHED), false, null, true).title());
        assertEquals(StanagShow.Tone.ALARM, StanagShow.page(card("MobileAlpha", "LOST", MapView.Kind.LOST), false, null, false).tone());
        assertEquals(StanagShow.Tone.WARN, StanagShow.page(card("MobileAlpha", "OVERWATCH", MapView.Kind.OVERWATCH, 1, 5, 1, true), false, null, false).tone());
    }

    @Test
    void aSleeperThatMissedItsCheckInSaysSoAndOneNeverHeardIsOffline() {
        assertEquals("Missed its check-in.", StanagShow.page(card("MobileAlpha", "UNKNOWN", MapView.Kind.UNKNOWN, -30_000, 5, 400_000, false), false, null, false).text());
        assertEquals("Not heard from.", StanagShow.page(card("MobileAlpha", "UNKNOWN", MapView.Kind.UNKNOWN, -1, 0, 400_000, false), false, null, false).text());
        assertEquals("OFFLINE", StanagShow.page(card("MobileAlpha", "UNKNOWN", MapView.Kind.UNKNOWN, -1, 0, -1, false), false, null, false).title());
    }

    @Test
    void theBarsSayLiveOnlyWhileThePreviewComesAndAClipIsCalledRecorded() {
        assertTrue(StanagShow.alertBar("MobileGolf", true, 0).startsWith("MobileGolf - OVERWATCH - ALERT - LIVE "));
        assertFalse(StanagShow.alertBar("MobileGolf", false, 0).contains("LIVE"), "the pictures from before the alert are not live");
        assertTrue(StanagShow.clipBar("MobileGolf", 0).endsWith(" - RECORDED"));
    }

    // ------------------------------------------------------------------ the clip that plays

    private static final long E1 = 1_000_000, E2 = 2_000_000;

    private static StanagClips.Alert over(long startedAt) {
        return new StanagClips.Alert(startedAt, false, false);
    }

    private static StanagClips.Alert running(long startedAt) {
        return new StanagClips.Alert(startedAt, true, false);
    }

    @Test
    void aClipPlaysOnceWhenItIsWhole() {
        StanagClips c = new StanagClips();
        assertEquals(-1, c.update("OVERWATCH", false, running(E1), s -> false), "the alert runs: its live picture");
        assertEquals(-1, c.update("OVERWATCH", false, over(E1), s -> false), "ended, the clip not here yet");
        assertEquals(E1, c.update("OVERWATCH", false, over(E1), s -> true), "whole: it plays");
        assertEquals(E1, c.update("OVERWATCH", false, over(E1), s -> true), "and plays on");
        c.ended(E1);
        assertEquals(-1, c.update("OVERWATCH", false, over(E1), s -> true), "once");
    }

    @Test
    void newMotionWhileAClipPlaysEndsItAndItIsNotTakenUpAgain() {
        StanagClips c = new StanagClips();
        c.update("OVERWATCH", false, running(E1), s -> false);
        assertEquals(E1, c.update("OVERWATCH", false, over(E1), s -> true));
        assertEquals(-1, c.update("OVERWATCH", false, running(E2), s -> s == E1), "a new detection: the live picture, the clip stops");
        assertEquals(-1, c.update("OVERWATCH", false, over(E2), s -> s == E1), "the stopped clip stays stopped (it is in CLIPS)");
        assertEquals(E2, c.update("OVERWATCH", false, over(E2), s -> true), "the new event's clip when it has come");
    }

    @Test
    void pilotingOrObservingTheDroneEndsTheClip() {
        StanagClips c = new StanagClips();
        c.update("OVERWATCH", false, running(E1), s -> false);
        assertEquals(E1, c.update("OVERWATCH", false, over(E1), s -> true));
        assertEquals(-1, c.update("OVERWATCH", true, over(E1), s -> true), "its video is here: that goes out");
        assertEquals(-1, c.update("OVERWATCH", false, over(E1), s -> true), "not again after");
    }

    @Test
    void aChangeOfModeEndsTheClipAndForgetsTheOnesWaiting() {
        StanagClips c = new StanagClips();
        c.update("OVERWATCH", false, running(E1), s -> false);
        c.update("OVERWATCH", false, running(E2), s -> false);
        assertEquals(E1, c.update("OVERWATCH", false, over(E2), s -> true), "the oldest first");
        assertEquals(-1, c.update("STANDBY", false, over(E2), s -> s == E1), "STANDBY from FLEET: its page, the clip stops");
        assertEquals(-1, c.update("ASLEEP", false, null, s -> true), "DEEP SLEEP: nothing of the watch that is over");
        assertEquals(-1, c.update("OVERWATCH", false, null, s -> true), "OVERWATCH again: the old events are not played");
        assertEquals(-1, c.update("OVERWATCH", false, over(E2), s -> true), "nor when the hub still names the last one");
    }

    @Test
    void severalClipsPlayOldestFirstEachOnce() {
        StanagClips c = new StanagClips();
        c.update("OVERWATCH", false, running(E1), s -> false);
        c.update("OVERWATCH", false, running(E2), s -> false);
        assertEquals(E1, c.update("OVERWATCH", false, over(E2), s -> true));
        c.ended(E1);
        assertEquals(E2, c.update("OVERWATCH", false, over(E2), s -> true));
        c.ended(E2);
        assertEquals(-1, c.update("OVERWATCH", false, over(E2), s -> true));
    }

    @Test
    void aDismissedAlertsClipNeverPlaysAndOneDismissedWhilePlayingStops() {
        StanagClips c = new StanagClips();
        c.update("OVERWATCH", false, running(E1), s -> false);
        c.update("OVERWATCH", false, new StanagClips.Alert(E1, false, true), s -> true);
        assertEquals(-1, c.update("OVERWATCH", false, over(E1), s -> true), "DISMISS: the pilot has seen it");
        StanagClips d = new StanagClips();
        d.update("OVERWATCH", false, running(E1), s -> false);
        assertEquals(E1, d.update("OVERWATCH", false, over(E1), s -> true));
        assertEquals(-1, d.update("OVERWATCH", false, new StanagClips.Alert(E1, false, true), s -> true), "its badge closed meanwhile");
    }

    @Test
    void aClipRemovedOrDeletedWhileItPlaysStops() {
        StanagClips c = new StanagClips();
        c.update("OVERWATCH", false, running(E1), s -> false);
        assertEquals(E1, c.update("OVERWATCH", false, over(E1), s -> true));
        assertEquals(-1, c.update("OVERWATCH", false, over(E1), s -> false), "REMOVE in CLIPS");
        assertEquals(-1, c.update("OVERWATCH", false, over(E1), s -> true));
    }

    @Test
    void onlyEventsSeenThisRunArePlayed() {
        StanagClips c = new StanagClips();
        assertEquals(-1, c.update("OVERWATCH", false, null, s -> true), "clips on the disk from before: none of them by itself");
        assertEquals(-1, c.update("OVERWATCH", false, over(E1), s -> true), "nor of an alert that was over before the streams were on");
        assertEquals(-1, c.update("OVERWATCH", false, over(E1), s -> true));
    }
}
