package de.droiddrone.pc.route;

import de.droiddrone.pc.map.Mercator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD area B on made-up terrain. The phase 2 gate: a route over a ridge never goes below the set height,
 * checked every metre, out and home.
 */
class RoutePlannerTest {
    private static final RoutePlanner.Limits LIM = RoutePlanner.Limits.ARDUCOPTER_DEFAULT;

    /** The line flown at a distance along: straight between the waypoints, as ArduPilot flies them. */
    private static double lineAt(List<RoutePlanner.Waypoint> w, double along) {
        for (int i = 1; i < w.size(); i++) {
            RoutePlanner.Waypoint a = w.get(i - 1), b = w.get(i);
            if (along <= b.alongM() + 1e-9) {
                double d = b.alongM() - a.alongM();
                return d <= 0 ? b.altAmslM() : a.altAmslM() + (b.altAmslM() - a.altAmslM()) * (along - a.alongM()) / d;
            }
        }
        return w.get(w.size() - 1).altAmslM();
    }

    /** Every metre along a line: the place there (straight along the leg it is on) and the line's height there. */
    private static void checkEveryMetre(RoutePlanner.Line line, TerrainSource t, double heightM, double tolerance) {
        List<RoutePlanner.Waypoint> w = line.waypoints();
        double len = line.lengthM();
        for (double s = 0; s <= len; s += 1.0) {
            int i = 0;
            while (i < line.along().length - 1 && line.along()[i + 1] < s) i++;
            int j = Math.min(i + 1, line.along().length - 1);
            double d = line.along()[j] - line.along()[i];
            double f = d <= 0 ? 0 : (s - line.along()[i]) / d;
            double lat = line.lat()[i] + (line.lat()[j] - line.lat()[i]) * f, lon = line.lon()[i] + (line.lon()[j] - line.lon()[i]) * f;
            double clearance = lineAt(w, s) - t.heightM(lat, lon);
            assertTrue(clearance >= heightM - tolerance, String.format("%.2f m over the ground at %.0f m along (set %.0f)", clearance, s, heightM));
        }
    }

    private static void checkSlopes(RoutePlanner.Line line) {
        List<RoutePlanner.Waypoint> w = line.waypoints();
        for (int i = 1; i < w.size(); i++) {
            double h = w.get(i).alongM() - w.get(i - 1).alongM(), dz = w.get(i).altAmslM() - w.get(i - 1).altAmslM();
            if (h < 1e-6) continue;
            assertTrue(dz / h <= LIM.climbMs() / LIM.cruiseMs() + 1e-6, "climb " + dz / h + " at leg " + i);
            assertTrue(-dz / h <= LIM.descentMs() / LIM.cruiseMs() + 1e-6, "descent " + -dz / h + " at leg " + i);
        }
    }

    @Test
    void phase2GateARidgeRouteNeverGoesBelowTheSetHeight() throws Exception {
        Ground g = Ground.ridge();
        Route r = Ground.route(new double[]{1000, 0});
        RoutePlanner.Plan p = RoutePlanner.plan(r, Ground.at(0, 0), true, g, LIM);
        checkEveryMetre(p.out(), g, 60, 0.02);
        checkEveryMetre(p.back(), g, 60, 0.02);
        checkSlopes(p.out());
        checkSlopes(p.back());
        // the climb starts well before the ridge: 150 m higher at a grade of 0.25 needs 600 m of run-up
        RoutePlanner.Waypoint top = p.out().waypoints().stream().max((a, b) -> Double.compare(a.altAmslM(), b.altAmslM())).orElseThrow();
        assertTrue(top.altAmslM() >= 250 + 60 - 0.01);
        // 150 m up at a grade of 0.25 needs 600 m of run-up, the ridge is 500 m away: it takes off higher than 60 m
        double atStart = lineAt(p.out().waypoints(), 0);
        assertTrue(atStart >= 310 - 0.25 * 500 - 1e-6 && atStart <= 310 - 0.25 * 500 + 5 + 1e-6, "start " + atStart);
        assertTrue(lineAt(p.out().waypoints(), 200) >= 235 - 1e-6, "climbing 300 m before the top");
    }

