package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.EscFirmware;
import de.droiddrone.pc.setup.FakeEscBoard;
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
 * tools changed it, the board must read exactly as the snapshot. What stops a restore: BoardRestoreStoppedTest; a
 * start with other defaults, and compare only: BoardRestoreDefaultsTest.
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

    private Path snapshot(boolean escs) throws IOException {
        return BoardSnapshot.take(board.port(), new BoardSnapshot.Options(escs, "", T), log::add).write(dir, null);
    }

    private BoardRestore.Report restore(Path snapshot, boolean escs) throws IOException {
        return BoardRestore.run(board.port(), snapshot, new BoardRestore.Options(escs, false, false, dir, T), log::add);
    }

    @Test
    void aBoardNobodyChangedIsAsInTheSnapshotAndIsNotWrittenTo() throws IOException {
        board.battery(16.4, 4);
        Path snapshot = snapshot(true);
        board.fly(); // Betaflight's own statistics count on: no change of anybody's
        int cliLines = board.cli().size();
        long passthroughs = board.mspRequests().stream().filter(c -> c == Msp.SET_PASSTHROUGH).count();
        BoardRestore.Report r = restore(snapshot, false);
        assertTrue(r.asSnapshot(), String.valueOf(r.left()));
        assertFalse(r.saved());
        assertFalse(r.comparedOnly());
        assertEquals(List.of(), r.before());
        assertEquals(0, r.exitCode());
        assertEquals(0, board.saves);
        assertEquals(List.of("diff all", "diff all", "dump all", "dump all", "exit"), board.cli().subList(cliLines, board.cli().size()),
                "read, not written");
        // the ESCs are only looked at when asked for - and the report says the snapshot has them
        assertEquals(List.of(), r.escs());
        assertEquals(List.of("The snapshot has the ESCs' settings: -Pescs=true (battery in) compares them."), r.notes());
        assertEquals(passthroughs, board.mspRequests().stream().filter(c -> c == Msp.SET_PASSTHROUGH).count());
    }

    @Test
    void whatEachToolOfTheAppChangedIsPutBack() throws IOException {
        board.battery(16.4, 4);
        Path snapshot = snapshot(true);
        String before = board.saved();
        // Drone setup: the receiver over MSP, then its CLI plan (BetaflightPlan, BatterySetup, GpsSetup, Beepers, ModeSwitch)
        List<String> written = AppWrites.droneSetup(board.port(), T, log::add);
        assertTrue(written.size() > 25, written.toString());
        // the motor test: output order and props direction (MSP, saved at once), REVERSE for motor 2 (saved inside its ESC)
        AppWrites.motorOrder(board.port(), T, 1, 0, 3, 2, 4, 5, 6, 7);
        AppWrites.propsOut(board.port(), T, true);
        AppWrites.reverse(board.port(), T, 2, true);
        // the phone app's beeper silence, saved
        AppWrites.beeperQuietSaved(board.port(), T);
        // and a few settings of any kind: a number, an array, a rate profile's, the stick order, a timer
        AppWrites.cli(board.port(), T, log::add, "set small_angle = 77", "set mag_calibration = 1,2,3", "rateprofile 1", "set roll_expo = 33",
                "map TAER1234", "profile 1", "timer A02 AF1", "set min_throttle = 1100");
        String changed = board.saved();
        for (String there : List.of("\nfeature RX_MSP\n", "set serial_update_rate_hz = 2000", "set force_battery_cell_count = 6",
                "set vbat_warning_cell_voltage = 320", "set gps_auto_baud = ON", "serial 2 2 115200 57600 0 115200", "set mag_hardware = NONE",
                "beeper -ARMING", "beacon RX_SET", "aux 0 0 0 1700 2100 0 0", " 36 2 1700 2100 0 0", " 13 13 1700 2100 0 0",
                "set motor_output_reordering = 1,0,3,2,4,5,6,7", "set yaw_motors_reversed = ON", "beeper -RX_LOST", "beacon -RX_LOST",
                "set small_angle = 77", "set mag_calibration = 1,2,3", "set roll_expo = 33", "map TAER1234", "timer A02 AF1",
                "set min_throttle = 1100", "# restore original profile selection\nprofile 1", "# restore original rateprofile selection\nrateprofile 1")) {
            assertTrue(changed.contains(there), there + " after the app's writes in\n" + changed);
        }
        assertFalse(changed.contains("\nfeature RX_SERIAL\n"), "the serial receiver is off");
        BoardRestore.Report r = restore(snapshot, true);
        assertTrue(r.saved());
        assertNull(r.stopped());
        assertEquals(List.of(), r.left(), "read back after the restore: no difference from the snapshot");
        assertTrue(r.asSnapshot());
        assertTrue(r.before().size() > 40, "every change was seen before the restore: " + r.before().size());
        assertEquals(before, board.saved(), "what the board holds is what it held when the snapshot was taken");
        assertEquals(before, board.inMemory());
        // the whole dump went in after defaults nosave, and was looked at in memory before it was saved
        List<String> cli = board.cli();
        int save = cli.lastIndexOf("save");
        assertEquals(List.of("aux", "dump all", "save"), cli.subList(save - 2, save + 1), "the modes read back, the dump compared, then save");
        assertTrue(cli.lastIndexOf("defaults nosave") < cli.lastIndexOf("set min_throttle = 1070"));
        assertTrue(Files.readString(r.beforeFolder().resolve(BoardRestore.REPLAY_LOG)).contains("Betaflight CLI: set min_throttle = 1070 -> min_throttle set to 1070"));
        // the board as the campaign left it is on file as well
        assertTrue(Files.readString(r.beforeFolder().resolve(BoardSnapshot.DUMP)).contains("set small_angle = 77"));
        assertTrue(r.beforeFolder().getFileName().toString().endsWith("-before-restore"), r.beforeFolder().toString());
        assertTrue(r.afterFolder().getFileName().toString().endsWith("-after-restore"), r.afterFolder().toString());
        // the ESC that was told REVERSE: reported, never written - so not everything is as it was
        assertEquals(1, r.escs().size(), r.escs().toString());
        assertTrue(r.escs().get(0).startsWith("ESC 2: its settings changed (1 byte: 0x0B (motor direction) 01 -> 02)"), r.escs().get(0));
        assertEquals(1, r.exitCode());
        for (FakeEscBoard.Esc e : board.escs()) assertEquals(0, e.erases + e.writes, "a restore writes to no ESC");
        assertEquals(2, board.escs().get(1).memory[0x1A00 + EscFirmware.MOTOR_DIRECTION], "still reversed");
    }

    @Test
    void aFeatureTheCliHasNoNameForIsPutBackOverMsp() throws IOException {
        // as Drone setup leaves a Betaflight 2025.12 board: RX_MSP on, and its CLI cannot say so
        board.cliNamesRxMsp = false;
        board.features(BetaflightPlan.mspReceiverMask(board.featureMask()));
        // and a build without a feature whose name the dump lists all the same
        board.featuresNotBuilt.add("RANGEFINDER");
        int mask = board.featureMask();
        Path snapshot = snapshot(false);
        String dump = Files.readString(snapshot.resolve(BoardSnapshot.DUMP));
        assertFalse(dump.contains("RX_MSP"));
        assertTrue(dump.contains("feature -RANGEFINDER"));
        board.features(FakeFlightBoard.DEFAULT_FEATURES); // the serial receiver again, the GPS off
        BoardRestore.Report r = restore(snapshot, false);
        assertTrue(r.saved());
        assertTrue(r.asSnapshot(), String.valueOf(r.left()));
        assertEquals(0, r.exitCode());
        assertEquals(mask, board.featureMask());
        assertTrue(BetaflightPlan.isMspReceiver(board.featureMask()));
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("over MSP")), r.notes().toString());
        assertEquals(2, board.saves, "the CLI's save, then the mask's");
        assertEquals(1, board.mspRequests().stream().filter(c -> c == Msp.SET_FEATURE_CONFIG).count());
        // "feature -RANGEFINDER" was answered Unavailable: nothing to switch off, no refusal of the replay
        assertTrue(r.notes().contains("\"feature -RANGEFINDER\": this Betaflight is built without it - nothing to switch off."), r.notes().toString());
    }

    @Test
    void theReplayIsDefaultsThenTheDumpsOwnLinesWithoutItsSave() throws IOException {
        List<String> lines = BoardRestore.replay(board.saved());
        assertEquals(List.of("batch start", "defaults nosave", "board_name MADEUPF405"), lines.subList(0, 3));
        assertEquals(1, lines.stream().filter(l -> l.equals("defaults nosave")).count());
        assertFalse(lines.contains("save"), "save is said apart, last, after every line was taken");
        assertTrue(lines.stream().noneMatch(l -> l.isBlank() || l.startsWith("#")), lines.toString());
        // every value written out: those that are Betaflight's defaults too, every feature off and on, every timer pin
        assertTrue(lines.containsAll(List.of("signature", "timer B00 AF2", "mixer QUADX", "feature -GPS", "feature GPS", "feature -RX_MSP",
                "beeper RX_LOST", "beacon -RX_SET", "map AETR1234", "aux 0 0 2 1700 2100 0 0", "aux 19 0 0 900 900 0 0",
                "set min_throttle = 1070", "set use_unsynced_pwm = OFF", "set min_check = 1050", "profile 1", "set p_roll = 60", "rateprofile 0")));
        assertTrue(lines.indexOf("feature -GPS") < lines.indexOf("feature GPS"), "off first, then on again");
        assertEquals("rateprofile 0", lines.get(lines.size() - 1));
        assertEquals(board.saved().lines().filter(l -> !l.isBlank() && !l.startsWith("#")).count(), lines.size(), "the dump's lines, save out, defaults in");
        // a text that has "defaults nosave" of its own (a diff all) keeps it where it is
        assertEquals(1, BoardRestore.replay(board.savedDiff()).stream().filter(l -> l.equals("defaults nosave")).count());
        assertEquals(List.of("defaults nosave", "set small_angle = 40"), BoardRestore.replay("# version\r\nset small_angle = 40\r\nsave"));
        // lines that would do something else than set a setting: the whole dump is not replayed
        for (String other : List.of("defaults", "exit", "flash_erase", "bl", "DFU")) {
            assertThrows(IOException.class, () -> BoardRestore.replay("# version\nset small_angle = 40\n" + other + "\n"), other);
        }
        assertThrows(IOException.class, () -> BoardRestore.replay("set small_angle = 40"), "no version heading: not a whole dump");
        assertThrows(IOException.class, () -> BoardRestore.replay("# version\n###ERROR IN dump: SOMETHING###"));
        assertNotEquals(List.of(), BoardRestore.NOT_PUT_BACK);
    }
}
