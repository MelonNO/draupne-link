package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.map.TileCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pins sheet's SEARCH and EXPORT GPX, and the rough pins' mark: the rows are the pins the search finds, what a
 * row does it does to its own pin under a search too, the file has the pins listed, and a rough pin is ~NAME in the
 * list and on the map while its name stays as it is. Made-up pins on an empty offline map; no window is shown.
 */
class PinManagerSearchTest {
    @TempDir
    Path dir;

    private MapView map;
    private Pins pins;
    private Pins.Pin alpha, bravo, charlie, tango;

    private PinManagerDialog dialog() {
        Theme.install();
        map = new MapView(TileCache.neverLoading(dir.resolve("tiles")));
        map.setSize(500, 400);
        map.setFollow(false);
        map.centreOn(59.91, 10.73, 14);
        pins = new Pins(dir.resolve("pins.txt"));
        map.setPins(pins);
        alpha = pins.add("Alpha", 59.9150, 10.7400);
        bravo = pins.add("Bravo", 59.9050, 10.7200);
        charlie = pins.add("Charlie", 59.9200, 10.7500);
        tango = pins.add("Tango", 59.9100, 10.7300, true); // a rough one
        PinManagerDialog d = new PinManagerDialog(null, pins, map, () -> Coordinates.Format.MGRS, () -> null, null);
        d.sheet.finishAnimations();
        return d;
    }

    private static List<String> names(PinManagerDialog d) {
        d.sheet.finishAnimations(); // the rows that slide away are gone
        return d.sheet.rows.stream().map(r -> r.pin.name()).toList();
    }

