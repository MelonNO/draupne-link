package de.droiddrone.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Made-up camera pictures for the motion detector's tests (no footage of wind in trees exists here): a textured
 * landscape, patches of foliage swaying in gusts (each leaf a little out of step with the next, the patch's edge moving
 * too), things that travel through it, the whole picture shaken, its exposure changed, a dark night with noisy pixels.
 * Luma at full size and chroma at half size each way, as the phone hands them over.
 */
final class SyntheticScene {
    static final int MARGIN = 16;
    final int width, height;
    private final int tw, th;
    private final float[] ground;
    private final float[] leaves;
    private final Random noise;
    final List<Patch> patches = new ArrayList<>();
    final List<Mover> movers = new ArrayList<>();
    final List<Shadow> shadows = new ArrayList<>();
    /** Shake: the whole picture moves by up to this, pixels (a random walk pulled back to the middle, plus a tremble). */
    float shake;
    /** The picture's brightness factor at a time (s). */
    java.util.function.DoubleUnaryOperator gain = t -> 1;
    float noiseSigma = 2;
    /** The camera knocked: the whole picture moved by this (pixels, x and y) at a time (s), on top of the shake. */
    java.util.function.DoubleFunction<double[]> shove = t -> null;
    private double shoveX, shoveY;
    /** A clear sky over the picture's top rows (0: none): flat and bright, a little lighter towards the top. */
    int skyRows;
    /** Night: the landscape this dark (a factor), hot pixels here and there. */
    float darkness = 1;
    int hotPixels;
    private double shakeX, shakeY;
    /** The shake of the first picture: where the detector's background sits. */
    double firstShakeX = Double.NaN, firstShakeY = Double.NaN;

    /** Foliage: an ellipse of leaves swaying amplitude pixels at freqHz, in gusts. */
    static final class Patch {
        final float cx, cy, rx, ry, amplitude, freqHz, phase;

        Patch(float cx, float cy, float rx, float ry, float amplitude, float freqHz, float phase) {
            this.cx = cx;
            this.cy = cy;
            this.rx = rx;
            this.ry = ry;
            this.amplitude = amplitude;
            this.freqHz = freqHz;
            this.phase = phase;
        }
    }

    /** Something that travels: a w x h box from (x0, y0) at (vx, vy) pixels a second, from t0 to t1 s. */
    static final class Mover {
        final float x0, y0, w, h, vx, vy, t0, t1, luma, u, v;
        final boolean textured;
        /**
         * Camouflage (1.0.58): over 0, its pattern is the leaves' (a net's, a treeline's), this strong, round its luma -
         * of the leaves' own brightness and grain, it differs from them only in where its blotches lie.
         */
        float camo;
        /** Where its top left is at a time (s), instead of the straight line: {x, y}. */
        java.util.function.DoubleFunction<float[]> path;
        /** pc.50: its size at a time (s), instead of w x h: {w, h} - a torch's spot as it swings to the camera and away. */
        java.util.function.DoubleFunction<float[]> size;
        /** pc.50: lit at night whatever its luma (ground in a headlight's beam); a luma over 200 is a light anyway. */
        boolean glows;

        Mover(float x0, float y0, float w, float h, float vx, float vy, float t0, float t1, float luma, float u, float v, boolean textured) {
            this.x0 = x0;
            this.y0 = y0;
            this.w = w;
            this.h = h;
            this.vx = vx;
            this.vy = vy;
            this.t0 = t0;
            this.t1 = t1;
            this.luma = luma;
            this.u = u;
            this.v = v;
            this.textured = textured;
        }

        boolean on(double t) {
            return t >= t0 && t < t1;
        }

        /** The box at time t, in the picture's coordinates (no shake): x, y, w, h. */
        float[] at(double t) {
            float[] s = size == null ? null : size.apply(t);
            float bw = s == null ? w : s[0], bh = s == null ? h : s[1];
            if (path != null) {
                float[] p = path.apply(t);
                return new float[]{p[0], p[1], bw, bh};
            }
            return new float[]{(float) (x0 + vx * (t - t0)), (float) (y0 + vy * (t - t0)), bw, bh};
        }
    }

