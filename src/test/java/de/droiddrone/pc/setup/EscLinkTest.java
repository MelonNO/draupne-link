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
            assertEquals("no answer", all.get(2).problem(), "why is for EscCheck to say, from the battery");
            assertTrue(all.get(2).silent());
            assertFalse(all.get(2).canSilence());
            assertFalse(all.get(0).silent());
            assertEquals("Bluejay 0.19", all.get(3).firmware());
            assertTrue(EscFirmware.summary(all).contains("3: no answer;"), EscFirmware.summary(all));
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
        dead.volts.addAll(List.of(16.0, 0.3)); // the battery in, then out during the read: they had no power
        EscCheck.Result none = EscCheck.run(() -> BetaflightUsb.onWire(dead), false, dir, log::add);
        assertEquals("WARN", none.state());
        assertEquals("No ESC answered. <b>Battery in</b>, props off, then <b>Read ESCs</b>.", none.text());
        assertFalse(none.canSilence());
        assertFalse(none.holds());

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

    // ------------------------------------------------------------------ F-080: an ESC that did not answer

    private static final String ESC1_RED = "ESC 1 did not answer - it may not turn now. Unplug the battery, plug it in, <b>Read ESCs</b> again.";

    /** The test drone of F-080: four AM32 2.17, the battery in as Betaflight reads it. */
    private static FakeEscBoard am32Quad() {
        FakeEscBoard board = new FakeEscBoard();
        for (int i = 0; i < 4; i++) board.am32(2, 17);
        board.volts.add(16.0);
        return board;
    }

    private EscCheck.Result read(FakeEscBoard board, boolean silence) {
        board.plugInAgain();
        return EscCheck.run(() -> BetaflightUsb.onWire(board), silence, dir, log::add);
    }

    @Test
    void anEscThatDoesNotAnswerWithTheBatteryInIsRedAndSaysUnplugTheBattery() {
        FakeEscBoard board = am32Quad();
        board.escs.get(0).answers = false;
        EscCheck.Result r = read(board, false);
        assertEquals("BAD", r.state(), r.text());
        assertTrue(r.holds());
        assertEquals(List.of(1), r.silent());
        assertEquals("1: no answer; 2: AM32 2.17 - no beacon timer; 3: AM32 2.17 - no beacon timer; 4: AM32 2.17 - no beacon timer.<br>"
                + ESC1_RED, r.text());
        assertFalse(r.text().contains("battery on?"), "the battery is in: not the reason");
        assertTrue(log.contains("ESC 1 did not answer - it may not turn now. Unplug the battery, plug it in, Read ESCs again."), log.toString());
        assertFalse(board.inPassthrough(), "the port is given back");

        // several
        board.escs.get(2).answers = false;
        EscCheck.Result two = read(board, false);
        assertEquals("BAD", two.state());
        assertTrue(two.text().endsWith("<br>ESC 1, 3 did not answer - they may not turn now. Unplug the battery, plug it in, <b>Read ESCs</b> again."), two.text());

        // none: red too, with the battery in
        for (FakeEscBoard.Esc e : board.escs) e.answers = false;
        EscCheck.Result none = read(board, false);
        assertEquals("BAD", none.state());
        assertEquals("ESC 1, 2, 3, 4 did not answer - they may not turn now. Unplug the battery, plug it in, <b>Read ESCs</b> again.", none.text());
        assertTrue(none.holds());

        // Betaflight does not say what the battery is: nothing says it is out - red
        FakeEscBoard unknown = bluejayQuad();
        unknown.escs.get(0).answers = false;
        EscCheck.Result notKnown = read(unknown, false);
        assertEquals("BAD", notKnown.state());
        assertTrue(notKnown.text().endsWith("<br>" + ESC1_RED), notKnown.text());
        assertFalse(notKnown.canSilence(), "Beacon off waits for a read in which all answer");
    }

    @Test
    void whenAllAnswerTheRowIsAsBefore() {
        EscCheck.Result r = read(am32Quad(), false);
        assertEquals("OK", r.state());
        assertEquals("4 x AM32 2.17 - no beacon timer.", r.text());
        assertFalse(r.holds());
        assertEquals(List.of(), r.silent());
        FakeEscBoard beeping = bluejayQuad();
        beeping.volts.add(16.0);
        EscCheck.Result b = read(beeping, false);
        assertEquals("WARN", b.state());
        assertEquals("4 x Bluejay 0.19 - beacon 10 min.<br>Beeps at rest. <b>Beacon off</b> ends it.", b.text());
        assertFalse(log.stream().anyMatch(l -> l.contains("did not answer")), log.toString());
    }

    @Test
    void withNoBatteryTheRowStillAsksForItAndIsNotRed() {
        // the battery pulled during the read: ESC 3 had no power
        FakeEscBoard pulled = bluejayQuad();
        pulled.volts.addAll(List.of(16.0, 0.3));
        pulled.escs.get(2).answers = false;
        EscCheck.Result r = read(pulled, false);
        assertEquals("WARN", r.state(), r.text());
        assertFalse(r.holds());
        assertTrue(r.text().contains("3: no answer (battery on?)"), r.text());
        assertFalse(r.text().contains("did not answer"), r.text());
        // out from the start: the ESCs are not asked
        FakeEscBoard out = bluejayQuad();
        out.volts.add(0.3);
        EscCheck.Result no = read(out, false);
        assertEquals("WARN", no.state());
        assertEquals("No battery. <b>Battery in</b>, props off, then <b>Read ESCs</b>.", no.text());
        assertFalse(out.said.contains("MSP 245"), "no passthrough without a battery");
    }

    @Test
    void beaconOffSaysTheSameAndWritesOnlyTheEscsThatAnswered() {
        FakeEscBoard board = bluejayQuad();
        board.volts.add(16.0);
        board.escs.get(1).answers = false;
        EscCheck.Result r = read(board, true);
        assertEquals("BAD", r.state());
        assertTrue(r.text().endsWith("<br>ESC 2 did not answer - it may not turn now. Unplug the battery, plug it in, <b>Read ESCs</b> again."), r.text());
        assertFalse(r.canSilence(), "Beacon off is not offered again until all answer");
        assertEquals(0, board.escs.get(1).erases + board.escs.get(1).writes);
        assertEquals(EscFirmware.BEACON_INFINITE, board.escs.get(0).memory[0x1A00 + EscFirmware.BEACON_DELAY]);
        assertTrue(log.contains("ESC 2 did not answer - it may not turn now. Unplug the battery, plug it in, Read ESCs again."), log.toString());
    }

    @Test
    void theRedStaysWithTheBoardUntilAReadInWhichAllAnswer() {
        FakeEscBoard board = am32Quad();
        board.escs.get(0).answers = false;
        EscCheck.Hold hold = new EscCheck.Hold();
        assertTrue(hold.after("uid:01", read(board, false)));
        assertEquals(List.of(1), hold.on("uid:01"));
        assertEquals(List.of(), hold.on("uid:02"), "another board is not held by it");
        assertEquals(List.of(), hold.on(null));

        // Drone setup closed and opened again: still held
        EscCheck.Hold again = new EscCheck.Hold();
        again.restore(hold.saved());
        assertEquals(List.of(1), again.on("uid:01"));

        // a read that does not reach the ESCs changes nothing; its row stays red and says why under its own words
        board.armed = true;
        EscCheck.Result armed = read(board, false);
        assertFalse(again.after("uid:01", armed));
        assertArrayEquals(new String[]{"BAD", "The drone is armed. Disarm it first.<br>" + ESC1_RED}, EscCheck.row(armed, again.on("uid:01")));
        // nor does one with the battery out
        board.armed = false;
        board.volts.clear();
        board.volts.addAll(List.of(16.0, 0.3));
        EscCheck.Result out = read(board, false);
        assertFalse(again.after("uid:01", out));
        assertEquals("BAD", EscCheck.row(out, again.on("uid:01"))[0]);
        assertEquals(List.of(1), again.on("uid:01"));

        // the battery unplugged and in again: all answer, the hold goes and the row is as before
        board.escs.get(0).answers = true;
        board.volts.clear();
        board.volts.add(16.0);
        EscCheck.Result good = read(board, false);
        assertTrue(again.after("uid:01", good));
        assertEquals(List.of(), again.on("uid:01"));
        assertEquals("", again.saved());
        assertArrayEquals(new String[]{"OK", "4 x AM32 2.17 - no beacon timer."}, EscCheck.row(good, again.on("uid:01")));
    }

    @Test
    void theHoldIsKeptByBoardAndReadsBackWhatItSaved() {
        EscCheck.Hold hold = new EscCheck.Hold();
        hold.restore("uid:0a=1,3;?=2");
        assertEquals(List.of(1, 3), hold.on("uid:0a"));
        assertEquals(List.of(2), hold.on(null), "a board that does not say its id");
        assertEquals("uid:0a=1,3;?=2", hold.saved());
        for (String nothing : new String[]{null, "", "false", "uid:0a=", "uid:0a=x", "=1"}) {
            hold.restore(nothing);
            assertEquals("", hold.saved(), String.valueOf(nothing));
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

    // ------------------------------------------------------------------ F-080: the by-hand tries' visit (EscProbe)

    @Test
    void theAppsOwnReadStillAsksMotorOneFirstSixTimesThenTheOthersThenAgain() throws IOException {
        FakeEscBoard board = am32Quad();
        board.escs.get(0).refuseConnects = 6; // not in its bootloader for the whole first pass
        try (BetaflightUsb fc = BetaflightUsb.onWire(board); EscLink escs = open(fc)) {
            List<EscFirmware.Info> all = escs.infoAll();
            assertEquals("4 x AM32 2.17 - no beacon timer", EscFirmware.summary(all));
        }
        assertEquals(List.of(0, 0, 0, 0, 0, 0, 1, 2, 3, 0), board.asked);
    }

    @Test
    void aVisitAsksTheEscsInTheOrderGivenAndOnlyThose() throws IOException {
        FakeEscBoard board = am32Quad();
        List<String> trace = new ArrayList<>();
        EscLink.Visit v = new EscLink.Visit(EscLink.Visit.order("4,3,2,1"), 0, 6, 0, 2, trace::add);
        try (BetaflightUsb fc = BetaflightUsb.onWire(board); EscLink escs = open(fc)) {
            assertEquals("4 x AM32 2.17 - no beacon timer", EscFirmware.summary(escs.infoAll(v)));
            for (int i = 0; i < 4; i++) assertNotNull(escs.settingsRead[i], "ESC " + (i + 1) + "'s settings kept for the probe");
        }
        assertEquals(List.of(3, 2, 1, 0), board.asked);
        assertTrue(trace.get(0).matches("\\+\\d+\\.\\d{3} s ESC 4 connect \\(pass 1, try 1\\): answered, 1F06 mode 4"), trace.get(0));
        for (FakeEscBoard.Esc e : board.escs) assertEquals(1, e.restarts, "every ESC is started again at the end, asked or not");

        board.asked.clear();
        board.plugInAgain();
        trace.clear();
        try (BetaflightUsb fc = BetaflightUsb.onWire(board); EscLink escs = open(fc)) {
            List<EscFirmware.Info> all = escs.infoAll(new EscLink.Visit(EscLink.Visit.order("4, 2,6"), 0, 6, 0, 2, trace::add));
            assertEquals(EscLink.NOT_ASKED, all.get(0).problem());
            assertEquals(EscLink.NOT_ASKED, all.get(2).problem());
            assertEquals("AM32 2.17", all.get(1).firmware());
            assertEquals("AM32 2.17", all.get(3).firmware());
            assertNull(escs.settingsRead[0]);
        }
        assertEquals(List.of(3, 1), board.asked);
        assertTrue(trace.stream().anyMatch(s -> s.endsWith("ESC 6: Betaflight has only 4 motor outputs - skipped")), trace.toString());
    }

    @Test
    void theFirstWaitSendsNothingToAnyEscBeforeItsTime() throws IOException {
        FakeEscBoard board = am32Quad();
        List<String> trace = new ArrayList<>();
        try (BetaflightUsb fc = BetaflightUsb.onWire(board); EscLink escs = open(fc)) {
            escs.infoAll(new EscLink.Visit(null, 150, 6, 0, 2, trace::add));
        }
        long waited = (board.firstCommandAt - board.passthroughAt) / 1_000_000;
        assertTrue(waited >= 150, "the first connect came " + waited + " ms after the passthrough began");
        assertTrue(trace.get(0).endsWith("nothing sent until 0.150 s (the ESC lines held high)"), trace.get(0));
        assertEquals(List.of(0, 1, 2, 3), board.asked);
    }

    @Test
    void triesAndPassesAreAsGivenAndEachConnectHasItsLine() throws IOException {
        FakeEscBoard board = am32Quad();
        board.escs.get(0).refuseConnects = 3;
        List<String> trace = new ArrayList<>();
        try (BetaflightUsb fc = BetaflightUsb.onWire(board); EscLink escs = open(fc)) {
            List<EscFirmware.Info> all = escs.infoAll(new EscLink.Visit(null, 0, 2, 0, 1, trace::add));
            assertTrue(all.get(0).silent(), "two connects, one pass: not enough for this one");
            assertEquals("AM32 2.17", all.get(3).firmware());
        }
        assertEquals(List.of(0, 0, 1, 2, 3), board.asked);
        assertTrue(trace.get(1).endsWith("ESC 1 connect (pass 1, try 2): ESC connect: the ESC did not answer"), trace.get(1));
        assertTrue(log.contains("ESC 1: ESC connect: the ESC did not answer"), log.toString());

        board.asked.clear();
        board.plugInAgain();
        board.escs.get(0).refuseConnects = 3;
        try (BetaflightUsb fc = BetaflightUsb.onWire(board); EscLink escs = open(fc)) {
            List<EscFirmware.Info> all = escs.infoAll(new EscLink.Visit(null, 0, 2, 0, 2, null));
            assertEquals("AM32 2.17", all.get(0).firmware(), "a second pass, and it answers on its fourth connect");
        }
        assertEquals(List.of(0, 0, 1, 2, 3, 0, 0), board.asked);
    }

    @Test
    void theOrderIsReadAsMotorNumbers() {
        assertArrayEquals(new int[]{3, 2, 1, 0}, EscLink.Visit.order("4,3,2,1"));
        assertArrayEquals(new int[]{0}, EscLink.Visit.order(" 1 "));
        assertNull(EscLink.Visit.order(" "));
        assertNull(EscLink.Visit.order(null));
        for (String bad : List.of("0", "1,1", "x", "1,,2", "256")) {
            assertThrows(IllegalArgumentException.class, () -> EscLink.Visit.order(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new EscLink.Visit(null, -1, 6, 0, 2, null));
        assertThrows(IllegalArgumentException.class, () -> new EscLink.Visit(null, 0, 0, 0, 2, null));
        assertThrows(IllegalArgumentException.class, () -> new EscLink.Visit(null, 0, 6, 0, 0, null));
    }

    @Test
    void theProbesOptionsKeepTheAppsVisitUnlessAskedAndRecoverWaits() {
        assertTrue(EscProbe.visit("read", "", "", "", "", "") == EscLink.Visit.AS_THE_APP);
        EscLink.Visit recover = EscProbe.visit("recover", "1", "", "", "", "");
        assertEquals(EscProbe.RECOVER_WAIT_MS, recover.firstWaitMs());
        assertArrayEquals(new int[]{0}, recover.order());
        assertEquals(6, recover.tries());
        EscLink.Visit v = EscProbe.visit("spin", "4,3,2,1", "3000", "1", "100", "3");
        assertArrayEquals(new int[]{3, 2, 1, 0}, v.order());
        assertEquals(3000, v.firstWaitMs());
        assertEquals(1, v.tries());
        assertEquals(100, v.pauseMs());
        assertEquals(3, v.passes());
        assertEquals(-1, EscProbe.visit("read", "", "2500", "", "", "").pauseMs(), "the link's own pause");
        assertThrows(IllegalArgumentException.class, () -> EscProbe.visit("read", "", "soon", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> EscProbe.visit("read", "", "", "0", "", ""));
    }

    @Test
    void theProbeShowsWhatAnAm32sHeaderSays() {
        FakeEscBoard board = new FakeEscBoard();
        FakeEscBoard.Esc e = board.am32(2, 17);
        e.memory[0x7C00 + 46] = 4;
        e.memory[0x7C00 + 36] = 1;
        assertEquals("bootloader 13, settings layout 2, input EDT arming (4), low-voltage cut-off on, direction normal, 3D off, "
                + "sine start off, telemetry interval 0", EscProbe.am32Details(e.bytes(0x7C00, EscFirmware.AM32_SETTINGS)));
        assertEquals("", EscProbe.am32Details(new byte[10]));
        assertEquals("", EscProbe.am32Details(null));
    }
}
