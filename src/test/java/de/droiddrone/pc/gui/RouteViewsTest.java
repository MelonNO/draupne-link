package de.droiddrone.pc.gui;

import de.droiddrone.pc.route.Route;
import de.droiddrone.pc.route.RoutePlanner;
import de.droiddrone.pc.route.RouteProfile;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet PRD RT-7 / RT-15: the route's profile chart and settings, off screen. */
class RouteViewsTest {
    private static RouteProfile profile() throws Exception {
        double lat0 = 60, lon0 = 10, mPerLon = 111_320 * Math.cos(Math.toRadians(60));
        Route r = new Route("T");
        r.add(lat0, lon0 + 1000 / mPerLon);
        RoutePlanner.Plan p = RoutePlanner.plan(r, new double[]{lat0, lon0}, true,
                (lat, lon) -> 100 + 150 * Math.exp(-Math.pow(((lon - lon0) * mPerLon - 500) / 60, 2)), RoutePlanner.Limits.ARDUCOPTER_DEFAULT);
        return RouteProfile.of(p, RouteProfile.Battery.DEFAULT, (lat, lon, agl) -> (lon - lon0) * mPerLon < 700);
    }

    private static int lit(BufferedImage img, java.awt.Color c) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) & 0xffffff) == (c.getRGB() & 0xffffff)) n++;
        return n;
    }

    @Test
    void theProfilePaintsTheGroundTheLineAndTheNote() throws Exception {
        BufferedImage img = new BufferedImage(480, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        RouteProfileView.paint(g, profile(), img.getWidth(), img.getHeight());
        g.dispose();
        assertTrue(lit(img, Theme.OK) > 100, "the line flown");
        assertTrue(lit(img, Theme.PANEL_2) > 1000, "the ground filled");
        BufferedImage empty = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D e = empty.createGraphics();
        RouteProfileView.paint(e, null, 200, 100);
        e.dispose();
        assertEquals(0, lit(empty, Theme.OK));
        RouteProfileView.paint(img.createGraphics(), profile(), 20, 20); // too small: nothing, no throw
    }

    @Test
    void theSettingsEditTheRouteAndSayWhatChanged() throws Exception {
        RouteSettingsPanel panel = new RouteSettingsPanel();
        Route r = new Route("T");
        r.add(60, 10);
        r.add(60.01, 10);
        List<String> said = new ArrayList<>();
        panel.setListener(new RouteSettingsPanel.Listener() {
            public void heightChanged(double h) {
                said.add("height " + h);
            }

            public void smoothingChanged(double s) {
                said.add("smoothing " + s);
            }

            public void pointHeightChanged(int i, double h) {
                said.add("point " + i + " " + h);
            }
        });
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            panel.setRoute(r);
            assertTrue(said.isEmpty(), "showing a route says nothing");
            spinner(panel, 0).setValue(80.0);
            spinner(panel, 1).setValue(9.0);
            panel.selectPoint(1);
            spinner(panel, 2).setValue(120.0);
        });
        assertEquals(80, r.heightM());
        assertEquals(9, r.smoothingM());
        assertEquals(120, r.heightAt(1));
        assertEquals(List.of("height 80.0", "smoothing 9.0", "point 1 120.0"), said);
    }

    /** The panel's spinners in order: height, smoothing, the point's height. */
    private static javax.swing.JSpinner spinner(java.awt.Container c, int index) {
        List<javax.swing.JSpinner> found = new ArrayList<>();
        collect(c, found);
        return found.get(index);
    }

    private static void collect(java.awt.Container c, List<javax.swing.JSpinner> out) {
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof javax.swing.JSpinner s) out.add(s);
            else if (k instanceof java.awt.Container sub) collect(sub, out);
        }
    }
}
