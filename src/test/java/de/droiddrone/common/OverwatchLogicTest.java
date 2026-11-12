package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The phone's overwatch rules, pure (1.0.47 / pc.36): the pre-roll, the battery and heat floor, one event's life. */
class OverwatchLogicTest {

    // ------------------------------------------------------------------ the pre-roll

    @Test
    void thePreRollKeepsTheLastFiveSecondsTwoASecond() {
        PreRoll r = new PreRoll();
        long t = 1_000_000;
        int added = 0;
        for (long now = t; now < t + 20_000; now += 125) { // the detector's 8 pictures a second
            if (r.due(now)) {
                r.add(now, new byte[]{(byte) added++});
            }
        }
        assertEquals(40, added, "two a second over 20 s");
        long end = t + 20_000;
        List<PreRoll.Frame> frames = r.snapshot(end);
        assertTrue(frames.size() >= 10 && frames.size() <= 11, "about 5 s of them: " + frames.size());
        for (PreRoll.Frame f : frames) assertTrue(end - f.atMs <= PreRoll.KEEP_MS);
        for (int i = 1; i < frames.size(); i++) assertTrue(frames.get(i).atMs > frames.get(i - 1).atMs, "oldest first");
    }

    @Test
    void theFirstPictureOfADetectionIsTheLastBeforeTheAlert() {
        PreRoll r = new PreRoll();
        long t = 1_000_000;
        int added = 0;
        long lastAt = 0;
        // 8 pictures a second; something found at 1250 ms (between two pre-roll pictures), still seen after that
        for (long now = t; now < t + 3_000; now += 125) {
            boolean detected = now >= t + 1_250;
            if (r.due(now, detected)) {
                r.add(now, new byte[]{(byte) added++});
                lastAt = now;
            }
            if (now == t + 1_250) assertEquals(now, lastAt, "the picture that found it is in");
        }
        assertEquals(6, added, "pc.50: one a second while quiet, the one that found it, then two a second while it stays found");
        // a detection flickering on and off every picture adds one picture at most every FIRST_MIN_MS
        r.clear();
        added = 0;
        for (long now = t; now < t + 1_000; now += 50) if (r.due(now, (now / 50) % 2 == 0)) r.add(now, new byte[]{(byte) added++});
        assertTrue(added <= 1_000 / PreRoll.FIRST_MIN_MS, "added " + added);
    }

    @Test
    void thePreRollNeverHoldsMoreThanItsBytes() {
        PreRoll r = new PreRoll();
        for (int i = 0; i < 10; i++) r.add(i * 500L, new byte[60 * 1024]);
        int bytes = 0;
        for (PreRoll.Frame f : r.snapshot(4500)) bytes += f.jpeg.length;
        assertTrue(bytes <= PreRoll.MAX_BYTES, "bytes " + bytes);
        assertEquals(4, r.size());
        r.clear();
        assertEquals(0, r.size());
        assertTrue(r.due(0));
    }

    // ------------------------------------------------------------------ the floor

    @Test
    void aLowBatteryEndsTheWatchOnlyOnTheSecondReadingAndNotWhileCharging() {
        PowerFloor f = new PowerFloor();
        assertEquals(PowerFloor.REASON_NONE, f.check(19, false, 0, 300));
        assertEquals(PowerFloor.REASON_BATTERY, f.check(19, false, 0, 300));
        f.reset();
        assertEquals(PowerFloor.REASON_NONE, f.check(15, true, 0, 300));
        assertEquals(PowerFloor.REASON_NONE, f.check(15, true, 0, 300), "charging: it watches on");
        assertEquals(PowerFloor.REASON_NONE, f.check(20, false, 0, 300), "20 % is not under 20 %");
        assertEquals(PowerFloor.REASON_NONE, f.check(20, false, 0, 300));
        assertEquals(PowerFloor.REASON_NONE, f.check(PowerFloor.UNKNOWN, false, 0, 300), "unknown is not low");
    }

