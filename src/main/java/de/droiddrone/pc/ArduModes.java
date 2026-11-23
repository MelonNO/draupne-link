package de.droiddrone.pc;

/**
 * ArduPilot Copter's flight modes (the heartbeat's custom_mode), as the pilot sees them: the number ArduPilot uses and
 * the name with spaces (ALT HOLD, SMART RTL). Fleet PRD AP-3: the mode name in RC CHECK, the OSD and FLEET.
 * Other vehicle types (Plane, Rover) number their modes differently: {@link #name(int, int)} says MODE n for them.
 */
public final class ArduModes {
    private ArduModes() {
    }

    public static final int STABILIZE = 0, ACRO = 1, ALT_HOLD = 2, AUTO = 3, GUIDED = 4, LOITER = 5, RTL = 6,
            CIRCLE = 7, LAND = 9, DRIFT = 11, SPORT = 13, FLIP = 14, AUTOTUNE = 15, POSHOLD = 16, BRAKE = 17,
            THROW = 18, AVOID_ADSB = 19, GUIDED_NOGPS = 20, SMART_RTL = 21, FLOWHOLD = 22, FOLLOW = 23, ZIGZAG = 24,
            SYSTEMID = 25, AUTOROTATE = 26, AUTO_RTL = 27, TURTLE = 28;

    private static final String[] COPTER = new String[29];

    static {
        COPTER[STABILIZE] = "STABILIZE";
        COPTER[ACRO] = "ACRO";
        COPTER[ALT_HOLD] = "ALT HOLD";
        COPTER[AUTO] = "AUTO";
        COPTER[GUIDED] = "GUIDED";
        COPTER[LOITER] = "LOITER";
        COPTER[RTL] = "RTL";
        COPTER[CIRCLE] = "CIRCLE";
        COPTER[LAND] = "LAND";
        COPTER[DRIFT] = "DRIFT";
        COPTER[SPORT] = "SPORT";
        COPTER[FLIP] = "FLIP";
        COPTER[AUTOTUNE] = "AUTOTUNE";
        COPTER[POSHOLD] = "POS HOLD";
        COPTER[BRAKE] = "BRAKE";
        COPTER[THROW] = "THROW";
        COPTER[AVOID_ADSB] = "AVOID ADSB";
        COPTER[GUIDED_NOGPS] = "GUIDED NOGPS";
        COPTER[SMART_RTL] = "SMART RTL";
        COPTER[FLOWHOLD] = "FLOW HOLD";
        COPTER[FOLLOW] = "FOLLOW";
        COPTER[ZIGZAG] = "ZIGZAG";
        COPTER[SYSTEMID] = "SYSTEM ID";
        COPTER[AUTOROTATE] = "AUTOROTATE";
        COPTER[AUTO_RTL] = "AUTO RTL";
        COPTER[TURTLE] = "TURTLE";
    }

    /**
     * MAVLink's MAV_TYPE values that ArduPilot Copter flies: quad, coaxial, helicopter, hexa, octo, tri, dodeca, deca.
     * 0 (generic, or not reported yet) counts as a copter: the fleet work is Copter only (PRD AP-13).
     */
    public static boolean isCopter(int mavType) {
        return switch (mavType) {
            case 0, 2, 3, 4, 13, 14, 15, 29, 35 -> true;
            default -> false;
        };
    }

    /** The mode's name for a vehicle of this MAV_TYPE: Copter's name, else MODE n (also for a number Copter lacks). */
    public static String name(int customMode, int mavType) {
        if (isCopter(mavType) && customMode >= 0 && customMode < COPTER.length && COPTER[customMode] != null) {
            return COPTER[customMode];
        }
        return "MODE " + customMode;
    }

    /** Copter's modes that bring the drone home or down by themselves (a failsafe may have chosen them). */
    public static boolean isReturnOrLand(int customMode) {
        return customMode == RTL || customMode == SMART_RTL || customMode == AUTO_RTL || customMode == LAND;
    }
}
