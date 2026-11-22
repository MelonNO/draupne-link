package de.droiddrone.pc.gui;

import de.droiddrone.bench.FakeUsb;
import de.droiddrone.bench.hw.FakeFlightBoard;
import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.GpsSearch;
import de.droiddrone.pc.setup.SetupBoard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-078 in Drone setup: on an armed board CONFIRM switched the receiver over MSP and then got no CLI - Betaflight
 * hears no '#' while armed - and the pilot read "No answer from the flight controller - is it running Betaflight?",
 * then "Replug the drone's USB."; FIND GPS stopped the same way. Now the receiver is switched only after the board
 * said it is not armed, and what the pilot reads is "The drone is armed. Disarm it first." Made-up board, no real port.
 */
@Timeout(30)
class DroneSetupArmedTest {
    private static final String ARMED = "The drone is armed. Disarm it first.";
    private static final int MSP_SET_ARMING_DISABLED = 99, MSP_SET_FEATURE_CONFIG = 37;
    private final FakeUsb usb = new FakeUsb();
    private final FakeFlightBoard board = new FakeFlightBoard("MADEUP1");
    @TempDir
    Path dir;

    @AfterEach
    void close() {
        BetaflightUsb.use(null);
    }

    private void plug() {
        board.uid = MotorTestRig.uid(1);
        usb.plug("MADEUP1", "Made-up Betaflight board", board::connect);
        BetaflightUsb.use(usb);
    }

    private BetaflightUsb open() throws IOException {
        plug();
        return BetaflightUsb.open(usb.ports().get(0));
    }

    @Test
    void confirmSwitchesNoReceiverOnAnArmedBoard() throws Exception {
        board.arm();
        int mask = board.featureMask();
        assertFalse(BetaflightPlan.isMspReceiver(mask), "a board CONFIRM switches to the phone's receiver");
        try (BetaflightUsb fc = open()) {
            IOException e = assertThrows(IOException.class, () -> DroneSetup.BetaflightPage.enterCli(fc, true, mask));
            assertEquals(ARMED, e.getMessage());
        }
        assertEquals(mask, board.featureMask(), "the receiver is not switched");
        assertFalse(board.mspRequests().contains(MSP_SET_FEATURE_CONFIG), board.mspRequests().toString());
        assertTrue(board.armed());
    }

    @Test
    void disarmedTheReceiverIsSwitchedWithArmingBlockedThenTheCli() throws Exception {
        int mask = board.featureMask();
        try (BetaflightUsb fc = open()) {
            DroneSetup.BetaflightPage.enterCli(fc, true, mask);
            assertTrue(board.inCli());
            assertTrue(BetaflightPlan.isMspReceiver(board.featureMask()), "switched in memory (CONFIRM's save keeps it)");
            List<Integer> asked = board.mspRequests();
            assertTrue(asked.indexOf(MSP_SET_ARMING_DISABLED) >= 0 && asked.indexOf(MSP_SET_ARMING_DISABLED) < asked.indexOf(MSP_SET_FEATURE_CONFIG),
                    "arming blocked before the receiver switch: " + asked);
        }
    }

    @Test
    void theUsbRowSaysArmedAndNeverReplug() {
        assertEquals(ARMED + "<br>Retrying, or press <b>Retry</b>.", DroneSetup.BetaflightPage.readProblem(ARMED, 1));
        assertFalse(DroneSetup.BetaflightPage.readProblem(ARMED, 9).contains("Replug"), "replugging doesn't disarm it");
        assertTrue(DroneSetup.BetaflightPage.readProblem("No answer.", 9).contains("Replug the drone's USB."));
    }

    @Test
    void theChecksSayArmedAsItIs() {
        assertEquals(ARMED, OrientationCheckDialog.lostLine(ARMED), "not 'Lost': the board is there, and armed");
        assertEquals("Lost: The flight controller disconnected", OrientationCheckDialog.lostLine("The flight controller disconnected"));
    }

    @Test
    void findGpsOnAnArmedBoardSaysItIsArmed() throws Exception {
        board.arm();
        plug();
        GpsSearch.Result r = new GpsSearch(() -> DroneSetup.BetaflightPage.gpsBoard(new SetupBoard("MADEUP1", MotorTestRig.id(1))),
                (diff, version) -> {
                    try {
                        return Files.writeString(dir.resolve("before.txt"), diff);
                    } catch (IOException e) {
                        return null;
                    }
                }, line -> { }, line -> { }, () -> true).run(); // "the page was left": no UART is tried
        assertEquals("Stopped: " + ARMED, r.problem());
        assertEquals(List.of(), board.cli(), "no CLI command reached it");
        assertTrue(board.armed());
    }
}
