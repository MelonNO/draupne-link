package de.droiddrone.pc.fleet;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * What stands round the BTR's patrol in the tour's finale (2026-11-10, the pilot: "have some more stuff around in it,
 * but keep it realistic military"): treelines behind the route and along its sides, a truck under a camouflage net at
 * the far treeline with its stores, a sandbagged post with an antenna mast and a radio, obstacles (hedgehogs, concrete
 * barriers, wire), a UAZ parked behind the drone. The tour's own models ({@link Raster3D}), lit by the sun, with their
 * shadows, on the simulation's floor.
 * <p>
 * Kept clear of the vehicle's road ({@link #ROAD_CLEAR_M}), and nothing tall between the watching drone and the route:
 * in the drone's picture the vehicle is never hidden (the overwatch alert and its box must find it). Near things stand
 * outside that picture, to the sides and behind - seen when the pilot flies after it.
 */
final class BtrSurroundings {
    /** Nothing this near the vehicle's road (m). */
    static final double ROAD_CLEAR_M = 9;
    /** How things fade: as the vehicle (BtrScene's 1/450 m), into the finale's haze. */
    private static final float[] FOG = Raster3D.fogTable(450);

    /** A thing placed: where (north, east, up), how turned and how big, its shadow. */
    record Prop(Raster3D.Mesh mesh, double n, double e, double up, double heading, double scale, int shadow) {}

    static final int NONE = 0, CAST = 1, CROWN = 2;

    private static final String[] TREES = {"spruce-0", "spruce-1", "pine-0", "pine-1", "birch-0", "birch-1"};
    private static final double[] TREE_H = {12.09, 12.09, 5.34, 5.34, 8.18, 8.18};
    private static final double[][] TREE_RANGE = {{11, 17}, {11, 17}, {13, 19}, {13, 19}, {9, 14}, {9, 14}};

    private final List<Prop> props = new ArrayList<>();
    private final double originN, originE, fwdN, fwdE, rightN, rightE, headingDeg;
    private final double[] roadN, roadE;

    /**
     * @param north east the drone's start, headingDeg where it looks (the vehicle's frame); roadN, roadE its road (every
     *              half metre, north and east)
     */
    BtrSurroundings(double north, double east, double headingDeg, double[] roadN, double[] roadE) {
        this.originN = north;
        this.originE = east;
        this.headingDeg = headingDeg;
        double h = Math.toRadians(headingDeg);
        fwdN = Math.cos(h);
        fwdE = Math.sin(h);
        rightN = -Math.sin(h);
        rightE = Math.cos(h);
        // every 8th point of the road is enough to keep clear of it (4 m apart, ROAD_CLEAR_M round each)
        int n = (roadN.length + 7) / 8;
        this.roadN = new double[n];
        this.roadE = new double[n];
        for (int i = 0; i < n; i++) {
            this.roadN[i] = roadN[i * 8];
            this.roadE[i] = roadE[i * 8];
        }
        Random r = new Random(1945);
        // the far treeline behind the route, in stands with gaps between them
        for (double s = -190; s <= 190; s += 26 + r.nextDouble() * 18) stand(r, 128 + r.nextDouble() * 30, s, 6 + r.nextInt(7), 11, 0.25);
        // the treelines along its sides, out of the drone's picture
        for (double f = -40; f <= 140; f += 22 + r.nextDouble() * 14) {
            stand(r, f, -118 - r.nextDouble() * 20, 4 + r.nextInt(5), 9, 0.3);
            stand(r, f, 118 + r.nextDouble() * 20, 4 + r.nextInt(5), 9, 0.3);
        }
        // and behind the drone
        for (double s = -110; s <= 110; s += 30 + r.nextDouble() * 15) stand(r, -70 - r.nextDouble() * 20, s, 4 + r.nextInt(5), 9, 0.2);
        truckUnderNet(121, -64);
        post(26, 58);
        obstacles(r);
        // a UAZ parked behind the drone, its stores by it
        put("uaz", -14, 6, 0, 160, 1, CAST);
        put("jerrycan", -12.2, 3.6, 0, 20, 1, CAST);
        put("jerrycan", -12.0, 3.2, 0, 35, 1, CAST);
        put("crate", -13.0, 2.6, 0, 70, 1, CAST);
    }

    /** A stand of trees round (f, s), about count of them within radius m; mostly conifers, some birch. */
    private void stand(Random r, double f, double s, int count, double radius, double birch) {
        for (int i = 0; i < count; i++) {
            double a = r.nextDouble() * 2 * Math.PI, d = Math.sqrt(r.nextDouble()) * radius;
            int k = r.nextDouble() < birch ? 4 + r.nextInt(2) : r.nextInt(4);
            double hgt = TREE_RANGE[k][0] + r.nextDouble() * (TREE_RANGE[k][1] - TREE_RANGE[k][0]);
            put(TREES[k], f + Math.cos(a) * d, s + Math.sin(a) * d, 0, 0, hgt / TREE_H[k], CROWN);
        }
    }

    /** A Ural under a large net at the treeline, crates and fuel by it. */
    private void truckUnderNet(double f, double s) {
        put("ural", f, s, 0, 75, 1, CAST);
        put("net-large", f, s, 0, 75, 1, CAST);
        put("crate", f - 5.5, s + 2.0, 0, 30, 1, CAST);
        put("crate", f - 5.8, s + 2.8, 0, 50, 1, CAST);
        put("crate", f - 5.6, s + 2.4, 0.46, 40, 1, NONE);
        put("ammo-box", f - 4.6, s + 4.0, 0, 70, 1.4, CAST);
        put("jerrycan", f - 4.0, s - 2.6, 0, 15, 1, CAST);
        put("jerrycan", f - 4.3, s - 2.9, 0, 30, 1, CAST);
    }

    /** A sandbagged post: a ring of bags, a small net over it, an antenna mast, a radio and ammunition. */
    private void post(double f, double s) {
        int[] bags = {0, 40, 80, 120, 160, 200, 240, 280};
        for (int b : bags) {
            double a = Math.toRadians(b);
            put("sandbag", f + Math.cos(a) * 2.2, s + Math.sin(a) * 2.2, 0, b + 90, 1.6, CAST);
            put("sandbag", f + Math.cos(a + 0.35) * 2.2, s + Math.sin(a + 0.35) * 2.2, 0.16, b + 110, 1.6, NONE);
        }
        put("net-small", f + 1.0, s + 3.5, 0, 20, 1, CAST);
        put("mast", f + 3.5, s - 3.0, 0, 0, 1, NONE);
        put("radio", f, s + 0.6, 0, 250, 1, NONE);
        put("ammo-box", f - 0.8, s - 0.4, 0, 30, 1.4, CAST);
        put("ammo-box", f - 0.5, s - 0.8, 0, 60, 1.4, CAST);
    }

    /** Hedgehogs and barriers across the left of the field, wire along the right: out of the drone's picture. */
    private void obstacles(Random r) {
        for (int i = 0; i < 7; i++) put("hedgehog", 22 + i * 4.5, -52 - r.nextDouble() * 3, 0, r.nextDouble() * 90, 1, CAST);
        put("barrier", 18, -46, 0, 90, 1, CAST);
        put("barrier", 18.2, -44, 0, 92, 1, CAST);
        put("barrier", 18.1, -42, 0, 88, 1, CAST);
        for (int i = 0; i < 4; i++) put("wire", 10 + i * 6, 46, 0, 0, 1, NONE);
    }

    /**
     * Puts a model f ahead, s right of the drone's start (m), turned heading from the drone's way (degrees) - unless it
     * would stand on the road or, above knee height, in the drone's picture in front of the route.
     */
    private void put(String model, double f, double s, double up, double heading, double scale, int shadow) {
        double n = originN + f * fwdN + s * rightN, e = originE + f * fwdE + s * rightE;
        for (int i = 0; i < roadN.length; i++) {
            double dn = roadN[i] - n, de = roadE[i] - e;
            if (dn * dn + de * de < ROAD_CLEAR_M * ROAD_CLEAR_M) return;
        }
        Raster3D.Mesh m = Raster3D.model(model);
        if (f > 0 && f < 120 && Math.abs(s) < f * 1.1 && m.height() * scale > 0.4) return; // would hide the route
        props.add(new Prop(m, n, e, up, headingDeg + heading, scale, shadow));
    }

    /** How many things stand round it (tests). */
    int count() {
        return props.size();
    }

    List<Prop> props() {
        return props;
    }

    /**
     * Everything into the picture (its depth buffer filled, for the vehicle drawn after), the nearest first; the
     * shadows first. fog: the finale's, into its haze.
     */
    void draw(Raster3D.Frame f, Scene.Camera cam, int haze) {
        f.fog = FOG;
        f.haze = haze;
        List<Prop> near = new ArrayList<>(props);
        near.sort((a, b) -> Double.compare(dist2(cam, a), dist2(cam, b)));
        for (Prop p : near) {
            switch (p.shadow()) {
                case CAST -> Raster3D.castShadow(f, cam, p.mesh(), p.n(), p.e(), p.heading(), p.scale(), p.up() + p.mesh().height() * p.scale());
                case CROWN -> {
                    double hh = p.mesh().height() * p.scale(), l = Math.hypot(Raster3D.SUN[0], Raster3D.SUN[1]);
                    Raster3D.shadow(f, cam, p.n() - Raster3D.SUN[0] / l * 0.45 * hh, p.e() - Raster3D.SUN[1] / l * 0.45 * hh, 0.22 * hh, 0.30);
                }
                default -> {
                }
            }
        }
        for (Prop p : near) Raster3D.draw(f, cam, p.mesh(), p.n(), p.e(), p.up(), p.heading(), 0, 0, p.scale(), -1);
    }

    private static double dist2(Scene.Camera cam, Prop p) {
        double dn = p.n() - cam.cn, de = p.e() - cam.ce;
        return dn * dn + de * de;
    }
}
