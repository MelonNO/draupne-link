package de.droiddrone.pc.map;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

/**
 * 4G COVERAGE (1.0.44, the pilot: "identify where there is poor reception before entering that area"): an ESTIMATE
 * of the 4G signal the drone gets, from where OpenCelliD says the cells are and the terrain between. No operator
 * publishes an open coverage map of Norway, so it is worked out here, and it is shown as a guess, not a measurement.
 * <p>
 * The cells: 4G and 5G cells of the chosen network, put together into sites (the sectors and bands of one mast sit
 * within a few dozen metres of each other in the data), each taken as an antenna {@link #MAST_M} above the ground at
 * its estimated place. The signal: RSRP (the reference signal's power, what a phone's bars show) at 800 MHz, Norway's
 * coverage band (band 20), from a typical rural macro cell ({@link #RS_EIRP_DBM}). The loss on the way: free space or
 * the two-ray ground reflection, whichever is more (near the ground the second one rules: the signal falls 12 dB per
 * doubled distance instead of 6), plus the highest ridge in the line as one knife edge (ITU-R P.526) over a 4/3
 * earth, plus the clutter near the ground - buildings, trees, the pilot's own body - that a drone at height is above.
 * The best site of each point wins. Not in it: several ridges in a line (one counted: the truth is worse), the masts'
 * down-tilted beams, and interference - a drone high up hears many cells at once, which costs quality the bars don't show.
 */
public final class CellCoverage {
    public static final double FREQUENCY_MHZ = 800;
    static final double WAVELENGTH_M = 299.792458 / FREQUENCY_MHZ;
    /** The antenna's height above the ground at the site: a typical Norwegian rural mast. */
    public static final double MAST_M = 30;
    /** A 20 MHz macro cell's 46 dBm spread over its 1,200 subcarriers (15 dBm each), plus a 17 dBi sector antenna, less 2 dB of cable. */
    public static final double RS_EIRP_DBM = 30;
    /** The ground clutter: this much at hand height, none from {@link #CLUTTER_FREE_M} up (above the trees and roofs). */
    public static final double CLUTTER_DB = 20, CLUTTER_FREE_M = 30;
    /** RSRP limits: good above -95 dBm (video flows), fair to -105, weak to -115 (a phone still holds on), below that none. */
    public static final double GOOD_DBM = -95, FAIR_DBM = -105, WEAK_DBM = -115;
    public static final int UNKNOWN = -1, NONE = 0, WEAK = 1, FAIR = 2, GOOD = 3;
    /** Sites farther than this don't count; nor more than the nearest {@link #NEAREST} (a point needs one good one). */
    public static final double MAX_SITE_M = 20_000;
    static final int NEAREST = 16;
    /** The terrain along a line: a look every 40 m, between 8 and 120 looks. */
    static final double PATH_STEP_M = 40;
    static final int PATH_MIN = 8, PATH_MAX = 120;
    /** Sites closer than this to each other (same network) are one mast. */
    static final double SITE_MERGE_M = 60;
    /** The drone heights offered: on the ground (the pilot's own phone), a low flight, and the usual 120 m ceiling. */
    public static final int[] HEIGHTS_M = {2, 50, 120};
    public static final int DEFAULT_HEIGHT_M = 50;

    private CellCoverage() {
    }

    /** A mast as the data has it: where, whose (null when not one of the three), how many cells, whether 5G is there. */
    public record Site(double lat, double lon, OpenCellId.Operator operator, int cells, boolean nr) {}

