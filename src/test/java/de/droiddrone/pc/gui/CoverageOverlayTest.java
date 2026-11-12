package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.CellCoverage;
import de.droiddrone.pc.map.OpenCellId;
import de.droiddrone.pc.map.TileCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 4G COVERAGE (1.0.44) on the map: drawn while on and the tools' overlays are shown, never in an exported picture. */
class CoverageOverlayTest {
    private static MapView map(Path dir) {
        MapView map = new MapView(new TileCache(dir, "test", false));
        map.setSize(400, 300);
        map.setFollow(false);
        map.centreOn(59.91, 10.73, 13);
        return map;
    }

    /** Every point GOOD over the visible area. */
    private static CellCoverage.Grid allGood(MapView map) {
        double[] area = map.visibleArea();
        byte[] level = new byte[16 * 12];
        Arrays.fill(level, (byte) CellCoverage.GOOD);
        CellCoverage.Grid g = CellCoverage.predict(area, 16, 12, List.of(), 50, OpenCellId.Operator.TELIA, (a, b) -> 0, (a, b) -> false, null);
        return new CellCoverage.Grid(g.x0(), g.y0(), g.x1(), g.y1(), 16, 12, level,
                List.of(new CellCoverage.Site(59.91, 10.73, OpenCellId.Operator.TELIA, 3, true)), 50, OpenCellId.Operator.TELIA, false);
    }

    private static int greenish(BufferedImage img) {
        int n = 0;
        for (int y = 150; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y), r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                if (g > r + 20 && g > b + 20) n++;
            }
        }
        return n;
    }

    @Test
    void theEstimateAndItsLegendShowOnlyWhileOnAndTheToolsAreOut(@TempDir Path dir) {
        MapView map = map(dir);
        int before = greenish(map.snapshot(400, 300));
        map.setCoverage(true, allGood(map), "1 mast, 100 % good");
        assertTrue(map.coverageOn());
        int on = greenish(map.snapshot(400, 300));
        assertTrue(on > before + 10_000, before + " -> " + on);
        assertEquals(before, greenish(map.picture(400, 300)), "not in an exported picture");
        map.setToolOverlaysShown(false);
        assertEquals(before, greenish(map.snapshot(400, 300)), "hidden with the other tools' results");
        map.setToolOverlaysShown(true);
        map.setCoverage(false, null, null);
        assertEquals(before, greenish(map.snapshot(400, 300)));
        assertNull(map.coverage());
    }

    @Test
    void theWindowsHelpers() {
        assertEquals(2, MapWindow.heightIndex("50"));
        assertEquals(3, MapWindow.heightIndex("120"));
        assertEquals(1, MapWindow.heightIndex(""), "the default, 10 m");
        assertEquals(1, MapWindow.heightIndex("77"));
        // 1.0.64 (the pilot: "it seems to reload every time the map is zoomed or moved"): the estimate is made for
        // the view and a margin round it, and serves every view inside that
        double[] view = {59.90, 59.95, 10.70, 10.80};
        double[] done = MapWindow.padded(view);
        assertEquals(59.90 - 0.05 * MapWindow.COVERAGE_PAD, done[0], 1e-9);
        assertEquals(10.80 + 0.10 * MapWindow.COVERAGE_PAD, done[3], 1e-9);
        assertTrue(MapWindow.serves(done, view));
        assertTrue(MapWindow.serves(done, new double[]{59.91, 59.96, 10.72, 10.82}), "moved by a fifth of the view: the same estimate");
        assertTrue(MapWindow.serves(done, new double[]{59.915, 59.94, 10.725, 10.775}), "zoomed in twice: the same estimate");
        assertFalse(MapWindow.serves(done, new double[]{59.93, 59.98, 10.70, 10.80}), "moved out of it");
        assertFalse(MapWindow.serves(done, new double[]{59.85, 60.00, 10.65, 10.85}), "zoomed out");
        assertFalse(MapWindow.serves(done, new double[]{59.92, 59.93, 10.74, 10.76}), "zoomed in five times: a finer one");

        byte[] level = {CellCoverage.GOOD, CellCoverage.GOOD, CellCoverage.WEAK, CellCoverage.UNKNOWN};
        CellCoverage.Grid g = new CellCoverage.Grid(0, 0, 1, 1, 2, 2, level, List.of(), 50, OpenCellId.Operator.ALL, false);
        assertEquals("0 masts, 67 % good, 33 % weak or none  -  no OpenCelliD key", MapWindow.coverageText(g, "no OpenCelliD key"));
        // all green from 40 m up is said to be what it is, with the way to the height where it differs
        byte[] green = {CellCoverage.GOOD, CellCoverage.GOOD, CellCoverage.GOOD, CellCoverage.GOOD};
        String high = MapWindow.coverageText(new CellCoverage.Grid(0, 0, 1, 1, 2, 2, green, List.of(), 50, OpenCellId.Operator.ALL, false), null);
        assertTrue(high.contains("above the trees") && high.contains("10 m"), high);
        String low = MapWindow.coverageText(new CellCoverage.Grid(0, 0, 1, 1, 2, 2, green, List.of(), 10, OpenCellId.Operator.ALL, false), null);
        assertFalse(low.contains("above the trees"), low);
        assertEquals(new int[]{0, 1, 0, 2}.length, g.counts().length);
        assertArrayEquals(new int[]{0, 1, 0, 2}, g.counts());
        // 1.0.64: "move the map for more" - a small move no longer fetches (the estimate serves its padded area)
        String left = MapWindow.boxesLeftText(40);
        assertTrue(left.startsWith("40 boxes not fetched") && !left.contains("move the map"), left);
        assertTrue(left.contains("zoom") && left.contains("away"), left);
    }
}
