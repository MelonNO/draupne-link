package de.droiddrone.pc.gui;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/**
 * A quad drawn in 3D as Betaflight sees it (1.0.42, Drone setup &gt; Orientation): it tilts and turns with Betaflight's
 * own attitude, seen from behind and above - the way the pilot stands at the bench, the drone's front away. Red props
 * and the camera at the front. Flat-shaded faces drawn far to near; a fixed grid on the ground under it.
 *
 * <p>Model space is the drone's (x forward, y right, z down, metres); Betaflight's roll positive = right side down,
 * pitch positive = nose down.
 */
final class DroneModelView extends JComponent {
    private record Face(double[][] p, Color color, boolean twoSided, boolean outline, boolean decal) {
        Face(double[][] p, Color color, boolean twoSided, boolean outline) {
            this(p, color, twoSided, outline, false);
        }
    }

    /** A decal (the arrow, the lens) is drawn after the face it lies on, which the far-to-near order can't tell. */
    private static final double DECAL_BIAS = 0.04;

    private static final Color FRAME = new Color(0x4a5158), PLATE = new Color(0x5f676f), MOTOR = new Color(0x8a9096),
            BELL = new Color(0xc9ced3), CAMERA = new Color(0x1c1e20), LENS = new Color(0x3d6fa8),
            FRONT_PROP = new Color(220, 60, 50, 150), REAR_PROP = new Color(150, 160, 170, 120), ARROW = new Color(0xe8b23a);
    /** Half the motor-to-motor distance along each axis: a 5" quad. */
    private static final double ARM = 0.08;
    private static final double GROUND = 0.07;
    /** The view: from behind, a little to the left, and above. */
    private static final double AZIMUTH = Math.toRadians(-18), ELEVATION = Math.toRadians(26), DISTANCE = 0.62;

    private final List<Face> model = build();
    private volatile double roll, pitch, yaw; // degrees, Betaflight's signs
    private volatile boolean live;

    DroneModelView() {
        setPreferredSize(new Dimension(520, 380));
        setOpaque(false);
    }

    /** Betaflight's attitude; yaw relative to where it started (degrees, clockwise from above). */
    void setAttitude(double rollDeg, double pitchDeg, double yawDeg) {
        roll = rollDeg;
        pitch = pitchDeg;
        yaw = yawDeg;
        live = true;
        repaint();
    }

    /** No reading (not connected): the drone is drawn flat and dim. */
    void clear() {
        live = false;
        roll = pitch = yaw = 0;
        repaint();
    }

    // ------------------------------------------------------------------ the model

    private static List<Face> build() {
        List<Face> f = new ArrayList<>();
        // the arms, from the middle out to each motor
        for (int i = 0; i < 4; i++) {
            double mx = (i < 2 ? 1 : -1) * ARM, my = (i % 2 == 0 ? 1 : -1) * ARM;
            double len = Math.hypot(mx, my), ux = mx / len, uy = my / len;
            box(f, 0.015, len, 0.016, -0.003, 0.003, ux, uy, FRAME);
        }
        box(f, -0.045, 0.055, -0.025, 0.025, -0.006, -0.003, FRAME); // the bottom plate's middle
        box(f, -0.035, 0.040, -0.016, 0.016, -0.026, -0.006, PLATE); // the stack
        box(f, -0.045, 0.050, -0.022, 0.022, -0.030, -0.026, FRAME); // the top plate
        // the camera at the front, its lens looking forward
        box(f, 0.040, 0.062, -0.013, 0.013, -0.026, -0.004, CAMERA);
        f.add(new Face(disc(0.0625, 0, -0.015, 0.0075, 'x', 16), LENS, false, false, true));
        // an arrow on the top plate, pointing forward
        f.add(new Face(new double[][]{{0.046, 0, -0.0305}, {0.010, -0.014, -0.0305}, {0.018, 0, -0.0305}, {0.010, 0.014, -0.0305}}, ARROW, false, false, true));
        // the motors and the props on them: red at the front
        for (int i = 0; i < 4; i++) {
            double mx = (i < 2 ? 1 : -1) * ARM, my = (i % 2 == 0 ? 1 : -1) * ARM;
            cylinder(f, mx, my, 0.0135, -0.004, 0.003, MOTOR, 14);
            cylinder(f, mx, my, 0.0125, -0.019, -0.004, BELL, 14);
            f.add(new Face(disc(mx, my, -0.022, 0.064, 'z', 32), mx > 0 ? FRONT_PROP : REAR_PROP, true, true));
        }
        return f;
    }