    /**
     * Someone walking straight at the camera (1.0.53, the pilot: "a person moving from inside the woods directly towards
     * the camera"): drawn in perspective from the camera {@link #camHeightM} up, looking level - head, body, swinging arms
     * and legs, a camouflage pattern of about the ground's brightness - growing as the distance falls, hardly moving
     * sideways. Trunks nearer than them hide them.
     */
    static final class Approacher {
        final double lateralM, startM, speed, t0, t1;
        final float luma, contrast;
        /** Metres a second sideways (right): 0 = straight at the camera. */
        double driftMps;

        /** lateralM off the camera's axis, startM away, speed m/s, from t0 to t1 s; luma the pattern's mean, contrast its +-. */
        Approacher(double lateralM, double startM, double speed, double t0, double t1, float luma, float contrast) {
            this.lateralM = lateralM;
            this.startM = startM;
            this.speed = speed;
            this.t0 = t0;
            this.t1 = t1;
            this.luma = luma;
            this.contrast = contrast;
        }

        boolean on(double t) {
            return t >= t0 && t < t1;
        }

        double distance(double t) {
            return Math.max(2, startM - speed * (t - t0));
        }

        double lateral(double t) {
            return lateralM + driftMps * (t - t0);
        }
    }

    /** A tree trunk standing distanceM away, lateralM off the axis, widthM thick, from the ground to the picture's top. */
    static final class Trunk {
        final double lateralM, distanceM, widthM;
        final float luma;

        Trunk(double lateralM, double distanceM, double widthM, float luma) {
            this.lateralM = lateralM;
            this.distanceM = distanceM;
            this.widthM = widthM;
            this.luma = luma;
        }
    }

    final List<Approacher> approachers = new ArrayList<>();
    final List<Trunk> trunks = new ArrayList<>();
    /** The perspective approachers and trunks are drawn in: the horizon's row, the focal length (px), the camera's height. */
    double horizonRow = 72, focalPx = 160, camHeightM = 1.5;

    /** The camera-space box (x, y, w, h, picture pixels) round an approacher at time t. */
    float[] box(Approacher a, double t) {
        double d = a.distance(t), s = focalPx / d;
        double cx = width / 2.0 + a.lateral(t) * s, feet = horizonRow + camHeightM * s;
        return new float[]{(float) (cx - 0.32 * s), (float) (feet - 1.80 * s), (float) (0.64 * s), (float) (1.80 * s)};
    }

    /** Whether picture point (x, y) is on the walker at time t: its pattern's luma, or NaN. */
    private float person(Approacher a, double x, double y, double t) {
        double d = a.distance(t), s = focalPx / d;
        double phase = 2 * Math.PI * 0.9 * (t - a.t0);
        double bob = 0.03 * Math.abs(Math.sin(phase));
        double sway = 0.04 * Math.sin(phase / 2);
        double X = (x - width / 2.0) / s - a.lateral(t) - sway, Z = (horizonRow + camHeightM * s - y) / s - bob;
        boolean on;
        double ax = Math.abs(X);
        if (Z > 1.53 && Z < 1.79) on = Math.hypot(X, (Z - 1.66) * 0.9) < 0.12; // head
        else if (Z >= 0.85 && Z <= 1.53) on = ax <= 0.22 // body
                || ax <= 0.31 && Z >= 0.8 + 0.12 * Math.abs(Math.sin(phase + (X < 0 ? 0 : Math.PI))) && Z <= 1.45; // arms
        else if (Z >= 0 && Z < 0.85) { // legs: the one swinging forward lifts its foot
            double lift = 0.18 * Math.max(0, Math.sin(phase + (X < 0 ? 0 : Math.PI)));
            on = ax >= 0.02 && ax <= 0.18 && Z >= lift;
        } else on = false;
        if (!on) return Float.NaN;
        // camouflage: blotches fixed to the body, scaling with it
        float p = (sample(leaves, (X + 0.4) * 12 + MARGIN, (2 - Z) * 12 + MARGIN) - 100) / 40;
        return a.luma + a.contrast * Math.max(-1, Math.min(1, p));
    }

    /** A cloud's shadow: a soft band darkening the picture by factor, its edge crossing at speed px/s from t0. */
    static final class Shadow {
        final float factor, speed, t0;

        Shadow(float factor, float speed, float t0) {
            this.factor = factor;
            this.speed = speed;
            this.t0 = t0;
        }
    }

