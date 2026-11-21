package de.droiddrone.bench.hw;

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
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A board's settings read and filed ({@link BoardSnapshot}), against the made-up board: all of it, and nothing changed. */
class BoardSnapshotTest {
    /** MSP requests that change something on a board, or hand its port to the ESCs. */
    private static final Set<Integer> WRITES = Set.of(Msp.SET_FEATURE_CONFIG, Msp.SET_MIXER_CONFIG, Msp.REBOOT, Msp.SET_BEEPER_CONFIG,
            Msp.SET_RAW_RC, Msp.SET_MOTOR, Msp.SET_PASSTHROUGH, Msp.EEPROM_WRITE, Msp.SET_MOTOR_OUTPUT_REORDERING, Msp.SEND_DSHOT_COMMAND);
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

    private BoardSnapshot take(boolean escs) throws IOException {
        return BoardSnapshot.take(board.port(), new BoardSnapshot.Options(escs, "", FakeFlightBoard.NO_WAITING), log::add);
    }

    @Test
    void oneVisitToTheCliReadsItAllAndChangesNothing() throws IOException {
        String before = board.saved();
        BoardSnapshot s = take(false);
        assertEquals(List.of("diff all", "dump all", "exit"), board.cli(), "the CLI entered once: leaving it restarts Betaflight");
        assertEquals(1, board.restarts);
        assertEquals(0, board.saves);
        assertEquals(before, board.saved());
        for (int request : board.mspRequests()) assertFalse(WRITES.contains(request), "MSP " + request + " is no read");
        assertEquals("4d4144452d55500000000001", s.uid());
        assertTrue(s.version().startsWith("Betaflight / STM32F405 (S405) 4.4.1"), s.version());
        assertEquals(board.savedDiff(), s.diffAll());
        assertEquals(before, s.dumpAll());
        assertEquals("4254464c", s.msp().get("fc_variant"));
        assertEquals("040401", s.msp().get("fc_version"));
        assertEquals(s.uid(), s.msp().get("uid"));
        byte[] mask = new byte[4];
        Msp.putU32(mask, 0, board.featureMask());
        assertEquals(Msp.hex(mask), s.msp().get("features"));
        assertEquals("0300", s.msp().get("mixer_and_props_direction"), "Quad X, props in");
        assertEquals("080001020304050607", s.msp().get("motor_order"));
        assertEquals("0001030204050607", s.msp().get("rx_map"), "AETR1234");
        assertTrue(s.msp().get("mode_ranges").startsWith("00022030" + "01010010"), "ARM on AUX3 up, ANGLE on AUX2 down: " + s.msp().get("mode_ranges"));
        assertEquals(18, s.msp().get("beeper").length());
        assertNull(s.escs(), "not asked for");
        assertFalse(board.mspRequests().contains(Msp.SET_PASSTHROUGH));
    }

    @Test
    void itIsFiledWithHashesAndTheDiffCopiedForAPasteByHand() throws IOException {
        BoardSnapshot s = take(false);
        Path folder = s.write(dir.resolve("board"), dir.resolve("backups"));
        assertEquals(dir.resolve("board").resolve(s.uid()), folder.getParent(), "a folder a board, a folder a snapshot");
        assertEquals(folder, s.folder());
        String index = Files.readString(folder.resolve(BoardSnapshot.INDEX));
        for (String file : List.of(BoardSnapshot.DIFF, BoardSnapshot.DUMP, BoardSnapshot.MSP_FACTS)) {
            assertTrue(index.contains("sha256 " + file + " = " + BoardSnapshot.sha256(Files.readAllBytes(folder.resolve(file))) + "\n"), file);
        }
        assertTrue(index.contains("uid = " + s.uid() + "\n") && index.contains("version = " + s.version() + "\n"), index);
        List<Path> copies;
        try (Stream<Path> files = Files.list(dir.resolve("backups"))) {
            copies = files.toList();
        }
        assertEquals(1, copies.size());
        assertTrue(copies.get(0).getFileName().toString().matches("betaflight-\\d{4}-\\d\\d-\\d\\d_\\d\\d-\\d\\d-\\d\\d\\.txt"), copies.toString());
        String copy = Files.readString(copies.get(0));
        assertTrue(copy.startsWith("# " + s.version() + "\n") && copy.contains(s.diffAll()), copy);
        // read back: the same snapshot
        BoardSnapshot back = BoardSnapshot.load(folder);
        assertEquals(List.of(), s.differences(back));
        assertEquals(List.of(), s.firmwareDifferences(back));
        assertEquals(s.uid(), back.uid());
        assertEquals(s.diffAll(), back.diffAll());
        assertEquals(s.dumpAll(), back.dumpAll());
        assertEquals(s.msp(), back.msp());
        // a second one in the same second has a folder of its own
        assertNotEquals(folder, s.write(dir.resolve("board"), null));
    }

    @Test
    void aFileChangedAfterwardsIsNotLoaded() throws IOException {
        Path folder = take(false).write(dir, null);
        Path diff = folder.resolve(BoardSnapshot.DIFF);
        Files.writeString(diff, Files.readString(diff).replace("small_angle = 40", "small_angle = 90"));
        IOException e = assertThrows(IOException.class, () -> BoardSnapshot.load(folder));
        assertTrue(e.getMessage().contains(BoardSnapshot.DIFF) && e.getMessage().contains("SHA-256"), e.getMessage());
        Files.delete(diff);
        assertThrows(IOException.class, () -> BoardSnapshot.load(folder));
        assertThrows(IOException.class, () -> BoardSnapshot.load(dir.resolve("no-such-snapshot")));
    }

