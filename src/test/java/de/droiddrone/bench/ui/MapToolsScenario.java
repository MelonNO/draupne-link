package de.droiddrone.bench.ui;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Ui;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Window;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MAP TOOLS used, not only opened (2026-11-19 afternoon): the drawing tools leave their drawings in the pilot's
 * graphics file and CLEAR ALL takes them out; the ruler measures and KEEP makes its line a drawing; GCS SEARCH
 * refuses a box no mast can cover (F-022) and works one out on a box of a sensible size; the lines that tell the
 * pilot what a tool is doing are whole (F-021, F-023); GO TO reads a grid and says so when it can't (F-026).
 */
@Tag("ui")
class MapToolsScenario {
    /** The big map of the map window. */
    static Component map(Ui ui) {
        Window w = ui.window("DRAUPNE Link map");
        for (Component c : ui.components()) {
            if (c.getClass().getSimpleName().equals("MapView") && Ui.edt(() -> SwingUtilities.getWindowAncestor(c)) == w) return c;
        }
        throw new AssertionError("No map in the map window");
    }

    /** The map window, MAP TOOLS out, and one of its three lists (DRAW, TOOLS, DATA). */
    static void openTools(Ui ui, String list) {
        if (ui.has("POP OUT MAP")) ui.click("POP OUT MAP");
        ui.until("the map window", 15_000, () -> ui.window("DRAUPNE Link map") != null);
        Ui.pause(700);
        ui.click("MAP TOOLS");
        Ui.pause(500);
        ui.click(list);
        Ui.pause(800);
    }

    private static Path graphicsFile(AppRig rig) {
        return rig.sandbox.documents().resolve("graphics.kml");
    }

