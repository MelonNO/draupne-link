package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The field round of 2026-11-18, drones with a GPS and no compass: "compass heading is wrong it spins around
 * randomly"; "if it was wrong in the air and then it lands it is still wrong".
 */
class BestHeadingTest {
    private static final double NAN = Double.NaN;

    @Test
    void withoutACompassTheHeadingIsTheWayItTravels() {
        BestHeading h = new BestHeading();
        // in the air, armed: the gyro's yaw says 339, the phone beside the motors says 200 - the drone flies east
        h.update(0, false, 339, true, 60, 90, true, 200, 3, 5);
        assertEquals(90, h.deg(), 1e-9);
        assertEquals(BestHeading.TRAVEL, h.source());
        assertFalse(h.stale());
    }

    @Test
    void slowInTheAirKeepsTheLastHeadingMarkedHeld() {
        BestHeading h = new BestHeading();
        h.update(0, false, 10, true, 40, 120, true, 300, 3, 5);
        h.update(1000, false, 250, true, 2, 77, true, 31, 3, 5); // a hover: the course is noise, the phone is disturbed
        assertEquals(120, h.deg(), 1e-9, "not the yaw, not the course of a hover, not the phone while armed");
        assertEquals(BestHeading.HELD, h.source());
        assertTrue(h.stale());
        h.update(2000, false, 250, true, BestHeading.TRAVEL_KMH, 200, true, 31, 3, 5);
        assertEquals(200, h.deg(), 1e-9);
        assertEquals(BestHeading.TRAVEL, h.source());
    }

    @Test
    void landedAndDisarmedThePhonesCompassTakesOver() {
        BestHeading h = new BestHeading();
        h.update(0, false, 10, true, 40, 120, true, 300, 3, 5);
        h.update(1000, false, 10, true, 0.4, 120, false, 215, 3, 5);
        assertEquals(215, h.deg(), 1e-9, "wrong in the air does not stay wrong on the ground");
        assertEquals(BestHeading.PHONE, h.source());
        assertFalse(h.approximate());
        h.update(2000, false, 10, true, 0.4, 120, false, 215, 1, 5);
        assertTrue(h.approximate(), "the phone's compass wants calibrating");
        h.update(3000, false, 10, true, 0.4, 120, false, 215, 3, 40);
        assertTrue(h.approximate(), "its own error estimate is large");
    }

    @Test
    void anUnknownArmedStateIsNeverTakenForDisarmed() {
        BestHeading h = new BestHeading();
        h.update(0, false, 10, false, NAN, NAN, null, 215, 3, 5);
        assertTrue(Double.isNaN(h.deg()));
        assertEquals(BestHeading.NONE, h.source());
    }

    @Test
    void aCompassOnTheFlightControllerIsTheHeading() {
        BestHeading h = new BestHeading();
        h.update(0, true, 45, true, 0, 300, false, 200, 3, 5);
        assertEquals(45, h.deg(), 1e-9);
        assertEquals(BestHeading.FC_COMPASS, h.source());
        // flying sideways for a moment is no reason to drop it
        h.update(1000, true, 45, true, 50, 135, true, 200, 3, 5);
        h.update(1000 + BestHeading.COMPASS_OFF_MS - 1, true, 45, true, 50, 135, true, 200, 3, 5);
        assertEquals(BestHeading.FC_COMPASS, h.source());
        h.update(9500, true, 50, true, 50, 60, true, 200, 3, 5); // forward again: the count starts anew
        h.update(10_000, true, 45, true, 50, 135, true, 200, 3, 5);
        h.update(10_000 + BestHeading.COMPASS_OFF_MS - 1, true, 45, true, 50, 135, true, 200, 3, 5);
        assertEquals(BestHeading.FC_COMPASS, h.source());
        assertFalse(h.compassDistrusted());
    }

    @Test
    void aCompassThatDisagreesWithTheFlightIsNotTrustedAgain() {
        BestHeading h = new BestHeading();
        for (long t = 0; t <= BestHeading.COMPASS_OFF_MS; t += 500) h.update(t, true, 300, true, 70, 80, true, NAN, -1, -1);
        assertTrue(h.compassDistrusted(), "140 degrees off the course through 8 s of fast flight");
        assertEquals(80, h.deg(), 1e-9);
        assertEquals(BestHeading.TRAVEL, h.source());
        h.update(20_000, true, 300, true, 0, 80, false, 215, 3, 5);
        assertEquals(BestHeading.PHONE, h.source(), "still not trusted after landing");
        h.reset();
        assertFalse(h.compassDistrusted(), "a new link starts fresh");
    }

    @Test
    void withoutAFixTheCourseIsNotUsed() {
        BestHeading h = new BestHeading();
        h.update(0, false, 10, false, 60, 90, true, 200, 3, 5);
        assertEquals(BestHeading.NONE, h.source());
        assertTrue(Double.isNaN(h.deg()));
    }

    @Test
    void headingsAreKeptWithinTheCircle() {
        BestHeading h = new BestHeading();
        h.update(0, false, NAN, true, 30, 365, true, NAN, -1, -1);
        assertEquals(5, h.deg(), 1e-9);
        h.update(1, false, NAN, true, 30, -10, true, NAN, -1, -1);
        assertEquals(350, h.deg(), 1e-9);
        assertEquals(20, BestHeading.apart(350, 10), 1e-9);
        assertEquals(180, BestHeading.apart(0, 180), 1e-9);
    }
}
