package de.droiddrone.pc.gui;

import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.fleet.Fleet;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/** The virtual fleet's panel lays out its drones' cards and paints off screen (no window). */
class FleetPanelTest {
    @Test
    void thePanelShowsACardPerDrone() throws Exception {
        try (Fleet fleet = new Fleet(PcConfig.defaults(), null, s -> { })) {
            for (int i = 0; i < 2; i++) {
                double[] at = fleet.nextPlace(Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON);
                fleet.add(fleet.freeName(), at[0], at[1], 0);
            }
            FleetPanel panel = new FleetPanel(fleet);
            JPanel content = panel.content();
            content.setSize(1180, 860);
            layout(content);
            BufferedImage img = new BufferedImage(1180, 860, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            content.paint(g);
            g.dispose();
            String dump = texts(content, new StringBuilder()).toString();
            assertTrue(dump.contains("MobileZulu  VIRTUAL"), dump);
            assertTrue(dump.contains("MobileYankee  VIRTUAL"), dump);
            assertTrue(dump.contains("127.0.0.11:"), "its address: " + dump);
            // for a look at the layout: build/test-work/fleet-panel.png (the tests' working folder)
            javax.imageio.ImageIO.write(img, "png", new java.io.File("fleet-panel.png"));
        }
    }

    private static void layout(java.awt.Component c) {
        c.doLayout();
        if (c instanceof java.awt.Container k) for (java.awt.Component child : k.getComponents()) layout(child);
    }

    private static StringBuilder texts(java.awt.Component c, StringBuilder sb) {
        if (c instanceof javax.swing.JLabel l) sb.append(l.getText()).append('\n');
        if (c instanceof java.awt.Container k) for (java.awt.Component child : k.getComponents()) texts(child, sb);
        return sb;
    }
}
