package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Mercator;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The map: where the drone is and where it has been, over Kartverket's topo map, satellite pictures or
 * OpenStreetMap. Pan by dragging, zoom with the wheel (smooth, about the pointer), double-click to fit the whole
 * flight, right-click for the map picture and the rest; the buttons live in {@link MapWindow}'s toolbar. The drone
 * is an arrow pointing where it heads; when its position is old (link or flight controller gone) it turns grey
 * under a red LAST KNOWN badge with the age, which is the point of the map: the place to start looking. Home is
 * where it armed, with range rings. When the map rests on a place, the levels under it are fetched for offline use
 * (see TileCache.prefetch). Repaints come from the window's 30 Hz refresh; tiles arrive on their own threads.
 */
class MapView extends JComponent {
    private static final double MIN_ZOOM = 2, FIRST_FIX_ZOOM = 16;
    private static final long STALE_MS = 3000;
    private static final int[] RING_M = {50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000};
    /** Levels fetched ahead under and over the one looked at, and the most tiles per rest. */
    private static final int PREFETCH_DOWN = 4, PREFETCH_UP = 3, PREFETCH_BUDGET = 1500;
    private static final Color BOX = new Color(10, 10, 10, 215);

    private final TileCache cache;
    private TileSource chosen; // null = auto (by where the drone is)
    private Consumer<TileSource> onSourceChosen;
    private Consumer<double[]> onCursor;
    private final Runnable tileReady = () -> SwingUtilities.invokeLater(this::repaint);
    private final Timer settled = new Timer(1200, e -> prefetchVisible());

    private double centerLat = 64.5, centerLon = 12.0, zoom = 4.3; // Norway, until a fix comes
    private double targetZoom = zoom;
    private Point zoomAnchor;
    private final Timer zoomAnim = new Timer(15, e -> animateZoom());
    private boolean follow = true;
    private boolean everCentred;
    private Point dragStart;
    private double dragLat, dragLon;
    private boolean dragged;

    private FlightTrack track;
    private FcTelemetry.Gps gps;
    private int headingDeg = -1;
    private boolean fcLost;
    private boolean connected;

