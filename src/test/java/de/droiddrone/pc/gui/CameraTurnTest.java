package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.flights.FlightLibrary;
import de.droiddrone.pc.observe.ObserverLink;
import org.junit.jupiter.api.Test;

import javax.swing.ImageIcon;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.55 (the pilot): a phone's picture turned once is turned everywhere it shows - one setting per phone
 * (CameraTurn) read by the main video, the observation tiles, the overwatch cards, clips and thumbnails, and the
 * previous flights' raw recording; and each observation tile carries its own drone's OSD.
 */
class CameraTurnTest {
    private static final int RED = 0xff0000, BLUE = 0x0000ff;

    /** A picture red on its top-left quarter, blue elsewhere. */
    private static BufferedImage marked(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, x < w / 2 && y < h / 2 ? RED : BLUE);
        return img;
    }

    private static int rgb(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) & 0xffffff;
    }

    @Test
    void aVirtualDroneNeverTakesTheOldPcWideSetting() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set(CameraTurn.KEY, "true"); // the pilot's settings: a real camera mounted upside down before 1.0.41
        CameraTurn turn = new CameraTurn(config, null);
        java.util.Set<String> virtual = new java.util.HashSet<>();
        turn.setVirtualTest(virtual::contains);
        assertTrue(turn.upsideDown("MobileZulu"), "not known yet as virtual: a real phone's fallback");
        virtual.add("MobileZulu"); // the name list learns it is the tour's drone
        assertFalse(turn.upsideDown("MobileZulu"), "the virtual picture is upright; the early answer wasn't kept");
        assertTrue(turn.upsideDown("MobileGolf"), "a real phone never set still takes it");
        turn.set("MobileZulu", true);
        assertTrue(turn.upsideDown("MobileZulu"), "turned by the pilot: its own setting wins");
    }

    @Test
    void eachPhoneKeepsItsOwnWayUpAndAnUnsetOneTakesTheOldPcWideSetting() throws Exception {
        PcConfig config = PcConfig.defaults();
        List<Map<String, String>> saved = new ArrayList<>();
        CameraTurn turn = new CameraTurn(config, saved::add);
        assertFalse(turn.upsideDown("MobileAlpha"));
        config.set(CameraTurn.KEY, "true"); // up to 1.0.40: one setting for every phone
        CameraTurn fresh = new CameraTurn(config, null);
        assertTrue(fresh.upsideDown("MobileAlpha"), "a phone never set takes the PC-wide setting");
        fresh.set("MobileAlpha", false);
        assertFalse(fresh.upsideDown("MobileAlpha"));
        assertTrue(fresh.upsideDown("MobileBravo"));
        assertFalse(fresh.upsideDown("mobilealpha"), "names in any case");

        List<String> told = new ArrayList<>();
        turn.addListener((phone, on) -> told.add(phone + "=" + on));
        turn.set("MobileCharlie", true);
        assertTrue(turn.upsideDown("MobileCharlie"));
        assertEquals(List.of("MobileCharlie=true"), told, "every view is told at once");
        assertEquals(Map.of("videoUpsideDown.MobileCharlie", "true"), saved.get(saved.size() - 1), "saved under the 1.0.41 key");
        assertEquals("true", config.getString("videoUpsideDown.MobileCharlie"));
    }

    @Test
    void thePictureTurnsHalfARoundAndAMirroredOneOnlyUpsideDown() {
        BufferedImage src = marked(8, 6);
        BufferedImage out = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        CameraTurn.draw(g, src, 0, 0, 8, 6, true, true);
        g.dispose();
        assertEquals(BLUE, rgb(out, 0, 0));
        assertEquals(RED, rgb(out, 7, 5), "the top-left quarter now bottom right");

        assertFalse(CameraTurn.flipX(true, true), "a mirrored picture turned: up-down only");
        assertTrue(CameraTurn.flipX(false, true));
        assertTrue(CameraTurn.flipX(true, false));
    }

    @Test
    void aMotionBoxTurnsWithThePicture() {
        assertArrayEquals(new float[]{0.7f, 0.6f, 0.2f, 0.1f}, CameraTurn.box(new float[]{0.1f, 0.3f, 0.2f, 0.1f}, true, true), 1e-6f);
        assertArrayEquals(new float[]{0.1f, 0.3f, 0.2f, 0.1f}, CameraTurn.box(new float[]{0.1f, 0.3f, 0.2f, 0.1f}, false, false), 1e-6f);
        assertNull(CameraTurn.box(null, true, true));
    }

    @Test
    void anUpsideDownCameraSeesOnTheOtherSide() {
        assertEquals(100, CameraTurn.bearing(90, 10, false), 1e-9);
        assertEquals(80, CameraTurn.bearing(90, 10, true), 1e-9, "its sensor's right is the world's left");
        assertEquals(355, CameraTurn.bearing(5, 10, true), 1e-9);
    }

    @Test
    void anOverwatchThumbnailTurnsWhenItsDroneIsUpsideDown() {
        ImageIcon icon = new ImageIcon(marked(96, 54));
        assertSame(icon, OverwatchPanel.turnedIcon(icon, false));
        javax.swing.Icon turned = OverwatchPanel.turnedIcon(icon, true);
        assertEquals(96, turned.getIconWidth());
        BufferedImage out = new BufferedImage(96, 54, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        turned.paintIcon(null, g, 0, 0);
        g.dispose();
        assertEquals(RED, rgb(out, 90, 50));
        assertEquals(BLUE, rgb(out, 5, 5));
    }

    @Test
    void previousFlightsTurnTheRawRecordingNotTheOsdCopy() {
        Path raw = Path.of("flight-1.mkv"), osd = Path.of("flight-1-osd.mkv");
        FlightLibrary.Flight both = new FlightLibrary.Flight(0, 1, "MobileAlpha", null, raw, osd, null, null, 0);
        assertTrue(PreviousFlightsDialog.rawRecording(raw, both), "the camera's own recording (the OSD copy didn't play)");
        assertFalse(PreviousFlightsDialog.rawRecording(osd, both), "the OSD copy was turned when it was made");
        FlightLibrary.Flight rawOnly = new FlightLibrary.Flight(0, 1, "MobileAlpha", null, raw, null, null, null, 0);
        assertTrue(PreviousFlightsDialog.rawRecording(raw, rawOnly));
    }

    // ------------------------------------------------------------------ the tiles

    /** A telemetry as an observer's ObserverLink fills it: the phone's records in a TelemetryData packet. */
    private static FcTelemetry telemetry(boolean failsafe) throws Exception {
        return de.droiddrone.pc.fleet.SampleTelemetry.idleDrone(failsafe);
    }

    @Test
    void aTilesOsdComesFromItsOwnDronesTelemetry() throws Exception {
        FcTelemetry t = telemetry(false);
        long now = MonoClock.ms();
        assertNull(ObservationOsd.data(t, null, 0, false, now), "nothing heard yet: no OSD");
        OsdRenderer.Data d = ObservationOsd.data(t, null, now - 100, false, now);
        assertNotNull(d);
        assertSame(t, d.telemetry(), "the tile's own drone's figures");
        assertTrue(d.connected() && d.linkReady());
        assertNull(d.channels(), "no sticks: nothing flies from the grid");
        assertFalse(d.fcLost());
        assertFalse(ObservationOsd.data(t, null, now - ObserverLink.SILENT_MS - 10, false, now).linkReady(), "silent: LINK LOST");
        assertNotNull(t.getBattery(), "the test telemetry has a battery");
    }

    @Test
    void aTilesOsdShowsTheDroneButNoPingAndNoArmingWarnings() throws Exception {
        FcTelemetry t = telemetry(true);
        long now = MonoClock.ms();
        OsdRenderer.Data d = ObservationOsd.data(t, null, now, false, now);
        OsdLayout layout = OsdLayout.defaults();
        BufferedImage img = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);

        Map<OsdElement, Rectangle2D> main = new EnumMap<>(OsdElement.class);
        Graphics2D g = img.createGraphics();
        new OsdRenderer().paint(g, 0, 0, 640, 360, d, layout, main);
        g.dispose();

        OsdRenderer tile = new OsdRenderer();
        tile.setObserver(true);
        tile.setMinUnit(ObservationOsd.MIN_UNIT);
        tile.setScale(ObservationOsd.SCALE);
        Map<OsdElement, Rectangle2D> shown = new EnumMap<>(OsdElement.class);
        g = img.createGraphics();
        tile.paint(g, 0, 0, 640, 360, d, layout, shown);
        g.dispose();
        assertTrue(shown.containsKey(OsdElement.BATTERY), "its battery: " + shown.keySet());
        assertTrue(shown.containsKey(OsdElement.HEADING), "its heading");
        assertTrue(main.containsKey(OsdElement.PING), "the pilot's OSD has the ping");
        assertFalse(shown.containsKey(OsdElement.PING), "no ping: an observer has no link of its own to time");
        assertTrue(main.containsKey(OsdElement.WARNINGS), "the pilot's OSD warns of FAILSAFE: " + main.keySet());
        assertFalse(shown.containsKey(OsdElement.WARNINGS), "FAILSAFE and arming blocks are normal on an idle drone");
        Rectangle2D a = main.get(OsdElement.BATTERY), b = shown.get(OsdElement.BATTERY);
        assertTrue(b.getHeight() < a.getHeight(), "a tile's OSD is scaled down: " + b.getHeight() + " vs " + a.getHeight());
        assertTrue(b.getHeight() >= a.getHeight() * ObservationOsd.MIN_UNIT / (360 / 720f) * 0.8, "but not below readable");
    }

    @Test
    void sixTileOsdsStayCheap() throws Exception {
        FcTelemetry t = telemetry(false);
        long now = MonoClock.ms();
        OsdRenderer.Data d = ObservationOsd.data(t, null, now, false, now);
        OsdLayout layout = OsdLayout.defaults();
        BufferedImage img = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        List<OsdRenderer> tiles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            OsdRenderer r = new OsdRenderer();
            r.setObserver(true);
            r.setMinUnit(ObservationOsd.MIN_UNIT);
            r.setScale(ObservationOsd.SCALE);
            tiles.add(r);
        }
        Runnable all = () -> {
            for (OsdRenderer r : tiles) {
                Graphics2D g = img.createGraphics();
                r.paint(g, 0, 0, 640, 360, d, layout);
                g.dispose();
            }
        };
        for (int i = 0; i < 100; i++) all.run();
        long t0 = System.nanoTime();
        int n = 100;
        for (int i = 0; i < n; i++) all.run();
        double ms = (System.nanoTime() - t0) / 1e6 / n;
        // ten times what the full-HD OSD measured (2.7 ms), like PaintCostTest: six tiles at once
        assertTrue(ms < 27, "six tile OSDs: " + ms + " ms");
    }

    @Test
    void aTileShowsItsPhonesPictureTurnedAndTurnsWhenSetElsewhere() throws Exception {
        PcConfig config = PcConfig.defaults();
        CameraTurn turn = new CameraTurn(config, null);
        BufferedImage[] painted = new BufferedImage[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            ObservationGrid grid = new ObservationGrid(null, turn, OsdLayout::defaults, () -> false);
            grid.setPhones(List.of("MobileAlpha", "MobileBravo"));
            grid.setSize(800, 300);
            layOut(grid);
            for (int i = 0; i < 2; i++) {
                var sink = grid.sink(i);
                sink.videoStarted(160, 90, false);
                int[] px = sink.beginFrame(160, 90);
                for (int y = 0; y < 90; y++) for (int x = 0; x < 160; x++) px[y * 160 + x] = x < 80 && y < 45 ? RED : BLUE;
                sink.presentFrame();
            }
            turn.set("MobileBravo", true); // as from VIDEO > Camera upside down, a card or another tile
            assertFalse(grid.upsideDown(0));
            assertTrue(grid.upsideDown(1));
            painted[0] = new BufferedImage(800, 300, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = painted[0].createGraphics();
            grid.printAll(g);
            g.dispose();
            grid.dispose();
        });
        // each tile's picture area: find a red pixel's place in the left and right halves
        double[] left = redCentre(painted[0], 0, 400), right = redCentre(painted[0], 400, 800);
        assertNotNull(left);
        assertNotNull(right);
        assertTrue(left[1] < 150 && left[0] < 200, "MobileAlpha as it comes: red top left " + java.util.Arrays.toString(left));
        assertTrue(right[1] > 100 && right[0] > 600, "MobileBravo turned: red bottom right " + java.util.Arrays.toString(right));
    }

    /** Lays a component tree out without a window. */
    private static void layOut(java.awt.Container c) {
        c.doLayout();
        for (java.awt.Component k : c.getComponents()) if (k instanceof java.awt.Container cc) layOut(cc);
    }

    private static double[] redCentre(BufferedImage img, int x0, int x1) {
        double sx = 0, sy = 0;
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = x0; x < x1; x++) {
                int c = img.getRGB(x, y);
                int r = (c >> 16) & 0xff, gr = (c >> 8) & 0xff, b = c & 0xff;
                if (r > 200 && gr < 60 && b < 60) {
                    sx += x;
                    sy += y;
                    n++;
                }
            }
        }
        return n == 0 ? null : new double[]{sx / n, sy / n};
    }
}
