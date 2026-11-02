package de.droiddrone.pc.map;

/**
 * Web Mercator, the projection every slippy map uses: the world is a square of 256 * 2^zoom pixels, tiles are 256
 * pixels. The zoom may be fractional here (the view scales the nearest tile level), so everything works in doubles.
 */
public final class Mercator {
    public static final int TILE = 256;
    private static final double MAX_LAT = 85.05112878;
    private static final double EARTH_RADIUS_M = 6371008.8;

    private Mercator() {
    }

    /** The world's width in pixels at this zoom. */
    public static double worldSize(double zoom) {
        return TILE * Math.pow(2, zoom);
    }

    /** World pixel x of a longitude. */
    public static double x(double lonDeg, double zoom) {
        return (lonDeg + 180) / 360 * worldSize(zoom);
    }

    /** World pixel y of a latitude (0 at the top, near the north pole). */
    public static double y(double latDeg, double zoom) {
        double lat = Math.toRadians(Math.max(-MAX_LAT, Math.min(MAX_LAT, latDeg)));
        return (1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2 * worldSize(zoom);
    }

    public static double lon(double x, double zoom) {
        return x / worldSize(zoom) * 360 - 180;
    }

    public static double lat(double y, double zoom) {
        double n = Math.PI - 2 * Math.PI * y / worldSize(zoom);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    /** Ground metres per pixel at this latitude and zoom. */
    public static double metersPerPixel(double latDeg, double zoom) {
        return 2 * Math.PI * EARTH_RADIUS_M * Math.cos(Math.toRadians(latDeg)) / worldSize(zoom);
    }

    /** Great-circle distance in metres. */
    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Initial bearing from the first point to the second, 0-359 (0 = north, 90 = east). */
    public static int bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (int) Math.round(Math.floorMod(Math.round(Math.toDegrees(Math.atan2(y, x))), 360));
    }
}
