package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Mercator;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
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
import java.awt.Rectangle;
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
 * (see TileCache.prefetch); hold Alt and drag a box to download that area whole (see MapDownloadDialog). Repaints
 * come from the window's 30 Hz refresh; tiles arrive on their own threads.
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
    private Consumer<double[]> onAreaSelected;
    /** Alt + drag: the box being drawn, in pixels; null when none. */
    private Point selectStart, selectEnd;
    /** SELECT WITH CURSOR in the download window: the next left drag draws a box, Alt or not. */
    private boolean selectNext;
    /** The box being drawn came from SELECT WITH CURSOR: the window is told when it is over. */
    private boolean selectingByRequest;
    private Runnable onSelectModeEnded;
    private final Runnable tileReady = () -> SwingUtilities.invokeLater(this::repaint);
    private final Timer settled = new Timer(1200, e -> prefetchVisible());
    /**
     * Following the drone the view never rests, so the rest prefetch never fires: every few seconds the levels under
     * the drone are fetched instead, when it has moved.
     */
    private static final int FOLLOW_PREFETCH_MS = 5000;
    private final Timer followPrefetch = new Timer(FOLLOW_PREFETCH_MS, e -> prefetchAroundDrone());
    private boolean movedSincePrefetch;
    /** An earlier flight's track (TRACKS in the map window), drawn muted under the live one; null for none. */
    private List<FlightTrack.Point> oldTrack;
    private String oldTrackName;

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
    private Pins pins;
    private FcTelemetry.Gps gps;
    /** The + and - buttons, drawn on the map (top right); set while painting. */
    private Rectangle zoomInButton = new Rectangle(), zoomOutButton = new Rectangle();
    private int headingDeg = -1;
    private boolean fcLost;
    private boolean connected;

    MapView(TileCache cache) {
        this.cache = cache;
        setOpaque(true);
        setFocusable(false);
        settled.setRepeats(false);
        followPrefetch.setRepeats(true);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    if (selectNext) { // the pilot changed their mind: no box
                        endSelectMode();
                        return;
                    }
                    TacticalCombo.show(menu(e.getX(), e.getY()), MapView.this, e.getX(), e.getY());
                    return;
                }
                dragged = false;
                if ((e.isAltDown() || selectNext) && SwingUtilities.isLeftMouseButton(e) && onAreaSelected != null) {
                    selectStart = selectEnd = e.getPoint(); // a box to download, not a pan
                    selectingByRequest = selectNext;
                    return;
                }
                dragStart = e.getPoint();
                dragLat = centerLat;
                dragLon = centerLon;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (selectStart != null) {
                    selectEnd = e.getPoint();
                    dragged = true;
                    repaint();
                    return;
                }
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
                if (selectStart == null) return;
                Rectangle box = selection();
                selectStart = selectEnd = null;
                repaint();
                // SELECT WITH CURSOR ends either way: a box drawn, or a plain click that meant to call it off
                if (selectingByRequest) endSelectMode();
                selectingByRequest = false;
                if (box.width >= 8 && box.height >= 8) onAreaSelected.accept(area(box));
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || dragged) return;
                if (zoomInButton.contains(e.getPoint())) zoomBy(1);
                else if (zoomOutButton.contains(e.getPoint())) zoomBy(-1);
                else if (e.getClickCount() == 2) fitTrack();
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
                zoomAnchor = follow ? null : e.getPoint();
                zoomBy(-e.getPreciseWheelRotation() * 0.6);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    /** Zooms in (positive) or out by this many levels, smoothly; the + and - buttons, the wheel and the keys use it. */
    void zoomBy(double levels) {
        TileSource src = effectiveSource();
        targetZoom = Math.max(MIN_ZOOM, Math.min(src.maxZoom + 1, targetZoom + levels));
        if (!zoomAnim.isRunning()) zoomAnim.start();
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

    /**
     * Gets an area to download for offline use as {minLat, maxLat, minLon, maxLon}: a box drawn with Alt + drag, or
     * the view from the right-click menu. Without a listener Alt + drag pans like any drag.
     */
    void setOnAreaSelected(Consumer<double[]> listener) {
        onAreaSelected = listener;
    }

    /** The next left drag on the map draws the box to download (the download window's SELECT WITH CURSOR). */
    void selectAreaNext() {
        selectNext = true;
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR));
        repaint();
    }

    boolean isSelectingArea() {
        return selectNext;
    }

    /** Told when SELECT WITH CURSOR ends: a box drawn, or a right-click that called it off. */
    void setOnSelectModeEnded(Runnable listener) {
        onSelectModeEnded = listener;
    }

    private void endSelectMode() {
        selectNext = false;
        setCursor(java.awt.Cursor.getDefaultCursor());
        if (onSelectModeEnded != null) onSelectModeEnded.run();
    }

    /** The box being drawn, normalised. */
    private Rectangle selection() {
        int x = Math.min(selectStart.x, selectEnd.x), y = Math.min(selectStart.y, selectEnd.y);
        return new Rectangle(x, y, Math.abs(selectEnd.x - selectStart.x), Math.abs(selectEnd.y - selectStart.y));
    }

    /** The places at a box's corners as {minLat, maxLat, minLon, maxLon}. */
    private double[] area(Rectangle box) {
        double[] nw = toLatLon(box.x, box.y), se = toLatLon(box.x + box.width, box.y + box.height);
        return new double[]{se[0], nw[0], nw[1], se[1]};
    }

    /** What the map shows, as {minLat, maxLat, minLon, maxLon}. */
    double[] visibleArea() {
        return area(new Rectangle(0, 0, getWidth(), getHeight()));
    }

    private void viewChanged() {
        settled.restart();
        repaint();
    }

    // ------------------------------------------------------------------ what to show

    /** The pilot's pins, drawn with their names and the way to them from the drone; right-click adds, renames, removes. */
    /** The tour's example pins: drawn like the real ones, never saved, gone when the tour ends. */
    private List<Pins.Pin> examplePins = List.of();

    /** Three made-up pins round the view's centre, so the tour can show what pins look like; off clears them. */
    void showExamplePins(boolean on) {
        if (!on) {
            examplePins = List.of();
        } else {
            double mPerDegLat = 111_320, mPerDegLon = 111_320 * Math.cos(Math.toRadians(centerLat));
            examplePins = List.of(
                    new Pins.Pin("Alpha", centerLat + 250 / mPerDegLat, centerLon + 300 / mPerDegLon),
                    new Pins.Pin("Bravo", centerLat - 400 / mPerDegLat, centerLon - 200 / mPerDegLon),
                    new Pins.Pin("Charlie", centerLat + 80 / mPerDegLat, centerLon - 450 / mPerDegLon));
        }
        repaint();
    }

    private List<Pins.Pin> allPins() {
        if (examplePins.isEmpty()) return pins.list();
        List<Pins.Pin> all = new java.util.ArrayList<>(pins.list());
        all.addAll(examplePins);
        return all;
    }

    void setPins(Pins pins) {
        this.pins = pins;
        repaint();
    }

    /** An earlier flight's track, drawn muted under the live one; null clears it. */
    void setOldTrack(List<FlightTrack.Point> points, String name) {
        oldTrack = points == null || points.isEmpty() ? null : points;
        oldTrackName = name;
        repaint();
    }

    boolean hasOldTrack() {
        return oldTrack != null;
    }

    /** The whole earlier track in view. */
    void fitOldTrack() {
        if (oldTrack != null) fit(oldTrack, null);
    }

    /** A pin at a place (the CURSOR cell's right-click, GO TO's "pin here"). */
    void addPinAt(double lat, double lon, String name) {
        if (pins == null) return;
        pins.add(name, lat, lon);
        repaint();
    }

    /** Centres on the drone's last known position and follows it again; nothing without a position. */
    void centreOnDrone() {
        FlightTrack.Point p = track == null ? null : track.last();
        if (p == null) return;
        centreOn(p.lat(), p.lon(), Math.max(zoom, 15));
        follow = true;
    }

    /** Centres on the launch point; nothing before the drone armed. */
    void centreOnHome() {
        FlightTrack.Point h = track == null ? null : track.home();
        if (h != null) centreOn(h.lat(), h.lon(), Math.max(zoom, 15));
    }

    /** The pin under a pixel (within its marker), or null. */
    private Pins.Pin pinAt(int x, int y) {
        if (pins == null) return null;
        TileSource src = effectiveSource();
        int zi = (int) Math.max(0, Math.min(src.maxZoom, Math.floor(zoom)));
        double scale = Math.pow(2, zoom - zi);
        double wcx = Mercator.x(centerLon, zi), wcy = Mercator.y(centerLat, zi);
        for (Pins.Pin p : allPins()) {
            Point2D s = toScreen(p.lat(), p.lon(), zi, scale, wcx, wcy, getWidth(), getHeight());
            if (Math.abs(s.getX() - x) <= 12 && Math.abs(s.getY() - y) <= 12) return p;
        }
        return null;
    }

    private void addPin(int x, int y) {
        if (pins == null) return;
        double[] ll = toLatLon(x, y);
        String name = (String) javax.swing.JOptionPane.showInputDialog(this, "Name of the pin", "Add pin",
                javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, pins.nextName());
        if (name == null) return;
        pins.add(name, ll[0], ll[1]);
        repaint();
    }

    private void renamePin(Pins.Pin pin) {
        String name = (String) javax.swing.JOptionPane.showInputDialog(this, "New name", "Rename " + pin.name(),
                javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, pin.name());
        if (name == null || name.isBlank()) return;
        pins.rename(pin, name);
        repaint();
    }

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
                // not viewChanged(): that would restart the rest timer on every fix and prefetch nothing in flight
                centerLat = p.lat();
                centerLon = p.lon();
                movedSincePrefetch = true;
            }
        }
        repaint();
    }

    /** The follow prefetch runs while the map is on screen only (FIND DRONE makes a map of its own each time). */
    @Override
    public void addNotify() {
        super.addNotify();
        followPrefetch.start();
    }

    @Override
    public void removeNotify() {
        followPrefetch.stop();
        settled.stop();
        super.removeNotify();
    }

    /** Every few seconds while following: the levels round the drone, when it has moved since the last time. */
    private void prefetchAroundDrone() {
        if (!follow || !movedSincePrefetch) return;
        movedSincePrefetch = false;
        prefetchVisible();
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
        fit(pts, track.home());
    }

    private void fit(List<FlightTrack.Point> pts, FlightTrack.Point h) {
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (FlightTrack.Point p : pts) {
            minLat = Math.min(minLat, p.lat());
            maxLat = Math.max(maxLat, p.lat());
            minLon = Math.min(minLon, p.lon());
            maxLon = Math.max(maxLon, p.lon());
        }
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

    private JPopupMenu menu(int x, int y) {
        JPopupMenu menu = TacticalCombo.menu();
        menu.setBackground(Theme.PANEL_2);
        if (pins != null) {
            Pins.Pin hit = pinAt(x, y);
            if (hit != null) {
                JMenuItem rename = new JMenuItem("Rename " + hit.name() + "...");
                rename.addActionListener(e -> renamePin(hit));
                menu.add(rename);
                JMenuItem remove = new JMenuItem("Remove " + hit.name());
                remove.addActionListener(e -> {
                    pins.remove(hit);
                    repaint();
                });
                menu.add(remove);
            } else {
                JMenuItem add = new JMenuItem("Add pin here (" + pins.nextName() + ")");
                add.addActionListener(e -> addPin(x, y));
                menu.add(add);
            }
            menu.addSeparator();
        }
        // the map picture is chosen with the toolbar's MAP TYPE (the minimap takes the map window's choice)
        JMenuItem followItem = new JMenuItem("Follow the drone");
        followItem.addActionListener(e -> setFollow(true));
        menu.add(followItem);
        JMenuItem homeItem = new JMenuItem("Centre on the launch point");
        homeItem.setEnabled(track != null && track.home() != null);
        homeItem.addActionListener(e -> centreOn(track.home().lat(), track.home().lon(), Math.max(zoom, 15)));
        menu.add(homeItem);
        JMenuItem fit = new JMenuItem("Fit the whole flight");
        fit.setEnabled(track != null && !track.isEmpty());
        fit.addActionListener(e -> fitTrack());
        menu.add(fit);
        if (onAreaSelected != null) {
            menu.addSeparator();
            JMenuItem download = new JMenuItem("Download this view for offline use...");
            download.addActionListener(e -> onAreaSelected.accept(visibleArea()));
            menu.add(download);
        }
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
            if (oldTrack != null) paintOldTrack(g, zi, scale, wcx, wcy, w, h);
            paintTrack(g, pts, zi, scale, wcx, wcy, w, h);
            if (home != null) paintHome(g, home, zi, scale, wcx, wcy, w, h);
            FlightTrack.Point last = pts.isEmpty() ? null : pts.get(pts.size() - 1);
            if (pins != null) paintPins(g, last, home, zi, scale, wcx, wcy, w, h);
            if (last != null) paintDrone(g, last, zi, scale, wcx, wcy, w, h);
            else paintNoPosition(g, w, h);

            paintScale(g, h);
            paintAttribution(g, src, w, h);
            paintZoomButtons(g, w);
            if (selectStart != null) paintSelection(g);
            g.setColor(Theme.LINE);
            g.drawRect(0, 0, w - 1, h - 1);
        } finally {
            g.dispose();
        }
    }

    /** The box being drawn with Alt + drag: red frame, its size on the ground. */
    private void paintSelection(Graphics2D g) {
        Rectangle box = selection();
        g.setColor(Theme.alpha(Theme.ACCENT, 40));
        g.fillRect(box.x, box.y, box.width, box.height);
        g.setColor(Theme.ACCENT);
        g.setStroke(new BasicStroke(2f));
        g.drawRect(box.x, box.y, box.width, box.height);
        if (box.width < 40 || box.height < 24) return;
        double[] a = area(box);
        double midLat = (a[0] + a[1]) / 2;
        String text = distanceText(Mercator.distanceM(midLat, a[2], midLat, a[3])) + " x " + distanceText(Mercator.distanceM(a[0], a[2], a[1], a[2]))
                + "   DOWNLOAD";
        g.setFont(Theme.headingFont(11f));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text);
        g.setColor(BOX);
        g.fillRect(box.x + 2, box.y + 2, tw + 12, fm.getHeight() + 4);
        g.setColor(Theme.TEXT);
        g.drawString(text, box.x + 8, box.y + 2 + fm.getAscent() + 2);
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

    /** An earlier flight: a thin muted line with its file's name at its end, under everything live. */
    private void paintOldTrack(Graphics2D g, int zi, double scale, double wcx, double wcy, int w, int h) {
        List<FlightTrack.Point> pts = oldTrack;
        if (pts == null || pts.isEmpty()) return;
        Path2D path = new Path2D.Double();
        boolean first = true;
        Point2D end = null;
        for (FlightTrack.Point p : pts) {
            end = toScreen(p.lat(), p.lon(), zi, scale, wcx, wcy, w, h);
            if (first) path.moveTo(end.getX(), end.getY()); else path.lineTo(end.getX(), end.getY());
            first = false;
        }
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0, 0, 0, 90));
        g.draw(path);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{7f, 5f}, 0f));
        g.setColor(new Color(0xb0, 0xb0, 0xa4, 200));
        g.draw(path);
        if (oldTrackName != null && end != null) {
            g.setFont(Theme.headingFont(10.5f));
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(oldTrackName) + 10;
            g.setColor(BOX);
            g.fillRect((int) end.getX() + 8, (int) end.getY() - fm.getHeight() / 2, tw, fm.getHeight() + 2);
            g.setColor(Theme.MUTED);
            g.drawString(oldTrackName, (int) end.getX() + 13, (int) end.getY() + fm.getAscent() / 2 - 1);
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
        g.drawString("L", (float) c.getX() - fm.stringWidth("L") / 2f, (float) c.getY() + fm.getAscent() / 2f - 1.5f);
    }

    /** "1.25 KM  045°": the way from a place to a pin. */
    static String wayText(double fromLat, double fromLon, Pins.Pin p) {
        return distanceText(Mercator.distanceM(fromLat, fromLon, p.lat(), p.lon()))
                + "  " + String.format(Locale.ROOT, "%03d°", Mercator.bearingDeg(fromLat, fromLon, p.lat(), p.lon()));
    }

    /**
     * A pin: a diamond with its name, and the distance and bearing to it from the drone's last position (D) and from
     * the launch point (L), for the one who walks there.
     */
    private void paintPins(Graphics2D g, FlightTrack.Point last, FlightTrack.Point home, int zi, double scale, double wcx, double wcy, int w, int h) {
        for (Pins.Pin p : allPins()) {
            Point2D c = toScreen(p.lat(), p.lon(), zi, scale, wcx, wcy, w, h);
            if (c.getX() < -200 || c.getX() > w + 200 || c.getY() < -200 || c.getY() > h + 200) continue;
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
            g.setColor(BOX);
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

    /** The phone's accuracy round its point: a faint disc, the drone is somewhere in it. */
    private void paintAccuracy(Graphics2D g, FlightTrack.Point last, Point2D c) {
        if (!last.phone() || last.accuracyM() <= 0) return;
        double r = last.accuracyM() / Mercator.metersPerPixel(last.lat(), zoom);
        if (r < 6) return;
        g.setColor(new Color(255, 255, 255, 40));
        g.fill(new Ellipse2D.Double(c.getX() - r, c.getY() - r, 2 * r, 2 * r));
        g.setStroke(new BasicStroke(1.2f));
        g.setColor(new Color(255, 255, 255, 150));
        g.draw(new Ellipse2D.Double(c.getX() - r, c.getY() - r, 2 * r, 2 * r));
    }

    private void paintDrone(Graphics2D g, FlightTrack.Point last, int zi, double scale, double wcx, double wcy, int w, int h) {
        Point2D c = toScreen(last.lat(), last.lon(), zi, scale, wcx, wcy, w, h);
        paintAccuracy(g, last, c);
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
            g.drawString("PHONE" + (last.accuracyM() > 0 ? "  ± " + last.accuracyM() + " M" : ""), (float) c.getX() + 13, (float) c.getY() + 4);
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

    /** + and - at the top right, for zooming without a wheel: the program's square boxes with bracket corners. */
    private void paintZoomButtons(Graphics2D g, int w) {
        int size = 30, gap = 4, x = w - 10 - size;
        zoomInButton = new Rectangle(x, 10, size, size);
        zoomOutButton = new Rectangle(x, 10 + size + gap, size, size);
        for (Rectangle r : new Rectangle[]{zoomInButton, zoomOutButton}) {
            g.setColor(BOX);
            g.fillRect(r.x, r.y, r.width, r.height);
            g.setColor(Theme.LINE);
            g.setStroke(new BasicStroke(1f));
            g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
            brackets(g, r.x, r.y, r.width, r.height, Theme.ACCENT);
        }
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        int cx = x + size / 2, arm = 7;
        int cy = zoomInButton.y + size / 2;
        g.drawLine(cx - arm, cy, cx + arm, cy);
        g.drawLine(cx, cy - arm, cx, cy + arm);
        cy = zoomOutButton.y + size / 2;
        g.drawLine(cx - arm, cy, cx + arm, cy);
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
