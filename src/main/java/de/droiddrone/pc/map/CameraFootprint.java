package de.droiddrone.pc.map;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The camera's view on the ground (1.0.39, for the map and STANAG 4609's frame centre and corners): rays through the
 * picture's edges from the drone, turned by the camera's tilt on the frame and the drone's attitude, followed until
 * they meet the terrain (the same Terrarium heights as RADIO HORIZON, over a 4/3 earth's curve - light bends less
 * than radio, but over a few km the difference is under a metre). A ray that never meets the ground within
 * {@link #MAX_RANGE_M} (sky, or the horizon far off) ends there, on its bearing: the outline is then "what the camera
 * sees of the ground out to 3 km". Pure geometry: no state, safe from any thread.
 */
public final class CameraFootprint {
    /** Ground further than this is not drawn, nor sent as a corner (an FPV camera's horizon is often far off). */
    public static final double MAX_RANGE_M = 3000;
    /** Rays per edge of the picture for the outline (the corners among them). */
    static final int PER_EDGE = 8;
    private static final double EARTH_M = 6371008.8 * 4 / 3;

    private CameraFootprint() {
    }

    /**
     * Where the camera is and how it points. altM above mean sea level; heading 0..360 from north; pitch nose up
     * positive, roll right wing down positive (MISB's sense; Betaflight's pitch is the other way round); tiltDeg the
     * camera's tilt up on the frame (an FPV camera's uptilt); hfov / vfov the picture's angles across and up.
     */
    public record Pose(double lat, double lon, double altM, double headingDeg, double pitchDeg, double rollDeg,
                       double tiltDeg, double hfovDeg, double vfovDeg) {
    }

    /**
     * The view: outline points {lat, lon} round the picture's edge (clockwise from the upper left corner), the
     * picture's centre and corners where they meet the ground ({lat, lon, height m}; null where that ray shows sky or
     * ground past MAX_RANGE_M), the slant range to the centre (NaN when it shows none), and whether the ground had to be
     * taken as flat (no terrain known there).
     */
    public record View(Pose pose, List<double[]> outline, double[] centre, double[][] corners, double slantRangeM,
                       boolean flatGround) {
    }

    /**
     * One ray from the camera (1.0.46, a motion detection's direction): its bearing from north, degrees, and where it
     * meets the ground ({lat, lon, height m}; null where it shows sky or ground past MAX_RANGE_M), the slant range to
     * there (NaN without), and whether the ground had to be taken as flat there.
     */
    public record Ray(double bearingDeg, double[] ground, double slantRangeM, boolean flatGround) {
    }

    /** The ray through picture point (u, v), -1..1 left to right and top to bottom of the picture as it is shown. */
    public static Ray ray(Pose p, double u, double v, RadioHorizon.Terrain terrain, double groundFallbackM) {
        boolean[] flat = {false};
        double[] hit = cast(p, u, v, terrain, groundFallbackM, flat);
        double[] d = direction(p, u, v);
        double bearing = (Math.toDegrees(Math.atan2(d[1], d[0])) + 360) % 360;
        boolean ground = hit[3] == 0;
        return new Ray(bearing, ground ? new double[]{hit[0], hit[1], hit[2]} : null, ground ? hit[4] : Double.NaN, flat[0]);
    }

    /** groundFallbackM: the ground's height where the terrain is not known (NaN: below the drone by its height over launch is the caller's choice). */
    public static View compute(Pose p, RadioHorizon.Terrain terrain, double groundFallbackM) {
        boolean[] flat = {false};
        List<double[]> outline = new ArrayList<>(4 * PER_EDGE);
        double[][] corners = new double[4][];
        double[][] edge = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}; // upper left, upper right, lower right, lower left
        for (int e = 0; e < 4; e++) {
            double[] a = edge[e], b = edge[(e + 1) % 4];
            for (int i = 0; i < PER_EDGE; i++) {
                double t = (double) i / PER_EDGE;
                double u = a[0] + (b[0] - a[0]) * t, v = a[1] + (b[1] - a[1]) * t;
                double[] hit = cast(p, u, v, terrain, groundFallbackM, flat);
                if (i == 0) corners[e] = hit[3] > 0 ? null : new double[]{hit[0], hit[1], hit[2]};
                outline.add(new double[]{hit[0], hit[1]});
            }
        }
        double[] c = cast(p, 0, 0, terrain, groundFallbackM, flat);
        double[] centre = c[3] > 0 ? null : new double[]{c[0], c[1], c[2]};
        return new View(p, outline, centre, corners, centre == null ? Double.NaN : c[4], flat[0]);
    }

    /**
     * The ray through picture point (u, v), -1..1 left to right and top to bottom, in north / east / down.
     */
    static double[] direction(Pose p, double u, double v) {
        // camera: forward, right, down
        double x = 1, y = u * Math.tan(Math.toRadians(p.hfovDeg() / 2)), z = v * Math.tan(Math.toRadians(p.vfovDeg() / 2));
        double[] d = pitch(new double[]{x, y, z}, p.tiltDeg()); // the camera's tilt on the frame
        d = roll(d, p.rollDeg());
        d = pitch(d, p.pitchDeg());
        d = yaw(d, p.headingDeg());
        double n = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        return new double[]{d[0] / n, d[1] / n, d[2] / n};
    }

    private static double[] pitch(double[] d, double deg) { // nose up positive: forward turns up (-down)
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        return new double[]{d[0] * c + d[2] * s, d[1], -d[0] * s + d[2] * c};
    }

    private static double[] roll(double[] d, double deg) { // right wing down positive: right turns down
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        return new double[]{d[0], d[1] * c - d[2] * s, d[1] * s + d[2] * c};
    }

    private static double[] yaw(double[] d, double deg) { // clockwise from north
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        return new double[]{d[0] * c - d[1] * s, d[0] * s + d[1] * c, d[2]};
    }

    /** {lat, lon, ground height, 1 if it met no ground (sky or too far) else 0, slant range m}. */
    static double[] cast(Pose p, double u, double v, RadioHorizon.Terrain terrain, double fallbackM, boolean[] flat) {
        double[] d = direction(p, u, v);
        double horiz = Math.hypot(d[0], d[1]);
        double prevS = 0, s = 0;
        double maxS = horiz < 1e-6 ? 20_000 : MAX_RANGE_M / horiz;
        while (s < maxS) {
            double step = Math.max(2, s * 0.01); // 1 % of the way: 30 m at the far end, metres near the drone
            prevS = s;
            s = Math.min(maxS, s + step);
            if (above(p, d, s, terrain, fallbackM, flat) <= 0) {
                for (int i = 0; i < 12; i++) { // the crossing to a few cm
                    double mid = (prevS + s) / 2;
                    if (above(p, d, mid, terrain, fallbackM, flat) <= 0) s = mid;
                    else prevS = mid;
                }
                double[] ll = RadioHorizon.offset(p.lat(), p.lon(), d[1] * s, d[0] * s);
                return new double[]{ll[0], ll[1], ground(ll[0], ll[1], terrain, fallbackM, flat), 0, s};
            }
        }
        // sky, or ground past the range: the range's end on the ray's bearing (straight up: over the drone)
        double reach = horiz < 1e-6 ? 0 : MAX_RANGE_M / horiz;
        double[] ll = RadioHorizon.offset(p.lat(), p.lon(), d[1] * reach, d[0] * reach);
        return new double[]{ll[0], ll[1], Double.NaN, 1, Double.NaN};
    }

    /** The ray's height over the ground s metres along it (the earth's curve taken off the ground's distance). */
    private static double above(Pose p, double[] d, double s, RadioHorizon.Terrain terrain, double fallbackM, boolean[] flat) {
        double north = d[0] * s, east = d[1] * s;
        double[] ll = RadioHorizon.offset(p.lat(), p.lon(), east, north);
        double dist = Math.hypot(north, east);
        double rayM = p.altM() - d[2] * s;
        return rayM - (ground(ll[0], ll[1], terrain, fallbackM, flat) - dist * dist / (2 * EARTH_M));
    }

    private static double ground(double lat, double lon, RadioHorizon.Terrain terrain, double fallbackM, boolean[] flat) {
        double h = terrain == null ? Double.NaN : terrain.heightM(lat, lon);
        if (Double.isNaN(h)) {
            flat[0] = true;
            return fallbackM;
        }
        return h;
    }

    /**
     * The terrain round the drone, fetched on a background thread the first time and again once it has flown
     * {@link #RELOAD_M} from where it was fetched; heightM is NaN until then (the view falls back to flat ground).
     */
    public static final class Terrain implements RadioHorizon.Terrain {
        static final double RADIUS_M = 5000, RELOAD_M = 2000;
        private final TileCache cache;
        private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cameraTerrain");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        private volatile RadioHorizon.Data data;
        private volatile double[] loading; // the point being fetched round, null when idle

        public Terrain(TileCache cache) {
            this.cache = cache;
        }

        /** Asks for the terrain round the point if what is in hand is far from it; returns at once. */
        public void around(double lat, double lon) {
            if (cache == null || loading != null) return;
            RadioHorizon.Data d = data;
            if (d != null && Mercator.distanceM(d.lat(), d.lon(), lat, lon) < RELOAD_M) return;
            double[] at = {lat, lon};
            loading = at;
            pool.execute(() -> {
                try {
                    RadioHorizon.Data next = new RadioHorizon.Data(lat, lon, true);
                    for (int[] t : RadioHorizon.tilesAround(lat, lon, RadioHorizon.TERRAIN_ZOOM, RADIUS_M)) {
                        String url = RadioHorizon.TERRAIN_URL.replace("{z}", Integer.toString(RadioHorizon.TERRAIN_ZOOM))
                                .replace("{x}", Integer.toString(t[0])).replace("{y}", Integer.toString(t[1]));
                        BufferedImage img = cache.fetchNow(RadioHorizon.TERRAIN_ID, url, RadioHorizon.TERRAIN_ZOOM, t[0], t[1]);
                        if (img != null) next.putTerrain(t[0], t[1], img);
                    }
                    data = next;
                } catch (RuntimeException ignored) {
                    // no terrain: the view stays on flat ground
                } finally {
                    loading = null;
                }
            });
        }

        @Override
        public double heightM(double lat, double lon) {
            RadioHorizon.Data d = data;
            return d == null ? Double.NaN : d.heightM(lat, lon);
        }
    }
}
