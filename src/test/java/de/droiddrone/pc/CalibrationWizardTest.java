package de.droiddrone.pc;

import de.droiddrone.pc.CalibrationWizard.StepKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simulates a TX16S in EdgeTX classic joystick mode with an AETR model: axis0 ail, axis1 ele (wired reversed),
 * axis2 thr, axis3 rud, axis4..7 switches. Sticks don't quite reach +-1 and centers are a little off.
 */
class CalibrationWizardTest {
    private long now = 0;

    private static Joystick.State state(float roll, float pitchRaw, float thr, float yaw, float sw1, boolean... buttons) {
        boolean[] b = new boolean[4];
        System.arraycopy(buttons, 0, b, 0, buttons.length);
        return new Joystick.State(new float[]{roll, pitchRaw, thr, yaw, sw1, -1f, -1f, -1f}, b);
    }

    private static Joystick.State rest() {
        return state(0.02f, -0.01f, -0.9f, 0.03f, -1f);
    }

    /**
     * The sticks swept smoothly from one state to the other and back and there again, in small steps (as a real stick
     * goes: the range step tells sticks from switches by that).
     */
    private void sweep(CalibrationWizard w, Joystick.State from, Joystick.State to) {
        Joystick.State[] legs = {from, to, from, to};
        for (int leg = 0; leg + 1 < legs.length; leg++) {
            float[] a = legs[leg].axes(), b = legs[leg + 1].axes();
            for (int k = 0; k <= 20; k++) {
                float[] x = new float[a.length];
                for (int i = 0; i < a.length; i++) x[i] = a[i] + (b[i] - a[i]) * k / 20f;
                w.update(new Joystick.State(x, legs[leg + 1].buttons()), now++);
            }
        }
    }

    /** Feeds a state long enough to pass the hold time. */
    private void hold(CalibrationWizard w, Joystick.State s) {
        w.update(s, now);
        now += 200;
        w.update(s, now);
        now += CalibrationWizard.HOLD_MS;
        w.update(s, now);
        now += 10;
    }

    private CalibrationWizard sweepAndCenter() {
        CalibrationWizard w = new CalibrationWizard(8, 4);
        assertEquals(StepKind.RANGE, w.getStep().kind());
        w.update(rest(), now++);
        assertFalse(w.canNext(), "sticks not swept yet");
        sweep(w, state(-0.85f, -0.9f, -0.9f, -0.9f, -1f), state(0.95f, 0.9f, 0.9f, 0.9f, -1f));
        assertTrue(w.canNext());
        w.next();
        assertEquals(StepKind.CENTER, w.getStep().kind());
        w.update(rest(), now);
        assertFalse(w.canNext(), "the sticks have to rest a moment first");
        now += CalibrationWizard.STILL_MS;
        w.update(rest(), now++);
        assertTrue(w.canNext());
        w.next();
        return w;
    }

    /** Like this project's TX16S: every stick reaches -1..1 and rests at 0. */
    private CalibrationWizard radioSweptTo(float centrePitch, long restMs) {
        CalibrationWizard w = new CalibrationWizard(8, 4);
        sweep(w, state(-1f, -1f, -1f, -1f, -1f), state(1f, 1f, 1f, 1f, -1f));
        w.next();
        w.update(state(0f, centrePitch, -1f, 0f, -1f), now);
        now += restMs;
        w.update(state(0f, centrePitch, -1f, 0f, -1f), now++);
        return w;
    }

    private void assignSticks(CalibrationWizard w) {
        hold(w, state(0f, 0f, 0.9f, 0f, -1f));   // throttle up
        hold(w, state(0f, 0f, -1f, 0.9f, -1f));  // yaw right
        hold(w, state(0f, 0.9f, -1f, 0f, -1f));  // pitch up
        hold(w, state(0.9f, 0f, -1f, 0f, -1f));  // roll right
    }

