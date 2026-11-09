package de.droiddrone.pc.map;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        // a millimetre's grace: a grid read in and written back stays itself (its corner came back as 49557.9999)
        long max = (long) Math.pow(10, digits) - 1;
        long e = Math.min(max, (long) Math.floor((u.easting() % 100_000 + 1e-3) / unit)),
                nn = Math.min(max, (long) Math.floor((u.northing() % 100_000 + 1e-3) / unit));
        return String.format(Locale.ROOT, "%d%c %c%c %0" + digits + "d %0" + digits + "d", u.zone(), u.band(), col, row, e, nn);
    }

    public static String feet(double meters) {
        return String.format(Locale.ROOT, "%.0f FT", meters * 3.28084);
    }

    // ------------------------------------------------------------------ reading positions (GO TO)

    /** UTM back to WGS84: the Krüger series the other way round (the same millimetre accuracy). */
    public static double[] fromUtm(int zone, boolean north, double easting, double northing) {
        double n = F / (2 - F);
        double aa = A / (1 + n) * (1 + n * n / 4 + n * n * n * n / 64);
        double b1 = n / 2 - 2 * n * n / 3 + 37 * n * n * n / 96;
        double b2 = n * n / 48 + n * n * n / 15;
        double b3 = 17 * n * n * n / 480;
        double d1 = 2 * n - 2 * n * n / 3 - 2 * n * n * n;
        double d2 = 7 * n * n / 3 - 8 * n * n * n / 5;
        double d3 = 56 * n * n * n / 15;
        double xi = (northing - (north ? 0 : 10_000_000)) / (K0 * aa), eta = (easting - 500000) / (K0 * aa);
        double xi2 = xi - (b1 * Math.sin(2 * xi) * Math.cosh(2 * eta) + b2 * Math.sin(4 * xi) * Math.cosh(4 * eta) + b3 * Math.sin(6 * xi) * Math.cosh(6 * eta));
        double eta2 = eta - (b1 * Math.cos(2 * xi) * Math.sinh(2 * eta) + b2 * Math.cos(4 * xi) * Math.sinh(4 * eta) + b3 * Math.cos(6 * xi) * Math.sinh(6 * eta));
        double chi = Math.asin(Math.sin(xi2) / Math.cosh(eta2));
        double phi = chi + d1 * Math.sin(2 * chi) + d2 * Math.sin(4 * chi) + d3 * Math.sin(6 * chi);
        double lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3);
        double lambda = lon0 + Math.atan2(Math.sinh(eta2), Math.cos(xi2));
        return new double[]{Math.toDegrees(phi), Math.toDegrees(lambda)};
    }

    private static final Pattern DECIMAL_PAIR = Pattern.compile("^([-+]?\\d{1,2}(?:\\.\\d+)?)\\s*([NS])?\\s*[,;]?\\s*([-+]?\\d{1,3}(?:\\.\\d+)?)\\s*([EW])?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DMS_PAIR = Pattern.compile("^(\\d{1,2})\\D+(\\d{1,2})\\D+(\\d{1,2}(?:\\.\\d+)?)\\D*([NS])\\s*,?\\s*(\\d{1,3})\\D+(\\d{1,2})\\D+(\\d{1,2}(?:\\.\\d+)?)\\D*([EW])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MGRS = Pattern.compile("^(\\d{1,2})\\s*([C-HJ-NP-X])\\s*([A-HJ-NP-Z])([A-HJ-NP-V])\\s*(\\d{1,10}(?:\\s+\\d{1,5})?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UTM = Pattern.compile("^(\\d{1,2})\\s*([C-HJ-NP-X])\\s+(\\d{5,7}(?:\\.\\d+)?)\\s*(?:M?\\s*E)?\\s*,?\\s+(\\d{6,8}(?:\\.\\d+)?)\\s*(?:M?\\s*N)?$", Pattern.CASE_INSENSITIVE);

    /**
     * A position as the pilot types or pastes it, in any of the formats the map writes: MGRS ("32V NM 97254 42617",
     * also without the spaces), UTM ("32V 597254 E 6642617 N" or "32V 597254 6642617"), decimal degrees
     * ("59.9139, 10.7522", also "59.9139 N 10.7522 E") or degrees-minutes-seconds. {lat, lon}, or null when it
     * reads as none of them.
     */
    public static double[] parse(String text) {
        if (text == null) return null;
        String s = text.trim().replace(' ', ' ').replaceAll("\\s+", " ");
        if (s.isEmpty()) return null;
        Matcher m = DECIMAL_PAIR.matcher(s);
        if (m.matches()) {
            double lat = Double.parseDouble(m.group(1)), lon = Double.parseDouble(m.group(3));
            if (m.group(2) != null && m.group(2).equalsIgnoreCase("S")) lat = -Math.abs(lat);
            if (m.group(4) != null && m.group(4).equalsIgnoreCase("W")) lon = -Math.abs(lon);
            return valid(lat, lon);
        }
        m = DMS_PAIR.matcher(s);
        if (m.matches()) {
            double lat = Integer.parseInt(m.group(1)) + Integer.parseInt(m.group(2)) / 60.0 + Double.parseDouble(m.group(3)) / 3600;
            double lon = Integer.parseInt(m.group(5)) + Integer.parseInt(m.group(6)) / 60.0 + Double.parseDouble(m.group(7)) / 3600;
            if (m.group(4).equalsIgnoreCase("S")) lat = -lat;
            if (m.group(8).equalsIgnoreCase("W")) lon = -lon;
            return valid(lat, lon);
        }
        m = MGRS.matcher(s);
        if (m.matches() && m.group(5).replaceAll("\\s", "").length() % 2 == 0) {
            int zone = Integer.parseInt(m.group(1));
            char band = Character.toUpperCase(m.group(2).charAt(0));
            char col = Character.toUpperCase(m.group(3).charAt(0)), row = Character.toUpperCase(m.group(4).charAt(0));
            String digits = m.group(5).replaceAll("\\s", ""); // "97254 42617" or "9725442617"
            if (m.group(5).contains(" ") && m.group(5).indexOf(' ') != digits.length() / 2) return null; // halves of unequal length
            int half = digits.length() / 2;
            double unit = Math.pow(10, 5 - half);
            double e = Integer.parseInt(digits.substring(0, half)) * unit, nn = Integer.parseInt(digits.substring(half)) * unit;
            int e100 = COLUMNS[(zone - 1) % 3].indexOf(col) + 1;
            if (e100 < 1 || zone < 1 || zone > 60) return null;
            int rowIndex = ROWS.indexOf(row);
            if (rowIndex < 0) return null;
            int n100 = Math.floorMod(rowIndex - (zone % 2 == 0 ? 5 : 0), 20);
            boolean north = band >= 'N';
            // the row letters repeat every 2 000 km: the band says which repetition
            double latMin = BANDS.indexOf(band) * 8 - 80;
            double minNorthing = toUtm(latMin + 1e-9, (zone - 1) * 6 - 180 + 3).northing();
            double northing = n100 * 100_000 + nn;
            while (northing < minNorthing - 100_000) northing += 2_000_000;
            return valid(fromUtm(zone, north, e100 * 100_000 + e, northing));
        }
        m = UTM.matcher(s);
        if (m.matches()) {
            int zone = Integer.parseInt(m.group(1));
            char band = Character.toUpperCase(m.group(2).charAt(0));
            if (zone < 1 || zone > 60) return null;
            return valid(fromUtm(zone, band >= 'N', Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))));
        }
        return null;
    }

    private static double[] valid(double[] ll) {
        return valid(ll[0], ll[1]);
    }

    private static double[] valid(double lat, double lon) {
        return Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180 ? null : new double[]{lat, lon};
    }
}
