package de.droiddrone.pc.sitl;

import de.droiddrone.common.MissionItem;
import de.droiddrone.common.MissionUpload;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.pc.launch.RallyPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD AC-10 against real ArduPilot Copter (SITL): the rally point as the PC sends it (RallyPlan's one item) goes
 * up through the phone's MissionUpload and reads back as sent; RALLY_INCL_HOME 0 is taken. gradlew fleetSitl.
 */
@Tag("ardusitl")
class SitlRallyTest {
    static final double LAT = 59.9, LON = 10.7, ALT = 100;
    private ArduSitl sitl;

    @AfterEach
    void close() {
        if (sitl != null) sitl.close();
    }

    @Test
    void theRallyPointReadsBackFromArduPilot() throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        sitl = ArduSitl.start(4, LAT, LON, ALT, 1);
        sitl.awaitReady(120_000);
        double[] at = GeoLocal.offset(LAT, LON, 30, 0);
        List<MissionItem> items = RallyPlan.items(at[0], at[1], 40);
        MissionUpload[] u = {new MissionUpload(items, SitlMission.link(sitl.fc, MissionUpload.TYPE_RALLY), MissionUpload.TYPE_RALLY)};
        SitlMission.feed(sitl.fc, () -> u[0]);
        u[0].start(System.currentTimeMillis());
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end && !u[0].done() && !u[0].failed()) {
            u[0].tick(System.currentTimeMillis());
            Thread.sleep(100);
        }
        assertTrue(u[0].done(), "read back: " + u[0].state() + " " + u[0].reason());
        assertEquals(0f, sitl.fc.setParam("RALLY_INCL_HOME", 0, 3000));
    }
}
