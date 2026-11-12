package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.video.Klv;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.65: the metadata of a drone this PC does not fly - its place from the network, and (the pilot: "the sectors from
 * when a drone is in overwatch to get transferred with the data as well") an overwatching drone's sector as the map
 * draws it.
 */
class StanagKlvTest {
    /** As TsReader does it: tags to their bytes; null when the checksum is wrong. */
    private static Map<Integer, byte[]> items(Klv.Frame f) {
        Map<Integer, byte[]> m = Klv.decode(Klv.encode(f));
        assertNotNull(m, "a whole packet");
        return m;
    }

    private static double signed(byte[] v, double range) {
        long raw = 0;
        for (byte b : v) raw = (raw << 8) | (b & 0xff);
        int bits = v.length * 8;
        if (bits < 64 && (raw & (1L << (bits - 1))) != 0) raw -= 1L << bits;
        return raw * range / ((1L << (bits - 1)) - 1);
    }

    // ST 0601: sensor position 13 / 14, heading 5, horizontal field of view 16, the four corners 82 - 89
    private static final int HEADING = 5, DESIGNATION = 10, LAT = 13, LON = 14, HFOV = 16, CORNER = 82;

    @Test
    void aDroneAtAKnownPlaceSaysItUnderItsName() {
        Map<Integer, byte[]> m = items(StanagKlv.frame(1_000, "MobileAlpha", new StanagKlv.Place(59.95, 10.7, Double.NaN, 45, null), "OP", "NOR"));
        assertEquals("MobileAlpha", new String(m.get(DESIGNATION), StandardCharsets.US_ASCII));
        assertEquals(59.95, signed(m.get(LAT), 90), 1e-6);
        assertEquals(10.7, signed(m.get(LON), 180), 1e-6);
        assertNotNull(m.get(HEADING));
        assertNull(m.get(HFOV), "no camera angles without a sector");
        assertNull(m.get(CORNER));
    }

    @Test
    void aDroneNobodyKnowsThePlaceOfStillSaysWhoItIs() {
        Map<Integer, byte[]> m = items(StanagKlv.frame(1_000, "MobileAlpha", StanagKlv.Place.UNKNOWN, "", "NOR"));
        assertEquals("MobileAlpha", new String(m.get(DESIGNATION), StandardCharsets.US_ASCII));
        assertNull(m.get(LAT));
        assertNull(m.get(HEADING));
        assertNotNull(items(StanagKlv.frame(1_000, "MobileAlpha", null, null, null)), "nothing known at all: still a whole packet");
    }

    @Test
    void anOverwatchingDronesSectorGoesWithItsFourCorners() {
        // a drone on flat ground looking north-east, a phone's camera: what the map draws for it
        CameraFootprint.Pose pose = new CameraFootprint.Pose(59.95, 10.7, 100, 45, 0, 0, 0, 70, 42);
        CameraFootprint.View sector = CameraFootprint.sitting(pose, null);
        Map<Integer, byte[]> m = items(StanagKlv.frame(1_000, "MobileGolf", new StanagKlv.Place(59.95, 10.7, Double.NaN, 45, sector), "", "NOR"));
        assertEquals(59.95, signed(m.get(LAT), 90), 1e-6);
        assertNotNull(m.get(HFOV), "the camera's angle across");
        double[][] corners = new double[4][];
        for (int i = 0; i < 4; i++) {
            assertNotNull(m.get(CORNER + 2 * i), "corner " + i);
            corners[i] = new double[]{signed(m.get(CORNER + 2 * i), 90), signed(m.get(CORNER + 2 * i + 1), 180)};
        }
        // a level camera half a metre up: its upper corners see no ground - they end at the watch range, as on the map
        double[][] same = StanagKlv.corners(sector);
        for (int i = 0; i < 4; i++) {
            assertEquals(same[i][0], corners[i][0], 1e-6);
            assertEquals(same[i][1], corners[i][1], 1e-6);
        }
        double upperLeftM = metres(pose, corners[0]), upperRightM = metres(pose, corners[1]), lowerRightM = metres(pose, corners[2]);
        assertEquals(CameraFootprint.WATCH_RANGE_M, upperLeftM, 15, "the far corners at the watch range");
        assertEquals(CameraFootprint.WATCH_RANGE_M, upperRightM, 15);
        assertTrue(lowerRightM < 10, "the near corners at the drone's feet: " + lowerRightM + " m");
        // the sector opens to the north-east: its far side lies north and east of the drone
        assertTrue(corners[0][0] > pose.lat() && corners[1][1] > pose.lon());
        // left of the view is north of its right when looking north-east
        assertTrue(corners[0][0] > corners[1][0], "upper left is the northern far corner");
    }

    private static double metres(CameraFootprint.Pose from, double[] to) {
        double dn = (to[0] - from.lat()) * 111_320.0, de = (to[1] - from.lon()) * 111_320.0 * Math.cos(Math.toRadians(from.lat()));
        return Math.hypot(dn, de);
    }
}