    MapView(TileCache cache) {
        this.cache = cache;
        setOpaque(true);
        setFocusable(false);
        settled.setRepeats(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    menu().show(MapView.this, e.getX(), e.getY());
                    return;
                }
                dragStart = e.getPoint();
                dragLat = centerLat;
                dragLon = centerLon;
                dragged = false;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart == null) return;
                int dx = e.getX() - dragStart.x, dy = e.getY() - dragStart.y;
                if (!dragged && Math.abs(dx) + Math.abs(dy) < 3) return;
                dragged = true;
                follow = false;
                centerLon = Mercator.lon(Mercator.x(dragLon, zoom) - dx, zoom);
                centerLat = Mercator.lat(Mercator.y(dragLat, zoom) - dy, zoom);
                viewChanged();
                tellCursor(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragStart = null;
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && !dragged && e.getClickCount() == 2) fitTrack();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                tellCursor(e);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (onCursor != null) onCursor.accept(null);
            }

            @Override
            public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                TileSource src = effectiveSource();
                targetZoom = Math.max(MIN_ZOOM, Math.min(src.maxZoom + 1, targetZoom - e.getPreciseWheelRotation() * 0.6));
                zoomAnchor = follow ? null : e.getPoint();
                if (!zoomAnim.isRunning()) zoomAnim.start();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    private void tellCursor(MouseEvent e) {
        if (onCursor == null) return;
        double[] ll = toLatLon(e.getX(), e.getY());
        onCursor.accept(ll);
    }

    /** The place under a pixel. */
    double[] toLatLon(int x, int y) {
        double lon = Mercator.lon(Mercator.x(centerLon, zoom) + x - getWidth() / 2.0, zoom);
        double lat = Mercator.lat(Mercator.y(centerLat, zoom) + y - getHeight() / 2.0, zoom);
        return new double[]{lat, lon};
    }

    /** Gets the place under the mouse as {lat, lon}, or null when it leaves the map. */
    void setOnCursor(Consumer<double[]> listener) {
        onCursor = listener;
    }

    private void viewChanged() {
        settled.restart();
        repaint();
    }

    // ------------------------------------------------------------------ what to show

    /** The source chosen in the settings; null = auto. */
    void setSource(TileSource source) {
        chosen = source;
        viewChanged();
    }

    TileSource source() {
        return chosen;
    }

    /** Called when the pilot picks a source on the map (the right-click menu), to save it. */
    void setOnSourceChosen(Consumer<TileSource> listener) {
        onSourceChosen = listener;
    }

    private void choose(TileSource source) {
        chosen = source;
        if (onSourceChosen != null) onSourceChosen.accept(source);
        viewChanged();
    }

    TileSource effectiveSource() {
        if (chosen != null) return chosen;
        FlightTrack.Point p = track == null ? null : track.last();
        return p != null ? TileSource.forPosition(p.lat(), p.lon()) : TileSource.forPosition(centerLat, centerLon);
    }

    double zoom() {
        return zoom;
    }

    /**
     * The latest from the link. The first fix centres the map on the drone; with FOLLOW on it stays centred.
     * @param headingDeg the drone's heading from its attitude, or -1 to use the GPS course
     */
    void update(FlightTrack track, FcTelemetry.Gps gps, int headingDeg, boolean fcLost, boolean connected) {
        this.track = track;
        this.gps = gps;
        this.headingDeg = headingDeg;
        this.fcLost = fcLost;
        this.connected = connected;
        FlightTrack.Point p = track == null ? null : track.last();
        if (p != null) {
            if (!everCentred) {
                everCentred = true;
                centerLat = p.lat();
                centerLon = p.lon();
                zoom = targetZoom = FIRST_FIX_ZOOM;
                viewChanged();
            } else if (follow && (centerLat != p.lat() || centerLon != p.lon())) {
                centerLat = p.lat();
                centerLon = p.lon();
                viewChanged();
            }
        }
        repaint();
    }

    void setFollow(boolean on) {
        follow = on;
        FlightTrack.Point p = track == null ? null : track.last();
        if (on && p != null) {
            centerLat = p.lat();
            centerLon = p.lon();
        }
        viewChanged();
    }

    boolean isFollowing() {
        return follow;
    }

    void centreOn(double lat, double lon, double zoom) {
        follow = false;
        everCentred = true;
        centerLat = lat;
        centerLon = lon;
        this.zoom = targetZoom = zoom;
        viewChanged();
    }

    /** The whole flight (and home) in view. */
    void fitTrack() {
        List<FlightTrack.Point> pts = track == null ? List.of() : track.points();
        if (pts.isEmpty()) return;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (FlightTrack.Point p : pts) {
            minLat = Math.min(minLat, p.lat());
            maxLat = Math.max(maxLat, p.lat());
            minLon = Math.min(minLon, p.lon());
            maxLon = Math.max(maxLon, p.lon());
        }
        FlightTrack.Point h = track.home();
        if (h != null) {
            minLat = Math.min(minLat, h.lat());
            maxLat = Math.max(maxLat, h.lat());
            minLon = Math.min(minLon, h.lon());
            maxLon = Math.max(maxLon, h.lon());
        }
        int w = Math.max(64, getWidth() - 80), hgt = Math.max(64, getHeight() - 120);
        double z = 18;
        while (z > MIN_ZOOM && (Mercator.x(maxLon, z) - Mercator.x(minLon, z) > w || Mercator.y(minLat, z) - Mercator.y(maxLat, z) > hgt)) z -= 0.1;
        follow = false;
        everCentred = true;
        centerLat = Mercator.lat((Mercator.y(minLat, z) + Mercator.y(maxLat, z)) / 2, z);
        centerLon = (minLon + maxLon) / 2;
        zoom = targetZoom = Math.min(z, effectiveSource().maxZoom);
        viewChanged();
    }

    private void animateZoom() {
        double d = targetZoom - zoom;
        if (Math.abs(d) < 0.004) {
            zoom = targetZoom;
            zoomAnim.stop();
        } else {
            setZoomKeeping(zoomAnchor, zoom + d * 0.3);
        }
        viewChanged();
    }

    /** Changes the zoom so the place under anchor (null: the centre) stays put. */
    private void setZoomKeeping(Point anchor, double newZoom) {
        if (anchor == null) {
            zoom = newZoom;
            return;
        }
        double ax = anchor.x - getWidth() / 2.0, ay = anchor.y - getHeight() / 2.0;
        double lon = Mercator.lon(Mercator.x(centerLon, zoom) + ax, zoom);
        double lat = Mercator.lat(Mercator.y(centerLat, zoom) + ay, zoom);
        zoom = newZoom;
        centerLon = Mercator.lon(Mercator.x(lon, zoom) - ax, zoom);
        centerLat = Mercator.lat(Mercator.y(lat, zoom) - ay, zoom);
    }

    /** The map has rested on a place: the levels under (and over) it are fetched for offline use. */
    private void prefetchVisible() {
        if (!isShowing() || getWidth() < 16) return;
        TileSource src = effectiveSource();
        if (!src.prefetchAllowed) return;
        int zi = (int) Math.max(0, Math.min(src.maxZoom, Math.floor(zoom)));
        double[] nw = toLatLon(0, 0), se = toLatLon(getWidth(), getHeight());
        cache.prefetch(src, zi, zi - PREFETCH_UP, zi + PREFETCH_DOWN, se[0], nw[0], nw[1], se[1], PREFETCH_BUDGET);
    }

    private JPopupMenu menu() {
        JPopupMenu menu = TacticalCombo.menu();
        menu.setBackground(Theme.PANEL_2);
        for (TileSource s : TileSource.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(s.title, s == chosen);
            item.addActionListener(e -> choose(s));
            menu.add(item);
        }
        JRadioButtonMenuItem auto = new JRadioButtonMenuItem("Auto (Kartverket in Norway, else OpenStreetMap)", chosen == null);
        auto.addActionListener(e -> choose(null));
        menu.add(auto);
        menu.addSeparator();
        JMenuItem followItem = new JMenuItem("Follow the drone");
        followItem.addActionListener(e -> setFollow(true));
        menu.add(followItem);
        JMenuItem homeItem = new JMenuItem("Centre on home");
        homeItem.setEnabled(track != null && track.home() != null);
        homeItem.addActionListener(e -> centreOn(track.home().lat(), track.home().lon(), Math.max(zoom, 15)));
        menu.add(homeItem);
        JMenuItem fit = new JMenuItem("Fit the whole flight");
        fit.setEnabled(track != null && !track.isEmpty());
        fit.addActionListener(e -> fitTrack());
        menu.add(fit);
        return menu;
    }

    // ------------------------------------------------------------------ painting

    private Point2D toScreen(double lat, double lon, int zi, double scale, double wcx, double wcy, int w, int h) {
        return new Point2D.Double((Mercator.x(lon, zi) - wcx) * scale + w / 2.0, (Mercator.y(lat, zi) - wcy) * scale + h / 2.0);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        try {
            int w = getWidth(), h = getHeight();
            g.setColor(Theme.PANEL);
            g.fillRect(0, 0, w, h);
            TileSource src = effectiveSource();
            int zi = (int) Math.max(0, Math.min(src.maxZoom, Math.floor(zoom)));
            double scale = Math.pow(2, zoom - zi);
            double wcx = Mercator.x(centerLon, zi), wcy = Mercator.y(centerLat, zi);
            paintTiles(g, src, zi, scale, wcx, wcy, w, h);

            List<FlightTrack.Point> pts = track == null ? List.of() : track.points();
            FlightTrack.Point home = track == null ? null : track.home();
            if (home != null) paintRings(g, home, zi, scale, wcx, wcy, w, h);
            paintTrack(g, pts, zi, scale, wcx, wcy, w, h);
            if (home != null) paintHome(g, home, zi, scale, wcx, wcy, w, h);
            FlightTrack.Point last = pts.isEmpty() ? null : pts.get(pts.size() - 1);
            if (last != null) paintDrone(g, last, zi, scale, wcx, wcy, w, h);
            else paintNoPosition(g, w, h);

            paintScale(g, h);
            paintAttribution(g, src, w, h);
            g.setColor(Theme.LINE);
            g.drawRect(0, 0, w - 1, h - 1);
        } finally {
            g.dispose();
        }
    }

    private void paintTiles(Graphics2D g, TileSource src, int zi, double scale, double wcx, double wcy, int w, int h) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int n = 1 << zi;
        int tx0 = (int) Math.floor((wcx - w / 2.0 / scale) / Mercator.TILE), tx1 = (int) Math.floor((wcx + w / 2.0 / scale) / Mercator.TILE);
        int ty0 = (int) Math.floor((wcy - h / 2.0 / scale) / Mercator.TILE), ty1 = (int) Math.floor((wcy + h / 2.0 / scale) / Mercator.TILE);
        for (int ty = Math.max(0, ty0); ty <= Math.min(n - 1, ty1); ty++) {
            int sy0 = (int) Math.floor((ty * Mercator.TILE - wcy) * scale + h / 2.0);
            int sy1 = (int) Math.floor(((ty + 1) * Mercator.TILE - wcy) * scale + h / 2.0);
            for (int tx = tx0; tx <= tx1; tx++) {
                int sx0 = (int) Math.floor((tx * Mercator.TILE - wcx) * scale + w / 2.0);
                int sx1 = (int) Math.floor(((tx + 1) * Mercator.TILE - wcx) * scale + w / 2.0);
                BufferedImage img = cache.get(src, zi, tx, ty, tileReady);
                if (img != null) {
                    g.drawImage(img, sx0, sy0, sx1, sy1, 0, 0, img.getWidth(), img.getHeight(), null);
                    continue;
                }
                // not in yet: the parent tile's quarter, blown up, until it is
                BufferedImage parent = zi > 0 ? cache.get(src, zi - 1, Math.floorDiv(Math.floorMod(tx, n), 2), ty / 2, tileReady) : null;
                if (parent != null) {
                    int px = (Math.floorMod(tx, n) & 1) * parent.getWidth() / 2, py = (ty & 1) * parent.getHeight() / 2;
                    g.drawImage(parent, sx0, sy0, sx1, sy1, px, py, px + parent.getWidth() / 2, py + parent.getHeight() / 2, null);
                } else {
                    g.setColor(Theme.PANEL_2);
                    g.fillRect(sx0, sy0, sx1 - sx0, sy1 - sy0);
                    g.setColor(Theme.LINE);
                    g.drawRect(sx0, sy0, sx1 - sx0, sy1 - sy0);
                }
            }
        }
    }

    private void paintTrack(Graphics2D g, List<FlightTrack.Point> pts, int zi, double scale, double wcx, double wcy, int w, int h) {
        if (pts.size() < 2) return;
        Path2D path = new Path2D.Double();
        boolean first = true;
        for (FlightTrack.Point p : pts) {
            Point2D s = toScreen(p.lat(), p.lon(), zi, scale, wcx, wcy, w, h);
            if (first) path.moveTo(s.getX(), s.getY()); else path.lineTo(s.getX(), s.getY());
            first = false;
        }
        g.setStroke(new BasicStroke(5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0, 0, 0, 120));
        g.draw(path);
        g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Theme.ACCENT);
        g.draw(path);
        if (zoom >= 15.5) { // each report as a dot: a gap in them is a gap in the telemetry
            g.setColor(new Color(255, 255, 255, 170));
            for (FlightTrack.Point p : pts) {
                Point2D s = toScreen(p.lat(), p.lon(), zi, scale, wcx, wcy, w, h);
                g.fill(new Ellipse2D.Double(s.getX() - 1.6, s.getY() - 1.6, 3.2, 3.2));
            }
        }
    }

    private void paintRings(Graphics2D g, FlightTrack.Point home, int zi, double scale, double wcx, double wcy, int w, int h) {
        Point2D c = toScreen(home.lat(), home.lon(), zi, scale, wcx, wcy, w, h);
        double mpp = Mercator.metersPerPixel(home.lat(), zoom);
        g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 6f}, 0f));
        g.setFont(Theme.headingFont(10.5f));
        int shown = 0;
        for (int m : RING_M) {
            double r = m / mpp;
            if (r < 36 || r > Math.max(w, h) * 1.2) continue;
            g.setColor(new Color(255, 255, 255, 130));
            g.draw(new Ellipse2D.Double(c.getX() - r, c.getY() - r, 2 * r, 2 * r));
            String label = m >= 1000 ? (m / 1000) + " KM" : m + " M";
            FontMetrics fm = g.getFontMetrics();
            int lx = (int) c.getX() - fm.stringWidth(label) / 2, ly = (int) (c.getY() - r) - 4;
            g.setColor(BOX);
            g.fillRect(lx - 4, ly - fm.getAscent(), fm.stringWidth(label) + 8, fm.getHeight());
            g.setColor(new Color(255, 255, 255, 200));
            g.drawString(label, lx, ly);
            if (++shown == 3) break;
        }
    }

    private void paintHome(Graphics2D g, FlightTrack.Point home, int zi, double scale, double wcx, double wcy, int w, int h) {
        Point2D c = toScreen(home.lat(), home.lon(), zi, scale, wcx, wcy, w, h);
        double r = 10;
        g.setColor(new Color(0, 0, 0, 170));
        g.fill(new Ellipse2D.Double(c.getX() - r, c.getY() - r, 2 * r, 2 * r));
        g.setStroke(new BasicStroke(2f));
        g.setColor(Color.WHITE);
        g.draw(new Ellipse2D.Double(c.getX() - r, c.getY() - r, 2 * r, 2 * r));
        g.setFont(Theme.headingFont(12f));
        FontMetrics fm = g.getFontMetrics();
        g.drawString("H", (float) c.getX() - fm.stringWidth("H") / 2f, (float) c.getY() + fm.getAscent() / 2f - 1.5f);
    }

    private void paintDrone(Graphics2D g, FlightTrack.Point last, int zi, double scale, double wcx, double wcy, int w, int h) {
        Point2D c = toScreen(last.lat(), last.lon(), zi, scale, wcx, wcy, w, h);
        long age = System.currentTimeMillis() - last.timeMs();
        boolean stale = age > STALE_MS || (fcLost && !last.phone()) || !connected;
        int heading = headingDeg >= 0 && !stale && !last.phone() ? headingDeg : last.courseDeg();
        AffineTransform saved = g.getTransform();
        g.translate(c.getX(), c.getY());
        g.rotate(Math.toRadians(heading));
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(0, -13);
        arrow.lineTo(8.5, 10);
        arrow.lineTo(0, 5.5);
        arrow.lineTo(-8.5, 10);
        arrow.closePath();
        g.setColor(new Color(0, 0, 0, 140));
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(arrow);
        g.setColor(stale ? Theme.MUTED : Theme.ACCENT);
        g.fill(arrow);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(arrow);
        g.setTransform(saved);
        if (last.phone()) { // the phone's GPS, not the drone's
            g.setFont(Theme.headingFont(10.5f));
            g.setColor(Color.WHITE);
            g.drawString("PHONE", (float) c.getX() + 13, (float) c.getY() + 4);
        }
        if (stale) badge(g, "LAST KNOWN  " + ageText(age), (int) c.getX(), (int) c.getY() - 24);
    }

    /** A red tag above a point: the program's square style with bracket corners. */
    private void badge(Graphics2D g, String text, int cx, int bottom) {
        g.setFont(Theme.headingFont(12f));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text) + 18, th = fm.getHeight() + 8;
        int x = cx - tw / 2, y = bottom - th;
        g.setColor(Theme.ACCENT);
        g.fillRect(x, y, tw, th);
        g.setColor(Color.WHITE);
        g.drawString(text, x + 9, y + 4 + fm.getAscent());
        brackets(g, x, y, tw, th, Color.WHITE);
    }

    /** The theme's corner brackets round a box. */
    private static void brackets(Graphics2D g, int x, int y, int w, int h, Color color) {
        int arm = 5;
        g.setColor(color);
        g.setStroke(new BasicStroke(1.5f));
        g.drawLine(x, y, x + arm, y);
        g.drawLine(x, y, x, y + arm);
        g.drawLine(x + w - 1, y, x + w - 1 - arm, y);
        g.drawLine(x + w - 1, y, x + w - 1, y + arm);
        g.drawLine(x, y + h - 1, x + arm, y + h - 1);
        g.drawLine(x, y + h - 1, x, y + h - 1 - arm);
        g.drawLine(x + w - 1, y + h - 1, x + w - 1 - arm, y + h - 1);
        g.drawLine(x + w - 1, y + h - 1, x + w - 1, y + h - 1 - arm);
    }

    private void paintNoPosition(Graphics2D g, int w, int h) {
        String text = !connected ? "NO DRONE POSITION" : "WAITING FOR A GPS FIX";
        g.setFont(Theme.headingFont(13f));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text) + 24, th = fm.getHeight() + 10;
        int x = (w - tw) / 2, y = 12;
        g.setColor(BOX);
        g.fillRect(x, y, tw, th);
        g.setColor(Theme.MUTED);
        g.drawString(text, x + 12, y + 5 + fm.getAscent());
        brackets(g, x, y, tw, th, Theme.ACCENT);
    }

    static String ageText(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + " S AGO";
        if (s < 3600) return (s / 60) + " MIN AGO";
        return (s / 3600) + " H " + (s % 3600 / 60) + " MIN AGO";
    }

    static String distanceText(double m) {
        return m >= 1000 ? String.format(Locale.ROOT, "%.2f KM", m / 1000) : String.format(Locale.ROOT, "%.0f M", m);
    }

    private void paintScale(Graphics2D g, int h) {
        double mpp = Mercator.metersPerPixel(centerLat, zoom);
        double target = 90 * mpp;
        double pow = Math.pow(10, Math.floor(Math.log10(target)));
        double nice = target / pow >= 5 ? 5 * pow : target / pow >= 2 ? 2 * pow : pow;
        int px = (int) Math.round(nice / mpp);
        String label = nice >= 1000 ? String.format(Locale.ROOT, "%.0f KM", nice / 1000) : String.format(Locale.ROOT, "%.0f M", nice);
        g.setFont(Theme.headingFont(10.5f));
        FontMetrics fm = g.getFontMetrics();
        int x = 12, y = h - 14;
        int bw = Math.max(px, fm.stringWidth(label)) + 16, bh = fm.getHeight() + 14;
        g.setColor(BOX);
        g.fillRect(x - 8, y - bh + 6, bw, bh);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(x, y, x + px, y);
        g.drawLine(x, y - 5, x, y);
        g.drawLine(x + px, y - 5, x + px, y);
        g.drawString(label, x, y - 7);
    }

    private void paintAttribution(Graphics2D g, TileSource src, int w, int h) {
        String text = src.attribution + (cache.isOffline() ? "   SAVED TILES ONLY" : "");
        g.setFont(Theme.font(Font.PLAIN, 10f));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text) + 10;
        g.setColor(BOX);
        g.fillRect(w - tw - 8, h - fm.getHeight() - 10, tw, fm.getHeight() + 2);
        g.setColor(new Color(255, 255, 255, 210));
        g.drawString(text, w - tw - 3, h - 10 - fm.getDescent() + 1);
    }

    /** For tests: the map as painted at this size. */
    BufferedImage snapshot(int w, int h) {
        setSize(w, h);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.Src);
        paintComponent(g);
        g.dispose();
        return img;
    }
}
