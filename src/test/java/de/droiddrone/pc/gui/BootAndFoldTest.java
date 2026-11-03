package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class BootAndFoldTest {
    private static int count(BufferedImage img, int rgb, int minAlpha) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y);
                if ((p >>> 24) >= minAlpha && (p & 0xffffff) == rgb) n++;
            }
        }
        return n;
    }

    /** Opaque and (near) black: a pixel of the cover. */
    private static boolean dark(int argb) {
        return (argb >>> 24) >= 250 && (argb & 0xff) < 0x20 && ((argb >> 8) & 0xff) < 0x20 && ((argb >> 16) & 0xff) < 0x20;
    }

    @Test
    void theBootStartsBlackWithTheMarkInTheMiddleAndEndsClear() {
        Rectangle video = new Rectangle(300, 80, 500, 300);
        BufferedImage start = BootReveal.frame(0, 800, 500, video);
        assertTrue(dark(start.getRGB(5, 5)), "black all over at the start: " + Integer.toHexString(start.getRGB(5, 5)));
        BufferedImage shown = BootReveal.frame(800, 800, 500, video);
        assertTrue(count(shown, Logo.RED.getRGB() & 0xffffff, 200) > 2000, "the mark, red, once faded in: " + count(shown, Logo.RED.getRGB() & 0xffffff, 200));
        assertTrue(dark(shown.getRGB(5, 5)), "still black round it: " + Integer.toHexString(shown.getRGB(5, 5)));
        BufferedImage flying = BootReveal.frame(2800, 800, 500, video);
        int alphaCorner = flying.getRGB(5, 5) >>> 24;
        assertTrue(alphaCorner > 240, "black stays while the pieces fly: " + alphaCorner);
        BufferedImage fading = BootReveal.frame(3800, 800, 500, video);
        int alphaFading = fading.getRGB(5, 5) >>> 24;
        assertTrue(alphaFading > 20 && alphaFading < 235, "the UI fades in after the morph: " + alphaFading);
        BufferedImage end = BootReveal.frame(4170, 800, 500, video);
        assertTrue((end.getRGB(400, 250) >>> 24) < 20, "see-through at the end");
        assertTrue(count(end, Logo.RED.getRGB() & 0xffffff, 200) < 50, "the pieces have faded");
    }

    /**
     * Half-way through the fade-in the mark is one shape at half strength: where the X runs under a bar's end the
     * red is no stronger than on the bar alone, and the dark disc in the middle hides the X's crossing. Piece by
     * piece, each at half alpha, both showed through.
     */
    @Test
    void theMarkFadesInAsOneShape() {
        int w = 800, h = 500;
        BufferedImage half = BootReveal.frame(280, w, h, new Rectangle(300, 80, 500, 300)); // 560 ms fade: alpha 0.5
        double size = Math.min(w, h) * 0.42, s = size / Logo.BOX, lx = (w - size) / 2, ly = (h - size) / 2;
        int overlap = half.getRGB((int) Math.round(lx + 170 * s), (int) Math.round(ly + 160 * s)); // in the left bar and the diagonal
        int bar = half.getRGB((int) Math.round(lx + 158 * s), (int) Math.round(ly + 300 * s)); // the left bar alone
        int redOverlap = (overlap >> 16) & 0xff, redBar = (bar >> 16) & 0xff;
        assertTrue(redBar > 90 && redBar < 160, "the bar at half strength: " + redBar);
        assertTrue(Math.abs(redOverlap - redBar) <= 12, "the X under the bar no stronger than the bar: " + redOverlap + " vs " + redBar);
        int centre = half.getRGB(w / 2, h / 2);
        assertTrue(((centre >> 16) & 0xff) < 40, "the disc hides the X's crossing: " + Integer.toHexString(centre));
    }

    @Test
    void aFoldHidesItsContentsHeight() {
        JPanel content = new JPanel();
        content.add(new JLabel("Fields"));
        content.setPreferredSize(new Dimension(200, 120));
        Fold fold = new Fold(content, true);
        assertEquals(120, fold.getPreferredSize().height);
        fold.setShown(false, false);
        assertEquals(0, fold.getPreferredSize().height);
        assertFalse(fold.isShown());
        fold.setShown(true, false);
        assertEquals(120, fold.getPreferredSize().height);
    }
}