    @Test
    void everyChangeIsNamedAndWhatChangesByItselfIsNot() throws IOException {
        BoardSnapshot was = take(false);
        board.fly();
        assertTrue(board.saved().contains("set stats_total_flights = 1"));
        assertEquals(List.of(), was.differences(take(false)), "Betaflight's flight statistics count by themselves");
        board.setUp("profile 1", "set p_roll = 70", "profile 0", "aux 3 13 13 1700 2100 0 0", "feature -GPS", "set small_angle = 25");
        List<String> d = was.differences(take(false));
        for (String expected : List.of(
                "diff all: only in the snapshot: [profile 1] set p_roll = 60",
                "diff all: only on the board now: [profile 1] set p_roll = 70",
                "dump all: only in the snapshot: [profile 1] set p_roll = 60",
                "diff all: only on the board now: [aux] aux 3 13 13 1700 2100 0 0",
                "dump all: only in the snapshot: [aux] aux 3 0 0 900 900 0 0",
                "diff all: only in the snapshot: [feature] feature GPS",
                "dump all: only on the board now: [feature] feature -GPS",
                "diff all: only in the snapshot: [master] set small_angle = 40",
                "dump all: only on the board now: [master] set small_angle = 25")) {
            assertTrue(d.contains(expected), expected + " in " + d);
        }
        assertTrue(d.stream().anyMatch(l -> l.startsWith("MSP features: ")), d.toString());
        assertTrue(d.stream().anyMatch(l -> l.startsWith("MSP mode_ranges: ")), d.toString());
        assertFalse(d.stream().anyMatch(l -> l.contains("stats_")), d.toString());
        // the diff: four lines gone or new, p_roll both, and the feature part's heading gone with its only line
        assertTrue(d.contains("diff all: only in the snapshot: # feature"), d.toString());
        assertEquals(6 + 8 + 2, d.size(), "nothing else: " + d);
    }

    @Test
    void theEscsSettingsAreKeptWithABatteryInAndSaidMissingWithout() throws IOException {
        // two BLHeli_S-kind ESCs (Bluejay) and two AM32: each kind's own page, where its chip has it
        board.fitEscs(escs -> {
            for (int i = 0; i < 2; i++) escs.silabs("Bluejay", 0, 19, "#S_H_50#", 4, 1);
            for (int i = 0; i < 2; i++) escs.am32(2, 16);
        });
        BoardSnapshot without = take(true);
        assertEquals(1, without.escs().size());
        assertTrue(without.escs().get(0).problem().contains("no battery"), without.escs().get(0).problem());
        assertFalse(board.mspRequests().contains(Msp.SET_PASSTHROUGH), "an ESC without power is not even asked");
        board.battery(16.4, 4);
        BoardSnapshot with = take(true);
        assertEquals(4, with.escs().size());
        for (int i = 0; i < 4; i++) {
            BoardSnapshot.Esc e = with.escs().get(i);
            assertNull(e.problem());
            assertEquals(i < 2 ? "Bluejay 0.19 - beacon 10 min" : "AM32 2.16 - no beacon timer", e.says());
            assertEquals(i < 2 ? 0x1A00 : 0x7C00, e.settingsAt());
            assertArrayEquals(board.escs().get(i).bytes(e.settingsAt(), i < 2 ? 512 : 1024), e.page());
        }
        for (FakeEscBoard.Esc e : board.escs()) assertEquals(0, e.erases + e.writes, "read only");
        assertFalse(board.inPassthrough());
        Path folder = with.write(dir, null);
        assertEquals(1024, Files.size(folder.resolve("esc3-settings.bin")));
        BoardSnapshot back = BoardSnapshot.load(folder);
        assertEquals(List.of(), with.escDifferences(back));
        assertEquals(List.of(), back.escDifferences(take(true)));
        // one ESC's saved spin direction changed, and a byte of an AM32's settings (no names known for those)
        board.escs().get(1).memory[0x1A00 + EscFirmware.MOTOR_DIRECTION] = 2;
        board.escs().get(2).memory[0x7C00 + 0x1D] = 7;
        List<String> d = back.escDifferences(take(true));
        assertEquals(2, d.size(), d.toString());
        assertTrue(d.get(0).startsWith("ESC 2: its settings changed (1 byte: 0x0B (motor direction) 01 -> 02)"), d.get(0));
        assertTrue(d.get(1).startsWith("ESC 3: its settings changed (1 byte: 0x1D 00 -> 07)"), d.get(1));
        // read without a battery they can't be said to be the same
        List<String> unread = back.escDifferences(without);
        assertEquals(4, unread.size());
        assertTrue(unread.get(3).startsWith("ESC 4: not read now (no battery"), unread.get(3));
        assertEquals(List.of(), without.escDifferences(with), "a snapshot without their pages has nothing to compare");
    }

    @Test
    void anArmedBoardIsLeftAlone() {
        board.arm();
        IOException e = assertThrows(IOException.class, () -> take(false));
        assertTrue(e.getMessage().contains("armed"), e.getMessage());
        assertEquals(List.of(), board.cli());
        assertFalse(board.inCli());
        assertEquals(0, board.restarts);
        assertTrue(board.armed());
    }
}
