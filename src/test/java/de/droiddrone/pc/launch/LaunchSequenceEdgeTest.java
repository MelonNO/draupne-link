package de.droiddrone.pc.launch;

import de.droiddrone.common.launch.ClimbWatchdog;
import de.droiddrone.common.launch.LaunchIntent;
import de.droiddrone.common.launch.LaunchMark;
import de.droiddrone.common.launch.LaunchSequence;
import de.droiddrone.common.launch.LaunchWire;
import de.droiddrone.common.launch.PreArmCheck;
import org.junit.jupiter.api.Test;

import java.util.List;

import static de.droiddrone.pc.launch.LaunchSequenceTest.order;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The launch on the reserve's phone against what the link and the flight controller do meanwhile: orders repeated,
 * late or replayed; a gap in the FC's heartbeats in the climb; an FC that stops talking (rebooting) mid-launch; arming
 * refused; battery not known (255); the GNSS fix lost.
 */
class LaunchSequenceEdgeTest {
    /** Up to IN GROUP with the whole launch's steps. */
    static LaunchSequenceTest.Phone inGroup() {
        LaunchSequenceTest.Phone p = new LaunchSequenceTest.Phone(LaunchChecksTest.mark());
        p.take(p.seq.order(order(7, 11), p.t));
        p.seq.fcReady(true);
        p.run(40_000, false, null);
        assertEquals(LaunchSequence.ON_THE_WAY, p.seq.state());
        p.take(p.seq.atGroup(p.t));
        p.take(p.seq.transitGranted(p.t));
        p.take(p.seq.inCell(p.t));
        assertEquals(LaunchSequence.IN_GROUP, p.seq.state());
        return p;
    }

    static LaunchWire.Status lastStatus(LaunchSequenceTest.Phone p) {
        for (int i = p.said.size() - 1; i >= 0; i--) if (p.said.get(i).type == LaunchIntent.REPORT) return p.said.get(i).status;
        return null;
    }

    /** The PC repeats the order at 5 Hz: a copy arriving after the drone joined its group was reported as NOT LAUNCHED. */
    @Test
    void aCopyOfTheOrderAfterTheLaunchOnlyRepeatsTheStatus() {
        LaunchSequenceTest.Phone p = inGroup();
        int arms = count(p, LaunchIntent.ARM);
        p.take(p.seq.order(order(7, 11), p.t + 100));
        assertEquals(LaunchSequence.IN_GROUP, p.seq.state(), "a copy changes nothing");
        assertEquals(LaunchSequence.IN_GROUP, lastStatus(p).state, "it is answered with the status");
        assertEquals(11, lastStatus(p).callId);
        assertEquals(arms, count(p, LaunchIntent.ARM));
        // a new call to a drone already flying: NOT LAUNCHED for that call, never a second arm
        p.take(p.seq.order(order(7, 12), p.t + 200));
        assertEquals(LaunchSequence.NOT_LAUNCHED, lastStatus(p).state);
        assertEquals(12, lastStatus(p).callId);
        assertEquals(arms, count(p, LaunchIntent.ARM));
    }

    @Test
    void aCopyAfterALaunchFailedKeepsLaunchFailed() {
        LaunchSequenceTest.Phone p = new LaunchSequenceTest.Phone(LaunchChecksTest.mark());
        p.take(p.seq.order(order(7, 21), p.t));
        p.seq.fcReady(true);
        p.run(9000, false, null);
        assertEquals(LaunchSequence.CLIMBING, p.seq.state());
        p.run(3000, false, s -> s.tiltDeg = 80); // tipping over in the climb
        assertEquals(LaunchSequence.LAUNCH_FAILED, p.seq.state());
        p.take(p.seq.order(order(7, 21), p.t + 50));
        assertEquals(LaunchSequence.LAUNCH_FAILED, p.seq.state());
        assertEquals(LaunchSequence.LAUNCH_FAILED, lastStatus(p).state);
    }

