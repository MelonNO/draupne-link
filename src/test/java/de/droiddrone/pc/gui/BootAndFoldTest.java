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

    @Test
    void theBootStartsBlackWithTheMarkInTheMiddleAndEndsClear() {
        Rectangle video = new Rectangle(300, 80, 500, 300);
        BufferedImage start = BootReveal.frame(0, 800, 500, video);
        assertEquals(0xff000000, start.getRGB(5, 5), "black all over at the start");
        BufferedImage shown = BootReveal.frame(400, 800, 500, video);
        assertTrue(count(shown, Logo.RED.getRGB() & 0xffffff, 200) > 2000, "the mark, red, once faded in");
        assertEquals(0xff000000, shown.getRGB(5, 5), "still black round it");
        BufferedImage flying = BootReveal.frame(900, 800, 500, video);
        int alphaCorner = flying.getRGB(5, 5) >>> 24;
        assertTrue(alphaCorner < 200, "the black lifts while the pieces fly: " + alphaCorner);
        BufferedImage end = BootReveal.frame(1495, 800, 500, video);
        assertTrue((end.getRGB(400, 250) >>> 24) < 20, "see-through at the end");
        assertTrue(count(end, Logo.RED.getRGB() & 0xffffff, 200) < 50, "the pieces have faded");
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
