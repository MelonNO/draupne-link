package de.droiddrone.pc.fleet;

import de.droiddrone.common.ArmRanges;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The virtual drone's Betaflight: arming, the RX-loss failsafe, the battery - driven with made-up times. */
class FlightModelTest {
    private static final long T0 = 1_000_000;

    /** Sticks in Betaflight's order: roll, pitch, yaw, throttle, AUX1 (ARM), AUX2 (ANGLE), then AUX3-4 low. */
    private static short[] sticks(int throttle, int arm) {
        return new short[]{1500, 1500, 1500, (short) throttle, (short) arm, 1500, 1000, 1000};
    }

    /** Feeds a frame every 20 ms from..to and steps the model with it. */
    private static long fly(FlightModel m, long from, long to, int throttle, int arm) {
        long t = from;
        for (; t < to; t += 20) {
            m.sticks(sticks(throttle, arm), 8, t);
            m.step(t, 0.02);
        }
        return t;
    }

    /** No sticks from..to: only steps. */
    private static long silent(FlightModel m, long from, long to) {
        long t = from;
        for (; t < to; t += 20) m.step(t, 0.02);
        return t;
    }

    private static FlightModel model(FlightModel.Procedure p) {
        FlightModel.Setup s = new FlightModel.Setup();
        s.procedure = p;
        return new FlightModel(s, 52.0, 13.0, 0, T0);
    }

    @Test
    void armsOnlyWithTheSwitchGoingOnThrottleLowAndCleanSticks() {
        FlightModel m = model(FlightModel.Procedure.DROP);
        long t = fly(m, T0, T0 + 1000, 1000, 1000);
        t = fly(m, t, t + 200, 1000, 1900);
        assertFalse(m.isArmed(), "the boot grace (3 s) blocks arming");
        assertNotEquals(0, m.blockers(t) & FlightModel.FLAG_ARMSWITCH, "the switch was on while blocked: ARMSWITCH");
        t = fly(m, t, T0 + 4000, 1000, 1900);
        assertFalse(m.isArmed(), "still on since it was blocked: it must go off first");
        t = fly(m, t, t + 200, 1000, 1000);
        assertEquals(0, m.blockers(t), "nothing blocks now");
        t = fly(m, t, t + 100, 1000, 1900);
        assertTrue(m.isArmed(), "off, then on, throttle low: armed");
        assertEquals(1, m.arms());
        assertNotEquals(0, m.modeFlags(t) & (1 << FlightModel.BOX_ARM));

        // the switch off disarms
        t = fly(m, t, t + 100, 1000, 1000);
        assertFalse(m.isArmed());
        assertEquals("the arm switch", m.lastDisarm());

        // throttle up: refused, THROTTLE
        t = fly(m, t, t + 100, 1400, 1000);
        t = fly(m, t, t + 100, 1400, 1900);
        assertFalse(m.isArmed(), "throttle not low");
        assertNotEquals(0, m.blockers(t) & FlightModel.FLAG_THROTTLE);
    }

    @Test
    void sticksMustBeCleanForHalfASecondFirst() {
        FlightModel m = model(FlightModel.Procedure.DROP);
        long t = silent(m, T0, T0 + 4000); // past the boot grace, no sticks yet
        t = fly(m, t, t + 200, 1000, 1000);
        t = fly(m, t, t + 100, 1000, 1900);
        assertFalse(m.isArmed(), "300 ms of sticks: not clean long enough");
        t = fly(m, t, t + 100, 1000, 1000);
        t = fly(m, t, t + 400, 1000, 1000);
        fly(m, t, t + 100, 1000, 1900);
        assertTrue(m.isArmed());
    }

    @Test
    void rxLossHoldsThenStageOneThenDrops() {
        FlightModel m = model(FlightModel.Procedure.DROP);
        long t = fly(m, T0, T0 + 4000, 1000, 1000);
        t = fly(m, t, t + 100, 1000, 1900);
        assertTrue(m.isArmed());
        t = fly(m, t, t + 3000, 1550, 1900); // climbing
        assertTrue(m.altitudeM() > 1, "it flies: " + m.altitudeM());
        long lost = t;
        t = silent(m, t, lost + 150);
        assertEquals(FlightModel.Failsafe.HOLD, m.failsafe(), "RXLOSS: the last sticks held");
        assertNotEquals(0, m.blockers(t) & FlightModel.FLAG_RXLOSS);
        t = silent(m, t, lost + 500);
        assertEquals(FlightModel.Failsafe.STAGE1, m.failsafe(), "stage 1 after 100 + 300 ms");
        assertNotEquals(0, m.modeFlags(t) & (1 << FlightModel.BOX_FAILSAFE), "the FAILSAFE mode shows");
        assertTrue(m.isArmed(), "stage 1 still flies (centred, throttle low)");
        t = silent(m, t, lost + 2100);
        assertFalse(m.isArmed(), "stage 2, DROP: disarmed");
        assertEquals("failsafe (drop)", m.lastDisarm());
        t = silent(m, t, t + 5000);
        assertEquals(0, m.altitudeM(), 1e-9, "it fell");

        // the sticks come back, the switch still on: no re-arm until it goes off and on
        t = fly(m, t, t + 1000, 1000, 1900);
        assertFalse(m.isArmed());
        t = fly(m, t, t + 100, 1000, 1000);
        fly(m, t, t + 100, 1000, 1900);
        assertTrue(m.isArmed(), "off and on again: armed");
    }

