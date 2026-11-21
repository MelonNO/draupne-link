package de.droiddrone.pc.gui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A switch row of MENU under the mouse (the row turns red): its switch and its ON / OFF can still be read. Seen in
 * the bench's pictures of 2026-11-20: "OFF" stood grey on red, and a switch that is on - red - on a red row.
 */
class MenuRowLitTest {
    @BeforeAll
    static void theme() {
        Theme.install();
    }

    private static double luminance(Color c) {
        double[] v = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    private static double contrast(Color a, Color b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** The row painted, under the mouse or not: {its ground, the switch's track away from the knob, the brightest of the word}. */
    private static Color[] painted(boolean on, boolean lit) throws Exception {
        Color[] out = new Color[3];
        SwingUtilities.invokeAndWait(() -> {
            MenuRows.SwitchRow row = new MenuRows.SwitchRow("Record video", "Every armed flight, to Videos", on, b -> { });
            row.setSize(row.getPreferredSize());
            row.getModel().setArmed(lit);
            BufferedImage img = new BufferedImage(row.getWidth(), row.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            row.paint(g);
            g.dispose();
            int w = row.getWidth(), h = row.getHeight();
            int trackX = w - 14 - 40, midY = h / 2;
            out[0] = new Color(img.getRGB(trackX + 20, (h - 20) / 2 - 5)); // just above the switch
            out[1] = new Color(img.getRGB(on ? trackX + 8 : trackX + 32, midY)); // the side of the track the knob is not on
            Color best = out[0];
            for (int x = trackX - 40; x < trackX - 6; x++) { // where ON / OFF is written, left of the switch
                for (int y = midY - 7; y <= midY + 7; y++) {
                    Color c = new Color(img.getRGB(x, y));
                    if (contrast(c, out[0]) > contrast(best, out[0])) best = c;
                }
            }
            out[2] = best;
        });
        return out;
    }

    @Test
    void onTheRedRowTheSwitchAndItsWordCanBeRead() throws Exception {
        for (boolean on : new boolean[]{false, true}) {
            Color[] lit = painted(on, true);
            assertTrue(contrast(lit[0], Theme.ACCENT) < 1.3, "the row under the mouse is red: #" + Integer.toHexString(lit[0].getRGB() & 0xffffff));
            assertTrue(contrast(lit[1], lit[0]) >= 3, (on ? "ON" : "OFF") + ": the switch stands out from the red row, " + contrast(lit[1], lit[0]) + " to 1");
            assertTrue(contrast(lit[2], lit[0]) >= 2.5, (on ? "ON" : "OFF") + ": the word can be read on the red row, " + contrast(lit[2], lit[0]) + " to 1");
            Color[] plain = painted(on, false);
            assertTrue(contrast(plain[2], plain[0]) >= 2.5, (on ? "ON" : "OFF") + ": and on the row as it lies, " + contrast(plain[2], plain[0]) + " to 1");
        }
    }
}
