package de.droiddrone.pc.gui;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * A 3D RadioMaster TX16S (Mode 2: throttle / yaw left, pitch / roll right) acting out the calibration step (1.0.39,
 * the pilot: "a 3d render of a FPV controller to illustrate what the user is supposed to do during calibration", then
 * "find a model online and use that instead"). The model is a CAD model of the TX16S MKII the pilot picked (STEP,
 * tessellated with OpenCASCADE into /model/tx16s-mkii.bin.gz: 47 named parts, ~62k triangles, see THIRD-PARTY.md),
 * made more real here: matte grained plastic, carbon-fibre backs, chrome toggles and pins, dark anodised stick ends,
 * the EdgeTX picture on the screen, the antenna folded back. What moves: each stick (GL) with its gimbal's inner piece
 * (G1) about the gimbal frame's centre, each toggle's paddle about its collar's top (the collar stays). The renderer, pure Java2D:
 * perspective, a depth buffer, per-pixel light (a studio key, fill and rim, a soft sky and floor), shadows from the
 * key light (a shadow map, softened), Fresnel reflections, filmic tone mapping, 2 x 2 supersampled. The stick or
 * switch the step wants glows red and moves, an arrow on the radio's face shows the way.
 */
final class ControllerRender extends JComponent {
    /** What the radio acts out. */
    enum Show { SWEEP, REST, THROTTLE_UP, YAW_RIGHT, PITCH_UP, ROLL_RIGHT, SWITCH, LIVE }

    private static final String RESOURCE = "/model/tx16s-mkii.bin.gz";
    private static final int SS = 2; // supersampling per side
    /**
     * A stick at full deflection: as far as this gimbal's frame lets it - the model's shaft touches the slot's rim
     * from 24 degrees (checked with trimesh over every direction, the 1.0.39 clipping round).
     */
    private static final double MAX_TILT = Math.toRadians(20);
    /**
     * A toggle as the CAD builds it: a round collar standing on its bushing's axis (out of the face for the front row,
     * up for the shoulders) and the flat paddle on the collar's top. The collar stays; the paddle hinges where it meets
     * the collar's top (the pilot: "the flat part of the switch should hinge at the cylindrical part ... now both
     * hinge") and throws from where the CAD draws it (one end) to the mirror of that about the axis - at most this.
     */
    private static final double LEVER_MAX_THROW = Math.toRadians(40);
    /** A vertex this far (cm) above the collar's top belongs to the paddle. */
    private static final double COLLAR_EPS = 0.004;
    /**
     * The camera per step, gliding from one to the next: for the sticks nearly square on to the face, so up on the
     * stick is up on screen (from higher up, a stick pushed forward points at the eye and reads as a ball); for a
     * switch from above and from its side, so its flip forward / back shows as a swing across the screen.
     */
    private static final double STICK_TILT = Math.toRadians(10), SWEEP_TILT = Math.toRadians(22), SWITCH_TILT_VIEW = Math.toRadians(38);
    private static final double SWITCH_YAW = Math.toRadians(42), SWITCH_ZOOM = 1.35;
    private static final double FRONT_SWITCH_TILT_VIEW = Math.toRadians(14), FRONT_SWITCH_YAW = Math.toRadians(72), FRONT_SWITCH_ZOOM = 2.6;
    private static final double DIST = 62;

    // groups: what moves together
    private static final int BODY = 0, LEFT_STICK = 1, RIGHT_STICK = 2, SWITCH0 = 3, SWITCHES = 8, GROUPS = SWITCH0 + SWITCHES;
    /**
     * The eight toggles by their TX16S names (RadioMaster's layout drawing): on the front of the top shoulders SA (left,
     * outer) SB (left, inner) SC (right, inner) SD (right, outer); on the shoulders SE (left, in front) SF (left, behind,
     * 2 positions) SG (right, in front) SH (right, behind, momentary). The calibration flips the one its AUX is for.
     */
    static final int SA = 0, SB = 1, SC = 2, SD = 3, SE = 4, SF = 5, SG = 6, SH = 7;
    private static final String[] SWITCH_NAMES = {"SA", "SB", "SC", "SD", "SE", "SF", "SG", "SH"};

    static String switchName(int slot) {
        return SWITCH_NAMES[Math.floorMod(slot, SWITCHES)];
    }

    /**
     * Which toggle a switch step flips (the pilot's radio): ARM on SF, PREARM on SH (momentary), the flight modes on
     * SA, GPS rescue (AUX4) on SC; anything else a spare one, by its AUX.
     */
    static int slotFor(String purpose, int aux) {
        if (purpose != null) {
            switch (purpose) {
                case "ARM" -> { return SF; }
                case "PREARM" -> { return SH; }
                case "flight modes" -> { return SA; }
                case "GPS rescue" -> { return SC; }
                default -> { }
            }
        }
        int[] spare = {SB, SD, SE, SG};
        return spare[Math.floorMod(aux, spare.length)];
    }

    // ---- materials: how a surface takes the light
    private static final int PLASTIC = 0, RUBBER = 1, CHROME = 2, ANODISED = 3, CARBON = 4, SCREEN = 5, BADGE = 6, GLOSS = 7, DARK = 8;
    // a part's role: lit when its stick / switch is the one asked for
    private static final int PLAIN = 0, CAP_LEFT = 1, CAP_RIGHT = 2, LEVER0 = 3; // LEVER0 + i: switch i's lever

    /** A part: its triangles, how it takes the light, what moves it. Positions in cm, the radio's centre at 0, z out of the face. */
    private record Part(String name, float[] p, float[] n, float[] uv, int[] t, int group, int kind, float r, float g, float b, int role) {}

    /** The model, loaded once: it never changes, only how it is posed and seen. */
    private static final class Model {
        final List<Part> parts = new ArrayList<>();
        int vertices, triangles;
        int[] triA, triB, triC, triPart, base;
        /**
         * The sticks' pivots and the toggles' (by slot), in cm: a lever turns where it leaves its bushing; its angle
         * there from the bushing's axis as the CAD has it, and which side that is (its OFF side).
         */
        final double[] leftPivot = new double[3], rightPivot = new double[3];
        final double[][] switchPivot = new double[SWITCHES][3];
        final double[] leverRest = new double[SWITCHES];
        /** Each lever's throw either side of its middle (radians). */
        final double[] leverThrow = new double[SWITCHES];
        /** Each bushing's axis, as an angle in the y-z plane from +y towards +z (the top row up, the front row out). */
        final double[] switchAxis = new double[SWITCHES];
        /** Each toggle's bushing (its box, cm) and its lever's part, for the check that a lever never leaves its bushing. */
        final double[][] bushingBox = new double[SWITCHES][];
        final int[] leverPart = new int[SWITCHES];
        /** The front of the gimbal frames, for the arrows. */
        double faceZ;
    }

    private static Model model;
    /** The screen's picture (Red ops - the pilot's pick of ten) and the badge's (plain carbon), made once. */
    private static int[] screenTex, badgeTex;
    private static final int SCREEN_W = 480, SCREEN_H = 248, BADGE_W = 256, BADGE_H = 152;

    private static synchronized Model model() {
        if (model == null) {
            screenTex = picture(SCREEN_W, SCREEN_H, ControllerRender::redOpsScreen);
            badgeTex = carbonBadge();
            try (InputStream in = ControllerRender.class.getResourceAsStream(RESOURCE)) {
                if (in == null) throw new IOException(RESOURCE + " missing");
                model = load(new DataInputStream(new GZIPInputStream(in, 1 << 16)));
            } catch (IOException e) {
                throw new IllegalStateException("The radio model could not be read: " + e.getMessage(), e);
            }
        }
        return model;
    }

    private final Model m;
    private Show show = Show.SWEEP;
    private int switchSlot; // SWITCH: the toggle flipped
    private String switchLabel = "";
    private long shownAt = System.currentTimeMillis();
    private float liveLx, liveLy, liveRx, liveRy;
    private double lx, ly, rx, ry; // the sticks as drawn (-1..1); at rest all centred
    /** Each lever's angle off its bushing's axis now (radians); the CAD pose is leverRest. */
    private final double[] lever = new double[SWITCHES];
    /** LIVE: each lever's snap from one place to the next - from, to, when it started. */
    private final double[] liveFrom = new double[SWITCHES], liveTo = new double[SWITCHES];
    private final long[] liveStart = new long[SWITCHES];
    private long fixedNow = -1; // tests: the clock stands still
    private double camTilt = STICK_TILT, camYaw, camZoom = 1, camFx, camFy = 0.3, camFz; // camF: the point looked at

    // per frame
    private final double[][] group = new double[GROUPS][];
    private double focal, centreX, centreY;
    private final float[] vx, vy, vz, nx, ny, nz, sx, sy, iz, lu, lv, ld;
    private int bufW, bufH;
    private float[] zbuf = new float[0], gb0 = new float[0], gb1 = new float[0];
    private int[] gtri = new int[0];
    private static final int SM = 768; // the shadow map's side
    private final float[] smap = new float[SM * SM];
    private double[] lightU, lightV;
    private double smMinU, smMinV, smScale;
    private BufferedImage frameImage;
    private final Timer timer = new Timer(33, e -> { // 30 frames a second; a still radio is not drawn again
        if (isShowing()) repaint();
    });

    ControllerRender() {
        m = model();
        setOpaque(false);
        setPreferredSize(new Dimension(440, 300));
        for (int i = 0; i < SWITCHES; i++) lever[i] = liveFrom[i] = liveTo[i] = offSide(i);
        int v = m.vertices;
        vx = new float[v];
        vy = new float[v];
        vz = new float[v];
        nx = new float[v];
        ny = new float[v];
        nz = new float[v];
        sx = new float[v];
        sy = new float[v];
        iz = new float[v];
        lu = new float[v];
        lv = new float[v];
        ld = new float[v];
    }

