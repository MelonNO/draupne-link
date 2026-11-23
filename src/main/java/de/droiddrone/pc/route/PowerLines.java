package de.droiddrone.pc.route;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Fleet PRD RT-14: power lines, pylons and masts near a route - what the terrain map lacks (RouteProfile.NOTE). From
 * OpenStreetMap through Overpass, only when the pilot asks (never while a drone may be flying: the caller checks), for
 * the route's box and {@link #MARGIN_M} around it; kept under %APPDATA%\DraupneLink\powerlines for {@link #KEEP_DAYS}
 * days. Each one near the route ({@link #NEAR_M}) with its distance along the route and to its side. OSM is not
 * complete: an empty list is not a clear sky.
 */
public final class PowerLines {
    public static final String OVERPASS = "https://overpass-api.de/api/interpreter";
    public static final double NEAR_M = 150, MARGIN_M = 300;
    static final int KEEP_DAYS = 30;
    static final double M_PER_DEG = 111_320;

    public enum Kind {
        LINE("Power line"), MINOR_LINE("Power line"), PYLON("Pylon"), MAST("Mast"), TOWER("Tower");

        public final String word;

        Kind(String word) {
            this.word = word;
        }
    }

    /** One thing from the map: points (one for a mast, the line's for a line), its height if tagged (NaN: not). */
    public record Feature(Kind kind, List<double[]> points, double heightM) {}

    /** One near the route: where it comes closest, how far along the route, how far to its side. */
    public record Near(Kind kind, double lat, double lon, double alongM, double offM, double heightM) {
        /** "Power line 1.2 km, 40 m off" */
        public String word() {
            String along = alongM >= 1000 ? String.format(Locale.ROOT, "%.1f km", alongM / 1000) : Math.round(alongM) + " m";
            String h = Double.isNaN(heightM) ? "" : ", " + Math.round(heightM) + " m high";
            return kind.word + " " + along + (offM < 5 ? ", crossed" : ", " + Math.round(offM) + " m off") + h;
        }
    }

    private PowerLines() {
    }

    /** {south, west, north, east} of the points, with marginM round it. */
    public static double[] box(List<double[]> latLon, double marginM) {
        double s = 90, w = 180, n = -90, e = -180;
        for (double[] p : latLon) {
            s = Math.min(s, p[0]);
            n = Math.max(n, p[0]);
            w = Math.min(w, p[1]);
            e = Math.max(e, p[1]);
        }
        double dLat = marginM / M_PER_DEG, dLon = marginM / (M_PER_DEG * Math.max(0.1, Math.cos(Math.toRadians((s + n) / 2))));
        return new double[]{s - dLat, w - dLon, n + dLat, e + dLon};
    }

    static String query(double[] b) {
        String bb = String.format(Locale.ROOT, "(%.5f,%.5f,%.5f,%.5f)", b[0], b[1], b[2], b[3]);
        return "[out:json][timeout:25];(way[\"power\"~\"^(line|minor_line)$\"]" + bb + ";node[\"power\"=\"tower\"]" + bb
                + ";node[\"man_made\"~\"^(mast|tower)$\"]" + bb + ";way[\"man_made\"~\"^(mast|tower)$\"]" + bb + ";);out geom;";
    }

    /** Overpass's answer to features. */
    static List<Feature> parse(String json) {
        List<Feature> out = new ArrayList<>();
        JsonArray els = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("elements");
        if (els == null) return out;
        for (JsonElement el : els) {
            JsonObject o = el.getAsJsonObject();
            JsonObject tags = o.has("tags") ? o.getAsJsonObject("tags") : new JsonObject();
            Kind k = kind(str(tags, "power"), str(tags, "man_made"));
            if (k == null) continue;
            List<double[]> pts = new ArrayList<>();
            if (o.has("geometry")) {
                for (JsonElement g : o.getAsJsonArray("geometry")) {
                    if (!g.isJsonObject()) continue;
                    pts.add(new double[]{g.getAsJsonObject().get("lat").getAsDouble(), g.getAsJsonObject().get("lon").getAsDouble()});
                }
            } else if (o.has("lat")) {
                pts.add(new double[]{o.get("lat").getAsDouble(), o.get("lon").getAsDouble()});
            }
            if (pts.isEmpty()) continue;
            if ((k == Kind.MAST || k == Kind.TOWER) && pts.size() > 1) pts = List.of(centre(pts)); // a drawn outline
            out.add(new Feature(k, pts, height(str(tags, "height"))));
        }
        return out;
    }

    private static Kind kind(String power, String manMade) {
        switch (power) {
            case "line":
                return Kind.LINE;
            case "minor_line":
                return Kind.MINOR_LINE;
            case "tower":
                return Kind.PYLON;
            default:
                break;
        }
        if (manMade.equals("mast")) return Kind.MAST;
        if (manMade.equals("tower")) return Kind.TOWER;
        return null;
    }

    private static double[] centre(List<double[]> pts) {
        double la = 0, lo = 0;
        for (double[] p : pts) {
            la += p[0];
            lo += p[1];
        }
        return new double[]{la / pts.size(), lo / pts.size()};
    }

    static double height(String h) {
        if (h.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(h.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    /**
     * The features within nearM of the route (its points, {lat, lon} each, in flying order), nearest first along it.
     * A pylon on a line already listed isn't listed again.
     */
    public static List<Near> near(List<double[]> route, List<Feature> features, double nearM) {
        List<Near> out = new ArrayList<>();
        if (route.size() < 1) return out;
        double lat0 = route.get(0)[0], lon0 = route.get(0)[1], cos = Math.cos(Math.toRadians(lat0));
        double[][] r = new double[route.size()][];
        double[] alongAt = new double[route.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = local(route.get(i), lat0, lon0, cos);
            if (i > 0) alongAt[i] = alongAt[i - 1] + Math.hypot(r[i][0] - r[i - 1][0], r[i][1] - r[i - 1][1]);
        }
        for (Feature f : features) {
            double best = Double.MAX_VALUE, bestAlong = 0;
            double[] bestAt = null;
            List<double[]> pts = f.points();
            // the feature's points and segments against the route's segments
            for (int j = 0; j < pts.size(); j++) {
                double[] a = local(pts.get(j), lat0, lon0, cos);
                double[] b = j + 1 < pts.size() ? local(pts.get(j + 1), lat0, lon0, cos) : a;
                for (int i = 0; i < r.length; i++) {
                    double[] p = r[i], q = i + 1 < r.length ? r[i + 1] : r[i];
                    double[] c = closest(p, q, a, b);
                    if (c[0] < best) {
                        best = c[0];
                        bestAlong = alongAt[i] + c[1];
                        bestAt = new double[]{c[2], c[3]};
                    }
                }
            }
            if (best > nearM || bestAt == null) continue;
            double lat = lat0 + bestAt[1] / M_PER_DEG, lon = lon0 + bestAt[0] / (M_PER_DEG * cos);
            out.add(new Near(f.kind(), lat, lon, bestAlong, best, f.heightM()));
        }
        out.sort(Comparator.comparingDouble(Near::alongM));
        // a line's pylons beside its crossing say nothing more
        List<Near> lines = out.stream().filter(n -> n.kind() == Kind.LINE || n.kind() == Kind.MINOR_LINE).toList();
        out.removeIf(n -> n.kind() == Kind.PYLON && lines.stream().anyMatch(l -> Math.abs(l.alongM() - n.alongM()) < nearM));
        return out;
    }

    private static double[] local(double[] ll, double lat0, double lon0, double cos) {
        return new double[]{(ll[1] - lon0) * M_PER_DEG * cos, (ll[0] - lat0) * M_PER_DEG};
    }

    /**
     * Closest approach of segment pq (the route's) and segment ab (the feature's): {distance, metres along pq from p,
     * x and y of the point on ab} - ab crossing pq gives 0 at the crossing.
     */
    static double[] closest(double[] p, double[] q, double[] a, double[] b) {
        double[] x = cross(p, q, a, b);
        if (x != null) return new double[]{0, Math.hypot(x[0] - p[0], x[1] - p[1]), x[0], x[1]};
        double[] best = null;
        // each end against the other segment
        for (double[] e : new double[][]{a, b}) {
            double[] o = onSeg(p, q, e);
            double d = Math.hypot(o[0] - e[0], o[1] - e[1]);
            if (best == null || d < best[0]) best = new double[]{d, Math.hypot(o[0] - p[0], o[1] - p[1]), e[0], e[1]};
        }
        for (double[] e : new double[][]{p, q}) {
            double[] o = onSeg(a, b, e);
            double d = Math.hypot(o[0] - e[0], o[1] - e[1]);
            if (d < best[0]) best = new double[]{d, Math.hypot(e[0] - p[0], e[1] - p[1]), o[0], o[1]};
        }
        return best;
    }

    private static double[] onSeg(double[] p, double[] q, double[] e) {
        double dx = q[0] - p[0], dy = q[1] - p[1], l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((e[0] - p[0]) * dx + (e[1] - p[1]) * dy) / l2));
        return new double[]{p[0] + t * dx, p[1] + t * dy};
    }

    private static double[] cross(double[] p, double[] q, double[] a, double[] b) {
        double rx = q[0] - p[0], ry = q[1] - p[1], sx = b[0] - a[0], sy = b[1] - a[1];
        double den = rx * sy - ry * sx;
        if (Math.abs(den) < 1e-9) return null;
        double t = ((a[0] - p[0]) * sy - (a[1] - p[1]) * sx) / den, u = ((a[0] - p[0]) * ry - (a[1] - p[1]) * rx) / den;
        if (t < 0 || t > 1 || u < 0 || u > 1) return null;
        return new double[]{p[0] + t * rx, p[1] + t * ry};
    }

    /** The features in the box: from the cache when fresh, else from Overpass (and kept). */
    public static List<Feature> fetch(double[] box, Path cacheDir, HttpClient http) throws IOException, InterruptedException {
        Path f = cacheDir.resolve(String.format(Locale.ROOT, "%.3f_%.3f_%.3f_%.3f.json", box[0], box[1], box[2], box[3]));
        if (Files.isRegularFile(f) && System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() < KEEP_DAYS * 86_400_000L) {
            return parse(Files.readString(f));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(OVERPASS)).timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(query(box), StandardCharsets.UTF_8))).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("map server: HTTP " + r.statusCode());
        List<Feature> out = parse(r.body());
        Files.createDirectories(cacheDir);
        Files.writeString(f, r.body());
        return out;
    }
}
