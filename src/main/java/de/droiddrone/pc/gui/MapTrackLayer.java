package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.TrackPath;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.List;

/**
 * The map's flight lines (MapView paints them, earliest under): the kept flights, an earlier flight from TRACKS, the
 * live track with its newest reports as dots, and PREVIOUS FLIGHTS' playback with the drone where it was.
 */
final class MapTrackLayer {
    private static final Color[] KEPT_COLOURS = {new Color(0x4fc3f7), new Color(0xba68c8), new Color(0x81c784),
            new Color(0xffb74d), new Color(0xf06292), new Color(0x90a4ae)};

    private MapTrackLayer() {
    }

    /** The kept flights: each a line in its own colour, its name at its end. */
    static void paintKept(Graphics2D g, List<MapView.Kept> kept, List<TrackPath> paths, MapProjection proj) {
        for (int i = 0; i < kept.size() && i < paths.size(); i++) {
            TrackPath tp = paths.get(i);
            if (tp.size() < 2) continue;
            Path2D path = line(tp, proj);
            Point2D end = path.getCurrentPoint();
            Color c = KEPT_COLOURS[i % KEPT_COLOURS.length];
            g.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(0, 0, 0, 110));
            g.draw(path);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Theme.alpha(c, 220));
            g.draw(path);
            String name = kept.get(i).name();
            if (name != null && end != null) {
                g.setFont(Theme.headingFont(10.5f));
                FontMetrics fm = g.getFontMetrics();
                int tw = fm.stringWidth(name) + 10;
                int lx = (int) end.getX() + 8;
                if (lx + tw > proj.w() - 4) lx = (int) end.getX() - 8 - tw; // near the right edge: the label on the left
                g.setColor(MapView.BOX);
                g.fillRect(lx, (int) end.getY() - fm.getHeight() / 2, tw, fm.getHeight() + 2);
                g.setColor(c);
                g.drawString(name, lx + 5, (int) end.getY() + fm.getAscent() / 2 - 1);
            }
        }
    }

    /** An earlier flight: a thin muted line with its file's name at its end, under everything live. */
    static void paintOldTrack(Graphics2D g, TrackPath tp, String name, MapProjection proj) {
        if (tp == null || tp.size() == 0) return;
        Path2D path = line(tp, proj);
        Point2D end = path.getCurrentPoint();
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0, 0, 0, 90));
        g.draw(path);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{7f, 5f}, 0f));
        g.setColor(new Color(0xb0, 0xb0, 0xa4, 200));
        g.draw(path);
        if (name != null && end != null) {
            g.setFont(Theme.headingFont(10.5f));
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(name) + 10;
            g.setColor(MapView.BOX);
            g.fillRect((int) end.getX() + 8, (int) end.getY() - fm.getHeight() / 2, tw, fm.getHeight() + 2);
            g.setColor(Theme.MUTED);
            g.drawString(name, (int) end.getX() + 13, (int) end.getY() + fm.getAscent() / 2 - 1);
        }
    }

    /** The flight played back: its line in the accent colour, and the drone where it was at the playback time. */
    static void paintReplay(Graphics2D g, TrackPath played, double lat, double lon, double headingDeg, MapProjection proj) {
        Path2D path = line(played, proj);
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0, 0, 0, 120));
        g.draw(path);
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Theme.ACCENT);
        g.draw(path);
        if (Double.isNaN(lat)) return;
        Point2D c = proj.toScreen(lat, lon);
        double x = c.getX(), y = c.getY();
        g.setStroke(new BasicStroke(2f));
        if (Double.isNaN(headingDeg)) {
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(x - 6, y - 6, 12, 12));
            g.setColor(Theme.ACCENT);
            g.draw(new Ellipse2D.Double(x - 6, y - 6, 12, 12));
            return;
        }
        double a = Math.toRadians(headingDeg);
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(0, -11);
        arrow.lineTo(7, 8);
        arrow.lineTo(0, 4);
        arrow.lineTo(-7, 8);
        arrow.closePath();
        Graphics2D r = (Graphics2D) g.create();
        r.translate(x, y);
        r.rotate(a);
        r.setColor(Color.WHITE);
        r.fill(arrow);
        r.setColor(Theme.ACCENT);
        r.draw(arrow);
        r.dispose();
    }

    static void paintTrack(Graphics2D g, TrackPath tp, MapProjection proj) {
        if (tp == null || tp.size() < 2) return;
        Path2D path = line(tp, proj);
        g.setStroke(new BasicStroke(5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0, 0, 0, 120));
        g.draw(path);
        g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Theme.ACCENT);
        g.draw(path);
        if (proj.zoom() >= 15.5) { // each report as a dot (the newest TrackPath.RECENT): a gap in them is a gap in the telemetry
            g.setColor(new Color(255, 255, 255, 170));
            double k = proj.level();
            for (int i = 0; i < tp.recentSize(); i++) {
                double x = proj.screenX(tp.recentX(i) * k), y = proj.screenY(tp.recentY(i) * k);
                if (x < -4 || y < -4 || x > proj.w() + 4 || y > proj.h() + 4) continue;
                g.fill(new Ellipse2D.Double(x - 1.6, y - 1.6, 3.2, 3.2));
            }
        }
    }

    /** A thinned track (zoom-0 map units) as a line on the screen. */
    static Path2D line(TrackPath tp, MapProjection proj) {
        Path2D path = new Path2D.Double(Path2D.WIND_NON_ZERO, tp.size());
        double k = proj.level();
        for (int i = 0; i < tp.size(); i++) {
            double x = proj.screenX(tp.x(i) * k), y = proj.screenY(tp.y(i) * k);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        return path;
    }
}
