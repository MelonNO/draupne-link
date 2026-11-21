package de.droiddrone.bench.hw;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Betaflight's own defaults are not the same at every start (seen on a real board, 4.4.1: min_throttle and
 * use_unsynced_pwm - it takes the ESCs for brushed ones at one start and not at the next), so "diff all" is not: a
 * snapshot and a restore must not hang on it. The made-up board flips those defaults at a start when told to.
 * Here too: the restore's compare-only mode, which never writes.
 */
class BoardRestoreDefaultsTest {
    private static final BoardPort.Timing T = FakeFlightBoard.NO_WAITING;
    private static final String OTHER_DEFAULTS = "Betaflight's own defaults differed at this start: 2 lines of diff all, the settings themselves are equal.";
    private static final List<String> READ = List.of("diff all", "diff all", "dump all", "dump all", "exit");
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

    private BoardSnapshot take() throws IOException {
        return BoardSnapshot.take(board.port(), new BoardSnapshot.Options(false, "", T), log::add);
    }

    private BoardRestore.Report restore(Path snapshot, boolean always, boolean compareOnly) throws IOException {
        return BoardRestore.run(board.port(), snapshot, new BoardRestore.Options(false, always, compareOnly, dir, T), log::add);
    }

    @Test
    void aSnapshotFromOneSideOfTheFlipIsPutBackExactlyOnTheOther() throws IOException {
        Path snapshot = take().write(dir, null);
        String before = board.saved();
        assertTrue(before.contains("set min_throttle = 1070") && before.contains("set use_unsynced_pwm = OFF"), before);
        assertFalse(Files.readString(snapshot.resolve(BoardSnapshot.DIFF)).contains("min_throttle"), "defaults at that start: no line in its diff all");
        // the campaign changes a setting; at the restart after its save Betaflight's defaults are the other pair
        board.flipDefaultsAtNextStart = true;
        AppWrites.cli(board.port(), T, log::add, "set small_angle = 77");
        assertTrue(board.savedDiff().contains("set min_throttle = 1070") && board.savedDiff().contains("set use_unsynced_pwm = OFF"),
                "the same values, no defaults any more:\n" + board.savedDiff());
        BoardRestore.Report r = restore(snapshot, false, false);
        assertTrue(r.saved());
        assertTrue(board.saved().contains("set min_throttle = 1070"), "min_throttle is the snapshot's, not this start's default");
        assertTrue(board.saved().contains("set use_unsynced_pwm = OFF"), "use_unsynced_pwm is the snapshot's, not this start's default");
        assertEquals(before, board.saved(), "every setting is the snapshot's");
        assertEquals(List.of(), r.left());
        assertEquals(0, r.exitCode());
        assertTrue(r.notes().contains(OTHER_DEFAULTS), "the other diff all is said, and is no failure: " + r.notes());
        assertTrue(board.cli().containsAll(List.of("defaults nosave", "set min_throttle = 1070", "set use_unsynced_pwm = OFF", "save")),
                "every value written out, whatever the defaults");
    }

    @Test
    void aBoardThatHasTheSnapshotsSettingsIsNotWrittenThoughItsDiffDiffers() throws IOException {
        board.flipDefaultsAtNextStart = true; // at the restart that ends the snapshot's own read
        Path snapshot = take().write(dir, null);
        String before = board.saved();
        int cliLines = board.cli().size();
        BoardRestore.Report r = restore(snapshot, false, false);
        assertFalse(r.saved());
        assertTrue(r.asSnapshot());
        assertEquals(List.of(), r.before());
        assertEquals(List.of(OTHER_DEFAULTS), r.notes());
        assertEquals(0, r.exitCode());
        assertEquals(0, board.saves);
        assertEquals(READ, board.cli().subList(cliLines, board.cli().size()), "read, not written");
        // replayed all the same (-Preplay=always), at a start with the other defaults: its own dump makes the same board again
        r = restore(snapshot, true, false);
        assertTrue(r.saved());
        assertNull(r.stopped());
        assertTrue(r.asSnapshot(), String.valueOf(r.left()));
        assertEquals(1, board.saves);
        assertEquals(before, board.saved());
        assertEquals(0, r.exitCode());
        assertTrue(r.notes().contains(OTHER_DEFAULTS), r.notes().toString());
        assertTrue(Files.isRegularFile(r.afterFolder().resolve(BoardSnapshot.DUMP)), "the board after the restore is on file");
    }

    @Test
    void compareOnlyNeverWritesWhateverDiffers() throws IOException {
        Path snapshot = take().write(dir, null);
        AppWrites.cli(board.port(), T, log::add, "set small_angle = 77");
        String changed = board.saved();
        int saves = board.saves, cliLines = board.cli().size();
        BoardRestore.Report r = restore(snapshot, true, true); // "always" with it: still nothing written
        assertTrue(r.comparedOnly());
        assertFalse(r.saved());
        assertNull(r.refused());
        assertEquals(List.of("dump all: only in the snapshot: [master] set small_angle = 40",
                "dump all: only on the board now: [master] set small_angle = 77"), r.left());
        assertEquals(1, r.exitCode());
        assertEquals(saves, board.saves);
        assertEquals(changed, board.saved());
        assertEquals(READ, board.cli().subList(cliLines, board.cli().size()));
        assertTrue(r.beforeFolder().getFileName().toString().endsWith("-compared"), r.beforeFolder().toString());
        // another board is compared too, and said to be another
        byte[] uid = board.uid;
        board.uid = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        r = restore(snapshot, false, true);
        assertNull(r.refused());
        assertTrue(r.left().stream().anyMatch(l -> l.startsWith("MSP uid: 4d4144452d55500000000001 in the snapshot, 0102030405060708090a0b0c now")), r.left().toString());
        board.uid = uid;
        // put back by hand, at a start with other defaults: equal, exit code 0, the diff said in its one line
        board.flipDefaultsAtNextStart = true;
        AppWrites.cli(board.port(), T, log::add, "set small_angle = 40");
        r = restore(snapshot, false, true);
        assertEquals(List.of(), r.left());
        assertEquals(List.of(OTHER_DEFAULTS), r.notes());
        assertEquals(0, r.exitCode());
        assertEquals(saves + 1, board.saves, "only the hand's own save");
        for (int request : board.mspRequests()) assertFalse(request == Msp.SET_FEATURE_CONFIG || request == Msp.EEPROM_WRITE, "MSP " + request);
    }
}
