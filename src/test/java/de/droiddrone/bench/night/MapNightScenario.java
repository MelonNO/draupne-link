package de.droiddrone.bench.night;

import de.droiddrone.bench.Watch;
import de.droiddrone.pc.Outside;
import de.droiddrone.pc.map.MapBenchAccess;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;
import de.droiddrone.testing.TroubledServer;
import de.droiddrone.testing.TroubledServer.Mode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map on an internet that comes and goes, for hours ({@code BENCH_HOURS}) - the pilot's own report: "the map is
 * stuck loading even though the computer has internet". The fast tests ({@code TileCacheBadNetworkTest}) show each
 * kind of trouble once; a map that is open all day meets them hundreds of times, one after the other and mixed, and
 * what is left over from one must not spoil the next.
 * <p>
 * The app's own tile cache - its loader threads, its real time limits (12 s for an answer to begin, 20 s for the whole
 * of it) - against a server on this PC. Cycle after cycle: the map moves to a place it has never shown, and the
 * server takes requests and says nothing, sends half a tile and stops, hangs up, says 503, is gone, or does a mix of
 * all for a few seconds up to three minutes; then the server is fine. From that moment the map must have its twelve
 * tiles <b>without being touched</b> (a map repaints only when its cache calls it) - how long that takes is the
 * figure. On every fourth cycle the map also moves on a network that is fine: tiles within seconds, as if nothing had
 * been.
 * <p>
 * What must hold: never stuck (two minutes is counted as stuck; the scenario then touches the map to see whether that
 * helps, which a pilot should not have to); threads and memory do not climb; a fine network after any trouble is as
 * quick as a fresh start. One row a cycle goes to soak/map-night.tsv.
 */
@Tag("night")
class MapNightScenario {
    private static final TileSource MAP = TileSource.KARTVERKET_TOPO;
    private static final long STUCK_MS = 120_000;

    /** A map of twelve tiles: asks for each when it paints, paints when the cache calls it - as MapView does. */
    private static final class Screen implements Runnable {
        final TileCache cache;
        final Set<String> shown = ConcurrentHashMap.newKeySet();
        volatile int x0, y0;

        Screen(TileCache cache) {
            this.cache = cache;
        }

        /** To a place never shown before. */
        void moveTo(int place) {
            x0 = 100 + Math.floorMod(place * 7, 3500);
            y0 = 200 + Math.floorMod(place * 13, 3500);
            shown.clear();
        }

        void paint() {
            int x0 = this.x0, y0 = this.y0;
            for (int x = x0; x < x0 + 4; x++) {
                for (int y = y0; y < y0 + 3; y++) if (cache.get(MAP, 12, x, y, this) != null && x0 == this.x0) shown.add(x + "/" + y);
            }
        }

        @Override
        public void run() {
            paint();
        }

        /** Waits for all twelve tiles; how long it took, or -1 when they did not come in time. */
        long whole(long withinMs) throws InterruptedException {
            long t0 = System.currentTimeMillis();
            while (shown.size() < 12) {
                if (System.currentTimeMillis() - t0 > withinMs) return -1;
                Thread.sleep(50);
            }
            return System.currentTimeMillis() - t0;
        }
    }

    private static final String[] TROUBLES = {"says nothing", "half a tile", "hangs up", "503", "gone", "a mix"};