    @Test
    void theRangeStepGoesOnByItselfAWhileAfterEveryStickReachedItsLimits() {
        // the pilot (1.0.39): four channels at their limits, a little reserve time, then the centre step - no Next
        CalibrationWizard w = new CalibrationWizard(8, 4);
        sweep(w, state(-1f, -1f, -1f, -0.3f, -1f), state(1f, 1f, 1f, 0.3f, -1f));
        assertEquals(-1, w.rangeAutoNextInMs(now), "yaw not swept yet: no countdown");
        now += 5000;
        w.update(rest(), now);
        assertEquals(StepKind.RANGE, w.getStep().kind(), "three of four is not enough, however long");
        sweep(w, state(0f, 0f, 0f, -1f, -1f), state(0f, 0f, 0f, 1f, -1f));
        long in = w.rangeAutoNextInMs(now);
        assertTrue(in > CalibrationWizard.RANGE_RESERVE_MS - 100 && in <= CalibrationWizard.RANGE_RESERVE_MS, "all four: the reserve starts (" + in + ")");
        now += in - 10;
        w.update(rest(), now);
        assertEquals(StepKind.RANGE, w.getStep().kind(), "still the reserve time");
        now += 20;
        w.update(rest(), now);
        assertEquals(StepKind.CENTER, w.getStep().kind(), "on to centring by itself");
        assertEquals(-1, w.rangeAutoNextInMs(now));
    }

    @Test
    void theCentreStepsCentreIsWhatTheFlightControllerGetsAsTheMiddleThrottleToo() {
        // the pilot (1.0.39): every stick centred at step 2, and that centre goes out as 1500 - the throttle as well
        CalibrationWizard w = new CalibrationWizard(8, 4);
        sweep(w, state(-0.9f, -0.95f, -0.92f, -0.9f, -1f), state(0.95f, 0.9f, 0.88f, 0.9f, -1f));
        w.next();
        Joystick.State centred = state(0.04f, -0.03f, 0.06f, 0.02f, -1f); // gimbals rest a little off the raw middle
        w.update(centred, now);
        now += CalibrationWizard.STILL_MS;
        w.update(centred, now++);
        w.next();
        assignSticks(w);
        while (w.getStep().kind() != StepKind.SUMMARY) w.skip();
        short[] ch = w.buildMapper().map(centred.axes(), centred.buttons());
        for (int i = 0; i <= PcConfig.THROTTLE_INDEX; i++) assertEquals(1500, ch[i], PcConfig.CHANNEL_NAMES[i] + " at the step-2 centre");
        short[] down = w.buildMapper().map(state(0.04f, -0.03f, -1f, 0.02f, -1f).axes(), centred.buttons()); // the lowest it went
        assertEquals(1000, down[PcConfig.THROTTLE_INDEX], "throttle down is still 1000 - the motors' start rule holds");
    }

    @Test
    void theFourSticksAreFoundInTheFirstStepAndOnlyTheyCanBeSticks() {
        // the pilot: "detect only four channels as the joysticks ... locked ... so no other channel can use it": a
        // switch flipped while the sticks circle is not taken for a stick, and can never become pitch afterwards
        CalibrationWizard w = new CalibrationWizard(8, 4);
        for (int k = 0; k < 6; k++) { // the sticks circle, the switch on axis4 is flipped back and forth meanwhile (a jump)
            float sw = k % 2 == 0 ? -1f : 1f;
            sweep(w, state(-1f, -1f, -1f, -1f, sw), state(1f, 1f, 1f, 1f, sw));
            w.update(state(1f, 1f, 1f, 1f, -sw), now++);
        }
        assertEquals(CalibrationWizard.STICK_AXES, w.getFullySweptAxisCount());
        assertFalse(w.isFullySwept(4), "the switch jumped: not a stick, though it went end to end");
        w.next();
        assertArrayEquals(new int[]{0, 1, 2, 3}, w.getStickAxes(), "the four sticks, locked");
        Joystick.State centred = state(0f, 0f, 0f, 0f, -1f);
        w.update(centred, now);
        now += CalibrationWizard.STILL_MS;
        w.update(centred, now++);
        w.next();
        assertEquals(StepKind.ASSIGN, w.getStep().kind(), "throttle up");
        hold(w, state(0f, 0f, 0f, 0f, 1f)); // the switch, all the way: not a stick
        assertEquals(StepKind.ASSIGN, w.getStep().kind());
        assertNull(w.getSpec(PcConfig.THROTTLE_INDEX), "a switch can never be the throttle");
        assignSticks(w);
        assertEquals(StepKind.SWITCH, w.getStep().kind());
        hold(w, state(0f, 0.9f, 0f, 0f, -1f)); // a stick moved at a switch step: not taken
        assertNull(w.getSpec(4), "a stick can never be AUX1");
        hold(w, state(0f, 0f, 0f, 0f, 1f));
        assertEquals("axis4", w.getSpec(4), "the switch is AUX1");
    }

