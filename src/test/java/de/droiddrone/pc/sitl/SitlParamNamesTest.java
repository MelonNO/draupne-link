package de.droiddrone.pc.sitl;

import de.droiddrone.common.FcCommon;
import de.droiddrone.common.OsdCommon;
import de.droiddrone.pc.setup.ardu.ArduNames;
import de.droiddrone.pc.setup.ardu.ArduPlan;
import de.droiddrone.pc.setup.ardu.ArduPorts;
import de.droiddrone.pc.setup.ardu.ArduSetupWriter;
import de.droiddrone.pc.setup.ardu.ArduTuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every ArduPilot parameter name DRAUPNE writes or reads, against real Copter (SITL, instance 12, its eeprom deleted
 * first): the whole list read from a fresh board, then with the features DRAUPNE turns on (avoidance, battery
 * monitor, OSD). Each name exists - itself or its other version's name (ArduNames) - in the units assumed (the fresh
 * board's defaults tell cm from m). Then Drone setup's whole plan is written by ArduSetupWriter to the fresh board and
 * read back: the failsafes and the avoidance among them, none left out. gradlew fleetSitl.
 */
@Tag("ardusitl")
class SitlParamNamesTest {
    private ArduSitl sitl;
    @TempDir
    Path backups;

    @AfterEach
    void close() {
        if (sitl != null) sitl.close();
    }

    /** Names DRAUPNE uses without writing them through ArduPlan (read by the PC or the phone, or set by the phone). */
    static Set<String> readOrSetElsewhere() {
        Set<String> s = new LinkedHashSet<>();
        for (String r : new String[]{"ROLL", "PITCH", "THROTTLE", "YAW"}) s.add("RCMAP_" + r); // ArduBenchCheck, the phone's stick map
        s.addAll(List.of(FcCommon.AP_PARAM_RC_OPTIONS));                                     // the phone, ArduBenchCheck, ArduPlan
        s.addAll(List.of("FENCE_TYPE", "FENCE_ACTION", "FENCE_ENABLE"));                     // RouteWindow.fenceOn, the phone
        s.addAll(List.of("RALLY_INCL_HOME", "RTL_ALT", "RTL_ALT_M"));                         // the phone (AC-10, RT-9)
        for (int n = 1; n <= 6; n++) s.add("FLTMODE" + n);                                    // ArduTuning reads them
        for (int n = 1; n <= 7; n++) { // ArduPorts: the ports the board has (SITL 7; ArduPorts.ports reads them from the list)
            s.add("SERIAL" + n + "_PROTOCOL");
            s.add("SERIAL" + n + "_BAUD");
        }
        s.addAll(List.of("FRAME_CLASS", "FRAME_TYPE", "AHRS_ORIENTATION"));                   // FromBetaflight
        return s;
    }

    /** The phone's OSD parameters (upstream DroidDrone's FcParams): only while the board has an OSD. */
    static Set<String> osd() {
        Set<String> s = new LinkedHashSet<>(List.of(FcCommon.AP_PARAM_OSD1_ENABLE, FcCommon.AP_PARAM_OSD1_TXT_RES, FcCommon.AP_PARAM_OSD_UNITS,
                FcCommon.AP_PARAM_OSD_MSG_TIME, FcCommon.AP_PARAM_OSD_W_RSSI, FcCommon.AP_PARAM_OSD_W_NSAT, FcCommon.AP_PARAM_OSD_W_BATVOLT,
                FcCommon.AP_PARAM_OSD_W_AVGCELLV, FcCommon.AP_PARAM_OSD_CELL_COUNT));
        for (String i : OsdCommon.AP_OSD_ITEMS) {
            s.add("OSD1_" + i + "_EN");
            s.add("OSD1_" + i + "_X");
            s.add("OSD1_" + i + "_Y");
        }
        return s;
    }

