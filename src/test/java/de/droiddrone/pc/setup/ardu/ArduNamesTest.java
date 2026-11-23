package de.droiddrone.pc.setup.ardu;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ArduPilot 4.6 / 4.7 names, units and the parameters an enable shows (SitlParamNamesTest checks them on real 4.7.3). */
class ArduNamesTest {
    /** A board as Copter 4.7 has it: the new names, the avoidance off (its AVD_* hidden). */
    static Map<String, Float> copter47() {
        Map<String, Float> p = new HashMap<>();
        for (Map.Entry<String, ArduPlan.Wanted> e : ArduPlan.wanted(ArduPlan.Choices.defaults(1)).entrySet()) p.put(e.getKey(), 0f);
        for (String old : ArduNames.RENAMED_47.keySet()) p.remove(old);
        p.put("MAV_SYSID", 1f);
        p.put("MAV_GCS_SYSID", 255f);
        p.put("RTL_ALT_M", 15f);
        p.put("WP_SPD", 10f);
        p.put("WP_SPD_UP", 2.5f);
        p.put("WP_SPD_DN", 1.5f);
        p.keySet().removeIf(k -> k.startsWith("AVD_") && !k.equals("AVD_ENABLE"));
        return p;
    }

    @Test
    void the47NamesGoBesideThe46OnesInTheirOwnUnits() {
        Map<String, ArduPlan.Wanted> w = ArduNames.withAliases(ArduPlan.wanted(ArduPlan.Choices.defaults(7)));
        assertEquals(7f, w.get("MAV_SYSID").value());
        assertEquals(255f, w.get("MAV_GCS_SYSID").value());
        assertEquals(30f, w.get("RTL_ALT_M").value(), 1e-4, "3000 cm = 30 m");
        assertEquals(10f, w.get("WP_SPD").value(), 1e-4, "1000 cm/s = 10 m/s");
        assertEquals(2.5f, w.get("WP_SPD_UP").value(), 1e-4);
        assertEquals(1.5f, w.get("WP_SPD_DN").value(), 1e-4);
        Map<String, ArduPlan.Wanted> t = ArduNames.withAliases(ArduTuning.wanted(new ArduTuning.Choice(ArduTuning.Frame.AP_X, ArduTuning.Size.FIVE, 4, 0)));
        assertEquals(t.get("ATC_ACCEL_R_MAX").value() / 100, t.get("ATC_ACC_R_MAX").value(), 1e-3, "cdeg/s/s -> deg/s/s");
        assertEquals(1500f, ArduNames.value(Map.of("RTL_ALT_M", 15f), "RTL_ALT"), 1e-3, "read in the 4.6 name's units");
        assertEquals(3f, ArduNames.value(Map.of("MAV_SYSID", 3f), "SYSID_THISMAV"));
        assertNull(ArduNames.value(Map.of(), "SYSID_THISMAV"));
    }

    @Test
    void on47TheOldNamesAreLeftOutTheAvoidanceShowsOnceOnAndNothingIsSilentlyUnset() {
        List<ArduPlan.Change> plan = ArduPlan.plan(ArduPlan.Choices.defaults(5), copter47());
        List<String> lines = ArduPlan.lines(plan);
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("NOT SET")), String.join("\n", lines));
        assertTrue(lines.contains("SYSID_THISMAV - this firmware has MAV_SYSID, left out"), String.join("\n", lines));
        assertTrue(lines.contains("MAV_SYSID 1 -> 5  (this drone's own id in the fleet (Copter 4.7))"), String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("RTL_ALT_M 15 -> 30")), String.join("\n", lines));
        assertTrue(lines.contains("AVD_F_ACTION -> 6  (too close: stop and hold; once AVD_ENABLE is on)"), String.join("\n", lines));
        ArduPlan.Change old = plan.stream().filter(c -> c.name().equals("RTL_ALT")).findFirst().orElseThrow();
        assertFalse(old.writable(), "the 4.6 name is never written to a 4.7 board");
        assertTrue(plan.stream().filter(c -> c.name().equals("AVD_F_DIST_XY")).findFirst().orElseThrow().writable());
    }

    @Test
    void aSafetyParameterNothingStandsForIsSaidFirst() {
        Map<String, Float> p = copter47();
        p.remove("AVD_ENABLE"); // a firmware built without avoidance
        p.remove("FS_EKF_ACTION");
        List<String> lines = ArduPlan.lines(ArduPlan.plan(ArduPlan.Choices.defaults(5), p));
        assertTrue(lines.get(0).startsWith("NOT SET: "), lines.get(0));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("NOT SET: FS_EKF_ACTION")), String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("NOT SET: AVD_F_ACTION") && l.contains("(AVD_ENABLE off)")) || lines.stream().anyMatch(l -> l.startsWith("NOT SET: AVD_F_ACTION")),
                String.join("\n", lines));
        p.put("BATT_MONITOR", 0f);
        p.keySet().removeIf(k -> k.startsWith("BATT_") && !k.equals("BATT_MONITOR"));
        lines = ArduPlan.lines(ArduPlan.plan(ArduPlan.Choices.defaults(5), p));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("NOT SET: BATT_FS_CRT_ACT") && l.contains("(BATT_MONITOR off)")), String.join("\n", lines));
    }
}
