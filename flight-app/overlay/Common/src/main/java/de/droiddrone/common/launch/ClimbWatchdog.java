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
 * Fleet PRD AL-14 / AL-16 (FA14 - FA16): the reserve's own watch over its vertical climb, on its phone (no 4G round
 * trip). From the moment the motors are told to climb until the drone passes {@link #endHeightM} (the follower floor
 * - the launch column's dangerous part: trees, wires, a branch overhead):
 * <ul>
 *   <li>TILT: leaning more than {@link #MAX_TILT_DEG} deg;</li>
 *   <li>IMPACT: an acceleration over {@link #IMPACT_G} g (the FC's or the phone's accelerometer, the larger);</li>
 *   <li>STALLED: climbing slower than {@link #MIN_CLIMB_MPS} m/s for {@link #STALL_MS} ms (stuck in a tree), counted
 *       from {@link #SPOOL_MS} ms after the climb began (the motors spool up and the drone leaves the ground);</li>
 *   <li>NO LIFT: motors near full ({@link #FULL_THROTTLE_PERCENT} %) for {@link #FULL_MS} ms without climbing;</li>
 *   <li>DRIFT: more than {@link #MAX_DRIFT_M} m sideways from the column's centre;</li>
 *   <li>MOTOR: one motor's RPM under {@link #MIN_RPM_RATIO} of the motors' mean while they work - only where ESC
 *       telemetry exists (NaN otherwise).</li>
 * </ul>
 * Any of them: ABORT - motors off at once, always (FA16). The verdict is sticky. A gusty climb (lean spikes, short
 * dips in the climb rate, a few metres of drift) is none of them.
 * Pure: the phone feeds the samples (FC attitude, accelerometer, climb rate, motor output, position), the tests theirs.
 */
public final class ClimbWatchdog {
    public static final double MAX_TILT_DEG = 50, IMPACT_G = 3, MIN_CLIMB_MPS = 0.5, MAX_DRIFT_M = 3,
            FULL_THROTTLE_PERCENT = 90, MIN_RPM_RATIO = 0.5;
    public static final long STALL_MS = 4000, FULL_MS = 2000, SPOOL_MS = 1500;
    /** A sample later than this after the last one: the feed stopped - fail closed. */
    public static final long SILENT_MS = 1000;

    public static final int WATCHING = 0, DONE = 1, ABORT_TILT = 2, ABORT_IMPACT = 3, ABORT_STALLED = 4,
            ABORT_NO_LIFT = 5, ABORT_DRIFT = 6, ABORT_MOTOR = 7, ABORT_NO_DATA = 8;

    public static String word(int verdict) {
        switch (verdict) {
            case WATCHING: return "CLIMBING";
            case DONE: return "CLIMB DONE";
            case ABORT_TILT: return "TILT";
            case ABORT_IMPACT: return "IMPACT";
            case ABORT_STALLED: return "CLIMB STALLED";
            case ABORT_NO_LIFT: return "NO LIFT";
            case ABORT_DRIFT: return "DRIFT";
            case ABORT_MOTOR: return "MOTOR";
            case ABORT_NO_DATA: return "NO DATA";
            default: return "?";
        }
    }

    public static boolean isAbort(int verdict) {
        return verdict >= ABORT_TILT;
    }

    /** One reading during the climb. NaN: not read (rpmRatio NaN without ESC telemetry). */
    public static final class Sample {
        public long atMs;
        public double tiltDeg = Double.NaN;
        /** The larger of the FC's and the phone's acceleration size, in g. */
        public double accelG = Double.NaN;
        /** Up positive, m/s. */
        public double climbMps = Double.NaN;
        /** Motor output 0..100 (ArduPilot's throttle out). */
        public double throttlePercent = Double.NaN;
        /** Sideways from the launch column's centre, m. */
        public double driftM = Double.NaN;
        /** Height above the take-off point, m. */
        public double heightM = Double.NaN;
        /** The slowest motor's RPM over the motors' mean; NaN without ESC telemetry. */
        public double rpmRatio = Double.NaN;
    }

    public final double endHeightM;
    private long startedAt = -1, lastAt = -1, slowSince = -1, fullSince = -1;
    private int verdict = WATCHING;

    /** @param endHeightM the height above take-off where the watch ends (the floor) */
    public ClimbWatchdog(double endHeightM) {
        this.endHeightM = endHeightM;
    }

    public int verdict() {
        return verdict;
    }

    /** The climb began now (the take-off was commanded). */
    public void start(long nowMs) {
        startedAt = nowMs;
        lastAt = nowMs;
    }

    /** Between samples: a feed that went silent aborts (no blind climbs). */
    public int tick(long nowMs) {
        if (verdict != WATCHING || startedAt < 0) return verdict;
        if (nowMs - lastAt > SILENT_MS) verdict = ABORT_NO_DATA;
        return verdict;
    }

    public int feed(Sample s) {
        if (verdict != WATCHING) return verdict;
        if (startedAt < 0) start(s.atMs);
        if (s.atMs - lastAt > SILENT_MS) return verdict = ABORT_NO_DATA;
        lastAt = Math.max(lastAt, s.atMs);
        if (!Double.isNaN(s.heightM) && s.heightM >= endHeightM) return verdict = DONE;
        if (!Double.isNaN(s.tiltDeg) && s.tiltDeg > MAX_TILT_DEG) return verdict = ABORT_TILT;
        if (!Double.isNaN(s.accelG) && s.accelG > IMPACT_G) return verdict = ABORT_IMPACT;
        if (!Double.isNaN(s.driftM) && s.driftM > MAX_DRIFT_M) return verdict = ABORT_DRIFT;
        boolean working = !Double.isNaN(s.throttlePercent) && s.throttlePercent > 30;
        if (working && !Double.isNaN(s.rpmRatio) && s.rpmRatio < MIN_RPM_RATIO) return verdict = ABORT_MOTOR;
        boolean spooled = s.atMs - startedAt >= SPOOL_MS;
        // no climb rate read counts as not climbing: fails closed
        boolean slow = Double.isNaN(s.climbMps) || s.climbMps < MIN_CLIMB_MPS;
        if (spooled && slow) {
            if (slowSince < 0) slowSince = s.atMs;
            if (s.atMs - slowSince >= STALL_MS) return verdict = ABORT_STALLED;
        } else {
            slowSince = -1;
        }
        boolean full = !Double.isNaN(s.throttlePercent) && s.throttlePercent >= FULL_THROTTLE_PERCENT;
        if (spooled && full && slow) {
            if (fullSince < 0) fullSince = s.atMs;
            if (s.atMs - fullSince >= FULL_MS) return verdict = ABORT_NO_LIFT;
        } else {
            fullSince = -1;
        }
        return verdict;
    }
}