    /** The chosen network's cells as masts: cells within {@link #SITE_MERGE_M} of each other go into one, at their middle. */
    public static List<Site> sites(List<OpenCellId.Cell> cells, OpenCellId.Operator only) {
        // each cell joins the first group of its network whose first cell is near, looked for in its own grid square and
        // the eight round it (squares as wide as the merge distance); else it starts a group
        double step = SITE_MERGE_M / 111_320.0;
        Map<Long, List<List<OpenCellId.Cell>>> squares = new HashMap<>();
        List<List<OpenCellId.Cell>> groups = new ArrayList<>();
        List<OpenCellId.Cell> sorted = new ArrayList<>(cells);
        sorted.sort((a, b) -> a.lat() != b.lat() ? Double.compare(a.lat(), b.lat()) : Double.compare(a.lon(), b.lon())); // the same groups every time
        for (OpenCellId.Cell c : sorted) {
            if (!only.has(c)) continue;
            OpenCellId.Operator op = OpenCellId.Operator.of(c.mcc(), c.net());
            long sy = (long) Math.floor(c.lat() / step), sx = (long) Math.floor(c.lon() * Math.cos(Math.toRadians(c.lat())) / step);
            List<OpenCellId.Cell> joined = null;
            for (long dy = -1; dy <= 1 && joined == null; dy++) {
                for (long dx = -1; dx <= 1 && joined == null; dx++) {
                    for (List<OpenCellId.Cell> g : squares.getOrDefault(square(sy + dy, sx + dx, op), List.of())) {
                        OpenCellId.Cell first = g.get(0);
                        if (Mercator.distanceM(first.lat(), first.lon(), c.lat(), c.lon()) <= SITE_MERGE_M) {
                            joined = g;
                            break;
                        }
                    }
                }
            }
            if (joined == null) {
                joined = new ArrayList<>();
                groups.add(joined);
                squares.computeIfAbsent(square(sy, sx, op), k -> new ArrayList<>()).add(joined);
            }
            joined.add(c);
        }
        List<Site> out = new ArrayList<>();
        for (List<OpenCellId.Cell> g : groups) {
            double lat = 0, lon = 0;
            boolean nr = false;
            for (OpenCellId.Cell c : g) {
                lat += c.lat();
                lon += c.lon();
                nr |= c.radio().equals("NR");
            }
            OpenCellId.Cell first = g.get(0);
            out.add(new Site(lat / g.size(), lon / g.size(), OpenCellId.Operator.of(first.mcc(), first.net()), g.size(), nr));
        }
        return out;
    }

    private static long square(long sy, long sx, OpenCellId.Operator op) {
        return ((sy * 10_000_000L) + sx) * 8 + (op == null ? 7 : op.ordinal());
    }

    /** Free-space loss in dB over distM at {@link #FREQUENCY_MHZ}. */
    public static double freeSpaceDb(double distM) {
        return 20 * Math.log10(Math.max(1, distM) / 1000) + 20 * Math.log10(FREQUENCY_MHZ) + 32.44;
    }

    /** The two-ray (plane earth) loss: 40 log d - 20 log (h1 h2), the antennas' heights above the ground between them. */
    public static double planeEarthDb(double distM, double h1M, double h2M) {
        return 40 * Math.log10(Math.max(1, distM)) - 20 * Math.log10(Math.max(1, h1M) * Math.max(1, h2M));
    }

    /** ITU-R P.526's single knife edge: the loss for the clearance parameter v (0 below -0.78: the line is clear). */
    public static double knifeEdgeDb(double v) {
        if (v <= -0.78) return 0;
        return 6.9 + 20 * Math.log10(Math.sqrt((v - 0.1) * (v - 0.1) + 1) + v - 0.1);
    }

    /** What the ground near the drone costs: {@link #CLUTTER_DB} at hand height, nothing above the trees. */
    public static double clutterDb(double droneM) {
        if (droneM >= CLUTTER_FREE_M) return 0;
        return CLUTTER_DB * (CLUTTER_FREE_M - Math.max(2, droneM)) / (CLUTTER_FREE_M - 2);
    }

    /**
     * The RSRP in dBm at a point from a site: the antenna {@link #MAST_M} over the ground at the site, the drone droneM
     * over the ground at the point. The terrain between is looked at every {@link #PATH_STEP_M}; the highest knife edge
     * counts. NaN when the ground at either end is unknown.
     */
    public static double rsrpDbm(double siteLat, double siteLon, double lat, double lon, double droneM, RadioHorizon.Terrain terrain) {
        double g0 = terrain.heightM(siteLat, siteLon), g1 = terrain.heightM(lat, lon);
        if (Double.isNaN(g0) || Double.isNaN(g1)) return Double.NaN;
        return rsrpDbm(siteLat, siteLon, g0, lat, lon, g1, droneM, Mercator.distanceM(siteLat, siteLon, lat, lon), terrain);
    }

