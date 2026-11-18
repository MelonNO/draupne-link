package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The field round of 2026-11-18: "sometimes the gps coords is all wrong and the location data jumps around randomly",
 * and the place must not stay stuck on a wrong start. Places here are made up (a field at 60 N 10 E).
 */
class PositionFilterTest {
    private static final double LAT = 60.0, LON = 10.0;
    /** One metre north, in degrees. */
    private static final double M = 1 / 111_320.0;

    private static PositionFilter settledAt(double lat, double lon, long now) {
        PositionFilter f = new PositionFilter();
        assertEquals(PositionFilter.TAKEN, f.offer(lat, lon, true, Double.NaN, now));
        assertTrue(f.has());
        return f;
    }

    @Test
    void aReadingWithoutAFixIsNoPosition() {
        assertFalse(PositionFilter.usableFc(0, 0, 0, 0), "what a GPS says before its first fix");
        assertFalse(PositionFilter.usableFc(0, 0, 79.541658, 159.961032), "no fix: a place from nowhere");
        assertFalse(PositionFilter.usableFc(0, 4, LAT, LON), "four satellites and no fix");
        assertFalse(PositionFilter.usableFc(2, 4, LAT, LON), "a fix on four satellites");
        assertFalse(PositionFilter.usableFc(2, 12, 0, 0), "0/0 with a fix flag");
        assertFalse(PositionFilter.usableFc(2, 12, 91, 10));
        assertTrue(PositionFilter.usableFc(2, 12, LAT, LON));
        assertTrue(PositionFilter.usableFc(1, 5, LAT, LON));

        PositionFilter f = new PositionFilter();
        assertEquals(PositionFilter.NO_FIX, f.offer(0, 0, false, Double.NaN, 0));
        assertEquals(PositionFilter.NO_FIX, f.offer(79.5, 159.9, false, Double.NaN, 100));
        assertEquals(PositionFilter.NO_FIX, f.offer(0, 0, true, Double.NaN, 200), "0/0 even when called usable");
        assertFalse(f.has());
        assertTrue(Double.isNaN(f.lat()));
    }

    @Test
    void theFirstReadingWithAFixIsThePlaceAtOnce() {
        PositionFilter f = new PositionFilter();
        assertEquals(PositionFilter.TAKEN, f.offer(LAT, LON, true, Double.NaN, 0));
        assertTrue(f.has());
        assertFalse(f.heldBack());
        assertEquals(LAT, f.lat(), 1e-9);
        assertEquals(0, f.moves(), "the first place is not a move");
    }

    @Test
    void oneFarReadingRepeatedIsNotThreeThatAgreeUntilItHasStoodAMoment() {
        PositionFilter f = settledAt(LAT, LON, 0);
        double far = LAT + 1; // the same far reading again and again: one GPS fix asked for many times, or a new place
        long t = 1000;
        for (; t < 1000 + PositionFilter.SAME_MS; t += 100) {
            assertEquals(PositionFilter.HELD_BACK, f.offer(far, LON, true, Double.NaN, t), "the same reading again, at " + t);
        }
        assertEquals(PositionFilter.TAKEN, f.offer(far, LON, true, Double.NaN, t), "it has stood there for two seconds");
        assertEquals(1, f.moves());
    }

    @Test
    void flyingOnIsFollowedReadingByReading() {
        PositionFilter f = settledAt(LAT, LON, 0);
        double lat = f.lat();
        long now = 1000;
        for (int i = 0; i < 50; i++) { // 150 km/h north: 4.2 m every 100 ms
            lat += 4.2 * M;
            now += 100;
            assertEquals(PositionFilter.TAKEN, f.offer(lat, LON, true, Double.NaN, now));
        }
        assertEquals(lat, f.lat(), 1e-9);
        assertEquals(0, f.moves());
    }

