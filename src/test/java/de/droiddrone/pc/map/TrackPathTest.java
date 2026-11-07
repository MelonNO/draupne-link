package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The map's thinned track (H6, 1.0.49): bounded, taken in as it grows, never far off the real line. */
class TrackPathTest {
    private static FlightTrack.Point point(double lat, double lon) {
        return new FlightTrack.Point(0, lat, lon, 0, 0, 0, 10, true, false, 0);
    }

    /** A wandering flight: 10 reports a second for two hours. */
    private static List<FlightTrack.Point> longFlight() {
        List<FlightTrack.Point> pts = new ArrayList<>();
        double lat = 60, lon = 10;
        for (int i = 0; i < 72_000; i++) {
            lat += 0.00002 * Math.sin(i / 300.0);
            lon += 0.00003 * Math.cos(i / 470.0);
            pts.add(point(lat, lon));
        }
        return pts;
    }

    @Test
    void aLongFlightStaysBoundedAndCloseToItsLine() {
        List<FlightTrack.Point> pts = longFlight();
        TrackPath p = new TrackPath();
        for (int from = 0; from < pts.size(); from += 7) p.add(pts.subList(from, Math.min(pts.size(), from + 7))); // as it grows
        assertEquals(pts.size(), p.taken());
        assertTrue(p.size() <= TrackPath.MAX_POINTS + 1, "bounded: " + p.size());
        // every report lies within the spacing of the line drawn (checked against its kept points)
        double spacing = p.spacing();
        for (int i = 0; i < pts.size(); i += 97) {
            double x = Mercator.x(pts.get(i).lon(), 0), y = Mercator.y(pts.get(i).lat(), 0);
            double nearest = Double.MAX_VALUE;
            for (int k = 0; k < p.size(); k++) nearest = Math.min(nearest, Math.hypot(p.x(k) - x, p.y(k) - y));
            assertTrue(nearest <= 2 * spacing, "report " + i + " is " + nearest / spacing + " spacings off");
        }
        // the spacing is what MAX_POINTS allow the flight's length, give or take the doubling
        double length = 0;
        for (int i = 1; i < pts.size(); i++) {
            length += Math.hypot(Mercator.x(pts.get(i).lon(), 0) - Mercator.x(pts.get(i - 1).lon(), 0),
                    Mercator.y(pts.get(i).lat(), 0) - Mercator.y(pts.get(i - 1).lat(), 0));
        }
        assertTrue(spacing <= 4 * length / TrackPath.MAX_POINTS, "no coarser than needed: " + spacing * TrackPath.MAX_POINTS / length);
    }

    @Test
    void theNewestPointIsAlwaysTheEndAndTheRecentAreKeptAsTheyCame() {
        TrackPath p = new TrackPath(10);
        List<FlightTrack.Point> pts = new ArrayList<>();
        for (int i = 0; i < 50; i++) pts.add(point(60 + i * 0.001, 10));
        pts.add(point(60.049 + 1e-9, 10)); // next to the last: thinned away, but still the end
        p.add(pts);
        assertTrue(p.size() <= 11);
        assertEquals(Mercator.y(60.049 + 1e-9, 0), p.y(p.size() - 1), 1e-15);
        assertEquals(51, p.recentSize());
        assertEquals(Mercator.y(60, 0), p.recentY(0), 1e-15, "oldest first");
        assertEquals(Mercator.x(10, 0), p.x(0), 1e-15, "the first point stays");
    }

    @Test
    void theTracksNewPointsAreTakenFromWhereItLeftOff() throws Exception {
        FlightTrack track = new FlightTrack(null);
        for (int i = 0; i < 5; i++) track.sample(gps(60 + i * 0.001, i + 1), true);
        TrackPath p = new TrackPath();
        p.add(track.pointsFrom(p.taken()));
        assertEquals(5, p.taken());
        track.sample(gps(60.01, 99), true);
        assertEquals(1, track.pointsFrom(p.taken()).size());
        p.add(track.pointsFrom(p.taken()));
        assertEquals(6, p.taken());
        assertTrue(track.pointsFrom(6).isEmpty());
    }

    private static de.droiddrone.pc.FcTelemetry.Gps gps(double lat, long at) {
        return new de.droiddrone.pc.FcTelemetry.Gps(2, 12, 40.0, 80, -1, 0, lat, 10, -1, 45, 0.8, at);
    }
}
