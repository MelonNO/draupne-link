package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fleet's timing runs on the monotonic clock (MonoClock): a wall-clock step (NTP, a time zone, the pilot setting
 * the PC's time) moved FleetOps' dispatch and order resends, GroupLink's ticks and heard times, and the ArduPilot
 * setup tools' timeouts. Only real dates stay on the wall clock (a log's file name, a file's age).
 */
class FleetClockTest {
    static final String[] FILES = {
            "gui/FleetOps.java", "air/GroupLink.java", "route/RouteSender.java", "setup/ardu/MavSession.java",
            "setup/ardu/ArduBenchCheck.java", "setup/ardu/ArduDfuFlow.java", "setup/ardu/ArduFirmwareFlow.java",
            "setup/ardu/ArduUsb.java", "setup/ardu/ArduLogs.java"};

    @Test
    void fleetTimingIsMonotonic() throws Exception {
        Path root = Path.of("").toAbsolutePath(); // the tests run in build/test-work
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        root = root.resolve("src/main/java/de/droiddrone/pc");
        List<String> wall = new ArrayList<>();
        for (String f : FILES) {
            List<String> lines = Files.readAllLines(root.resolve(f));
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (!l.contains("System.currentTimeMillis()")) continue;
                if (l.contains("timeUtcS()")) continue; // ArduLogs: a log's date when the FC has none
                wall.add(f + ":" + (i + 1) + ": " + l.trim());
            }
        }
        assertEquals(List.of(), wall);
    }
}
