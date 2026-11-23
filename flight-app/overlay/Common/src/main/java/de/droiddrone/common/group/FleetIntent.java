/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone (GPL v3 or later, see the project's LICENSE).
 */

package de.droiddrone.common.group;

/**
 * DRAUPNE fleet: what a follower's group rules want its flight controller to do now, in neutral words; the phone's
 * MAVLink side turns it into commands, and only when it changes. Immutable. Java 11.
 * <ul>
 * <li>{@link #NONE}: nothing from the rules.</li>
 * <li>{@link #LOITER}: hold the place (ArduPilot LOITER).</li>
 * <li>{@link #GUIDED}: fly to lat / lon / altAmsl at up to speedMs (NaN: the FC's own speed), nose to yawDeg (NaN:
 *     keep), with velE / velN (NaN: none) as the target's velocity feed-forward - SET_POSITION_TARGET_GLOBAL_INT with
 *     position and velocity, so a follower keeps its cell in a moving group instead of trailing it.</li>
 * <li>{@link #ALT_HOLD}: hold the height with centred sticks, no position (no GNSS: the drone drifts with the wind).</li>
 * <li>{@link #RTL}: set RTL_ALT to rtlAltRelM (metres above home) and switch to plain RTL (to its own rally point).</li>
 * <li>{@link #LAND}: land where it is.</li>
 * </ul>
 */
public final class FleetIntent {
    public static final int NONE = 0, LOITER = 1, GUIDED = 2, ALT_HOLD = 3, RTL = 4, LAND = 5;

    public final int kind;
    public final double lat, lon, altAmsl, yawDeg, speedMs, rtlAltRelM, velE, velN;

    private FleetIntent(int kind, double lat, double lon, double altAmsl, double yawDeg, double speedMs, double rtlAltRelM,
                        double velE, double velN) {
        this.kind = kind;
        this.lat = lat;
        this.lon = lon;
        this.altAmsl = altAmsl;
        this.yawDeg = yawDeg;
        this.speedMs = speedMs;
        this.rtlAltRelM = rtlAltRelM;
        this.velE = velE;
        this.velN = velN;
    }

    private static FleetIntent of(int kind) {
        return new FleetIntent(kind, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    public static final FleetIntent NOTHING = of(NONE);
    public static final FleetIntent HOLD = of(LOITER);
    public static final FleetIntent HEIGHT_ONLY = of(ALT_HOLD);
    public static final FleetIntent DOWN = of(LAND);

    public static FleetIntent guided(double lat, double lon, double altAmsl, double yawDeg, double speedMs) {
        return guided(lat, lon, altAmsl, yawDeg, speedMs, Double.NaN, Double.NaN);
    }

    public static FleetIntent guided(double lat, double lon, double altAmsl, double yawDeg, double speedMs, double velE, double velN) {
        return new FleetIntent(GUIDED, lat, lon, altAmsl, yawDeg, speedMs, Double.NaN, velE, velN);
    }

    public static FleetIntent rtl(double rtlAltRelM) {
        return new FleetIntent(RTL, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, rtlAltRelM, Double.NaN, Double.NaN);
    }

    public static String kindName(int kind) {
        switch (kind) {
            case NONE: return "NONE";
            case LOITER: return "LOITER";
            case GUIDED: return "GUIDED";
            case ALT_HOLD: return "ALT_HOLD";
            case RTL: return "RTL";
            case LAND: return "LAND";
            default: return "KIND " + kind;
        }
    }

    @Override
    public String toString() {
        if (kind == GUIDED) return "GUIDED " + lat + "," + lon + " " + Math.round(altAmsl) + " m";
        if (kind == RTL) return "RTL at " + Math.round(rtlAltRelM) + " m";
        return kindName(kind);
    }
}
