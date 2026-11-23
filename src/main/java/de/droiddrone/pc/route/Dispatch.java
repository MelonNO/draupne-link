package de.droiddrone.pc.route;

import de.droiddrone.common.group.GeoLocal;

import java.util.ArrayList;
import java.util.List;

/**
 * Fleet PRD FD-3 / AC-2 / AC-3 / AC-4: DISPATCH planned - each drone a slot of its own round the target (a ring, the
 * slots at least {@link #SLOT_GAP_M} apart), a terrain-following route there from where it stands, and a height layer of
 * its own: drone k flies its whole route k x 10 m above the route height (the order kept over the terrain, AC-3), and
 * climbs straight up to its route's first height in its own column before it leaves (the launch's climb, AC-4); the
 * launches go one after another, {@link #STAGGER_S} apart. At its slot each holds and calls the pilot (B5: YOUR TURN).
 * Pure: the terrain comes from a TerrainSource (MissingTerrain says what to look up first).
 */
public final class Dispatch {
    /** Slots on the ring at least this far apart: twice the protection radius (K2) and some. */
    public static final double SLOT_GAP_M = 30, MIN_RING_M = 40;
    public static final double LAYER_M = RouteShare.LAYER_SPACING_M;
    public static final double STAGGER_S = 10;

    /** A drone to send: where it stands, its home's height above sea level. */
    public record Drone(String name, double lat, double lon, double homeAmsl) {}

    /** One drone's part: its slot, its layer above the route height, its mission, its first height, when it launches. */
    public record Leg(String name, double slotLat, double slotLon, double layerM, MissionBuilder.Mission mission,
                      double climbToM, double launchAfterS) {}

    private Dispatch() {
    }

    /** The slots round the target: one per drone, evenly on a ring big enough to keep them SLOT_GAP_M apart. */
    public static List<double[]> slots(double lat, double lon, int n) {
        List<double[]> out = new ArrayList<>();
        if (n <= 0) return out;
        if (n == 1) {
            out.add(new double[]{lat, lon});
            return out;
        }
        double r = Math.max(MIN_RING_M, SLOT_GAP_M / (2 * Math.sin(Math.PI / n)));
        for (int k = 0; k < n; k++) {
            double a = 2 * Math.PI * k / n;
            out.add(GeoLocal.offset(lat, lon, r * Math.sin(a), r * Math.cos(a)));
        }
        return out;
    }

    /**
     * Every drone's leg; the nearest drone gets the slot nearest to it in turn (no two routes cross the ring's middle
     * for nothing). Throws MissingTerrain with every node any route lacks.
     */
    public static List<Leg> plan(List<Drone> drones, double targetLat, double targetLon, double heightM, TerrainSource terrain,
                                 RoutePlanner.Limits limits) throws RoutePlanner.MissingTerrain {
        List<double[]> free = slots(targetLat, targetLon, drones.size());
        List<Leg> legs = new ArrayList<>();
        List<double[]> missing = new ArrayList<>();
        for (int k = 0; k < drones.size(); k++) {
            Drone d = drones.get(k);
            int best = 0;
            for (int i = 1; i < free.size(); i++) {
                if (GeoLocal.distance(d.lat(), d.lon(), free.get(i)[0], free.get(i)[1])
                        < GeoLocal.distance(d.lat(), d.lon(), free.get(best)[0], free.get(best)[1])) best = i;
            }
            double[] slot = free.remove(best);
            Route r = new Route(d.name());
            r.setHeightM(heightM + k * LAYER_M);
            r.add(slot[0], slot[1]);
            try {
                RoutePlanner.Plan p = RoutePlanner.plan(r, new double[]{d.lat(), d.lon()}, false, terrain, limits);
                MissionBuilder.Mission m = MissionBuilder.build(p, d.lat(), d.lon(), d.homeAmsl());
                double first = p.out().waypoints().isEmpty() ? heightM : p.out().waypoints().get(0).altAmslM() - d.homeAmsl();
                legs.add(new Leg(d.name(), slot[0], slot[1], k * LAYER_M, m, Math.max(first, heightM + k * LAYER_M), k * STAGGER_S));
            } catch (RoutePlanner.MissingTerrain e) {
                missing.addAll(e.nodes());
            }
        }
        if (!missing.isEmpty()) throw new RoutePlanner.MissingTerrain(missing);
        return legs;
    }
}
