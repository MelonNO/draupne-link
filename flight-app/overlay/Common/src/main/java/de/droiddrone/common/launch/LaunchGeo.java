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

/** Fleet PRD area F: the few sums the launch rules need (Java 11, no Android). */
public final class LaunchGeo {
    private LaunchGeo() {
    }

    private static final double EARTH_M = 6_371_000.0;

    /** Great-circle distance in metres. */
    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** The smaller angle between two headings, 0..180. */
    public static double headingDiff(double a, double b) {
        double d = Math.abs(((a - b) % 360 + 360) % 360);
        return d > 180 ? 360 - d : d;
    }

    /**
     * How far the drone leans from level, 0..180 degrees, from roll and pitch (an upside-down drone is 180, one on its
     * side 90): the angle between its up axis and the vertical.
     */
    public static double tiltDeg(double rollDeg, double pitchDeg) {
        double c = Math.cos(Math.toRadians(rollDeg)) * Math.cos(Math.toRadians(pitchDeg));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
    }
}
