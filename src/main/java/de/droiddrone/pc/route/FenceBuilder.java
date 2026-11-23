package de.droiddrone.pc.route;

import de.droiddrone.common.MissionItem;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.pc.map.Airspace;
import de.droiddrone.pc.map.MapGraphics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Fleet PRD AP-9: the geofence an ArduPilot drone keeps on board - the AIRSPACE areas round where it flies
 * (restricted, danger, prohibited: each a polygon it must stay out of) and the pilot's own map rectangles named for it
 * ("FENCE ..." or "FENCE OUT ...": stay out; "FENCE IN ...": stay in - the other rectangles on the map are left alone,
 * a rectangle there can mean anything). As ArduPilot's fence list (MAVLink mission type 1): one item per corner,
 * MAV_CMD_NAV_FENCE_POLYGON_VERTEX_EXCLUSION / _INCLUSION with the corner count in param1. The nearest areas first,
 * at most {@link #MAX_ITEMS} corners (the FC's room), each polygon at most {@link #MAX_CORNERS} corners.
 */
public final class FenceBuilder {
    public static final int POLYGON_INCLUSION = 5001, POLYGON_EXCLUSION = 5002;
    public static final int MAX_ITEMS = 200, MAX_CORNERS = 40;
    /** Areas whose nearest corner is further than this from the middle are left out. */
    public static final double REACH_M = 25_000;

    /** One fence polygon: its corners (lat, lon) and whether the drone must stay inside. */
    public record Polygon(String name, List<double[]> corners, boolean inclusion) {}

    private FenceBuilder() {
    }

    /** The polygons for a flight round (lat, lon): the airspaces near it, the rectangles named FENCE. */
    public static List<Polygon> polygons(List<Airspace> airspace, List<MapGraphics.Item> drawn, double lat, double lon) {
        List<Polygon> out = new ArrayList<>();
        for (Airspace a : airspace) {
            if (a.ring() == null || a.ring().size() < 3 || a.later()) continue;
            if (nearest(a.ring(), lat, lon) > REACH_M) continue;
            out.add(new Polygon(a.name(), simplify(a.ring()), false));
        }
        for (MapGraphics.Item it : drawn) {
            if (it.kind() != MapGraphics.Kind.AREA || it.name() == null || it.points().size() < 3) continue;
            String n = it.name().trim().toUpperCase(Locale.ROOT);
            if (!n.startsWith("FENCE")) continue;
            List<double[]> ring = new ArrayList<>();
            for (MapGraphics.LatLon p : it.points()) ring.add(new double[]{p.lat(), p.lon()});
            out.add(new Polygon(it.name(), ring, n.startsWith("FENCE IN")));
        }
        out.sort(Comparator.comparingDouble(p -> nearest(p.corners(), lat, lon)));
        return out;
    }

    /** ArduPilot's fence items for these polygons: the nearest first while they fit in MAX_ITEMS. */
    public static List<MissionItem> items(List<Polygon> polygons) {
        List<MissionItem> out = new ArrayList<>();
        for (Polygon p : polygons) {
            List<double[]> c = p.corners();
            if (out.size() + c.size() > MAX_ITEMS) continue;
            for (double[] v : c) {
                out.add(new MissionItem(out.size(), 0, p.inclusion() ? POLYGON_INCLUSION : POLYGON_EXCLUSION, true, c.size(), 0, 0, 0,
                        (int) Math.round(v[0] * 1e7), (int) Math.round(v[1] * 1e7), 0));
            }
        }
        return out;
    }

    /** At most MAX_CORNERS corners: every k-th kept (a ring from a circle or a long border). An open last == first dropped. */
    static List<double[]> simplify(List<double[]> ring) {
        List<double[]> r = new ArrayList<>(ring);
        if (r.size() > 3 && r.get(0)[0] == r.get(r.size() - 1)[0] && r.get(0)[1] == r.get(r.size() - 1)[1]) r.remove(r.size() - 1);
        if (r.size() <= MAX_CORNERS) return r;
        List<double[]> out = new ArrayList<>();
        double step = r.size() / (double) MAX_CORNERS;
        for (int i = 0; i < MAX_CORNERS; i++) out.add(r.get((int) Math.floor(i * step)));
        return out;
    }

    static double nearest(List<double[]> ring, double lat, double lon) {
        double best = Double.MAX_VALUE;
        for (double[] p : ring) best = Math.min(best, GeoLocal.distance(lat, lon, p[0], p[1]));
        return best;
    }
}
