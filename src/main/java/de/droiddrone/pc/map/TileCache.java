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
 * Thread-safe; the fetches call back when a tile is in. The tiles asked for wait in a bounded queue, the newest
 * first, and one no map shows any more is dropped (1.0.49). While a drone on this PC may be flying
 * ({@link de.droiddrone.pc.PossiblyFlying}) nothing is fetched - map, prefetch, area downloads, the terrain - and the
 * disk alone serves: the 4G uplink and downlink are the sticks' and the picture's.
 */
public final class TileCache {
    private static final int MEMORY_TILES = 600;
    private static final long RETRY_MS = 60_000;

    /** Tiles waiting to load at most (1.0.49, M13): beyond it the ones asked for longest ago are dropped. */
    static final int QUEUE_MAX = 256;
    /**
     * A waiting tile is dropped when every map that asked for it has since painted for this long without asking for it
     * again: it is off the screen (panned or zoomed away) - on weak 4G the shown tiles waited behind minutes of those.
     */
    static final long STALE_MS = 1500;
    /** While a drone may fly, a tile not on disk is looked at again this often (it is fetched once the flag drops). */
    static final long HELD_RETRY_MS = 5000;
    private static final int LOADERS = 2;
    /** The asker of a get without a callback: never stale. */
    private static final Runnable NO_ONE = () -> { };

