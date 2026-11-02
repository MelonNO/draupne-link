package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoordinatesTest {
    @Test
    void utmMatchesTheReferenceValue() {
        // GeographicLib's worked example: 33.3 N 44.4 E (Baghdad) -> 38N 444140.54 3684706.36
        Coordinates.Utm u = Coordinates.toUtm(33.3, 44.4);
        assertEquals(38, u.zone());
        assertTrue(u.north());
        assertEquals('S', u.band());
        assertEquals(444140.54, u.easting(), 0.05);
        assertEquals(3684706.36, u.northing(), 0.05);
    }

    @Test
    void mgrsSquareLettersAndDigits() {
        // the same place: 100 km square MB (GeographicLib: 38SMB4488 at 1 km)
        assertEquals("38S MB 44140 84706", Coordinates.mgrs(33.3, 44.4, 5));
        assertEquals("38S MB 44 84", Coordinates.mgrs(33.3, 44.4, 2));
        assertEquals("38S MB 44140 84706", Coordinates.format(Coordinates.Format.MGRS, 33.3, 44.4));
    }

    @Test
    void southernHemisphereUsesTheFalseNorthing() {
        Coordinates.Utm u = Coordinates.toUtm(-33.9, 18.4); // Cape Town: 34H
        assertEquals(34, u.zone());
        assertFalse(u.north());
        assertEquals('H', u.band());
        assertTrue(u.northing() > 6_000_000 && u.northing() < 6_300_000, "false northing of 10 000 km: " + u.northing());
    }

    @Test
    void norwaysZoneExceptions() {
        assertEquals(32, Coordinates.zone(60.39, 5.32), "Bergen: zone 32, not 31");
        assertEquals('V', Coordinates.band(60.39));
        assertEquals(31, Coordinates.zone(55.0, 5.32), "south of 56: the normal zone 31");
        assertEquals(33, Coordinates.zone(78.22, 15.63), "Longyearbyen: Svalbard's zone 33");
        assertEquals(32, Coordinates.zone(59.9139, 10.7522));
        assertEquals('X', Coordinates.band(78.22));
        assertTrue(Coordinates.mgrs(59.9139, 10.7522, 5).startsWith("32V "), Coordinates.mgrs(59.9139, 10.7522, 5));
    }

    @Test
    void utmGoesBackToDegrees() {
        for (double[] ll : new double[][]{{59.9139, 10.7522}, {33.3, 44.4}, {-33.9, 18.4}, {78.22, 15.63}, {60.39, 5.32}, {0.5, -179.9}}) {
            Coordinates.Utm u = Coordinates.toUtm(ll[0], ll[1]);
            double[] back = Coordinates.fromUtm(u.zone(), u.north(), u.easting(), u.northing());
            assertEquals(ll[0], back[0], 1e-7, "lat of " + ll[0] + "," + ll[1]);
            assertEquals(ll[1], back[1], 1e-7, "lon of " + ll[0] + "," + ll[1]);
        }
    }

    @Test
    void positionsTypedOrPastedAreRead() {
        double[] p = Coordinates.parse("59.9139, 10.7522");
        assertEquals(59.9139, p[0], 1e-9);
        assertEquals(10.7522, p[1], 1e-9);
        assertArrayEquals(p, Coordinates.parse("59.9139 10.7522"), 1e-9);
        assertArrayEquals(p, Coordinates.parse("  59.9139N 10.7522E "), 1e-9);
        assertEquals(-33.9, Coordinates.parse("33.9 S, 18.4 E")[0], 1e-9);
        assertEquals(-18.4, Coordinates.parse("33.9 N 18.4 W")[1], 1e-9);
        // what the map writes, read back
        double[] mgrs = Coordinates.parse(Coordinates.mgrs(59.9139, 10.7522, 5));
        assertEquals(59.9139, mgrs[0], 2e-5, "MGRS at 1 m");
        assertEquals(10.7522, mgrs[1], 2e-5);
        double[] squashed = Coordinates.parse("38SMB4414084706");
        assertEquals(33.3, squashed[0], 2e-5, "GeographicLib's example without spaces");
        assertEquals(44.4, squashed[1], 2e-5);
        double[] coarse = Coordinates.parse("38S MB 44 84");
        assertEquals(33.3, coarse[0], 0.02, "1 km square: its corner");
        double[] south = Coordinates.parse(Coordinates.mgrs(-33.9, 18.4, 5));
        assertEquals(-33.9, south[0], 2e-5, "Cape Town");
        assertEquals(18.4, south[1], 2e-5);
        double[] svalbard = Coordinates.parse(Coordinates.mgrs(78.22, 15.63, 5));
        assertEquals(78.22, svalbard[0], 2e-5);
        double[] utm = Coordinates.parse(Coordinates.format(Coordinates.Format.UTM, 59.9139, 10.7522));
        assertEquals(59.9139, utm[0], 2e-5, "UTM as written, with E and N");
        assertEquals(10.7522, utm[1], 2e-5);
        Coordinates.Utm oslo = Coordinates.toUtm(59.9139, 10.7522);
        String bare = String.format(java.util.Locale.ROOT, "32V %.0f %.0f", oslo.easting(), oslo.northing());
        assertEquals(59.9139, Coordinates.parse(bare)[0], 1e-4, "UTM bare: " + bare);
        assertEquals(59.9139, Coordinates.parse(String.format(java.util.Locale.ROOT, "32 V %.1f E, %.1f N", oslo.easting(), oslo.northing()))[0], 1e-4);
        double[] dms = Coordinates.parse(Coordinates.format(Coordinates.Format.DMS, 59.9139, 10.7522));
        assertEquals(59.9139, dms[0], 2e-5, "DMS as written");
        assertEquals(10.7522, dms[1], 2e-5);
        assertEquals(-33.9, Coordinates.parse("33\u00b054'00.0\"S 18\u00b024'00.0\"E")[0], 1e-6);
        assertNull(Coordinates.parse(""));
        assertNull(Coordinates.parse(null));
        assertNull(Coordinates.parse("hello"));
        assertNull(Coordinates.parse("95, 10"), "no such latitude");
        assertNull(Coordinates.parse("32V NM 9725 42617"), "odd digits: not MGRS");
        assertNull(Coordinates.parse("99V NM 97254 42617"), "no zone 99");
    }

    @Test
    void theOtherFormats() {
        assertEquals("59.913900 10.752200", Coordinates.format(Coordinates.Format.DECIMAL, 59.9139, 10.7522));
        assertEquals("59°54'50.0\"N 10°45'07.9\"E", Coordinates.format(Coordinates.Format.DMS, 59.9139, 10.7522));
        assertEquals("33°54'00.0\"S 18°24'00.0\"E", Coordinates.format(Coordinates.Format.DMS, -33.9, 18.4));
        assertTrue(Coordinates.format(Coordinates.Format.UTM, 33.3, 44.4).startsWith("38S 444141 E 3684706 N"));
        assertEquals("328 FT", Coordinates.feet(100));
        assertEquals(Coordinates.Format.MGRS, Coordinates.Format.parse(null));
        assertEquals(Coordinates.Format.DMS, Coordinates.Format.parse("DMS"));
    }
}
