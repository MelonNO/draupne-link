package de.droiddrone.pc.gui;

import de.droiddrone.pc.air.GroupLink;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * Fleet PRD G4 / GU-3: the follower group on the map, from above - the ring of cells round the empty core, the four
 * transit shafts, the keep-out zone round the group's edge (red, the lead must stay out), the leash ring round the
 * centre, and the label: how many follow, the floor. Fixed to the compass, as the group is.
 */
final class MapGroupLayer {
    static final Color CELLS = new Color(0, 150, 57), SHAFT = new Color(0xb8, 0x8a, 0x00), KEEP_OUT = new Color(227, 27, 35);

    private MapGroupLayer() {
    }

    static void paint(Graphics2D g, GroupLink.Picture p, MapProjection proj) {
        if (p == null) return;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            Theme.smooth(g2);
            Point2D c = proj.toScreen(p.lat(), p.lon());
            double mpp = metresPerPixel(proj, p.lat());
            double keep = p.keepOutHalfM() / mpp, edge = p.edgeHalfM() / mpp, leash = p.leashM() / mpp;
            g2.setColor(Theme.alpha(KEEP_OUT, 28));
            g2.fill(new Rectangle2D.Double(c.getX() - keep, c.getY() - keep, keep * 2, keep * 2));
            g2.setColor(KEEP_OUT);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{8f, 6f}, 0f));
            g2.draw(new Rectangle2D.Double(c.getX() - keep, c.getY() - keep, keep * 2, keep * 2));
            g2.setColor(Theme.MUTED);
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{2f, 4f}, 0f));
            g2.draw(new Rectangle2D.Double(c.getX() - edge, c.getY() - edge, edge * 2, edge * 2));
            g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{5f, 5f}, 0f));
            g2.draw(new Ellipse2D.Double(c.getX() - leash, c.getY() - leash, leash * 2, leash * 2));
            double half = p.shaftHalfM() / mpp;
            g2.setStroke(new BasicStroke(1.2f));
            for (double[] s : p.shafts()) {
                Point2D at = proj.toScreen(s[0], s[1]);
                g2.setColor(Theme.alpha(SHAFT, 90));
                g2.fill(new Rectangle2D.Double(at.getX() - half, at.getY() - half, half * 2, half * 2));
                g2.setColor(SHAFT);
                g2.draw(new Rectangle2D.Double(at.getX() - half, at.getY() - half, half * 2, half * 2));
            }
            g2.setColor(CELLS);
            for (double[] cell : p.cells()) {
                Point2D at = proj.toScreen(cell[0], cell[1]);
                g2.fill(new Ellipse2D.Double(at.getX() - 3, at.getY() - 3, 6, 6));
            }
            g2.setFont(Theme.headingFont(12f));
            String label = "GROUP " + p.followers() + " FOLLOW " + (p.lead() == null ? "-" : p.lead()) + "  FLOOR " + Math.round(p.floorM()) + " M";
            g2.setColor(new Color(0, 0, 0, 160));
            int w = g2.getFontMetrics().stringWidth(label);
            g2.fillRect((int) (c.getX() - w / 2.0) - 4, (int) (c.getY() - keep) - 20, w + 8, 18);
            g2.setColor(Color.WHITE);
            g2.drawString(label, (int) (c.getX() - w / 2.0), (int) (c.getY() - keep) - 6);
        } finally {
            g2.dispose();
        }
    }

    /** Metres per screen pixel at this latitude (two points 100 m apart east-west). */
    static double metresPerPixel(MapProjection proj, double lat) {
        double dLon = 100 / (111_320 * Math.cos(Math.toRadians(lat)));
        Point2D a = proj.toScreen(lat, 0), b = proj.toScreen(lat, dLon);
        double px = Math.abs(b.getX() - a.getX());
        return px <= 0 ? 1 : 100 / px;
    }
}
