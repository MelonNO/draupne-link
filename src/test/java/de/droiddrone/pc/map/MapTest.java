package de.droiddrone.pc.map;

import de.droiddrone.pc.FcTelemetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MapTest {
    private static final double OSLO_LAT = 59.9139, OSLO_LON = 10.7522;

    // ------------------------------------------------------------------ projection

    @Test
    void osloIsOnTheKnownTileAtZoom10() {
        assertEquals(542, (int) (Mercator.x(OSLO_LON, 10) / Mercator.TILE));
        assertEquals(297, (int) (Mercator.y(OSLO_LAT, 10) / Mercator.TILE));
    }

    @Test
    void projectionRoundTripsAtAFractionalZoom() {
        double z = 13.37;
        assertEquals(OSLO_LAT, Mercator.lat(Mercator.y(OSLO_LAT, z), z), 1e-9);
        assertEquals(OSLO_LON, Mercator.lon(Mercator.x(OSLO_LON, z), z), 1e-9);
    }

    @Test
    void distanceAndBearing() {
        assertEquals(305_000, Mercator.distanceM(OSLO_LAT, OSLO_LON, 60.3913, 5.3221), 3000, "Oslo-Bergen");
        assertEquals(0, Mercator.bearingDeg(60, 10, 61, 10));
        assertEquals(90, Mercator.bearingDeg(0, 10, 0, 11));
        assertEquals(270, Mercator.bearingDeg(0, 10, 0, 9));
        assertEquals(Mercator.metersPerPixel(0, 0) / 2, Mercator.metersPerPixel(0, 1), 1e-6);
    }

    // ------------------------------------------------------------------ sources

    @Test
    void kartverketUrlsHaveRowBeforeColumnAndOsmTheOtherWayRound() {
        assertEquals("https://cache.kartverket.no/v1/wmts/1.0.0/topo/default/webmercator/10/297/542.png",
                TileSource.KARTVERKET_TOPO.url(10, 542, 297));
        assertEquals("https://tile.openstreetmap.org/10/542/297.png", TileSource.OSM.url(10, 542, 297));
    }

    @Test
    void autoPicksKartverketInNorwayAndOpenStreetMapElsewhere() {
        assertEquals(TileSource.KARTVERKET_TOPO, TileSource.forPosition(OSLO_LAT, OSLO_LON));
        assertEquals(TileSource.KARTVERKET_TOPO, TileSource.forPosition(78.22, 15.63), "Longyearbyen");
        assertEquals(TileSource.OSM, TileSource.forPosition(52.52, 13.40), "Berlin");
    }

    @Test
    void settingsValuesParse() {
        assertNull(TileSource.parse("auto"));
        assertNull(TileSource.parse(null));
        assertEquals(TileSource.SATELLITE, TileSource.parse("Satellite"));
        assertEquals(TileSource.KARTVERKET_RASTER, TileSource.parse("kartverket-raster"));
    }

    // ------------------------------------------------------------------ the cache

    @Test
    void aTileOnDiskIsServedWithoutTheNetwork(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("osm").resolve("3").resolve("4").resolve("2.png");
        Files.createDirectories(file.getParent());
        BufferedImage tile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        tile.setRGB(0, 0, 0x123456);
        ImageIO.write(tile, "png", file.toFile());
        TileCache cache = new TileCache(dir, "test", false);
        CountDownLatch ready = new CountDownLatch(1);
        assertNull(cache.get(TileSource.OSM, 3, 4, 2, ready::countDown), "not in memory yet");
        assertTrue(ready.await(5, TimeUnit.SECONDS), "loaded from disk");
        BufferedImage got = cache.get(TileSource.OSM, 3, 4, 2, null);
        assertNotNull(got);
        assertEquals(0x123456, got.getRGB(0, 0) & 0xffffff);
        assertFalse(cache.isOffline(), "nothing was fetched");
    }

    @Test
    void aMissingTileWithoutNetworkIsNullAndTheCacheSaysOffline(@TempDir Path dir) throws Exception {
        TileCache cache = new TileCache(dir, "test", false);
        CountDownLatch tried = new CountDownLatch(1);
        assertNull(cache.get(TileSource.OSM, 3, 4, 2, tried::countDown));
        Thread.sleep(300);
        assertNull(cache.get(TileSource.OSM, 3, 4, 2, null));
        assertTrue(cache.isOffline());
        assertEquals(1, tried.getCount(), "onReady only when a tile came");
        assertNull(cache.get(TileSource.OSM, 3, 0, 9, null), "below the world");
    }

    // ------------------------------------------------------------------ the track

    private static FcTelemetry.Gps fix(double lat, double lon, long at, int fixType) {
        return new FcTelemetry.Gps(fixType, 12, 36.0, 120, -1, 0, lat, lon, -1, 90, 0.9, at);
    }

    @Test
    void theTrackKeepsEveryNewFixAndTheFileHoldsThemAtOnce(@TempDir Path dir) throws Exception {
        FlightTrack track = new FlightTrack(dir);
        track.sample(fix(OSLO_LAT, OSLO_LON, 1000, 1), false);          // 2D, not armed: a point, not home
        track.sample(fix(OSLO_LAT, OSLO_LON, 1000, 1), true);           // the same report again: ignored
        track.sample(fix(OSLO_LAT + 0.001, OSLO_LON, 1500, 2), true);   // 3D and armed: home
        track.sample(fix(OSLO_LAT + 0.002, OSLO_LON, 2000, 2), true);
        track.sample(fix(0, 0, 2500, 0), true);                         // no fix: nothing
        List<FlightTrack.Point> pts = track.points();
        assertEquals(3, pts.size());
        assertEquals(OSLO_LAT + 0.001, track.home().lat(), 1e-9, "home: the first 3D fix while armed");
        assertEquals(2000, track.last().timeMs());

        String gpx = Files.readString(track.file());
        assertEquals(3, gpx.split("<trkpt ").length - 1, "every point on disk before close()");
        assertTrue(gpx.contains("lat=\"59.9149000\""));
        String last = Files.readString(dir.resolve("last-position.txt"));
        assertTrue(last.contains("59.915900, 10.752200"), last); // at the first point, then at most once a second: t=1000 and t=2000
        track.close();
        assertTrue(Files.readString(track.file()).trim().endsWith("</gpx>"));
        track.sample(fix(OSLO_LAT, OSLO_LON, 9000, 2), true);
        assertEquals(3, track.points().size(), "closed: no more points");
    }

    @Test
    void aTrackWithoutAFolderStaysInMemory() {
        FlightTrack track = new FlightTrack(null);
        track.sample(fix(OSLO_LAT, OSLO_LON, 1000, 2), true);
        assertEquals(1, track.points().size());
        assertNull(track.file());
    }
}
