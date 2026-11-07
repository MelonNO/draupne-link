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

    @Test
    void incompleteCalibrationIsIgnored() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set("axis5.min", "-1");
        assertTrue(config.getAxisCalibrations().isEmpty());
    }
}
