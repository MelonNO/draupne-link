package de.droiddrone.pc.map;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Map tiles: in memory for the ones on screen, on disk for good (tiles\source\z\x\y.png: nothing is ever deleted or
 * fetched again, so every place looked at works without internet), fetched with two connections at most. When the
 * map rests on a place, {@link #prefetch} also fetches the levels below it (zooming in later works offline too) on a
 * third, slow thread; not for OpenStreetMap, whose tile policy forbids fetching what isn't being looked at.
 * Thread-safe; the fetches call back when a tile is in.
 */
public final class TileCache {
    private static final int MEMORY_TILES = 600;
    private static final long RETRY_MS = 60_000;

    private final Path dir;
    private final HttpClient http;
    private final String userAgent;
    private final ExecutorService pool;
    private final ExecutorService prefetcher;
    private final Map<String, BufferedImage> memory = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > MEMORY_TILES;
        }
    };
    private final Set<String> loading = new HashSet<>();
    private final Map<String, Long> failedUntil = new HashMap<>();
    private volatile boolean offline; // the last fetch didn't reach the server
    private final boolean network; // false in tests: disk only
    private final AtomicInteger prefetchGeneration = new AtomicInteger();
    private final AtomicInteger prefetchLeft = new AtomicInteger();

    public TileCache(Path dir) {
        this(dir, "DRAUPNE-Link/" + de.droiddrone.pc.AppInfo.version() + " (+https://github.com/MelonNO/draupne-link)", true);
    }

    /** @param network false: disk only (tests) */
    public TileCache(Path dir, String userAgent, boolean network) {
        this.dir = dir;
        this.userAgent = userAgent;
        this.network = network;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
        pool = Executors.newFixedThreadPool(2, r -> thread(r, "mapTiles"));
        prefetcher = Executors.newSingleThreadExecutor(r -> thread(r, "mapPrefetch"));
    }

    private static Thread thread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    }

    /** %APPDATA%\DraupneLink\tiles. */
    public static Path defaultDir() {
        return de.droiddrone.pc.AppPaths.dataDir().resolve("tiles");
    }

    /** True once a fetch failed to reach any server (the map then says it shows saved tiles only). */
    public boolean isOffline() {
        return offline;
    }

    /** Tiles the current prefetch still has to fetch (0 when done). */
    public int prefetchLeft() {
        return prefetchLeft.get();
    }

    /**
     * The tile, if it is in memory; else null, and it is loaded (disk, then the server) with onReady called on a
     * loader thread when it is there. x wraps round the world; a y outside the world is nothing.
     */
    public BufferedImage get(TileSource source, int z, int x, int y, Runnable onReady) {
        int n = 1 << z;
        if (y < 0 || y >= n || z < 0 || z > source.maxZoom) return null;
        x = Math.floorMod(x, n);
        String key = source.id + "/" + z + "/" + x + "/" + y;
        synchronized (this) {
            BufferedImage img = memory.get(key);
            if (img != null) return img;
            Long until = failedUntil.get(key);
            if (until != null && until > System.currentTimeMillis()) return null;
            if (!loading.add(key)) return null;
        }
        int fx = x;
        pool.execute(() -> load(key, source, z, fx, y, onReady));
        return null;
    }

    private Path file(TileSource source, int z, int x, int y) {
        return dir.resolve(source.id).resolve(Integer.toString(z)).resolve(Integer.toString(x)).resolve(y + ".png");
    }

    private void load(String key, TileSource source, int z, int x, int y, Runnable onReady) {
        BufferedImage img = null;
        try {
            Path file = file(source, z, x, y);
            if (Files.isRegularFile(file)) img = read(file);
            if (img == null) {
                byte[] data = fetch(source.url(z, x, y));
                if (data != null) {
                    img = ImageIO.read(new java.io.ByteArrayInputStream(data));
                    if (img != null) write(file, data);
                }
            }
        } catch (IOException | RuntimeException e) {
            // no tile this time; tried again after RETRY_MS
        }
        synchronized (this) {
            loading.remove(key);
            if (img != null) {
                memory.put(key, img);
                failedUntil.remove(key);
            } else {
                failedUntil.put(key, System.currentTimeMillis() + RETRY_MS);
            }
        }
        if (img != null && onReady != null) onReady.run();
    }

    // ------------------------------------------------------------------ prefetching an area

    /** A tile to fetch: zoom, x, y. */
    record Tile(int z, int x, int y) {}

    /**
     * The tiles covering the area at every zoom from zMin to zMax, nearest zoom first, at most budget of them:
     * what {@link #prefetch} fetches.
     */
    static List<Tile> plan(int zFrom, int zMin, int zMax, double minLat, double maxLat, double minLon, double maxLon, int budget) {
        List<Tile> tiles = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        for (int d = 0; zFrom - d >= zMin || zFrom + d <= zMax; d++) { // the levels nearest the current one first
            if (d == 0) order.add(zFrom);
            else {
                if (zFrom + d <= zMax) order.add(zFrom + d);
                if (zFrom - d >= zMin) order.add(zFrom - d);
            }
        }
        for (int z : order) {
            int n = 1 << z;
            int x0 = (int) Math.floor(Mercator.x(minLon, z) / Mercator.TILE), x1 = (int) Math.floor(Mercator.x(maxLon, z) / Mercator.TILE);
            int y0 = Math.max(0, (int) Math.floor(Mercator.y(maxLat, z) / Mercator.TILE)), y1 = Math.min(n - 1, (int) Math.floor(Mercator.y(minLat, z) / Mercator.TILE));
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    if (tiles.size() >= budget) return tiles;
                    tiles.add(new Tile(z, Math.floorMod(x, n), y));
                }
            }
        }
        return tiles;
    }

    /**
     * Fetches, slowly, everything of the area that isn't on disk yet, from zFrom outwards to zMin..zMax; a new call
     * replaces the last one. Nothing for a source that doesn't allow it.
     */
    public void prefetch(TileSource source, int zFrom, int zMin, int zMax, double minLat, double maxLat, double minLon, double maxLon, int budget) {
        if (!network || !source.prefetchAllowed) return;
        int generation = prefetchGeneration.incrementAndGet();
        List<Tile> tiles = plan(zFrom, Math.max(0, zMin), Math.min(source.maxZoom, zMax), minLat, maxLat, minLon, maxLon, budget);
        prefetchLeft.set(tiles.size());
        prefetcher.execute(() -> {
            int left = tiles.size();
            for (Tile t : tiles) {
                if (prefetchGeneration.get() != generation) return; // a newer area
                Path file = file(source, t.z(), t.x(), t.y());
                if (!Files.isRegularFile(file)) {
                    try {
                        byte[] data = fetch(source.url(t.z(), t.x(), t.y()));
                        if (data != null && ImageIO.read(new java.io.ByteArrayInputStream(data)) != null) write(file, data);
                        else if (offline) return; // no point going on without a network
                    } catch (IOException | RuntimeException e) {
                        // this one another time
                    }
                }
                if (prefetchGeneration.get() == generation) prefetchLeft.set(--left);
            }
        });
    }

    private byte[] fetch(String url) throws IOException {
        if (!network) {
            offline = true;
            return null;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12))
                .header("User-Agent", userAgent).header("Accept", "image/png,image/*").GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            offline = false;
            return response.statusCode() == 200 ? response.body() : null;
        } catch (java.net.ConnectException | java.net.UnknownHostException | java.net.http.HttpTimeoutException e) {
            offline = true;
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static BufferedImage read(Path file) {
        try {
            return ImageIO.read(file.toFile());
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(Path file, byte[] data) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        Files.write(tmp, data);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
