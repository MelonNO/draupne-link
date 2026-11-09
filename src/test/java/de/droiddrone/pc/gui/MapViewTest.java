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
    void aPhonePointDrawsItsAccuracyAndAnEarlierTrackIsMuted(@TempDir Path dir) {
        MapView map = new MapView(new TileCache(dir, "test", false));
        FlightTrack track = new FlightTrack(null);
        long now = System.currentTimeMillis();
        track.sample(null, new FcTelemetry.PhonePosition(59.9139, 10.7522, 50, 40, 0, 0, 0, now), true, true);
        map.update(track, null, -1, false, true);
        BufferedImage with = map.snapshot(400, 300);
        FlightTrack bare = new FlightTrack(null);
        bare.sample(null, new FcTelemetry.PhonePosition(59.9139, 10.7522, 50, 65535, 0, 0, 0, now), true, true);
        map.update(bare, null, -1, false, true);
        BufferedImage without = map.snapshot(400, 300);
        int whiteWith = 0, whiteWithout = 0;
        for (int y = 0; y < 300; y++) {
            for (int x = 0; x < 400; x++) {
                if ((with.getRGB(x, y) & 0xffffff) == 0xffffff) whiteWith++;
                if ((without.getRGB(x, y) & 0xffffff) == 0xffffff) whiteWithout++;
            }
        }
        assertTrue(whiteWith > whiteWithout + 100, "the accuracy ring: " + whiteWith + " vs " + whiteWithout);

        FlightTrack old = new FlightTrack(null);
        old.sample(fix(59.9139, 10.7522, now - 5000), true);
        old.sample(fix(59.9239, 10.7622, now - 4000), true);
        map.setOldTrack(old.points(), "2026-11-01 10:00");
        assertTrue(map.hasOldTrack());
        map.fitOldTrack();
        BufferedImage img = map.snapshot(400, 300);
        int grey = 0;
        for (int y = 0; y < 300; y++) for (int x = 0; x < 400; x++) if ((img.getRGB(x, y) & 0xffffff) == 0xb0b0a4) grey++;
        assertTrue(grey > 20, "the muted line: " + grey);
        map.setOldTrack(null, null);
        assertFalse(map.hasOldTrack());
    }

    @Test
    void theDownloadWindowKnowsNorwayAndTimes() {
        assertTrue(MapDownloadDialog.touchesNorway(new double[]{59.9, 59.95, 10.7, 10.8}), "Oslo");
        assertFalse(MapDownloadDialog.touchesNorway(new double[]{52.4, 52.6, 13.3, 13.5}), "Berlin");
        assertTrue(MapDownloadDialog.touchesNorway(new double[]{58.5, 59.5, 11.0, 13.0}), "a box over the Swedish border: its west corners are in");
        assertEquals("UNDER A MINUTE", MapDownloadDialog.timeText(100));
        assertEquals("ROUGHLY 3 MIN", MapDownloadDialog.timeText(1000));
        assertEquals("ROUGHLY 1 H 23 MIN", MapDownloadDialog.timeText(30_000));
        assertEquals("ROUGHLY 5 MIN", MapDownloadDialog.timeText(1000, 3.3), "at the measured pace");
        assertEquals("2026-11-02 14:05", MapWindow.trackTitle("track-20261102-140512.gpx"));
        assertEquals("other.gpx", MapWindow.trackTitle("other.gpx"));
        String text = FindDroneDialog.describe(new FlightTrack.Point(System.currentTimeMillis() - 5000, 59.9139, 10.7522, 50, 3, 90, 0, true, true, 7),
                null, java.nio.file.Path.of("track-20261102-140512.gpx"), true);
        assertTrue(text.contains("(phone GPS, \u00b1 7 m)"), text);
        assertTrue(text.contains("MGRS            32V"), text);
        assertTrue(text.contains("From the file   track-20261102-140512.gpx"), text);
        assertFalse(text.contains("Satellites"));
        String drone = FindDroneDialog.describe(new FlightTrack.Point(System.currentTimeMillis(), 59.9139, 10.7522, 50, 3, 90, 11, false, false, 0), null, null, false);
        assertTrue(drone.contains("Satellites 11"), drone);
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

        // SELECT WITH CURSOR: a plain click ends the mode (the window is told) and selects nothing; a right-click too
        boolean[] ended = {false};
        got[0] = null;
        map.setOnSelectModeEnded(() -> ended[0] = true);
        map.selectAreaNext();
        assertTrue(map.isSelectingArea());
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK, 100, 100, 1, false, MouseEvent.BUTTON1));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_RELEASED, 0, 0, 101, 100, 1, false, MouseEvent.BUTTON1));
        assertNull(got[0], "a click is no box");
        assertFalse(map.isSelectingArea());
        assertTrue(ended[0], "the download window is told, so it comes back");
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, map.getCursor().getType());
        ended[0] = false;
        map.selectAreaNext();
        assertEquals(java.awt.Cursor.CROSSHAIR_CURSOR, map.getCursor().getType());
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON3_DOWN_MASK, 100, 100, 1, true, MouseEvent.BUTTON3));
        assertFalse(map.isSelectingArea(), "a right-click calls it off");
        assertTrue(ended[0]);
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, map.getCursor().getType(), "the cursor is back");
        // and a drag without Alt in the mode draws the box
        ended[0] = false;
        map.selectAreaNext();
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK, 100, 100, 1, false, MouseEvent.BUTTON1));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_DRAGGED, 0, InputEvent.BUTTON1_DOWN_MASK, 200, 180, 1, false, MouseEvent.NOBUTTON));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_RELEASED, 0, 0, 200, 180, 1, false, MouseEvent.BUTTON1));
        assertNotNull(got[0]);
        assertTrue(ended[0]);
        assertFalse(map.isSelectingArea());
        got[0] = null;

        // without Alt a drag pans the map and selects nothing
        got[0] = null;
        double before = map.toLatLon(200, 150)[0];
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK, 100, 100, 1, false, MouseEvent.BUTTON1));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_DRAGGED, 0, InputEvent.BUTTON1_DOWN_MASK, 100, 160, 1, false, MouseEvent.NOBUTTON));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_RELEASED, 0, 0, 100, 160, 1, false, MouseEvent.BUTTON1));
        assertNull(got[0]);
        assertNotEquals(before, map.toLatLon(200, 150)[0], "panned");
    }

    private static int near(BufferedImage img, int rgb, int tol) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int c = img.getRGB(x, y);
                if (Math.abs(((c >> 16) & 255) - ((rgb >> 16) & 255)) <= tol && Math.abs(((c >> 8) & 255) - ((rgb >> 8) & 255)) <= tol
                        && Math.abs((c & 255) - (rgb & 255)) <= tol) n++;
            }
        }
        return n;
    }

    /** 1.0.41: an earlier flight stays on the map in its own colour, and the other drones on the network are drawn. */
    @Test
    void earlierFlightsAndTheOtherDronesAreDrawn(@TempDir Path dir) {
        MapView map = new MapView(new TileCache(dir, "test", false));
        long now = System.currentTimeMillis();
        FlightTrack live = new FlightTrack(null);
        live.sample(fix(59.9139, 10.7522, now - 2000), true);
        live.sample(fix(59.9149, 10.7542, now - 1000), true);
        live.sample(fix(59.9159, 10.7562, now), true);
        map.update(live, fix(59.9159, 10.7562, now), 45, false, true);
        BufferedImage before = map.snapshot(400, 300);
        FlightTrack earlier = new FlightTrack(null);
        earlier.sample(fix(59.9140, 10.7535, now - 9000), true);
        earlier.sample(fix(59.9150, 10.7540, now - 8000), true);
        earlier.sample(fix(59.9160, 10.7538, now - 7000), true);
        map.setKept(java.util.List.of(new MapView.Kept("MobileAlpha 22:41", earlier.points())), i -> { }, () -> { });
        map.setNetDrones(java.util.List.of(new MapView.NetDrone("MobileBravo", 59.9163, 10.7580, "FLOWN BY PCBravo", MapView.Kind.ACTIVE, true, "PCBravo")),
                (name, watch) -> { });
        BufferedImage img = map.snapshot(400, 300);
        if (Boolean.getBoolean("shot")) {
            try {
                javax.imageio.ImageIO.write(img, "png", Path.of("map-net.png").toFile());
            } catch (java.io.IOException ignored) {
            }
        }
        int blue = near(img, 0x4fc3f7, 40), yellow = near(img, Theme.WARN.getRGB() & 0xffffff, 40);
        assertTrue(blue > near(before, 0x4fc3f7, 40) + 30, "the earlier flight's line: " + blue);
        assertTrue(yellow > near(before, Theme.WARN.getRGB() & 0xffffff, 40) + 40, "the flown drone's diamond and its name: " + yellow);
    }

    /**
     * H6 (1.0.49): the map is asked every 33 ms; it repaints when something changed (a new report, the heading), not
     * every time - and the track it draws is taken in as it grows, not copied whole each paint.
     */
    @Test
    void theMapRepaintsOnChangeOnly(@TempDir Path dir) {
        int[] repaints = {0};
        MapView map = new MapView(new TileCache(dir, "test", false)) {
            @Override
            public void repaint(long tm, int x, int y, int width, int height) {
                repaints[0]++;
            }
        };
        FlightTrack track = new FlightTrack(null);
        long now = System.currentTimeMillis();
        FcTelemetry.Gps a = fix(59.9139, 10.7522, now);
        track.sample(a, true);
        map.update(track, a, 45, false, true);
        int after = repaints[0];
        assertTrue(after >= 1, "the first report shows");
        for (int i = 0; i < 20; i++) map.update(track, a, 45, false, true); // 20 refreshes, nothing new
        assertEquals(after, repaints[0], "nothing changed: no repaint");
        map.update(track, a, 50, false, true);
        assertTrue(repaints[0] > after, "the heading turned");
        after = repaints[0];
        FcTelemetry.Gps b = fix(59.9149, 10.7542, now + 100);
        track.sample(b, true);
        map.update(track, b, 50, false, true);
        assertTrue(repaints[0] > after, "a new report");
        assertEquals(2, map.trackPath().taken(), "the line took in the new point only");
    }

    /** 1.0.56, the pilot: "create an indication that the map is loading". */
    @Test
    void loadingMapShowsWhileTilesAreOnTheirWay(@TempDir Path dir) throws Exception {
        MapView map = new MapView(TileCache.neverLoading(dir));
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            map.setSize(400, 300);
            java.awt.Graphics2D g = img.createGraphics();
            map.printAll(g); // the tiles are asked for here...
            map.printAll(g); // ...and are on their way now
            g.dispose();
        });
        assertNotNull(map.loadingChip, "LOADING MAP shows");
        assertTrue(map.loadingChip.y >= 45 && map.loadingChip.y < 70 && Math.abs(map.loadingChip.getCenterX() - 200) < 2, "top middle, under NO DRONE POSITION: " + map.loadingChip);
    }
}
