package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.Airspace;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.List;

/**
 * 1.0.64, R-, P- and D-areas and NOTAMs on the map (MAP TOOLS > TOOLS > AIRSPACE): an outline and a light fill each,
 * dashed for a NOTAM that hasn't started; names and limits from zoom {@link #LABEL_ZOOM}; a click on one shows what it
 * is. Stateless: MapView keeps the areas.
 */
final class MapAirspaceLayer {
    static final int LABEL_ZOOM = 9;
    static final Color RESTRICTED = new Color(225, 45, 45), PROHIBITED = new Color(150, 0, 20), DANGER = new Color(245, 145, 0),
            NOTAM = new Color(215, 70, 210);

    private MapAirspaceLayer() {
    }

    static Color color(Airspace.Kind k) {
        return switch (k) {
            case RESTRICTED -> RESTRICTED;
            case PROHIBITED -> PROHIBITED;
            case DANGER -> DANGER;
            case NOTAM -> NOTAM;
        };
    }

    static Path2D shape(Airspace a, MapProjection proj) {
        Path2D p = new Path2D.Double();
        boolean first = true;
        for (double[] pt : a.ring()) {
            Point2D s = proj.toScreen(pt[0], pt[1]);
            if (first) p.moveTo(s.getX(), s.getY());
            else p.lineTo(s.getX(), s.getY());
            first = false;
        }
        p.closePath();
        return p;
    }

    /** The areas, the larger first so a small one inside a big one stays clickable and seen. */
    static void paint(Graphics2D g, List<Airspace> areas, MapProjection proj) {
        if (areas.isEmpty()) return;
        java.awt.Composite saved = g.getComposite();
        g.setComposite(java.awt.AlphaComposite.SrcOver); // a light fill over the map, whatever the caller blends with
        try {
            paintAreas(g, areas, proj);
        } finally {
            g.setComposite(saved);
        }
    }

    private static void paintAreas(Graphics2D g, List<Airspace> areas, MapProjection proj) {
        java.awt.Rectangle view = new java.awt.Rectangle(-200, -200, proj.w() + 400, proj.h() + 400);
        for (Airspace a : areas) {
            Path2D s = shape(a, proj);
            if (!s.intersects(view)) continue;
            Color c = color(a.kind());
            g.setColor(Theme.alpha(c, a.later() ? 18 : 40));
            g.fill(s);
            g.setColor(new Color(0, 0, 0, 120));
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(s);
            g.setColor(c);
            g.setStroke(a.later() ? new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{8f, 6f}, 0f)
                    : new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(s);
        }
        if (proj.zoom() < LABEL_ZOOM) return;
        g.setFont(Theme.headingFont(10.5f));
        FontMetrics fm = g.getFontMetrics();
        for (Airspace a : areas) {
            double[] mid = a.middle();
            Point2D m = proj.toScreen(mid[0], mid[1]);
            if (m.getX() < 0 || m.getY() < 0 || m.getX() > proj.w() || m.getY() > proj.h()) continue;
            Path2D s = shape(a, proj);
            java.awt.Rectangle b = s.getBounds();
            String l1 = a.name(), l2 = a.limits();
            int tw = Math.max(fm.stringWidth(l1), fm.stringWidth(l2)) + 8;
            if (b.width < tw || b.height < 2 * fm.getHeight() + 4) continue; // too small to label at this zoom
            int x = (int) m.getX() - tw / 2, y = (int) m.getY() - fm.getHeight();
            g.setColor(MapView.BOX);
            g.fillRect(x, y, tw, (l2.isEmpty() ? 1 : 2) * fm.getHeight() + 2);
            g.setColor(color(a.kind()));
            g.drawString(l1, x + 4, y + fm.getAscent());
            if (!l2.isEmpty()) {
                g.setColor(Theme.MUTED);
                g.drawString(l2, x + 4, y + fm.getHeight() + fm.getAscent());
            }
        }
    }

    /** The area at x, y: the smallest of those it lies in (a NOTAM over its D-area is the one meant), or null. */
    static Airspace at(List<Airspace> areas, MapProjection proj, int x, int y) {
        Airspace best = null;
        double bestSize = Double.MAX_VALUE;
        for (Airspace a : areas) {
            Path2D s = shape(a, proj);
            if (!s.contains(x, y)) continue;
            java.awt.Rectangle b = s.getBounds();
            double size = (double) b.width * b.height;
            if (size < bestSize) {
                bestSize = size;
                best = a;
            }
        }
        return best;
    }

    /** What the clicked area is, in a box beside x, y (kept inside the map). */
    static void paintInfo(Graphics2D g, Airspace a, int x, int y, int w, int h) {
        g.setComposite(java.awt.AlphaComposite.SrcOver);
        g.setFont(Theme.font(java.awt.Font.PLAIN, 12f));
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = a.info();
        int tw = 0;
        for (String l : lines) tw = Math.max(tw, fm.stringWidth(l));
        tw += 14;
        int th = lines.size() * fm.getHeight() + 10;
        int bx = Math.min(x + 12, w - tw - 6), by = Math.min(y + 12, h - th - 6);
        bx = Math.max(6, bx);
        by = Math.max(6, by);
        g.setColor(MapView.BOX);
        g.fillRect(bx, by, tw, th);
        g.setColor(color(a.kind()));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRect(bx, by, tw, th);
        for (int i = 0; i < lines.size(); i++) {
            g.setColor(i == 0 ? color(a.kind()) : Theme.TEXT);
            g.drawString(lines.get(i), bx + 7, by + 5 + i * fm.getHeight() + fm.getAscent());
        }
    }
}
