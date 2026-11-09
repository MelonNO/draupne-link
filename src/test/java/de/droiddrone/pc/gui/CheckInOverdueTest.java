package de.droiddrone.pc.gui;

import de.droiddrone.common.SleepCheck;
import de.droiddrone.pc.ControlLink;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When a sleeping drone counts as UNKNOWN on the map: a whole check-in missed and a minute more. CHECK EVERY "0 min"
 * (1.0.50): always listening says hello once a minute, so it is held to a 1 min check - a silent one is UNKNOWN
 * three minutes after it was last heard, not 255 minutes.
 */
class CheckInOverdueTest {
    private static final long T0 = 1_800_000_000_000L;
    private static final long MIN = 60_000L;

    private static ControlLink.SleepHello hello(int nextCheckMin, long at) {
        return new ControlLink.SleepHello(80, false, nextCheckMin, 3900, at);
    }

    @Test
    void alwaysListeningIsLateAsAOneMinuteCheck() {
        PowerController.Asleep a = new PowerController.Asleep("MobileZulu", "100.64.0.5", T0, SleepCheck.ALWAYS, true);
        assertTrue(a.listening());
        long heard = T0 + 10 * MIN;
        ControlLink.SleepHello h = hello(SleepCheck.ALWAYS, heard);
        assertTrue(PowerController.checkInOverdue(a, h, T0, heard + 2 * MIN) <= 0, "two minutes quiet: on time still");
        assertTrue(PowerController.checkInOverdue(a, h, T0, heard + 3 * MIN + 1000) > 0, "three minutes quiet: UNKNOWN");
        // exactly as a 1 min interval is
        PowerController.Asleep one = new PowerController.Asleep("MobileZulu", "100.64.0.5", T0, 1, true);
        assertEquals(PowerController.checkInOverdue(one, hello(1, heard), T0, heard + 5 * MIN),
                PowerController.checkInOverdue(a, h, T0, heard + 5 * MIN));
    }

    @Test
    void alwaysListeningNotYetHeardIsLateAsAOneMinuteCheckFromItsSleep() {
        PowerController.Asleep a = new PowerController.Asleep("MobileZulu", "100.64.0.5", T0, SleepCheck.ALWAYS, false);
        assertTrue(PowerController.checkInOverdue(a, null, T0 - 60 * MIN, T0 + 2 * MIN) <= 0);
        assertTrue(PowerController.checkInOverdue(a, null, T0 - 60 * MIN, T0 + 3 * MIN + 1000) > 0);
    }

    @Test
    void timedChecksAreUnchanged() {
        PowerController.Asleep a = new PowerController.Asleep("MobileZulu", "100.64.0.5", T0, 5, false);
        assertFalse(a.listening());
        long heard = T0 + 5 * MIN;
        ControlLink.SleepHello h = hello(5, heard);
        assertTrue(PowerController.checkInOverdue(a, h, T0, heard + 11 * MIN - 1000) <= 0, "the next check, one interval of grace, a minute");
        assertTrue(PowerController.checkInOverdue(a, h, T0, heard + 11 * MIN + 1000) > 0);
    }
}
