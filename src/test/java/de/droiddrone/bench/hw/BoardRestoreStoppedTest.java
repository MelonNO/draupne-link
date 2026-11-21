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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What stops a restore ({@link BoardRestore}), and what it then leaves behind: a broken wire, a refused line and a
 * replay that does not make the snapshot's settings leave nothing saved; an armed board, another board and another
 * firmware are not written to at all.
 */
class BoardRestoreStoppedTest {
    private static final BoardPort.Timing T = FakeFlightBoard.NO_WAITING;
    /** The CLI lines of the read a restore starts with: diff all and dump all, each twice, and exit. */
    private static final int READ_LINES = 5;
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

    private BoardRestore.Report restore(Path snapshot) throws IOException {
        return BoardRestore.run(board.port(), snapshot, new BoardRestore.Options(false, false, false, dir, T), log::add);
    }

    /** What a test campaign left on the board, saved. */
    private String campaign() throws IOException {
        AppWrites.cli(board.port(), T, log::add, "set small_angle = 77", "feature -GPS", "aux 0 0 0 1700 2100 0 0");
        return board.saved();
    }

    @Test
    void theWireCutInTheMiddleOfTheReplayLeavesNothingSaved() throws IOException {
        Path snapshot = snapshot();
        String campaign = campaign();
        int saves = board.saves;
        // on USB power alone: the cut takes the board's power, it starts again from what was saved
        board.cutAfterCliCommands = READ_LINES + 8;
        BoardRestore.Report r = restore(snapshot);
        assertFalse(r.saved());
        assertNotNull(r.stopped());
        assertTrue(r.stopped().contains("broke off"), r.stopped());
        assertEquals(saves, board.saves);
        assertEquals(campaign, board.saved(), "nothing of the replay is saved");
        assertFalse(r.asSnapshot());
        assertEquals(1, r.exitCode());
        assertFalse(r.before().isEmpty());
        assertEquals(r.before(), r.left(), "read again: it differs from the snapshot as it did before");
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("as it was before the restore")), r.notes().toString());
        assertEquals(8, Files.readAllLines(r.beforeFolder().resolve(BoardRestore.REPLAY_LOG)).size(), "the lines taken until the cut are on file");
        // with a battery in the board runs on: in the CLI, half the replay in its memory - it is taken out of there
        board.cutTakesPower = false;
        board.cutAfterCliCommands = READ_LINES + 8;
        r = restore(snapshot);
        assertFalse(r.saved());
        assertEquals(saves, board.saves);
        assertEquals(campaign, board.saved());
        assertEquals(campaign, board.inMemory(), "the half replay is gone from its memory too");
        assertFalse(board.inCli());
        assertTrue(log.stream().anyMatch(l -> l.contains("was in the CLI")), "said: " + log);
        assertEquals(r.before(), r.left());
    }

    @Test
    void aLineBetaflightRefusesStopsItAndTheBoardIsAsItWas() throws IOException {
        Path snapshot = snapshot();
        String campaign = campaign();
        int saves = board.saves;
        board.refuseCli("mixer QUADX");
        BoardRestore.Report r = restore(snapshot);
        assertFalse(r.saved());
        assertTrue(r.stopped().contains("refused \"mixer QUADX\""), r.stopped());
        assertEquals(saves, board.saves);
        assertEquals(campaign, board.saved());
        assertEquals(campaign, board.inMemory());
        List<String> cli = board.cli();
        int refusedAt = cli.lastIndexOf("mixer QUADX");
        assertEquals("exit", cli.get(refusedAt + 1), "nothing after the refused line but exit: " + cli.subList(refusedAt, cli.size()));
        assertFalse(cli.subList(refusedAt, cli.size()).contains("save"));
        assertEquals(r.before(), r.left());
        assertEquals(1, r.exitCode());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("as it was before the restore")), r.notes().toString());
    }

    @Test
    void aReplayThatDoesNotMakeTheSnapshotsSettingsIsNotSaved() throws IOException {
        // the snapshot's board had a timer taken off a pin: "diff all" says so, a dump just has no line for the pin
        board.setUp("timer A02 NONE");
        Path snapshot = snapshot();
        assertTrue(Files.readString(snapshot.resolve(BoardSnapshot.DIFF)).contains("timer A02 NONE"));
        assertFalse(Files.readString(snapshot.resolve(BoardSnapshot.DUMP)).contains("A02"));
        String campaign = campaign();
        int saves = board.saves;
        // "defaults nosave" gives the pin its timer back and no line of the dump takes it off again
        BoardRestore.Report r = restore(snapshot);
        assertFalse(r.saved());
        assertEquals("Every line was taken, yet 2 line(s) of Betaflight's settings in memory are not the snapshot's", r.stopped());
        assertTrue(r.notes().contains("In memory before save - dump all: only on the board now: [timer] timer A02 AF3"), r.notes().toString());
        assertTrue(r.notes().contains("In memory before save - dump all: only on the board now: [timer] # pin A02: TIM3 CH1 (AF3)"), r.notes().toString());
        assertEquals(saves, board.saves);
        assertEquals(campaign, board.saved(), "looked at in memory, found other than the snapshot, left by exit");
        List<String> cli = board.cli();
        int looked = cli.lastIndexOf("defaults nosave");
        assertEquals(List.of("aux", "dump all", "exit"), cli.subList(cli.size() - READ_LINES - 3, cli.size() - READ_LINES));
        assertFalse(cli.subList(looked, cli.size()).contains("save"));
        assertEquals(r.before(), r.left());
        assertEquals(1, r.exitCode());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("as it was before the restore")), r.notes().toString());
    }

    @Test
    void anArmedBoardAnotherBoardAndAnotherFirmwareAreNotWrittenTo() throws IOException {
        Path snapshot = snapshot();
        String before = board.saved();
        int cliLines = board.cli().size(), restarts = board.restarts, mspRequests = board.mspRequests().size();
        board.arm();
        BoardRestore.Report r = restore(snapshot);
        assertEquals("The board is armed. Disarm it first.", r.refused());
        assertEquals(2, r.exitCode());
        assertFalse(r.asSnapshot());
        board.disarm();
        byte[] uid = board.uid;
        board.uid = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        r = restore(snapshot);
        assertTrue(r.refused().startsWith("Another board: the snapshot is of 4d4144452d55500000000001, this one is 0102030405060708090a0b0c"), r.refused());
        board.uid = uid;
        board.version = new int[]{4, 5, 1};
        r = restore(snapshot);
        assertTrue(r.refused().startsWith("Another firmware than the snapshot's (fc_version 040401 -> 040501)"), r.refused());
        board.version = new int[]{4, 4, 1};
        // refused before its CLI was even opened: nothing restarted, and no request that writes
        assertEquals(cliLines, board.cli().size());
        assertEquals(restarts, board.restarts);
        Set<Integer> writes = Set.of(Msp.SET_FEATURE_CONFIG, Msp.SET_MIXER_CONFIG, Msp.SET_BEEPER_CONFIG, Msp.SET_MOTOR, Msp.EEPROM_WRITE, Msp.REBOOT);
        for (int request : board.mspRequests().subList(mspRequests, board.mspRequests().size())) assertFalse(writes.contains(request), "MSP " + request);
        // another build of the same version: only the CLI's version line says so - read, then refused
        board.build = "May 5 2023 / 10:00:00 (otherbld0)";
        r = restore(snapshot);
        assertTrue(r.refused().startsWith("Another firmware than the snapshot's"), r.refused());
        board.build = "Apr 11 2023 / 01:20:48 (madeup000)";
        // a snapshot somebody edited
        Path dump = snapshot.resolve(BoardSnapshot.DUMP);
        Files.writeString(dump, Files.readString(dump) + "set small_angle = 1\n");
        r = restore(snapshot);
        assertTrue(r.refused().contains("SHA-256"), r.refused());
        r = restore(dir.resolve("no-such-snapshot"));
        assertNotNull(r.refused());
        assertEquals(0, board.saves);
        assertEquals(before, board.saved());
    }
}
