package de.droiddrone.pc.gui;

import de.droiddrone.bench.hw.FakeFlightBoard;
import de.droiddrone.pc.gui.CheckRow.State;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.EscCheck;
import de.droiddrone.pc.setup.FakeEscBoard;
import de.droiddrone.pc.setup.GpsSearch;
import de.droiddrone.pc.setup.SetupBoard;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-071: with the port choice on Automatic the other USB tools of Drone setup took the first board that answered -
 * the orientation check (it writes the board alignment), the compass calibration, the live view behind CALIBRATE -
 * and FIND GPS and the ESC row took whatever board was on the port last read. With two flight controllers on USB,
 * or after a swap on the cable, they read or wrote another board than the one the first step read. Each of them now
 * works on that board, by USB port and by id, as the motor test does since F-060 (SetupBoard): another board is asked
 * what it is and sent nothing more; with no board read, none is chosen between two. The step is in a guide that is
 * never shown, no window is opened, the boards are made up - no real port.
 */
@Timeout(60)
class DroneSetupUsbToolsTest {
    /** MSP_FC_VARIANT and MSP_UID: what a board is asked to say what it is. */
    private static final Set<Integer> WHAT_IT_IS = Set.of(2, 160);
    /** The made-up USB; the step itself only in the tests that open it. */
    private final MotorsPageRig rig = new MotorsPageRig();
    private final List<String> log = new ArrayList<>();
    @TempDir
    Path dir;

    @AfterEach
    void close() throws Exception {
        rig.close();
    }

    /** A Betaflight board with a battery in and four ESCs whose beacon is on, on a port of this name. */
    private FakeFlightBoard plug(String port, int id) {
        FakeFlightBoard board = new FakeFlightBoard(port);
        board.uid = MotorTestRig.uid(id);
        board.battery(16.0, 4);
        rig.usb.plug(port, "Made-up Betaflight board", board::connect);
        return board;
    }

    private static SetupBoard read(String port, int id) {
        return new SetupBoard(port, MotorTestRig.id(id));
    }

    private static int written(FakeFlightBoard board) {
        int n = 0;
        for (FakeEscBoard.Esc e : board.escs()) n += e.erases + e.writes;
        return n;
    }

    // ------------------------------------------------------------------ the live view and CALIBRATE (the second step)

    @Test
    void withTwoBoardsTheLiveViewFollowsTheOneTheFirstStepReadAndCalibrateLevelsThatOne() throws Exception {
        LevelBoard a = LevelBoard.plug(rig.usb, "MADEUP1", 1), b = LevelBoard.plug(rig.usb, "MADEUP2", 2); // A comes first in the port list
        rig.board = read("MADEUP2", 2);
        rig.open();
        Waits.await(() -> b.attitudes() >= 3, 20_000, "the live view to follow board B");
        rig.press("CALIBRATE");
        Waits.await(() -> b.calibrations() == 1, 10_000, "board B to be told to calibrate");
        assertEquals(0, a.opens(), "board A's port is not opened");
        assertEquals(List.of(), a.requests(), "and board A is asked nothing");
    }

    /** The pilot swapped drones on the cable after the first step read one: the same port, another board. */
    @Test
    void anotherBoardOnThePortIsNotFollowedAndNotLevelled() throws Exception {
        LevelBoard swapped = LevelBoard.plug(rig.usb, "MADEUP1", 2);
        rig.board = read("MADEUP1", 1);
        rig.open();
        Waits.await(() -> swapped.opens() >= 2, 20_000, "the live view to look at the port again by itself");
        rig.press("CALIBRATE");
        assertEquals("WARN: No flight controller: not calibrated. USB to this PC, then Calibrate.", rig.shows(MotorsPageRig.LEVEL));
        assertEquals("MADEUP1: not the board Drone setup read. Nothing sent. Plug that one in, or Back and Read again.", rig.underTheModel());
        assertTrue(WHAT_IT_IS.containsAll(swapped.requests()), "the other board is asked what it is and nothing else: " + swapped.requests());
        assertEquals(0, swapped.calibrations());
    }

    @Test
    void checkItOpensTheOrientationCheckForTheBoardTheFirstStepRead() throws Exception {
        rig.board = read("MADEUP7", 7);
        rig.open();
        rig.press("WRONG? CHECK IT");
        assertEquals(1, rig.orientationChecks);
        assertEquals(read("MADEUP7", 7), rig.orientationCheckFor, "the same port and the same board id as Drone setup's CONFIRM");
        assertEquals("PENDING: Check open.", rig.shows(MotorsPageRig.ORIENTATION));
    }

    // ------------------------------------------------------------------ the orientation check's and the compass calibration's way in

    private record Said(State state, String text) {
    }

    /** What the orientation check, the compass calibration and the live view open their flight controller with. */
    private BetaflightUsb open(SetupBoard target, String readAgain, List<Said> said) {
        return OrientationCheckDialog.openBetaflight(target, readAgain, (state, text) -> said.add(new Said(state, text)));
    }

