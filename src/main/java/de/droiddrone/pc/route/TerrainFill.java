package de.droiddrone.pc.route;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Fleet PRD RT-2: fills a {@link TerrainStore} with the heights a route needs, from a lookup that may take its time
 * (the map's online {@link de.droiddrone.pc.map.Elevation}: Kartverket in Norway, the world service elsewhere). Run
 * off the Swing thread ({@link #start}). Nodes the lookup can't answer stay missing, and the route can't be planned
 * until they are found ({@link RoutePlanner} refuses it) - nothing is guessed.
 */
public final class TerrainFill {
    /** A height lookup that blocks: metres above sea level, or null / NaN when unknown. */
    @FunctionalInterface
    public interface Lookup {
        Double heightM(double lat, double lon);
    }

    /** How far along: done of total nodes, found of them. */
    @FunctionalInterface
    public interface Progress {
        void at(int done, int total, int found);
    }

    private TerrainFill() {
    }

    /**
     * Looks up every node of nodes on the caller's thread, stores each found, flushes the store at the end (and
     * every 200 nodes). Stops early when cancelled. Returns how many were found.
     */
    public static int fill(TerrainStore store, List<double[]> nodes, Lookup lookup, Progress progress, BooleanSupplier cancelled) {
        int found = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (cancelled != null && cancelled.getAsBoolean()) break;
            double[] n = nodes.get(i);
            Double h = lookup.heightM(n[0], n[1]);
            if (h != null && !h.isNaN()) {
                store.put(n[0], n[1], h);
                found++;
            }
            if (progress != null) progress.at(i + 1, nodes.size(), found);
            if ((i + 1) % 200 == 0) flushQuietly(store);
        }
        flushQuietly(store);
        return found;
    }

    /** {@link #fill} on a daemon thread of its own (low priority); done gets the count found. */
    public static Thread start(TerrainStore store, List<double[]> nodes, Lookup lookup, Progress progress,
                               BooleanSupplier cancelled, java.util.function.IntConsumer done) {
        Thread t = new Thread(() -> {
            int found = fill(store, nodes, lookup, progress, cancelled);
            if (done != null) done.accept(found);
        }, "terrainFill");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        return t;
    }

    /** The map's height service as a blocking lookup (never on the Swing thread). */
    public static Lookup of(de.droiddrone.pc.map.Elevation elevation) {
        return elevation::heightNow;
    }

    private static void flushQuietly(TerrainStore store) {
        try {
            store.flush();
        } catch (java.io.IOException ignored) {
            // kept in memory; written at the next flush
        }
    }
}
