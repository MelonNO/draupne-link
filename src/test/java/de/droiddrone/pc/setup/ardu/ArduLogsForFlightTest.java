package de.droiddrone.pc.setup.ardu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet AP-10 / PREVIOUS FLIGHTS' FC log: a log taken over USB on the bench ("USB COM7 ...", the phone's name not known
 * there) is found for the flight of its time, as the dialog promises; one of another time, or another drone's, is not.
 */
class ArduLogsForFlightTest {
    private static long at(String t) {
        return LocalDateTime.parse(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Test
    void aUsbLogIsFoundForTheFlightOfItsTime(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("USB COM7 2026-11-23 10-05-00.bin"), new byte[10]);
        Files.write(dir.resolve("MobileAlpha 2026-11-23 10-01-00.bin"), new byte[10]);
        Files.write(dir.resolve("MobileBravo 2026-11-23 10-02-00.bin"), new byte[10]);
        Files.write(dir.resolve("USB COM7 2026-11-23 14-00-00.bin"), new byte[10]);
        List<Path> logs = ArduLogs.forFlight(dir, "MobileAlpha", at("2026-11-23T10:00:00"), at("2026-11-23T10:20:00"));
        assertEquals(List.of("USB COM7 2026-11-23 10-05-00.bin", "MobileAlpha 2026-11-23 10-01-00.bin"),
                logs.stream().map(p -> p.getFileName().toString()).toList(), "its own and the bench's of its time, newest first");
        assertTrue(ArduLogs.forFlight(dir, null, at("2026-11-23T10:00:00"), 0).isEmpty(), "an old record without a drone: none");
        assertNull(ArduLogs.usbPrefix("USB.bin"));
    }
}
