package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The map's tile queue (1.0.49): newest first, bounded, what no map shows any more dropped (M13); nothing fetched
 * while a drone on this PC may be flying, the disk still serving (H7).
 */
class TileQueueTest {
    @TempDir
    Path dir;

    private final long[] now = {1_000_000};
    private final boolean[] held = {false};

    private TileCache cache() {
        TileCache c = new TileCache(dir, "test", false, false, () -> held[0]);
        c.clock = () -> now[0];
        return c;
    }

    private static String key(int x, int y) {
        return TileSource.KARTVERKET_TOPO.id + "/10/" + x + "/" + y;
    }

    @Test
    void theNewestAskedLoadsFirstAndAskingAgainMakesItNewest() {
        TileCache c = cache();
        Runnable map = () -> { };
        for (int x = 0; x < 3; x++) assertNull(c.get(TileSource.KARTVERKET_TOPO, 10, x, 5, map));
        assertEquals(List.of(key(2, 5), key(1, 5), key(0, 5)), c.queuedKeys(), "what is on the screen now first");
        c.get(TileSource.KARTVERKET_TOPO, 10, 0, 5, map);
        assertEquals(key(0, 5), c.queuedKeys().get(0), "asked again: the newest again, not twice");
        assertEquals(3, c.queuedKeys().size());
        assertEquals(key(0, 5), c.takeNext().key);
    }

    @Test
    void theQueueIsBoundedTheOldestGo() {
        TileCache c = cache();
        Runnable map = () -> { };
        for (int x = 0; x < TileCache.QUEUE_MAX + 40; x++) c.get(TileSource.KARTVERKET_TOPO, 10, x, 5, map);
        List<String> q = c.queuedKeys();
        assertEquals(TileCache.QUEUE_MAX, q.size());
        assertEquals(key(TileCache.QUEUE_MAX + 39, 5), q.get(0));
        assertFalse(q.contains(key(0, 5)), "the first asked dropped");
    }

    @Test
    void aTileTheMapNoLongerShowsIsDropped() {
        TileCache c = cache();
        Runnable map = () -> { }, otherMap = () -> { };
        c.get(TileSource.KARTVERKET_TOPO, 10, 1, 1, map); // shown here once, then panned away
        c.get(TileSource.KARTVERKET_TOPO, 10, 2, 2, otherMap); // another map that doesn't paint again
        now[0] += TileCache.STALE_MS + 500;
        c.get(TileSource.KARTVERKET_TOPO, 10, 7, 7, map); // the map paints its new place
        TileCache.Request first = c.takeNext();
        assertEquals(key(7, 7), first.key);
        TileCache.Request second = c.takeNext();
        assertEquals(key(2, 2), second.key, "the other map hasn't painted since: still its tile");
        assertNull(c.takeNext(), "the tile panned away from is dropped, not fetched");
    }

    @Test
    void whilePossiblyFlyingNothingIsFetchedButTheDiskServes() throws Exception {
        TileCache c = cache();
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        Path onDisk = dir.resolve(TileSource.KARTVERKET_TOPO.id).resolve("10").resolve("3").resolve("4.png");
        Files.createDirectories(onDisk.getParent());
        ImageIO.write(img, "png", onDisk.toFile());
        held[0] = true;
        AtomicInteger ready = new AtomicInteger();
        Runnable map = ready::incrementAndGet;
        c.get(TileSource.KARTVERKET_TOPO, 10, 3, 4, map);
        c.get(TileSource.KARTVERKET_TOPO, 10, 9, 9, map);
        for (TileCache.Request r; (r = c.takeNext()) != null; ) c.load(r);
        assertEquals(1, ready.get(), "the tile on disk came");
        assertNotNull(c.get(TileSource.KARTVERKET_TOPO, 10, 3, 4, map));
        assertFalse(c.isOffline(), "held is not offline: no server was even asked");
        assertNull(c.get(TileSource.KARTVERKET_TOPO, 10, 9, 9, map), "the other waits");
        assertTrue(c.queuedKeys().isEmpty(), "not asked again at once");
        now[0] += TileCache.HELD_RETRY_MS + 1;
        held[0] = false;
        c.get(TileSource.KARTVERKET_TOPO, 10, 9, 9, map);
        assertEquals(List.of(key(9, 9)), c.queuedKeys(), "landed: asked again soon, not after the minute a failure waits");
        held[0] = true;
        assertNull(c.fetchNow(TileSource.KARTVERKET_TOPO, 10, 9, 9));
        assertFalse(c.isOffline(), "fetchNow (the terrain) asked no server either");
    }

    @Test
    void anAreaDownloadWaitsWhilePossiblyFlying() throws Exception {
        TileCache c = cache();
        held[0] = true;
        TileCache.Download d = c.download(TileSource.KARTVERKET_TOPO, 3, 3, 59.910, 59.918, 10.745, 10.760);
        Thread.sleep(400);
        assertEquals(0, d.done(), "nothing fetched, nothing counted");
        assertTrue(d.isHeldForFlight());
        held[0] = false;
        for (int i = 0; i < 100 && !d.isFinished(); i++) Thread.sleep(50);
        assertTrue(d.isFinished(), "on by itself once landed");
        assertFalse(d.isHeldForFlight());
    }
}
