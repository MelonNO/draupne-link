package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightUsb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A board made safe after whatever a test left behind ({@link BoardSafe}), against the made-up board. */
class BoardSafeTest {
    private static final BoardPort.Timing T = FakeFlightBoard.NO_WAITING;
    private static final int[] AT_REST = {1000, 1000, 1000, 1000};
    private final FakeFlightBoard board = new FakeFlightBoard();
    private final List<String> log = new ArrayList<>();

    @BeforeEach
    void plugIn() {
        BetaflightUsb.use(board.usb());
    }

    @AfterEach
    void thisPcsPortsAgain() {
        BetaflightUsb.use(null);
    }

    private BoardSafe.Result makeSafe() {
        return BoardSafe.run(board.port(), T, null, log::add);
    }

    /** Motor values over USB, as a test that then died left them: Betaflight keeps them. */
    private static void leaveOn(BetaflightUsb fc, int... values) throws IOException {
        byte[] p = Msp.allMotors(1000);
        for (int i = 0; i < values.length; i++) Msp.putU16(p, 2 * i, values[i]);
        fc.msp(Msp.SET_MOTOR, p);
    }

    @Test
    void motorsATestLeftOnAreStoppedAndReadBackAtRest() throws IOException {
        try (BetaflightUsb fc = BetaflightUsb.open(board.port())) {
            leaveOn(fc, 1080, 1000, 1100, 1000);
        }
        assertArrayEquals(new int[]{1080, 1000, 1100, 1000}, board.motors(), "the port let go of: the motors stay on");
        BoardSafe.Result r = makeSafe();
        assertArrayEquals(AT_REST, board.motors());
        assertArrayEquals(AT_REST, r.motors());
        assertTrue(r.atRest());
        assertFalse(r.armed());
        assertTrue(r.safe());
        assertNull(r.problem());
        assertEquals("SAFE: motors read [1000, 1000, 1000, 1000] (at rest), disarmed.", r.text());
        assertEquals(0, board.saves + board.restarts, "nothing saved, nothing restarted");
        assertTrue(makeSafe().safe(), "and again, on a board that is at rest already");
    }

    @Test
    void anArmedBoardIsNotSafeAndIsSaidSo() {
        board.arm();
        BoardSafe.Result r = makeSafe();
        assertFalse(r.safe());
        assertTrue(r.armed());
        assertFalse(r.atRest(), "armed motors take no value from USB");
        assertTrue(r.text().startsWith("NOT SAFE: motors read [1060, 1060, 1060, 1060] (NOT AT REST), ARMED."), r.text());
        assertTrue(r.text().contains("disarm at the radio"), r.text());
        board.disarm();
        assertTrue(makeSafe().safe());
    }

    @Test
    void in3dModeStopIsTheMiddle() throws IOException {
        board.features(FakeFlightBoard.DEFAULT_FEATURES | Msp.FEATURE_3D);
        try (BetaflightUsb fc = BetaflightUsb.open(board.port())) {
            leaveOn(fc, 1000, 1000, 1000, 1000); // full reverse there
        }
        BoardSafe.Result r = makeSafe();
        assertArrayEquals(new int[]{1500, 1500, 1500, 1500}, board.motors());
        assertTrue(r.safe(), r.text());
        assertFalse(BoardSafe.atRest(new int[]{1500, 1000}, Msp.STOP_3D));
        assertFalse(BoardSafe.atRest(new int[]{1500, 1520}, Msp.STOP_3D));
        assertTrue(BoardSafe.atRest(new int[]{980, 1000}, 1000), "a lower mincommand is rest too");
        assertFalse(BoardSafe.atRest(new int[]{1000, 1001}, 1000));
    }

    @Test
    void aBoardAToolLeftInTheCliIsTakenOutOfItAndStopped() throws IOException {
        // a tool died in the CLI with a motor on and a change not saved; a battery keeps the board running
        board.cutTakesPower = false;
        BetaflightUsb fc = BetaflightUsb.open(board.port());
        leaveOn(fc, 1090);
        fc.enterCli();
        fc.command("set small_angle = 99");
        board.cutWire();
        assertTrue(board.inCli());
        assertEquals(1090, board.motors()[0]);
        BoardSafe.Result r = makeSafe();
        assertTrue(r.safe(), r.text());
        assertFalse(board.inCli());
        assertEquals(1, board.restarts, "exit restarts Betaflight");
        assertEquals(0, board.saves);
        assertFalse(board.inMemory().contains("small_angle = 99"), "what was not saved is dropped");
        assertArrayEquals(AT_REST, board.motors());
        assertTrue(log.stream().anyMatch(l -> l.contains("was in the CLI")), log.toString());
    }

    @Test
    void aBoardThatIsNotThereIsNotSafe() {
        board.unplug();
        BoardSafe.Result r = BoardSafe.run(board.port(), new BoardPort.Timing(5, 60), null, log::add);
        assertFalse(r.safe());
        assertNotNull(r.problem());
        assertTrue(r.text().startsWith("NOT SAFE: " + board.port().systemName()), r.text());
    }

    @Test
    void theArmingLocksPlaceIsAfterTheStop() throws IOException {
        try (BetaflightUsb fc = BetaflightUsb.open(board.port())) {
            leaveOn(fc, 1050, 1050, 1050, 1050);
        }
        int[][] seen = new int[1][];
        BoardSafe.Result r = BoardSafe.run(board.port(), T, fc -> {
            seen[0] = board.motors();
            return "arming locked";
        }, log::add);
        assertArrayEquals(AT_REST, seen[0], "the lock comes on a board that is stopped already");
        assertEquals("arming locked", r.lock());
        assertTrue(r.text().endsWith("disarmed, arming locked."), r.text());
        BoardSafe.Result failed = BoardSafe.run(board.port(), T, fc -> {
            throw new IOException("the lock was not taken");
        }, log::add);
        assertFalse(failed.safe(), "a lock that fails is no safe board");
    }
}