    @Test
    void theMapOnAnInternetThatComesAndGoesForHours() throws Exception {
        long endAt = System.currentTimeMillis() + Math.max(5 * 60_000L, Night.runMs() - 2 * 60_000L);
        BufferedImage tile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(tile, "png", out);
        Path dir = Files.createDirectories(de.droiddrone.bench.Sandbox.runRoot().resolve("work").resolve("map-night-" + System.currentTimeMillis()));
        List<String> said = new CopyOnWriteArrayList<>();
        Random random = new Random(Long.getLong("bench.seed", 20261119L));
        TroubledServer server = new TroubledServer(out.toByteArray(), "image/png");
        TileCache cache = null;
        try {
            Outside.use(new Outside.Doors() { // the map's own addresses, asked of the server on this PC
                @Override
                public String url(String url) {
                    return server.address() + URI.create(url).getPath();
                }
            });
            cache = MapBenchAccess.cache(dir, said::add);
            Screen map = new Screen(cache);
            Night.Table table = new Night.Table("map-night", "minutes", "cycle", "trouble s", "whole after s", "fine network s", "threads", "heap MB",
                    "connections renewed", "requests");
            List<String> wrong = new ArrayList<>();
            List<Long> heaps = new ArrayList<>(), times = new ArrayList<>();
            long t0 = System.currentTimeMillis(), worst = 0, worstFine = 0;
            int cycle = 0, threadsAfterWarmUp = 0;
            String worstAfter = "";
            while ((System.currentTimeMillis() < endAt || cycle < 4) && wrong.size() < 20) {
                cycle++;
                // ---- trouble: a few seconds mostly, now and then past the three minutes after which the map says SAVED TILES ONLY
                String trouble = TROUBLES[random.nextInt(TROUBLES.length)];
                long troubleMs = cycle % 9 == 0 ? 170_000 + random.nextInt(40_000) : 4000 + random.nextInt(cycle % 3 == 0 ? 60_000 : 20_000);
                if (endAt - System.currentTimeMillis() < 5 * 60_000L) troubleMs = Math.min(troubleMs, 30_000); // the last cycles are short ones
                switch (trouble) {
                    case "says nothing" -> server.always(Mode.SILENT);
                    case "half a tile" -> server.always(Mode.HALF);
                    case "hangs up" -> server.always(Mode.HANG_UP);
                    case "503" -> server.always(Mode.BUSY);
                    case "gone" -> server.down();
                    default -> server.each(n -> switch (ThreadLocalRandom.current().nextInt(5)) {
                        case 0 -> Mode.OK;
                        case 1 -> Mode.SILENT;
                        case 2 -> Mode.HALF;
                        case 3 -> Mode.HANG_UP;
                        default -> Mode.BUSY;
                    });
                }
                map.moveTo(cycle);
                map.paint();
                long troubleEnd = System.currentTimeMillis() + troubleMs;
                while (System.currentTimeMillis() < troubleEnd) {
                    Thread.sleep(1000);
                    if (random.nextInt(15) == 0) map.paint(); // the pilot moves the mouse over the map now and then
                }

                // ---- the internet is fine: the map by itself
                server.up();
                server.always(Mode.OK);
                long whole = map.whole(STUCK_MS);
                String what = "cycle " + cycle + " (" + (System.currentTimeMillis() - t0) / 60_000 + " min in), after " + troubleMs / 1000 + " s of \"" + trouble + "\"";
                if (whole < 0) {
                    int had = map.shown.size();
                    boolean loading = cache.loading(), offline = cache.isOffline();
                    map.paint();
                    long touched = map.whole(30_000);
                    wrong.add(what + ": the map did not have its tiles within " + STUCK_MS / 1000 + " s of a fine network - " + had + " of 12, loading=" + loading
                            + ", offline=" + offline + "; touched, " + (touched < 0 ? "still not within 30 s" : "it had them " + touched / 1000.0 + " s later"));
                    whole = STUCK_MS;
                } else if (whole > worst) {
                    worst = whole;
                    worstAfter = what;
                }
                times.add(whole);

                // ---- and on a network that is fine: a new place, as quick as a fresh start
                long fine = -1;
                if (cycle % 4 == 0) {
                    Thread.sleep(3000);
                    map.moveTo(-cycle);
                    map.paint();
                    fine = map.whole(20_000);
                    if (fine < 0) wrong.add(what + ": on the fine network after it a new place did not load within 20 s (" + map.shown.size() + " of 12)");
                    else worstFine = Math.max(worstFine, fine);
                }
                int threads = Thread.getAllStackTraces().size();
                if (cycle == 3) threadsAfterWarmUp = threads;
                long heap = Watch.usedMb();
                heaps.add(heap);
                table.row((System.currentTimeMillis() - t0) / 60_000.0, cycle, troubleMs / 1000.0, whole / 1000.0, fine < 0 ? "" : String.format(java.util.Locale.ROOT, "%.1f", fine / 1000.0),
                        threads, heap, MapBenchAccess.renewals(cache), server.requests());
            }
            List<Long> sorted = new ArrayList<>(times);
            sorted.sort(null);
            String summary = cycle + " cycles in " + (System.currentTimeMillis() - t0) / 60_000 + " min; the map whole after a fine network: half of the times within "
                    + sorted.get(sorted.size() / 2) / 1000.0 + " s, nine in ten within " + sorted.get(Math.min(sorted.size() - 1, sorted.size() * 9 / 10)) / 1000.0 + " s, at the slowest "
                    + worst / 1000.0 + " s (" + worstAfter + "); a new place on a fine network at the slowest " + worstFine / 1000.0 + " s; " + server.requests() + " requests, "
                    + MapBenchAccess.renewals(cache) + " connection pools renewed; threads " + threadsAfterWarmUp + " after the third cycle, " + Thread.getAllStackTraces().size()
                    + " at the end; " + wrong.size() + " wrong.";
            Files.writeString(de.droiddrone.bench.Sandbox.runRoot().resolve("soak").resolve("map-night.txt"),
                    summary + System.lineSeparator() + String.join(System.lineSeparator(), wrong) + System.lineSeparator() + System.lineSeparator()
                            + "What the map said in the app's log (the last 200 lines):" + System.lineSeparator()
                            + String.join(System.lineSeparator(), said.subList(Math.max(0, said.size() - 200), said.size())), StandardCharsets.UTF_8);
            System.out.println("MAP NIGHT: " + summary);
            assertTrue(wrong.isEmpty(), wrong.size() + " time(s) the map was not whole by itself: " + String.join(" | ", wrong.subList(0, Math.min(5, wrong.size()))));
            assertTrue(Thread.getAllStackTraces().size() <= threadsAfterWarmUp + 30, "threads do not pile up: " + threadsAfterWarmUp + " after the third cycle, "
                    + Thread.getAllStackTraces().size() + " at the end");
            // the cache keeps 600 tiles in memory: full after some fifty cycles - what climbs after that is not the tiles
            if (heaps.size() >= 120) {
                double perCycle = Night.slope(heaps.subList(60, heaps.size()));
                assertTrue(perCycle * 1024 < 150, String.format("memory does not climb with the cycles: %+.0f KB a cycle", perCycle * 1024));
            }
        } finally {
            if (cache != null) MapBenchAccess.end(cache);
            Outside.use(null);
            server.close();
        }
    }
}
