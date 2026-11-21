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

    /** As fly, with the roll and pitch sticks where wanted (angle mode is on in these frames: AUX2 at 1500). */
    private static long fly(FlightModel m, long from, long to, int roll, int pitch, int throttle, int arm) {
        long t = from;
        for (; t < to; t += 20) {
            short[] f = sticks(throttle, arm);
            f[0] = (short) roll;
            f[1] = (short) pitch;
            m.sticks(f, 8, t);
            m.step(t, 0.02);
        }
        return t;
    }

    /** Armed, up and some way from home: the time it is then. */
    private static long awayFromHome(FlightModel m) {
        long t = fly(m, T0, T0 + 4000, 1000, 1000);
        t = fly(m, t, t + 100, 1000, 1900);
        t = fly(m, t, t + 3000, 1600, 1900);
        t = fly(m, t, t + 6000, 1500, 1750, 1550, 1900); // nose down: away
        assertTrue(m.isArmed() && m.altitudeM() > 2 && m.homeDistanceM() > 20, "up and away: " + m.altitudeM() + " m up, " + m.homeDistanceM() + " m out");
        return t;
    }

    /**
     * 1.0.71 (the pilot: "it is important that the drone does not disarm if gps rescue is set"): the failsafe set to
     * GPS rescue keeps the drone ARMED through a lost link - it climbs, flies home, lands there, and only then disarms.
     */
    @Test
    void gpsRescueStaysArmedFliesHomeAndDisarmsOnlyOnTheGround() {
        FlightModel m = model(FlightModel.Procedure.GPS_RESCUE);
        long t = awayFromHome(m);
        t = silent(m, t, t + 2500);
        assertEquals(FlightModel.Failsafe.RESCUE, m.failsafe());
        assertTrue(m.isArmed(), "a rescue flies: armed");
        assertNotEquals(0, m.modeFlags(t) & (1 << FlightModel.BOX_GPS_RESCUE), "GPS RESCUE in the mode flags");
        assertEquals("RX_LOST", m.beeping(t));
        long landed = -1;
        for (long end = t + 120_000; t < end && landed < 0; t = silent(m, t, t + 1000)) {
            if (!m.isArmed()) landed = t;
            else assertEquals(FlightModel.Failsafe.RESCUE, m.failsafe(), "armed all the way home");
        }
        assertTrue(landed > 0, "it came down within two minutes");
        assertEquals("failsafe (GPS rescue landed)", m.lastDisarm());
        assertTrue(m.homeDistanceM() < 5, "at home: " + m.homeDistanceM() + " m");
        assertFalse(m.crashed());
        // and on the ground the switch must go off before it arms again
        t = fly(m, t, t + 1500, 1000, 1900);
        assertFalse(m.isArmed(), "the switch still on: no re-arm");
    }

    /** The link back in a rescue: the drone flies on home by itself, armed, until the pilot moves a stick. */
    @Test
    void theLinkBackInARescueLeavesItArmedAndAStickTakesItBack() {
        FlightModel m = model(FlightModel.Procedure.GPS_RESCUE);
        long t = awayFromHome(m);
        t = silent(m, t, t + 6000);
        assertEquals(FlightModel.Failsafe.RESCUE, m.failsafe());
        // the sticks are back, centred, ARM still on: it is still the rescue's
        t = fly(m, t, t + 2000, 1500, 1900);
        assertTrue(m.isArmed(), "armed with the link back");
        assertEquals(FlightModel.Failsafe.RESCUE, m.failsafe(), "no stick moved: the rescue flies on");
        // a stick moved: the pilot's again
        t = fly(m, t, t + 300, 1800, 1500, 1500, 1900);
        assertEquals(FlightModel.Failsafe.NONE, m.failsafe());
        assertTrue(m.isArmed());
        assertEquals(0, m.blockers(t) & FlightModel.FLAG_FAILSAFE);
    }

    /**
     * What a pilot on GPS rescue must know (Betaflight's rc_controls.c): ARM off when the link returns disarms the
     * drone in the air - the switch is obeyed again once the receiver's data is believed (half a second).
     */
    @Test
    void armOffWhenTheLinkReturnsDisarmsARescueInTheAir() {
        FlightModel m = model(FlightModel.Procedure.GPS_RESCUE);
        long t = awayFromHome(m);
        t = silent(m, t, t + 6000);
        assertTrue(m.isArmed() && m.altitudeM() > 5);
        t = fly(m, t, t + 300, 1000, 1000); // the link back, ARM off: not believed yet
        assertTrue(m.isArmed(), "the first frames after a loss are not obeyed");
        fly(m, t, t + 600, 1000, 1000);
        assertFalse(m.isArmed(), "believed after half a second: disarmed, in the air");
        assertEquals("the arm switch", m.lastDisarm());
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
