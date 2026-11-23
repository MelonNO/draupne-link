package de.droiddrone.pc.sitl;

import de.droiddrone.common.group.GeoLocal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD RT-9 / A5 against real ArduPilot Copter (SITL, instances 10 and 11):
 * <ul>
 *   <li>a drone DISPATCHed over a hill the map knows (SITL's own ground is flat): its RTL_ALT on ArduPilot is the
 *   height the PC planned - the hill plus the route height - set by RETURN before it armed; at its slot an RTL climbs to
 *   it before it comes home;</li>
 *   <li>Drone setup's FS_EKF_ACTION is taken; with GNSS gone in flight ArduPilot holds its height (ALT_HOLD), it
 *   doesn't land.</li>
 * </ul>
 * Real time, a few minutes. gradlew fleetSitl.
 */
@Tag("ardusitl")
class SitlReturnTest {
    static final double LAT = 59.9, LON = 10.7, ALT = 100, HILL = 30;
    private ArduSitl sitl;
    private SitlFleetPhone phone;
    private de.droiddrone.pc.gui.FleetOpsAccess ops;
    private final List<String> log = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        if (ops != null) ops.close();
        if (phone != null) phone.close();
        if (sitl != null) sitl.close();
    }

    @Test
    void aDispatchedDronesRtlAltIsThePlannedOneAndAnRtlClimbsToIt() throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        // ArduPilot SITL now and then refuses the arm ("GPS 1: not healthy" for a moment, a host under load): the launch then
        // ends NOT LAUNCHED, as on a real drone; the whole run is tried again, three times at most
        double[] target = GeoLocal.offset(LAT, LON, 0, 400);
        float before = Float.NaN;
        for (int attempt = 0; ; attempt++) {
            if (attempt > 0) {
                close();
                java.nio.file.Files.deleteIfExists(ArduSitl.home().resolve("run10").resolve("eeprom.bin"));
            }
            log.clear();
            sitl = ArduSitl.start(10, LAT, LON, ALT, 1);
            sitl.awaitReady(120_000);
            assertEquals(0, new de.droiddrone.pc.setup.ardu.ArduTools(sitl.fc).simpleAccelCal());
            phone = new SitlFleetPhone(sitl, "DD");
            // a hill on the map, on the way out and home (40 - 160 m north); the slot 400 m north on flat ground
            de.droiddrone.pc.route.TerrainSource hill = (lat, lon) -> {
                double n = GeoLocal.north(LAT, LON, lat, lon);
                return ALT + HILL * Math.max(0, 1 - Math.abs(n - 100) / 60); // a 30 m hill, its top 100 m north
            };
            ops = new de.droiddrone.pc.gui.FleetOpsAccess(null, n -> "D1".equals(n) ? phone.address() : null, log, hill);
            sitl.awaitPreArm(120_000);
            before = sitl.fc.readParam("RTL_ALT_M", 3000) * 100; // Copter 4.7: RTL_ALT moved to RTL_ALT_M (m)
            if (Float.isNaN(before)) before = sitl.fc.readParam("RTL_ALT", 3000);
            ops.click("D1", "SAFE TO AUTOLAUNCH: mark");
            Thread.sleep(2500);
            ops.dispatch(target[0], target[1]);
            long end = System.currentTimeMillis() + 180_000;
            while (System.currentTimeMillis() < end && !holding(target) && !phone.launchWhy().contains("ARM REFUSED")) {
                ops.tick();
                Thread.sleep(250);
            }
            if (holding(target) || attempt == 2 || !phone.launchWhy().contains("ARM REFUSED")) break;
        }
        assertTrue(holding(target), "holds at its slot: " + java.util.Arrays.toString(phone.fc.position()) + " " + log + " " + phone.texts + " " + phone.uploadWhy() + " launch " + phone.launchWhy());
        double planned = ops.returnHeightOf("D1");
        assertEquals(60 + HILL, planned, 0.5, "RT-9: the hill + its route height " + log);
        assertTrue(ops.returnOnDrone("D1"));
        float rtl = Float.isNaN(sitl.fc.readParam("RTL_ALT_M", 3000)) ? sitl.fc.readParam("RTL_ALT", 3000) : sitl.fc.readParam("RTL_ALT_M", 3000) * 100;
        assertEquals(planned * 100, rtl, 1, "ArduPilot's RTL_ALT is the planned one (was " + before + ")");
        assertTrue(log.stream().anyMatch(s -> s.startsWith("D1: return height")), "read back before it left: " + log);

        // RTL from its slot, 60 m up: it climbs to RTL_ALT first, then comes home
        double startH = phone.fc.position()[3];
        assertTrue(startH < planned - 15, "below its return height at the slot: " + startH);
        phone.mode(SitlFleetPhone.RTL);
        double top = startH, distAtTop = Double.NaN;
        long end = System.currentTimeMillis() + 180_000;
        boolean home = false;
        while (System.currentTimeMillis() < end && !home) {
            double[] p = phone.fc.position();
            if (p[3] > top) {
                top = p[3];
                distAtTop = GeoLocal.distance(target[0], target[1], p[0], p[1]);
            }
            home = GeoLocal.distance(LAT, LON, p[0], p[1]) < 60 && top > planned - 3;
            Thread.sleep(200);
        }
        assertEquals(planned, top, 3, "climbed to its RTL_ALT: top " + top);
        assertTrue(home, "on its way home over its rally point");
        assertTrue(distAtTop < 80, "climbed before it crossed the hill: " + distAtTop + " m from the slot");
    }

    @Test
    void droneSetupsGnssLossHoldsItsHeight() throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        sitl = ArduSitl.start(11, LAT, LON, ALT, 1);
        sitl.awaitReady(120_000);
        assertEquals(0, new de.droiddrone.pc.setup.ardu.ArduTools(sitl.fc).simpleAccelCal());
        Map<String, de.droiddrone.pc.setup.ardu.ArduPlan.Wanted> w = de.droiddrone.pc.setup.ardu.ArduPlan.wanted(
                de.droiddrone.pc.setup.ardu.ArduPlan.Choices.defaults(12));
        float want = w.get("FS_EKF_ACTION").value();
        assertEquals(1f, sitl.fc.readParam("FS_EKF_ACTION", 3000), "Copter's default: LAND");
        assertEquals(want, sitl.fc.setParam("FS_EKF_ACTION", want, 3000), "taken, read back");
        assertEquals(2f, want, "ALT_HOLD");
        phone = new SitlFleetPhone(sitl, "DD");
        sitl.awaitPreArm(120_000);
        phone.takeOff(30);
        long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end && phone.fc.position()[3] < 28) Thread.sleep(250);
        phone.mode(SitlFleetPhone.LOITER);
        Thread.sleep(3000);
        String gps = Float.isNaN(sitl.fc.readParam("SIM_GPS1_ENABLE", 3000)) ? "SIM_GPS_DISABLE" : "SIM_GPS1_ENABLE";
        sitl.fc.setParam(gps, gps.equals("SIM_GPS_DISABLE") ? 1 : 0, 3000); // the GNSS gone
        end = System.currentTimeMillis() + 90_000;
        int mode = phone.fc.customMode();
        while (System.currentTimeMillis() < end && mode == SitlFleetPhone.LOITER) {
            Thread.sleep(250);
            mode = phone.fc.customMode();
        }
        double h = phone.fc.position()[3];
        assertEquals(SitlFleetPhone.ALT_HOLD, mode, "the EKF failsafe: ALT_HOLD, not LAND (9); ArduPilot said "
                + phone.texts.subList(Math.max(0, phone.texts.size() - 10), phone.texts.size()));
        Thread.sleep(5000);
        assertEquals(SitlFleetPhone.ALT_HOLD, phone.fc.customMode(), "still holding");
        assertTrue(phone.fc.armedFlag());
        assertEquals(h, phone.fc.position()[3], 6, "its height held");
        sitl.fc.setParam(gps, gps.equals("SIM_GPS_DISABLE") ? 0 : 1, 3000); // its GNSS back: the instance's eeprom as it was
    }

    /** In AUTO, within 80 m of the target, standing still: at its LOITER_UNLIM. */
    private boolean holding(double[] target) {
        double[] pos = phone.fc.position();
        if (pos == null || phone.fc.customMode() != 3) return false;
        return GeoLocal.distance(target[0], target[1], pos[0], pos[1]) < 80 && Math.hypot(pos[5], pos[6]) < 0.5;
    }
}
