package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.map.Mercator;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.Locale;

/**
 * What the pilot draws on the map (MAP TOOLS): lines, arrows, rectangles, unit symbols and the ruler's measurement with
 * each leg's length and bearing. MapView decides which items and in what order; this draws one.
 */
final class MapGraphicsLayer {
    private MapGraphicsLayer() {
    }

    static void paintItem(Graphics2D g, MapGraphics.Item it, boolean live, MapProjection proj) {
        List<MapGraphics.LatLon> pts = it.points();
        if (pts.isEmpty()) return;
        Point2D[] sp = new Point2D[pts.size()];
        boolean anyNear = false;
        for (int i = 0; i < pts.size(); i++) {
            sp[i] = proj.toScreen(pts.get(i).lat(), pts.get(i).lon());
            if (sp[i].getX() > -2000 && sp[i].getX() < proj.w() + 2000 && sp[i].getY() > -2000 && sp[i].getY() < proj.h() + 2000) anyNear = true;
        }
        if (!anyNear) return;
        if (it.kind() == MapGraphics.Kind.SYMBOL) {
            UnitSymbol.paint(g, (int) Math.round(sp[0].getX()), (int) Math.round(sp[0].getY()), it.unit(), it.affiliation(), it.echelon(), it.name());
            return;
        }
        boolean around = it.closed() || it.kind() == MapGraphics.Kind.AREA;
        Path2D path = new Path2D.Double();
        path.moveTo(sp[0].getX(), sp[0].getY());
        for (int i = 1; i < sp.length; i++) path.lineTo(sp[i].getX(), sp[i].getY());
        if (around && sp.length > 2) path.closePath();
        boolean measure = it.kind() == MapGraphics.Kind.MEASUREMENT;
        Color colour = measure ? Theme.ACCENT : new Color(it.colorRgb());
        float width = measure ? 2.5f : it.widthPx();
        if (it.kind() == MapGraphics.Kind.AREA && it.filled() && sp.length > 2) {
            g.setColor(Theme.alpha(colour, 70));
            g.fill(path);
        }
        g.setColor(new Color(0, 0, 0, 150));
        g.setStroke(new BasicStroke(width + 3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);
        g.setColor(colour);
        g.setStroke(measure ? new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{8f, 5f}, 0f)
                : new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);
        if (it.kind() == MapGraphics.Kind.ARROW && sp.length >= 2) arrowHead(g, sp[sp.length - 2], sp[sp.length - 1], colour, width);
        if (measure) {
            for (Point2D p : sp) {
                g.setColor(Color.BLACK);
                g.fillOval((int) p.getX() - 5, (int) p.getY() - 5, 10, 10);
                g.setColor(Color.WHITE);
                g.fillOval((int) p.getX() - 3, (int) p.getY() - 3, 6, 6);
            }
            double[] legs = it.legsM();
            g.setFont(Theme.headingFont(10.5f));
            for (int i = 0; i < legs.length; i++) {
                MapGraphics.LatLon a = pts.get(i), b = pts.get((i + 1) % pts.size());
                Point2D sa = sp[i], sb = sp[(i + 1) % sp.length];
                if (sa.distance(sb) < 40) continue; // too short for a label of its own
                String text = MapView.distanceText(legs[i]) + "  " + Mercator.bearingDeg(a.lat(), a.lon(), b.lat(), b.lon()) + "\u00b0";
                label(g, text, (int) ((sa.getX() + sb.getX()) / 2), (int) ((sa.getY() + sb.getY()) / 2) - 9, Theme.TEXT);
            }
            String total = (live ? "" : "TOTAL ") + MapView.distanceText(it.lengthM()) + (around ? "   AREA " + MapGraphics.areaText(it.areaM2()) : "");
            Point2D end = around ? centre(sp) : sp[sp.length - 1];
            g.setFont(Theme.headingFont(12f));
            label(g, total, (int) end.getX(), (int) end.getY() + (around ? 24 : 18), Theme.WARN); // under the centre: clear of a leg's label
            if (!live && !it.name().isEmpty() && !it.name().equals("Measurement")) {
                g.setFont(Theme.headingFont(10.5f));
                label(g, it.name().toUpperCase(Locale.ROOT), (int) sp[0].getX(), (int) sp[0].getY() - 14, Theme.MUTED);
            }
        } else if (!live && !it.name().isEmpty()) {
            g.setFont(Theme.headingFont(10.5f));
            Point2D at = around ? centre(sp) : sp[0];
            label(g, it.name().toUpperCase(Locale.ROOT), (int) at.getX(), (int) at.getY() - (around ? 0 : 14), new Color(it.colorRgb()));
        }
    }

    private static Point2D centre(Point2D[] sp) {
        double x = 0, y = 0;
        for (Point2D p : sp) {
            x += p.getX();
            y += p.getY();
        }
        return new Point2D.Double(x / sp.length, y / sp.length);
    }

    private static void arrowHead(Graphics2D g, Point2D from, Point2D to, Color colour, float width) {
        double angle = Math.atan2(to.getY() - from.getY(), to.getX() - from.getX());
        double size = 9 + width * 2;
        Path2D head = new Path2D.Double();
        head.moveTo(to.getX(), to.getY());
        head.lineTo(to.getX() - size * Math.cos(angle - 0.5), to.getY() - size * Math.sin(angle - 0.5));
        head.lineTo(to.getX() - size * Math.cos(angle + 0.5), to.getY() - size * Math.sin(angle + 0.5));
        head.closePath();
        g.setColor(new Color(0, 0, 0, 150));
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(head);
        g.setColor(colour);
        g.fill(head);
    }

    /** A short text in a dark box, centred on x, with its baseline at y. */
    private static void label(Graphics2D g, String text, int x, int y, Color colour) {
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text) + 10;
        g.setColor(MapView.BOX);
        g.fillRect(x - tw / 2, y - fm.getAscent() - 2, tw, fm.getHeight() + 4);
        g.setColor(colour);
        g.drawString(text, x - tw / 2 + 5, y);
    }
}