    @Test
    void sticksBackDuringStageOneEndTheFailsafe() {
        FlightModel m = model(FlightModel.Procedure.DROP);
        long t = fly(m, T0, T0 + 4000, 1000, 1000);
        t = fly(m, t, t + 100, 1000, 1900);
        t = fly(m, t, t + 2000, 1500, 1900);
        t = silent(m, t, t + 800);
        assertEquals(FlightModel.Failsafe.STAGE1, m.failsafe());
        fly(m, t, t + 100, 1500, 1900);
        assertEquals(FlightModel.Failsafe.NONE, m.failsafe());
        assertTrue(m.isArmed(), "back in time: it flies on");
    }

    @Test
    void landFailsafeDescendsAndDisarmsOnTheGround() {
        FlightModel m = model(FlightModel.Procedure.LAND);
        long t = fly(m, T0, T0 + 4000, 1000, 1000);
        t = fly(m, t, t + 100, 1000, 1900);
        t = fly(m, t, t + 4000, 1550, 1900);
        double high = m.altitudeM();
        assertTrue(high > 3, "up: " + high);
        t = silent(m, t, t + 2500);
        assertEquals(FlightModel.Failsafe.LANDING, m.failsafe());
        assertTrue(m.isArmed(), "landing, motors on");
        silent(m, t, t + 60_000);
        assertFalse(m.isArmed(), "on the ground: disarmed");
        assertEquals("failsafe (landed)", m.lastDisarm());
        assertFalse(m.crashed(), "a landing, not a crash");
    }

    @Test
    void theBatteryDrainsFasterUnderLoad() {
        FlightModel idle = model(FlightModel.Procedure.DROP);
        silent(idle, T0, T0 + 60_000);
        FlightModel flying = model(FlightModel.Procedure.DROP);
        long t = fly(flying, T0, T0 + 4000, 1000, 1000);
        t = fly(flying, t, t + 100, 1000, 1900);
        fly(flying, t, T0 + 60_000, 1450, 1900);
        assertTrue(idle.mahDrawn() < 10, "disarmed: a trickle, " + idle.mahDrawn());
        assertTrue(flying.mahDrawn() > 100, "a minute of hovering draws hundreds of mAh: " + flying.mahDrawn());
        assertTrue(flying.volts() < idle.volts() - 0.3, "and the voltage sags: " + flying.volts() + " vs " + idle.volts());
        assertTrue(idle.volts() > 16 && idle.volts() < 17.2, "a full 4S at rest: " + idle.volts());
        flying.newBattery();
        assertTrue(flying.mahDrawn() > 0, "no new pack while armed");
    }

    @Test
    void itsModeRangesAreWhatThePhonesIdleCheckReads() {
        FlightModel m = model(FlightModel.Procedure.DROP);
        int[][] arm = ArmRanges.parse(m.modeRanges());
        assertEquals(1, arm.length);
        assertArrayEquals(new int[]{0, 1700, 2100}, arm[0], "ARM on AUX1, 1700-2100");
        assertFalse(m.frameMayArm(sticks(1000, 1000), 8), "the switch low can't arm");
        assertTrue(m.frameMayArm(sticks(1000, 1900), 8));
        assertTrue(m.frameMayArm(new short[]{1500, 1500, 1500, 1000}, 4), "AUX1 not sent: unknown, may arm");
    }

    @Test
    void theGpsFixComesAndHomeIsSetAtArming() {
        FlightModel m = model(FlightModel.Procedure.DROP);
        assertFalse(m.gpsFix(T0 + 1000));
        assertTrue(m.gpsFix(T0 + FlightModel.GPS_FIX_AFTER_MS));
        assertEquals(-1, m.homeDistanceM(), "no home before arming");
        long t = fly(m, T0, T0 + 4000, 1000, 1000);
        t = fly(m, t, t + 100, 1000, 1900);
        assertEquals(0, m.homeDistanceM(), 1e-6);
        // forward (pitch up on the stick) and up: it leaves home northwards
        for (long end = t + 5000; t < end; t += 20) {
            m.sticks(new short[]{1500, 1700, 1500, 1600, 1900, 1500, 1000, 1000}, 8, t);
            m.step(t, 0.02);
        }
        assertTrue(m.homeDistanceM() > 10, "away from home: " + m.homeDistanceM());
        assertTrue(m.latDeg() > 52.0, "north");
        assertEquals(180, m.homeDirectionDeg(), 5, "home is behind it, south");
        assertTrue(m.pitchDeg() < -10, "nose down to go forward: " + m.pitchDeg());
    }
}
