package de.droiddrone.pc.gui;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/**
 * A quad drawn in 3D as Betaflight sees it (1.0.42, Drone setup &gt; Orientation): it tilts and turns with Betaflight's
 * own attitude, seen from behind and above - the way the pilot stands at the bench, the drone's front away. The
 * drone is a textured mesh (/model/fpv-drone.bin.gz, made by tools/model/export_fpv_drone.py, see THIRD-PARTY.md),
 * rasterised with a depth buffer at 2 x 2 sub-pixels; the front props tinted red. A fixed grid on the ground under it.
 *
 * <p>Model space is the drone's (x forward, y right, z down, metres); Betaflight's roll positive = right side down,
 * pitch positive = nose down.
 */
final class DroneModelView extends JComponent {
    private static final String MESH = "/model/fpv-drone.bin.gz";
    private static final String[] TEXTURES = {"/model/fpv-drone-0.jpg", "/model/fpv-drone-1.jpg"};
    private static final int TEX = 1024;
    /** Flag bits per vertex: the texture (0, 1; 2 = none) and a front prop. */
    private static final int TEXTURE_BITS = 3, FRONT_PROP = 4;
    private static final int PLAIN = 0x3a3d40, FRONT_TINT = 0xdc3c32;

    private static final double GROUND = 0.09;
    /** The view: from behind, a little to the left, and above. */
    private static final double AZIMUTH = Math.toRadians(-18), ELEVATION = Math.toRadians(26), DISTANCE = 0.62;
    /** Sub-pixels per pixel along each axis. */
    private static final int SS = 2;

    private record Mesh(float[] p, float[] u, float[] v, byte[] flag, int[] tri, int[][] tex) {}

    private static Mesh mesh;

    private static synchronized Mesh mesh() {
        if (mesh == null) {
            try (InputStream in = DroneModelView.class.getResourceAsStream(MESH)) {
                if (in == null) throw new IOException(MESH + " missing");
                mesh = load(new DataInputStream(new GZIPInputStream(in, 1 << 16)));
            } catch (IOException e) {
                throw new IllegalStateException("The drone model could not be read: " + e.getMessage(), e);
            }
        }
        return mesh;
    }

