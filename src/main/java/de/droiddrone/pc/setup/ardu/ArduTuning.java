package de.droiddrone.pc.setup.ardu;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Fleet PRD AS-7 / H2: tuning an ArduPilot quad for DRAUPNE - a frame preset (FRAME_CLASS quad, FRAME_TYPE in
 * ArduPilot's or Betaflight's motor order) and a size preset with STARTING POINTS for the rate PIDs, the filters, the
 * attitude accelerations and the thrust curve; then AUTOTUNE as an option on one position of the mode switch.
 * <p>
 * The numbers: filters, accelerations and thrust expo from the prop size as Mission Planner's "initial parameters"
 * work them out (gyro filter = 289.22 x prop^-0.838, Hz; ATC_ACCEL_R/P_MAX = -2.613267 p^3 + 343.39216 p^2 - 15083.7121 p
 * + 235771, to 100; MOT_THST_EXPO = 0.15686 ln p + 0.23693, 0.5 - 0.8); the D-term filters at half the gyro's. The
 * PIDs are a gentle start below a typical tune of that size (ArduPilot's own defaults are the 10" ones) - every one is
 * marked a starting point, and AUTOTUNE is what tunes the drone. Freestyle or acro tuning is a non-goal (PRD).
 * Battery compensation from the cells: full 4.2 V, empty 3.0 V a cell (DRAUPNE's Li-ion floor, as ArduPlan's 3 %).
 */
public final class ArduTuning {
    /** Copter's AUTOTUNE flight mode number. */
    public static final int MODE_AUTOTUNE = 15;
    public static final int FRAME_CLASS_QUAD = 1, FRAME_TYPE_X = 1;

    /** What the pilot does for AUTOTUNE, short. */
    public static final String AUTOTUNE_HOWTO = "AUTOTUNE: a calm day, open space, a full pack. Take off in LOITER, "
            + "climb to 15 m, switch to AUTOTUNE. It twitches each axis for 5-10 min; nudge the sticks only to keep it "
            + "clear (it pauses meanwhile). Done: land in AUTOTUNE and disarm to keep the tune. Switch out first to drop it.";

    /** The size presets. */
    public enum Size {
        FIVE("5\"", 5, 0.070, 0.0012),
        SEVEN("7\"", 7, 0.090, 0.0020),
        TEN("10\"", 10, 0.135, 0.0036);

        public final String label;
        public final double propInch, ratePI, rateD;

        Size(String label, double propInch, double ratePI, double rateD) {
            this.label = label;
            this.propInch = propInch;
            this.ratePI = ratePI;
            this.rateD = rateD;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** The motor orders offered: ArduPilot's X, Betaflight's X (props in), Betaflight's X with props out. */
    public enum Frame {
        AP_X("ArduPilot X", FRAME_TYPE_X),
        BF_X("Betaflight X", FromBetaflight.FRAME_TYPE_BF_X),
        BF_X_OUT("Betaflight X, props out", FromBetaflight.FRAME_TYPE_BF_X_REVERSED);

        public final String label;
        public final int type;

        Frame(String label, int type) {
            this.label = label;
            this.type = type;
        }

        public static Frame ofType(Integer type) {
            if (type != null) for (Frame f : values()) if (f.type == type) return f;
            return BF_X;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * @param size the size preset, null: keep the tune it has (a drone tuned before is not undone)
     * @param autotunePos the mode switch position (1..6) that becomes AUTOTUNE; 0: not offered
     */
    public record Choice(Frame frame, Size size, int cells, int autotunePos) {}

    private ArduTuning() {
    }

    static double gyroFilterHz(double p) {
        return Math.max(20, Math.min(80, Math.round(289.22 * Math.pow(p, -0.838))));
    }

    static double accelRollPitch(double p) {
        double v = -2.613267 * p * p * p + 343.39216 * p * p - 15083.7121 * p + 235771;
        return Math.max(10_000, Math.round(v / 100.0) * 100.0);
    }

    static double thrustExpo(double p) {
        double v = Math.round((0.15686 * Math.log(p) + 0.23693) * 100) / 100.0;
        return Math.max(0.5, Math.min(0.8, v));
    }

    /** The wanted values, in the order they are written. */
    public static Map<String, ArduPlan.Wanted> wanted(Choice c) {
        Map<String, ArduPlan.Wanted> w = new LinkedHashMap<>();
        put(w, "FRAME_CLASS", FRAME_CLASS_QUAD, "quad");
        put(w, "FRAME_TYPE", c.frame().type, c.frame().label + " motor order");
        if (c.size() != null) tune(w, c);
        if (c.autotunePos() >= 1 && c.autotunePos() <= 6) {
            put(w, "FLTMODE" + c.autotunePos(), MODE_AUTOTUNE, "AUTOTUNE on mode switch position " + c.autotunePos());
            put(w, "AUTOTUNE_AXES", 7, "tune roll, pitch and yaw");
        }
        return w;
    }

    /**
     * As {@link #wanted(Choice)}, against what the board has: a mode switch position that is AUTOTUNE now and is not the
     * chosen one (AUTOTUNE taken off, or moved) gets its own mode back (ArduPlan's DEFAULT_MODES) - left as it was, the
     * switch kept an AUTOTUNE the pilot had unticked.
     */
    public static Map<String, ArduPlan.Wanted> wanted(Choice c, Map<String, Float> now) {
        Map<String, ArduPlan.Wanted> w = wanted(c);
        for (int pos = 1; pos <= 6; pos++) {
            Float m = now.get("FLTMODE" + pos);
            if (m == null || Math.round(m) != MODE_AUTOTUNE || pos == c.autotunePos()) continue;
            put(w, "FLTMODE" + pos, ArduPlan.DEFAULT_MODES[pos - 1], "AUTOTUNE off position " + pos);
        }
        return w;
    }

    /**
     * Why AUTOTUNE can't go on this mode switch position, or null. A 3-position switch lands on 1, 4 and 6: on 1 (the
     * switch down, where it is armed) ArduPilot can't arm in AUTOTUNE; 6 is LOITER, where AUTOTUNE starts from and where
     * the pilot goes to drop it.
     */
    public static String autotuneRefused(int pos) {
        if (pos == 1) return "AUTOTUNE on position 1: it can't arm there. Pick 2-5.";
        if (pos == 6) return "AUTOTUNE on position 6 takes LOITER, where it starts from. Pick 2-5.";
        return null;
    }

    private static void tune(Map<String, ArduPlan.Wanted> w, Choice c) {
        String start = c.size().label + " start";
        double p = c.size().propInch, gyro = gyroFilterHz(p);
        put(w, "INS_GYRO_FILTER", gyro, "gyro filter, " + start);
        put(w, "INS_ACCEL_FILTER", 10, "accel filter, " + start);
        for (String axis : new String[]{"RLL", "PIT"}) {
            put(w, "ATC_RAT_" + axis + "_P", c.size().ratePI, "rate P, " + start);
            put(w, "ATC_RAT_" + axis + "_I", c.size().ratePI, "rate I, " + start);
            put(w, "ATC_RAT_" + axis + "_D", c.size().rateD, "rate D, " + start);
            put(w, "ATC_RAT_" + axis + "_FLTD", gyro / 2, "D filter, " + start);
            put(w, "ATC_RAT_" + axis + "_FLTT", gyro / 2, "target filter, " + start);
        }
        put(w, "ATC_RAT_YAW_P", 0.18, "yaw P, " + start);
        put(w, "ATC_RAT_YAW_I", 0.018, "yaw I, " + start);
        put(w, "ATC_RAT_YAW_FLTE", 2, "yaw filter, " + start);
        put(w, "ATC_RAT_YAW_FLTT", gyro / 2, "yaw target filter, " + start);
        double acc = accelRollPitch(p);
        put(w, "ATC_ACCEL_R_MAX", acc, "roll accel, " + start);
        put(w, "ATC_ACCEL_P_MAX", acc, "pitch accel, " + start);
        put(w, "ATC_ACCEL_Y_MAX", p <= 7 ? 27_000 : 18_000, "yaw accel, " + start);
        put(w, "MOT_THST_EXPO", thrustExpo(p), "thrust curve, " + start);
        if (c.cells() > 0) {
            put(w, "MOT_BAT_VOLT_MAX", Math.round(4.2 * c.cells() * 100) / 100.0, "full pack, for steady thrust");
            put(w, "MOT_BAT_VOLT_MIN", Math.round(3.0 * c.cells() * 100) / 100.0, "empty pack, for steady thrust");
        }
    }

    /** The mode switch's modes with AUTOTUNE in its position (0: as they are). */
    public static int[] modesWith(int[] modes, int autotunePos) {
        int[] m = modes.clone();
        if (autotunePos >= 1 && autotunePos <= m.length) m[autotunePos - 1] = MODE_AUTOTUNE;
        return m;
    }

    /** "5\": gyro 75 Hz, rate P 0.070 D 0.0012 - starting points" */
    public static String summary(Size s) {
        return String.format(Locale.ROOT, "%s: gyro %d Hz, rate P %.3f D %.4f - starting points", s.label,
                (int) gyroFilterHz(s.propInch), s.ratePI, s.rateD);
    }

    private static void put(Map<String, ArduPlan.Wanted> w, String name, double v, String why) {
        w.put(name, new ArduPlan.Wanted((float) v, why));
    }
}
