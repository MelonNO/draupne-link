package de.droiddrone.pc.fleet;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
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
 * The tour's finale scene (the pilot, 2026-11-08): a BTR driving a loop in front of a virtual drone in overwatch. The
 * vehicle is drawn real - its own textures, lit by a low sun, its wheels turning, a shadow on the ground - in a world
 * drawn like a computer simulation: a pale sky and ground, a faint data grid, glowing outlines of far structures,
 * floating fragments, scan lines and now and then a glitch. The motion detection's picture gets the world and the
 * vehicle without the screen effects, so only the vehicle travels in it.
 * <p>
 * Software raster with a depth buffer (DroneModelView's way), one thread per picture (the video's, the detection's,
 * overwatch's preview's each have their own buffers). The loop is fixed on the ground relative to the drone's start:
 * {@link #AHEAD_M} in front of it, {@link #ACROSS_M} to each side, {@link #DEPTH_M} deep, at {@link #SPEED_MS}. The
 * vehicle waits out of the picture, to the left, until {@link #driveFrom}; then it drives in along a straight line and
 * takes the loop. (The detection's first 20 s after its warm-up are strict - a slow, long thing reads as flicker then -
 * so the scenario lets it in once the watching is older than that.)
 */
final class BtrScene implements Scene.World {
    static final double AHEAD_M = 30, ACROSS_M = 18, DEPTH_M = 8, SPEED_MS = 6;
    /** Where it waits: on the loop's near side, this far across (out of a 90 degree picture from the drone's start). */
    static final double WAIT_ACROSS_M = -50;
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

    // ------------------------------------------------------------------ the loop

    private final Model m = model();
    private final double centreN, centreE, fwdN, fwdE, rightN, rightE;
    private volatile long startMs;
    private final double approachM;
    /** Arc length along the loop at each of LOOP_STEPS angles, and the loop's length. */
    private static final int LOOP_STEPS = 720;
    /** The loop is taken from its near side's middle, heading across (where the way in joins it). */
    private static final double LOOP_START = 1.5 * Math.PI;
    private final double[] arc = new double[LOOP_STEPS + 1];
    private final double loopM;
    /** The far structures and the floating fragments, fixed in the world. */
    private final double[][] structures, fragments;

    /**
     * @param north     east the drone's start point (metres, the flight model's)
     * @param headingDeg where the drone looks: the loop is AHEAD_M in front
     */
    BtrScene(double north, double east, double headingDeg, long startMs) {
        double h = Math.toRadians(headingDeg);
        fwdN = Math.cos(h);
        fwdE = Math.sin(h);
        rightN = -Math.sin(h);
        rightE = Math.cos(h);
        centreN = north + fwdN * AHEAD_M;
        centreE = east + fwdE * AHEAD_M;
        this.startMs = startMs;
        double len = 0;
        double[] prev = loopPoint(LOOP_START);
        for (int i = 1; i <= LOOP_STEPS; i++) {
            double[] q = loopPoint(LOOP_START + 2 * Math.PI * i / LOOP_STEPS);
            len += Math.hypot(q[0] - prev[0], q[1] - prev[1]);
            arc[i] = len;
            prev = q;
        }
        loopM = len;
        approachM = -WAIT_ACROSS_M; // along the loop's near side, from WAIT_ACROSS_M to its middle (where it heads across)
        Random r = new Random(4609);
        structures = new double[18][];
        for (int i = 0; i < structures.length; i++) {
            double a = r.nextDouble() * 2 * Math.PI, d = 140 + r.nextDouble() * 160;
            structures[i] = new double[]{north + Math.cos(a) * d, east + Math.sin(a) * d, 8 + r.nextDouble() * 22, 8 + r.nextDouble() * 26,
                    10 + r.nextDouble() * 45, r.nextDouble() * Math.PI / 2};
        }
        fragments = new double[70][];
        for (int i = 0; i < fragments.length; i++) {
            double a = r.nextDouble() * 2 * Math.PI, d = 15 + r.nextDouble() * 130;
            fragments[i] = new double[]{north + Math.cos(a) * d, east + Math.sin(a) * d, 0.8 + r.nextDouble() * 9, r.nextDouble() * 6.28, 0.2 + r.nextDouble() * 0.5};
        }
    }

    /** The loop at angle t: an ellipse across the view; {north, east}. */
    private double[] loopPoint(double t) {
        double a = Math.cos(t) * ACROSS_M, b = Math.sin(t) * DEPTH_M;
        return new double[]{centreN + rightN * a + fwdN * b, centreE + rightE * a + fwdE * b};
    }

    /** It starts driving at this time (until then it waits out of the picture). */
    void driveFrom(long ms) {
        startMs = ms;
    }

    long drivesFrom() {
        return startMs;
    }

    /** Where the vehicle is: {north, east, heading degrees, metres driven}. */
    double[] pose(long nowMs) {
        double driven = Math.max(0, nowMs - startMs) / 1000.0 * SPEED_MS;
        if (driven < approachM) { // the way in: straight across, towards the loop's left end
            double a = WAIT_ACROSS_M + driven;
            double heading = Math.toDegrees(Math.atan2(rightE, rightN));
            return new double[]{centreN + rightN * a - fwdN * DEPTH_M, centreE + rightE * a - fwdE * DEPTH_M, heading, driven};
        }
        double s = (driven - approachM) % loopM;
        int i = Arrays.binarySearch(arc, s);
        i = Math.min(LOOP_STEPS, Math.max(1, i < 0 ? -i - 1 : i));
        double f = (s - arc[i - 1]) / Math.max(1e-9, arc[i] - arc[i - 1]);
        double t = LOOP_START + 2 * Math.PI * (i - 1 + f) / LOOP_STEPS;
        double[] at = loopPoint(t), ahead = loopPoint(t + 0.01);
        double heading = Math.toDegrees(Math.atan2(ahead[1] - at[1], ahead[0] - at[0]));
        return new double[]{at[0], at[1], heading, driven};
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

    private static final Color SKY_TOP = new Color(0xeef2f6), SKY_LOW = new Color(0xd6dfe8), GROUND = new Color(0xc7d0d9),
            GRID = new Color(0x7e98b2), GRID_MAJOR = new Color(0x5f7f9f), EDGE = new Color(0x8fd8ff);

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
            world(g, cam, v, w, h, nowMs, analysis);
            double[] q = pose(nowMs);
            shadow(g, cam, q);
        } finally {
            g.dispose();
        }
        vehicle(Scene.pixels(img), w, h, cam, v, pose(nowMs));
        if (!analysis) effects(img, cam, nowMs);
    }

    /** Sky, ground, the grid, the far structures (outlines glowing), a haze at the horizon. */
    private void world(Graphics2D g, Scene.Camera cam, Scene.View v, int w, int h, long nowMs, boolean analysis) {
        g.setPaint(new GradientPaint(0, 0, SKY_TOP, 0, h, SKY_LOW));
        g.fillRect(0, 0, w, h);
        double[] a = cam.horizon(-1), b = cam.horizon(1);
        if (a != null && b != null) {
            double dx = b[0] - a[0], dy = b[1] - a[1];
            double len = Math.max(1e-6, Math.hypot(dx, dy));
            double ux = dx / len, uy = dy / len;
            double nx = -uy, ny = ux;
            if (!cam.towardGround(nx, ny)) {
                nx = -nx;
                ny = -ny;
            }
            double far = (w + h) * 4.0;
            Path2D ground = new Path2D.Double();
            ground.moveTo(a[0] - ux * far, a[1] - uy * far);
            ground.lineTo(a[0] + ux * far, a[1] + uy * far);
            ground.lineTo(a[0] + ux * far + nx * far, a[1] + uy * far + ny * far);
            ground.lineTo(a[0] - ux * far + nx * far, a[1] - uy * far + ny * far);
            ground.closePath();
            // the ground pales into the haze at the horizon
            float hx = (float) a[0], hy = (float) a[1];
            g.setPaint(new GradientPaint(hx, hy, new Color(0xdde4ec), (float) (hx + nx * h * 0.35), (float) (hy + ny * h * 0.35), GROUND));
            g.fill(ground);
        } else if (cam.lookingDown()) {
            g.setColor(GROUND);
            g.fillRect(0, 0, w, h);
        }
        float px = Math.max(1f, w / 640f);
        // the data grid: every 10 m, stronger every 50
        double step = 10, reach = 200;
        double gn = Math.floor(v.north() / step) * step, ge = Math.floor(v.east() / step) * step;
        g.setStroke(new BasicStroke(px * 0.8f));
        g.setColor(new Color(GRID.getRed(), GRID.getGreen(), GRID.getBlue(), 70));
        for (double n = gn - reach; n <= gn + reach; n += step) if (Math.floorMod((long) n, 50) != 0) cam.line(g, n, ge - reach, 0, n, ge + reach, 0);
        for (double e = ge - reach; e <= ge + reach; e += step) if (Math.floorMod((long) e, 50) != 0) cam.line(g, gn - reach, e, 0, gn + reach, e, 0);
        // and a fine one close by, every 2 m (the floor under the camera)
        double fine = 2, near = 24;
        double fn = Math.floor(v.north() / fine) * fine, fe = Math.floor(v.east() / fine) * fine;
        g.setColor(new Color(GRID.getRed(), GRID.getGreen(), GRID.getBlue(), 45));
        for (double n = fn - near; n <= fn + near; n += fine) cam.line(g, n, fe - near, 0, n, fe + near, 0);
        for (double e = fe - near; e <= fe + near; e += fine) cam.line(g, fn - near, e, 0, fn + near, e, 0);
        g.setStroke(new BasicStroke(px * 1.2f));
        g.setColor(new Color(GRID_MAJOR.getRed(), GRID_MAJOR.getGreen(), GRID_MAJOR.getBlue(), 110));
        for (double n = gn - reach; n <= gn + reach; n += step) if (Math.floorMod((long) n, 50) == 0) cam.line(g, n, ge - reach, 0, n, ge + reach, 0);
        for (double e = ge - reach; e <= ge + reach; e += step) if (Math.floorMod((long) e, 50) == 0) cam.line(g, gn - reach, e, 0, gn + reach, e, 0);
        // the far structures: pale blocks with glowing edges
        for (double[] s : structures) structure(g, cam, s, px);
        if (analysis) return;
        // floating fragments: small squares that shimmer where they hang (they never travel)
        double t = nowMs / 1000.0;
        for (double[] f : fragments) {
            double[] c = cam.cam(f[0], f[1], f[2]);
            if (c[2] < 1) continue;
            double sx = cam.cx + cam.f * c[0] / c[2], sy = cam.cy + cam.f * c[1] / c[2];
            double size = Math.max(1.5, cam.f * f[4] / c[2]);
            int alpha = (int) (60 + 60 * Math.sin(t * 1.7 + f[3]));
            if (alpha <= 8) continue;
            g.setColor(new Color(255, 255, 255, Math.min(255, alpha + 60)));
            g.fill(new java.awt.geom.Rectangle2D.Double(sx - size / 2, sy - size / 2, size, size));
            g.setColor(new Color(EDGE.getRed(), EDGE.getGreen(), EDGE.getBlue(), alpha));
            g.draw(new java.awt.geom.Rectangle2D.Double(sx - size, sy - size, size * 2, size * 2));
        }
    }

    /** A far block {north, east, width, depth, height, turn}: its faces filled pale, its edges glowing. */
    private static void structure(Graphics2D g, Scene.Camera cam, double[] s, float px) {
        double c = Math.cos(s[5]), sn = Math.sin(s[5]);
        double[][] base = new double[4][];
        double[][] dirs = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 4; i++) {
            double a = dirs[i][0] * s[2] / 2, b = dirs[i][1] * s[3] / 2;
            base[i] = new double[]{s[0] + a * c - b * sn, s[1] + a * sn + b * c};
        }
        List<double[]> pts = new ArrayList<>();
        for (double[] p : base) {
            for (double z : new double[]{0, s[4]}) {
                double[] q = cam.cam(p[0], p[1], z);
                if (q[2] < 1) return;
                pts.add(new double[]{cam.cx + cam.f * q[0] / q[2], cam.cy + cam.f * q[1] / q[2]});
            }
        }
        // the silhouette
        pts.sort((u, v) -> u[0] != v[0] ? Double.compare(u[0], v[0]) : Double.compare(u[1], v[1]));
        List<double[]> hull = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            int start = hull.size();
            for (int j = 0; j < pts.size(); j++) {
                double[] p = pass == 0 ? pts.get(j) : pts.get(pts.size() - 1 - j);
                while (hull.size() >= start + 2) {
                    double[] o = hull.get(hull.size() - 2), a = hull.get(hull.size() - 1);
                    if ((a[0] - o[0]) * (p[1] - o[1]) - (a[1] - o[1]) * (p[0] - o[0]) > 0) break;
                    hull.remove(hull.size() - 1);
                }
                hull.add(p);
            }
            hull.remove(hull.size() - 1);
        }
        Path2D path = new Path2D.Double();
        for (int i = 0; i < hull.size(); i++) {
            if (i == 0) path.moveTo(hull.get(i)[0], hull.get(i)[1]);
            else path.lineTo(hull.get(i)[0], hull.get(i)[1]);
        }
        path.closePath();
        g.setColor(new Color(250, 252, 255, 120));
        g.fill(path);
        g.setStroke(new BasicStroke(px));
        g.setColor(new Color(EDGE.getRed(), EDGE.getGreen(), EDGE.getBlue(), 150));
        for (int i = 0; i < 4; i++) {
            double[] p = base[i], n = base[(i + 1) % 4];
            cam.line(g, p[0], p[1], 0, n[0], n[1], 0);
            cam.line(g, p[0], p[1], s[4], n[0], n[1], s[4]);
            cam.line(g, p[0], p[1], 0, p[0], p[1], s[4]);
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
    private void vehicle(int[] px, int w, int h, Scene.Camera cam, Scene.View v, double[] q) {
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
            float light = (float) (0.30 + 0.26 * sky + 0.78 * sun);
            double dist = b.cz[i0];
            float fog = (float) (1 - Math.exp(-dist * fogK));
            raster(px, b.z, w, h, b, i0, i1, i2, m.tex()[i0], light, fog);
        }
    }

    /** One triangle, perspective-correct texture, nearest texel. */
    private void raster(int[] px, float[] zb, int w, int h, Buffers b, int i0, int i1, int i2, int texId, float light, float fog) {
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
        int haze = 0xdde4ec;
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

    /** The simulation's screen: faint scan lines, a cool edge glow, and now and then a glitch band. */
    private static void effects(BufferedImage img, Scene.Camera cam, long nowMs) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = Scene.pixels(img);
        int every = Math.max(2, h / 240);
        for (int y = 0; y < h; y += every) {
            int o = y * w;
            for (int x = 0; x < w; x++) {
                int c = px[o + x];
                px[o + x] = (c & 0xff000000) | ((((c >> 16) & 0xff) * 243 >> 8) << 16) | ((((c >> 8) & 0xff) * 245 >> 8) << 8) | ((c & 0xff) * 250 >> 8);
            }
        }
        // a glitch: about every 5 s for 0.18 s, a band of rows slips sideways with a cyan cast
        long cycle = 5300, at = nowMs % cycle;
        if (at < 180) {
            Random r = new Random(nowMs / cycle * 31 + at / 60);
            int bands = 1 + r.nextInt(2);
            for (int k = 0; k < bands; k++) {
                int y0 = r.nextInt(Math.max(1, h - h / 10)), bh = Math.max(2, h / 60 + r.nextInt(Math.max(1, h / 25)));
                int shift = (r.nextBoolean() ? 1 : -1) * (w / 80 + r.nextInt(Math.max(1, w / 40)));
                int[] row = new int[w];
                for (int y = y0; y < Math.min(h, y0 + bh); y++) {
                    System.arraycopy(px, y * w, row, 0, w);
                    for (int x = 0; x < w; x++) {
                        int c = row[Math.floorMod(x - shift, w)];
                        int rr = ((c >> 16) & 0xff) * 200 >> 8, gg = Math.min(255, (((c >> 8) & 0xff) * 240 >> 8) + 12), bb = Math.min(255, (c & 0xff) + 30);
                        px[y * w + x] = 0xff000000 | (rr << 16) | (gg << 8) | bb;
                    }
                }
            }
        }
    }

    private static double[] unit(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }
}
