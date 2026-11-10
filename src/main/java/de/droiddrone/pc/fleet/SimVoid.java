package de.droiddrone.pc.fleet;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

/**
 * The tour's six scenes' surroundings (2026-11-10, the pilot: "make the realistic scenes be in an Animus style
 * environment", then "push it much further"): the realistic land, models and people are an island in a reconstructed
 * memory. Round the action the real ground; at its edge it breaks into light-blue tiles; beyond, a white floor with a
 * glowing light-blue grid lined up with the scene, out to the horizon; above, a white void with a light-blue haze where
 * the floor meets it ({@link Raster3D#ground}). The trees near the action stay real, the others are ghosts of white
 * blocks with lit rims breaking up at the top ({@link Raster3D#ghost}). Cubes and shards drift up from the island's
 * edge and from the ghosts. No scan lines and no glitches since 2026-11-10 (the pilot: "remove the scan lines on the
 * screen in addition to the random glitches"). All drawn here, nothing copied: no logos, no text.
 * <p>
 * What stands still (the void, the floor, the grid, the edge, the ghosts) is part of the picture kept per camera
 * position and of the motion detection's picture alike - it never moves. What moves (the fragments) is drawn on the
 * video only, after the scene, so the motion detection sees only what really travels.
 */
final class SimVoid {
    private SimVoid() {}

    /** The void above (white), the haze where the floor meets it (light blue), the floor near (white, a little grey). */
    static final int VOID = 0xfdfeff, HORIZON = 0xdfedf8, FLOOR = 0xf1f4f7;
    /** The grid's lines; an edge tile's face and its lit rim; a ghost's blocks. */
    static final int GRID = 0x3fa6ec, TILE = 0xcbe7fa, RIM = 0x55b5f2, GHOST = 0xdcedfa;

    /** The void's colour by sin(elevation), every 1/256 from -0.25 to 0.75. */
    private static final int[] SKY = new int[256];

    static {
        for (int i = 0; i < SKY.length; i++) {
            double s = i / 256.0 - 0.25;
            // the light-blue haze at the horizon, white above it
            SKY[i] = s <= 0 ? HORIZON : mix(VOID, HORIZON, Math.exp(-s * 9));
        }
    }

    /**
     * The void behind everything: every pixel by its ray's elevation (worked out at a row's ends and stepped across: the
     * elevation's sine is near linear along a row). Rolled and pitched with the camera; the ground is drawn over it.
     */
    static void sky(int[] px, int w, int h, Scene.Camera cam) {
        double[][] r = cam.r;
        for (int y = 0; y < h; y++) {
            double v = (y + 0.5 - cam.cy) / cam.f;
            int a = skyIndex(r, (0.5 - cam.cx) / cam.f, v), b = skyIndex(r, (w - 0.5 - cam.cx) / cam.f, v);
            int row = y * w;
            if (a == b) {
                java.util.Arrays.fill(px, row, row + w, SKY[a]);
                continue;
            }
            int k = a << 16, step = ((b - a) << 16) / Math.max(1, w - 1);
            for (int x = 0; x < w; x++, k += step) px[row + x] = SKY[k >> 16];
        }
    }

    /** The table's index for the ray through (u, v) (the picture's offsets over its focal length). */
    private static int skyIndex(double[][] r, double u, double v) {
        double dn = r[0][0] * u + r[1][0] * v + r[2][0], de = r[0][1] * u + r[1][1] * v + r[2][1], du = r[0][2] * u + r[1][2] * v + r[2][2];
        double s = du / Math.sqrt(dn * dn + de * de + du * du);
        return Math.max(0, Math.min(SKY.length - 1, (int) ((s + 0.25) * 256)));
    }

    static int mix(int a, int b, double k) {
        int r = (int) ((a >> 16 & 0xff) + ((b >> 16 & 0xff) - (a >> 16 & 0xff)) * k);
        int g = (int) ((a >> 8 & 0xff) + ((b >> 8 & 0xff) - (a >> 8 & 0xff)) * k);
        int bl = (int) ((a & 0xff) + ((b & 0xff) - (a & 0xff)) * k);
        return r << 16 | g << 8 | bl;
    }

    /** A well-mixed hash of x (any bits). */
    static int hash(int x) {
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }

    // ------------------------------------------------------------------ the fragments drifting up

