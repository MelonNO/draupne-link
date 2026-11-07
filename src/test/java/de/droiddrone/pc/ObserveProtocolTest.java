package de.droiddrone.pc;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.Observe;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** OBSERVATION's packets, record and the phone's observer table (Observe, compiled into both apps; the phone runs it). */
class ObserveProtocolTest {
    @Test
    void requestRoundTrip() {
        byte[] p = Observe.encodeRequest("key1", 640, 360, 15, 1000, Observe.FLAG_AUDIO | Observe.FLAG_VIDEO_CONFIG, 2);
        assertEquals(Observe.OBSERVE, p[0]);
        assertEquals("key1", Observe.keyOf(p, p.length));
        Observe.Request r = Observe.decodeRequest(p, p.length);
        assertNotNull(r);
        assertEquals("key1", r.key);
        assertEquals(640, r.width);
        assertEquals(360, r.height);
        assertEquals(15, r.fps);
        assertEquals(1000, r.maxKbps);
        assertTrue(r.audio());
        assertTrue(r.videoConfig());
        assertFalse(r.audioConfig());
        assertEquals(2, r.detection);
        // the layout as PROTOCOL.md has it: [0x50][key UTF][w u16][h u16][fps u8][kbps u16][flags u8][detection u8]
        assertEquals(1 + 2 + 4 + 2 + 2 + 1 + 2 + 1 + 1, p.length);
    }

    @Test
    void oddPacketsAreReadSafely() {
        byte[] p = Observe.encodeRequest("k", 1280, 720, 30, 4000, 0, 0);
        assertNull(Observe.decodeRequest(p, p.length - 1), "cut short");
        assertNull(Observe.decodeRequest(new byte[]{0x51, 0, 1}, 3), "another packet");
        assertNull(Observe.keyOf(new byte[]{Observe.OBSERVE, 0, 9, 'a'}, 4), "the key runs past the end");
        byte[] odd = Observe.encodeRequest("k", 1, 99_999, 200, 1, 0, 9);
        Observe.Request clamped = Observe.decodeRequest(odd, odd.length);
        assertEquals(16, clamped.width, "clamped");
        assertEquals(4096, clamped.height);
        assertEquals(60, clamped.fps);
        assertEquals(100, clamped.maxKbps);
        assertEquals(3, clamped.detection);
    }

    @Test
    void stopAndStateRoundTrip() {
        byte[] stop = Observe.encodeStop("abc");
        assertEquals(Observe.STOP, stop[0]);
        assertEquals("abc", Observe.keyOf(stop, stop.length));
        assertNull(Observe.decodeRequest(stop, stop.length), "a stop is no request");
        byte[] st = Observe.encodeState(Observe.ENDED_PILOT, 1280, 720, 30, 2);
        Observe.State s = Observe.decodeState(st, st.length);
        assertEquals(Observe.ENDED_PILOT, s.state);
        assertEquals(1280, s.width);
        assertEquals(720, s.height);
        assertEquals(30, s.fps);
        assertEquals(2, s.observers);
        assertNull(Observe.decodeState(st, 7));
    }

    @Test
    void recordRoundTripThroughTheTelemetry() throws Exception {
        Observe.Position p = new Observe.Position(Observe.POS_VALID | Observe.FC_COMPASS, 59.9123456, 10.7543210, 123, 4,
                271.4, 12.3, 3, 8, 71.25);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Observe.writeRecord(new DataOutputStream(bytes), p);
        byte[] d = bytes.toByteArray();
        assertEquals(2 + Observe.RECORD_BYTES, d.length);
        FcTelemetry t = new FcTelemetry();
        t.parse(new DataReader(d, true), null);
        FcTelemetry.Observed o = t.getObserved();
        assertNotNull(o);
        Observe.Position q = o.position();
        assertTrue(q.hasPosition());
        assertTrue(q.fcCompass());
        assertEquals(59.9123456, q.lat, 1e-7);
        assertEquals(10.7543210, q.lon, 1e-7);
        assertEquals(123, q.altM);
        assertEquals(4, q.ageS);
        assertEquals(271.4, q.fcHeadingDeg, 0.05);
        assertEquals(12.3, q.phoneAzimuthDeg, 0.05);
        assertEquals(3, q.phoneAccuracy);
        assertEquals(8, q.phoneErrorDeg);
        assertEquals(71.25, q.hfovDeg, 0.01);
    }

