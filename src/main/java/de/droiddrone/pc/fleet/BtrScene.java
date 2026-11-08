package de.droiddrone.pc.fleet;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;

/**
 * The tour's finale scene (the pilot, 2026-11-08): a BTR on patrol in front of a virtual drone in overwatch. The
 * vehicle is drawn real - its own textures, lit by a low sun, its wheels turning, a shadow on the ground - in a world
 * drawn like a computer simulation: a pale sky and ground, a faint data grid, glowing outlines of far structures,
 * floating fragments, scan lines and now and then a glitch. The motion detection's picture gets the world and the
 * vehicle without the screen effects, so only the vehicle travels in it.
 * <p>
 * Software raster with a depth buffer (DroneModelView's way), one thread per picture (the video's, the detection's,
 * overwatch's preview's each have their own buffers). Its route (1.0.52, the pilot: "more realistic of a military
 * vehicle, not just a circle, and farther away") is fixed on the ground relative to the drone's start: a road
 * {@link #NEAR_M} to {@link #FAR_M} in front of it, wandering across its picture with bends, speed changes and halts,
 * never the same stretch twice. It waits out of the picture, to the left, until {@link #driveFrom}. (The detection's
 * first 20 s after its warm-up are strict - a slow, long thing reads as flicker then - so the scenario lets it in once
 * the watching is older than that.)
 */
final class BtrScene implements Scene.World {
    /** The sun: from the south-west, 35 degrees up (north, east, up). */
    private static final double[] SUN = unit(new double[]{-0.574, -0.574, 0.70});
    private static final String MESH = "/model/btr.bin.gz";
    private static final String[] TEXTURES = {"/model/btr-0.jpg", "/model/btr-1.jpg", "/model/btr-2.jpg", "/model/btr-3.jpg"};
    /** A drone this far outside the hull's box counts as touching it. */
    static final double HIT_MARGIN_M = 0.35;

    // ------------------------------------------------------------------ the model

    /**
     * The mesh; each texture as a mip chain (level 0 the image, each next one half its size, box-filtered), so a far or
     * small vehicle averages its texture as a camera's picture does instead of shimmering.
     */
    record Model(float[] p, float[] u, float[] v, byte[] tex, int[] tri, int[][][] mip, int[][] mipW, int[][] mipH,
                 int[] wheel, float[][] wheelAxle, float length, float width, float height, int[] outline) {}

    private static Model model;

    static synchronized Model model() {
        if (model == null) {
            try (InputStream in = BtrScene.class.getResourceAsStream(MESH)) {
                if (in == null) throw new IOException(MESH + " missing");
                model = load(new DataInputStream(new GZIPInputStream(in, 1 << 16)));
            } catch (IOException e) {
                throw new IllegalStateException("The BTR model could not be read: " + e.getMessage(), e);
            }
        }
        return model;
    }

