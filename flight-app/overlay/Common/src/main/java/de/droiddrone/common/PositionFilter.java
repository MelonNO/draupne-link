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
 * Which of a GPS's readings are the drone's place (the pilot, 2026-11-18: "sometimes the gps coords is all wrong and
 * the location data jumps around randomly" - and it must not "stay stuck on a wrong location if it boots wrong then
 * jumps far to the correct place"; the recordings of those flights have rows without a fix at 0/0 and at places
 * thousands of km away, taken as positions).
 * One filter per source (the flight controller's GPS, the phone's):
 * <ul>
 * <li>a reading without a fix, with too few satellites, at 0/0 or off the globe is no position;</li>
 * <li>a reading the drone could have flown to from the place held (under {@link #MAX_KMH}, with some slack) is the
 * new place;</li>
 * <li>a reading farther off than that is held back as a candidate. Readings that agree with each other -
 * {@link #AGREE} different ones, or the same one for {@link #SAME_MS} - become the place: a wrong start gives way to
 * the right place, while one leap between two good readings is dropped;</li>
 * <li>the first reading with a fix is the place at once (a drone that connects shows where it is without a wait);
 * should it be a wrong one, the readings after it agree with each other and take over, as above.</li>
 * </ul>
 * Pure: both apps use it, the PC's tests the cases. Times are a monotonic clock's milliseconds.
 */
public final class PositionFilter {
    /** {@link #offer}: the reading is the place now. */
    public static final int TAKEN = 0;
    /** {@link #offer}: no position in this reading (no fix, 0/0 ...). The place held stays. */
    public static final int NO_FIX = 1;
    /** {@link #offer}: a position, held back - too far from the place held to have been flown to. */
    public static final int HELD_BACK = 2;

    /** The flight controller's GPS with fewer satellites than this: no position. */
    public static final int MIN_SATS = 5;
    /** Faster than this between two readings: not flown. */
    public static final double MAX_KMH = 250;
    /** A GPS's own wander, allowed on top of the speed. */
    public static final double SLACK_M = 30;
    /** This many different readings that agree with each other become the place. */
    public static final int AGREE = 3;
    /** One reading repeated (a GPS standing still to the last digit) for this long becomes the place too. */
    public static final long SAME_MS = 2000;
    /** Candidates further apart in time than this don't count as one group. */
    public static final long GROUP_MS = 5000;

    private boolean has;
    private double lat, lon;
    private long at;

    private int candidates, different;
    private double candLat, candLon;
    private long candAt, candSince;

    private int moves;
    private boolean heldBack;

    /** A reading of the flight controller's GPS: has it a position at all? */
    public static boolean usableFc(int fix, int sats, double lat, double lon) {
        return fix >= 1 && sats >= MIN_SATS && onGlobe(lat, lon);
    }

    /** Not NaN, not 0/0, inside the globe's ranges. */
    public static boolean onGlobe(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return false;
        if (lat == 0 && lon == 0) return false;
        return Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }

    /**
     * One reading.
     * @param usable    it carries a position (see {@link #usableFc}; a phone's fix: fresh and {@link #onGlobe})
     * @param accuracyM the reading's own accuracy in metres, NaN unknown (it widens the slack)
     * @return {@link #TAKEN}, {@link #NO_FIX} or {@link #HELD_BACK}
     */
    public int offer(double lat, double lon, boolean usable, double accuracyM, long now) {
        if (!usable || !onGlobe(lat, lon)) {
            heldBack = false;
            return NO_FIX;
        }
        double slack = SLACK_M + (Double.isNaN(accuracyM) ? 0 : Math.max(0, accuracyM));
        if (!has || reachable(this.lat, this.lon, at, lat, lon, now, slack)) {
            take(lat, lon, now);
            return TAKEN;
        }
        // too far from the place held: does it agree with the candidates before it?
        boolean joins = candidates > 0 && now - candAt <= GROUP_MS
                && reachable(candLat, candLon, candAt, lat, lon, now, slack);
        if (!joins) {
            candidates = 1;
            different = 1;
            candSince = now;
        } else {
            candidates++;
            if (lat != candLat || lon != candLon) different++;
        }
        candLat = lat;
        candLon = lon;
        candAt = now;
        if (different >= AGREE || (candidates >= AGREE && now - candSince >= SAME_MS)) {
            moves++;
            take(lat, lon, now);
            return TAKEN;
        }
        heldBack = true;
        return HELD_BACK;
    }

    private void take(double lat, double lon, long now) {
        has = true;
        this.lat = lat;
        this.lon = lon;
        at = now;
        candidates = 0;
        different = 0;
        heldBack = false;
    }

    private static boolean reachable(double lat0, double lon0, long at0, double lat, double lon, long now, double slack) {
        double seconds = Math.max(0, now - at0) / 1000.0;
        return distanceM(lat0, lon0, lat, lon) <= slack + MAX_KMH / 3.6 * seconds;
    }

    /** A place is held. */
    public boolean has() {
        return has;
    }

    public double lat() {
        return has ? lat : Double.NaN;
    }

    public double lon() {
        return has ? lon : Double.NaN;
    }

    /** When the place held was last confirmed by a reading. */
    public long at() {
        return at;
    }

    /** The last reading was a position that was held back ("GPS ?"). */
    public boolean heldBack() {
        return heldBack;
    }

    /** How often a group of agreeing readings moved the place away from the one held (each is worth a log line). */
    public int moves() {
        return moves;
    }

    public void reset() {
        has = false;
        candidates = 0;
        different = 0;
        heldBack = false;
        moves = 0;
    }

    /** Metres between two places (a sphere; fine for the few km this is about, and for "the other side of the world"). */
    public static double distanceM(double lat0, double lon0, double lat1, double lon1) {
        double p0 = Math.toRadians(lat0), p1 = Math.toRadians(lat1);
        double dp = p1 - p0, dl = Math.toRadians(lon1 - lon0);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p0) * Math.cos(p1) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6_371_000.0 * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
