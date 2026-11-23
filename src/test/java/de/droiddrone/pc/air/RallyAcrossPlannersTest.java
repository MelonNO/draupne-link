package de.droiddrone.pc.air;

import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.pc.launch.RallyPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD AC-10 across the two planners: followers get their rally points from the group's exit plans (RallyPoints),
 * drones on their own from RallyPlan. No point of either kind lies within twice the protection radius (20 m) of another
 * drone's, whichever was given first. Places are made up.
 */
class RallyAcrossPlannersTest {
    static final double LAT = 60.0, LON = 10.0;
    final AirParams p = AirParams.defaults();
    final double apart = 2 * p.radiusM();

    private GroupController group(V2 site) {
        GroupController g = new GroupController(p, 0, LAT, LON, null);
        Map<String, Double> batt = new LinkedHashMap<>();
        for (int i = 1; i <= 12; i++) batt.put(String.format("F%02d", i), 50.0 + i);
        g.form("LEAD", batt, V2.ZERO, site);
        return g;
    }

    /** Every pair of all these points at least 20 m apart. */
    private void allApart(Map<String, V2> points) {
        List<String> ids = new ArrayList<>(points.keySet());
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                double d = points.get(ids.get(i)).dist(points.get(ids.get(j)));
                assertTrue(d >= apart - 1e-6, ids.get(i) + " and " + ids.get(j) + " rally points " + d + " m apart");
            }
        }
    }

    @Test
    void soloPointsGivenFirstTheFollowersKeepClearOfThem() {
        V2 site = new V2(-600, 0);
        GroupController g = group(site);
        Map<String, V2> solo = new LinkedHashMap<>(); // three drones on their own standing on the group's line home
        solo.put("S1", site.plus(new V2(-5, 3)));
        solo.put("S2", site.plus(new V2(-30, 0)));
        solo.put("S3", site.plus(new V2(-80, -4)));
        g.setOutsideRallies(solo);
        Map<String, V2> all = new LinkedHashMap<>(g.rallies());
        assertEquals(12, all.size());
        all.putAll(solo);
        allApart(all);
    }

    @Test
    void followersGivenFirstRallyPlanKeepsTheSoloDronesClearOfThem() {
        V2 site = new V2(-600, 0);
        GroupController g = group(site);
        List<double[]> taken = new ArrayList<>();
        Map<String, V2> all = new LinkedHashMap<>();
        for (Map.Entry<String, V2> e : g.rallies().entrySet()) {
            taken.add(GeoLocal.offset(LAT, LON, e.getValue().e(), e.getValue().n()));
            all.put(e.getKey(), e.getValue());
        }
        for (int i = 0; i < 6; i++) { // six drones on their own, standing among the followers' points
            V2 stand = site.plus(new V2(-i * 12.0, i % 2 == 0 ? 2 : -3));
            double[] ll = GeoLocal.offset(LAT, LON, stand.e(), stand.n());
            double[] at = RallyPlan.place(ll[0], ll[1], taken);
            taken.add(at);
            all.put("S" + i, new V2(GeoLocal.east(LAT, LON, at[0], at[1]), GeoLocal.north(LAT, LON, at[0], at[1])));
        }
        allApart(all);
        // and the group, told of them afterwards, has nothing to move
        Map<String, V2> before = new LinkedHashMap<>(g.rallies());
        Map<String, V2> solo = new LinkedHashMap<>(all);
        solo.keySet().removeAll(before.keySet());
        g.setOutsideRallies(solo);
        assertEquals(before, g.rallies(), "already clear: no follower's point moved");
    }

    @Test
    void aSoloPointPlacedOnAFollowersPointMovesThatFollowerNotTheOthers() {
        V2 site = new V2(-600, 0);
        GroupController g = group(site);
        Map<String, V2> before = new LinkedHashMap<>(g.rallies());
        String victim = before.keySet().iterator().next();
        g.setOutsideRallies(Map.of("S1", before.get(victim).plus(new V2(4, 4))));
        Map<String, V2> after = g.rallies();
        int moved = 0;
        for (String id : before.keySet()) if (!before.get(id).equals(after.get(id))) moved++;
        assertEquals(1, moved, "only the follower on it moves");
        Map<String, V2> all = new LinkedHashMap<>(after);
        all.put("S1", before.get(victim).plus(new V2(4, 4)));
        allApart(all);
    }

    @Test
    void aReserveThatJoinedIsNotCheckedAgainstItself() {
        V2 site = new V2(-600, 0);
        GroupController g = group(site);
        V2 own = new V2(-400, -300);
        g.addReserve("R1", 90, own, 200, 0);
        Map<String, V2> before = new LinkedHashMap<>(g.rallies());
        g.setOutsideRallies(Map.of("R1", own)); // its RallyPlan point, the same it brought
        assertEquals(before, g.rallies());
        assertEquals(own, g.rallies().get("R1"));
    }
}
