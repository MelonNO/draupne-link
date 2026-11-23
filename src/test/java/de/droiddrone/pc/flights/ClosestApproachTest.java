package de.droiddrone.pc.flights;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD AC-14: every real flight keeps its closest approach to another DRAUPNE drone - in its record, read back by
 * PREVIOUS FLIGHTS (the summary's CLOSEST).
 */
class ClosestApproachTest {
    static final double LAT = 59.91, LON = 10.73;
    /** About a metre north, in degrees. */
    static final double M = 1 / 111_195.0;

    @TempDir
    Path root;

    @Test
    void theNearestOtherDroneSidewaysAndInHeight() {
        List<ClosestApproach.Other> others = List.of(
                new ClosestApproach.Other("Lead", LAT, LON, 100), // the flown drone itself
                new ClosestApproach.Other("MobileB", LAT + 30 * M, LON, 100),
                new ClosestApproach.Other("MobileC", LAT + 12 * M, LON, 120), // 12 m sideways, 20 m higher: 23 m
                new ClosestApproach.Other("MobileD", LAT + 18 * M, LON, Double.NaN)); // the network's: sideways only
        ClosestApproach.Near n = ClosestApproach.nearest("Lead", LAT, LON, 100, others);
        assertEquals("MobileD", n.drone(), "18 m sideways beats 23 m across");
        assertEquals(18, n.distanceM(), 0.2);
        assertTrue(Double.isNaN(n.heightM()));
        assertEquals("18 M MobileD (SIDE)", n.text());

        ClosestApproach.Near c = ClosestApproach.nearest("Lead", LAT, LON, 100, others.subList(0, 3));
        assertEquals("MobileC", c.drone());
        assertEquals(Math.hypot(12, 20), c.distanceM(), 0.2);
        assertEquals("23 M MobileC (12 M SIDE, 20 M HEIGHT)", c.text());
        ClosestApproach.Near noHeight = ClosestApproach.nearest("Lead", LAT, LON, Double.NaN, others.subList(0, 3));
        assertEquals("MobileC", noHeight.drone(), "its own height not known: sideways for all");
        assertEquals(12, noHeight.distanceM(), 0.2);
        assertNull(ClosestApproach.nearest("Lead", LAT, LON, 100, List.of(others.get(0))), "nobody but itself");
        assertNull(ClosestApproach.nearest("Lead", Double.NaN, LON, 100, others), "no position of its own");
    }

    @Test
    void theRecordKeepsTheNearestAndReadsItBack() throws IOException {
        Path dir = Files.createDirectories(root.resolve("tracks"));
        long start = FlightFiles.at("2026-11-23T10:00:00");
        FlightRecord r = FlightRecord.start(dir, "Lead", "192.0.2.7", "1.0.72", start);
        r.armed(start + 1000);
        r.closest(null, start + 2000); // armed, nobody in the air yet: watched
        r.closest(new ClosestApproach.Near("MobileB", 40, 10), start + 3000);
        r.closest(new ClosestApproach.Near("MobileC", 25, Double.NaN), start + 4000);
        r.closest(new ClosestApproach.Near("MobileB", 24.6, Double.NaN), start + 4100); // nearer by less than a metre: not at once
        r.closest(new ClosestApproach.Near("MobileB", 60, 0), start + 5000); // further: not kept
        r.end(FlightRecord.DISCONNECT, start + 6000);
        FlightRecord.flush();

        FlightRecord back = FlightRecord.read(r.file());
        assertTrue(back.watchedOthers());
        assertEquals("MobileB", back.closest().drone(), "the nearest, written at the end at the latest");
        assertEquals(24.6, back.closest().sideM(), 0.05);
        assertTrue(Double.isNaN(back.closest().heightM()));
        assertEquals(start + 4100, back.closestAtMs());

        FlightRecord none = FlightRecord.start(dir, "Lead", "192.0.2.7", "1.0.72", start + 60_000);
        none.closestWatched();
        none.end(FlightRecord.DISCONNECT, start + 70_000);
        FlightRecord.flush();
        FlightRecord noneBack = FlightRecord.read(none.file());
        assertTrue(noneBack.watchedOthers(), "watched, and nobody came: NONE, not unknown");
        assertNull(noneBack.closest());
    }

    @Test
    void previousFlightsShowsIt() throws IOException {
        Path tracks = Files.createDirectories(root.resolve("tracks")), videos = Files.createDirectories(root.resolve("videos")),
                logs = Files.createDirectories(root.resolve("logs"));
        long t0 = FlightFiles.at(FlightSummaryTest.T0);
        Path track = FlightFiles.gpx(tracks, FlightSummaryTest.T0, 120, FlightSummaryTest::point);
        FlightRecord r = FlightRecord.start(tracks, "MobileAlpha", "192.0.2.7", "1.0.72", t0);
        r.track(track);
        r.armed(t0 + 10_000);
        r.closest(new ClosestApproach.Near("MobileB", 12, 7), t0 + 40_000);
        r.end(FlightRecord.DISCONNECT, t0 + 120_000);
        FlightRecord.flush();

        List<FlightLibrary.Flight> flights = FlightLibrary.scan(tracks, videos, logs);
        assertEquals(1, flights.size());
        FlightSummary s = FlightSummary.of(flights.get(0));
        assertEquals("14 M", s.closestText());
        assertEquals("MobileB", s.closest.drone());
        FlightSummary.Figure f = s.figures().get(9);
        assertEquals("CLOSEST", f.label());
        assertTrue(f.known());
    }
}