    static double rsrpDbm(double siteLat, double siteLon, double g0, double lat, double lon, double g1, double droneM, double d,
                          RadioHorizon.Terrain terrain) {
        d = Math.max(d, 10);
        double antenna = g0 + MAST_M, drone = g1 + droneM;
        int n = (int) Math.max(PATH_MIN, Math.min(PATH_MAX, Math.round(d / PATH_STEP_M)));
        double worst = Double.NEGATIVE_INFINITY;
        for (int i = 1; i < n; i++) {
            double f = i / (double) n, x = f * d;
            double g = terrain.heightM(siteLat + (lat - siteLat) * f, siteLon + (lon - siteLon) * f);
            if (Double.isNaN(g)) continue;
            double bulge = x * (d - x) / (2 * RadioHorizon.EFFECTIVE_EARTH_M);
            double above = g + bulge - (antenna + (drone - antenna) * f); // how far the ground reaches over the line
            double v = above * Math.sqrt(2 * d / (WAVELENGTH_M * x * (d - x)));
            worst = Math.max(worst, v);
        }
        // the ground reflection sees each end's height over the other end's ground when that is lower (a mast on a hill over a valley)
        double h1 = MAST_M + Math.max(0, g0 - g1), h2 = droneM + Math.max(0, g1 - g0);
        double loss = Math.max(freeSpaceDb(d), planeEarthDb(d, h1, h2)) + knifeEdgeDb(worst) + clutterDb(droneM);
        return RS_EIRP_DBM - loss;
    }

    /** GOOD, FAIR, WEAK or NONE for an RSRP; UNKNOWN for NaN. */
    public static int level(double rsrpDbm) {
        if (Double.isNaN(rsrpDbm)) return UNKNOWN;
        if (rsrpDbm >= GOOD_DBM) return GOOD;
        if (rsrpDbm >= FAIR_DBM) return FAIR;
        if (rsrpDbm >= WEAK_DBM) return WEAK;
        return NONE;
    }

    /** Whether the cell data covers a place (its box was fetched, or the country file has it). */
    public interface Known {
        boolean at(double lat, double lon);
    }

    /**
     * The estimate over an area: cols x rows points, evenly spread in Mercator (row 0 the northernmost), each a level;
     * the area's corners in zoom-0 world pixels so it is drawn where it belongs at any zoom.
     */
    public record Grid(double x0, double y0, double x1, double y1, int cols, int rows, byte[] level, List<Site> sites, double droneM,
                       OpenCellId.Operator operator, boolean terrainMissing) {
        public int at(int col, int row) {
            return level[row * cols + col];
        }

        /** How many of the known points are at each level, NONE to GOOD. */
        public int[] counts() {
            int[] c = new int[4];
            for (byte b : level) if (b >= 0) c[b]++;
            return c;
        }
    }

