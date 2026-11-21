package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.pc.setup.BetaflightUsb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A snapshot put back ({@link BoardRestore}) on the made-up board: after nothing, and after each of the app's own
 * tools changed it, the board must read exactly as the snapshot. What stops a restore: BoardRestoreStoppedTest.
 */
class BoardRestoreTest {
    private static final BoardPort.Timing T = FakeFlightBoard.NO_WAITING;
    @TempDir
    Path dir;
    private final FakeFlightBoard board = FakeFlightBoard.flown();
    private final List<String> log = new ArrayList<>();

    @BeforeEach
    void plugIn() {
        BetaflightUsb.use(board.usb());
    }

    @AfterEach
    void thisPcsPortsAgain() {
        BetaflightUsb.use(null);
    }

    private Path snapshot() throws IOException {
        return BoardSnapshot.take(board.port(), new BoardSnapshot.Options(false, "", T), log::add).write(dir, null);
    }

    private BoardRestore.Report restore(Path snapshot, boolean always) throws IOException {
        return BoardRestore.run(board.port(), snapshot, new BoardRestore.Options(false, always, dir, T), log::add);
    }

    @Test
    void aBoardNobodyChangedIsAsInTheSnapshotAndIsNotWrittenTo() throws IOException {
        Path snapshot = snapshot();
        String before = board.saved();
        int cliLines = board.cli().size();
        BoardRestore.Report r = restore(snapshot, false);
        assertTrue(r.asSnapshot(), String.valueOf(r.left()));
        assertFalse(r.saved());
        assertEquals(List.of(), r.before());
        assertEquals(0, r.exitCode());
        assertEquals(0, board.saves);
        assertEquals(List.of("diff all", "dump all", "exit"), board.cli().subList(cliLines, board.cli().size()), "read, not written");
        assertEquals(before, board.saved());
        // replayed all the same, its own diff makes the same board again
        board.fly();
        BoardRestore.Report again = restore(snapshot, true);
        assertTrue(again.saved());
        assertNull(again.stopped());
        assertTrue(again.asSnapshot(), String.valueOf(again.left()));
        assertEquals(1, board.saves);
        assertEquals(before, board.saved(), "the flight statistics are the snapshot's again too");
        assertTrue(board.cli().contains("defaults nosave") && board.cli().contains("save"));
        assertTrue(Files.isRegularFile(again.afterFolder().resolve(BoardSnapshot.DUMP)), "the board after the restore is on file");
    }

    @Test
    void whatEachToolOfTheAppChangedIsPutBack() throws IOException {
        Path snapshot = snapshot();
        String before = board.saved();
        // Drone setup: the receiver over MSP, then its CLI plan (BetaflightPlan, BatterySetup, GpsSetup, Beepers, ModeSwitch)
        List<String> written = AppWrites.droneSetup(board.port(), T, log::add);
        assertTrue(written.size() > 25, written.toString());
        // the motor test: output order and props direction (MSP, saved at once)
        AppWrites.motorOrder(board.port(), T, 1, 0, 3, 2, 4, 5, 6, 7);
        AppWrites.propsOut(board.port(), T, true);
        // the phone app's beeper silence, saved
        AppWrites.beeperQuietSaved(board.port(), T);
        // and a few settings of any kind: a number, an array, a rate profile's, the stick order
        AppWrites.cli(board.port(), T, log::add, "set small_angle = 77", "set mag_calibration = 1,2,3", "rateprofile 1", "set roll_expo = 33",
                "map TAER1234", "profile 1");
        String changed = board.saved();
        for (String there : List.of("feature RX_MSP", "feature -RX_SERIAL", "set serial_update_rate_hz = 2000", "set force_battery_cell_count = 6",
                "set vbat_warning_cell_voltage = 320", "set gps_auto_baud = ON", "serial 2 2 115200 57600 0 115200", "set mag_hardware = NONE",
                "beeper -ARMING", "beacon RX_SET", "aux 0 0 0 1700 2100 0 0", " 36 2 1700 2100 0 0", " 13 13 1700 2100 0 0",
                "set motor_output_reordering = 1,0,3,2,4,5,6,7", "set yaw_motors_reversed = ON", "beeper -RX_LOST", "beacon -RX_LOST",
                "set small_angle = 77", "set mag_calibration = 1,2,3", "set roll_expo = 33", "map TAER1234",
                "# restore original profile selection\nprofile 1", "# restore original rateprofile selection\nrateprofile 1")) {
            assertTrue(changed.contains(there), there + " after the app's writes in\n" + changed);
        }
        BoardRestore.Report r = restore(snapshot, false);
        assertTrue(r.saved());
        assertNull(r.stopped());
        assertEquals(List.of(), r.left(), "read back after the restore: no difference from the snapshot");
        assertTrue(r.asSnapshot());
        assertEquals(0, r.exitCode());
        assertTrue(r.before().size() > 40, "every change was seen before the restore: " + r.before().size());
        assertEquals(before, board.saved(), "what the board holds is what it held when the snapshot was taken");
        assertEquals(before, board.inMemory());
        // the board as the campaign left it is on file as well
        assertTrue(Files.readString(r.beforeFolder().resolve(BoardSnapshot.DIFF)).contains("set small_angle = 77"));
        assertTrue(r.beforeFolder().getFileName().toString().endsWith("-before-restore"), r.beforeFolder().toString());
        assertTrue(r.afterFolder().getFileName().toString().endsWith("-after-restore"), r.afterFolder().toString());
    }

