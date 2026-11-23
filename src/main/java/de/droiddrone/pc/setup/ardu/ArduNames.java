package de.droiddrone.pc.setup.ardu;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ArduPilot parameter names across firmware versions, checked against Copter 4.7.3 SITL's whole list and the 4.6.3 /
 * 4.7.3 sources (SitlParamNamesTest):
 * <ul>
 *   <li>Renamed in Copter 4.7, some with new units: the 4.6 name is the one DRAUPNE's plans use, the 4.7 name goes
 *   beside it ({@link #withAliases}) with the value scaled; the firmware has one of the two, the other is listed apart
 *   and never written.</li>
 *   <li>Shown only once a feature is on (ArduPilot hides a group's parameters while its enable is 0): the avoidance's
 *   AVD_* behind AVD_ENABLE, the battery's BATT_* behind BATT_MONITOR. Planned against a board with the feature off they
 *   look missing; Drone setup writes the enable first and then looks for them again ({@link #shownBy}).</li>
 * </ul>
 */
public final class ArduNames {
    private ArduNames() {
    }

    /** The other version's name of a parameter and the factor its value takes there. */
    public record Alias(String name, double scale) {
    }

    /** 4.6 name -> 4.7 name, value x scale (Copter 4.7.3: ArduCopter/Parameters.cpp, AC_WPNav, AC_AttitudeControl). */
    static final Map<String, Alias> RENAMED_47 = new LinkedHashMap<>();
    /** 4.7 name -> 4.6 name, value x scale. */
    static final Map<String, Alias> RENAMED_46 = new LinkedHashMap<>();

    static {
        renamed("SYSID_THISMAV", "MAV_SYSID", 1);
        renamed("SYSID_MYGCS", "MAV_GCS_SYSID", 1);
        renamed("RTL_ALT", "RTL_ALT_M", 0.01);           // cm -> m
        renamed("WPNAV_SPEED", "WP_SPD", 0.01);          // cm/s -> m/s
        renamed("WPNAV_SPEED_UP", "WP_SPD_UP", 0.01);
        renamed("WPNAV_SPEED_DN", "WP_SPD_DN", 0.01);
        renamed("ATC_ACCEL_R_MAX", "ATC_ACC_R_MAX", 0.01); // cdeg/s/s -> deg/s/s
        renamed("ATC_ACCEL_P_MAX", "ATC_ACC_P_MAX", 0.01);
        renamed("ATC_ACCEL_Y_MAX", "ATC_ACC_Y_MAX", 0.01);
    }

    private static void renamed(String old, String now, double scale) {
        RENAMED_47.put(old, new Alias(now, scale));
        RENAMED_46.put(now, new Alias(old, 1 / scale));
    }

    /** The same parameter's name in the other version (and the factor to it), or null: not renamed. */
    public static Alias alias(String name) {
        Alias a = RENAMED_47.get(name);
        return a != null ? a : RENAMED_46.get(name);
    }

    /** Every wanted value, each renamed one followed by its 4.7 name with the value in that name's units. */
    public static Map<String, ArduPlan.Wanted> withAliases(Map<String, ArduPlan.Wanted> wanted) {
        Map<String, ArduPlan.Wanted> out = new LinkedHashMap<>();
        for (Map.Entry<String, ArduPlan.Wanted> e : wanted.entrySet()) {
            out.put(e.getKey(), e.getValue());
            Alias a = RENAMED_47.get(e.getKey());
            if (a == null || wanted.containsKey(a.name())) continue;
            out.put(a.name(), new ArduPlan.Wanted((float) (e.getValue().value() * a.scale()), e.getValue().why() + " (Copter 4.7)"));
        }
        return out;
    }

    /** A parameter's value as its 4.6 name means it, from whichever of its names the board has; null: neither. */
    public static Float value(Map<String, Float> params, String name) {
        Float v = params.get(name);
        if (v != null) return v;
        Alias a = alias(name);
        Float o = a == null ? null : params.get(a.name());
        return o == null ? null : (float) (o / a.scale());
    }

    /** The enable whose 0 hides this parameter, or null. */
    public static String shownBy(String name) {
        if (name.startsWith("AVD_") && !name.equals("AVD_ENABLE")) return "AVD_ENABLE";
        if (name.startsWith("BATT_") && !name.equals("BATT_MONITOR")) return "BATT_MONITOR";
        return null;
    }
}
