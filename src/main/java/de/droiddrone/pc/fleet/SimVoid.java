package de.droiddrone.pc.fleet;

import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * The tour's six scenes' surroundings (2026-11-10, the pilot: "make the realistic scenes be in an Animus style
 * environment"): the realistic land, models and people stand in a reconstructed memory - a bright white void instead
 * of a sky, no horizon line, the far land fading into white rather than into haze ({@link Raster3D}: the memory fog, the
 * ground breaking up into its cells at its edge with a faint light-blue grid strongest there, far trees falling apart
 * into blocks), small fragments drifting up where the world ends, now and then a short glitch on the screen. All drawn
 * here, nothing copied: no logos, no text.
 * <p>
 * What stands still (the void, the fog, the grid, the dissolving edge) is part of the picture kept per camera position
 * and of the motion detection's picture alike - it never moves. What moves (the fragments, the glitch, the shimmer) is
 * drawn on the video only, after the scene, so the motion detection sees only what really travels.
 */
final class SimVoid {
    private SimVoid() {}

    /** The void at the horizon (white, a little cool), its top (pale grey), the floor beyond the land (light grey). */
    static final int VOID = 0xf3f6f9, VOID_TOP = 0xd3dae1, FLOOR = 0xe2e8ee;
    /** The grid's lines and the cells about to dissolve (light blue). */
    static final int GRID = 0x8fc6ec, EDGE = 0xcfe6f8;

    /** The void's colour by sin(elevation), every 1/256 from -0.25 to 0.75. */
    private static final int[] SKY = new int[256];

    static {
        for (int i = 0; i < SKY.length; i++) {
            double s = i / 256.0 - 0.25;
            // brightest just over the horizon (a glow), greyer towards the top; below the horizon the floor's white
            double k = s <= 0 ? 0 : Math.pow(Math.min(1, s / 0.6), 0.8);
            int c = mix(VOID, VOID_TOP, k);
            double glow = Math.exp(-Math.abs(s) * 22) * 0.6;
            SKY[i] = mix(c, 0xfcfdfe, glow);
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

    // ------------------------------------------------------------------ the fragments drifting up where the world ends

    /** Fragments of a scene: {ahead, right, up at birth, rise m/s, life s, phase s, size m, spin}. */
    static double[][] fragments(long seed) {
        Random r = new Random(seed);
        double[][] out = new double[44][];
        for (int i = 0; i < out.length; i++) {
            // mostly ahead, where the camera looks, round where the land and the trees dissolve
            double a = Math.toRadians(r.nextDouble() < 0.8 ? -75 + r.nextDouble() * 150 : r.nextDouble() * 360), d = 24 + r.nextDouble() * 50;
            out[i] = new double[]{d * Math.cos(a), d * Math.sin(a), 6 + r.nextDouble() * 14, 0.18 + r.nextDouble() * 0.3, 9 + r.nextDouble() * 8,
                    r.nextDouble() * 20, 0.35 + r.nextDouble() * 0.6, r.nextDouble() * Math.PI};
        }
        return out;
    }

    /**
     * The fragments at t s (the video only): small squares, a little turned, that rise slowly and fade in and out, behind
     * whatever is nearer. place turns (ahead, right) into {north, east}.
     */
    static void drawFragments(Raster3D.Frame f, Scene.Camera cam, double[][] frags, double t, java.util.function.BiFunction<Double, Double, double[]> place) {
        for (double[] q : frags) {
            double life = q[4], age = ((t + q[5]) % life + life) % life;
            double alpha = 0.55 * Math.sin(Math.PI * age / life);
            if (alpha < 0.03) continue;
            double[] at = place.apply(q[0], q[1]);
            double[] c = cam.cam(at[0], at[1], q[2] + age * q[3]);
            if (c[2] < 2) continue;
            double s = cam.f * q[6] / c[2] / 2;
            if (s < 0.7) continue;
            double sx = cam.cx + cam.f * c[0] / c[2], sy = cam.cy + cam.f * c[1] / c[2];
            shard(f, sx, sy, Math.min(s, 14), q[7] + age * 0.15, (float) (1 / c[2]), alpha);
        }
    }

    /** One square fragment (half size s px, turned by spin) mixed in by alpha: a light-blue face, a brighter edge. */
    private static void shard(Raster3D.Frame f, double sx, double sy, double s, double spin, float iz, double alpha) {
        double cs = Math.cos(spin), sn = Math.sin(spin), reach = s * 1.42;
        int x0 = Math.max(0, (int) (sx - reach)), x1 = Math.min(f.w - 1, (int) (sx + reach));
        int y0 = Math.max(0, (int) (sy - reach)), y1 = Math.min(f.h - 1, (int) (sy + reach));
        double inner = s - Math.max(0.8, s * 0.22);
        for (int y = y0; y <= y1; y++) {
            double dy = y + 0.5 - sy;
            int row = y * f.w;
            for (int x = x0; x <= x1; x++) {
                double dx = x + 0.5 - sx, u = Math.abs(dx * cs + dy * sn), v = Math.abs(-dx * sn + dy * cs);
                if (u >= s || v >= s) continue;
                int i = row + x;
                if (f.depth[i] > iz) continue;
                boolean edge = u > inner || v > inner;
                f.px[i] = mix(f.px[i], edge ? 0x6fb2e6 : 0xb4d2ea, edge ? alpha : alpha * 0.6);
            }
        }
    }

    // ------------------------------------------------------------------ the screen: a shimmer, now and then a glitch

    /** A glitch comes once in each of these (ms), somewhere in its first GLITCH_SPREAD_MS: 10-20 s apart. */
    static final long GLITCH_CYCLE_MS = 15_000, GLITCH_SPREAD_MS = 5_000, GLITCH_MS = 260;

    /** When the glitch of the cycle that holds nowMs starts (ms). */
    static long glitchAt(long nowMs) {
        long k = Math.floorDiv(nowMs, GLITCH_CYCLE_MS);
        return k * GLITCH_CYCLE_MS + (hash((int) k * 31 + 7) & 0x7fffffff) % GLITCH_SPREAD_MS;
    }

    /**
     * The video's screen: a faint band of light that slides down the picture (7 s a pass), and in a glitch (about every
     * 15 s, for a quarter second) the colours split a few pixels apart and one or two slices of rows slip sideways.
     */
    static void screen(BufferedImage img, long nowMs) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = Scene.pixels(img);
        // the shimmer: a few percent lighter in a soft band
        int band = Math.max(4, h / 12);
        int mid = (int) (Math.floorMod(nowMs, 7000L) * (h + 2 * band) / 7000) - band;
        for (int y = Math.max(0, mid - band); y < Math.min(h, mid + band); y++) {
            double k = 0.05 * (1 + Math.cos(Math.PI * (y - mid) / band)) / 2;
            int add = (int) (k * 256);
            if (add <= 0) continue;
            int o = y * w;
            for (int x = 0; x < w; x++) {
                int c = px[o + x];
                int r = c >> 16 & 0xff, g = c >> 8 & 0xff, b = c & 0xff;
                px[o + x] = (r + ((255 - r) * add >> 8)) << 16 | (g + ((255 - g) * add >> 8)) << 8 | (b + ((255 - b) * add >> 8));
            }
        }
        long since = nowMs - glitchAt(nowMs);
        if (since < 0 || since >= GLITCH_MS) return;
        Random r = new Random(glitchAt(nowMs) * 31 + since / 90);
        // the colours split: red from the right, blue from the left
        int d = Math.max(1, w / 420);
        int[] line = new int[w];
        for (int y = 0; y < h; y++) {
            int o = y * w;
            System.arraycopy(px, o, line, 0, w);
            for (int x = 0; x < w; x++) {
                int rr = line[Math.min(w - 1, x + d)] & 0xff0000, gg = line[x] & 0xff00, bb = line[Math.max(0, x - d)] & 0xff;
                px[o + x] = rr | gg | bb;
            }
        }
        // a slice or two slip sideways, a little lighter
        int slices = 1 + r.nextInt(2);
        for (int k = 0; k < slices; k++) {
            int y0 = r.nextInt(Math.max(1, h - h / 12)), bh = Math.max(2, h / 70 + r.nextInt(Math.max(1, h / 30)));
            int shift = (r.nextBoolean() ? 1 : -1) * (w / 140 + r.nextInt(Math.max(1, w / 60)));
            for (int y = y0; y < Math.min(h, y0 + bh); y++) {
                int o = y * w;
                System.arraycopy(px, o, line, 0, w);
                for (int x = 0; x < w; x++) px[o + x] = mix(line[Math.floorMod(x - shift, w)], 0xeaf4fc, 0.07);
            }
        }
    }
}
