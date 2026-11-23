/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone (GPL v3 or later, see the project's LICENSE).
 */

package de.droiddrone.common.group;

/**
 * DRAUPNE fleet (PRD area E / K): metres east and north round a place, and back. A flat-earth (equirectangular)
 * approximation: inside a few kilometres - a follower group, its lanes, its way home - its error is far under a metre.
 * Java 11 (shared with the phone app).
 */
public final class GeoLocal {
    private GeoLocal() {
    }

    public static final double EARTH_R = 6_371_000.0;

    /** Metres east of (lat0, lon0) to (lat, lon). */
    public static double east(double lat0, double lon0, double lat, double lon) {
        return Math.toRadians(lon - lon0) * Math.cos(Math.toRadians(lat0)) * EARTH_R;
    }

    /** Metres north of (lat0, lon0) to (lat, lon). */
    public static double north(double lat0, double lon0, double lat, double lon) {
        return Math.toRadians(lat - lat0) * EARTH_R;
    }

    /** The place e metres east and n metres north of (lat0, lon0): {lat, lon}. */
    public static double[] offset(double lat0, double lon0, double e, double n) {
        double lat = lat0 + Math.toDegrees(n / EARTH_R);
        double lon = lon0 + Math.toDegrees(e / (EARTH_R * Math.cos(Math.toRadians(lat0))));
        return new double[]{lat, lon};
    }

    /** Ground distance in metres. */
    public static double distance(double lat0, double lon0, double lat, double lon) {
        return Math.hypot(east(lat0, lon0, lat, lon), north(lat0, lon0, lat, lon));
    }

    /** The compass bearing 0-360 from the first place to the second (0 when they are the same). */
    public static double bearing(double lat0, double lon0, double lat, double lon) {
        double e = east(lat0, lon0, lat, lon), n = north(lat0, lon0, lat, lon);
        if (e == 0 && n == 0) return 0;
        double b = Math.toDegrees(Math.atan2(e, n));
        return b < 0 ? b + 360 : b;
    }
}
