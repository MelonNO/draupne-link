package de.droiddrone.pc.fleet;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The tour's six drones' pictures (2026-11-09; 2026-11-10, the pilot: "Make the activities more serious. Make them
 * tactical army related. Make everything in a realistic style"): what each one's camera sees, in front of where it
 * stands - a field, tracks, treelines and a far forest, textured models lit by a low sun, their shadows - in a
 * simulated memory ({@link SimVoid}, 2026-11-10, the pilot: "push it much further"; "less grass", then "reintroduce
 * the grass"): the real land an island round the action and back to the drone, breaking into tiles at its edge, on
 * the BTR finale's floor - grey-blue, its grid every 10 m, a pale sky, far structures with glowing edges
 * ({@link SimWorld}; 2026-11-10, the pilot: the "simulation wall" "more similar to the btr simulation finale" - the
 * treelines away from the action were a wall of white ghost blocks, now they are gone); only the trees right at the
 * action real; fragments drifting up (no scan lines or glitches since 2026-11-10, the pilot), camouflage nets pegged
 * to the ground ({@link Raster3D#groundSkirts}). Seen as a drone sees
 * it (2026-11-10, the pilot: it looked like a CCTV camera, not a drone): set down on the grass BACK_M back, its lens
 * just over the ground - the near grass out of focus, blades in front of it, the wide lens bending the picture
 * ({@link DroneLens}):
 * <ul>
 * <li>{@link Kind#DRONES}: a drone team at a treeline - operators kneeling by their UAZ, crates, a radio and an antenna
 * mast - its quads (the pilot's FPV model in its own colours, and a recon quad) taking off one after another, flying a
 * reconnaissance route over the field and the trees, and landing. The drone the tour has the pilot fly.</li>
 * <li>{@link Kind#CONVOY}: a BTR-80, two Ural trucks and a UAZ on a forest road, spaced, slowing for the bend.</li>
 * <li>{@link Kind#PATROL}: a section in file along a treeline: it halts, goes to cover facing out, and moves on.</li>
 * <li>{@link Kind#CHECKPOINT}: a roadblock - concrete barriers in a chicane, sandbagged posts, hedgehogs, wire - where
 * a UAZ stops, a guard checks it, and it drives on through the chicane.</li>
 * <li>{@link Kind#MORTAR}: a mortar with its crew and ammunition under a camouflage net, firing: the round held to the
 * muzzle and dropped, the crew ducks, flash and smoke, the next round.</li>
 * <li>{@link Kind#HIDE}: a BTR-80 under a net at the treeline, its engine idling; an observation post on a rise -
 * sandbags, one prone with binoculars, one on the radio - and a sentry walking his beat across its front.</li>
 * </ul>
 * Models: /model/tactical.bin.gz and the FPV drone ({@link Raster3D}, THIRD-PARTY.md). What stands still is drawn once
 * for as long as the camera stays where it is (a drone sitting on the ground), and only what moves each picture. The
 * motion detection's picture gets it without the fragments and the darkened corners: only what
 * really travels moves in it.
 * {@link #addSound} mixes what is heard: the quads, the engines, the mortar.
 */
final class ShowScene implements Scene.World, Scene.Sounding {
    /** What a drone sees. */
    enum Kind { DRONES, CONVOY, PATROL, CHECKPOINT, MORTAR, HIDE }

    private final Kind kind;
    /** The scene's frame: BACK_M in front of where the drone stood when it was made, the way it looked. */
    private final double originN, originE, headingDeg;
    private final long epochMs;
    /** What never moves (drawn once a camera position), and the ground's tracks. */
    private final List<Put> statics = new ArrayList<>();
    private final Raster3D.GroundMap ground;
    /** The far structures round the scene, as the BTR finale's ({@link SimWorld}). */
    private final SimWorld far;
    /** The island of real land round the action on the memory's floor. */
    private final Raster3D.Memory memory;
    /** The fragments drifting up from the island's edge ({@link SimVoid#fragments}). */
    private final double[][] fragments;

    ShowScene(Kind kind, double droneN, double droneE, double headingDeg) {
        this(kind, droneN, droneE, headingDeg, System.currentTimeMillis());
    }

    /**
     * @param droneN where the drone stands (m north), as droneE (m east), headingDeg the way it looks: the scene is laid
     *               out BACK_M in front of it
     */
    ShowScene(Kind kind, double droneN, double droneE, double headingDeg, long epochMs) {
        this(kind, droneN, droneE, headingDeg, epochMs, REAL_TREES_M);
    }

    /** @param realTreesM trees and bushes this near the action stay real (REAL_TREES_M; a test asks for more) */
    ShowScene(Kind kind, double droneN, double droneE, double headingDeg, long epochMs, double realTreesM) {
        this.kind = kind;
        double hr = Math.toRadians(headingDeg);
        this.originN = droneN + BACK_M * Math.cos(hr);
        this.originE = droneE + BACK_M * Math.sin(hr);
        this.headingDeg = headingDeg;
        this.epochMs = epochMs;
        List<double[][]> tracks = new ArrayList<>();
        switch (kind) {
            case DRONES -> dronesLayout(tracks);
            case CONVOY -> convoyLayout(tracks);
            case PATROL -> patrolLayout(tracks);
            case CHECKPOINT -> checkpointLayout(tracks);
            case MORTAR -> mortarLayout(tracks);
            case HIDE -> hideLayout(tracks);
        }
        ground = groundMap(tracks);
        List<double[]> action = action();
        List<double[]> edge = new ArrayList<>(), real = new ArrayList<>();
        // only the trees and bushes right at the action stay; the treelines away from it are gone: the floor and the
        // far structures behind, as the BTR finale's (2026-11-10, the pilot: the "simulation wall" of ghost blocks
        // "more similar to the btr simulation finale")
        for (int i = statics.size() - 1; i >= 0; i--) {
            Put p = statics.get(i);
            if ((p.mesh().flags() & Raster3D.CARD) == 0) continue;
            if (nearest(action, p.ahead(), p.right()) < realTreesM) real.add(new double[]{p.ahead(), p.right()});
            else statics.remove(i);
        }
        memory = island(action, real, edge);
        fragments = SimVoid.fragments(4700 + kind.ordinal(), edge, List.of());
        far = new SimWorld(originN, originE);
    }

    /** The real land reaches this far (m) from the action, then breaks up into the floor (4 for an hour: the pilot, then "reintroduce the grass"). */
    static final double ISLAND_M = 13;
    /** Trees and bushes this near the action (m) stay real. */
    static final double REAL_TREES_M = 4;
    /** A real tree stands on real land this far round it (m). */
    static final double TREE_LAND_M = 4;
    /** The drone stands this far behind the scene's frame (m): the action 13 - 23 m off, as a drone set down to watch. */
    static final double BACK_M = 4;
    /** The action's places along a road count this far out from the drone (m): further the road is the floor's. */
    static final double ROAD_IN_M = 38;

    /** Where the action is (ahead, right): everything placed but the trees and bushes, and where things move. */
    private List<double[]> action() {
        List<double[]> out = new ArrayList<>();
        for (Put p : statics) if ((p.mesh().flags() & Raster3D.CARD) == 0) out.add(new double[]{p.ahead(), p.right()});
        switch (kind) {
            case DRONES -> {
                for (double[] pad : PADS) out.add(pad);
            }
            case CONVOY -> {
                for (double d = 0; d <= road.length; d += 2) if (Math.hypot(road.at(d)[0], road.at(d)[1]) < ROAD_IN_M) out.add(road.at(d));
            }
            case PATROL -> {
                for (double x = 0; x <= PATROL_SPAN; x += 2) out.add(new double[]{PATROL_AHEAD, PATROL_FROM + x});
            }
            case CHECKPOINT -> {
                for (double d = 0; d <= chicane.length; d += 2) if (Math.hypot(chicane.at(d)[0], chicane.at(d)[1]) < ROAD_IN_M) out.add(chicane.at(d));
            }
            case MORTAR -> out.add(new double[]{M_AHEAD, M_RIGHT});
            case HIDE -> {
                for (double x = 0; x <= 1; x += 0.25) out.add(new double[]{SENTRY_FROM[0] + (SENTRY_TO[0] - SENTRY_FROM[0]) * x, SENTRY_FROM[1] + (SENTRY_TO[1] - SENTRY_FROM[1]) * x});
            }
        }
        return out;
    }

    private static double nearest(List<double[]> pts, double ahead, double right) {
        double best = Double.MAX_VALUE;
        for (double[] q : pts) best = Math.min(best, (q[0] - ahead) * (q[0] - ahead) + (q[1] - right) * (q[1] - right));
        return Math.sqrt(best);
    }

    /**
     * The island: each metre cell (in the scene's frame) real land within about ISLAND_M of the action, of the ground
     * between it and the drone and TREE_LAND_M of a real tree, the edge ragged by whole cells; a band of light-blue tiles
     * just past it, a few scattered further out (lifting off: their middles go to edge, where the fragments start); the
     * floor beyond.
     */
    private Raster3D.Memory island(List<double[]> action, List<double[]> trees, List<double[]> edge) {
        List<double[]> seeds = new ArrayList<>(action);
        for (double a = -BACK_M; a <= 0; a += 2) seeds.add(new double[]{a, 0});
        int a0 = -40, r0 = -120, na = 180, nr = 240;
        byte[] land = new byte[na * nr];
        for (int i = 0; i < na; i++) {
            for (int j = 0; j < nr; j++) {
                double ahead = a0 + i + 0.5, right = r0 + j + 0.5;
                double d = Math.min(nearest(seeds, ahead, right), nearest(trees, ahead, right) + ISLAND_M - TREE_LAND_M);
                if (d > ISLAND_M + 6) continue;
                int h = SimVoid.hash((a0 + i) * 73856093 ^ (r0 + j) * 19349663);
                double s = d + ((h & 0xff) / 255.0 - 0.5) * 2.4;
                double h2 = (h >>> 8 & 0xff) / 255.0;
                if (s < ISLAND_M) {
                    land[i * nr + j] = Raster3D.Memory.LAND;
                } else if (s < ISLAND_M + 1.3 || (s < ISLAND_M + 3.5 && h2 < 0.3)) {
                    land[i * nr + j] = Raster3D.Memory.EDGE;
                    edge.add(new double[]{ahead, right});
                }
            }
        }
        return new Raster3D.Memory(originN, originE, headingDeg, land, a0, r0, na, nr);
    }

    Kind kind() {
        return kind;
    }

    // ------------------------------------------------------------------ the scene's frame

    /** A point ahead / right of the drone's start (metres) as {north, east}. */
    private double[] at(double ahead, double right) {
        double h = Math.toRadians(headingDeg);
        return new double[]{originN + ahead * Math.cos(h) - right * Math.sin(h), originE + ahead * Math.sin(h) + right * Math.cos(h)};
    }

    /** Something placed in the scene: a mesh, where, how turned and how big, its pose (frames a to b), its shadow. */
    record Put(Raster3D.Mesh mesh, double ahead, double right, double up, double heading, double pitch, double roll,
               double scale, int fa, int fb, float blend, int shadow) {
        Put(Raster3D.Mesh mesh, double ahead, double right, double heading, double scale) {
            this(mesh, ahead, right, 0, heading, 0, 0, scale, 0, 0, 0, CAST);
        }

        Put(Raster3D.Mesh mesh, double ahead, double right, double up, double heading, double scale) {
            this(mesh, ahead, right, up, heading, 0, 0, scale, 0, 0, 0, CAST);
        }
    }

    /** Shadows: none, the mesh's own cast along the sun, a soft one at the feet (a person), a crown's (a tree). */
    static final int NONE = 0, CAST = 1, FEET = 2, CROWN = 3, AIR = 4;

    /** A soft puff: smoke, dust, a flash. */
    record Puff(double ahead, double right, double up, double radius, int rgb, double alpha, boolean add) {}

    private static Raster3D.Mesh m(String name) {
        return Raster3D.model(name);
    }

    // ------------------------------------------------------------------ the picture

    /**
     * A drone set down on the grass (2026-11-10, the pilot: it looked like a CCTV camera on its mast, not a drone): its
     * camera this high over the ground, tilted up as an FPV camera is, the frame resting a little crooked.
     */
    static final double GROUND_EYE_M = 0.12, UPTILT_DEG = 5, REST_ROLL_DEG = 1.2;

    /**
     * The camera's view: on the ground from just over the grass; lifting off, it goes over into the drone's own view by
     * 1 m (no jump); in the air, from where it flies.
     */
    static Scene.View eye(Scene.View v) {
        if (v.alt() >= 1) return v;
        double k = 1 - Math.max(0, v.alt());
        return new Scene.View(v.north(), v.east(), v.alt() - (Scene.Camera.EYE_M - GROUND_EYE_M) * k, v.rollDeg() + REST_ROLL_DEG * k,
                v.pitchDeg() + UPTILT_DEG * k, v.headingDeg());
    }

    /** What stands still, drawn for one camera position and picture size (each painting thread its own). */
    private static final class Background {
        Scene.View v;
        int[] px;
        float[] depth;
        int w, h;

        boolean holds(Scene.View o, int w, int h) {
            return v != null && this.w == w && this.h == h && Math.abs(v.north() - o.north()) < 1e-3 && Math.abs(v.east() - o.east()) < 1e-3
                    && Math.abs(v.alt() - o.alt()) < 1e-3 && Math.abs(v.headingDeg() - o.headingDeg()) < 1e-3
                    && Math.abs(v.pitchDeg() - o.pitchDeg()) < 1e-3 && Math.abs(v.rollDeg() - o.rollDeg()) < 1e-3;
        }
    }

    private final ThreadLocal<Background> backgrounds = ThreadLocal.withInitial(Background::new);

    @Override
    public void render(BufferedImage img, Scene.View drone, long nowMs, boolean analysis) {
        Scene.View v = eye(drone);
        int w = img.getWidth(), h = img.getHeight();
        Scene.Camera cam = new Scene.Camera(v, w, h);
        Raster3D.Frame f = Raster3D.frame(img);
        Background b = backgrounds.get();
        if (b.holds(v, w, h)) {
            System.arraycopy(b.px, 0, f.px, 0, w * h);
            System.arraycopy(b.depth, 0, f.depth, 0, w * h);
        } else {
            SimVoid.sky(f.px, w, h, cam);
            Raster3D.ground(f, cam, Raster3D.groundTexture("ground-grass"), Raster3D.groundTexture("ground-dirt"), ground, memory);
            // the far structures on the floor (hundreds of metres off: behind everything drawn after them)
            java.awt.Graphics2D g = img.createGraphics();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                far.paintStructures(g, cam, w);
            } finally {
                g.dispose();
            }
            // nearest first: what is hidden behind them fails the depth test before its texture is read
            List<Put> near = new ArrayList<>(statics);
            near.sort((x, y) -> Double.compare(camDist(cam, x), camDist(cam, y)));
            draw(f, cam, near, List.of());
            // sitting on the ground: the grass near the lens out of focus, and blades right in front of it (sinking
            // away as the drone lifts off)
            if (drone.alt() < 0.05) {
                double[] far = cam.cam(cam.cn + 1000 * Math.cos(Math.toRadians(v.headingDeg())), cam.ce + 1000 * Math.sin(Math.toRadians(v.headingDeg())), cam.cu);
                DroneLens.defocus(f.px, w, h, cam.cy + cam.f * far[1] / far[2]);
            }
            DroneLens.grass(f, 991 + kind.ordinal(), Math.max(0, drone.alt()));
            if (b.px == null || b.px.length != w * h) {
                b.px = new int[w * h];
                b.depth = new float[w * h];
            }
            System.arraycopy(f.px, 0, b.px, 0, w * h);
            System.arraycopy(f.depth, 0, b.depth, 0, w * h);
            b.v = v;
            b.w = w;
            b.h = h;
        }
        double t = (nowMs - epochMs) / 1000.0;
        List<Put> puts = new ArrayList<>();
        List<Puff> puffs = new ArrayList<>();
        moving(puts, puffs, t);
        draw(f, cam, puts, puffs);
        if (!analysis) SimVoid.drawFragments(f, cam, fragments, t, this::at);
        DroneLens.apply(f.px, w, h, !analysis);
    }

    private void draw(Raster3D.Frame f, Scene.Camera cam, List<Put> puts, List<Puff> puffs) {
        for (Put p : puts) {
            double[] q = at(p.ahead(), p.right());
            double hd = headingDeg + p.heading();
            switch (p.shadow()) {
                case CAST -> Raster3D.castShadow(f, cam, p.mesh(), q[0], q[1], hd, p.scale(), p.up() + p.mesh().height() * p.scale());
                case FEET -> {
                    double[] s = at(p.ahead() + 0.25, p.right() + 0.25);
                    if (p.up() < 0.3) Raster3D.shadow(f, cam, s[0], s[1], 0.55 * p.scale(), 0.35);
                }
                case CROWN -> {
                    double hh = p.mesh().height() * p.scale();
                    double[] s = sunward(q, -0.45 * hh);
                    Raster3D.shadow(f, cam, s[0], s[1], 0.22 * hh, 0.30);
                }
                case AIR -> Raster3D.shadow(f, cam, q[0], q[1], 0.45, Math.max(0.12, 0.45 - p.up() * 0.03));
                default -> {
                }
            }
        }
        for (Put p : puts) {
            double[] q = at(p.ahead(), p.right());
            Raster3D.draw(f, cam, p.mesh(), p.fa(), p.fb(), p.blend(), q[0], q[1], p.up(), headingDeg + p.heading(), p.pitch(), p.roll(), p.scale(), -1);
        }
        for (Puff p : puffs) {
            double[] q = at(p.ahead(), p.right());
            Raster3D.puff(f, cam, q[0], q[1], p.up(), p.radius(), p.rgb(), p.alpha(), p.add());
        }
    }

    private double camDist(Scene.Camera cam, Put p) {
        double[] q = at(p.ahead(), p.right());
        return Math.hypot(q[0] - cam.cn, q[1] - cam.ce);
    }

    /** A world point moved along the sun's direction on the ground (negative: away from it). */
    private static double[] sunward(double[] q, double m) {
        double l = Math.hypot(Raster3D.SUN[0], Raster3D.SUN[1]);
        return new double[]{q[0] + Raster3D.SUN[0] / l * m, q[1] + Raster3D.SUN[1] / l * m};
    }

    private void moving(List<Put> out, List<Puff> puffs, double t) {
        switch (kind) {
            case DRONES -> drones(out, t);
            case CONVOY -> convoy(out, puffs, t);
            case PATROL -> patrol(out, t);
            case CHECKPOINT -> checkpoint(out, t);
            case MORTAR -> mortar(out, puffs, t);
            case HIDE -> hide(out, puffs, t);
        }
    }

    // ------------------------------------------------------------------ the land: ground, trees, bushes

    /** The tracks' dirt over the grass, blended at their edges, a cell every half metre round the scene. */
    private Raster3D.GroundMap groundMap(List<double[][]> tracks) {
        int size = 320;
        double cell = 0.5;
        double[] c = at(45, 0);
        double n0 = c[0] - size * cell / 2, e0 = c[1] - size * cell / 2;
        byte[] dirt = new byte[size * size], tone = new byte[size * size];
        double h = Math.toRadians(headingDeg), ch = Math.cos(h), sh = Math.sin(h);
        Random r = new Random(77);
        double[] wob = new double[64];
        for (int i = 0; i < wob.length; i++) wob[i] = r.nextDouble();
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                double dn = n0 + (i + 0.5) * cell - originN, de = e0 + (j + 0.5) * cell - originE;
                double ahead = dn * ch + de * sh, right = -dn * sh + de * ch;
                double best = 0;
                for (double[][] tr : tracks) {
                    double half = tr[0][0];
                    for (int k = 1; k + 1 < tr.length; k++) {
                        double d = segDist(ahead, right, tr[k], tr[k + 1]);
                        // a worn track: dirt in the middle, grass coming back at its edges, a little ragged
                        double edge = half * (0.85 + 0.3 * wob[(int) (Math.abs(ahead * 3.1 + right * 1.7)) % wob.length]);
                        double w = d < edge ? 1 : d < edge + 0.9 ? 1 - (d - edge) / 0.9 : 0;
                        best = Math.max(best, w * tr[0][1]);
                    }
                }
                dirt[i * size + j] = (byte) Math.round(best * 255);
                // the field's lighter and darker patches (128: as the texture is)
                double patch = 0.5 + 0.28 * Math.sin(ahead * 0.13 + 1.3 * Math.sin(right * 0.07)) * Math.cos(right * 0.11 + 0.8 * Math.sin(ahead * 0.05))
                        + 0.12 * Math.sin(ahead * 0.41 + right * 0.29);
                tone[i * size + j] = (byte) Math.max(0, Math.min(255, Math.round(patch * 255)));
            }
        }
        return new Raster3D.GroundMap(dirt, tone, size, cell, n0, e0);
    }

    private static double segDist(double a, double r, double[] p, double[] q) {
        double da = q[0] - p[0], dr = q[1] - p[1];
        double k = Math.max(0, Math.min(1, ((a - p[0]) * da + (r - p[1]) * dr) / (da * da + dr * dr)));
        return Math.hypot(a - p[0] - k * da, r - p[1] - k * dr);
    }

    /** A track for the ground map: its half width, how worn (0..1), then its points (ahead, right). */
    private static double[][] track(double half, double worn, double[]... pts) {
        double[][] out = new double[pts.length + 1][];
        out[0] = new double[]{half, worn};
        System.arraycopy(pts, 0, out, 1, pts.length);
        return out;
    }

    private static final String[] TREES = {"spruce-0", "spruce-1", "pine-0", "pine-1", "birch-0", "birch-1"};
    /** Each card's tree as drawn (m) and the heights it is given. */
    private static final double[] TREE_H = {12.09, 12.09, 5.34, 5.34, 8.18, 8.18};
    private static final double[][] TREE_RANGE = {{11, 17}, {11, 17}, {13, 19}, {13, 19}, {9, 14}, {9, 14}};

    /** A treeline: trees between ahead a0..a1, right r0..r1, about one per area m2; mostly conifers, some birch. */
    private void trees(Random r, double a0, double a1, double r0, double r1, double area, double birch) {
        int n = (int) ((a1 - a0) * (r1 - r0) / area);
        for (int i = 0; i < n; i++) {
            double ahead = a0 + r.nextDouble() * (a1 - a0), right = r0 + r.nextDouble() * (r1 - r0);
            tree(r, ahead, right, birch);
        }
    }

    private void tree(Random r, double ahead, double right, double birch) {
        int k = r.nextDouble() < birch ? 4 + r.nextInt(2) : r.nextInt(4);
        double hgt = TREE_RANGE[k][0] + r.nextDouble() * (TREE_RANGE[k][1] - TREE_RANGE[k][0]);
        statics.add(new Put(m(TREES[k]), ahead, right, 0, 0, 0, 0, hgt / TREE_H[k], 0, 0, 0, CROWN));
    }

    /** Bushes along a line (ahead, right pairs), about every step metres. */
    private void bushes(Random r, double step, double[]... line) {
        for (int i = 0; i + 1 < line.length; i++) {
            double len = Math.hypot(line[i + 1][0] - line[i][0], line[i + 1][1] - line[i][1]);
            for (double d = 0; d < len; d += step * (0.6 + 0.8 * r.nextDouble())) {
                double f = d / len;
                double ahead = line[i][0] + (line[i + 1][0] - line[i][0]) * f + (r.nextDouble() - 0.5) * 2;
                double right = line[i][1] + (line[i + 1][1] - line[i][1]) * f + (r.nextDouble() - 0.5) * 2;
                statics.add(new Put(m("bush-" + r.nextInt(4)), ahead, right, 0, 0, 0, 0, 0.9 + 0.9 * r.nextDouble(), 0, 0, 0, NONE));
            }
        }
    }

    /** The far forest all round, 90 to 150 m out: the horizon. */
    private void farForest(Random r) {
        for (int i = 0; i < 90; i++) {
            double a = r.nextDouble() * 2 * Math.PI, d = 90 + r.nextDouble() * 60;
            tree(r, d * Math.cos(a), d * Math.sin(a), 0.2);
        }
    }

    /** A wall of sandbags along the points (ahead, right), layers high, the bags laid like bricks. */
    private void sandbags(int layers, double[]... line) {
        Raster3D.Mesh bag = m("sandbag");
        List<double[]> at = new ArrayList<>();
        double len = 0.58, high = 0.16;
        double a0 = line[0][0], r0 = line[0][1];
        Random r = new Random(Math.round(a0 * 31 + r0 * 17));
        for (int i = 0; i + 1 < line.length; i++) {
            double da = line[i + 1][0] - line[i][0], dr = line[i + 1][1] - line[i][1];
            double seg = Math.hypot(da, dr);
            double dir = Math.toDegrees(Math.atan2(dr, da));
            for (int l = 0; l < layers; l++) {
                for (double d = (l % 2) * len / 2; d < seg - len / 4; d += len) {
                    double f = (d + len / 2) / seg;
                    at.add(new double[]{line[i][0] + da * f - a0, line[i][1] + dr * f - r0, l * high, dir + (r.nextDouble() - 0.5) * 8, 0.95 + 0.1 * r.nextDouble()});
                }
            }
        }
        // the copies in the wall's own axes: x ahead, y left (= -right)
        double[][] pl = new double[at.size()][];
        for (int i = 0; i < pl.length; i++) {
            double[] q = at.get(i);
            pl[i] = new double[]{q[0], -q[1], q[2], q[3], q[4]};
        }
        statics.add(new Put(Raster3D.compose(bag, pl), a0, r0, 0, 0, 1));
    }

    // ------------------------------------------------------------------ poses and paths

    /** The soldier's frames (tools/model/soldier_rig.py): a walk cycle, then the poses. */
    static final int WALK_FRAMES = 16;
    static final double WALK_STRIDE_M = 1.44;
    static final int STAND = 16, KNEEL = 17, KNEEL_AIM = 18, PRONE_BINO = 19, PRONE_AIM = 20, OPERATOR = 21,
            STAND_OPERATOR = 22, GUNNER = 23, LOADER_ROUND = 24, LOADER = 25, COVER = 26, RADIO = 27, KNEEL_BACK = 28;
    /** Further than this a soldier is drawn from the lighter model. */
    static final double FAR_SOLDIER_M = 22;

    private Raster3D.Mesh soldier(double ahead, double right) {
        return Math.hypot(ahead, right) > FAR_SOLDIER_M ? m("soldier-far") : m("soldier");
    }

    /** A soldier standing still in a pose. */
    private Put man(double ahead, double right, double up, double heading, int pose) {
        return new Put(soldier(ahead, right), ahead, right, up, heading, 0, 0, 1, pose, pose, 0, FEET);
    }

    /** A soldier between two poses (blend 0..1). */
    private Put man(double ahead, double right, double heading, int from, int to, double blend) {
        float b = (float) Math.max(0, Math.min(1, blend));
        return new Put(soldier(ahead, right), ahead, right, 0, heading, 0, 0, 1, from, to, b, FEET);
    }

    /** A soldier walking, walked metres into his walk. */
    private Put walker(double ahead, double right, double heading, double walked) {
        double ph = walked / WALK_STRIDE_M;
        ph -= Math.floor(ph);
        double fp = ph * WALK_FRAMES;
        int f0 = (int) fp % WALK_FRAMES;
        return new Put(soldier(ahead, right), ahead, right, 0, heading, 0, 0, 1, f0, (f0 + 1) % WALK_FRAMES, (float) (fp - Math.floor(fp)), FEET);
    }

    private static double smooth(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    /**
     * A smooth path (Catmull-Rom through its points, ahead / right / up), walked by distance: where along it, which way
     * it heads (degrees, relative), how hard it turns (1/m, right +) and climbs.
     */
    static final class Path {
        final double[] a, r, u, s;
        final double length;

        Path(boolean closed, double[]... pts) {
            int n = pts.length, seg = closed ? n : n - 1, per = 24;
            List<double[]> q = new ArrayList<>();
            for (int i = 0; i < seg; i++) {
                double[] p0 = pts[closed ? Math.floorMod(i - 1, n) : Math.max(0, i - 1)], p1 = pts[i], p2 = pts[(i + 1) % n];
                double[] p3 = pts[closed ? (i + 2) % n : Math.min(n - 1, i + 2)];
                for (int k = 0; k < per; k++) {
                    double t = (double) k / per, t2 = t * t, t3 = t2 * t;
                    double[] x = new double[3];
                    for (int c = 0; c < 3; c++) {
                        double v0 = c < p0.length ? p0[c] : 0, v1 = c < p1.length ? p1[c] : 0, v2 = c < p2.length ? p2[c] : 0, v3 = c < p3.length ? p3[c] : 0;
                        x[c] = 0.5 * (2 * v1 + (v2 - v0) * t + (2 * v0 - 5 * v1 + 4 * v2 - v3) * t2 + (3 * v1 - v0 - 3 * v2 + v3) * t3);
                    }
                    q.add(x);
                }
            }
            double[] last = closed ? q.get(0) : pts[n - 1];
            q.add(new double[]{last[0], last[1], last.length > 2 ? last[2] : 0});
            int m = q.size();
            a = new double[m];
            r = new double[m];
            u = new double[m];
            s = new double[m];
            for (int i = 0; i < m; i++) {
                a[i] = q.get(i)[0];
                r[i] = q.get(i)[1];
                u[i] = q.get(i)[2];
                if (i > 0) s[i] = s[i - 1] + Math.sqrt(sq(a[i] - a[i - 1]) + sq(r[i] - r[i - 1]) + sq(u[i] - u[i - 1]));
            }
            length = s[m - 1];
        }

        private static double sq(double x) {
            return x * x;
        }

        private int seg(double d) {
            int lo = 0, hi = s.length - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (s[mid] <= d) lo = mid;
                else hi = mid;
            }
            return lo;
        }

        /** {ahead, right, up} at d metres along. */
        double[] at(double d) {
            d = Math.max(0, Math.min(length, d));
            int i = seg(d);
            double f = s[i + 1] > s[i] ? (d - s[i]) / (s[i + 1] - s[i]) : 0;
            return new double[]{a[i] + (a[i + 1] - a[i]) * f, r[i] + (r[i + 1] - r[i]) * f, u[i] + (u[i + 1] - u[i]) * f};
        }

        /** The heading (degrees, relative to the scene) at d. */
        double heading(double d) {
            double[] p = at(d - 0.5), q = at(d + 0.5);
            return Math.toDegrees(Math.atan2(q[1] - p[1], q[0] - p[0]));
        }

        /** The curvature at d (1/m, turning right +). */
        double turn(double d) {
            double h1 = heading(d - 1.5), h2 = heading(d + 1.5);
            double dh = Math.toRadians(((h2 - h1) % 360 + 540) % 360 - 180);
            return dh / 3;
        }
    }

    // ------------------------------------------------------------------ DRONES: a drone team at a treeline

    /** The quads' size against the real thing: they read at 25-45 m (the pilot: further away; the FPV in its colours). */
    static final double QUAD_SCALE = 2.0;
    /** A quad's sortie (s): on its pad, climbing, the route, coming down, on its pad. */
    static final double SORTIE_S = 42, PAD_S = 4, CLIMB_S = 3, ROUTE_S = 22, LAND_S = 4;
    /** The quads' pads (ahead, right), the route's height over them on the way out and back. */
    static final double[][] PADS = {{11.0, 1.0}, {11.6, 3.6}, {10.6, 6.2}};
    static final double CRUISE_UP_M = 4;
    private Path[] routes;

    private void dronesLayout(List<double[][]> tracks) {
        Random r = new Random(4609);
        trees(r, 30, 50, -60, 60, 26, 0.25);
        trees(r, 24, 30, -60, -14, 30, 0.3);
        bushes(r, 3.2, new double[]{25, -60}, new double[]{22, -14}, new double[]{28, 10}, new double[]{29, 60});
        farForest(r);
        tracks.add(track(1.3, 0.85, new double[]{12, -60}, new double[]{15, -30}, new double[]{16.5, -10}, new double[]{17, -5}));
        tracks.add(track(1.8, 0.45, new double[]{15, -5}, new double[]{13.5, -1}, new double[]{11.5, 3}));
        // the team's post: their UAZ, gear, a mast, the team
        statics.add(new Put(m("uaz"), 17.0, -6.0, 70, 1));
        statics.add(new Put(m("mast"), 18.5, -9.5, 0, 0, 0, 0, 1, 0, 0, 0, NONE));
        statics.add(new Put(m("crate"), 14.6, -3.0, 0, 20, 0, 0, 1, 0, 0, 0, CAST));
        statics.add(new Put(m("crate"), 15.2, -3.6, 0, 35, 0, 0, 1, 0, 0, 0, CAST));
        statics.add(new Put(m("radio"), 14.6, -3.0, 0.46, 200, 0, 0, 1, 0, 0, 0, NONE));
        statics.add(new Put(m("ammo-box"), 14.0, -2.2, 0, 70, 1.3));
        statics.add(new Put(m("jerrycan"), 16.2, -4.2, 0, 10, 1));
        statics.add(new Put(m("jerrycan"), 16.5, -4.5, 0, 30, 1));
        statics.add(man(13.4, -1.2, 0, 165, OPERATOR));
        statics.add(man(14.4, 0.6, 0, 195, STAND_OPERATOR));
        statics.add(man(15.5, -2.4, 0, 140, RADIO));
        statics.add(man(18.0, -11.5, 0, 210, STAND));
        // the route: out over the field to the right, over the trees and along behind them, back low across the field
        double[][] wp = {{9, 10, 5}, {17, 18, 9}, {31, 14, 13}, {39, -4, 14}, {31, -20, 11}, {16, -16, 7}, {8, -6, 4.5}, {7, 2, 4}};
        routes = new Path[PADS.length];
        for (int i = 0; i < PADS.length; i++) {
            double[][] pts = new double[wp.length + 2][];
            pts[0] = new double[]{PADS[i][0], PADS[i][1], CRUISE_UP_M};
            for (int k = 0; k < wp.length; k++) pts[k + 1] = new double[]{wp[k][0] + i * 2.0, wp[k][1] - i * 2.5, wp[k][2] + i * 1.2};
            pts[wp.length + 1] = pts[0];
            routes[i] = new Path(false, pts);
        }
    }

    /** Where quad i is at t: {ahead, right, up, heading, pitch, roll, speed m/s}. */
    double[] quad(int i, double t) {
        double tau = Math.floorMod((long) Math.floor((t + i * SORTIE_S / PADS.length) * 1000), (long) (SORTIE_S * 1000)) / 1000.0;
        double[] pad = PADS[i];
        Path p = routes[i];
        if (tau < PAD_S) return new double[]{pad[0], pad[1], 0, 0, 0, 0, 0};
        tau -= PAD_S;
        if (tau < CLIMB_S) {
            double k = smooth(tau / CLIMB_S);
            return new double[]{pad[0], pad[1], CRUISE_UP_M * k, 0, 0, 0, 1.5};
        }
        tau -= CLIMB_S;
        if (tau < ROUTE_S) {
            double d = distance(tau, p.length), d2 = distance(tau + 0.25, p.length), d0 = distance(tau - 0.25, p.length);
            double[] q = p.at(d);
            double v = (d2 - d0) / 0.5, acc = (d2 - 2 * d + d0) / (0.25 * 0.25);
            double roll = Math.toDegrees(Math.atan(v * v * p.turn(d) / 9.81));
            double pitch = -Math.min(32, 2.6 * v + 2.2 * acc);
            double heading = v < 0.3 ? 0 : p.heading(d);
            return new double[]{q[0], q[1], q[2], heading, pitch, Math.max(-45, Math.min(45, roll)), v};
        }
        tau -= ROUTE_S;
        if (tau < LAND_S) {
            double k = smooth(tau / LAND_S);
            return new double[]{pad[0], pad[1], CRUISE_UP_M * (1 - k), 0, 0, 0, 1.0};
        }
        return new double[]{pad[0], pad[1], 0, 0, 0, 0, 0};
    }

    /** Metres along a route of length len after tau s of its ROUTE_S: speeding up and slowing down at its ends. */
    private static double distance(double tau, double len) {
        double x = Math.max(0, Math.min(1, tau / ROUTE_S));
        double ramp = 0.12; // the share of the time spent speeding up (and slowing down)
        double vmax = 1 / (1 - ramp);
        double d;
        if (x < ramp) d = vmax * x * x / (2 * ramp);
        else if (x > 1 - ramp) d = 1 - vmax * (1 - x) * (1 - x) / (2 * ramp);
        else d = vmax * ramp / 2 + vmax * (x - ramp);
        return d * len;
    }

    private void drones(List<Put> out, double t) {
        for (int i = 0; i < PADS.length; i++) {
            double[] q = quad(i, t);
            boolean recon = i == 1;
            Raster3D.Mesh mesh = recon ? m("recon-quad") : Raster3D.fpvDrone();
            double lift = recon ? 0.0 : 0.06 * QUAD_SCALE; // the FPV model's origin is its middle
            out.add(new Put(mesh, q[0], q[1], q[2] + lift, q[3], q[4], q[5], QUAD_SCALE, 0, 0, 0, q[2] > 0.3 ? AIR : NONE));
        }
    }

    // ------------------------------------------------------------------ CONVOY: vehicles on a forest road

    private Path road;
    /** The convoy: model, its engine's pitch, its place behind the lead (s). */
    private static final String[] CONVOY = {"btr80", "ural", "ural", "uaz"};
    private static final double[] CONVOY_PITCH = {1.0, 1.12, 1.18, 1.45};
    static final double CONVOY_GAP_S = 3.4, ROAD_FAST = 9, ROAD_SLOW = 4.5;
    /** The convoy comes by again this long after its tail left (s). */
    static final double CONVOY_PAUSE_S = 4;
    private double convoyS;
    private double[] roadTime; // seconds to each point along the road

    private void convoyLayout(List<double[][]> tracks) {
        Random r = new Random(4611);
        double[][] pts = {{56, -66}, {44, -48}, {28, -26}, {16, -8}, {15, 6}, {24, 24}, {42, 48}, {54, 66}};
        road = new Path(false, pts);
        double[][] tr = new double[pts.length + 1][];
        tr[0] = new double[]{2.6, 1};
        System.arraycopy(pts, 0, tr, 1, pts.length);
        tracks.add(tr);
        // the forest beyond the road and on both sides, a few trees and bushes this side of it
        trees(r, 30, 60, -22, 22, 16, 0.25);
        trees(r, 24, 64, -70, -30, 18, 0.25);
        trees(r, 24, 64, 30, 70, 18, 0.25);
        trees(r, 9, 14, -34, -16, 30, 0.4);
        trees(r, 9, 14, 16, 34, 30, 0.4);
        bushes(r, 3, new double[]{11, -16}, new double[]{12, -9});
        bushes(r, 3, new double[]{12, 10}, new double[]{11, 16});
        bushes(r, 4, new double[]{23, -16}, new double[]{22, 0}, new double[]{24, 14});
        farForest(r);
        // the time to each point, slower where the road bends
        roadTime = new double[401];
        double step = road.length / 400, sum = 0;
        for (int i = 1; i <= 400; i++) {
            double d = (i - 0.5) * step;
            double v = Math.max(ROAD_SLOW, Math.min(ROAD_FAST, Math.sqrt(1.6 / Math.max(1e-4, Math.abs(road.turn(d))))));
            sum += step / v;
            roadTime[i] = sum;
        }
        convoyS = sum + (CONVOY.length - 1) * CONVOY_GAP_S + CONVOY_PAUSE_S;
    }

    /** Where along the road (m) a vehicle is s seconds after it set off; -1 before, past the end after. */
    double roadAt(double s) {
        if (s < 0) return -1;
        if (s >= roadTime[roadTime.length - 1]) return road.length + 1;
        int lo = 0, hi = roadTime.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (roadTime[mid] <= s) lo = mid;
            else hi = mid;
        }
        double f = (s - roadTime[lo]) / (roadTime[hi] - roadTime[lo]);
        return (lo + f) * road.length / (roadTime.length - 1);
    }

    /** Vehicle k of the convoy at t: {ahead, right, heading, speed}, or null off the road. */
    double[] convoyVehicle(int k, double t) {
        double s = Math.floorMod((long) Math.floor(t * 1000), (long) (convoyS * 1000)) / 1000.0 - k * CONVOY_GAP_S;
        double d = roadAt(s);
        if (d < 0 || d > road.length) return null;
        double[] q = road.at(d);
        double v = (roadAt(s + 0.2) - roadAt(s - 0.2)) / 0.4;
        return new double[]{q[0], q[1], road.heading(d), v};
    }

    private void convoy(List<Put> out, List<Puff> puffs, double t) {
        for (int k = 0; k < CONVOY.length; k++) {
            double[] q = convoyVehicle(k, t);
            if (q == null) continue;
            double bob = 0.25 * Math.sin(t * 7.3 + k) * Math.min(1, q[3] / ROAD_FAST);
            out.add(new Put(m(CONVOY[k]), q[0], q[1], 0, q[2], bob, 0.3 * Math.sin(t * 5.1 + k * 2), 1, 0, 0, 0, CAST));
            // dust off the dry road behind it
            double h = Math.toRadians(q[2]);
            double len = k == 3 ? 2.2 : 3.9;
            for (int j = 0; j < 3; j++) {
                double age = (t * 1.3 + j / 3.0) % 1.0;
                double back = len + age * 4;
                puffs.add(new Puff(q[0] - Math.cos(h) * back, q[1] - Math.sin(h) * back, 0.5 + age * 1.2, 0.8 + age * 1.6, 0xb8a78c,
                        0.22 * (1 - age) * Math.min(1, q[3] / ROAD_FAST), false));
            }
        }
    }

    // ------------------------------------------------------------------ PATROL: a section along a treeline

    static final int PATROL_MEN = 7;
    static final double PATROL_SPACING = 5, PATROL_AHEAD = 13, PATROL_FROM = -25.8, PATROL_SPAN = 57.2, PATROL_WALK = 1.3;
    /** The patrol's minute: walking, halting, to cover (kneeling, facing out), up again, walking on. */
    static final double P_WALK1 = 26, P_HALT = 2, P_DOWN = 2, P_COVER = 10, P_UP = 2, P_CYCLE = 60;

    private void patrolLayout(List<double[][]> tracks) {
        Random r = new Random(4612);
        trees(r, 18, 40, -70, 70, 15, 0.3);
        bushes(r, 2.6, new double[]{16.5, -70}, new double[]{15.8, -20}, new double[]{17, 20}, new double[]{16.5, 70});
        farForest(r);
        tracks.add(track(0.5, 0.55, new double[]{PATROL_AHEAD + 0.6, -80}, new double[]{PATROL_AHEAD - 0.4, 0}, new double[]{PATROL_AHEAD + 0.6, 80}));
    }

    /** Metres the patrol has walked by t, and where in its minute it is. */
    static double patrolWalked(double t) {
        double cycles = Math.floor(t / P_CYCLE), c = t - cycles * P_CYCLE;
        double per = (P_CYCLE - P_HALT - P_DOWN - P_COVER - P_UP) * PATROL_WALK;
        double still = P_WALK1 + P_HALT + P_DOWN + P_COVER + P_UP;
        double walked = c < P_WALK1 ? c : c < still ? P_WALK1 : P_WALK1 + c - still;
        return cycles * per + walked * PATROL_WALK;
    }

    private void patrol(List<Put> out, double t) {
        double c = t - Math.floor(t / P_CYCLE) * P_CYCLE;
        double walked = patrolWalked(t);
        double halt = P_WALK1, down = halt + P_HALT, cover = down + P_DOWN, up = cover + P_COVER, on = up + P_UP;
        for (int k = 0; k < PATROL_MEN; k++) {
            double x = Math.floorMod((long) Math.round((walked - k * PATROL_SPACING) * 1000), (long) (PATROL_SPAN * 1000)) / 1000.0;
            double right = PATROL_FROM + x;
            double ahead = PATROL_AHEAD + 0.4 * Math.sin(right * 0.15);
            // to cover: turned out to the field, every other man the other way along it
            double out1 = k % 2 == 0 ? 160 : 210;
            if (c < halt || c >= on) {
                out.add(walker(ahead, right, 90, walked - k * 0.37));
            } else if (c < down) {
                out.add(man(ahead, right, 90, Math.floorMod((int) ((walked - k * 0.37) / WALK_STRIDE_M * WALK_FRAMES), WALK_FRAMES), STAND, (c - halt) / P_HALT));
            } else if (c < cover) {
                double f = smooth((c - down) / P_DOWN);
                out.add(man(ahead, right, 90 + (out1 - 90) * f, STAND, KNEEL_AIM, f));
            } else if (c < up) {
                out.add(man(ahead, right, 0, out1, KNEEL_AIM));
            } else {
                double f = smooth((c - up) / P_UP);
                out.add(man(ahead, right, out1 + (90 - out1) * f, KNEEL_AIM, STAND, f));
            }
        }
    }

    // ------------------------------------------------------------------ CHECKPOINT: a roadblock on a road

    static final double CP_NEAR = 11.6, CP_FAR = 14.8, CP_STOP = 8.6, CP_CYCLE = 40;
    private Path chicane;

    private void checkpointLayout(List<double[][]> tracks) {
        Random r = new Random(4613);
        tracks.add(track(3.4, 1, new double[]{13.2, -90}, new double[]{13.2, 90}));
        tracks.add(track(1.0, 0.6, new double[]{9.5, -7}, new double[]{7, -14}));
        trees(r, 30, 58, -70, 70, 17, 0.3);
        trees(r, 22, 30, -70, -18, 24, 0.3);
        trees(r, 22, 30, 18, 70, 24, 0.3);
        bushes(r, 3.5, new double[]{21, -40}, new double[]{20, 40});
        farForest(r);
        // the chicane: a barrier on each lane, a little apart
        statics.add(new Put(m("barrier"), CP_FAR, 2.0, 90, 1));
        statics.add(new Put(m("barrier"), CP_FAR - 0.1, 4.0, 90, 1));
        statics.add(new Put(m("barrier"), CP_NEAR, -3.0, 90, 1));
        statics.add(new Put(m("barrier"), CP_NEAR + 0.1, -5.0, 90, 1));
        // the posts: sandbags round them
        sandbags(4, new double[]{7.8, -9.0}, new double[]{9.0, -8.0}, new double[]{9.0, -5.6}, new double[]{7.8, -4.6});
        sandbags(4, new double[]{19.4, 6.2}, new double[]{18.4, 7.2}, new double[]{18.4, 9.6}, new double[]{19.4, 10.6});
        // hedgehogs and wire along the road beyond the posts
        for (double[] q : new double[][]{{9.4, -14}, {9.7, -19}, {17.6, -13}, {17.4, 13}, {9.6, 14}, {10.0, 19.5}}) {
            statics.add(new Put(m("hedgehog"), q[0], q[1], 0, q[1] * 7, 0, 0, 1, 0, 0, 0, CAST));
        }
        statics.add(new Put(m("wire"), 8.8, -23.5, 0, 90, 0, 0, 1, 0, 0, 0, NONE));
        statics.add(new Put(m("wire"), 9.0, 23.5, 0, 90, 0, 0, 1, 0, 0, 0, NONE));
        statics.add(new Put(m("wire"), 18.2, -20, 0, 90, 0, 0, 1, 0, 0, 0, NONE));
        statics.add(new Put(m("crate"), 7.6, -3.2, 0, 80, 1));
        // the guards who stay: one kneeling behind the near post's bags, one at the far post
        statics.add(man(8.2, -6.8, 0, 10, KNEEL_AIM));
        statics.add(man(19.0, 8.4, 0, 230, STAND));
        // the UAZ's way through: the near lane, round the near barriers in the far lane, back to the near lane
        chicane = new Path(false, new double[]{CP_NEAR, 70}, new double[]{CP_NEAR, 30}, new double[]{CP_NEAR, CP_STOP},
                new double[]{CP_NEAR, 1.2}, new double[]{CP_FAR - 0.4, -2.6}, new double[]{CP_FAR - 0.4, -5.6},
                new double[]{CP_NEAR, -10}, new double[]{CP_NEAR, -40}, new double[]{CP_NEAR, -80});
    }

    /** The checkpoint's minute: the UAZ's distance along its way at c s into the cycle, and its speed. */
    double[] cpCar(double c) {
        double stopAt = 0;
        for (double d = 0; d < chicane.length; d += 0.05) {
            if (chicane.at(d)[1] <= CP_STOP) {
                stopAt = d;
                break;
            }
        }
        // in at 10 m/s, braking over the last 20 m, stopped from 8 s to 22 s, off slowly through the chicane
        double brake = 20, vIn = 10, tBrake = 2 * brake / vIn, tIn = (stopAt - brake) / vIn;
        if (c < tIn) return new double[]{vIn * c, vIn};
        if (c < tIn + tBrake) {
            double x = c - tIn;
            return new double[]{stopAt - brake + vIn * x - vIn / tBrake * x * x / 2, vIn * (1 - x / tBrake)};
        }
        double go = 22;
        if (c < go) return new double[]{stopAt, 0};
        double x = c - go, acc = 0.9;
        double d = x < 4 ? acc * x * x / 2 : acc * 8 + 3.6 * (x - 4) + 0.5 * Math.min(x - 4, 10) * Math.min(x - 4, 10) * 0.6;
        return new double[]{stopAt + d, x < 4 ? acc * x : 3.6 + 0.6 * Math.min(x - 4, 10)};
    }

    private void checkpoint(List<Put> out, double t) {
        double c = t - Math.floor(t / CP_CYCLE) * CP_CYCLE;
        double[] car = cpCar(c);
        if (car[0] < chicane.length) {
            double[] q = chicane.at(car[0]);
            double idle = car[1] < 0.1 ? 0.012 * Math.sin(t * 40) : 0;
            out.add(new Put(m("uaz"), q[0], q[1], idle, chicane.heading(car[0]), 0, 0, 1, 0, 0, 0, CAST));
        }
        // the guard: from his place to the driver's window, a look, back
        double[] home = {9.4, 3.0}, window = {10.2, CP_STOP + 0.3};
        double walk = Math.hypot(window[0] - home[0], window[1] - home[1]) / 1.3;
        double t1 = 9, t2 = t1 + walk, t3 = 18, t4 = t3 + walk;
        double toWin = Math.toDegrees(Math.atan2(window[1] - home[1], window[0] - home[0]));
        if (c < t1 || c >= t4 + 1) {
            out.add(man(home[0], home[1], 0, 70, STAND));
        } else if (c < t2) {
            double f = (c - t1) / walk;
            out.add(walker(home[0] + (window[0] - home[0]) * f, home[1] + (window[1] - home[1]) * f, toWin, (c - t1) * 1.3));
        } else if (c < t3) {
            out.add(man(window[0], window[1], 0, 15, STAND));
        } else if (c < t4) {
            double f = (c - t3) / walk;
            out.add(walker(window[0] + (home[0] - window[0]) * f, window[1] + (home[1] - window[1]) * f, toWin + 180, (c - t3) * 1.3));
        } else {
            out.add(man(home[0], home[1], 0, 70, STAND));
        }
    }

    // ------------------------------------------------------------------ MORTAR: a firing position

    static final double M_AHEAD = 9.5, M_RIGHT = -1.2, M_HEADING = 8, FIRE_CYCLE = 9, FIRE_AT = 3.5;
    /** The muzzle in the mortar's axes (export_tactical.py prints it). */
    static final double[] MUZZLE = {-0.017, 0.005, 1.05};

    private void mortarLayout(List<double[][]> tracks) {
        Random r = new Random(4614);
        trees(r, 27, 55, -70, 70, 16, 0.3);
        trees(r, 17, 27, 7, 40, 18, 0.3);
        bushes(r, 3, new double[]{21, -50}, new double[]{20, 0}, new double[]{16, 8});
        farForest(r);
        tracks.add(track(1.4, 0.7, new double[]{M_AHEAD, M_RIGHT}, new double[]{11.5, 2}, new double[]{13, 4}));
        tracks.add(track(1.0, 0.5, new double[]{13, 4}, new double[]{16, 12}, new double[]{20, 30}));
        statics.add(new Put(m("net-small"), 13.0, 3.6, 0, 15, 0, 0, 1, 0, 0, 0, CAST));
        statics.add(new Put(m("crate"), 12.4, 3.6, 0, 95, 1));
        statics.add(new Put(m("crate"), 13.1, 3.5, 0, 100, 1));
        statics.add(new Put(m("crate"), 12.7, 3.55, 0.46, 97, 0, 0, 1, 0, 0, 0, NONE));
        statics.add(new Put(m("ammo-box"), 11.0, 1.4, 0, 30, 1.4));
        statics.add(new Put(m("ammo-box"), 11.3, 1.1, 0, 60, 1.4));
        statics.add(new Put(m("jerrycan"), 13.4, 1.8, 0, 10, 1));
        statics.add(new Put(m("radio"), 12.0, 1.8, 0, 250, 1));
        sandbags(3, new double[]{11.0, -3.6}, new double[]{11.7, -1.6}, new double[]{11.4, 0.4});
        sandbags(2, new double[]{8.6, -3.8}, new double[]{10.6, -3.9});
        statics.add(man(11.8, 2.3, 0, 200, KNEEL));
    }

    private void mortar(List<Put> out, List<Puff> puffs, double t) {
        double c = t - Math.floor(t / FIRE_CYCLE) * FIRE_CYCLE;
        double since = c - FIRE_AT;
        double recoil = since >= 0 && since < 0.25 ? -0.035 * (1 - since / 0.25) : 0;
        out.add(new Put(m("mortar"), M_AHEAD, M_RIGHT, recoil, M_HEADING, 0, 0, 1, 0, 0, 0, CAST));
        // the gunner at the sight, the loader beside the muzzle
        double ga = M_AHEAD - 0.1, gr = M_RIGHT - 0.85, la = M_AHEAD + 0.1, lr = M_RIGHT + 0.7;
        int gFrom, gTo, lFrom, lTo;
        double gB, lB;
        if (c < 1.2) {
            lFrom = KNEEL_BACK;
            lTo = LOADER_ROUND;
            lB = smooth(c / 1.2);
        } else if (c < 2.5) {
            lFrom = lTo = LOADER_ROUND;
            lB = 0;
        } else if (c < 3.2) {
            lFrom = LOADER;
            lTo = COVER;
            lB = smooth((c - 2.5) / 0.7);
        } else if (c < 4.6) {
            lFrom = lTo = COVER;
            lB = 0;
        } else {
            lFrom = COVER;
            lTo = KNEEL_BACK;
            lB = smooth((c - 4.6) / 1.2);
        }
        if (c < 2.6 || c >= 6.0) {
            gFrom = gTo = GUNNER;
            gB = 0;
        } else if (c < 3.2) {
            gFrom = GUNNER;
            gTo = COVER;
            gB = smooth((c - 2.6) / 0.6);
        } else if (c < 4.8) {
            gFrom = gTo = COVER;
            gB = 0;
        } else {
            gFrom = COVER;
            gTo = GUNNER;
            gB = smooth((c - 4.8) / 1.2);
        }
        out.add(man(ga, gr, M_HEADING + 70, gFrom, gTo, gB));
        out.add(man(la, lr, M_HEADING - 100, lFrom, lTo, lB));
        if (since < 0) return;
        // the muzzle in the scene's frame (ahead, right)
        double h = Math.toRadians(M_HEADING);
        double ma = M_AHEAD + MUZZLE[0] * Math.cos(h) + MUZZLE[1] * Math.sin(h), mr = M_RIGHT + MUZZLE[0] * Math.sin(h) - MUZZLE[1] * Math.cos(h);
        if (since < 0.12) {
            double k = 1 - since / 0.12;
            puffs.add(new Puff(ma, mr, MUZZLE[2] + 0.25, 0.9, 0xfff0c0, 0.95 * k, true));
            puffs.add(new Puff(ma, mr, MUZZLE[2] + 0.1, 0.4, 0xffffff, k, true));
        }
        // smoke from the muzzle drifting up, and the blast's dust round the base plate
        for (int j = 0; j < 4; j++) {
            double age = since - j * 0.12;
            if (age < 0 || age > 4.5) continue;
            double k = age / 4.5;
            puffs.add(new Puff(ma + 0.25 * age + 0.1 * j, mr - 0.2 * age, MUZZLE[2] + 0.3 + 0.9 * age + 0.25 * j, 0.35 + 0.9 * Math.sqrt(age) + 0.1 * j,
                    0xc8c6c0, 0.55 * (1 - k) * (1 - k), false));
        }
        if (since < 2) {
            double k = since / 2;
            for (int j = 0; j < 5; j++) {
                double a = j * 2 * Math.PI / 5 + 0.4;
                double rr = 0.4 + 1.4 * Math.sqrt(k);
                puffs.add(new Puff(M_AHEAD + rr * Math.cos(a), M_RIGHT + rr * Math.sin(a), 0.25, 0.35 + 0.5 * k, 0xa89880, 0.35 * (1 - k), false));
            }
        }
    }

    // ------------------------------------------------------------------ HIDE: a BTR under a net, an OP on a rise

    static final double RISE_AHEAD = 9, RISE_RIGHT = 5, RISE_R = 5.5, RISE_H = 0.9;
    static final double[] SENTRY_FROM = {15.5, -12}, SENTRY_TO = {15.5, 0};
    /** The BTR in its hide: where, which way its nose points (the net set back so the nose shows). */
    static final double BTR_AHEAD = 19.5, BTR_RIGHT = -5, BTR_HEADING = 200;

    private void hideLayout(List<double[][]> tracks) {
        Random r = new Random(4615);
        trees(r, 23, 52, -70, 70, 15, 0.3);
        trees(r, 21, 24, -26, 12, 10, 0.2);
        bushes(r, 2.4, new double[]{17, -40}, new double[]{17.5, -14}, new double[]{18.5, 4}, new double[]{19, 30});
        farForest(r);
        tracks.add(track(1.6, 0.8, new double[]{BTR_AHEAD, BTR_RIGHT}, new double[]{15, -3}, new double[]{12, 10}, new double[]{6, 40}));
        double bh = Math.toRadians(BTR_HEADING);
        statics.add(new Put(m("btr80"), BTR_AHEAD, BTR_RIGHT, 0, BTR_HEADING, 0, 0, 1, 0, 0, 0, NONE));
        statics.add(new Put(m("net-large"), BTR_AHEAD - 3.0 * Math.cos(bh), BTR_RIGHT - 3.0 * Math.sin(bh), 0, BTR_HEADING - 90, 0, 0, 1, 0, 0, 0, CAST));
        statics.add(new Put(m("jerrycan"), 17.4, -1.0, 0, 40, 1));
        statics.add(new Put(m("crate"), 17.6, -9.4, 0, 120, 1));
        statics.add(new Put(rise(), RISE_AHEAD, RISE_RIGHT, 0, 0, 0, 0, 1, 0, 0, 0, NONE));
        // the OP's bags along the rise's far edge
        double[][] arc = new double[6][];
        for (int i = 0; i < arc.length; i++) {
            double a = Math.toRadians(-60 + i * 24);
            arc[i] = new double[]{RISE_AHEAD + 2.4 * Math.cos(a), RISE_RIGHT + 2.4 * Math.sin(a)};
        }
        sandbagsOn(2, arc);
    }

    /** The rise's height over the field at (ahead, right). */
    static double riseAt(double ahead, double right) {
        double d = Math.hypot(ahead - RISE_AHEAD, right - RISE_RIGHT) / RISE_R;
        if (d >= 1) return 0;
        double k = 1 - d * d;
        return RISE_H * k * k;
    }

    /** The rise: a low mound of grass. */
    private static Raster3D.Mesh rise() {
        int rings = 10, spokes = 28;
        float[] p = new float[(rings * spokes + 1) * 3], uv = new float[(rings * spokes + 1) * 2];
        p[2] = (float) RISE_H;
        for (int i = 1; i <= rings; i++) {
            double rr = RISE_R * i / rings;
            for (int j = 0; j < spokes; j++) {
                double a = 2 * Math.PI * j / spokes;
                int o = 1 + (i - 1) * spokes + j;
                double x = rr * Math.cos(a), y = rr * Math.sin(a);
                p[o * 3] = (float) x;
                p[o * 3 + 1] = (float) y;
                p[o * 3 + 2] = (float) riseAt(RISE_AHEAD + x, RISE_RIGHT - y) - (i == rings ? 0.02f : 0);
                uv[o * 2] = (float) (y / Raster3D.GROUND_TILE_M);
                uv[o * 2 + 1] = (float) (x / Raster3D.GROUND_TILE_M);
            }
        }
        List<Integer> tri = new ArrayList<>();
        for (int j = 0; j < spokes; j++) {
            tri.add(0);
            tri.add(1 + (j + 1) % spokes);
            tri.add(1 + j);
        }
        for (int i = 1; i < rings; i++) {
            for (int j = 0; j < spokes; j++) {
                int a = 1 + (i - 1) * spokes + j, b = 1 + (i - 1) * spokes + (j + 1) % spokes, c = 1 + i * spokes + j, d = 1 + i * spokes + (j + 1) % spokes;
                tri.add(a);
                tri.add(b);
                tri.add(c);
                tri.add(b);
                tri.add(d);
                tri.add(c);
            }
        }
        int[] t = tri.stream().mapToInt(Integer::intValue).toArray();
        Raster3D.Texture grass = Raster3D.groundTexture("ground-grass");
        int[] rgb = new int[t.length / 3];
        return new Raster3D.Mesh(p, t, rgb, uv, new byte[t.length / 3], new Raster3D.Texture[]{grass}, null,
                new float[][]{Raster3D.normals(p, t)}, Raster3D.TERRAIN, null);
    }

    /** Sandbags along a line on the rise (each bag at the rise's height there). */
    private void sandbagsOn(int layers, double[]... line) {
        int before = statics.size();
        sandbags(layers, line);
        Put wall = statics.remove(before);
        statics.add(new Put(wall.mesh(), wall.ahead(), wall.right(), riseAt(wall.ahead(), wall.right()) - 0.05, 0, 0, 0, 1, 0, 0, 0, NONE));
    }

    private void hide(List<Put> out, List<Puff> puffs, double t) {
        // the observer sweeps the ground beyond with his binoculars; the radio man talks now and then
        double oa = RISE_AHEAD + 1.0, or = RISE_RIGHT - 0.3;
        out.add(new Put(soldier(oa, or), oa, or, riseAt(oa, or) - 0.04, 125 + 22 * Math.sin(t * 0.45), 0, 0, 1, PRONE_BINO, PRONE_BINO, 0, NONE));
        double ra = RISE_AHEAD - 0.8, rr = RISE_RIGHT + 1.4;
        double talk = (t % 16) / 16;
        double b = talk < 0.45 ? smooth(talk / 0.08) : 1 - smooth((talk - 0.45) / 0.08);
        out.add(new Put(soldier(ra, rr), ra, rr, riseAt(ra, rr), 140, 0, 0, 1, KNEEL_BACK, RADIO, (float) b, NONE));
        // the sentry's beat in front of the hide
        double len = Math.hypot(SENTRY_TO[0] - SENTRY_FROM[0], SENTRY_TO[1] - SENTRY_FROM[1]);
        double leg = len / 1.2, pause = 3, cyc = 2 * (leg + pause);
        double c = t % cyc;
        double dir = Math.toDegrees(Math.atan2(SENTRY_TO[1] - SENTRY_FROM[1], SENTRY_TO[0] - SENTRY_FROM[0]));
        if (c < leg) {
            double f = c / leg;
            out.add(walker(SENTRY_FROM[0] + (SENTRY_TO[0] - SENTRY_FROM[0]) * f, SENTRY_FROM[1] + (SENTRY_TO[1] - SENTRY_FROM[1]) * f, dir, c * 1.2));
        } else if (c < leg + pause) {
            out.add(man(SENTRY_TO[0], SENTRY_TO[1], 0, dir + 180 * smooth((c - leg) / pause), STAND));
        } else if (c < 2 * leg + pause) {
            double f = (c - leg - pause) / leg;
            out.add(walker(SENTRY_TO[0] + (SENTRY_FROM[0] - SENTRY_TO[0]) * f, SENTRY_TO[1] + (SENTRY_FROM[1] - SENTRY_TO[1]) * f, dir + 180, (c - leg - pause) * 1.2));
        } else {
            out.add(man(SENTRY_FROM[0], SENTRY_FROM[1], 0, dir + 180 + 180 * smooth((c - 2 * leg - pause) / pause), STAND));
        }
        // the idling engine's exhaust at the hull's back, drifting up through the net's edge
        double h = Math.toRadians(BTR_HEADING);
        double ea = BTR_AHEAD - 3.4 * Math.cos(h) + 1.0 * Math.sin(h), er = BTR_RIGHT - 3.4 * Math.sin(h) - 1.0 * Math.cos(h);
        for (int j = 0; j < 4; j++) {
            double age = (t * 0.6 + j / 4.0) % 1.0;
            puffs.add(new Puff(ea - 0.6 * age, er + 0.3 * age, 1.2 + 1.6 * age, 0.25 + 0.6 * age, 0x9a9a96, 0.18 * (1 - age), false));
        }
    }

    // ------------------------------------------------------------------ the sound

    /** One source heard: where, how loud, its sample and pitch. */
    private record Src(double ahead, double right, double loud, String sample, double pitch) {}

    private SoundSample.Voice[] voices;
    private String[] voiceSample;
    private double[] gainL, gainR;
    private float[] boom;
    private long boomAt = Long.MIN_VALUE;
    private int boomPos = Integer.MAX_VALUE;

    @Override
    public void addSound(float[] left, float[] right, Scene.View v, long nowMs) {
        double t = (nowMs - epochMs) / 1000.0;
        List<Src> sources = new ArrayList<>();
        switch (kind) {
            case DRONES -> {
                for (int i = 0; i < PADS.length; i++) {
                    double[] q = quad(i, t);
                    double on = q[2] > 0.05 ? 1 : 0;
                    sources.add(new Src(q[0], q[1], 1.6 * on, "fpv-flight", 0.9 + 0.025 * q[6] + 0.04 * i));
                }
            }
            case CONVOY -> {
                for (int k = 0; k < CONVOY.length; k++) {
                    double[] q = convoyVehicle(k, t);
                    if (q == null) sources.add(new Src(0, 0, 0, "btr-drive", 1));
                    else sources.add(new Src(q[0], q[1], 2.2, "btr-drive", CONVOY_PITCH[k] * (0.85 + 0.15 * q[3] / ROAD_FAST)));
                }
            }
            case CHECKPOINT -> {
                double c = t - Math.floor(t / CP_CYCLE) * CP_CYCLE;
                double[] car = cpCar(c);
                if (car[0] < chicane.length) {
                    double[] q = chicane.at(car[0]);
                    boolean idle = car[1] < 0.1;
                    sources.add(new Src(q[0], q[1], 1.4, idle ? "btr-idle" : "btr-drive", idle ? 1.5 : 1.4 + 0.02 * car[1]));
                } else {
                    sources.add(new Src(0, 0, 0, "btr-idle", 1));
                }
            }
            case HIDE -> sources.add(new Src(BTR_AHEAD, BTR_RIGHT, 1.6, "btr-idle", 1.0));
            case MORTAR -> {
                long shot = (long) Math.floor((t - FIRE_AT) / FIRE_CYCLE);
                if (shot != boomAt && t - FIRE_AT - shot * FIRE_CYCLE < 0.5) {
                    boomAt = shot;
                    boomPos = 0;
                }
            }
            case PATROL -> {
            }
        }
        if (kind == Kind.MORTAR) {
            mixBoom(left, right, v);
            return;
        }
        if (sources.isEmpty()) return;
        if (voices == null || voices.length != sources.size()) {
            voices = new SoundSample.Voice[sources.size()];
            voiceSample = new String[sources.size()];
            gainL = new double[sources.size()];
            gainR = new double[sources.size()];
        }
        double h = Math.toRadians(v.headingDeg());
        int n = left.length;
        for (int k = 0; k < sources.size(); k++) {
            Src s = sources.get(k);
            if (!s.sample().equals(voiceSample[k])) {
                voices[k] = new SoundSample.Voice(SoundSample.of(s.sample()), true);
                for (int skip = 0; skip < 4000 * (k + 1); skip++) voices[k].next(1); // not all in step
                voiceSample[k] = s.sample();
            }
            double[] q = at(s.ahead(), s.right());
            double dn = q[0] - v.north(), de = q[1] - v.east();
            double dist = Math.max(2, Math.hypot(dn, de));
            double bearing = Math.atan2(de, dn) - h;
            double level = Math.min(1, 6 / dist) * s.loud() * 0.5;
            double pan = Math.sin(bearing), angle = (pan + 1) * Math.PI / 4;
            double toL = level * Math.cos(angle), toR = level * Math.sin(angle);
            for (int i = 0; i < n; i++) {
                double f = (double) i / n;
                float x = voices[k].next(s.pitch());
                left[i] = clamp(left[i] + (float) (x * (gainL[k] + (toL - gainL[k]) * f)));
                right[i] = clamp(right[i] + (float) (x * (gainR[k] + (toR - gainR[k]) * f)));
            }
            gainL[k] = toL;
            gainR[k] = toR;
        }
    }

    /** The mortar's report: a low thump and a rumble, made here (no recording of one is bundled), from the muzzle's side. */
    private void mixBoom(float[] left, float[] right, Scene.View v) {
        if (boom == null) {
            int rate = AacEncoder.SAMPLE_RATE, n = (int) (rate * 1.6);
            boom = new float[n];
            Random r = new Random(81);
            double lp = 0, lp2 = 0;
            for (int i = 0; i < n; i++) {
                double s = (double) i / rate;
                lp += (r.nextGaussian() - lp) * 0.06;
                lp2 += (lp - lp2) * 0.05;
                double thump = Math.sin(2 * Math.PI * (55 - 20 * s) * s) * Math.exp(-s * 9);
                boom[i] = (float) ((0.75 * thump + 5.0 * lp2 * Math.exp(-s * 2.6) + 1.2 * lp * Math.exp(-s * 30)) * 0.8);
            }
        }
        if (boomPos >= boom.length) return;
        double[] q = at(M_AHEAD, M_RIGHT);
        double bearing = Math.atan2(q[1] - v.east(), q[0] - v.north()) - Math.toRadians(v.headingDeg());
        double angle = (Math.sin(bearing) + 1) * Math.PI / 4;
        double gl = Math.cos(angle) * 0.9, gr = Math.sin(angle) * 0.9;
        for (int i = 0; i < left.length && boomPos < boom.length; i++, boomPos++) {
            left[i] = clamp(left[i] + (float) (boom[boomPos] * gl));
            right[i] = clamp(right[i] + (float) (boom[boomPos] * gr));
        }
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}