    @Test
    void copiesWhileItRunsArmOnce() {
        LaunchSequenceTest.Phone p = new LaunchSequenceTest.Phone(LaunchChecksTest.mark());
        p.take(p.seq.order(order(7, 31), p.t));
        p.seq.fcReady(true);
        for (int i = 0; i < 60; i++) { // 12 s of copies at 5 Hz
            p.run(200, false, null);
            p.take(p.seq.order(order(7, 31), p.t));
        }
        assertEquals(1, count(p, LaunchIntent.ARM));
        assertTrue(LaunchSequence.motorsMayRun(p.seq.state()), LaunchSequence.stateWord(p.seq.state()));
    }

    /** The same call id a long time later is a new call: the PC's ids start at 1 each time it starts. */
    @Test
    void theSameCallIdLongAfterIsANewCall() {
        LaunchMark m = LaunchChecksTest.mark();
        LaunchSequenceTest.Phone p = new LaunchSequenceTest.Phone(m);
        p.take(p.seq.order(order(7, 1), p.t));
        p.seq.fcReady(true);
        PreArmCheck.Reading noFix = LaunchChecksTest.level();
        noFix.gpsFix3d = false;
        for (int i = 0; i < 400 && p.seq.state() != LaunchSequence.NOT_LAUNCHED; i++) {
            p.t += 50;
            p.check.motion(p.t, 1, 0.1);
            p.take(p.seq.tick(p.t, noFix, false, null));
        }
        assertEquals(LaunchSequence.NOT_LAUNCHED, p.seq.state());
        assertEquals(PreArmCheck.NO_GPS, p.seq.reason());
        assertTrue(m.valid(), "NO GPS keeps the mark");
        p.take(p.seq.order(order(7, 1), p.t + 1000));
        assertEquals(LaunchSequence.NOT_LAUNCHED, p.seq.state(), "a copy soon after: the status again");
        p.t += LaunchSequence.REPLAY_MS + 1;
        p.take(p.seq.order(order(7, 1), p.t));
        assertEquals(LaunchSequence.WAKING, p.seq.state(), "a new call after the PC started again");
    }

    /** A gap in the FC's heartbeats in the climb was read as DISARMED: the motors were forced off in the air. */
    @Test
    void aHeartbeatGapInTheClimbIsNoDisarm() {
        LaunchSequenceTest.Phone p = new LaunchSequenceTest.Phone(LaunchChecksTest.mark());
        p.take(p.seq.order(order(7, 41), p.t));
        p.seq.fcReady(true);
        p.run(9000, false, null);
        assertEquals(LaunchSequence.CLIMBING, p.seq.state());
        for (int i = 0; i < 40; i++) { // 2 s with no fresh armed word
            p.t += 50;
            p.height += 0.125;
            ClimbWatchdog.Sample s = new ClimbWatchdog.Sample();
            s.atMs = p.t;
            s.climbMps = 2.5;
            s.heightM = p.height;
            s.tiltDeg = 3;
            s.accelG = 1.1;
            s.throttlePercent = 55;
            s.driftM = 0.4;
            p.take(p.seq.tick(p.t, LaunchChecksTest.level(), false, false, s));
        }
        assertEquals(LaunchSequence.CLIMBING, p.seq.state());
        assertEquals(0, count(p, LaunchIntent.MOTORS_OFF));
        assertTrue(p.seq.status().armed, "the last word known stands");
        // the FC's own fresh word DISARMED: that is a disarm
        p.t += 50;
        p.take(p.seq.tick(p.t, LaunchChecksTest.level(), false, true, null));
        assertEquals(LaunchSequence.LAUNCH_FAILED, p.seq.state());
        assertEquals(LaunchSequence.DISARMED_IN_CLIMB, p.seq.reason());
    }

    @Test
    void anArmedWordNotKnownNeverCountsAsArmed() {
        LaunchSequenceTest.Phone p = new LaunchSequenceTest.Phone(LaunchChecksTest.mark());
        p.take(p.seq.order(order(7, 51), p.t));
        p.seq.fcReady(true);
        p.run(7200, true, null);
        assertEquals(LaunchSequence.ARMING, p.seq.state());
        for (int i = 0; i < 120; i++) {
            p.t += 50;
            p.take(p.seq.tick(p.t, LaunchChecksTest.level(), true, false, null)); // a stale "armed"
        }
        assertEquals(LaunchSequence.NOT_LAUNCHED, p.seq.state());
        assertEquals(LaunchSequence.ARM_REFUSED, p.seq.reason());
        assertTrue(count(p, LaunchIntent.MOTORS_OFF) >= 1, "a late arm must not stay");
        // then the FC reboots: a copy of the order, its fresh words again - nothing arms
        int arms = count(p, LaunchIntent.ARM);
        p.take(p.seq.order(order(7, 51), p.t + 10));
        p.run(20_000, false, null);
        assertEquals(arms, count(p, LaunchIntent.ARM));
    }

