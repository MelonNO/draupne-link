package de.droiddrone.pc.sitl;

import de.droiddrone.common.MissionItem;
import de.droiddrone.common.MissionUpload;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.pc.setup.ardu.ArduPlan;
import de.droiddrone.pc.setup.ardu.ArduSetupWriter;
import de.droiddrone.pc.setup.ardu.MavSession;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fleet PRD's phase gates against real ArduPilot Copter (SITL, ArduSitl): gate 1 - set up as Drone setup does it,
 * armed on the phone's overrides, flown away, the PC cut: the phone's rule brakes it and after 20 s it comes home and
 * lands by itself; gate 2's upload half - 100 route items to the FC and read back in under 5 s (the phone's
 * MissionUpload, here over SITL's TCP as over USB). Skipped where SITL isn't installed. Real time: a few minutes.
 * gradlew fleetSitl.
 */
@Tag("ardusitl")
class SitlGateTest {
    static final double LAT = 59.9, LON = 10.7, ALT = 100;

    private static void until(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (ok.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("waited " + ms / 1000 + " s for: " + what);
    }

    @Test
    void gate1ArmsOnOverridesAndComesHomeAfterACut(@TempDir Path backups) throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        try (ArduSitl sitl = ArduSitl.start(0, LAT, LON, ALT, 1)) {
            MavSession fc = sitl.fc;
            sitl.awaitReady(120_000);
            // Drone setup's own plan and writer (backup, write, read back)
            ArduPlan.Choices c = new ArduPlan.Choices(1, 3, 3300, 1, 2, ArduPlan.DEFAULT_MODES.clone(), 30, 10, 2.5, 1.5, true);
            ArduSetupWriter.Result r = new ArduSetupWriter(fc, null).write(ArduPlan.plan(c, fc.readAllParams(60_000)), backups,
                    java.time.LocalDateTime.now());
            assertTrue(r.ok(), r.failure());
            try (SitlPilot pilot = new SitlPilot(fc)) {
                pilot.frame[5] = SitlPilot.modePwm(3); // LOITER (FLTMODE3 in DEFAULT_MODES)
                Thread.sleep(1500);
                pilot.frame[4] = 2000; // ARM switch up
                until(fc::armedFlag, 15_000, "armed on the overrides");
                pilot.frame[3] = 1900; // climb
                until(() -> fc.position() != null && fc.position()[3] > 25, 30_000, "25 m up");
                pilot.frame[3] = 1500;
                pilot.frame[1] = 1200; // forward
                Thread.sleep(8000);
                pilot.frame[1] = 1500;
                double away = GeoLocal.distance(LAT, LON, fc.position()[0], fc.position()[1]);
                assertTrue(away > 40, "flown away: " + away + " m");
                pilot.setPc(false); // the PC's link cut
                until(() -> fc.customMode() == 5, 3000, "LOITER within a second or two of the cut");
                until(() -> fc.customMode() == 21 || fc.customMode() == 6, 25_000, "home by Smart RTL after 20 s (" + pilot.lastAction + ")");
                until(() -> !fc.armedFlag(), 150_000, "landed and disarmed at home");
                double off = GeoLocal.distance(LAT, LON, fc.position()[0], fc.position()[1]);
                assertTrue(off < 5, "landed " + off + " m from home");
            }
        }
    }

    @Test
    void gate2AHundredItemsUpAndReadBackInUnderFiveSeconds() throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        try (ArduSitl sitl = ArduSitl.start(1, LAT, LON, ALT, 1)) {
            MavSession fc = sitl.fc;
            List<MissionItem> items = new ArrayList<>();
            items.add(MissionItem.at(0, MissionItem.NAV_WAYPOINT, LAT, LON, ALT)); // home
            for (int i = 1; i < 100; i++) items.add(MissionItem.at(i, MissionItem.NAV_WAYPOINT, LAT + i * 1e-4, LON, ALT + 60));
            MissionUpload[] up = new MissionUpload[1];
            up[0] = new MissionUpload(items, SitlMission.link(fc));
            SitlMission.feed(fc, () -> up[0]);
            long t0 = System.currentTimeMillis();
            up[0].start(t0);
            while (!up[0].done() && !up[0].failed() && System.currentTimeMillis() - t0 < 20_000) {
                up[0].tick(System.currentTimeMillis());
                Thread.sleep(20);
            }
            long took = System.currentTimeMillis() - t0;
            assertTrue(up[0].done(), "uploaded and read back: " + up[0].reason());
            assertTrue(took < 5000, "100 items in " + took + " ms");
        }
    }
}