    @Test
    void oneFarLeapBetweenGoodReadingsIsDropped() {
        PositionFilter f = settledAt(LAT, LON, 0);
        double lat = f.lat();
        assertEquals(PositionFilter.HELD_BACK, f.offer(LAT + 0.01, LON + 0.02, true, Double.NaN, 1000), "1.6 km in a moment");
        assertTrue(f.heldBack());
        assertEquals(lat, f.lat(), 1e-9, "the place held stays");
        assertEquals(PositionFilter.TAKEN, f.offer(lat + 3 * M, LON, true, Double.NaN, 1100));
        assertFalse(f.heldBack());
        assertEquals(0, f.moves());
        // and a second lone leap later starts from nothing: it does not join the first one's group
        assertEquals(PositionFilter.HELD_BACK, f.offer(LAT + 0.0101, LON + 0.02, true, Double.NaN, 1200));
        assertEquals(PositionFilter.TAKEN, f.offer(lat + 4 * M, LON, true, Double.NaN, 1300));
        assertEquals(PositionFilter.HELD_BACK, f.offer(LAT + 0.0102, LON + 0.02, true, Double.NaN, 1400));
        assertEquals(lat + 4 * M, f.lat(), 1e-9, "three leaps with good readings between them never agree as a group");
    }

    @Test
    void aWrongStartGivesWayToReadingsThatAgreeFarAway() {
        PositionFilter f = settledAt(39.688, 33.691, 0); // booted at a place 2 600 km off
        assertEquals(PositionFilter.HELD_BACK, f.offer(LAT, LON, true, Double.NaN, 1000));
        assertEquals(39.688, f.lat(), 0.001);
        assertEquals(PositionFilter.HELD_BACK, f.offer(LAT + M, LON, true, Double.NaN, 1200));
        assertEquals(PositionFilter.TAKEN, f.offer(LAT + 2 * M, LON, true, Double.NaN, 1400));
        assertEquals(LAT + 2 * M, f.lat(), 1e-9, "the right place wins: not stuck on the wrong start");
        assertEquals(1, f.moves());
    }

    @Test
    void candidatesFarApartInTimeAreNotOneGroup() {
        PositionFilter f = settledAt(LAT, LON, 0);
        double far = LAT + 1; // 111 km: out of reach for half an hour
        assertEquals(PositionFilter.HELD_BACK, f.offer(far, LON, true, Double.NaN, 1000));
        assertEquals(PositionFilter.HELD_BACK, f.offer(far + M, LON, true, Double.NaN, 1000 + PositionFilter.GROUP_MS + 1));
        assertEquals(PositionFilter.HELD_BACK, f.offer(far + 2 * M, LON, true, Double.NaN, 1200 + PositionFilter.GROUP_MS));
        assertEquals(PositionFilter.TAKEN, f.offer(far + 3 * M, LON, true, Double.NaN, 1400 + PositionFilter.GROUP_MS),
                "three that agree since the gap");
    }

    @Test
    void afterALongSilenceAFarPlaceIsReachable() {
        PositionFilter f = settledAt(LAT, LON, 0);
        // ten minutes later, 20 km away: 120 km/h - flown
        assertEquals(PositionFilter.TAKEN, f.offer(LAT + 20_000 * M, LON, true, Double.NaN, 600_000));
    }

    @Test
    void aPhonesPoorAccuracyWidensTheSlack() {
        PositionFilter f = settledAt(LAT, LON, 0);
        double lat = f.lat();
        assertEquals(PositionFilter.HELD_BACK, f.offer(lat + 80 * M, LON, true, Double.NaN, 100));
        assertEquals(PositionFilter.TAKEN, f.offer(lat + 80 * M, LON, true, 60, 200), "80 m off with 60 m accuracy");
    }

    @Test
    void noFixAfterAPlaceKeepsThePlaceAndItsAge() {
        PositionFilter f = settledAt(LAT, LON, 0);
        long at = f.at();
        assertEquals(PositionFilter.NO_FIX, f.offer(0, 0, false, Double.NaN, 5000));
        assertTrue(f.has());
        assertEquals(at, f.at());
        assertFalse(f.heldBack());
    }

    @Test
    void distancesAreMetres() {
        assertEquals(111_195, PositionFilter.distanceM(60, 10, 61, 10), 200);
        assertEquals(55_600, PositionFilter.distanceM(60, 10, 60, 11), 200);
        assertEquals(0, PositionFilter.distanceM(60, 10, 60, 10), 1e-6);
    }
}
