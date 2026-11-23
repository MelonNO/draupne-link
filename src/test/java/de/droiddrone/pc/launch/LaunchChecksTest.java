package de.droiddrone.pc.launch;

import de.droiddrone.common.launch.ClimbWatchdog;
import de.droiddrone.common.launch.LaunchGeo;
import de.droiddrone.common.launch.LaunchMark;
import de.droiddrone.common.launch.PreArmCheck;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD AL-13 / AL-14 / AL-19: the reserve phone's own checks - before the motors start, and over the climb.
 * The AL-19 cases (upside down, moved 20 m, rotated, carried, branch strike, stuck in a tree, and a gusty climb that
 * must NOT abort) as made-up readings. Places are made up.
 */
class LaunchChecksTest {
    static final double LAT = 60.0, LON = 10.0;

    static LaunchMark mark() {
        return new LaunchMark(7, LAT, LON, 90, 0, 40);
    }

    /** A drone standing level where it was marked, all healthy, 80 % battery. */
    static PreArmCheck.Reading level() {
        PreArmCheck.Reading r = new PreArmCheck.Reading();
        r.rollDeg = 1.5;
        r.pitchDeg = -2;
        r.headingDeg = 92;
        r.lat = LAT;
        r.lon = LON;
        r.gpsFix3d = true;
        r.batteryPercent = 80;
        r.imuHealthy = r.compassHealthy = r.fcHealthy = true;
        return r;
    }

    /** Resting samples at 50 Hz for ms (tiny jitter). */
    static void rest(PreArmCheck c, long from, long ms) {
        Random rnd = new Random(1);
        for (long t = from; t <= from + ms; t += 20) c.motion(t, 1 + rnd.nextGaussian() * 0.01, Math.abs(rnd.nextGaussian()) * 0.5);
    }

    static double offsetLat(double metresNorth) {
        return LAT + metresNorth / 111_195.0;
    }

    @Test
    void aLevelStillDroneWhereItWasMarkedPassesAfterFiveSeconds() {
        PreArmCheck c = new PreArmCheck(40);
        rest(c, 0, 3000);
        assertEquals(PreArmCheck.WAIT, c.check(3000, level(), mark()), "not yet 5 s of still samples");
        rest(c, 3000, 2100);
        assertEquals(PreArmCheck.OK, c.check(5100, level(), mark()));
    }

    @Test
    void upsideDownOrOnItsSideNeverArms() {
        PreArmCheck c = new PreArmCheck(40);
        rest(c, 0, 6000);
        PreArmCheck.Reading r = level();
        r.rollDeg = 180;
        r.pitchDeg = 0;
        assertEquals(PreArmCheck.TIPPED, c.check(6000, r, mark()));
        r.rollDeg = 90;
        assertEquals(PreArmCheck.TIPPED, c.check(6000, r, mark()));
        r.rollDeg = 35;
        r.pitchDeg = 35;
        assertTrue(LaunchGeo.tiltDeg(35, 35) > 45);
        assertEquals(PreArmCheck.TIPPED, c.check(6000, r, mark()), "35 and 35 together lean over 45");
        r.rollDeg = 30;
        r.pitchDeg = 20;
        assertEquals(PreArmCheck.OK, c.check(6000, r, mark()), "a slope of 36 deg is within 45");
        assertTrue(PreArmCheck.clearsMark(PreArmCheck.TIPPED));
    }

    @Test
    void movedTwentyMetresOrRotatedIsMoved() {
        PreArmCheck c = new PreArmCheck(40);
        rest(c, 0, 6000);
        PreArmCheck.Reading r = level();
        r.lat = offsetLat(20);
        assertEquals(PreArmCheck.MOVED, c.check(6000, r, mark()));
        r = level();
        r.lat = offsetLat(8);
        assertEquals(PreArmCheck.OK, c.check(6000, r, mark()), "8 m: within the 10");
        r = level();
        r.headingDeg = 90 + 45;
        assertEquals(PreArmCheck.MOVED, c.check(6000, r, mark()), "turned 45 deg");
        r.headingDeg = 90 - 25;
        assertEquals(PreArmCheck.OK, c.check(6000, r, mark()));
        r.headingDeg = 350;
        LaunchMark north = new LaunchMark(8, LAT, LON, 10, 0, 40);
        assertEquals(PreArmCheck.OK, c.check(6000, r, north), "across north: 20 deg");
        assertTrue(PreArmCheck.clearsMark(PreArmCheck.MOVED));
    }

    @Test
    void carriedIsMotionInTheLastFiveSeconds() {
        PreArmCheck c = new PreArmCheck(40);
        rest(c, 0, 4000);
        for (long t = 4000; t < 6000; t += 20) c.motion(t, 1 + 0.4 * Math.sin(t / 150.0), 40); // a walking gait
        rest(c, 6000, 1000);
        assertEquals(PreArmCheck.CARRIED, c.check(7000, level(), mark()));
        rest(c, 7000, 4100);
        assertEquals(PreArmCheck.OK, c.check(11_100, level(), mark()), "set down: 5 s still again");
        assertTrue(PreArmCheck.clearsMark(PreArmCheck.CARRIED));
    }

