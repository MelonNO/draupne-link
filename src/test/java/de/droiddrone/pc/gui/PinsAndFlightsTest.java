package de.droiddrone.pc.gui;

import de.droiddrone.pc.flights.FlightLibraryTest;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pilot's list of 2026-11-08: OUTLINE / FILL only with RECTANGLE; the pins sheet with EDIT LIST; PREVIOUS FLIGHTS.
 * Pictures to $PINS_SHOTS when set (made-up pins and flights, an empty offline map).
 */
class PinsAndFlightsTest {
    private static void layout(Container c) {
        c.doLayout();
        for (Component k : c.getComponents()) if (k instanceof Container ct) layout(ct);
    }

    private static void shot(Container root, int w, int h, String name) throws Exception {
        if (System.getenv("PINS_SHOTS") == null) return;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        root.paint(g);
        g.dispose();
        Files.createDirectories(Path.of(System.getenv("PINS_SHOTS")));
        javax.imageio.ImageIO.write(img, "png", Path.of(System.getenv("PINS_SHOTS"), name + ".png").toFile());
    }

    private static MapView map(Path dir) {
        MapView map = new MapView(new TileCache(dir.resolve("tiles"), "test", false));
        map.setSize(500, 400);
        map.setFollow(false);
        map.centreOn(59.91, 10.73, 14);
        return map;
    }

    @Test
    void outlineAndFillComeOutOnlyWithTheRectangle(@TempDir Path dir) {
        MapView map = map(dir);
        MapToolsPanel tools = new MapToolsPanel(map);
        tools.syncTools();
        tools.fillFields.finish();
        assertFalse(tools.fillFields.isOpen());
        assertEquals(0, tools.fillFields.getPreferredSize().height, "hidden: no room taken");
        map.setTool(MapView.Tool.AREA);
        tools.syncTools();
        assertTrue(tools.fillFields.isOpen());
        tools.fillFields.finish();
        assertTrue(tools.fillFields.getPreferredSize().height > 10, "out with RECTANGLE");
        map.setTool(MapView.Tool.LINE);
        tools.syncTools();
        assertFalse(tools.fillFields.isOpen(), "back in for a line");
    }