    @Test
    void aCliffIsClimbedBeforeItIsReached() throws Exception {
        Ground g = Ground.cliff();
        RoutePlanner.Plan p = RoutePlanner.plan(Ground.route(new double[]{600, 0}), Ground.at(0, 0), true, g, LIM);
        // a vertical step between two samples: each sample's floor takes its neighbours' ground too
        checkEveryMetre(p.out(), g, 60, 1e-6);
        checkSlopes(p.out());
        // 40 m up at 0.25: the line leaves the valley height about 160 m before the cliff (within the 5 m smoothing)
        double atCliff = lineAt(p.out().waypoints(), 301);
        assertTrue(atCliff >= 200 - 1e-6 && atCliff <= 205 + 1e-6, "at the cliff " + atCliff);
        assertTrue(lineAt(p.out().waypoints(), 130) <= 165 + 1e-6);
        assertTrue(lineAt(p.out().waypoints(), 150) > 160);
    }

    @Test
    void flatGroundNeedsOnlyTheRoutesOwnPointsAndTheHeightIsAboveSeaLevel() throws Exception {
        Ground g = Ground.flat(300);
        Route r = Ground.route(new double[]{500, 0}, new double[]{500, 500}, new double[]{0, 800});
        RoutePlanner.Plan p = RoutePlanner.plan(r, Ground.at(0, 0), true, g, LIM);
        List<RoutePlanner.Waypoint> w = p.out().waypoints();
        assertEquals(4, w.size(), "start + the three points: " + w);
        for (RoutePlanner.Waypoint x : w) assertEquals(360, x.altAmslM(), 1e-6, "300 m ground + 60 m");
        assertEquals(-1, w.get(0).routePoint());
        assertEquals(List.of(1, 2, 3), w.subList(1, 4).stream().map(RoutePlanner.Waypoint::routePoint).toList());
        double expected = 500 + 500 + Mercator.distanceM(r.point(1).lat(), r.point(1).lon(), r.point(2).lat(), r.point(2).lon());
        assertEquals(expected, p.out().lengthM(), 0.5);
    }

    @Test
    void smoothingDropsWaypointsAndKeepsTheLineWithinIt() throws Exception {
        Ground g = Ground.bumpy();
        Route r = Ground.route(new double[]{2000, 0});
        r.setSmoothingM(0);
        RoutePlanner.Plan tight = RoutePlanner.plan(r, Ground.at(0, 0), true, g, LIM);
        r.setSmoothingM(5);
        RoutePlanner.Plan smooth = RoutePlanner.plan(r, Ground.at(0, 0), true, g, LIM);
        assertTrue(smooth.out().waypoints().size() < 10, "5 m of smoothing over 3.5 m bumps: a few waypoints, not " + smooth.out().waypoints().size());
        assertTrue(tight.out().waypoints().size() > 5 * smooth.out().waypoints().size(), "no smoothing follows the bumps");
        checkEveryMetre(smooth.out(), g, 60, 0.02);
        RoutePlanner.Line l = smooth.out();
        for (int i = 0; i < l.along().length; i++) {
            assertTrue(l.flown()[i] <= l.floor()[i] + 5 + 1e-6, "at most the smoothing over the least height, at " + l.along()[i]);
        }
    }

    @Test
    void aPointsOwnHeightIsFlownThere() throws Exception {
        Ground g = Ground.flat(100);
        Route r = Ground.route(new double[]{400, 0}, new double[]{800, 0});
        r.setOwnHeight(0, 100);
        RoutePlanner.Plan p = RoutePlanner.plan(r, Ground.at(0, 0), true, g, LIM);
        RoutePlanner.Waypoint at1 = p.out().waypoints().stream().filter(x -> x.routePoint() == 1).findFirst().orElseThrow();
        assertTrue(at1.altAmslM() >= 200 - 1e-6 && at1.altAmslM() <= 205 + 1e-6, "100 m ground + its own 100 m: " + at1.altAmslM());
        checkSlopes(p.out());
        checkSlopes(p.back());
    }

