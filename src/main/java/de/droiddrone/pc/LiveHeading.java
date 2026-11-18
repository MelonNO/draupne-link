package de.droiddrone.pc;

import de.droiddrone.common.BestHeading;
import de.droiddrone.common.Observe;
import de.droiddrone.common.PositionFilter;

/**
 * Which way the connected drone looks, for the map's arrow, its camera's view and the OSD's heading (1.0.62, the
 * pilot: "the orientation in map does not match the aircraft orientation, both the icon and sector, when in
 * Overwatch"; 1.0.71, after flying drones with a GPS and no compass: "compass heading is wrong it spins around
 * randomly").
 * Betaflight's yaw is a compass heading only with a compass; without one it is the gyro's drift since power-up, and
 * the phone's own compass sits beside the motors' currents while they run. The rule is {@link BestHeading}'s, one
 * per link (it lives in the link's {@link FcTelemetry}): the FC's compass while it agrees with the flight, else the
 * way the drone travels, else - disarmed and still - the phone's compass, else the last heading, marked stale.
 * With nothing at all the heading is unknown - better nothing on the map than a wrong way.
 */
public final class LiveHeading {
    /** The phone's record older than this: none. */
    static final long RECORD_STALE_MS = 3000;
    /** The attitude older than this: none. */
    static final long ATTITUDE_STALE_MS = 2000;
    /** A GPS report older than this says nothing about the way travelled now. */
    static final long GPS_STALE_MS = 2000;
    /** Armed or disarmed by a status no older than this; otherwise not known. */
    static final long STATUS_STALE_MS = 2000;
    /** The phone's own fix older than this when sent: no course. */
    static final int PHONE_FIX_MAX_AGE_S = 3;

    /** @param deg degrees clockwise from north, NaN: not known; source: {@link BestHeading}'s; stale: an older one kept */
    public record Now(double deg, int source, boolean stale, boolean approximate) {
        public boolean known() {
            return !Double.isNaN(deg);
        }
    }

    private LiveHeading() {
    }

    /** Degrees clockwise from north, or NaN: not known. */
    public static double of(FcTelemetry t) {
        return t == null ? Double.NaN : of(t, MonoClock.ms());
    }

    static double of(FcTelemetry t, long now) {
        return now(t, now).deg();
    }

    /** The heading with where it is from. */
    public static Now now(FcTelemetry t) {
        return t == null ? new Now(Double.NaN, BestHeading.NONE, false, false) : now(t, MonoClock.ms());
    }

    static Now now(FcTelemetry t, long now) {
        FcTelemetry.Attitude att = t.getAttitude();
        double fcYaw = att != null && now - att.receivedAt() < ATTITUDE_STALE_MS ? att.headingDeg() : Double.NaN;
        FcTelemetry.Observed ob = t.getObserved();
        Observe.Position p = ob != null && now - ob.receivedAt() < RECORD_STALE_MS ? ob.position() : null;
        Boolean compass = t.fcCompass();
        if (p != null && p.fcCompass()) compass = true;
        if (Double.isNaN(fcYaw) && p != null) fcYaw = p.fcHeadingDeg;

        boolean fix = false;
        double speed = Double.NaN, course = Double.NaN;
        FcTelemetry.Gps gps = t.getGps();
        FcTelemetry.PhonePosition phone = t.getPhonePosition();
        if (gps != null && now - gps.receivedAt() < GPS_STALE_MS && gps.fix() >= 1 && gps.sats() >= PositionFilter.MIN_SATS) {
            fix = true;
            speed = gps.speedKmh();
            course = gps.courseDeg();
        } else if (phone != null && now - phone.receivedAt() < GPS_STALE_MS && phone.ageS() <= PHONE_FIX_MAX_AGE_S && phone.positionOk()) {
            fix = true;
            speed = phone.speedKmh();
            course = phone.courseDeg();
        }
        Boolean armed = t.isArmedNow(now, STATUS_STALE_MS) ? Boolean.TRUE : t.isDisarmedNow(now, STATUS_STALE_MS) ? Boolean.FALSE : null;
        BestHeading h = t.heading();
        synchronized (h) {
            h.update(now, compass, fcYaw, fix, speed, course, armed,
                    p == null ? Double.NaN : p.phoneAzimuthDeg, p == null ? -1 : p.phoneAccuracy, p == null ? -1 : p.phoneErrorDeg);
            return new Now(h.deg(), h.source(), h.stale(), h.approximate());
        }
    }
}
