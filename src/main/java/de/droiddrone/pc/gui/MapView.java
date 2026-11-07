package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.map.CellCoverage;
import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.map.Mercator;
import de.droiddrone.pc.map.OpenCellId;
import de.droiddrone.pc.map.RadioHorizon;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;
import de.droiddrone.pc.map.TrackPath;

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
    private final Timer settled = new Timer(1200, e -> {
        prefetchVisible();
        rested();
    });
    /**
     * Following the drone the view never rests, so the rest prefetch never fires: every few seconds the levels under
     * the drone are fetched instead, when it has moved.
     */
    private static final int FOLLOW_PREFETCH_MS = 5000;
    private final Timer followPrefetch = new Timer(FOLLOW_PREFETCH_MS, e -> prefetchAroundDrone());
    private boolean movedSincePrefetch;
    /**
     * Earlier flights kept on the map until removed by hand (1.0.41, the pilot: "if one drone flight has happened and
     * then the pilot connects to another have both routes be saved in the map ... must be manually reset or deleted"):
     * each in a colour of its own, under the live one, its name at its end.
     */
    record Kept(String name, List<FlightTrack.Point> points) {}

    private List<Kept> kept = List.of();
    /** The kept flights' lines, thinned (TrackPath), made once when they are set. */
    private List<TrackPath> keptPaths = List.of();
    private java.util.function.IntConsumer onRemoveKept;
    private Runnable onClearKept;
    private static final Color[] KEPT_COLOURS = {new Color(0x4fc3f7), new Color(0xba68c8), new Color(0x81c784),
            new Color(0xffb74d), new Color(0xf06292), new Color(0x90a4ae)};

    /**
     * Every other drone on the network, where its phone says it is (phone app pc.32; 1.0.41, the pilot: "the location
     * of all drones on the network shown in the MAP ... connect to a drone from the map by right clicking it").
     * @param state  what to write under the name: IDLE, FLOWN BY PCBravo, ARMED, DEEP SLEEP ...
     * @param stale  not heard from for a while: drawn grey
     * @param pilot  the PC that flies it (to watch through), or null
     */
    record NetDrone(String name, double lat, double lon, String state, boolean stale, boolean flown, String pilot) {}

    private List<NetDrone> netDrones = List.of();
    /** Right-click on a drone: its name, and true to watch it (through its pilot PC), false to connect and fly. */
    private java.util.function.BiConsumer<String, Boolean> onDroneConnect;

    void setKept(List<Kept> flights, java.util.function.IntConsumer onRemove, Runnable onClear) {
        kept = flights == null ? List.of() : List.copyOf(flights);
        List<TrackPath> paths = new java.util.ArrayList<>();
        for (Kept k : kept) paths.add(TrackPath.of(k.points()));
        keptPaths = paths;
        onRemoveKept = onRemove;
        onClearKept = onClear;
        repaint();
    }

    void setNetDrones(List<NetDrone> drones, java.util.function.BiConsumer<String, Boolean> onConnect) {
        netDrones = drones == null ? List.of() : List.copyOf(drones);
        onDroneConnect = onConnect;
        repaint();
    }

    /** The kept flight whose line passes within 7 px of (x, y), or -1. */
    private int keptAt(int x, int y) {
        TileSource src = effectiveSource();
        int zi = (int) Math.max(0, Math.min(src.maxZoom, Math.floor(zoom)));
        double scale = Math.pow(2, zoom - zi);
        double wcx = Mercator.x(centerLon, zi), wcy = Mercator.y(centerLat, zi);
        int w = getWidth(), h = getHeight();
        for (int i = kept.size() - 1; i >= 0; i--) {
            Point2D prev = null;
            for (FlightTrack.Point p : kept.get(i).points()) {
                Point2D s = toScreen(p.lat(), p.lon(), zi, scale, wcx, wcy, w, h);
                if (prev != null && java.awt.geom.Line2D.ptSegDist(prev.getX(), prev.getY(), s.getX(), s.getY(), x, y) <= 7) return i;
                prev = s;
            }
        }
        return -1;
    }

    /** The other drone drawn within 14 px of (x, y), or null. */
    private NetDrone netDroneAt(int x, int y) {
        TileSource src = effectiveSource();
        int zi = (int) Math.max(0, Math.min(src.maxZoom, Math.floor(zoom)));
        double scale = Math.pow(2, zoom - zi);
        double wcx = Mercator.x(centerLon, zi), wcy = Mercator.y(centerLat, zi);
        for (NetDrone d : netDrones) {
            Point2D s = toScreen(d.lat(), d.lon(), zi, scale, wcx, wcy, getWidth(), getHeight());
            if (s.distance(x, y) <= 14) return d;
        }
        return null;
    }

    /** An earlier flight's track (TRACKS in the map window), drawn muted under the live one; null for none. */
    private List<FlightTrack.Point> oldTrack;
    private TrackPath oldTrackPath;
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
    /**
     * The live track's line, thinned and taken in as it grows (1.0.49), and the track it is of. With it, what update()
     * saw last: a repaint only when something changed (it is asked every 33 ms).
     */
    private TrackPath trackPath;
    private FlightTrack trackPathOf;
    private FlightTrack.Point seenLast;
    private FcTelemetry.Gps seenGps;
    private boolean seenDetections;
    private long repaintAskedAt;
    /** At least this often while there is a drone: its LAST KNOWN age counts on. */
    static final long IDLE_REPAINT_MS = 1000;
    /** While detection marks fade: this often. */
    static final long FADE_REPAINT_MS = 100;
    private Pins pins;
    /** The position format the pilot chose (the map window's or the main window's), for COPY POSITION. */
    private java.util.function.Supplier<Coordinates.Format> coordinateFormat = () -> Coordinates.Format.MGRS;

    // ---- MAP TOOLS (1.0.35): what the pilot draws
    enum Tool { NONE, RULER, LINE, AREA, ARROW, SYMBOL, EXPORT_BOX, ERASER, HORIZON, SEARCH }

    /**
     * RADIO HORIZON (1.0.39): the one mast placed with the tool, what it reaches (null while loading), and a status line.
     * It stays on the map when the tool ends, until the cross on its label is clicked (the GCS SEARCH too).
     */
    private MapGraphics.LatLon horizonMast;
    private RadioHorizon.Result horizon;
    private Path2D horizonShape; // the seen stretches in zoom-0 world pixels, made once per result
    private String horizonStatus;
    private Consumer<MapGraphics.LatLon> onHorizonMast;
    /** GCS SEARCH (1.0.39): the wanted area {minLat, maxLat, minLon, maxLon}, the heat map over it (null while working). */
    private double[] searchArea;
    private RadioHorizon.Search search;
    private BufferedImage searchImage; // one pixel per cell, made once per result
    /** The crosses on the two labels (where they were last painted): a click there takes the result off the map. */
    private Rectangle horizonCross, searchCross;
    private String searchStatus;
    private Consumer<double[]> onSearchArea;
    /**
     * 4G COVERAGE (1.0.44): on or off (the button in MAP TOOLS), the estimate over the area last worked out (null
     * while none), its picture, and the legend's status line. Worked out by the window when the view rests.
     */
    private boolean coverageOn;
    private CellCoverage.Grid coverage;
    private BufferedImage coverageImage; // one pixel per point, made once per estimate
    private String coverageStatus;
    private Consumer<double[]> onViewRested;

    private MapGraphics graphics;
    private Tool tool = Tool.NONE;
    /** The points of the line, arrow or measurement being drawn (a rectangle's corners are selectStart / selectEnd). */
    private final List<MapGraphics.LatLon> drawing = new java.util.ArrayList<>();
    private Point mouseAt;
    /** The last measurement: shown with its legs until the next one, or kept as a graphic (KEEP). */
    private MapGraphics.Item measurement;
    private int drawColor = 0xe31b23, drawWidth = 4;
    private boolean drawFilled;
    private MapGraphics.UnitType symbolUnit = MapGraphics.UnitType.INFANTRY;
    private MapGraphics.Affiliation symbolAffiliation = MapGraphics.Affiliation.FRIEND;
    private MapGraphics.Echelon symbolEchelon = MapGraphics.Echelon.PLATOON;
    private Runnable onToolChanged;
    private Consumer<MapGraphics.Item> onMeasurement;
    private Consumer<double[]> onExportArea;
    private final Runnable graphicsChanged = () -> SwingUtilities.invokeLater(this::repaint);
    /** Painting for a picture (EXPORT): no zoom buttons, no NO POSITION text. */
    private boolean forPicture;
    /** A point picked up with a double-click (the item, which of its points) follows the mouse until a click drops it. */
    private MapGraphics.Item moving, movingLive;
    private int movingIndex = -1;
    private FcTelemetry.Gps gps;
    /** The + and - buttons, drawn on the map (top right); set while painting. */
    private Rectangle zoomInButton = new Rectangle(), zoomOutButton = new Rectangle();
    private int headingDeg = -1;
    /** What the camera sees of the ground (1.0.39), drawn under the drone; null for none. */
    private CameraFootprint.View cameraView;
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
                if (SwingUtilities.isLeftMouseButton(e) && crossAt(e.getPoint())) {
                    crossPressed = true;
                    dragged = false; // a box dragged just before must not swallow the click
                    return;
                }
                crossPressed = false;
                if (SwingUtilities.isRightMouseButton(e)) {
                    if (moving != null) { // the point goes back where it was
                        cancelMoving();
                        return;
                    }
                    if (tool != Tool.NONE) { // a step back in the tool, or out of it
                        undoToolPoint();
                        return;
                    }
                    if (selectNext) { // the pilot changed their mind: no box
                        endSelectMode();
                        return;
                    }
                    TacticalCombo.show(menu(e.getX(), e.getY()), MapView.this, e.getX(), e.getY());
                    return;
                }
                dragged = false;
                if ((tool == Tool.AREA || tool == Tool.EXPORT_BOX || tool == Tool.SEARCH) && SwingUtilities.isLeftMouseButton(e)) {
                    selectStart = selectEnd = e.getPoint(); // the rectangle to draw, or the box to export
                    return;
                }
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
                if (crossPressed) return;
                dragStart = null;
                if (selectStart == null) return;
                Rectangle box = selection();
                selectStart = selectEnd = null;
                repaint();
                if (tool == Tool.AREA || tool == Tool.EXPORT_BOX || tool == Tool.SEARCH) {
                    boxToolDone(box);
                    return;
                }
                // SELECT WITH CURSOR ends either way: a box drawn, or a plain click that meant to call it off
                if (selectingByRequest) endSelectMode();
                selectingByRequest = false;
                if (box.width >= 8 && box.height >= 8) onAreaSelected.accept(area(box));
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || dragged) return;
                if (crossPressed) { // the cross was pressed: that result goes, nothing else happens
                    crossPressed = false;
                    if (horizonCross != null && horizonCross.contains(e.getPoint())) clearHorizon();
                    else if (searchCross != null && searchCross.contains(e.getPoint())) clearSearch();
                    repaint();
                    return;
                }
                if (moving != null) { // the point picked up is dropped here
                    dropMoving();
                    return;
                }
                if (tool == Tool.ERASER) {
                    eraseAt(e.getX(), e.getY());
                    return;
                }
                if (tool == Tool.HORIZON) { // the mast goes here; a second click moves it (one at a time)
                    double[] ll = toLatLon(e.getX(), e.getY());
                    horizonMast = new MapGraphics.LatLon(ll[0], ll[1]);
                    setHorizon(null, "Loading the terrain...");
                    if (onHorizonMast != null) onHorizonMast.accept(horizonMast);
                    return;
                }
                if (tool == Tool.RULER || tool == Tool.LINE || tool == Tool.ARROW || tool == Tool.SYMBOL) {
                    toolClick(e);
                    return;
                }
                if (tool == Tool.NONE && e.getClickCount() == 2 && pickUpAt(e.getX(), e.getY())) return;
                if (tool == Tool.NONE && e.getClickCount() == 1 && clickObserved(e.getX(), e.getY())) return;
                if (zoomInButton.contains(e.getPoint())) zoomBy(1);
                else if (zoomOutButton.contains(e.getPoint())) zoomBy(-1);
                else if (e.getClickCount() == 2) fitTrack();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                tellCursor(e);
                mouseAt = e.getPoint();
                if (moving != null) followMoving(e.getX(), e.getY());
                if (tool != Tool.NONE) repaint(); // the rubber band to the cursor
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

    /** Every pin drawn: the saved ones and, during the tour, the example pins (the pin manager lists these too). */
    List<Pins.Pin> allPins() {
        if (examplePins.isEmpty()) return pins.list();
        List<Pins.Pin> all = new java.util.ArrayList<>(pins.list());
        all.addAll(examplePins);
        return all;
    }

    boolean isExamplePin(Pins.Pin pin) {
        return examplePins.contains(pin);
    }

    /** The pin manager renamed or removed an example pin (null name = removed): it goes along, unsaved as ever. */
    void changeExamplePin(Pins.Pin pin, String name) {
        List<Pins.Pin> now = new java.util.ArrayList<>(examplePins);
        int i = now.indexOf(pin);
        if (i < 0) return;
        if (name == null) now.remove(i);
        else now.set(i, new Pins.Pin(name.trim(), pin.lat(), pin.lon()));
        examplePins = now;
        repaint();
    }

    void setPins(Pins pins) {
        this.pins = pins;
        repaint();
    }

    void setCoordinateFormat(java.util.function.Supplier<Coordinates.Format> format) {
        if (format != null) coordinateFormat = format;
    }

    /** A pin's position as text in the chosen format, put on the clipboard (to paste into GO TO, a message, another map). */
    static String copyPosition(Coordinates.Format format, Pins.Pin pin) {
        String text = Coordinates.format(format, pin.lat(), pin.lon());
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text), null);
        return text;
    }

    /** An earlier flight's track, drawn muted under the live one; null clears it. */
    void setOldTrack(List<FlightTrack.Point> points, String name) {
        oldTrack = points == null || points.isEmpty() ? null : points;
        oldTrackPath = oldTrack == null ? null : TrackPath.of(oldTrack);
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
        FlightTrack.Point p = track == null ? null : track.last();
        double lat = p != null ? p.lat() : centerLat, lon = p != null ? p.lon() : centerLon;
        return chosen != null ? chosen.at(lat, lon) : TileSource.forPosition(lat, lon);
    }

    double zoom() {
        return zoom;
    }

    /**
     * The latest from the link. The first fix centres the map on the drone; with FOLLOW on it stays centred.
     * @param headingDeg the drone's heading from its attitude, or -1 to use the GPS course
     */
    void update(FlightTrack track, FcTelemetry.Gps gps, int headingDeg, boolean fcLost, boolean connected) {
        boolean changed = track != this.track || gps != seenGps || headingDeg != this.headingDeg || fcLost != this.fcLost
                || connected != this.connected;
        this.track = track;
        this.gps = gps;
        seenGps = gps;
        this.headingDeg = headingDeg;
        this.fcLost = fcLost;
        this.connected = connected;
        if (track != trackPathOf) { // another flight: its line afresh
            trackPathOf = track;
            trackPath = track == null ? null : new TrackPath();
        }
        if (trackPath != null && track.size() > trackPath.taken()) trackPath.add(track.pointsFrom(trackPath.taken()));
        FlightTrack.Point p = track == null ? null : track.last();
        if (p != seenLast) {
            seenLast = p;
            changed = true;
        }
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
        // 1.0.49: asked every 33 ms; repainted when something changed, while detection marks fade, and once a second
        // for the ages shown - not 30 times a second whatever happens (the Swing thread also shows the video)
        long now = de.droiddrone.pc.MonoClock.ms();
        boolean fading = !detections.isEmpty();
        if (fading != seenDetections) changed = true;
        seenDetections = fading;
        if (changed || (fading && now - repaintAskedAt >= FADE_REPAINT_MS) || now - repaintAskedAt >= IDLE_REPAINT_MS) {
            repaintAskedAt = now;
            repaint();
        }
    }

    /** The live track's line as drawn (tests). */
    TrackPath trackPath() {
        return trackPath;
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
        rested();
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

    // ------------------------------------------------------------------ overwatch (1.0.47)

    /**
     * A drone in overwatch on the map: where it stands, the ground its camera sees (view, null when its heading isn't
     * known), the detection's direction (bearingDeg, NaN: none), alert = an alert is on (the symbol pulses, red), replay
     * = a clip of it plays (the view as it was, calmer).
     */
    record OverwatchMark(String drone, double lat, double lon, CameraFootprint.View view, double bearingDeg, boolean alert,
                         boolean replay, String label) {}

    private volatile List<OverwatchMark> overwatchMarks = List.of();
    /** Right-click on a drone: CLIPS of it (the map window's clip list). */
    private Consumer<String> onClips;

    void setOverwatchMarks(List<OverwatchMark> marks) {
        List<OverwatchMark> next = marks == null ? List.of() : List.copyOf(marks);
        if (next.equals(overwatchMarks)) return; // asked every refresh: a repaint only when something changed
        overwatchMarks = next;
        repaint();
    }

    void setOnClips(Consumer<String> onClips) {
        this.onClips = onClips;
    }

    /** Whether (lat, lon) is on the map's screen now, with a margin. */
    boolean shows(double lat, double lon, int margin) {
        TileSource src = effectiveSource();
        int zi = (int) Math.max(0, Math.min(src.maxZoom, Math.floor(zoom)));
        double scale = Math.pow(2, zoom - zi);
        Point2D s = toScreen(lat, lon, zi, scale, Mercator.x(centerLon, zi), Mercator.y(centerLat, zi), getWidth(), getHeight());
        return s.getX() >= margin && s.getY() >= margin && s.getX() <= getWidth() - margin && s.getY() <= getHeight() - margin;
    }

    /** Pans to (lat, lon), keeping the zoom (at least 14: close enough to see the ground the camera sees). */
    void panTo(double lat, double lon) {
        centreOn(lat, lon, Math.max(zoom, 14));
    }

    /** The overwatch drone within 16 px of (x, y), or null. */
    private OverwatchMark overwatchAt(int x, int y) {
        TileSource src = effectiveSource();
        int zi = (int) Math.max(0, Math.min(src.maxZoom, Math.floor(zoom)));
        double scale = Math.pow(2, zoom - zi);
        double wcx = Mercator.x(centerLon, zi), wcy = Mercator.y(centerLat, zi);
        for (OverwatchMark m : overwatchMarks) {
            if (toScreen(m.lat(), m.lon(), zi, scale, wcx, wcy, getWidth(), getHeight()).distance(x, y) <= 16) return m;
        }
        return null;
    }

    /**
     * The overwatch drones: the camera's view in the alert colour (orange-red, pale) and its edge, the detection's
     * direction dashed from the drone, rings pulsing out from the drone while its alert is on, and its name. A clip
     * playing shows its view as it was, in the camera view's own pale yellow.
     */
    private void paintOverwatch(Graphics2D g0, int zi, double scale, double wcx, double wcy, int w, int h) {
        List<OverwatchMark> marks = overwatchMarks;
        if (marks.isEmpty()) return;
        Graphics2D g = (Graphics2D) g0.create();
        try {
            double phase = (System.currentTimeMillis() % 1400) / 1400.0;
            for (OverwatchMark m : marks) {
                Color c = m.replay() ? new Color(255, 236, 160) : ALERT;
                Point2D d = toScreen(m.lat(), m.lon(), zi, scale, wcx, wcy, w, h);
                if (m.view() != null) {
                    Path2D shape = new Path2D.Double();
                    for (double[] ll : m.view().outline()) {
                        Point2D p = toScreen(ll[0], ll[1], zi, scale, wcx, wcy, w, h);
                        if (shape.getCurrentPoint() == null) shape.moveTo(p.getX(), p.getY());
                        else shape.lineTo(p.getX(), p.getY());
                    }
                    shape.closePath();
                    g.setColor(Theme.alpha(c, m.alert() ? 60 : 40));
                    g.fill(shape);
                    g.setColor(Theme.alpha(c, 210));
                    g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(shape);
                }
                if (!Double.isNaN(m.bearingDeg())) {
                    double[] end = RadioHorizon.offset(m.lat(), m.lon(), Math.sin(Math.toRadians(m.bearingDeg())) * DETECTION_LINE_M,
                            Math.cos(Math.toRadians(m.bearingDeg())) * DETECTION_LINE_M);
                    Point2D e = toScreen(end[0], end[1], zi, scale, wcx, wcy, w, h);
                    g.setColor(new Color(0, 0, 0, 130));
                    g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new java.awt.geom.Line2D.Double(d, e));
                    g.setColor(Theme.alpha(c, 235));
                    g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{7, 5}, 0));
                    g.draw(new java.awt.geom.Line2D.Double(d, e));
                }
                if (m.alert()) {
                    for (int i = 0; i < 2; i++) {
                        double p = (phase + i * 0.5) % 1.0;
                        double r = 10 + 30 * p;
                        g.setColor(Theme.alpha(ALERT, (int) Math.round(220 * (1 - p))));
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
                    if (bx + tw > w - 4) bx = (int) d.getX() - 14 - tw;
                    g.setColor(BOX);
                    g.fillRect(bx, by, tw, fm.getHeight() + 2);
                    g.setColor(c);
                    g.drawString(m.label(), bx + 5, by + fm.getAscent() + 1);
                }
            }
        } finally {
            g.dispose();
        }
    }

    /** The overwatch alert's colour: a red that isn't the map's accent or the warnings' yellow. */
    static final Color ALERT = new Color(0xff4d3a);

    private JPopupMenu menu(int x, int y) {
        JPopupMenu menu = TacticalCombo.menu();
        menu.setBackground(Theme.PANEL_2);
        NetDrone drone = netDroneAt(x, y);
        OverwatchMark watching = overwatchAt(x, y);
        String clipsOf = watching != null ? watching.drone() : drone != null ? drone.name() : null;
        if (clipsOf != null && onClips != null) { // 1.0.47: the drone's overwatch clips
            JMenuItem clips = new JMenuItem("Clips of " + clipsOf + " (overwatch)...");
            clips.addActionListener(e -> onClips.accept(clipsOf));
            menu.add(clips);
            if (drone == null) menu.addSeparator();
        }
        if (drone != null && onDroneConnect != null) {
            if (drone.flown()) {
                JMenuItem watch = new JMenuItem(drone.pilot() != null ? "Watch " + drone.name() + " (" + drone.pilot() + " flies it)"
                        : "Watch " + drone.name() + " (its pilot PC isn't known yet)");
                watch.setEnabled(drone.pilot() != null);
                watch.addActionListener(e -> onDroneConnect.accept(drone.name(), true));
                menu.add(watch);
            }
            JMenuItem fly = new JMenuItem(drone.flown() ? "Connect to " + drone.name() + " - another PC flies it, so this one watches"
                    : "Connect to " + drone.name() + " and fly it");
            fly.addActionListener(e -> onDroneConnect.accept(drone.name(), false));
            menu.add(fly);
            menu.addSeparator();
        }
        int flight = keptAt(x, y);
        if (flight >= 0 && onRemoveKept != null) {
            JMenuItem remove = new JMenuItem("Remove the flight " + kept.get(flight).name() + " from the map");
            remove.addActionListener(e -> onRemoveKept.accept(flight));
            menu.add(remove);
        }
        if (!kept.isEmpty() && onClearKept != null) {
            JMenuItem clear = new JMenuItem("Remove all " + kept.size() + " earlier flight" + (kept.size() == 1 ? "" : "s") + " from the map...");
            clear.addActionListener(e -> onClearKept.run());
            menu.add(clear);
            menu.addSeparator();
        }
        MapGraphics.Item item = itemAt(x, y);
        if (item != null) {
            JMenuItem rename = new JMenuItem("Rename " + item.name() + "...");
            rename.addActionListener(e -> renameItem(item));
            menu.add(rename);
            if (item.kind() == MapGraphics.Kind.MEASUREMENT) {
                JMenuItem profile = new JMenuItem("Elevation profile of " + item.name());
                profile.addActionListener(e -> {
                    if (onMeasurement != null) onMeasurement.accept(item);
                });
                menu.add(profile);
            }
            JMenuItem remove = new JMenuItem("Remove " + item.name());
            remove.addActionListener(e -> graphics.remove(item));
            menu.add(remove);
            menu.addSeparator();
        }
        if (pins != null) {
            Pins.Pin hit = pinAt(x, y);
            if (hit != null) {
                String position = Coordinates.format(coordinateFormat.get(), hit.lat(), hit.lon());
                JMenuItem copy = new JMenuItem("Copy " + hit.name() + "'s position (" + position + ")");
                copy.addActionListener(e -> copyPosition(coordinateFormat.get(), hit));
                menu.add(copy);
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

    // ------------------------------------------------------------------ MAP TOOLS (1.0.35)

    /** The drawings shared with the map window and the minimap: both repaint when it changes. */
    void setGraphics(MapGraphics g) {
        graphics = g;
        if (g != null) g.onChange(graphicsChanged);
        repaint();
    }

    MapGraphics graphics() {
        return graphics;
    }

    /** The tool on the map; NONE ends it. The same tool again ends it too (the panel's button is a toggle). */
    void setTool(Tool t) {
        if (tool == t) {
            cancelTool();
            return;
        }
        if (tool != Tool.NONE) cancelTool();
        tool = t;
        drawing.clear();
        if (t == Tool.RULER && measurement != null) {
            measurement = null; // the next measurement takes the place of the last
            if (onMeasurement != null) onMeasurement.accept(null);
        }
        setCursor(t == Tool.NONE ? java.awt.Cursor.getDefaultCursor() : java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR));
        repaint();
        toolChanged();
    }

    Tool tool() {
        return tool;
    }

    /** Esc: the tool ends, what it was drawing is dropped; a point picked up goes back. */
    void cancelTool() {
        if (moving != null) cancelMoving();
        tool = Tool.NONE;
        drawing.clear();
        selectStart = selectEnd = null;
        setCursor(java.awt.Cursor.getDefaultCursor());
        repaint();
        toolChanged();
    }

    /** Enter: the line, arrow or measurement ends at its last point. */
    void finishTool() {
        if (tool == Tool.RULER || tool == Tool.LINE || tool == Tool.ARROW) finishDrawing(false);
        else cancelTool();
    }

    void setDrawStyle(int colorRgb, int widthPx, boolean filled) {
        drawColor = colorRgb;
        drawWidth = widthPx;
        drawFilled = filled;
    }

    void setSymbolStyle(MapGraphics.UnitType unit, MapGraphics.Affiliation affiliation, MapGraphics.Echelon echelon) {
        symbolUnit = unit;
        symbolAffiliation = affiliation;
        symbolEchelon = echelon;
        if (tool == Tool.SYMBOL) repaint();
    }

    /** Told where the RADIO HORIZON's mast was put, and null when it went with the tool. */
    void setOnHorizonMast(Consumer<MapGraphics.LatLon> listener) {
        onHorizonMast = listener;
    }

    MapGraphics.LatLon horizonMast() {
        return horizonMast;
    }

    RadioHorizon.Result horizon() {
        return horizon;
    }

    /** What the mast reaches (null: not yet), and the line shown under the mast (null: none). */
    void setHorizon(RadioHorizon.Result result, String status) {
        horizonStatus = status;
        if (result != horizon) {
            horizon = result;
            horizonShape = result == null ? null : horizonShape(result);
        }
        repaint();
    }

    private boolean crossPressed;
    /** The radio horizon, the GCS search and the ruler's measurement on the map: only while MAP TOOLS' TOOLS list is out (hidden, not cleared). */
    private boolean toolOverlaysShown = true;

    void setToolOverlaysShown(boolean shown) {
        if (toolOverlaysShown == shown) return;
        toolOverlaysShown = shown;
        if (!shown) { // not painted: their crosses take no clicks
            horizonCross = null;
            searchCross = null;
        }
        repaint();
    }

    boolean toolOverlaysShown() {
        return toolOverlaysShown;
    }

    private boolean crossAt(Point p) {
        return (horizonCross != null && horizonCross.contains(p)) || (searchCross != null && searchCross.contains(p));
    }

    /** Where the crosses are (null: not shown), for tests. */
    Rectangle horizonCross() {
        return horizonCross;
    }

    Rectangle searchCross() {
        return searchCross;
    }

    /** The cross on the radio horizon's label: the mast and what it reaches go, and the window is told (null). */
    void clearHorizon() {
        boolean had = horizonMast != null;
        horizonMast = null;
        horizon = null;
        horizonShape = null;
        horizonStatus = null;
        horizonCross = null;
        repaint();
        if (had && onHorizonMast != null) onHorizonMast.accept(null);
    }

    /** Told the GCS SEARCH's wanted area {minLat, maxLat, minLon, maxLon}, and null when it went with the tool. */
    void setOnSearchArea(Consumer<double[]> listener) {
        onSearchArea = listener;
    }

    double[] searchArea() {
        return searchArea;
    }

    RadioHorizon.Search search() {
        return search;
    }

    /** The heat map (null: not yet) and the line shown over the wanted area (null: none). */
    void setSearch(RadioHorizon.Search result, String status) {
        searchStatus = status;
        if (result != search) {
            search = result;
            searchImage = result == null ? null : searchImage(result);
        }
        repaint();
    }

    /** The cross on the GCS SEARCH's label: the heat map and the area go, and the window is told (null). */
    void clearSearch() {
        boolean had = searchArea != null;
        searchArea = null;
        search = null;
        searchImage = null;
        searchStatus = null;
        searchCross = null;
        repaint();
        if (had && onSearchArea != null) onSearchArea.accept(null);
    }

    /** Told the visible area {minLat, maxLat, minLon, maxLon} when the map has rested on a place (and every few seconds while following). */
    void setOnViewRested(Consumer<double[]> listener) {
        onViewRested = listener;
    }

    private void rested() {
        if (onViewRested != null && isShowing() && getWidth() >= 16) onViewRested.accept(visibleArea());
    }

    /** 4G COVERAGE on or off; the estimate (null: none yet, the old one stays while a new one is worked out) and its status line. */
    void setCoverage(boolean on, CellCoverage.Grid grid, String status) {
        coverageOn = on;
        coverageStatus = status;
        if (grid != coverage) {
            coverage = grid;
            coverageImage = grid == null ? null : coverageImage(grid);
        }
        repaint();
    }

    boolean coverageOn() {
        return coverageOn;
    }

    CellCoverage.Grid coverage() {
        return coverage;
    }

    String coverageStatus() {
        return coverageStatus;
    }

    /** The levels as see-through colours, the GCS SEARCH's red - yellow - green with orange between; unknown left clear. */
    static final Color[] COVERAGE_COLOURS = {new Color(227, 27, 35, 110), new Color(255, 140, 0, 110), new Color(252, 212, 5, 100),
            new Color(46, 204, 64, 95)};
    static final String[] COVERAGE_NAMES = {"NONE", "WEAK", "FAIR", "GOOD"};

    private static BufferedImage coverageImage(CellCoverage.Grid g) {
        BufferedImage img = new BufferedImage(g.cols(), g.rows(), BufferedImage.TYPE_INT_ARGB);
        for (int r = 0; r < g.rows(); r++) {
            for (int c = 0; c < g.cols(); c++) {
                int v = g.at(c, r);
                if (v >= 0) img.setRGB(c, r, COVERAGE_COLOURS[v].getRGB());
            }
        }
        return img;
    }

    /** A mast's dot: Telenor blue, Telia purple, Ice orange, others grey. */
    static Color operatorColour(OpenCellId.Operator op) {
        if (op == null) return new Color(0x9e9e9e);
        return switch (op) {
            case TELENOR -> new Color(0x2196f3);
            case TELIA -> new Color(0xb455f0);
            case ICE -> new Color(0xff8c1a);
            default -> new Color(0x9e9e9e);
        };
    }

    static final int SEARCH_BUCKETS = 11; // 0 %, 10 % ... 100 %

    /** The heat map as a picture, a pixel per cell (row 0 north), see-through outside the 30 km circle. */
    private static BufferedImage searchImage(RadioHorizon.Search r) {
        BufferedImage img = new BufferedImage(r.size(), r.size(), BufferedImage.TYPE_INT_ARGB);
        int[] colours = new int[SEARCH_BUCKETS];
        for (int i = 0; i < SEARCH_BUCKETS; i++) colours[i] = searchColour(i / (double) (SEARCH_BUCKETS - 1)).getRGB();
        for (int cy = 0; cy < r.size(); cy++) {
            for (int cx = 0; cx < r.size(); cx++) {
                float v = r.at(cx, cy);
                if (!Float.isNaN(v)) img.setRGB(cx, cy, colours[Math.round(v * (SEARCH_BUCKETS - 1))]);
            }
        }
        return img;
    }

    /** Red (no point of the area sees a mast there) through yellow to green (every point does). */
    static Color searchColour(double v) {
        Color bad = new Color(227, 27, 35), mid = new Color(252, 212, 5), good = new Color(46, 204, 64);
        Color a = v < 0.5 ? bad : mid, b = v < 0.5 ? mid : good;
        double t = v < 0.5 ? v * 2 : (v - 0.5) * 2;
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t), (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t), 110);
    }

    /** One quad per seen stretch, a degree wide, in zoom-0 world pixels (painted through a transform at any zoom). */
    private static Path2D horizonShape(RadioHorizon.Result r) {
        Path2D path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (int b = 0; b < r.spans().length; b++) {
            float[] s = r.spans()[b];
            for (int i = 0; i + 1 < s.length; i += 2) {
                double[] a = worldAt(r, b - 0.5, s[i]), c = worldAt(r, b + 0.5, s[i]);
                double[] d = worldAt(r, b + 0.5, s[i + 1]), e = worldAt(r, b - 0.5, s[i + 1]);
                path.moveTo(a[0], a[1]);
                path.lineTo(c[0], c[1]);
                path.lineTo(d[0], d[1]);
                path.lineTo(e[0], e[1]);
                path.closePath();
            }
        }
        return path;
    }

    /** The zoom-0 world pixel distM from the mast on a bearing. */
    private static double[] worldAt(RadioHorizon.Result r, double bearingDeg, double distM) {
        double[] ll = RadioHorizon.destination(r.lat(), r.lon(), bearingDeg, distM);
        return new double[]{Mercator.x(ll[1], 0), Mercator.y(ll[0], 0)};
    }

    /** Called when a tool starts or ends, or the measurement comes or goes (the panel lights its buttons). */
    void setOnToolChanged(Runnable listener) {
        onToolChanged = listener;
    }

    /** Gets the measurement to show the elevation profile of; null when it went. */
    void setOnMeasurement(Consumer<MapGraphics.Item> listener) {
        onMeasurement = listener;
    }

    /** Gets the box drawn with the EXPORT tool as {minLat, maxLat, minLon, maxLon}. */
    void setOnExportArea(Consumer<double[]> listener) {
        onExportArea = listener;
    }

    MapGraphics.Item currentMeasurement() {
        return measurement;
    }

    /** KEEP: the measurement becomes a graphic (named Measurement n), kept between sessions. */
    void keepMeasurement() {
        if (measurement == null || graphics == null) return;
        MapGraphics.Item kept = graphics.add(measurement.withName(graphics.nextName(MapGraphics.Kind.MEASUREMENT, null)));
        measurement = null;
        if (onMeasurement != null) onMeasurement.accept(kept);
        toolChanged();
        repaint();
    }

    boolean hasMeasurement() {
        return measurement != null;
    }

    void clearMeasurement() {
        if (measurement == null) return;
        measurement = null;
        if (onMeasurement != null) onMeasurement.accept(null);
        toolChanged();
        repaint();
    }

    private void toolChanged() {
        if (onToolChanged != null) onToolChanged.run();
    }

    /** A left click with a point tool: the symbol placed, or a point added; a double-click or the first point again ends. */
    private void toolClick(MouseEvent e) {
        double[] ll = toLatLon(e.getX(), e.getY());
        MapGraphics.LatLon at = new MapGraphics.LatLon(ll[0], ll[1]);
        if (tool == Tool.SYMBOL) {
            if (graphics != null) {
                graphics.add(MapGraphics.Item.symbol(graphics.nextName(MapGraphics.Kind.SYMBOL, symbolUnit), at, symbolUnit, symbolAffiliation, symbolEchelon));
            }
            endTool();
            return;
        }
        if (e.getClickCount() >= 2) { // the double-click's first click added the point already
            finishDrawing(false);
            return;
        }
        if (tool == Tool.RULER && drawing.size() >= 3) {
            Point2D first = screenOf(drawing.get(0));
            if (first.distance(e.getPoint()) <= 10) { // back at the first point: the loop closes, the area counts
                finishDrawing(true);
                return;
            }
        }
        drawing.add(at);
        repaint();
    }

    /** A right click while a tool is on: the last point taken back; nothing left, or a box or symbol tool: the tool ends. */
    private void undoToolPoint() {
        if (!drawing.isEmpty()) {
            drawing.remove(drawing.size() - 1);
            if (drawing.isEmpty()) cancelTool();
            else repaint();
            return;
        }
        cancelTool();
    }

    private void finishDrawing(boolean closed) {
        List<MapGraphics.LatLon> pts = new java.util.ArrayList<>(drawing);
        if (pts.size() < 2) {
            cancelTool();
            return;
        }
        switch (tool) {
            case RULER -> {
                measurement = MapGraphics.Item.measurement("Measurement", pts, closed);
                if (onMeasurement != null) onMeasurement.accept(measurement);
            }
            case LINE -> {
                if (graphics != null) graphics.add(MapGraphics.Item.line(graphics.nextName(MapGraphics.Kind.LINE, null), pts, drawColor, drawWidth));
            }
            case ARROW -> {
                if (graphics != null) graphics.add(MapGraphics.Item.arrow(graphics.nextName(MapGraphics.Kind.ARROW, null), pts, drawColor, drawWidth));
            }
            default -> {
            }
        }
        endTool();
    }

    /** The rectangle drawn, or the box to export. */
    private void boxToolDone(Rectangle box) {
        if (tool == Tool.SEARCH) { // the wanted area: the heat map is worked out round it; a new box replaces it, the tool stays
            if (box.width < 8 || box.height < 8) return;
            searchArea = area(box);
            setSearch(null, "Loading the terrain...");
            if (onSearchArea != null) onSearchArea.accept(searchArea);
            return;
        }
        if (box.width < 8 || box.height < 8) {
            cancelTool();
            return;
        }
        if (tool == Tool.AREA && graphics != null) {
            double[] nw = toLatLon(box.x, box.y), se = toLatLon(box.x + box.width, box.y + box.height);
            List<MapGraphics.LatLon> corners = List.of(new MapGraphics.LatLon(nw[0], nw[1]), new MapGraphics.LatLon(nw[0], se[1]),
                    new MapGraphics.LatLon(se[0], se[1]), new MapGraphics.LatLon(se[0], nw[1]));
            graphics.add(MapGraphics.Item.area(graphics.nextName(MapGraphics.Kind.AREA, null), corners, drawColor, drawWidth, drawFilled));
        } else if (tool == Tool.EXPORT_BOX && onExportArea != null) {
            onExportArea.accept(area(box));
        }
        endTool();
    }

    private void endTool() {
        tool = Tool.NONE;
        drawing.clear();
        setCursor(java.awt.Cursor.getDefaultCursor());
        repaint();
        toolChanged();
    }

    private void renameItem(MapGraphics.Item item) {
        String name = (String) javax.swing.JOptionPane.showInputDialog(this, "New name", "Rename " + item.name(),
                javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, item.name());
        if (name == null || name.isBlank() || graphics == null) return;
        graphics.rename(item, name);
    }

    /** The view's numbers for the current zoom and centre: {zi, scale, wcx, wcy}. */
    private double[] view() {
        TileSource src = effectiveSource();
        int zi = (int) Math.max(0, Math.min(src.maxZoom, Math.floor(zoom)));
        double scale = Math.pow(2, zoom - zi);
        return new double[]{zi, scale, Mercator.x(centerLon, zi), Mercator.y(centerLat, zi)};
    }

    private Point2D screenOf(MapGraphics.LatLon p) {
        double[] v = view();
        return toScreen(p.lat(), p.lon(), (int) v[0], v[1], v[2], v[3], getWidth(), getHeight());
    }

    /** The drawing or symbol under a pixel (symbols first, then lines within 6 px of a leg), or null. */
    MapGraphics.Item itemAt(int x, int y) {
        if (graphics == null) return null;
        List<MapGraphics.Item> items = graphics.list();
        if (measurement != null && toolOverlaysShown) items.add(measurement); // hidden with TOOLS: not picked either
        double[] v = view();
        int zi = (int) v[0];
        for (MapGraphics.Item it : items) {
            if (it.kind() != MapGraphics.Kind.SYMBOL) continue;
            Point2D c = toScreen(it.points().get(0).lat(), it.points().get(0).lon(), zi, v[1], v[2], v[3], getWidth(), getHeight());
            if (UnitSymbol.bounds((int) c.getX(), (int) c.getY(), it.unit(), it.affiliation()).contains(x, y)) return it;
        }
        for (MapGraphics.Item it : items) {
            if (it.kind() == MapGraphics.Kind.SYMBOL) continue;
            List<MapGraphics.LatLon> pts = it.points();
            boolean around = it.closed() || it.kind() == MapGraphics.Kind.AREA;
            int legs = around ? pts.size() : pts.size() - 1;
            for (int i = 0; i < legs; i++) {
                MapGraphics.LatLon a = pts.get(i), b = pts.get((i + 1) % pts.size());
                Point2D sa = toScreen(a.lat(), a.lon(), zi, v[1], v[2], v[3], getWidth(), getHeight());
                Point2D sb = toScreen(b.lat(), b.lon(), zi, v[1], v[2], v[3], getWidth(), getHeight());
                if (java.awt.geom.Line2D.ptSegDist(sa.getX(), sa.getY(), sb.getX(), sb.getY(), x, y) <= 6) return it;
            }
        }
        return null;
    }

    /** The ERASER's click: the drawing or symbol under it goes (the measurement too). */
    private void eraseAt(int x, int y) {
        MapGraphics.Item hit = itemAt(x, y);
        if (hit == null) return;
        if (hit == measurement) clearMeasurement();
        else if (graphics != null) graphics.remove(hit);
    }

    /**
     * A double-click on a point of a drawing, or on a symbol: picked up; it follows the mouse until a click drops it
     * (a right click or Esc puts it back). The measurement's points too.
     * @return true if something was picked up
     */
    private boolean pickUpAt(int x, int y) {
        if (graphics == null) return false;
        List<MapGraphics.Item> items = graphics.list();
        if (measurement != null && toolOverlaysShown) items.add(measurement); // hidden with TOOLS: not picked either
        double[] v = view();
        for (MapGraphics.Item it : items) {
            List<MapGraphics.LatLon> pts = it.points();
            for (int i = 0; i < pts.size(); i++) {
                Point2D c = toScreen(pts.get(i).lat(), pts.get(i).lon(), (int) v[0], v[1], v[2], v[3], getWidth(), getHeight());
                boolean hit = it.kind() == MapGraphics.Kind.SYMBOL
                        ? UnitSymbol.bounds((int) c.getX(), (int) c.getY(), it.unit(), it.affiliation()).contains(x, y)
                        : c.distance(x, y) <= 8;
                if (hit) {
                    moving = it;
                    movingIndex = i;
                    movingLive = it;
                    setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR));
                    repaint();
                    return true;
                }
            }
        }
        return false;
    }

    private void followMoving(int x, int y) {
        double[] ll = toLatLon(x, y);
        List<MapGraphics.LatLon> pts = new java.util.ArrayList<>(moving.points());
        pts.set(movingIndex, new MapGraphics.LatLon(ll[0], ll[1]));
        movingLive = moving.withPoints(pts);
        repaint();
    }

    private void dropMoving() {
        MapGraphics.Item was = moving, now = movingLive;
        moving = movingLive = null;
        movingIndex = -1;
        setCursor(java.awt.Cursor.getDefaultCursor());
        if (was == measurement) {
            measurement = now;
            if (onMeasurement != null) onMeasurement.accept(now);
        } else if (graphics != null && now != was) {
            graphics.replace(was, now);
        }
        repaint();
    }

    private void cancelMoving() {
        moving = movingLive = null;
        movingIndex = -1;
        setCursor(tool == Tool.NONE ? java.awt.Cursor.getDefaultCursor() : java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR));
        repaint();
    }

    /** Something is being moved: for the window (Esc puts it back). */
    boolean isMoving() {
        return moving != null;
    }

    /**
     * PLACE AT: a typed position. With the ruler, a line or an arrow on, the point joins what is being drawn; otherwise
     * a symbol in the chosen style goes there. The map centres on it.
     */
    void placeAt(double lat, double lon) {
        MapGraphics.LatLon at = new MapGraphics.LatLon(lat, lon);
        if (tool == Tool.RULER || tool == Tool.LINE || tool == Tool.ARROW) {
            drawing.add(at);
        } else if (graphics != null) {
            graphics.add(MapGraphics.Item.symbol(graphics.nextName(MapGraphics.Kind.SYMBOL, symbolUnit), at, symbolUnit, symbolAffiliation, symbolEchelon));
            if (tool == Tool.SYMBOL) endTool();
        }
        setFollow(false);
        centreOn(lat, lon, zoom);
    }

    // ---- painting the tools

    /** Blue, not green: it reads over the GCS SEARCH's red - yellow - green heat map (the pilot: both at the same time). */
    private static final Color HORIZON_SEEN = new Color(30, 144, 255, 120);

    /** GCS SEARCH: the heat map, the 30 km circle round the area's centre, the area's box, the best place. */
    private void paintSearch(Graphics2D g0, int zi, double scale, double wcx, double wcy, int w, int h) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            if (searchImage != null) { // the grid's outer corners on screen; cells stay sharp (nearest neighbour)
                double half = search.size() / 2.0 * RadioHorizon.SEARCH_CELL_M;
                double dLat = half / 111_320.0, dLon = half / (111_320.0 * Math.cos(Math.toRadians(search.lat())));
                Point2D tl = toScreen(search.lat() + dLat, search.lon() - dLon, zi, scale, wcx, wcy, w, h);
                Point2D br = toScreen(search.lat() - dLat, search.lon() + dLon, zi, scale, wcx, wcy, w, h);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(searchImage, (int) Math.round(tl.getX()), (int) Math.round(tl.getY()), (int) Math.round(br.getX()),
                        (int) Math.round(br.getY()), 0, 0, searchImage.getWidth(), searchImage.getHeight(), null);
            }
            double cLat = (searchArea[0] + searchArea[1]) / 2, cLon = (searchArea[2] + searchArea[3]) / 2;
            Point2D c = toScreen(cLat, cLon, zi, scale, wcx, wcy, w, h);
            double pxPerM = scale / Mercator.metersPerPixel(cLat, zi);
            double r = RadioHorizon.MAX_RANGE_M * pxPerM;
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{8f, 6f}, 0f));
            g.setColor(Theme.ACCENT);
            g.draw(new Ellipse2D.Double(c.getX() - r, c.getY() - r, 2 * r, 2 * r));
            Point2D nw = toScreen(searchArea[1], searchArea[2], zi, scale, wcx, wcy, w, h);
            Point2D se = toScreen(searchArea[0], searchArea[3], zi, scale, wcx, wcy, w, h);
            g.setStroke(new BasicStroke(2f));
            g.setColor(Color.WHITE);
            g.draw(new Rectangle((int) Math.round(nw.getX()), (int) Math.round(nw.getY()),
                    (int) Math.round(se.getX() - nw.getX()), (int) Math.round(se.getY() - nw.getY())));
            int labelY = (int) Math.round(se.getY()) + 4;
            if (search != null) { // the best place: a ring and its share
                Point2D b = toScreen(search.bestLat(), search.bestLon(), zi, scale, wcx, wcy, w, h);
                int bx = (int) Math.round(b.getX()), by = (int) Math.round(b.getY());
                g.setStroke(new BasicStroke(3f));
                g.setColor(Color.BLACK);
                g.drawOval(bx - 9, by - 9, 18, 18);
                g.setStroke(new BasicStroke(2f));
                g.setColor(Color.WHITE);
                g.drawOval(bx - 9, by - 9, 18, 18);
                g.drawLine(bx - 13, by, bx - 5, by);
                g.drawLine(bx + 5, by, bx + 13, by);
                g.drawLine(bx, by - 13, bx, by - 5);
                g.drawLine(bx, by + 5, bx, by + 13);
                label(g, "BEST " + Math.round(search.bestScore() * 100) + " %", bx, by + 16);
            }
            searchCross = labelWithCross(g, searchStatus != null ? searchStatus : "GCS SEARCH", (int) Math.round((nw.getX() + se.getX()) / 2), labelY);
        } finally {
            g.dispose();
        }
    }

    /** Masts are drawn from this zoom in (further out they would cover the estimate). */
    static final double COVERAGE_MASTS_ZOOM = 11;

    /**
     * 4G COVERAGE: the estimate's colours over the map, the masts as small dots in their network's colour, and the
     * legend bottom left - that it is an estimate, the colours, what it is for, the status, and OpenCelliD's attribution.
     */
    private void paintCoverage(Graphics2D g0, int zi, double scale, double wcx, double wcy, int w, int h) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            CellCoverage.Grid grid = coverage;
            if (grid != null && coverageImage != null) {
                double k = Math.pow(2, zi) * scale; // zoom-0 world pixels to screen
                int sx0 = (int) Math.round((grid.x0() * Math.pow(2, zi) - wcx) * scale + w / 2.0);
                int sy0 = (int) Math.round((grid.y0() * Math.pow(2, zi) - wcy) * scale + h / 2.0);
                int sx1 = (int) Math.round(sx0 + (grid.x1() - grid.x0()) * k), sy1 = (int) Math.round(sy0 + (grid.y1() - grid.y0()) * k);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(coverageImage, sx0, sy0, sx1, sy1, 0, 0, coverageImage.getWidth(), coverageImage.getHeight(), null);
                if (zoom >= COVERAGE_MASTS_ZOOM) {
                    g.setStroke(new BasicStroke(1.5f));
                    for (CellCoverage.Site s : grid.sites()) {
                        Point2D p = toScreen(s.lat(), s.lon(), zi, scale, wcx, wcy, w, h);
                        if (p.getX() < -8 || p.getY() < -8 || p.getX() > w + 8 || p.getY() > h + 8) continue;
                        int x = (int) Math.round(p.getX()), y = (int) Math.round(p.getY());
                        g.setColor(operatorColour(s.operator()));
                        g.fillOval(x - 4, y - 4, 9, 9);
                        g.setColor(Color.BLACK);
                        g.drawOval(x - 4, y - 4, 9, 9);
                        if (s.nr()) { // 5G there too: a white ring round it
                            g.setColor(Color.WHITE);
                            g.drawOval(x - 7, y - 7, 15, 15);
                        }
                    }
                }
            }
            paintCoverageLegend(g, grid, h);
        } finally {
            g.dispose();
        }
    }

    /** Bottom left over the scale bar: the top middle has NO DRONE POSITION, the top right the zoom buttons. */
    private void paintCoverageLegend(Graphics2D g, CellCoverage.Grid grid, int h) {
        g.setFont(Theme.headingFont(11f));
        FontMetrics fm = g.getFontMetrics();
        Font small = Theme.font(Font.PLAIN, 10.5f);
        FontMetrics sm = g.getFontMetrics(small);
        String title = "4G COVERAGE (ESTIMATE)" + (grid != null ? String.format(Locale.ROOT, "   DRONE %.0f M   %s", grid.droneM(),
                grid.operator().title.toUpperCase(Locale.ROOT)) : "");
        String status = coverageStatus == null ? "" : coverageStatus;
        int lh = fm.getHeight(), sh = sm.getHeight();
        int swatchW = 0;
        for (String n : COVERAGE_NAMES) swatchW += 14 + fm.stringWidth(n) + 12;
        int bw = Math.max(Math.max(fm.stringWidth(title), swatchW), Math.max(sm.stringWidth(status), sm.stringWidth(OpenCellId.ATTRIBUTION))) + 16;
        int bh = lh + lh + 4 + (status.isEmpty() ? 0 : sh) + sh + 10;
        int x = 4, y = h - 50 - bh;
        g.setColor(BOX);
        g.fillRect(x, y, bw, bh);
        g.setColor(Theme.ACCENT);
        g.fillRect(x, y, 3, bh);
        int ty = y + 4 + fm.getAscent();
        g.setColor(Theme.TEXT);
        g.drawString(title, x + 8, ty);
        ty += lh + 2;
        int sx = x + 8;
        for (int i = COVERAGE_NAMES.length - 1; i >= 0; i--) { // GOOD first
            Color c = COVERAGE_COLOURS[i];
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue()));
            g.fillRect(sx, ty - fm.getAscent() + 2, 10, 10);
            g.setColor(Theme.TEXT);
            g.drawString(COVERAGE_NAMES[i], sx + 14, ty);
            sx += 14 + fm.stringWidth(COVERAGE_NAMES[i]) + 12;
        }
        g.setFont(small);
        ty += sh + 2;
        if (!status.isEmpty()) {
            g.setColor(Theme.TEXT);
            g.drawString(status, x + 8, ty);
            ty += sh;
        }
        g.setColor(Theme.MUTED);
        g.drawString(OpenCellId.ATTRIBUTION, x + 8, ty);
    }

    /** A line of text on a dark box, centred on x, from y down. */
    private static void label(Graphics2D g, String text, int x, int y) {
        g.setFont(Theme.headingFont(11f));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text) + 10;
        g.setColor(new Color(10, 10, 10, 215));
        g.fillRect(x - tw / 2, y, tw, fm.getHeight() + 2);
        g.setColor(Theme.TEXT);
        g.drawString(text, x - tw / 2 + 5, y + 1 + fm.getAscent());
    }

    /** The label with a cross at its right end (in the accent colour, like the tour's EXIT); the cross's box. */
    private static Rectangle labelWithCross(Graphics2D g, String text, int x, int y) {
        g.setFont(Theme.headingFont(11f));
        FontMetrics fm = g.getFontMetrics();
        int bh = fm.getHeight() + 2, tw = fm.stringWidth(text) + 10, all = tw + bh;
        int x0 = x - all / 2;
        g.setColor(new Color(10, 10, 10, 215));
        g.fillRect(x0, y, all, bh);
        g.setColor(Theme.TEXT);
        g.drawString(text, x0 + 5, y + 1 + fm.getAscent());
        Rectangle cross = new Rectangle(x0 + tw, y, bh, bh);
        g.setColor(Theme.ACCENT);
        g.fillRect(cross.x, cross.y, cross.width, cross.height);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int m = 5;
        g.drawLine(cross.x + m, cross.y + m, cross.x + cross.width - m, cross.y + cross.height - m);
        g.drawLine(cross.x + cross.width - m, cross.y + m, cross.x + m, cross.y + cross.height - m);
        return cross;
    }

    /** RADIO HORIZON: the seen ground blue, the 30 km limit and the smooth-earth horizon as dashed rings, the mast. */
    private void paintHorizon(Graphics2D g0, int zi, double scale, double wcx, double wcy, int w, int h) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            if (horizonShape != null) {
                double k = Math.pow(2, zi) * scale;
                AffineTransform t = new AffineTransform();
                t.translate(w / 2.0 - wcx * scale, h / 2.0 - wcy * scale);
                t.scale(k, k);
                // no antialiasing: the quads' shared edges would show as seams
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
                g.setColor(HORIZON_SEEN);
                g.fill(t.createTransformedShape(horizonShape));
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            }
            Point2D c = toScreen(horizonMast.lat(), horizonMast.lon(), zi, scale, wcx, wcy, w, h);
            double pxPerM = scale / Mercator.metersPerPixel(horizonMast.lat(), zi);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{8f, 6f}, 0f));
            g.setColor(Theme.ACCENT);
            double r = RadioHorizon.MAX_RANGE_M * pxPerM;
            g.draw(new Ellipse2D.Double(c.getX() - r, c.getY() - r, 2 * r, 2 * r));
            if (horizon != null && horizon.smoothEarthM() < RadioHorizon.MAX_RANGE_M) {
                double rs = horizon.smoothEarthM() * pxPerM;
                g.setColor(Theme.TEXT);
                g.draw(new Ellipse2D.Double(c.getX() - rs, c.getY() - rs, 2 * rs, 2 * rs));
            }
            // the mast: a tower with its waves
            int x = (int) Math.round(c.getX()), y = (int) Math.round(c.getY());
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Color.BLACK);
            g.fillOval(x - 4, y - 4, 8, 8);
            g.setColor(Theme.ACCENT);
            g.drawLine(x, y, x - 6, y + 12);
            g.drawLine(x, y, x + 6, y + 12);
            g.drawLine(x, y - 14, x, y);
            g.drawArc(x - 7, y - 21, 14, 14, -30, 240);
            horizonCross = labelWithCross(g, horizonStatus != null ? horizonStatus : "RADIO HORIZON", x, y + 16);
        } finally {
            g.dispose();
        }
    }

    private void paintGraphics(Graphics2D g, int zi, double scale, double wcx, double wcy, int w, int h) {
        if (graphics != null) {
            for (MapGraphics.Item it : graphics.list()) {
                if (it == moving) continue; // its moving copy is painted instead
                paintItem(g, it, false, zi, scale, wcx, wcy, w, h);
            }
        }
        if (measurement != null && measurement != moving && toolOverlaysShown) paintItem(g, measurement, false, zi, scale, wcx, wcy, w, h);
        if (movingLive != null) paintItem(g, movingLive, false, zi, scale, wcx, wcy, w, h);
        if (tool == Tool.NONE || forPicture) return;
        // what is being drawn, with the rubber band to the cursor
        if (tool == Tool.SYMBOL && mouseAt != null) {
            java.awt.Composite saved = g.getComposite();
            g.setComposite(AlphaComposite.SrcOver.derive(0.7f));
            UnitSymbol.paint(g, mouseAt.x, mouseAt.y, symbolUnit, symbolAffiliation, symbolEchelon, null);
            g.setComposite(saved);
            return;
        }
        if (drawing.isEmpty()) return;
        List<MapGraphics.LatLon> pts = new java.util.ArrayList<>(drawing);
        if (mouseAt != null) {
            double[] ll = toLatLon(mouseAt.x, mouseAt.y);
            pts.add(new MapGraphics.LatLon(ll[0], ll[1]));
        }
        MapGraphics.Item live = switch (tool) {
            case RULER -> MapGraphics.Item.measurement("", pts, false);
            case ARROW -> MapGraphics.Item.arrow("", pts, drawColor, drawWidth);
            default -> MapGraphics.Item.line("", pts, drawColor, drawWidth);
        };
        paintItem(g, live, true, zi, scale, wcx, wcy, w, h);
    }

    private void paintItem(Graphics2D g, MapGraphics.Item it, boolean live, int zi, double scale, double wcx, double wcy, int w, int h) {
        List<MapGraphics.LatLon> pts = it.points();
        if (pts.isEmpty()) return;
        Point2D[] sp = new Point2D[pts.size()];
        boolean anyNear = false;
        for (int i = 0; i < pts.size(); i++) {
            sp[i] = toScreen(pts.get(i).lat(), pts.get(i).lon(), zi, scale, wcx, wcy, w, h);
            if (sp[i].getX() > -2000 && sp[i].getX() < w + 2000 && sp[i].getY() > -2000 && sp[i].getY() < h + 2000) anyNear = true;
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
                String text = distanceText(legs[i]) + "  " + Mercator.bearingDeg(a.lat(), a.lon(), b.lat(), b.lon()) + "\u00b0";
                label(g, text, (int) ((sa.getX() + sb.getX()) / 2), (int) ((sa.getY() + sb.getY()) / 2) - 9, Theme.TEXT);
            }
            String total = (live ? "" : "TOTAL ") + distanceText(it.lengthM()) + (around ? "   AREA " + MapGraphics.areaText(it.areaM2()) : "");
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
        g.setColor(BOX);
        g.fillRect(x - tw / 2, y - fm.getAscent() - 2, tw, fm.getHeight() + 4);
        g.setColor(colour);
        g.drawString(text, x - tw / 2 + 5, y);
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
            if (coverageOn && !forPicture && toolOverlaysShown) paintCoverage(g, zi, scale, wcx, wcy, w, h); // under the other tools
            if (searchArea != null && !forPicture && toolOverlaysShown) paintSearch(g, zi, scale, wcx, wcy, w, h);
            if (horizonMast != null && !forPicture && toolOverlaysShown) paintHorizon(g, zi, scale, wcx, wcy, w, h); // over the heat map

            FlightTrack.Point home = track == null ? null : track.home();
            if (home != null) paintRings(g, home, zi, scale, wcx, wcy, w, h);
            if (oldTrack != null) paintOldTrack(g, zi, scale, wcx, wcy, w, h);
            paintKept(g, zi, scale, wcx, wcy, w, h);
            paintTrack(g, zi, scale, wcx, wcy, w, h);
            if (home != null) paintHome(g, home, zi, scale, wcx, wcy, w, h);
            FlightTrack.Point last = track == null ? null : track.last();
            if (pins != null) paintPins(g, last, home, zi, scale, wcx, wcy, w, h);
            paintGraphics(g, zi, scale, wcx, wcy, w, h);
            if (cameraView != null && !forPicture && connected) paintCameraView(g, zi, scale, wcx, wcy, w, h);
            if (!forPicture) paintDetections(g, zi, scale, wcx, wcy, w, h);
            if (!forPicture) paintObserved(g, zi, scale, wcx, wcy, w, h);
            paintNetDrones(g, zi, scale, wcx, wcy, w, h);
            if (!forPicture) paintOverwatch(g, zi, scale, wcx, wcy, w, h); // over the drone's diamond: its alert (1.0.47)
            if (last != null) paintDrone(g, last, zi, scale, wcx, wcy, w, h);
            else if (!forPicture) paintNoPosition(g, w, h);

            paintScale(g, h);
            paintAttribution(g, src, w, h);
            if (!forPicture) paintZoomButtons(g, w);
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
                + (tool == Tool.AREA ? "   RECTANGLE" : tool == Tool.EXPORT_BOX ? "   EXPORT" : "   DOWNLOAD");
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

    /** The kept flights: each a line in its own colour, its name at its end. */
    private void paintKept(Graphics2D g, int zi, double scale, double wcx, double wcy, int w, int h) {
        List<TrackPath> paths = keptPaths;
        for (int i = 0; i < kept.size() && i < paths.size(); i++) {
            TrackPath tp = paths.get(i);
            if (tp.size() < 2) continue;
            Path2D path = line(tp, zi, scale, wcx, wcy, w, h);
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
                if (lx + tw > w - 4) lx = (int) end.getX() - 8 - tw; // near the right edge: the label on the left
                g.setColor(BOX);
                g.fillRect(lx, (int) end.getY() - fm.getHeight() / 2, tw, fm.getHeight() + 2);
                g.setColor(c);
                g.drawString(name, lx + 5, (int) end.getY() + fm.getAscent() / 2 - 1);
            }
        }
    }

    /** The other drones on the network: a diamond each (grey when not heard from for a while), its name and state. */
    private void paintNetDrones(Graphics2D g, int zi, double scale, double wcx, double wcy, int w, int h) {
        for (NetDrone d : netDrones) {
            Point2D s = toScreen(d.lat(), d.lon(), zi, scale, wcx, wcy, w, h);
            if (s.getX() < -40 || s.getY() < -40 || s.getX() > w + 40 || s.getY() > h + 40) continue;
            Color c = d.stale() ? Theme.MUTED : d.flown() ? Theme.WARN : new Color(0x4fc3f7);
            Path2D diamond = new Path2D.Double();
            diamond.moveTo(s.getX(), s.getY() - 9);
            diamond.lineTo(s.getX() + 9, s.getY());
            diamond.lineTo(s.getX(), s.getY() + 9);
            diamond.lineTo(s.getX() - 9, s.getY());
            diamond.closePath();
            g.setStroke(new BasicStroke(3f));
            g.setColor(new Color(0, 0, 0, 150));
            g.draw(diamond);
            g.setColor(Theme.alpha(c, 200));
            g.fill(diamond);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.2f));
            g.draw(diamond);
            g.setFont(Theme.headingFont(11f));
            FontMetrics fm = g.getFontMetrics();
            String line1 = d.name(), line2 = d.state();
            int tw = Math.max(fm.stringWidth(line1), line2 == null ? 0 : fm.stringWidth(line2)) + 10;
            int bx = (int) s.getX() + 13, by = (int) s.getY() - fm.getHeight();
            if (bx + tw > w - 4) bx = (int) s.getX() - 13 - tw; // near the right edge: the label on the left
            g.setColor(BOX);
            g.fillRect(bx, by, tw, (line2 == null ? 1 : 2) * fm.getHeight() + 2);
            g.setColor(c);
            g.drawString(line1, bx + 5, by + fm.getAscent());
            if (line2 != null) {
                g.setColor(Theme.MUTED);
                g.drawString(line2, bx + 5, by + fm.getHeight() + fm.getAscent());
            }
        }
    }

    /** An earlier flight: a thin muted line with its file's name at its end, under everything live. */
    private void paintOldTrack(Graphics2D g, int zi, double scale, double wcx, double wcy, int w, int h) {
        TrackPath tp = oldTrackPath;
        if (tp == null || tp.size() == 0) return;
        Path2D path = line(tp, zi, scale, wcx, wcy, w, h);
        Point2D end = path.getCurrentPoint();
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

    private void paintTrack(Graphics2D g, int zi, double scale, double wcx, double wcy, int w, int h) {
        TrackPath tp = trackPath;
        if (tp == null || tp.size() < 2) return;
        Path2D path = line(tp, zi, scale, wcx, wcy, w, h);
        g.setStroke(new BasicStroke(5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0, 0, 0, 120));
        g.draw(path);
        g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Theme.ACCENT);
        g.draw(path);
        if (zoom >= 15.5) { // each report as a dot (the newest TrackPath.RECENT): a gap in them is a gap in the telemetry
            g.setColor(new Color(255, 255, 255, 170));
            double k = Mercator.worldSize(zi) / Mercator.TILE;
            for (int i = 0; i < tp.recentSize(); i++) {
                double x = (tp.recentX(i) * k - wcx) * scale + w / 2.0, y = (tp.recentY(i) * k - wcy) * scale + h / 2.0;
                if (x < -4 || y < -4 || x > w + 4 || y > h + 4) continue;
                g.fill(new Ellipse2D.Double(x - 1.6, y - 1.6, 3.2, 3.2));
            }
        }
    }

    /** A thinned track (zoom-0 map units) as a line on the screen. */
    private static Path2D line(TrackPath tp, int zi, double scale, double wcx, double wcy, int w, int h) {
        Path2D path = new Path2D.Double(Path2D.WIND_NON_ZERO, tp.size());
        double k = Mercator.worldSize(zi) / Mercator.TILE;
        for (int i = 0; i < tp.size(); i++) {
            double x = (tp.x(i) * k - wcx) * scale + w / 2.0, y = (tp.y(i) * k - wcy) * scale + h / 2.0;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        return path;
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

    // ------------------------------------------------------------------ observation (1.0.48)

    /**
     * An observed phone: its tile's number (from 1) and colour, where it is, which way it looks (NaN: unknown) and
     * whether that is approximate, its view cone {lat, lon} (empty without a heading), and whether its tile has the
     * mouse (highlighted) or is selected.
     */
    record Observed(int tile, Color colour, double lat, double lon, double headingDeg, boolean approximate,
                    List<double[]> cone, boolean highlighted, boolean selected) {}

    private volatile List<Observed> observed = List.of();
    private volatile java.util.function.IntConsumer onObservedClick;
    /** Where the last paint put each cone and marker, for a click (Swing thread). */
    private final List<java.awt.Shape> observedShapes = new java.util.ArrayList<>();
    private final List<Integer> observedTiles = new java.util.ArrayList<>();

    /** The observed phones (empty: none); a click on a cone or its marker selects that tile. */
    void setObserved(List<Observed> marks, java.util.function.IntConsumer onClick) {
        observed = marks == null ? List.of() : marks;
        onObservedClick = onClick;
        repaint(); // set five times a second while observing (the map no longer repaints every 33 ms whatever happens)
    }

    private boolean clickObserved(int x, int y) {
        java.util.function.IntConsumer c = onObservedClick;
        if (c == null) return false;
        for (int i = observedShapes.size() - 1; i >= 0; i--) { // the topmost first
            if (observedShapes.get(i).contains(x, y)) {
                c.accept(observedTiles.get(i));
                return true;
            }
        }
        return false;
    }

    /**
     * Each observed phone: its cone in its tile's colour (stronger while its tile has the mouse or is selected; dashed
     * edge when the heading is approximate), a round marker with its number at the phone.
     */
    private void paintObserved(Graphics2D g0, int zi, double scale, double wcx, double wcy, int w, int h) {
        List<Observed> marks = observed;
        observedShapes.clear();
        observedTiles.clear();
        if (marks.isEmpty()) return;
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setFont(Theme.headingFont(11f));
            for (Observed m : marks) {
                Point2D c = toScreen(m.lat(), m.lon(), zi, scale, wcx, wcy, w, h);
                boolean strong = m.highlighted() || m.selected();
                if (m.cone().size() >= 3) {
                    Path2D shape = new Path2D.Double();
                    shape.moveTo(c.getX(), c.getY());
                    for (double[] ll : m.cone()) {
                        Point2D p = toScreen(ll[0], ll[1], zi, scale, wcx, wcy, w, h);
                        shape.lineTo(p.getX(), p.getY());
                    }
                    shape.closePath();
                    g.setColor(Theme.alpha(m.colour(), strong ? 90 : 38));
                    g.fill(shape);
                    g.setColor(Theme.alpha(m.colour(), strong ? 255 : 170));
                    g.setStroke(m.approximate()
                            ? new BasicStroke(strong ? 2.4f : 1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{6, 5}, 0)
                            : new BasicStroke(strong ? 2.4f : 1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(shape);
                    observedShapes.add(shape);
                    observedTiles.add(m.tile() - 1);
                }
                double r = strong ? 11 : 9;
                Ellipse2D dot = new Ellipse2D.Double(c.getX() - r, c.getY() - r, 2 * r, 2 * r);
                g.setColor(new Color(0, 0, 0, 170));
                g.fill(new Ellipse2D.Double(c.getX() - r - 2, c.getY() - r - 2, 2 * r + 4, 2 * r + 4));
                g.setColor(m.colour());
                g.fill(dot);
                g.setColor(Color.BLACK);
                String n = String.valueOf(m.tile());
                FontMetrics fm = g.getFontMetrics();
                g.drawString(n, (float) (c.getX() - fm.stringWidth(n) / 2.0), (float) (c.getY() + fm.getAscent() / 2.0 - 1));
                observedShapes.add(dot);
                observedTiles.add(m.tile() - 1);
            }
        } finally {
            g.dispose();
        }
    }

    /** The camera's view on the map (null takes it away), worked out five times a second off the Swing thread. */
    void setCameraView(CameraFootprint.View view) {
        if (view == cameraView) return;
        cameraView = view;
        repaint();
    }

    /** The motion detection's marks (1.0.46; empty: none): the next repaint shows them. */
    void setDetections(List<de.droiddrone.pc.DetectionMarks.Mark> marks) {
        detections = marks == null ? List.of() : marks;
    }

    private volatile List<de.droiddrone.pc.DetectionMarks.Mark> detections = List.of();
    /** A ray that meets no ground is drawn this far (the camera's reach on the ground, CameraFootprint). */
    static final double DETECTION_LINE_M = CameraFootprint.MAX_RANGE_M;

    /**
     * The motion detection (1.0.46): from the drone a thin dashed yellow line along each detection's direction, and
     * where it meets the terrain a ring marked EST (an estimate: the camera's set angle and tilt, the map's heights),
     * MOVING or LIGHT and its age beside it. Fading over DetectionMarks.KEEP_MS.
     */
    private void paintDetections(Graphics2D g0, int zi, double scale, double wcx, double wcy, int w, int h) {
        List<de.droiddrone.pc.DetectionMarks.Mark> marks = detections;
        if (marks.isEmpty()) return;
        long now = de.droiddrone.pc.MonoClock.ms();
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setFont(Theme.headingFont(10.5f));
            for (de.droiddrone.pc.DetectionMarks.Mark m : marks) {
                float a = m.alpha(now);
                if (a <= 0) continue;
                Point2D d = toScreen(m.droneLat(), m.droneLon(), zi, scale, wcx, wcy, w, h);
                double[] end = m.ground() != null ? m.ground() : de.droiddrone.pc.map.RadioHorizon.offset(m.droneLat(), m.droneLon(),
                        Math.sin(Math.toRadians(m.bearingDeg())) * DETECTION_LINE_M, Math.cos(Math.toRadians(m.bearingDeg())) * DETECTION_LINE_M);
                Point2D e = toScreen(end[0], end[1], zi, scale, wcx, wcy, w, h);
                Color yellow = Theme.alpha(Theme.WARN, Math.round(220 * a));
                g.setColor(new Color(0, 0, 0, Math.round(120 * a)));
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new java.awt.geom.Line2D.Double(d, e));
                g.setColor(yellow);
                g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{6, 4}, 0));
                g.draw(new java.awt.geom.Line2D.Double(d, e));
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
     * The camera's view: the ground it sees, pale over the map, its edge, and a line from the drone to where the
     * picture's centre lands (a cross there). Ground out of the camera's reach ends at CameraFootprint.MAX_RANGE_M.
     */
    private void paintCameraView(Graphics2D g0, int zi, double scale, double wcx, double wcy, int w, int h) {
        CameraFootprint.View v = cameraView;
        Graphics2D g = (Graphics2D) g0.create();
        try {
            Path2D shape = new Path2D.Double();
            for (double[] ll : v.outline()) {
                Point2D p = toScreen(ll[0], ll[1], zi, scale, wcx, wcy, w, h);
                if (shape.getCurrentPoint() == null) shape.moveTo(p.getX(), p.getY());
                else shape.lineTo(p.getX(), p.getY());
            }
            shape.closePath();
            g.setColor(new Color(255, 236, 160, 46));
            g.fill(shape);
            g.setColor(new Color(255, 236, 160, 200));
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(shape);
            if (v.centre() != null) {
                Point2D d = toScreen(v.pose().lat(), v.pose().lon(), zi, scale, wcx, wcy, w, h);
                Point2D c = toScreen(v.centre()[0], v.centre()[1], zi, scale, wcx, wcy, w, h);
                g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{5, 4}, 0));
                g.draw(new java.awt.geom.Line2D.Double(d, c));
                g.setStroke(new BasicStroke(2f));
                g.draw(new java.awt.geom.Line2D.Double(c.getX() - 6, c.getY(), c.getX() + 6, c.getY()));
                g.draw(new java.awt.geom.Line2D.Double(c.getX(), c.getY() - 6, c.getX(), c.getY() + 6));
            }
        } finally {
            g.dispose();
        }
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
        if (virtualDrone) { // the virtual fleet's: never to be taken for a real drone
            g.setFont(Theme.headingFont(11f));
            g.setColor(Theme.WARN);
            g.drawString("VIRTUAL", (float) c.getX() + 13, (float) c.getY() + (last.phone() ? 17 : 4));
        }
        if (stale) badge(g, "LAST KNOWN  " + ageText(age), (int) c.getX(), (int) c.getY() - 24);
    }

    private volatile boolean virtualDrone;

    /** The live drone is one of the virtual fleet: VIRTUAL beside it. */
    void setVirtualDrone(boolean on) {
        virtualDrone = on;
        repaint();
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
        if (cache.isHeld()) { // 1.0.50: say why nothing new loads (PossiblyFlying)
            String why = de.droiddrone.pc.PossiblyFlying.reasonNow();
            text = src.attribution + "   SAVED TILES ONLY - " + (why == null ? "a drone here may be flying" : why);
        }
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
    /** The map as a picture for EXPORT: like a snapshot, without the zoom buttons and the NO POSITION text. */
    BufferedImage picture(int w, int h) {
        forPicture = true;
        try {
            return snapshot(w, h);
        } finally {
            forPicture = false;
        }
    }

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
