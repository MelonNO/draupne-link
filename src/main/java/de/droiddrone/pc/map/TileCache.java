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

    /**
     * A tile that could not be fetched for a reason that passes - no answer, half an answer, a hang-up, 503, a
     * sign-in page instead of a picture - is asked for again after this long, twice as long after each further miss,
     * a minute at most; and the maps that show it are called then, so they ask without being touched (1.0.71).
     */
    static final long RETRY_FIRST_MS = 2000;
    /** Misses in a row after which the connections are thrown away and made anew (one may be dead and used again). */
    static final int RENEW_AFTER = 3;
    /** How long after the last miss the map still says it shows saved tiles only, when nothing has been reached since. */
    static final long OFFLINE_KEEP_MS = 150_000;

    private final Path dir;
    private volatile HttpClient http;
    private final String userAgent;
    /** True while a drone on this PC may fly (PossiblyFlying, 1.0.49): nothing is fetched, the disk still serves. */
    private final java.util.function.BooleanSupplier held;
    java.util.function.LongSupplier clock = System::currentTimeMillis; // tests set their own
    /**
     * How long a server may take to begin its answer; how long an answer once begun may stand still; and the longest
     * any answer may take in all (tests make them short).
     */
    long headMs = 12_000, stallMs = 15_000, wholeMs = 120_000;
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
    /** The tiles whose miss passes (see RETRY_FIRST_MS), and how often in a row each has missed. */
    private final Map<String, Integer> tileMisses = new HashMap<>();
    /** Fetches in a row that reached no server, when the first of them was, and when a server was last reached / missed. */
    private int missesInARow;
    private long firstMissAt;
    private volatile long reachedAt, missedAt;
    private volatile boolean triedWithoutNetwork; // disk only (tests): a fetch was wanted
    private boolean saidMissed;
    /** How often the connections were made anew (tests). */
    volatile int renewals;
    /** A line for the app's log when the map's servers stop answering and when they answer again; tests listen. */
    java.util.function.Consumer<String> say = de.droiddrone.pc.AppLog::line;
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
        http = newClient();
        prefetcher = Executors.newSingleThreadExecutor(r -> thread(r, "mapPrefetch"));
        if (loaders) for (int i = 0; i < LOADERS; i++) thread(this::loaderRun, "mapTiles").start();
    }

    /**
     * HTTP/1.1 on purpose (1.0.71): one connection a request, and one that got no answer in time is closed. Over
     * HTTP/2 every tile shares one connection, and one gone dead without a word - the PC moved from Wi-Fi to 4G, woke
     * from sleep, the VPN came up - went on being used: every tile timed out on it while the internet was fine.
     */
    private static HttpClient newClient() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** Tests only: nothing more is asked for, loaded or called (the app keeps its one cache for as long as it runs). */
    void end() {
        ended = true;
        synchronized (this) {
            queued.clear();
            wakeAt.clear();
        }
    }

    private volatile boolean ended;

    /** Disk only, its loaders running, and held back while the supplier says so - as while a drone may fly (other packages' tests). */
    public static TileCache heldWhile(Path dir, java.util.function.BooleanSupplier held) {
        return new TileCache(dir, "test", false, true, held);
    }

    /** Disk only, and nothing ever loads: what is asked stays on its way (other packages' tests). */
    public static TileCache neverLoading(Path dir) {
        return new TileCache(dir, "test", false, false, () -> false);
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
        /** Asked for by the cache itself while tiles miss ({@link #probeDue}); fetched: it came from the server, not the disk. */
        boolean probe, fetched;

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

    /**
     * True while the last fetch reached no server (the map then says it shows saved tiles only) - for
     * {@link #OFFLINE_KEEP_MS} after it at most: a miss with nothing asked for since (all that is shown is on disk)
     * left the map saying so for the rest of the session.
     */
    public boolean isOffline() {
        if (!network) return triedWithoutNetwork;
        long missed = missedAt;
        return missed > reachedAt && clock.getAsLong() - missed < OFFLINE_KEEP_MS;
    }

    /**
     * Tiles are on their way to a map now (1.0.56, the pilot: "an indication that the map is loading"): one loading,
     * or one waiting that a map still shows. Not while fetching is held (a drone may fly): nothing would come.
     */
    public synchronized boolean loading() {
        if (isHeld()) return false;
        if (!active.isEmpty()) return true;
        for (Request r : queued.values()) if (!stale(r)) return true;
        return false;
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
        if (y < 0 || y >= n || z < 0 || z > source.maxZoom || ended) return null;
        x = Math.floorMod(x, n);
        String key = source.id + "/" + z + "/" + x + "/" + y;
        Runnable asker = onReady == null ? NO_ONE : onReady;
        synchronized (this) {
            long now = clock.getAsLong();
            lastAsk.put(asker, now); // this map paints now: what it asked for before and not now is off its screen
            BufferedImage img = memory.get(key);
            if (img != null) return img;
            Long until = failedUntil.get(key);
            if (until != null && until > now) {
                // it missed for a reason that passes: this map is called when it is asked for again - a map paints
                // only when called, and one that showed a missed tile kept its blank square until the pilot moved it
                if (tileMisses.containsKey(key) && asker != NO_ONE) wakeAt(asker, until);
                return null;
            }
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
            } catch (Throwable t) {
                // load handles its own; never lose the thread - with both gone no tile loads until the app is started
                // again. An Error too (a picture too big for the memory left): said, and on
                try {
                    say.accept("Map: a tile failed to load (" + t + ").");
                } catch (Throwable saying) {
                    // not even that: the thread stays all the same
                }
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
                    img = decode(data);
                    if (img != null) keep(file, data);
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return img;
    }

    /** The picture in what a server sent; null when it is none (a sign-in page, an error page, half a file). */
    private static BufferedImage decode(byte[] data) {
        try {
            return ImageIO.read(new java.io.ByteArrayInputStream(data));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A fetched tile to the disk; when the disk won't take it (full, read-only) the tile is still shown this time. */
    private static void keep(Path file, byte[] data) {
        try {
            write(file, data);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    public BufferedImage fetchNow(TileSource source, int z, int x, int y) {
        return fetchNow(source.id, source.url(z, x, y), z, x, y);
    }

    /** A taken tile: from disk, else (unless held) from its server; then everyone who asked for it is called. */
    void load(Request r) {
        BufferedImage img = null;
        boolean wasHeld = false, passes = true; // passes: the miss is one that passes - asked for again soon, by itself
        try {
            Path file = file(r.source, r.z, r.x, r.y);
            if (Files.isRegularFile(file)) img = read(file);
            if (img == null && held.getAsBoolean()) {
                wasHeld = true; // a drone here may fly: not fetched now
            } else if (img == null) {
                Answer a = answer(r.source.url(r.z, r.x, r.y));
                if (a.body() != null) {
                    img = decode(a.body());
                    if (img != null) {
                        keep(file, a.body());
                        r.fetched = true;
                    }
                } else if (a.status() != 0 && !busy(a.status())) {
                    passes = false; // the server said no (404 outside its map, 403): as before, a minute, and not by itself
                }
            }
        } catch (IOException | RuntimeException e) {
            // no tile this time
        } finally {
            // whatever happened above, an Error too: the tile leaves "loading" - one left there was never asked for
            // again, and the map said LOADING MAP for the rest of the session
            settle(r, img, wasHeld, passes);
        }
    }

    /** 408, 425, 429 and the 500s: the server is there and can't just now. */
    private static boolean busy(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    /** How long a tile waits after its n-th miss in a row. */
    static long retryAfter(int n) {
        return Math.min(RETRY_MS, RETRY_FIRST_MS << Math.min(10, Math.max(0, n - 1)));
    }

    private void settle(Request r, BufferedImage img, boolean wasHeld, boolean passes) {
        List<Runnable> call;
        long again = 0;
        synchronized (this) {
            active.remove(r.key);
            call = new ArrayList<>(r.askers.keySet());
            if (img != null) {
                memory.put(r.key, img);
                failedUntil.remove(r.key);
                tileMisses.remove(r.key);
                if (r.fetched) {
                    long now = clock.getAsLong();
                    fetchedAt = now;
                    probesInARow = 0;
                    long wait = forwardWaitMs == 0 ? probeMs : forwardWaitMs;
                    if (tileMisses.isEmpty()) {
                        forwardWaitMs = 0; // all there: the next trouble starts afresh
                    } else if (forwardedAt == 0 || now - forwardedAt >= wait) {
                        // the server gives tiles: the ones waiting out a miss now, not after their waits. Twice as
                        // long before the next time - a server that gives one tile and refuses the rest is not
                        // asked for the whole screen every time one comes
                        forwardedAt = now;
                        forwardWaitMs = Math.min(RETRY_MS, wait * 2);
                        bringForward();
                    }
                }
            } else {
                long now = clock.getAsLong();
                failedUntil.values().removeIf(until -> until <= now); // a long offline session would otherwise keep every miss
                if (tileMisses.size() > 4000) {
                    // and every count of them - with their waits: a wait without its count woke no map, and the
                    // tile stayed blank until the map was touched
                    bringForward();
                    tileMisses.clear();
                }
                if (wasHeld) {
                    failedUntil.put(r.key, now + HELD_RETRY_MS);
                } else if (passes) {
                    again = now + retryAfter(tileMisses.merge(r.key, 1, Integer::sum));
                    failedUntil.put(r.key, again);
                    probeIdle = 0;
                    setProbe(r);
                } else {
                    tileMisses.remove(r.key);
                    failedUntil.put(r.key, now + RETRY_MS);
                }
            }
        }
        if (img != null) {
            for (Runnable c : call) if (c != NO_ONE) callSafely(c);
        } else if (wasHeld) {
            wakeLater(call);
        } else if (again > 0) {
            for (Runnable c : call) if (c != NO_ONE) wakeAt(c, again);
        }
    }

    /** A map's own failure when called is its own: the others that asked are still called, the loader goes on. */
    private static void callSafely(Runnable asker) {
        try {
            asker.run();
        } catch (RuntimeException ignored) {
        }
    }

    // ---- maps called again when a tile they show can be asked for again (a miss that passes)

    private final Map<Runnable, Long> wakeAt = new HashMap<>();
    /** When the next wake is set for (clock ms); 0: none is. */
    private long wakeSetFor;

    private void wakeAt(Runnable asker, long at) {
        synchronized (this) {
            wakeAt.merge(asker, at, Math::min);
            setWake();
        }
    }

    /** One timer for the soonest of them; when it rings, the next. Called with the lock held. */
    private void setWake() {
        if (wakeAt.isEmpty()) return;
        long soonest = Long.MAX_VALUE;
        for (long at : wakeAt.values()) soonest = Math.min(soonest, at);
        if (wakeSetFor != 0 && wakeSetFor <= soonest) return;
        // one timer at a time: a timer left pending when a sooner one was set rang later with nothing due and set
        // yet another - with two maps waiting through an outage their number only grew
        if (wakeTimer != null) wakeTimer.cancel(false);
        wakeSetFor = soonest;
        wakeTimer = WAKER.schedule(this::wakeDue, Math.max(0, soonest - clock.getAsLong()) + 30, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private java.util.concurrent.ScheduledFuture<?> wakeTimer;

    /** How many times the wake timer rang (tests). */
    volatile int wakeRuns;

    private void wakeDue() {
        wakeRuns++;
        List<Runnable> due = new ArrayList<>();
        synchronized (this) {
            if (ended) return;
            long now = clock.getAsLong();
            wakeSetFor = 0;
            for (java.util.Iterator<Map.Entry<Runnable, Long>> it = wakeAt.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Runnable, Long> e = it.next();
                if (e.getValue() <= now) {
                    due.add(e.getKey());
                    it.remove();
                }
            }
            setWake();
        }
        for (Runnable asker : due) callSafely(asker);
    }

    // ---- one tile asked for now and then while tiles miss (1.0.71, the bench's night: MapNightScenario)

    /**
     * While tiles wait out their misses and a map shows them, one of them is asked for this often whatever the tiles'
     * own waits have grown to. Each tile's wait doubles up to a minute, so after a minute of a server in trouble every
     * tile was 32 to 60 s from its next try: the internet came back and the map stayed empty for most of a minute
     * more (measured: 59 s after 62 s of 503s, 32 s after half a minute). When that one tile comes, the others are
     * asked for at once ({@link #bringForward}; their miss counts stay, so tiles that fail again wait longer, not
     * shorter). Only while nothing at all has come from the server since the last such try: with tiles loading, what
     * still misses is those tiles' own matter and their own waits do. One small request every few seconds is less
     * than the tiles' own tries cost a server in trouble.
     */
    long probeMs = 8000;
    /** The tile that last missed for a reason that passes: the one asked for again as the probe. */
    private Request probe;
    private boolean probeSet;
    private int probeIdle;
    /** When a tile last came from the server, and when the probe last looked (clock ms). */
    private long fetchedAt, probedAt;

    /** Called with the lock held, after a miss that passes. */
    private void setProbe(Request missed) {
        probe = missed;
        if (probeSet || ended) return;
        probeSet = true;
        probedAt = clock.getAsLong(); // what comes from the server from now on says it gives tiles
        // every probeMs for the first PROBES_QUICK tries without a tile (five minutes), then a quarter as often: an
        // outage ends within minutes or lasts, and a tile that never comes is not asked for every 8 s all day
        long wait = probesInARow < PROBES_QUICK ? probeMs : probeMs * 4;
        WAKER.schedule(this::probeDue, wait, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    static final int PROBES_QUICK = 40;
    /** Probes asked since a tile last came from the server. */
    private int probesInARow;
    /** When the waiting tiles were last brought forward because a tile came, and how long until the next time (0: probeMs). */
    private long forwardedAt, forwardWaitMs;

    /** Some tile has missed twice in a row or more: the server gives no tiles just now (the map says so). */
    public synchronized boolean inTrouble() {
        for (int misses : tileMisses.values()) if (misses >= 2) return true;
        return false;
    }

    private void probeDue() {
        synchronized (this) {
            probeSet = false;
            Request p = probe;
            if (ended || p == null || tileMisses.isEmpty()) return; // no tile waits out a miss any more
            if (wakeAt.isEmpty()) {
                // no map waits for a tile: closed or moved to tiles it has - or just called and about to ask again
                // (a window paints a moment after it is called). Looked at three more times, then left alone
                if (++probeIdle <= 3) setProbe(p);
                return;
            }
            probeIdle = 0;
            long now = clock.getAsLong();
            boolean tilesCome = fetchedAt != 0 && fetchedAt >= probedAt;
            if (tilesCome || held.getAsBoolean() || active.containsKey(p.key) || queued.containsKey(p.key)) {
                setProbe(p); // the server gives tiles, nothing may be fetched just now, or it is on its way as it is
                return;
            }
            failedUntil.remove(p.key);
            Request r = new Request(p.key, p.source, p.z, p.x, p.y);
            r.probe = true;
            probesInARow++;
            r.askers.put(NO_ONE, now);
            queued.putLast(p.key, r); // the newest asked: the next a loader takes
            notifyAll();
        }
    }

    /**
     * A tile came from the server: every tile waiting out a miss is asked for now, by the maps that show it. The
     * miss counts stay - a tile that fails again waits longer, not from the start. Called with the lock held.
     */
    private void bringForward() {
        long now = clock.getAsLong();
        for (String key : tileMisses.keySet()) failedUntil.remove(key);
        for (Map.Entry<Runnable, Long> e : wakeAt.entrySet()) e.setValue(now);
        wakeSetFor = 0;
        setWake();
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
                        else if (isOffline()) return; // no point going on without a network
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
                            else misses.incrementAndGet(); // not answered: no server reached
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

    /**
     * One request, with a time limit for the whole of it. 1.0.71 (the pilot: "the map is stuck loading even though
     * the computer has internet"): the request's own timeout ends when the answer's head is in, so a transfer that
     * stalled after it - half a tile, then nothing, as a weak or changing link does - held its loader thread for
     * ever; two of those and no tile loaded again until the app was restarted, LOADING MAP turning all the while.
     * Now the head has {@link #headMs}, and an answer that has begun is given up when nothing more of it came for
     * {@link #stallMs}; that counts as a server not reached (status 0), like no connection, no name, a hang-up or
     * half an answer.
     * <p>
     * Until 2026-11-20 the limit was 20 s for the whole answer, however well it was coming: on an internet that
     * gives a kilobyte or two a second - one bar, or the downlink shared with a drone's video - no tile was ever
     * whole in time, each was thrown away and begun again from nothing, and the map stayed empty for as long as the
     * link stayed slow. Now a slow tile that keeps coming is taken; {@link #wholeMs} is only the end of patience
     * with an answer that trickles without end.
     */
    private Answer answer(String url) throws IOException {
        if (!network) {
            triedWithoutNetwork = true;
            return new Answer(0, null);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(de.droiddrone.pc.Outside.url(url))).timeout(Duration.ofMillis(headMs))
                .header("User-Agent", userAgent).header("Accept", "image/png,image/*").GET().build();
        HttpClient client = http;
        // when the answer last moved (its head in, or more of its body), by System.nanoTime; NOT_BEGUN before the head
        java.util.concurrent.atomic.AtomicLong movedAt = new java.util.concurrent.atomic.AtomicLong(NOT_BEGUN);
        java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> asked = client.sendAsync(request, info -> moving(movedAt));
        long begun = System.nanoTime();
        try {
            HttpResponse<byte[]> response;
            while (true) {
                long now = System.nanoTime(), moved = movedAt.get();
                long wholeLeft = wholeMs - (now - begun) / 1_000_000;
                long stallLeft = moved == NOT_BEGUN ? Long.MAX_VALUE : stallMs - (now - moved) / 1_000_000;
                if (wholeLeft <= 0 || stallLeft <= 0) {
                    asked.cancel(true); // lets go of the connection
                    missed(client, stallLeft <= 0 ? "its answer stood still for " + stallMs / 1000 + " s" : "no whole answer in " + wholeMs / 1000 + " s");
                    return new Answer(0, null);
                }
                try {
                    // in short waits: the head may come in the middle of one, and the standing still counts from then
                    response = asked.get(Math.min(200, Math.min(wholeLeft, stallLeft)), java.util.concurrent.TimeUnit.MILLISECONDS);
                    break;
                } catch (java.util.concurrent.TimeoutException e) {
                    // not yet: looked at again
                }
            }
            reached();
            return new Answer(response.statusCode(), response.statusCode() == 200 ? response.body() : null);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable why = e.getCause() == null ? e : e.getCause();
            missed(client, why.getClass().getSimpleName() + (why.getMessage() == null ? "" : ": " + why.getMessage()));
            return new Answer(0, null);
        } catch (InterruptedException e) {
            asked.cancel(true);
            Thread.currentThread().interrupt();
            return new Answer(0, null);
        }
    }

    private static final long NOT_BEGUN = Long.MIN_VALUE;

    /** An answer's body as bytes, with every piece of it that comes written down as "it moved" (see {@link #answer}). */
    private static HttpResponse.BodySubscriber<byte[]> moving(java.util.concurrent.atomic.AtomicLong movedAt) {
        HttpResponse.BodySubscriber<byte[]> to = HttpResponse.BodySubscribers.ofByteArray();
        movedAt.set(System.nanoTime()); // the head is in
        return new HttpResponse.BodySubscriber<>() {
            @Override
            public java.util.concurrent.CompletionStage<byte[]> getBody() {
                return to.getBody();
            }

            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                to.onSubscribe(subscription);
            }

            @Override
            public void onNext(List<java.nio.ByteBuffer> piece) {
                movedAt.set(System.nanoTime());
                to.onNext(piece);
            }

            @Override
            public void onError(Throwable why) {
                to.onError(why);
            }

            @Override
            public void onComplete() {
                to.onComplete();
            }
        };
    }

    /** A server answered (whatever it said). After fetches that reached none: said in the log. */
    private void reached() {
        long now = clock.getAsLong();
        boolean back, said;
        long since;
        synchronized (this) {
            back = missesInARow > 0;
            said = saidMissed;
            since = firstMissAt;
            missesInARow = 0;
            saidMissed = false;
            reachedAt = now;
        }
        if (!back) return;
        if (said) say.accept("Map: its server answers again, " + Math.max(1, (now - since) / 1000) + " s after the first miss.");
        // The waiting tiles are not called in from here: any answer of any server counted - a 503, the terrain's
        // server while the map's own hung up - and each one threw every tile's wait away, so a server in trouble
        // was asked without a pause (a review of 2026-11-19). A tile that comes does that (settle).
    }

    /**
     * A fetch reached no server. The first of a row is said in the log; after every {@link #RENEW_AFTER} in a row
     * the connections are made anew - a kept connection that has gone dead is not found out by using it again.
     */
    private void missed(HttpClient used, String why) {
        boolean first, renew;
        synchronized (this) {
            long now = clock.getAsLong();
            first = missesInARow == 0;
            if (first) firstMissAt = now;
            missesInARow++;
            missedAt = now;
            renew = missesInARow % RENEW_AFTER == 0 && http == used;
            if (first) saidMissed = true;
        }
        if (first) say.accept("Map: its server was not reached (" + why + "). Asked again by itself.");
        if (renew) {
            HttpClient fresh = newClient(); // not under the lock: the window's thread asks for tiles under it
            boolean swapped;
            synchronized (this) {
                swapped = http == used;
                if (swapped) {
                    http = fresh;
                    renewals++;
                }
            }
            try {
                // The old one takes nothing new and ends when what it carries has ended (each within its own time
                // limit). Not shutdownNow: that broke off everybody else's request on it - the other loader's, an
                // area download's, the terrain a radio horizon was reading, which is not asked for again
                (swapped ? used : fresh).shutdown();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * The tile in a file; null when it holds none - empty, cut short, or broken in a way that makes the reader throw
     * rather than say so (1.0.71: that one was never fetched again, its square blank for good). The caller fetches it anew.
     */
    private static BufferedImage read(Path file) {
        try {
            return ImageIO.read(file.toFile());
        } catch (IOException | RuntimeException e) {
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
