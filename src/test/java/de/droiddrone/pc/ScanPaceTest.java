package de.droiddrone.pc;

import de.droiddrone.common.SleepCheck;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.64: every PC asked every Tailscale device who it is every 5 s - a sleeping phone's radio woke for each. Resting
 * and silent ones are asked once a minute; a resting one stays online for its own check-in interval plus 2 min, at
 * least 5 min (the pilot: "at least 5 as the wake check can be set to 5").
 */
class ScanPaceTest {
    private static final String PHONE = "100.64.0.5", PC = "100.64.0.9";

    @Test
    void anAwakeDeviceIsAskedEveryScanARestingOneOnceAMinute() {
        ScanPace p = new ScanPace();
        assertTrue(p.due(PHONE, 0), "never asked");
        p.asked(PHONE, 0);
        p.answered(PHONE, 100, 0, 5, "MobileAlpha"); // normal
        assertTrue(p.due(PHONE, 5000), "awake: every scan");
        p.asked(PHONE, 5000);
        p.answered(PHONE, 5100, ScanPace.STANDBY, 0, "MobileAlpha");
        assertFalse(p.due(PHONE, 10_000), "standby: once a minute");
        assertTrue(p.due(PHONE, 65_000));
        assertTrue(p.mayHurry(PHONE), "standby: FLEET's waiting mode may have it asked at once");
        assertEquals("MobileAlpha", p.name(PHONE));
        p.asked(PC, 0);
        p.answered(PC, 50, Names.POWER_UNKNOWN, 0, "PCBravo");
        assertTrue(p.due(PC, 5000), "a PC: every scan");
    }

    /**
     * The pilot: "for deep sleep and overwatch phones no command from the PC's need to be faster than the check every
     * interval" - asked round its expected check-in, never faster than it checks in; FLEET waits for that too.
     */
    @Test
    void aSleeperIsAskedOnlyRoundItsCheckIn() {
        ScanPace p = new ScanPace();
        p.asked(PHONE, 0);
        p.answered(PHONE, 1000, ScanPace.DEEP_SLEEP, 5, "MobileAlpha"); // heard in its window at 1 s
        assertFalse(p.mayHurry(PHONE));
        assertFalse(p.due(PHONE, 60_000), "between check-ins: its socket is closed");
        assertFalse(p.due(PHONE, 1000 + 289_000));
        assertTrue(p.due(PHONE, 1000 + 290_000), "10 s before its next check-in");
        p.asked(PHONE, 1000 + 290_000);
        p.unanswered(PHONE);
        assertTrue(p.due(PHONE, 1000 + 330_000), "up to 30 s after it");
        assertFalse(p.due(PHONE, 1000 + 331_000));
        assertFalse(p.due(PHONE, 1000 + 500_000));
        assertTrue(p.due(PHONE, 1000 + 590_000), "the next one");
        p.answered(PHONE, 1000 + 595_000, ScanPace.OVERWATCH, SleepCheck.ALWAYS, "MobileAlpha");
        p.asked(PHONE, 1000 + 595_000);
        assertFalse(p.due(PHONE, 1000 + 640_000), "listening: once a minute");
        assertTrue(p.due(PHONE, 1000 + 655_000));
    }

    /** 1.0.64: waking a timed sleeper, the link's Connects go only round its expected check-in. */
    @Test
    void connectsWaitForTheCheckIn() {
        long next = 1_000_000;
        assertTrue(de.droiddrone.pc.gui.PowerControllerAccess.holdConnects(true, false, 5, next - 60_000, next), "a minute before: held");
        assertFalse(de.droiddrone.pc.gui.PowerControllerAccess.holdConnects(true, false, 5, next - 5000, next), "5 s before: Connects");
        assertFalse(de.droiddrone.pc.gui.PowerControllerAccess.holdConnects(true, false, 5, next - 300_000 + 30_000, next), "just after the last one: still");
        assertFalse(de.droiddrone.pc.gui.PowerControllerAccess.holdConnects(true, true, 5, next - 60_000, next), "listening: never held");
        assertFalse(de.droiddrone.pc.gui.PowerControllerAccess.holdConnects(false, false, 5, next - 60_000, next), "not waking it: dormant decides");
    }

