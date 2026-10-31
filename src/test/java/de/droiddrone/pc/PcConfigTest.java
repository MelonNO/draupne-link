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
        updates.put("linkMode", "host");
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
        assertEquals(LinkMode.HOST_RELAY, reloaded.getLinkMode());
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
    void upgradeMovesTheOldAutoFrameRateToASteady60Once(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("p.properties");
        Files.writeString(file, "cameraFpsMin=30\ncameraFpsMax=60\nsettingsVersion=3\n");
        PcConfig config = PcConfig.load(file);
        assertTrue(config.upgrade(file));
        PcConfig after = PcConfig.load(file);
        assertEquals(60, after.getCameraFpsMin());
        assertEquals(60, after.getCameraFpsMax());
        // a steady 30 chosen on purpose stays
        Files.writeString(file, "cameraFpsMin=30\ncameraFpsMax=30\nsettingsVersion=3\n");
        PcConfig chosen = PcConfig.load(file);
        chosen.upgrade(file);
        assertEquals(30, PcConfig.load(file).getCameraFpsMin());
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

    @Test
    void incompleteCalibrationIsIgnored() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set("axis5.min", "-1");
        assertTrue(config.getAxisCalibrations().isEmpty());
    }
}
