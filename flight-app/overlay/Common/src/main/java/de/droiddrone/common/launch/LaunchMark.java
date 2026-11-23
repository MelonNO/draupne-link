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
 * Fleet PRD AL-1 / AL-2 / AL-4 (FA1, FA3): the pilot's SAFE TO AUTOLAUNCH mark, as the reserve's phone keeps it -
 * where the drone stood, which way it pointed, when, and the follower floor the pilot confirmed it can climb straight
 * up to with nothing above it. The mark has an id the PC's launch order must name: an order for an older mark never
 * launches a drone marked again since.
 * <p>
 * Cleared by itself (AL-4) when the drone moves (more than {@link #MOVED_M} m or {@link #TURNED_DEG} deg of heading
 * from where it was marked, read with a good GPS fix), when it launches, or when it is marked OUT OF ACTION; and by the
 * pilot. Once cleared it stays cleared: a new mark is a new object with a new id.
 * Not thread-safe: one thread (the phone's service timer) owns it.
 */
public final class LaunchMark {
    /** AL-13: within 10 m and 30 deg of heading of where it was marked. */
    public static final double MOVED_M = 10, TURNED_DEG = 30;

    public static final int CLEARED_NOT = 0, CLEARED_MOVED = 1, CLEARED_LAUNCHED = 2, CLEARED_OUT_OF_ACTION = 3,
            CLEARED_PILOT = 4, CLEARED_CHECK_FAILED = 5;

    public final int id;
    public final double lat, lon, headingDeg;
    public final long markedAtMs;
    /** The follower floor at marking, metres above the ground: the climb the pilot confirmed is clear (AL-2). */
    public final double floorM;
    private int cleared = CLEARED_NOT;

    public LaunchMark(int id, double lat, double lon, double headingDeg, long markedAtMs, double floorM) {
        if (id == 0) throw new IllegalArgumentException("mark id 0 is 'none'");
        this.id = id;
        this.lat = lat;
        this.lon = lon;
        this.headingDeg = headingDeg;
        this.markedAtMs = markedAtMs;
        this.floorM = floorM;
    }

    public boolean valid() {
        return cleared == CLEARED_NOT;
    }

    /** Why it was cleared, CLEARED_NOT while valid. */
    public int clearedWhy() {
        return cleared;
    }

    /** Cleared for good (a first reason is kept). */
    public void clear(int why) {
        if (cleared == CLEARED_NOT && why != CLEARED_NOT) cleared = why;
    }

    /** The place and heading are those it was marked at (AL-13's 10 m / 30 deg). */
    public boolean stillAt(double nowLat, double nowLon, double nowHeadingDeg) {
        return LaunchGeo.distanceM(lat, lon, nowLat, nowLon) <= MOVED_M
                && LaunchGeo.headingDiff(headingDeg, nowHeadingDeg) <= TURNED_DEG;
    }

    /**
     * While it rests (FA3): a position and heading read now. With a good fix, one outside the mark's limits clears it
     * (MOVED). Without a fix nothing is concluded - the check before arming reads it again.
     * @return true while still valid
     */
    public boolean observe(double nowLat, double nowLon, double nowHeadingDeg, boolean goodFix) {
        if (!valid()) return false;
        if (goodFix && !stillAt(nowLat, nowLon, nowHeadingDeg)) clear(CLEARED_MOVED);
        return valid();
    }
}
