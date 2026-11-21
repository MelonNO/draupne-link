package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightUsb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three tools as the Gradle tasks start them (boardSnapshot, boardRestore, boardSafe): what they say and their
 * exit codes, against the made-up board - the tasks themselves are only ever run on a real one, by hand.
 */
class BoardToolsByHandTest {
    private static final BoardPort.Timing T = FakeFlightBoard.NO_WAITING, GIVE_UP_SOON = new BoardPort.Timing(5, 40);
    @TempDir
    Path dir;
    private final FakeFlightBoard board = FakeFlightBoard.flown();
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

    /** What was said since the last look. */
    private String said() {
        String text = said.toString(StandardCharsets.UTF_8);
        said.reset();
        return text;
    }

    @Test
    void aSnapshotARestoreAndASafeBoardAsTheTasksSayThem() throws Exception {
        Path root = dir.resolve("board"), backups = dir.resolve("backups");
        assertEquals(0, BoardSnapshot.byHand(new String[]{"", ""}, out, root, backups, T));
        String text = said();
        assertTrue(text.contains("Nothing on it is changed.") && text.contains("Sound to expect: none."), text);
        assertTrue(text.contains("Betaflight is back after its restart: disarmed."), text);
        Path snapshot;
        try (Stream<Path> folders = Files.list(root.resolve("4d4144452d55500000000001"))) {
            snapshot = folders.findFirst().orElseThrow();
        }
        assertTrue(text.contains("SNAPSHOT: " + snapshot), text);
        assertTrue(text.contains("gradlew boardRestore -Psnapshot=\"" + snapshot + "\""), text);
        try (Stream<Path> copies = Files.list(backups)) {
            assertEquals(1, copies.count());
        }
        // put back at once, the port named in small letters: nothing differs, nothing is written
        String port = board.port().systemName().toLowerCase(java.util.Locale.ROOT);
        assertEquals(0, BoardRestore.byHand(new String[]{port, snapshot.toString(), "", ""}, out, root, T));
        text = said();
        assertTrue(text.contains("AS IN THE SNAPSHOT ALREADY: nothing written."), text);
        assertTrue(text.contains("What a restore never puts back:") && text.contains("ESC settings") && text.contains("GPS module")
                && text.contains("spin direction") && text.contains("firmware"), text);
        assertTrue(text.contains("Betaflight is back after its restart: disarmed."), text);
        assertEquals(0, board.saves);
        // compare only, after a start with other defaults: the same settings, the other diff said in one line, exit code 0
        board.flipDefaultsAtNextStart = true;
        board.unplug();
        board.plugIn();
        assertEquals(0, BoardRestore.byHand(new String[]{"", snapshot.toString(), "", "", "only"}, out, root, T));
        text = said();
        assertTrue(text.contains("Compare only (nothing is written) of the flight controller on"), text);
        assertTrue(text.contains("COMPARED ONLY, nothing written: dump all and the MSP facts are the snapshot's."), text);
        assertTrue(text.contains("Note: Betaflight's own defaults differed at this start: 2 lines of diff all, the settings themselves are equal."), text);
        assertTrue(!text.contains("What a restore never puts back") && text.contains("Betaflight is back after its restart: disarmed."), text);
        assertEquals(0, board.saves);
        // a word after -Pcompare= that is not "only" is not taken for "go on and write"
        assertEquals(2, BoardRestore.byHand(new String[]{"", snapshot.toString(), "", "", "yes"}, out, root, T));
        assertTrue(said().contains("-Pcompare=yes is not known"));
        assertEquals(0, board.saves);
        // armed: the restore refuses, and the board is not safe
        board.arm();
        assertEquals(2, BoardRestore.byHand(new String[]{"", snapshot.toString()}, out, root, T));
        assertTrue(said().contains("REFUSED, nothing written: The board is armed. Disarm it first."));
        // R3: boardSafe sets the keeper's arming lock - which disarms an armed board; on a board where the lock does not
        // show it is not called safe
        board.ignoreArmingDisable = true;
        assertEquals(1, BoardSafe.byHand(new String[]{""}, out, T));
        text = said();
        assertTrue(text.contains("NOT SAFE: " + board.port().systemName() + ": the arming lock (MSP_ARMING_DISABLE) was sent and does not show in the board's status"), text);
        assertTrue(board.armed());
        board.ignoreArmingDisable = false;
        assertEquals(0, BoardSafe.byHand(new String[]{""}, out, T));
        text = said();
        assertTrue(text.contains("SAFE: motors read [1000, 1000, 1000, 1000] (at rest), disarmed, arming locked - it was ARMED: the lock disarmed it."), text);
        assertTrue(!board.armed() && board.armingLocked());
        assertEquals(0, BoardSafe.byHand(new String[]{port}, out, T));
        assertTrue(said().contains("SAFE: motors read [1000, 1000, 1000, 1000] (at rest), disarmed, arming locked."));
        // no snapshot named, and a port with no board on it
        assertEquals(2, BoardRestore.byHand(new String[]{"", ""}, out, root, T));
        assertTrue(said().contains("Which snapshot?"));
        assertEquals(1, BoardSnapshot.byHand(new String[]{"COM99"}, out, root, backups, GIVE_UP_SOON));
        assertTrue(said().contains("NO SNAPSHOT: No flight controller on COM99."));
        assertEquals(1, BoardRestore.byHand(new String[]{"COM99", snapshot.toString()}, out, root, GIVE_UP_SOON));
        assertTrue(said().contains("NOT RESTORED, and nothing written: No flight controller on COM99."));
        assertEquals(1, BoardSafe.byHand(new String[]{"COM99"}, out, GIVE_UP_SOON));
        assertTrue(said().contains("NOT SAFE: no flight controller found on COM99"));
        board.unplug();
        assertEquals(1, BoardSafe.byHand(new String[]{""}, out, GIVE_UP_SOON));
        assertTrue(said().contains("NOT SAFE: no flight controller found on USB"));
    }
}
