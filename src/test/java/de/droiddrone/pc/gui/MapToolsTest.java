package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.map.Mercator;
import de.droiddrone.pc.map.TileCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** MAP TOOLS (1.0.35): the ruler, the drawings, the symbols and the export box, driven by mouse events like the pilot's. */
class MapToolsTest {
    private static MapView map(Path dir, MapGraphics g) {
        MapView map = new MapView(new TileCache(dir, "test", false));
        map.setSize(400, 300);
        map.setGraphics(g);
        map.setFollow(false);
        map.centreOn(59.91, 10.73, 14);
        return map;
    }

    private static void click(MapView map, int x, int y) {
        click(map, x, y, 1);
    }

    private static void click(MapView map, int x, int y, int count) {
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK, x, y, count, false, MouseEvent.BUTTON1));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_RELEASED, 0, 0, x, y, count, false, MouseEvent.BUTTON1));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_CLICKED, 0, 0, x, y, count, false, MouseEvent.BUTTON1));
    }

    private static void rightClick(MapView map, int x, int y) {
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON3_DOWN_MASK, x, y, 1, true, MouseEvent.BUTTON3));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_RELEASED, 0, 0, x, y, 1, true, MouseEvent.BUTTON3));
    }

    private static void drag(MapView map, int x0, int y0, int x1, int y1) {
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK, x0, y0, 1, false, MouseEvent.BUTTON1));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_DRAGGED, 0, InputEvent.BUTTON1_DOWN_MASK, x1, y1, 1, false, MouseEvent.NOBUTTON));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_RELEASED, 0, 0, x1, y1, 1, false, MouseEvent.BUTTON1));
    }

    private static int count(BufferedImage img, int rgb) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) & 0xffffff) == rgb) n++;
        }
        return n;
    }

    @Test
    void theRulerMeasuresLegsClosesOnItsFirstPointAndCanBeKept(@TempDir Path dir) {
        MapGraphics g = new MapGraphics(null);
        MapView map = map(dir, g);
        int[] changes = {0};
        map.setOnToolChanged(() -> changes[0]++);
        MapGraphics.Item[] told = {null};
        map.setOnMeasurement(m -> told[0] = m);
        map.setTool(MapView.Tool.RULER);
        assertEquals(MapView.Tool.RULER, map.tool());
        click(map, 100, 100);
        click(map, 200, 100);
        click(map, 200, 200);
        assertNull(map.currentMeasurement(), "still drawing");
        click(map, 103, 98); // back at the first point: the loop closes
        MapGraphics.Item m = map.currentMeasurement();
        assertNotNull(m, "a measurement");
        assertEquals(MapView.Tool.NONE, map.tool(), "the tool ended with it");
        assertTrue(m.closed());
        assertEquals(3, m.points().size());
        assertEquals(3, m.legsM().length, "three legs round");
        double[] a = map.toLatLon(100, 100), b = map.toLatLon(200, 100);
        assertEquals(Mercator.distanceM(a[0], a[1], b[0], b[1]), m.legsM()[0], 0.5, "the first leg as the map measures");
        assertTrue(m.areaM2() > 0, "closed: an area");
        assertSame(m, told[0], "the window was told (the elevation profile)");
        assertTrue(count(map.snapshot(400, 300), 0xe31b23) > 100, "the measurement is drawn");
        assertTrue(g.isEmpty(), "not a graphic until KEEP");
        map.keepMeasurement();
        assertEquals(1, g.list().size());
        assertEquals("Measurement 1", g.list().get(0).name());
        assertTrue(g.list().get(0).closed());
        assertNull(map.currentMeasurement());
        assertTrue(changes[0] >= 3, "tool on, tool off, kept: " + changes[0]);

        // Esc with nothing drawn, then a ruler cancelled by right-clicks
        map.setTool(MapView.Tool.RULER);
        click(map, 50, 50);
        click(map, 90, 50);
        rightClick(map, 0, 0); // the last point back
        rightClick(map, 0, 0); // none left: the tool ends
        assertEquals(MapView.Tool.NONE, map.tool());
        assertNull(map.currentMeasurement());
    }

    @Test
    void aLineEndsWithADoubleClickAnArrowWithEnterAndBothAreHit(@TempDir Path dir) {
        MapGraphics g = new MapGraphics(null);
        MapView map = map(dir, g);
        map.setDrawStyle(0x3b82f6, 4, false);
        map.setTool(MapView.Tool.LINE);
        click(map, 50, 50);
        click(map, 150, 60);
        click(map, 250, 200);
        click(map, 250, 200, 2); // the double-click's second click: the line ends at its last point
        assertEquals(MapView.Tool.NONE, map.tool());
        assertEquals(1, g.list().size());
        MapGraphics.Item line = g.list().get(0);
        assertEquals(MapGraphics.Kind.LINE, line.kind());
        assertEquals("Line 1", line.name());
        assertEquals(3, line.points().size());
        assertEquals(0x3b82f6, line.colorRgb());
        assertEquals(4, line.widthPx());
        assertSame(line, map.itemAt(100, 55), "a click near its first leg finds it");
        assertNull(map.itemAt(50, 250), "nothing there");

        map.setTool(MapView.Tool.ARROW);
        click(map, 300, 50);
        click(map, 350, 120);
        map.finishTool(); // Enter
        assertEquals(2, g.list().size());
        assertEquals(MapGraphics.Kind.ARROW, g.list().get(1).kind());
        assertEquals("Arrow 1", g.list().get(1).name());
        assertTrue(count(map.snapshot(400, 300), 0x3b82f6) > 100, "both are drawn in the chosen colour");
    }

    @Test
    void aRectangleIsDraggedASymbolIsPlacedAndTheExportBoxHandsOverItsArea(@TempDir Path dir) {
        MapGraphics g = new MapGraphics(null);
        MapView map = map(dir, g);
        map.setDrawStyle(0x2ecc40, 2, true);
        map.setTool(MapView.Tool.AREA);
        drag(map, 100, 100, 200, 180);
        assertEquals(1, g.list().size());
        MapGraphics.Item box = g.list().get(0);
        assertEquals(MapGraphics.Kind.AREA, box.kind());
        assertEquals("Area 1", box.name());
        assertEquals(4, box.points().size());
        assertTrue(box.filled());
        double[] nw = map.toLatLon(100, 100), se = map.toLatLon(200, 180);
        assertEquals(nw[0], box.points().get(0).lat(), 1e-9);
        assertEquals(se[1], box.points().get(2).lon(), 1e-9);
        assertSame(box, map.itemAt(150, 100), "its top edge");

        map.setSymbolStyle(MapGraphics.UnitType.ARMOUR, MapGraphics.Affiliation.HOSTILE, MapGraphics.Echelon.COMPANY);
        map.setTool(MapView.Tool.SYMBOL);
        click(map, 300, 150);
        assertEquals(2, g.list().size());
        MapGraphics.Item symbol = g.list().get(1);
        assertEquals(MapGraphics.Kind.SYMBOL, symbol.kind());
        assertEquals("Armour 1", symbol.name());
        assertEquals(MapGraphics.Affiliation.HOSTILE, symbol.affiliation());
        assertEquals(MapGraphics.Echelon.COMPANY, symbol.echelon());
        assertSame(symbol, map.itemAt(300, 150));
        assertTrue(count(map.snapshot(400, 300), MapGraphics.Affiliation.HOSTILE.fillRgb) > 50, "the hostile frame's fill is painted");

        double[][] got = {null};
        map.setOnExportArea(a -> got[0] = a);
        map.setTool(MapView.Tool.EXPORT_BOX);
        drag(map, 60, 60, 260, 220);
        assertNotNull(got[0]);
        double[] a = map.toLatLon(60, 60), b = map.toLatLon(260, 220);
        assertEquals(b[0], got[0][0], 1e-9, "south");
        assertEquals(a[1], got[0][2], 1e-9, "west");
        assertEquals(MapView.Tool.NONE, map.tool());
        assertEquals(2, g.list().size(), "exporting draws nothing");
        assertEquals(1, g.inside(got[0][0], got[0][1], got[0][2], got[0][3]).size(), "the rectangle has a point in the box; the symbol at x 300 is outside");
    }

    /** A double-click on a point picks it up, the next click drops it there; the eraser takes an item off; PLACE AT puts a symbol at a position. */
    @Test
    void pointsMoveByDoubleClickTheEraserErasesAndPlaceAtPlaces(@TempDir Path dir) {
        MapGraphics g = new MapGraphics(null);
        MapView map = map(dir, g);
        map.setTool(MapView.Tool.LINE);
        click(map, 100, 100);
        click(map, 200, 100);
        map.finishTool();
        MapGraphics.Item line = g.list().get(0);
        click(map, 200, 100, 2); // the second point, picked up
        assertTrue(map.isMoving());
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_MOVED, 0, 0, 220, 160, 0, false, MouseEvent.NOBUTTON));
        click(map, 220, 160); // dropped
        assertFalse(map.isMoving());
        MapGraphics.Item moved = g.list().get(0);
        assertNotSame(line, moved);
        double[] at = map.toLatLon(220, 160);
        assertEquals(at[0], moved.points().get(1).lat(), 1e-9, "the second point is where it was dropped");
        assertEquals(line.points().get(0), moved.points().get(0), "the first point stayed");
        // a right-click while moving puts the point back
        click(map, 100, 100, 2);
        assertTrue(map.isMoving());
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_MOVED, 0, 0, 50, 50, 0, false, MouseEvent.NOBUTTON));
        rightClick(map, 50, 50);
        assertFalse(map.isMoving());
        assertEquals(moved, g.list().get(0), "unchanged");

        map.setSymbolStyle(MapGraphics.UnitType.ISR_ROTOR, MapGraphics.Affiliation.FRIEND, MapGraphics.Echelon.TEAM);
        map.placeAt(59.95, 10.80);
        assertEquals(2, g.list().size());
        MapGraphics.Item symbol = g.list().get(1);
        assertEquals(MapGraphics.Kind.SYMBOL, symbol.kind());
        assertEquals(MapGraphics.UnitType.ISR_ROTOR, symbol.unit());
        assertEquals(59.95, symbol.points().get(0).lat(), 1e-9);
        assertFalse(map.isFollowing(), "the map went there");
        double[] centre = map.toLatLon(200, 150);
        assertEquals(59.95, centre[0], 1e-4, "centred on the symbol");

        map.setTool(MapView.Tool.ERASER);
        click(map, 200, 150); // the symbol is at the centre now
        assertEquals(1, g.list().size(), "the symbol is erased");
        assertEquals(MapView.Tool.ERASER, map.tool(), "the eraser stays until Esc");
        map.cancelTool();
        assertEquals(MapView.Tool.NONE, map.tool());
    }

    @Test
    void symbolsPaintEveryTypeAndAffiliation() {
        BufferedImage img = new BufferedImage(60, 60, BufferedImage.TYPE_INT_RGB);
        for (MapGraphics.Affiliation a : MapGraphics.Affiliation.values()) {
            for (MapGraphics.UnitType t : MapGraphics.UnitType.values()) {
                java.awt.Graphics2D g = img.createGraphics();
                g.setColor(java.awt.Color.BLACK);
                g.fillRect(0, 0, 60, 60);
                UnitSymbol.paint(g, 30, 30, t, a, MapGraphics.Echelon.PLATOON, "X");
                g.dispose();
                assertTrue(count(img, a.fillRgb) > 40, t + " " + a + " shows its frame");
                assertTrue(UnitSymbol.bounds(30, 30, a).contains(30, 30));
            }
        }
        assertEquals(List.of(), List.of()); // keeps the import used when the lists above change
    }
}