    @Test
    void theMapGivesAPlaceForANewPinAndARightClickCallsItOff(@TempDir Path dir) {
        MapView map = map(dir);
        double[][] got = new double[1][];
        boolean[] called = new boolean[1];
        map.pickPointNext(ll -> {
            called[0] = true;
            got[0] = ll;
        });
        assertTrue(map.isPickingPoint());
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_PRESSED, 0, java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 250, 200, 1, false, MouseEvent.BUTTON1));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_RELEASED, 0, 0, 250, 200, 1, false, MouseEvent.BUTTON1));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_CLICKED, 0, 0, 250, 200, 1, false, MouseEvent.BUTTON1));
        assertTrue(called[0]);
        assertNotNull(got[0]);
        assertEquals(59.91, got[0][0], 0.01);
        assertFalse(map.isPickingPoint());
        called[0] = false;
        map.pickPointNext(ll -> {
            called[0] = true;
            got[0] = ll;
        });
        map.cancelTool(); // Esc
        assertTrue(called[0]);
        assertNull(got[0], "called off: no place");
    }

    @Test
    void thePinsSheetEditsOnlyInEditList(@TempDir Path dir) throws Exception {
        Theme.install();
        MapView map = map(dir);
        Pins pins = new Pins(dir.resolve("pins.txt"));
        map.setPins(pins);
        Pins.Pin alpha = pins.add("Alpha", 59.9150, 10.7400);
        pins.add("Bravo", 59.9050, 10.7200);
        pins.add("Charlie", 59.9200, 10.7500);
        Path gpx = FlightLibraryTest.gpx(Files.createDirectories(dir.resolve("Tracks")), "2026-11-08T14:05:00", 30);
        FlightTrack launchTrack = FlightTrack.read(gpx);
        SwingUtilities.invokeAndWait(() -> {
            PinManagerDialog d = new PinManagerDialog(null, pins, map, () -> de.droiddrone.pc.map.Coordinates.Format.MGRS, () -> launchTrack, null);
            try {
                for (Pins.Pin p : pins.list()) d.knowHeight(p, 212 + p.name().length());
                PinManagerDialog.Sheet s = d.sheet;
                assertEquals(3, s.rows.size());
                assertEquals(0, s.editShown, "EDIT LIST off to begin with");
                int plain = s.getPreferredSize().height;
                assertEquals(PinManagerDialog.HEADER + 3 * PinManagerDialog.ROW + 4, plain);
                assertTrue(d.brgText(alpha).endsWith("°"), "bearing from launch");
                assertNotEquals("-", d.distText(alpha), "distance from launch");
                assertEquals("217 m", d.amslText(alpha));

                d.setSize(720, 440);
                for (int i = 0; i < 3; i++) layout(d.getRootPane());
                shot(d.getRootPane(), 720, 440, "pins");

                d.edit.set(true);
                s.finishAnimations();
                assertEquals(plain + PinManagerDialog.ROW, s.getPreferredSize().height, "the + row out with EDIT LIST");
                int[] c = s.columns();
                // a click on a row's X (the column only there in EDIT LIST) removes it, sliding away
                int y = PinManagerDialog.HEADER + PinManagerDialog.ROW + PinManagerDialog.ROW / 2;
                s.dispatchEvent(new MouseEvent(s, MouseEvent.MOUSE_MOVED, 0, 0, c[7] + 10, y, 0, false));
                for (int i = 0; i < 3; i++) layout(d.getRootPane());
                shot(d.getRootPane(), 720, 440, "pins-edit");
                s.click(c[7] + 10, y, null);
                assertEquals(2, pins.list().size(), "Bravo removed");
                assertTrue(s.rows.stream().anyMatch(r -> r.leaving), "its row slides away");
                s.finishAnimations();
                assertEquals(2, s.rows.size());
                // the name renames in EDIT LIST
                s.startRename(0, PinManagerDialog.HEADER);
                assertNotNull(s.renaming);
                java.lang.reflect.Field f = PinManagerDialog.Sheet.class.getDeclaredField("renameField");
                f.setAccessible(true);
                ((javax.swing.JTextField) f.get(s)).setText("Objective");
                s.endRename(true);
                assertTrue(pins.list().stream().anyMatch(p -> p.name().equals("Objective")));
                // out of EDIT LIST: no X, a click on the row goes to the pin
                d.edit.set(false);
                s.finishAnimations();
                c = s.columns();
                s.click(c[7] + 10, PinManagerDialog.HEADER + 5, null);
                assertEquals(2, pins.list().size(), "no X without EDIT LIST: the click went to the pin");
                assertNotNull(s.selected);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                d.dispose();
            }
        });
    }

    @Test
    void previousFlightsListsPlaysAndScrubs(@TempDir Path dir) throws Exception {
        Theme.install();
        Path tracks = Files.createDirectories(dir.resolve("Tracks")), videos = Files.createDirectories(dir.resolve("Videos")),
                logs = Files.createDirectories(dir.resolve("logs"));
        FlightLibraryTest.linkLog(logs, "2026-11-08T14:04:50", 200, "MobileZulu");
        FlightLibraryTest.gpx(tracks, "2026-11-08T14:05:00", 180);
        FlightLibraryTest.video(videos, "2026-11-08T14:05:00", 180);
        FlightLibraryTest.gpx(tracks, "2026-11-07T10:00:00", 90);
        PreviousFlightsDialog[] holder = new PreviousFlightsDialog[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new PreviousFlightsDialog(null, new TileCache(dir.resolve("tiles"), "test", false),
                TileSource.KARTVERKET_TOPO, f -> {}, tracks, videos, logs));
        PreviousFlightsDialog d = holder[0];
        try {
            long until = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < until) { // the scan and the load run off the Swing thread
                boolean[] ready = new boolean[1];
                SwingUtilities.invokeAndWait(() -> ready[0] = d.data != null);
                if (ready[0]) break;
                Thread.sleep(50);
            }
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(2, d.list.getModel().getSize());
                assertNotNull(d.data, "the newest loaded");
                assertEquals("MobileZulu", d.data.flight.drone());
                assertFalse(d.data.link.isEmpty());
                assertTrue(d.play.isEnabled());
                d.seek(FlightLibraryTest.at("2026-11-08T14:06:30"));
                assertEquals(FlightLibraryTest.at("2026-11-08T14:06:30"), d.position());
                d.togglePlay();
                assertEquals("Pause", d.play.getText());
                d.togglePlay();
                assertEquals("Play", d.play.getText());
                d.setSize(1280, 760);
                for (int i = 0; i < 3; i++) layout(d.getRootPane());
                d.map.setReplay(d.data.path()); // fitted to the map's size now
                d.tick();
            });
            Thread.sleep(300); // the player says it can't play the (empty) made-up video
            SwingUtilities.invokeAndWait(() -> {
                try {
                    shot(d.getRootPane(), 1280, 760, "previous-flights");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(d::dispose);
        }
    }

    @Test
    void theRecordingPlaysFromTheRawFileWhenTheOsdCopyWontOpen(@TempDir Path dir) throws Exception {
        Theme.install();
        Path tracks = Files.createDirectories(dir.resolve("Tracks")), videos = Files.createDirectories(dir.resolve("Videos"));
        FlightLibraryTest.gpx(tracks, "2026-11-08T14:05:00", 6);
        Path mkv = FlightLibraryTest.video(videos, "2026-11-08T14:05:00", 4); // the -osd copy stays an empty file: unplayable
        de.droiddrone.pc.video.TestStreams.Stream st = de.droiddrone.pc.video.TestStreams.h264(320, 180, 100, 25);
        Files.delete(mkv);
        try (de.droiddrone.pc.video.StreamRecorder r = new de.droiddrone.pc.video.StreamRecorder(mkv, false, 320, 180, st.codecConfig())) {
            long t = 0;
            for (de.droiddrone.pc.video.TestStreams.Packet p : st.packets()) r.write(p.data(), p.keyFrame(), t += 40);
        }
        PreviousFlightsDialog[] holder = new PreviousFlightsDialog[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new PreviousFlightsDialog(null, new TileCache(dir.resolve("tiles"), "test", false),
                TileSource.KARTVERKET_TOPO, f -> {}, tracks, videos, null));
        PreviousFlightsDialog d = holder[0];
        try {
            long until = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < until && !d.video.hasPicture()) Thread.sleep(50);
            assertTrue(d.video.hasPicture(), "the first picture shown on loading (from the recording, the OSD copy being unplayable)");
            SwingUtilities.invokeAndWait(() -> {
                d.seek(FlightLibraryTest.at("2026-11-08T14:05:02"));
                d.togglePlay();
            });
            Thread.sleep(600);
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(d.position() > FlightLibraryTest.at("2026-11-08T14:05:02") + 300, "the clock runs while playing");
                assertTrue(d.video.hasPicture());
                d.togglePlay();
            });
        } finally {
            SwingUtilities.invokeAndWait(d::dispose);
        }
    }
}
