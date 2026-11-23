package de.droiddrone.pc.sitl;

import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.common.launch.LaunchSequence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD phases 4 and 5 against real ArduPilot Copter (four SITL instances): a lead and two followers in the air,
 * FOLLOW GROUP through the window's own FleetOps - the followers flown by the phone's shared rules with GUIDED targets;
 * then a reserve on the ground marked SAFE TO AUTOLAUNCH and CALLed - its checks, arming, climb and joining done by
 * the shared LaunchSequence with ArduPilot's own arm and NAV_TAKEOFF. Separation measured all along (never inside 10 m
 * sideways AND 10 m in height). Real time, a few minutes. gradlew fleetSitl.
 */
@Tag("ardusitl")
class SitlFleetTest {
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

    private SitlFleetPhone drone(String name, int n, double e, double north) throws Exception {
        double[] at = GeoLocal.offset(LAT, LON, e, north);
        ArduSitl s = ArduSitl.start(n, at[0], at[1], ALT, 1);
        sitls.add(s);
        s.awaitReady(120_000);
        assertEquals(0, new de.droiddrone.pc.setup.ardu.ArduTools(s.fc).simpleAccelCal(), "accelerometer");
        SitlFleetPhone p = new SitlFleetPhone(s, "DD");
        phones.put(name, p);
        return p;
    }

    private void until(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (ok.getAsBoolean()) return;
            Thread.sleep(200);
        }
        fail("waited " + ms / 1000 + " s for: " + what + " " + log);
    }

    /** The worst margin of any pair of airborne drones against the 10 m / 10 m rule (negative: inside both). */
    private double closest() {
        double worst = Double.MAX_VALUE;
        List<SitlFleetPhone> ps = new ArrayList<>(phones.values());
        for (int i = 0; i < ps.size(); i++) {
            for (int j = i + 1; j < ps.size(); j++) {
                double[] a = ps.get(i).fc.position(), b = ps.get(j).fc.position();
                if (a == null || b == null || a[3] < 3 && b[3] < 3) continue; // both on the ground
                double h = GeoLocal.distance(a[0], a[1], b[0], b[1]), v = Math.abs(a[2] - b[2]);
                worst = Math.min(worst, Math.max(h - 10, v - 9.5));
            }
        }
        return worst;
    }

    @Test
    void aGroupFollowsAndAReserveLaunchesAndJoinsOnRealArduPilot() throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        SitlFleetPhone lead = drone("Lead", 0, 150, 0);
        SitlFleetPhone f1 = drone("F1", 1, -20, -20);
        SitlFleetPhone f2 = drone("F2", 2, 20, 20);
        SitlFleetPhone reserve = drone("R1", 3, -120, 60);
        lead.takeOff(70);
        f1.takeOff(45);
        f2.takeOff(60);
        until(() -> lead.fc.position()[3] > 60 && f1.fc.position()[3] > 40 && f2.fc.position()[3] > 55, 90_000, "all three up");
        ops = new de.droiddrone.pc.gui.FleetOpsAccess("Lead", name -> {
            SitlFleetPhone p = phones.get(name);
            return p == null ? null : p.address();
        }, log);
        ops.click("F1", "FOLLOW GROUP: add");
        ops.click("F2", "FOLLOW GROUP: add");
        ops.click("F2", "FOLLOW GROUP: start");
        until(ops::grouped, 15_000, "the group formed");
        double worst = Double.MAX_VALUE;
        long end = System.currentTimeMillis() + 40_000;
        while (System.currentTimeMillis() < end) {
            worst = Math.min(worst, closest());
            Thread.sleep(200);
        }
        assertTrue(f1.isFollower() && f2.isFollower(), "both follow " + log);
        assertTrue(worst > 0, "apart while forming: " + worst);

        ops.click("R1", "SAFE TO AUTOLAUNCH: mark");
        Thread.sleep(2000);
        ops.callReserve();
        long until = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < until && !reserve.isFollower()) {
            ops.tick();
            worst = Math.min(worst, closest());
            Thread.sleep(250);
        }
        assertTrue(reserve.isFollower(), "the reserve launched and joined: launch " + LaunchSequence.stateWord(reserve.launchState()) + " " + log);
        assertTrue(worst > 0, "apart through the launch: " + worst);
    }
}
