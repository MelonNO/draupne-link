package de.droiddrone.pc.gui;

import de.droiddrone.pc.route.Route;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.List;

/**
 * Fleet PRD RT-1 / GU-3: a route on the map - the pilot's points numbered and joined, the planned flight line (with
 * the waypoints the planner added over the terrain) thin and dashed beside it - and where a click lands on it: a
 * point (to drag, delete, give its own height) or a leg (to insert a point). Drawn by MapView over the tracks.
 */
final class MapRouteLayer {
    static final Color ROUTE = new Color(0x00b4ff);
    static final Color PLANNED = new Color(0x00b4ff);
    /** A point is hit within this many pixels; a leg within this. */
    static final int POINT_HIT = 9, LEG_HIT = 6;

    /**
     * Where a place is drawn: its longitude taken the short way from the view's centre - a route over the antimeridian
     * (179.99 to -179.99) was drawn and hit-tested across the whole world, its far points off the other side.
     */
    static Point2D at(MapProjection proj, double lat, double lon) {
        double d = lon - proj.centerLon();
        d -= 360 * Math.floor((d + 180) / 360);
        return proj.toScreen(lat, proj.centerLon() + d);
    }

    private MapRouteLayer() {
    }

    static void paint(Graphics2D g, Route route, List<double[]> planned, int selected, MapProjection proj) {
        if (route == null || route.size() == 0) return;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            Theme.smooth(g2);
            if (planned != null && planned.size() >= 2) {
                Path2D line = new Path2D.Double();
                for (int i = 0; i < planned.size(); i++) {
                    Point2D p = at(proj, planned.get(i)[0], planned.get(i)[1]);
                    if (i == 0) line.moveTo(p.getX(), p.getY());
                    else line.lineTo(p.getX(), p.getY());
                }
                g2.setColor(Theme.alpha(PLANNED, 150));
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 5f}, 0f));
                g2.draw(line);
            }
            Path2D legs = new Path2D.Double();
            List<Route.Point> pts = route.points();
            for (int i = 0; i < pts.size(); i++) {
                Point2D p = at(proj, pts.get(i).lat(), pts.get(i).lon());
                if (i == 0) legs.moveTo(p.getX(), p.getY());
                else legs.lineTo(p.getX(), p.getY());
            }
            g2.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(0, 0, 0, 120));
            g2.draw(legs);
            g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(ROUTE);
            g2.draw(legs);
            g2.setFont(Theme.headingFont(11f).deriveFont(Font.BOLD));
            for (int i = 0; i < pts.size(); i++) {
                Point2D p = at(proj, pts.get(i).lat(), pts.get(i).lon());
                int r = i == selected ? 9 : 7;
                g2.setColor(i == selected ? Theme.WARN : ROUTE);
                g2.fillOval((int) p.getX() - r, (int) p.getY() - r, r * 2, r * 2);
                g2.setColor(Color.BLACK);
                String n = String.valueOf(i + 1);
                int w = g2.getFontMetrics().stringWidth(n);
                g2.drawString(n, (int) p.getX() - w / 2, (int) p.getY() + g2.getFontMetrics().getAscent() / 2 - 2);
                if (pts.get(i).hasOwnHeight()) { // its own height beside it
                    g2.setColor(Color.WHITE);
                    g2.drawString(Math.round(pts.get(i).heightM()) + " m", (int) p.getX() + r + 3, (int) p.getY() + 4);
                }
            }
        } finally {
            g2.dispose();
        }
    }

    /** The route point at (x, y), or -1. */
    static int pointAt(Route route, MapProjection proj, int x, int y) {
        if (route == null) return -1;
        int best = -1;
        double bestD = POINT_HIT;
        for (int i = 0; i < route.size(); i++) {
            Point2D p = at(proj, route.point(i).lat(), route.point(i).lon());
            double d = p.distance(x, y);
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** The leg (from point i to i+1) that (x, y) lies on, as i; -1 if none. */
    static int legAt(Route route, MapProjection proj, int x, int y) {
        if (route == null) return -1;
        for (int i = 0; i + 1 < route.size(); i++) {
            Point2D a = at(proj, route.point(i).lat(), route.point(i).lon());
            Point2D b = at(proj, route.point(i + 1).lat(), route.point(i + 1).lon());
            if (java.awt.geom.Line2D.ptSegDist(a.getX(), a.getY(), b.getX(), b.getY(), x, y) <= LEG_HIT) return i;
        }
        return -1;
    }

    /** Fleet RT-14: power lines and masts near the route - a red ring where each comes closest, with its word. */
    static void paintObstacles(Graphics2D g, List<de.droiddrone.pc.route.PowerLines.Near> near, MapProjection proj) {
        g.setFont(Theme.headingFont(10f));
        g.setStroke(new BasicStroke(2f));
        for (de.droiddrone.pc.route.PowerLines.Near n : near) {
            Point2D p = at(proj, n.lat(), n.lon());
            int x = (int) p.getX(), y = (int) p.getY();
            g.setColor(Theme.BAD);
            g.drawOval(x - 7, y - 7, 14, 14);
            g.drawLine(x - 4, y + 4, x + 4, y - 4);
            g.setColor(Theme.TEXT);
            g.drawString(n.kind().word.toUpperCase(java.util.Locale.ROOT), x + 10, y + 4);
        }
        g.setStroke(new BasicStroke(1f));
    }
}