    @Test
    void recordWithoutHeadings() throws Exception {
        Observe.Position p = new Observe.Position(0, 0, 0, 0, 0, Double.NaN, Double.NaN, -1, -1, 0);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Observe.writeRecord(new DataOutputStream(bytes), p);
        DataReader r = new DataReader(bytes.toByteArray(), true);
        assertEquals(Observe.RECORD, r.readShort());
        Observe.Position q = Observe.readRecord(r);
        assertFalse(q.hasPosition());
        assertTrue(Double.isNaN(q.fcHeadingDeg));
        assertTrue(Double.isNaN(q.phoneAzimuthDeg));
        assertEquals(-1, q.phoneAccuracy);
        assertEquals(-1, q.phoneErrorDeg);
    }

    @Test
    void theTableTakesThreeExpiresAndAsksForTheBiggest() {
        Observe.Observers<String> t = new Observe.Observers<>(Observe.MAX_OBSERVERS);
        Observe.Request grid = new Observe.Request("k", 640, 360, 15, 1000, Observe.FLAG_AUDIO, 0);
        Observe.Request sharp = new Observe.Request("k", 1280, 720, 30, 4000, 0, 2);
        assertNull(t.wanted(), "nobody: no picture");
        assertEquals(Observe.STREAMING, t.onRequest("a", grid, 0));
        assertEquals(Observe.STREAMING, t.onRequest("b", sharp, 0));
        assertEquals(Observe.STREAMING, t.onRequest("c", grid, 0));
        assertEquals(Observe.REFUSED_FULL, t.onRequest("d", grid, 0), "a fourth PC waits");
        assertEquals(Observe.STREAMING, t.onRequest("a", grid, 10), "one in the table is always answered");
        Observe.Request w = t.wanted();
        assertEquals(1280, w.width);
        assertEquals(30, w.fps);
        assertEquals(4000, w.maxKbps);
        assertTrue(w.audio(), "anyone's sound wish counts");
        assertEquals(2, w.detection);
        List<String> gone = t.expire(Observe.OBSERVER_EXPIRES_MS + 5);
        assertEquals(List.of("b", "c"), gone, "silent for 3 s: out");
        assertEquals(640, t.wanted().width);
        assertTrue(t.remove("a"));
        assertTrue(t.isEmpty());
    }

    @Test
    void stepsFollowTheEncoderTable() {
        assertEquals(3, Observe.stepFor(Observe.GRID_KBPS), "1 Mbit/s");
        assertEquals(2, Observe.stepFor(800), "0.5 Mbit/s");
        assertEquals(0, Observe.stepFor(100), "the floor");
        assertEquals(5, Observe.stepFor(4000));
    }

    @Test
    void theCameraLooksOutOfTheBackOfThePhone() {
        // a phone upright, screen to the south: the back camera looks north. Rows: east, north, up of device x, y, z
        float[] facingNorth = {1, 0, 0, 0, 0, -1, 0, 1, 0};
        assertEquals(0, Observe.cameraAzimuthDeg(facingNorth, false), 0.01);
        assertEquals(180, Observe.cameraAzimuthDeg(facingNorth, true), 0.01, "the front camera: the other way");
        // turned to look east: device z points west
        float[] facingEast = {0, 0, -1, -1, 0, 0, 0, 1, 0};
        assertEquals(90, Observe.cameraAzimuthDeg(facingEast, false), 0.01);
        // lying flat, top edge to the west: the camera sees the ground - the top edge's way instead
        float[] flatTopWest = {0, -1, 0, 1, 0, 0, 0, 0, 1};
        assertEquals(270, Observe.cameraAzimuthDeg(flatTopWest, false), 0.01);
        assertTrue(Double.isNaN(Observe.cameraAzimuthDeg(new float[9], false)));
    }
}