    @Test
    void aRadioThatSendsOnly1800IsCalibratedToItsOwnEnds() {
        // the pilot: "if the user does not use 2000 but only 1800 that is then max" - a radio set to 80 %: raw +-0.6
        CalibrationWizard w = new CalibrationWizard(8, 4);
        sweep(w, state(-0.6f, -0.6f, -0.6f, -0.6f, -1f), state(0.6f, 0.6f, 0.6f, 0.6f, -1f));
        assertEquals(CalibrationWizard.STICK_AXES, w.getFullySweptAxisCount(), "its own limits count (up to 1.0.38 it was stuck here)");
        // still pushing further out: the wait starts again, so its real ends are in before it goes on
        now += CalibrationWizard.RANGE_RESERVE_MS - 100;
        w.update(state(0.62f, 0f, 0f, 0f, -1f), now);
        now += 200;
        w.update(state(0f, 0f, 0f, 0f, -1f), now);
        assertEquals(StepKind.RANGE, w.getStep().kind(), "a limit grew: waiting again");
        now += CalibrationWizard.RANGE_RESERVE_MS;
        w.update(state(0f, 0f, 0f, 0f, -1f), now);
        assertEquals(StepKind.CENTER, w.getStep().kind(), "limits still for the reserve time: on to centring");
        Joystick.State centred = state(0f, 0f, 0f, 0f, -1f);
        w.update(centred, now);
        now += CalibrationWizard.STILL_MS;
        w.update(centred, now++);
        w.next();
        hold(w, state(0f, 0f, 0.6f, 0f, -1f));  // throttle up
        hold(w, state(0f, 0f, 0f, 0.6f, -1f));  // yaw right
        hold(w, state(0f, 0.6f, 0f, 0f, -1f));  // pitch up
        hold(w, state(0.6f, 0f, 0f, 0f, -1f));  // roll right
        while (w.getStep().kind() != StepKind.SUMMARY) w.skip();
        short[] top = w.buildMapper().map(state(0.62f, 0.6f, 0.6f, 0.6f, -1f).axes(), new boolean[4]);
        short[] bottom = w.buildMapper().map(state(-0.6f, -0.6f, -0.6f, -0.6f, -1f).axes(), new boolean[4]);
        for (int i = 0; i <= PcConfig.THROTTLE_INDEX; i++) {
            assertEquals(2000, top[i], PcConfig.CHANNEL_NAMES[i] + ": its own top is 2000");
            assertEquals(1000, bottom[i], PcConfig.CHANNEL_NAMES[i] + ": its own bottom is 1000");
        }
        Map<String, String> saved = w.result();
        assertEquals("0.6200", saved.get("axis0.max"), "the furthest it went is saved");
        assertEquals("-0.6000", saved.get("axis0.min"));
    }

    @Test
    void anOldCalibrationWithTheThrottleCentreAtTheBottomStaysLinear() {
        // before 1.0.39 the centre step took the throttle down: as 1500 that would idle the motors at half throttle
        AxisCalibration old = new AxisCalibration(-0.95f, -0.94f, 0.95f);
        assertEquals(1000, old.toThrottle(-0.95f));
        assertEquals(1500, old.toThrottle(0f), 2);
        AxisCalibration centred = new AxisCalibration(-0.95f, 0.1f, 0.95f);
        assertEquals(1500, centred.toThrottle(0.1f), "a centre in the middle of the range: 1500");
        assertEquals(1000, centred.toThrottle(-0.95f));
        assertEquals(2000, centred.toThrottle(0.95f));
    }

