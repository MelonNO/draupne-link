package de.droiddrone.pc.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-060, Drone setup's side: CHECK NOW opens the motor test for the flight controller its first step read - the same
 * USB port and the same board id that CONFIRM is for - and the row "Motors and props" says "Checked." only when a
 * motor was turned in that window: closing it untouched marked the motors as checked. The step is in a guide that is
 * never shown; the motor test's window is not opened here (MotorTestBoardTest has it).
 */
@Timeout(60)
class DroneSetupMotorsCheckTest {
    private static final String CHECKED = DroneSetup.BetaflightPage.MOTORS_CHECKED_KEY;
    private final MotorsPageRig rig = new MotorsPageRig();

    @AfterEach
    void close() throws Exception {
        rig.close();
    }

    private void motorTestClosed(boolean turned) throws Exception {
        MotorsPageRig.onSwing(() -> rig.motorTestClosed.accept(turned));
    }

    @Test
    void checkNowOpensTheMotorTestForTheBoardDroneSetupRead() throws Exception {
        rig.board = new MotorTestDialog.Board("MADEUP7", "uid:4d4144452d55500000000007");
        rig.open(() -> null);
        rig.press("CHECK NOW");
        assertEquals(1, rig.motorTests);
        assertEquals(new MotorTestDialog.Board("MADEUP7", "uid:4d4144452d55500000000007"), rig.motorTestFor,
                "the same port and the same board id as Drone setup's CONFIRM");
        assertEquals("PENDING: Motor test open.", rig.shows(MotorsPageRig.MOTORS));
    }

    @Test
    void withNoBoardReadTheMotorTestIsOpenedForNone() throws Exception {
        rig.open(() -> null);
        rig.press("CHECK NOW");
        assertEquals(1, rig.motorTests);
        assertNull(rig.motorTestFor, "none read: the motor test then takes only a board that is alone on USB");
    }

    @Test
    void theRowSaysCheckedOnlyAfterAMotorWasTurned() throws Exception {
        rig.open(() -> null);
        assertEquals("INFO: Not sure? Check now.", rig.shows(MotorsPageRig.MOTORS));
        rig.press("CHECK NOW");
        motorTestClosed(false);
        assertEquals("INFO: No motor turned. Check now.", rig.shows(MotorsPageRig.MOTORS));
        assertNull(rig.saved.get(CHECKED), "nothing is saved as checked");
        rig.press("CHECK NOW");
        motorTestClosed(true);
        assertEquals("OK: Checked.", rig.shows(MotorsPageRig.MOTORS));
        assertEquals("true", rig.saved.get(CHECKED));
    }

    @Test
    void aCheckMadeBeforeStandsWhenTheWindowIsClosedWithNoMotorTurned() throws Exception {
        rig.saved.put(CHECKED, "true");
        rig.open(() -> null);
        assertEquals("OK: Checked.", rig.shows(MotorsPageRig.MOTORS));
        rig.press("CHECK NOW");
        motorTestClosed(false);
        assertEquals("OK: Checked.", rig.shows(MotorsPageRig.MOTORS));
    }

    @Test
    void alreadyCheckedIsThePilotsOwnWord() throws Exception {
        rig.open(() -> null);
        rig.press("ALREADY CHECKED");
        assertEquals("OK: Checked.", rig.shows(MotorsPageRig.MOTORS));
        assertEquals("true", rig.saved.get(CHECKED));
        assertEquals(0, rig.motorTests);
    }
}