    @Test
    void aRestingPhoneStaysOnlineForItsCheckIntervalAndAMargin() {
        assertEquals(5 * 60_000L, ScanPace.holdMs(1), "at least 5 min");
        assertEquals(5 * 60_000L, ScanPace.holdMs(SleepCheck.ALWAYS), "listening: hellos every minute - 5 min");
        assertEquals(7 * 60_000L, ScanPace.holdMs(5), "5 min checks: 7 min");
        assertEquals(32 * 60_000L, ScanPace.holdMs(30));
        assertEquals(5 * 60_000L, ScanPace.holdMs(0), "unknown");
        ScanPace p = new ScanPace();
        p.asked(PHONE, 0);
        p.answered(PHONE, 0, ScanPace.DEEP_SLEEP, 5, "MobileAlpha");
        for (long t = 60_000; t <= 6 * 60_000; t += 60_000) {
            p.asked(PHONE, t);
            p.unanswered(PHONE); // its socket closed between windows
        }
        assertEquals(Set.of(PHONE), p.held(6 * 60_000 + 59_000), "still online at 6:59");
        assertEquals(Set.of(PHONE), p.held(7 * 60_000));
        assertTrue(p.held(7 * 60_000 + 1).isEmpty(), "gone after its 7 min");
        p.answered(PHONE, 7 * 60_000 + 10_000, 0, 5, "MobileAlpha");
        assertTrue(p.held(7 * 60_000 + 20_000).isEmpty(), "awake again: held no more (it answers)");
    }

    @Test
    void aSilentAddressIsAskedOnceAMinuteAfterThreeScans() {
        ScanPace p = new ScanPace();
        for (int i = 0; i < ScanPace.SILENT_AFTER; i++) {
            assertTrue(p.due(PC, i * 5000L));
            p.asked(PC, i * 5000L);
            p.unanswered(PC);
        }
        assertFalse(p.due(PC, 15_000));
        assertTrue(p.due(PC, 10_000 + 60_000));
        assertTrue(p.held(20_000).isEmpty(), "silent, never resting: not held");
        p.retain(Set.of());
        assertTrue(p.due(PC, 20_000), "gone from the network: forgotten");
    }

    /**
     * 1.0.64: a listening sleeper says hello once a minute; this PC's own query, a minute after its last one and not
     * with the hello, woke the phone's radio a second time each minute. A hello heard is its answer.
     */
    @Test
    void aListeningSleepersHelloIsItsAnswer() {
        ScanPace p = new ScanPace();
        p.asked(PHONE, 0);
        p.answered(PHONE, 100, ScanPace.DEEP_SLEEP, SleepCheck.ALWAYS, "MobileAlpha");
        for (long t = 30_000; t < 600_000; t += 60_000) { // its hellos, every minute
            p.heard(PHONE, t, ScanPace.DEEP_SLEEP, 0);
            for (long scan = t + 5000; scan < t + 60_000; scan += 5000) assertFalse(p.due(PHONE, scan), "not asked while its hellos come: " + scan);
        }
        assertTrue(p.held(600_000).contains(PHONE), "and online by them");
        assertTrue(p.due(PHONE, 570_000 + ScanPace.HEARD_QUIET_MS), "its hello missing: asked");
        // a timed sleeper's hello marks its window
        p.asked(PC, 0);
        p.answered(PC, 1000, ScanPace.DEEP_SLEEP, 5, "MobileBravo");
        p.heard(PC, 301_000, ScanPace.DEEP_SLEEP, 0);
        assertFalse(p.due(PC, 400_000));
        assertTrue(p.due(PC, 301_000 + 292_000), "round the check-in after the one heard");
    }

    @Test
    void windowHellos() {
        assertTrue(SleepCheck.helloDue(false, false, 4000, 1500), "the burst: every 1.5 s");
        assertFalse(SleepCheck.helloDue(false, false, 6200, 1500), "then 3 s apart");
        assertTrue(SleepCheck.helloDue(false, false, 7700, 3000), "the fifth at 7.7 s (5 s apart, the 9.2 s one never went)");
        // a 10 s window as the phone's task runs it (every 1.5 s from 0.2 s): five hellos
        int sent = 0;
        long last = -1;
        for (long t = 200; t < 10_000; t += 1500) {
            if (SleepCheck.helloDue(false, false, t, last < 0 ? Long.MAX_VALUE : t - last)) {
                sent++;
                last = t;
            }
        }
        assertEquals(5, sent);
        assertEquals(7700, last);
        assertFalse(SleepCheck.helloDue(true, false, 30_000, 25_000), "listening: a minute");
        assertTrue(SleepCheck.helloDue(true, false, 70_000, 60_000));
        assertFalse(SleepCheck.helloDue(true, true, 70_000, 60_000), "listening in deep sleep: the alarm's");
        assertTrue(SleepCheck.helloDue(true, true, 3000, 1500), "but the burst as ever");
    }
}
