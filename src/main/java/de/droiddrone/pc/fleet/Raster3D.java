package de.droiddrone.pc.fleet;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Flat-coloured meshes drawn into a virtual drone's picture through a depth buffer (the tour's scenes, 2026-11-09):
 * Kenney's CC0 props (/model/props.bin.gz, tools/model/export_kenney.py, see THIRD-PARTY.md), the FPV drone of Drone
 * setup &gt; Orientation (/model/fpv-drone.bin.gz, its texture baked into a colour per triangle and its mesh thinned for
 * the video's speed) and boxes made here (race gates). Each triangle one colour, lit by a low sun; seen through the
 * drone's {@link Scene.Camera}.
 * <p>
 * Model axes: x forward, y left, z up, metres. Placed by north, east, up, heading (clockwise from north), pitch (nose
 * up +), roll (right side down +) and a scale. One {@link Frame} per thread and picture size: the video's and the
 * motion detection's pictures are drawn at once on their own threads.
 */
final class Raster3D {
    /** A mesh: vertices (x y z), triangles (three indices each) and one RGB colour per triangle. */
    record Mesh(float[] p, int[] tri, int[] rgb) {
        int triangles() {
            return rgb.length;
        }
    }

    /** The sun: from the south-west, 35 degrees up (north, east, up). */
    private static final double[] SUN = unit(new double[]{-0.574, -0.574, 0.70});

    private Raster3D() {
    }

    // ------------------------------------------------------------------ the meshes

    private static Map<String, Mesh> props;
    private static Mesh fpv;

    /** A Kenney prop by its file name (vehicle-truck-red, building-small-a ...). */
    static synchronized Mesh prop(String name) {
        if (props == null) {
            try (InputStream in = Raster3D.class.getResourceAsStream("/model/props.bin.gz")) {
                if (in == null) throw new IOException("/model/props.bin.gz missing");
                props = loadProps(new DataInputStream(new GZIPInputStream(in, 1 << 16)));
            } catch (IOException e) {
                throw new IllegalStateException("The props could not be read: " + e.getMessage(), e);
            }
        }
        Mesh m = props.get(name);
        if (m == null) throw new IllegalArgumentException("no prop " + name);
        return m;
    }

    static Map<String, Mesh> loadProps(DataInputStream in) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!new String(magic, java.nio.charset.StandardCharsets.US_ASCII).equals("PRP1")) throw new IOException("not a PRP1 file");
        int n = in.readInt();
        Map<String, Mesh> out = new HashMap<>();
        for (int k = 0; k < n; k++) {
            String name = in.readUTF();
            int nv = in.readInt(), nt = in.readInt();
            float[] p = new float[nv * 3];
            for (int i = 0; i < p.length; i++) p[i] = in.readFloat();
            int[] tri = new int[nt * 3], rgb = new int[nt];
            for (int t = 0; t < nt; t++) {
                tri[t * 3] = in.readUnsignedShort();
                tri[t * 3 + 1] = in.readUnsignedShort();
                tri[t * 3 + 2] = in.readUnsignedShort();
                rgb[t] = in.readInt();
            }
            out.put(name, new Mesh(p, tri, rgb));
        }
        return out;
    }

    /** The FPV drone (its texture baked, thinned to about a tenth), front forward, 0.3 m across. */
    static synchronized Mesh fpvDrone() {
        if (fpv == null) {
            try (InputStream in = Raster3D.class.getResourceAsStream("/model/fpv-drone.bin.gz")) {
                if (in == null) throw new IOException("/model/fpv-drone.bin.gz missing");
                fpv = thin(loadDrone(new DataInputStream(new GZIPInputStream(in, 1 << 16))), FPV_CELL_M);
            } catch (IOException e) {
                throw new IllegalStateException("The drone model could not be read: " + e.getMessage(), e);
            }
        }
        return fpv;
    }

    /** The FPV mesh's vertices are welded on a grid this fine (59 000 triangles to some thousands). */
    static final double FPV_CELL_M = 0.006;
    private static final int TEX = 1024, FRONT_PROP = 4, TEXTURE_BITS = 3;

    /** DroneModelView's DRM1 (x forward, y right, z down) into this class's axes, a colour per triangle from its texture. */
    private static Mesh loadDrone(DataInputStream in) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!new String(magic, java.nio.charset.StandardCharsets.US_ASCII).equals("DRM1")) throw new IOException("not a DRM1 mesh");
        int nv = in.readInt();
        float[] p = new float[nv * 3], u = new float[nv], v = new float[nv];
        byte[] flag = new byte[nv];
        for (int i = 0; i < nv; i++) {
            p[i * 3] = in.readFloat();
            p[i * 3 + 1] = -in.readFloat(); // right to left
            p[i * 3 + 2] = -in.readFloat(); // down to up
            u[i] = in.readFloat();
            v[i] = in.readFloat();
            flag[i] = in.readByte();
        }
        int[] tri = new int[in.readInt() * 3];
        for (int i = 0; i < tri.length; i++) tri[i] = in.readUnsignedShort();
        int[][] tex = new int[2][];
        for (int k = 0; k < 2; k++) {
            try (InputStream t = Raster3D.class.getResourceAsStream("/model/fpv-drone-" + k + ".jpg")) {
                if (t == null) throw new IOException("fpv-drone-" + k + ".jpg missing");
                BufferedImage img = ImageIO.read(t);
                if (img == null || img.getWidth() != TEX || img.getHeight() != TEX) throw new IOException("fpv-drone-" + k + ".jpg is not " + TEX + " square");
                tex[k] = img.getRGB(0, 0, TEX, TEX, null, 0, TEX);
            }
        }
        int nt = tri.length / 3;
        int[] rgb = new int[nt];
        for (int t = 0; t < nt; t++) {
            int a = tri[t * 3], b = tri[t * 3 + 1], c = tri[t * 3 + 2];
            int which = flag[a] & TEXTURE_BITS;
            int col = 0x3a3d40;
            if (which < 2) {
                float uu = (u[a] + u[b] + u[c]) / 3, vv = (v[a] + v[b] + v[c]) / 3;
                int tx = (int) ((uu - Math.floor(uu)) * TEX) & (TEX - 1), ty = (int) ((1 - (vv - Math.floor(vv))) * TEX) & (TEX - 1);
                col = tex[which][ty * TEX + tx] & 0xffffff;
            }
            if ((flag[a] & FRONT_PROP) != 0) col = ((col >> 1) & 0x7f7f7f) + ((0xdc3c32 >> 1) & 0x7f7f7f);
            rgb[t] = col;
        }
        return new Mesh(p, tri, rgb);
    }

    /**
     * Vertex clustering: every vertex moved to its cell's first, triangles that collapse dropped, a triangle's twin
     * (same corners) dropped. The shape stays to the cell's size; a small far quad looks the same.
     */
    static Mesh thin(Mesh m, double cell) {
        int nv = m.p().length / 3;
        Map<Long, Integer> cells = new HashMap<>();
        int[] remap = new int[nv];
        List<Float> pts = new ArrayList<>();
        for (int i = 0; i < nv; i++) {
            long kx = Math.round(m.p()[i * 3] / cell), ky = Math.round(m.p()[i * 3 + 1] / cell), kz = Math.round(m.p()[i * 3 + 2] / cell);
            long key = (kx & 0x1fffff) << 42 | (ky & 0x1fffff) << 21 | (kz & 0x1fffff);
            Integer at = cells.get(key);
            if (at == null) {
                at = pts.size() / 3;
                cells.put(key, at);
                pts.add(m.p()[i * 3]);
                pts.add(m.p()[i * 3 + 1]);
                pts.add(m.p()[i * 3 + 2]);
            }
            remap[i] = at;
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<int[]> tris = new ArrayList<>();
        for (int t = 0; t < m.triangles(); t++) {
            int a = remap[m.tri()[t * 3]], b = remap[m.tri()[t * 3 + 1]], c = remap[m.tri()[t * 3 + 2]];
            if (a == b || b == c || a == c) continue;
            int lo = Math.min(a, Math.min(b, c)), hi = Math.max(a, Math.max(b, c)), mid = a + b + c - lo - hi;
            if (!seen.add(((long) lo << 42) | ((long) mid << 21) | hi)) continue;
            tris.add(new int[]{a, b, c, m.rgb()[t]});
        }
        float[] p = new float[pts.size()];
        for (int i = 0; i < p.length; i++) p[i] = pts.get(i);
        int[] tri = new int[tris.size() * 3], rgb = new int[tris.size()];
        for (int t = 0; t < tris.size(); t++) {
            int[] x = tris.get(t);
            tri[t * 3] = x[0];
            tri[t * 3 + 1] = x[1];
            tri[t * 3 + 2] = x[2];
            rgb[t] = x[3];
        }
        return new Mesh(p, tri, rgb);
    }

    /** Boxes made into one mesh: each {x0, y0, z0, x1, y1, z1} with its colour. */
    static Mesh boxes(double[][] boxes, int[] colours) {
        float[] p = new float[boxes.length * 8 * 3];
        int[] tri = new int[boxes.length * 12 * 3], rgb = new int[boxes.length * 12];
        int[][] faces = {{0, 2, 1}, {0, 3, 2}, {4, 5, 6}, {4, 6, 7}, {0, 1, 5}, {0, 5, 4}, {2, 3, 7}, {2, 7, 6}, {1, 2, 6}, {1, 6, 5}, {0, 4, 7}, {0, 7, 3}};
        for (int b = 0; b < boxes.length; b++) {
            double[] q = boxes[b];
            double[][] c = {{q[0], q[1], q[2]}, {q[3], q[1], q[2]}, {q[3], q[4], q[2]}, {q[0], q[4], q[2]},
                    {q[0], q[1], q[5]}, {q[3], q[1], q[5]}, {q[3], q[4], q[5]}, {q[0], q[4], q[5]}};
            for (int i = 0; i < 8; i++) {
                p[(b * 8 + i) * 3] = (float) c[i][0];
                p[(b * 8 + i) * 3 + 1] = (float) c[i][1];
                p[(b * 8 + i) * 3 + 2] = (float) c[i][2];
            }
            for (int f = 0; f < 12; f++) {
                tri[(b * 12 + f) * 3] = b * 8 + faces[f][0];
                tri[(b * 12 + f) * 3 + 1] = b * 8 + faces[f][1];
                tri[(b * 12 + f) * 3 + 2] = b * 8 + faces[f][2];
                rgb[b * 12 + f] = colours[b];
            }
        }
        return new Mesh(p, tri, rgb);
    }

    // ------------------------------------------------------------------ drawing

    /** A picture's depth buffer, one per thread and size; cleared for each picture. */
    static final class Frame {
        final int w, h;
        final float[] depth;
        int[] px;

        Frame(int w, int h) {
            this.w = w;
            this.h = h;
            depth = new float[w * h];
        }

        /** The picture's pixels drawn into from now on (TYPE_INT_RGB), the depth cleared. */
        void begin(BufferedImage img) {
            px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            java.util.Arrays.fill(depth, 0f);
        }
    }

    private static final ThreadLocal<Frame> FRAMES = new ThreadLocal<>();

    /** This thread's frame for the picture, cleared (img: TYPE_INT_RGB). */
    static Frame frame(BufferedImage img) {
        Frame f = FRAMES.get();
        if (f == null || f.w != img.getWidth() || f.h != img.getHeight()) {
            f = new Frame(img.getWidth(), img.getHeight());
            FRAMES.set(f);
        }
        f.begin(img);
        return f;
    }

    /** The nearest the camera draws: closer triangles are left out (a gate flown through). */
    private static final double NEAR_M = 0.15;

    /**
     * The mesh at north, east, up (metres, the scene's), turned by heading / pitch / roll (degrees), scale times its
     * size; tint (an RGB, or -1) mixed in by half.
     */
    static void draw(Frame f, Scene.Camera cam, Mesh m, double n, double e, double u, double headingDeg, double pitchDeg,
                     double rollDeg, double scale, int tint) {
        double h = Math.toRadians(headingDeg), pt = Math.toRadians(pitchDeg), rl = Math.toRadians(rollDeg);
        double[] fwd = {Math.cos(h), Math.sin(h), 0}, left = {Math.sin(h), -Math.cos(h), 0}, up = {0, 0, 1};
        double[] fwd1 = add(scale(fwd, Math.cos(pt)), scale(up, Math.sin(pt)));
        double[] up1 = add(scale(up, Math.cos(pt)), scale(fwd, -Math.sin(pt)));
        double[] left2 = add(scale(left, Math.cos(rl)), scale(up1, Math.sin(rl)));
        double[] up2 = add(scale(up1, Math.cos(rl)), scale(left, -Math.sin(rl)));
        // a quick reject: the mesh's middle well behind the camera
        double[] mid = cam.cam(n, e, u);
        if (mid[2] < -8 * Math.max(1, scale)) return;
        float[] p = m.p();
        int nv = p.length / 3;
        double[] cx = new double[nv], cy = new double[nv], cz = new double[nv];
        double[] wx = new double[nv], wy = new double[nv], wz = new double[nv];
        for (int i = 0; i < nv; i++) {
            double x = p[i * 3] * scale, y = p[i * 3 + 1] * scale, z = p[i * 3 + 2] * scale;
            wx[i] = n + x * fwd1[0] + y * left2[0] + z * up2[0];
            wy[i] = e + x * fwd1[1] + y * left2[1] + z * up2[1];
            wz[i] = u + x * fwd1[2] + y * left2[2] + z * up2[2];
            double[] c = cam.cam(wx[i], wy[i], wz[i]);
            cx[i] = c[0];
            cy[i] = c[1];
            cz[i] = c[2];
        }
        int[] tri = m.tri(), rgb = m.rgb();
        for (int t = 0; t < rgb.length; t++) {
            int a = tri[t * 3], b = tri[t * 3 + 1], c = tri[t * 3 + 2];
            if (cz[a] < NEAR_M || cz[b] < NEAR_M || cz[c] < NEAR_M) continue;
            // the face's light: its normal turned to the camera, against the sun
            double ex = wx[b] - wx[a], ey = wy[b] - wy[a], ez = wz[b] - wz[a];
            double fx = wx[c] - wx[a], fy = wy[c] - wy[a], fz = wz[c] - wz[a];
            double nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
            double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl == 0) continue;
            if (nx * (cam.cn - wx[a]) + ny * (cam.ce - wy[a]) + nz * (cam.cu - wz[a]) < 0) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
            double sun = Math.max(0, (nx * SUN[0] + ny * SUN[1] + nz * SUN[2]) / nl);
            int col = rgb[t];
            if (tint >= 0) col = ((col >> 1) & 0x7f7f7f) + ((tint >> 1) & 0x7f7f7f);
            fill(f, cam, cx[a], cy[a], cz[a], cx[b], cy[b], cz[b], cx[c], cy[c], cz[c], lit(col, 0.62 + 0.55 * sun));
        }
    }

    private static void fill(Frame f, Scene.Camera cam, double x0c, double y0c, double z0, double x1c, double y1c, double z1,
                             double x2c, double y2c, double z2, int colour) {
        float x0 = (float) (cam.cx + cam.f * x0c / z0), y0 = (float) (cam.cy + cam.f * y0c / z0);
        float x1 = (float) (cam.cx + cam.f * x1c / z1), y1 = (float) (cam.cy + cam.f * y1c / z1);
        float x2 = (float) (cam.cx + cam.f * x2c / z2), y2 = (float) (cam.cy + cam.f * y2c / z2);
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(f.w - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(f.h - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) return;
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-6f) return;
        float inv = 1 / area, iz0 = (float) (1 / z0), iz1 = (float) (1 / z1), iz2 = (float) (1 / z2);
        float[] depth = f.depth;
        int[] px = f.px;
        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            int row = y * f.w;
            for (int x = minX; x <= maxX; x++) {
                float qx = x + 0.5f;
                float w0 = ((x1 - qx) * (y2 - py) - (x2 - qx) * (y1 - py)) * inv;
                float w1 = ((x2 - qx) * (y0 - py) - (x0 - qx) * (y2 - py)) * inv;
                float w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float d = w0 * iz0 + w1 * iz1 + w2 * iz2;
                int i = row + x;
                if (d <= depth[i]) continue;
                depth[i] = d;
                px[i] = colour;
            }
        }
    }

    /**
     * A soft dark disc on the ground under something in the air (radius metres, how dark 0..1), so its height reads;
     * drawn before the meshes, into the picture only.
     */
    static void shadow(Frame f, Scene.Camera cam, double n, double e, double radius, double dark) {
        double[] c = cam.cam(n, e, 0);
        if (c[2] < 0.5) return;
        double[] fwdC = cam.cam(n + radius, e, 0), sideC = cam.cam(n, e + radius, 0);
        if (fwdC[2] < 0.3 || sideC[2] < 0.3) return;
        double sx = cam.cx + cam.f * c[0] / c[2], sy = cam.cy + cam.f * c[1] / c[2];
        double ax = cam.cx + cam.f * fwdC[0] / fwdC[2] - sx, ay = cam.cy + cam.f * fwdC[1] / fwdC[2] - sy;
        double bx = cam.cx + cam.f * sideC[0] / sideC[2] - sx, by = cam.cy + cam.f * sideC[1] / sideC[2] - sy;
        double rx = Math.max(Math.abs(ax), Math.abs(bx)), ry = Math.max(Math.abs(ay), Math.abs(by));
        if (rx < 1 || ry < 0.5) return;
        int x0 = Math.max(0, (int) (sx - rx)), x1 = Math.min(f.w - 1, (int) (sx + rx));
        int y0 = Math.max(0, (int) (sy - ry)), y1 = Math.min(f.h - 1, (int) (sy + ry));
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double dx = (x - sx) / rx, dy = (y - sy) / ry, r2 = dx * dx + dy * dy;
                if (r2 >= 1) continue;
                double k = 1 - dark * (1 - r2);
                int i = y * f.w + x;
                f.px[i] = lit(f.px[i], k);
            }
        }
    }

    static int lit(int c, double k) {
        int r = Math.min(255, (int) ((c >> 16 & 0xff) * k)), g = Math.min(255, (int) ((c >> 8 & 0xff) * k)), b = Math.min(255, (int) ((c & 0xff) * k));
        return r << 16 | g << 8 | b;
    }

    private static double[] unit(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] scale(double[] a, double s) {
        return new double[]{a[0] * s, a[1] * s, a[2] * s};
    }
}
