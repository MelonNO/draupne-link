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
