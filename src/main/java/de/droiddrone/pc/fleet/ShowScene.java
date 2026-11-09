package de.droiddrone.pc.fleet;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The tour's six drones' pictures (2026-11-09, the pilot: "each one of the drones should have something visually
 * interesting happening in them. Do not use the BTR here"): what each one's camera sees, in front of where it stands,
 * in the virtual fleet's own world ({@link SimWorld}'s sky and grid, its screen effects) with real 3D models:
 * <ul>
 * <li>{@link Kind#RACE}: four FPV quads - Drone setup's own model - racing round a loop through three gates.</li>
 * <li>{@link Kind#RALLY}: trucks and a motorcycle lapping an oval, a forest and a camp beyond.</li>
 * <li>{@link Kind#TOWN}: a street with houses, trees and lamp posts, traffic both ways.</li>
 * <li>{@link Kind#CAMP}: tents in the trees, a parked truck, soldiers on patrol.</li>
 * <li>{@link Kind#SWARM}: flying robots weaving over platforms and walls, clouds drifting.</li>
 * <li>{@link Kind#JUMP}: a runner hopping from platform to platform among spinning coins.</li>
 * </ul>
 * Models: Kenney's CC0 kits and the FPV drone ({@link Raster3D}); the soldiers are {@link Soldier}. The scene is laid out
 * in front of the drone as it stood when the scene was made (its start point and heading); the motion detection's
 * picture gets it without the screen effects. {@link #addSound} mixes what is heard: the quads, the engines.
 */
final class ShowScene implements Scene.World, Scene.Sounding {
    /** What a drone sees. */
    enum Kind { RACE, RALLY, TOWN, CAMP, SWARM, JUMP }

    private final Kind kind;
    /** Where the drone stood and looked when the scene was made: the scene's frame. */
    private final double originN, originE, headingDeg;
    private final SimWorld env = new SimWorld(0, 0);
    private final long epochMs;

    ShowScene(Kind kind, double originN, double originE, double headingDeg) {
        this(kind, originN, originE, headingDeg, System.currentTimeMillis());
    }

    ShowScene(Kind kind, double originN, double originE, double headingDeg, long epochMs) {
        this.kind = kind;
        this.originN = originN;
        this.originE = originE;
        this.headingDeg = headingDeg;
        this.epochMs = epochMs;
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

    /** Something placed in the scene: a mesh, where, how turned and how big. */
    private record Put(Raster3D.Mesh mesh, double ahead, double right, double up, double heading, double pitch, double roll,
                       double scale, int tint, double shadow) {
        Put(Raster3D.Mesh mesh, double ahead, double right, double up, double heading, double scale) {
            this(mesh, ahead, right, up, heading, 0, 0, scale, -1, 0);
        }
    }

    /** A point moving round an ellipse ahead of the drone: where, which way it heads (relative), how hard it turns. */
    record Lap(double ahead, double right, double heading, double turn) {}

    /**
     * On an ellipse centred cA ahead, semi-axes ra (ahead) and rr (right), at angle s (radians, clockwise seen from
     * above from its near side): the point, the way along it (degrees, relative to the drone's heading) and its
     * curvature (1/m, positive turning right).
     */
    static Lap lap(double cA, double ra, double rr, double s) {
        double a = cA - ra * Math.cos(s), r = rr * Math.sin(s);
        double da = ra * Math.sin(s), dr = rr * Math.cos(s);
        double dda = ra * Math.cos(s), ddr = -rr * Math.sin(s);
        double v = Math.hypot(da, dr);
        double cross = dr * dda - da * ddr; // > 0: turning left in (right, ahead) seen from above
        return new Lap(a, r, Math.toDegrees(Math.atan2(dr, da)), -cross / (v * v * v));
    }

    // ------------------------------------------------------------------ the picture

    /** A drone on the ground looks from this high, this much down: as on a mast over its post (the scene in view). */
    static final double MAST_M = 3.2, MAST_PITCH_DEG = -9;

    /** The camera's view: a drone sitting on the ground sees from its mast; one in the air, from where it flies. */
    static Scene.View eye(Scene.View v) {
        if (v.alt() >= 1) return v;
        return new Scene.View(v.north(), v.east(), MAST_M, v.rollDeg(), v.pitchDeg() + MAST_PITCH_DEG, v.headingDeg());
    }

    @Override
    public void render(BufferedImage img, Scene.View drone, long nowMs, boolean analysis) {
        Scene.View v = eye(drone);
        int w = img.getWidth(), h = img.getHeight();
        Scene.Camera cam = new Scene.Camera(v, w, h);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            env.paint(g, cam, v, w, h, nowMs, analysis, false);
        } finally {
            g.dispose();
        }
        double t = (nowMs - epochMs) / 1000.0;
        List<Put> puts = new ArrayList<>();
        place(puts, t);
        Raster3D.Frame f = Raster3D.frame(img);
        for (Put p : puts) {
            if (p.shadow() <= 0) continue;
            double[] q = at(p.ahead(), p.right());
            Raster3D.shadow(f, cam, q[0], q[1], p.shadow(), Math.max(0.15, 0.5 - p.up() * 0.06));
        }
        for (Put p : puts) {
            double[] q = at(p.ahead(), p.right());
            Raster3D.draw(f, cam, p.mesh(), q[0], q[1], p.up(), headingDeg + p.heading(), p.pitch(), p.roll(), p.scale(), p.tint());
        }
        if (kind == Kind.CAMP) soldiers(img, cam, t);
        if (!analysis) SimWorld.effects(img, nowMs);
    }

    private void place(List<Put> out, double t) {
        switch (kind) {
            case RACE -> race(out, t);
            case RALLY -> rally(out, t);
            case TOWN -> town(out, t);
            case CAMP -> camp(out, t);
            case SWARM -> swarm(out, t);
            case JUMP -> jump(out, t);
        }
    }

    // ---- RACE: four quads through four gates

    static final double RACE_CENTRE = 7, RACE_RA = 3.2, RACE_RR = 5.5, RACE_ALT = 1.5;
    /** Each quad's speed (m/s), start on the lap (radians) and lane (m, out from the middle). */
    private static final double[][] RACERS = {{11.0, 0.0, 0.0}, {11.6, 0.55, 0.35}, {10.5, 1.25, -0.3}, {12.2, 2.2, 0.15}};
    private static final int[] RACER_TINT = {0xe83a2f, 0x2f8fe8, 0xf2c230, 0x3ccf5a};
    private static final double QUAD_SCALE = 3.0;
    private static Raster3D.Mesh gate;

    private static synchronized Raster3D.Mesh gate() {
        if (gate == null) {
            int orange = 0xff7a1a, white = 0xf0f0f0;
            gate = Raster3D.boxes(new double[][]{
                    {-0.06, 0.95, 0, 0.06, 1.1, 2.6}, {-0.06, -1.1, 0, 0.06, -0.95, 2.6},
                    {-0.06, -1.1, 2.45, 0.06, 1.1, 2.6}, {-0.06, -1.1, 0.75, 0.06, 1.1, 0.88}},
                    new int[]{orange, orange, white, white});
        }
        return gate;
    }

    /** How far round the race loop (radians) a quad is at t. */
    static double raceAngle(int i, double t) {
        double perimeter = 2 * Math.PI * Math.sqrt((RACE_RA * RACE_RA + RACE_RR * RACE_RR) / 2);
        return RACERS[i][1] + 2 * Math.PI * RACERS[i][0] * t / perimeter;
    }

    private void race(List<Put> out, double t) {
        for (int k = 1; k < 4; k++) { // none on the near side: seen edge on, it stood in the middle of the picture
            Lap l = lap(RACE_CENTRE, RACE_RA, RACE_RR, k * Math.PI / 2);
            out.add(new Put(gate(), l.ahead(), l.right(), 0, l.heading(), 1));
        }
        out.add(new Put(Raster3D.prop("flag"), RACE_CENTRE + RACE_RA + 2, 0, 0, 90, 1.2));
        Raster3D.Mesh quad = Raster3D.fpvDrone();
        for (int i = 0; i < RACERS.length; i++) {
            double s = raceAngle(i, t);
            Lap l = lap(RACE_CENTRE, RACE_RA + RACERS[i][2], RACE_RR + RACERS[i][2], s);
            double speed = RACERS[i][0];
            double roll = Math.toDegrees(Math.atan(speed * speed * l.turn() / 9.81));
            roll = Math.max(-60, Math.min(60, roll));
            double alt = RACE_ALT + 0.35 * Math.sin(2 * s + i);
            out.add(new Put(quad, l.ahead(), l.right(), alt, l.heading(), -16, roll, QUAD_SCALE, RACER_TINT[i], 0.45));
        }
    }

    // ---- RALLY: trucks and a motorcycle round an oval

    private static final String[] RALLY_CARS = {"vehicle-truck-red", "vehicle-truck-yellow", "vehicle-truck-green", "vehicle-truck-purple", "vehicle-motorcycle"};
    private static final double[][] RALLY = {{8.0, 0.0}, {7.4, 1.2}, {8.6, 2.5}, {7.0, 3.6}, {9.5, 4.8}};
    static final double RALLY_CENTRE = 15, RALLY_RA = 5, RALLY_RR = 11;

    private void rally(List<Put> out, double t) {
        out.add(new Put(Raster3D.prop("decoration-forest"), 28, -12, 0, 0, 1.4));
        out.add(new Put(Raster3D.prop("decoration-tents"), 29, 9, 0, 30, 1.2));
        out.add(new Put(Raster3D.prop("decoration-forest"), 31, 2, 0, 90, 1.4));
        for (int i = 0; i < RALLY_CARS.length; i++) {
            double perimeter = 2 * Math.PI * Math.sqrt((RALLY_RA * RALLY_RA + RALLY_RR * RALLY_RR) / 2);
            double s = RALLY[i][1] + 2 * Math.PI * RALLY[i][0] * t / perimeter;
            Lap l = lap(RALLY_CENTRE, RALLY_RA, RALLY_RR, s);
            boolean bike = i == RALLY_CARS.length - 1;
            double roll = bike ? Math.max(-35, Math.min(35, Math.toDegrees(Math.atan(RALLY[i][0] * RALLY[i][0] * l.turn() / 9.81)))) : 0;
            double bounce = bike ? 0 : 0.03 * Math.abs(Math.sin(t * 9 + i * 1.7));
            out.add(new Put(Raster3D.prop(RALLY_CARS[i]), l.ahead(), l.right(), bounce, l.heading(), 0, roll, 1.3, -1, 0));
        }
    }

    // ---- TOWN: a street, traffic both ways

    static final double TOWN_ROAD = 13, TILE = 3;

    private void town(List<Put> out, double t) {
        Raster3D.Mesh road = Raster3D.prop("road-straight"), posts = Raster3D.prop("road-straight-lightposts");
        String[] houses = {"building-small-a", "building-small-b", "building-garage", "building-small-c", "building-small-d", "building-small-b", "building-small-a", "building-small-c"};
        for (int k = -6; k <= 6; k++) {
            boolean cross = k == 0;
            out.add(new Put(cross ? Raster3D.prop("road-intersection") : k % 2 == 0 ? posts : road, TOWN_ROAD, k * TILE, 0, 90, TILE));
            if (cross) continue;
            String house = houses[Math.floorMod(k * 3, houses.length)];
            out.add(new Put(Raster3D.prop(house), TOWN_ROAD + TILE, k * TILE, 0, 180, TILE));
            if (Math.floorMod(k, 3) == 1) out.add(new Put(Raster3D.prop("grass-trees-tall"), TOWN_ROAD - TILE, k * TILE, 0, 0, TILE));
        }
        for (int k = 1; k <= 3; k++) out.add(new Put(Raster3D.prop("road-straight"), TOWN_ROAD + k * TILE, 0, 0, 0, TILE));
        out.add(new Put(Raster3D.prop("pavement-fountain"), TOWN_ROAD - TILE, 0, 0, 0, TILE));
        // traffic: far lane to the right, near lane to the left, round again off the street's ends
        double span = 2 * 6.5 * TILE;
        double[][] cars = {{7.5, 0, 0.6}, {6.0, 0.45, 0.6}, {8.5, 0.15, -0.6}, {6.8, 0.7, -0.6}};
        String[] which = {"vehicle-truck-red", "vehicle-motorcycle", "vehicle-truck-yellow", "vehicle-truck-purple"};
        for (int i = 0; i < cars.length; i++) {
            double lane = cars[i][2];
            double pos = Math.floorMod((long) Math.round((cars[i][0] * t + cars[i][1] * span) * 1000), (long) (span * 1000)) / 1000.0 - span / 2;
            double right = lane > 0 ? pos : -pos;
            out.add(new Put(Raster3D.prop(which[i]), TOWN_ROAD + lane, right, 0, lane > 0 ? 90 : -90, 0, 0, 0.9, -1, 0));
        }
    }

    // ---- CAMP: tents in the trees, a truck, soldiers on patrol

    private void camp(List<Put> out, double t) {
        out.add(new Put(Raster3D.prop("decoration-tents"), 19, -3, 0, 15, 1.3));
        out.add(new Put(Raster3D.prop("decoration-forest"), 25, 10, 0, 0, 1.5));
        out.add(new Put(Raster3D.prop("decoration-forest"), 26, -15, 0, 60, 1.5));
        out.add(new Put(Raster3D.prop("vehicle-truck-green"), 14, 7, 0, -60, 1.5));
        out.add(new Put(Raster3D.prop("flag"), 13, -9, 0, 90, 1.8));
    }

    /** The patrol: three soldiers on a loop in front of the tents. */
    static final double[][] PATROL = {{9, -7}, {13, -7}, {13, 5}, {9, 5}};
    static final double WALK_MS = 1.3;

    private void soldiers(BufferedImage img, Scene.Camera cam, double t) {
        double loop = 0;
        for (int i = 0; i < PATROL.length; i++) {
            double[] a = PATROL[i], b = PATROL[(i + 1) % PATROL.length];
            loop += Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // far ones first: they are drawn over each other, not through the depth buffer
            List<double[]> men = new ArrayList<>();
            for (int k = 0; k < 3; k++) {
                double d = Math.floorMod((long) ((WALK_MS * t + k * 2.6) * 1000), (long) (loop * 1000)) / 1000.0;
                for (int i = 0; i < PATROL.length; i++) {
                    double[] a = PATROL[i], b = PATROL[(i + 1) % PATROL.length];
                    double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
                    if (d <= len) {
                        double f = d / len;
                        double ahead = a[0] + (b[0] - a[0]) * f, right = a[1] + (b[1] - a[1]) * f;
                        double dir = Math.toDegrees(Math.atan2(b[1] - a[1], b[0] - a[0]));
                        men.add(new double[]{ahead, right, dir, WALK_MS * t + k});
                        break;
                    }
                    d -= len;
                }
            }
            men.sort((x, y) -> Double.compare(y[0], x[0]));
            for (double[] m : men) {
                double[] q = at(m[0], m[1]);
                Soldier.paint(g, cam, q[0], q[1], headingDeg + m[2], m[3], false);
            }
        } finally {
            g.dispose();
        }
    }

    // ---- SWARM: flying robots over platforms

    private void swarm(List<Put> out, double t) {
        out.add(new Put(Raster3D.prop("platform-large-grass"), 12, -6, 0, 0, 1));
        out.add(new Put(Raster3D.prop("platform-large-grass"), 16, 5, 0, 20, 1));
        out.add(new Put(Raster3D.prop("wall-high"), 18, -1, 0, 0, 1.6));
        out.add(new Put(Raster3D.prop("wall-high"), 18, 1.4, 0, 0, 1.6));
        out.add(new Put(Raster3D.prop("wall-high"), 10, 9, 0, 70, 1.6));
        Raster3D.Mesh cloud = Raster3D.prop("cloud");
        for (int k = 0; k < 4; k++) {
            double drift = Math.floorMod((long) ((0.8 * t + k * 22) * 1000), 90_000L) / 1000.0 - 45;
            out.add(new Put(cloud, 55 + k * 9, drift, 14 + 3 * k, 0, 0, 0, 7 + k, -1, 0));
        }
        Raster3D.Mesh robot = Raster3D.prop("enemy-flying");
        for (int i = 0; i < 5; i++) {
            // a figure of eight ahead, each one on it at its own place and height
            double s = 0.55 * t + i * 2 * Math.PI / 5;
            double ahead = 11 + 4 * Math.sin(2 * s) / 2 + 1.5 * i % 3, right = 8 * Math.sin(s);
            double dA = 4 * Math.cos(2 * s), dR = 8 * Math.cos(s);
            double up = 1.6 + 0.9 * i % 2.4 + 0.25 * Math.sin(t * 2.3 + i);
            double heading = Math.toDegrees(Math.atan2(dR, dA));
            out.add(new Put(robot, ahead, right, up, heading, 0, 12 * Math.sin(s + 1), 1.4, -1, 0.7));
        }
    }

    // ---- JUMP: a runner hopping from platform to platform

    /** The platforms: ahead, right, height (m). */
    static final double[][] PLATFORMS = {{8, -6, 0}, {11, -2.5, 0.8}, {13, 1.5, 1.6}, {11, 5.5, 0.8}, {8, 8, 0}, {6, 2, 0.4}};
    static final double HOP_S = 1.1;

    private void jump(List<Put> out, double t) {
        Raster3D.Mesh pad = Raster3D.prop("platform-medium"), round = Raster3D.prop("platform-grass-large-round");
        for (int i = 0; i < PLATFORMS.length; i++) {
            double[] p = PLATFORMS[i];
            double size = i % 2 == 0 ? 0.6 : 1.0;
            // a pillar under a raised one, its top at the platform's height
            out.add(new Put(i % 2 == 0 ? round : pad, p[0], p[1], p[2] - 0.5 * size, 0, 0, 0, size, -1, 0));
            // a coin over each, spinning
            out.add(new Put(Raster3D.prop("coin"), p[0], p[1], p[2] + 2.4 + 0.12 * Math.sin(t * 3 + i), t * 200 + i * 40, 1.3));
        }
        out.add(new Put(Raster3D.prop("flag"), PLATFORMS[2][0] + 0.5, PLATFORMS[2][1], PLATFORMS[2][2], 90, 1.5));
        out.add(new Put(Raster3D.prop("block-coin"), 15, -5, 2.5 + 0.2 * Math.sin(t * 1.7), 0, 1.2));
        out.add(new Put(Raster3D.prop("block-coin"), 15, 7, 3.0 + 0.2 * Math.sin(t * 1.3), 0, 1.2));
        int n = PLATFORMS.length;
        double hop = t / HOP_S;
        int from = Math.floorMod((int) Math.floor(hop), n);
        double f = hop - Math.floor(hop);
        double[] a = PLATFORMS[from], b = PLATFORMS[(from + 1) % n];
        double ahead = a[0] + (b[0] - a[0]) * f, right = a[1] + (b[1] - a[1]) * f;
        double up = a[2] + (b[2] - a[2]) * f + 1.6 * 4 * f * (1 - f);
        double heading = Math.toDegrees(Math.atan2(b[1] - a[1], b[0] - a[0]));
        out.add(new Put(Raster3D.prop("character"), ahead, right, up, heading, 0, 0, 1.8, -1, 0.5));
    }

    // ------------------------------------------------------------------ the sound

    private SoundSample.Voice[] voices;
    private double[] gainL, gainR;

    @Override
    public void addSound(float[] left, float[] right, Scene.View v, long nowMs) {
        double t = (nowMs - epochMs) / 1000.0;
        List<double[]> sources = new ArrayList<>(); // ahead, right, loudness, pitch
        switch (kind) {
            case RACE -> {
                for (int i = 0; i < RACERS.length; i++) {
                    Lap l = lap(RACE_CENTRE, RACE_RA + RACERS[i][2], RACE_RR + RACERS[i][2], raceAngle(i, t));
                    sources.add(new double[]{l.ahead(), l.right(), 0.55, 0.95 + 0.04 * i});
                }
            }
            case RALLY -> {
                double perimeter = 2 * Math.PI * Math.sqrt((RALLY_RA * RALLY_RA + RALLY_RR * RALLY_RR) / 2);
                for (int i = 0; i < RALLY.length; i++) {
                    Lap l = lap(RALLY_CENTRE, RALLY_RA, RALLY_RR, RALLY[i][1] + 2 * Math.PI * RALLY[i][0] * t / perimeter);
                    sources.add(new double[]{l.ahead(), l.right(), 0.9, i == RALLY.length - 1 ? 1.7 : 1.25 + 0.05 * i});
                }
            }
            case TOWN -> sources.add(new double[]{TOWN_ROAD, 0, 0.6, 1.2});
            case CAMP -> sources.add(new double[]{14, 7, 0.5, 0.9});
            case SWARM -> {
                for (int i = 0; i < 5; i++) sources.add(new double[]{11, 8 * Math.sin(0.55 * t + i * 2 * Math.PI / 5), 0.35, 0.62 + 0.03 * i});
            }
            case JUMP -> {
                return;
            }
        }
        if (voices == null || voices.length != sources.size()) {
            voices = new SoundSample.Voice[sources.size()];
            gainL = new double[sources.size()];
            gainR = new double[sources.size()];
            String sample = kind == Kind.RACE || kind == Kind.SWARM ? "fpv-flight" : kind == Kind.CAMP ? "btr-idle" : "btr-drive";
            for (int i = 0; i < voices.length; i++) {
                voices[i] = new SoundSample.Voice(SoundSample.of(sample), true);
                for (int skip = 0; skip < 4000 * (i + 1); skip++) voices[i].next(1); // not all in step
            }
        }
        double h = Math.toRadians(v.headingDeg());
        int n = left.length;
        for (int k = 0; k < sources.size(); k++) {
            double[] s = sources.get(k);
            double[] q = at(s[0], s[1]);
            double dn = q[0] - v.north(), de = q[1] - v.east();
            double dist = Math.max(2, Math.hypot(dn, de));
            double bearing = Math.atan2(de, dn) - h;
            double level = Math.min(1, 6 / dist) * s[2] * 0.5;
            double pan = Math.sin(bearing), angle = (pan + 1) * Math.PI / 4;
            double toL = level * Math.cos(angle), toR = level * Math.sin(angle);
            for (int i = 0; i < n; i++) {
                double f = (double) i / n;
                float x = voices[k].next(s[3]);
                left[i] = clamp(left[i] + (float) (x * (gainL[k] + (toL - gainL[k]) * f)));
                right[i] = clamp(right[i] + (float) (x * (gainR[k] + (toR - gainR[k]) * f)));
            }
            gainL[k] = toL;
            gainR[k] = toR;
        }
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}
