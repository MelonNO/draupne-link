package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet AP-12: GO LOOK's ground point to the phone. Places made up. */
class GoLookTest {
    @Test
    void thePlaceRoundTripsAndNonsenseIsRefused() {
        byte[] p = FcCommand.goLook(59.9123456, -10.7654321);
        FcCommand c = FcCommand.read(p, p.length);
        assertEquals(FcCommand.GO_LOOK, c.kind);
        assertEquals(59.9123456, c.lat, 1e-7);
        assertEquals(-10.7654321, c.lon, 1e-7);
        assertNull(FcCommand.read(p, 9), "cut short");
        assertNull(FcCommand.read(FcCommand.goLook(0, 0), 10), "0/0 is no place");
        assertNull(FcCommand.read(FcCommand.goLook(95, 0), 10), "off the globe");
        assertTrue(Double.isNaN(FcCommand.read(FcCommand.simple(FcCommand.PAUSE), 2).lat));
    }
}
