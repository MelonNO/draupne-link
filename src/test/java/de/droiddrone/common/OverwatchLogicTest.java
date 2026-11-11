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
        for (; t < 1000 + OverwatchEvent.DISMISS_CAP_MS; t += 10_000) {
            assertEquals(OverwatchEvent.Action.NONE, e.onDetection(t), "still moving, still silent at " + t);
        }
        assertEquals(OverwatchEvent.Action.ALERT_AGAIN, e.onDetection(t), "10 min after the dismiss it alerts again");
        assertEquals(1, e.alertNo());
        assertFalse(e.isSilenced(t));
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

    @Test
    void aLongEventStopsItsClipAtTheCapButGoesOn() {
        OverwatchEvent e = new OverwatchEvent();
        e.onDetection(0);
        OverwatchEvent.Action stop = OverwatchEvent.Action.NONE;
        long t = 0;
        for (; t <= OverwatchEvent.MAX_CLIP_MS + 1000; t += 1000) {
            e.onDetection(t);
            OverwatchEvent.Action a = e.tick(t);
            if (a == OverwatchEvent.Action.STOP_CLIP) {
                assertEquals(OverwatchEvent.Action.NONE, stop, "once");
                stop = a;
            }
        }
        assertEquals(OverwatchEvent.Action.STOP_CLIP, stop);
        assertTrue(e.isActive());
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
