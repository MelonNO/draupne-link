package de.droiddrone.pc.air;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fleet PRD FD-6: the drones out on their own (dispatched, a route flown, a reserve on its way) come home by ArduPilot's
 * RTL - up to their return height where they are, straight to their rally point, down. Two whose ways home pass within
 * twice the protection radius of each other must come home a layer apart. Each drone gets the lowest return height at or
 * over its own lowest (its route's terrain clearance, RT-9) that is a layer spacing clear of every drone whose way home
 * comes near its own; the drones are taken lowest-needed first, then by id, so the result is the same on every PC.
 * Pure planning: what it gives is each drone's RTL_ALT (above its home).
 */
public final class SoloReturns {
    private SoloReturns() {
    }

    /** A drone out on its own: where it is now, its rally point, its home's height above sea level, its lowest RTL_ALT. */
    public record Solo(String id, V2 pos, V2 rally, double homeAmsl, double minRtlAltRelM) {}

    /** Every drone's return height above its home (m), by id. */
    public static Map<String, Double> heights(List<Solo> drones, AirParams p) {
        List<Solo> order = new ArrayList<>(drones);
        order.sort(Comparator.comparingDouble((Solo s) -> s.homeAmsl() + s.minRtlAltRelM()).thenComparing(Solo::id));
        Map<String, Double> amsl = new LinkedHashMap<>();
        Map<String, Double> out = new LinkedHashMap<>();
        Map<String, Solo> byId = new LinkedHashMap<>();
        for (Solo s : order) {
            double h = s.homeAmsl() + Math.max(s.minRtlAltRelM(), p.floorM());
            boolean moved = true;
            while (moved) { // up a layer at a time past every crossing way home it would share a height with
                moved = false;
                for (Map.Entry<String, Double> e : amsl.entrySet()) {
                    Solo o = byId.get(e.getKey());
                    if (Math.abs(e.getValue() - h) >= p.layerM() - ExitPlanner.VERTICAL_TOLERANCE_M) continue;
                    if (segmentDistance(s.pos(), s.rally(), o.pos(), o.rally()) >= 2 * p.radiusM()) continue;
                    h = e.getValue() + p.layerM();
                    moved = true;
                }
            }
            amsl.put(s.id(), h);
            byId.put(s.id(), s);
            out.put(s.id(), h - s.homeAmsl());
        }
        return out;
    }

    /** The closest two segments a-b and c-d come (m). */
    static double segmentDistance(V2 a, V2 b, V2 c, V2 d) {
        if (crosses(a, b, c, d)) return 0;
        return Math.min(Math.min(pointSegment(a, c, d), pointSegment(b, c, d)), Math.min(pointSegment(c, a, b), pointSegment(d, a, b)));
    }

    private static double pointSegment(V2 q, V2 a, V2 b) {
        V2 ab = b.minus(a);
        double len2 = ab.dot(ab);
        double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, q.minus(a).dot(ab) / len2));
        return q.dist(a.plus(ab.times(t)));
    }

    private static boolean crosses(V2 a, V2 b, V2 c, V2 d) {
        double d1 = cross(c, d, a), d2 = cross(c, d, b), d3 = cross(a, b, c), d4 = cross(a, b, d);
        return d1 * d2 < 0 && d3 * d4 < 0;
    }

    private static double cross(V2 o, V2 a, V2 b) {
        return (a.e() - o.e()) * (b.n() - o.n()) - (a.n() - o.n()) * (b.e() - o.e());
    }
}
