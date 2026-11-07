package de.droiddrone.pc.fleet;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A virtual drone's camera picture: sky and ground split by the horizon as the drone's roll and pitch put it, a ground
 * grid and posts that move with its heading, position and height, its name and a clock burned in, and optional
 * targets passing - a walker, a vehicle, a light at night - for the motion detection and overwatch. Drawn with Java2D,
 * small and cheap; the camera looks along the nose, level with it.
 */
final class Scene {
    /** The camera's horizontal field of view, degrees (an action camera's 16:9 picture). */
    static final double HFOV_DEG = 90;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** Something that moves through the picture, in metres from the drone's start point (north, east). */
    enum Kind { WALKER, VEHICLE, LIGHT }

    static final class Target {
        final Kind kind;
        final double startN, startE, dirDeg, speed;
        final long startMs, untilMs;

        Target(Kind kind, double startN, double startE, double dirDeg, double speed, long startMs, long untilMs) {
            this.kind = kind;
            this.startN = startN;
            this.startE = startE;
            this.dirDeg = dirDeg;
            this.speed = speed;
            this.startMs = startMs;
            this.untilMs = untilMs;
        }

        double[] at(long nowMs) {
            double t = (nowMs - startMs) / 1000.0;
            double d = Math.toRadians(dirDeg);
            return new double[]{startN + Math.cos(d) * speed * t, startE + Math.sin(d) * speed * t};
        }
    }

    /** What the camera sees from: the drone's state. */
    record View(double north, double east, double alt, double rollDeg, double pitchDeg, double headingDeg) {}

    private volatile String name;
    private final double[][] posts;
    private final List<Target> targets = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean night;

    Scene(String name, long seed) {
        this.name = name;
        Random r = new Random(seed);
        posts = new double[60][];
        for (int i = 0; i < posts.length; i++) {
            posts[i] = new double[]{(r.nextDouble() - 0.5) * 500, (r.nextDouble() - 0.5) * 500, 2 + r.nextDouble() * 6};
        }
    }

    void setName(String name) {
        this.name = name;
    }

    void setNight(boolean night) {
        this.night = night;
    }

    boolean isNight() {
        return night;
    }

    /**
     * Sends something through the picture in front of the camera where it is now: a person 10 m out at walking pace, a
     * car 25 m out at 30 km/h, a person with a lamp 15 m out (for the night) - crossing from just outside the
     * left edge to the other side. (1.0.50: they went in front of the start point, so a drone flown or placed anywhere
     * else never saw them; and they began well outside the picture, so a light took 7 s to be seen.)
     */
    void addTarget(Kind kind, View from, long nowMs) {
        addTarget(kind, from, nowMs, kind == Kind.VEHICLE ? 25 : kind == Kind.LIGHT ? 15 : 10);
    }

    /** SOMEONE NOW's distance: close, large in the picture. */
    static final double CLOSE_M = 6;

    /** As addTarget, at dist metres in front. */
    void addTarget(Kind kind, View from, long nowMs, double dist) {
        double speed = kind == Kind.VEHICLE ? 8 : kind == Kind.LIGHT ? 2 : 1.4;
        double half = Math.tan(Math.toRadians(HFOV_DEG / 2)) * dist * 1.08;
        double h = Math.toRadians(from.headingDeg());
        double cn = from.north() + Math.cos(h) * dist, ce = from.east() + Math.sin(h) * dist;
        // from the left edge across, perpendicular to the view
        double leftN = cn + Math.cos(h - Math.PI / 2) * half, leftE = ce + Math.sin(h - Math.PI / 2) * half;
        long dur = (long) (2 * half / speed * 1000);
        targets.add(new Target(kind, leftN, leftE, from.headingDeg() + 90, speed, nowMs, nowMs + dur));
    }

    /** The targets still crossing (the finished ones go). */
    List<Target> targets(long nowMs) {
        targets.removeIf(t -> nowMs > t.untilMs);
        return new ArrayList<>(targets);
    }

    boolean hasTargets(long nowMs) {
        return !targets(nowMs).isEmpty();
    }