    @Test
    void gpsBatteryAndHealthFailures() {
        PreArmCheck c = new PreArmCheck(40);
        rest(c, 0, 6000);
        PreArmCheck.Reading r = level();
        r.gpsFix3d = false;
        assertEquals(PreArmCheck.NO_GPS, c.check(6000, r, mark()));
        assertFalse(PreArmCheck.clearsMark(PreArmCheck.NO_GPS), "AL-3: skipped, the mark stays");
        r = level();
        r.batteryPercent = 35;
        assertEquals(PreArmCheck.LOW_BATTERY, c.check(6000, r, mark()));
        r.batteryPercent = -1;
        assertEquals(PreArmCheck.LOW_BATTERY, c.check(6000, r, mark()), "unknown battery fails closed");
        assertFalse(PreArmCheck.clearsMark(PreArmCheck.LOW_BATTERY));
        r = level();
        r.compassHealthy = false;
        assertEquals(PreArmCheck.FC_UNHEALTHY, c.check(6000, r, mark()));
        r = level();
        r.rollDeg = Double.NaN;
        assertEquals(PreArmCheck.FC_UNHEALTHY, c.check(6000, r, mark()), "no attitude read: fails closed");
        LaunchMark gone = mark();
        gone.clear(LaunchMark.CLEARED_PILOT);
        assertEquals(PreArmCheck.MARK_GONE, c.check(6000, level(), gone));
        assertEquals(PreArmCheck.MARK_GONE, c.check(6000, level(), null));
        assertEquals(PreArmCheck.LOW_BATTERY, new PreArmCheck(0).check(6000, withBattery(39), mark()), "default minimum 40");
    }

    private static PreArmCheck.Reading withBattery(int p) {
        PreArmCheck.Reading r = level();
        r.batteryPercent = p;
        return r;
    }

    @Test
    void aMarkIsClearedForGoodWhenTheDroneMovesWhileItRests() {
        LaunchMark m = mark();
        assertTrue(m.observe(offsetLat(3), LON, 95, true));
        assertTrue(m.observe(offsetLat(50), LON, 95, false), "without a fix nothing is concluded");
        assertFalse(m.observe(offsetLat(12), LON, 95, true));
        assertEquals(LaunchMark.CLEARED_MOVED, m.clearedWhy());
        assertFalse(m.observe(LAT, LON, 90, true), "back where it was: still cleared (a new mark is a new id)");
        m.clear(LaunchMark.CLEARED_PILOT);
        assertEquals(LaunchMark.CLEARED_MOVED, m.clearedWhy(), "the first reason is kept");
        assertThrows(IllegalArgumentException.class, () -> new LaunchMark(0, LAT, LON, 0, 0, 40));
    }

    // ------------------------------------------------------------------ the climb

    /** A sample of a clean climb at 2.5 m/s, t ms after the start. */
    static ClimbWatchdog.Sample climb(long t) {
        ClimbWatchdog.Sample s = new ClimbWatchdog.Sample();
        s.atMs = t;
        double spool = Math.min(1, t / 1200.0);
        s.climbMps = t < 800 ? 0 : 2.5 * spool;
        s.heightM = Math.max(0, (t - 800) / 1000.0 * 2.2);
        s.tiltDeg = 3;
        s.accelG = 1.2;
        s.throttlePercent = 55;
        s.driftM = 0.3;
        return s;
    }

    @Test
    void aCleanClimbEndsAtTheFloor() {
        ClimbWatchdog w = new ClimbWatchdog(40);
        w.start(0);
        int v = ClimbWatchdog.WATCHING;
        long t = 0;
        while (v == ClimbWatchdog.WATCHING && t < 60_000) v = w.feed(climb(t += 50));
        assertEquals(ClimbWatchdog.DONE, v);
        assertTrue(t > 18_000 && t < 20_000, "40 m at 2.2 m/s: " + t);
    }

    @Test
    void theGustyClimbDoesNotAbort() {
        Random rnd = new Random(42);
        for (int run = 0; run < 50; run++) {
            ClimbWatchdog w = new ClimbWatchdog(40);
            w.start(0);
            double h = 0, drift = 0, driftV = 0;
            long t = 0;
            int v = ClimbWatchdog.WATCHING;
            long gustUntil = 0;
            double gust = 0;
            while (v == ClimbWatchdog.WATCHING && t < 120_000) {
                t += 40;
                if (t > gustUntil && rnd.nextDouble() < 0.01) { // a gust every few seconds, 0.5 - 1.5 s long
                    gustUntil = t + 500 + rnd.nextInt(1000);
                    gust = 0.5 + rnd.nextDouble() * 0.5;
                }
                boolean inGust = t < gustUntil;
                ClimbWatchdog.Sample s = new ClimbWatchdog.Sample();
                s.atMs = t;
                double base = t < 800 ? 0 : 2.4;
                s.climbMps = inGust ? base * (1 - gust) - 0.3 * gust : base + rnd.nextGaussian() * 0.2; // dips, short sinks
                h += Math.max(0, s.climbMps) * 0.04;
                s.heightM = h;
                s.tiltDeg = Math.abs((inGust ? 35 * gust : 4) + rnd.nextGaussian() * 2); // spikes to ~35
                s.accelG = 1.1 + (inGust ? 0.6 * gust : 0) + Math.abs(rnd.nextGaussian()) * 0.1;
                s.throttlePercent = inGust ? 70 + 15 * gust : 55;
                driftV += (inGust ? 0.3 : -0.05) * 0.04 - driftV * 0.04 * 0.6;
                drift = Math.max(0, drift + driftV * 0.04);
                s.driftM = Math.min(drift, 2.8); // the position controller keeps it within the column
                s.rpmRatio = 0.85 + rnd.nextDouble() * 0.1;
                v = w.feed(s);
            }
            assertEquals(ClimbWatchdog.DONE, v, "run " + run + " at " + t + " ms: " + ClimbWatchdog.word(v));
        }
    }