    /**
     * The estimate for an area {minLat, maxLat, minLon, maxLon}: a point counts only where the cell data is known;
     * the sites may reach in from outside the area. Parallel over rows; null when cancelled.
     */
    public static Grid predict(double[] area, int cols, int rows, List<Site> sites, double droneM, OpenCellId.Operator operator,
                               RadioHorizon.Terrain terrain, Known known, BooleanSupplier cancelled) {
        double x0 = Mercator.x(area[2], 0), x1 = Mercator.x(area[3], 0), y0 = Mercator.y(area[1], 0), y1 = Mercator.y(area[0], 0);
        int k = sites.size();
        double[] sLat = new double[k], sLon = new double[k], sGround = new double[k];
        for (int i = 0; i < k; i++) {
            sLat[i] = sites.get(i).lat();
            sLon[i] = sites.get(i).lon();
            sGround[i] = terrain.heightM(sLat[i], sLon[i]);
        }
        byte[] level = new byte[cols * rows];
        Arrays.fill(level, (byte) UNKNOWN);
        boolean[] missing = {false};
        IntStream.range(0, rows).parallel().forEach(r -> {
            if (cancelled != null && cancelled.getAsBoolean()) return;
            double lat = Mercator.lat(y0 + (r + 0.5) * (y1 - y0) / rows, 0);
            double cos = Math.cos(Math.toRadians(lat));
            double[] dist = new double[k];
            Integer[] order = new Integer[k];
            for (int c = 0; c < cols; c++) {
                double lon = Mercator.lon(x0 + (c + 0.5) * (x1 - x0) / cols, 0);
                if (!known.at(lat, lon)) continue;
                double ground = terrain.heightM(lat, lon);
                if (Double.isNaN(ground)) {
                    missing[0] = true;
                    continue;
                }
                int m = 0;
                for (int i = 0; i < k; i++) {
                    double e = (sLon[i] - lon) * 111_320.0 * cos, nn = (sLat[i] - lat) * 111_320.0;
                    double dd = Math.sqrt(e * e + nn * nn);
                    if (dd <= MAX_SITE_M && !Double.isNaN(sGround[i])) {
                        dist[i] = dd;
                        order[m++] = i;
                    }
                }
                Arrays.sort(order, 0, m, (a, b) -> Double.compare(dist[a], dist[b]));
                double best = Double.NEGATIVE_INFINITY;
                for (int j = 0; j < Math.min(m, NEAREST); j++) {
                    int i = order[j];
                    best = Math.max(best, rsrpDbm(sLat[i], sLon[i], sGround[i], lat, lon, ground, droneM, dist[i], terrain));
                }
                level[r * cols + c] = (byte) (m == 0 ? NONE : level(best));
            }
        });
        if (cancelled != null && cancelled.getAsBoolean()) return null;
        return new Grid(x0, y0, x1, y1, cols, rows, level, sites, droneM, operator, missing[0]);
    }

    /**
     * The terrain under an area, from the same height tiles as the RADIO HORIZON (Terrarium, zoom 12, kept on disk):
     * fetched now, on the caller's thread. Missing tiles leave NaN heights there.
     */
    public static RadioHorizon.Data loadTerrain(TileCache cache, double[] area, BooleanSupplier cancelled) {
        double cLat = (area[0] + area[1]) / 2, cLon = (area[2] + area[3]) / 2;
        RadioHorizon.Data data = new RadioHorizon.Data(cLat, cLon, !TileSource.inNorway(cLat, cLon));
        int z = RadioHorizon.TERRAIN_ZOOM, n = 1 << z;
        int tx0 = (int) Math.floor(Mercator.x(area[2], z) / Mercator.TILE), tx1 = (int) Math.floor(Mercator.x(area[3], z) / Mercator.TILE);
        int ty0 = Math.max(0, (int) Math.floor(Mercator.y(area[1], z) / Mercator.TILE));
        int ty1 = Math.min(n - 1, (int) Math.floor(Mercator.y(area[0], z) / Mercator.TILE));
        for (int y = ty0; y <= ty1; y++) {
            for (int x = tx0; x <= tx1; x++) {
                if (cancelled.getAsBoolean()) return data;
                String url = RadioHorizon.TERRAIN_URL.replace("{z}", Integer.toString(z)).replace("{x}", Integer.toString(x)).replace("{y}", Integer.toString(y));
                BufferedImage img = cache.fetchNow(RadioHorizon.TERRAIN_ID, url, z, x, y);
                if (img != null) data.putTerrain(x, y, img);
            }
        }
        return data;
    }

    /** An area grown by metres on every side. */
    public static double[] grow(double[] area, double m) {
        double dLat = m / 111_320.0, dLon = m / (111_320.0 * Math.cos(Math.toRadians((area[0] + area[1]) / 2)));
        return new double[]{area[0] - dLat, area[1] + dLat, area[2] - dLon, area[3] + dLon};
    }
}
