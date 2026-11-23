package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.pc.setup.BetaflightUsb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A restore ({@link BoardRestore}) on a board whose built-in config has a line its build of Betaflight does not have -
 * the real F405's case (4.4.1, "# config: YES", 2026-11-23): "defaults nosave" was answered with its "# resetting to
 * defaults" line and then that config line's ###ERROR, and the restore stopped: "Betaflight refused "defaults
 * nosave": # resetting to defaults", nothing saved. SITL has no built-in config, so its answer has no such line. Here:
 * the bytes as Betaflight sends them, the restore that takes them and saves, and the refusals that still stop it.
 */
class BoardRestoreBuiltInConfigTest {
    private static final BoardPort.Timing T = FakeFlightBoard.NO_WAITING;
    /** For instance: which line the real board's config has is not on file (the replay's log keeps an answer's first line only). */
    private static final String NOT_BUILT = "set camera_control_mode = HARDWARE_PWM";
    private static final String ERROR = "###ERROR IN set: INVALID NAME: camera_control_mode = HARDWARE_PWM###";
    /**
     * Betaflight 4.4.1's bytes for "defaults nosave" (cli.c): the echo and CR LF at the Enter (processCharacter);
     * cliDefaults' cliPrintHashLine - CR LF, "# resetting to defaults", CR LF; on a board with a built-in config each of
     * its lines this build lacks, with CR LF (cliPrintErrorLinef - cliProcessCustomDefaults(false) leaves the error
     * writer on); the prompt, CR LF "# " (cliPrompt).
     */
    static final String F405_ANSWER = "defaults nosave\r\n\r\n# resetting to defaults\r\n" + ERROR + "\r\n\r\n# ";
    /** The same on SITL (no built-in config): the hash line and the prompt. */
    static final String SITL_ANSWER = "defaults nosave\r\n\r\n# resetting to defaults\r\n\r\n# ";
    @TempDir
    Path dir;
    private final FakeFlightBoard board = FakeFlightBoard.flown();
    private final List<String> log = new ArrayList<>();
    /** Every byte the board sent, as it came over the wire. */
    private final ByteArrayOutputStream heard = new ByteArrayOutputStream();

    @BeforeEach
    void plugIn() {
        BetaflightUsb.use(tapped(board.usb()));
    }

    @AfterEach
    void thisPcsPortsAgain() {
        BetaflightUsb.use(null);
    }

    private BetaflightUsb.Usb tapped(BetaflightUsb.Usb usb) {
        return new BetaflightUsb.Usb() {
            @Override
            public List<BetaflightUsb.Port> ports() {
                return usb.ports();
            }

            @Override
            public BetaflightUsb.Wire open(BetaflightUsb.Port p) throws IOException {
                BetaflightUsb.Wire w = usb.open(p);
                return new BetaflightUsb.Wire() {
                    @Override
                    public int read(byte[] buf) throws IOException {
                        int n = w.read(buf);
                        if (n > 0) synchronized (heard) {
                            heard.write(buf, 0, n);
                        }
                        return n;
                    }

                    @Override
                    public int available() throws IOException {
                        return w.available();
                    }

                    @Override
                    public void write(byte[] data) throws IOException {
                        w.write(data);
                    }

                    @Override
                    public boolean isOpen() {
                        return w.isOpen();
                    }

                    @Override
                    public void close() {
                        w.close();
                    }
                };
            }
        };
    }