    private void onSwing(java.util.function.Consumer<PinManagerDialog> test) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PinManagerDialog d = dialog();
            try {
                test.accept(d);
            } finally {
                d.dispose();
            }
        });
    }

    @Test
    void theRowsAreThePinsTheSearchFinds() throws Exception {
        onSwing(d -> {
            assertEquals(List.of("Alpha", "Bravo", "Charlie", "Tango"), names(d));
            assertFalse(d.none.isVisible());

            d.search.setText("br");
            assertTrue(d.sheet.rows.stream().anyMatch(r -> r.leaving), "the others slide away");
            assertEquals(List.of("Bravo"), names(d));
            d.search.setText("  A ");
            assertEquals(List.of("Alpha", "Bravo", "Charlie", "Tango"), names(d), "an a in every name; any case, spaces around it nothing");

            // by its place as the list writes it: MGRS here, with or without the spaces
            String grid = Coordinates.format(Coordinates.Format.MGRS, charlie.lat(), charlie.lon());
            assertEquals(d.gridText(charlie), grid);
            d.search.setText(grid.toLowerCase(Locale.ROOT).replace(" ", ""));
            assertEquals(List.of("Charlie"), names(d));
            d.search.setText(String.format(Locale.ROOT, "%.4f", charlie.lat()));
            assertEquals(List.of(), names(d), "latitude and longitude aren't what this list shows");
            assertTrue(d.none.isVisible());
            assertEquals("No pin found.", d.none.getText());
            assertFalse(d.exportGpx.isEnabled(), "nothing listed, nothing to export");

            d.search.setText("");
            assertEquals(List.of("Alpha", "Bravo", "Charlie", "Tango"), names(d), "in the pins' own order again");
            assertFalse(d.none.isVisible());
            assertTrue(d.exportGpx.isEnabled());

            // a pin dropped on the map meanwhile comes into a searched list only when the search finds it
            d.search.setText("o");
            pins.add("Delta", 59.9000, 10.7000);
            pins.add("Oscar", 59.9010, 10.7010);
            d.refresh(); // as the list does once a second
            assertEquals(List.of("Bravo", "Tango", "Oscar"), names(d));
        });
    }

    @Test
    void aRowActsOnItsOwnPinUnderASearch() throws Exception {
        onSwing(d -> {
            PinManagerDialog.Sheet s = d.sheet;
            d.setSize(720, 440);
            Offscreen.layout(d, 720, 440);
            int firstRow = PinManagerDialog.HEADER + PinManagerDialog.ROW / 2;

            // the first row is Charlie now, not Alpha: a click goes to Charlie
            d.search.setText("ch");
            assertEquals(List.of("Charlie"), names(d));
            int[] c = s.columns();
            s.click(c[2] + 5, firstRow, null);
            assertEquals(charlie, s.selected);
            assertEquals(charlie.lat(), map.toLatLon(map.getWidth() / 2, map.getHeight() / 2)[0], 1e-3, "the map went to Charlie");
            s.click(c[5] + 5, firstRow, null); // its go-to mark
            assertEquals(charlie, s.selected);

            // the selected pin searched away is no longer selected (Ctrl+C would copy a pin not in sight)
            d.search.setText("br");
            assertEquals(List.of("Bravo"), names(d));
            assertNull(s.selected);

            // EDIT LIST: the name renames Bravo; it stays listed as long as the search finds the new name
            d.edit.set(true);
            s.finishAnimations();
            s.startRename(0, PinManagerDialog.HEADER);
            assertEquals(bravo, s.renaming);
            renameField(s).setText("Bridge");
            s.endRename(true);
            assertEquals(List.of("Bridge"), names(d));
            assertEquals(List.of("Alpha", "Bridge", "Charlie", "Tango"), pins.list().stream().map(Pins.Pin::name).toList());

            // a rough pin renamed: its own name in the field (no ~), still rough after
            d.search.setText("tan");
            assertEquals(List.of("Tango"), names(d));
            s.startRename(0, PinManagerDialog.HEADER);
            assertEquals("Tango", renameField(s).getText(), "the name as it is kept, without the list's ~");
            renameField(s).setText("Tower");
            s.endRename(true);
            assertEquals(List.of(), names(d), "the search doesn't find the new name: the row goes");
            Pins.Pin tower = pins.list().get(3);
            assertEquals(new Pins.Pin("Tower", tango.lat(), tango.lon(), tango.createdMs(), true), tower, "the same pin, rough as before");

            // the X on the first row removes that row's pin
            d.search.setText("lie");
            assertEquals(List.of("Charlie"), names(d));
            c = s.columns();
            s.dispatchEvent(new java.awt.event.MouseEvent(s, java.awt.event.MouseEvent.MOUSE_MOVED, 0, 0, c[7] + 10, firstRow, 0, false));
            s.click(c[7] + 10, firstRow, null);
            assertEquals(List.of("Alpha", "Bridge", "Tower"), pins.list().stream().map(Pins.Pin::name).toList(), "Charlie, not the list's first pin");

            // the + row: a pin the search wouldn't find clears it, so it is seen; one it finds leaves it
            d.search.setText("al");
            assertEquals(List.of("Alpha"), names(d));
            Pins.Pin alpine = d.addAt("Alpine", 59.9300, 10.7600);
            assertEquals("al", d.search.getText());
            assertEquals(List.of("Alpha", "Alpine"), names(d));
            Pins.Pin next = d.addAt(null, 59.9310, 10.7610);
            assertEquals("Bravo", next.name(), "the next free name");
            assertEquals("", d.search.getText());
            assertEquals(List.of("Alpha", "Bridge", "Tower", "Alpine", "Bravo"), names(d));

            // REMOVE ALL under a search: the pins listed and no other
            d.search.setText("al");
            d.removeAll(d.listed());
            assertEquals(List.of("Bridge", "Tower", "Bravo"), pins.list().stream().map(Pins.Pin::name).toList());
            assertFalse(pins.list().contains(alpine));
            d.search.setText("");
            assertEquals(List.of("Bridge", "Tower", "Bravo"), names(d));
        });
    }

    private static JTextField renameField(PinManagerDialog.Sheet s) {
        try {
            java.lang.reflect.Field f = PinManagerDialog.Sheet.class.getDeclaredField("renameField");
            f.setAccessible(true);
            return (JTextField) f.get(s);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void exportGpxWritesThePinsListed() throws Exception {
        onSwing(d -> {
            try {
                Path all = dir.resolve("out").resolve("all.gpx"), found = dir.resolve("out").resolve("found.gpx");
                assertEquals(4, d.exportGpx(all), "no search: every pin");
                String gpx = Files.readString(all, StandardCharsets.UTF_8);
                assertTrue(gpx.startsWith("<?xml") && gpx.contains("<gpx version=\"1.1\""), gpx);
                for (String name : List.of("Alpha", "Bravo", "Charlie", "Tango")) assertTrue(gpx.contains("<name>" + name + "</name>"), name + " in " + gpx);
                assertTrue(gpx.contains("<wpt lat=\"59.9150000\" lon=\"10.7400000\"><name>Alpha</name></wpt>"));
                assertFalse(gpx.contains("~"), "a rough pin under its own name");
                assertTrue(gpx.indexOf("Alpha") < gpx.indexOf("Bravo") && gpx.indexOf("Bravo") < gpx.indexOf("Charlie"), "in the list's order");

                d.search.setText("a");
                d.search.setText("ta");
                assertEquals(1, d.exportGpx(found), "a search: the pins it lists");
                String one = Files.readString(found, StandardCharsets.UTF_8);
                assertTrue(one.contains("<name>Tango</name>"));
                assertFalse(one.contains("Alpha") || one.contains("Bravo") || one.contains("Charlie"), one);
                assertEquals(4, pins.list().size(), "the pins themselves are as they were");
                try (var files = Files.list(dir.resolve("out"))) {
                    assertEquals(2, files.count(), "written whole: no half file left beside them");
                }
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static int count(BufferedImage img, int rgb) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) & 0xffffff) == rgb) n++;
        return n;
    }

    @Test
    void aRoughPinHasItsMarkInTheListAndOnTheMap() throws Exception {
        onSwing(d -> {
            try {
                assertEquals("~TANGO", MapPlaceLayer.shownName(tango));
                assertEquals("ALPHA", MapPlaceLayer.shownName(alpha));
                assertEquals("Tango", pins.list().get(3).name(), "shown so only");

                // the list at its own size and at a narrow one: nothing cut off, the pictures to look at
                for (int[] size : new int[][]{{720, 440}, {600, 360}}) {
                    String at = size[0] + "x" + size[1];
                    d.search.setText("");
                    d.edit.set(false);
                    d.sheet.finishAnimations();
                    Offscreen.layout(d, size[0], size[1]);
                    Offscreen.assertNothingClipped(d.getRootPane(), at);
                    assertTrue(d.search.getWidth() >= 120, at + ": room to type in SEARCH: " + d.search.getBounds());
                    Offscreen.shot(d, "pins-" + at);
                    d.search.setText("o");
                    d.edit.set(true);
                    d.sheet.finishAnimations();
                    Offscreen.layout(d, size[0], size[1]);
                    Offscreen.assertNothingClipped(d.getRootPane(), at + " searched");
                    Offscreen.shot(d, "pins-" + at + "-search-edit");
                }

                // the same pin on the map, rough and not: the rough one's label has the ~ more
                Pins plain = new Pins(null), rough = new Pins(null);
                plain.add("Tango", 59.91, 10.73, false);
                rough.add("Tango", 59.91, 10.73, true);
                map.setPins(plain);
                BufferedImage a = map.snapshot(500, 400);
                map.setPins(rough);
                BufferedImage b = map.snapshot(500, 400);
                int warn = Theme.WARN.getRGB() & 0xffffff;
                assertTrue(count(a, warn) > 50, "the pin and its name are drawn");
                assertTrue(count(b, warn) > count(a, warn), "more of the name's colour with the ~: " + count(a, warn) + " -> " + count(b, warn));
                String shots = System.getenv("DEBRIEF_SHOTS");
                if (shots != null && !shots.isBlank()) javax.imageio.ImageIO.write(b, "png", Path.of(shots, "pins-map-rough.png").toFile());
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
    }
}
