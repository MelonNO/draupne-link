package de.droiddrone.pc.map;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.0.64, NOTAMs for the map (the pilot chose pasting them from IPPC.no: Norway has no public NOTAM API). Reads ICAO
 * NOTAMs as text - one or many, as IPPC shows them - and works out each one's area:
 * <ul>
 * <li>a polygon where E) lists three or more points ("595500N 0104500E - ..."),</li>
 * <li>else a circle where E) gives a radius round a point ("PSN 595500N 0104500E RADIUS 2NM"),</li>
 * <li>else the Q-line's circle (its centre and radius in NM).</li>
 * </ul>
 * B) and C) are its times (UTC, YYMMDDhhmm; C) PERM never ends; an EST end is an estimate - the NOTAM stays until it
 * is replaced, cancelled or removed). A NOTAMR / NOTAMC names the one it replaces or cancels ({@link #replaced});
 * a NOTAMC has no area of its own. A Q-line circle of {@link #WIDE_NM} or more (999: "the whole FIR") is not an
 * area to draw. Pure (tests).
 */
public final class NotamParser {
    private NotamParser() {
    }

    /** One NOTAM: its area, times (end null: permanent) and text. */
    public record Notam(String id, List<double[]> ring, Instant start, Instant end, boolean endEstimated, String lower, String upper,
                        String text, String schedule, String raw) {
        /** In force at now. */
        public boolean activeAt(Instant now) {
            return (start == null || !now.isBefore(start)) && (end == null || now.isBefore(end));
        }

        /** Over at now (gone from the map and the saved list). */
        public boolean overAt(Instant now) {
            return end != null && !endEstimated && !now.isBefore(end);
        }
    }

    private static final Pattern ID = Pattern.compile("\\b([A-Z]\\d{4}/\\d{2})\\b");
    /** "A1235/26 NOTAMR A1234/26", "A1236/26 NOTAMC A1234/26": this NOTAM's id, its kind, the id it replaces or cancels. */
    private static final Pattern HEAD = Pattern.compile("\\b([A-Z]\\d{4}/\\d{2})\\s+NOTAM([NRC])(?:\\s+([A-Z]\\d{4}/\\d{2}))?");
    /** A Q-line radius this wide (NM) covers a FIR, not a place: not drawn. */
    static final int WIDE_NM = 100;

    /** The ids the pasted text replaces or cancels (NOTAMR, NOTAMC): saved ones of these ids go. */
    public static List<String> replaced(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = HEAD.matcher(text.toUpperCase(Locale.ROOT));
        while (m.find()) if (m.group(3) != null && !m.group(2).equals("N")) out.add(m.group(3));
        return out;
    }

    private static final Pattern QLINE =Pattern.compile("Q\\)\\s*([A-Z]{4})/([A-Z]{5})/[^/]*/[^/]*/[^/]*/(\\d{3})/(\\d{3})/(\\d{4}[NS]\\d{5}[EW])(\\d{3})");
    private static final Pattern FIELD_B = Pattern.compile("\\bB\\)\\s*(\\d{10})");
    private static final Pattern FIELD_C = Pattern.compile("\\bC\\)\\s*(\\d{10}|PERM)\\s*(EST)?");
    private static final Pattern FIELD_D = Pattern.compile("\\bD\\)\\s*(.*?)(?=\\s+[EFG]\\)|$)", Pattern.DOTALL);
    private static final Pattern FIELD_E = Pattern.compile("\\bE\\)\\s*(.*?)(?=\\s+[FG]\\)\\s|\\s*$)", Pattern.DOTALL);
    private static final Pattern FIELD_F = Pattern.compile("\\bF\\)\\s*([^\\n]*?)(?=\\s+G\\)|\\n|$)");
    private static final Pattern FIELD_G = Pattern.compile("\\bG\\)\\s*([^\\n]*)");
    /** A point: DDMM[SS[.s]]N DDDMM[SS[.s]]E, with or without spaces between. */
    static final Pattern POINT = Pattern.compile("(\\d{2})(\\d{2})(\\d{2}(?:[.,]\\d+)?)?\\s?([NS])\\s*[,/]?\\s*(\\d{3})(\\d{2})(\\d{2}(?:[.,]\\d+)?)?\\s?([EW])");
    private static final Pattern RADIUS = Pattern.compile("RADIUS\\s+(?:OF\\s+)?(\\d+(?:[.,]\\d+)?)\\s*(NM|KM|M)\\b");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyMMddHHmm", Locale.ROOT);

    /** Every NOTAM in the text (one may be several pasted together); those whose area can't be found are left out. */
    public static List<Notam> parse(String text) {
        List<Notam> out = new ArrayList<>();
        if (text == null) return out;
        String t = text.replace('\r', '\n').toUpperCase(Locale.ROOT);
        // each one starts at its id (A1234/26 NOTAMN, "(A1234/26 ..."), or where there is none, at its Q)
        List<Integer> starts = new ArrayList<>();
        Matcher q = Pattern.compile("Q\\)").matcher(t);
        while (q.find()) {
            int back = Math.max(0, q.start() - 60);
            String before = t.substring(back, q.start());
            Matcher id = ID.matcher(before);
            int at = q.start();
            while (id.find()) at = back + id.start();
            Matcher head = HEAD.matcher(before); // its own id, not the one it replaces (which comes after it)
            while (head.find()) at = back + head.start();
            starts.add(at);
        }
        for (int i = 0; i < starts.size(); i++) {
            String block = t.substring(starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : t.length()).trim();
            Notam n = one(block);
            if (n != null) out.add(n);
        }
        return out;
    }

    /** One NOTAM's block, or null when its area can't be found. */
    static Notam one(String block) {
        Matcher idm = ID.matcher(block);
        String id = idm.find() ? idm.group(1) : "";
        Matcher head = HEAD.matcher(block);
        if (!id.isEmpty() && head.find() && head.start() == idm.start() && head.group(2).equals("C")) return null; // a cancellation: no area
        Matcher qm = QLINE.matcher(block);
        String e = group(FIELD_E, block).replaceAll("\\s+", " ").trim();
        List<double[]> ring = area(e);
        String lower = "", upper = "";
        if (qm.find()) {
            if (ring == null) {
                double[] c = point(qm.group(5));
                int nm = Integer.parseInt(qm.group(6));
                if (c != null && nm > 0 && nm < WIDE_NM) ring = Airspace.circle(c[0], c[1], nm * 1852.0);
            }
            lower = "FL" + Integer.parseInt(qm.group(3));
            upper = "FL" + Integer.parseInt(qm.group(4));
            if (lower.equals("FL0")) lower = "GND";
        }
        if (ring == null || ring.size() < 3) return null;
        String f = group(FIELD_F, block).trim(), g = group(FIELD_G, block).trim();
        if (!f.isEmpty()) lower = f.replaceAll("\\s+", " ");
        if (!g.isEmpty()) upper = g.replaceAll("\\s+", " ");
        Instant start = time(group(FIELD_B, block));
        Matcher cm = FIELD_C.matcher(block);
        Instant end = null;
        boolean est = false;
        if (cm.find() && !cm.group(1).equals("PERM")) {
            end = time(cm.group(1));
            est = cm.group(2) != null;
        }
        String schedule = group(FIELD_D, block).replaceAll("\\s+", " ").trim();
        return new Notam(id, ring, start, end, est, lower, upper, e, schedule, block);
    }

    /** E)'s area: three or more points a polygon; one point and a radius a circle; else null. */
    static List<double[]> area(String e) {
        List<double[]> pts = new ArrayList<>();
        Matcher m = POINT.matcher(e);
        while (m.find()) {
            double[] p = point(m.group());
            if (p != null) pts.add(p);
        }
        if (pts.size() >= 3) {
            double[] f = pts.get(0), l = pts.get(pts.size() - 1);
            if (pts.size() > 3 && Math.abs(f[0] - l[0]) < 1e-6 && Math.abs(f[1] - l[1]) < 1e-6) pts.remove(pts.size() - 1);
            return pts;
        }
        Matcher r = RADIUS.matcher(e);
        if (!pts.isEmpty() && r.find()) {
            double v = Double.parseDouble(r.group(1).replace(',', '.'));
            double m2 = r.group(2).equals("NM") ? v * 1852 : r.group(2).equals("KM") ? v * 1000 : v;
            return Airspace.circle(pts.get(0)[0], pts.get(0)[1], m2);
        }
        return null;
    }

    /** "595500N 0104500E", "5955N01045E", "595500.5N0104500E" as {lat, lon}; null when not one. */
    static double[] point(String s) {
        Matcher m = POINT.matcher(s);
        if (!m.find()) return null;
        double lat = Integer.parseInt(m.group(1)) + Integer.parseInt(m.group(2)) / 60.0
                + (m.group(3) == null ? 0 : Double.parseDouble(m.group(3).replace(',', '.')) / 3600.0);
        double lon = Integer.parseInt(m.group(5)) + Integer.parseInt(m.group(6)) / 60.0
                + (m.group(7) == null ? 0 : Double.parseDouble(m.group(7).replace(',', '.')) / 3600.0);
        if (m.group(4).equals("S")) lat = -lat;
        if (m.group(8).equals("W")) lon = -lon;
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
        return new double[]{lat, lon};
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static Instant time(String yymmddhhmm) {
        if (yymmddhhmm == null || yymmddhhmm.length() != 10) return null;
        try {
            return LocalDateTime.parse(yymmddhhmm, TIME).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The map's area for a NOTAM: its id and times in the info, dashed while it hasn't started. */
    public static Airspace toAirspace(Notam n, Instant now) {
        List<String> info = new ArrayList<>();
        info.add("NOTAM " + n.id());
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd MMM HH:mm'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
        info.add((n.start() == null ? "?" : f.format(n.start())) + " - " + (n.end() == null ? "PERM" : f.format(n.end()) + (n.endEstimated() ? " EST" : ""))
                + (n.schedule().isEmpty() ? "" : "  " + n.schedule()));
        if (!n.lower().isEmpty() || !n.upper().isEmpty()) info.add(n.lower() + " - " + n.upper());
        String text = n.text();
        while (!text.isEmpty()) { // the text wrapped to the box
            int cut = Math.min(text.length(), 60);
            if (cut < text.length()) {
                int sp = text.lastIndexOf(' ', cut);
                if (sp > 20) cut = sp;
            }
            info.add(text.substring(0, cut).trim());
            text = text.substring(cut).trim();
            if (info.size() > 12) {
                info.add("...");
                break;
            }
        }
        boolean later = n.start() != null && now.isBefore(n.start());
        return new Airspace("notam:" + n.id() + ":" + n.raw().hashCode(), n.id().isEmpty() ? "NOTAM" : n.id(), Airspace.Kind.NOTAM, n.ring(),
                n.lower(), n.upper(), info, later);
    }
}
