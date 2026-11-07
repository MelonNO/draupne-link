package de.droiddrone.pc.map;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * RADIO HORIZON and GCS SEARCH (1.0.39): where an aircraft can see an antenna. Along every degree of bearing, out to
 * 30 km (nothing reaches further, whatever the heights), a straight line from the antenna to the aircraft over a 4/3
 * earth (the usual radio refraction) must clear the terrain - a hill blocks outright - and the aircraft must be above
 * the ground where it is. Forest does not block outright: the metres of canopy the line runs through cost signal by
 * the frequency, ITU-R's fitted in-leaf model (FITU-R, 0.39 f^0.39 d^0.25 dB; Norway's spruce is in leaf all year),
 * and past {@link #MAX_FOLIAGE_LOSS_DB} the aircraft counts as out of sight: about 9 m of trees at 5.8 GHz, 37 m at
 * 2.4 GHz, 200 m at 800 MHz. (Weissberger's model, tried first, let 5.8 GHz through 45 m: the pilot found that far
 * too generous.)
 * <p>
 * RADIO HORIZON: one mast, its height above the ground at the mast; the aircraft's altitude above the ground under
 * the aircraft, wherever it is (it follows the terrain - the pilot: "above ground from the aircraft"). GCS SEARCH: the pilot's wanted area, sampled 5 x 5; the aircraft at its
 * height above the ground at each of those points, a mast of the set height on every 100 m cell within 30 km of the
 * area's centre; a cell's score is the share of the area's points that see it (the line of sight works both ways).
 * <p>
 * The terrain: the open Terrarium height tiles (AWS, global; Norway from Kartverket's model). The trees: read off the
 * map's own pictures, since the land-cover datasets got Norway's forest wrong - Kartverket's topo map at zoom 15
 * (2.4 m a pixel) over Norway, where forest is a yellow-green whatever the hill shading; OpenStreetMap's forest
 * green elsewhere at zoom 13 (its tile policy: no mass downloads). Lower zooms lose forest under the contour lines
 * and names (measured: 77 % forest at zoom 11, 85 % at 14, 90 % at 15 over the same woods).
 */
public final class RadioHorizon {
    /** No signal is shown beyond this, whatever the heights. */
    public static final double MAX_RANGE_M = 30_000;
    /** The 4/3 earth radius radio planning uses for normal refraction. */
    static final double EFFECTIVE_EARTH_M = 6371008.8 * 4 / 3;
    private static final double EARTH_M = 6371008.8;
    /** More loss in the trees than this and the aircraft is out of sight (a typical link's fade margin). */
    public static final double MAX_FOLIAGE_LOSS_DB = 20;
    public static final int BEARINGS = 360;
    static final double STEP_M = 20;
    /** Forest looked at this often along the line (twice a step), so a thin belt of trees counts by its width. */
    static final int FOREST_LOOKS = 2;
    public static final int TERRAIN_ZOOM = 12;
    public static final int FOREST_ZOOM_NORWAY = 15, FOREST_ZOOM_OSM = 13;
    static final String TERRAIN_ID = "terrarium";
    static final String TERRAIN_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png";
    public static final int FOREST = 1, OPEN = 0, UNKNOWN = -1;
    /** GCS SEARCH: the heat map's cell, and how many points of the wanted area each way. */
    public static final double SEARCH_CELL_M = 100;
    public static final int SEARCH_POINTS = 5;
    /** GCS SEARCH sweeps every quarter degree: 130 m apart at 30 km, so the 100 m cells far out are not one bearing's copies. */
    static final int SEARCH_BEARINGS = 4 * BEARINGS;

    private RadioHorizon() {
    }

    /** The ground height in metres, NaN when unknown. */
    public interface Terrain {
        double heightM(double lat, double lon);
    }

    /** FOREST, OPEN or UNKNOWN. */
    public interface Cover {
        int at(double lat, double lon);
    }

    /**
     * Mast and aircraft height above the ground (the mast above the ground where it stands, the aircraft above the
     * ground under it: RADIO HORIZON wherever it flies, GCS SEARCH over the wanted area's points); the trees' height where the map shows forest; the link's frequency,
     * which decides how far it gets through the trees.
     */
    public record Settings(double mastM, double aircraftM, double treeM, double frequencyMHz) {}

    /**
     * Per bearing (0 = north, one degree each), the stretches the aircraft is seen in, as {from, to} metre pairs.
     * terrainMissing: some heights were unknown (taken as the mast's ground); forestUnknownPct: of the points looked
     * at, how many had no forest map yet (taken as open); forestDepthM: the canopy the link gets through at its frequency.
     */
    public record Result(double lat, double lon, double groundM, Settings settings, float[][] spans, double smoothEarthM,
                         boolean terrainMissing, int forestUnknownPct, double forestDepthM) {
        /** The furthest seen distance on a bearing, 0 when none. */
        public double reachM(int bearing) {
            float[] s = spans[Math.floorMod(bearing, BEARINGS)];
            return s.length == 0 ? 0 : s[s.length - 1];
        }
    }

    /**
     * GCS SEARCH: the heat map round the wanted area's centre, size x size cells of {@link #SEARCH_CELL_M} (row 0 the
     * northernmost), each the share 0..1 of the area's points that see a mast there; NaN outside the 30 km circle.
     * best: the highest-scoring cell, the one nearest the centre among equals.
     */
    public record Search(double lat, double lon, double minLat, double maxLat, double minLon, double maxLon, Settings settings,
                         int size, float[] score, double bestLat, double bestLon, float bestScore, boolean terrainMissing,
                         int forestUnknownPct, double forestDepthM) {
        /** A cell's centre {lat, lon}. */
        public double[] cellCentre(int cx, int cy) {
            return offset(lat, lon, (cx + 0.5 - size / 2.0) * SEARCH_CELL_M, (size / 2.0 - cy - 0.5) * SEARCH_CELL_M);
        }

        public float at(int cx, int cy) {
            return score[cy * size + cx];
        }
    }

    /** The radio horizon over a smooth 4/3 earth: the antenna's horizon plus the aircraft's, before the 30 km cap. */
    public static double smoothEarthM(double mastM, double aircraftM) {
        return Math.sqrt(2 * EFFECTIVE_EARTH_M * Math.max(0, mastM)) + Math.sqrt(2 * EFFECTIVE_EARTH_M * Math.max(0, aircraftM));
    }

    /** FITU-R in leaf: the loss through depthM of trees, 0.39 f^0.39 d^0.25 dB with f in MHz. */
    public static double foliageLossDb(double frequencyMHz, double depthM) {
        if (depthM <= 0) return 0;
        return 0.39 * Math.pow(Math.max(1, frequencyMHz), 0.39) * Math.pow(depthM, 0.25);
    }

    /** The canopy depth whose loss is {@link #MAX_FOLIAGE_LOSS_DB} at this frequency. */
    public static double forestDepthM(double frequencyMHz) {
        return Math.pow(MAX_FOLIAGE_LOSS_DB / (0.39 * Math.pow(Math.max(1, frequencyMHz), 0.39)), 4);
    }

    /** A point east and north of another by metres (flat over 30 km is close enough for a grid). */
    public static double[] offset(double lat, double lon, double eastM, double northM) {
        return new double[]{lat + northM / 111_320.0, lon + eastM / (111_320.0 * Math.cos(Math.toRadians(lat)))};
    }

    /** The point distM from (lat, lon) on a bearing, over the sphere: {lat, lon}. */
    public static double[] destination(double lat, double lon, double bearingDeg, double distM) {
        double phi1 = Math.toRadians(lat), lambda1 = Math.toRadians(lon), theta = Math.toRadians(bearingDeg), delta = distM / EARTH_M;
        double phi2 = Math.asin(Math.sin(phi1) * Math.cos(delta) + Math.cos(phi1) * Math.sin(delta) * Math.cos(theta));
        double lambda2 = lambda1 + Math.atan2(Math.sin(theta) * Math.sin(delta) * Math.cos(phi1), Math.cos(delta) - Math.sin(phi1) * Math.sin(phi2));
        return new double[]{Math.toDegrees(phi2), Math.toDegrees(lambda2)};
    }

    // ---------------------------------------------------------------- the sweep along one bearing

    /**
     * One bearing's line of sight from a source to every sample out to 30 km. The ground and the treetops along it,
     * the earth's bulge taken off; then for each sample the line to the target there: the terrain must stay under it
     * (the steepest ground slope so far), and the canopy it runs under is summed - a canopy sample catches every line
     * flatter than its own slope from the source, so a Fenwick tree over those slopes sums it in log time.
     */
    static final class Sweep {
        final int n = (int) Math.round(MAX_RANGE_M / STEP_M);
        final double[] drop = new double[n + 1], ground = new double[n + 1], top = new double[n + 1], wood = new double[n + 1];
        final double[] slopes = new double[n + 1], sorted = new double[n + 1], fenwick = new double[n + 2];
        final int[] rank = new int[n + 1];
        final boolean[] seen = new boolean[n + 1];
        final double[] lat = new double[n + 1], lon = new double[n + 1];
        long looked, unknown;
        boolean missing;

        Sweep() {
            for (int i = 1; i <= n; i++) {
                double d = i * STEP_M;
                drop[i] = d * d / (2 * EFFECTIVE_EARTH_M);
            }
        }

        /** The ground (bulge off), treetops (bulge off, -inf without trees) and canopy metres of each sample out from (lat0, lon0). */
        void fill(double lat0, double lon0, double bearing, double fallbackGround, Settings s, Terrain terrain, Cover cover) {
            double phi1 = Math.toRadians(lat0), lambda1 = Math.toRadians(lon0);
            double sinPhi1 = Math.sin(phi1), cosPhi1 = Math.cos(phi1);
            double theta = Math.toRadians(bearing), sinT = Math.sin(theta), cosT = Math.cos(theta);
            for (int i = 1; i <= n; i++) {
                double g = Double.NaN;
                int forest = 0, looks = 0;
                for (int k = FOREST_LOOKS - 1; k >= 0; k--) { // the sample's own point last: its height counts
                    double d = i * STEP_M - k * STEP_M / FOREST_LOOKS;
                    double delta = d / EARTH_M, sinD = Math.sin(delta), cosD = Math.cos(delta);
                    double sinPhi2 = sinPhi1 * cosD + cosPhi1 * sinD * cosT;
                    double phi2 = Math.asin(sinPhi2);
                    double lambda2 = lambda1 + Math.atan2(sinT * sinD * cosPhi1, cosD - sinPhi1 * sinPhi2);
                    double pLat = Math.toDegrees(phi2), pLon = Math.toDegrees(lambda2);
                    int c = s.treeM() > 0 ? cover.at(pLat, pLon) : OPEN;
                    looks++;
                    if (c == FOREST) forest++;
                    if (c == UNKNOWN) unknown++;
                    if (k == 0) {
                        g = terrain.heightM(pLat, pLon);
                        lat[i] = pLat;
                        lon[i] = pLon;
                    }
                }
                looked += looks;
                if (Double.isNaN(g)) {
                    missing = true;
                    g = fallbackGround;
                }
                ground[i] = g - drop[i];
                wood[i] = forest * STEP_M / looks;
                top[i] = forest > 0 ? g + s.treeM() - drop[i] : Double.NEGATIVE_INFINITY;
            }
        }

        /**
         * seen[i]: a target at height target[i] above sea level (a NaN: none there) sees the source, at most maxWoodM
         * of canopy in the line.
         */
        void run(double source, double[] target, double maxWoodM) {
            int m = 0;
            for (int i = 1; i <= n; i++) {
                if (top[i] == Double.NEGATIVE_INFINITY) continue;
                slopes[i] = (top[i] - source) / (i * STEP_M);
                sorted[m++] = slopes[i];
            }
            Arrays.sort(sorted, 0, m);
            for (int i = 1; i <= n; i++) if (top[i] != Double.NEGATIVE_INFINITY) rank[i] = Arrays.binarySearch(sorted, 0, m, slopes[i]) + 1;
            Arrays.fill(fenwick, 0, m + 2, 0);
            double inserted = 0, maxGround = Double.NEGATIVE_INFINITY;
            for (int i = 1; i <= n; i++) {
                double d = i * STEP_M;
                if (top[i] != Double.NEGATIVE_INFINITY) { // this sample's canopy joins: it can catch its own target too
                    for (int r = rank[i]; r <= m; r += r & -r) fenwick[r] += wood[i];
                    inserted += wood[i];
                }
                double t = target[i];
                boolean ok = !Double.isNaN(t) && t >= ground[i] + drop[i];
                if (ok) {
                    double slope = (t - drop[i] - source) / d;
                    if (slope < maxGround) ok = false; // a hill in the line
                    else if (m > 0) {
                        // the canopy with a slope above the line's: everything inserted minus what is at or below it
                        int below = upperBound(sorted, m, slope);
                        double atOrBelow = 0;
                        for (int r = below; r > 0; r -= r & -r) atOrBelow += fenwick[r];
                        ok = inserted - atOrBelow <= maxWoodM + 1e-9;
                    }
                }
                seen[i] = ok;
                maxGround = Math.max(maxGround, (ground[i] - source) / d);
            }
        }

        private static int upperBound(double[] a, int m, double v) {
            int lo = 0, hi = m;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (a[mid] <= v) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }
    }

    public static Result compute(double lat, double lon, Settings s, Terrain terrain, Cover cover) {
        boolean missing = false;
        double h0 = terrain.heightM(lat, lon);
        if (Double.isNaN(h0)) {
            missing = true;
            h0 = 0;
        }
        double antenna = h0 + s.mastM();
        double maxWood = s.treeM() > 0 ? forestDepthM(s.frequencyMHz()) : Double.POSITIVE_INFINITY;
        Sweep sweep = new Sweep();
        double[] target = new double[sweep.n + 1];
        float[][] spans = new float[BEARINGS][];
        float[] buf = new float[2 * sweep.n + 2];
        for (int b = 0; b < BEARINGS; b++) {
            sweep.fill(lat, lon, b, h0, s, terrain, cover);
            for (int i = 1; i <= sweep.n; i++) target[i] = sweep.ground[i] + sweep.drop[i] + s.aircraftM(); // above the ground under it
            sweep.run(antenna, target, maxWood);
            int count = 0;
            boolean inSpan = false;
            for (int i = 1; i <= sweep.n; i++) {
                double d = i * STEP_M;
                if (sweep.seen[i] && !inSpan) {
                    buf[count++] = (float) Math.max(0, d - STEP_M / 2);
                    inSpan = true;
                } else if (!sweep.seen[i] && inSpan) {
                    buf[count++] = (float) (d - STEP_M / 2);
                    inSpan = false;
                }
            }
            if (inSpan) buf[count++] = (float) MAX_RANGE_M;
            spans[b] = Arrays.copyOf(buf, count);
        }
        missing |= sweep.missing;
        int pct = sweep.looked == 0 ? 0 : (int) Math.round(100.0 * sweep.unknown / sweep.looked);
        return new Result(lat, lon, h0, s, spans, smoothEarthM(s.mastM(), s.aircraftM()), missing, pct, forestDepthM(s.frequencyMHz()));
    }

    /** The radius to load round the wanted area's centre: 30 km from its farthest point. */
    public static double searchRadiusM(double minLat, double maxLat, double minLon, double maxLon) {
        double cLat = (minLat + maxLat) / 2, cLon = (minLon + maxLon) / 2;
        return MAX_RANGE_M + Mercator.distanceM(cLat, cLon, maxLat, maxLon);
    }

    /**
     * GCS SEARCH: from each of the wanted area's 5 x 5 points (the aircraft over it), which cells within 30 km of its
     * centre see it with a mast on them; the share of points per cell. progress gets each point done; cancelled stops it (null).
     */
    public static Search search(double minLat, double maxLat, double minLon, double maxLon, Settings s, Terrain terrain, Cover cover,
                                IntConsumer progress, BooleanSupplier cancelled) {
        double cLat = (minLat + maxLat) / 2, cLon = (minLon + maxLon) / 2;
        int size = (int) Math.ceil(2 * MAX_RANGE_M / SEARCH_CELL_M);
        double cosLat = Math.cos(Math.toRadians(cLat));
        // every cell's centre and its ground, the mast on top of it
        double[] cellLat = new double[size * size], cellLon = new double[size * size];
        boolean[] inside = new boolean[size * size];
        for (int cy = 0; cy < size; cy++) {
            for (int cx = 0; cx < size; cx++) {
                double east = (cx + 0.5 - size / 2.0) * SEARCH_CELL_M, north = (size / 2.0 - cy - 0.5) * SEARCH_CELL_M;
                int k = cy * size + cx;
                double[] ll = offset(cLat, cLon, east, north);
                cellLat[k] = ll[0];
                cellLon[k] = ll[1];
                inside[k] = Math.hypot(east, north) <= MAX_RANGE_M;
            }
        }
        List<double[]> points = new ArrayList<>();
        for (int py = 0; py < SEARCH_POINTS; py++) {
            for (int px = 0; px < SEARCH_POINTS; px++) {
                points.add(new double[]{minLat + (maxLat - minLat) * py / (SEARCH_POINTS - 1), minLon + (maxLon - minLon) * px / (SEARCH_POINTS - 1)});
            }
        }
        double maxWood = s.treeM() > 0 ? forestDepthM(s.frequencyMHz()) : Double.POSITIVE_INFINITY;
        int[] hits = new int[size * size];
        AtomicInteger done = new AtomicInteger();
        boolean[] missing = {false};
        long[] counts = {0, 0};
        double fallback = Double.isNaN(terrain.heightM(cLat, cLon)) ? 0 : terrain.heightM(cLat, cLon);
        IntStream.range(0, points.size()).parallel().forEach(p -> {
            if (cancelled != null && cancelled.getAsBoolean()) return;
            double tLat = points.get(p)[0], tLon = points.get(p)[1];
            double g0 = terrain.heightM(tLat, tLon);
            boolean miss = Double.isNaN(g0);
            if (miss) g0 = fallback;
            double aircraft = g0 + s.aircraftM();
            Sweep sweep = new Sweep();
            double[] target = new double[sweep.n + 1];
            boolean[][] seen = new boolean[SEARCH_BEARINGS][];
            for (int b = 0; b < SEARCH_BEARINGS; b++) {
                sweep.fill(tLat, tLon, b * 360.0 / SEARCH_BEARINGS, g0, s, terrain, cover);
                for (int i = 1; i <= sweep.n; i++) target[i] = sweep.ground[i] + sweep.drop[i] + s.mastM(); // the mast on the ground there
                sweep.run(aircraft, target, maxWood);
                seen[b] = sweep.seen.clone();
            }
            // each cell takes the sample nearest it: its bearing and distance from this point
            boolean[] mine = new boolean[size * size];
            double tCos = Math.cos(Math.toRadians(tLat));
            for (int k = 0; k < mine.length; k++) {
                if (!inside[k]) continue;
                double east = (cellLon[k] - tLon) * 111_320.0 * tCos, north = (cellLat[k] - tLat) * 111_320.0;
                double dist = Math.hypot(east, north);
                if (dist > MAX_RANGE_M) continue;
                int i = (int) Math.round(dist / STEP_M);
                if (i == 0) {
                    mine[k] = true;
                    continue;
                }
                int b = (int) Math.floorMod(Math.round(Math.toDegrees(Math.atan2(east, north)) * SEARCH_BEARINGS / 360.0), SEARCH_BEARINGS);
                mine[k] = seen[b][Math.min(i, sweep.n)];
            }
            synchronized (hits) {
                for (int k = 0; k < mine.length; k++) if (mine[k]) hits[k]++;
                missing[0] |= miss || sweep.missing;
                counts[0] += sweep.looked;
                counts[1] += sweep.unknown;
            }
            if (progress != null) progress.accept(done.incrementAndGet());
        });
        float[] score = new float[size * size];
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int k = 0; k < score.length; k++) {
            if (!inside[k]) {
                score[k] = Float.NaN;
                continue;
            }
            score[k] = hits[k] / (float) points.size();
            double dist = Math.hypot((cellLon[k] - cLon) * 111_320.0 * cosLat, (cellLat[k] - cLat) * 111_320.0);
            if (best < 0 || score[k] > score[best] || (score[k] == score[best] && dist < bestDist)) {
                best = k;
                bestDist = dist;
            }
        }
        int pct = counts[0] == 0 ? 0 : (int) Math.round(100.0 * counts[1] / counts[0]);
        return new Search(cLat, cLon, minLat, maxLat, minLon, maxLon, s, size, score, cellLat[best], cellLon[best], score[best],
                missing[0], pct, forestDepthM(s.frequencyMHz()));
    }

    /** A Terrarium pixel's height: (R * 256 + G + B / 256) - 32768 metres. */
    public static double terrariumHeight(int rgb) {
        return ((rgb >> 16) & 0xff) * 256.0 + ((rgb >> 8) & 0xff) + (rgb & 0xff) / 256.0 - 32768;
    }

    /**
     * Forest on the map picture. Kartverket's topo: a yellow-green (#d2e096 in the light, darker in the hill shade)
     * - green above red, far above blue; fields (#f1ecb1) and bare mountain (#e8d7aa) have red on top. OpenStreetMap:
     * its forest green #add19e and the darker tree marks, not the lighter meadow, park or scrub greens.
     */
    public static boolean isForest(int rgb, boolean osm) {
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        if (osm) return r < 190 && g - r >= 25 && g - r <= 60 && g - b >= 35 && g - b <= 75;
        return g > r + 6 && g - b > 40 && g - b > g - r;
    }

    // ---------------------------------------------------------------- the map data round a mast

    /** Called as the data comes in: the terrain's tiles first, then the forest's from the middle outwards. */
    public interface Progress {
        void update(Data data, int terrainDone, int terrainTotal, int forestDone, int forestTotal);
    }

    /** Terrain and forest round a point, filled by {@link #load}; safe to read while it fills. */
    public static final class Data implements Terrain, Cover {
        private static final int FOREST_CELLS = 128; // a tile's forest kept at half its pixels: 2 of 4 forest = forest
        final double lat, lon;
        final int forestZoom;
        final boolean osm;
        private final Map<Long, short[]> terrain = new ConcurrentHashMap<>(); // decimetres
        private final Map<Long, long[]> forest = new ConcurrentHashMap<>();

        Data(double lat, double lon, boolean osm) {
            this.lat = lat;
            this.lon = lon;
            this.osm = osm;
            this.forestZoom = osm ? FOREST_ZOOM_OSM : FOREST_ZOOM_NORWAY;
        }

        public double lat() {
            return lat;
        }

        public double lon() {
            return lon;
        }

        private static long key(int x, int y) {
            return ((long) x << 32) | (y & 0xffffffffL);
        }

        void putTerrain(int x, int y, BufferedImage img) {
            int w = img.getWidth(), h = img.getHeight();
            short[] dm = new short[Mercator.TILE * Mercator.TILE];
            for (int py = 0; py < Mercator.TILE; py++) {
                for (int px = 0; px < Mercator.TILE; px++) {
                    double m = terrariumHeight(img.getRGB(px * w / Mercator.TILE, py * h / Mercator.TILE));
                    dm[py * Mercator.TILE + px] = (short) Math.max(-32767, Math.min(32767, Math.round(m * 10)));
                }
            }
            terrain.put(key(x, y), dm);
        }

        void putForest(int x, int y, BufferedImage img) {
            int w = img.getWidth(), h = img.getHeight();
            long[] bits = new long[FOREST_CELLS * FOREST_CELLS / 64];
            for (int cy = 0; cy < FOREST_CELLS; cy++) {
                for (int cx = 0; cx < FOREST_CELLS; cx++) {
                    int x0 = cx * w / FOREST_CELLS, x1 = Math.max(x0 + 1, (cx + 1) * w / FOREST_CELLS);
                    int y0 = cy * h / FOREST_CELLS, y1 = Math.max(y0 + 1, (cy + 1) * h / FOREST_CELLS);
                    int yes = 0, all = 0;
                    for (int py = y0; py < y1; py++) {
                        for (int px = x0; px < x1; px++) {
                            all++;
                            if (isForest(img.getRGB(px, py), osm)) yes++;
                        }
                    }
                    if (yes * 2 >= all) { // a contour line or a letter through the wood stays wood
                        int i = cy * FOREST_CELLS + cx;
                        bits[i >> 6] |= 1L << (i & 63);
                    }
                }
            }
            forest.put(key(x, y), bits);
        }

        private double pixel(int wx, int wy) {
            short[] t = terrain.get(key(Math.floorDiv(wx, Mercator.TILE), Math.floorDiv(wy, Mercator.TILE)));
            if (t == null) return Double.NaN;
            return t[Math.floorMod(wy, Mercator.TILE) * Mercator.TILE + Math.floorMod(wx, Mercator.TILE)] / 10.0;
        }

        @Override
        public double heightM(double lat, double lon) {
            double fx = Mercator.x(lon, TERRAIN_ZOOM) - 0.5, fy = Mercator.y(lat, TERRAIN_ZOOM) - 0.5;
            int x = (int) Math.floor(fx), y = (int) Math.floor(fy);
            double ax = fx - x, ay = fy - y;
            double a = pixel(x, y), b = pixel(x + 1, y), c = pixel(x, y + 1), d = pixel(x + 1, y + 1);
            return (a * (1 - ax) + b * ax) * (1 - ay) + (c * (1 - ax) + d * ax) * ay; // NaN when a tile is missing
        }

        @Override
        public int at(double lat, double lon) {
            double fx = Mercator.x(lon, forestZoom), fy = Mercator.y(lat, forestZoom);
            int tx = (int) Math.floor(fx / Mercator.TILE), ty = (int) Math.floor(fy / Mercator.TILE);
            long[] bits = forest.get(key(tx, ty));
            if (bits == null) return UNKNOWN;
            int cx = (int) ((fx - tx * Mercator.TILE) * FOREST_CELLS / Mercator.TILE);
            int cy = (int) ((fy - ty * Mercator.TILE) * FOREST_CELLS / Mercator.TILE);
            int i = Math.min(FOREST_CELLS - 1, cy) * FOREST_CELLS + Math.min(FOREST_CELLS - 1, cx);
            return (bits[i >> 6] & (1L << (i & 63))) != 0 ? FOREST : OPEN;
        }
    }

    /** The tiles at zoom z within 30 km of the point, nearest first, as {x, y, distance}. */
    static List<int[]> tilesAround(double lat, double lon, int z) {
        return tilesAround(lat, lon, z, MAX_RANGE_M);
    }

    static List<int[]> tilesAround(double lat, double lon, int z, double radiusM) {
        double mx = Mercator.x(lon, z), my = Mercator.y(lat, z);
        double mpp = Mercator.metersPerPixel(lat, z);
        double reachPx = (radiusM + 500) / mpp;
        int x0 = (int) Math.floor((mx - reachPx) / Mercator.TILE), x1 = (int) Math.floor((mx + reachPx) / Mercator.TILE);
        int y0 = Math.max(0, (int) Math.floor((my - reachPx) / Mercator.TILE));
        int y1 = Math.min((1 << z) - 1, (int) Math.floor((my + reachPx) / Mercator.TILE));
        List<int[]> tiles = new ArrayList<>();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double nx = Math.max(x * Mercator.TILE, Math.min(mx, (x + 1) * Mercator.TILE)); // the tile's nearest point
                double ny = Math.max(y * Mercator.TILE, Math.min(my, (y + 1) * Mercator.TILE));
                double dist = Math.hypot(nx - mx, ny - my);
                if (dist <= reachPx) {
                    tiles.add(new int[]{x, y, (int) Math.round(dist)});
                }
            }
        }
        tiles.sort(Comparator.comparingInt(t -> t[2]));
        return tiles;
    }

    public static Data load(TileCache cache, double lat, double lon, Progress progress, BooleanSupplier cancelled) {
        return load(cache, lat, lon, MAX_RANGE_M, progress, cancelled);
    }

    /**
     * Fetches the terrain, then the forest map, within radiusM of the point (from disk when it was fetched before),
     * telling progress as it goes; stops early when cancelled. Over Norway the forest comes from Kartverket's topo map.
     */
    public static Data load(TileCache cache, double lat, double lon, double radiusM, Progress progress, BooleanSupplier cancelled) {
        boolean osm = !TileSource.inNorway(lat, lon);
        Data data = new Data(lat, lon, osm);
        List<int[]> terrainTiles = tilesAround(lat, lon, TERRAIN_ZOOM, radiusM);
        int forestZoom = data.forestZoom;
        List<int[]> forestTiles = tilesAround(lat, lon, forestZoom, radiusM);
        TileSource coverSource = osm ? TileSource.OSM : TileSource.KARTVERKET_TOPO;
        ExecutorService pool = Executors.newFixedThreadPool(osm ? 1 : 4, r -> {
            Thread t = new Thread(r, "radioHorizon");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        try {
            CompletionService<Void> done = new ExecutorCompletionService<>(pool);
            for (int[] t : terrainTiles) {
                done.submit(() -> {
                    if (cancelled.getAsBoolean()) return null;
                    String url = TERRAIN_URL.replace("{z}", Integer.toString(TERRAIN_ZOOM))
                            .replace("{x}", Integer.toString(t[0])).replace("{y}", Integer.toString(t[1]));
                    BufferedImage img = cache.fetchNow(TERRAIN_ID, url, TERRAIN_ZOOM, t[0], t[1]);
                    if (img != null) data.putTerrain(t[0], t[1], img);
                    return null;
                });
            }
            for (int i = 0; i < terrainTiles.size(); i++) {
                done.take();
                if (cancelled.getAsBoolean()) return data;
            }
            progress.update(data, terrainTiles.size(), terrainTiles.size(), 0, forestTiles.size());
            for (int[] t : forestTiles) {
                done.submit(() -> {
                    if (cancelled.getAsBoolean()) return null;
                    BufferedImage img = cache.fetchNow(coverSource, forestZoom, t[0], t[1]);
                    if (img != null) data.putForest(t[0], t[1], img);
                    return null;
                });
            }
            for (int i = 0; i < forestTiles.size(); i++) {
                done.take();
                if (cancelled.getAsBoolean()) return data;
                if ((i + 1) % 50 == 0 || i + 1 == forestTiles.size()) {
                    progress.update(data, terrainTiles.size(), terrainTiles.size(), i + 1, forestTiles.size());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return data;
    }
}
