package de.droiddrone.pc.air;

import java.util.HashMap;
import java.util.Map;

/**
 * PRD AC-4: height changes only in a cleared vertical column - takeoff, an autolaunch climb, a landing, a layer
 * change outside the cube's transit shafts. A column is a cylinder of the protection radius from one height to another,
 * reserved for the time of the move; no other drone may be in it or predicted in it, and two reserved columns never
 * overlap (launches whose columns overlap go one after the other, AC-5). Not clear: the move waits.
 */
public final class ColumnReservations {
    /** A column: centre (airspace frame), from and to height (AMSL, any order). */
    public record Column(double e, double n, double fromAlt, double toAlt) {
        double low() {
            return Math.min(fromAlt, toAlt);
        }

        double high() {
            return Math.max(fromAlt, toAlt);
        }
    }

    private final AirParams p;
    private final Map<String, Column> reserved = new HashMap<>();

    public ColumnReservations(AirParams p) {
        this.p = p;
    }

    /**
     * Reserve a column for who, if no other reservation overlaps it (sideways within 2 radii: two drones in them would
     * be within one, and in height within a layer spacing) and none of the given drones is in it now or predicted in
     * it within 5 s ({@code others}: {e, n, u, ve, vn, vu} each).
     */
    public boolean reserve(String who, Column c, Iterable<double[]> others) {
        for (Map.Entry<String, Column> r : reserved.entrySet()) {
            if (r.getKey().equals(who)) continue;
            Column o = r.getValue();
            boolean side = Math.hypot(o.e() - c.e(), o.n() - c.n()) < 2 * p.radiusM();
            boolean height = o.low() < c.high() + p.layerM() && c.low() < o.high() + p.layerM();
            if (side && height) return false;
        }
        for (double[] d : others) {
            for (double t = 0; t <= ConflictPredictor.HORIZON_S; t += ConflictPredictor.STEP_S) {
                double e = d[0] + d[3] * t, n = d[1] + d[4] * t, u = d[2] + d[5] * t;
                if (Math.hypot(e - c.e(), n - c.n()) < p.radiusM() && u > c.low() - p.layerM() && u < c.high() + p.layerM()) return false;
            }
        }
        reserved.put(who, c);
        return true;
    }

    public void release(String who) {
        reserved.remove(who);
    }

    public Column of(String who) {
        return reserved.get(who);
    }
}