    private final Path dir;
    private final HttpClient http;
    private final String userAgent;
    /** True while a drone on this PC may fly (PossiblyFlying, 1.0.49): nothing is fetched, the disk still serves. */
    private final java.util.function.BooleanSupplier held;
    java.util.function.LongSupplier clock = System::currentTimeMillis; // tests set their own
    private final ExecutorService prefetcher;
    /** The tiles waiting to load, the newest asked for last (taken first); and those loading now. */
    private final LinkedHashMap<String, Request> queued = new LinkedHashMap<>();
    private final Map<String, Request> active = new HashMap<>();
    /** When each map (by its callback) last asked for any tile: how stale its older requests are. */
    private final Map<Runnable, Long> lastAsk = new java.util.WeakHashMap<>();
    private final Map<String, BufferedImage> memory = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > MEMORY_TILES;
        }
    };
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
        this(dir, userAgent, network, true, de.droiddrone.pc.PossiblyFlying::now);
    }

    /**
     * @param loaders false: no loader threads (tests take the queue with {@link #takeNext})
     * @param held    true while nothing may be fetched (this PC's PossiblyFlying flag)
     */
    TileCache(Path dir, String userAgent, boolean network, boolean loaders, java.util.function.BooleanSupplier held) {
        this.dir = dir;
        this.userAgent = userAgent;
        this.network = network;
        this.held = held;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
        prefetcher = Executors.newSingleThreadExecutor(r -> thread(r, "mapPrefetch"));
        if (loaders) for (int i = 0; i < LOADERS; i++) thread(this::loaderRun, "mapTiles").start();
    }

    /** Whether fetching waits now: a drone on this PC may be flying (the map says so). */
    public boolean isHeld() {
        return held.getAsBoolean();
    }

    /** One tile waiting or loading, and who asked for it when. */
    static final class Request {
        final String key;
        final TileSource source;
        final int z, x, y;
        final Map<Runnable, Long> askers = new HashMap<>();

        Request(String key, TileSource source, int z, int x, int y) {
            this.key = key;
            this.source = source;
            this.z = z;
            this.x = x;
            this.y = y;
        }
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
     * loader thread when it is there - every onReady that asked meanwhile. x wraps round the world; a y outside the
     * world is nothing. The newest asked is loaded first (1.0.49, M13): a map asks for what it shows at every paint,
     * so what is on the screen now goes before what was minutes ago.
     */
    public BufferedImage get(TileSource source, int z, int x, int y, Runnable onReady) {
        int n = 1 << z;
        if (y < 0 || y >= n || z < 0 || z > source.maxZoom) return null;
        x = Math.floorMod(x, n);
        String key = source.id + "/" + z + "/" + x + "/" + y;
        Runnable asker = onReady == null ? NO_ONE : onReady;
        synchronized (this) {
            long now = clock.getAsLong();
            lastAsk.put(asker, now); // this map paints now: what it asked for before and not now is off its screen
            BufferedImage img = memory.get(key);
            if (img != null) return img;
            Long until = failedUntil.get(key);
            if (until != null && until > now) return null;
            Request loadingNow = active.get(key);
            if (loadingNow != null) {
                loadingNow.askers.put(asker, now);
                return null;
            }
            Request r = queued.remove(key); // asked again: the newest again
            if (r == null) r = new Request(key, source, z, x, y);
            r.askers.put(asker, now);
            queued.putLast(key, r);
            while (queued.size() > QUEUE_MAX) queued.pollFirstEntry(); // the longest ago asked go
            notifyAll();
        }
        return null;
    }

    /**
     * The next tile to load, newest first, those off every asker's screen dropped; null when none waits. Taken from
     * the queue into the loading ones. Package-private: the tests take the queue themselves.
     */
    synchronized Request takeNext() {
        for (Map.Entry<String, Request> e; (e = queued.pollLastEntry()) != null; ) {
            Request r = e.getValue();
            if (stale(r)) continue;
            active.put(r.key, r);
            return r;
        }
        return null;
    }

    /** Every map that asked for it has painted since without it, for STALE_MS: it is no longer shown. */
    private boolean stale(Request r) {
        for (Map.Entry<Runnable, Long> a : r.askers.entrySet()) {
            Long last = a.getKey() == NO_ONE ? null : lastAsk.get(a.getKey());
            if (last == null || last - a.getValue() < STALE_MS) return false;
        }
        return !r.askers.isEmpty();
    }

    /** The keys waiting, the next to load first (tests). */
    synchronized List<String> queuedKeys() {
        List<String> out = new ArrayList<>(queued.sequencedKeySet().reversed());
        return out;
    }

    private void loaderRun() {
        while (true) {
            Request r;
            synchronized (this) {
                while ((r = takeNext()) == null) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            try {
                load(r);
            } catch (RuntimeException ignored) {
                // load handles its own; never lose the thread
            }
        }
    }

    private Path file(TileSource source, int z, int x, int y) {
        return dir.resolve(source.id).resolve(Integer.toString(z)).resolve(Integer.toString(x)).resolve(y + ".png");
    }

    /**
     * A tile now, on the caller's thread: memory, disk, else the server (kept on disk under id like the map's own).
     * For the radio horizon, which needs the terrain and the map pictures round a point before it can work. Null
     * when it can't be had (offline, or the server said no). Not kept in memory: thousands of them would push the
     * map's own tiles out; the caller keeps what it needs of each.
     */
    public BufferedImage fetchNow(String id, String url, int z, int x, int y) {
        synchronized (this) {
            BufferedImage img = memory.get(id + "/" + z + "/" + x + "/" + y);
            if (img != null) return img;
        }
        BufferedImage img = null;
        Path file = dir.resolve(id).resolve(Integer.toString(z)).resolve(Integer.toString(x)).resolve(y + ".png");
        try {
            if (Files.isRegularFile(file)) img = read(file);
            if (img == null && held.getAsBoolean()) return null; // a drone here may fly: the disk only
            if (img == null) {
                byte[] data = fetch(url);
                if (data != null) {
                    img = ImageIO.read(new java.io.ByteArrayInputStream(data));
                    if (img != null) write(file, data);
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return img;
    }

    public BufferedImage fetchNow(TileSource source, int z, int x, int y) {
        return fetchNow(source.id, source.url(z, x, y), z, x, y);
    }

    /** A taken tile: from disk, else (unless held) from its server; then everyone who asked for it is called. */
    void load(Request r) {
        BufferedImage img = null;
        boolean wasHeld = false;
        try {
            Path file = file(r.source, r.z, r.x, r.y);
            if (Files.isRegularFile(file)) img = read(file);
            if (img == null && held.getAsBoolean()) {
                wasHeld = true; // a drone here may fly: not fetched now
            } else if (img == null) {
                byte[] data = fetch(r.source.url(r.z, r.x, r.y));
                if (data != null) {
                    img = ImageIO.read(new java.io.ByteArrayInputStream(data));
                    if (img != null) write(file, data);
                }
            }
        } catch (IOException | RuntimeException e) {
            // no tile this time; tried again after RETRY_MS
        }
        List<Runnable> call;
        synchronized (this) {
            active.remove(r.key);
            call = new ArrayList<>(r.askers.keySet());
            if (img != null) {
                memory.put(r.key, img);
                failedUntil.remove(r.key);
            } else {
                long now = clock.getAsLong();
                failedUntil.values().removeIf(until -> until <= now); // a long offline session would otherwise keep every miss
                failedUntil.put(r.key, now + (wasHeld ? HELD_RETRY_MS : RETRY_MS));
            }
        }
        if (img != null) {
            for (Runnable c : call) if (c != NO_ONE) c.run();
        } else if (wasHeld) {
            wakeLater(call);
        }
    }

    /**
     * Maps whose tile was held back (a drone here may fly) are called again after HELD_RETRY_MS, so they paint and ask
     * again - a map repaints only on a change (1.0.49), and landing changes nothing it sees. Once per wait, whatever
     * the number of tiles.
     */
    private final java.util.Set<Runnable> heldAskers = new java.util.HashSet<>();
    private boolean wakeScheduled;
    private static final java.util.concurrent.ScheduledExecutorService WAKER = Executors.newSingleThreadScheduledExecutor(r -> thread(r, "mapTilesWake"));

    private void wakeLater(List<Runnable> askers) {
        synchronized (this) {
            for (Runnable a : askers) if (a != NO_ONE) heldAskers.add(a);
            if (wakeScheduled || heldAskers.isEmpty()) return;
            wakeScheduled = true;
        }
        WAKER.schedule(() -> {
            List<Runnable> wake;
            synchronized (this) {
                wakeScheduled = false;
                wake = new ArrayList<>(heldAskers);
                heldAskers.clear();
            }
            for (Runnable a : wake) a.run();
        }, HELD_RETRY_MS + 50, java.util.concurrent.TimeUnit.MILLISECONDS);
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
        if (held.getAsBoolean()) { // a drone here may fly: no prefetch (the newer call above stops a running one)
            prefetchLeft.set(0);
            return;
        }
        List<Tile> tiles = plan(zFrom, Math.max(0, zMin), Math.min(source.maxZoom, zMax), minLat, maxLat, minLon, maxLon, budget);
        prefetchLeft.set(tiles.size());
        prefetcher.execute(() -> {
            int left = tiles.size();
            for (Tile t : tiles) {
                if (prefetchGeneration.get() != generation) return; // a newer area
                if (held.getAsBoolean()) { // armed meanwhile: the rest another time
                    if (prefetchGeneration.get() == generation) prefetchLeft.set(0);
                    return;
                }
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
        /** Held because a drone on this PC may fly (1.0.49): it goes on by itself once none may. */
        public boolean isHeldForFlight() {
            return !finished && !cancelled && held.getAsBoolean();
        }

        private boolean waitWhilePaused() {
            while ((paused || held.getAsBoolean()) && !cancelled) {
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
