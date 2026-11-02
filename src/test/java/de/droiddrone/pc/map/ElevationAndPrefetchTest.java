package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ElevationAndPrefetchTest {
    @Test
    void kartverketInNorwayOpenMeteoElsewhere() {
        assertTrue(Elevation.url(59.9139, 10.7522).startsWith("https://ws.geonorge.no/hoydedata/v1/punkt?nord=59.913900&ost=10.752200"));
        assertTrue(Elevation.url(52.52, 13.41).startsWith("https://api.open-meteo.com/v1/elevation?latitude=52.520000&longitude=13.410000"));
        assertTrue(Elevation.url(59.33, 18.07).startsWith("https://api.open-meteo.com"), "Stockholm: not Kartverket");
    }

    @Test
    void parsesBothAnswers() {
        assertEquals(4.9, Elevation.parse("{\"koordsys\":4258,\"punkter\":[{\"datakilde\":\"dtm1\",\"terreng\":\"BymessigBebyggelse\",\"x\":10.7522,\"y\":59.9139,\"z\":4.9}]}"), 1e-9);
        assertEquals(38.0, Elevation.parse("{\"elevation\":[38.0]}"), 1e-9);
        assertEquals(-2.5, Elevation.parse("{\"elevation\":[-2.5]}"), 1e-9);
        assertNull(Elevation.parse("{\"error\":true}"));
        assertNull(Elevation.parse(null));
    }

    @Test
    void placesTenMetresApartShareAnAnswer() {
        Elevation e = new Elevation("test", false);
        assertEquals(Elevation.key(59.91390, 10.75220), Elevation.key(59.91394, 10.75223));
        assertNotEquals(Elevation.key(59.9139, 10.7522), Elevation.key(59.9149, 10.7522));
        Double[] got = {0.0};
        e.lookup(59.9139, 10.7522, z -> got[0] = z);
        // no network: the answer (null) comes on the lookup thread; nothing to assert but no exception
    }

    @Test
    void thePrefetchPlanTakesTheNearestLevelsFirstWithinTheBudget() {
        // a view about 1.1 km across at zoom 15 around Oslo
        List<TileCache.Tile> plan = TileCache.plan(15, 12, 18, 59.910, 59.918, 10.745, 10.760, 100000);
        assertEquals(15, plan.get(0).z(), "the level looked at first");
        int[] perZoom = new int[19];
        for (TileCache.Tile t : plan) perZoom[t.z()]++;
        assertTrue(perZoom[15] >= 2 && perZoom[15] <= 6, "z15: " + perZoom[15]);
        assertTrue(perZoom[16] >= 2 * perZoom[15] && perZoom[16] <= 6 * perZoom[15], "about four times as many a level down: " + perZoom[16] + " vs " + perZoom[15]);
        assertEquals(1, perZoom[12], "far out: one tile");
        assertTrue(perZoom[18] > 100, "the deepest level has the most");
        int firstDeep = -1, firstFar = -1;
        for (int i = 0; i < plan.size(); i++) {
            if (firstDeep < 0 && plan.get(i).z() == 16) firstDeep = i;
            if (firstFar < 0 && plan.get(i).z() == 14) firstFar = i;
        }
        assertTrue(firstDeep < firstFar, "one level in before one level out");

        List<TileCache.Tile> capped = TileCache.plan(15, 12, 18, 59.910, 59.918, 10.745, 10.760, 50);
        assertEquals(50, capped.size(), "the budget caps it");
    }

    @Test
    void openStreetMapIsNotPrefetchedAtRestButAnAskedForAreaIs(@TempDir Path dir) throws Exception {
        assertFalse(TileSource.OSM.prefetchAllowed);
        assertTrue(TileSource.KARTVERKET_TOPO.prefetchAllowed);
        TileCache cache = new TileCache(dir, "test", false);
        TileCache.Download d = cache.download(TileSource.OSM, 3, 3, 59.910, 59.918, 10.745, 10.760);
        assertEquals(1, d.total, "one tile at zoom 3");
        assertTrue(await(d), "finished");
    }

    @Test
    void anAreaDownloadCountsItsTilesSkipsSavedOnesAndStopsWhenNoServerAnswers(@TempDir Path dir) throws Exception {
        double minLat = 59.910, maxLat = 59.918, minLon = 10.745, maxLon = 10.760;
        assertEquals(TileCache.plan(12, 12, 18, minLat, maxLat, minLon, maxLon, 1_000_000).size(), TileCache.count(12, 18, minLat, maxLat, minLon, maxLon),
                "counted as planned");
        assertEquals(1, TileCache.count(12, 12, minLat, maxLat, minLon, maxLon));
        assertTrue(TileCache.count(2, 19, -85, 85, -180, 180) > TileCache.MAX_DOWNLOAD, "the world is too much");

        // one tile of the area already on disk
        int x = (int) (Mercator.x(minLon, 12) / Mercator.TILE), y = (int) (Mercator.y(maxLat, 12) / Mercator.TILE);
        Path file = dir.resolve("kartverket").resolve("12").resolve(Integer.toString(x)).resolve(y + ".png");
        Files.createDirectories(file.getParent());
        ImageIO.write(new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB), "png", file.toFile());

        TileCache cache = new TileCache(dir, "test", false); // no network: every fetch fails to reach a server
        TileCache.Download d = cache.download(TileSource.KARTVERKET_TOPO, 12, 18, minLat, maxLat, minLon, maxLon);
        assertEquals(TileCache.count(12, 18, minLat, maxLat, minLon, maxLon), d.total);
        assertTrue(await(d), "finished");
        assertEquals(1, d.onDisk(), "the saved tile was skipped, not fetched");
        assertEquals(0, d.fetched());
        assertTrue(d.failed() >= 20, "tried before giving up: " + d.failed());
        assertTrue(d.gaveUp(), "no server: stops instead of failing every tile");
        assertTrue(d.done() < d.total);

        TileCache.Download c = cache.download(TileSource.KARTVERKET_TOPO, 12, 18, minLat, maxLat, minLon, maxLon);
        c.cancel();
        assertTrue(await(c));
        assertTrue(c.isCancelled());
    }

    private static boolean await(TileCache.Download d) throws InterruptedException {
        for (int i = 0; i < 500 && !d.isFinished(); i++) Thread.sleep(10);
        return d.isFinished();
    }
}