    private String heard() {
        synchronized (heard) {
            return heard.toString(StandardCharsets.US_ASCII);
        }
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
    void defaultsNosaveIsAnsweredWithBetaflightsBytesAndTheRestoreTakesThem() throws IOException {
        try (BetaflightUsb fc = BoardPort.open(board.port(), T, log::add)) {
            fc.enterCli();
            assertEquals("Command batch started", fc.command("batch start"));
            // no built-in config (SITL, the made-up board as R1 made it)
            synchronized (heard) {
                heard.reset();
            }
            assertEquals("# resetting to defaults", fc.command("defaults nosave"));
            assertEquals(SITL_ANSWER, heard());
            // a built-in config with a line this build lacks (the real F405)
            board.builtInConfigNotTaken.add(NOT_BUILT);
            synchronized (heard) {
                heard.reset();
            }
            String reply = fc.command("defaults nosave");
            assertEquals(F405_ANSWER, heard(), "Betaflight 4.4.1's bytes");
            assertEquals("# resetting to defaults\r\n" + ERROR, reply);
            // what stopped the restore on the real board: the error is in the answer, its first line is the hash line
            assertTrue(BetaflightPlan.refused(reply));
            List<String> notes = new ArrayList<>();
            assertEquals("# resetting to defaults", BoardRestore.defaultsAnswer(reply, notes));
            assertEquals(1, notes.size(), notes.toString());
            assertTrue(notes.get(0).startsWith("\"defaults nosave\": the board's built-in config has 1 line(s) this Betaflight does not take")
                    && notes.get(0).endsWith(ERROR), notes.get(0));
            // and inside a batch that line marked it as failed: its end is refused (as a save in it would be)
            assertTrue(BetaflightPlan.refused(fc.command("batch end")));
        }
        assertEquals(0, board.saves);
    }

    @Test
    void whatTheAnswerToDefaultsIsTakenFor() {
        List<String> notes = new ArrayList<>();
        // taken as it is: SITL's, a build without hash lines (MINIMAL_CLI), another line than an error after it
        for (String taken : List.of("# resetting to defaults", "", "# resetting to defaults\r\nsomething else")) {
            assertEquals(taken, BoardRestore.defaultsAnswer(taken, notes));
            assertFalse(BetaflightPlan.refused(taken));
        }
        assertEquals(List.of(), notes);
        // the built-in config's errors: noted, out of the answer
        String two = "# resetting to defaults\r\n" + ERROR + "\r\n###ERROR IN resource: INVALID RESOURCE NAME: 'CAMERA_CONTROL'###";
        assertEquals("# resetting to defaults", BoardRestore.defaultsAnswer(two, notes));
        assertTrue(notes.get(0).contains("2 line(s)"), notes.toString());
        // refused: no "# resetting to defaults" first - the errors stay, and the write stops
        for (String refused : List.of("###ERROR IN defaults: PARSING FAILED###", "###ERROR IN defaults: PARSING FAILED###\r\n# resetting to defaults",
                "###ERROR IN input: UNKNOWN COMMAND, TRY 'HELP'###")) {
            assertEquals(refused, BoardRestore.defaultsAnswer(refused, notes));
            assertTrue(BetaflightPlan.refused(refused), refused);
        }
        assertEquals(1, notes.size());
    }

    @Test
    void aBoardWhoseBuiltInConfigHasALineItsBuildLacksIsRestoredAndSaved() throws IOException {
        board.builtInConfigNotTaken.add(NOT_BUILT);
        Path snapshot = snapshot();
        String before = board.saved();
        String campaign = campaign();
        assertFalse(campaign.equals(before));
        int saves = board.saves, msp = board.mspRequests().size();
        BoardRestore.Report r = restore(snapshot);
        assertNull(r.stopped(), "1.0.71's bench: Betaflight refused \"defaults nosave\": # resetting to defaults");
        assertTrue(r.saved());
        // with that answer taken but the batch as before, Betaflight refused the save unread, the restore said saved, and
        // the feature mask went over MSP onto the campaign's settings: one save, the wrong one
        assertEquals(saves + 1, board.saves, "Betaflight took the save");
        for (int request : board.mspRequests().subList(msp, board.mspRequests().size())) {
            assertFalse(request == Msp.EEPROM_WRITE || request == Msp.SET_FEATURE_CONFIG, "the CLI's save alone: MSP " + request);
        }
        assertEquals(before, board.saved(), "what the board holds is what it held when the snapshot was taken");
        assertTrue(r.asSnapshot(), String.valueOf(r.left()));
        assertEquals(0, r.exitCode());
        assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("\"defaults nosave\": the board's built-in config has 1 line(s)") && n.endsWith(ERROR)),
                r.notes().toString());
        // defaults before the batch, the batch ended before the dump in memory is read: neither marks it, the save is taken
        List<String> cli = board.cli();
        int defaults = cli.lastIndexOf("defaults nosave"), save = cli.lastIndexOf("save");
        assertEquals("batch start", cli.get(defaults + 1));
        assertEquals(List.of("batch end", "aux", "dump all", "save"), cli.subList(save - 3, save + 1));
        assertEquals(List.of("Betaflight CLI: defaults nosave -> # resetting to defaults", "Betaflight CLI: batch start -> Command batch started"),
                Files.readAllLines(r.beforeFolder().resolve(BoardRestore.REPLAY_LOG)).subList(0, 2));
    }

    @Test
    void aRealRefusalStillStopsTheWriteBeforeAnythingIsSaved() throws IOException {
        board.builtInConfigNotTaken.add(NOT_BUILT);
        Path snapshot = snapshot();
        String campaign = campaign();
        int saves = board.saves;
        // "defaults nosave" itself refused: an error, no "# resetting to defaults" line
        board.refuseCli("defaults nosave");
        BoardRestore.Report r = restore(snapshot);
        assertFalse(r.saved());
        assertTrue(r.stopped().startsWith("Betaflight refused \"defaults nosave\": ###ERROR IN defaults"), r.stopped());
        List<String> cli = board.cli();
        int refusedAt = cli.lastIndexOf("defaults nosave");
        assertEquals("exit", cli.get(refusedAt + 1), "nothing after the refused line but exit: " + cli.subList(refusedAt, cli.size()));
        // a batch that ends with an error in it: stopped at its end, before the dump in memory and the save
        board.refuseNothing();
        board.refuseCli("batch end");
        r = restore(snapshot);
        assertFalse(r.saved());
        assertTrue(r.stopped().startsWith("Betaflight refused \"batch end\""), r.stopped());
        cli = board.cli();
        assertEquals("exit", cli.get(cli.lastIndexOf("batch end") + 1));
        // a line of the dump refused, with the config's errors before it taken: stopped there
        board.refuseNothing();
        board.refuseCli("mixer QUADX");
        r = restore(snapshot);
        assertFalse(r.saved());
        assertTrue(r.stopped().startsWith("Betaflight refused \"mixer QUADX\""), r.stopped());
        // each time: nothing saved, nothing left in memory, read again as it was
        assertEquals(saves, board.saves);
        assertEquals(campaign, board.saved());
        assertEquals(campaign, board.inMemory());
        assertEquals(r.before(), r.left());
        assertEquals(1, r.exitCode());
    }
}
