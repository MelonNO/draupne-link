package de.droiddrone.pc.setup.ardu;

import de.droiddrone.common.CellVolts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fleet PRD AP-7, AS-8, AS-9 (with FD-5, AP-5, RT-10, AL-15, AC-11): what Drone setup writes to an ArduPilot Copter for
 * DRAUPNE - built from the pilot's choices and what the flight controller has now, as a plan the pilot reads before
 * CONFIRM. Only the parameters that change are in it; each says why in a few words. A parameter this firmware doesn't
 * have is listed apart (an older ArduPilot) and never written.
 * <p>
 * The choices behind the numbers (ArduPilot Copter 4.6):
 * <ul>
 *   <li>Sticks come only as RC overrides from the phone (SYSID_MYGCS 255 = the phone's ground station id), no radio
 *       receiver: no radio failsafe (FS_THR_ENABLE 0); overrides never time out by themselves (RC_OVERRIDE_TIME -1) - the
 *       phone holds the sticks centred while its own link-loss rule runs, and ArduPilot's GCS failsafe (5 s without the
 *       phone's heartbeat: the app itself stopped) brings the drone home by RTL. The 5 s are a margin over the phone's
 *       1 Hz heartbeat on a busy USB link.</li>
 *   <li>Battery never sends it home and never blocks arming (FD-5, AS-8): low = a warning only (BATT_FS_LOW_ACT 0) at
 *       3.2 V a cell; critical = LAND where it is (BATT_FS_CRT_ACT 1) at 3.0 V a cell - 3 % on the Li-ion curve
 *       ({@link CellVolts}); BATT_ARM_VOLT / BATT_ARM_MAH 0.</li>
 *   <li>No terrain data on the drone (RT-10): TERRAIN_ENABLE 0. Routes carry heights above sea level.</li>
 *   <li>Crash check disarms (AL-15): FS_CRASH_CHECK 1; vibration failsafe on (FS_VIBE_ENABLE 1).</li>
 *   <li>Avoidance of the other DRAUPNE drones, given as ADSB_VEHICLE by the PC (AC-11, FD-8): AVD_ENABLE 1, stop and
 *       hold (AVD_F_ACTION 6 = hover) inside 10 m sideways / 10 m height, warn inside 20 m; ADSB_TYPE 1 takes the
 *       messages. Not tried on a real drone.</li>
 *   <li>The arm switch (ArduPilot's RCx_OPTION 153) and the 6-position mode channel on the AUX channels DRAUPNE's
 *       radio mapping uses for ARM and the flight mode switch (frame channel = 4 + AUX).</li>
 * </ul>
 */
public final class ArduPlan {
    /** ArduPilot's arm / disarm switch function for an RCx_OPTION. */
    public static final int RC_OPTION_ARM_DISARM = 153;
    /**
     * Copter's flight modes on the mode switch by default, positions 1 to 6: STABILIZE, ALT HOLD, LOITER, ALT HOLD, RTL,
     * LOITER. GU-4: a 3-position switch (about 1000 / 1500 / 2000 us) lands on positions 1, 4 and 6 - STABILIZE, ALT HOLD
     * and LOITER, the order of the Betaflight drones' acro, angle and position hold. Before, its top was LAND. RTL and LAND
     * are the OSD's buttons (and position 5 of a 6-position switch).
     */
    public static final int[] DEFAULT_MODES = {0, 2, 5, 2, 6, 5};

    /** The pilot's choices for one drone. */
    public record Choices(int sysId, int cells, int capacityMah, int armAux, int modeAux, int[] flightModes,
                          double rtlAltM, double cruiseMs, double climbMs, double descentMs, boolean avoidance) {
        public static Choices defaults(int sysId) {
            return new Choices(sysId, 4, 3000, 1, 2, DEFAULT_MODES.clone(), 30, 10, 2.5, 1.5, true);
        }
    }

    /** One parameter that changes: its value now (NaN: the firmware has none), the new one, and why. */
    public record Change(String name, float now, float to, String why) {
        public boolean missing() {
            return Float.isNaN(now);
        }

        /** "RTL_ALT 1500 -> 3000  (come home 30 m up)" */
        public String line() {
            return name + " " + num(now) + " -> " + num(to) + "  (" + why + ")";
        }
    }

    private ArduPlan() {
    }

    /** What the choices want, in the order it is written (identity first, then the rest), with the reason. */
    public static Map<String, Wanted> wanted(Choices c) {
        Map<String, Wanted> w = new LinkedHashMap<>();
        put(w, "SYSID_THISMAV", c.sysId(), "this drone's own id in the fleet");
        put(w, "SYSID_MYGCS", 255, "sticks only from DRAUPNE's phone");
        // sticks and failsafes
        put(w, "FS_THR_ENABLE", 0, "no radio receiver: the phone is the radio");
        put(w, "RC_OVERRIDE_TIME", -1, "sticks held by the phone, never timed out");
        put(w, "FS_GCS_ENABLE", 1, "phone app stopped: RTL");
        put(w, "FS_GCS_TIMEOUT", 5, "5 s without the phone's heartbeat");
        put(w, "FS_CRASH_CHECK", 1, "crash: disarm");
        put(w, "FS_VIBE_ENABLE", 1, "vibration failsafe on");
        // battery: warnings only, LAND at 3 %
        double low = CellVolts.LOW * c.cells(), crit = 3.0 * c.cells();
        put(w, "BATT_CAPACITY", c.capacityMah(), "pack size");
        put(w, "BATT_LOW_VOLT", round2(low), "warn at 3.2 V a cell");
        put(w, "BATT_CRT_VOLT", round2(crit), "3 % (3.0 V a cell)");
        put(w, "BATT_FS_LOW_ACT", 0, "low battery: a warning only");
        put(w, "BATT_FS_CRT_ACT", 1, "3 %: land where it is");
        put(w, "BATT_ARM_VOLT", 0, "battery never blocks arming");
        put(w, "BATT_ARM_MAH", 0, "battery never blocks arming");
        // routes
        put(w, "TERRAIN_ENABLE", 0, "no terrain data on the drone");
        put(w, "RTL_ALT", Math.round(c.rtlAltM() * 100), "come home " + Math.round(c.rtlAltM()) + " m up");
        put(w, "RTL_ALT_TYPE", 0, "home height above take-off");
        put(w, "WPNAV_SPEED", Math.round(c.cruiseMs() * 100), "route speed " + fmt(c.cruiseMs()) + " m/s");
        put(w, "WPNAV_SPEED_UP", Math.round(c.climbMs() * 100), "climb " + fmt(c.climbMs()) + " m/s");
        put(w, "WPNAV_SPEED_DN", Math.round(c.descentMs() * 100), "descend " + fmt(c.descentMs()) + " m/s");
        // switches
        int armCh = 4 + c.armAux(), modeCh = 4 + c.modeAux();
        put(w, "RC" + armCh + "_OPTION", RC_OPTION_ARM_DISARM, "ARM on AUX" + c.armAux());
        put(w, "FLTMODE_CH", modeCh, "mode switch on AUX" + c.modeAux());
        int[] modes = c.flightModes();
        for (int i = 0; i < 6 && i < modes.length; i++) put(w, "FLTMODE" + (i + 1), modes[i], "mode switch position " + (i + 1));
        // the others, kept apart
        if (c.avoidance()) {
            put(w, "ADSB_TYPE", 1, "other DRAUPNE drones as traffic");
            put(w, "AVD_ENABLE", 1, "avoid other DRAUPNE drones");
            put(w, "AVD_F_ACTION", 6, "too close: stop and hold");
            put(w, "AVD_F_DIST_XY", 10, "10 m sideways");
            put(w, "AVD_F_DIST_Z", 10, "10 m in height");
            put(w, "AVD_W_DIST_XY", 20, "warn at 20 m");
            put(w, "AVD_W_DIST_Z", 20, "warn at 20 m");
        }
        return w;
    }

    /** One wanted value and its reason. */
    public record Wanted(float value, String why) {
    }

    /**
     * The plan: every wanted value that differs from the flight controller's (more than its float rounding), and the
     * ones it lacks, apart. The arm channel's other functions on the same channel are not touched.
     */
    public static List<Change> plan(Choices c, Map<String, Float> now) {
        return plan(c, Map.of(), now);
    }

    /** With more wanted values (fleet AS-11: frame and board turn carried from Betaflight), after the choices'. */
    public static List<Change> plan(Choices c, Map<String, Wanted> extra, Map<String, Float> now) {
        Map<String, Wanted> all = new LinkedHashMap<>(wanted(c));
        all.putAll(extra);
        return changes(all, now);
    }

    /** Any set of wanted values (AS-6 ports, AS-7 tuning) against the FC's: what changes, the missing ones kept apart. */
    public static List<Change> changes(Map<String, Wanted> all, Map<String, Float> now) {
        List<Change> out = new ArrayList<>();
        for (Map.Entry<String, Wanted> e : all.entrySet()) {
            Float have = now.get(e.getKey());
            float to = e.getValue().value();
            if (have != null && same(have, to)) continue;
            out.add(new Change(e.getKey(), have == null ? Float.NaN : have, to, e.getValue().why()));
        }
        return out;
    }

    /** Equal as ArduPilot stores it: integers exactly, the rest to 1/1000. */
    public static boolean same(float a, float b) {
        return Math.abs(a - b) <= Math.max(1e-3f, Math.abs(b) * 1e-6f);
    }

    /** The plan's lines for CONFIRM; the missing parameters last, marked. */
    public static List<String> lines(List<Change> plan) {
        List<String> l = new ArrayList<>();
        for (Change c : plan) if (!c.missing()) l.add(c.line());
        for (Change c : plan) if (c.missing()) l.add(c.name() + " - not in this firmware, left out");
        return l;
    }

    private static void put(Map<String, Wanted> w, String name, double value, String why) {
        w.put(name, new Wanted((float) value, why));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    static String num(float v) {
        if (Float.isNaN(v)) return "-";
        if (v == Math.rint(v) && Math.abs(v) < 1e7) return Long.toString((long) v);
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
