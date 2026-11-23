package de.droiddrone.pc.route;

import de.droiddrone.common.MissionItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet PRD RT-8, B4 - B6, RT-7 and RT-12: the mission, the profile and one route for several drones. */
class MissionBuilderTest {
    private static final RoutePlanner.Limits LIM = RoutePlanner.Limits.ARDUCOPTER_DEFAULT;

    @Test
    void theMissionTakesOffFliesOutHoldsAndComesHomeTheSameWay() throws Exception {
        Ground g = Ground.ridge();
        Route r = Ground.route(new double[]{400, 0}, new double[]{1000, 0});
        double[] home = Ground.at(0, 0);
        RoutePlanner.Plan p = RoutePlanner.plan(r, home, true, g, LIM);
        MissionBuilder.Mission m = MissionBuilder.build(p, home[0], home[1], g.heightM(home[0], home[1]));
        List<MissionItem> it = m.items();
        for (int i = 0; i < it.size(); i++) assertEquals(i, it.get(i).seq);
        assertEquals(MissionItem.NAV_WAYPOINT, it.get(0).command, "home");
        assertEquals(MissionItem.NAV_TAKEOFF, it.get(1).command);
        assertEquals(MissionItem.NAV_LOITER_UNLIM, it.get(m.holdSeq()).command, "B5: hold and call the pilot");
        assertEquals(1000, Ground.east(it.get(m.holdSeq()).lonDeg()), 0.1);
        assertTrue(m.returnSeq() > m.holdSeq());
        assertEquals(MissionItem.NAV_LAND, it.get(m.endSeq()).command);
        assertEquals(0, Ground.east(it.get(m.endSeq()).lonDeg()), 0.1);
        for (MissionItem x : it) {
            assertTrue(x.frame == MissionItem.FRAME_GLOBAL, "B4 / RT-10: altitudes above sea level, no terrain frames");
            if (x.seq > 0 && x.command != MissionItem.NAV_LAND) assertTrue(x.z >= 160 - 1e-3, "above the valley's 100 m + 60 m: " + x);
        }
        // the way home goes over the ridge again (B6): an item near its top on the way back
        boolean overRidge = false;
        for (int i = m.returnSeq(); i < m.endSeq(); i++) if (it.get(i).z >= 310 - 1e-3) overRidge = true;
        assertTrue(overRidge);
    }

    @Test
    void flyToHereHoldsAtTheEndOfTheWayHomeInsteadOfLanding() throws Exception {
        double[] there = Ground.at(300, 0), from = Ground.at(0, 0);
        RoutePlanner.Plan p = RoutePlanner.plan(Route.flyToHere(there[0], there[1]), from, false, Ground.flat(20), LIM);
        MissionBuilder.Mission m = MissionBuilder.build(p, from[0], from[1], 20);
        assertNotEquals(MissionItem.NAV_TAKEOFF, m.items().get(1).command);
        assertEquals(MissionItem.NAV_LOITER_UNLIM, m.items().get(m.holdSeq()).command);
        assertEquals(MissionItem.NAV_LOITER_UNLIM, m.items().get(m.endSeq()).command);
    }

    @Test
    void oneRouteForSeveralDronesOnLayersOfTheirOwn() throws Exception {
        double[] home = Ground.at(0, 0);
        RoutePlanner.Plan p = RoutePlanner.plan(Ground.route(new double[]{500, 0}), home, true, Ground.flat(0), LIM);
        MissionBuilder.Mission base = MissionBuilder.build(p, home[0], home[1], 0);
        List<MissionBuilder.Mission> three = RouteShare.layered(base, 3, RouteShare.LAYER_SPACING_M);
        for (int k = 0; k < 3; k++) {
            MissionBuilder.Mission m = three.get(k);
            assertEquals(base.items().get(0).z, m.items().get(0).z, "home stays");
            assertEquals(base.items().get(m.holdSeq()).z + 10 * k, m.items().get(m.holdSeq()).z, 1e-4);
            assertEquals(MissionItem.NAV_LAND, m.items().get(m.endSeq()).command);
            assertEquals(0f, m.items().get(m.endSeq()).z, "the land is not raised");
        }
        assertArrayEquals(new double[]{0, 4, 8}, RouteShare.slots(3, RouteShare.minSlotS(LIM, 20)));
    }

    @Test
    void theProfileSaysDistanceTimeBatteryClearanceAndWhereThereIsNo4G() throws Exception {
        double[] home = Ground.at(0, 0);
        RoutePlanner.Plan p = RoutePlanner.plan(Ground.route(new double[]{1000, 0}), home, true, Ground.flat(100), LIM);
        RouteProfile pr = RouteProfile.of(p, RouteProfile.Battery.DEFAULT,
                (lat, lon, agl) -> Ground.east(lon) < 400 || Ground.east(lon) > 600);
        assertEquals(1000, pr.outM(), 0.5);
        assertEquals(1000, pr.backM(), 0.5);
        assertEquals(100 + 60 / 2.5, pr.outS(), 0.5, "1 km at 10 m/s, the 60 m climb at 2.5 m/s");
        assertEquals(100 + 60 / 1.5, pr.backS(), 0.5, "and the 60 m down at 1.5 m/s");
        assertEquals(pr.outS() * 100 / 23 / 60, pr.outPercent(), 1e-6);
        assertEquals(60, pr.lowestClearanceM(), 1e-6);
        assertEquals(1, pr.noCoverage().size());
        assertEquals(400, pr.noCoverage().get(0)[0], 50);
        assertEquals(600, pr.noCoverage().get(0)[1], 50);
        assertTrue(pr.along().length <= RouteProfile.CHART_POINTS + 1);
        assertEquals(1000, pr.along()[pr.along().length - 1], 0.5, "the chart reaches the end");
        assertTrue(pr.summary().startsWith("1.0 KM  2:04  OUT 9 %"), pr.summary());
        assertTrue(pr.summary().endsWith("NO 4G: 1"));
        assertTrue(pr.overBattery(15));
        assertFalse(pr.overBattery(50));
    }

    @Test
    void theRidgesLowestClearanceIsTheSetHeight() throws Exception {
        double[] home = Ground.at(0, 0);
        RoutePlanner.Plan p = RoutePlanner.plan(Ground.route(new double[]{1000, 0}), home, true, Ground.ridge(), LIM);
        RouteProfile pr = RouteProfile.of(p, RouteProfile.Battery.DEFAULT, null);
        assertTrue(pr.lowestClearanceM() >= 60 - 0.02);
        assertTrue(pr.noCoverage().isEmpty(), "coverage not known: no stretches said");
    }
}
