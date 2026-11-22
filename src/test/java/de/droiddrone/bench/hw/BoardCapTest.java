package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightUsb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gradlew boardCap ({@link BoardCap}): Betaflight's motor_output_limit written in every PID profile, saved, and read
 * back - the throttle layer that holds whatever makes the throttle, the flight controller's own failsafe too. On the
 * made-up board; the task itself is only ever run on a real one, by hand.
 */
class BoardCapTest {
    private static final BoardPort.Timing T = FakeFlightBoard.NO_WAITING;
    private final FakeFlightBoard board = FakeFlightBoard.flown();
    private final List<String> log = new ArrayList<>();
    private final ByteArrayOutputStream said = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(said, true, StandardCharsets.UTF_8);

    @BeforeEach
    void plugIn() {
        BetaflightUsb.use(board.usb());
    }

    @AfterEach
    void thisPcsPortsAgain() {
        BetaflightUsb.use(null);
    }

    private static long count(String text, String line) {
        return text.lines().filter(l -> l.strip().equals(line)).count();
    }

    @Test
    void theLimitIsWrittenInEveryPidProfileSavedAndReadBack() {
        board.setUp("profile 1"); // the second PID profile is the one in use: it is the one in use afterwards too
        String before = board.saved();
        assertEquals(FakeFlightBoard.PROFILES, count(before, "set motor_output_limit = 100"), "a stock board: 100 in every profile");

        BoardCap.Result r = BoardCap.run(board.port(), 15, T, log::add);
        assertTrue(r.capped(), r.text() + "\n" + log);
        assertEquals(0, r.exitCode());
        assertNull(r.refused());
        assertNull(r.stopped());
        assertTrue(r.saved());
        assertEquals(List.of(100, 100), r.before());
        assertEquals(List.of(15, 15), r.after());
        assertEquals("CAPPED: motor_output_limit = 15 in each of the board's 2 PID profiles, saved and read back (it was 100, 100). "
                + "Betaflight gives its motors no more than 15 % now, whoever asks. gradlew boardRestore puts a snapshot's own values back.", r.text());
        assertEquals(1, board.saves);
        String after = board.saved();
        assertEquals(FakeFlightBoard.PROFILES, count(after, "set motor_output_limit = 15"));
        assertEquals(before.replace("set motor_output_limit = 100", "set motor_output_limit = 15"), after, "and nothing else of the board's is other than it was");
        assertTrue(after.contains("# restore original profile selection\nprofile 1\n"), "the PID profile in use is the one that was");
        assertFalse(board.armed() || board.inCli());
        assertEquals("save", board.cli().stream().filter(c -> c.equals("save") || c.startsWith("set ")).reduce((a, b) -> b).orElse(""), "save comes after the last set");
        assertEquals(2, board.cli().stream().filter(c -> c.equals("set motor_output_limit = 15")).count());

        // a board that has it already is not written again - and Betaflight's own 100 goes back the same way
        BoardCap.Result again = BoardCap.run(board.port(), 15, T, log::add);
        assertTrue(again.capped());
        assertFalse(again.saved());
        assertEquals(1, board.saves);
        assertTrue(again.text().startsWith("AS IT IS ALREADY: motor_output_limit = 15 in each of the board's 2 PID profiles; nothing written."), again.text());
        assertTrue(BoardCap.run(board.port(), 100, T, log::add).capped());
        assertEquals(before, board.saved());
    }

    @Test
    void anArmedBoardIsNotWrittenTo() {
        board.arm();
        String before = board.saved();
        BoardCap.Result r = BoardCap.run(board.port(), 15, T, log::add);
        assertFalse(r.capped());
        assertEquals("The board is armed. Disarm it first.", r.refused());
        assertEquals(2, r.exitCode());
        assertEquals("REFUSED, nothing written: The board is armed. Disarm it first.", r.text());
        assertEquals(List.of(), board.cli(), "its CLI was not even opened");
        assertEquals(before, board.saved());
        assertEquals(0, board.saves);
    }

    @Test
    void aLimitThatIsNoPerCentIsRefused() {
        for (int limit : new int[]{0, -5, 101}) {
            BoardCap.Result r = BoardCap.run(board.port(), limit, T, log::add);
            assertEquals(2, r.exitCode());
            assertTrue(r.refused().contains("1 to 100"), r.refused());
        }
        assertEquals(List.of(), board.cli());
        assertEquals(0, board.opens, "the port was not opened");
    }

    @Test
    void aWriteBetaflightDoesNotTakeIsNotSaved() {
        String before = board.saved();
        board.refuseCli("set motor_output_limit");
        BoardCap.Result r = BoardCap.run(board.port(), 15, T, log::add);
        assertFalse(r.capped());
        assertFalse(r.saved());
        assertEquals(1, r.exitCode());
        assertTrue(r.stopped().contains("Betaflight refused \"set motor_output_limit = 15\""), r.stopped());
        assertTrue(r.text().startsWith("NOT CAPPED, and nothing saved: Betaflight refused \"set motor_output_limit = 15\""), r.text());
        assertEquals(0, board.saves);
        assertEquals(before, board.saved());
        assertEquals("exit", board.cli().get(board.cli().size() - 1), "left without saving: Betaflight drops what was typed");
        assertFalse(board.inMemory().contains("set motor_output_limit = 15"));
    }

    @Test
    void asTheTaskSaysIt() {
        String port = board.port().systemName();
        assertEquals(2, BoardCap.byHand(new String[]{port, ""}, out, T));
        assertTrue(text().contains("Which limit? gradlew boardCap -Pport=" + port + " -Plimit=15"), text());
        said.reset();
        assertEquals(2, BoardCap.byHand(new String[]{port, "low"}, out, T));
        assertTrue(text().contains("-Plimit=low is no number"), text());
        said.reset();
        assertEquals(0, BoardCap.byHand(new String[]{port, "15"}, out, T));
        String all = text();
        assertTrue(all.contains("motor_output_limit = 15 for the flight controller on " + board.port() + ": written in its CLI in every PID profile, saved (Betaflight restarts), read back."), all);
        assertTrue(all.contains("CAPPED: motor_output_limit = 15 in each of the board's 2 PID profiles, saved and read back (it was 100, 100)."), all);
        assertTrue(all.contains("Betaflight is back after its restart: disarmed."), all);
        assertEquals(1, board.saves);
        said.reset();
        assertEquals(1, BoardCap.byHand(new String[]{"COM99", "15"}, out, new BoardPort.Timing(5, 40)));
        assertTrue(text().contains("NOT CAPPED: No flight controller on COM99."), text());
    }

    private String text() {
        return said.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
