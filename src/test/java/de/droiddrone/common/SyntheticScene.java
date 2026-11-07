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
            return new float[]{(float) (x0 + vx * (t - t0)), (float) (y0 + vy * (t - t0)), w, h};
        }
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
        double sx = x + MARGIN + shakeX, sy = y + MARGIN + shakeY;
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
                luma = m.textured ? m.luma + (sample(ground, mx - b[0] + 3, my - b[1] + 3) - 110) * 0.4f : m.luma;
                u = m.u;
                v = m.v;
            }
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
                    if (m.on(t) && m.luma > 200 && darkness < 1) {
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
