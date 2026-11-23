package de.droiddrone.pc.air;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The PC's height layers (PRD AC-2, AC-13, K1, K21). A layer is base + n x the layer spacing, with base = the floor
 * above the highest ground under the area, so every layer is at or above the floor everywhere and keeps its order over
 * slopes. A follower group takes a band: its abort layer (n = 0) and its cube layers, all its own. A drone not parked
 * in a group - the lead, a reserve on its way, a dispatched drone - gets a layer of its own: shared with no other such
 * drone within {@link #OWN_LAYER_RANGE_M}, and never with a group's band. Heights are metres above sea level.
 * <p>
 * The ceiling (metres above the highest ground; NaN: none, the pilot's choice K21) is never exceeded: no layer above it
 * is given out, and a request then gets {@link #NO_LAYER} (AC-13: "NO LAYER FREE").
 */
public final class LayerTable {
    public static final int NO_LAYER = -1;
    public static final double OWN_LAYER_RANGE_M = 500;

    private final double groundAmsl;
    private final AirParams p;
    private final Map<Integer, String> band = new HashMap<>();
    private final Map<Integer, Set<String>> own = new HashMap<>();
    private final Map<String, double[]> where = new HashMap<>();
    private final Map<String, Integer> layerOf = new HashMap<>();

    /** @param highestGroundAmsl the highest ground under the area the layers are for */
    public LayerTable(AirParams p, double highestGroundAmsl) {
        this.p = p;
        this.groundAmsl = highestGroundAmsl;
    }

    /** The base: the floor over the highest ground (a group's abort layer). */
    public double baseAmsl() {
        return groundAmsl + p.floorM();
    }

    public double altAmsl(int layer) {
        return baseAmsl() + layer * p.layerM();
    }

    public boolean underCeiling(int layer) {
        return Double.isNaN(p.ceilingM()) || altAmsl(layer) - groundAmsl <= p.ceilingM() + 1e-6;
    }

    /** A group's band, layers from..from+cubeLayers (abort + cube); false when the ceiling or another owner is in the way. */
    public boolean takeBand(String group, int from, int cubeLayers) {
        for (int n = from; n <= from + cubeLayers; n++) {
            if (!underCeiling(n)) return false;
            String o = band.get(n);
            if (o != null && !o.equals(group) || !own.getOrDefault(n, Set.of()).isEmpty()) return false;
        }
        band.values().removeIf(group::equals);
        for (int n = from; n <= from + cubeLayers; n++) band.put(n, group);
        return true;
    }

    /** The lowest layer index above every band (where own layers start). */
    public int aboveBands() {
        return band.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
    }

    /**
     * A layer of its own for a drone at (e, n) in the airspace's frame, at or above {@code from}: the lowest one with no
     * band and no other own-layer drone within {@link #OWN_LAYER_RANGE_M}; {@link #NO_LAYER} when none is under the
     * ceiling.
     */
    public int ownLayer(String id, double e, double n, int from) {
        release(id);
        for (int l = Math.max(from, 0); ; l++) {
            if (!underCeiling(l) || l > 10_000) return NO_LAYER;
            if (band.containsKey(l)) continue;
            boolean clash = false;
            for (String o : own.getOrDefault(l, Set.of())) {
                double[] w = where.get(o);
                if (w != null && Math.hypot(w[0] - e, w[1] - n) <= OWN_LAYER_RANGE_M) clash = true;
            }
            if (clash) continue;
            own.computeIfAbsent(l, k -> new HashSet<>()).add(id);
            where.put(id, new double[]{e, n});
            layerOf.put(id, l);
            return l;
        }
    }

    /** A drone with its own layer moved: kept for the range check. */
    public void moved(String id, double e, double n) {
        if (where.containsKey(id)) where.put(id, new double[]{e, n});
    }

    public void release(String id) {
        Integer l = layerOf.remove(id);
        if (l != null) own.getOrDefault(l, new HashSet<>()).remove(id);
        where.remove(id);
        band.values().removeIf(id::equals);
    }

    /** The own layer this drone has, or {@link #NO_LAYER}. */
    public int layerOf(String id) {
        return layerOf.getOrDefault(id, NO_LAYER);
    }

    public String bandOwner(int layer) {
        return band.get(layer);
    }
}