    @Test
    void aGlitchOfOneReadingIsForgotten() {
        PowerFloor f = new PowerFloor();
        assertEquals(PowerFloor.REASON_NONE, f.check(5, false, 0, 300));
        assertEquals(PowerFloor.REASON_NONE, f.check(60, false, 0, 300));
        assertEquals(PowerFloor.REASON_NONE, f.check(5, false, 0, 300), "the count started over");
    }

    @Test
    void heatIsTheThermalStatusWhereKnownElseTheBatterysTemperature() {
        PowerFloor f = new PowerFloor();
        f.check(80, false, PowerFloor.THERMAL_SEVERE, 300);
        assertEquals(PowerFloor.REASON_HEAT, f.check(80, false, PowerFloor.THERMAL_SEVERE, 300));
        f.reset();
        f.check(80, false, 2, 500);
        assertEquals(PowerFloor.REASON_NONE, f.check(80, false, 2, 500), "MODERATE with a hot battery: the status wins where known");
        f.reset();
        f.check(80, false, PowerFloor.UNKNOWN, PowerFloor.HOT_DECI_C);
        assertEquals(PowerFloor.REASON_HEAT, f.check(80, false, PowerFloor.UNKNOWN, PowerFloor.HOT_DECI_C), "before Android 10: 45 C");
        f.reset();
        f.check(10, false, 4, 300);
        assertEquals(PowerFloor.REASON_HEAT, f.check(10, false, 4, 300), "hot and low: heat is said");
    }

    // ------------------------------------------------------------------ one event

    @Test
    void anEventStartsAtTheFirstDetectionAndEndsThirtySecondsAfterTheLast() {
        OverwatchEvent e = new OverwatchEvent();
        assertEquals(OverwatchEvent.Action.START, e.onDetection(1000));
        int first = e.event();
        assertEquals(OverwatchEvent.Action.NONE, e.onDetection(5000));
        assertEquals(OverwatchEvent.Action.NONE, e.tick(34_999), "29.999 s quiet");
        assertEquals(OverwatchEvent.Action.ENDED, e.tick(35_000));
        assertFalse(e.isActive());
        assertEquals(OverwatchEvent.Action.NONE, e.tick(40_000), "ended once");
        assertEquals(OverwatchEvent.Action.START, e.onDetection(50_000), "the next is a new event");
        assertNotEquals(first, e.event());
    }

    @Test
    void aDismissSilencesUntilTheEndButNeverLongerThanTenMinutes() {
        OverwatchEvent e = new OverwatchEvent();
        e.onDetection(0);
        e.dismiss(1000);
        assertTrue(e.isSilenced(1000));
        long t = 1000;
        for (; t < 1000 + OverwatchEvent.DISMISS_CAP_MS; t += 1000) { // the same thing, moving on without a pause
            assertEquals(OverwatchEvent.Action.NONE, e.onDetection(t, 0.5f, 0.5f), "still moving, still silent at " + t);
        }
        assertEquals(OverwatchEvent.Action.ALERT_AGAIN, e.onDetection(t, 0.5f, 0.5f), "10 min after the dismiss it alerts again");
        assertEquals(1, e.alertNo());
        assertFalse(e.isSilenced(t));
    }