    @Test
    void aBranchStrikeIsAnImpact() {
        ClimbWatchdog w = run(5000, s -> s.accelG = 4.2);
        assertEquals(ClimbWatchdog.ABORT_IMPACT, w.verdict());
    }

    @Test
    void stuckInATreeStalls() {
        ClimbWatchdog w = new ClimbWatchdog(40);
        w.start(0);
        int v = 0;
        long t = 0;
        while (t < 20_000 && !ClimbWatchdog.isAbort(v)) {
            ClimbWatchdog.Sample s = climb(t += 50);
            if (t > 3000) {
                s.climbMps = 0.1; // held by the branches at 4 m
                s.heightM = 4.4;
                s.throttlePercent = 70;
            }
            v = w.feed(s);
        }
        assertEquals(ClimbWatchdog.ABORT_STALLED, v);
        assertTrue(t >= 7000 && t <= 7100, "4 s after it stopped climbing: " + t);
    }

    @Test
    void motorsFullWithoutClimbingIsNoLift() {
        ClimbWatchdog w = new ClimbWatchdog(40);
        w.start(0);
        int v = 0;
        long t = 0;
        while (t < 20_000 && !ClimbWatchdog.isAbort(v)) {
            ClimbWatchdog.Sample s = climb(t += 50);
            if (t > 3000) {
                s.climbMps = 0.2;
                s.throttlePercent = 97;
            }
            v = w.feed(s);
        }
        assertEquals(ClimbWatchdog.ABORT_NO_LIFT, v, "before the 4 s stall rule");
        assertTrue(t <= 5100, "" + t);
    }

    @Test
    void tiltDriftAndAMotorAbort() {
        assertEquals(ClimbWatchdog.ABORT_TILT, run(4000, s -> s.tiltDeg = 55).verdict());
        assertEquals(ClimbWatchdog.ABORT_DRIFT, run(4000, s -> s.driftM = 3.4).verdict());
        assertEquals(ClimbWatchdog.ABORT_MOTOR, run(4000, s -> s.rpmRatio = 0.3).verdict());
        assertEquals(ClimbWatchdog.WATCHING, run(4000, s -> s.rpmRatio = Double.NaN).verdict(), "no ESC telemetry: not a reason");
        assertEquals(ClimbWatchdog.WATCHING, run(4000, s -> {
            s.rpmRatio = 0.3;
            s.throttlePercent = 20; // idle: a slow motor at low output is no failure
        }).verdict());
    }

    @Test
    void aSilentFeedAbortsAndTheVerdictSticks() {
        ClimbWatchdog w = new ClimbWatchdog(40);
        w.start(0);
        w.feed(climb(500));
        assertEquals(ClimbWatchdog.WATCHING, w.tick(1400));
        assertEquals(ClimbWatchdog.ABORT_NO_DATA, w.tick(1600));
        assertEquals(ClimbWatchdog.ABORT_NO_DATA, w.feed(climb(1650)), "sticky");
    }

    @Test
    void spoolUpIsNotAStall() {
        ClimbWatchdog w = new ClimbWatchdog(40);
        w.start(0);
        for (long t = 50; t <= 1450; t += 50) {
            ClimbWatchdog.Sample s = climb(t);
            s.climbMps = 0;
            s.throttlePercent = 95; // full while spooling, not climbing yet
            assertEquals(ClimbWatchdog.WATCHING, w.feed(s), "t " + t);
        }
    }

    /** A clean climb whose sample at t gets changed. */
    private static ClimbWatchdog run(long atMs, java.util.function.Consumer<ClimbWatchdog.Sample> change) {
        ClimbWatchdog w = new ClimbWatchdog(40);
        w.start(0);
        for (long t = 50; t <= atMs + 500 && !ClimbWatchdog.isAbort(w.verdict()) && w.verdict() != ClimbWatchdog.DONE; t += 50) {
            ClimbWatchdog.Sample s = climb(t);
            if (t >= atMs) change.accept(s);
            w.feed(s);
        }
        return w;
    }
}
