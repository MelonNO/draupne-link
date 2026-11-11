package de.droiddrone.pc.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 1.0.64, R-, P- and D-areas for the map (the pilot chose openAIP, a free key): fetched from openAIP's airspace API in
 * 1 x 1 degree boxes, kept on disk a week (%APPDATA%\DraupneLink\airspace), never fetched while a drone here may fly
 * (saved boxes only then). Only the key and the box leave the PC. openAIP's data is CC BY-NC (attributed on the map);
 * it is community data and may lag the AIP - the AIP and NOTAMs are the official word.
 */
public final class OpenAipAirspace {
    static final String API = "https://api.core.openaip.net/api/airspaces";
    public static final String ATTRIBUTION = "Airspace: openAIP (CC BY-NC)";
    /** openAIP's airspace types kept here. */
    static final int TYPE_RESTRICTED = 1, TYPE_DANGER = 2, TYPE_PROHIBITED = 3;
    static final long MAX_AGE_MS = 7L * 24 * 3600_000;
    /** A view wider than this many boxes is drawn from what is saved: fetching it would ask too much. */
    public static final int MAX_FETCH_BOXES = 16;

    /** What a view gave: its areas, and a word on what is missing (null: all there). */
    public record Answer(List<Airspace> areas, String note) {}

    private final Path dir;
    private final String userAgent;
    private final boolean network;
    private final BooleanSupplier held;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    /** Parsed boxes in memory (key "lat_lon" of the box's south-west corner). */
    private final Map<String, List<Airspace>> boxes = new LinkedHashMap<>();

    public OpenAipAirspace(Path dir) {
        this(dir, "DRAUPNE-Link/" + de.droiddrone.pc.AppInfo.version() + " (+https://github.com/MelonNO/draupne-link)", true,
                de.droiddrone.pc.PossiblyFlying::now);
    }

    /** @param network false: disk only (tests); held true while nothing may be fetched */
    OpenAipAirspace(Path dir, String userAgent, boolean network, BooleanSupplier held) {
        this.dir = dir;
        this.userAgent = userAgent;
        this.network = network;
        this.held = held;
    }

    public static Path defaultDir() {
        return de.droiddrone.pc.AppPaths.dataDir().resolve("airspace");
    }

    /**
     * The areas round the view {minLat, maxLat, minLon, maxLon}: saved boxes as they are, missing or old ones fetched
     * when a key is given, nothing is held, and the view is not too wide. Several areas span several boxes: each once.
     */
    public synchronized Answer areas(double[] view, String key) {
        int s = (int) Math.floor(view[0]), n = (int) Math.floor(view[1]), w = (int) Math.floor(view[2]), e = (int) Math.floor(view[3]);
        int count = (n - s + 1) * (e - w + 1);
        boolean mayFetch = count <= MAX_FETCH_BOXES;
        String note = null;
        Map<String, Airspace> out = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (int lat = s; lat <= n; lat++) {
            for (int lon = w; lon <= e; lon++) {
                List<Airspace> box = box(lat, lon, key, mayFetch, now);
                if (box == null) {
                    if (note == null) note = why(key, mayFetch);
                    continue;
                }
                for (Airspace a : box) out.putIfAbsent(a.id(), a);
            }
        }
        return new Answer(new ArrayList<>(out.values()), note);
    }

    private String why(String key, boolean mayFetch) {
        if (key == null || key.isBlank()) return "no openAIP key (Key...)";
        if (!mayFetch) return "zoom in to fetch areas";
        if (held.getAsBoolean()) return "saved areas only: a drone may be flying";
        if (!network) return "offline: saved areas only";
        return "some areas not fetched";
    }

