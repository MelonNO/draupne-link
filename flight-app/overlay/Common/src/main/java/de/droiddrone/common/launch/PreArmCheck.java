/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone.
 *
 *  DroidDrone is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DroidDrone is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidDrone.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.common.launch;

/**
 * Fleet PRD AL-3 / AL-13 (FA2, FA13): what the reserve's phone checks when it wakes for a launch, before the motors
 * may start. All of it on the phone, from what its flight controller and its own sensors read - no 4G round trip.
 * <ul>
 *   <li>tilt within {@link #MAX_TILT_DEG} deg of level: upside down or on its side never arms (TIPPED);</li>
 *   <li>within 10 m and 30 deg of heading of where it was marked (MOVED; {@link LaunchMark});</li>
 *   <li>no motion in the last {@link #STILL_MS} ms - not being carried (CARRIED): it needs that long of samples
 *       after waking, so the answer is WAIT until then;</li>
 *   <li>IMU and compass healthy, the FC healthy (FC UNHEALTHY);</li>
 *   <li>a GPS fix (NO GPS), the battery over the launch minimum (LOW BATTERY), the mark still valid (MARK GONE).</li>
 * </ul>
 * AL-13's failures (TIPPED, MOVED, CARRIED, FC UNHEALTHY) clear the mark: the pilot looks at the phone's picture and
 * marks it again. AL-3's (NO GPS, LOW BATTERY) skip the drone and leave the mark: a fix may come, a pack may be swapped.
 * Battery never blocks arming in normal flight (PRD FD-5); here it decides only whether this reserve is sent.
 */
public final class PreArmCheck {
    public static final double MAX_TILT_DEG = 45;
    public static final long STILL_MS = 5000;
    /** A sample that moved: acceleration this far from 1 g, or this fast a turn. Resting jitter stays well under. */
    public static final double MOTION_G = 0.15, MOTION_DPS = 15;
    /** The launch minimum (the PC sends its own with the order; this when it sends none). */
    public static final int DEFAULT_MIN_BATTERY_PERCENT = 40;
    /** The FC's readings older than this are none (the phone asks health and GPS once a second at 10 Hz telemetry). */
    public static final long MAX_FC_AGE_MS = 3000;

    public static final int OK = 0, WAIT = 1, TIPPED = 2, MOVED = 3, CARRIED = 4, NO_GPS = 5, LOW_BATTERY = 6,
            FC_UNHEALTHY = 7, MARK_GONE = 8;

    /** The words the pilot reads (AL-13: MOVED or TIPPED with the phone's picture and grid). */
    public static String word(int result) {
        switch (result) {
            case OK: return "OK";
            case WAIT: return "CHECKING";
            case TIPPED: return "TIPPED";
            case MOVED: return "MOVED";
            case CARRIED: return "CARRIED";
            case NO_GPS: return "NO GPS";
            case LOW_BATTERY: return "LOW BATTERY";
            case FC_UNHEALTHY: return "FC UNHEALTHY";
            case MARK_GONE: return "MARK GONE";
            default: return "?";
        }
    }

    /** AL-13's failures clear the mark; AL-3's only skip the drone. */
    public static boolean clearsMark(int result) {
        return result == TIPPED || result == MOVED || result == CARRIED || result == FC_UNHEALTHY;
    }

    /** What is read now. Unknown numbers are NaN; the booleans false when not known (a check fails closed). */
    public static final class Reading {
        public double rollDeg = Double.NaN, pitchDeg = Double.NaN, headingDeg = Double.NaN;
        public double lat = Double.NaN, lon = Double.NaN;
        public boolean gpsFix3d;
        public int batteryPercent = -1;
        public boolean imuHealthy, compassHealthy, fcHealthy;
        /**
         * How old the oldest of these FC readings is (attitude, health, GPS), ms; 0: fresh. Older than {@link #MAX_FC_AGE_MS}
         * fails closed (FC UNHEALTHY): an FC rebooting mid-launch sends nothing, and its last words read as OK.
         */
        public long fcAgeMs;
    }

    private final int minBatteryPercent;
    private long samplesSince = -1, lastMotionAt = Long.MIN_VALUE;

    public PreArmCheck(int minBatteryPercent) {
        this.minBatteryPercent = minBatteryPercent > 0 ? minBatteryPercent : DEFAULT_MIN_BATTERY_PERCENT;
    }

    /**
     * One motion sample (the FC's or the phone's accelerometer and gyro), at the rate they come.
     * @param accelG the acceleration's size in g (1 at rest); NaN: not read
     * @param gyroDps the turn rate's size in deg/s; NaN: not read
     */
    public void motion(long nowMs, double accelG, double gyroDps) {
        if (Double.isNaN(accelG) && Double.isNaN(gyroDps)) return;
        if (samplesSince < 0) samplesSince = nowMs;
        boolean moved = !Double.isNaN(accelG) && Math.abs(accelG - 1) > MOTION_G
                || !Double.isNaN(gyroDps) && gyroDps > MOTION_DPS;
        if (moved) lastMotionAt = nowMs;
    }

    /** Ran once a launch begins: the motion window starts anew. */
    public void restart() {
        samplesSince = -1;
        lastMotionAt = Long.MIN_VALUE;
    }

    /** The answer now; the first failure found, in the order the pilot most needs to hear it. */
    public int check(long nowMs, Reading r, LaunchMark mark) {
        if (mark == null || !mark.valid()) return MARK_GONE;
        if (r.fcAgeMs < 0 || r.fcAgeMs > MAX_FC_AGE_MS) return FC_UNHEALTHY; // nothing new from the FC: fails closed
        if (!r.imuHealthy || !r.compassHealthy || !r.fcHealthy) return FC_UNHEALTHY;
        if (Double.isNaN(r.rollDeg) || Double.isNaN(r.pitchDeg)) return FC_UNHEALTHY; // no attitude: fails closed
        if (LaunchGeo.tiltDeg(r.rollDeg, r.pitchDeg) > MAX_TILT_DEG) return TIPPED;
        if (lastMotionAt != Long.MIN_VALUE && nowMs - lastMotionAt < STILL_MS) return CARRIED;
        if (!r.gpsFix3d || Double.isNaN(r.lat) || Double.isNaN(r.lon)) return NO_GPS;
        if (Double.isNaN(r.headingDeg) || !mark.stillAt(r.lat, r.lon, r.headingDeg)) return MOVED;
        if (r.batteryPercent < 0 || r.batteryPercent > 100 || r.batteryPercent < minBatteryPercent) return LOW_BATTERY; // 255: not known
        if (samplesSince < 0 || nowMs - samplesSince < STILL_MS) return WAIT; // not yet 5 s of samples
        return OK;
    }
}
