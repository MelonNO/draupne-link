package de.droiddrone.pc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PcConfigTest {
    @TempDir
    Path dir;

    /**
     * 1.0.71 (the test bench's F-009): "pcName=Bjørn" saved by Notepad as ANSI stopped the app starting, without a
     * window or a word - one byte that is not UTF-8 made the load throw.
     */
    @Test
    void aSettingsFileSavedAsAnsiStillLoadsAndIsUtf8AfterTheNextSave(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("draupne-link.properties");
        Files.write(file, "port=6286\r\npcName=Bjørn\r\nkey=DD\r\n".getBytes(StandardCharsets.ISO_8859_1)); // as Notepad's ANSI writes it
        PcConfig config = assertDoesNotThrow(() -> PcConfig.load(file), "the settings load (the app starts)");
        assertEquals("DD", config.getKey(), "the lines that are fine are read");
        assertEquals("Bjørn", config.getString("pcName"), "and the name as the pilot wrote it");
        config.save(file, Map.of("rcRate", "25"));
        String saved = Files.readString(file, StandardCharsets.UTF_8); // throws if it were not UTF-8 now
        assertTrue(saved.contains("pcName=Bj"), saved);
        assertEquals("Bjørn", PcConfig.load(file).getString("pcName"));
        assertEquals(saved.lines().count(), PcConfig.readLines(file).size(), "no line gained or lost");
    }

    /** A byte no code page explains (a crash's rubbish) still loads: that line may be odd, the others are read. */
    /**
     * A save that fails - here something stands where the file is put together - and the save after it. Until
     * 2026-11-20 the first one's change was lost for good: in the app until it closed, never in the file.
     */
    @Test
    void whatASaveCouldNotWriteGoesIntoTheFileWithTheNextSave(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("draupne-link.properties");
        Files.writeString(file, "key=DD\nport=6286\naxis0.center=0.00\n");
        PcConfig config = PcConfig.load(file);
        Path inTheWay = Files.createDirectory(dir.resolve("draupne-link.properties.part"));
        Files.writeString(inTheWay.resolve("x"), "x");
        assertThrows(java.io.IOException.class, () -> config.write(file, Map.of("axis0.center", "0.12")));
        assertThrows(java.io.IOException.class, () -> config.write(file, Map.of("axis1.center", "0.34")), "and a second one, the first still waiting");
        assertEquals("0.00", PcConfig.load(file).getString("axis0.center"), "the file is as it was");
        Files.delete(inTheWay.resolve("x"));
        Files.delete(inTheWay);

        config.write(file, Map.of("rcRate", "25"));
        PcConfig read = PcConfig.load(file);
        assertEquals("25", read.getString("rcRate"));
        assertEquals("0.12", read.getString("axis0.center"), "the change that could not be written is in the file now");
        assertEquals("0.34", read.getString("axis1.center"));
        assertEquals("DD", read.getKey());
        // and it is not written again and again: a later value of the same key stays
        config.write(file, Map.of("axis0.center", "0.50"));
        config.write(file, Map.of("rcRate", "30"));
        assertEquals("0.50", PcConfig.load(file).getString("axis0.center"));
    }

    @Test
    void rubbishBytesAndAUtf8MarkDoNotStopTheLoad(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("draupne-link.properties");
        Files.write(file, new byte[]{'k', 'e', 'y', '=', 'D', 'D', '\n', (byte) 0x81, (byte) 0xFF, 0, '\n', 'p', 'o', 'r', 't', '=', '7', '\n'});
        PcConfig config = PcConfig.load(file);
        assertEquals("DD", config.getKey());
        assertEquals("7", config.getString("port"));
        // Notepad's "UTF-8 with BOM": the mark is not part of the first key
        Files.write(file, ("﻿key=EE\r\npcName=Bjørn\r\n").getBytes(StandardCharsets.UTF_8));
        assertEquals("EE", PcConfig.load(file).getKey());
        assertEquals("Bjørn", PcConfig.load(file).getString("pcName"));
    }

    /**
     * The test bench's F-025: "cameraWidth=wide" in the settings file. PILOT UAV waited for the phone for ever and the
     * link's thread wrote the same error into the log without a pause. The numbers the link asks for at every turn
     * now fall back to the built-in ones, and say so once.
     */
    @Test
    void aNumberOfTheLinkThatIsNoNumberFallsBackToTheBuiltInOneAndSaysSoOnce(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("draupne-link.properties");
        Files.writeString(file, "cameraWidth=wide\r\ncameraHeight=720\r\ntelemetryRate=often\r\nserialBaudRate=115200baud\r\n"
                + "bitrateLimitMbps=4,5\r\nsharpWidth=\r\nsharpHeight=1080\r\nsharpBitrateLimitMbps=12\r\n", StandardCharsets.UTF_8);
        PcConfig config = PcConfig.load(file);
        PcConfig builtIn = PcConfig.defaults();
        for (int turn = 0; turn < 3; turn++) {
            assertEquals(builtIn.getCameraWidth(), config.getCameraWidth());
            assertEquals(720, config.getCameraHeight(), "a number that is one stays");
            assertEquals(builtIn.getTelemetryRate(), config.getTelemetryRate());
            assertEquals(builtIn.getSerialBaudRate(), config.getSerialBaudRate());
            assertEquals(builtIn.getBitrateLimitMbps(), config.getBitrateLimitMbps());
            assertEquals(builtIn.getSharpWidth(), config.getSharpWidth(), "an empty value too");
            assertEquals(12, config.getSharpBitrateLimitMbps());
        }
        List<String> notes = config.takeNotes();
        assertEquals(5, notes.size(), "one line a wrong value, however often it is asked for: " + notes);
        assertTrue(notes.contains("Settings file: cameraWidth=wide is not a number. Using " + builtIn.getCameraWidth() + "."), notes.toString());
        assertTrue(config.takeNotes().isEmpty(), "said once");
        assertTrue(builtIn.takeNotes().isEmpty(), "the built-in settings have nothing to say");
    }

    /**
     * Review 1.0.53: a key twice in the file (a hand edit, a copied block) - save changed the first line only, and the
     * next start read the last, so the change seemed never to save (an ARM switch, a channel mapping).
     */
    @Test
    void aKeyTwiceInTheFileIsChangedEverywhere() throws Exception {
        Path file = dir.resolve(PcConfig.FILE_NAME);
        Files.write(file, List.of("aux1=button0", "# a block copied in", "aux1=button1"), StandardCharsets.UTF_8);
        PcConfig config = PcConfig.load(file);
        assertEquals("button1", config.getString("aux1"), "the last line counts when read");
        config.save(file, Map.of("aux1", "axis5"));
        assertEquals("axis5", PcConfig.load(file).getString("aux1"), "saved: " + Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    @Test
    void saveStartsFromTemplateAndKeepsComments() throws Exception {
        Path file = dir.resolve(PcConfig.FILE_NAME);
        PcConfig config = PcConfig.load(file);
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("ip", "100.64.1.2");
        updates.put("axis2.min", "-0.9000");
        updates.put("axis2.center", "-0.9000");
        updates.put("axis2.max", "0.9000");
        config.save(file, updates);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("# --- Network")), "template comments kept");
        assertEquals(1, lines.stream().filter(l -> l.startsWith("ip=")).count(), "ip replaced in place, not duplicated");
        assertTrue(lines.contains("axis2.max=0.9000"));

        PcConfig reloaded = PcConfig.load(file);
        assertEquals("100.64.1.2", reloaded.getIp());
        assertEquals(new AxisCalibration(-0.9f, -0.9f, 0.9f), reloaded.getAxisCalibrations().get(2));
        assertEquals("100.64.1.2", config.getIp(), "in-memory copy updated too");
    }

    @Test
    void emptyChannelMeansUnmapped() throws Exception {
        Path file = dir.resolve(PcConfig.FILE_NAME);
        PcConfig config = PcConfig.load(file);
        config.save(file, Map.of("aux3", ""));
        assertNull(PcConfig.load(file).getChannelSpecs()[6]);
    }

    @Test
    void upgradeMovesOldRcRateDefaultOnce() throws Exception {
        Path file = dir.resolve(PcConfig.FILE_NAME);
        Files.writeString(file, "# mine\nrcRate=100\ntelemetryRate=10\nip=100.64.0.20\n");
        PcConfig config = PcConfig.load(file);
        assertTrue(config.upgrade(file));
        assertEquals(250, config.getRcRate());
        assertEquals(20, config.getTelemetryRate());
        assertEquals(250, PcConfig.load(file).getRcRate(), "written to the file");
        assertTrue(Files.readString(file).startsWith("# mine"), "comments kept");

        config.save(file, Map.of("rcRate", "100")); // a deliberate choice afterwards is left alone
        PcConfig again = PcConfig.load(file);
        assertFalse(again.upgrade(file));
        assertEquals(100, again.getRcRate());
        assertEquals(250, PcConfig.defaults().getRcRate(), "new default");
    }

    /**
     * An older settings file on a full disk (the test bench's full disk, 2026-11-24): bringing it up to date at the
     * start is a write, and its failure ended the start - the app did not open. Now the app starts with the new
     * values and the next save that can write takes them into the file. The disk that refuses is played by a folder
     * standing where the new file is put together.
     */
    @Test
    void anOlderFileThatCannotBeWrittenNowDoesNotStopTheStart() throws Exception {
        Path file = dir.resolve(PcConfig.FILE_NAME);
        Files.writeString(file, "rcRate=100\nip=100.64.0.20\n");
        String before = Files.readString(file);
        Path inTheWay = Files.createDirectories(file.resolveSibling(file.getFileName() + ".part"));
        PcConfig config = PcConfig.load(file);
        assertTrue(assertDoesNotThrow(() -> config.upgrade(file), "the start goes on"), "brought up to date");
        assertEquals(250, config.getRcRate(), "the app runs with the new values");
        assertEquals(Integer.toString(PcConfig.SETTINGS_VERSION), config.getString("settingsVersion"));
        assertEquals(before, Files.readString(file), "the file as it was: nothing half written");

        Files.delete(inTheWay); // room again
        config.save(file, Map.of("ip", "100.64.0.21"));
        PcConfig again = PcConfig.load(file);
        assertEquals(250, again.getRcRate(), "the next save took the update into the file");
        assertEquals("100.64.0.21", again.getIp());
        assertFalse(again.upgrade(file), "once only");
    }

    @Test
    void upgradeMovesTheOldTopBitrateTo4Once(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("p.properties");
        Files.writeString(file, "bitrateLimitMbps=6\nsettingsVersion=4\n");
        assertTrue(PcConfig.load(file).upgrade(file));
        assertEquals(4, PcConfig.load(file).getBitrateLimitMbps());
        Files.writeString(file, "bitrateLimitMbps=8\nsettingsVersion=4\n");
        PcConfig.load(file).upgrade(file);
        assertEquals(8, PcConfig.load(file).getBitrateLimitMbps(), "a limit chosen on purpose stays");
    }

    @Test
    void upgradeFromVersion2OnlyTouchesTelemetry() throws Exception {
        Path file = dir.resolve(PcConfig.FILE_NAME);
        Files.writeString(file, "rcRate=100\ntelemetryRate=10\nsettingsVersion=2\n");
        PcConfig config = PcConfig.load(file);
        assertTrue(config.upgrade(file));
        assertEquals(100, config.getRcRate(), "chosen after version 2: kept");
        assertEquals(20, config.getTelemetryRate());
        assertFalse(PcConfig.load(file).upgrade(file), "once only");
    }

    /** H5 (1.0.49): before, a saved path under C:\Users whose next folder started with u stopped the app starting. */
    @Test
    void aWindowsPathRoundTripsAndJavasOwnReaderAgrees() throws Exception {
        Path file = dir.resolve(PcConfig.FILE_NAME);
        PcConfig config = PcConfig.load(file);
        String path = "C:\\Users\\ulrik\\Videos\\DRAUPNE: test #1 = ok!";
        config.save(file, Map.of("recording.folder", path, "phone name", " lead space"));
        PcConfig again = PcConfig.load(file);
        assertEquals(path, again.getString("recording.folder"));
        java.util.Properties javas = new java.util.Properties();
        try (java.io.Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            javas.load(r); // threw "Malformed \\uxxxx encoding" before
        }
        assertEquals(path, javas.getProperty("recording.folder"), "java.util.Properties escaping");
        assertEquals(" lead space", javas.getProperty("phone name"));
    }

    @Test
    void aValueCantInjectALine() throws Exception {
        Path file = dir.resolve(PcConfig.FILE_NAME);
        PcConfig config = PcConfig.load(file);
        config.save(file, Map.of("name.MobileAlpha", "PHONE|1.2.3.4|x\nrcRate=1000\r\nip=6.6.6.6\u2028key=x|h|v|0"));
        PcConfig again = PcConfig.load(file);
        assertEquals(250, again.getRcRate(), "no rcRate line was made");
        assertEquals("", again.getIp(), "no ip line was made");
        assertEquals("PHONE|1.2.3.4|x\nrcRate=1000\r\nip=6.6.6.6\u2028key=x|h|v|0", again.getString("name.MobileAlpha"), "the value itself kept");
        assertEquals(1, Files.readAllLines(file).stream().filter(l -> l.startsWith("name.MobileAlpha")).count());
    }

    @Test
    void anOldRawSettingsFileStillReadsAndIsEscapedAtTheNextSave() throws Exception {
        Path file = dir.resolve(PcConfig.FILE_NAME);
        // as 1.0.48 wrote them: values as they came
        Files.writeString(file, "# mine\nrecording.folder=C:\\Users\\ulrik\\Videos\\\nip=100.64.0.20\nkept.folder=D:\\temp\\new\n"
                + "stanag.target=127.0.0.1:15000\n");
        PcConfig config = PcConfig.load(file); // threw before
        assertEquals("C:\\Users\\ulrik\\Videos\\", config.getString("recording.folder"), "not C:Usersulrik...");
        assertEquals("100.64.0.20", config.getIp(), "the line after a path ending in a backslash is its own");
        assertEquals("D:\\temp\\new", config.getString("kept.folder"), "\\t and \\n in a raw path are not a tab and a line break");
        assertEquals("127.0.0.1:15000", config.getString("stanag.target"));
        config.save(file, Map.of("rcRate", "250"));
        PcConfig again = PcConfig.load(file);
        assertEquals("C:\\Users\\ulrik\\Videos\\", again.getString("recording.folder"));
        assertEquals("D:\\temp\\new", again.getString("kept.folder"));
        assertTrue(Files.readString(file).startsWith("# mine"), "comments kept");
        java.util.Properties javas = new java.util.Properties();
        try (java.io.Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            javas.load(r);
        }
        assertEquals("D:\\temp\\new", javas.getProperty("kept.folder"), "the old raw lines were escaped as they were read");
        assertEquals("100.64.0.20", javas.getProperty("ip"));
    }

    /**
     * 1.0.64: the window sets a change in memory at once and its writer thread puts it in the file a moment later, in
     * order. A write waiting behind a newer change must not put its older value back in memory.
     */
    @Test
    void aQueuedWriteNeverPutsAnOlderValueBackInMemory() throws Exception {
        Path file = dir.resolve("queued.properties");
        PcConfig config = PcConfig.defaults();
        config.apply(Map.of("phone.asleep", "one"));
        assertEquals("one", config.getString("phone.asleep"), "in memory at once, nothing written yet");
        assertFalse(Files.exists(file));
        config.apply(Map.of("phone.asleep", "two")); // a newer change before the first write ran
        config.write(file, Map.of("phone.asleep", "one"));
        assertEquals("two", config.getString("phone.asleep"), "the older write left memory alone");
        assertEquals("one", PcConfig.load(file).getString("phone.asleep"));
        config.write(file, Map.of("phone.asleep", "two"));
        assertEquals("two", PcConfig.load(file).getString("phone.asleep"), "the file has the newest once its write ran");
        assertEquals("two", config.getString("phone.asleep"));
        // save() is both, as before
        config.save(file, Map.of("rcRate", "123"));
        assertEquals("123", config.getString("rcRate"));
        assertEquals("123", PcConfig.load(file).getString("rcRate"));
    }

    @Test
    void incompleteCalibrationIsIgnored() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set("axis5.min", "-1");
        assertTrue(config.getAxisCalibrations().isEmpty());
    }
}