    private static Mesh load(DataInputStream in) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!new String(magic, java.nio.charset.StandardCharsets.US_ASCII).equals("DRM1")) throw new IOException("not a DRM1 mesh");
        int nv = in.readInt();
        float[] p = new float[nv * 3], u = new float[nv], v = new float[nv];
        byte[] flag = new byte[nv];
        for (int i = 0; i < nv; i++) {
            p[i * 3] = in.readFloat();
            p[i * 3 + 1] = in.readFloat();
            p[i * 3 + 2] = in.readFloat();
            u[i] = in.readFloat();
            v[i] = in.readFloat();
            flag[i] = in.readByte();
        }
        int[] tri = new int[in.readInt() * 3];
        for (int i = 0; i < tri.length; i++) tri[i] = in.readUnsignedShort();
        int[][] tex = new int[TEXTURES.length][];
        for (int k = 0; k < TEXTURES.length; k++) {
            try (InputStream t = DroneModelView.class.getResourceAsStream(TEXTURES[k])) {
                if (t == null) throw new IOException(TEXTURES[k] + " missing");
                BufferedImage img = ImageIO.read(t);
                if (img == null || img.getWidth() != TEX || img.getHeight() != TEX) throw new IOException(TEXTURES[k] + " is not " + TEX + " square");
                tex[k] = img.getRGB(0, 0, TEX, TEX, null, 0, TEX);
            }
        }
        return new Mesh(p, u, v, flag, tri, tex);
    }

    private final Mesh m = mesh();
    private volatile double roll, pitch, yaw; // degrees, Betaflight's signs
    private volatile boolean live;

    // per frame
    private final float[] wx, wy, wz, sx, sy, iz;
    private int bufW, bufH;
    private float[] zbuf = new float[0];
    private int[] cbuf = new int[0];
    private BufferedImage frame;
    private double[] lastPose;

    DroneModelView() {
        setPreferredSize(new Dimension(520, 380));
        setOpaque(false);
        int nv = m.u().length;
        wx = new float[nv];
        wy = new float[nv];
        wz = new float[nv];
        sx = new float[nv];
        sy = new float[nv];
        iz = new float[nv];
    }

    /** Betaflight's attitude; yaw relative to where it started (degrees, clockwise from above). */
    void setAttitude(double rollDeg, double pitchDeg, double yawDeg) {
        roll = rollDeg;
        pitch = pitchDeg;
        yaw = yawDeg;
        live = true;
        repaint();
    }

    /** An "N" at the grid's far edge (the orientation check's compass steps: north is ahead of the pilot). */
    private boolean northMark;

    void setNorthMark(boolean on) {
        northMark = on;
        repaint();
    }

    /** No reading (not connected): the drone is drawn flat and dim. */
    void clear() {
        live = false;
        roll = pitch = yaw = 0;
        repaint();
    }

    // ------------------------------------------------------------------ geometry

    /** Model to world: yaw, then pitch, then roll (Betaflight's pitch positive = nose down: NED's negative). */
    static double[][] rotation(double rollDeg, double pitchDeg, double yawDeg) {
        double r = Math.toRadians(rollDeg), p = Math.toRadians(-pitchDeg), y = Math.toRadians(yawDeg);
        double cr = Math.cos(r), sr = Math.sin(r), cp = Math.cos(p), sp = Math.sin(p), cy = Math.cos(y), sy = Math.sin(y);
        return new double[][]{
                {cy * cp, cy * sp * sr - sy * cr, cy * sp * cr + sy * sr},
                {sy * cp, sy * sp * sr + cy * cr, sy * sp * cr - cy * sr},
                {-sp, cp * sr, cp * cr}};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] unit(double[] a) {
        double l = Math.sqrt(dot(a, a));
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }

    /** The camera: where it is, and its forward, right and down axes (world, z down). */
    private static final double[] EYE, FORWARD, RIGHT, DOWN;
    /** Towards the light: from above, front left. */
    private static final double[] LIGHT = unit(new double[]{0.35, -0.45, -1});

    static {
        double ce = Math.cos(ELEVATION);
        EYE = new double[]{-ce * Math.cos(AZIMUTH) * DISTANCE, -ce * Math.sin(AZIMUTH) * DISTANCE, -Math.sin(ELEVATION) * DISTANCE};
        FORWARD = unit(new double[]{-EYE[0], -EYE[1], -EYE[2]});
        RIGHT = unit(cross(FORWARD, new double[]{0, 0, -1}));
        DOWN = cross(FORWARD, RIGHT);
    }

    private double scale, cx, cy;

    /** World to screen: {x, y, depth}. */
    private double[] project(double x, double y, double z) {
        double[] d = {x - EYE[0], y - EYE[1], z - EYE[2]};
        double depth = dot(d, FORWARD);
        return new double[]{cx + scale * dot(d, RIGHT) / depth, cy + scale * dot(d, DOWN) / depth, depth};
    }

    private Path2D.Double line(double[] a, double[] b) {
        double[] s = project(a[0], a[1], a[2]), e = project(b[0], b[1], b[2]);
        Path2D.Double path = new Path2D.Double();
        path.moveTo(s[0], s[1]);
        path.lineTo(e[0], e[1]);
        return path;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void paintComponent(Graphics graphics) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        scale = Math.min(w, h * 1.25) * 1.3;
        cx = w / 2.0;
        cy = h * 0.52;
        double[][] rot = rotation(roll, pitch, yaw);
        BufferedImage drone = render(w, h, rot);

        // the ground: a fixed grid, and the drone's shadow straight down on it
        g.setStroke(new BasicStroke(1f));
        g.setColor(Theme.alpha(Theme.LINE, 150));
        for (int i = -5; i <= 5; i++) {
            double t = i * 0.05;
            g.draw(line(new double[]{t, -0.25, GROUND}, new double[]{t, 0.25, GROUND}));
            g.draw(line(new double[]{-0.25, t, GROUND}, new double[]{0.25, t, GROUND}));
        }
        g.setColor(new Color(0, 0, 0, 60));
        g.fill(shadow());

        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(drone, 0, 0, null);
        if (northMark) { // past the grid's far edge (whichever of the four lies highest on the screen), over the drone
            double[] far = null;
            for (double[] p : new double[][]{{0.31, 0}, {-0.31, 0}, {0, 0.31}, {0, -0.31}}) {
                double[] s = project(p[0], p[1], GROUND);
                if (far == null || s[1] < far[1]) far = s;
            }
            g.setFont(Theme.headingFont(20f));
            g.setColor(Theme.ACCENT);
            String n = "N";
            g.drawString(n, (float) (far[0] - g.getFontMetrics().stringWidth(n) / 2.0), (float) far[1]);
        }
        g.setFont(Theme.headingFont(12f));
        g.setColor(Theme.MUTED);
        g.drawString("RED PROPS = FRONT", 10, h - 10);
        if (!live) {
            g.setFont(Theme.font(Font.BOLD, 14f));
            String s = "Waiting for Betaflight...";
            g.drawString(s, (w - g.getFontMetrics().stringWidth(s)) / 2, 24);
        }
        g.dispose();
    }

    /** Every SHADOW_STEP-th vertex makes the shadow: the same outline at a fraction of the cost. */
    private static final int SHADOW_STEP = 8;

    /** The drone's outline on the ground: the hull of its vertices dropped straight down (world from the last render). */
    private Area shadow() {
        int n = (wx.length + SHADOW_STEP - 1) / SHADOW_STEP;
        double[][] pts = new double[n][];
        for (int i = 0; i < n; i++) {
            int v = i * SHADOW_STEP;
            double[] s = project(wx[v], wy[v], GROUND);
            pts[i] = new double[]{s[0], s[1]};
        }
        Arrays.sort(pts, (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        double[][] hull = new double[2 * n][];
        int k = 0;
        for (int pass = 0; pass < 2; pass++) { // lower then upper chain (Andrew's monotone chain)
            int start = k;
            for (int j = 0; j < n; j++) {
                double[] p = pts[pass == 0 ? j : n - 1 - j];
                while (k >= start + 2 && turn(hull[k - 2], hull[k - 1], p) <= 0) k--;
                hull[k++] = p;
            }
            k--;
        }
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < k; i++) {
            if (i == 0) path.moveTo(hull[i][0], hull[i][1]);
            else path.lineTo(hull[i][0], hull[i][1]);
        }
        path.closePath();
        return new Area(path);
    }

    private static double turn(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    private BufferedImage render(int w, int h, double[][] rot) {
        double[] pose = {w, h, roll, pitch, yaw, live ? 1 : 0};
        if (frame != null && Arrays.equals(pose, lastPose)) return frame;
        lastPose = pose;
        int bw = w * SS, bh = h * SS;
        if (bw != bufW || bh != bufH) {
            bufW = bw;
            bufH = bh;
            zbuf = new float[bw * bh];
            cbuf = new int[bw * bh];
            frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        }
        float[] p = m.p();
        double f = scale * SS, ox = cx * SS, oy = cy * SS;
        for (int i = 0, k = 0; i < wx.length; i++, k += 3) {
            double x = rot[0][0] * p[k] + rot[0][1] * p[k + 1] + rot[0][2] * p[k + 2];
            double y = rot[1][0] * p[k] + rot[1][1] * p[k + 1] + rot[1][2] * p[k + 2];
            double z = rot[2][0] * p[k] + rot[2][1] * p[k + 1] + rot[2][2] * p[k + 2];
            wx[i] = (float) x;
            wy[i] = (float) y;
            wz[i] = (float) z;
            double dx = x - EYE[0], dy = y - EYE[1], dz = z - EYE[2];
            double depth = dx * FORWARD[0] + dy * FORWARD[1] + dz * FORWARD[2];
            sx[i] = (float) (ox + f * (dx * RIGHT[0] + dy * RIGHT[1] + dz * RIGHT[2]) / depth);
            sy[i] = (float) (oy + f * (dx * DOWN[0] + dy * DOWN[1] + dz * DOWN[2]) / depth);
            iz[i] = (float) (1 / depth);
        }
        Arrays.fill(zbuf, 0f);
        double dim = live ? 1 : 0.6;
        inBands(bh, (from, to) -> rasterBand(from, to, dim));
        int[] out = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
        inBands(h, (from, to) -> {
            for (int y = from; y < to; y++) {
                for (int x = 0; x < w; x++) {
                    int covered = 0, r = 0, g = 0, b = 0;
                    for (int q = 0; q < SS * SS; q++) {
                        int i = (y * SS + q / SS) * bw + x * SS + q % SS;
                        if (zbuf[i] == 0f) continue;
                        int c = cbuf[i];
                        covered++;
                        r += c >> 16 & 0xff;
                        g += c >> 8 & 0xff;
                        b += c & 0xff;
                    }
                    out[y * w + x] = covered == 0 ? 0
                            : (covered * 255 / (SS * SS)) << 24 | (r / covered) << 16 | (g / covered) << 8 | b / covered;
                }
            }
        });
        return frame;
    }

    /** Rows split into bands, one task each: every band writes only its own rows. */
    private static final int BANDS = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);

    private static void inBands(int rows, java.util.function.BiConsumer<Integer, Integer> band) {
        int n = Math.min(BANDS, Math.max(1, rows));
        java.util.stream.IntStream.range(0, n).parallel().forEach(i -> band.accept(rows * i / n, rows * (i + 1) / n));
    }

    /** Every triangle's sub-pixels in rows [rowFrom, rowTo): the nearest kept, textured and lit. */
    private void rasterBand(int rowFrom, int rowTo, double dim) {
        int[] tri = m.tri();
        float[] tu = m.u(), tv = m.v();
        byte[] flag = m.flag();
        for (int t = 0; t < tri.length; t += 3) {
            int a = tri[t], b = tri[t + 1], c = tri[t + 2];
            float x0 = sx[a], y0 = sy[a], x1 = sx[b], y1 = sy[b], x2 = sx[c], y2 = sy[c];
            int minY = Math.max(rowFrom, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
            int maxY = Math.min(rowTo - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
            if (minY > maxY) continue;
            float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
            if (Math.abs(area) < 1e-5f) continue;
            int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
            int maxX = Math.min(bufW - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
            if (minX > maxX) continue;
            // the face's light: its normal in the world, turned to the eye
            double ex = wx[b] - wx[a], ey = wy[b] - wy[a], ez = wz[b] - wz[a];
            double fx = wx[c] - wx[a], fy = wy[c] - wy[a], fz = wz[c] - wz[a];
            double nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
            double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl == 0) continue;
            if (nx * (EYE[0] - wx[a]) + ny * (EYE[1] - wy[a]) + nz * (EYE[2] - wz[a]) < 0) continue; // its back: the mesh is closed, so a front is nearer (half the work)
            double diffuse = Math.max(0, (nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]) / nl);
            float light = (float) ((0.62 + 0.75 * diffuse) * dim);
            int fl = flag[a], texture = fl & TEXTURE_BITS;
            int[] tex = texture < m.tex().length ? m.tex()[texture] : null;
            boolean front = (fl & FRONT_PROP) != 0;
            float inv = 1 / area, z0 = iz[a], z1 = iz[b], z2 = iz[c];
            float u0 = tu[a] * z0, u1 = tu[b] * z1, u2 = tu[c] * z2, v0 = tv[a] * z0, v1 = tv[b] * z1, v2 = tv[c] * z2;
            for (int y = minY; y <= maxY; y++) {
                float py = y + 0.5f;
                int row = y * bufW;
                for (int x = minX; x <= maxX; x++) {
                    float px = x + 0.5f;
                    float w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) * inv;
                    float w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) * inv;
                    float w2 = 1 - w0 - w1;
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                    float depth = w0 * z0 + w1 * z1 + w2 * z2;
                    int idx = row + x;
                    if (depth <= zbuf[idx]) continue;
                    zbuf[idx] = depth;
                    int colour = PLAIN;
                    if (tex != null) {
                        float u = (w0 * u0 + w1 * u1 + w2 * u2) / depth, v = (w0 * v0 + w1 * v1 + w2 * v2) / depth;
                        int tx = (int) ((u - (float) Math.floor(u)) * TEX) & (TEX - 1);
                        int ty = (int) ((1 - (v - (float) Math.floor(v))) * TEX) & (TEX - 1);
                        colour = tex[ty * TEX + tx];
                    }
                    if (front) colour = mix(colour, FRONT_TINT);
                    cbuf[idx] = lit(colour, light);
                }
            }
        }
    }

    /** Half the colour, half the front props' red. */
    private static int mix(int c, int tint) {
        return ((c >> 1) & 0x7f7f7f) + ((tint >> 1) & 0x7f7f7f);
    }

    private static int lit(int c, float k) {
        int r = Math.min(255, (int) ((c >> 16 & 0xff) * k)), g = Math.min(255, (int) ((c >> 8 & 0xff) * k)), b = Math.min(255, (int) ((c & 0xff) * k));
        return r << 16 | g << 8 | b;
    }
}
