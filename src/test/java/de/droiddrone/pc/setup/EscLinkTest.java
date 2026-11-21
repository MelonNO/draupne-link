package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ESCs through the flight controller (1.0.71): which firmware each runs, its own beacon, and that beacon switched
 * off - against made-up ESCs whose memory behaves like flash ({@link FakeEscBoard}).
 */
class EscLinkTest {
    @TempDir
    Path dir;
    private final List<String> log = new ArrayList<>();

    @org.junit.jupiter.api.BeforeAll
    static void noWaiting() {
        EscLink.RETRY_PAUSE_MS = 0;
    }

    private EscLink open(BetaflightUsb fc) throws IOException {
        EscLink escs = fc.escs(log::add);
        escs.retryPauseMs = 0;
        return escs;
    }

    private static FakeEscBoard bluejayQuad() {
        FakeEscBoard board = new FakeEscBoard();
        for (int i = 0; i < 4; i++) board.silabs("Bluejay", 0, 19, "#S_H_50#", 4, i % 2 == 0 ? 1 : 2);
        return board;
    }

    @Test
    void theChecksumIsXmodems() {
        int crc = 0;
        for (byte b : "123456789".getBytes(StandardCharsets.US_ASCII)) crc = EscLink.crc(crc, b & 0xff);
        assertEquals(0x31C3, crc);
        // a frame: '/', command, address, count, parameters, checksum high byte first
        byte[] f = EscLink.frame(EscLink.FROM_PC, EscLink.READ, 0x1A00, new byte[]{(byte) 0x80}, -1);
        assertEquals(8, f.length);
        assertArrayEquals(new byte[]{0x2F, 0x3A, 0x1A, 0x00, 0x01, (byte) 0x80}, java.util.Arrays.copyOf(f, 6));
    }

    @Test
    void fourBluejayEscsAreReadWithTheirBeacon() throws IOException {
        FakeEscBoard board = bluejayQuad();
        try (BetaflightUsb fc = BetaflightUsb.onWire(board)) {
            List<EscFirmware.Info> all;
            try (EscLink escs = open(fc)) {
                assertEquals(4, escs.count());
                all = escs.infoAll();
            }
            assertEquals(4, all.size());
            for (EscFirmware.Info i : all) {
                assertEquals(EscFirmware.Kind.BLUEJAY, i.kind());
                assertEquals("Bluejay 0.19", i.firmware());
                assertEquals("S-H-50", i.layout());
                assertEquals("10 min", i.beaconText());
                assertTrue(i.beeps() && i.canSilence());
            }
            assertEquals("4 x Bluejay 0.19 - beacon 10 min", EscFirmware.summary(all));
            // reading wrote nothing, every ESC was let go again, and MSP answers after the passthrough
            for (FakeEscBoard.Esc e : board.escs) {
                assertEquals(0, e.erases + e.writes);
                assertEquals(1, e.restarts);
            }
            assertFalse(board.inPassthrough());
            assertEquals(22, fc.msp(101, null).length);
        }
    }