    /** An FC that stops talking mid-launch (rebooting): its last words read as OK, now they are too old. */
    @Test
    void anFcThatStopsTalkingIsUnhealthy() {
        LaunchMark m = LaunchChecksTest.mark();
        LaunchSequenceTest.Phone p = new LaunchSequenceTest.Phone(m);
        p.take(p.seq.order(order(7, 61), p.t));
        p.seq.fcReady(true);
        p.run(5100, false, null);
        assertEquals(LaunchSequence.BEEPING, p.seq.state());
        PreArmCheck.Reading stale = LaunchChecksTest.level();
        stale.fcAgeMs = PreArmCheck.MAX_FC_AGE_MS + 1;
        p.t += 50;
        p.take(p.seq.tick(p.t, stale, false, null));
        assertEquals(LaunchSequence.NOT_LAUNCHED, p.seq.state());
        assertEquals(PreArmCheck.FC_UNHEALTHY, p.seq.reason());
        assertEquals(0, count(p, LaunchIntent.ARM));
        PreArmCheck c = new PreArmCheck(40);
        PreArmCheck.Reading fresh = LaunchChecksTest.level();
        fresh.fcAgeMs = PreArmCheck.MAX_FC_AGE_MS;
        LaunchChecksTest.rest(c, 0, 6000);
        assertEquals(PreArmCheck.OK, c.check(6000, fresh, LaunchChecksTest.mark()));
        fresh.fcAgeMs = -1;
        assertEquals(PreArmCheck.FC_UNHEALTHY, c.check(6000, fresh, LaunchChecksTest.mark()));
    }

    /** Battery 255 is ArduPilot's "not known" (FleetStatus): never 255 % charged. */
    @Test
    void aBatteryNotKnownIsNoBattery() {
        PreArmCheck c = new PreArmCheck(40);
        LaunchChecksTest.rest(c, 0, 6000);
        PreArmCheck.Reading r = LaunchChecksTest.level();
        for (int b : new int[]{255, 101, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 39}) {
            r.batteryPercent = b;
            assertEquals(PreArmCheck.LOW_BATTERY, c.check(6000, r, LaunchChecksTest.mark()), "battery " + b);
        }
        r.batteryPercent = 100;
        assertEquals(PreArmCheck.OK, c.check(6000, r, LaunchChecksTest.mark()));
        r.batteryPercent = 40;
        assertEquals(PreArmCheck.OK, c.check(6000, r, LaunchChecksTest.mark()));
    }

    @Test
    void theGnssFixLostWhileItBeepsStopsItAndKeepsTheMark() {
        LaunchMark m = LaunchChecksTest.mark();
        LaunchSequenceTest.Phone p = new LaunchSequenceTest.Phone(m);
        p.take(p.seq.order(order(7, 71), p.t));
        p.seq.fcReady(true);
        p.run(5100, false, null);
        assertEquals(LaunchSequence.BEEPING, p.seq.state());
        PreArmCheck.Reading lost = LaunchChecksTest.level();
        lost.gpsFix3d = false;
        p.t += 50;
        List<LaunchIntent> in = p.seq.tick(p.t, lost, false, null);
        p.take(in);
        assertEquals(LaunchSequence.NOT_LAUNCHED, p.seq.state());
        assertEquals(PreArmCheck.NO_GPS, p.seq.reason());
        assertTrue(m.valid());
        assertEquals(0, count(p, LaunchIntent.ARM));
        assertTrue(in.stream().anyMatch(i -> i.type == LaunchIntent.BEEP && i.ms == 0), "the warning stops");
    }

    static int count(LaunchSequenceTest.Phone p, int type) {
        int n = 0;
        for (LaunchIntent i : p.said) if (i.type == type) n++;
        return n;
    }
}