    /** An axis-aligned box. */
    private static void box(List<Face> f, double x0, double x1, double y0, double y1, double z0, double z1, Color c) {
        double[][] v = {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
        int[][] q = {{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 1, 5, 4}, {2, 3, 7, 6}, {1, 2, 6, 5}, {0, 4, 7, 3}};
        for (int[] k : q) f.add(new Face(new double[][]{v[k[0]], v[k[1]], v[k[2]], v[k[3]]}, c, false, true));
    }

    /** A box along the direction (ux, uy) from r0 to r1 out from the middle, w wide. */
    private static void box(List<Face> f, double r0, double r1, double w, double z0, double z1, double ux, double uy, Color c) {
        double px = -uy * w / 2, py = ux * w / 2;
        double[] a = {ux * r0 - px, uy * r0 - py}, b = {ux * r1 - px, uy * r1 - py}, d = {ux * r1 + px, uy * r1 + py}, e = {ux * r0 + px, uy * r0 + py};
        double[][] base = {a, b, d, e};
        double[][] v = new double[8][];
        for (int i = 0; i < 4; i++) {
            v[i] = new double[]{base[i][0], base[i][1], z0};
            v[i + 4] = new double[]{base[i][0], base[i][1], z1};
        }
        int[][] q = {{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 1, 5, 4}, {1, 2, 6, 5}, {2, 3, 7, 6}, {3, 0, 4, 7}};
        for (int[] k : q) f.add(new Face(new double[][]{v[k[0]], v[k[1]], v[k[2]], v[k[3]]}, c, false, true));
    }

    /** An upright cylinder (along z) from z0 (top, smaller z) to z1. */
    private static void cylinder(List<Face> f, double cx, double cy, double r, double z0, double z1, Color c, int n) {
        double[][] top = new double[n][], bottom = new double[n][];
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            top[i] = new double[]{cx + r * Math.cos(a), cy + r * Math.sin(a), z0};
            bottom[i] = new double[]{cx + r * Math.cos(a), cy + r * Math.sin(a), z1};
        }
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            f.add(new Face(new double[][]{top[i], top[j], bottom[j], bottom[i]}, c, false, false)); // wound outwards
        }
        double[][] topFace = new double[n][];
        for (int i = 0; i < n; i++) topFace[i] = top[n - 1 - i];
        f.add(new Face(topFace, c, false, true));
        f.add(new Face(bottom, c, false, false));
    }

    /** A flat disc facing along the axis ('z': up, 'x': forward). */
    private static double[][] disc(double cx, double cy, double cz, double r, char axis, int n) {
        double[][] p = new double[n][];
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            double u = r * Math.cos(a), v = r * Math.sin(a);
            // wound so the normal points up (-z) or forward (+x)
            p[i] = axis == 'z' ? new double[]{cx + u, cy - v, cz} : new double[]{cx, cy + u, cz + v};
        }
        return p;
    }

    // ------------------------------------------------------------------ drawing

    /** Model to world: yaw, then pitch, then roll (Betaflight's pitch positive = nose down: NED's negative). */
    static double[][] rotation(double rollDeg, double pitchDeg, double yawDeg) {
        double r = Math.toRadians(rollDeg), p = Math.toRadians(-pitchDeg), y = Math.toRadians(yawDeg);
        double cr = Math.cos(r), sr = Math.sin(r), cp = Math.cos(p), sp = Math.sin(p), cy = Math.cos(y), sy = Math.sin(y);
        return new double[][]{
                {cy * cp, cy * sp * sr - sy * cr, cy * sp * cr + sy * sr},
                {sy * cp, sy * sp * sr + cy * cr, sy * sp * cr - cy * sr},
                {-sp, cp * sr, cp * cr}};
    }

    private static double[] apply(double[][] m, double[] v) {
        return new double[]{m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
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
    private double[] project(double[] w) {
        double[] d = {w[0] - EYE[0], w[1] - EYE[1], w[2] - EYE[2]};
        double depth = dot(d, FORWARD);
        return new double[]{cx + scale * dot(d, RIGHT) / depth, cy + scale * dot(d, DOWN) / depth, depth};
    }

    private Path2D.Double outline(double[][] world) {
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < world.length; i++) {
            double[] s = project(world[i]);
            if (i == 0) path.moveTo(s[0], s[1]);
            else path.lineTo(s[0], s[1]);
        }
        path.closePath();
        return path;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        int w = getWidth(), h = getHeight();
        scale = Math.min(w, h * 1.25) * 1.3;
        cx = w / 2.0;
        cy = h * 0.52;
        double[][] m = rotation(roll, pitch, yaw);

        // the ground: a fixed grid, and the props' shadows straight down on it
        g.setStroke(new BasicStroke(1f));
        g.setColor(Theme.alpha(Theme.LINE, 150));
        for (int i = -5; i <= 5; i++) {
            double t = i * 0.05;
            g.draw(outline(new double[][]{{t, -0.25, GROUND}, {t, 0.25, GROUND}}));
            g.draw(outline(new double[][]{{-0.25, t, GROUND}, {0.25, t, GROUND}}));
        }
        g.setColor(new Color(0, 0, 0, 60));
        for (Face f : model) {
            if (!f.twoSided()) continue;
            double[][] s = new double[f.p().length][];
            for (int i = 0; i < s.length; i++) {
                double[] v = apply(m, f.p()[i]);
                s[i] = new double[]{v[0], v[1], GROUND};
            }
            g.fill(outline(s));
        }

        // the drone: faces turned to the camera, far ones first
        record Drawn(Path2D.Double path, Color color, double depth, boolean outline) {}
        List<Drawn> drawn = new ArrayList<>();
        for (Face f : model) {
            double[][] world = new double[f.p().length][];
            double[] centre = new double[3];
            for (int i = 0; i < world.length; i++) {
                world[i] = apply(m, f.p()[i]);
                for (int k = 0; k < 3; k++) centre[k] += world[i][k] / world.length;
            }
            double[] normal = unit(cross(sub(world[1], world[0]), sub(world[2], world[0])));
            double[] toEye = sub(EYE, centre);
            double facing = dot(normal, toEye);
            if (facing <= 0 && !f.twoSided()) continue;
            if (facing < 0) normal = new double[]{-normal[0], -normal[1], -normal[2]};
            double light = 0.42 + 0.58 * Math.max(0, dot(normal, LIGHT));
            Color c = shade(f.color(), live ? light : light * 0.6);
            drawn.add(new Drawn(outline(world), c, dot(sub(centre, EYE), FORWARD) - (f.decal() ? DECAL_BIAS : 0), f.outline()));
        }
        drawn.sort((a, b) -> Double.compare(b.depth(), a.depth()));
        g.setStroke(new BasicStroke(0.8f));
        for (Drawn d : drawn) {
            g.setColor(d.color());
            g.fill(d.path());
            if (d.outline()) {
                g.setColor(new Color(0, 0, 0, d.color().getAlpha() < 255 ? 70 : 110));
                g.draw(d.path());
            }
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

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static Color shade(Color c, double k) {
        return new Color(clamp(c.getRed() * k), clamp(c.getGreen() * k), clamp(c.getBlue() * k), c.getAlpha());
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
