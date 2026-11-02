package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MapViewTest {
    private static final int ACCENT = 0xe31b23;

    private static FcTelemetry.Gps fix(double lat, double lon, long at) {
        return new FcTelemetry.Gps(2, 14, 40.0, 80, -1, 0, lat, lon, -1, 45, 0.8, at);
    }

    private static int count(BufferedImage img, int rgb) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) & 0xffffff) == rgb) n++;
        }
        return n;
    }

    @Test
    void paintsWithoutAnyData(@TempDir Path dir) {
        MapView map = new MapView(new TileCache(dir, "test", false));
        BufferedImage img = map.snapshot(400, 300);
        assertEquals(400, img.getWidth());
        assertEquals(TileSource.KARTVERKET_TOPO, map.effectiveSource(), "auto over Norway until a fix comes");
    }

    @Test
    void theFirstFixCentresTheMapAndTheTrackAndDroneAreDrawn(@TempDir Path dir) {
        MapView map = new MapView(new TileCache(dir, "test", false));
        FlightTrack track = new FlightTrack(null);
        long now = System.currentTimeMillis();
        track.sample(fix(59.9139, 10.7522, now - 2000), true);
        track.sample(fix(59.9149, 10.7542, now - 1000), true);
        track.sample(fix(59.9159, 10.7562, now), true);
        map.update(track, fix(59.9159, 10.7562, now), 45, false, true);
        BufferedImage img = map.snapshot(400, 300);
        assertTrue(count(img, ACCENT) > 60, "track line, drone arrow and FOLLOW lit: " + count(img, ACCENT));
        assertTrue(map.isFollowing());

        // the link goes: the drone turns grey (no red arrow) under a red LAST KNOWN badge, which is bigger
        map.update(track, null, -1, true, false);
        BufferedImage lost = map.snapshot(400, 300);
        assertTrue(count(lost, ACCENT) > count(img, ACCENT) + 200, "LAST KNOWN badge: " + count(lost, ACCENT) + " vs " + count(img, ACCENT));
    }

    @Test
    void fittingTheFlightZoomsOutUntilItAllShows(@TempDir Path dir) {
        MapView map = new MapView(new TileCache(dir, "test", false));
        FlightTrack track = new FlightTrack(null);
        long now = System.currentTimeMillis();
        track.sample(fix(59.9139, 10.7522, now - 1000), true);
        track.sample(fix(59.9339, 10.7522, now), true); // 2.2 km north
        map.update(track, null, -1, false, true);
        map.setSize(400, 300);
        map.fitTrack();
        assertFalse(map.isFollowing());
        BufferedImage img = map.snapshot(400, 300);
        assertTrue(count(img, ACCENT) > 40, "the track still shows");
    }

    @Test
    void satOrMapSwitchesAndReportsTheChoice(@TempDir Path dir) {
        MapView map = new MapView(new TileCache(dir, "test", false));
        TileSource[] chosen = new TileSource[1];
        map.setOnSourceChosen(s -> chosen[0] = s);
        map.setSource(TileSource.SATELLITE);
        assertEquals(TileSource.SATELLITE, map.effectiveSource());
        map.setSource(null);
        assertNull(map.source());
        assertEquals("12 S AGO", MapView.ageText(12_500));
        assertEquals("3 MIN AGO", MapView.ageText(190_000));
        assertEquals("1.25 KM", MapView.distanceText(1250));
        assertEquals("230 M", MapView.distanceText(230.4));
    }

    @Test
    void altDragDrawsABoxAndHandsOverItsArea(@TempDir Path dir) {
        MapView map = new MapView(new TileCache(dir, "test", false));
        map.setSize(400, 300);
        double[][] got = new double[1][];
        map.setOnAreaSelected(a -> got[0] = a);
        int alt = InputEvent.ALT_DOWN_MASK | InputEvent.BUTTON1_DOWN_MASK;
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_PRESSED, 0, alt, 100, 100, 1, false, MouseEvent.BUTTON1));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_DRAGGED, 0, alt, 220, 180, 1, false, MouseEvent.NOBUTTON));
        BufferedImage during = map.snapshot(400, 300);
        assertTrue(count(during, ACCENT) > 200, "the box's frame shows while dragging: " + count(during, ACCENT));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_RELEASED, 0, InputEvent.ALT_DOWN_MASK, 220, 180, 1, false, MouseEvent.BUTTON1));
        assertNotNull(got[0], "the area was handed over");
        double[] nw = map.toLatLon(100, 100), se = map.toLatLon(220, 180);
        assertEquals(se[0], got[0][0], 1e-9, "minLat");
        assertEquals(nw[0], got[0][1], 1e-9, "maxLat");
        assertEquals(nw[1], got[0][2], 1e-9, "minLon");
        assertEquals(se[1], got[0][3], 1e-9, "maxLon");
        assertTrue(count(map.snapshot(400, 300), ACCENT) < count(during, ACCENT), "the box is gone after");

        // the view as an area: the corners
        double[] view = map.visibleArea();
        assertEquals(map.toLatLon(0, 0)[0], view[1], 1e-9);
        assertEquals(map.toLatLon(400, 300)[1], view[3], 1e-9);

        // without Alt a drag pans the map and selects nothing
        got[0] = null;
        double before = map.toLatLon(200, 150)[0];
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK, 100, 100, 1, false, MouseEvent.BUTTON1));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_DRAGGED, 0, InputEvent.BUTTON1_DOWN_MASK, 100, 160, 1, false, MouseEvent.NOBUTTON));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_RELEASED, 0, 0, 100, 160, 1, false, MouseEvent.BUTTON1));
        assertNull(got[0]);
        assertNotEquals(before, map.toLatLon(200, 150)[0], "panned");
    }
}
