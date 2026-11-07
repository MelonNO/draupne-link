package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/** M8 (1.0.49): the OSD's fonts are made once, not about 30 a paint. */
class OsdFontsTest {
    @Test
    void aFontIsMadeOnceForEachSize() {
        assertSame(Theme.headingFont(14.5f), Theme.headingFont(14.5f));
        assertSame(Theme.monoFont(Font.BOLD, 18f), Theme.monoFont(Font.BOLD, 18f));
        assertNotSame(Theme.monoFont(Font.BOLD, 18f), Theme.monoFont(Font.PLAIN, 18f));
        assertNotSame(Theme.font(Font.PLAIN, 18f), Theme.monoFont(Font.PLAIN, 18f));
        assertEquals(18f, Theme.monoFont(Font.PLAIN, 18f).getSize2D());
        for (int i = 0; i < Theme.FONT_CACHE * 3; i++) Theme.headingFont(10f + i / 100f); // a window resized step by step
        assertSame(Theme.headingFont(14.5f), Theme.headingFont(14.5f), "still right after many sizes (the oldest went)");
    }

    /** The time to paint the whole HUD on a 1280x720 picture, as the screen does: printed, and well inside a frame. */
    @Test
    void theHudPaintsWellInsideAFrame() {
        OsdRenderer.Data d = new OsdPreview().data();
        OsdLayout layout = OsdLayout.defaults();
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        OsdRenderer osd = new OsdRenderer();
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 40; i++) {
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                osd.paint(g, 0, 0, 1280, 720, d, layout);
                g.dispose();
            }
            best = Math.min(best, (System.nanoTime() - t0) / 40);
        }
        System.out.printf("OSD paint at 1280x720: %.2f ms (fonts cached)%n", best / 1e6);
        assertTrue(best < 16_000_000, "a paint must leave room in a 60 fps frame: " + best / 1e6 + " ms");
    }
}
