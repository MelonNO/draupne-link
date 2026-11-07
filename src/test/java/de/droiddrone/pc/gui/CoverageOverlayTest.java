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
        assertEquals(1, MapWindow.heightIndex("50"));
        assertEquals(2, MapWindow.heightIndex("120"));
        assertEquals(1, MapWindow.heightIndex(""), "the default, 50 m");
        assertEquals(1, MapWindow.heightIndex("77"));
        double[] a = {59.90, 59.95, 10.70, 10.80};
        assertTrue(MapWindow.sameView(a, new double[]{59.901, 59.951, 10.701, 10.801}));
        assertFalse(MapWindow.sameView(a, new double[]{59.92, 59.97, 10.70, 10.80}), "moved by 40 % of its height");
        assertFalse(MapWindow.sameView(a, new double[]{59.85, 60.00, 10.65, 10.85}), "zoomed out");

        byte[] level = {CellCoverage.GOOD, CellCoverage.GOOD, CellCoverage.WEAK, CellCoverage.UNKNOWN};
        CellCoverage.Grid g = new CellCoverage.Grid(0, 0, 1, 1, 2, 2, level, List.of(), 50, OpenCellId.Operator.ALL, false);
        assertEquals("0 masts, 67 % good, 33 % weak or none  -  no OpenCelliD key", MapWindow.coverageText(g, "no OpenCelliD key"));
        assertEquals(new int[]{0, 1, 0, 2}.length, g.counts().length);
        assertArrayEquals(new int[]{0, 1, 0, 2}, g.counts());
    }
}