    /**
     * A scene's fragments, from where its world breaks up: from the island's edge tiles (edge: {ahead, right} pairs,
     * starting at the floor) and the ghosts' tops (tops: {ahead, right, height}). Each: {ahead, right, up at birth, rise
     * m/s, life s, phase s, size m, spin, a cube (0) or a shard (1)}.
     */
    static double[][] fragments(long seed, List<double[]> edge, List<double[]> tops) {
        Random r = new Random(seed);
        int n = (edge.isEmpty() ? 0 : 60) + (tops.isEmpty() ? 0 : 50);
        double[][] out = new double[n][];
        for (int i = 0; i < n; i++) {
            boolean fromEdge = i < (edge.isEmpty() ? 0 : 60);
            double[] at = fromEdge ? edge.get(r.nextInt(edge.size())) : tops.get(r.nextInt(tops.size()));
            double up = fromEdge ? r.nextDouble() * 0.6 : at[2] * (0.55 + 0.45 * r.nextDouble());
            out[i] = new double[]{at[0] + r.nextDouble() - 0.5, at[1] + r.nextDouble() - 0.5, up, 0.3 + r.nextDouble() * 0.45,
                    5 + r.nextDouble() * 6, r.nextDouble() * 20, 0.5 + r.nextDouble() * 0.7, r.nextDouble() * Math.PI, r.nextDouble() < 0.6 ? 0 : 1};
        }
        return out;
    }

    /**
     * The fragments at t s (the video only): small cubes and shards that rise, turn slowly and fade in and out, behind
     * whatever is nearer. place turns (ahead, right) into {north, east}.
     */
    static void drawFragments(Raster3D.Frame f, Scene.Camera cam, double[][] frags, double t, java.util.function.BiFunction<Double, Double, double[]> place) {
        for (double[] q : frags) {
            double life = q[4], age = ((t + q[5]) % life + life) % life;
            double alpha = 0.9 * Math.min(1, Math.min(age / 0.8, (life - age) / (life * 0.45)));
            if (alpha < 0.03) continue;
            double[] at = place.apply(q[0], q[1]);
            double[] c = cam.cam(at[0], at[1], q[2] + age * q[3]);
            if (c[2] < 1.5) continue;
            double s = cam.f * q[6] * (1 - 0.35 * age / life) / c[2] / 2;
            if (s < 0.8) continue;
            double sx = cam.cx + cam.f * c[0] / c[2], sy = cam.cy + cam.f * c[1] / c[2];
            shard(f, sx, sy, Math.min(s, 22), q[7] + age * 0.5, (float) (1 / c[2]), alpha, q[8] < 0.5);
        }
    }

    /**
     * One fragment (about s px across its middle, turned by spin) mixed in by alpha: a cube seen from a corner - three
     * faces, light to mid blue - or a flat shard, a triangle; their edges lit.
     */
    private static void shard(Raster3D.Frame f, double sx, double sy, double s, double spin, float iz, double alpha, boolean cube) {
        // three axes 120 degrees apart: a cube's faces are the rhombi between two of them, a shard the triangle of two
        double[] ex = new double[3], ey = new double[3];
        for (int k = 0; k < 3; k++) {
            double a = spin + k * 2 * Math.PI / 3;
            ex[k] = Math.cos(a) * s;
            ey[k] = Math.sin(a) * s;
        }
        int[] face = {0xe4f4ff, 0x8fcdf5, 0x4aa8ec};
        int x0 = Math.max(0, (int) (sx - s - 1)), x1 = Math.min(f.w - 1, (int) (sx + s + 1));
        int y0 = Math.max(0, (int) (sy - s - 1)), y1 = Math.min(f.h - 1, (int) (sy + s + 1));
        double edge = Math.min(0.3, 1.2 / s);
        for (int y = y0; y <= y1; y++) {
            double dy = y + 0.5 - sy;
            int row = y * f.w;
            for (int x = x0; x <= x1; x++) {
                int i = row + x;
                if (f.depth[i] > iz) continue;
                double dx = x + 0.5 - sx;
                for (int k = 0; k < (cube ? 3 : 1); k++) {
                    int j = (k + 1) % 3;
                    // (dx, dy) in the axes k and j
                    double det = ex[k] * ey[j] - ey[k] * ex[j];
                    double a = (dx * ey[j] - dy * ex[j]) / det, b = (ex[k] * dy - ey[k] * dx) / det;
                    boolean in = cube ? a >= 0 && a <= 1 && b >= 0 && b <= 1 : a >= 0 && b >= 0 && a + b <= 1;
                    if (!in) continue;
                    boolean rim = cube ? a < edge || a > 1 - edge || b < edge || b > 1 - edge : a < edge || b < edge || a + b > 1 - edge;
                    f.px[i] = mix(f.px[i], rim ? 0x2f95e2 : face[cube ? k : 1], rim ? alpha : alpha * 0.85);
                    break;
                }
            }
        }
    }
}
