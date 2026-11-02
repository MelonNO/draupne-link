package de.droiddrone.pc.map;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The terrain height under a point: Kartverket's open height service over Norway (its 1 m terrain model), the
 * Open-Meteo elevation service elsewhere (90 m). One lookup at a time, the newest wins; answers are kept, so moving
 * the mouse back over a place asks nothing. Answers come on the lookup thread.
 */
public final class Elevation {
    private static final Pattern KARTVERKET = Pattern.compile("\"z\"\\s*:\\s*(-?[0-9.]+)");
    private static final Pattern OPEN_METEO = Pattern.compile("\"elevation\"\\s*:\\s*\\[\\s*(-?[0-9.]+)");
    private static final int KEEP = 2000;

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "elevation");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private final String userAgent;
    private final boolean network;
    private final Map<Long, Double> known = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Double> eldest) {
            return size() > KEEP;
        }
    };
    private volatile long wanted; // the newest request: older ones still queued are skipped

    public Elevation() {
        this("DRAUPNE-Link/" + de.droiddrone.pc.AppInfo.version() + " (+https://github.com/MelonNO/draupne-link)", true);
    }

    public Elevation(String userAgent, boolean network) {
        this.userAgent = userAgent;
        this.network = network;
    }

    /** Places about 10 m apart count as the same. */
    static long key(double lat, double lon) {
        return (Math.round(lat * 10000) + 2_000_000L) * 10_000_000L + Math.round(lon * 10000) + 2_000_000L;
    }

    /** result gets the height in metres, or null when unknown; at once if known, else from the lookup thread. */
    public void lookup(double lat, double lon, Consumer<Double> result) {
        long key = key(lat, lon);
        Double known;
        synchronized (this) {
            known = this.known.get(key);
        }
        if (known != null) {
            result.accept(known);
            return;
        }
        wanted = key;
        pool.execute(() -> {
            if (wanted != key) return; // the mouse moved on
            Double z = fetch(lat, lon);
            if (z != null) {
                synchronized (this) {
                    this.known.put(key, z);
                }
            }
            if (wanted == key) result.accept(z);
        });
    }

    static String url(double lat, double lon) {
        return TileSource.inNorway(lat, lon)
                ? String.format(Locale.ROOT, "https://ws.geonorge.no/hoydedata/v1/punkt?nord=%.6f&ost=%.6f&koordsys=4258&geojson=false", lat, lon)
                : String.format(Locale.ROOT, "https://api.open-meteo.com/v1/elevation?latitude=%.6f&longitude=%.6f", lat, lon);
    }

    static Double parse(String json) {
        if (json == null) return null;
        Matcher m = KARTVERKET.matcher(json);
        if (!m.find()) {
            m = OPEN_METEO.matcher(json);
            if (!m.find()) return null;
        }
        try {
            return Double.parseDouble(m.group(1));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Double fetch(double lat, double lon) {
        if (!network) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url(lat, lon))).timeout(Duration.ofSeconds(8))
                    .header("User-Agent", userAgent).header("Accept", "application/json").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? parse(response.body()) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
