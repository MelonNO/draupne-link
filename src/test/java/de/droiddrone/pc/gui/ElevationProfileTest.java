package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/** The ruler's elevation profile: what it knows while the heights come in, and that any state paints. */
class ElevationProfileTest {
    @Test
    void theRangeAndCountTakeTheKnownHeightsOnly() {
        ElevationProfileView v = new ElevationProfileView();
        assertNull(v.range());
        assertEquals(0, v.samples());
        v.set(new double[]{0, 50, 100, 150}, new double[]{120, Double.NaN, 95, 140});
        assertEquals(4, v.samples());
        assertEquals(3, v.known());
        assertArrayEquals(new double[]{95, 140}, v.range(), 1e-9);
        v.set(null, null);
        assertEquals(0, v.samples(), "nothing measured");
    }

    @Test
    void everyStatePaintsWithoutTrouble() {
        ElevationProfileView v = new ElevationProfileView();
        v.setSize(300, 132);
        double[][][] cases = {
                {{}, {}},
                {{0, 100}, {Double.NaN, Double.NaN}}, // looking up the terrain
                {{0, 100, 200}, {100, 100, 100}}, // flat
                {{0, 100, 200, 300}, {50, Double.NaN, Double.NaN, 70}}, // a gap
                {{0, 0}, {10, 12}}, // no length
        };
        for (double[][] c : cases) {
            v.set(c[0], c[1]);
            BufferedImage img = new BufferedImage(300, 132, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            v.paint(g);
            g.dispose();
        }
        v.setSize(10, 10); // too small to draw: nothing, no exception
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        v.paint(g);
        g.dispose();
    }
}
