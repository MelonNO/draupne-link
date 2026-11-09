package de.droiddrone.pc.fleet;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * A virtual drone's camera picture: the simulation world ({@link SimWorld}, 1.0.52: every drone in the tour's style)
 * as the drone's roll, pitch, heading, position and height put it, and optional targets passing - a soldier walking,
 * a BTR on patrol, a soldier with a lamp at night - for the motion detection and overwatch. An armed drone flying into
 * one crashes and the target is gone ({@link #hit}). The camera looks along the nose, level with it.
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
        /** A VEHICLE: the BTR on its patrol (1.0.52, the pilot: the realistic BTR, not a drawn car), and its sound. */
        final BtrScene btr;
        final BtrSound sound;

        Target(Kind kind, double startN, double startE, double dirDeg, double speed, long startMs, long untilMs) {
            this(kind, startN, startE, dirDeg, speed, startMs, untilMs, null);
        }

        Target(Kind kind, double startN, double startE, double dirDeg, double speed, long startMs, long untilMs, BtrScene btr) {
            this.kind = kind;
            this.startN = startN;
            this.startE = startE;
            this.dirDeg = dirDeg;
            this.speed = speed;
            this.startMs = startMs;
            this.untilMs = untilMs;
            this.btr = btr;
            this.sound = btr == null ? null : new BtrSound(startMs);
        }

        double[] at(long nowMs) {
            if (btr != null) {
                double[] q = btr.pose(nowMs);
                return new double[]{q[0], q[1]};
            }
            double t = (nowMs - startMs) / 1000.0;
            double d = Math.toRadians(dirDeg);
            return new double[]{startN + Math.cos(d) * speed * t, startE + Math.sin(d) * speed * t};
        }

        /** Whether a drone at this point touches it: the BTR's hull, a soldier's height and reach. */
        boolean hits(double north, double east, double alt, long nowMs) {
            if (btr != null) return btr.hits(north, east, alt, nowMs);
            double[] p = at(nowMs);
            return Math.hypot(north - p[0], east - p[1]) <= SOLDIER_REACH_M && alt <= SOLDIER_HEIGHT_M;
        }
    }

    /** A soldier as a drone hits him: this far from his middle, this high (with a margin). */
    static final double SOLDIER_REACH_M = 0.7, SOLDIER_HEIGHT_M = 2.1;
    /** A VEHICLE's patrol: this near and far ahead of the drone that sent it (near: the detection reaches about 80 m). */
    static final double VEHICLE_NEAR_M = 20, VEHICLE_FAR_M = 40;
    /** And this long, unless hit. */
    static final long VEHICLE_MS = 10 * 60_000L;

    /** What the camera sees from: the drone's state. */
    record View(double north, double east, double alt, double rollDeg, double pitchDeg, double headingDeg) {}

    /**
     * A world of its own drawn instead of the grid and posts (the tour's scenario, {@link BtrScene}); its picture then
     * gets the same name, VIRTUAL and clock burned in.
     */
    interface World {
        /** @param analysis the motion detection's small picture: the world alone, no screen effects */
        void render(BufferedImage img, View v, long nowMs, boolean analysis);
    }

    private volatile World world;
    private volatile String name;
    /** The world it stands in (every virtual drone's, 1.0.52). */
    private final SimWorld env = new SimWorld(0, 0);
    private final List<Target> targets = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean night;

    Scene(String name, long seed) {
        this.name = name;
    }

    void setName(String name) {
        this.name = name;
    }

    void setWorld(World w) {
        world = w;
    }

    World world() {
        return world;
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

    /** As addTarget, at dist metres in front (a VEHICLE: a BTR on patrol VEHICLE_NEAR_M to VEHICLE_FAR_M ahead). */
    void addTarget(Kind kind, View from, long nowMs, double dist) {
        if (kind == Kind.VEHICLE) {
            BtrScene btr = new BtrScene(from.north(), from.east(), from.headingDeg(), nowMs, VEHICLE_NEAR_M, VEHICLE_FAR_M);
            targets.add(new Target(kind, from.north(), from.east(), from.headingDeg(), 0, nowMs, nowMs + VEHICLE_MS, btr));
            return;
        }
        double speed = kind == Kind.VEHICLE ? 8 : kind == Kind.LIGHT ? 2 : 1.4;
        double half = Math.tan(Math.toRadians(HFOV_DEG / 2)) * dist * 1.08;
        double h = Math.toRadians(from.headingDeg());
        double cn = from.north() + Math.cos(h) * dist, ce = from.east() + Math.sin(h) * dist;
        // from the left edge across, perpendicular to the view
        double leftN = cn + Math.cos(h - Math.PI / 2) * half, leftE = ce + Math.sin(h - Math.PI / 2) * half;
        long dur = (long) (2 * half / speed * 1000);
        targets.add(new Target(kind, leftN, leftE, from.headingDeg() + 90, speed, nowMs, nowMs + dur));
    }

    /**
     * A soldier dist metres in front of the camera, a little to one side, walking straight at it (1.0.53: the detection
     * must find someone coming head-on, who hardly moves in the picture - he grows).
     */
    void addApproacher(View from, long nowMs, double dist, double sideM) {
        double h = Math.toRadians(from.headingDeg());
        double n = from.north() + Math.cos(h) * dist + Math.cos(h + Math.PI / 2) * sideM;
        double e = from.east() + Math.sin(h) * dist + Math.sin(h + Math.PI / 2) * sideM;
        double speed = 1.4;
        long dur = (long) ((dist - 3) / speed * 1000); // until 3 m off
        targets.add(new Target(Kind.WALKER, n, e, from.headingDeg() + 180, speed, nowMs, nowMs + dur));
    }

    /** The targets still crossing (the finished ones go). */
    List<Target> targets(long nowMs) {
        targets.removeIf(t -> nowMs > t.untilMs);
        return new ArrayList<>(targets);
    }

    boolean hasTargets(long nowMs) {
        return !targets(nowMs).isEmpty();
    }

    /** The drone flying here, every tick: its BTRs flee while it is in the air ({@link BtrScene#threat}). */
    void threat(double north, double east, boolean airborne, long nowMs) {
        for (Target t : targets) if (t.btr != null) t.btr.threat(north, east, airborne, nowMs);
    }

    /** The target a drone at this point flies into, taken out of the picture (1.0.52: it is hit); null for none. */
    Target hit(double north, double east, double alt, long nowMs) {
        for (Target t : targets(nowMs)) {
            if (t.hits(north, east, alt, nowMs)) {
                targets.remove(t);
                return t;
            }
        }
        return null;
    }

    /**
     * Draws the picture into img (TYPE_INT_RGB). Nothing is written on a generated picture any more (1.0.52, the pilot:
     * the OSD shows the state and the clock, the graphics make it plainly virtual); extra is unused there.
     */
    void render(BufferedImage img, View v, long nowMs, String extra) {
        render(img, v, nowMs, extra, true);
    }

    /**
     * @param burnIn false for the motion detection's picture (a world's screen effects left out); nothing is written
     *               on either since 1.0.52
     */
    void render(BufferedImage img, View v, long nowMs, String extra, boolean burnIn) {
        int w = img.getWidth(), h = img.getHeight();
        World wd = world;
        if (wd != null) {
            wd.render(img, v, nowMs, !burnIn);
            return;
        }
        Graphics2D g = img.createGraphics();
        Camera cam = new Camera(v, w, h);
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            env.paint(g, cam, v, w, h, nowMs, !burnIn, night);
        } finally {
            g.dispose();
        }
        paintTargets(img, v, nowMs);
        if (burnIn) SimWorld.effects(img, nowMs);
    }

    /**
     * The name, VIRTUAL and the clock written on a replayed flight's picture, extra at the bottom: real footage, so it
     * keeps saying it is a replay in the virtual fleet.
     */
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
        for (Target t : list) if (t.btr != null) t.btr.renderVehicle(img, v, nowMs, night); // through its own depth buffer
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Camera cam = new Camera(v, img.getWidth(), img.getHeight());
            for (Target t : list) if (t.btr == null) paintTarget(g, cam, t, nowMs);
        } finally {
            g.dispose();
        }
    }

    /**
     * A model of what moves (1.0.50, the pilot: the detection must be tested by something moving in the picture): a
     * soldier walking (a 3D model since 1.0.52, {@link Soldier}), a soldier with a lamp. Facing the way he goes. (The
     * BTR draws itself: {@link BtrScene#renderVehicle}.)
     */
    private void paintTarget(Graphics2D g, Camera cam, Target t, long nowMs) {
        double[] p = t.at(nowMs);
        double[] s = cam.screen(p[0], p[1]);
        if (s == null) return; // behind the camera
        double x = s[0], y = s[1], m = s[2]; // where it stands on the picture, pixels a metre there
        double walked = (nowMs - t.startMs) / 1000.0 * t.speed;
        switch (t.kind) {
            case WALKER -> {
                Soldier.paint(g, cam, p[0], p[1], t.dirDeg, walked, night);
                if (night) lamp(g, cam, p, t.dirDeg, new Color(0xfffbe0)); // a torch at night
            }
            case LIGHT -> {
                Soldier.paint(g, cam, p[0], p[1], t.dirDeg, walked, night);
                lamp(g, cam, p, t.dirDeg, new Color(0xffffe8));
            }
            case VEHICLE -> {
                // drawn by its BtrScene
            }
        }
    }

    /** A light held out in front of a walking soldier, chest high, and its glow. */
    private static void lamp(Graphics2D g, Camera cam, double[] at, double dirDeg, Color lamp) {
        double d = Math.toRadians(dirDeg);
        double[] k = cam.cam(at[0] + Math.cos(d) * 0.4, at[1] + Math.sin(d) * 0.4, 1.2);
        if (k[2] < 0.5) return;
        double hx = cam.cx + cam.f * k[0] / k[2], hy = cam.cy + cam.f * k[1] / k[2], m = cam.f / k[2];
        double r = Math.max(2, 0.12 * m);
        g.setColor(new Color(lamp.getRed(), lamp.getGreen(), lamp.getBlue(), 90));
        g.fill(new java.awt.geom.Ellipse2D.Double(hx - 2.5 * r, hy - 2.5 * r, 5 * r, 5 * r));
        g.setColor(lamp);
        g.fill(new java.awt.geom.Ellipse2D.Double(hx - r, hy - r, 2 * r, 2 * r));
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

    /**
     * The picture's chroma at half size each way, as a phone's YUV 4:2:0 analysis picture carries it (BT.601 video
     * range, as luma): {U, V}, each (w / 2) x (h / 2). The motion detection counts colour as well as brightness.
     */
    static byte[][] chroma(int[] px, int w, int h) {
        int cw = w / 2, ch = h / 2;
        byte[] u = new byte[cw * ch], v = new byte[cw * ch];
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                int r = 0, g = 0, b = 0;
                for (int j = 0; j < 2; j++) {
                    int row = (cy * 2 + j) * w + cx * 2;
                    for (int i = 0; i < 2; i++) {
                        int c = px[row + i];
                        r += c >> 16 & 0xff;
                        g += c >> 8 & 0xff;
                        b += c & 0xff;
                    }
                }
                r /= 4;
                g /= 4;
                b /= 4;
                u[cy * cw + cx] = (byte) ((-38 * r - 74 * g + 112 * b + 128 >> 8) + 128);
                v[cy * cw + cx] = (byte) ((112 * r - 94 * g - 18 * b + 128 >> 8) + 128);
            }
        }
        return new byte[][]{u, v};
    }

    /** The drone's camera: world (north, east, up) to the picture. */
    static final class Camera {
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
