package de.droiddrone.pc.gui;

import de.droiddrone.bench.hw.FakeFlightBoard;
import de.droiddrone.pc.setup.MotorTest;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-061: a test motor stops by the sending thread's own clock. Every stop the window had was a Swing event (the 20 s
 * auto-stop, a click's 3 s, SPACE, leaving the window), and Betaflight keeps the last value it was sent: a window that
 * hung left the motor turning. Here the Swing thread is held as a hung window holds it, and the made-up board must
 * still be told STOP - with limits short enough that no test waits 20 s. Never shown; no real port.
 */
@Timeout(60)
class MotorTestLimitsTest {
    private static final long NEVER = 600_000;
    private static final int RAISED = 1050;
    private MotorTestRig rig;

    @AfterEach
    void close() throws Exception {
        if (rig != null) rig.close();
    }

    private FakeFlightBoard open(long autoStopMs, long pulseMs, long silentMs) throws Exception {
        rig = new MotorTestRig();
        FakeFlightBoard board = rig.plug("MADEUP1", 1);
        rig.open(new MotorLimits(autoStopMs, pulseMs, silentMs, MotorLimits.now()));
        rig.connectAndTick();
        return board;
    }

    @Test
    void aMotorStopsWhenTheWindowHangs() throws Exception {
        FakeFlightBoard board = open(NEVER, NEVER, 1500);
        rig.hangSwing(() -> rig.window.sliders.get(0).set(RAISED)); // raised, and from then on the window answers nothing
        Waits.await(() -> board.motors()[0] == RAISED, 5000, "motor 1 to turn");
        Waits.await(() -> MotorTestRig.allStopped(board), 10_000, "every motor stopped though the window hangs");
        MotorTestRig.afterMoreMotorFrames(board);
        assertTrue(MotorTestRig.allStopped(board), "and they stay stopped");
        // clicks made while it hung start nothing when it answers again; the box is unticked and the window says why
        javax.swing.SwingUtilities.invokeLater(() -> rig.window.pulse(2));
        rig.swingGoesOn();
        Waits.waitForOnSwing(() -> !rig.box().isSelected(), 5000, "the box unticked after the window answered again");
        MotorTestRig.afterMoreMotorFrames(board);
        assertTrue(MotorTestRig.allStopped(board), "a click queued while the window hung starts no motor");
        assertTrue(rig.text().contains("Window froze: motors stopped."), rig.text());
        MotorTestRig.onSwing(() -> assertEquals(MotorTest.STOP, rig.window.sliders.get(0).value, "the slider is back at 0"));
        assertTrue(rig.log.stream().anyMatch(l -> l.contains("window did not answer")), rig.log.toString());
    }

    @Test
    void aClicksMotorStopsAfterItsTimeWithNoSwingEvent() throws Exception {
        FakeFlightBoard board = open(NEVER, 1500, NEVER);
        MotorTestRig.onSwing(() -> rig.window.sliders.get(1).set(RAISED)); // motor 2 by its slider: not a click's
        rig.hangSwing(() -> rig.window.pulse(1)); // Swing's own timer for the click can no longer fire
        Waits.await(() -> board.motors()[0] > MotorTest.STOP, 5000, "motor 1 to turn");
        Waits.await(() -> board.motors()[0] == MotorTest.STOP, 10_000, "motor 1 stopped after the click's time");
        assertEquals(RAISED, board.motors()[1], "only the click's motor: the slider's keeps its value");
    }

    @Test
    void everyMotorStopsAfterTheAutoStopTimeWithNoSwingEvent() throws Exception {
        FakeFlightBoard board = open(1500, NEVER, NEVER);
        rig.hangSwing(() -> rig.window.sliders.get(0).set(RAISED));
        Waits.await(() -> board.motors()[0] == RAISED, 5000, "motor 1 to turn");
        Waits.await(() -> MotorTestRig.allStopped(board), 10_000, "every motor stopped after the auto-stop time");
        rig.swingGoesOn();
        Waits.waitForOnSwing(() -> rig.text().contains("Auto-stopped after"), 5000, "the window to say why");
        assertTrue(rig.box().isSelected(), "the box stays ticked: the window was there, a slider goes on");
    }

    /**
     * The cable out and in again while a motor runs and the window hangs: what was asked of the lost board is over
     * with it. The thread that sends forgets it by itself - the window's own stop for a lost board is a Swing event.
     */
    @Test
    void aBoardLostAndBackIsNotSentTheOldValuesAgain() throws Exception {
        FakeFlightBoard board = open(NEVER, NEVER, NEVER);
        rig.hangSwing(() -> rig.window.sliders.get(0).set(RAISED));
        Waits.await(() -> board.motors()[0] == RAISED, 5000, "motor 1 to turn");
        int opensBefore = board.opens;
        board.cutWire(); // the board starts again with its motors stopped; its port is there for the next open
        Waits.await(() -> board.opens > opensBefore, 15_000, "the motor test to open the board again");
        MotorTestRig.afterMoreMotorFrames(board);
        assertTrue(MotorTestRig.allStopped(board), "motor 1 is not started again by what was asked before the board was lost");
    }

