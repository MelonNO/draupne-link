package de.droiddrone.pc.air;

import de.droiddrone.pc.air.virt.VirtualAirspace;
import de.droiddrone.pc.air.virt.VirtualCopter;

import java.util.LinkedHashMap;
import java.util.Map;

/** Test helper: a follower group in the kinematic model, every follower in its cell, the lead outside it. */
final class Groups {
    static final double GROUND = 100; // flat ground, metres above sea level

    private Groups() {
    }

    /** n followers (F01..) with batteries 40..99 %, centre at (0, 0), the lead 100 m north at its own height. */
    static VirtualAirspace group(int n, long seed) {
        return group(n, seed, AirParams.defaults());
    }

    static VirtualAirspace group(int n, long seed, AirParams p) {
        VirtualAirspace a = new VirtualAirspace(p, GROUND, seed);
        a.gc = new GroupController(p, GROUND, a.lat0, a.lon0, VirtualAirspace.prover(p, GROUND));
        Map<String, Double> batt = new LinkedHashMap<>();
        java.util.Random r = new java.util.Random(seed);
        for (int i = 1; i <= n; i++) batt.put(String.format("F%02d", i), 40 + r.nextInt(60) + 0.0);
        a.gc.form("LEAD", batt, V2.ZERO, new V2(-600, 0));
        for (String id : batt.keySet()) {
            double[] at = a.gc.cellPlace(id);
            VirtualCopter c = a.add(id, at[0], at[1], at[2]);
            c.battery = batt.get(id);
            c.homeAmsl = GROUND;
            a.follower(id);
        }
        VirtualCopter lead = a.add("LEAD", 0, 100, GROUND + leadAgl(p, n));
        lead.homeAmsl = GROUND;
        a.leadId = "LEAD";
        return a;
    }

    /** The lead's own layer: two spacings over the cube's top layer. */
    static double leadAgl(AirParams p, int n) {
        return new HollowCube(p).layerAlt(HollowCube.layersFor(n) - 1) + 2 * p.layerM();
    }

    /** A pilot that flies straight to a point and stays there. */
    static VirtualAirspace.Pilot goTo(double e, double n, double u) {
        return (t, lead) -> new double[]{e, n, u};
    }

    /** Every follower's distance from its cell, the largest. */
    static double worstCellError(VirtualAirspace a) {
        double w = 0;
        for (String id : a.gc.members().keySet()) {
            GroupController.Member m = a.gc.members().get(id);
            if (m.state() != GroupController.State.IN_CELL) continue;
            double[] at = a.gc.cellPlace(id);
            VirtualCopter c = a.copters.get(id);
            w = Math.max(w, Math.hypot(c.e - at[0], c.n - at[1]));
        }
        return w;
    }

    static String describe(VirtualAirspace a) {
        var c = a.closest();
        if (c == null) return "nobody in the air";
        return String.format(java.util.Locale.ROOT, "same height: %5.1f m apart | under a radius sideways: %4.1f m apart in height | worst pair %s-%s %.1f / %.1f m at %.0f s",
                a.minSameHeightM(), a.minOverheadM(), c.a(), c.b(), c.horizontalM(), c.verticalM(), a.closestAtS());
    }
}
