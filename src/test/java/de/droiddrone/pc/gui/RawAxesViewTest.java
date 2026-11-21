package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The calibration's bars (the test bench's F-034): the tick beside a bar means "counted as a stick". It went by the
 * bar's range alone, the count under them by the range and a smooth travel - a switch flipped in the range step got
 * a tick and was not counted, and four ticks stood beside "Full range: 3 of 4 stick axes".
 */
class RawAxesViewTest {
    private static final int ROW = 30;

    /** Whether the row of this axis has the tick's green to the right of its bar. */
    private static boolean ticked(BufferedImage img, int axis) {
        int ok = Theme.OK.getRGB() & 0xffffff;
        for (int y = axis * ROW; y < (axis + 1) * ROW; y++) {
            for (int x = img.getWidth() - 140; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y) & 0xffffff;
                if (Math.abs((rgb >> 16 & 0xff) - (ok >> 16 & 0xff)) < 40 && Math.abs((rgb >> 8 & 0xff) - (ok >> 8 & 0xff)) < 40
                        && Math.abs((rgb & 0xff) - (ok & 0xff)) < 40) return true;
            }
        }
        return false;
    }

    private static BufferedImage paint(RawAxesView view) {
        view.setSize(560, 8 * ROW + 70);
        BufferedImage img = new BufferedImage(560, 8 * ROW + 70, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        view.paint(g);
        g.dispose();
        return img;
    }

    @Test
    void theTickFollowsTheCountNotTheRangeAlone() {
        // axis 0: a stick swept end to end; axis 5: a switch flipped from one end to the other; axis 1: half way
        float[] axes = new float[8], min = new float[8], max = new float[8], center = new float[8];
        min[0] = -1;
        max[0] = 1;
        min[5] = -1;
        max[5] = 1;
        max[1] = 0.5f;
        RawAxesView view = new RawAxesView();
        view.setData(new RawAxesView.Data(axes, new boolean[16], min, max, center, new String[8], new String[16], -1, -1, 0, true));
        BufferedImage byRange = paint(view);
        assertTrue(ticked(byRange, 0) && ticked(byRange, 5) && !ticked(byRange, 1), "without the wizard's word: by the range, as it was");

        boolean[] swept = new boolean[8];
        swept[0] = true; // the wizard counts axis 0 only: the switch's one jump is no stick
        view.setSwept(swept);
        BufferedImage byCount = paint(view);
        assertTrue(ticked(byCount, 0), "the stick has its tick");
        assertFalse(ticked(byCount, 5), "the flipped switch has none: it is not one of the \"n of 4\"");
        assertFalse(ticked(byCount, 1));
    }
}
