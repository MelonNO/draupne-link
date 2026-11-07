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
        this(userAgent, network, de.droiddrone.pc.PossiblyFlying::now);
    }

    /** @param held true while nothing may be looked up (this PC's PossiblyFlying flag) */
    Elevation(String userAgent, boolean network, java.util.function.BooleanSupplier held) {
        this.userAgent = userAgent;
        this.network = network;
        this.held = held;
    }

    private final java.util.function.BooleanSupplier held;

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

    /**
     * The terrain along a path (1.0.35, the ruler's elevation profile): the path sampled every stepM metres (at most
     * maxSamples points, the vertices always among them), each sample's height looked up in turn on the lookup thread.
     * result gets {distances along the path in m, heights in m (NaN = not known yet or unknown)} after every few
     * answers and once at the end, so a chart fills in as the answers come; cancelled by a newer profile request.
     * @param path points as {lat, lon}
     */
    public void profile(java.util.List<double[]> path, double stepM, int maxSamples, Consumer<double[][]> result) {
        java.util.List<double[]> samples = sample(path, stepM, maxSamples);
        double[] dist = new double[samples.size()], height = new double[samples.size()];
        java.util.Arrays.fill(height, Double.NaN);
        for (int i = 0; i < samples.size(); i++) dist[i] = samples.get(i)[2];
        long serial = ++profileSerial;
        result.accept(new double[][]{dist.clone(), height.clone()});
        pool.execute(() -> {
            int since = 0;
            for (int i = 0; i < samples.size(); i++) {
                if (profileSerial != serial) return; // a newer profile was asked for
                double[] s = samples.get(i);
                long key = key(s[0], s[1]);
                Double z;
                synchronized (this) {
                    z = known.get(key);
                }
                if (z == null) {
                    z = fetch(s[0], s[1]);
                    if (z != null) {
                        synchronized (this) {
                            known.put(key, z);
                        }
                    }
                }
                height[i] = z == null ? Double.NaN : z;
                if (++since >= 8 || i == samples.size() - 1) {
                    since = 0;
                    if (profileSerial == serial) result.accept(new double[][]{dist.clone(), height.clone()});
                }
            }
        });
    }

    private volatile long profileSerial;

    /** The path's points and, between them, a point every stepM (stretched so at most maxSamples come): {lat, lon, m along}. */
    static java.util.List<double[]> sample(java.util.List<double[]> path, double stepM, int maxSamples) {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        if (path.isEmpty()) return out;
        double total = 0;
        for (int i = 1; i < path.size(); i++) total += Mercator.distanceM(path.get(i - 1)[0], path.get(i - 1)[1], path.get(i)[0], path.get(i)[1]);
        double step = Math.max(stepM, total / Math.max(1, maxSamples - path.size()));
        double along = 0;
        out.add(new double[]{path.get(0)[0], path.get(0)[1], 0});
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1), b = path.get(i);
            double leg = Mercator.distanceM(a[0], a[1], b[0], b[1]);
            for (double d = step; d < leg - step / 2; d += step) {
                double f = d / leg;
                out.add(new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, along + d});
            }
            along += leg;
            out.add(new double[]{b[0], b[1], along});
        }
        return out;
    }

    static String url(double lat, double lon) {
        return TileSource.inNorway(lat, lon) ? kartverketUrl(lat, lon) : worldUrl(lat, lon);
    }

    static String kartverketUrl(double lat, double lon) {
        return String.format(Locale.ROOT, "https://ws.geonorge.no/hoydedata/v1/punkt?nord=%.6f&ost=%.6f&koordsys=4258&geojson=false", lat, lon);
    }

    static String worldUrl(double lat, double lon) {
        return String.format(Locale.ROOT, "https://api.open-meteo.com/v1/elevation?latitude=%.6f&longitude=%.6f", lat, lon);
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

    /** Kartverket over Norway; the world service elsewhere, and also when Kartverket has nothing for a border place. */
    private Double fetch(double lat, double lon) {
        if (!network || held.getAsBoolean()) return null; // 1.0.49: no lookups while a drone here may fly (unknown, asked again later)
        Double z = TileSource.inNorway(lat, lon) ? fetch(kartverketUrl(lat, lon)) : null;
        return z != null ? z : fetch(worldUrl(lat, lon));
    }

    private Double fetch(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                    .header("User-Agent", userAgent).header("Accept", "application/json").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? parse(response.body()) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