    /**
     * pc.50, the pilot: "new motion can alert again" - a dismissed event hid whatever came next for up to 10 min.
     * New is motion after 5 s of none, or far from where the last was; not within 10 s of the dismiss.
     */
    @Test
    void newMotionAfterADismissAlertsAgain() {
        OverwatchEvent e = new OverwatchEvent();
        e.onDetection(0, 0.2f, 0.5f);
        e.dismiss(1000);
        assertEquals(OverwatchEvent.Action.NONE, e.onDetection(2000, 0.9f, 0.5f), "right after the dismiss: nothing, wherever it is");
        long t = 3000;
        for (; t < 20_000; t += 500) assertEquals(OverwatchEvent.Action.NONE, e.onDetection(t, 0.2f + t / 200_000f, 0.5f), "the dismissed one walking on");
        assertEquals(OverwatchEvent.Action.ALERT_AGAIN, e.onDetection(t, 0.85f, 0.4f), "something else, far from it");
        assertEquals(1, e.alertNo());
        e.dismiss(t);
        t += 12_000; // quiet for 12 s (the event still runs: under 30 s)
        assertEquals(OverwatchEvent.Action.ALERT_AGAIN, e.onDetection(t, 0.85f, 0.4f), "motion again after a pause");
        assertEquals(2, e.alertNo());
    }

    @Test
    void aDismissedEventThatEndsLeavesNoSilenceBehind() {
        OverwatchEvent e = new OverwatchEvent();
        e.onDetection(0);
        e.dismiss(100);
        assertEquals(OverwatchEvent.Action.ENDED, e.tick(30_000));
        assertFalse(e.isSilenced(30_000));
        assertEquals(OverwatchEvent.Action.START, e.onDetection(40_000), "a new event alerts at once");
        assertFalse(e.isSilenced(40_000));
    }

    /**
     * pc.50: an event ends with its clip. At the cap it only stopped its clip and went on: a scene that kept moving
     * (wind, an animal - for hours) rang once at its start, recorded 15 min, and a person arriving later got no ring
     * and no clip. Now what still moves is a new event: its own alert, pictures before it and clip.
     */
    @Test
    void aLongEventEndsWithItsClipAndWhatStillMovesIsANewEvent() {
        OverwatchEvent e = new OverwatchEvent();
        assertEquals(OverwatchEvent.Action.START, e.onDetection(0, 0.5f, 0.5f));
        int first = e.event();
        long t = 1000;
        for (; t < OverwatchEvent.MAX_CLIP_MS; t += 1000) { // the same thing, moving on without a pause
            assertEquals(OverwatchEvent.Action.NONE, e.onDetection(t, 0.5f, 0.5f), "at " + t);
            assertEquals(OverwatchEvent.Action.NONE, e.tick(t), "at " + t);
        }
        assertEquals(OverwatchEvent.Action.NONE, e.onDetection(t, 0.9f, 0.1f), "no ring from the old event at its very end");
        assertEquals(OverwatchEvent.Action.ENDED, e.tick(t), "15 min: the event ends with its clip");
        assertFalse(e.isActive());
        assertEquals(OverwatchEvent.Action.NONE, e.tick(t + 100), "ended once");
        assertEquals(OverwatchEvent.Action.START, e.onDetection(t + 125, 0.9f, 0.1f), "the next detection: a new event - alert, pictures, clip");
        assertNotEquals(first, e.event());
        assertEquals(0, e.alertNo());
        assertEquals(OverwatchEvent.Action.NONE, e.tick(t + 225), "and that one has its own 15 min");
    }

