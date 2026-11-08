package de.droiddrone.pc.fleet;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The virtual drones' world (1.0.52, the pilot: every environment in the tour's style): drawn like a computer
 * simulation - a pale sky and ground, a faint data grid, glowing outlines of far structures, floating fragments, and
 * on the video scan lines and now and then a glitch. Real-looking things (the BTR, the soldier) move in it. At NIGHT
 * the same world, dark. The motion detection's picture gets the world without fragments and screen effects, so only
 * what really travels moves in it. First drawn for the tour's BTR scene, now every virtual drone's.
 */
final class SimWorld {
    /** One palette: day or night. */
    private record Palette(Color skyTop, Color skyLow, Color haze, Color ground, Color grid, Color gridMajor, Color edge, Color face) {}

    private static final Palette DAY = new Palette(new Color(0xeef2f6), new Color(0xd6dfe8), new Color(0xdde4ec), new Color(0xc7d0d9),
            new Color(0x7e98b2), new Color(0x5f7f9f), new Color(0x8fd8ff), new Color(250, 252, 255, 120));
    private static final Palette NIGHT = new Palette(new Color(0x0c1220), new Color(0x121a2a), new Color(0x182234), new Color(0x0e141e),
            new Color(0x2c4a66), new Color(0x35607f), new Color(0x4fa8d8), new Color(40, 60, 90, 90));

    /** The far structures and the floating fragments, fixed in the world round where it was made. */
    private final double[][] structures, fragments;

    /** A world round (north, east), metres in the flight model's frame (the same seed: the tour's world as before). */
    SimWorld(double north, double east) {
        Random r = new Random(4609);
        structures = new double[18][];
        for (int i = 0; i < structures.length; i++) {
            double a = r.nextDouble() * 2 * Math.PI, d = 230 + r.nextDouble() * 200;
            structures[i] = new double[]{north + Math.cos(a) * d, east + Math.sin(a) * d, 8 + r.nextDouble() * 22, 8 + r.nextDouble() * 26,
                    10 + r.nextDouble() * 45, r.nextDouble() * Math.PI / 2};
        }
        fragments = new double[70][];
        for (int i = 0; i < fragments.length; i++) {
            double a = r.nextDouble() * 2 * Math.PI, d = 15 + r.nextDouble() * 130;
            fragments[i] = new double[]{north + Math.cos(a) * d, east + Math.sin(a) * d, 0.8 + r.nextDouble() * 9, r.nextDouble() * 6.28, 0.2 + r.nextDouble() * 0.5};
        }
    }

    /** The haze far things fade into (the vehicle's fog), 0xRRGGBB. */
    static int haze(boolean night) {
        return (night ? NIGHT : DAY).haze().getRGB() & 0xffffff;
    }

    /** Sky, ground, the grid, the far structures (outlines glowing), a haze at the horizon; fragments unless analysis. */
    void paint(Graphics2D g, Scene.Camera cam, Scene.View v, int w, int h, long nowMs, boolean analysis, boolean night) {
        Palette pal = night ? NIGHT : DAY;
        g.setPaint(new GradientPaint(0, 0, pal.skyTop(), 0, h, pal.skyLow()));
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
            g.setPaint(new GradientPaint(hx, hy, pal.haze(), (float) (hx + nx * h * 0.35), (float) (hy + ny * h * 0.35), pal.ground()));
            g.fill(ground);
        } else if (cam.lookingDown()) {
            g.setColor(pal.ground());
            g.fillRect(0, 0, w, h);
        }
        float px = Math.max(1f, w / 640f);
        Color grid = pal.grid(), major = pal.gridMajor(), edge = pal.edge();
        // the data grid: every 10 m, stronger every 50
        double step = 10, reach = 200;
        double gn = Math.floor(v.north() / step) * step, ge = Math.floor(v.east() / step) * step;
        g.setStroke(new BasicStroke(px * 0.8f));
        g.setColor(new Color(grid.getRed(), grid.getGreen(), grid.getBlue(), 70));
        for (double n = gn - reach; n <= gn + reach; n += step) if (Math.floorMod((long) n, 50) != 0) cam.line(g, n, ge - reach, 0, n, ge + reach, 0);
        for (double e = ge - reach; e <= ge + reach; e += step) if (Math.floorMod((long) e, 50) != 0) cam.line(g, gn - reach, e, 0, gn + reach, e, 0);
        // and a fine one close by, every 2 m (the floor under the camera)
        double fine = 2, near = 24;
        double fn = Math.floor(v.north() / fine) * fine, fe = Math.floor(v.east() / fine) * fine;
        g.setColor(new Color(grid.getRed(), grid.getGreen(), grid.getBlue(), 45));
        for (double n = fn - near; n <= fn + near; n += fine) cam.line(g, n, fe - near, 0, n, fe + near, 0);
        for (double e = fe - near; e <= fe + near; e += fine) cam.line(g, fn - near, e, 0, fn + near, e, 0);
        g.setStroke(new BasicStroke(px * 1.2f));
        g.setColor(new Color(major.getRed(), major.getGreen(), major.getBlue(), 110));
        for (double n = gn - reach; n <= gn + reach; n += step) if (Math.floorMod((long) n, 50) == 0) cam.line(g, n, ge - reach, 0, n, ge + reach, 0);
        for (double e = ge - reach; e <= ge + reach; e += step) if (Math.floorMod((long) e, 50) == 0) cam.line(g, gn - reach, e, 0, gn + reach, e, 0);
        // the far structures: pale blocks with glowing edges
        for (double[] s : structures) structure(g, cam, s, px, pal);
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
            g.setColor(night ? new Color(120, 200, 255, Math.min(255, alpha + 30)) : new Color(255, 255, 255, Math.min(255, alpha + 60)));
            g.fill(new java.awt.geom.Rectangle2D.Double(sx - size / 2, sy - size / 2, size, size));
            g.setColor(new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), alpha));
            g.draw(new java.awt.geom.Rectangle2D.Double(sx - size, sy - size, size * 2, size * 2));
        }
    }

    /** A far block {north, east, width, depth, height, turn}: its faces filled pale, its edges glowing. */
    private static void structure(Graphics2D g, Scene.Camera cam, double[] s, float px, Palette pal) {
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
        g.setColor(pal.face());
        g.fill(path);
        g.setStroke(new BasicStroke(px));
        Color edge = pal.edge();
        g.setColor(new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 150));
        for (int i = 0; i < 4; i++) {
            double[] p = base[i], n = base[(i + 1) % 4];
            cam.line(g, p[0], p[1], 0, n[0], n[1], 0);
            cam.line(g, p[0], p[1], s[4], n[0], n[1], s[4]);
            cam.line(g, p[0], p[1], 0, p[0], p[1], s[4]);
        }
    }

    /** The simulation's screen: faint scan lines, and now and then a glitch band (the video only). */
    static void effects(BufferedImage img, long nowMs) {
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
}