    @Test
    void reversingTheRouteFliesItTheOtherWay() throws Exception {
        Ground g = Ground.ridge();
        Route r = Ground.route(new double[]{300, 0}, new double[]{1000, 0});
        RoutePlanner.Plan a = RoutePlanner.plan(r, Ground.at(0, 0), true, g, LIM);
        r.reverse();
        assertEquals(1000, Ground.east(r.point(0).lon()), 0.01);
        RoutePlanner.Plan b = RoutePlanner.plan(r, Ground.at(1000, 0), false, g, LIM);
        assertEquals(a.out().lengthM() - 300, b.out().lengthM(), 0.5, "from 1000 m back to 0 via 300 m");
        checkEveryMetre(b.out(), g, 60, 0.02);
    }

    @Test
    void aRouteWithAPlaceWhoseHeightIsNotKnownIsRefused() throws Exception {
        TerrainStore store = new TerrainStore(null);
        Route r = Ground.route(new double[]{200, 0});
        RoutePlanner.MissingTerrain m = assertThrows(RoutePlanner.MissingTerrain.class,
                () -> RoutePlanner.plan(r, Ground.at(0, 0), true, store, LIM));
        assertFalse(m.nodes().isEmpty());
        assertTrue(m.nodes().size() < 200, "each grid node once: " + m.nodes().size());
        // the fill looks them up; then it plans
        int found = TerrainFill.fill(store, m.nodes(), (lat, lon) -> 120.0, null, null);
        assertEquals(m.nodes().size(), found);
        RoutePlanner.Plan p = RoutePlanner.plan(r, Ground.at(0, 0), true, store, LIM);
        assertEquals(180, p.out().waypoints().get(0).altAmslM(), 1e-3);
        // one node the lookup can't answer: still refused, that node named
        TerrainStore partly = new TerrainStore(null);
        TerrainFill.fill(partly, m.nodes(), (lat, lon) -> Math.abs(Ground.east(lon) - 100) < 4 ? null : 120.0, null, null);
        RoutePlanner.MissingTerrain again = assertThrows(RoutePlanner.MissingTerrain.class,
                () -> RoutePlanner.plan(r, Ground.at(0, 0), true, partly, LIM));
        assertTrue(again.nodes().size() >= 1 && again.nodes().size() <= 3, again.nodes().size() + " left");
    }

    @Test
    void rtlAltitudeClearsTheHighestGroundOnTheStraightLineHome() throws Exception {
        Ground g = Ground.ridge();
        Route r = Ground.route(new double[]{1000, 0});
        RoutePlanner.Plan p = RoutePlanner.plan(r, Ground.at(0, 0), true, g, LIM);
        double[] home = Ground.at(0, 0);
        int cm = RoutePlanner.rtlAltCm(p, g, home[0], home[1], 60);
        // the ridge's top 250 m, home 100 m: 150 m over home + 60 m
        assertEquals(21_000, cm, 100);
        assertEquals(200, RoutePlanner.rtlAltCm(RoutePlanner.plan(r, home, true, Ground.flat(0), LIM), Ground.flat(0), home[0], home[1], 0),
                "ArduPilot's least RTL_ALT");
    }

    @Test
    void homeHeightCheck() {
        Ground g = Ground.flat(150);
        double[] home = Ground.at(0, 0);
        assertTrue(RoutePlanner.homeCheck(160, home[0], home[1], g).ok());
        RoutePlanner.HomeCheck off = RoutePlanner.homeCheck(190, home[0], home[1], g);
        assertFalse(off.ok());
        assertEquals("HOME 40 M OFF THE MAP - CHECK GPS / BARO", off.text());
        assertFalse(RoutePlanner.homeCheck(150, home[0], home[1], (la, lo) -> Double.NaN).ok());
    }

    @Test
    void flyToHereIsOnePointFromWhereTheDroneIs() throws Exception {
        double[] there = Ground.at(300, 400);
        Route r = Route.flyToHere(there[0], there[1]);
        assertEquals(1, r.size());
        RoutePlanner.Plan p = RoutePlanner.plan(r, Ground.at(0, 0), false, Ground.flat(50), LIM);
        assertEquals(500, p.out().lengthM(), 0.5);
        assertEquals(2, p.out().waypoints().size());
    }
}
