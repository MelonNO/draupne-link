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
 * third, slow thread; not for OpenStreetMap, whose tile policy forbids fetching what isn't being looked at. An area
 * the pilot asks for ({@link #download}, like Mission Planner's prefetch) is fetched whole, for every picture.
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
                long now = System.currentTimeMillis();
                failedUntil.values().removeIf(until -> until <= now); // a long offline session would otherwise keep every miss
                failedUntil.put(key, now + RETRY_MS);
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

    // ------------------------------------------------------------------ downloading an area for offline use

    /** Above this many tiles the download window warns that it will take long (hours); it is not a limit. */
    public static final int MAX_DOWNLOAD = 250_000;
    /** Fetches in a row that reached no server before a download gives up. */
    private static final int GIVE_UP_AFTER = 20;
    /** Answers in a row that weren't a tile (403, 429, 404 ...) before a picture is left out: the server said no. */
    private static final int REFUSED_AFTER = 40;

    /** How many tiles cover the area at every zoom from zMin to zMax (counted, not listed: may be billions). */
    public static long count(int zMin, int zMax, double minLat, double maxLat, double minLon, double maxLon) {
        long n = 0;
        for (int z = Math.max(0, zMin); z <= zMax; z++) {
            long side = 1L << z;
            long x0 = (long) Math.floor(Mercator.x(minLon, z) / Mercator.TILE), x1 = (long) Math.floor(Mercator.x(maxLon, z) / Mercator.TILE);
            long y0 = Math.max(0, (long) Math.floor(Mercator.y(maxLat, z) / Mercator.TILE)), y1 = Math.min(side - 1, (long) Math.floor(Mercator.y(minLat, z) / Mercator.TILE));
            if (x1 >= x0 && y1 >= y0) n += (x1 - x0 + 1) * (y1 - y0 + 1);
        }
        return n;
    }

    /**
     * The area's tiles one at a time, coarse level first, row by row: nothing is listed ahead, so an area of
     * hundreds of millions of tiles costs no memory (listing it froze the window and ran out of heap). Shared by a
     * picture's workers.
     */
    static final class Walk {
        private final int zMax;
        private final double minLat, maxLat, minLon, maxLon;
        private int z, x0, x1, y0, y1, x, y;
        private boolean ended;

        Walk(int zMin, int zMax, double minLat, double maxLat, double minLon, double maxLon) {
            this.zMax = zMax;
            this.minLat = minLat;
            this.maxLat = maxLat;
            this.minLon = minLon;
            this.maxLon = maxLon;
            z = Math.max(0, zMin) - 1;
            ended = !nextLevel();
        }

        /** Moves to the next level that has tiles; false when past the last. */
        private boolean nextLevel() {
            while (++z <= zMax) {
                int n = 1 << z;
                x0 = (int) Math.floor(Mercator.x(minLon, z) / Mercator.TILE);
                x1 = (int) Math.floor(Mercator.x(maxLon, z) / Mercator.TILE);
                y0 = Math.max(0, (int) Math.floor(Mercator.y(maxLat, z) / Mercator.TILE));
                y1 = Math.min(n - 1, (int) Math.floor(Mercator.y(minLat, z) / Mercator.TILE));
                if (x1 >= x0 && y1 >= y0) {
                    x = x0;
                    y = y0;
                    return true;
                }
            }
            return false;
        }

        /** The next tile, or null when the area is through. */
        synchronized Tile next() {
            if (ended) return null;
            Tile t = new Tile(z, Math.floorMod(x, 1 << z), y);
            if (++x > x1) {
                x = x0;
                if (++y > y1) ended = !nextLevel();
            }
            return t;
        }
    }

    /**
     * An area being fetched for offline use: every tile of it from one level to another for each picture asked for,
     * picture after picture, coarse levels first, those already on disk skipped. The figures are read as it runs;
     * {@link #cancel} stops it (what is saved stays), {@link #pause} holds it.
     */
    public final class Download {
        public final long total;
        private final AtomicInteger done = new AtomicInteger(), fetched = new AtomicInteger(), onDisk = new AtomicInteger(),
                failed = new AtomicInteger();
        private final List<TileSource> refused = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile TileSource current;
        private volatile boolean cancelled, finished, gaveUp, paused;

        private Download(long total) {
            this.total = total;
        }

        /** The picture being fetched now. */
        public TileSource current() {
            return current;
        }

        /** Tiles looked at so far (fetched, on disk already, or failed). */
        public int done() {
            return done.get();
        }

        public int fetched() {
            return fetched.get();
        }

        /** Tiles that were on disk already. */
        public int onDisk() {
            return onDisk.get();
        }

        public int failed() {
            return failed.get();
        }

        /** Pictures left out because their server kept saying no (403, 429 ...): the rest of them was skipped. */
        public List<TileSource> refused() {
            return refused;
        }

        public boolean isFinished() {
            return finished;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        /** It stopped by itself: no server could be reached for a while. */
        public boolean gaveUp() {
            return gaveUp;
        }

        public boolean isPaused() {
            return paused;
        }

        /** Holds the fetching (the workers wait); {@link #resume} goes on where it was. */
        public void pause() {
            paused = true;
        }

        public void resume() {
            paused = false;
        }

        public void cancel() {
            cancelled = true;
        }

        /** Waits while paused; false when cancelled meanwhile. */
        private boolean waitWhilePaused() {
            while (paused && !cancelled) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !cancelled;
        }
    }

    /** How many tiles the area takes for these pictures from zMin to zMax (each picture to its own deepest level). */
    public static long count(List<TileSource> sources, int zMin, int zMax, double minLat, double maxLat, double minLon, double maxLon) {
        long n = 0;
        for (TileSource s : sources) n += count(zMin, Math.min(s.maxZoom, zMax), minLat, maxLat, minLon, maxLon);
        return n;
    }

    /**
     * Fetches the whole area from zMin to zMax for every picture given, one picture after another, like Mission
     * Planner's prefetch, on threads of its own; however many tiles that is (count first: the window warns above
     * {@link #MAX_DOWNLOAD}). The tiles are walked as they are fetched, never listed. Kartverket and Esri with two
     * connections; OpenStreetMap, whose policy frowns on this, with one and a pause between tiles - keep its areas
     * small. A picture whose server keeps refusing is left out and the next one started.
     */
    public Download download(List<TileSource> sources, int zMin, int zMax, double minLat, double maxLat, double minLon, double maxLon) {
        int z0 = Math.max(0, zMin);
        Download d = new Download(count(sources, z0, zMax, minLat, maxLat, minLon, maxLon));
        Thread runner = thread(() -> {
            for (TileSource s : sources) {
                if (d.cancelled || d.gaveUp) break;
                d.current = s;
                run(d, s, new Walk(z0, Math.min(s.maxZoom, zMax), minLat, maxLat, minLon, maxLon));
            }
            d.finished = true;
        }, "mapDownload");
        runner.start();
        return d;
    }

    public Download download(TileSource source, int zMin, int zMax, double minLat, double maxLat, double minLon, double maxLon) {
        return download(List.of(source), zMin, zMax, minLat, maxLat, minLon, maxLon);
    }

    /** One picture's tiles on its own workers; returns when they are through, cancelled, given up or refused. */
    private void run(Download d, TileSource source, Walk walk) {
        int threads = source.prefetchAllowed ? 2 : 1;
        long gapMs = source.prefetchAllowed ? 0 : 250;
        ExecutorService workers = Executors.newFixedThreadPool(threads, r -> thread(r, "mapDownload-" + source.id));
        AtomicInteger misses = new AtomicInteger(); // fetches in a row that reached no server
        AtomicInteger refusals = new AtomicInteger(); // answers in a row that weren't a tile
        for (int i = 0; i < threads; i++) {
            workers.execute(() -> {
                for (Tile t; (t = walk.next()) != null; ) {
                    if (!d.waitWhilePaused()) break;
                    if (misses.get() >= GIVE_UP_AFTER) {
                        d.gaveUp = true;
                        break;
                    }
                    if (refusals.get() >= REFUSED_AFTER) {
                        if (!d.refused.contains(source)) d.refused.add(source);
                        break;
                    }
                    Path file = file(source, t.z(), t.x(), t.y());
                    if (Files.isRegularFile(file)) {
                        d.onDisk.incrementAndGet();
                    } else {
                        boolean ok = false, answered = false;
                        try {
                            Answer a = answer(source.url(t.z(), t.x(), t.y()));
                            answered = a.status() != 0;
                            if (a.body() != null && ImageIO.read(new java.io.ByteArrayInputStream(a.body())) != null) {
                                write(file, a.body());
                                ok = true;
                            }
                        } catch (IOException | RuntimeException e) {
                            // counted as failed
                        }
                        if (ok) {
                            d.fetched.incrementAndGet();
                            misses.set(0);
                            refusals.set(0);
                        } else {
                            d.failed.incrementAndGet();
                            if (answered) refusals.incrementAndGet();
                            else if (offline) misses.incrementAndGet();
                        }
                        if (gapMs > 0) {
                            try {
                                Thread.sleep(gapMs);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                    d.done.incrementAndGet();
                }
            });
        }
        workers.shutdown();
        try {
            workers.awaitTermination(365, java.util.concurrent.TimeUnit.DAYS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            d.cancelled = true;
        }
    }

    /** What a server said: status 0 = not reached; body only for a 200. */
    private record Answer(int status, byte[] body) {}

    private byte[] fetch(String url) throws IOException {
        return answer(url).body();
    }

    private Answer answer(String url) throws IOException {
        if (!network) {
            offline = true;
            return new Answer(0, null);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12))
                .header("User-Agent", userAgent).header("Accept", "image/png,image/*").GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            offline = false;
            return new Answer(response.statusCode(), response.statusCode() == 200 ? response.body() : null);
        } catch (java.net.ConnectException | java.net.UnknownHostException | java.net.http.HttpTimeoutException e) {
            offline = true;
            return new Answer(0, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(0, null);
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