    @Test
    void theChecksRefuseAnotherBoardOnThePortAndSendItNothing() {
        FakeFlightBoard swapped = plug("MADEUP1", 2);
        List<Said> said = new ArrayList<>();
        assertNull(open(read("MADEUP1", 1), SetupBoard.BACK_AND_READ_AGAIN, said), "the orientation check, from the second step");
        assertNull(open(read("MADEUP1", 1), SetupBoard.READ_AGAIN, said), "the compass calibration, from the first step");
        assertEquals(List.of(
                new Said(State.BAD, "<b>MADEUP1</b>: not the board Drone setup read. Nothing sent.<br>Plug that one in, or <b>Back</b> and <b>Read again</b>."),
                new Said(State.BAD, "<b>MADEUP1</b>: not the board Drone setup read. Nothing sent.<br>Plug that one in, or <b>Read again</b>.")), said);
        assertTrue(WHAT_IT_IS.containsAll(swapped.mspRequests()), "asked what it is and nothing else: " + swapped.mspRequests());
        assertEquals(List.of(), swapped.cli(), "its CLI is never opened");
    }

    @Test
    void theChecksDoNotChooseBetweenTwoBoardsWhenNoneWasRead() {
        FakeFlightBoard a = plug("MADEUP1", 1), b = plug("MADEUP2", 2);
        List<Said> said = new ArrayList<>();
        assertNull(open(null, SetupBoard.BACK_AND_READ_AGAIN, said));
        assertEquals(List.of(new Said(State.BAD, "2 flight controllers on USB. Unplug all but one.")), said);
        assertEquals(List.of(), a.cli());
        assertEquals(List.of(), b.cli());
    }

    @Test
    void theChecksOpenTheBoardThatWasReadAndNoOther() {
        FakeFlightBoard a = plug("MADEUP1", 1), b = plug("MADEUP2", 2);
        List<Said> said = new ArrayList<>();
        try (BetaflightUsb fc = open(read("MADEUP2", 2), SetupBoard.BACK_AND_READ_AGAIN, said)) {
            assertNotNull(fc, said.toString());
            assertEquals(1, b.opens);
            assertEquals(0, a.opens, "board A's port is not opened");
        }
        assertEquals(List.of(), said);
    }

    @Test
    void whileTheBoardThatWasReadIsNotPluggedInTheChecksSayWhereItShouldBe() {
        plug("MADEUP1", 1);
        List<Said> said = new ArrayList<>();
        assertNull(open(read("MADEUP2", 2), SetupBoard.BACK_AND_READ_AGAIN, said));
        assertEquals(List.of(new Said(State.WARN, "Drone setup's board is not on <b>MADEUP2</b>.<br>Plug it in, or <b>Back</b> and <b>Read again</b>.")), said);
    }

    // ------------------------------------------------------------------ the ESC row

    @Test
    void beaconOffNeverWritesTheEscsOfAnotherBoard() {
        FakeFlightBoard swapped = plug("MADEUP1", 2);
        SetupBoard target = read("MADEUP1", 1);
        EscCheck.Result r = EscCheck.run(() -> DroneSetup.BetaflightPage.escBoard(target), true, dir, log::add);
        assertNull(r.escs(), "no ESC was read");
        assertEquals("MADEUP1: not the board Drone setup read. Nothing sent. Plug that one in, or Read again.", r.text());
        assertEquals(0, written(swapped), "no ESC of the other board was erased or written");
        assertTrue(WHAT_IT_IS.containsAll(swapped.mspRequests()), "asked what it is and nothing else: " + swapped.mspRequests());
    }

    @Test
    void withNoBoardReadAndTwoOnUsbNoEscIsTouched() {
        FakeFlightBoard a = plug("MADEUP1", 1), b = plug("MADEUP2", 2);
        EscCheck.Result r = EscCheck.run(() -> DroneSetup.BetaflightPage.escBoard(null), true, dir, log::add);
        assertEquals("2 flight controllers on USB. Unplug all but one.", r.text());
        assertEquals(0, written(a) + written(b));
        assertFalse(a.mspRequests().contains(245) || b.mspRequests().contains(245), "neither was told to hand its port to the ESCs (MSP_SET_PASSTHROUGH)");
    }

    @Test
    void theEscsOfTheBoardThatWasReadAreReadAndSilenced() {
        FakeFlightBoard a = plug("MADEUP1", 1), b = plug("MADEUP2", 2);
        SetupBoard target = read("MADEUP2", 2);
        EscCheck.Result r = EscCheck.run(() -> DroneSetup.BetaflightPage.escBoard(target), true, dir, log::add);
        assertNotNull(r.escs(), r.text() + " " + log);
        assertEquals(4, r.escs().size());
        assertTrue(written(b) > 0, "the beacon of board B's ESCs was switched off");
        assertEquals(0, a.opens, "board A's port is not opened");
        assertEquals(0, written(a));
    }

