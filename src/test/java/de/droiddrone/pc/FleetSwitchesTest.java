package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet PRD E4 / FA4: DISCARD LEAD and CALL RESERVE as radio switches, a flip up = one press. */
class FleetSwitchesTest {
    private static short[] frame(int discard, int reserve) {
        short[] f = new short[12];
        java.util.Arrays.fill(f, (short) 1500);
        f[3 + 5] = (short) discard; // AUX5
        f[3 + 6] = (short) reserve; // AUX6
        return f;
    }

    @Test
    void aFlipUpAfterRestingDownIsOnePress() {
        FleetSwitches s = new FleetSwitches(5, 6);
        long t = 0;
        assertEquals(FleetSwitches.NONE, s.onFrame(frame(1000, 1000), 12, t));
        assertEquals(FleetSwitches.NONE, s.onFrame(frame(1000, 1000), 12, t += 400));
        assertEquals(FleetSwitches.DISCARD_LEAD, s.onFrame(frame(2000, 1000), 12, t += 10));
        assertEquals(FleetSwitches.NONE, s.onFrame(frame(2000, 1000), 12, t += 10), "held up: once");
        assertEquals(FleetSwitches.CALL_RESERVE, s.onFrame(frame(2000, 2000), 12, t += 10));
    }

    @Test
    void aSwitchThatStartsUpOrBouncesNeverFires() {
        FleetSwitches s = new FleetSwitches(5, 6);
        long t = 0;
        assertEquals(FleetSwitches.NONE, s.onFrame(frame(2000, 2000), 12, t), "up at the start: not a press");
        assertEquals(FleetSwitches.NONE, s.onFrame(frame(1000, 1000), 12, t += 100));
        assertEquals(FleetSwitches.NONE, s.onFrame(frame(2000, 2000), 12, t += 100), "down only 100 ms: a bounce");
        for (int i = 0; i < 20; i++) assertEquals(FleetSwitches.NONE, s.onFrame(frame(1500 + (i % 2) * 150, 1500), 12, t += 50), "jitter round the middle");
    }

    @Test
    void pressesTooCloseTogetherCountOnce() {
        FleetSwitches s = new FleetSwitches(5, 0);
        long t = 0;
        s.onFrame(frame(1000, 1000), 12, t);
        assertEquals(FleetSwitches.DISCARD_LEAD, s.onFrame(frame(2000, 1000), 12, t += 400));
        s.onFrame(frame(1000, 1000), 12, t += 10);
        assertEquals(FleetSwitches.NONE, s.onFrame(frame(2000, 1000), 12, t += 400), "within a second of the last");
        s.onFrame(frame(1000, 1000), 12, t += 10);
        assertEquals(FleetSwitches.DISCARD_LEAD, s.onFrame(frame(2000, 2000), 12, t += 700), "CALL RESERVE not on the radio here");
        assertEquals(FleetSwitches.NONE, new FleetSwitches(0, 0).onFrame(frame(2000, 2000), 12, 0));
        assertEquals(FleetSwitches.NONE, new FleetSwitches(5, 6).onFrame(new short[6], 6, 0), "a short frame");
        assertEquals(5, FleetSwitches.aux("x", 5));
        assertEquals(7, FleetSwitches.aux(" 7 ", 5));
    }
}