    @Test
    void aFeatureTheCliHasNoNameForIsPutBackOverMsp() throws IOException {
        // as Drone setup leaves a Betaflight 2025.12 board: RX_MSP on, and its CLI cannot say so
        board.cliNamesRxMsp = false;
        board.features(BetaflightPlan.mspReceiverMask(board.featureMask()));
        int mask = board.featureMask();
        Path snapshot = snapshot();
        assertFalse(Files.readString(snapshot.resolve(BoardSnapshot.DIFF)).contains("RX_MSP"));
        board.features(FakeFlightBoard.DEFAULT_FEATURES); // the serial receiver again, the GPS off
        BoardRestore.Report r = restore(snapshot, false);
        assertTrue(r.asSnapshot(), String.valueOf(r.left()));
        assertEquals(mask, board.featureMask());
        assertTrue(BetaflightPlan.isMspReceiver(board.featureMask()));
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("over MSP")), r.notes().toString());
        assertEquals(2, board.saves, "the CLI's save, then the mask's");
        // with nothing the CLI cannot say, nothing is written over MSP
        assertEquals(1, board.mspRequests().stream().filter(c -> c == Msp.SET_FEATURE_CONFIG).count());
    }

    @Test
    void theReplayIsTheDiffsOwnLinesWithDefaultsFirstAndWithoutItsSave() throws IOException {
        List<String> lines = BoardRestore.replay(board.savedDiff());
        assertEquals(List.of("batch start", "defaults nosave", "board_name MADEUPF405"), lines.subList(0, 3));
        assertEquals(1, lines.stream().filter(l -> l.equals("defaults nosave")).count());
        assertFalse(lines.contains("save"), "save is said apart, last, after every line was taken");
        assertTrue(lines.stream().noneMatch(l -> l.isBlank() || l.startsWith("#")), lines.toString());
        assertTrue(lines.containsAll(List.of("signature", "feature GPS", "aux 0 0 2 1700 2100 0 0", "profile 1", "set p_roll = 60", "rateprofile 0")));
        assertEquals("rateprofile 0", lines.get(lines.size() - 1));
        // a diff without "defaults nosave" (an old Betaflight's, a plain "diff") gets it first - after the batch's start
        assertEquals(List.of("batch start", "defaults nosave", "set small_angle = 40", "batch end"),
                BoardRestore.replay("# version\n# Betaflight / X (Y) 3.5.7\n\nbatch start\nset small_angle = 40\nbatch end\n"));
        assertEquals(List.of("defaults nosave", "set small_angle = 40"), BoardRestore.replay("# version\r\nset small_angle = 40\r\nsave"));
        // lines that would do something else than set a setting: the whole diff is not replayed
        for (String other : List.of("defaults", "exit", "flash_erase", "bl", "DFU")) {
            assertThrows(IOException.class, () -> BoardRestore.replay("# version\nset small_angle = 40\n" + other + "\n"), other);
        }
        assertThrows(IOException.class, () -> BoardRestore.replay("set small_angle = 40"), "no version heading: not a whole diff");
        assertThrows(IOException.class, () -> BoardRestore.replay("# version\n###ERROR IN diff: SOMETHING###"));
        assertNotEquals(List.of(), BoardRestore.NOT_PUT_BACK);
    }
}
