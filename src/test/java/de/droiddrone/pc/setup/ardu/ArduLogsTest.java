package de.droiddrone.pc.setup.ardu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet AP-10: the drone's own flight log - listed, downloaded whole though parts get lost, kept, found by flight. */
class ArduLogsTest {
    private FakeArduPilot fake;
    private MavSession fc;

    @AfterEach
    void close() {
        if (fc != null) fc.close();
    }

    @Test
    void aLogComesWholeAndIsFoundByItsFlight(@TempDir Path dir) throws Exception {
        fake = new FakeArduPilot();
        byte[] log = new byte[50_000];
        new Random(2).nextBytes(log);
        fake.logs.add(new byte[1000]);
        fake.logs.add(log);
        fake.dropLogEvery = 17;
        fc = new MavSession(fake);
        assertTrue(fc.awaitHeartbeat(3000));
        List<ArduLogs.Entry> l = ArduLogs.list(fc, 3000);
        assertEquals(2, l.size());
        ArduLogs.Entry e = l.get(1);
        assertEquals(50_000, e.size());
        byte[] got = ArduLogs.download(fc, e, null, 60_000);
        assertArrayEquals(log, got, "whole, the lost parts asked for again");
        Path f = ArduLogs.save(dir, "MobileAlpha", e, got);
        assertEquals(50_000, Files.size(f));
        long t = e.timeUtcS() * 1000;
        assertEquals(List.of(f), ArduLogs.forFlight(dir, "MobileAlpha", t - 60_000, t + 60_000));
        assertEquals(List.of(), ArduLogs.forFlight(dir, "MobileBravo", t - 60_000, t + 60_000), "another drone's");
        assertEquals(List.of(), ArduLogs.forFlight(dir, "MobileAlpha", t + 3_600_000, t + 7_200_000), "another time");
    }

    @Test
    void noLogsIsAnEmptyList() throws Exception {
        fake = new FakeArduPilot();
        fc = new MavSession(fake);
        assertTrue(fc.awaitHeartbeat(3000));
        assertEquals(List.of(), ArduLogs.list(fc, 3000));
    }

    @Test
    void theTunnelCarriesLogsAndTheyAreRequests() {
        assertTrue(de.droiddrone.common.MavTunnel.isLogRequest(119));
        assertFalse(de.droiddrone.common.MavTunnel.isLogRequest(120));
        assertTrue(de.droiddrone.common.MavTunnel.toPc(120), "LOG_DATA back");
        byte[] f = new com.MAVLink.common.msg_log_request_data(0, 90, 1, (short) 1, (short) 1, 255, 190, true).pack().encodePacket();
        assertTrue(de.droiddrone.common.MavTunnel.toFc(f, 0, f.length));
    }
}
