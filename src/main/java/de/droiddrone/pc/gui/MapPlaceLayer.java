package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Mercator;
import de.droiddrone.pc.map.Pins;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.Locale;

/** The fixed places on the map: the launch point with its range rings, and the pilot's pins with the way to each. */
final class MapPlaceLayer {
    private static final int[] RING_M = {50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000};

    private MapPlaceLayer() {
    }

    static void paintRings(Graphics2D g, FlightTrack.Point home, MapProjection proj) {
        Point2D c = proj.toScreen(home.lat(), home.lon());
        double mpp = Mercator.metersPerPixel(home.lat(), proj.zoom());
        g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 6f}, 0f));
        g.setFont(Theme.headingFont(10.5f));
        int shown = 0;
        for (int m : RING_M) {
            double r = m / mpp;
            if (r < 36 || r > Math.max(proj.w(), proj.h()) * 1.2) continue;
            g.setColor(new Color(255, 255, 255, 130));
            g.draw(new Ellipse2D.Double(c.getX() - r, c.getY() - r, 2 * r, 2 * r));
            String label = m >= 1000 ? (m / 1000) + " KM" : m + " M";
            FontMetrics fm = g.getFontMetrics();
            int lx = (int) c.getX() - fm.stringWidth(label) / 2, ly = (int) (c.getY() - r) - 4;
            g.setColor(MapView.BOX);
            g.fillRect(lx - 4, ly - fm.getAscent(), fm.stringWidth(label) + 8, fm.getHeight());
            g.setColor(new Color(255, 255, 255, 200));
            g.drawString(label, lx, ly);
            if (++shown == 3) break;
        }
    }

    static void paintHome(Graphics2D g, FlightTrack.Point home, MapProjection proj) {
        Point2D c = proj.toScreen(home.lat(), home.lon());
        double r = 10;
        g.setColor(new Color(0, 0, 0, 170));
        g.fill(new Ellipse2D.Double(c.getX() - r, c.getY() - r, 2 * r, 2 * r));
        g.setStroke(new BasicStroke(2f));
        g.setColor(Color.WHITE);
        g.draw(new Ellipse2D.Double(c.getX() - r, c.getY() - r, 2 * r, 2 * r));
        g.setFont(Theme.headingFont(12f));
        FontMetrics fm = g.getFontMetrics();
        g.drawString("L", (float) c.getX() - fm.stringWidth("L") / 2f, (float) c.getY() + fm.getAscent() / 2f - 1.5f);
    }

    /** "1.25 KM  045°": the way from a place to a pin. */
    static String wayText(double fromLat, double fromLon, Pins.Pin p) {
        return MapView.distanceText(Mercator.distanceM(fromLat, fromLon, p.lat(), p.lon()))
                + "  " + String.format(Locale.ROOT, "%03d°", Mercator.bearingDeg(fromLat, fromLon, p.lat(), p.lon()));
    }

    /**
     * A pin: a diamond with its name, and the distance and bearing to it from the drone's last position (D) and from
     * the launch point (L), for the one who walks there.
     */
    static void paintPins(Graphics2D g, List<Pins.Pin> all, FlightTrack.Point last, FlightTrack.Point home, MapProjection proj) {
        for (Pins.Pin p : all) {
            Point2D c = proj.toScreen(p.lat(), p.lon());
            if (c.getX() < -200 || c.getX() > proj.w() + 200 || c.getY() < -200 || c.getY() > proj.h() + 200) continue;
            Path2D d = new Path2D.Double();
            d.moveTo(c.getX(), c.getY() - 9);
            d.lineTo(c.getX() + 7, c.getY());
            d.lineTo(c.getX(), c.getY() + 9);
            d.lineTo(c.getX() - 7, c.getY());
            d.closePath();
            g.setColor(new Color(0, 0, 0, 150));
            g.setStroke(new BasicStroke(4f));
            g.draw(d);
            g.setColor(Theme.WARN);
            g.fill(d);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.4f));
            g.draw(d);
            String line1 = p.name().toUpperCase(Locale.ROOT);
            String line2 = last == null ? null : (home == null ? "" : "D  ") + wayText(last.lat(), last.lon(), p);
            String line3 = home == null ? null : "L  " + wayText(home.lat(), home.lon(), p);
            g.setFont(Theme.headingFont(11f));
            FontMetrics fm = g.getFontMetrics();
            int lines = 1 + (line2 == null ? 0 : 1) + (line3 == null ? 0 : 1);
            int tw = Math.max(fm.stringWidth(line1), Math.max(line2 == null ? 0 : fm.stringWidth(line2), line3 == null ? 0 : fm.stringWidth(line3))) + 12;
            int th = fm.getHeight() * lines + 6;
            int x = (int) c.getX() + 11, y = (int) c.getY() - th / 2;
            g.setColor(MapView.BOX);
            g.fillRect(x, y, tw, th);
            g.setColor(Theme.WARN);
            g.drawString(line1, x + 6, y + 3 + fm.getAscent());
            int ly = y + 3 + fm.getAscent() + fm.getHeight();
            if (line2 != null) {
                g.setColor(Theme.TEXT);
                g.drawString(line2, x + 6, ly);
                ly += fm.getHeight();
            }
            if (line3 != null) {
                g.setColor(Theme.MUTED);
                g.drawString(line3, x + 6, ly);
            }
        }
    }
}
