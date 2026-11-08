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
                fleet.add(fleet.freeName(), at[0], at[1], at[2]);
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
            // the pilot's start place, shown as MGRS
            assertTrue(dump.contains("Start place " + Fleet.DEFAULT_PLACE), dump); // 1.0.52: the tour's launch point
            // for a look at the layout: build/test-work/fleet-panel.png (the tests' working folder)
            javax.imageio.ImageIO.write(img, "png", new java.io.File("fleet-panel.png"));
        }
    }

    /** LOOK (1.0.52, the pilot): degrees, or a grid the drone looks at. */
    @Test
    void lookTakesDegreesOrAGrid() {
        double[] from = de.droiddrone.pc.map.Coordinates.parse("32V PN 48978 50762");
        assertEquals(90.0, FleetPanel.lookHeading("90", from), 1e-9);
        assertEquals(270.0, FleetPanel.lookHeading("-90", from), 1e-9);
        assertEquals(45.0, FleetPanel.lookHeading("405", from), 1e-9);
        double toGrid = FleetPanel.lookHeading("32V PN 49199 50956", from);
        assertTrue(toGrid > 30 && toGrid < 60, "north-east: " + toGrid);
        assertNull(FleetPanel.lookHeading("somewhere", from));
        assertNull(FleetPanel.lookHeading("", from));
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
