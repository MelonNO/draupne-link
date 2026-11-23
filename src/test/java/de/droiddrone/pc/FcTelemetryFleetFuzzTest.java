package de.droiddrone.pc;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcCommand;
import de.droiddrone.common.MissionBlock;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The PC's reading of the phone's telemetry for an ArduPilot drone under garbage: random records after the fleet's
 * 0x40E0 / 0x40E1 and the MAVLink records, cut short and lying lengths - never throws, never hangs; the two fleet
 * records read exactly at their edges.
 */
class FcTelemetryFleetFuzzTest {
    static byte[] record(short code, byte[] payload) {
        return ByteBuffer.allocate(2 + payload.length).putShort(code).put(payload).array();
    }

    @Test
    void randomTelemetryNeverThrowsOrHangs() {
        Random r = new Random(11);
        short[] codes = {FcCommand.ACK_RECORD, MissionBlock.STATUS_RECORD, 0x4008, 0x4009, 0x400A, 0x400B, 0x400C, 0x400D,
                0x400E, 0x400F, 0x4010, 0x4011, 0x4012, 0x4013, 0x4014, 0x40F0, 0x40F1, 0x40F3, 0x40F4, 0x40FA};
        long t0 = System.nanoTime();
        for (int i = 0; i < 20_000; i++) {
            FcTelemetry t = new FcTelemetry();
            byte[] d = new byte[2 + r.nextInt(120)];
            r.nextBytes(d);
            short c = codes[r.nextInt(codes.length)];
            d[0] = (byte) (c >> 8);
            d[1] = (byte) c;
            t.parse(new DataReader(d, true), FcTelemetryMavlinkTest.ARDUCOPTER);
            t.parse(new DataReader(d, true), null);
        }
        assertTrue(System.nanoTime() - t0 < 30_000_000_000L, "20 000 packets in under 30 s");
    }

    @Test
    void theFleetRecordsReadAtTheirEdges() {
        FcTelemetry t = new FcTelemetry();
        byte[] a = record(FcCommand.ACK_RECORD, FcCommand.ack(65535, 255));
        byte[] s = record(MissionBlock.STATUS_RECORD, MissionBlock.status(65535, 4, 100, "x".repeat(200)));
        byte[] both = ByteBuffer.allocate(a.length + s.length).put(a).put(s).array();
        t.parse(new DataReader(both, true), FcTelemetryMavlinkTest.ARDUCOPTER);
        assertEquals(65535, t.getLastCommandAck().command());
        assertEquals(255, t.getLastCommandAck().result());
        FcTelemetry.RouteUpload u = t.getRouteUpload(65535);
        assertNotNull(u);
        assertEquals(4, u.state());
        assertEquals(100, u.percent());
        assertEquals(120, u.reason().length());
        assertTrue(u.failed());
        // a status record cut inside its reason: no status taken from it
        FcTelemetry c = new FcTelemetry();
        byte[] cut = record(MissionBlock.STATUS_RECORD, MissionBlock.status(7, 3, 100, "done"));
        c.parse(new DataReader(java.util.Arrays.copyOf(cut, cut.length - 2), true), FcTelemetryMavlinkTest.ARDUCOPTER);
        assertNull(c.getRouteUpload(7), "a record cut inside its reason is no status (its reason read as zeros before)");
    }
}
