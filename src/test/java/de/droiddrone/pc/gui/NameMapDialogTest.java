package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * I-009 (the bench's menus-windows, 1.0.71): NAME MAP's list stood empty with nothing said. It says
 * "No phones yet - open DRAUPNE Flight"; the button that asks every device again is SCAN AGAIN. Never shown on screen.
 */
class NameMapDialogTest {
    @Test
    void anEmptyListSaysWhy() {
        Theme.install();
        assertEquals("No phones yet - open DRAUPNE Flight", NameMapDialog.EMPTY);
        JTable table = new JTable(0, 6);
        table.setSize(600, 160);
        BufferedImage img = new BufferedImage(600, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        NameMapDialog.emptyNote(table, g);
        g.dispose();
        int x0 = Integer.MAX_VALUE, x1 = -1, lit = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xffffff) == 0) continue;
                lit++;
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
            }
        }
        assertTrue(lit > 100, "the words are drawn: " + lit);
        assertTrue(x0 > 20 && x1 < 580 && Math.abs((x0 + x1) / 2 - 300) < 20, "in the middle, whole: " + x0 + " - " + x1);
    }
}