    @Test
    void theCentreIsTakenOnlyOnceTheSticksRestAndAveraged() {
        CalibrationWizard w = radioSweptTo(0f, 100);
        assertFalse(w.canNext(), "100 ms is not resting yet");
        w.update(state(0f, 0.2f, -1f, 0f, -1f), now);  // a thumb on the stick: starts over
        now += CalibrationWizard.STILL_MS - 50;
        w.update(state(0f, 0.004f, -1f, 0f, -1f), now);
        assertFalse(w.canNext());
        w.update(state(0f, 0f, -1f, 0f, -1f), now + 1);
        w.update(state(0f, -0.004f, -1f, 0f, -1f), now + 2);
        now += CalibrationWizard.STILL_MS;
        w.update(state(0f, 0f, -1f, 0f, -1f), now++);
        assertTrue(w.canNext());
        w.next();
        assertEquals(0f, w.getCenter()[1], 0.003f, "the average of the resting readings, not the thumb's 0.2");
    }

    @Test
    void aCentreOffTheMiddleIsFlagged() {
        // this project's drone: pitch centre captured at -0.034 while the stick rests at 0 - 1518 sent at rest
        CalibrationWizard w = radioSweptTo(-0.034f, CalibrationWizard.STILL_MS);
        w.next();
        assignSticks(w);
        assertEquals(List.of("PITCH 17 us"), w.offCenterSticks());

        CalibrationWizard good = radioSweptTo(0.004f, CalibrationWizard.STILL_MS);
        good.next();
        assignSticks(good);
        assertTrue(good.offCenterSticks().isEmpty(), "2 us is noise");
    }

    @Test
    void fullCalibrationDetectsSticksDirectionsAndSwitches() {
        CalibrationWizard w = sweepAndCenter();

        assertEquals(PcConfig.THROTTLE_INDEX, w.getStep().channel());
        hold(w, state(0.02f, -0.01f, 0.9f, 0.03f, -1f));
        assertEquals("axis2", w.getSpec(PcConfig.THROTTLE_INDEX));

        hold(w, state(0.02f, -0.01f, -0.9f, 0.9f, -1f));      // yaw right
        assertEquals("axis3", w.getSpec(2));
        hold(w, state(0.02f, -0.9f, -0.9f, 0.03f, -1f));      // pitch up: raw goes negative on this radio
        assertEquals("-axis1", w.getSpec(1));
        hold(w, state(0.95f, -0.01f, -0.9f, 0.03f, -1f));     // roll right
        assertEquals("axis0", w.getSpec(0));

        assertEquals(StepKind.SWITCH, w.getStep().kind());
        w.update(rest(), now++);                                // sticks back; baseline was captured on entry
        hold(w, state(0.02f, -0.01f, -0.9f, 0.03f, 1f));      // AUX1 = arm switch on axis4
        assertEquals("axis4", w.getSpec(4));
        hold(w, state(0.02f, -0.01f, -0.9f, 0.03f, 1f, false, false, true)); // AUX2 = button 2
        assertEquals("button2", w.getSpec(5));
        w.skip();
        w.skip();
        assertEquals(StepKind.SUMMARY, w.getStep().kind());

        Map<String, String> result = w.result();
        assertEquals("axis0", result.get("roll"));
        assertEquals("-axis1", result.get("pitch"));
        assertEquals("axis3", result.get("yaw"));
        assertEquals("axis2", result.get("throttle"));
        assertEquals("axis4", result.get("aux1"));
        assertEquals("button2", result.get("aux2"));
        assertEquals("", result.get("aux3"));
        assertEquals("-0.9000", result.get("axis2.min"));
        assertEquals("0.9000", result.get("axis2.max"));

        RcMapper mapper = w.buildMapper();
        short[] ch = mapper.map(state(0.02f, -0.9f, -0.9f, 0.03f, 1f, false, false, true).axes(),
                new boolean[]{false, false, true, false});
        assertEquals(1500, ch[0], "roll at its measured center");
        assertEquals(2000, ch[1], "pitch stick up -> 2000 despite the reversed axis");
        assertEquals(1500, ch[2], "yaw at its measured center");
        assertEquals(1000, ch[3], "throttle at its measured bottom");
        assertEquals(2000, ch[4], "arm switch on");
        assertEquals(2000, ch[5]);
    }