    @Override
    public void addNotify() {
        super.addNotify();
        timer.start();
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    /** The step to act out (not SWITCH: see showSwitch). */
    void show(Show s) {
        showSwitch(s, switchSlot, switchLabel);
    }

    /** SWITCH: the toggle in this slot (slotFor) flips, the label says which AUX. */
    void showSwitch(int slot, String label) {
        showSwitch(Show.SWITCH, slot, label);
    }

    private void showSwitch(Show s, int slot, String label) {
        if (s == show && slot == switchSlot && label.equals(switchLabel)) return;
        show = s;
        switchSlot = Math.floorMod(slot, SWITCHES);
        switchLabel = label;
        shownAt = now();
        repaint();
    }

    Show shown() {
        return show;
    }

    int switchSlot() {
        return switchSlot;
    }

    /** LIVE: each toggle as its channel has it, -1 (1000) .. 1 (2000), NaN for one that no channel is on. */
    private final double[] liveSwitch = filledNaN();

    private static double[] filledNaN() {
        double[] d = new double[SWITCHES];
        java.util.Arrays.fill(d, Double.NaN);
        return d;
    }

    /** LIVE (the final check, the pilot: "make the switches move when pressed"): a toggle's channel, -1..1, NaN: none. */
    void setLiveSwitch(int slot, double value) {
        liveSwitch[Math.floorMod(slot, SWITCHES)] = Double.isNaN(value) ? Double.NaN : Math.max(-1, Math.min(1, value));
    }

    /** LIVE: the sticks as the controller has them, -1..1 (throttle down = ly -1, centred 0). */
    void setLive(float lx, float ly, float rx, float ry) {
        liveLx = clamp(lx);
        liveLy = clamp(ly);
        liveRx = clamp(rx);
        liveRy = clamp(ry);
    }

    /** A lever's OFF position: the side of the axis the CAD has it on. */
    private double offSide(int slot) {
        return m.leverRest[slot] >= 0 ? m.leverThrow[slot] : -m.leverThrow[slot];
    }

    /** How far past its end a snapping lever goes, at most (of its swing). */
    static final double SNAP_OVERSHOOT = 0.12;

    /** A toggle snapping over: quick, a little past, back (0..1 over 140 ms). */
    private static double snap(double t) {
        if (t <= 0) return 0;
        if (t >= 0.14) return 1;
        double x = t / 0.14;
        return 1 + Math.sin(x * Math.PI * 1.5) * SNAP_OVERSHOOT * (1 - x) - Math.pow(1 - x, 3);
    }

    private static float clamp(float v) {
        return Math.max(-1, Math.min(1, v));
    }

    private long now() {
        return fixedNow >= 0 ? fixedNow : System.currentTimeMillis();
    }

    // ---- the model

    /** The model's millimetres to the radio's own cm: centred on the body, the face towards +z. */
    private static final double CX = -10, CY = 2.5, MM = 0.1;

    /**
     * Reads the mesh resource ('TXM1', parts: name, rgb, vertices (mm), normals (int8 x 3), triangles) and makes the
     * radio of it: materials by part name, the moving parts in their groups, the pivots from the parts themselves.
     */
    private static Model load(DataInputStream in) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!new String(magic, java.nio.charset.StandardCharsets.US_ASCII).equals("TXM1")) throw new IOException("not a TXM1 mesh");
        int count = in.readInt();
        record Raw(String name, float[] p, float[] n, int[] t, double[] box) {}
        List<Raw> raws = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            byte[] nameBytes = new byte[in.readUnsignedShort()];
            in.readFully(nameBytes);
            String name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
            in.readFloat(); // the CAD colour: the materials below choose their own
            in.readFloat();
            in.readFloat();
            int nv = in.readInt();
            float[] p = new float[nv * 3], n = new float[nv * 3];
            double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int i = 0; i < nv; i++) {
                double x = (in.readFloat() - CX) * MM, y = (in.readFloat() - CY) * MM, z = in.readFloat() * MM;
                p[i * 3] = (float) x;
                p[i * 3 + 1] = (float) y;
                p[i * 3 + 2] = (float) z;
                box[0] = Math.min(box[0], x);
                box[1] = Math.min(box[1], y);
                box[2] = Math.min(box[2], z);
                box[3] = Math.max(box[3], x);
                box[4] = Math.max(box[4], y);
                box[5] = Math.max(box[5], z);
            }
            for (int i = 0; i < nv * 3; i++) n[i] = in.readByte() / 127f;
            int nt = in.readInt();
            int[] t = new int[nt * 3];
            boolean small = nv < 65536;
            for (int i = 0; i < nt * 3; i++) t[i] = small ? in.readUnsignedShort() : in.readInt();
            raws.add(new Raw(name, p, n, t, box));
        }
        Model md = new Model();
        // the gimbals: the frame's centre is the pivot, a little behind its front
        for (Raw r : raws) {
            if (!r.name().startsWith("Part1Gimble")) continue;
            double[] pv = centre(r.box())[0] < 0 ? md.leftPivot : md.rightPivot;
            pv[0] = (r.box()[0] + r.box()[3]) / 2;
            pv[1] = (r.box()[1] + r.box()[4]) / 2;
            pv[2] = r.box()[5] - 0.4;
            md.faceZ = Math.max(md.faceZ, r.box()[5]);
        }
        // the toggles: each lever with its bushing (the nearest); the row by which way the lever points (up: the top
        // row on the shoulders, out of the face: the front row), the side by x, outer = further from the middle
        List<Raw> levers = new ArrayList<>(), bushings = new ArrayList<>();
        for (Raw r : raws) {
            if (r.name().startsWith("SD3 L")) levers.add(r); // "SD3 L" and "SD3 Long"
            else if (r.name().startsWith("SD3:")) bushings.add(r);
        }
        java.util.Map<Raw, Integer> slotOf = new java.util.HashMap<>();
        java.util.Map<Raw, double[]> leverInfo = new java.util.HashMap<>(); // pivot x y z, angle off the axis, the axis
        java.util.Map<Raw, double[]> bushingOf = new java.util.HashMap<>();
        java.util.Map<Raw, double[]> collarOf = new java.util.HashMap<>(); // axis point, axis, the collar's top along it
        List<Raw> topLeft = new ArrayList<>(), frontLeft = new ArrayList<>(), topRight = new ArrayList<>(), frontRight = new ArrayList<>();
        for (Raw l : levers) {
            double[] c = centre(l.box());
            Raw best = null;
            double bestD = Double.MAX_VALUE;
            for (Raw bu : bushings) {
                double[] bc = centre(bu.box());
                double d = Math.pow(bc[0] - c[0], 2) + Math.pow(bc[1] - c[1], 2) + Math.pow(bc[2] - c[2], 2);
                if (d < bestD) {
                    bestD = d;
                    best = bu;
                }
            }
            double[] bb = best == null ? l.box() : best.box(), bc = centre(bb);
            double fx = 0, fy = 0, fz = 0, far = -1; // the lever's tip: its point farthest from the bushing
            float[] p = l.p();
            for (int i = 0; i < p.length; i += 3) {
                double d = Math.pow(p[i] - bc[0], 2) + Math.pow(p[i + 1] - bc[1], 2) + Math.pow(p[i + 2] - bc[2], 2);
                if (d > far) {
                    far = d;
                    fx = p[i] - bc[0];
                    fy = p[i + 1] - bc[1];
                    fz = p[i + 2] - bc[2];
                }
            }
            boolean front = Math.abs(fz) > Math.abs(fy); // out of the face: the front row
            // the bushing's axis (straight out of the face, or up), and along it the collar: from its foot (the lowest
            // ring) to its top, the highest point on the collar's wall (the paddle's own points are nearer the axis)
            double[] a = front ? new double[]{0, 0, Math.signum(fz)} : new double[]{0, Math.signum(fy), 0};
            double foot = Double.MAX_VALUE;
            for (int i = 0; i < p.length; i += 3) foot = Math.min(foot, along(p, i, bc, a));
            double radius = 0;
            for (int i = 0; i < p.length; i += 3) if (along(p, i, bc, a) < foot + 0.02) radius = Math.max(radius, across(p, i, bc, a));
            double top = foot;
            for (int i = 0; i < p.length; i += 3) {
                double r = across(p, i, bc, a), alongI = along(p, i, bc, a);
                if (r > 0.97 * radius && r < 1.03 * radius && alongI < foot + 2.0) top = Math.max(top, alongI);
            }
            List<Float> paddle = new ArrayList<>();
            for (int i = 0; i < p.length; i += 3) {
                if (along(p, i, bc, a) > top + COLLAR_EPS) for (int k = 0; k < 3; k++) paddle.add(p[i + k]);
            }
            float[] pp = new float[paddle.size()];
            for (int i = 0; i < pp.length; i++) pp[i] = paddle.get(i);
            double[] line = centreLine(pp.length >= 9 ? pp : p, new double[]{fx + bc[0], fy + bc[1], fz + bc[2]});
            double[] pivot = {bc[0] + a[0] * top, bc[1] + a[1] * top, bc[2] + a[2] * top};
            double axisAngle = Math.atan2(a[2], a[1]); // in the y-z plane, from +y towards +z
            double rest = Math.IEEEremainder(Math.atan2(line[5], line[4]) - axisAngle, 2 * Math.PI);
            leverInfo.put(l, new double[]{pivot[0], pivot[1], pivot[2], rest, axisAngle, front ? 1 : 0});
            collarOf.put(l, new double[]{bc[0], bc[1], bc[2], a[0], a[1], a[2], top});
            bushingOf.put(l, bb);
            boolean leftSide = c[0] < 0;
            (front ? (leftSide ? frontLeft : frontRight) : (leftSide ? topLeft : topRight)).add(l);
        }
        // the front row by how far out (SA, SD outer), the shoulders by front and back (SE, SG in front; SF, SH behind)
        Comparator<Raw> outerFirst = Comparator.comparingDouble(l -> -Math.abs(centre(l.box())[0]));
        Comparator<Raw> frontFirst = Comparator.comparingDouble(l -> -centre(l.box())[2]);
        int[][] rows = {{SE, SF}, {SA, SB}, {SG, SH}, {SD, SC}};
        List<List<Raw>> groups = List.of(topLeft, frontLeft, topRight, frontRight);
        for (int gi = 0; gi < 4; gi++) {
            List<Raw> row = new ArrayList<>(groups.get(gi));
            row.sort(gi % 2 == 0 ? frontFirst : outerFirst);
            for (int i = 0; i < Math.min(2, row.size()); i++) {
                int slot = rows[gi][i];
                slotOf.put(row.get(i), slot);
                double[] info = leverInfo.get(row.get(i));
                md.switchPivot[slot] = new double[]{info[0], info[1], info[2]};
                md.leverRest[slot] = info[3];
                md.leverThrow[slot] = Math.min(LEVER_MAX_THROW, Math.abs(info[3])); // its CAD end and the mirror
                md.switchAxis[slot] = info[4];
                md.bushingBox[slot] = bushingOf.get(row.get(i));
            }
        }
        // 1.0.52 (the pilot: "the top switches on the left side are not symmetrical with the switches on the right"):
        // the CAD has each right-hand paddle twisted and leaning its own way. Each right toggle is its left partner
        // mirrored across the pair's middle - same shape, same rest, same throw; the right bushings stay where they are.
        java.util.Map<Integer, Raw> bySlot = new java.util.HashMap<>();
        slotOf.forEach((r, s) -> bySlot.put(s, r));
        for (int[] pair : new int[][]{{SB, SC}, {SA, SD}, {SE, SG}, {SF, SH}}) {
            Raw l = bySlot.get(pair[0]), r = bySlot.get(pair[1]);
            if (l == null || r == null) continue;
            double mid = (md.switchPivot[pair[0]][0] + md.switchPivot[pair[1]][0]) / 2;
            float[] p = l.p().clone(), n = l.n().clone();
            int[] t = l.t().clone();
            for (int i = 0; i < p.length; i += 3) {
                p[i] = (float) (2 * mid - p[i]);
                n[i] = -n[i];
            }
            for (int i = 0; i < t.length; i += 3) { // mirrored: the other winding, so its faces still face out
                int k = t[i + 1];
                t[i + 1] = t[i + 2];
                t[i + 2] = k;
            }
            double[] b = l.box();
            Raw mirrored = new Raw(r.name(), p, n, t, new double[]{2 * mid - b[3], b[1], b[2], 2 * mid - b[0], b[4], b[5]});
            double[] c = collarOf.get(l);
            collarOf.put(mirrored, new double[]{2 * mid - c[0], c[1], c[2], -c[3], c[4], c[5], c[6]});
            raws.set(raws.indexOf(r), mirrored);
            slotOf.remove(r);
            slotOf.put(mirrored, pair[1]);
            double[] pl = md.switchPivot[pair[0]];
            md.switchPivot[pair[1]] = new double[]{2 * mid - pl[0], pl[1], pl[2]};
            md.leverRest[pair[1]] = md.leverRest[pair[0]];
            md.leverThrow[pair[1]] = md.leverThrow[pair[0]];
            md.switchAxis[pair[1]] = md.switchAxis[pair[0]];
        }
        // the antenna folds back, about its base
        double[] antPivot = null;
        for (Raw r : raws) if (r.name().startsWith("Ant Base")) antPivot = new double[]{(r.box()[0] + r.box()[3]) / 2, r.box()[4] - 0.4, (r.box()[2] + r.box()[5]) / 2};
        for (Raw r : raws) {
            String nm = r.name();
            int groupId = BODY, role = PLAIN, kind, rgb;
            boolean leftSide = centre(r.box())[0] < 0;
            if (nm.startsWith("GL")) {
                groupId = leftSide ? LEFT_STICK : RIGHT_STICK;
                role = leftSide ? CAP_LEFT : CAP_RIGHT;
                kind = ANODISED;
                rgb = 0x2a2c30;
            } else if (nm.startsWith("G1")) { // the gimbal's inner plate stays (tilted with the stick it came up through the frame,
                // slid it went into it); the shaft tips through its hole
                kind = DARK;
                rgb = 0x141517;
            } else if (slotOf.containsKey(r)) {
                int slot = slotOf.get(r);
                groupId = SWITCH0 + slot;
                role = LEVER0 + slot;
                kind = CHROME;
                rgb = 0xd2d5da;
            } else if (nm.startsWith("SD3:") || nm.startsWith("Handle Pin")) {
                kind = CHROME;
                rgb = 0xb8bcc2;
            } else if (nm.startsWith("Screen")) {
                kind = SCREEN;
                rgb = 0xffffff;
            } else if (nm.startsWith("TX16S")) {
                kind = BADGE;
                rgb = 0x1a1b1d;
            } else if (nm.startsWith("CF Back")) {
                kind = CARBON;
                rgb = 0x1b1c1e;
            } else if (nm.startsWith("Part1Gimble")) {
                kind = DARK;
                rgb = 0x18191b;
            } else if (nm.startsWith("Button") || nm.startsWith("Men Wheel") || nm.startsWith("Antenna:") || nm.startsWith("Ant Pin")) {
                kind = RUBBER;
                rgb = 0x1c1d20;
            } else if (nm.startsWith("Dial")) {
                kind = ANODISED;
                rgb = 0x2e3034;
            } else if (nm.startsWith("Handle") || nm.startsWith("Lanyard") || nm.startsWith("Ant")) {
                kind = GLOSS;
                rgb = 0x1a1b1e;
            } else { // the shell
                kind = PLASTIC;
                rgb = 0x222427;
            }
            float[] p = r.p(), n = r.n();
            int[] tris = r.t();
            Object[] collar = null;
            if (slotOf.containsKey(r)) { // a lever: the paddle moves, its collar stays with the radio
                Object[][] split = splitCollar(p, n, tris, collarOf.get(r));
                collar = split[0];
                p = (float[]) split[1][0];
                n = (float[]) split[1][1];
                tris = (int[]) split[1][2];
            } else if (nm.startsWith("GL")) { // a stick: from just under its inner plate's top up (it reached the gimbal's floor)
                double top = 0;
                for (Raw q : raws) if (q.name().startsWith("G1") && (centre(q.box())[0] < 0) == leftSide) top = q.box()[5];
                Object[] cut = clip(p, n, tris, new double[]{0, 0, top}, new double[]{0, 0, 1}, STICK_KEEP);
                p = (float[]) cut[0];
                n = (float[]) cut[1];
                tris = (int[]) cut[2];
            }
            if (antPivot != null && (nm.startsWith("Antenna:") || nm.startsWith("Ant Pin"))) {
                Mat fold = Mat.translate(antPivot[0], antPivot[1], antPivot[2]).mul(Mat.rotX(Math.toRadians(-72)))
                        .mul(Mat.translate(-antPivot[0], -antPivot[1], -antPivot[2]));
                for (int i = 0; i < p.length; i += 3) {
                    double[] q = fold.apply(p[i], p[i + 1], p[i + 2]), o = fold.rotate(n[i], n[i + 1], n[i + 2]);
                    p[i] = (float) q[0];
                    p[i + 1] = (float) q[1];
                    p[i + 2] = (float) q[2];
                    n[i] = (float) o[0];
                    n[i + 1] = (float) o[1];
                    n[i + 2] = (float) o[2];
                }
            }
            // uv: the picture's on the screen and the badge (across its front), the weave on the carbon (2 mm a step)
            float[] uv = new float[p.length / 3 * 2];
            double[] box = r.box();
            for (int i = 0, j = 0; i < p.length; i += 3, j += 2) {
                if (kind == SCREEN || kind == BADGE) {
                    uv[j] = (float) ((p[i] - box[0]) / Math.max(1e-6, box[3] - box[0]));
                    uv[j + 1] = (float) ((box[4] - p[i + 1]) / Math.max(1e-6, box[4] - box[1]));
                } else {
                    uv[j] = p[i] * 5;
                    uv[j + 1] = p[i + 1] * 5;
                }
            }
            if (slotOf.containsKey(r)) md.leverPart[slotOf.get(r)] = md.parts.size();
            md.parts.add(new Part(nm, p, n, uv, tris, groupId, kind, lin((rgb >> 16) & 255), lin((rgb >> 8) & 255), lin(rgb & 255), role));
            if (collar != null && ((int[]) collar[2]).length > 0) {
                float[] cp = (float[]) collar[0];
                float[] cuv = new float[cp.length / 3 * 2];
                for (int i = 0, j = 0; i < cp.length; i += 3, j += 2) {
                    cuv[j] = cp[i] * 5;
                    cuv[j + 1] = cp[i + 1] * 5;
                }
                md.parts.add(new Part(nm + " collar", cp, (float[]) collar[1], cuv, (int[]) collar[2], BODY, CHROME,
                        lin(0xd2), lin(0xd5), lin(0xda), PLAIN));
            }
        }
        int v = 0, t = 0;
        md.base = new int[md.parts.size()];
        for (int i = 0; i < md.parts.size(); i++) {
            md.base[i] = v;
            v += md.parts.get(i).p().length / 3;
            t += md.parts.get(i).t().length / 3;
        }
        md.vertices = v;
        md.triangles = t;
        md.triA = new int[t];
        md.triB = new int[t];
        md.triC = new int[t];
        md.triPart = new int[t];
        int k = 0;
        for (int i = 0; i < md.parts.size(); i++) {
            int[] tt = md.parts.get(i).t();
            for (int j = 0; j < tt.length; j += 3, k++) {
                md.triA[k] = md.base[i] + tt[j];
                md.triB[k] = md.base[i] + tt[j + 1];
                md.triC[k] = md.base[i] + tt[j + 2];
                md.triPart[k] = i;
            }
        }
        return md;
    }

    /** How far along the axis (from the axis point) vertex i of p is. */
    private static double along(float[] p, int i, double[] o, double[] a) {
        return (p[i] - o[0]) * a[0] + (p[i + 1] - o[1]) * a[1] + (p[i + 2] - o[2]) * a[2];
    }

    /** How far from the axis vertex i of p is. */
    private static double across(float[] p, int i, double[] o, double[] a) {
        double dx = p[i] - o[0], dy = p[i + 1] - o[1], dz = p[i + 2] - o[2];
        double s = dx * a[0] + dy * a[1] + dz * a[2];
        dx -= s * a[0];
        dy -= s * a[1];
        dz -= s * a[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * A lever split at its collar's top: {collar, paddle}, each {positions, normals, triangles}. A triangle with every
     * corner up to the collar's top is the collar's; one with any corner above it, the paddle's.
     * @param c axis point x y z, axis x y z, the collar's top along it
     */
    private static Object[][] splitCollar(float[] p, float[] n, int[] t, double[] c) {
        double[] o = {c[0], c[1], c[2]}, a = {c[3], c[4], c[5]};
        double top = c[6] + COLLAR_EPS;
        List<Integer> low = new ArrayList<>(), high = new ArrayList<>();
        for (int i = 0; i < t.length; i += 3) {
            boolean up = along(p, t[i] * 3, o, a) > top || along(p, t[i + 1] * 3, o, a) > top || along(p, t[i + 2] * 3, o, a) > top;
            List<Integer> to = up ? high : low;
            to.add(t[i]);
            to.add(t[i + 1]);
            to.add(t[i + 2]);
        }
        return new Object[][]{subMesh(p, n, low), subMesh(p, n, high)};
    }

    /** The vertices these triangles use, renumbered: {positions, normals, triangles}. */
    private static Object[] subMesh(float[] p, float[] n, List<Integer> tris) {
        java.util.Map<Integer, Integer> map = new java.util.LinkedHashMap<>();
        int[] nt = new int[tris.size()];
        for (int i = 0; i < nt.length; i++) nt[i] = map.computeIfAbsent(tris.get(i), k -> map.size());
        float[] np = new float[map.size() * 3], nn = new float[map.size() * 3];
        for (var e : map.entrySet()) {
            System.arraycopy(p, e.getKey() * 3, np, e.getValue() * 3, 3);
            System.arraycopy(n, e.getKey() * 3, nn, e.getValue() * 3, 3);
        }
        return new Object[]{np, nn, nt};
    }

    /**
     * How deep under its inner plate's top a stick is kept (cm): the CAD's stick reaches down to the gimbal's floor, 1 mm
     * under the pivot, and any tilt swung that end into the floor and out at the slot; the plate hides what stays.
     */
    static final double STICK_KEEP = 0.1;

    /**
     * A part's centre line: its middle and its long direction (the covariance's main axis, by power iteration),
     * pointing towards `tip`: {cx, cy, cz, dx, dy, dz}.
     */
    private static double[] centreLine(float[] p, double[] tip) {
        int n = p.length / 3;
        double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < n; i++) {
            cx += p[i * 3];
            cy += p[i * 3 + 1];
            cz += p[i * 3 + 2];
        }
        cx /= n;
        cy /= n;
        cz /= n;
        double[][] cov = new double[3][3];
        for (int i = 0; i < n; i++) {
            double[] d = {p[i * 3] - cx, p[i * 3 + 1] - cy, p[i * 3 + 2] - cz};
            for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) cov[a][b] += d[a] * d[b];
        }
        double[] v = {tip[0] - cx, tip[1] - cy, tip[2] - cz};
        for (int it = 0; it < 60; it++) {
            double[] w = new double[3];
            for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) w[a] += cov[a][b] * v[b];
            double l = Math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]);
            v = new double[]{w[0] / l, w[1] / l, w[2] / l};
        }
        if (v[0] * (tip[0] - cx) + v[1] * (tip[1] - cy) + v[2] * (tip[2] - cz) < 0) v = new double[]{-v[0], -v[1], -v[2]};
        return new double[]{cx, cy, cz, v[0], v[1], v[2]};
    }

    /**
     * A mesh cut by a plane, the side its normal points to kept (and keep cm behind it): triangles sliced where they
     * cross it (the new corners interpolated, normals too), so long ones - a stick's shaft - are not lost whole.
     * {positions, normals, triangles}.
     */
    private static Object[] clip(float[] p, float[] n, int[] t, double[] point, double[] normal, double keep) {
        List<float[]> verts = new ArrayList<>(); // x y z nx ny nz
        for (int i = 0; i < p.length; i += 3) verts.add(new float[]{p[i], p[i + 1], p[i + 2], n[i], n[i + 1], n[i + 2]});
        List<Integer> out = new ArrayList<>();
        java.util.function.ToDoubleFunction<float[]> side = v -> (v[0] - point[0]) * normal[0] + (v[1] - point[1]) * normal[1]
                + (v[2] - point[2]) * normal[2] + keep;
        for (int i = 0; i < t.length; i += 3) {
            int[] tri = {t[i], t[i + 1], t[i + 2]};
            List<Integer> poly = new ArrayList<>();
            for (int k = 0; k < 3; k++) {
                int a = tri[k], b = tri[(k + 1) % 3];
                double da = side.applyAsDouble(verts.get(a)), db = side.applyAsDouble(verts.get(b));
                if (da >= 0) poly.add(a);
                if ((da >= 0) != (db >= 0)) { // the edge crosses: a new corner on the plane
                    double f = da / (da - db);
                    float[] va = verts.get(a), vb = verts.get(b), v = new float[6];
                    for (int c = 0; c < 6; c++) v[c] = (float) (va[c] + (vb[c] - va[c]) * f);
                    verts.add(v);
                    poly.add(verts.size() - 1);
                }
            }
            for (int k = 1; k + 1 < poly.size(); k++) {
                out.add(poly.get(0));
                out.add(poly.get(k));
                out.add(poly.get(k + 1));
            }
        }
        float[] np = new float[verts.size() * 3], nn = new float[verts.size() * 3];
        for (int i = 0; i < verts.size(); i++) {
            float[] v = verts.get(i);
            System.arraycopy(v, 0, np, i * 3, 3);
            System.arraycopy(v, 3, nn, i * 3, 3);
        }
        int[] nt = new int[out.size()];
        for (int i = 0; i < nt.length; i++) nt[i] = out.get(i);
        return new Object[]{np, nn, nt};
    }

    /**
     * For tests: how many of a lever's points, at this angle off its bushing's axis, are under the bushing's face but
     * outside the bushing (seen through its sides) - 0 when it never clips.
     */
    static int leverPointsOutsideBushing(int slot, double angle) {
        Model md = model();
        Part lever = md.parts.get(md.leverPart[slot]);
        double[] pv = md.switchPivot[slot], box = md.bushingBox[slot];
        double rot = angle - md.leverRest[slot], c = Math.cos(rot), sn = Math.sin(rot);
        // under the bushing's face: the face itself, flat (the front row's points out of the radio's face, the shoulders' up)
        boolean front = Math.abs(Math.sin(md.switchAxis[slot])) > Math.abs(Math.cos(md.switchAxis[slot]));
        double ay = front ? 0 : 1, az = front ? 1 : 0;
        double faceY = front ? pv[1] : box[4], faceZ = front ? box[5] : pv[2]; // the front ones hinge under their face
        float[] p = lever.p();
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (int v : lever.t()) used.add(v);
        int outside = 0;
        double tol = 0.02; // 0.2 mm
        for (int v : used) {
            double x = p[v * 3], y = p[v * 3 + 1] - pv[1], z = p[v * 3 + 2] - pv[2];
            double y2 = y * c - z * sn + pv[1], z2 = y * sn + z * c + pv[2]; // rotX, as the view does it
            boolean under = (y2 - faceY) * ay + (z2 - faceZ) * az < 0;
            boolean inside = x >= box[0] - tol && x <= box[3] + tol && y2 >= box[1] - tol && y2 <= box[4] + tol && z2 >= box[2] - tol && z2 <= box[5] + tol;
            if (under && !inside) outside++;
        }
        return outside;
    }

    /** For tests: the angles a lever is drawn at - OFF, ON, and the snap's overshoot past each. */
    static double[] leverAngles(int slot) {
        Model md = model();
        double off = md.leverRest[slot] >= 0 ? md.leverThrow[slot] : -md.leverThrow[slot];
        return new double[]{off, -off, off * (1 + SNAP_OVERSHOOT), -off * (1 + SNAP_OVERSHOOT)};
    }

    private static double[] centre(double[] box) {
        return new double[]{(box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2};
    }

    /** sRGB 0..255 to linear light. */
    private static float lin(int c) {
        return (float) Math.pow(c / 255.0, 2.2);
    }

    // ---- the pictures on it

    /** The badge between the gimbals: plain carbon (the pilot: no TX16S name on it). */
    private static int[] carbonBadge() {
        BufferedImage img = new BufferedImage(BADGE_W, BADGE_H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < BADGE_H; y++) {
            for (int x = 0; x < BADGE_W; x++) {
                int c = (int) (22 + 26 * weave(x / 9.0, y / 9.0));
                img.setRGB(x, y, (c << 16) | (c << 8) | (c + 2));
            }
        }
        return img.getRGB(0, 0, BADGE_W, BADGE_H, null, 0, BADGE_W);
    }

    /** For pictures: the screen as it is drawn on the radio. */
    static BufferedImage screenPicture() {
        model();
        int[] px = screenTex;
        BufferedImage img = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, SCREEN_W, SCREEN_H, px, 0, SCREEN_W);
        return img;
    }

    /** A picture drawn by `draw` (antialiased), as pixels. */
    private static int[] picture(int w, int h, java.util.function.Consumer<Graphics2D> draw) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            draw.accept(g);
        } finally {
            g.dispose();
        }
        return img.getRGB(0, 0, w, h, null, 0, w);
    }

    /** A top bar like EdgeTX's: dark, a line of the accent under it, the name left, the battery and signal right. */
    private static void topBar(Graphics2D g, Color bar, Color line, Color text, String title) {
        g.setColor(bar);
        g.fillRect(0, 0, SCREEN_W, 24);
        g.setColor(line);
        g.fillRect(0, 23, SCREEN_W, 2);
        g.setColor(text);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString(title, 10, 17);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.drawString("16.4V", SCREEN_W - 50, 17);
        for (int i = 0; i < 5; i++) g.fillRect(SCREEN_W - 110 + i * 8, 18 - 3 - i * 3, 5, 3 + i * 3);
    }

    /**
     * The radio's screen, Red ops (the pilot chose it of ten, Minimal's large mark in it: "I basically want the logo
     * larger"): black and red, a grid, the DRAUPNE mark large with its name over a faint crosshair, the flight state in
     * a column, ARMED.
     */
    private static void redOpsScreen(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, new Color(16, 0, 2), 0, SCREEN_H, new Color(4, 0, 0)));
        g.fillRect(0, 0, SCREEN_W, SCREEN_H);
        g.setColor(new Color(227, 27, 35, 34));
        for (int x = 0; x < SCREEN_W; x += 12) g.drawLine(x, 26, x, SCREEN_H);
        for (int y = 26; y < SCREEN_H; y += 12) g.drawLine(0, y, SCREEN_W, y);
        topBar(g, new Color(20, 0, 2), new Color(227, 27, 35), new Color(255, 80, 80), "DRAUPNE  OPS");
        // Red ops and Minimal in one (the pilot: "combine minimal and red ops, I basically want the logo larger"): the
        // mark large on the left with its name, over a faint crosshair; the flight state beside it
        Color red = new Color(255, 40, 46);
        int cx = 140, cy = 118;
        g.setColor(new Color(255, 40, 46, 70));
        g.setStroke(new BasicStroke(1.5f));
        g.drawOval(cx - 92, cy - 92, 184, 184);
        for (int k = 0; k < 4; k++) {
            double a = k * Math.PI / 2;
            g.drawLine((int) (cx + 92 * Math.cos(a)), (int) (cy + 92 * Math.sin(a)), (int) (cx + 108 * Math.cos(a)), (int) (cy + 108 * Math.sin(a)));
        }
        Logo.paint(g, cx - 75, cy - 80, 150, 1f);
        g.setFont(Theme.headingFont(20f));
        g.setColor(Color.WHITE);
        FontMetrics name = g.getFontMetrics();
        g.drawString("DRAUPNE", cx - name.stringWidth("DRAUPNE") / 2, 230);
        g.setColor(red);
        g.fillRect(cx - 40, 237, 80, 2);
        g.setFont(Theme.headingFont(13f));
        String[][] rows = {{"MODE", "ANGLE"}, {"SATS", "14"}, {"LQ", "100%"}, {"ALT", "120 M"}, {"DIST", "2.4 KM"}};
        for (int i = 0; i < rows.length; i++) {
            g.setColor(new Color(255, 120, 120));
            g.drawString(rows[i][0], 300, 64 + i * 26);
            g.setColor(Color.WHITE);
            g.drawString(rows[i][1], 370, 64 + i * 26);
        }
        g.setColor(red);
        g.fillRect(300, 196, 150, 30);
        g.setColor(Color.BLACK);
        g.setFont(Theme.headingFont(18f));
        g.drawString("ARMED", 345, 218);
    }

    /** A 2 x 2 twill carbon weave, 0..1: bright where a tow faces the light along its run. */
    private static double weave(double u, double v) {
        int iu = (int) Math.floor(u), iv = (int) Math.floor(v);
        double fu = u - iu, fv = v - iv;
        boolean along = Math.floorMod(iu + iv, 4) < 2; // the twill: tows go over two, under two, a step on each row
        double across = along ? fv : fu;
        return 0.35 + 0.65 * Math.pow(Math.sin(Math.PI * across), 2) * (along ? 1 : 0.55);
    }

    // ---- the motion

    private static double ease(double p) {
        p = Math.max(0, Math.min(1, p));
        return p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
    }

    /** Out to the target, held, back: 0..1..0 over a 2.8 s loop. */
    private static double pushAndHold(double t) {
        double c = t % 2.8;
        if (c < 0.7) return ease(c / 0.7);
        if (c < 2.1) return 1;
        return 1 - ease((c - 2.1) / 0.7);
    }

    private void animate(double t) {
        double tlx = 0, tly = 0, trx = 0, try_ = 0; // at rest every stick centred, throttle too (the pilot)
        double[] targetLever = new double[SWITCHES];
        for (int i = 0; i < SWITCHES; i++) targetLever[i] = offSide(i);
        switch (show) {
            case SWEEP -> { // circles pushed into the corners
                double a = t * 2 * Math.PI / 2.2;
                tlx = clamp((float) (1.35 * Math.cos(a)));
                tly = clamp((float) (1.35 * Math.sin(a)));
                trx = tlx;
                try_ = tly;
            }
            case REST -> {
                // hands off: all four centred
            }
            case THROTTLE_UP -> tly = pushAndHold(t);
            case YAW_RIGHT -> tlx = pushAndHold(t);
            case PITCH_UP -> try_ = pushAndHold(t);
            case ROLL_RIGHT -> trx = pushAndHold(t);
            case SWITCH -> { // ON for 1.2 s, OFF for 1.2 s, each change a snap; SH (momentary) held a moment and let go
                double off = offSide(switchSlot);
                double period = switchSlot == SH ? 2.0 : 2.4, onFor = switchSlot == SH ? 0.8 : 1.2, c = t % period;
                targetLever[switchSlot] = c < onFor ? off - 2 * off * snap(c) : -off + 2 * off * snap(c - onFor);
            }
            case LIVE -> {
                tlx = liveLx;
                tly = liveLy;
                trx = liveRx;
                try_ = liveRy;
                // 1000 the OFF end, 2000 the other, 1500 the middle of a 3-position one
                for (int i = 0; i < SWITCHES; i++) if (!Double.isNaN(liveSwitch[i])) targetLever[i] = -offSide(i) * liveSwitch[i];
            }
        }
        // the sticks follow their targets quickly (a step change glides instead of jumping)
        double k = fixedNow >= 0 ? 1 : show == Show.LIVE ? 0.55 : show == Show.SWEEP ? 1 : 0.35;
        lx += (tlx - lx) * k;
        ly += (tly - ly) * k;
        rx += (trx - rx) * k;
        ry += (try_ - ry) * k;
        if (show != Show.LIVE || fixedNow >= 0) { // a picture: the levers where they are asked to be
            System.arraycopy(targetLever, 0, lever, 0, SWITCHES); // the snap is in the target already
            return;
        }
        // the final check: a switch the pilot flips snaps over to its new place (it jumped there before, the pilot)
        long nowMs = now();
        for (int i = 0; i < SWITCHES; i++) {
            if (targetLever[i] != liveTo[i]) {
                liveFrom[i] = lever[i];
                liveTo[i] = targetLever[i];
                liveStart[i] = nowMs;
            }
            lever[i] = liveFrom[i] + (liveTo[i] - liveFrom[i]) * snap((nowMs - liveStart[i]) / 1000.0);
        }
    }

    // ---- the view

    @Override
    protected void paintComponent(Graphics graphics) {
        int w = getWidth(), h = getHeight();
        if (w < 20 || h < 20) return;
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        try {
            double t = (now() - shownAt) / 1000.0;
            animate(t);
            setUpView(w, h);
            // a soft shadow under the radio, so it sits on something
            Point2D shadow = project(new double[]{0, -11.0, -1.5}, BODY);
            float sr = (float) (h * 0.62);
            g.setPaint(new RadialGradientPaint(shadow, sr, new float[]{0f, 1f}, new Color[]{new Color(0, 0, 0, 170), new Color(0, 0, 0, 0)}));
            g.fill(new java.awt.geom.Ellipse2D.Double(shadow.getX() - sr, shadow.getY() - sr * 0.2, 2 * sr, sr * 0.4));
            g.drawImage(render(w, h, t), 0, 0, null);
            paintHints(g, t);
        } finally {
            g.dispose();
        }
    }

    /** The camera: a little above the radio, swaying slowly left and right; the radio fills the view. */
    /** Pictures only: a camera of its own (tilt, yaw, zoom, the point looked at x, y, z); null: the step's. */
    private double[] cameraOverride;

    private void setUpView(int w, int h) {
        if (cameraOverride != null) {
            camTilt = cameraOverride[0];
            camYaw = cameraOverride[1];
            camZoom = cameraOverride[2];
            camFx = cameraOverride[3];
            camFy = cameraOverride[4];
            camFz = cameraOverride[5];
            buildView(w, h);
            return;
        }
        boolean sw = show == Show.SWITCH; // close in on the switch, from its side
        double[] sp = m.switchPivot[switchSlot];
        // a front toggle (SA SB SC SD) swings up and down out of the face: seen from above it swung towards the eye and
        // hardly showed (the pilot: they "do not hinge correctly"), so from the side, low and close, its hinge in the middle
        boolean front = Math.abs(Math.sin(m.switchAxis[switchSlot])) > Math.abs(Math.cos(m.switchAxis[switchSlot]));
        double tiltTo = sw ? (front ? FRONT_SWITCH_TILT_VIEW : SWITCH_TILT_VIEW) : show == Show.SWEEP ? SWEEP_TILT : STICK_TILT;
        double side = sw ? (front ? FRONT_SWITCH_YAW : SWITCH_YAW) : 0;
        double yawTo = sw ? (sp[0] < 0 ? side : -side) : 0;
        double fxTo = sw ? (front ? sp[0] : sp[0] * 0.45) : 0, fyTo = sw ? (front ? sp[1] + 0.3 : sp[1] - 3.0) : 0.3;
        double fzTo = sw && front ? sp[2] + 1.2 : 0;
        double k = fixedNow >= 0 ? 1 : 0.12;
        camTilt += (tiltTo - camTilt) * k;
        camYaw += (yawTo - camYaw) * k;
        camZoom += ((sw ? (front ? FRONT_SWITCH_ZOOM : SWITCH_ZOOM) : 1) - camZoom) * k;
        camFx += (fxTo - camFx) * k;
        camFy += (fyTo - camFy) * k;
        camFz += (fzTo - camFz) * k;
        buildView(w, h);
    }

    private void buildView(int w, int h) {
        // no sway: a radio that stands still is drawn once (the pilot: the animation lagged)
        double[] view = Mat.translate(0, 0, -DIST).mul(Mat.rotX(camTilt)).mul(Mat.rotY(camYaw)).mul(Mat.translate(-camFx, -camFy, -camFz)).m;
        focal = Math.min(w / (2 * 12.0), h / (2 * 11.6)) * DIST * camZoom;
        centreX = w / 2.0;
        centreY = h / 2.0;
        group[BODY] = view;
        group[LEFT_STICK] = Mat.of(view).mul(stick(m.leftPivot, lx, ly)).m;
        group[RIGHT_STICK] = Mat.of(view).mul(stick(m.rightPivot, rx, ry)).m;
        for (int i = 0; i < SWITCHES; i++) {
            double[] p = m.switchPivot[i];
            // from the CAD's pose to the angle now, about the point where the lever leaves its bushing (rotX: +y towards +z)
            group[SWITCH0 + i] = Mat.of(view).mul(Mat.translate(p[0], p[1], p[2])).mul(Mat.rotX(lever[i] - m.leverRest[i]))
                    .mul(Mat.translate(-p[0], -p[1], -p[2])).m;
        }
    }

    /** A stick at (x, y): tipped about its pivot, right = +x, up (forward) = +y. */
    private static Mat stick(double[] pivot, double x, double y) {
        return Mat.translate(pivot[0], pivot[1], pivot[2]).mul(Mat.rotY(x * MAX_TILT)).mul(Mat.rotX(-y * MAX_TILT))
                .mul(Mat.translate(-pivot[0], -pivot[1], -pivot[2]));
    }

    private Point2D project(double[] p, int g) {
        double[] mm = group[g];
        double x = mm[0] * p[0] + mm[1] * p[1] + mm[2] * p[2] + mm[3];
        double y = mm[4] * p[0] + mm[5] * p[1] + mm[6] * p[2] + mm[7];
        double z = mm[8] * p[0] + mm[9] * p[1] + mm[10] * p[2] + mm[11];
        return new Point2D.Double(centreX + focal * x / -z, centreY - focal * y / -z);
    }

    // ---- the renderer

    // lights in view space (towards the light), linear colours: a warm key from the upper left, a cool fill, a rim from behind
    private static final double[] KEY = norm(-0.38, 0.55, 0.75), FILL = norm(0.75, 0.1, 0.55), RIM = norm(0.35, 0.55, -0.75);
    private static final double[] KEY_C = {2.7, 2.6, 2.45}, FILL_C = {0.42, 0.46, 0.55}, RIM_C = {0.9, 0.95, 1.05};
    private static final double[] SKY = {0.20, 0.22, 0.26}, FLOOR = {0.035, 0.035, 0.04};
    /** The studio's softbox, seen in the reflections. */
    private static final double[] BOX = norm(-0.35, 0.85, 0.45);

    private static double[] norm(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    private int activeRole() {
        return switch (show) {
            case THROTTLE_UP, YAW_RIGHT -> CAP_LEFT;
            case PITCH_UP, ROLL_RIGHT -> CAP_RIGHT;
            case SWITCH -> LEVER0 + switchSlot;
            default -> -1;
        };
    }

    /** What the last frame was drawn from: the same again and the picture is reused. */
    private double[] lastPose;

    private double[] pose(int w, int h) {
        double[] k = new double[14 + SWITCHES];
        k[0] = w;
        k[1] = h;
        k[2] = camTilt;
        k[3] = camYaw;
        k[4] = camZoom;
        k[5] = camFx;
        k[6] = camFy;
        k[7] = camFz;
        k[8] = lx;
        k[9] = ly;
        k[10] = rx;
        k[11] = ry;
        k[12] = activeRole();
        k[13] = show.ordinal();
        System.arraycopy(lever, 0, k, 14, SWITCHES);
        return k;
    }

    private BufferedImage render(int w, int h, double t) {
        double[] pose = pose(w, h);
        if (frameImage != null && java.util.Arrays.equals(pose, lastPose)) return frameImage;
        lastPose = pose;
        int bw = w * SS, bh = h * SS;
        if (bw != bufW || bh != bufH) {
            bufW = bw;
            bufH = bh;
            zbuf = new float[bw * bh];
            gb0 = new float[bw * bh];
            gb1 = new float[bw * bh];
            gtri = new int[bw * bh];
            frameImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        }
        transform();
        shadowPass();
        geometryPass();
        return shadePass(w, h, t);
    }

    /** Every vertex into view space, onto the screen and into the light's view. */
    private void transform() {
        double f = focal * SS, ox = centreX * SS, oy = centreY * SS;
        lightU = norm(KEY[2], 0, -KEY[0]); // across the key light (up x it), then up it
        lightV = new double[]{KEY[1] * lightU[2] - KEY[2] * lightU[1], KEY[2] * lightU[0] - KEY[0] * lightU[2], KEY[0] * lightU[1] - KEY[1] * lightU[0]};
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (int pi = 0; pi < m.parts.size(); pi++) {
            Part part = m.parts.get(pi);
            double[] mm = group[part.group()];
            float[] p = part.p(), n = part.n();
            int count = p.length / 3, base = m.base[pi];
            for (int i = 0, k = 0; i < count; i++, k += 3) {
                int v = base + i;
                double x = mm[0] * p[k] + mm[1] * p[k + 1] + mm[2] * p[k + 2] + mm[3];
                double y = mm[4] * p[k] + mm[5] * p[k + 1] + mm[6] * p[k + 2] + mm[7];
                double z = mm[8] * p[k] + mm[9] * p[k + 1] + mm[10] * p[k + 2] + mm[11];
                vx[v] = (float) x;
                vy[v] = (float) y;
                vz[v] = (float) z;
                nx[v] = (float) (mm[0] * n[k] + mm[1] * n[k + 1] + mm[2] * n[k + 2]);
                ny[v] = (float) (mm[4] * n[k] + mm[5] * n[k + 1] + mm[6] * n[k + 2]);
                nz[v] = (float) (mm[8] * n[k] + mm[9] * n[k + 1] + mm[10] * n[k + 2]);
                sx[v] = (float) (ox + f * x / -z);
                sy[v] = (float) (oy - f * y / -z);
                iz[v] = (float) (1 / -z);
                double u = x * lightU[0] + y * lightU[1] + z * lightU[2], vv = x * lightV[0] + y * lightV[1] + z * lightV[2];
                lu[v] = (float) u;
                lv[v] = (float) vv;
                ld[v] = (float) (x * KEY[0] + y * KEY[1] + z * KEY[2]);
                minU = Math.min(minU, u);
                maxU = Math.max(maxU, u);
                minV = Math.min(minV, vv);
                maxV = Math.max(maxV, vv);
            }
        }
        smMinU = minU;
        smMinV = minV;
        smScale = (SM - 4) / Math.max(maxU - minU, maxV - minV);
    }

    /** Rows split into bands, one task each: every pass runs on all cores, each band writing only its own rows. */
    private static final int BANDS = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);

    private static void inBands(int rows, java.util.function.BiConsumer<Integer, Integer> band) {
        int n = Math.min(BANDS, Math.max(1, rows));
        java.util.stream.IntStream.range(0, n).parallel().forEach(i -> band.accept(rows * i / n, rows * (i + 1) / n));
    }

    /** The depth seen from the key light (the nearest to it kept), for the shadows. */
    private void shadowPass() {
        java.util.Arrays.fill(smap, -Float.MAX_VALUE);
        inBands(SM, this::shadowBand);
    }

    private void shadowBand(int rowFrom, int rowTo) {
        float s = (float) smScale, mu = (float) smMinU, mv = (float) smMinV;
        for (int k = 0; k < m.triangles; k++) {
            int a = m.triA[k], b = m.triB[k], c = m.triC[k];
            float x0 = (lu[a] - mu) * s + 2, y0 = (lv[a] - mv) * s + 2, x1 = (lu[b] - mu) * s + 2, y1 = (lv[b] - mv) * s + 2;
            float x2 = (lu[c] - mu) * s + 2, y2 = (lv[c] - mv) * s + 2;
            float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
            if (Math.abs(area) < 1e-6f) continue;
            int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
            int maxX = Math.min(SM - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
            int minY = Math.max(rowFrom, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
            int maxY = Math.min(rowTo - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
            if (minY > maxY) continue;
            float inv = 1 / area, d0 = ld[a], d1 = ld[b], d2 = ld[c];
            for (int y = minY; y <= maxY; y++) {
                float py = y + 0.5f;
                for (int x = minX; x <= maxX; x++) {
                    float px = x + 0.5f;
                    float w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) * inv;
                    float w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) * inv;
                    float w2 = 1 - w0 - w1;
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                    float d = w0 * d0 + w1 * d1 + w2 * d2;
                    int i = y * SM + x;
                    if (d > smap[i]) smap[i] = d;
                }
            }
        }
    }

    /** What is in front at every sub-pixel: its triangle and where on it (perspective-correct). */
    private void geometryPass() {
        java.util.Arrays.fill(zbuf, 0f);
        inBands(bufH, this::geometryBand);
    }

    private void geometryBand(int rowFrom, int rowTo) {
        for (int k = 0; k < m.triangles; k++) {
            int a = m.triA[k], b = m.triB[k], c = m.triC[k];
            float x0 = sx[a], y0 = sy[a], x1 = sx[b], y1 = sy[b], x2 = sx[c], y2 = sy[c];
            float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
            if (Math.abs(area) < 1e-5f) continue;
            int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
            int maxX = Math.min(bufW - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
            int minY = Math.max(rowFrom, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
            int maxY = Math.min(rowTo - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
            if (minX > maxX || minY > maxY) continue;
            float inv = 1 / area, z0 = iz[a], z1 = iz[b], z2 = iz[c];
            for (int y = minY; y <= maxY; y++) {
                float py = y + 0.5f;
                int row = y * bufW;
                for (int x = minX; x <= maxX; x++) {
                    float px = x + 0.5f;
                    float w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) * inv;
                    float w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) * inv;
                    float w2 = 1 - w0 - w1;
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                    float depth = w0 * z0 + w1 * z1 + w2 * z2;
                    int idx = row + x;
                    if (depth <= zbuf[idx]) continue;
                    zbuf[idx] = depth;
                    gtri[idx] = k;
                    gb0[idx] = w0 * z0 / depth;
                    gb1[idx] = w1 * z1 / depth;
                }
            }
        }
    }

    /**
     * The light, then 2 x 2 down to the view; coverage becomes the edge's alpha. Where the four sub-pixels show the
     * same triangle, it is lit once (at their middle); only edges are lit four times - most of the radio is inside.
     */
    private BufferedImage shadePass(int w, int h, double time) {
        int[] out = ((DataBufferInt) frameImage.getRaster().getDataBuffer()).getData();
        int active = activeRole();
        inBands(h, (from, to) -> {
            double[] rgb = new double[3];
            for (int y = from; y < to; y++) {
                for (int x = 0; x < w; x++) {
                    int i0 = (y * SS) * bufW + x * SS, i1 = i0 + 1, i2 = i0 + bufW, i3 = i2 + 1;
                    boolean c0 = zbuf[i0] != 0f, c1 = zbuf[i1] != 0f, c2 = zbuf[i2] != 0f, c3 = zbuf[i3] != 0f;
                    int covered = (c0 ? 1 : 0) + (c1 ? 1 : 0) + (c2 ? 1 : 0) + (c3 ? 1 : 0);
                    if (covered == 0) {
                        out[y * w + x] = 0;
                        continue;
                    }
                    double r = 0, g = 0, b = 0;
                    if (covered == 4 && gtri[i0] == gtri[i1] && gtri[i0] == gtri[i2] && gtri[i0] == gtri[i3]) {
                        shade(gtri[i0], (gb0[i0] + gb0[i3]) * 0.5f, (gb1[i0] + gb1[i3]) * 0.5f, active, 0.7, x * SS, y * SS, rgb);
                        r = rgb[0] * 4;
                        g = rgb[1] * 4;
                        b = rgb[2] * 4;
                    } else {
                        int[] idx = {i0, i1, i2, i3};
                        for (int q = 0; q < 4; q++) {
                            int i = idx[q];
                            if (zbuf[i] == 0f) continue;
                            shade(gtri[i], gb0[i], gb1[i], active, 0.7, x * SS + (q & 1), y * SS + (q >> 1), rgb);
                            r += rgb[0];
                            g += rgb[1];
                            b += rgb[2];
                        }
                    }
                    out[y * w + x] = ((covered * 255 / 4) << 24) | (tone(r / covered) << 16) | (tone(g / covered) << 8) | tone(b / covered);
                }
            }
        });
        return frameImage;
    }

    /** Filmic (ACES) tone mapping and gamma, as a table: linear light (0..TONE_MAX) to an sRGB byte. */
    private static final int TONE_STEPS = 8192;
    private static final double TONE_MAX = 6;
    private static final int[] TONE = new int[TONE_STEPS + 1];

    static {
        for (int i = 0; i <= TONE_STEPS; i++) {
            double x = i * TONE_MAX / TONE_STEPS * 1.45; // the exposure
            double v = x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14);
            TONE[i] = (int) Math.round(255 * Math.pow(Math.max(0, Math.min(1, v)), 1 / 2.2));
        }
    }

    private static int tone(double x) {
        if (!(x > 0)) return 0;
        return x >= TONE_MAX ? TONE[TONE_STEPS] : TONE[(int) (x * (TONE_STEPS / TONE_MAX))];
    }

    private void shade(int tri, float b0, float b1, int active, double glow, int px, int py, double[] out) { // glow: the asked-for part's
        int a = m.triA[tri], b = m.triB[tri], c = m.triC[tri];
        int pi = m.triPart[tri];
        Part part = m.parts.get(pi);
        float b2 = 1 - b0 - b1;
        double x = b0 * vx[a] + b1 * vx[b] + b2 * vx[c], y = b0 * vy[a] + b1 * vy[b] + b2 * vy[c], z = b0 * vz[a] + b1 * vz[b] + b2 * vz[c];
        double nxx = b0 * nx[a] + b1 * nx[b] + b2 * nx[c], nyy = b0 * ny[a] + b1 * ny[b] + b2 * ny[c], nzz = b0 * nz[a] + b1 * nz[b] + b2 * nz[c];
        double nl = Math.sqrt(nxx * nxx + nyy * nyy + nzz * nzz);
        if (nl < 1e-9) {
            nzz = 1;
            nl = 1;
        }
        nxx /= nl;
        nyy /= nl;
        nzz /= nl;
        double vl = Math.sqrt(x * x + y * y + z * z), ex = -x / vl, ey = -y / vl, ez = -z / vl; // to the eye
        if (nxx * ex + nyy * ey + nzz * ez < 0) { // the far side of a face seen from behind
            nxx = -nxx;
            nyy = -nyy;
            nzz = -nzz;
        }
        int kind = part.kind();
        float[] uv = part.uv();
        int base = m.base[pi];
        int ia = (a - base) * 2, ib = (b - base) * 2, ic = (c - base) * 2;
        double u = b0 * uv[ia] + b1 * uv[ib] + b2 * uv[ic], v = b0 * uv[ia + 1] + b1 * uv[ib + 1] + b2 * uv[ic + 1];
        double ar = part.r(), ag = part.g(), ab = part.b();
        double rough, metal, er = 0, eg = 0, eb = 0;
        switch (kind) {
            case CHROME -> {
                rough = 0.1;
                metal = 1;
            }
            case ANODISED -> {
                rough = 0.38;
                metal = 0.85;
            }
            case RUBBER -> {
                rough = 0.85;
                metal = 0;
            }
            case GLOSS -> {
                rough = 0.25;
                metal = 0;
            }
            case CARBON -> { // the weave under a clear coat
                double wv = weave(u, v);
                ar *= 0.6 + 1.1 * wv;
                ag *= 0.6 + 1.1 * wv;
                ab *= 0.6 + 1.15 * wv;
                rough = 0.18;
                metal = 0;
            }
            case SCREEN -> {
                rough = 0.04;
                metal = 0;
                int tx = Math.min(SCREEN_W - 1, Math.max(0, (int) (u * SCREEN_W))), ty = Math.min(SCREEN_H - 1, Math.max(0, (int) (v * SCREEN_H)));
                int c0 = screenTex[ty * SCREEN_W + tx];
                double lit = part.n().length > 0 && Math.abs(part.n()[(a - base) * 3 + 2]) > 0.5 ? 1.5 : 0; // only its face glows
                er = lin((c0 >> 16) & 255) * lit;
                eg = lin((c0 >> 8) & 255) * lit;
                eb = lin(c0 & 255) * lit;
                ar = ag = ab = 0.01;
            }
            case BADGE -> {
                int tx = Math.min(BADGE_W - 1, Math.max(0, (int) (u * BADGE_W))), ty = Math.min(BADGE_H - 1, Math.max(0, (int) (v * BADGE_H)));
                int c0 = badgeTex[ty * BADGE_W + tx];
                ar = lin((c0 >> 16) & 255);
                ag = lin((c0 >> 8) & 255);
                ab = lin(c0 & 255);
                rough = 0.2;
                metal = 0;
            }
            case DARK -> {
                rough = 0.7;
                metal = 0;
            }
            default -> { // the shell's plastic: a fine matte texture
                rough = 0.6;
                metal = 0;
                double grain = 1 + 0.07 * (hash(px * 0.5, py * 0.5) - 0.5);
                ar *= grain;
                ag *= grain;
                ab *= grain;
            }
        }
        if (part.role() != PLAIN && part.role() == active) { // the stick / lever asked for: red, glowing
            ar = 0.75;
            ag = 0.02;
            ab = 0.025;
            metal = 0.3;
            rough = 0.3;
            er = 0.45 * glow;
            eg = 0.01 * glow;
            eb = 0.012 * glow;
        }
        double shadow = keyShadow(x, y, z, nxx, nyy, nzz);
        double nk = Math.max(0, nxx * KEY[0] + nyy * KEY[1] + nzz * KEY[2]);
        double nf = Math.max(0, nxx * FILL[0] + nyy * FILL[1] + nzz * FILL[2]);
        double nr = Math.max(0, nxx * RIM[0] + nyy * RIM[1] + nzz * RIM[2]);
        double hemi = 0.5 + 0.5 * nyy; // the sky above, the floor below
        double ambR = FLOOR[0] + (SKY[0] - FLOOR[0]) * hemi, ambG = FLOOR[1] + (SKY[1] - FLOOR[1]) * hemi, ambB = FLOOR[2] + (SKY[2] - FLOOR[2]) * hemi;
        double ne = Math.max(0, nxx * ex + nyy * ey + nzz * ez);
        double diffR = KEY_C[0] * nk * shadow + FILL_C[0] * nf + RIM_C[0] * nr * 0.4 + ambR;
        double diffG = KEY_C[1] * nk * shadow + FILL_C[1] * nf + RIM_C[1] * nr * 0.4 + ambG;
        double diffB = KEY_C[2] * nk * shadow + FILL_C[2] * nf + RIM_C[2] * nr * 0.4 + ambB;
        // highlights: Blinn-Phong for the lights, the studio (sky, floor, softbox) in the reflection
        double shine = 2 / Math.max(0.002, rough * rough * rough * rough) - 2;
        double specK = blinn(nxx, nyy, nzz, ex, ey, ez, KEY, shine) * shadow, specF = blinn(nxx, nyy, nzz, ex, ey, ez, FILL, shine);
        double norm = (shine + 8) / (8 * Math.PI) * 0.35;
        double rdot = 2 * (nxx * ex + nyy * ey + nzz * ez);
        double rx_ = rdot * nxx - ex, ry_ = rdot * nyy - ey, rz_ = rdot * nzz - ez;
        double sky = Math.max(0, Math.min(1, (ry_ + 0.15) / 0.8));
        double box = Math.pow(Math.max(0, rx_ * BOX[0] + ry_ * BOX[1] + rz_ * BOX[2]), 4 + 120 * (1 - rough) * (1 - rough)) * 3.2 * (1 - rough * 0.8);
        double envR = FLOOR[0] + (SKY[0] * 3.2 - FLOOR[0]) * sky + box, envG = FLOOR[1] + (SKY[1] * 3.2 - FLOOR[1]) * sky + box,
                envB = FLOOR[2] + (SKY[2] * 3.2 - FLOOR[2]) * sky + box * 1.05;
        double om = 1 - ne, om2 = om * om, fres = om2 * om2 * om;
        double f0r = metal * ar + (1 - metal) * 0.04, f0g = metal * ag + (1 - metal) * 0.04, f0b = metal * ab + (1 - metal) * 0.04;
        double fr = f0r + (1 - f0r) * fres * (1 - rough), fg = f0g + (1 - f0g) * fres * (1 - rough), fb = f0b + (1 - f0b) * fres * (1 - rough);
        double spec = (specK * KEY_C[0] + specF * FILL_C[0]) * norm;
        double envScale = 1 - rough * 0.75;
        out[0] = ar * (1 - metal) * diffR + fr * (spec + envR * envScale) + er;
        out[1] = ag * (1 - metal) * diffG + fg * (spec * KEY_C[1] / KEY_C[0] + envG * envScale) + eg;
        out[2] = ab * (1 - metal) * diffB + fb * (spec * KEY_C[2] / KEY_C[0] + envB * envScale) + eb;
    }

    private static double blinn(double nx, double ny, double nz, double ex, double ey, double ez, double[] l, double shine) {
        double hx = l[0] + ex, hy = l[1] + ey, hz = l[2] + ez, hl = Math.sqrt(hx * hx + hy * hy + hz * hz);
        double d = Math.max(0, (nx * hx + ny * hy + nz * hz) / hl);
        return Math.pow(d, shine) * Math.max(0, nx * l[0] + ny * l[1] + nz * l[2]);
    }

    /** 1 lit, 0 in the key light's shadow, between at the edge (3 x 3 of the map). */
    private double keyShadow(double x, double y, double z, double nxx, double nyy, double nzz) {
        double u = (x * lightU[0] + y * lightU[1] + z * lightU[2] - smMinU) * smScale + 2;
        double v = (x * lightV[0] + y * lightV[1] + z * lightV[2] - smMinV) * smScale + 2;
        double d = x * KEY[0] + y * KEY[1] + z * KEY[2];
        double slope = 1 - Math.max(0, nxx * KEY[0] + nyy * KEY[1] + nzz * KEY[2]);
        double bias = 0.05 + 0.1 * slope;
        int cu = (int) Math.floor(u), cv = (int) Math.floor(v), lit = 0, n = 0;
        for (int dv = -1; dv <= 1; dv++) {
            for (int du = -1; du <= 1; du++) {
                int su = cu + du * 2, sv = cv + dv * 2;
                if (su < 0 || sv < 0 || su >= SM || sv >= SM || smap[sv * SM + su] <= d + bias) lit++;
                n++;
            }
        }
        return lit / (double) n;
    }

    private static double hash(double x, double y) {
        double s = Math.sin(Math.floor(x) * 127.1 + Math.floor(y) * 311.7) * 43758.5453;
        return s - Math.floor(s);
    }

    // ---- the arrows, on the radio's face

    private void paintHints(Graphics2D g, double t) {
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Theme.ACCENT);
        double[] lp = m.leftPivot, rp = m.rightPivot;
        double z = m.faceZ + 0.2, off = 2.7;
        switch (show) {
            case SWEEP -> {
                for (double[] pv : new double[][]{lp, rp}) arc(g, pv[0], pv[1], z, off, t);
                label(g, "FULL CIRCLES, INTO EVERY CORNER");
            }
            case REST -> {
                for (double[] pv : new double[][]{lp, rp}) centreMark(g, pv[0], pv[1], z);
                label(g, "ALL STICKS CENTRED - HANDS OFF");
            }
            case THROTTLE_UP -> {
                arrow(g, lp[0] - off, lp[1] - 1.5, lp[0] - off, lp[1] + 1.5, z);
                label(g, "THROTTLE  UP");
            }
            case YAW_RIGHT -> {
                arrow(g, lp[0] - 1.5, lp[1] - off, lp[0] + 1.5, lp[1] - off, z);
                label(g, "YAW  RIGHT");
            }
            case PITCH_UP -> {
                arrow(g, rp[0] + off, rp[1] - 1.5, rp[0] + off, rp[1] + 1.5, z);
                label(g, "PITCH  UP (FORWARD)");
            }
            case ROLL_RIGHT -> {
                arrow(g, rp[0] - 1.5, rp[1] - off, rp[0] + 1.5, rp[1] - off, z);
                label(g, "ROLL  RIGHT");
            }
            case SWITCH -> {
                double[] sp = m.switchPivot[switchSlot];
                Path2D p = new Path2D.Double();
                double axis = m.switchAxis[switchSlot];
                for (int k = 0; k <= 16; k++) { // an arc past the lever's tip, round its bushing's axis, the way it throws
                    double a = axis + Math.toRadians(-34 + 68 * k / 16.0);
                    Point2D s = project(new double[]{sp[0], sp[1] + 3.0 * Math.cos(a), sp[2] + 3.0 * Math.sin(a)}, BODY);
                    if (k == 0) p.moveTo(s.getX(), s.getY()); else p.lineTo(s.getX(), s.getY());
                }
                g.draw(p);
                label(g, (switchSlot == SH ? "PRESS " : "FLIP ") + switchName(switchSlot) + (switchSlot == SH ? " AND HOLD" : " TO ON")
                        + (switchLabel.isEmpty() ? "" : "  (" + switchLabel + ")"));
            }
            case LIVE -> label(g, "YOUR STICKS");
        }
    }

    /** Four short ticks pointing in at the gimbal's middle: centre here. */
    private void centreMark(Graphics2D g, double cx, double cy, double z) {
        for (int k = 0; k < 4; k++) {
            double a = k * Math.PI / 2;
            arrow(g, cx + 3.0 * Math.cos(a), cy + 3.0 * Math.sin(a), cx + 2.4 * Math.cos(a), cy + 2.4 * Math.sin(a), z);
        }
    }

    private void arrow(Graphics2D g, double x1, double y1, double x2, double y2, double z) {
        Point2D a = project(new double[]{x1, y1, z}, BODY), b = project(new double[]{x2, y2, z}, BODY);
        g.draw(new java.awt.geom.Line2D.Double(a, b));
        head(g, a, b);
    }

    private static void head(Graphics2D g, Point2D from, Point2D to) {
        double ang = Math.atan2(to.getY() - from.getY(), to.getX() - from.getX()), len = 11;
        Path2D h = new Path2D.Double();
        h.moveTo(to.getX() + Math.cos(ang) * 3, to.getY() + Math.sin(ang) * 3);
        h.lineTo(to.getX() - Math.cos(ang - 0.5) * len, to.getY() - Math.sin(ang - 0.5) * len);
        h.lineTo(to.getX() - Math.cos(ang + 0.5) * len, to.getY() - Math.sin(ang + 0.5) * len);
        h.closePath();
        g.fill(h);
    }

    /** A circular arrow round a gimbal, turning with the sticks. */
    private void arc(Graphics2D g, double cx, double cy, double z, double r, double t) {
        double start = t * 2 * Math.PI / 2.2 - Math.toRadians(250);
        Path2D p = new Path2D.Double();
        Point2D last = null, before = null;
        for (int k = 0; k <= 30; k++) {
            double a = start + Math.toRadians(220) * k / 30.0;
            Point2D s = project(new double[]{cx + r * Math.cos(a), cy + r * Math.sin(a), z}, BODY);
            if (k == 0) p.moveTo(s.getX(), s.getY()); else p.lineTo(s.getX(), s.getY());
            before = last;
            last = s;
        }
        g.draw(p);
        head(g, before, last);
    }

    private void label(Graphics2D g, String text) {
        g.setFont(Theme.headingFont(12f));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text), x = (getWidth() - tw) / 2, y = getHeight() - 6;
        g.setColor(new Color(0, 0, 0, 190)); // readable over the radio too
        g.fillRoundRect(x - 8, y - fm.getAscent() - 3, tw + 16, fm.getAscent() + 7, 8, 8);
        g.setColor(Theme.ACCENT);
        g.drawString(text, x, y);
    }

    /**
     * For pictures: the sticks at (lx, ly) / (rx, ry) and the toggles (-1..1 each, NaN: as they rest) seen from a camera
     * of its own: tilt and yaw in degrees, a zoom, the point looked at (cm).
     */
    static BufferedImage closeUp(double lx, double ly, double rx, double ry, double[] switches, double tiltDeg, double yawDeg,
                                 double zoom, double fx, double fy, double fz, int w, int h) {
        ControllerRender r = new ControllerRender();
        r.setSize(w, h);
        r.fixedNow = 1_000_000;
        r.shownAt = 1_000_000;
        r.show = Show.LIVE;
        r.setLive((float) lx, (float) ly, (float) rx, (float) ry);
        if (switches != null) for (int i = 0; i < Math.min(SWITCHES, switches.length); i++) r.setLiveSwitch(i, switches[i]);
        r.cameraOverride = new double[]{Math.toRadians(tiltDeg), Math.toRadians(yawDeg), zoom, fx, fy, fz};
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        r.paintComponent(g);
        g.dispose();
        return img;
    }

    /** A toggle's hinge (cm), for pictures. */
    static double[] switchPivot(int slot) {
        return model().switchPivot[Math.floorMod(slot, SWITCHES)].clone();
    }

    /** The left and right gimbal's pivots (cm), for pictures. */
    static double[] pivots() {
        Model md = model();
        return new double[]{md.leftPivot[0], md.leftPivot[1], md.leftPivot[2], md.rightPivot[0], md.rightPivot[1], md.rightPivot[2]};
    }

    /** For tests and pictures: a frame of a step (SWITCH: the toggle in this slot), this far into it. */
    static BufferedImage frame(Show s, int switchSlot, long atMs, int w, int h) {
        ControllerRender r = new ControllerRender();
        r.setSize(w, h);
        r.fixedNow = 1_000_000 + atMs;
        r.shownAt = 1_000_000;
        r.show = s;
        r.switchSlot = Math.floorMod(switchSlot, SWITCHES);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        r.paintComponent(g);
        g.dispose();
        return img;
    }

    /** A 3 x 4 affine matrix, row major: rotation | translation. */
    private static final class Mat {
        final double[] m;

        private Mat(double[] m) {
            this.m = m;
        }

        static Mat of(double[] m) {
            return new Mat(m);
        }

        static Mat translate(double x, double y, double z) {
            return new Mat(new double[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z});
        }

        static Mat rotX(double a) {
            double c = Math.cos(a), s = Math.sin(a);
            return new Mat(new double[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0});
        }

        static Mat rotY(double a) {
            double c = Math.cos(a), s = Math.sin(a);
            return new Mat(new double[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0});
        }

        Mat mul(Mat o) {
            double[] a = m, b = o.m, r = new double[12];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 4; j++) {
                    r[i * 4 + j] = a[i * 4] * b[j] + a[i * 4 + 1] * b[4 + j] + a[i * 4 + 2] * b[8 + j] + (j == 3 ? a[i * 4 + 3] : 0);
                }
            }
            return new Mat(r);
        }

        double[] apply(double x, double y, double z) {
            return new double[]{m[0] * x + m[1] * y + m[2] * z + m[3], m[4] * x + m[5] * y + m[6] * z + m[7], m[8] * x + m[9] * y + m[10] * z + m[11]};
        }

        double[] rotate(double x, double y, double z) {
            return new double[]{m[0] * x + m[1] * y + m[2] * z, m[4] * x + m[5] * y + m[6] * z, m[8] * x + m[9] * y + m[10] * z};
        }
    }
}