    SyntheticScene(int width, int height, long seed) {
        this.width = width;
        this.height = height;
        tw = width + 2 * MARGIN;
        th = height + 2 * MARGIN;
        Random r = new Random(seed);
        noise = new Random(seed * 31 + 7);
        ground = new float[tw * th];
        leaves = new float[tw * th];
        // the landscape: smooth shapes at a few sizes plus fine grain
        layer(ground, r, 40, 70);
        layer(ground, r, 12, 30);
        layer(ground, r, 4, 14);
        for (int i = 0; i < ground.length; i++) ground[i] += 70;
        // leaves: fine, contrasty
        layer(leaves, r, 3, 50);
        layer(leaves, r, 8, 30);
        for (int i = 0; i < leaves.length; i++) leaves[i] += 60;
    }

    /** Adds value noise of the given cell size and amplitude (bilinear between random grid points). */
    private void layer(float[] into, Random r, int cell, float amplitude) {
        int gw = tw / cell + 2, gh = th / cell + 2;
        float[] grid = new float[gw * gh];
        for (int i = 0; i < grid.length; i++) grid[i] = r.nextFloat() * amplitude;
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                float gx = (float) x / cell, gy = (float) y / cell;
                int ix = (int) gx, iy = (int) gy;
                float fx = gx - ix, fy = gy - iy;
                float a = grid[iy * gw + ix], b = grid[iy * gw + ix + 1], c = grid[(iy + 1) * gw + ix], d = grid[(iy + 1) * gw + ix + 1];
                into[y * tw + x] += a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
            }
        }
    }

    private float sample(float[] tex, double x, double y) {
        x = Math.max(0, Math.min(tw - 1.001, x));
        y = Math.max(0, Math.min(th - 1.001, y));
        int ix = (int) x, iy = (int) y;
        float fx = (float) (x - ix), fy = (float) (y - iy);
        int i = iy * tw + ix;
        float top = tex[i] + (tex[i + 1] - tex[i]) * fx;
        float bottom = tex[i + tw] + (tex[i + tw + 1] - tex[i + tw]) * fx;
        return top + (bottom - top) * fy;
    }

    /** Gusts: 0.2 .. 1.3, changing over seconds. */
    static double gust(double t, float phase) {
        return 0.65 + 0.35 * Math.sin(0.23 * t + phase) + 0.3 * Math.sin(0.71 * t + 2 * phase) * Math.sin(0.05 * t);
    }

    /** What the picture shows at (x, y) at time t: luma, u, v. */
    private void look(double x, double y, double t, float[] out) {
        double sx = x + MARGIN + shakeX + shoveX, sy = y + MARGIN + shakeY + shoveY;
        float luma = sample(ground, sx, sy), u = 128 + (luma > 140 ? 4 : -2), v = 128 + (luma > 120 ? 6 : 2);
        if (sy - MARGIN < skyRows) {
            luma = (float) (200 - 0.3 * (sy - MARGIN));
            u = 136;
            v = 124;
        }
        for (Patch p : patches) {
            double reach = 2 * p.amplitude + 2; // nowhere near this patch: nothing to work out
            if (Math.abs(sx - MARGIN - p.cx) > p.rx * 1.2 + reach || Math.abs(sy - MARGIN - p.cy) > p.ry * 1.2 + reach) continue;
            // each leaf a little out of step with its neighbours
            double g = gust(t, p.phase) * p.amplitude;
            double w = 2 * Math.PI * p.freqHz * t + p.phase + 0.18 * sx + 0.11 * sy;
            double dx = g * Math.sin(w), dy = 0.4 * g * Math.cos(1.3 * w);
            double px = sx - dx, py = sy - dy;
            double ex = (px - MARGIN - p.cx) / p.rx, ey = (py - MARGIN - p.cy) / p.ry;
            // a ragged edge: the leaves' own texture decides where the patch ends
            double edge = 1 + (sample(leaves, px, py) - 100) / 300;
            if (ex * ex + ey * ey < edge) {
                luma = sample(leaves, px, py);
                u = 113;
                v = 116;
            }
        }
        for (Mover m : movers) {
            if (!m.on(t)) continue;
            float[] b = m.at(t);
            double mx = sx - MARGIN, my = sy - MARGIN;
            if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3]) {
                luma = m.camo > 0 ? m.luma + (sample(leaves, (mx - b[0]) * 0.8 + 7, (my - b[1]) * 0.8 + 7) - 100) * m.camo
                        : m.textured ? m.luma + (sample(ground, mx - b[0] + 3, my - b[1] + 3) - 110) * 0.4f : m.luma;
                u = m.u;
                v = m.v;
            }
        }
        // the walkers and the trunks, farthest first (no shake on the scene's own coordinates: they shake with it)
        double ux = sx - MARGIN, uy = sy - MARGIN;
        double nearestPerson = Double.MAX_VALUE;
        for (Approacher a : approachers) {
            if (!a.on(t)) continue;
            float p = person(a, ux, uy, t);
            if (Float.isNaN(p) || a.distance(t) > nearestPerson) continue;
            nearestPerson = a.distance(t);
            luma = p;
            u = 124;
            v = 126;
        }
        for (Trunk k : trunks) {
            if (k.distanceM > nearestPerson) continue;
            double s = focalPx / k.distanceM, cx = width / 2.0 + k.lateralM * s;
            if (Math.abs(ux - cx) > k.widthM * s / 2 || uy > horizonRow + camHeightM * s) continue;
            luma = k.luma + (sample(leaves, ux * 0.5 + MARGIN, uy * 3 + MARGIN) - 100) * 0.15f;
            u = 124;
            v = 132;
        }
        for (Shadow s : shadows) {
            if (t < s.t0) continue;
            double edgeX = (t - s.t0) * s.speed - 40; // the band's front, moving right; soft over 30 px
            double into = (edgeX - x) / 30;
            if (into > 0) luma *= (float) (1 - (1 - s.factor) * Math.min(1, into));
        }
        out[0] = luma;
        out[1] = u;
        out[2] = v;
    }

    /** The picture at time t (s): {luma, u, v}. */
    byte[][] frame(double t) {
        if (shake > 0) {
            // a random walk pulled back to the middle, and a tremble each picture
            shakeX = shakeX * 0.7 + noise.nextGaussian() * shake * 0.5;
            shakeY = shakeY * 0.7 + noise.nextGaussian() * shake * 0.5;
            shakeX = Math.max(-shake, Math.min(shake, shakeX));
            shakeY = Math.max(-shake, Math.min(shake, shakeY));
        }
        double[] knock = shove.apply(t);
        shoveX = knock == null ? 0 : knock[0];
        shoveY = knock == null ? 0 : knock[1];
        if (Double.isNaN(firstShakeX)) {
            firstShakeX = shakeX;
            firstShakeY = shakeY;
        }
        byte[] y = new byte[width * height];
        byte[] u = new byte[(width / 2) * (height / 2)];
        byte[] v = new byte[(width / 2) * (height / 2)];
        float k = (float) gain.applyAsDouble(t) * darkness;
        float[] px = new float[3];
        for (int yy = 0; yy < height; yy++) {
            for (int xx = 0; xx < width; xx++) {
                look(xx, yy, t, px);
                float l = px[0];
                boolean light = false;
                for (Mover m : movers) { // a light keeps its brightness however dark the night
                    if (m.on(t) && (m.luma > 200 || m.glows) && darkness < 1) {
                        float[] b = m.at(t);
                        if (xx >= b[0] && xx < b[0] + b[2] && yy >= b[1] && yy < b[1] + b[3]) light = true;
                    }
                }
                float value = light ? l : l * k;
                value += (float) noise.nextGaussian() * noiseSigma;
                y[yy * width + xx] = (byte) Math.max(0, Math.min(255, Math.round(value)));
                if ((xx & 1) == 0 && (yy & 1) == 0) {
                    int c = (yy / 2) * (width / 2) + xx / 2;
                    u[c] = (byte) Math.max(0, Math.min(255, Math.round(px[1])));
                    v[c] = (byte) Math.max(0, Math.min(255, Math.round(px[2])));
                }
            }
        }
        for (int i = 0; i < hotPixels; i++) { // sensor noise at night: single bright pixels, somewhere new each picture
            y[noise.nextInt(width * height)] = (byte) (150 + noise.nextInt(100));
        }
        return new byte[][]{y, u, v};
    }

    double shakeX() {
        return shakeX;
    }

    double shakeY() {
        return shakeY;
    }
}