    @Test
    void letGoBeforeHoldTimeDoesNotAssign() {
        CalibrationWizard w = sweepAndCenter();
        Joystick.State up = state(0.02f, -0.01f, 0.9f, 0.03f, -1f);
        w.update(up, now);
        w.update(up, now + 200);
        w.update(rest(), now + 300);
        w.update(up, now + 500);
        w.update(up, now + 600);
        assertNull(w.getSpec(PcConfig.THROTTLE_INDEX));
        assertEquals(PcConfig.THROTTLE_INDEX, w.getStep().channel());
    }

    @Test
    void assignedAxisIsNotPickedTwice() {
        CalibrationWizard w = sweepAndCenter();
        hold(w, state(0.02f, -0.01f, 0.9f, 0.03f, -1f));      // throttle
        hold(w, state(0.02f, -0.01f, 0.9f, 0.03f, -1f));      // throttle still up during the yaw step
        assertNull(w.getSpec(2), "throttle axis must not become yaw");
        assertEquals(2, w.getStep().channel());
    }

    @Test
    void backForgetsLaterAssignments() {
        CalibrationWizard w = sweepAndCenter();
        hold(w, state(0.02f, -0.01f, 0.9f, 0.03f, -1f));
        hold(w, state(0.02f, -0.01f, -0.9f, 0.9f, -1f));
        assertEquals("axis3", w.getSpec(2));
        w.back();
        assertEquals(2, w.getStep().channel());
        assertNull(w.getSpec(2));
        assertEquals("axis2", w.getSpec(PcConfig.THROTTLE_INDEX));
    }

    @Test
    void untouchedAuxAxisGetsNominalCalibration() {
        CalibrationWizard w = sweepAndCenter();
        hold(w, state(0.02f, -0.01f, 0.9f, 0.03f, -1f));
        hold(w, state(0.02f, -0.01f, -0.9f, 0.9f, -1f));
        hold(w, state(0.02f, -0.9f, -0.9f, 0.03f, -1f));
        hold(w, state(0.95f, -0.01f, -0.9f, 0.03f, -1f));
        w.update(rest(), now++);
        // a 3-position switch flipped only to its middle: range 1.0 -> still trusted; here only 0.6 -> nominal
        hold(w, state(0.02f, -0.01f, -0.9f, 0.03f, -0.4f));
        assertEquals("axis4", w.getSpec(4));
        assertEquals(new AxisCalibration(-1f, 0f, 1f), w.getCalibrations().get(4));
    }

    @Test
    void switchStepsAreNamedAfterWhatDroneSetupPutOnThem() {
        de.droiddrone.pc.setup.ModeSwitch.Purpose[] p = new de.droiddrone.pc.setup.ModeSwitch.Purpose[8];
        p[0] = new de.droiddrone.pc.setup.ModeSwitch.Purpose("ARM", "arm hint");
        p[1] = new de.droiddrone.pc.setup.ModeSwitch.Purpose("flight modes", "modes hint");
        p[3] = new de.droiddrone.pc.setup.ModeSwitch.Purpose("GPS rescue", "rescue hint");
        CalibrationWizard w = new CalibrationWizard(8, 4, p);
        List<String> titles = w.steps().stream().map(CalibrationWizard.Step::title).toList();
        assertTrue(titles.contains("AUX1 - ARM"), titles.toString());
        assertTrue(titles.contains("AUX2 - flight modes"));
        assertTrue(titles.contains("AUX3 - reserve"), "nothing chosen for it: a spare");
        assertTrue(titles.contains("AUX4 - GPS rescue"));
        assertEquals(4, w.switchCount());
        p[5] = new de.droiddrone.pc.setup.ModeSwitch.Purpose("GPS rescue", "on AUX6");
        assertEquals(6, new CalibrationWizard(8, 4, p).switchCount(), "a chosen switch above AUX4 is asked for too");
    }
}