    private static Model load(DataInputStream in) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!new String(magic, java.nio.charset.StandardCharsets.US_ASCII).equals("BTR1")) throw new IOException("not a BTR1 mesh");
        int nv = in.readInt();
        float[] p = new float[nv * 3], u = new float[nv], v = new float[nv];
        byte[] tex = new byte[nv];
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = 0;
        for (int i = 0; i < nv; i++) {
            p[i * 3] = in.readFloat();
            p[i * 3 + 1] = in.readFloat();
            p[i * 3 + 2] = in.readFloat();
            u[i] = in.readFloat();
            v[i] = in.readFloat();
            tex[i] = in.readByte();
            minX = Math.min(minX, p[i * 3]);
            maxX = Math.max(maxX, p[i * 3]);
            minY = Math.min(minY, p[i * 3 + 1]);
            maxY = Math.max(maxY, p[i * 3 + 1]);
            maxZ = Math.max(maxZ, p[i * 3 + 2]);
        }
        int[] tri = new int[in.readInt() * 3];
        for (int i = 0; i < tri.length; i++) tri[i] = in.readUnsignedShort();
        int[][][] mip = new int[TEXTURES.length][][];
        int[][] mw = new int[TEXTURES.length][], mh = new int[TEXTURES.length][];
        for (int k = 0; k < TEXTURES.length; k++) {
            try (InputStream t = BtrScene.class.getResourceAsStream(TEXTURES[k])) {
                if (t == null) throw new IOException(TEXTURES[k] + " missing");
                BufferedImage img = ImageIO.read(t);
                if (img == null) throw new IOException(TEXTURES[k] + " unreadable");
                List<int[]> levels = new ArrayList<>();
                List<Integer> ws = new ArrayList<>(), hs = new ArrayList<>();
                int w = img.getWidth(), h = img.getHeight();
                int[] px = img.getRGB(0, 0, w, h, null, 0, w);
                while (true) {
                    levels.add(px);
                    ws.add(w);
                    hs.add(h);
                    if (w == 1 && h == 1) break;
                    int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
                    int[] next = new int[nw * nh];
                    for (int y = 0; y < nh; y++) {
                        for (int x = 0; x < nw; x++) {
                            int r = 0, g = 0, b = 0, n = 0;
                            for (int dy = 0; dy < 2; dy++) {
                                for (int dx = 0; dx < 2; dx++) {
                                    int sx = Math.min(w - 1, x * 2 + dx), sy = Math.min(h - 1, y * 2 + dy);
                                    int c = px[sy * w + sx];
                                    r += (c >> 16) & 0xff;
                                    g += (c >> 8) & 0xff;
                                    b += c & 0xff;
                                    n++;
                                }
                            }
                            next[y * nw + x] = 0xff000000 | (r / n << 16) | (g / n << 8) | b / n;
                        }
                    }
                    px = next;
                    w = nw;
                    h = nh;
                }
                mip[k] = levels.toArray(new int[0][]);
                mw[k] = ws.stream().mapToInt(Integer::intValue).toArray();
                mh[k] = hs.stream().mapToInt(Integer::intValue).toArray();
            }
        }
        // the wheels: their vertices in eight groups (four axles, two sides), each turning about its own axle
        int[] wheel = new int[nv];
        Arrays.fill(wheel, -1);
        List<Float> xs = new ArrayList<>();
        for (int i = 0; i < nv; i++) if (tex[i] == 1) xs.add(p[i * 3]);
        xs.sort(null);
        List<Float> splits = new ArrayList<>();
        for (int i = 1; i < xs.size(); i++) if (xs.get(i) - xs.get(i - 1) > 0.25f) splits.add((xs.get(i) + xs.get(i - 1)) / 2);
        int axles = splits.size() + 1;
        float[][] box = new float[axles * 2][]; // per wheel: min x, max x, min z, max z
        for (int i = 0; i < nv; i++) {
            if (tex[i] != 1) continue;
            int a = 0;
            while (a < splits.size() && p[i * 3] > splits.get(a)) a++;
            int w = a * 2 + (p[i * 3 + 1] > 0 ? 1 : 0);
            wheel[i] = w;
            float[] b = box[w];
            if (b == null) box[w] = b = new float[]{Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
            b[0] = Math.min(b[0], p[i * 3]);
            b[1] = Math.max(b[1], p[i * 3]);
            b[2] = Math.min(b[2], p[i * 3 + 2]);
            b[3] = Math.max(b[3], p[i * 3 + 2]);
        }
        float[][] axle = new float[box.length][]; // centre x, centre z, radius
        for (int w = 0; w < box.length; w++) {
            float[] b = box[w];
            if (b == null) continue;
            axle[w] = new float[]{(b[0] + b[1]) / 2, (b[2] + b[3]) / 2, Math.max(0.1f, (b[3] - b[2]) / 2)};
        }
        // the shadow's outline: every vertex's footprint, its convex hull
        int[] outline = hull(p, nv);
        return new Model(p, u, v, tex, tri, mip, mw, mh, wheel, axle, maxX - minX, maxY - minY, maxZ, outline);
    }

    /** The convex hull (indices) of the vertices seen from above. */
    private static int[] hull(float[] p, int nv) {
        Integer[] idx = new Integer[nv];
        for (int i = 0; i < nv; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> p[a * 3] != p[b * 3] ? Float.compare(p[a * 3], p[b * 3]) : Float.compare(p[a * 3 + 1], p[b * 3 + 1]));
        int[] h = new int[nv * 2];
        int k = 0;
        for (int pass = 0; pass < 2; pass++) {
            int start = k;
            for (int j = 0; j < nv; j++) {
                int i = pass == 0 ? idx[j] : idx[nv - 1 - j];
                while (k >= start + 2 && turn(p, h[k - 2], h[k - 1], i) <= 0) k--;
                h[k++] = i;
            }
            k--;
        }
        return Arrays.copyOf(h, Math.max(0, k));
    }

    private static double turn(float[] p, int o, int a, int b) {
        return (p[a * 3] - p[o * 3]) * (double) (p[b * 3 + 1] - p[o * 3 + 1]) - (p[a * 3 + 1] - p[o * 3 + 1]) * (double) (p[b * 3] - p[o * 3]);
    }

    // ------------------------------------------------------------------ the route

    private final Model m = model();
    private final double originN, originE, fwdN, fwdE, rightN, rightE;
    /** Its range ahead of the drone (NEAR_M, FAR_M unless a test asks). */
    private final double nearM, farM;
    private volatile long startMs;
    /** The world round it (the far structures, the fragments): {@link SimWorld}, every virtual drone's since 1.0.52. */
    private final SimWorld env;
    /** The road it drives, every ROUTE_STEP_M along it: north and east. */
    private final double[] routeN, routeE;
    /** Its drive, every TICK_S from the start: the distance along the road and the speed then. */
    private final float[] driveS, driveV;

    /**
     * The route's nearest and farthest point ahead of the drone, metres (the pilot: farther away - about three times the
     * 1.0.51 loop's). Farther, the vehicle is a few pixels in the detection's picture and the detector of
     * 1.0.52 no longer finds it reliably (it reaches about 80 m; measured: 85-140 m took over a minute, 100-190 m never).
     */
    static final double NEAR_M = 50, FAR_M = 95;
    private static final double ROUTE_STEP_M = 0.5, TICK_S = 0.1;
    /** Its drive is worked out this far ahead; after that it stands (a tour never takes this long). */
    private static final double DRIVE_S = 40 * 60;
    /** How hard it speeds up, brakes and takes a bend (m/s2): a heavy wheeled vehicle off-road. */
    private static final double ACCEL = 0.9, BRAKE = 1.6, LATERAL = 1.2;

    /**
     * @param north     east the drone's start point (metres, the flight model's)
     * @param headingDeg where the drone looks: the route is NEAR_M to FAR_M in front of it, across its picture
     */
    BtrScene(double north, double east, double headingDeg, long startMs) {
        this(north, east, headingDeg, startMs, NEAR_M, FAR_M);
    }

    /** With its own range ahead (tests: how far the detection still finds it). */
    BtrScene(double north, double east, double headingDeg, long startMs, double nearM, double farM) {
        this.nearM = nearM;
        this.farM = farM;
        double h = Math.toRadians(headingDeg);
        fwdN = Math.cos(h);
        fwdE = Math.sin(h);
        rightN = -Math.sin(h);
        rightE = Math.cos(h);
        originN = north;
        originE = east;
        this.startMs = startMs;
        env = new SimWorld(north, east);
        List<double[]> way = waypoints(new Random(1980), nearM, farM);
        double[][] road = road(way);
        routeN = road[0];
        routeE = road[1];
        float[][] d = drive(way, road[2]);
        driveS = d[0];
        driveV = d[1];
    }

    /**
     * Where it drives, in the drone's frame {forward, right, speed on the way there m/s, halt there s}: from its hide
     * outside the picture's left edge into the picture, then on and on across it - legs of 35-90 m, bends of at most 70
     * degrees, now a crawl, now a dash, now and then a halt (to look, as a vehicle on patrol does). Seeded: the same
     * drive each time, never a loop.
     */
    private static List<double[]> waypoints(Random r, double near, double far) {
        List<double[]> w = new ArrayList<>();
        double mid = (near + far) / 2, side = near * 0.95, scale = mid / 145;
        double in = near + 0.15 * (far - near); // the way in at the near side: biggest in the picture when it is first seen
        w.add(new double[]{in, -in * 1.2, 0, 0}); // the hide: 50 degrees left of the nose, out of a 90 degree picture
        w.add(new double[]{in * 1.02, -in * 0.83, 6.5, 0});
        w.add(new double[]{in, -in * 0.48, 6, 0});
        double f = near + 0.3 * (far - near), s = -mid * 0.26, dir = Math.toRadians(80); // heading across, to the right
        w.add(new double[]{f, s, 3.5, 6}); // the first halt, in the picture
        for (int i = 0; i < 90; i++) {
            double leg = (35 + r.nextDouble() * 55) * Math.max(0.5, scale);
            double turn = Math.toRadians((r.nextDouble() * 2 - 1) * 70);
            double nf = f + Math.cos(dir + turn) * leg, ns = s + Math.sin(dir + turn) * leg;
            if (nf < near || nf > far || Math.abs(ns) > side) { // out of its area: back towards the middle
                double back = Math.atan2(-s * 0.6, mid - f);
                double t = Math.atan2(Math.sin(back - dir), Math.cos(back - dir));
                turn = Math.max(-Math.toRadians(110), Math.min(Math.toRadians(110), t));
                nf = f + Math.cos(dir + turn) * leg;
                ns = s + Math.sin(dir + turn) * leg;
            }
            dir += turn;
            f = Math.max(near - 10, Math.min(far + 10, nf));
            s = Math.max(-side - 10, Math.min(side + 10, ns));
            double speed = r.nextDouble() < 0.25 ? 2 + r.nextDouble() * 1.5 : 4.5 + r.nextDouble() * 4;
            double halt = r.nextDouble() < 0.2 ? 3 + r.nextDouble() * 7 : 0;
            w.add(new double[]{f, s, speed, halt});
        }
        return w;
    }

    /** The waypoints as a smooth road (Catmull-Rom), every ROUTE_STEP_M: {north[], east[], waypoint index there[]}. */
    private double[][] road(List<double[]> way) {
        List<double[]> pts = new ArrayList<>();
        int n = way.size();
        for (int i = 0; i < n - 1; i++) {
            double[] p0 = world(way.get(Math.max(0, i - 1))), p1 = world(way.get(i)), p2 = world(way.get(i + 1)),
                    p3 = world(way.get(Math.min(n - 1, i + 2)));
            int k = 400;
            for (int j = 0; j < k; j++) {
                double t = (double) j / k, t2 = t * t, t3 = t2 * t;
                double pn = 0.5 * (2 * p1[0] + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3);
                double pe = 0.5 * (2 * p1[1] + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3);
                pts.add(new double[]{pn, pe, i + t});
            }
        }
        double[] last = world(way.get(n - 1));
        pts.add(new double[]{last[0], last[1], n - 1});
        List<double[]> even = new ArrayList<>(); // resampled every ROUTE_STEP_M of road
        even.add(pts.get(0));
        double carry = 0;
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            double seg = Math.hypot(b[0] - a[0], b[1] - a[1]);
            double pos = ROUTE_STEP_M - carry;
            while (pos <= seg) {
                double k = pos / seg;
                even.add(new double[]{a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k});
                pos += ROUTE_STEP_M;
            }
            carry = seg - (pos - ROUTE_STEP_M);
        }
        double[][] out = new double[3][even.size()];
        for (int i = 0; i < even.size(); i++) {
            out[0][i] = even.get(i)[0];
            out[1][i] = even.get(i)[1];
            out[2][i] = even.get(i)[2];
        }
        return out;
    }

    /** A point in the drone's frame {forward, right} on the ground {north, east}. */
    private double[] world(double[] w) {
        return new double[]{originN + fwdN * w[0] + rightN * w[1], originE + fwdE * w[0] + rightE * w[1]};
    }

    /**
     * Drives the road: towards each leg's speed at ACCEL / BRAKE, slower in bends (LATERAL), braking in time for each
     * halt and standing there its seconds. Every TICK_S: {distance along the road[], speed[]}.
     */
    private float[][] drive(List<double[]> way, double[] wpAt) {
        int points = routeN.length;
        double[] limit = new double[points]; // the bend's speed limit at each point
        for (int i = 0; i < points; i++) {
            int a = Math.max(0, i - 6), b = Math.min(points - 1, i + 6);
            double h1 = Math.atan2(routeE[i] - routeE[a], routeN[i] - routeN[a]), h2 = Math.atan2(routeE[b] - routeE[i], routeN[b] - routeN[i]);
            double dh = Math.abs(Math.atan2(Math.sin(h2 - h1), Math.cos(h2 - h1)));
            double curvature = dh / Math.max(ROUTE_STEP_M, (b - a) * ROUTE_STEP_M / 2);
            limit[i] = curvature < 1e-4 ? 99 : Math.sqrt(LATERAL / curvature);
        }
        List<int[]> halts = new ArrayList<>(); // {road point, ms}
        for (int w = 1; w < way.size(); w++) {
            if (way.get(w)[3] <= 0) continue;
            int at = 0;
            while (at < points - 1 && wpAt[at] < w) at++;
            halts.add(new int[]{at, (int) Math.round(way.get(w)[3] * 1000)});
        }
        int ticks = (int) (DRIVE_S / TICK_S);
        float[] ds = new float[ticks], dv = new float[ticks];
        double pos = 0, v = 0, standUntil = -1;
        int nextHalt = 0;
        for (int k = 0; k < ticks; k++) {
            double t = k * TICK_S;
            int i = Math.min(points - 1, (int) (pos / ROUTE_STEP_M));
            if (standUntil > t || i >= points - 1) {
                v = 0;
            } else {
                int leg = (int) Math.min(way.size() - 1, Math.floor(wpAt[i]) + 1);
                double want = Math.min(way.get(leg)[2], limit[i]);
                // a bend ahead: slow down for it in time
                for (int j = i; j < Math.min(points, i + 60); j++) want = Math.min(want, Math.sqrt(limit[j] * limit[j] + 2 * BRAKE * (j - i) * ROUTE_STEP_M));
                if (nextHalt < halts.size()) {
                    double toHalt = (halts.get(nextHalt)[0] - i) * ROUTE_STEP_M;
                    want = Math.min(want, Math.max(0.3, Math.sqrt(2 * BRAKE * Math.max(0, toHalt))));
                    if (toHalt <= 0.3) {
                        standUntil = t + halts.get(nextHalt)[1] / 1000.0;
                        nextHalt++;
                        want = 0;
                        v = 0;
                    }
                }
                v = want > v ? Math.min(want, v + ACCEL * TICK_S) : Math.max(want, v - BRAKE * TICK_S);
                pos += v * TICK_S;
            }
            ds[k] = (float) pos;
            dv[k] = (float) v;
        }
        return new float[][]{ds, dv};
    }

    /** It starts driving at this time (until then it waits out of the picture). */
    void driveFrom(long ms) {
        startMs = ms;
    }

    long drivesFrom() {
        return startMs;
    }

    /** Its speed now, m/s (0 waiting or halted). */
    double speedAt(long nowMs) {
        int k = (int) Math.floor((nowMs - startMs) / 1000.0 / TICK_S);
        return k < 0 ? 0 : driveV[Math.min(driveV.length - 1, k)];
    }

    /** Where the vehicle is: {north, east, heading degrees, metres driven}. */
    double[] pose(long nowMs) {
        double t = (nowMs - startMs) / 1000.0 / TICK_S;
        double s;
        if (t <= 0) {
            s = 0;
        } else {
            int k = (int) t;
            s = k >= driveS.length - 1 ? driveS[driveS.length - 1] : driveS[k] + (driveS[k + 1] - driveS[k]) * (t - k);
        }
        double x = Math.min(routeN.length - 1.001, s / ROUTE_STEP_M);
        int i = (int) x;
        double f = x - i;
        double n = routeN[i] + (routeN[i + 1] - routeN[i]) * f, e = routeE[i] + (routeE[i + 1] - routeE[i]) * f;
        int a = Math.max(0, i - 2), b = Math.min(routeN.length - 1, i + 3);
        double heading = Math.toDegrees(Math.atan2(routeE[b] - routeE[a], routeN[b] - routeN[a]));
        return new double[]{n, e, heading, s};
    }

    /** Whether a drone at this point touches the vehicle (its box and a margin). */
    boolean hits(double north, double east, double alt, long nowMs) {
        double[] q = pose(nowMs);
        double h = Math.toRadians(q[2]);
        double dn = north - q[0], de = east - q[1];
        double x = dn * Math.cos(h) + de * Math.sin(h), y = -dn * Math.sin(h) + de * Math.cos(h);
        return Math.abs(x) <= m.length() / 2 + HIT_MARGIN_M && Math.abs(y) <= m.width() / 2 + HIT_MARGIN_M
                && alt <= m.height() + HIT_MARGIN_M;
    }

    /**
     * Where the vehicle is from a drone, for the sound: {distance m, bearing from the nose (radians, right +), speed
     * towards the drone m/s}.
     */
    double[] relative(Scene.View v, long nowMs) {
        double[] q = pose(nowMs), q2 = pose(nowMs + 100);
        double dn = q[0] - v.north(), de = q[1] - v.east(), du = 1.2 - v.alt();
        double dist = Math.sqrt(dn * dn + de * de + du * du);
        double bearing = Math.atan2(de, dn) - Math.toRadians(v.headingDeg());
        bearing = Math.atan2(Math.sin(bearing), Math.cos(bearing));
        double dist2 = Math.hypot(q2[0] - v.north(), q2[1] - v.east());
        double towards = (Math.hypot(dn, de) - dist2) / 0.1;
        return new double[]{dist, bearing, towards};
    }

    // ------------------------------------------------------------------ the picture

    /** One thread's buffers (the video's, the detection's, the preview's draw at once). */
    private static final class Buffers {
        float[] z = new float[0];
        float[] cx, cy, cz, sx, sy;
        int w, h;
    }

    private final ThreadLocal<Buffers> buffers = ThreadLocal.withInitial(Buffers::new);

    @Override
    public void render(BufferedImage img, Scene.View v, long nowMs, boolean analysis) {
        int w = img.getWidth(), h = img.getHeight();
        Scene.Camera cam = new Scene.Camera(v, w, h);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            env.paint(g, cam, v, w, h, nowMs, analysis, false);
        } finally {
            g.dispose();
        }
        renderVehicle(img, v, nowMs, false);
        if (!analysis) SimWorld.effects(img, nowMs);
    }

    /**
     * The vehicle alone - its shadow, itself, and at night its headlights - over a picture already drawn (a virtual
     * drone's world with a BTR in it, 1.0.52).
     */
    void renderVehicle(BufferedImage img, Scene.View v, long nowMs, boolean night) {
        int w = img.getWidth(), h = img.getHeight();
        Scene.Camera cam = new Scene.Camera(v, w, h);
        double[] q = pose(nowMs);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (!night) shadow(g, cam, q);
        } finally {
            g.dispose();
        }
        vehicle(Scene.pixels(img), w, h, cam, v, q, night ? 0.32f : 1f, SimWorld.haze(night));
        if (night) headlights(img, cam, q);
    }

    /** Two headlights at the front at night, and their glow. */
    private void headlights(BufferedImage img, Scene.Camera cam, double[] q) {
        double hd = Math.toRadians(q[2]), c = Math.cos(hd), s = Math.sin(hd);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (double side : new double[]{-0.33, 0.33}) {
                double x = m.length() / 2, y = side * m.width();
                double[] k = cam.cam(q[0] + x * c - y * s, q[1] + x * s + y * c, 1.1);
                if (k[2] < 0.5) continue;
                double hx = cam.cx + cam.f * k[0] / k[2], hy = cam.cy + cam.f * k[1] / k[2];
                double r = Math.max(2, 0.16 * cam.f / k[2]);
                g.setColor(new Color(255, 248, 208, 90));
                g.fill(new java.awt.geom.Ellipse2D.Double(hx - 2.5 * r, hy - 2.5 * r, 5 * r, 5 * r));
                g.setColor(new Color(0xfff8d0));
                g.fill(new java.awt.geom.Ellipse2D.Double(hx - r, hy - r, 2 * r, 2 * r));
            }
        } finally {
            g.dispose();
        }
    }

    /** The vehicle's shadow: its outline cast along the sun onto the ground, soft-edged. */
    private void shadow(Graphics2D g, Scene.Camera cam, double[] q) {
        double h = Math.toRadians(q[2]), c = Math.cos(h), s = Math.sin(h);
        float[] p = m.p();
        Path2D path = new Path2D.Double();
        int n = 0;
        for (int idx : m.outline()) {
            // the outline at mid height, cast: a point at height z falls z * (sun across / sun up) away
            double x = p[idx * 3], y = p[idx * 3 + 1], z = m.height() * 0.6;
            double wn = q[0] + x * c - y * s - SUN[0] / SUN[2] * z, we = q[1] + x * s + y * c - SUN[1] / SUN[2] * z;
            double[] k = cam.cam(wn, we, 0);
            if (k[2] < 0.5) return;
            double sx = cam.cx + cam.f * k[0] / k[2], sy = cam.cy + cam.f * k[1] / k[2];
            if (n++ == 0) path.moveTo(sx, sy);
            else path.lineTo(sx, sy);
        }
        if (n < 3) return;
        path.closePath();
        g.setColor(new Color(40, 52, 64, 70));
        g.setStroke(new BasicStroke(Math.max(2f, (float) cam.w / 200f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);
        g.setColor(new Color(40, 52, 64, 120));
        g.fill(path);
    }

    /** The vehicle, textured and lit, into the picture's pixels through a depth buffer. */
    private void vehicle(int[] px, int w, int h, Scene.Camera cam, Scene.View v, double[] q, float lightScale, int haze) {
        Buffers b = buffers.get();
        int nv = m.u().length;
        if (b.w != w || b.h != h) {
            b.z = new float[w * h];
            b.w = w;
            b.h = h;
        }
        if (b.cx == null) {
            b.cx = new float[nv];
            b.cy = new float[nv];
            b.cz = new float[nv];
            b.sx = new float[nv];
            b.sy = new float[nv];
        }
        // quick out: the whole vehicle behind the camera or far off the picture
        double[] centre = cam.cam(q[0], q[1], 1.2);
        if (centre[2] < -6) return;
        Arrays.fill(b.z, Float.MAX_VALUE);
        double hd = Math.toRadians(q[2]), c = Math.cos(hd), s = Math.sin(hd);
        float[] p = m.p();
        double spin = q[3];
        double[][] r = cam.r;
        // world positions -> camera space
        double[] wx = new double[nv], wy = new double[nv], wz = new double[nv]; // world n, e, u (for the light)
        for (int i = 0; i < nv; i++) {
            double x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
            int wh = m.wheel()[i];
            if (wh >= 0 && m.wheelAxle()[wh] != null) {
                float[] a = m.wheelAxle()[wh];
                double ang = spin / a[2], dx = x - a[0], dz = z - a[1];
                double ca = Math.cos(ang), sa = Math.sin(ang);
                x = a[0] + dx * ca + dz * sa;
                z = a[1] - dx * sa + dz * ca;
            }
            double n = q[0] + x * c - y * s, e = q[1] + x * s + y * c;
            wx[i] = n;
            wy[i] = e;
            wz[i] = z;
            double dn = n - cam.cn, de = e - cam.ce, du = z - cam.cu;
            double kx = r[0][0] * dn + r[0][1] * de + r[0][2] * du;
            double ky = r[1][0] * dn + r[1][1] * de + r[1][2] * du;
            double kz = r[2][0] * dn + r[2][1] * de + r[2][2] * du;
            b.cx[i] = (float) kx;
            b.cy[i] = (float) ky;
            b.cz[i] = (float) kz;
            if (kz > 0.05) {
                b.sx[i] = (float) (cam.cx + cam.f * kx / kz);
                b.sy[i] = (float) (cam.cy + cam.f * ky / kz);
            }
        }
        int[] tri = m.tri();
        double fogK = 1.0 / 450;
        for (int t = 0; t < tri.length; t += 3) {
            int i0 = tri[t], i1 = tri[t + 1], i2 = tri[t + 2];
            if (b.cz[i0] < 0.3 || b.cz[i1] < 0.3 || b.cz[i2] < 0.3) continue;
            // the face's normal in the world, turned to the camera (the mesh isn't consistently wound)
            double ax = wx[i1] - wx[i0], ay = wy[i1] - wy[i0], az = wz[i1] - wz[i0];
            double bx = wx[i2] - wx[i0], by = wy[i2] - wy[i0], bz = wz[i2] - wz[i0];
            double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl < 1e-12) continue;
            nx /= nl;
            ny /= nl;
            nz /= nl;
            double toCam = nx * (cam.cn - wx[i0]) + ny * (cam.ce - wy[i0]) + nz * (cam.cu - wz[i0]);
            if (toCam < 0) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
            double sun = Math.max(0, nx * SUN[0] + ny * SUN[1] + nz * SUN[2]);
            double sky = 0.5 + 0.5 * nz;
            float light = (float) (0.30 + 0.26 * sky + 0.78 * sun) * lightScale;
            double dist = b.cz[i0];
            float fog = (float) (1 - Math.exp(-dist * fogK));
            raster(px, b.z, w, h, b, i0, i1, i2, m.tex()[i0], light, fog, haze);
        }
    }

    /** One triangle, perspective-correct texture, nearest texel. */
    private void raster(int[] px, float[] zb, int w, int h, Buffers b, int i0, int i1, int i2, int texId, float light, float fog, int haze) {
        float x0 = b.sx[i0], y0 = b.sy[i0], x1 = b.sx[i1], y1 = b.sy[i1], x2 = b.sx[i2], y2 = b.sy[i2];
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-6f) return;
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) return;
        float iz0 = 1 / b.cz[i0], iz1 = 1 / b.cz[i1], iz2 = 1 / b.cz[i2];
        float[] uu = m.u(), vv = m.v();
        float u0 = uu[i0] * iz0, u1 = uu[i1] * iz1, u2 = uu[i2] * iz2;
        float v0 = vv[i0] * iz0, v1 = vv[i1] * iz1, v2 = vv[i2] * iz2;
        // the mip level: about one texel a pixel
        int id = texId & 3;
        float du1 = uu[i1] - uu[i0], dv1 = vv[i1] - vv[i0], du2 = uu[i2] - uu[i0], dv2 = vv[i2] - vv[i0];
        double texels = Math.abs(du1 * dv2 - du2 * dv1) * m.mipW()[id][0] * (double) m.mipH()[id][0];
        int level = texels <= Math.abs(area) ? 0 : (int) Math.floor(0.5 * Math.log(texels / Math.abs(area)) / Math.log(2) + 0.5);
        level = Math.max(0, Math.min(m.mip()[id].length - 1, level));
        int[] tex = m.mip()[id][level];
        int tw = m.mipW()[id][level], th = m.mipH()[id][level];
        float inv = 1 / area;
        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            for (int x = minX; x <= maxX; x++) {
                float pxx = x + 0.5f;
                float w0 = ((x1 - pxx) * (y2 - py) - (x2 - pxx) * (y1 - py)) * inv;
                float w1 = ((x2 - pxx) * (y0 - py) - (x0 - pxx) * (y2 - py)) * inv;
                float w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float iz = w0 * iz0 + w1 * iz1 + w2 * iz2;
                float z = 1 / iz;
                int o = y * w + x;
                if (z >= zb[o]) continue;
                zb[o] = z;
                float u = (w0 * u0 + w1 * u1 + w2 * u2) * z, v = (w0 * v0 + w1 * v1 + w2 * v2) * z;
                u -= (float) Math.floor(u);
                v -= (float) Math.floor(v);
                int tx = Math.min(tw - 1, (int) (u * tw)), ty = Math.min(th - 1, (int) ((1 - v) * th));
                int t = tex[ty * tw + tx];
                int r = Math.min(255, (int) (((t >> 16) & 0xff) * light)), gg = Math.min(255, (int) (((t >> 8) & 0xff) * light)),
                        bb = Math.min(255, (int) ((t & 0xff) * light));
                r += (int) ((((haze >> 16) & 0xff) - r) * fog);
                gg += (int) ((((haze >> 8) & 0xff) - gg) * fog);
                bb += (int) (((haze & 0xff) - bb) * fog);
                px[o] = 0xff000000 | (r << 16) | (gg << 8) | bb;
            }
        }
    }

    private static double[] unit(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }
}
