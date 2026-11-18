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

package de.droiddrone.common;

/**
 * Which way a drone points, from what it has (the pilot, 2026-11-18, flying drones with a GPS and no compass:
 * "compass heading is wrong it spins around randomly"; "it does not spin when on ground, but if it was wrong in the
 * air and then it lands it is still wrong"). Without a compass Betaflight's yaw is the gyro's drift, and the phone's
 * own compass sits beside the motors' currents while they run. So, one drone's heading over time:
 * <ol>
 * <li>the flight controller's yaw, when it has a compass - and that compass agrees with the way the drone travels
 * (a compass more than {@link #COMPASS_OFF_DEG} off the GPS course for {@link #COMPASS_OFF_MS} of fast flight is not
 * trusted again on this link);</li>
 * <li>else the way it travels (the GPS course) at {@link #TRAVEL_KMH} or more - steady, though not the nose's way in
 * a sideways slide;</li>
 * <li>else, disarmed and still: the phone's compass (the motors are off);</li>
 * <li>else the last heading there was, marked {@link #HELD} - shown greyed, never a new guess.</li>
 * </ol>
 * Pure and with a memory: one per drone link. Both apps use it, the PC's tests the cases.
 */
public final class BestHeading {
    public static final int NONE = 0, FC_COMPASS = 1, TRAVEL = 2, PHONE = 3, HELD = 4;

    /** From this ground speed on the GPS course is the heading. */
    public static final double TRAVEL_KMH = 7;
    /** The compass is checked against the course from this speed on. */
    public static final double COMPASS_CHECK_KMH = 20;
    public static final double COMPASS_OFF_DEG = 60;
    public static final long COMPASS_OFF_MS = 8000;
    /** Android's SENSOR_STATUS_ACCURACY_MEDIUM: below it the phone's compass wants calibrating. */
    public static final int PHONE_ACCURACY_GOOD = 2;
    /** The phone sensor's own heading error estimate past this: approximate. */
    public static final int PHONE_ERROR_MAX_DEG = 25;

    private double deg = Double.NaN;
    private int source = NONE;
    private boolean approximate;
    private boolean compassDistrusted;
    private long compassOffSince = -1;

    /**
     * What is known now. Unknown values: NaN for degrees and speed, null for the flags, -1 for the phone's figures.
     * @param fcCompass       the flight controller reports a compass (null: no word)
     * @param fcYawDeg        its yaw
     * @param gpsFix          the GPS course and speed are a fix's
     * @param speedKmh        ground speed
     * @param courseDeg       the way it travels
     * @param armed           null: not known (then never taken for disarmed)
     * @param phoneAzimuthDeg where the phone's camera looks, by the phone's own sensor
     * @param phoneAccuracy   Android's sensor accuracy 0..3
     * @param phoneErrorDeg   the sensor's own error estimate
     */
    public void update(long now, Boolean fcCompass, double fcYawDeg, boolean gpsFix, double speedKmh, double courseDeg,
                       Boolean armed, double phoneAzimuthDeg, int phoneAccuracy, int phoneErrorDeg) {
        boolean course = gpsFix && !Double.isNaN(courseDeg) && !Double.isNaN(speedKmh);
        boolean compass = Boolean.TRUE.equals(fcCompass) && !Double.isNaN(fcYawDeg);
        if (compass && !compassDistrusted && course && speedKmh >= COMPASS_CHECK_KMH) {
            if (apart(fcYawDeg, courseDeg) > COMPASS_OFF_DEG) {
                if (compassOffSince < 0) compassOffSince = now;
                if (now - compassOffSince >= COMPASS_OFF_MS) compassDistrusted = true;
            } else {
                compassOffSince = -1;
            }
        } else if (!compassDistrusted) {
            compassOffSince = -1;
        }
        if (compass && !compassDistrusted) {
            set(fcYawDeg, FC_COMPASS, false);
        } else if (course && speedKmh >= TRAVEL_KMH) {
            set(courseDeg, TRAVEL, false);
        } else if (Boolean.FALSE.equals(armed) && !Double.isNaN(phoneAzimuthDeg)) {
            set(phoneAzimuthDeg, PHONE, phoneAccuracy < PHONE_ACCURACY_GOOD || phoneErrorDeg > PHONE_ERROR_MAX_DEG);
        } else if (!Double.isNaN(deg)) {
            source = HELD;
        } else {
            source = NONE;
        }
    }

    private void set(double d, int from, boolean approx) {
        deg = ((d % 360) + 360) % 360;
        source = from;
        approximate = approx;
    }

    /** Degrees clockwise from north; NaN: none yet. */
    public double deg() {
        return deg;
    }

    /** Where {@link #deg} is from: {@link #FC_COMPASS}, {@link #TRAVEL}, {@link #PHONE}, {@link #HELD} or {@link #NONE}. */
    public int source() {
        return source;
    }

    /** An older heading kept: nothing says which way it points now. */
    public boolean stale() {
        return source == HELD;
    }

    /** The phone's compass with low accuracy. */
    public boolean approximate() {
        return source == PHONE && approximate;
    }

    /** The flight controller's compass disagreed with the flight's direction and is no longer used on this link. */
    public boolean compassDistrusted() {
        return compassDistrusted;
    }

    public void reset() {
        deg = Double.NaN;
        source = NONE;
        approximate = false;
        compassDistrusted = false;
        compassOffSince = -1;
    }

    /** Degrees between two headings, 0..180. */
    public static double apart(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    public static String name(int source) {
        switch (source) {
            case FC_COMPASS:
                return "compass";
            case TRAVEL:
                return "travel";
            case PHONE:
                return "phone";
            case HELD:
                return "held";
            default:
                return "none";
        }
    }
}
