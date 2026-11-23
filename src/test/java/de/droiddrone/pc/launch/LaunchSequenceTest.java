package de.droiddrone.pc.launch;

import de.droiddrone.common.launch.ClimbWatchdog;
import de.droiddrone.common.launch.LaunchIntent;
import de.droiddrone.common.launch.LaunchMark;
import de.droiddrone.common.launch.LaunchSequence;
import de.droiddrone.common.launch.LaunchWire;
import de.droiddrone.common.launch.PreArmCheck;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD AL-7 - AL-18: a marked reserve's launch on its phone, end to end with made-up readings - and AL-12's rule
 * (only a marked drone, only on the pilot's call, ever arms by itself) attacked with random event sequences.
 */
class LaunchSequenceTest {
    static LaunchWire.Order order(int markId, int callId) {
        LaunchWire.Order o = new LaunchWire.Order();
        o.kind = LaunchWire.KIND_LAUNCH;
        o.markId = markId;
        o.callId = callId;
        o.approachHeightM = 60;
        o.floorM = 40;
        o.columnLat = LaunchChecksTest.LAT;
        o.columnLon = LaunchChecksTest.LON;
        o.leadSysId = 3;
        return o;
    }

    static LaunchWire.Order cancel(int callId) {
        LaunchWire.Order o = new LaunchWire.Order();
        o.kind = LaunchWire.KIND_CANCEL;
        o.callId = callId;
        return o;
    }

    /** A phone and its drone: the sequence, the check's motion feed, and every intent given so far. */
    static final class Phone {
        final PreArmCheck check = new PreArmCheck(40);
        final LaunchSequence seq = new LaunchSequence(check);
        final List<LaunchIntent> said = new ArrayList<>();
        long t;
        boolean armed;
        double height;

        Phone(LaunchMark mark) {
            seq.setMark(mark);
        }

        void take(List<LaunchIntent> in) {
            for (LaunchIntent i : in) {
                said.add(i);
                if (i.type == LaunchIntent.MOTORS_OFF) armed = false;
            }
        }

        /** Run for ms with the drone level and still; the FC arms when told (unless refuse); climbs once armed. */
        void run(long ms, boolean refuseArm, java.util.function.Consumer<ClimbWatchdog.Sample> climbChange) {
            long end = t + ms;
            while (t < end) {
                t += 50;
                check.motion(t, 1.0, 0.2);
                ClimbWatchdog.Sample s = null;
                if (seq.state() == LaunchSequence.CLIMBING) {
                    height += 0.125;
                    s = new ClimbWatchdog.Sample();
                    s.atMs = t;
                    s.climbMps = 2.5;
                    s.heightM = height;
                    s.tiltDeg = 3;
                    s.accelG = 1.1;
                    s.throttlePercent = 55;
                    s.driftM = 0.4;
                    if (climbChange != null) climbChange.accept(s);
                }
                List<LaunchIntent> in = seq.tick(t, LaunchChecksTest.level(), armed, s);
                take(in);
                for (LaunchIntent i : in) if (i.type == LaunchIntent.ARM && !refuseArm) armed = true;
            }
        }

        List<Integer> types() {
            List<Integer> out = new ArrayList<>();
            for (LaunchIntent i : said) out.add(i.type);
            return out;
        }

        boolean armedEver() {
            return types().contains(LaunchIntent.ARM);
        }
    }

    @Test
    void theWholeLaunch() {
        LaunchMark m = LaunchChecksTest.mark();
        Phone p = new Phone(m);
        p.take(p.seq.order(order(7, 11), p.t));
        assertEquals(LaunchSequence.WAKING, p.seq.state());
        p.run(1000, false, null);
        assertEquals(LaunchSequence.WAKING, p.seq.state(), "the FC hasn't answered yet");
        p.seq.fcReady(true);
        p.run(3000, false, null);
        assertEquals(LaunchSequence.CHECKING, p.seq.state(), "not yet 5 s still since the order");
        p.run(1100, false, null);
        assertEquals(LaunchSequence.BEEPING, p.seq.state(), "5 s still, then the beep");
        assertFalse(p.armedEver());
        p.run(2100, false, null);
        assertTrue(p.armedEver(), "after the 2 s beep");
        int beep = p.types().indexOf(LaunchIntent.BEEP), guided = p.types().indexOf(LaunchIntent.SET_MODE_GUIDED),
                arm = p.types().indexOf(LaunchIntent.ARM), takeoff = p.types().indexOf(LaunchIntent.TAKEOFF);
        assertTrue(beep < guided && guided < arm && arm < takeoff, "beep, GUIDED, ARM, then the take-off: " + p.said);
        assertEquals(2000, p.said.get(beep).ms);
        assertEquals(60, p.said.get(takeoff).heightM, 1e-9, "to its own approach layer");
        assertFalse(m.valid(), "one-shot: the mark is cleared at launch");
        assertEquals(LaunchMark.CLEARED_LAUNCHED, m.clearedWhy());
        p.run(18_000, false, null);
        assertTrue(p.seq.status().passedFloor, "the mini-view leaves at the floor");
        p.run(10_000, false, null);
        assertEquals(LaunchSequence.ON_THE_WAY, p.seq.state());
        p.take(p.seq.atGroup(p.t));
        p.take(p.seq.transitGranted(p.t));
        p.take(p.seq.inCell(p.t));
        assertEquals(LaunchSequence.IN_GROUP, p.seq.state());
        assertFalse(p.types().contains(LaunchIntent.MOTORS_OFF));
        LaunchWire.Status last = p.said.get(p.said.size() - 1).status;
        assertEquals(LaunchSequence.IN_GROUP, last.state);
        assertEquals(11, last.callId);
        // the same order again: the mark is gone, nothing arms
        Phone again = p;
        int arms = count(again.types(), LaunchIntent.ARM);
        again.take(again.seq.order(order(7, 12), again.t));
        again.seq.fcReady(true);
        again.run(20_000, false, null);
        assertEquals(arms, count(again.types(), LaunchIntent.ARM));
        assertEquals(LaunchSequence.NOT_LAUNCHED, again.seq.state());
    }

    private static int count(List<Integer> l, int v) {
        int n = 0;
        for (int i : l) if (i == v) n++;
        return n;
    }

    @Test
    void anOrderForAnotherMarkOrNoMarkLaunchesNothing() {
        Phone p = new Phone(LaunchChecksTest.mark());
        p.take(p.seq.order(order(8, 1), 0)); // the PC knows an older mark
        assertEquals(LaunchSequence.NOT_LAUNCHED, p.seq.state());
        assertEquals(LaunchSequence.WRONG_MARK, p.seq.reason());
        p.seq.fcReady(true);
        p.run(20_000, false, null);
        assertFalse(p.armedEver());
        Phone none = new Phone(null);
        none.take(none.seq.order(order(7, 1), 0));
        none.seq.fcReady(true);
        none.run(20_000, false, null);
        assertFalse(none.armedEver());
        assertTrue(none.seq.order(null, 0).isEmpty(), "a packet that didn't decode does nothing");
    }

    @Test
    void cancelBeforeArmingStopsItAfterArmingNot() {
        Phone p = new Phone(LaunchChecksTest.mark());
        p.take(p.seq.order(order(7, 5), 0));
        p.seq.fcReady(true);
        p.run(6000, false, null);
        assertEquals(LaunchSequence.BEEPING, p.seq.state());
        p.take(p.seq.order(cancel(4), p.t));
        assertEquals(LaunchSequence.BEEPING, p.seq.state(), "another call's cancel");
        p.take(p.seq.order(cancel(5), p.t));
        assertEquals(LaunchSequence.CANCELLED, p.seq.state());
        assertEquals(0, p.said.get(p.types().lastIndexOf(LaunchIntent.BEEP)).ms, "the beep stops");
        p.run(10_000, false, null);
        assertFalse(p.armedEver());
        assertTrue(p.seq.mark().valid(), "a cancel leaves the mark for the next call");

        Phone q = new Phone(LaunchChecksTest.mark());
        q.take(q.seq.order(order(7, 6), 0));
        q.seq.fcReady(true);
        q.run(9000, false, null);
        assertEquals(LaunchSequence.CLIMBING, q.seq.state());
        q.take(q.seq.order(cancel(6), q.t));
        assertEquals(LaunchSequence.CLIMBING, q.seq.state(), "FA10: once armed it carries on and joins the lead now");
        assertFalse(q.types().contains(LaunchIntent.MOTORS_OFF));
    }

    @Test
    void aCheckFailureIsNotLaunchedAndClearsTheMarkWhenItShould() {
        LaunchMark m = LaunchChecksTest.mark();
        PreArmCheck check = new PreArmCheck(40);
        LaunchSequence seq = new LaunchSequence(check);
        seq.setMark(m);
        seq.order(order(7, 1), 0);
        seq.fcReady(true);
        List<LaunchIntent> said = new ArrayList<>();
        PreArmCheck.Reading upside = LaunchChecksTest.level();
        upside.rollDeg = 175;
        for (long t = 50; t < 8000; t += 50) {
            check.motion(t, 1, 0.1);
            said.addAll(seq.tick(t, upside, false, null));
        }
        assertEquals(LaunchSequence.NOT_LAUNCHED, seq.state());
        assertEquals(PreArmCheck.TIPPED, seq.reason());
        assertEquals("TIPPED", LaunchSequence.reasonWord(seq.reason()));
        assertFalse(m.valid(), "AL-13: a failure clears the mark");
        assertTrue(said.stream().anyMatch(i -> i.type == LaunchIntent.CLEAR_MARK));
        assertTrue(said.stream().noneMatch(i -> i.type == LaunchIntent.ARM || i.type == LaunchIntent.BEEP));

        LaunchMark keep = LaunchChecksTest.mark();
        LaunchSequence s2 = new LaunchSequence(new PreArmCheck(40));
        s2.setMark(keep);
        s2.order(order(7, 2), 0);
        s2.fcReady(true);
        PreArmCheck.Reading noFix = LaunchChecksTest.level();
        noFix.gpsFix3d = false;
        s2.tick(100, noFix, false, null); // WAKING -> CHECKING
        s2.tick(150, noFix, false, null);
        assertEquals(LaunchSequence.NOT_LAUNCHED, s2.state());
        assertTrue(keep.valid(), "AL-3: NO GPS skips the drone and keeps its mark");
    }

    @Test
    void aMoveDuringTheBeepStopsIt() {
        Phone p = new Phone(LaunchChecksTest.mark());
        p.take(p.seq.order(order(7, 1), 0));
        p.seq.fcReady(true);
        p.run(5600, false, null);
        assertEquals(LaunchSequence.BEEPING, p.seq.state());
        p.check.motion(p.t + 10, 1.6, 80); // picked up during the beep
        p.run(3000, false, null);
        assertEquals(LaunchSequence.NOT_LAUNCHED, p.seq.state());
        assertEquals(PreArmCheck.CARRIED, p.seq.reason());
        assertFalse(p.armedEver());
    }

    @Test
    void aClimbFailureTurnsTheMotorsOffFirstAndSilencesTheBeeper() {
        Phone p = new Phone(LaunchChecksTest.mark());
        p.take(p.seq.order(order(7, 1), 0));
        p.seq.fcReady(true);
        p.run(8000, false, null);
        assertEquals(LaunchSequence.CLIMBING, p.seq.state());
        int before = p.said.size();
        p.run(200, false, s -> s.accelG = 5); // a branch
        assertEquals(LaunchSequence.LAUNCH_FAILED, p.seq.state());
        assertEquals("IMPACT", LaunchSequence.reasonWord(p.seq.reason()));
        assertEquals(LaunchIntent.MOTORS_OFF, p.said.get(before).type, "motors off before anything else");
        assertTrue(p.types().contains(LaunchIntent.BEEPER_SILENT));
        List<LaunchIntent> ooa = p.seq.outOfAction(p.t);
        assertTrue(ooa.stream().anyMatch(i -> i.type == LaunchIntent.BEEPER_NORMAL), "FA18: silent until OUT OF ACTION");
    }

    @Test
    void anFcThatWontArmIsNotLaunchedAndDisarmedToBeSure() {
        Phone p = new Phone(LaunchChecksTest.mark());
        p.take(p.seq.order(order(7, 1), 0));
        p.seq.fcReady(true);
        p.run(15_000, true, null);
        assertEquals(LaunchSequence.NOT_LAUNCHED, p.seq.state());
        assertEquals(LaunchSequence.ARM_REFUSED, p.seq.reason());
        assertTrue(p.types().contains(LaunchIntent.MOTORS_OFF));
    }

    @Test
    void outOfActionBeforeArmingCancels() {
        Phone p = new Phone(LaunchChecksTest.mark());
        p.take(p.seq.order(order(7, 1), 0));
        p.seq.fcReady(true);
        p.run(3000, false, null);
        p.take(p.seq.outOfAction(p.t));
        assertEquals(LaunchSequence.CANCELLED, p.seq.state());
        assertEquals(LaunchMark.CLEARED_OUT_OF_ACTION, p.seq.mark().clearedWhy());
        p.run(10_000, false, null);
        assertFalse(p.armedEver());
    }

    @Test
    void anFcThatNeverAnswersIsNotLaunched() {
        Phone p = new Phone(LaunchChecksTest.mark());
        p.take(p.seq.order(order(7, 1), 0));
        p.run(31_000, false, null);
        assertEquals(LaunchSequence.NOT_LAUNCHED, p.seq.state());
        assertEquals(LaunchSequence.FC_NOT_READY, p.seq.reason());
    }

    /**
     * AL-12 attacked: random sequences of orders (right and wrong marks, cancels, garbage), marks set and cleared,
     * OUT OF ACTION, FC readiness, tilts and motion, armed flags that come and go. ARM may appear only when, at that
     * moment, a LAUNCH order naming the mark then held was accepted, that mark was valid, at least 2 s of beep had run
     * since the last BEEP 2000, and nothing failed in between - checked by an oracle that watches from outside.
     */
    @Test
    void onlyAMarkedDroneOnThePilotsCallEverArms() {
        Random rnd = new Random(20261123);
        int arms = 0;
        for (int run = 0; run < 3000; run++) {
            PreArmCheck check = new PreArmCheck(40);
            LaunchSequence seq = new LaunchSequence(check);
            LaunchMark mark = rnd.nextBoolean() ? new LaunchMark(1 + rnd.nextInt(3), LaunchChecksTest.LAT, LaunchChecksTest.LON, 90, 0, 40) : null;
            seq.setMark(mark);
            Integer orderedMark = null;
            long beepAt = Long.MIN_VALUE;
            boolean fcArmed = false;
            long t = 0;
            for (int step = 0; step < 600; step++) {
                t += 50 + rnd.nextInt(100);
                List<LaunchIntent> in = new ArrayList<>();
                int ev = rnd.nextInt(100);
                if (ev < 3) {
                    LaunchWire.Order o = order(1 + rnd.nextInt(3), rnd.nextInt(5));
                    in.addAll(seq.order(o, t));
                    if (seq.order() == o && seq.state() == LaunchSequence.WAKING) orderedMark = o.markId;
                } else if (ev < 5) {
                    in.addAll(seq.order(cancel(rnd.nextInt(5)), t));
                } else if (ev < 6) {
                    byte[] junk = new byte[rnd.nextInt(40)];
                    rnd.nextBytes(junk);
                    in.addAll(seq.order(LaunchWire.decodeOrder(junk, 0, junk.length), t));
                } else if (ev < 7) {
                    mark = rnd.nextBoolean() ? new LaunchMark(1 + rnd.nextInt(3), LaunchChecksTest.LAT, LaunchChecksTest.LON, 90, t, 40) : null;
                    seq.setMark(mark);
                } else if (ev < 8) {
                    if (seq.mark() != null) seq.mark().clear(LaunchMark.CLEARED_PILOT);
                } else if (ev < 9) {
                    in.addAll(seq.outOfAction(t));
                } else if (ev < 15) {
                    seq.fcReady(rnd.nextInt(4) != 0);
                } else if (ev < 18) {
                    fcArmed = !fcArmed;
                }
                check.motion(t, rnd.nextInt(800) == 0 ? 1.8 : 1.0, rnd.nextInt(800) == 0 ? 90 : 0.2);
                PreArmCheck.Reading r = LaunchChecksTest.level();
                if (rnd.nextInt(400) == 0) r.rollDeg = 120;
                if (rnd.nextInt(600) == 0) r.gpsFix3d = false;
                LaunchMark held = seq.mark();
                boolean heldValid = held != null && held.valid();
                int heldId = held == null ? -1 : held.id;
                in.addAll(seq.tick(t, r, fcArmed, null));
                for (LaunchIntent i : in) {
                    if (i.type == LaunchIntent.BEEP) beepAt = i.ms == LaunchSequence.BEEP_MS ? t : Long.MIN_VALUE;
                    if (i.type == LaunchIntent.ARM) {
                        arms++;
                        assertNotNull(orderedMark, "run " + run + ": ARM without an accepted order");
                        assertTrue(heldValid, "run " + run + ": ARM with no valid mark");
                        assertEquals((int) orderedMark, heldId, "run " + run + ": ARM for another mark than ordered");
                        assertTrue(beepAt != Long.MIN_VALUE && t - beepAt >= LaunchSequence.BEEP_MS, "run " + run + ": ARM without the 2 s beep");
                        assertEquals(0, r.rollDeg > 45 ? 1 : 0, "run " + run + ": ARM while tipped");
                        assertTrue(r.gpsFix3d, "run " + run + ": ARM without GPS");
                    }
                }
            }
        }
        assertTrue(arms > 20, "the random runs did reach arming now and then: " + arms);
    }
}
