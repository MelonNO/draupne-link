package de.droiddrone.pc.air;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PRD AC-10: before a drone leaves the ground it gets its own landing (rally) point, at least twice the protection
 * radius from every other drone's. Here: a line of points along the way home from the group, starting at the landing
 * site and running on away from the group, {@link #spacingM} apart. The drones whose lanes point most towards home get
 * the nearest points: with {@link ExitPlanner} giving them the lowest return heights, whoever flies over another's
 * rally point or climb point on the way home is always the higher of the two - no circles, no start delays needed for
 * a group whose drones all land at one site.
 */
public final class RallyPoints {
    private RallyPoints() {
    }

    /** 2.5 protection radii: two drones on neighbouring points are always more than two radii apart. */
    public static double spacingM(AirParams p) {
        return 2.5 * p.radiusM();
    }

    /**
     * Rally points for these drones at the site: ids with their lane bearings seen from the group's centre (degrees),
     * the group's centre and the site (airspace frame).
     */
    public static Map<String, V2> assign(Map<String, Double> laneBearings, V2 centre, V2 site, AirParams p) {
        return assign(laneBearings, centre, site, p, List.of());
    }

    /**
     * As {@link #assign(Map, V2, V2, AirParams)}, the points along the line that lie within two protection radii of one
     * of these (other drones' rally points: those this PC gave drones on their own, RallyPlan) skipped.
     */
    public static Map<String, V2> assign(Map<String, Double> laneBearings, V2 centre, V2 site, AirParams p, java.util.Collection<V2> avoid) {
        V2 home = site.minus(centre);
        V2 dir = home.length() < 1 ? new V2(-1, 0) : home.unit();
        List<String> ids = new ArrayList<>(laneBearings.keySet());
        ids.sort(Comparator.comparingDouble((String id) -> -towards(laneBearings.get(id), dir)).thenComparing(id -> id));
        Map<String, V2> out = new HashMap<>();
        int slot = 0;
        for (String id : ids) {
            V2 at;
            while (!free(at = site.plus(dir.times(slot * spacingM(p))), avoid, p)) slot++;
            out.put(id, at);
            slot++;
        }
        return out;
    }

    /** No point of these within two protection radii of at (AC-10). */
    public static boolean free(V2 at, java.util.Collection<V2> taken, AirParams p) {
        for (V2 t : taken) if (t.dist(at) < 2 * p.radiusM()) return false;
        return true;
    }

    /** The first point along the line from the site, at the spacing, clear of all these. */
    public static V2 firstFree(V2 site, V2 centre, java.util.Collection<V2> taken, AirParams p) {
        V2 home = site.minus(centre);
        V2 dir = home.length() < 1 ? new V2(-1, 0) : home.unit();
        for (int slot = 0; ; slot++) {
            V2 at = site.plus(dir.times(slot * spacingM(p)));
            if (free(at, taken, p)) return at;
        }
    }

    /** How much a lane on this bearing points along dir (1: straight that way, -1: straight away). */
    static double towards(double bearingDeg, V2 dir) {
        double b = Math.toRadians(bearingDeg);
        return Math.sin(b) * dir.e() + Math.cos(b) * dir.n();
    }

    /** The next free rally point at a site for a drone that joins later: beyond those given out. */
    public static V2 next(V2 site, V2 centre, int taken, AirParams p) {
        V2 home = site.minus(centre);
        V2 dir = home.length() < 1 ? new V2(-1, 0) : home.unit();
        return site.plus(dir.times(taken * spacingM(p)));
    }
}
