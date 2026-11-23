package de.droiddrone.pc.air;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The battery order of a follower group (PRD AC-30, FL-1, E5): the lowest batteries in the bottom layer, the highest
 * at the top. A drone moves only when its battery puts it in another layer; its place within a layer never causes a
 * move. Checked at every enter or exit, or every 5 minutes. The next lead is the bottom-row drone with the lowest
 * battery.
 */
public final class BatteryOrder {
    private BatteryOrder() {
    }

    /** A follower's place: layer (0 = bottom row) and cell (0-7). */
    public record Place(int layer, int cell) {}

    /**
     * Where everyone should be: sorted by battery (lowest first, then by id), 8 to a layer from the bottom. Those whose
     * layer stays keep their cell; the others take the free cells of their new layer, lowest index first.
     */
    public static Map<String, Place> assign(Map<String, Place> now, Map<String, Double> battery) {
        List<String> ids = new ArrayList<>(now.keySet());
        ids.sort(Comparator.comparingDouble((String id) -> battery.getOrDefault(id, 100.0)).thenComparing(id -> id));
        Map<String, Integer> layerOf = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) layerOf.put(ids.get(i), i / HollowCube.CELLS);
        Map<String, Place> out = new LinkedHashMap<>();
        Map<Integer, Set<Integer>> used = new HashMap<>();
        for (String id : ids) { // stayers keep their cell
            Place p = now.get(id);
            if (p != null && p.layer() == layerOf.get(id)) {
                out.put(id, p);
                used.computeIfAbsent(p.layer(), k -> new HashSet<>()).add(p.cell());
            }
        }
        for (String id : ids) {
            if (out.containsKey(id)) continue;
            int layer = layerOf.get(id);
            Set<Integer> u = used.computeIfAbsent(layer, k -> new HashSet<>());
            int cell = 0;
            while (u.contains(cell)) cell++;
            u.add(cell);
            out.put(id, new Place(layer, cell));
        }
        return out;
    }

    /** The next lead (DISCARD LEAD, a silent lead taken over): the bottom row's lowest battery, or null when empty. */
    public static String nextLead(Map<String, Place> places, Map<String, Double> battery) {
        return places.entrySet().stream().filter(e -> e.getValue().layer() == 0)
                .min(Comparator.comparingDouble((Map.Entry<String, Place> e) -> battery.getOrDefault(e.getKey(), 100.0))
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey).orElse(null);
    }
}
