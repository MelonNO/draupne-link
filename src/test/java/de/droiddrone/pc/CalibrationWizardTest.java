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
        w.update(state(-0.85f, -0.9f, -0.9f, -0.9f, -1f), now++);
        w.update(state(0.95f, 0.9f, 0.9f, 0.9f, -1f), now++);
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
        w.update(state(-1f, -1f, -1f, -1f, -1f), now++);
        w.update(state(1f, 1f, 1f, 1f, -1f), now++);
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
}