    /** How many drawings the pilot's graphics file holds. */
    private static int drawings(AppRig rig) {
        try {
            Path file = graphicsFile(rig);
            if (!Files.exists(file)) return 0;
            String kml = Files.readString(file, StandardCharsets.UTF_8);
            int n = 0;
            for (int at = kml.indexOf("<Placemark"); at >= 0; at = kml.indexOf("<Placemark", at + 1)) n++;
            return n;
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    private static void waitForDrawings(AppRig rig, int wanted) {
        long end = System.currentTimeMillis() + 5000;
        while (drawings(rig) != wanted && System.currentTimeMillis() < end) Ui.pause(100);
    }

    /** GO TO with a position typed: the map there, zoomed to where a drawn box is some hundred metres. */
    static void goTo(Ui ui, String position, String button) {
        ui.click("GO TO");
        ui.until("the Go to position dialog", 5000, () -> ui.window("Go to position") != null);
        Window dialog = ui.window("Go to position");
        JTextComponent field = ui.first(JTextComponent.class, f -> SwingUtilities.getWindowAncestor(f) == dialog);
        ui.fill(field, position);
        ui.click(button);
        Ui.pause(600);
    }

    @Test
    void whatIsDrawnIsKeptAndClearAllTakesItAway() throws Exception {
        AppRig.scenario("maptools-draw").drones(1).run(rig -> {
            Ui ui = rig.ui;
            openTools(ui, "DRAW");
            Component map = map(ui);
            ui.click("LINE");
            // all of it in the map's upper left: the map of a 1093 x 584 window is that small
            ui.clickAt(map, 120, 80);
            ui.clickAt(map, 200, 140);
            ui.doubleClickAt(map, 260, 90);
            ui.click("RECTANGLE");
            ui.drag(map, 120, 180, 210, 250);
            ui.click("ARROW");
            ui.clickAt(map, 300, 80);
            ui.doubleClickAt(map, 380, 150);
            ui.click("SYMBOL");
            ui.clickAt(map, 330, 230);
            ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
            ui.shot("a line, a box, an arrow and a symbol drawn");
            waitForDrawings(rig, 4);
            rig.check(drawings(rig) == 4, "the four drawings are in the pilot's graphics file: " + drawings(rig) + " in " + graphicsFile(rig));

            ui.click("ERASER");
            ui.clickAt(map, 165, 180); // on the box's upper edge
            ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
            waitForDrawings(rig, 3);
            ui.shot("the box erased");
            rig.check(drawings(rig) == 3, "ERASER on the box's edge takes the box out: " + drawings(rig) + " drawings left");

            ui.click("DATA");
            Ui.pause(700);
            ui.click("CLEAR ALL");
            ui.until("CLEAR ALL asks first", 5000, () -> ui.window("Clear all") != null);
            ui.shot("CLEAR ALL asks");
            ui.click("OK");
            waitForDrawings(rig, 0);
            ui.shot("after CLEAR ALL");
            rig.check(drawings(rig) == 0, "CLEAR ALL leaves no drawing in the file: " + drawings(rig));
        });
    }

    @Test
    void theRulerMeasuresAndKeepMakesItADrawing() throws Exception {
        AppRig.scenario("maptools-ruler").drones(1).run(rig -> {
            Ui ui = rig.ui;
            openTools(ui, "TOOLS");
            goTo(ui, "60.8669, 11.7381", "Go");
            Component map = map(ui);
            ui.click("RULER");
            ui.clickAt(map, 150, 150);
            ui.clickAt(map, 250, 220);
            ui.doubleClickAt(map, 330, 160);
            Ui.pause(600);
            ui.shot("two legs measured");
            rig.check(ui.sees("ELEVATION PROFILE"), "the measured line's height profile shows");
            rig.check(ui.has("KEEP") && ui.has("CLEAR"), "KEEP and CLEAR are offered for the measurement");
            ui.click("KEEP");
            waitForDrawings(rig, 1);
            ui.shot("the measurement kept");
            rig.check(drawings(rig) == 1, "KEEP puts the measurement among the drawings: " + drawings(rig));
        });
    }

    @Test
    void gcsSearchRefusesABoxNoMastCanCoverAndWorksOnASmallOne() throws Exception {
        AppRig.scenario("maptools-gcs-search").drones(1).run(rig -> {
            Ui ui = rig.ui;
            openTools(ui, "TOOLS");
            Component map = map(ui);
            // no drone has a position: the map shows half of Europe, a box of 150 px is 400 km across
            ui.click("GCS SEARCH");
            ui.drag(map, 120, 120, 270, 270);
            ui.until("the tool to say the box is too big", 5000, () -> ui.sees("Area too big"));
            Ui.pause(1500);
            ui.shot("a box 400 km across");
            rig.check(!ui.sees("Loading terrain"), "nothing is loaded for a box that big (F-022)");
            ui.click("GCS SEARCH");

            goTo(ui, "60.8669, 11.7381", "Go");
            ui.click("GCS SEARCH");
            ui.drag(map, 300, 150, 380, 230);
            ui.until("the search to give its best spot", 90_000, () -> ui.sees("Best:"));
            ui.shot("the best spot for a box of a few hundred metres");
            rig.check(!ui.sees("Area too big"), "a box of a few hundred metres is searched");
        });
    }

    private static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /**
     * A pin and a line, the map's own EXPORT of the box round them (a KML and a picture), CLEAR ALL, and IMPORT of
     * that KML: the pin and the line are back.
     */
    @Test
    void aPinAndALineExportedClearedAndImportedAgain() throws Exception {
        AppRig.scenario("maptools-export-import").drones(1).run(rig -> {
            Ui ui = rig.ui;
            openTools(ui, "TOOLS");
            goTo(ui, "60.8669, 11.7381", "Go and pin");
            Path pins = rig.sandbox.documents().resolve("pins.txt");
            long end = System.currentTimeMillis() + 5000;
            while (!read(pins).contains("Alpha") && System.currentTimeMillis() < end) Ui.pause(100);
            rig.check(read(pins).contains("Alpha"), "\"Go and pin\" leaves the pin Alpha in the pilot's pins file: " + read(pins).strip());
            Component map = map(ui);
            int cx = Ui.edt(map::getWidth) / 2, cy = Ui.edt(map::getHeight) / 2; // the pin is there: GO TO centres the map on it
            ui.click("DRAW");
            Ui.pause(700);
            ui.click("LINE");
            ui.clickAt(map, cx - 60, cy - 50);
            ui.doubleClickAt(map, cx + 60, cy - 50);
            ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
            waitForDrawings(rig, 1);

            ui.click("DATA");
            Ui.pause(700);
            ui.click("EXPORT...");
            ui.drag(map, cx - 110, cy - 90, cx + 110, cy + 90);
            ui.until("the export's save dialog", 10_000, () -> ui.window("Export box as PNG + KML") != null);
            ui.click("Save");
            ui.until("the export to be said saved", 20_000, () -> ui.sees("Saved:") || ui.sees("Export failed"));
            ui.shot("the box exported");
            Path exports = rig.sandbox.documents().resolve("Exports");
            Path kml = null, png = null;
            for (String name : FlyScenario.names(exports)) {
                if (name.endsWith(".kml")) kml = exports.resolve(name);
                if (name.endsWith(".png")) png = exports.resolve(name);
            }
            rig.check(kml != null && png != null, "a KML and a picture are in Exports: " + FlyScenario.names(exports));
            if (kml == null || png == null) return;
            String written = read(kml);
            rig.check(written.contains("Alpha") && written.contains("<LineString"), "the KML holds the pin and the line");
            java.awt.image.BufferedImage picture = javax.imageio.ImageIO.read(png.toFile());
            rig.check(picture != null && picture.getWidth() >= 200 && picture.getHeight() >= 160, "the picture is one, of the box's size: "
                    + (picture == null ? "none" : picture.getWidth() + " x " + picture.getHeight()));
            ui.click("OK");

            ui.click("CLEAR ALL");
            ui.until("CLEAR ALL asks first", 5000, () -> ui.window("Clear all") != null);
            ui.click("OK");
            waitForDrawings(rig, 0);
            rig.check(drawings(rig) == 0 && !read(pins).contains("Alpha"), "CLEAR ALL takes the line and the pin away");

            ui.click("IMPORT...");
            ui.until("the Import KML dialog", 10_000, () -> ui.window("Import KML") != null);
            Window chooser = ui.window("Import KML");
            JTextComponent name = ui.first(JTextComponent.class, f -> SwingUtilities.getWindowAncestor(f) == chooser);
            ui.fill(name, kml.toString());
            ui.click("Open");
            ui.until("the import to be said done", 20_000, () -> ui.sees("imported") || ui.sees("Import failed"));
            ui.shot("the KML imported");
            rig.check(ui.sees("1 drawings and symbols, 1 pins imported"), "IMPORT says what it brought: one line, one pin");
            ui.click("OK");
            waitForDrawings(rig, 1);
            rig.check(drawings(rig) == 1 && read(pins).contains("Alpha"), "the line and the pin are back: " + drawings(rig) + " drawing(s), pins: " + read(pins).strip());
        });
    }

    @Test
    void theToolsOwnLinesAreWhole() throws Exception {
        AppRig.scenario("maptools-lines").drones(1).run(rig -> {
            Ui ui = rig.ui;
            openTools(ui, "TOOLS");
            ui.click("R-AREAS");
            ui.until("the airspace line to say no key is set", 10_000, () -> ui.sees("no openAIP key"));
            ui.shot("R-AREAS without a key");
            JLabel line = ui.first(JLabel.class, l -> l.getText() != null && l.getText().contains("no openAIP key"));
            int[] has = Ui.edt(() -> new int[]{line.getHeight(), line.getPreferredSize().height, line.getWidth(), line.getPreferredSize().width});
            rig.check(has[0] >= has[1], "the line that says why no area shows has the height of its words (F-021): " + has[0] + " of " + has[1]);
            rig.check(has[2] >= has[3], "and their width: " + has[2] + " of " + has[3]);
            ui.click("R-AREAS");

            ui.click("COVERAGE");
            Ui.pause(1200);
            ui.shot("COVERAGE's fields");
            boolean all = false;
            for (javax.swing.JComboBox<?> c : ui.all(javax.swing.JComboBox.class)) {
                if (Ui.edt(() -> c.getItemCount() > 1 && "All".equals(String.valueOf(c.getItemAt(0))) && "Telenor".equals(String.valueOf(c.getItemAt(1))))) all = true;
            }
            rig.check(all, "the network list's first entry is \"All\" (F-023: \"All networks\" read \"All ne...\")");
            ui.click("COVERAGE");

            // GO TO: a position it can't read is said once, however often; one it can read is gone to
            ui.click("GO TO");
            ui.until("the Go to position dialog", 5000, () -> ui.window("Go to position") != null);
            Window dialog = ui.window("Go to position");
            JTextComponent field = ui.first(JTextComponent.class, f -> SwingUtilities.getWindowAncestor(f) == dialog);
            ui.fill(field, "somewhere north");
            ui.click("Go");
            ui.until("the dialog to say it can't read that", 5000, () -> ui.sees("Unreadable position"));
            ui.fill(field, "still nowhere"); // the dialog is put up anew each time, with the same field and line
            ui.click("Go");
            Ui.pause(600);
            ui.until("the dialog to be up again", 5000, () -> ui.window("Go to position") != null && ui.sees("Unreadable position"));
            ui.shot("GO TO with words that are no position, twice");
            JLabel hint = ui.first(JLabel.class, l -> l.getText() != null && l.getText().contains("Unreadable position"));
            String said = Ui.edt(hint::getText);
            rig.check(said.indexOf("Unreadable") == said.lastIndexOf("Unreadable"), "\"Unreadable position\" is said once (F-026): " + said);
            ui.fill(field, "38N 551248 E 93052 N"); // F-016: north of the equator by 93 km, a northing of five digits
            ui.click("Go");
            ui.until("the dialog to close on a position it can read", 5000, () -> ui.window("Go to position") == null);
            ui.shot("gone to a UTM position just north of the equator");
        });
    }
}
