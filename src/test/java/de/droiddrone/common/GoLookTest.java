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

    /** ArduPilot DENIES a DO_REPOSITION whose height is NaN (SITL 4.7.3, SitlEdgeTest): the phone sends the height it has. */
    @Test
    void theRepositionHeightIsANumberOrNothingIsSent() {
        assertEquals(25.5f, FcCommand.goLookHeight(25.5));
        assertEquals(-3f, FcCommand.goLookHeight(-3), "below home (a valley): still its height");
        assertTrue(Float.isNaN(FcCommand.goLookHeight(Double.NaN)), "not known: not sent");
        assertTrue(Float.isNaN(FcCommand.goLookHeight(Double.POSITIVE_INFINITY)));
        assertTrue(Float.isNaN(FcCommand.goLookHeight(1e6)));
    }
}