    /**
     * pc.50: new motion alerts again in an event nobody dismissed too (an acknowledged alert rang once and never
     * again while anything kept the event alive) - an event rings once a minute at most, its start counted.
     */
    @Test
    void newMotionAlertsAgainInAnEventNobodyDismissedOnceAMinuteAtMost() {
        OverwatchEvent e = new OverwatchEvent();
        e.onDetection(0, 0.2f, 0.5f);
        assertEquals(OverwatchEvent.Action.NONE, e.onDetection(2000, 0.9f, 0.5f), "something else, far - but the alert is 2 s old");
        long t = 2500;
        for (; t < OverwatchEvent.AGAIN_EVERY_MS; t += 500) assertEquals(OverwatchEvent.Action.NONE, e.onDetection(t, 0.9f, 0.5f), "at " + t);
        assertEquals(OverwatchEvent.Action.ALERT_AGAIN, e.onDetection(t, 0.9f, 0.5f), "it is remembered, and rings a minute after the first, still moving");
        assertEquals(1, e.alertNo());
        long rang = t;
        for (t += 500; t < rang + 20_000; t += 500) assertEquals(OverwatchEvent.Action.NONE, e.onDetection(t, 0.9f, 0.5f), "moving on: nothing new");
        assertEquals(OverwatchEvent.Action.NONE, e.onDetection(t, 0.2f, 0.4f), "new again, but a ring went 20 s ago");
        for (t += 500; t < rang + OverwatchEvent.AGAIN_EVERY_MS; t += 500) {
            assertEquals(OverwatchEvent.Action.NONE, e.onDetection(t, 0.2f, 0.4f), "one a minute at most, at " + t);
        }
        assertEquals(OverwatchEvent.Action.ALERT_AGAIN, e.onDetection(t, 0.2f, 0.4f), "the minute over and still moving: it rings");
        assertEquals(2, e.alertNo());
        assertEquals(OverwatchEvent.Action.NONE, e.onDetection(t + 500, 0.2f, 0.4f), "once");
    }

    @Test
    void theSameThingMovingOnRingsOnceAndAPauseMakesItNew() {
        OverwatchEvent e = new OverwatchEvent();
        e.onDetection(0, 0.1f, 0.5f);
        long t = 125;
        for (; t < 5 * 60_000; t += 125) { // walking slowly across, 8 pictures a second
            assertEquals(OverwatchEvent.Action.NONE, e.onDetection(t, 0.1f + t / 1_000_000f, 0.5f), "at " + t);
            assertEquals(OverwatchEvent.Action.NONE, e.tick(t));
        }
        t += OverwatchEvent.NEW_AFTER_QUIET_MS; // it stood still for 5 s (the event still runs: under 30 s)
        assertEquals(OverwatchEvent.Action.ALERT_AGAIN, e.onDetection(t, 0.4f, 0.5f), "motion after a pause is new");
        e.dismiss(t + 1000);
        assertEquals(OverwatchEvent.Action.NONE, e.onDetection(t + 8000, 0.9f, 0.5f), "dismissed: the dismiss's own rule (not within 10 s of it)");
    }

    /** pc.50: a DISMISS is never answered with a ring at once - a dismissed event at its clip's cap waits for its silence to end. */
    @Test
    void aDismissedEventPastItsClipsCapEndsWhenItsSilenceDoes() {
        OverwatchEvent e = new OverwatchEvent();
        e.onDetection(0, 0.5f, 0.5f);
        long dismissed = OverwatchEvent.MAX_CLIP_MS - 60_000; // a minute before the cap
        long t = 1000;
        for (; t < dismissed; t += 1000) e.onDetection(t, 0.5f, 0.5f);
        e.dismiss(dismissed);
        for (; t < dismissed + OverwatchEvent.DISMISS_CAP_MS; t += 1000) {
            assertEquals(OverwatchEvent.Action.NONE, e.onDetection(t, 0.5f, 0.5f), "at " + t);
            assertEquals(OverwatchEvent.Action.NONE, e.tick(t), "silent, and going on past the cap, at " + t);
        }
        assertEquals(OverwatchEvent.Action.NONE, e.onDetection(t, 0.5f, 0.5f), "10 min after the dismiss, past the cap: the old event doesn't ring");
        assertEquals(OverwatchEvent.Action.ENDED, e.tick(t), "it ends");
        assertEquals(OverwatchEvent.Action.START, e.onDetection(t, 0.5f, 0.5f), "and the ring is the new event's");

        // new motion ends the silence as before the cap - and past the cap that is the event's end
        OverwatchEvent f = new OverwatchEvent();
        f.onDetection(0, 0.5f, 0.5f);
        for (t = 1000; t < OverwatchEvent.MAX_CLIP_MS - 5000; t += 1000) f.onDetection(t, 0.5f, 0.5f);
        long d = t;
        f.dismiss(d); // 5 s before the cap
        for (; t < d + 20_000; t += 1000) {
            assertEquals(OverwatchEvent.Action.NONE, f.onDetection(t, 0.5f, 0.5f));
            assertEquals(OverwatchEvent.Action.NONE, f.tick(t), "no ring 5 s after the dismiss, at " + t);
        }
        assertEquals(OverwatchEvent.Action.NONE, f.onDetection(t, 0.9f, 0.1f), "someone else, far from it");
        assertEquals(OverwatchEvent.Action.ENDED, f.tick(t));
        assertEquals(OverwatchEvent.Action.START, f.onDetection(t, 0.9f, 0.1f));

        // dismissed again and again (several PCs), it still ends 10 min past the cap
        OverwatchEvent g = new OverwatchEvent();
        g.onDetection(0);
        long cap = OverwatchEvent.MAX_CLIP_MS, more = OverwatchEvent.DISMISS_CAP_MS;
        for (t = 20_000; t < cap + more; t += 20_000) {
            g.onDetection(t);
            g.dismiss(t);
            assertEquals(OverwatchEvent.Action.NONE, g.tick(t), "at " + t);
        }
        assertTrue(g.isSilenced(cap + more - 1));
        assertEquals(OverwatchEvent.Action.NONE, g.tick(cap + more - 1));
        assertEquals(OverwatchEvent.Action.ENDED, g.tick(cap + more), "silenced or not");
    }

