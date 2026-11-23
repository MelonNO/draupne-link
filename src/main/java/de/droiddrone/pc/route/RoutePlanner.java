package de.droiddrone.pc.route;

import de.droiddrone.pc.map.Mercator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fleet PRD area B: the flight line of a route over terrain, with no terrain data on the drone (RT-10: the mission's
 * altitudes are above sea level, B4 / RT-5, and ArduPilot flies them as they are).
 * <ul>
 *   <li>RT-2: the ground under the route every {@link #LINE_STEP_M} m (well under the 10 m asked); any place whose
 *   height is not known refuses the plan ({@link MissingTerrain} names the nodes to look up).</li>
 *   <li>RT-3: the drone never lower than the set height above the ground (the point's own height where it has one,
 *   in between from one point's to the next); waypoints are added where a straight leg would come lower, and
 *   left out where the line can run straight at most the smoothing above the set height.</li>
 *   <li>RT-4: no leg steeper than the drone climbs or descends at cruise speed (WPNAV_SPEED_UP / _DN against
 *   WPNAV_SPEED); the climb starts early enough to clear a ridge at the set height.</li>
 * </ul>
 * The route's points are always waypoints (the line turns there). Pure and synchronous: terrain comes from a
 * {@link TerrainSource} (the store on disk, or made-up terrain in the tests).
 */
public final class RoutePlanner {
    /** The ground is looked at, and the line checked, every metre along the route. */
    public static final double LINE_STEP_M = 1.0;
    private static final double EPS = 1e-6;

    private RoutePlanner() {
    }

    /**
     * The drone's speeds (ArduPilot WPNAV_SPEED, WPNAV_SPEED_UP, WPNAV_SPEED_DN, here in m/s): a leg's slope is held
     * within climbMs / cruiseMs up and descentMs / cruiseMs down. ArduPilot Copter's defaults: 10, 2.5, 1.5 m/s.
     */
    public record Limits(double cruiseMs, double climbMs, double descentMs) {
        public static final Limits ARDUCOPTER_DEFAULT = new Limits(10, 2.5, 1.5);

        public Limits {
            if (!(cruiseMs > 0) || !(climbMs > 0) || !(descentMs > 0)) throw new IllegalArgumentException("speeds must be over 0");
        }

        /** From the parameters as the FC keeps them (cm/s). */
        public static Limits fromParams(double wpnavSpeedCm, double wpnavSpeedUpCm, double wpnavSpeedDnCm) {
            return new Limits(wpnavSpeedCm / 100, wpnavSpeedUpCm / 100, wpnavSpeedDnCm / 100);
        }

        double climbGrade() {
            return climbMs / cruiseMs;
        }

        double descentGrade() {
            return descentMs / cruiseMs;
        }
    }

    /** RT-2: places along the route whose ground height is not known; the plan is refused until they are. */
    public static final class MissingTerrain extends Exception {
        private final List<double[]> nodes;

        MissingTerrain(List<double[]> nodes) {
            super(nodes.size() + " terrain heights missing");
            this.nodes = nodes;
        }

        /** The grid nodes to look up ({lat, lon}), each once (see {@link TerrainStore#missing}). */
        public List<double[]> nodes() {
            return nodes;
        }
    }

    /**
     * One waypoint of the flight line. routePoint: the route's point number (1, 2, ...) it is, 0 for one the planner
     * added, -1 for the start. alongM: how far along this line.
     */
    public record Waypoint(double lat, double lon, double altAmslM, double alongM, int routePoint) {}

    /**
     * One direction of flight: its waypoints and, for the profile, the samples every {@link #LINE_STEP_M}: the
     * distance along, the ground, the least height the drone may fly (ground + set height, slopes held), the line
     * flown (straight between the waypoints).
     */
    public record Line(List<Waypoint> waypoints, double[] along, double[] lat, double[] lon, double[] ground,
                       double[] floor, double[] flown) {
        public double lengthM() {
            return along.length == 0 ? 0 : along[along.length - 1];
        }

        /** The least clearance over the ground along the line flown (m) and where (index into the samples). */
        public int lowestClearanceIndex() {
            int at = 0;
            for (int i = 1; i < flown.length; i++) if (flown[i] - ground[i] < flown[at] - ground[at]) at = i;
            return at;
        }
    }

    /**
     * A route planned: out from the start through its points (then hold, B5), and back the same way reversed (B6).
     * takeoff: the drone starts on the ground at start (a NAV_TAKEOFF first); else it is in the air there ("fly to
     * here").
     */
    public record Plan(Route route, double startLat, double startLon, boolean takeoff, Line out, Line back, Limits limits) {}

    /**
     * The route flown from start ({lat, lon}). Throws {@link MissingTerrain} when any ground height on the way out or
     * back is not known.
     */
    public static Plan plan(Route route, double[] start, boolean takeoff, TerrainSource terrain, Limits limits) throws MissingTerrain {
        if (route.size() == 0) throw new IllegalArgumentException("a route needs a point");
        List<double[]> out = new ArrayList<>(); // {lat, lon, height above ground, route point number}
        out.add(new double[]{start[0], start[1], route.heightAt(0), -1});
        for (int i = 0; i < route.size(); i++) {
            Route.Point p = route.point(i);
            out.add(new double[]{p.lat(), p.lon(), route.heightAt(i), i + 1});
        }
        List<double[]> back = new ArrayList<>();
        for (int i = out.size() - 1; i >= 0; i--) back.add(out.get(i));

        Map<Long, double[]> missing = new LinkedHashMap<>();
        Samples so = sample(out, terrain, missing), sb = sample(back, terrain, missing);
        if (!missing.isEmpty()) throw new MissingTerrain(new ArrayList<>(missing.values()));
        return new Plan(route.copy(), start[0], start[1], takeoff, line(so, route.smoothingM(), limits),
                line(sb, route.smoothingM(), limits), limits);
    }

    /** The path sampled every metre (vertices among the samples): where, how far along, the ground, the set height. */
    private record Samples(double[] along, double[] lat, double[] lon, double[] ground, double[] height, int[] vertexAt, int[] vertexNumber) {}

    private static Samples sample(List<double[]> path, TerrainSource terrain, Map<Long, double[]> missing) {
        List<double[]> s = new ArrayList<>(); // lat, lon, along, height
        int[] vertexAt = new int[path.size()];
        int[] vertexNumber = new int[path.size()];
        double along = 0;
        for (int v = 0; v < path.size(); v++) {
            double[] b = path.get(v);
            vertexNumber[v] = (int) b[3];
            if (v == 0) {
                vertexAt[0] = 0;
                s.add(new double[]{b[0], b[1], 0, b[2]});
                continue;
            }
            double[] a = path.get(v - 1);
            double leg = Mercator.distanceM(a[0], a[1], b[0], b[1]);
            int steps = Math.max(1, (int) Math.ceil(leg / LINE_STEP_M));
            // the short way round: across the antimeridian (179.99 to -179.99) the samples went round the whole globe
            double dLon = b[1] - a[1];
            if (dLon > 180) dLon -= 360;
            else if (dLon < -180) dLon += 360;
            for (int k = 1; k <= steps; k++) {
                double f = (double) k / steps;
                s.add(new double[]{a[0] + (b[0] - a[0]) * f, wrapLon(a[1] + dLon * f), along + leg * f, a[2] + (b[2] - a[2]) * f});
            }
            along += leg;
            vertexAt[v] = s.size() - 1;
        }
        int n = s.size();
        double[] al = new double[n], la = new double[n], lo = new double[n], gr = new double[n], he = new double[n];
        for (int i = 0; i < n; i++) {
            double[] x = s.get(i);
            la[i] = x[0];
            lo[i] = x[1];
            al[i] = x[2];
            he[i] = x[3];
            gr[i] = terrain.heightM(x[0], x[1]);
            if (Double.isNaN(gr[i])) {
                long key = TerrainStore.key(TerrainStore.latIndex(x[0]), TerrainStore.lonIndex(x[1]));
                missing.putIfAbsent(key, TerrainStore.node(x[0], x[1]));
            }
        }
        return new Samples(al, la, lo, gr, he, vertexAt, vertexNumber);
    }

    /** A longitude back into -180..180. */
    static double wrapLon(double lon) {
        if (lon > 180) return lon - 360;
        if (lon < -180) return lon + 360;
        return lon;
    }

    private static Line line(Samples s, double smoothing, Limits limits) {
        int n = s.along.length;
        double[] floor = new double[n];
        // the higher ground of a sample and its neighbours: the line runs straight from one sample to the next, so
        // with both ends over the higher of the two grounds, a step or peak between samples stays under it too
        for (int i = 0; i < n; i++) {
            double g = s.ground[i];
            if (i > 0) g = Math.max(g, s.ground[i - 1]);
            if (i < n - 1) g = Math.max(g, s.ground[i + 1]);
            floor[i] = g + s.height[i];
        }
        // RT-4: descents no steeper than allowed (forward), climbs no steeper - started early enough (backward)
        double up = limits.climbGrade(), down = limits.descentGrade();
        for (int i = 1; i < n; i++) floor[i] = Math.max(floor[i], floor[i - 1] - down * (s.along[i] - s.along[i - 1]));
        for (int i = n - 2; i >= 0; i--) floor[i] = Math.max(floor[i], floor[i + 1] - up * (s.along[i + 1] - s.along[i]));

        // RT-3: as few waypoints as keep the line within [floor, floor + smoothing] and the slopes within the limits
        List<Integer> at = new ArrayList<>();
        List<Double> alt = new ArrayList<>();
        at.add(0);
        alt.add(floor[0]);
        int v = 1; // the next route vertex that must be a waypoint
        int a = 0;
        double za = floor[0];
        while (a < n - 1) {
            while (v < s.vertexAt.length - 1 && s.vertexAt[v] <= a) v++; // a vertex reached (or two points on one spot)
            int end = s.vertexAt[v];
            // String pulling in the band [floor, floor + smoothing]: from (a, za) the slopes that keep a straight line
            // in the band narrow as it goes on; where none is left, the line bends at the point that set the bound it
            // ran into - the band's top (it had to dip under it) or the floor (it had to rise over it). That is the
            // taut line through the band, with few bends; ending each stretch at its farthest point instead made the
            // line zig-zag along the band's bottom.
            double lo = -down, hi = up; // the chord's slope from (a, za), within the climb / descent limits
            int loAt = -1, hiAt = -1;   // which sample set lo (on the floor) / hi (on the band's top); -1: a limit
            int best = a + 1;
            double bestZ = Math.max(floor[a + 1], za - down * (s.along[a + 1] - s.along[a]));
            for (int i = a + 1; i <= end; i++) {
                double d = s.along[i] - s.along[a];
                if (d <= EPS) continue;
                double iLo = (floor[i] - za) / d, iHi = (floor[i] + smoothing - za) / d;
                // i as the end: its height within its band, the slope within what the samples before it allow
                double eLo = Math.max(lo, iLo), eHi = Math.min(hi, iHi);
                if (eLo <= eHi + EPS) {
                    best = i;
                    bestZ = za + eLo * d; // the lowest end that works (the pilot's own point: its set height if it can)
                    if (i == end) break;  // the pilot's point: a waypoint in any case
                }
                // i as a sample under a longer line
                boolean raised = false, lowered = false;
                if (iLo > lo) {
                    lo = iLo;
                    loAt = i;
                    raised = true;
                }
                if (iHi < hi) {
                    hi = iHi;
                    hiAt = i;
                    lowered = true;
                }
                if (lo > hi + EPS) {
                    // the bend: at the bound i ran into (not at i itself)
                    if (raised && hiAt >= 0 && hiAt < i) {
                        best = hiAt;
                        bestZ = floor[hiAt] + smoothing;
                    } else if (lowered && loAt >= 0 && loAt < i) {
                        best = loAt;
                        bestZ = floor[loAt];
                    }
                    break; // else: the farthest end that worked (a climb or descent limit closed it)
                }
            }
            at.add(best);
            alt.add(Math.max(bestZ, floor[best]));
            a = best;
            za = alt.get(alt.size() - 1);
        }

        double[] flown = new double[n];
        List<Waypoint> wps = new ArrayList<>();
        int vertexIndex = 0;
        for (int k = 0; k < at.size(); k++) {
            int i = at.get(k);
            int number = 0;
            while (vertexIndex < s.vertexAt.length && s.vertexAt[vertexIndex] < i) vertexIndex++;
            if (vertexIndex < s.vertexAt.length && s.vertexAt[vertexIndex] == i) number = s.vertexNumber[vertexIndex];
            wps.add(new Waypoint(s.lat[i], s.lon[i], alt.get(k), s.along[i], number));
            if (k > 0) {
                int p = at.get(k - 1);
                double zp = alt.get(k - 1), zi = alt.get(k), dd = s.along[i] - s.along[p];
                for (int j = p; j <= i; j++) flown[j] = dd <= EPS ? zi : zp + (zi - zp) * (s.along[j] - s.along[p]) / dd;
            } else {
                flown[0] = alt.get(0);
            }
        }
        return new Line(wps, s.along, s.lat, s.lon, s.ground, floor, flown);
    }

    // --- RT-9 and RT-5 ---

    /**
     * RT-9: ArduPilot's RTL_ALT (cm, above home) for this plan: over the highest ground on the straight line home from
     * anywhere on the route (each waypoint and every 50 m of the line out), plus clearanceM, so a failsafe RTL - which
     * climbs to RTL_ALT and flies straight home - clears it too. ArduPilot takes 200 - 300000 cm.
     */
    public static int rtlAltCm(Plan plan, TerrainSource terrain, double homeLat, double homeLon, double clearanceM) throws MissingTerrain {
        double homeGround = terrain.heightM(homeLat, homeLon);
        Map<Long, double[]> missing = new LinkedHashMap<>();
        if (Double.isNaN(homeGround)) missing.put(TerrainStore.key(TerrainStore.latIndex(homeLat), TerrainStore.lonIndex(homeLon)),
                TerrainStore.node(homeLat, homeLon));
        double highest = Double.NEGATIVE_INFINITY;
        Line l = plan.out();
        double nextAt = 0;
        for (int i = 0; i < l.along().length; i++) {
            boolean waypoint = false;
            for (Waypoint w : l.waypoints()) if (Math.abs(w.alongM() - l.along()[i]) < EPS) waypoint = true;
            if (!waypoint && l.along()[i] < nextAt && i != l.along().length - 1) continue;
            nextAt = l.along()[i] + 50;
            highest = Math.max(highest, highestOnLine(l.lat()[i], l.lon()[i], homeLat, homeLon, terrain, missing));
        }
        if (!missing.isEmpty()) throw new MissingTerrain(new ArrayList<>(missing.values()));
        double cm = (highest - homeGround + clearanceM) * 100;
        return (int) Math.max(200, Math.min(300_000, Math.ceil(cm)));
    }

    private static double highestOnLine(double lat0, double lon0, double lat1, double lon1, TerrainSource t, Map<Long, double[]> missing) {
        double len = Mercator.distanceM(lat0, lon0, lat1, lon1);
        int steps = Math.max(1, (int) Math.ceil(len / 10));
        double top = Double.NEGATIVE_INFINITY;
        double dLon = lon1 - lon0;
        if (dLon > 180) dLon -= 360;
        else if (dLon < -180) dLon += 360;
        for (int k = 0; k <= steps; k++) {
            double f = (double) k / steps, la = lat0 + (lat1 - lat0) * f, lo = wrapLon(lon0 + dLon * f);
            double h = t.heightM(la, lo);
            if (Double.isNaN(h)) {
                missing.putIfAbsent(TerrainStore.key(TerrainStore.latIndex(la), TerrainStore.lonIndex(lo)), TerrainStore.node(la, lo));
            } else {
                top = Math.max(top, h);
            }
        }
        return top;
    }

    /** RT-5: the FC's home altitude against the terrain there (its altitudes and the map's must agree). */
    public record HomeCheck(double reportedAmslM, double terrainAmslM, double differenceM, boolean ok) {
        /** The pilot's line: "HOME HEIGHT OK" or "HOME 34 M OFF THE MAP - CHECK GPS / BARO". */
        public String text() {
            if (Double.isNaN(terrainAmslM)) return "HOME HEIGHT NOT KNOWN ON THE MAP";
            return ok ? "HOME HEIGHT OK" : String.format(java.util.Locale.ROOT, "HOME %.0f M OFF THE MAP - CHECK GPS / BARO", Math.abs(differenceM));
        }
    }

    /** GPS height and the map's terrain usually agree within this (GPS vertical error, the map's own). */
    public static final double HOME_TOLERANCE_M = 20;

    public static HomeCheck homeCheck(double reportedHomeAmslM, double homeLat, double homeLon, TerrainSource terrain) {
        double g = terrain.heightM(homeLat, homeLon);
        double diff = reportedHomeAmslM - g;
        return new HomeCheck(reportedHomeAmslM, g, diff, !Double.isNaN(g) && Math.abs(diff) <= HOME_TOLERANCE_M);
    }
}
