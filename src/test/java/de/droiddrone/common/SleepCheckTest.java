package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** CHECK EVERY "0 min" (1.0.50, phone pc.39): the shared wire value, who may be sent it, and the packets that carry it. */
class SleepCheckTest {
    @Test
    void alwaysIsOneByteThatNoMinutesValueUses() {
        assertEquals(255, SleepCheck.ALWAYS);
        assertTrue(SleepCheck.isAlways(SleepCheck.ALWAYS));
        for (int m : new int[]{0, 1, 2, 5, 254}) assertFalse(SleepCheck.isAlways(m), m + " min");
        assertEquals("0 min", SleepCheck.label(SleepCheck.ALWAYS));
        assertEquals("5 min", SleepCheck.label(5));
    }

    @Test
    void onlyAPc39PhoneIsSentAlwaysAnOlderOrUnknownOneGetsOneMinute() {
        assertEquals(SleepCheck.ALWAYS, SleepCheck.forPhone(SleepCheck.ALWAYS, 39));
        assertEquals(SleepCheck.ALWAYS, SleepCheck.forPhone(SleepCheck.ALWAYS, 40));
        assertEquals(1, SleepCheck.forPhone(SleepCheck.ALWAYS, 38), "pc.38 would read 255 as 255 minutes");
        assertEquals(1, SleepCheck.forPhone(SleepCheck.ALWAYS, -1), "an app that hasn't said its version");
        assertEquals(5, SleepCheck.forPhone(5, 21), "minutes go as they are");
        assertEquals(2, SleepCheck.forPhone(2, 39));
    }

    @Test
    void alwaysCountsAsAOneMinuteCheckForTheLatenessRule() {
        assertEquals(1, SleepCheck.checkInMinutes(SleepCheck.ALWAYS));
        assertEquals(1, SleepCheck.checkInMinutes(0));
        assertEquals(5, SleepCheck.checkInMinutes(5));
        assertTrue(SleepCheck.ALWAYS_HELLO_MS <= 60_000, "a hello at least once a minute");
    }

    @Test
    void theOverwatchAndPowerModeRequestsCarryItAsTheirIntervalByte() {
        byte[] ow = OverwatchProtocol.request((byte) 0x6E, SleepCheck.ALWAYS, 2, 0, 0x01020304);
        assertEquals(0x6E, ow[0] & 0xff);
        assertEquals(OverwatchProtocol.MODE, ow[1] & 0xff);
        assertEquals(SleepCheck.ALWAYS, ow[2] & 0xff);
        assertEquals(1, OverwatchProtocol.request((byte) 0x6E, SleepCheck.forPhone(SleepCheck.ALWAYS, 36), 2, 0, 1)[2] & 0xff,
                "a pc.36 phone in overwatch: 1 min");
    }
}
