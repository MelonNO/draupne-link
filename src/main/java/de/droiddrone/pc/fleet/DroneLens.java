package de.droiddrone.pc.fleet;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What makes a tour drone's picture read as a drone's camera and not a CCTV camera on a mast (2026-11-10, the pilot):
 * the drone sits on the ground, so a few blades right in front of the lens stand out of focus along the bottom (few:
 * the pilot, "less grass" - it sits on the memory's floor); and the
 * wide FPV lens bends the picture a little towards its corners (barrel) and darkens them.
 * <p>
 * The blades stand still (drawn with what is kept per camera position, nearer than anything, so nothing that moves
 * passes in front of them). The bend is in the motion detection's picture too, so its boxes sit on the video's
 * picture; the darkening is on the video only.
 */
final class DroneLens {
    private DroneLens() {}

    /** The barrel bend: a point at the corner stays there, the middle is drawn this much larger. */
    static final double BARREL = 0.10;
    /** The corners this much darker. */
    static final double VIGNETTE = 0.28;

    // ------------------------------------------------------------------ the grass in front of the lens

    /** Blades along the bottom of the picture. */
    private static final int BLADES = 6;

    /**
     * The blades in front of a drone on the ground: soft dark-green strokes rising from below the picture's bottom,
     * leaning, the nearest the widest and softest. sink (0..1) lowers them out of the picture (lifting off).
     */
    static void grass(Raster3D.Frame f, long seed, double sink) {
        if (sink >= 1) return;
        int w = f.w, h = f.h;
        Random r = new Random(seed);
        for (int b = 0; b < BLADES; b++) {
            // spread along the bottom, more towards the corners (where a drone's arms and the grass round them show)
            double u = r.nextDouble();
            double bx = (u < 0.5 ? 0.5 * Math.pow(2 * u, 1.6) : 1 - 0.5 * Math.pow(2 * (1 - u), 1.6)) * w;
            double near = r.nextDouble();
            double height = h * (0.07 + 0.20 * near * near) * (0.6 + 0.4 * r.nextDouble());
            double base = h * (1.0 + 0.04 * r.nextDouble() + sink * 0.45);
            double lean = (r.nextDouble() - 0.5) * 0.9 + (bx < w / 2.0 ? -0.15 : 0.15);
            double bend = (r.nextDouble() - 0.5) * 1.2;
            double width = w * (0.003 + 0.010 * near);
            double blur = w * (0.002 + 0.006 * near);
            int g = 0x2a + (int) (r.nextDouble() * 0x22);
            int rgb = (int) (g * 0.72) << 16 | g << 8 | (int) (g * 0.38);
            double alpha = 0.75 + 0.2 * r.nextDouble();
            blade(f, bx, base, height, lean, bend, width, blur, rgb, alpha);
        }
    }

    /** One blade, row by row from its base up: its middle leans and bends, it narrows to a point; edges blurred. */
    private static void blade(Raster3D.Frame f, double bx, double base, double height, double lean, double bend, double width,
                              double blur, int rgb, double alpha) {
        int y0 = Math.max(0, (int) (base - height)), y1 = Math.min(f.h - 1, (int) base);
        for (int y = y0; y <= y1; y++) {
            double t = (base - (y + 0.5)) / height; // 0 at the base, 1 at the tip
            if (t < 0 || t > 1) continue;
            double a = lean + bend * t;
            double cx = bx + Math.sin(a) * height * t;
            double half = width * (1 - t) / 2 + 0.3;
            int x0 = Math.max(0, (int) (cx - half - blur)), x1 = Math.min(f.w - 1, (int) (cx + half + blur));
            int row = y * f.w;
            for (int x = x0; x <= x1; x++) {
                double d = Math.abs(x + 0.5 - cx) - half;
                double k = d <= 0 ? 1 : 1 - d / blur;
                if (k <= 0) continue;
                k *= alpha * (t > 0.85 ? (1 - t) / 0.15 : 1);
                int i = row + x;
                f.px[i] = SimVoid.mix(f.px[i], rgb, k);
                if (k > 0.5) f.depth[i] = NEAREST;
            }
        }
    }

    /** The blades' depth (1 / z): nearer than anything drawn later. */
    private static final float NEAREST = 1 / 0.05f;

    // ------------------------------------------------------------------ the ground near the lens out of focus

    /** The bottom row's blur (a share of the picture's width): the grass a hand's breadth off, focus far out. */
    static final double NEAR_BLUR = 0.011;

    /**
     * The ground from the horizon down blurred more the nearer it is (a lens focused on the scene, the grass a hand
     * away): a box blur each way whose radius grows from nothing at horizonY to NEAR_BLUR of the width at the bottom -
     * the ground's distance falls off about as 1 / the rows below the horizon. Once per camera position.
     */
    static void defocus(int[] px, int w, int h, double horizonY) {
        int top = Math.max(0, (int) Math.ceil(horizonY));
        if (top >= h - 1) return;
        double span = h - horizonY, most = NEAR_BLUR * w;
        int[] line = new int[Math.max(w, h)];
        // across each row
        for (int y = top; y < h; y++) {
            int rad = (int) (most * (y + 0.5 - horizonY) / span);
            if (rad < 1) continue;
            int o = y * w;
            System.arraycopy(px, o, line, 0, w);
            box(line, px, o, 1, w, rad);
        }
        // down each column, the same radius as the row it lands on (sums down the column, per colour)
        int[] cr = new int[h + 1], cg = new int[h + 1], cb = new int[h + 1];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int c = px[y * w + x];
                cr[y + 1] = cr[y] + (c >> 16 & 0xff);
                cg[y + 1] = cg[y] + (c >> 8 & 0xff);
                cb[y + 1] = cb[y] + (c & 0xff);
            }
            for (int y = top; y < h; y++) {
                int rad = (int) (most * (y + 0.5 - horizonY) / span);
                if (rad < 1) continue;
                int a = Math.max(top, y - rad), b = Math.min(h - 1, y + rad), n = b - a + 1;
                px[y * w + x] = ((cr[b + 1] - cr[a]) / n) << 16 | ((cg[b + 1] - cg[a]) / n) << 8 | (cb[b + 1] - cb[a]) / n;
            }
        }
    }

    /** dst[o + i * step] = the mean of src[i - rad .. i + rad] (clipped at the ends), i < n. */
    private static void box(int[] src, int[] dst, int o, int step, int n, int rad) {
        int sr = 0, sg = 0, sb = 0, cnt = 0;
        for (int i = 0; i <= Math.min(n - 1, rad); i++) {
            int c = src[i];
            sr += c >> 16 & 0xff;
            sg += c >> 8 & 0xff;
            sb += c & 0xff;
            cnt++;
        }
        for (int i = 0; i < n; i++) {
            dst[o + i * step] = (sr / cnt) << 16 | (sg / cnt) << 8 | sb / cnt;
            int add = i + rad + 1, drop = i - rad;
            if (add < n) {
                int c = src[add];
                sr += c >> 16 & 0xff;
                sg += c >> 8 & 0xff;
                sb += c & 0xff;
                cnt++;
            }
            if (drop >= 0) {
                int c = src[drop];
                sr -= c >> 16 & 0xff;
                sg -= c >> 8 & 0xff;
                sb -= c & 0xff;
                cnt--;
            }
        }
    }

    // ------------------------------------------------------------------ the lens

    /** Per picture size: where each pixel is taken from, and how much of it is left (of 256) at the corners. */
    private record Map(int[] from, short[] keep) {}

    private static final ConcurrentHashMap<Long, Map> MAPS = new ConcurrentHashMap<>();
    private static final ThreadLocal<int[]> COPY = ThreadLocal.withInitial(() -> new int[0]);

    private static Map map(int w, int h) {
        return MAPS.computeIfAbsent((long) w << 32 | h, key -> {
            int[] from = new int[w * h];
            short[] keep = new short[w * h];
            double cx = w / 2.0, cy = h / 2.0, half = Math.hypot(cx, cy);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double dx = (x + 0.5 - cx) / half, dy = (y + 0.5 - cy) / half;
                    double rr = dx * dx + dy * dy;
                    // taken from nearer the middle: the middle drawn larger, the corners where they were
                    double s = 1 - BARREL + BARREL * rr;
                    int sx = Math.max(0, Math.min(w - 1, (int) (cx + dx * s * half)));
                    int sy = Math.max(0, Math.min(h - 1, (int) (cy + dy * s * half)));
                    from[y * w + x] = sy * w + sx;
                    keep[y * w + x] = (short) Math.round(256 * (1 - VIGNETTE * rr * rr));
                }
            }
            return new Map(from, keep);
        });
    }

    /** The lens on a picture: bent; darkened at the corners unless it is the motion detection's picture. */
    static void apply(int[] px, int w, int h, boolean darken) {
        Map m = map(w, h);
        int[] copy = COPY.get();
        if (copy.length < w * h) {
            copy = new int[w * h];
            COPY.set(copy);
        }
        System.arraycopy(px, 0, copy, 0, w * h);
        int[] from = m.from();
        if (!darken) {
            for (int i = 0; i < w * h; i++) px[i] = copy[from[i]];
            return;
        }
        short[] keep = m.keep();
        for (int i = 0; i < w * h; i++) {
            int c = copy[from[i]], k = keep[i];
            if (k >= 256) {
                px[i] = c;
                continue;
            }
            px[i] = ((c >> 16 & 0xff) * k >> 8) << 16 | ((c >> 8 & 0xff) * k >> 8) << 8 | (c & 0xff) * k >> 8;
        }
    }
}
