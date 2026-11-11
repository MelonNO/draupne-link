package de.droiddrone.pc;

import de.droiddrone.common.Observe;

/**
 * Which way the connected drone looks, for the map's arrow and its camera's view (1.0.62, the pilot: "the
 * orientation in map does not match the aircraft orientation, both the icon and sector, when in Overwatch").
 * Betaflight's yaw is a compass heading only with a compass; without one it is the gyro's drift since power-up. Then
 * the phone's own sensor is the heading (phone app pc.48 sends its record to the pilot too, as to observers), and
 * with neither the heading is unknown - better nothing on the map than a wrong way.
 */
public final class LiveHeading {
    /** The phone's record older than this: none. */
    static final long RECORD_STALE_MS = 3000;
    /** The attitude older than this: none. */
    static final long ATTITUDE_STALE_MS = 2000;

    private LiveHeading() {
    }

    /** Degrees clockwise from north, or NaN: not known. */
    public static double of(FcTelemetry t) {
        return t == null ? Double.NaN : of(t, MonoClock.ms());
    }

    static double of(FcTelemetry t, long now) {
        FcTelemetry.Attitude att = t.getAttitude();
        double fcYaw = att != null && now - att.receivedAt() < ATTITUDE_STALE_MS ? att.headingDeg() : Double.NaN;
        FcTelemetry.Observed ob = t.getObserved();
        Observe.Position p = ob != null && now - ob.receivedAt() < RECORD_STALE_MS ? ob.position() : null;
        Boolean compass = t.fcCompass();
        if (p != null && p.fcCompass()) compass = true;
        if (Boolean.TRUE.equals(compass)) return !Double.isNaN(fcYaw) ? fcYaw : p != null ? p.fcHeadingDeg : Double.NaN;
        if (p != null && !Double.isNaN(p.phoneAzimuthDeg)) return p.phoneAzimuthDeg;
        return compass == null ? fcYaw : Double.NaN; // no word on a compass (an older phone app): the yaw, as before
    }
}