    private List<Airspace> box(int lat, int lon, String key, boolean mayFetch, long now) {
        String id = lat + "_" + lon;
        Path f = dir.resolve(id + ".json");
        boolean fresh;
        try {
            fresh = Files.isRegularFile(f) && now - Files.getLastModifiedTime(f).toMillis() < MAX_AGE_MS;
        } catch (IOException e) {
            fresh = false;
        }
        if (!fresh && mayFetch && key != null && !key.isBlank() && network && !held.getAsBoolean()) {
            String json = fetch(lat, lon, key);
            if (json != null) {
                try {
                    write(f, json.getBytes(StandardCharsets.UTF_8));
                    boxes.put(id, parse(json));
                    return boxes.get(id);
                } catch (IOException e) {
                    // used this time anyway
                    return parse(json);
                }
            }
        }
        List<Airspace> mem = boxes.get(id);
        if (mem != null) return mem;
        if (!Files.isRegularFile(f)) return null;
        try {
            List<Airspace> a = parse(Files.readString(f, StandardCharsets.UTF_8));
            boxes.put(id, a);
            return a;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** One box's restricted, danger and prohibited areas from openAIP, every page: one JSON array of items, or null. */
    private String fetch(int lat, int lon, String key) {
        JsonArray all = new JsonArray();
        for (int page = 1; page <= 10; page++) {
            String url = API + "?bbox=" + lon + "," + lat + "," + (lon + 1) + "," + (lat + 1) + "&type=" + TYPE_RESTRICTED + "&type=" + TYPE_DANGER
                    + "&type=" + TYPE_PROHIBITED + "&limit=1000&page=" + page;
            try {
                HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", userAgent)
                        .header("Accept", "application/json").header("x-openaip-api-key", key.trim()).GET().build();
                HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) return null;
                JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
                if (o.has("items")) all.addAll(o.getAsJsonArray("items"));
                int pages = o.has("totalPages") ? o.get("totalPages").getAsInt() : 1;
                if (page >= pages) break;
            } catch (IOException | RuntimeException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        JsonObject saved = new JsonObject();
        saved.add("items", all);
        return saved.toString();
    }

    /** openAIP's answer (or a saved box: {"items": [...]}) as areas; the types not kept here left out. */
    public static List<Airspace> parse(String json) {
        List<Airspace> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);
        JsonArray items = root.isJsonArray() ? root.getAsJsonArray()
                : root.getAsJsonObject().has("items") ? root.getAsJsonObject().getAsJsonArray("items") : new JsonArray();
        for (JsonElement el : items) {
            try {
                JsonObject o = el.getAsJsonObject();
                int type = o.has("type") ? o.get("type").getAsInt() : -1;
                Airspace.Kind kind = type == TYPE_RESTRICTED ? Airspace.Kind.RESTRICTED : type == TYPE_DANGER ? Airspace.Kind.DANGER
                        : type == TYPE_PROHIBITED ? Airspace.Kind.PROHIBITED : null;
                if (kind == null || !o.has("geometry")) continue;
                String name = o.has("name") ? o.get("name").getAsString() : kind.word;
                String id = o.has("_id") ? o.get("_id").getAsString() : name;
                List<double[]> ring = ring(o.getAsJsonObject("geometry"));
                if (ring.size() < 3) continue;
                String lower = limit(o.get("lowerLimit")), upper = limit(o.get("upperLimit"));
                List<String> info = new ArrayList<>();
                info.add(name);
                info.add(kind.word + (lower.isEmpty() && upper.isEmpty() ? "" : "  " + lower + " - " + upper));
                info.add("openAIP - check the AIP and NOTAMs");
                out.add(new Airspace(id, name, kind, ring, lower, upper, info, false));
            } catch (RuntimeException e) {
                // one bad item: the rest
            }
        }
        return out;
    }

    /** A GeoJSON Polygon's outer ring (or a MultiPolygon's first) as {lat, lon} points; GeoJSON is lon, lat. */
    static List<double[]> ring(JsonObject geometry) {
        List<double[]> out = new ArrayList<>();
        String type = geometry.has("type") ? geometry.get("type").getAsString() : "";
        JsonArray coords = geometry.getAsJsonArray("coordinates");
        if (coords == null || coords.isEmpty()) return out;
        JsonArray outer = type.equals("MultiPolygon") ? coords.get(0).getAsJsonArray().get(0).getAsJsonArray() : coords.get(0).getAsJsonArray();
        for (JsonElement p : outer) {
            JsonArray a = p.getAsJsonArray();
            out.add(new double[]{a.get(1).getAsDouble(), a.get(0).getAsDouble()});
        }
        if (out.size() > 1) {
            double[] f = out.get(0), l = out.get(out.size() - 1);
            if (f[0] == l[0] && f[1] == l[1]) out.remove(out.size() - 1);
        }
        return out;
    }

    /** openAIP's limit {value, unit (1 ft, 6 FL, 0 m), referenceDatum (0 GND, 1 MSL, 2 STD)} as said on a chart. */
    static String limit(JsonElement e) {
        if (e == null || !e.isJsonObject()) return "";
        JsonObject o = e.getAsJsonObject();
        int value = o.has("value") ? o.get("value").getAsInt() : 0;
        int unit = o.has("unit") ? o.get("unit").getAsInt() : 1;
        int datum = o.has("referenceDatum") ? o.get("referenceDatum").getAsInt() : 0;
        if (unit == 6) return String.format(Locale.ROOT, "FL%d", value);
        if (value == 0 && datum == 0) return "GND";
        String u = unit == 0 ? " M" : " FT";
        return value + u + (datum == 0 ? " AGL" : datum == 1 ? " AMSL" : "");
    }

    private static void write(Path file, byte[] data) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        Files.write(tmp, data);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
