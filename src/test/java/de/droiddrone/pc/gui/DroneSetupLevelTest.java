package de.droiddrone.pc.gui;

import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-063: CALIBRATE (the accelerometer's level, Drone setup's second step) pressed with no flight controller waited
 * without limit and was done on the next board plugged in - which may be held in a hand, and is then saved as "flat".
 * Here a calibration that finds no board is dropped at once and said, and one asked of a board is never done on
 * another. The step is in a guide that is never shown; the boards are made up (LevelBoard), no real port.
 */
@Timeout(60)
class DroneSetupLevelTest {
    private MotorsPageRig rig;

    @AfterEach
    void close() throws Exception {
        if (rig != null) rig.close();
    }

    private void open() throws Exception {
        rig = new MotorsPageRig();
        rig.open(); // Drone setup has read no board: the one that is alone on USB
    }

    private LevelBoard plug(String port) {
        return LevelBoard.plug(rig.usb, port, port.equals("MADEUP1") ? 1 : 2);
    }

    @Test
    void calibratePressedWithNoBoardIsDroppedAtOnceAndSaid() throws Exception {
        open();
        rig.press("CALIBRATE");
        assertEquals("WARN: No flight controller: not calibrated. USB to this PC, then Calibrate.", rig.shows(MotorsPageRig.LEVEL));
    }

    @Test
    void calibratePressedWithNoBoardIsNeverDoneOnABoardPluggedInLater() throws Exception {
        open();
        rig.press("CALIBRATE");
        LevelBoard later = plug("MADEUP1");
        Waits.await(() -> later.attitudes() >= 3, 20_000, "the live view to follow the board plugged in later");
        assertEquals(0, later.calibrations(), "the board plugged in after the press is not calibrated");
        assertTrue(rig.shows(MotorsPageRig.LEVEL).contains("not calibrated"), rig.shows(MotorsPageRig.LEVEL));
    }

    /**
     * The board goes between the press and the calibration: the live thread is held in a request to board A (the
     * test holds the made-up board, which then answers nothing), CALIBRATE is pressed, A's cable comes out and
     * board B goes in. B must not be calibrated for a press that was made with A on the table.
     */
    @Test
    void aCalibrationAskedOfABoardThatIsLostIsNotDoneOnTheNextBoard() throws Exception {
        open();
        LevelBoard a = plug("MADEUP1");
        Waits.await(() -> a.attitudes() >= 2, 20_000, "the live view to follow board A");
        Thread live = MotorsPageRig.liveThread();
        LevelBoard b;
        synchronized (a) {
            Waits.await(() -> live.getState() == Thread.State.BLOCKED, 10_000, "the live thread to wait for board A");
            rig.press("CALIBRATE");
            a.unplug();
            rig.usb.unplug("MADEUP1");
            b = plug("MADEUP2");
        }
        LevelBoard other = b;
        Waits.await(() -> other.attitudes() >= 3, 20_000, "the live view to follow board B");
        assertEquals(0, other.calibrations(), "board B is not calibrated for a press made with board A there");
        assertEquals(0, a.calibrations());
        assertEquals("WARN: Flight controller lost: not calibrated. Calibrate again.", rig.shows(MotorsPageRig.LEVEL));
    }

    /**
     * F-079: Betaflight answers MSP_ACC_CALIBRATION OK while armed and does nothing (msp.c) - the row said
     * "Calibrated: reads flat." for a level never taken. Now an armed board is not asked, and the row says why.
     */
    @Test
    void anArmedBoardIsNotCalibratedAndTheRowSaysItIsArmed() throws Exception {
        open();
        LevelBoard board = plug("MADEUP1");
        Waits.await(() -> board.attitudes() >= 1, 20_000, "the live view to follow the board");
        board.arm();
        rig.press("CALIBRATE");
        Waits.waitForOnSwing(() -> rig.says(MotorsPageRig.LEVEL).equals("The drone is armed. Disarm it first."), 15_000,
                "the row to say the board is armed");
        assertEquals("WARN: The drone is armed. Disarm it first.", rig.shows(MotorsPageRig.LEVEL));
        assertFalse(board.requests().contains(DroneSetup.MotorsPage.MSP_ACC_CALIBRATION), "not asked: " + board.requests());
        int before = board.attitudes();
        Waits.await(() -> board.attitudes() >= before + 3, 10_000, "the live view to go on with the same board");
    }

    @Test
    void withABoardThereCalibrateLevelsThatBoard() throws Exception {
        open();
        LevelBoard board = plug("MADEUP1");
        Waits.await(() -> board.attitudes() >= 1, 20_000, "the live view to follow the board");
        rig.press("CALIBRATE");
        assertEquals("PENDING: Calibrating. Keep it still...", rig.shows(MotorsPageRig.LEVEL));
        Waits.await(() -> board.calibrations() == 1, 10_000, "the board to be told to calibrate");
        Waits.waitForOnSwing(() -> rig.says(MotorsPageRig.LEVEL).equals("Calibrated: reads flat."), 15_000, "the row to say it is calibrated");
        assertEquals(1, board.calibrations(), "once");
    }
}
