package de.droiddrone.pc.map;

import java.util.Locale;

/**
 * Positions as the pilot wants to read them: MGRS (the military grid, the default), UTM, decimal degrees or
 * degrees-minutes-seconds. WGS84; the UTM conversion is the Krüger series, good to a millimetre, with Norway's
 * zone exceptions (zone 32 over south-west Norway, the Svalbard zones).
 */
public final class Coordinates {
    public enum Format {
        MGRS("mgrs", "MGRS"), UTM("utm", "UTM"), DECIMAL("decimal", "LAT LON"), DMS("dms", "DEG MIN SEC");

        public final String id;
        public final String title;

        Format(String id, String title) {
            this.id = id;
            this.title = title;
        }

        /** The settings value; MGRS for anything unknown. */
        public static Format parse(String value) {
            if (value != null) for (Format f : values()) if (f.id.equalsIgnoreCase(value.trim())) return f;
            return MGRS;
        }
    }

    /** A UTM position; band is the MGRS latitude band letter. */
    public record Utm(int zone, boolean north, char band, double easting, double northing) {}

    private static final double A = 6378137, F = 1 / 298.257223563, K0 = 0.9996;
    private static final String BANDS = "CDEFGHJKLMNPQRSTUVWX";
    private static final String[] COLUMNS = {"ABCDEFGH", "JKLMNPQR", "STUVWXYZ"};
    private static final String ROWS = "ABCDEFGHJKLMNPQRSTUV";

    private Coordinates() {
    }

    public static String format(Format format, double lat, double lon) {
        return switch (format) {
            case MGRS -> mgrs(lat, lon, 5);
            case UTM -> {
                Utm u = toUtm(lat, lon);
                yield String.format(Locale.ROOT, "%d%c %.0f E %.0f N", u.zone(), u.band(), u.easting(), u.northing());
            }
            case DECIMAL -> String.format(Locale.ROOT, "%.6f %.6f", lat, lon);
            case DMS -> dms(lat, true) + " " + dms(lon, false);
        };
    }

    static String dms(double deg, boolean latitude) {
        char h = latitude ? (deg < 0 ? 'S' : 'N') : (deg < 0 ? 'W' : 'E');
        double a = Math.abs(deg);
        int d = (int) a;
        double m = (a - d) * 60;
        int mi = (int) m;
        double s = (m - mi) * 60;
        if (s >= 59.95) { // no 60.0"
            s = 0;
            if (++mi == 60) {
                mi = 0;
                d++;
            }
        }
        return String.format(Locale.ROOT, "%d°%02d'%04.1f\"%c", d, mi, s, h);
    }

    /** The UTM zone, with Norway's exceptions. */
    public static int zone(double lat, double lon) {
        int zone = (int) Math.floor((lon + 180) / 6) + 1;
        if (lat >= 56 && lat < 64 && lon >= 3 && lon < 12) zone = 32;
        if (lat >= 72 && lat < 84) {
            if (lon >= 0 && lon < 9) zone = 31;
            else if (lon >= 9 && lon < 21) zone = 33;
            else if (lon >= 21 && lon < 33) zone = 35;
            else if (lon >= 33 && lon < 42) zone = 37;
        }
        return Math.max(1, Math.min(60, zone));
    }

    public static char band(double lat) {
        if (lat >= 84 || lat < -80) return lat >= 0 ? 'Z' : 'A'; // the poles: no UTM band (UPS); not expected here
        return BANDS.charAt(Math.min(19, (int) Math.floor((lat + 80) / 8)));
    }

    public static Utm toUtm(double lat, double lon) {
        int zone = zone(lat, lon);
        double lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3);
        double n = F / (2 - F);
        double aa = A / (1 + n) * (1 + n * n / 4 + n * n * n * n / 64);
        double a1 = n / 2 - 2 * n * n / 3 + 5 * n * n * n / 16;
        double a2 = 13 * n * n / 48 - 3 * n * n * n / 5;
        double a3 = 61 * n * n * n / 240;
        double phi = Math.toRadians(lat), dl = Math.toRadians(lon) - lon0;
        double sn = 2 * Math.sqrt(n) / (1 + n);
        double t = Math.sinh(atanh(Math.sin(phi)) - sn * atanh(sn * Math.sin(phi)));
        double xi = Math.atan2(t, Math.cos(dl));
        double eta = atanh(Math.sin(dl) / Math.sqrt(1 + t * t));
        double e = eta + a1 * Math.cos(2 * xi) * Math.sinh(2 * eta) + a2 * Math.cos(4 * xi) * Math.sinh(4 * eta) + a3 * Math.cos(6 * xi) * Math.sinh(6 * eta);
        double nn = xi + a1 * Math.sin(2 * xi) * Math.cosh(2 * eta) + a2 * Math.sin(4 * xi) * Math.cosh(4 * eta) + a3 * Math.sin(6 * xi) * Math.cosh(6 * eta);
        boolean north = lat >= 0;
        double easting = 500000 + K0 * aa * e;
        double northing = K0 * aa * nn + (north ? 0 : 10_000_000);
        return new Utm(zone, north, band(lat), easting, northing);
    }

    private static double atanh(double x) {
        return 0.5 * Math.log((1 + x) / (1 - x));
    }

    /** MGRS with digits per axis (5 = 1 m): "32V NM 97254 42617". */
    public static String mgrs(double lat, double lon, int digits) {
        Utm u = toUtm(lat, lon);
        int e100 = (int) Math.floor(u.easting() / 100_000), n100 = (int) Math.floor(u.northing() / 100_000);
        char col = COLUMNS[(u.zone() - 1) % 3].charAt(Math.max(0, Math.min(7, e100 - 1)));
        char row = ROWS.charAt(Math.floorMod(n100 + (u.zone() % 2 == 0 ? 5 : 0), 20));
        double unit = Math.pow(10, 5 - digits);
        long e = (long) Math.floor(u.easting() % 100_000 / unit), nn = (long) Math.floor(u.northing() % 100_000 / unit);
        return String.format(Locale.ROOT, "%d%c %c%c %0" + digits + "d %0" + digits + "d", u.zone(), u.band(), col, row, e, nn);
    }

    public static String feet(double meters) {
        return String.format(Locale.ROOT, "%.0f FT", meters * 3.28084);
    }
}