    /** Draws the picture into img (TYPE_INT_RGB), the name, the clock and extra burned in. */
    void render(BufferedImage img, View v, long nowMs, String extra) {
        render(img, v, nowMs, extra, true);
    }

    /**
     * @param burnIn the name, VIRTUAL, the clock and extra written on it - not on the motion detection's picture (the
     *               phone's analysis picture has none, and a clock's digits change every picture)
     */
    void render(BufferedImage img, View v, long nowMs, String extra, boolean burnIn) {
        int w = img.getWidth(), h = img.getHeight();
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Camera cam = new Camera(v, w, h);
            // sky, then the ground as a polygon below the horizon (the horizon is far: two points on it)
            g.setColor(night ? new Color(0x0b1020) : new Color(0x6fa6d8));
            g.fillRect(0, 0, w, h);
            double[] a = cam.horizon(-1), b = cam.horizon(1);
            if (a != null && b != null) {
                double dx = b[0] - a[0], dy = b[1] - a[1];
                double len = Math.max(1e-6, Math.hypot(dx, dy));
                double ux = dx / len, uy = dy / len;
                double nx = -uy, ny = ux; // across the horizon, turned to the ground's side
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
                g.setColor(night ? new Color(0x0d120d) : new Color(0x5b7a3c));
                g.fill(ground);
            } else if (cam.lookingDown()) {
                g.setColor(night ? new Color(0x0d120d) : new Color(0x5b7a3c));
                g.fillRect(0, 0, w, h);
            }
            // the grid on the ground, every 20 m round the drone
            g.setStroke(new BasicStroke(Math.max(1f, w / 640f)));
            g.setColor(night ? new Color(0x26331f) : new Color(0x3f5a28));
            double step = 20, reach = 300;
            double gn = Math.floor(v.north() / step) * step, ge = Math.floor(v.east() / step) * step;
            for (double n = gn - reach; n <= gn + reach; n += step) cam.line(g, n, ge - reach, 0, n, ge + reach, 0);
            for (double e = ge - reach; e <= ge + reach; e += step) cam.line(g, gn - reach, e, 0, gn + reach, e, 0);
            // the start pad
            g.setColor(night ? new Color(0x404040) : new Color(0xd8d0b0));
            cam.line(g, -3, -3, 0, -3, 3, 0);
            cam.line(g, -3, 3, 0, 3, 3, 0);
            cam.line(g, 3, 3, 0, 3, -3, 0);
            cam.line(g, 3, -3, 0, -3, -3, 0);
            // posts
            g.setColor(night ? new Color(0x202020) : new Color(0x4a3a2a));
            for (double[] p : posts) {
                if (Math.abs(p[0] - v.north()) > 400 || Math.abs(p[1] - v.east()) > 400) continue;
                cam.line(g, p[0], p[1], 0, p[0], p[1], p[2]);
            }
            for (Target t : targets(nowMs)) paintTarget(g, cam, t, nowMs);
            if (burnIn) burnIn(g, w, h, extra);
        } finally {
            g.dispose();
        }
    }

    /** The name, VIRTUAL and the clock written on any picture (a replayed flight's too), extra at the bottom. */
    void burnIn(BufferedImage img, String extra) {
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            burnIn(g, img.getWidth(), img.getHeight(), extra);
        } finally {
            g.dispose();
        }
    }

    private void burnIn(Graphics2D g, int w, int h, String extra) {
        float fs = Math.max(11f, h / 22f);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(fs)));
        String left = name + "  VIRTUAL";
        String clock = LocalTime.now().format(CLOCK);
        outlined(g, left, fs * 0.6f, fs * 1.3f);
        outlined(g, clock, w - g.getFontMetrics().stringWidth(clock) - fs * 0.6f, fs * 1.3f);
        if (extra != null && !extra.isEmpty()) outlined(g, extra, fs * 0.6f, h - fs * 0.6f);
    }

    /**
     * The targets alone, over a picture that is already there (a looped old flight): seen from where the drone is now.
     * They are part of the picture the motion detection gets, as on the phone - never a detection of their own.
     */
    void paintTargets(BufferedImage img, View v, long nowMs) {
        List<Target> list = targets(nowMs);
        if (list.isEmpty()) return;
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Camera cam = new Camera(v, img.getWidth(), img.getHeight());
            for (Target t : list) paintTarget(g, cam, t, nowMs);
        } finally {
            g.dispose();
        }
    }

    /**
     * A model of what moves (1.0.50, the pilot: the detection must be tested by something moving in the picture): a
     * person walking (legs and arms swinging), a car driving (body, cabin, turning wheels; headlights at night), a
     * person with a lamp. Drawn side on, facing the way it goes.
     */
    private void paintTarget(Graphics2D g, Camera cam, Target t, long nowMs) {
        double[] p = t.at(nowMs);
        double[] s = cam.screen(p[0], p[1]);
        if (s == null) return; // behind the camera
        double x = s[0], y = s[1], m = s[2]; // where it stands on the picture, pixels a metre there
        double walked = (nowMs - t.startMs) / 1000.0 * t.speed;
        switch (t.kind) {
            case WALKER -> person(g, x, y, m, walked, night ? new Color(0x2a2a2a) : new Color(0x283a8a),
                    night ? new Color(0x1e1e1e) : new Color(0x30302a), night ? new Color(0xfffbe0) : null); // a torch at night
            case LIGHT -> person(g, x, y, m, walked, night ? new Color(0x262626) : new Color(0x6a5a3a),
                    night ? new Color(0x1c1c1c) : new Color(0x3a3020), new Color(0xffffe8));
            case VEHICLE -> car(g, x, y, m, walked);
        }
    }

    /** A person 1.8 m tall walking to the right; lamp: a light held out in front (null: none). */
    private static void person(Graphics2D g, double x, double y, double m, double walked, Color body, Color legs, Color lamp) {
        double swing = Math.sin(walked / 0.7 * Math.PI) * 0.28 * m; // a step every 0.7 m
        double hip = y - 0.9 * m, shoulder = y - 1.45 * m;
        g.setStroke(new BasicStroke((float) Math.max(1.5, 0.14 * m), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(legs);
        g.draw(new java.awt.geom.Line2D.Double(x, hip, x + swing, y));
        g.draw(new java.awt.geom.Line2D.Double(x, hip, x - swing, y));
        g.setColor(body);
        g.fill(new java.awt.geom.RoundRectangle2D.Double(x - 0.2 * m, shoulder, 0.4 * m, hip - shoulder + 0.05 * m, 0.15 * m, 0.15 * m));
        g.setStroke(new BasicStroke((float) Math.max(1.2, 0.1 * m), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Line2D.Double(x, shoulder + 0.05 * m, x - swing * 0.8, hip));
        double handX = x + swing * 0.8, handY = hip;
        if (lamp != null) {
            handX = x + 0.4 * m;
            handY = shoulder + 0.25 * m;
        }
        g.draw(new java.awt.geom.Line2D.Double(x, shoulder + 0.05 * m, handX, handY));
        double head = 0.13 * m;
        g.fill(new java.awt.geom.Ellipse2D.Double(x - head, shoulder - 2.2 * head, 2 * head, 2 * head));
        if (lamp != null) {
            double r = Math.max(2, 0.12 * m); // the lamp and its glow
            g.setColor(new Color(lamp.getRed(), lamp.getGreen(), lamp.getBlue(), 90));
            g.fill(new java.awt.geom.Ellipse2D.Double(handX - 2.5 * r, handY - 2.5 * r, 5 * r, 5 * r));
            g.setColor(lamp);
            g.fill(new java.awt.geom.Ellipse2D.Double(handX - r, handY - r, 2 * r, 2 * r));
        }
    }

    /** A car 4.5 m long, side on, driving to the right. */
    private void car(Graphics2D g, double x, double y, double m, double driven) {
        double len = 4.5 * m, wheel = 0.33 * m;
        double left = x - len / 2;
        g.setColor(night ? new Color(0x3a3a3a) : new Color(0xb02020));
        g.fill(new java.awt.geom.RoundRectangle2D.Double(left, y - 1.15 * m, len, 0.75 * m, 0.25 * m, 0.25 * m)); // the body
        Path2D cabin = new Path2D.Double();
        cabin.moveTo(left + 0.9 * m, y - 1.1 * m);
        cabin.lineTo(left + 1.5 * m, y - 1.6 * m);
        cabin.lineTo(left + 3.2 * m, y - 1.6 * m);
        cabin.lineTo(left + 3.8 * m, y - 1.1 * m);
        cabin.closePath();
        g.fill(cabin);
        g.setColor(night ? new Color(0x101820) : new Color(0x9fc4e0)); // the windows
        g.fill(new java.awt.geom.Rectangle2D.Double(left + 1.55 * m, y - 1.52 * m, 0.75 * m, 0.38 * m));
        g.fill(new java.awt.geom.Rectangle2D.Double(left + 2.4 * m, y - 1.52 * m, 0.75 * m, 0.38 * m));
        for (double at : new double[]{left + 0.95 * m, left + len - 0.95 * m}) {
            g.setColor(new Color(0x181818));
            g.fill(new java.awt.geom.Ellipse2D.Double(at - wheel, y - 2 * wheel, 2 * wheel, 2 * wheel));
            g.setColor(new Color(0x9a9a9a)); // a spoke, turning with the way driven
            double a = driven / 0.33;
            g.setStroke(new BasicStroke((float) Math.max(1, 0.05 * m)));
            g.draw(new java.awt.geom.Line2D.Double(at - Math.cos(a) * wheel * 0.7, y - wheel - Math.sin(a) * wheel * 0.7,
                    at + Math.cos(a) * wheel * 0.7, y - wheel + Math.sin(a) * wheel * 0.7));
        }
        if (night) { // headlights in front, a tail light behind
            double r = Math.max(2, 0.15 * m);
            g.setColor(new Color(255, 248, 208, 90));
            g.fill(new java.awt.geom.Ellipse2D.Double(left + len - r * 1.5, y - 0.85 * m - 2.5 * r, 5 * r, 5 * r));
            g.setColor(new Color(0xfff8d0));
            g.fill(new java.awt.geom.Ellipse2D.Double(left + len - r, y - 0.85 * m - r, 2 * r, 2 * r));
            g.setColor(new Color(0xd02020));
            g.fill(new java.awt.geom.Ellipse2D.Double(left - r * 0.5, y - 0.85 * m - r * 0.6, r * 1.2, r * 1.2));
        }
    }

    private static void outlined(Graphics2D g, String text, float x, float y) {
        g.setColor(Color.BLACK);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) g.drawString(text, x + dx, y + dy);
        g.setColor(Color.WHITE);
        g.drawString(text, x, y);
    }

    /** The picture's pixels, 0xRRGGBB (img must be TYPE_INT_RGB). */
    static int[] pixels(BufferedImage img) {
        return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    }

    /** The luma of a picture averaged down to at most maxWidth wide (the motion detection's input), BT.601. */
    static byte[] luma(int[] px, int w, int h, int maxWidth, int[] sizeOut) {
        int f = Math.max(1, (w + maxWidth - 1) / maxWidth);
        int ow = w / f, oh = h / f;
        byte[] y = new byte[ow * oh];
        for (int oy = 0; oy < oh; oy++) {
            for (int ox = 0; ox < ow; ox++) {
                int sum = 0;
                for (int j = 0; j < f; j++) {
                    int row = (oy * f + j) * w + ox * f;
                    for (int i = 0; i < f; i++) {
                        int c = px[row + i];
                        sum += (66 * (c >> 16 & 0xff) + 129 * (c >> 8 & 0xff) + 25 * (c & 0xff) + 128 >> 8) + 16;
                    }
                }
                y[oy * ow + ox] = (byte) (sum / (f * f));
            }
        }
        sizeOut[0] = ow;
        sizeOut[1] = oh;
        return y;
    }

    /** The drone's camera: world (north, east, up) to the picture. */
    private static final class Camera {
        final double cn, ce, cu;
        final double[][] r; // world -> camera (x right, y down, z forward)
        final double f, cx, cy;
        final int w, h;

        Camera(View v, int w, int h) {
            this.w = w;
            this.h = h;
            cn = v.north();
            ce = v.east();
            cu = v.alt() + 0.3;
            f = w / 2.0 / Math.tan(Math.toRadians(HFOV_DEG / 2));
            cx = w / 2.0;
            cy = h / 2.0;
            double y = Math.toRadians(v.headingDeg()), p = Math.toRadians(v.pitchDeg()), ro = Math.toRadians(v.rollDeg());
            // body axes in world (n, e, u): forward, right, down after yaw, pitch (nose up +), roll (right wing down +)
            double[] fwd = {Math.cos(y) * Math.cos(p), Math.sin(y) * Math.cos(p), Math.sin(p)};
            double[] right0 = {-Math.sin(y), Math.cos(y), 0};
            double[] up0 = cross(fwd, right0);
            double[] right = add(scale(right0, Math.cos(ro)), scale(up0, -Math.sin(ro)));
            double[] up = add(scale(up0, Math.cos(ro)), scale(right0, Math.sin(ro)));
            r = new double[][]{right, {-up[0], -up[1], -up[2]}, fwd};
        }

        double[] cam(double n, double e, double u) {
            double dn = n - cn, de = e - ce, du = u - cu;
            return new double[]{r[0][0] * dn + r[0][1] * de + r[0][2] * du, r[1][0] * dn + r[1][1] * de + r[1][2] * du,
                    r[2][0] * dn + r[2][1] * de + r[2][2] * du};
        }

        /** A point on the horizon (a direction along the ground, far away), left or right of the view; null if behind. */
        double[] horizon(int side) {
            double[] fwd = r[2];
            double hn = fwd[0], he = fwd[1];
            double len = Math.hypot(hn, he);
            if (len < 1e-3) return null; // looking straight up or down
            hn /= len;
            he /= len;
            double sn = -he * side * 0.5 + hn, se = hn * side * 0.5 + he; // a little to the side of straight ahead
            double[] c = {r[0][0] * sn + r[0][1] * se, r[1][0] * sn + r[1][1] * se, r[2][0] * sn + r[2][1] * se};
            if (c[2] <= 1e-6) return null;
            return new double[]{cx + f * c[0] / c[2], cy + f * c[1] / c[2]};
        }

        /** Whether the picture direction (nx, ny) from the horizon points to the ground's side. */
        boolean towardGround(double nx, double ny) {
            // the world's down, seen in the camera: where it points on the picture is the ground's side
            double[] down = {-r[0][2], -r[1][2]};
            return nx * down[0] + ny * down[1] > 0;
        }

        boolean lookingDown() {
            return r[2][2] < 0;
        }

        void line(Graphics2D g, double n1, double e1, double u1, double n2, double e2, double u2) {
            double[] a = cam(n1, e1, u1), b = cam(n2, e2, u2);
            double near = 0.3;
            if (a[2] < near && b[2] < near) return;
            if (a[2] < near) a = cut(a, b, near);
            else if (b[2] < near) b = cut(b, a, near);
            g.drawLine((int) Math.round(cx + f * a[0] / a[2]), (int) Math.round(cy + f * a[1] / a[2]),
                    (int) Math.round(cx + f * b[0] / b[2]), (int) Math.round(cy + f * b[1] / b[2]));
        }

        /** An upright box (width, height in m) standing at a ground point, facing the camera. */
        /**
         * A ground point on the picture and the scale there, for a model standing upright on it and facing the camera:
         * {x, y, pixels a metre}; null when it is behind or too close.
         */
        double[] screen(double n, double e) {
            double[] base = cam(n, e, 0);
            if (base[2] < 0.5) return null;
            return new double[]{cx + f * base[0] / base[2], cy + f * base[1] / base[2], f / base[2]};
        }

        private static double[] cut(double[] behind, double[] front, double near) {
            double t = (near - behind[2]) / (front[2] - behind[2]);
            return new double[]{behind[0] + (front[0] - behind[0]) * t, behind[1] + (front[1] - behind[1]) * t, near};
        }

        private static double[] cross(double[] a, double[] b) {
            return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
        }

        private static double[] add(double[] a, double[] b) {
            return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
        }

        private static double[] scale(double[] a, double s) {
            return new double[]{a[0] * s, a[1] * s, a[2] * s};
        }
    }
}
