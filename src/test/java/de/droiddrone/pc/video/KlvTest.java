package de.droiddrone.pc.video;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KlvTest {
    private static Klv.Frame frame(double lat, double pitch, double[][] corners) {
        return new Klv.Frame(1_700_000_000_123_456L, "TEST", "DRAUPNE", "EO FPV", 90, pitch, -12.5, 14,
                lat, 10.75, 512.3, 70, 42.1, 0, 25, 0, 230.4, 59.912, 10.751, 180.2, corners, "NOR");
    }

    private static final double[][] CORNERS = {{59.915, 10.745}, {59.915, 10.757}, {59.909, 10.757}, {59.909, 10.745}};

    @Test
    void valuesComeBackFromTheirFixedPointMappings() {
        Map<Integer, byte[]> t = Klv.decode(Klv.encode(frame(59.9123456, 4.2, CORNERS)));
        assertNotNull(t, "key, length and checksum are right");
        assertEquals(1_700_000_000_123_456L, Klv.be(t.get(Klv.TAG_TIME), false));
        assertEquals("TEST", new String(t.get(Klv.TAG_MISSION), StandardCharsets.US_ASCII));
        assertEquals(90, Klv.decodeUnsigned(t.get(Klv.TAG_HEADING), 360), 0.01);
        assertEquals(4.2, Klv.decodeSigned(t.get(Klv.TAG_PITCH), 20), 0.001);
        assertEquals(4.2, Klv.decodeSigned(t.get(Klv.TAG_PITCH_FULL), 90), 1e-6);
        assertEquals(-12.5, Klv.decodeSigned(t.get(Klv.TAG_ROLL), 50), 0.002);
        assertEquals(59.9123456, Klv.decodeSigned(t.get(Klv.TAG_SENSOR_LAT), 90), 1e-7);
        assertEquals(10.75, Klv.decodeSigned(t.get(Klv.TAG_SENSOR_LON), 180), 1e-7);
        assertEquals(512.3, Klv.decodeAltitude(t.get(Klv.TAG_SENSOR_ALT)), 0.31);
        assertEquals(70, Klv.decodeUnsigned(t.get(Klv.TAG_HFOV), 180), 0.003);
        assertEquals(25, Klv.decodeSigned(t.get(Klv.TAG_REL_ELEVATION), 180), 1e-6);
        assertEquals(230.4, Klv.decodeUnsigned(t.get(Klv.TAG_SLANT_RANGE), 5_000_000), 0.01);
        assertEquals(59.912, Klv.decodeSigned(t.get(Klv.TAG_CENTRE_LAT), 90), 1e-7);
        assertEquals(180.2, Klv.decodeAltitude(t.get(Klv.TAG_CENTRE_ELEV)), 0.31);
        for (int i = 0; i < 4; i++) {
            assertEquals(CORNERS[i][0], Klv.decodeSigned(t.get(Klv.TAG_CORNER + 2 * i), 90), 1e-7);
            assertEquals(CORNERS[i][1], Klv.decodeSigned(t.get(Klv.TAG_CORNER + 2 * i + 1), 180), 1e-7);
            assertEquals(CORNERS[i][0] - 59.912, Klv.decodeSigned(t.get(Klv.TAG_OFFSET_CORNER + 2 * i), 0.075), 3e-6);
        }
        assertEquals(Klv.UAS_LS_VERSION, t.get(Klv.TAG_VERSION)[0]);
        assertEquals(Klv.TAG_TIME, t.keySet().iterator().next(), "the time stamp first");
        Integer lastTag = null;
        for (Integer k : t.keySet()) lastTag = k;
        assertEquals(Klv.TAG_CHECKSUM, lastTag, "the checksum last");
    }

    @Test
    void outOfRangeAnglesUseTheErrorValueAndTheFullRangeTag() {
        Map<Integer, byte[]> t = Klv.decode(Klv.encode(frame(59.9, -35, null)));
        assertArrayEquals(new byte[]{(byte) 0x80, 0}, t.get(Klv.TAG_PITCH), "pitch past +-20: 0x8000");
        assertEquals(-35, Klv.decodeSigned(t.get(Klv.TAG_PITCH_FULL), 90), 1e-6);
        assertFalse(t.containsKey(Klv.TAG_CORNER), "no corners known, none sent");
    }

    @Test
    void unknownValuesAreLeftOut() {
        double nan = Double.NaN;
        Klv.Frame f = new Klv.Frame(5, null, "DRAUPNE", "EO FPV", nan, nan, nan, nan, nan, nan, nan, 70, 42, 0, 0, 0,
                nan, nan, nan, nan, null, "NOR");
        Map<Integer, byte[]> t = Klv.decode(Klv.encode(f));
        assertNotNull(t);
        for (int tag : new int[]{Klv.TAG_HEADING, Klv.TAG_PITCH, Klv.TAG_SENSOR_LAT, Klv.TAG_SENSOR_ALT, Klv.TAG_CENTRE_LAT, Klv.TAG_SLANT_RANGE, Klv.TAG_MISSION}) {
            assertFalse(t.containsKey(tag), "tag " + tag);
        }
        assertTrue(t.containsKey(Klv.TAG_SECURITY));
    }

    @Test
    void theChecksumIsMisbsWordSum() {
        byte[] p = Klv.encode(frame(59.9, 1, CORNERS));
        // jMISB's way, independently: big-endian 16-bit words over all but the checksum's two value bytes
        int sum = 0;
        for (int i = 0; i + 1 < p.length - 2; i += 2) sum += ((p[i] & 0xff) << 8) | (p[i + 1] & 0xff);
        if ((p.length - 2) % 2 == 1) sum += (p[p.length - 3] & 0xff) << 8;
        assertEquals(sum & 0xffff, ((p[p.length - 2] & 0xff) << 8) | (p[p.length - 1] & 0xff));
        p[30] ^= 1;
        assertNull(Klv.decode(p), "a changed byte fails the checksum");
    }

    @Test
    void theLengthIsBerLongFormPast127() {
        byte[] p = Klv.encode(frame(59.9, 1, CORNERS));
        assertTrue(p.length > 255, "corners, security set and all: " + p.length);
        assertEquals((byte) 0x82, p[16], "two length bytes follow");
        assertEquals(p.length - 19, ((p[17] & 0xff) << 8) | (p[18] & 0xff));
        byte[] small = Klv.encode(frame(59.9, 1, null));
        if (small.length - 18 < 256) {
            assertEquals((byte) 0x81, small[16], "one length byte");
            assertEquals(small.length - 18, small[17] & 0xff);
        }
        assertArrayEquals(new byte[]{0x05}, Klv.berLength(5));
        assertArrayEquals(new byte[]{(byte) 0x82, 0x01, 0x2c}, Klv.berLength(300));
    }

    @Test
    void theSecuritySetSaysUnclassifiedAndTheCountry() {
        Map<Integer, byte[]> t = Klv.decode(Klv.encode(frame(59.9, 1, null)));
        byte[] sec = t.get(Klv.TAG_SECURITY);
        Map<Integer, byte[]> s = Klv.items(sec, 0, sec.length);
        assertArrayEquals(new byte[]{Klv.UNCLASSIFIED}, s.get(1));
        assertArrayEquals(new byte[]{Klv.ISO3166_THREE_LETTER}, s.get(2));
        assertEquals("//NOR", new String(s.get(3), StandardCharsets.US_ASCII));
        assertEquals("NOR", new String(s.get(13), StandardCharsets.UTF_16BE));
        assertEquals(Klv.SECURITY_VERSION, Klv.be(s.get(22), false));
        assertEquals("//XXX", new String(Klv.items(Klv.security("??"), 0, Klv.security("??").length).get(3), StandardCharsets.US_ASCII),
                "no country set: XXX, never a wrong one");
    }
}