    // ------------------------------------------------------------------ the limits themselves, on a made-up clock

    @Test
    void theLimitsAreTheOnesTheWindowPromises() {
        MotorLimits l = new MotorLimits(0);
        assertEquals(20_000, l.autoStopMs);
        assertEquals(3000, l.pulseMs);
        assertEquals(1000, l.silentMs, "a second: five times sooner than the app's freeze watch (5 s)");
        assertTrue(MotorLimits.BEAT_MS * 5 <= l.silentMs, "several beats are missed before the motors stop");
    }

    @Test
    void aWindowThatAnswersKeepsItsMotorUntilTheAutoStop() {
        MotorLimits l = new MotorLimits(0);
        l.ask(new int[]{RAISED}, 0);
        assertArrayEquals(new int[]{RAISED}, l.allowed(0), "sent: the 20 s count from here");
        for (long t = 0; t < 20_000; t += 100) {
            l.alive(t);
            assertArrayEquals(new int[]{RAISED}, l.allowed(t + 50), "running at " + t + " ms");
        }
        l.alive(20_000);
        assertArrayEquals(new int[0], l.allowed(20_000), "20 s: all stop");
        assertEquals(MotorLimits.Cut.AUTO_STOP, l.takeCut());
        assertNull(l.takeCut(), "said once");
    }

    @Test
    void aSilentWindowStopsThemAndNothingStartsBeforeItHasTakenThat() {
        MotorLimits l = new MotorLimits(0);
        l.ask(new int[]{RAISED, RAISED}, 0);
        assertArrayEquals(new int[]{RAISED, RAISED}, l.allowed(999));
        assertArrayEquals(new int[0], l.allowed(1000), "no word from the Swing thread for a second");
        l.ask(new int[]{RAISED}, 5000); // a click that waited in the hung window's queue
        assertArrayEquals(new int[0], l.allowed(5001), "starts nothing");
        assertEquals(MotorLimits.Cut.WINDOW_SILENT, l.takeCut());
        l.ask(new int[]{RAISED}, 5100);
        assertArrayEquals(new int[]{RAISED}, l.allowed(5101), "asked anew after that: it runs");
    }

    @Test
    void nothingIsCutWhileNoMotorRuns() {
        MotorLimits l = new MotorLimits(0);
        assertArrayEquals(new int[0], l.allowed(60_000), "a silent window with nothing running");
        assertNull(l.takeCut());
        l.ask(new int[]{MotorTest.STOP, MotorTest.STOP}, 60_000);
        assertArrayEquals(new int[]{MotorTest.STOP, MotorTest.STOP}, l.allowed(120_000));
        assertNull(l.takeCut());
    }

    @Test
    void aClickEndsItsOwnMotorOnlyAndAMovedSliderIsThePilotsAgain() {
        MotorLimits l = new MotorLimits(NEVER, 3000, NEVER, 0);
        l.ask(new int[]{1020, RAISED}, 0);
        l.pulse(1, 0);
        assertArrayEquals(new int[]{1020, RAISED}, l.allowed(2999));
        assertArrayEquals(new int[]{MotorTest.STOP, RAISED}, l.allowed(3000), "the click's motor, after 3 s");
        l.ask(new int[]{1020, RAISED}, 3500); // another slider moved while the click's still shows its value
        assertArrayEquals(new int[]{MotorTest.STOP, RAISED}, l.allowed(3501), "the ended click stays ended");
        l.pulse(1, 4000); // clicked again
        assertArrayEquals(new int[]{1020, RAISED}, l.allowed(4001));
        l.ask(new int[]{1030, RAISED}, 4500); // the pilot moved its slider: no longer the click's
        assertArrayEquals(new int[]{1030, RAISED}, l.allowed(60_000));
        assertNull(l.takeCut(), "a click's end is no cut: nothing to say");
    }

    @Test
    void theAutoStopCountsFromWhenAMotorStartedAndAnewAfterAStop() {
        MotorLimits l = new MotorLimits(20_000, NEVER, NEVER, 0);
        l.ask(new int[]{RAISED}, 0);
        assertArrayEquals(new int[]{RAISED}, l.allowed(0));
        assertArrayEquals(new int[]{RAISED}, l.allowed(10_000));
        l.stop();
        assertArrayEquals(new int[0], l.allowed(10_100));
        l.ask(new int[]{RAISED}, 15_000);
        assertArrayEquals(new int[]{RAISED}, l.allowed(15_000));
        assertArrayEquals(new int[]{RAISED}, l.allowed(34_999), "20 s from the new start");
        assertArrayEquals(new int[0], l.allowed(35_000));
    }
}
