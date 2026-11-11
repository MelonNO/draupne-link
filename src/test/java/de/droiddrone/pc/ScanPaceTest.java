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

    @Test
    void windowHellos() {
        assertTrue(SleepCheck.helloDue(false, false, 4000, 1500), "the burst: every 1.5 s");
        assertFalse(SleepCheck.helloDue(false, false, 6000, 1500), "then 5 s apart");
        assertTrue(SleepCheck.helloDue(false, false, 9000, 5000));
        assertFalse(SleepCheck.helloDue(true, false, 30_000, 25_000), "listening: a minute");
        assertTrue(SleepCheck.helloDue(true, false, 70_000, 60_000));
        assertFalse(SleepCheck.helloDue(true, true, 70_000, 60_000), "listening in deep sleep: the alarm's");
        assertTrue(SleepCheck.helloDue(true, true, 3000, 1500), "but the burst as ever");
    }
}
