package de.droiddrone.pc.sitl;

import de.droiddrone.common.group.ExitPlan;
import de.droiddrone.common.group.GeoLocal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The whole fleet flow against real ArduPilot Copter (five SITL instances, 20 to 24), through the window's own
 * controls: four drones marked and DISPATCHed - each holds at its slot; the pilot TAKEs CONTROL of one and makes the
 * other three a FOLLOW GROUP; DISCARD LEAD on the radio (the pilot flies the new lead down and out); CALL RESERVE on the
 * radio (a fifth drone climbs from the ground and joins); one follower's link cut (it holds 2 min, then flies its exit
 * plan home); then the PC gone for everyone (every follower out along its lane, the lead home over them). Separation
 * measured all along (never inside 10 m sideways AND 10 m in height); every drone must end landed at its own home or
 * rally point. Real time, about 20 minutes. gradlew fleetSitl.
 */
@Tag("ardusitl")
class SitlFlowTest {
    static final double LAT = 59.91, LON = 10.72, ALT = 100;
    static final int FIRST = 20;
    private final List<ArduSitl> sitls = new ArrayList<>();
    private final Map<String, SitlFleetPhone> phones = new LinkedHashMap<>();
    private final Map<String, double[]> homes = new HashMap<>();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private de.droiddrone.pc.gui.FleetOpsAccess ops;
    private double worst = Double.MAX_VALUE;
    private String worstAt = "";

    @AfterEach
    void close() {
        if (ops != null) ops.close();
        for (SitlFleetPhone p : phones.values()) p.close();
        for (ArduSitl s : sitls) s.close();
    }

    private SitlFleetPhone drone(String name, double e, double n) throws Exception {
        double[] at = GeoLocal.offset(LAT, LON, e, n);
        ArduSitl s = ArduSitl.start(FIRST + sitls.size(), at[0], at[1], ALT, 1);
        sitls.add(s);
        s.awaitReady(120_000);
        assertEquals(0, new de.droiddrone.pc.setup.ardu.ArduTools(s.fc).simpleAccelCal(), "accelerometer");
        SitlFleetPhone p = new SitlFleetPhone(s, "DD");
        phones.put(name, p);
        homes.put(name, at);
        return p;
    }

