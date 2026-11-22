package de.droiddrone.pc.gui;

import de.droiddrone.pc.flights.FlightRecord;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * I-006 (the bench's flights-never-armed, 1.0.71): PREVIOUS FLIGHTS with no flight said "No flights yet." - only armed
 * flights are kept - and its REPORT, EXPORT and DELETE kept their red frames and words while they did nothing. A kept
 * flight was ended without a word in the log. Never shown on screen.
 */
class PreviousFlightsEmptyTest {
    @Test
    void noFlightSaysNoArmedFlightsAndItsButtonsLookOff(@TempDir Path dir) throws Exception {
        Theme.install();
        Path tracks = Files.createDirectories(dir.resolve("Tracks")), videos = Files.createDirectories(dir.resolve("Videos")),
                logs = Files.createDirectories(dir.resolve("logs"));
        PreviousFlightsDialog[] holder = new PreviousFlightsDialog[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new PreviousFlightsDialog(null, new TileCache(dir.resolve("tiles"), "test", false),
                TileSource.KARTVERKET_TOPO, f -> { }, tracks, videos, logs));
        PreviousFlightsDialog d = holder[0];
        try {
            d.awaitLoaded();
            List<String> red = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(0, d.list.getModel().getSize());
                assertTrue(texts(d.getContentPane()).contains("No armed flights yet."), "the list's note: " + texts(d.getContentPane()));
                for (JButton b : new JButton[]{d.play, d.report, d.delete}) assertFalse(b.isEnabled(), b.getText() + " off with no flight");
                for (JButton b : new JButton[]{d.report, d.delete}) if (redPixels(b) > 0) red.add(b.getText() + ": " + redPixels(b));
            });
            assertEquals(List.of(), red, "off, nothing of them red (their frame and words grey)");
        } finally {
            SwingUtilities.invokeAndWait(d::dispose);
        }
    }

    @Test
    void aKeptFlightSaysSoInOneLineWithItsLength() {
        assertEquals("Flight kept, 0:10 - MAP TOOLS > DATA > PREVIOUS FLIGHTS", MainWindow.keptLine(10_400, FlightRecord.DISCONNECT));
        assertEquals("Flight kept, 12:03, ended by LINK LOST - MAP TOOLS > DATA > PREVIOUS FLIGHTS",
                MainWindow.keptLine(723_000, FlightRecord.LINK_LOST));
        assertEquals("Flight kept, 1:00:00, ended by LOST DRONE - MAP TOOLS > DATA > PREVIOUS FLIGHTS",
                MainWindow.keptLine(3_600_000, FlightRecord.LOST_DRONE));
    }

    private static List<String> texts(java.awt.Container c) {
        List<String> out = new ArrayList<>();
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof javax.swing.JLabel l && l.isVisible()) out.add(l.getText());
            if (k instanceof java.awt.Container x) out.addAll(texts(x));
        }
        return out;
    }

    /** Pixels of the app's red (ACCENT) in the button as painted at its own size. */
    private static int redPixels(JButton b) {
        b.setSize(b.getPreferredSize());
        b.doLayout();
        BufferedImage img = new BufferedImage(b.getWidth(), b.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        b.paint(g);
        g.dispose();
        Color accent = Theme.ACCENT;
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = rgb >> 16 & 0xff, gr = rgb >> 8 & 0xff, bl = rgb & 0xff;
                if (r > 150 && r > 3 * gr && r > 3 * bl && Math.abs(r - accent.getRed()) < 80) n++;
            }
        }
        return n;
    }
}
