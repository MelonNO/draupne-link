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
    void incompleteCalibrationIsIgnored() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set("axis5.min", "-1");
        assertTrue(config.getAxisCalibrations().isEmpty());
    }
}
