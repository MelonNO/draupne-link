package de.droiddrone.pc.gui;

import de.droiddrone.pc.DetectionMarks;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.map.RadioHorizon;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.List;

/**
 * What the cameras see on the map: the live drone's view on the ground, the motion detection's directions, and the
 * overwatch drones' views, directions and alerts.
 */
final class GroundViewLayer {
    private GroundViewLayer() {
    }

    /** A camera's ground outline {lat, lon} as a closed shape on the screen. */
    static Path2D outline(List<double[]> places, MapProjection proj) {
        Path2D shape = new Path2D.Double();
        for (double[] ll : places) {
            Point2D p = proj.toScreen(ll[0], ll[1]);
            if (shape.getCurrentPoint() == null) shape.moveTo(p.getX(), p.getY());
            else shape.lineTo(p.getX(), p.getY());
        }
        shape.closePath();
        return shape;
    }

    /**
     * The camera's view: the ground it sees, pale over the map, its edge, and a line from the drone to where the
     * picture's centre lands (a cross there). Ground out of the camera's reach ends at CameraFootprint.MAX_RANGE_M.
     */
    static void paintCameraView(Graphics2D g0, CameraFootprint.View v, MapProjection proj) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            Path2D shape = outline(v.outline(), proj);
            g.setColor(new Color(255, 236, 160, 46));
            g.fill(shape);
            g.setColor(new Color(255, 236, 160, 200));
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(shape);
            if (v.centre() != null) {
                Point2D d = proj.toScreen(v.pose().lat(), v.pose().lon());
                Point2D c = proj.toScreen(v.centre()[0], v.centre()[1]);
                g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{5, 4}, 0));
                g.draw(new Line2D.Double(d, c));
                g.setStroke(new BasicStroke(2f));
                g.draw(new Line2D.Double(c.getX() - 6, c.getY(), c.getX() + 6, c.getY()));
                g.draw(new Line2D.Double(c.getX(), c.getY() - 6, c.getX(), c.getY() + 6));
            }
        } finally {
            g.dispose();
        }
    }

    /**
     * The motion detection (1.0.46): from the drone a thin dashed yellow line along each detection's direction, and
     * where it meets the terrain a ring marked EST (an estimate: the camera's set angle and tilt, the map's heights),
     * MOVING or LIGHT and its age beside it. Fading over DetectionMarks.KEEP_MS.
     */
    static void paintDetections(Graphics2D g0, List<DetectionMarks.Mark> marks, MapProjection proj) {
        if (marks.isEmpty()) return;
        long now = MonoClock.ms();
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setFont(Theme.headingFont(10.5f));
            for (DetectionMarks.Mark m : marks) {
                float a = m.alpha(now);
                if (a <= 0) continue;
                Point2D d = proj.toScreen(m.droneLat(), m.droneLon());
                double[] end = m.ground() != null ? m.ground() : RadioHorizon.offset(m.droneLat(), m.droneLon(),
                        Math.sin(Math.toRadians(m.bearingDeg())) * MapView.DETECTION_LINE_M, Math.cos(Math.toRadians(m.bearingDeg())) * MapView.DETECTION_LINE_M);
                Point2D e = proj.toScreen(end[0], end[1]);
                Color yellow = Theme.alpha(Theme.WARN, Math.round(220 * a));
                g.setColor(new Color(0, 0, 0, Math.round(120 * a)));
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(d, e));
                g.setColor(yellow);
                g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{6, 4}, 0));
                g.draw(new Line2D.Double(d, e));
                String what = (m.light() ? "LIGHT" : "MOVING") + "  " + Math.max(0, (now - m.seenAt()) / 1000) + " S";
                if (m.ground() != null) {
                    double r = 7;
                    g.setStroke(new BasicStroke(2f));
                    g.draw(new Ellipse2D.Double(e.getX() - r, e.getY() - r, 2 * r, 2 * r));
                    g.drawString("EST " + what + (Double.isNaN(m.rangeM()) ? "" : "  " + Math.round(m.rangeM()) + " M"),
                            (float) e.getX() + 10, (float) e.getY() + 4);
                } else {
                    g.drawString(what, (float) e.getX() + 6, (float) e.getY() + 4);
                }
            }
        } finally {
            g.dispose();
        }
    }

    /**
     * The overwatch drones: the camera's view in the alert colour (orange-red, pale) and its edge, the detection's
     * direction dashed from the drone, rings pulsing out from the drone while its alert is on, and its name. A clip
     * playing shows its view as it was, in the camera view's own pale yellow.
     */
    static void paintOverwatch(Graphics2D g0, List<MapView.OverwatchMark> marks, MapProjection proj) {
        if (marks.isEmpty()) return;
        Graphics2D g = (Graphics2D) g0.create();
        try {
            double phase = (System.currentTimeMillis() % 1400) / 1400.0;
            for (MapView.OverwatchMark m : marks) {
                if (m.watching()) { // a drone in OVERWATCH, no alert (1.0.50): the ground its camera watches, calm teal
                    if (m.view() == null) continue; // its eye (the drone's sign) stands for it
                    Path2D shape = outline(m.view().outline(), proj);
                    g.setColor(Theme.alpha(MapView.OVERWATCH_COLOR, 38));
                    g.fill(shape);
                    g.setColor(Theme.alpha(MapView.OVERWATCH_COLOR, 190));
                    g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{6, 4}, 0));
                    g.draw(shape);
                    continue;
                }
                Color c = m.replay() ? new Color(255, 236, 160) : MapView.ALERT;
                Point2D d = proj.toScreen(m.lat(), m.lon());
                if (m.view() != null) {
                    Path2D shape = outline(m.view().outline(), proj);
                    g.setColor(Theme.alpha(c, m.alert() ? 60 : 40));
                    g.fill(shape);
                    g.setColor(Theme.alpha(c, 210));
                    g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(shape);
                }
                if (!Double.isNaN(m.bearingDeg())) {
                    double[] end = RadioHorizon.offset(m.lat(), m.lon(), Math.sin(Math.toRadians(m.bearingDeg())) * MapView.DETECTION_LINE_M,
                            Math.cos(Math.toRadians(m.bearingDeg())) * MapView.DETECTION_LINE_M);
                    Point2D e = proj.toScreen(end[0], end[1]);
                    g.setColor(new Color(0, 0, 0, 130));
                    g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new Line2D.Double(d, e));
                    g.setColor(Theme.alpha(c, 235));
                    g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{7, 5}, 0));
                    g.draw(new Line2D.Double(d, e));
                }
                if (m.alert()) {
                    for (int i = 0; i < 2; i++) {
                        double p = (phase + i * 0.5) % 1.0;
                        double r = 10 + 30 * p;
                        g.setColor(Theme.alpha(MapView.ALERT, (int) Math.round(220 * (1 - p))));
                        g.setStroke(new BasicStroke(2.5f));
                        g.draw(new Ellipse2D.Double(d.getX() - r, d.getY() - r, 2 * r, 2 * r));
                    }
                }
                double r = 8;
                g.setColor(new Color(0, 0, 0, 160));
                g.fill(new Ellipse2D.Double(d.getX() - r - 1.5, d.getY() - r - 1.5, 2 * r + 3, 2 * r + 3));
                g.setColor(Theme.alpha(c, 230));
                g.fill(new Ellipse2D.Double(d.getX() - r, d.getY() - r, 2 * r, 2 * r));
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(1.4f));
                g.draw(new Ellipse2D.Double(d.getX() - 3, d.getY() - 3, 6, 6)); // an eye: it watches
                if (m.label() != null) {
                    g.setFont(Theme.headingFont(11f));
                    FontMetrics fm = g.getFontMetrics();
                    int tw = fm.stringWidth(m.label()) + 10;
                    int bx = (int) d.getX() + 14, by = (int) d.getY() + 10;
                    if (bx + tw > proj.w() - 4) bx = (int) d.getX() - 14 - tw;
                    g.setColor(MapView.BOX);
                    g.fillRect(bx, by, tw, fm.getHeight() + 2);
                    g.setColor(c);
                    g.drawString(m.label(), bx + 5, by + fm.getAscent() + 1);
                }
            }
        } finally {
            g.dispose();
        }
    }
}
