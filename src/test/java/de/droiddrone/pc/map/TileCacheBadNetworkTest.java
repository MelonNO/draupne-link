package de.droiddrone.pc.map;

import de.droiddrone.pc.Outside;
import de.droiddrone.testing.TroubledServer;
import de.droiddrone.testing.TroubledServer.Mode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The map on a bad internet (2026-11-19, the pilot: "the map is stuck loading even though the computer has
 * internet"). The tile cache against a server that does what networks do - takes a request and says nothing, sends
 * half a tile and stops, hangs up, says 503, is gone for a while - and then is fine again. Each time the map must
 * have its tiles a few seconds after the server is fine, <b>without anyone touching it</b>: a map repaints only when
 * something calls it, so a tile that failed and is never asked for again stays blank for good.
 * <p>
 * The map here ({@link Screen}) does what {@code MapView} does: it asks for every tile it shows when it paints, and
 * paints when the cache calls it.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class TileCacheBadNetworkTest {
    @TempDir
    Path dir;

    private static final TileSource MAP = TileSource.KARTVERKET_TOPO;
    private TroubledServer server;
    private final java.util.List<TileCache> caches = new java.util.ArrayList<>();
    private byte[] png;

    @BeforeEach
    void serverUp() throws Exception {
        BufferedImage tile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(tile, "png", out);
        png = out.toByteArray();
        server = new TroubledServer(png, "image/png");
        Outside.use(new Outside.Doors() { // the map's own addresses, asked of the server on this PC
            @Override
            public String url(String url) {
                return server.address() + URI.create(url).getPath();
            }
        });
    }

    @AfterEach
    void serverDown() throws Exception {
        for (TileCache c : caches) c.end(); // or its retries go on, to the next test's server
        Outside.use(null);
        server.close();
    }

    /** The tile cache as the app has it - its own loader threads, the network on - with the waits of a test. */
    private TileCache cache() {
        TileCache c = new TileCache(dir, "test", true, true, () -> false);
        c.headMs = 700;
        c.stallMs = 600;
        c.wholeMs = 1200;
        caches.add(c);
        return c;
    }

    /** A map showing six tiles: asks for each when it paints, paints when the cache calls it. */
    private static final class Screen implements Runnable {
        final TileCache cache;
        final Set<String> shown = ConcurrentHashMap.newKeySet();
        final AtomicInteger paints = new AtomicInteger();

        Screen(TileCache cache) {
            this.cache = cache;
        }

        void paint() {
            paints.incrementAndGet();
            for (int x = 100; x < 103; x++) {
                for (int y = 200; y < 202; y++) if (cache.get(MAP, 12, x, y, this) != null) shown.add(x + "/" + y);
            }
        }

        @Override
        public void run() {
            paint();
        }

        /** Waits for all six tiles; how long it took, or -1 when they did not come in time. */
        long whole(long withinMs) throws InterruptedException {
            long t0 = System.currentTimeMillis();
            while (shown.size() < 6) {
                if (System.currentTimeMillis() - t0 > withinMs) return -1;
                Thread.sleep(20);
            }
            return System.currentTimeMillis() - t0;
        }
    }

    private void assertWhole(Screen map, long withinMs, String after) throws InterruptedException {
        long took = map.whole(withinMs);
        assertTrue(took >= 0, "the map has its six tiles within " + withinMs / 1000 + " s " + after + ", untouched: it has " + map.shown.size()
                + " after " + map.paints.get() + " paints and " + server.requests() + " requests");
    }

    @Test
    void aGoodServerGivesTheMapItsTiles() throws Exception {
        Screen map = new Screen(cache());
        map.paint();
        assertWhole(map, 5000, "of a good server");
        assertEquals(6, server.requests(), "each tile asked for once");
        assertFalse(map.cache.isOffline());
    }

    /** The head of the answer comes, half the tile comes, then nothing: a transfer stalled on a weak link. */
    @Test
    void aTileThatStopsHalfWayDoesNotStopTheMapForGood() throws Exception {
        server.each(n -> n <= 4 ? Mode.HALF : Mode.OK);
        Screen map = new Screen(cache());
        map.paint();
        assertWhole(map, 20_000, "after four transfers stalled half way");
        assertFalse(map.cache.loading(), "and nothing is left \"loading\"");
    }

    /**
     * A slow internet that works - one bar, or the downlink shared with a drone's video: every tile comes, a piece
     * at a time, and takes longer than any limit for "the whole answer" used to allow (20 s in the app; here each
     * tile takes 1.5 s, the old limit's 1.2 s and more). Until 2026-11-20 each such tile was thrown away at the limit
     * and begun again from nothing - the map never came, for as long as the link stayed slow.
     */
    @Test
    void aTileThatComesSlowlyButKeepsComingIsTaken() throws Exception {
        server.pieces = 10;
        server.pieceEveryMs = 150;
        server.always(Mode.SLOW);
        TileCache cache = cache();
        cache.wholeMs = 20_000; // the end of patience is far off; standing still (600 ms) is what counts
        java.util.List<String> said = new java.util.concurrent.CopyOnWriteArrayList<>();
        cache.say = said::add;
        Screen map = new Screen(cache);
        map.paint();
        assertWhole(map, 15_000, "of a slow server that keeps sending");
        assertEquals(6, server.requests(), "each tile asked for once - none thrown away half-come and begun again");
        assertTrue(said.isEmpty(), "nothing said of a server not reached: " + said);
        // and the app's own patience (the waits above are a test's): a 40 KB tile at half a kilobyte a second takes
        // 80 s - it is waited for as long as it moves, and one that stands still is let go of within 20 s
        TileCache asTheAppHasIt = new TileCache(dir.resolve("app"), "test", false, true, () -> false);
        caches.add(asTheAppHasIt);
        assertTrue(asTheAppHasIt.wholeMs >= 80_000, "the whole answer may take " + asTheAppHasIt.wholeMs + " ms");
        assertTrue(asTheAppHasIt.stallMs >= 5000 && asTheAppHasIt.stallMs <= 20_000, "standing still for " + asTheAppHasIt.stallMs + " ms ends it");
        assertFalse(cache.isOffline());
        assertFalse(cache.inTrouble());
    }

    /** An answer that trickles without end - a piece now and then, never whole - does not hold a loader for good. */
    @Test
    void anAnswerThatTricklesWithoutEndIsGivenUpAtLast() throws Exception {
        server.pieces = 100;
        server.pieceEveryMs = 100; // 10 s for one tile: it never stands still for 600 ms, and is never whole in 1.2 s
        server.each(n -> n <= 2 ? Mode.SLOW : Mode.OK);
        TileCache cache = cache();
        java.util.List<String> said = new java.util.concurrent.CopyOnWriteArrayList<>();
        cache.say = said::add;
        Screen map = new Screen(cache);
        map.paint();
        assertWhole(map, 8000, "after two answers that trickled without end");
        assertTrue(said.stream().anyMatch(s -> s.contains("no whole answer in")), "the log says why: " + said);
        assertFalse(cache.loading(), "and nothing is left \"loading\"");
    }

    /** Half a tile and then nothing is given up for standing still - not waited out to the end of patience. */
    @Test
    void aTileThatStandsStillIsGivenUpLongBeforeTheEndOfPatience() throws Exception {
        server.each(n -> n <= 2 ? Mode.HALF : Mode.OK);
        TileCache cache = cache();
        cache.wholeMs = 60_000;
        java.util.List<String> said = new java.util.concurrent.CopyOnWriteArrayList<>();
        cache.say = said::add;
        Screen map = new Screen(cache);
        map.paint();
        assertWhole(map, 8000, "after two transfers that stood still half way");
        assertTrue(said.stream().anyMatch(s -> s.contains("its answer stood still")), "the log says why: " + said);
    }

    /** The request is taken and never answered. */
    @Test
    void aServerThatSaysNothingDoesNotStopTheMapForGood() throws Exception {
        server.each(n -> n <= 4 ? Mode.SILENT : Mode.OK);
        Screen map = new Screen(cache());
        map.paint();
        assertWhole(map, 20_000, "after four requests never answered");
    }

    /** No server for three seconds - the PC's network not up yet as the app starts, a cable out - then it is there. */
    @Test
    void afterAnOutageTheMapComesBackByItself() throws Exception {
        server.down();
        Screen map = new Screen(cache());
        map.paint();
        Thread.sleep(3000);
        assertEquals(0, map.shown.size());
        assertTrue(map.cache.isOffline(), "the map knows it reached nothing");
        server.up();
        assertWhole(map, 15_000, "after the server came back");
        assertFalse(map.cache.isOffline(), "and no longer says it shows saved tiles only");
    }

    @Test
    void aServerThatHangsUpOrIsBusyIsAskedAgain() throws Exception {
        server.each(n -> n <= 3 ? Mode.HANG_UP : n <= 6 ? Mode.BUSY : Mode.OK);
        Screen map = new Screen(cache());
        map.paint();
        assertWhole(map, 20_000, "after three hang-ups and three times 503");
    }

    /** Tiles the server does not have (outside its map) are not asked for over and over. */
    @Test
    void aTileTheServerDoesNotHaveIsNotAskedForAgainAndAgain() throws Exception {
        server.always(Mode.NOT_THERE);
        Screen map = new Screen(cache());
        map.paint();
        Thread.sleep(6000);
        for (int i = 0; i < 5; i++) map.paint(); // the pilot moves the mouse over the map
        Thread.sleep(500);
        assertTrue(server.requests() <= 6, "six tiles, each asked for once in six seconds: " + server.requests() + " requests " + server.paths());
        assertFalse(map.cache.loading());
    }

    /** A tile file a crash, a full disk or another program left broken is fetched anew, not shown blank for ever. */
    @Test
    void aBrokenTileFileIsFetchedAgain() throws Exception {
        Path column = dir.resolve(MAP.id).resolve("12").resolve("100");
        Files.createDirectories(column);
        Files.write(column.resolve("200.png"), new byte[0]);
        Files.write(column.resolve("201.png"), java.util.Arrays.copyOf(png, png.length / 3)); // cut short
        Path next = dir.resolve(MAP.id).resolve("12").resolve("101");
        Files.createDirectories(next);
        Files.writeString(next.resolve("200.png"), "<html>Sign in to the hotel's Wi-Fi</html>", StandardCharsets.UTF_8);
        Screen map = new Screen(cache());
        map.paint();
        assertWhole(map, 10_000, "with three broken files on disk");
        assertArrayEquals(png, Files.readAllBytes(column.resolve("200.png")), "and the files are whole again");
        assertArrayEquals(png, Files.readAllBytes(column.resolve("201.png")));
        assertArrayEquals(png, Files.readAllBytes(next.resolve("200.png")));
    }

    /** What a server sends that is no picture - a sign-in page, an error page with 200 - is not kept as a tile. */
    @Test
    void anAnswerThatIsNoPictureIsNotKeptAsATile() throws Exception {
        server.close();
        server = new TroubledServer("<html>Sign in to continue</html>".getBytes(StandardCharsets.UTF_8), "text/html");
        Screen map = new Screen(cache());
        map.paint();
        Thread.sleep(2500);
        assertEquals(0, map.shown.size());
        try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
            assertEquals(0, files.filter(Files::isRegularFile).count(), "nothing on disk that would be shown, or skipped, as a tile later");
        }
    }

    /** A map whose own code fails when called does not take the loader threads with it. */
    @Test
    void aMapThatFailsWhenCalledDoesNotStopTheOthers() throws Exception {
        TileCache cache = cache();
        Runnable broken = () -> {
            throw new IllegalStateException("a map's own bug");
        };
        for (int x = 100; x < 103; x++) for (int y = 200; y < 202; y++) cache.get(MAP, 12, x, y, broken);
        Screen map = new Screen(cache);
        map.paint();
        assertWhole(map, 10_000, "beside a map that throws each time it is called");
    }

    /** A server that stays bad is asked again, but ever less often: not as fast as the tiles can fail. */
    @Test
    void aServerThatStaysBadIsNotHammered() throws Exception {
        server.always(Mode.BUSY);
        Screen map = new Screen(cache());
        map.paint();
        Thread.sleep(9000);
        // each tile at 0 s, 2 s later, 4 s after that: three times in nine seconds, the fourth not before 14 s - and
        // one tile more after eight seconds (the probe)
        assertTrue(server.requests() >= 12 && server.requests() <= 19, "six tiles asked two or three times each in nine seconds, one once more: " + server.requests());
        assertEquals(2000, TileCache.retryAfter(1));
        assertEquals(4000, TileCache.retryAfter(2));
        assertEquals(32_000, TileCache.retryAfter(5));
        assertEquals(60_000, TileCache.retryAfter(6));
        assertEquals(60_000, TileCache.retryAfter(500), "a minute at most, however long it lasts");
    }

    /**
     * The bench's night (MapNightScenario) measured it: after a minute of 503s the map had its tiles 59 s after the
     * server was fine again - each tile's own wait had doubled to 32 s and more, and nothing asked before that. Now
     * one tile is asked for every few seconds while the server is missed, and its answer forgives them all.
     */
    @Test
    void afterALongOutageTheMapIsBackWithinSecondsNotAfterTheTilesOwnLongWaits() throws Exception {
        server.always(Mode.BUSY);
        TileCache cache = cache();
        cache.probeMs = 2000;
        Screen map = new Screen(cache);
        map.paint();
        Thread.sleep(31_000); // each tile asked at 0, 2, 6, 14 and 30 s: its next try is 32 s away
        int asked = server.requests();
        assertTrue(asked >= 30 && asked <= 50, "half a minute of 503s: six tiles five times each, and one tile every two seconds - not all of them: " + asked);
        server.always(Mode.OK);
        assertWhole(map, 6000, "of a fine server after half a minute of 503s");
        assertFalse(cache.isOffline());
    }

    /**
     * A review's catch (2026-11-19): every answer of any server - a 503 too - threw all the tiles' waits away when a
     * fetch before it had reached none. A server that hung up on one request and said 503 to the next was asked
     * again 30 ms later, round after round, without a pause.
     */
    @Test
    void aServerThatHangsUpAndSays503InTurnsIsNotHammered() throws Exception {
        server.each(n -> n % 2 == 0 ? Mode.HANG_UP : Mode.BUSY);
        Screen map = new Screen(cache());
        map.paint();
        Thread.sleep(9000);
        // as with 503s alone: each tile at 0 s, 2 s later, 4 s after that, and one tile more after eight seconds - 19
        // tries; Java's own HTTP client asks once more by itself when a connection is hung up on, so up to twice that
        // reaches the server (29 measured). Without a pause it was hundreds
        assertTrue(server.requests() >= 12 && server.requests() <= 40, "six tiles two or three times each in nine seconds, whatever the server's two ways of saying no: "
                + server.requests());
    }

    /**
     * And what took its place: a tile that comes from the server calls the waiting ones in - also when it is one of
     * the map's own (the tiles' waits out of step after the pilot moved the map), not only the cache's own try.
     */
    @Test
    void oneTileThatComesBringsTheOthersInThoughTheirOwnWaitsAreLonger() throws Exception {
        server.always(Mode.BUSY);
        TileCache cache = cache();
        cache.probeMs = 60_000; // not the probe's doing here
        Screen map = new Screen(cache);
        map.paint(); // its six tiles asked at 0, 2, 6 s - the next time at 14 s
        Thread.sleep(6500);
        Set<String> late = ConcurrentHashMap.newKeySet();
        Runnable[] moved = new Runnable[1];
        moved[0] = () -> { // the map moved: three tiles more, their tries at 6.5, 8.5 and 12.5 s
            for (int x = 300; x < 303; x++) if (cache.get(MAP, 12, x, 200, moved[0]) != null) late.add("" + x);
        };
        moved[0].run();
        Thread.sleep(3000);
        server.always(Mode.OK); // fine from 9.5 s on: a late tile comes at 12.5 s, the first six would wait until 14 s
        long t0 = System.currentTimeMillis();
        while (late.size() < 3 && System.currentTimeMillis() - t0 < 6000) Thread.sleep(20);
        assertEquals(3, late.size(), "the moved map has its tiles at their own next try");
        long took = map.whole(1000);
        assertTrue(took >= 0, "and the first six came with them, not at their own try a second and a half later: " + map.shown.size() + " of 6");
        assertFalse(cache.inTrouble(), "nothing waits any more");
    }

    /** What the map's "NO MAP SERVER" goes by when the server answers but gives no tiles: a tile that missed twice. */
    @Test
    void aServerThatAnswersButGivesNoTilesIsTroubleToo() throws Exception {
        server.always(Mode.BUSY);
        TileCache cache = cache();
        Screen map = new Screen(cache);
        map.paint();
        Thread.sleep(1000);
        assertFalse(cache.inTrouble(), "one miss is a hiccup");
        assertFalse(cache.isOffline(), "and a 503 is an answer: the server was reached");
        Thread.sleep(2500);
        assertTrue(cache.inTrouble(), "the same tiles refused twice: the map has no tiles to show and says so");
        server.always(Mode.OK);
        assertWhole(map, 15_000, "of a fine server");
        assertFalse(cache.inTrouble());
    }

    /**
     * Two maps (the small one and the map window) waiting through an outage: one wake timer serves them. Each sooner
     * wake used to leave the timer before it pending, and that one set another when it rang - their number only grew.
     */
    @Test
    void twoMapsWaitingDoNotMultiplyTheTimers() throws Exception {
        server.always(Mode.BUSY);
        TileCache cache = cache();
        cache.probeMs = 60_000;
        Screen one = new Screen(cache);
        Runnable[] two = new Runnable[1];
        two[0] = () -> {
            for (int x = 400; x < 404; x++) cache.get(MAP, 12, x, 200, two[0]);
        };
        one.paint();
        Thread.sleep(700);
        two[0].run();
        Thread.sleep(12_000);
        // each map is called when its tiles may be asked again: at 2, 6 and 14 s after its first try - six calls due in
        // these 12.7 s, a timer each and a few for their re-asking; hundreds before
        assertTrue(cache.wakeRuns <= 24, "the timer rang " + cache.wakeRuns + " times for two maps in 12 s");
    }

    /** Nobody looks at the map any more (its window is closed): the server in trouble is left alone. */
    @Test
    void aServerInTroubleIsNotAskedForAMapNobodyShows() throws Exception {
        server.always(Mode.BUSY);
        TileCache cache = cache();
        cache.probeMs = 500;
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable[] window = new Runnable[1];
        window[0] = () -> {
            if (closed.get()) return;
            for (int x = 100; x < 103; x++) cache.get(MAP, 12, x, 200, window[0]);
        };
        window[0].run();
        Thread.sleep(3000);
        assertTrue(server.requests() >= 6, "while it is shown it is asked for: " + server.requests());
        closed.set(true);
        Thread.sleep(6000); // its last call comes (and asks nothing), the probe looks three more times
        int after = server.requests();
        Thread.sleep(5000);
        assertEquals(after, server.requests(), "nothing more is asked of the server once no map waits");
    }

    /** What the app's log gets: one line when the map's server stops answering, one when it answers again. */
    @Test
    void theLogSaysWhenTheServerIsLostAndWhenItIsBack() throws Exception {
        server.down();
        TileCache cache = cache();
        java.util.List<String> said = new java.util.concurrent.CopyOnWriteArrayList<>();
        cache.say = said::add;
        Screen map = new Screen(cache);
        map.paint();
        Thread.sleep(2500);
        assertEquals(1, said.size(), "one line for six tiles that reached nothing: " + said);
        assertTrue(said.get(0).startsWith("Map: its server was not reached (") && said.get(0).contains("Connect"), said.toString());
        assertTrue(cache.renewals >= 1, "after " + TileCache.RENEW_AFTER + " misses in a row the connections are made anew: " + cache.renewals);
        server.up();
        assertWhole(map, 15_000, "after the server came back");
        assertEquals(2, said.size(), said.toString());
        assertTrue(said.get(1).startsWith("Map: its server answers again, "), said.toString());
    }

    /** "SAVED TILES ONLY" does not stay for the rest of the session after one miss with nothing asked for since. */
    @Test
    void theMapDoesNotSayOfflineForEverAfterOneMiss() throws Exception {
        server.down();
        TileCache cache = cache();
        long[] later = {0};
        cache.clock = () -> System.currentTimeMillis() + later[0];
        assertNull(cache.fetchNow(MAP, 12, 100, 200));
        assertTrue(cache.isOffline());
        later[0] = TileCache.OFFLINE_KEEP_MS - 5000;
        assertTrue(cache.isOffline(), "still said a while after the miss");
        later[0] = TileCache.OFFLINE_KEEP_MS + 1000;
        assertFalse(cache.isOffline(), "not for ever: nothing was asked for since, nothing is known");
    }

    /**
     * DOWNLOAD AREA on a bad internet: it gets through a patch of hang-ups and 503s (what failed is fetched by a
     * second pass, what is on disk is not fetched twice), and when nothing answers at all it gives up after a few
     * tiles instead of waiting out each of thousands.
     */
    @Test
    void anAreaDownloadGetsThroughABadPatchAndGivesUpWhenNothingAnswers() throws Exception {
        server.each(n -> n % 7 == 3 ? Mode.HANG_UP : n % 11 == 5 ? Mode.BUSY : Mode.OK);
        TileCache cache = cache();
        TileCache.Download first = cache.download(MAP, 12, 13, 61.10, 61.16, 10.40, 10.52);
        long end = System.currentTimeMillis() + 40_000;
        while (!first.isFinished() && System.currentTimeMillis() < end) Thread.sleep(50);
        assertTrue(first.isFinished(), "the download ends");
        assertTrue(first.total >= 6, "an area of some tiles: " + first.total);
        assertEquals(first.total, first.done(), "every tile was looked at");
        assertFalse(first.gaveUp(), "a server that answers most of the time is not given up");
        assertTrue(first.failed() >= 1 && first.fetched() >= first.total / 2, first.fetched() + " fetched, " + first.failed() + " failed of " + first.total);

        server.always(Mode.OK);
        TileCache.Download second = cache.download(MAP, 12, 13, 61.10, 61.16, 10.40, 10.52);
        end = System.currentTimeMillis() + 40_000;
        while (!second.isFinished() && System.currentTimeMillis() < end) Thread.sleep(50);
        assertTrue(second.isFinished());
        assertEquals(first.failed(), second.fetched(), "the second pass fetches what failed in the first");
        assertEquals(first.fetched(), second.onDisk(), "and nothing twice");
        assertEquals(0, second.failed());

        server.down();
        TileCache.Download none = cache.download(MAP, 12, 15, 61.30, 61.50, 10.40, 10.80); // hundreds of tiles
        end = System.currentTimeMillis() + 30_000;
        while (!none.isFinished() && System.currentTimeMillis() < end) Thread.sleep(50);
        assertTrue(none.isFinished(), "with no server the download ends by itself");
        assertTrue(none.gaveUp(), "and says it gave up");
        assertTrue(none.total > 200 && none.done() < 60, "after a few tiles, not after all " + none.total + ": " + none.done());
    }

    /** The radio horizon's and the terrain's fetch on the caller's thread: back within the time limit, never held. */
    @Test
    void aFetchOnTheCallersThreadComesBackWhenATransferStalls() {
        server.always(Mode.HALF);
        TileCache cache = cache();
        long t0 = System.currentTimeMillis();
        assertNull(cache.fetchNow(MAP, 12, 100, 200));
        long took = System.currentTimeMillis() - t0;
        assertTrue(took < 5000, "back after " + took + " ms");
    }
}
