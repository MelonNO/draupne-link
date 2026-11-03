package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.MapGraphics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.QuadCurve2D;
import java.awt.geom.Rectangle2D;
import java.util.Locale;

/**
 * A NATO unit symbol on the map (1.0.35), APP-6 / MIL-STD-2525 style: the frame by affiliation (friend a rectangle,
 * hostile a diamond, neutral a square, unknown a quatrefoil, each in the standard's light colour), the unit type as
 * the icon inside, the echelon as the mark above, the name under it. The sizes are the standard's proportions and
 * stay the same on screen whatever the zoom.
 */
final class UnitSymbol {
    private static final Color BOX = new Color(10, 10, 10, 215);

    private UnitSymbol() {
    }

    /** The frame's bounds on screen for a symbol centred at (cx, cy): the hit area. */
    static Rectangle bounds(int cx, int cy, MapGraphics.Affiliation affiliation) {
        return switch (affiliation) {
            case FRIEND -> new Rectangle(cx - 18, cy - 12, 36, 24);
            case HOSTILE -> new Rectangle(cx - 17, cy - 17, 34, 34);
            case NEUTRAL -> new Rectangle(cx - 14, cy - 14, 28, 28);
            case UNKNOWN -> new Rectangle(cx - 17, cy - 16, 34, 32);
        };
    }

    private static Shape frame(int cx, int cy, MapGraphics.Affiliation affiliation) {
        Rectangle b = bounds(cx, cy, affiliation);
        return switch (affiliation) {
            case FRIEND, NEUTRAL -> b;
            case HOSTILE -> {
                Path2D d = new Path2D.Double();
                d.moveTo(cx, b.y);
                d.lineTo(b.x + b.width, cy);
                d.lineTo(cx, b.y + b.height);
                d.lineTo(b.x, cy);
                d.closePath();
                yield d;
            }
            case UNKNOWN -> { // four lobes round a square
                Area a = new Area(new Rectangle2D.Double(cx - 9, cy - 8, 18, 16));
                a.add(new Area(new Ellipse2D.Double(cx - 17, cy - 8, 16, 16)));
                a.add(new Area(new Ellipse2D.Double(cx + 1, cy - 8, 16, 16)));
                a.add(new Area(new Ellipse2D.Double(cx - 8, cy - 16, 16, 16)));
                a.add(new Area(new Ellipse2D.Double(cx - 8, cy, 16, 16)));
                yield a;
            }
        };
    }

    /** The box inside the frame the icon is drawn in. */
    private static Rectangle inner(int cx, int cy, MapGraphics.Affiliation affiliation) {
        return switch (affiliation) {
            case FRIEND -> new Rectangle(cx - 15, cy - 9, 30, 18);
            case HOSTILE -> new Rectangle(cx - 10, cy - 8, 20, 16);
            case NEUTRAL -> new Rectangle(cx - 11, cy - 11, 22, 22);
            case UNKNOWN -> new Rectangle(cx - 10, cy - 8, 20, 16);
        };
    }

    static void paint(Graphics2D g, int cx, int cy, MapGraphics.UnitType unit, MapGraphics.Affiliation affiliation,
                      MapGraphics.Echelon echelon, String name) {
        Shape frame = frame(cx, cy, affiliation);
        g.setColor(new Color(0, 0, 0, 120));
        g.setStroke(new BasicStroke(4f));
        g.draw(frame);
        g.setColor(new Color(affiliation.fillRgb));
        g.fill(frame);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1.6f));
        g.draw(frame);
        icon(g, inner(cx, cy, affiliation), unit);
        Rectangle b = bounds(cx, cy, affiliation);
        if (unit == MapGraphics.UnitType.HQ) { // the headquarters' staff: a line down from the frame's left bottom corner
            g.setStroke(new BasicStroke(1.6f));
            g.drawLine(b.x, b.y + b.height, b.x, b.y + b.height + 16);
        }
        g.setFont(Theme.headingFont(11f));
        FontMetrics fm = g.getFontMetrics();
        if (echelon != null) {
            String mark = echelon.mark;
            int tw = fm.stringWidth(mark);
            g.setColor(Color.WHITE);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) g.drawString(mark, cx - tw / 2 + dx, b.y - 3 + dy);
            g.setColor(Color.BLACK);
            g.drawString(mark, cx - tw / 2, b.y - 3);
        }
        if (name != null && !name.isEmpty()) {
            String text = name.toUpperCase(Locale.ROOT);
            int tw = fm.stringWidth(text) + 10;
            int x = cx - tw / 2, y = b.y + b.height + (unit == MapGraphics.UnitType.HQ ? 18 : 4);
            g.setColor(BOX);
            g.fillRect(x, y, tw, fm.getHeight() + 2);
            g.setColor(Theme.TEXT);
            g.drawString(text, x + 5, y + 1 + fm.getAscent());
        }
    }

    private static void icon(Graphics2D g, Rectangle r, MapGraphics.UnitType unit) {
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int x0 = r.x, y0 = r.y, x1 = r.x + r.width, y1 = r.y + r.height, cx = r.x + r.width / 2, cy = r.y + r.height / 2;
        switch (unit) {
            case INFANTRY -> {
                g.drawLine(x0, y0, x1, y1);
                g.drawLine(x0, y1, x1, y0);
            }
            case MECHANIZED -> {
                g.drawLine(x0, y0, x1, y1);
                g.drawLine(x0, y1, x1, y0);
                g.draw(new Ellipse2D.Double(cx - r.width * 0.35, cy - r.height * 0.28, r.width * 0.7, r.height * 0.56));
            }
            case ARMOUR -> g.draw(new Ellipse2D.Double(cx - r.width * 0.36, cy - r.height * 0.3, r.width * 0.72, r.height * 0.6));
            case ARTILLERY -> g.fill(new Ellipse2D.Double(cx - 3.5, cy - 3.5, 7, 7));
            case RECON -> g.drawLine(x0, y1, x1, y0);
            case ANTI_TANK -> {
                g.drawLine(x0, y1, cx, y0);
                g.drawLine(x1, y1, cx, y0);
            }
            case ENGINEER -> { // a bar with three teeth down, the standard's "E"
                int top = cy - r.height / 5, len = r.height / 2;
                g.drawLine(x0 + 3, top, x1 - 3, top);
                g.drawLine(x0 + 3, top, x0 + 3, top + len);
                g.drawLine(cx, top, cx, top + len);
                g.drawLine(x1 - 3, top, x1 - 3, top + len);
            }
            case AIR_DEFENCE -> g.draw(new QuadCurve2D.Double(x0 + 2, y1, cx, y0 - r.height * 0.6, x1 - 2, y1));
            case HQ, UNIT -> {
            }
        }
    }
}
