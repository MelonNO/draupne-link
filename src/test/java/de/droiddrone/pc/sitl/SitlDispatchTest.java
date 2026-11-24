package de.droiddrone.pc.sitl;

import de.droiddrone.common.group.GeoLocal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD phase 3 against real ArduPilot Copter (three SITL instances): drones on the ground marked SAFE TO
 * AUTOLAUNCH, DISPATCHed through the window's FleetOps to a place 250 m north - each route planned over (flat) ground,
 * uploaded and read back with the phone's MissionUpload, launched by the shared LaunchSequence, started in AUTO; each
 * must end holding at its slot (the mission's LOITER_UNLIM) on a height of its own, never two inside 10 m sideways and
 * 10 m in height. Real time, a few minutes. gradlew fleetSitl.
 */
@Tag("ardusitl")
class SitlDispatchTest {
    static final double LAT = 59.9, LON = 10.7, ALT = 100;
    private final List<ArduSitl> sitls = new ArrayList<>();
    private final Map<String, SitlFleetPhone> phones = new LinkedHashMap<>();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private de.droiddrone.pc.gui.FleetOpsAccess ops;

    @AfterEach
    void close() {
        if (ops != null) ops.close();
        for (SitlFleetPhone p : phones.values()) p.close();
        for (ArduSitl s : sitls) s.close();
    }

    @Test
    void threeDronesDispatchedHoldAtTheirOwnSlotsAndHeights() throws Exception {
        dispatch(3, 4, 240_000);
    }

    /** The PRD's phase 3 gate: 10 drones (instances 40 - 49), none within 10 m on one height layer. */
    @Test
    void tenDronesDispatchedPhase3Gate() throws Exception {
        dispatch(10, 40, 480_000);
    }

    private void dispatch(int count, int firstInstance, long withinMs) throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        String[] names = new String[count];
        for (int i = 0; i < count; i++) names[i] = "D" + (i + 1);
        for (int i = 0; i < names.length; i++) {
            double[] at = GeoLocal.offset(LAT, LON, (i % 5) * 25, (i / 5) * -25);
            ArduSitl s = ArduSitl.start(firstInstance + i, at[0], at[1], ALT, 1);
            sitls.add(s);
            s.awaitReady(120_000);
            assertEquals(0, new de.droiddrone.pc.setup.ardu.ArduTools(s.fc).simpleAccelCal());
            phones.put(names[i], new SitlFleetPhone(s, "DD"));
        }
        ops = new de.droiddrone.pc.gui.FleetOpsAccess(null, n -> phones.get(n) == null ? null : phones.get(n).address(), log, (lat, lon) -> ALT);
        for (ArduSitl s : sitls) s.awaitPreArm(120_000); // settled, as a reserve that has stood a while
        for (String n : names) ops.click(n, "SAFE TO AUTOLAUNCH: mark");
        Thread.sleep(2500);
        double[] target = GeoLocal.offset(LAT, LON, 25, 250);
        ops.dispatch(target[0], target[1]);
        double worst = Double.MAX_VALUE;
        long end = System.currentTimeMillis() + withinMs;
        while (System.currentTimeMillis() < end && !phones.values().stream().allMatch(p -> holding(p, target))) {
            ops.tick();
            worst = Math.min(worst, closest());
            trace();
            Thread.sleep(250);
        }
        for (Map.Entry<String, SitlFleetPhone> e : phones.entrySet()) {
            assertTrue(holding(e.getValue(), target), e.getKey() + " holds at its slot: mode " + e.getValue().fc.customMode()
                    + " at " + java.util.Arrays.toString(e.getValue().fc.position()) + " launch " + e.getValue().launchWhy()
                    + " ArduPilot said " + e.getValue().texts.subList(Math.max(0, e.getValue().texts.size() - 10), e.getValue().texts.size()) + " " + log);
        }
        java.nio.file.Files.write(java.nio.file.Path.of("build", "sitl-dispatch-" + count + ".csv"), traceLines);
        java.nio.file.Files.write(java.nio.file.Path.of("build", "sitl-dispatch-" + count + ".log"), log);
        assertTrue(worst > 0, "never two inside 10 m and 10 m: " + worst + " " + worstAt);
        // AC-10: each had its own rally point on ArduPilot, read back from it, RTL to it (not home)
        List<double[]> rallies = new ArrayList<>();
        for (Map.Entry<String, SitlFleetPhone> e : phones.entrySet()) {
            List<de.droiddrone.common.MissionItem> r = e.getValue().rallyRead;
            assertNotNull(r, e.getKey() + ": its rally point read back from ArduPilot " + log);
            assertEquals(1, r.size());
            assertEquals(0f, e.getValue().rallyInclHome, e.getKey() + ": RALLY_INCL_HOME 0");
            rallies.add(new double[]{r.get(0).x / 1e7, r.get(0).y / 1e7});
        }
        for (int i = 0; i < rallies.size(); i++) {
            for (int j = i + 1; j < rallies.size(); j++) {
                assertTrue(GeoLocal.distance(rallies.get(i)[0], rallies.get(i)[1], rallies.get(j)[0], rallies.get(j)[1])
                        >= de.droiddrone.pc.launch.RallyPlan.MIN_APART_M, "rally points twice the protection radius apart");
            }
        }
        List<Long> heights = new ArrayList<>();
        for (SitlFleetPhone p : phones.values()) heights.add(Math.round(p.fc.position()[2] / 5.0));
        assertEquals(count, heights.stream().distinct().count(), "each its own height: " + heights);
    }

    /** In AUTO, within 80 m of the target, standing still: at its LOITER_UNLIM. */
    private static boolean holding(SitlFleetPhone p, double[] target) {
        double[] pos = p.fc.position();
        if (pos == null || p.fc.customMode() != 3) return false;
        return GeoLocal.distance(target[0], target[1], pos[0], pos[1]) < 80 && Math.hypot(pos[5], pos[6]) < 0.5;
    }

    private final List<String> traceLines = new ArrayList<>(List.of("t,drone,north,east,rel,mode,vn,ve"));

    /** Every drone's place each tick, north/east metres from the first drone's home: build/sitl-dispatch-N.csv. */
    private void trace() {
        long t = (System.currentTimeMillis() - started) / 100;
        for (Map.Entry<String, SitlFleetPhone> e : phones.entrySet()) {
            double[] p = e.getValue().fc.position();
            if (p == null) continue;
            double n = GeoLocal.distance(LAT, LON, p[0], LON) * Math.signum(p[0] - LAT), ea = GeoLocal.distance(LAT, LON, LAT, p[1]) * Math.signum(p[1] - LON);
            traceLines.add(String.format(java.util.Locale.ROOT, "%.1f,%s,%.1f,%.1f,%.1f,%d,%.1f,%.1f", t / 10.0, e.getKey(), n, ea, p[3], e.getValue().fc.customMode(), p[5], p[6]));
        }
    }

    private String worstAt = "";
    private double worstSeen = Double.MAX_VALUE;
    private final long started = System.currentTimeMillis();

    private double closest() {
        double worst = Double.MAX_VALUE;
        List<String> ids = new ArrayList<>(phones.keySet());
        List<SitlFleetPhone> ps = new ArrayList<>(phones.values());
        for (int i = 0; i < ps.size(); i++) {
            for (int j = i + 1; j < ps.size(); j++) {
                double[] a = ps.get(i).fc.position(), b = ps.get(j).fc.position();
                if (a == null || b == null || a[3] < 3 && b[3] < 3) continue;
                double m = Math.max(GeoLocal.distance(a[0], a[1], b[0], b[1]) - 10, Math.abs(a[2] - b[2]) - 9.5);
                if (m < worstSeen) {
                    worstSeen = m;
                    worstAt = ids.get(i) + "/" + ids.get(j) + " at " + (System.currentTimeMillis() - started) / 1000 + " s: " + String.format(java.util.Locale.ROOT,
                            "%.1f m apart, heights %.1f/%.1f, modes %d/%d", GeoLocal.distance(a[0], a[1], b[0], b[1]), a[3], b[3], ps.get(i).fc.customMode(), ps.get(j).fc.customMode());
                }
                worst = Math.min(worst, m);
            }
        }
        return worst;
    }
}
