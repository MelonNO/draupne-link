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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Map tiles: in memory for the ones on screen, on disk for everything ever shown (tiles\source\z\x\y.png), fetched
 * from the tile server with two connections at most. A tile older than {@value #REFRESH_DAYS} days is fetched again
 * when the server answers, else the old one is shown: the map works where the PC has no internet, for every place the
 * pilot has looked at before. Thread-safe; the fetches run on their own threads and call back when a tile is in.
 */
public final class TileCache {
    static final int REFRESH_DAYS = 30;
    private static final int MEMORY_TILES = 600;
    private static final long RETRY_MS = 60_000;

    private final Path dir;
    private final HttpClient http;
    private final String userAgent;
    private final ExecutorService pool;
    private final Map<String, BufferedImage> memory = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > MEMORY_TILES;
        }
    };
    private final Set<String> loading = new HashSet<>();
    private final Map<String, Long> failedUntil = new HashMap<>();
    private volatile boolean offline; // no network at all: the last fetch didn't reach the server
    private final boolean network; // false in tests: disk only

    public TileCache(Path dir) {
        this(dir, "DRAUPNE-Link/" + de.droiddrone.pc.AppInfo.version() + " (+https://github.com/MelonNO/draupne-link)", true);
    }

    /** @param network false: disk only (tests) */
    public TileCache(Path dir, String userAgent, boolean network) {
        this.dir = dir;
        this.userAgent = userAgent;
        this.network = network;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
        pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "mapTiles");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /** %APPDATA%\DraupneLink\tiles. */
    public static Path defaultDir() {
        return de.droiddrone.pc.AppPaths.dataDir().resolve("tiles");
    }

    /** True once a fetch failed to reach any server (the map then says it shows saved tiles only). */
    public boolean isOffline() {
        return offline;
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

    private void load(String key, TileSource source, int z, int x, int y, Runnable onReady) {
        BufferedImage img = null;
        try {
            Path file = dir.resolve(source.id).resolve(Integer.toString(z)).resolve(Integer.toString(x)).resolve(y + ".png");
            boolean onDisk = Files.isRegularFile(file);
            boolean fresh = onDisk && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < REFRESH_DAYS * 86_400_000L;
            if (onDisk && (fresh || offline)) img = read(file);
            if (img == null) {
                byte[] data = fetch(source.url(z, x, y));
                if (data != null) {
                    img = ImageIO.read(new java.io.ByteArrayInputStream(data));
                    if (img != null) write(file, data);
                }
            }
            if (img == null && onDisk) img = read(file); // stale is better than nothing
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