    /**
     * F-080: ESC 1 of the board read does not answer with the battery in - motor 1 may not turn until the battery is
     * unplugged. The row is red, and Drone setup is not ready for that board (its status line says why instead of
     * "Betaflight set up") until a read in which all answer; another board is not held by it.
     */
    @Test
    void anEscThatDidNotAnswerHoldsDroneSetupNotReadyForThatBoardUntilAllAnswer() {
        FakeFlightBoard a = plug("MADEUP1", 1);
        a.escs().get(0).refuseConnects = 1000; // never reached
        SetupBoard target = read("MADEUP1", 1);
        EscCheck.Result r = EscCheck.run(() -> DroneSetup.BetaflightPage.escBoard(target), false, dir, log::add);
        assertEquals("BAD", r.state(), r.text());
        assertEquals(MotorTestRig.id(1), r.board(), "the board that was read");
        String line = "ESC 1 did not answer - it may not turn now. Unplug the battery, plug it in, Read ESCs again.";
        assertTrue(log.contains(line), log.toString());

        EscCheck.Hold hold = new EscCheck.Hold();
        assertTrue(hold.after(r.board(), r));
        assertEquals(line, DroneSetup.BetaflightPage.escStatus(hold.on(MotorTestRig.id(1))), "not ready: said in the step's status line");
        assertNull(DroneSetup.BetaflightPage.escStatus(hold.on(MotorTestRig.id(2))), "another board is not held");
        assertArrayEquals(new String[]{"BAD", "ESC 1 did not answer - it may not turn now. Unplug the battery, plug it in, <b>Read ESCs</b> again."},
                DroneSetup.BetaflightPage.escIdle(hold.on(MotorTestRig.id(1))), "the row as Drone setup opens again on that board");
        assertArrayEquals(new String[]{"INFO", "Battery in, props off: <b>Read ESCs</b>."}, DroneSetup.BetaflightPage.escIdle(hold.on(MotorTestRig.id(2))));

        // the battery out and in again: all answer, ready again
        a.escs().get(0).refuseConnects = 0;
        EscCheck.Result good = EscCheck.run(() -> DroneSetup.BetaflightPage.escBoard(target), false, dir, log::add);
        assertEquals(List.of(), good.silent(), good.text());
        assertTrue(hold.after(good.board(), good));
        assertNull(DroneSetup.BetaflightPage.escStatus(hold.on(MotorTestRig.id(1))));
    }

    // ------------------------------------------------------------------ FIND GPS

    private GpsSearch.Result findGps(SetupBoard target) {
        return new GpsSearch(() -> DroneSetup.BetaflightPage.gpsBoard(target), (diff, version) -> {
            try {
                return Files.writeString(dir.resolve("before.txt"), diff);
            } catch (java.io.IOException e) {
                return null;
            }
        }, line -> { }, log::add, () -> true).run(); // "the page was left": a search that does begin tries no UART
    }

    @Test
    void findGpsNeverOpensTheCliOfAnotherBoard() {
        FakeFlightBoard swapped = plug("MADEUP1", 2);
        GpsSearch.Result r = findGps(read("MADEUP1", 1));
        assertEquals("Stopped: MADEUP1: not the board Drone setup read. Nothing sent. Plug that one in, or Read again.", r.problem());
        assertEquals(List.of(), swapped.cli(), "no CLI command reached the other board");
        assertTrue(WHAT_IT_IS.containsAll(swapped.mspRequests()), "asked what it is and nothing else: " + swapped.mspRequests());
        assertEquals(0, swapped.restarts, "and it was not restarted");
    }

    @Test
    void withNoBoardReadAndTwoOnUsbFindGpsOpensNoCli() {
        FakeFlightBoard a = plug("MADEUP1", 1), b = plug("MADEUP2", 2);
        GpsSearch.Result r = findGps(null);
        assertEquals("Stopped: 2 flight controllers on USB. Unplug all but one.", r.problem());
        assertEquals(List.of(), a.cli());
        assertEquals(List.of(), b.cli());
    }

    // ------------------------------------------------------------------ which board that is

    @Test
    void theBoardOfTheToolsIsTheOneLastReadOrThePilotsPortAndElseNone() {
        assertNull(DroneSetup.BetaflightPage.board(null, null, null), "nothing read, Automatic: none - a tool takes only a board that is alone");
        assertEquals(new SetupBoard("COM7", "uid:01"), DroneSetup.BetaflightPage.board(null, "COM7", "uid:01"), "the board last read");
        assertEquals(new SetupBoard("COM7", "uid:01"), DroneSetup.BetaflightPage.board("COM7", "COM7", "uid:01"));
        assertEquals(new SetupBoard("COM7", null), DroneSetup.BetaflightPage.board(null, "COM7", null), "a board that does not say its id: its port");
        assertEquals(new SetupBoard("COM9", null), DroneSetup.BetaflightPage.board("COM9", "COM7", "uid:01"),
                "the pilot chose another port and nothing was read on it yet: that port, never the board read on the other");
        assertEquals(new SetupBoard("COM9", null), DroneSetup.BetaflightPage.board("COM9", null, null));
    }
}