    /** Everything Drone setup's plans can want (ArduPlan, AS-6 ports, AS-7 tuning), each renamed one with its 4.7 name. */
    static Map<String, ArduPlan.Wanted> planned(Map<String, Float> now) {
        Map<String, ArduPlan.Wanted> w = new LinkedHashMap<>(ArduPlan.wanted(ArduPlan.Choices.defaults(12)));
        w.putAll(ArduTuning.wanted(new ArduTuning.Choice(ArduTuning.Frame.values()[0], ArduTuning.Size.values()[0], 4, 6), now));
        w.putAll(ArduPorts.plan(new ArduPorts.Choice(3, 1, 57, true), now).wanted());
        return ArduNames.withAliases(w);
    }

    @Test
    void everyNameWeUseIsOnCopter47InTheUnitsWeAssumeAndTheWholePlanReadsBack() throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        Files.deleteIfExists(ArduSitl.home().resolve("run12").resolve("eeprom.bin"));
        sitl = ArduSitl.start(12, 59.9, 10.7, 100, 1);
        Thread.sleep(3000);
        Map<String, Float> fresh = sitl.fc.readAllParams(60_000);

        // the 4.7 names, not 4.6's; units from the fresh board's defaults (and the 4.7.3 source's @Units)
        for (Map.Entry<String, String> e : Map.of("SYSID_THISMAV", "MAV_SYSID", "SYSID_MYGCS", "MAV_GCS_SYSID", "RTL_ALT", "RTL_ALT_M",
                "WPNAV_SPEED", "WP_SPD", "WPNAV_SPEED_UP", "WP_SPD_UP", "WPNAV_SPEED_DN", "WP_SPD_DN", "ATC_ACCEL_R_MAX", "ATC_ACC_R_MAX",
                "ATC_ACCEL_P_MAX", "ATC_ACC_P_MAX", "ATC_ACCEL_Y_MAX", "ATC_ACC_Y_MAX").entrySet()) {
            assertFalse(fresh.containsKey(e.getKey()), e.getKey() + " is gone in 4.7");
            assertTrue(fresh.containsKey(e.getValue()), e.getValue() + " is its 4.7 name");
            assertEquals(e.getValue(), ArduNames.alias(e.getKey()).name());
        }
        assertEquals(15f, fresh.get("RTL_ALT_M"), "RTL_ALT_M in m (4.6: RTL_ALT 1500 cm)");
        assertEquals(10f, fresh.get("WP_SPD"), "WP_SPD in m/s (4.6: WPNAV_SPEED 1000 cm/s)");
        assertEquals(2.5f, fresh.get("WP_SPD_UP"));
        assertEquals(1.5f, fresh.get("WP_SPD_DN"));
        assertEquals(1100f, fresh.get("ATC_ACC_R_MAX"), "deg/s/s (4.6: 110000 cdeg/s/s)");
        assertEquals(255f, fresh.get("MAV_GCS_SYSID"));
        // kept names, kept units
        assertEquals(5f, fresh.get("FS_GCS_TIMEOUT"), "s");
        assertEquals(3f, fresh.get("RC_OVERRIDE_TIME"), "s");
        assertEquals(20f, fresh.get("INS_GYRO_FILTER"), "Hz");
        assertEquals(1f, fresh.get("FS_EKF_ACTION"), "1 Land; we write 2 AltHold");
        assertFalse(fresh.containsKey("AVD_F_ACTION"), "the avoidance's parameters hide while AVD_ENABLE is 0");
        assertEquals(0f, fresh.get("BATT_MONITOR"), "SITL's battery monitor off: BATT_* hidden");
        assertFalse(fresh.containsKey("BATT_FS_CRT_ACT"));
        assertFalse(fresh.containsKey("OSD1_ENABLE"), "no OSD: its parameters hidden - the phone must not wait for them");