    /**
     * pc.50: the alert and the motion's end went to every PC until ALL had them - one PC switched off kept the phone
     * sending through the event and 30 min after it. Once any PC has it, the others are told a minute more.
     */
    @Test
    void aPcThatNeverAnswersIsNotCalledOnOnceAnotherHasIt() {
        assertTrue(OverwatchEvent.resendOwed(false, false, 20 * 60_000), "nobody has it: it goes on");
        assertTrue(OverwatchEvent.resendOwed(false, true, OverwatchEvent.STRAGGLER_MS - 1), "one has it: the others a minute more");
        assertFalse(OverwatchEvent.resendOwed(false, true, OverwatchEvent.STRAGGLER_MS), "then it rests for them");
        assertFalse(OverwatchEvent.resendOwed(true, true, 0), "all have it");
        assertFalse(OverwatchEvent.resendOwed(true, false, 0), "nobody to tell");
    }

    /**
     * pc.50: the pictures before an alert count by a clock that never steps; each carries its wall time for the PC. By
     * the wall clock a correction backwards stopped them for as long as it stepped (one forwards threw them all away).
     */
    @Test
    void aStepOfThePhonesClockLosesNoPictureBeforeTheAlert() {
        PreRoll r = new PreRoll();
        long mono = 5_000_000, wall = 1_700_000_000_000L, lastWall = 0;
        int added = 0;
        for (int i = 0; i < 160; i++, mono += 125, wall += 125) { // 20 s at 8 pictures a second, something moving
            if (i == 80) wall -= 3_600_000; // the network sets the phone's clock back an hour
            if (r.due(mono, true)) {
                r.add(mono, wall, new byte[]{(byte) added++});
                lastWall = wall;
            }
        }
        assertEquals(40, added, "two a second all along");
        List<PreRoll.Frame> frames = r.snapshot(mono);
        assertEquals(10, frames.size(), "the last 5 s of them");
        assertEquals(lastWall, frames.get(frames.size() - 1).atMs, "each with its wall time, as the PC is told");
        assertEquals(500, frames.get(1).atMs - frames.get(0).atMs);
    }

    @Test
    void eventNumbersGoOnFromTheLastAndSkipZero() {
        OverwatchEvent e = new OverwatchEvent();
        e.continueFrom(65535);
        e.onDetection(0);
        assertEquals(1, e.event());
        e.abandon();
        assertFalse(e.isActive());
    }
}
