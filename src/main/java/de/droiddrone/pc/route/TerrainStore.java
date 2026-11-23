package de.droiddrone.pc.route;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fleet PRD RT-2 and "Offline": the terrain heights this PC has looked up, kept on disk so a route over an area
 * sampled once plans again without the internet. Heights sit on a grid of {@link #GRID_DEG} degrees (about 11 m
 * north-south, 6 m east-west in Norway); a place takes the height of its nearest grid node. Files:
 * %APPDATA%\DraupneLink\terrain\&lt;lat tenth&gt;_&lt;lon tenth&gt;.txt, one node a line ("latIndex lonIndex height").
 * Thread-safe (one lock): the fill writes from its thread while the map plans.
 */
public final class TerrainStore implements TerrainSource {
    public static final double GRID_DEG = 1e-4;
    /** A tile file covers 0.1 degree each way (1000 x 1000 nodes at most). */
    static final int TILE_NODES = 1000;

    private final Path dir;
    private final Map<Long, Float> heights = new HashMap<>();
    private final Set<Long> loadedTiles = new HashSet<>();
    private final Set<Long> dirtyTiles = new LinkedHashSet<>();

    /** @param dir the folder of its files; null keeps it in memory only (the tests) */
    public TerrainStore(Path dir) {
        this.dir = dir;
    }

    /** The store in %APPDATA%\DraupneLink\terrain. */
    public static TerrainStore onDisk() {
        return new TerrainStore(de.droiddrone.pc.AppPaths.dataDir().resolve("terrain"));
    }

    public static long latIndex(double lat) {
        return Math.round(lat / GRID_DEG);
    }

    public static long lonIndex(double lon) {
        return Math.round(lon / GRID_DEG);
    }

    static long key(long latIndex, long lonIndex) {
        return (latIndex + 1_000_000L) * 10_000_000L + (lonIndex + 2_000_000L);
    }

    /** The grid node nearest a place: {lat, lon} in degrees. */
    public static double[] node(double lat, double lon) {
        return new double[]{latIndex(lat) * GRID_DEG, lonIndex(lon) * GRID_DEG};
    }

    private static long tileOf(long latIndex, long lonIndex) {
        return key(Math.floorDiv(latIndex, TILE_NODES), Math.floorDiv(lonIndex, TILE_NODES));
    }

    @Override
    public synchronized double heightM(double lat, double lon) {
        long la = latIndex(lat), lo = lonIndex(lon);
        ensureLoaded(la, lo);
        Float h = heights.get(key(la, lo));
        return h == null ? Double.NaN : h;
    }

    /** A height looked up for the node nearest this place. */
    public synchronized void put(double lat, double lon, double heightM) {
        if (Double.isNaN(heightM)) return;
        long la = latIndex(lat), lo = lonIndex(lon);
        ensureLoaded(la, lo);
        heights.put(key(la, lo), (float) heightM);
        dirtyTiles.add(tileOf(la, lo));
    }

    public synchronized boolean has(double lat, double lon) {
        return !Double.isNaN(heightM(lat, lon));
    }

    /** Every node a path's samples fall on that has no height yet, each once, in path order: {lat, lon}. */
    public synchronized List<double[]> missing(List<double[]> samples) {
        Set<Long> seen = new HashSet<>();
        List<double[]> out = new ArrayList<>();
        for (double[] s : samples) {
            long la = latIndex(s[0]), lo = lonIndex(s[1]);
            long k = key(la, lo);
            if (!seen.add(k)) continue;
            ensureLoaded(la, lo);
            if (!heights.containsKey(k)) out.add(new double[]{la * GRID_DEG, lo * GRID_DEG});
        }
        return out;
    }

    /** What changed goes to disk (each changed tile written whole, to a file beside it, then moved over). */
    public void flush() throws IOException {
        if (dir == null) return;
        Map<Long, List<String>> write = new HashMap<>();
        synchronized (this) {
            for (long tile : dirtyTiles) write.put(tile, linesOf(tile));
            dirtyTiles.clear();
        }
        Files.createDirectories(dir);
        for (Map.Entry<Long, List<String>> e : write.entrySet()) {
            Path file = dir.resolve(fileName(e.getKey()));
            Path tmp = file.resolveSibling(file.getFileName() + ".part");
            Files.write(tmp, e.getValue(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<String> linesOf(long tile) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Long, Float> e : heights.entrySet()) {
            long k = e.getKey();
            long la = k / 10_000_000L - 1_000_000L, lo = k % 10_000_000L - 2_000_000L;
            if (tileOf(la, lo) != tile) continue;
            lines.add(la + " " + lo + " " + String.format(Locale.ROOT, "%.1f", e.getValue()));
        }
        return lines;
    }

    private static String fileName(long tile) {
        long tla = tile / 10_000_000L - 1_000_000L, tlo = tile % 10_000_000L - 2_000_000L;
        return tla + "_" + tlo + ".txt";
    }

    private void ensureLoaded(long la, long lo) {
        if (dir == null) return;
        long tile = tileOf(la, lo);
        if (!loadedTiles.add(tile)) return;
        Path file = dir.resolve(fileName(tile));
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.trim().split(" ");
                if (f.length != 3) continue;
                try {
                    heights.putIfAbsent(key(Long.parseLong(f[0]), Long.parseLong(f[1])), Float.parseFloat(f[2]));
                } catch (NumberFormatException ignored) {
                    // a broken line is looked up again
                }
            }
        } catch (IOException ignored) {
            // unreadable: those nodes are looked up again
        }
    }
}