        // the features DRAUPNE turns on, or a board has: then everything is there
        for (String en : new String[]{"ADSB_TYPE", "AVD_ENABLE", "BATT_MONITOR", "OSD_TYPE"}) {
            assertEquals(en.equals("BATT_MONITOR") ? 4f : 1f, sitl.fc.setParam(en, en.equals("BATT_MONITOR") ? 4 : 1, 3000), en);
        }
        sitl.close(); // BATT_MONITOR and OSD_TYPE take a restart
        sitl = ArduSitl.start(12, 59.9, 10.7, 100, 1);
        Thread.sleep(3000);
        Map<String, Float> on = sitl.fc.readAllParams(60_000);
        assertEquals(100f, on.get("AVD_F_DIST_XY"), "AVD distances in m (default 100 m)");
        assertEquals(10.5f, on.get("BATT_LOW_VOLT"), 0.01, "V");
        List<String> missing = new ArrayList<>();
        Set<String> all = new LinkedHashSet<>(planned(on).keySet());
        all.addAll(readOrSetElsewhere());
        all.addAll(osd());
        for (String n : all) {
            if (on.containsKey(n)) continue;
            ArduNames.Alias a = ArduNames.alias(n);
            if (a != null && on.containsKey(a.name())) continue; // the other version's name: that one is used
            missing.add(n);
        }
        assertEquals(List.of(), missing, "every name we use is on Copter 4.7.3 (itself or its 4.6 / 4.7 twin)");
        sitl.close();

        // Drone setup's plan written to the fresh board (avoidance off, as delivered) and read back
        Files.deleteIfExists(ArduSitl.home().resolve("run12").resolve("eeprom.bin"));
        sitl = ArduSitl.start(12, 59.9, 10.7, 100, 1);
        Thread.sleep(3000);
        sitl.fc.setParam("BATT_MONITOR", 4, 3000); // a real board has its battery monitor on
        sitl.close();
        sitl = ArduSitl.start(12, 59.9, 10.7, 100, 1);
        Thread.sleep(3000);
        Map<String, Float> before = sitl.fc.readAllParams(60_000);
        List<ArduPlan.Change> plan = ArduPlan.plan(ArduPlan.Choices.defaults(12), before);
        List<String> lines = ArduPlan.lines(plan);
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("NOT SET")), "nothing left unset: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("AVD_F_ACTION -> 6") && l.contains("once AVD_ENABLE is on")), String.join("\n", lines));
        assertTrue(lines.contains("SYSID_THISMAV - this firmware has MAV_SYSID, left out"), String.join("\n", lines));
        List<String> log = new ArrayList<>();
        ArduSetupWriter.Result r = new ArduSetupWriter(sitl.fc, log::add).write(plan, backups, java.time.LocalDateTime.now());
        assertTrue(r.ok(), r.failure() + " " + log);
        Map<String, Float> after = sitl.fc.readAllParams(60_000);
        Map<String, ArduPlan.Wanted> want = ArduNames.withAliases(ArduPlan.wanted(ArduPlan.Choices.defaults(12)));
        for (Map.Entry<String, ArduPlan.Wanted> e : want.entrySet()) {
            Float v = after.get(e.getKey());
            if (v == null) {
                ArduNames.Alias a = ArduNames.alias(e.getKey());
                assertNotNull(a, e.getKey() + " is on the board after setup");
                assertTrue(after.containsKey(a.name()), e.getKey() + " or " + a.name());
                continue;
            }
            assertTrue(ArduPlan.same(v, e.getValue().value()), e.getKey() + " = " + v + ", wanted " + e.getValue().value());
        }
        assertEquals(12f, after.get("MAV_SYSID"), "its fleet id, under 4.7's name");
        assertEquals(6f, after.get("AVD_F_ACTION"), "too close: hold");
        assertEquals(30f, after.get("RTL_ALT_M"), "30 m");
        assertEquals(10f, after.get("WP_SPD"), "10 m/s, not 1000");
        assertEquals(1f, after.get("BATT_FS_CRT_ACT"), "3 %: land");
        assertEquals(2f, after.get("FS_EKF_ACTION"));
    }
}