    @Test
    void theBeaconGoesOffAndNothingElseChanges() throws IOException {
        FakeEscBoard board = bluejayQuad();
        byte[][] before = new byte[4][];
        for (int i = 0; i < 4; i++) before[i] = board.escs.get(i).bytes(0x1A00, 512);
        try (BetaflightUsb fc = BetaflightUsb.onWire(board); EscLink escs = open(fc)) {
            for (int i = 0; i < 4; i++) {
                EscFirmware.Info after = escs.silence(i, dir);
                assertEquals("off", after.beaconText());
                assertFalse(after.beeps());
                assertEquals("Bluejay 0.19", after.firmware());
            }
        }
        for (int i = 0; i < 4; i++) {
            byte[] now = board.escs.get(i).bytes(0x1A00, 512);
            assertEquals(EscFirmware.BEACON_INFINITE, now[EscFirmware.BEACON_DELAY]);
            for (int b = 0; b < 512; b++) if (b != EscFirmware.BEACON_DELAY) assertEquals(before[i][b], now[b], "ESC " + (i + 1) + " byte " + b);
            assertEquals(1, board.escs.get(i).erases);
        }
        // what was there before is kept, one file an ESC
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> kept = files.sorted().toList();
            assertEquals(4, kept.size());
            assertArrayEquals(before[0], Files.readAllBytes(kept.get(0)));
            assertTrue(kept.get(0).getFileName().toString().startsWith("esc1-EFM8BB2-1A00-"), kept.get(0).toString());
        }
        // a second time there is nothing to do
        board.plugInAgain();
        try (BetaflightUsb fc = BetaflightUsb.onWire(board); EscLink escs = open(fc)) {
            assertEquals("off", escs.silence(0, dir).beaconText());
        }
        assertEquals(1, board.escs.get(0).erases);
    }

    @Test
    void theNewerChipHasItsSettingsElsewhereAndBiggerPages() throws IOException {
        FakeEscBoard board = new FakeEscBoard();
        FakeEscBoard.Esc e = board.bb51("Bluejay", 0, 21, "#Z_H_30#", 3, 2);
        e.memory[0x3000 + 0x700] = 0x42; // something else on the settings' page
        try (BetaflightUsb fc = BetaflightUsb.onWire(board); EscLink escs = open(fc)) {
            assertEquals("Bluejay 0.21 - beacon 5 min", EscFirmware.summary(List.of(escs.info(0))));
            assertEquals("off", escs.silence(0, dir).beaconText());
        }
        assertEquals(EscFirmware.BEACON_INFINITE, e.memory[0x3000 + EscFirmware.BEACON_DELAY]);
        assertEquals(2, e.memory[0x3000 + EscFirmware.MOTOR_DIRECTION]);
        assertEquals(0x42, e.memory[0x3000 + 0x700]);
        assertEquals(0x02, e.memory[0x0FFF], "the firmware is untouched");
        assertEquals((byte) 0xFF, e.memory[0x2FFF], "and so is what lies before the page");
    }

    @Test
    void theKindsAreToldApart() throws IOException {
        FakeEscBoard board = new FakeEscBoard();
        board.silabs("", 16, 7, "#A_H_30#", 4, 1);                 // BLHeli_S
        board.silabs("", 16, 9, "#A_H_30#", 5, 1);                 // BLHeli_M
        FakeEscBoard.Esc jesc = board.silabs("", 16, 7, "#A_H_30#", 2, 1);
        System.arraycopy("JESC".getBytes(StandardCharsets.US_ASCII), 0, jesc.memory, 0x90, 4);
        board.am32(2, 16);
        board.arm32();
        try (BetaflightUsb fc = BetaflightUsb.onWire(board); EscLink escs = open(fc)) {
            List<EscFirmware.Info> all = escs.infoAll();
            assertEquals("BLHeli_S 16.7", all.get(0).firmware());
            assertTrue(all.get(0).canSilence());
            assertEquals("BLHeli_M 16.9", all.get(1).firmware());
            assertFalse(all.get(1).canSilence(), "its beacon is off already");
            assertEquals("JESC 16.7", all.get(2).firmware());
            assertEquals("2 min", all.get(2).beaconText());
            assertEquals("AM32 2.16", all.get(3).firmware());
            assertFalse(all.get(3).beeps() || all.get(3).canSilence());
            assertEquals(EscFirmware.Kind.ARM_OTHER, all.get(4).kind());
            assertFalse(all.get(4).canSilence());
            assertEquals("1: BLHeli_S 16.7 - beacon 10 min; 2: BLHeli_M 16.9 - beacon off; 3: JESC 16.7 - beacon 2 min; "
                    + "4: AM32 2.16 - no beacon timer; 5: BLHeli_32 - beacon not readable", EscFirmware.summary(all));
            // nothing is written to an ESC this app can't silence
            escs.silence(3, dir);
            escs.silence(4, dir);
            escs.silence(1, dir);
        }
        for (int i : new int[]{1, 3, 4}) assertEquals(0, board.escs.get(i).erases + board.escs.get(i).writes, "ESC " + (i + 1));
    }

    @Test
    void anEscWithoutPowerIsSaidAndTheOthersAreRead() throws IOException {
        FakeEscBoard board = bluejayQuad();
        board.escs.get(2).answers = false;
        board.escs.get(0).refuseConnects = 2; // still on its way into the bootloader: asked again
        try (BetaflightUsb fc = BetaflightUsb.onWire(board); EscLink escs = open(fc)) {
            List<EscFirmware.Info> all = escs.infoAll();
            assertEquals("Bluejay 0.19", all.get(0).firmware());
            assertEquals("no answer (battery on?)", all.get(2).problem());
            assertFalse(all.get(2).canSilence());
            assertEquals("Bluejay 0.19", all.get(3).firmware());
            assertTrue(EscFirmware.summary(all).contains("3: no answer (battery on?)"), EscFirmware.summary(all));
        }
    }

    @Test
    void noiseOnTheLineIsAskedAgain() throws IOException {
        FakeEscBoard board = bluejayQuad();
        try (BetaflightUsb fc = BetaflightUsb.onWire(board); EscLink escs = open(fc)) {
            escs.init(0);
            board.noiseOnce = true;
            assertEquals(0x100, escs.readBytes(0x1A00, 0x100).length);
        }
    }

    @Test
    void aWriteThatReadsBackWrongIsDoneAgain() throws IOException {
        FakeEscBoard board = bluejayQuad();
        FakeEscBoard.Esc e = board.escs.get(1);
        byte[] before = e.bytes(0x1A00, 512);
        e.corruptWrites = 1;
        try (BetaflightUsb fc = BetaflightUsb.onWire(board); EscLink escs = open(fc)) {
            assertEquals("off", escs.silence(1, dir).beaconText());
        }
        assertEquals(2, e.erases);
        byte[] now = e.bytes(0x1A00, 512);
        for (int b = 0; b < 512; b++) if (b != EscFirmware.BEACON_DELAY) assertEquals(before[b], now[b], "byte " + b);
        assertEquals(2, now[EscFirmware.MOTOR_DIRECTION], "the direction is as it was (reversed)");
    }

    @Test
    void whenItCannotBeWrittenTheOldSettingsGoBack() throws IOException {
        FakeEscBoard board = bluejayQuad();
        FakeEscBoard.Esc e = board.escs.get(0);
        byte[] before = e.bytes(0x1A00, 512);
        e.corruptWrites = 6; // every try of the new settings (two writes each) reads back wrong; the old ones then write fine
        try (BetaflightUsb fc = BetaflightUsb.onWire(board); EscLink escs = open(fc)) {
            IOException said = assertThrows(IOException.class, () -> escs.silence(0, dir));
            assertTrue(said.getMessage().contains("Its settings are as before"), said.getMessage());
        }
        assertArrayEquals(before, e.bytes(0x1A00, 512));
    }

    @Test
    void anEscThatDiesAfterTheEraseIsSaidLoudly() throws IOException {
        FakeEscBoard board = bluejayQuad();
        FakeEscBoard.Esc e = board.escs.get(1); // reversed
        e.diesAfterErase = true;
        try (BetaflightUsb fc = BetaflightUsb.onWire(board); EscLink escs = open(fc)) {
            IOException said = assertThrows(IOException.class, () -> escs.silence(1, dir));
            assertTrue(said.getMessage().contains("ESC 2: SETTINGS MAY BE LOST"), said.getMessage());
            assertTrue(said.getMessage().contains("it was REVERSED"), said.getMessage());
            assertTrue(said.getMessage().contains("esc2-EFM8BB2-1A00-"), said.getMessage());
            // the others are still reached
            assertEquals("off", escs.silence(2, dir).beaconText());
        }
    }

    @Test
    void settingsThatAreNotUnderstoodAreNeverWritten() throws IOException {
        FakeEscBoard board = new FakeEscBoard();
        FakeEscBoard.Esc odd = board.silabs("", 16, 7, "garbage", 4, 1);      // no "#...#" layout
        FakeEscBoard.Esc range = board.silabs("Bluejay", 0, 19, "#S_H_50#", 9, 1); // a beacon value there is none of
        FakeEscBoard.Esc blank = board.silabs("", 16, 7, "#A_H_30#", 4, 1);
        java.util.Arrays.fill(blank.memory, 0x1A00, 0x1C00, (byte) 0xFF);
        try (BetaflightUsb fc = BetaflightUsb.onWire(board); EscLink escs = open(fc)) {
            assertEquals("settings not understood", escs.silence(0, dir).problem());
            assertEquals("beacon setting not understood (9)", escs.silence(1, dir).problem());
            assertEquals("no settings (blank)", escs.silence(2, dir).problem());
        }
        for (FakeEscBoard.Esc e : List.of(odd, range, blank)) assertEquals(0, e.erases + e.writes);
        assertNull(EscFirmware.beaconOff(EscFirmware.chip(0xE8B2, EscLink.MODE_SILABS), odd.bytes(0x1A00, 512)));
        assertNotNull(EscFirmware.chip(0xE8B1, EscLink.MODE_SILABS));
        assertNull(EscFirmware.chip(0x1234, EscLink.MODE_SILABS));
        assertNull(EscFirmware.chip(0xE8B2, EscLink.MODE_ATMEL));
    }

    // ------------------------------------------------------------------ Drone setup's ESCS row (EscCheck)

    @Test
    void theRowSaysWhatBeepsAndOffersTheBeaconOff() {
        FakeEscBoard board = bluejayQuad();
        EscCheck.Result read = EscCheck.run(() -> BetaflightUsb.onWire(board), false, dir, log::add);
        assertEquals("WARN", read.state());
        assertTrue(read.canSilence());
        assertEquals("4 x Bluejay 0.19 - beacon 10 min.<br>Beeps at rest. <b>Beacon off</b> ends it.", read.text());
        assertTrue(log.contains("ESCs: 4 x Bluejay 0.19 - beacon 10 min."), log.toString());
        for (FakeEscBoard.Esc e : board.escs) assertEquals(0, e.erases);

        board.plugInAgain();
        EscCheck.Result off = EscCheck.run(() -> BetaflightUsb.onWire(board), true, dir, log::add);
        assertEquals("OK", off.state());
        assertFalse(off.canSilence());
        assertEquals("4 x Bluejay 0.19 - beacon off.", off.text());
        assertTrue(log.contains("ESC 3: beacon 10 min -> off (saved in the ESC)."), log.toString());
        assertFalse(board.inPassthrough(), "the port is given back");
    }

    @Test
    void escsWithoutABeaconTimerAreSimplyFine() {
        FakeEscBoard board = new FakeEscBoard();
        for (int i = 0; i < 4; i++) board.am32(2, 17);
        EscCheck.Result r = EscCheck.run(() -> BetaflightUsb.onWire(board), true, dir, log::add);
        assertEquals("OK", r.state());
        assertEquals("4 x AM32 2.17 - no beacon timer.", r.text());
        for (FakeEscBoard.Esc e : board.escs) assertEquals(0, e.erases + e.writes);
    }

    @Test
    void theRowSaysWhenNothingAnswersOrTheDroneIsArmedOrAWriteFailed() {
        FakeEscBoard dead = bluejayQuad();
        for (FakeEscBoard.Esc e : dead.escs) e.answers = false;
        EscCheck.Result none = EscCheck.run(() -> BetaflightUsb.onWire(dead), false, dir, log::add);
        assertEquals("WARN", none.state());
        assertEquals("No ESC answered. <b>Battery in</b>, props off, then <b>Read ESCs</b>.", none.text());
        assertFalse(none.canSilence());

        FakeEscBoard armed = bluejayQuad();
        armed.armed = true;
        EscCheck.Result no = EscCheck.run(() -> BetaflightUsb.onWire(armed), true, dir, log::add);
        assertEquals("The drone is armed. Disarm it first.", no.text());

        FakeEscBoard one = bluejayQuad();
        one.escs.get(1).diesAfterErase = true;
        EscCheck.Result bad = EscCheck.run(() -> BetaflightUsb.onWire(one), true, dir, log::add);
        assertEquals("BAD", bad.state());
        assertTrue(bad.text().contains("ESC 2: SETTINGS MAY BE LOST"), bad.text());
        assertTrue(bad.text().contains("motor test"), bad.text());
        // the three others were done
        assertEquals(EscFirmware.BEACON_INFINITE, one.escs.get(3).memory[0x1A00 + EscFirmware.BEACON_DELAY]);

        FakeEscBoard mixed = new FakeEscBoard();
        mixed.arm32();
        EscCheck.Result unread = EscCheck.run(() -> BetaflightUsb.onWire(mixed), false, dir, log::add);
        assertEquals("INFO", unread.state());
        assertTrue(unread.text().contains("BLHeliSuite32"), unread.text());
    }

    @Test
    void anArmedDroneIsNeverHandedToTheEscs() {
        FakeEscBoard board = bluejayQuad();
        board.armed = true;
        try (BetaflightUsb fc = BetaflightUsb.onWire(board)) {
            IOException said = assertThrows(IOException.class, () -> fc.escs(log::add));
            assertEquals("The drone is armed. Disarm it first.", said.getMessage());
            assertFalse(board.inPassthrough());
            assertFalse(board.said.contains("MSP 245"));
        }
    }

    @Test
    void closingThePortEndsThePassthroughFirst() throws IOException {
        FakeEscBoard board = bluejayQuad();
        BetaflightUsb fc = BetaflightUsb.onWire(board);
        EscLink escs = open(fc);
        escs.info(0);
        assertTrue(board.inPassthrough());
        fc.close(); // the pilot closes Drone setup in the middle
        assertFalse(board.inPassthrough());
        assertTrue(escs.closed());
        assertEquals(1, board.escs.get(3).restarts);
    }
}