    /** Waits, the PC's tick running and the separation measured, until ok (fails after ms). */
    private void fly(BooleanSupplier ok, long ms, String what) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            ops.tick();
            measure(what);
            if (ok.getAsBoolean()) return;
            Thread.sleep(250);
        }
        fail("waited " + ms / 1000 + " s for: " + what + " (" + where() + ") " + log);
    }

    private void measure(String phase) {
        List<String> names = new ArrayList<>(phones.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                double[] a = phones.get(names.get(i)).fc.position(), b = phones.get(names.get(j)).fc.position();
                if (a == null || b == null || a[3] < 3 || b[3] < 3) continue; // one on the ground (or taking off from it)
                double m = Math.max(GeoLocal.distance(a[0], a[1], b[0], b[1]) - 10, Math.abs(a[2] - b[2]) - 9.5);
                if (m < worst) {
                    worst = m;
                    worstAt = names.get(i) + "-" + names.get(j) + " in " + phase + " at " + where();
                }
            }
        }
    }

    private String where() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, SitlFleetPhone> e : phones.entrySet()) {
            double[] p = e.getValue().fc.position();
            sb.append(e.getKey()).append(p == null ? " ?" : String.format(java.util.Locale.ROOT, " %.0f/%.0f/%.0f m%d",
                    GeoLocal.east(LAT, LON, p[0], p[1]), GeoLocal.north(LAT, LON, p[0], p[1]), p[3], e.getValue().fc.customMode()))
                    .append("; ");
        }
        return sb.toString();
    }

    private boolean down(SitlFleetPhone p) {
        double[] pos = p.fc.position();
        return pos != null && pos[3] < 1.5 && !p.fc.armedFlag();
    }

    @Test
    void dispatchFollowDiscardCallCutAndHome() throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        String[] sent = {"D1", "D2", "D3", "D4"};
        for (int i = 0; i < sent.length; i++) drone(sent[i], i * 25, 0);
        SitlFleetPhone reserve = drone("R1", 40, 120);
        ops = new de.droiddrone.pc.gui.FleetOpsAccess(null, n -> phones.get(n) == null ? null : phones.get(n).address(), log, (lat, lon) -> ALT);
        for (ArduSitl s : sitls) s.awaitPreArm(120_000);

        // FD-3: dispatched, each to its slot and layer
        for (String n : sent) ops.click(n, "SAFE TO AUTOLAUNCH: mark");
        Thread.sleep(2500);
        double[] target = GeoLocal.offset(LAT, LON, 40, 300);
        ops.dispatch(target[0], target[1]);
        fly(() -> phones.entrySet().stream().filter(e -> !e.getKey().equals("R1")).allMatch(e -> holding(e.getValue(), target)),
                300_000, "all four at their slots");
        assertTrue(String.valueOf(ops.stateOf("D1")).startsWith("YOUR TURN"), "FLEET says YOUR TURN: " + ops.stateOf("D1"));

        // FD-4 / FL-1: the pilot takes D1; the others follow it
        ops.click("D1", "TAKE CONTROL");
        assertEquals("D1", ops.flown());
        phones.get("D1").setPiloted(true);
        for (String f : List.of("D2", "D3", "D4")) ops.click(f, "FOLLOW GROUP: add");
        ops.click("D2", "FOLLOW GROUP: start");
        fly(ops::grouped, 15_000, "the group formed");
        long settle = System.currentTimeMillis() + 60_000;
        fly(() -> System.currentTimeMillis() > settle, 70_000, "the group forming");
        ops.click("R1", "SAFE TO AUTOLAUNCH: mark");

        // FL-6: DISCARD LEAD on the radio; the pilot flies the new lead down into the abort layer and out
        ops.flip(5);
        String lead = ops.flown();
        assertNotEquals("D1", lead, "a new lead: " + log);
        phones.get("D1").setPiloted(false);
        SitlFleetPhone nl = phones.get(lead);
        nl.setPiloted(true);
        double[] at = nl.fc.position();
        nl.pilotTo(at[0], at[1], at[2] - 9);
        Thread.sleep(8000);
        double[] c = groupCentre();
        double e = GeoLocal.east(c[0], c[1], at[0], at[1]), n = GeoLocal.north(c[0], c[1], at[0], at[1]), len = Math.max(1, Math.hypot(e, n));
        double[] out = GeoLocal.offset(c[0], c[1], e / len * 150, n / len * 150);
        nl.pilotTo(out[0], out[1], at[2] - 9);
        fly(() -> ops.memberState(lead) == null, 90_000, lead + " has left the group");

        // AL-5: CALL RESERVE on the radio - R1 climbs from the ground and joins
        ops.flip(6);
        fly(() -> reserve.isFollower() && ops.memberState("R1") == de.droiddrone.pc.air.GroupController.State.IN_CELL, 240_000,
                "R1 in its cell");

        // AC-37: one follower's link cut - it holds 2 min, then flies its exit plan home and lands
        String cut = List.of("D2", "D3", "D4").stream().filter(d -> !d.equals(lead)).findFirst().orElseThrow();
        SitlFleetPhone cp = phones.get(cut);
        ExitPlan cutPlan = cp.plan();
        assertNotNull(cutPlan, cut + " holds an exit plan");
        cp.linkUp = false;
        fly(() -> down(cp), 480_000, cut + " home by its exit plan");
        assertLandedAtItsOwn(cut, cutPlan);

        // AC-37: the PC gone - every follower out along its lane after 2 min; the lead home over them after 20 s
        Map<String, ExitPlan> plans = new HashMap<>();
        for (Map.Entry<String, SitlFleetPhone> p : phones.entrySet()) {
            if (p.getKey().equals(lead) || p.getKey().equals("D1") || down(p.getValue())) continue;
            plans.put(p.getKey(), p.getValue().plan());
            p.getValue().linkUp = false;
        }
        long goneAt = System.currentTimeMillis();
        double leadAlt = Double.isNaN(nl.leadRtlAltM) ? 80 : nl.leadRtlAltM;
        // the lead holds (its phone's brake; here GUIDED holds it where it is, SITL's own radio being at low throttle)
        fly(() -> System.currentTimeMillis() - goneAt > 20_000, 30_000, "the lead holding");
        nl.rtl(leadAlt);
        fly(() -> plans.keySet().stream().allMatch(d -> down(phones.get(d))) && down(nl), 600_000, "everyone home");
        for (Map.Entry<String, ExitPlan> p : plans.entrySet()) assertLandedAtItsOwn(p.getKey(), p.getValue());
        assertLandedAtItsOwn(lead, null);

        // the old lead last, by RTL at its own layer
        SitlFleetPhone d1 = phones.get("D1");
        d1.rtl(d1.fc.position()[3]);
        fly(() -> down(d1), 300_000, "D1 home");
        assertLandedAtItsOwn("D1", null);
        assertTrue(worst > 0, "never two inside 10 m sideways and 10 m in height: " + worst + " (" + worstAt + ")");
    }

    private double[] groupCentre() {
        List<double[]> ps = new ArrayList<>();
        for (Map.Entry<String, SitlFleetPhone> e : phones.entrySet()) {
            if (ops.memberState(e.getKey()) != null) ps.add(e.getValue().fc.position());
        }
        double lat = 0, lon = 0;
        for (double[] p : ps) {
            lat += p[0] / ps.size();
            lon += p[1] / ps.size();
        }
        return new double[]{lat, lon};
    }

    /** Down within 25 m of its own home, its own rally point (AC-10) or its exit plan's rally point. */
    private void assertLandedAtItsOwn(String d, ExitPlan plan) {
        SitlFleetPhone p = phones.get(d);
        double[] pos = p.fc.position();
        assertTrue(down(p), d + " landed: " + where());
        List<double[]> own = new ArrayList<>();
        own.add(homes.get(d));
        if (plan != null) own.add(new double[]{plan.rallyLat, plan.rallyLon});
        List<de.droiddrone.common.MissionItem> r = p.rallyRead;
        if (r != null) for (de.droiddrone.common.MissionItem i : r) own.add(new double[]{i.x / 1e7, i.y / 1e7});
        double best = Double.MAX_VALUE;
        for (double[] o : own) best = Math.min(best, GeoLocal.distance(o[0], o[1], pos[0], pos[1]));
        assertTrue(best < 25, d + " landed " + Math.round(best) + " m from its own home or rally point: " + where());
    }

    /** In AUTO, within 80 m of the target, standing still: at its LOITER_UNLIM. */
    private static boolean holding(SitlFleetPhone p, double[] target) {
        double[] pos = p.fc.position();
        if (pos == null || p.fc.customMode() != 3) return false;
        return GeoLocal.distance(target[0], target[1], pos[0], pos[1]) < 80 && Math.hypot(pos[5], pos[6]) < 0.5;
    }
}
