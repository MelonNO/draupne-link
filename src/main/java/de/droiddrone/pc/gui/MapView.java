package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.map.CellCoverage;
import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.map.Mercator;
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
 * come from the window's 30 Hz refresh; tiles arrive on their own threads. Each paint works out one MapProjection and
 * hands it to the layers: MapTrackLayer (flight lines), MapPlaceLayer (launch point, pins), MapGraphicsLayer (drawings),
 * MapToolLayer (coverage, search, radio horizon) and GroundViewLayer (camera views, detections, overwatch).
 */
class MapView extends JComponent {
    private static final double MIN_ZOOM = 2, FIRST_FIX_ZOOM = 16;
    private static final long STALE_MS = 3000;
    /** Levels fetched ahead under and over the one looked at, and the most tiles per rest. */
    private static final int PREFETCH_DOWN = 4, PREFETCH_UP = 3, PREFETCH_BUDGET = 1500;
    static final Color BOX = new Color(10, 10, 10, 215);

    private final TileCache cache;
    private TileSource chosen = TileSource.DEFAULT;
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

    /**
     * Every other drone on the network, where its phone says it is (phone app pc.32; 1.0.41, the pilot: "the location
     * of all drones on the network shown in the MAP ... connect to a drone from the map by right clicking it").
     * @param state  what to write under the name: IDLE, FLOWN BY PCBravo, ARMED, DEEP SLEEP ...
     * @param kind   which sign it gets (1.0.50, the pilot: a drone put to sleep must never look like a lost one)
     * @param pilot  the PC that flies it (to watch through), or null
     */
    // ---- 1.0.64: AIRSPACE (R-, P-, D-areas, NOTAMs): drawn while on, whatever tool is out; a click shows what one is

    private java.util.List<de.droiddrone.pc.map.Airspace> airspace = java.util.List.of();
    private String airspaceAttribution;
    private de.droiddrone.pc.map.Airspace airspaceInfo;
    private java.awt.Point airspaceInfoAt;

    void setAirspace(java.util.List<de.droiddrone.pc.map.Airspace> areas, String attribution) {
        airspace = areas == null ? java.util.List.of() : java.util.List.copyOf(areas);
        airspaceAttribution = attribution;
        if (airspaceInfo != null && !airspace.contains(airspaceInfo)) airspaceInfo = null;
        repaint();
    }

    java.util.List<de.droiddrone.pc.map.Airspace> airspace() {
        return airspace;
    }

    /** The area whose box shows (a click on it), or null. */
    de.droiddrone.pc.map.Airspace airspaceInfo() {
        return airspaceInfo;
    }

    record NetDrone(String name, double lat, double lon, String state, Kind kind, boolean flown, String pilot, Kind queued,
                    double headingDeg) {
        NetDrone(String name, double lat, double lon, String state, Kind kind, boolean flown, String pilot) {
            this(name, lat, lon, state, kind, flown, pilot, null);
        }

        NetDrone(String name, double lat, double lon, String state, Kind kind, boolean flown, String pilot, Kind queued) {
            this(name, lat, lon, state, kind, flown, pilot, queued, Double.NaN);
        }

        /** Not heard from as it should be (asleep, lost or unknown): its name in grey. */
        boolean stale() {
            return kind != Kind.ACTIVE;
        }
    }

    /**
     * What a drone on the map is doing, each with a sign of its own (2026-11-10, the pilot: "Moon = Deep sleep, eye =
     * overwatch, Diamond = Standby, Arrow = Piloted drone", as confirmed - a mode only queued shows as an arrow to its
     * sign): ACTIVE (heard now, awake: an arrow), STANDBY (a diamond), ASLEEP (DEEP SLEEP on time with its check-ins: a
     * moon), OVERWATCH (on time: an eye), LOST (it went quiet while flown or armed: a red triangle with "!"), UNKNOWN
     * (quiet while idle, or asleep but its check-ins overdue: a grey "?"), CRASHED (1.0.52: LOST DRONE pressed for it -
     * down and gone, until it is heard again or cleared: a grey square with an X). STANDBY last: the spectators' mirror
     * sends the ordinal, and an older PC reads one it doesn't know as UNKNOWN.
     */
    enum Kind { ACTIVE, ASLEEP, OVERWATCH, LOST, UNKNOWN, CRASHED, STANDBY }

    /** A queued mode's sign (ControlLink.POWER_*: 1 standby, 2 deep sleep, 3 overwatch), else null. */
    static Kind queuedKind(int mode) {
        return switch (mode) {
            case 1 -> Kind.STANDBY;
            case 2 -> Kind.ASLEEP;
            case 3 -> Kind.OVERWATCH;
            default -> null;
        };
    }

    private List<NetDrone> netDrones = List.of();
    /** A left click on a drone's sign: its name (the main window picks it); null: nothing. */
    private java.util.function.Consumer<String> onDronePicked;

    void setOnDronePicked(java.util.function.Consumer<String> c) {
        onDronePicked = c;
    }
    /** The other drone under the mouse: only its name and state are written out (1.0.50, the pilot); null: none. */
    private volatile String hoveredNet;
    /** Right-click on a drone: its name, and true to watch it (through its pilot PC), false to connect and fly. */
    private java.util.function.BiConsumer<String, Boolean> onDroneConnect;
    /** Right-click "Clear CRASHED" on a crashed drone (1.0.52); null: not offered. */
    private Consumer<String> onClearCrashed;

    void setOnClearCrashed(Consumer<String> listener) {
        onClearCrashed = listener;
    }

    /** Right-click "Mark CRASHED" on a drone not heard from (LOST, UNKNOWN; 1.0.52); null: not offered. */
    private Consumer<String> onMarkCrashed;

    void setOnMarkCrashed(Consumer<String> listener) {
        onMarkCrashed = listener;
    }

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
        MapProjection proj = projection();
        for (int i = kept.size() - 1; i >= 0; i--) {
            Point2D prev = null;
            for (FlightTrack.Point p : kept.get(i).points()) {
                Point2D s = proj.toScreen(p.lat(), p.lon());
                if (prev != null && java.awt.geom.Line2D.ptSegDist(prev.getX(), prev.getY(), s.getX(), s.getY(), x, y) <= 7) return i;
                prev = s;
            }
        }
        return -1;
    }

    /** The other drone drawn within 14 px of (x, y), or null. */
    private NetDrone netDroneAt(int x, int y) {
        MapProjection proj = projection();
        for (NetDrone d : netDrones) {
            Point2D s = proj.toScreen(d.lat(), d.lon());
            if (s.distance(x, y) <= 14) return d;
        }
        return null;
    }

    /** An earlier flight's track (TRACKS in the map window), drawn muted under the live one; null for none. */
    private List<FlightTrack.Point> oldTrack;
    private TrackPath oldTrackPath;
    private String oldTrackName;
    /** PREVIOUS FLIGHTS' map: the flight played back, bright, and where the drone was at the playback time. */
    private TrackPath replayPath;
    private double replayLat = Double.NaN, replayLon, replayHeading = Double.NaN;
    /** The pins list's "+ > on the map": the next left click is the place (right-click or Esc calls it off). */
    private Consumer<double[]> pickNext;

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
                    if (pickNext != null) { // the pin's place: called off
                        endPick(null);
                        return;
                    }
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
                if (pickNext != null) { // the place asked for (a pin added from the pins list)
                    endPick(toLatLon(e.getX(), e.getY()));
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
                    setHorizon(null, "Loading terrain...");
                    if (onHorizonMast != null) onHorizonMast.accept(horizonMast);
                    return;
                }
                if (tool == Tool.RULER || tool == Tool.LINE || tool == Tool.ARROW || tool == Tool.SYMBOL) {
                    toolClick(e);
                    return;
                }
                if (tool == Tool.NONE && e.getClickCount() == 2 && pickUpAt(e.getX(), e.getY())) return;
                if (tool == Tool.NONE && e.getClickCount() == 1 && clickObserved(e.getX(), e.getY())) return;
                if (tool == Tool.NONE && e.getClickCount() == 1 && onDronePicked != null) {
                    NetDrone picked = netDroneAt(e.getX(), e.getY());
                    if (picked != null) { // 2026-11-10 (the pilot): picked on the map, picked in the PHONE list and FLEET
                        onDronePicked.accept(picked.name());
                        return;
                    }
                }
                if (tool == Tool.NONE && e.getClickCount() == 1 && !zoomInButton.contains(e.getPoint()) && !zoomOutButton.contains(e.getPoint())
                        && (airspaceInfo != null || !airspace.isEmpty())) {
                    // 1.0.64: what this area is - a click elsewhere closes the box
                    de.droiddrone.pc.map.Airspace hit = MapAirspaceLayer.at(airspace, projection(), e.getX(), e.getY());
                    boolean was = airspaceInfo != null;
                    airspaceInfo = hit;
                    airspaceInfoAt = e.getPoint();
                    repaint();
                    if (hit != null || was) return;
                }
                if (zoomInButton.contains(e.getPoint())) zoomBy(1);
                else if (zoomOutButton.contains(e.getPoint())) zoomBy(-1);
                else if (e.getClickCount() == 2) fitTrack();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                tellCursor(e);
                mouseAt = e.getPoint();
                NetDrone over = netDrones.isEmpty() ? null : netDroneAt(e.getX(), e.getY());
                String overName = over == null ? null : over.name();
                if (!java.util.Objects.equals(overName, hoveredNet)) { // its name and state show while the mouse is on it
                    hoveredNet = overName;
                    repaint();
                }
                if (moving != null) followMoving(e.getX(), e.getY());
                if (tool != Tool.NONE) repaint(); // the rubber band to the cursor
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (onCursor != null) onCursor.accept(null);
                if (hoveredNet != null) {
                    hoveredNet = null;
                    repaint();
                }
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
        return projection().toLatLon(x, y);
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

    /** PREVIOUS FLIGHTS: the flight to play back (in view, bright); null clears it with its marker. */
    void setReplay(List<FlightTrack.Point> points) {
        replayPath = points == null || points.isEmpty() ? null : TrackPath.of(points);
        replayLat = Double.NaN;
        if (points != null && !points.isEmpty()) fit(points, null);
        repaint();
    }

    /** Where the played-back drone is now (NaN: nowhere known); heading in degrees, NaN unknown. */
    void setReplayMarker(double lat, double lon, double headingDeg) {
        if (Double.compare(lat, replayLat) == 0 && Double.compare(lon, replayLon) == 0 && Double.compare(headingDeg, replayHeading) == 0) return;
        replayLat = lat;
        replayLon = lon;
        replayHeading = headingDeg;
        repaint();
    }

    /** The next left click on the map is a place for this (a crosshair until then); a right-click calls it off (null). */
    void pickPointNext(Consumer<double[]> then) {
        pickNext = then;
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR));
        repaint();
    }

    boolean isPickingPoint() {
        return pickNext != null;
    }

    private void endPick(double[] latLon) {
        Consumer<double[]> then = pickNext;
        pickNext = null;
        setCursor(java.awt.Cursor.getDefaultCursor());
        repaint();
        if (then != null) then.accept(latLon);
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
        MapProjection proj = projection();
        for (Pins.Pin p : allPins()) {
            Point2D s = proj.toScreen(p.lat(), p.lon());
            if (Math.abs(s.getX() - x) <= 12 && Math.abs(s.getY() - y) <= 12) return p;
        }
        return null;
    }

    private void addPin(int x, int y) {
        if (pins == null) return;
        double[] ll = toLatLon(x, y);
        String name = (String) Dialogs.input(this, "Pin name", "Add pin",
                javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, pins.nextName());
        if (name == null) return;
        pins.add(name, ll[0], ll[1]);
        repaint();
    }

    private void renamePin(Pins.Pin pin) {
        String name = (String) Dialogs.input(this, "New name", "Rename " + pin.name(),
                javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, pin.name());
        if (name == null || name.isBlank()) return;
        pins.rename(pin, name);
        repaint();
    }

    /** The source chosen in the settings; null = the default. */
    void setSource(TileSource source) {
        chosen = source == null ? TileSource.DEFAULT : source;
        viewChanged();
    }

    TileSource source() {
        return chosen;
    }

    /** Called when the pilot picks a source on the map (the right-click menu), to save it. */
    void setOnSourceChosen(Consumer<TileSource> listener) {
        onSourceChosen = listener;
    }

    /** The map shown: the chosen one, or the world topo map where it has nothing - by the place looked at (the middle). */
    TileSource effectiveSource() {
        return chosen.at(centerLat, centerLon);
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
                         boolean replay, String label, boolean watching) {
        OverwatchMark(String drone, double lat, double lon, CameraFootprint.View view, double bearingDeg, boolean alert,
                      boolean replay, String label) {
            this(drone, lat, lon, view, bearingDeg, alert, replay, label, false);
        }
    }

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
        Point2D s = projection().toScreen(lat, lon);
        return s.getX() >= margin && s.getY() >= margin && s.getX() <= getWidth() - margin && s.getY() <= getHeight() - margin;
    }

    /** Pans to (lat, lon), keeping the zoom (at least 14: close enough to see the ground the camera sees). */
    void panTo(double lat, double lon) {
        centreOn(lat, lon, Math.max(zoom, 14));
    }

    /** The overwatch drone within 16 px of (x, y), or null. */
    private OverwatchMark overwatchAt(int x, int y) {
        MapProjection proj = projection();
        for (OverwatchMark m : overwatchMarks) {
            if (proj.toScreen(m.lat(), m.lon()).distance(x, y) <= 16) return m;
        }
        return null;
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
            JMenuItem clips = new JMenuItem("Overwatch clips of " + clipsOf + "...");
            clips.addActionListener(e -> onClips.accept(clipsOf));
            menu.add(clips);
            if (drone == null) menu.addSeparator();
        }
        if (drone != null && drone.kind() == Kind.CRASHED && onClearCrashed != null) {
            JMenuItem clear = new JMenuItem("Clear CRASHED on " + drone.name());
            clear.addActionListener(e -> onClearCrashed.accept(drone.name()));
            menu.add(clear);
        }
        if (drone != null && (drone.kind() == Kind.LOST || drone.kind() == Kind.UNKNOWN) && onMarkCrashed != null) {
            JMenuItem mark = new JMenuItem("Mark CRASHED: " + drone.name());
            mark.addActionListener(e -> onMarkCrashed.accept(drone.name()));
            menu.add(mark);
        }
        if (drone != null && onDroneConnect != null) {
            if (drone.flown()) {
                JMenuItem watch = new JMenuItem(drone.pilot() != null ? "Watch " + drone.name() + " (" + drone.pilot() + " flies)"
                        : "Watch " + drone.name() + " (pilot PC unknown)");
                watch.setEnabled(drone.pilot() != null);
                watch.addActionListener(e -> onDroneConnect.accept(drone.name(), true));
                menu.add(watch);
            }
            // 1.0.51 (the pilot): no right-click WAKE - a sleeping drone wakes from the LINK card
            JMenuItem fly = new JMenuItem(drone.flown() ? "Connect to " + drone.name() + " (another PC flies: watch only)"
                    : "Connect to " + drone.name() + " and fly");
            fly.addActionListener(e -> onDroneConnect.accept(drone.name(), false));
            menu.add(fly);
            menu.addSeparator();
        }
        int flight = keptAt(x, y);
        if (flight >= 0 && onRemoveKept != null) {
            JMenuItem remove = new JMenuItem("Remove flight " + kept.get(flight).name());
            remove.addActionListener(e -> onRemoveKept.accept(flight));
            menu.add(remove);
        }
        if (!kept.isEmpty() && onClearKept != null) {
            JMenuItem clear = new JMenuItem("Remove all " + kept.size() + " earlier flight" + (kept.size() == 1 ? "" : "s") + "...");
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
        double[] here = toLatLon(x, y); // 1.0.51 (the pilot): the clicked point's grid to the clipboard
        String grid = Coordinates.format(coordinateFormat.get(), here[0], here[1]);
        JMenuItem copyGrid = new JMenuItem("Copy grid (" + grid + ")");
        copyGrid.addActionListener(e -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(grid), null));
        menu.add(copyGrid);
        if (pins != null) {
            Pins.Pin hit = pinAt(x, y);
            if (hit != null) {
                String position = Coordinates.format(coordinateFormat.get(), hit.lat(), hit.lon());
                JMenuItem copy = new JMenuItem("Copy " + hit.name() + " (" + position + ")");
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
        JMenuItem followItem = new JMenuItem("Follow drone");
        followItem.addActionListener(e -> setFollow(true));
        menu.add(followItem);
        JMenuItem homeItem = new JMenuItem("Centre on launch point");
        homeItem.setEnabled(track != null && track.home() != null);
        homeItem.addActionListener(e -> centreOn(track.home().lat(), track.home().lon(), Math.max(zoom, 15)));
        menu.add(homeItem);
        JMenuItem fit = new JMenuItem("Fit whole flight");
        fit.setEnabled(track != null && !track.isEmpty());
        fit.addActionListener(e -> fitTrack());
        menu.add(fit);
        if (onAreaSelected != null) {
            menu.addSeparator();
            JMenuItem download = new JMenuItem("Download this view offline...");
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
        if (pickNext != null) endPick(null); // Esc: no pin after all
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
            horizonShape = result == null ? null : MapToolLayer.horizonShape(result);
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
            searchImage = result == null ? null : MapToolLayer.searchImage(result);
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
            coverageImage = grid == null ? null : MapToolLayer.coverageImage(grid);
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
            setSearch(null, "Loading terrain...");
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
        String name = (String) Dialogs.input(this, "New name", "Rename " + item.name(),
                javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, item.name());
        if (name == null || name.isBlank() || graphics == null) return;
        graphics.rename(item, name);
    }

    /** Where the map looks now, at its size now. */
    MapProjection projection() {
        return MapProjection.of(centerLat, centerLon, zoom, effectiveSource().maxZoom, getWidth(), getHeight());
    }

    private Point2D screenOf(MapGraphics.LatLon p) {
        return projection().toScreen(p.lat(), p.lon());
    }

    /** The drawing or symbol under a pixel (symbols first, then lines within 6 px of a leg), or null. */
    MapGraphics.Item itemAt(int x, int y) {
        if (graphics == null) return null;
        List<MapGraphics.Item> items = graphics.list();
        if (measurement != null && toolOverlaysShown) items.add(measurement); // hidden with TOOLS: not picked either
        MapProjection proj = projection();
        for (MapGraphics.Item it : items) {
            if (it.kind() != MapGraphics.Kind.SYMBOL) continue;
            Point2D c = proj.toScreen(it.points().get(0).lat(), it.points().get(0).lon());
            if (UnitSymbol.bounds((int) c.getX(), (int) c.getY(), it.unit(), it.affiliation()).contains(x, y)) return it;
        }
        for (MapGraphics.Item it : items) {
            if (it.kind() == MapGraphics.Kind.SYMBOL) continue;
            List<MapGraphics.LatLon> pts = it.points();
            boolean around = it.closed() || it.kind() == MapGraphics.Kind.AREA;
            int legs = around ? pts.size() : pts.size() - 1;
            for (int i = 0; i < legs; i++) {
                MapGraphics.LatLon a = pts.get(i), b = pts.get((i + 1) % pts.size());
                Point2D sa = proj.toScreen(a.lat(), a.lon());
                Point2D sb = proj.toScreen(b.lat(), b.lon());
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
        MapProjection proj = projection();
        for (MapGraphics.Item it : items) {
            List<MapGraphics.LatLon> pts = it.points();
            for (int i = 0; i < pts.size(); i++) {
                Point2D c = proj.toScreen(pts.get(i).lat(), pts.get(i).lon());
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

    /** The tour's own drawings (its fleet steps' units): painted only, never in graphics.kml, gone when the tour ends. */
    private volatile List<MapGraphics.Item> tourItems = List.of();

    void setTourItems(List<MapGraphics.Item> items) {
        tourItems = items == null ? List.of() : List.copyOf(items);
        repaint();
    }

    private void paintGraphics(Graphics2D g, MapProjection proj) {
        if (graphics != null) {
            for (MapGraphics.Item it : graphics.list()) {
                if (it == moving) continue; // its moving copy is painted instead
                MapGraphicsLayer.paintItem(g, it, false, proj);
            }
        }
        for (MapGraphics.Item it : tourItems) MapGraphicsLayer.paintItem(g, it, false, proj);
        if (measurement != null && measurement != moving && toolOverlaysShown) MapGraphicsLayer.paintItem(g, measurement, false, proj);
        if (movingLive != null) MapGraphicsLayer.paintItem(g, movingLive, false, proj);
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
        MapGraphicsLayer.paintItem(g, live, true, proj);
    }

    // ------------------------------------------------------------------ painting

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        try {
            int w = getWidth(), h = getHeight();
            g.setColor(Theme.PANEL);
            g.fillRect(0, 0, w, h);
            TileSource src = effectiveSource();
            MapProjection proj = MapProjection.of(centerLat, centerLon, zoom, src.maxZoom, w, h);
            paintTiles(g, src, proj);
            if (coverageOn && !forPicture && toolOverlaysShown) MapToolLayer.paintCoverage(g, coverage, coverageImage, coverageStatus, proj); // under the other tools
            if (!airspace.isEmpty() && !forPicture) MapAirspaceLayer.paint(g, airspace, proj); // 1.0.64: on while toggled, TOOLS out or not
            if (searchArea != null && !forPicture && toolOverlaysShown) searchCross = MapToolLayer.paintSearch(g, searchArea, search, searchImage, searchStatus, proj);
            if (horizonMast != null && !forPicture && toolOverlaysShown) horizonCross = MapToolLayer.paintHorizon(g, horizonMast, horizon, horizonShape, horizonStatus, proj); // over the heat map

            FlightTrack.Point home = track == null ? null : track.home();
            if (home != null) MapPlaceLayer.paintRings(g, home, proj);
            if (oldTrack != null) MapTrackLayer.paintOldTrack(g, oldTrackPath, oldTrackName, proj);
            MapTrackLayer.paintKept(g, kept, keptPaths, proj);
            MapTrackLayer.paintTrack(g, trackPath, proj);
            if (home != null) MapPlaceLayer.paintHome(g, home, proj);
            FlightTrack.Point last = track == null ? null : track.last();
            if (pins != null) MapPlaceLayer.paintPins(g, allPins(), last, home, proj);
            paintGraphics(g, proj);
            if (replayPath != null) MapTrackLayer.paintReplay(g, replayPath, replayLat, replayLon, replayHeading, proj);
            if (cameraView != null && !forPicture && connected) GroundViewLayer.paintCameraView(g, cameraView, proj);
            if (!forPicture) GroundViewLayer.paintDetections(g, detections, proj);
            if (!forPicture) paintObserved(g, proj);
            paintNetDrones(g, proj);
            if (last != null) paintDrone(g, last, proj);
            else if (!forPicture && replayPath == null) paintNoPosition(g, w, h);
            // an overwatch alert over everything else on the map (1.0.50, the pilot: it was under the drone's label)
            if (!forPicture) GroundViewLayer.paintOverwatch(g, overwatchMarks, proj);
            if (!forPicture && highlighted != null) paintHighlight(g, proj);

            paintAirspaceTop(g, proj);
            paintScale(g, h);
            paintAttribution(g, src, w, h);
            if (!forPicture && tilesMissing && cache.loading()) paintLoading(g, w);
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

    /** The last paint had a tile on screen not in yet (the LOADING MAP chip). */
    private boolean tilesMissing;

    /** 1.0.64: the clicked area's box, and openAIP's attribution while its areas show. */
    private void paintAirspaceTop(Graphics2D g, MapProjection proj) {
        if (forPicture) return;
        if (airspaceInfo != null && airspaceInfoAt != null) MapAirspaceLayer.paintInfo(g, airspaceInfo, airspaceInfoAt.x, airspaceInfoAt.y, proj.w(), proj.h());
        if (airspaceAttribution != null && !airspace.isEmpty()) {
            g.setFont(Theme.font(java.awt.Font.PLAIN, 10.5f));
            java.awt.FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(airspaceAttribution) + 8;
            g.setColor(BOX);
            g.fillRect(4, 4, tw, fm.getHeight() + 2);
            g.setColor(Theme.MUTED);
            g.drawString(airspaceAttribution, 8, 5 + fm.getAscent());
        }
    }

    private void paintTiles(Graphics2D g, TileSource src, MapProjection proj) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        tilesMissing = false;
        loadingChip = null;
        int n = 1 << proj.zi();
        int tx0 = (int) Math.floor((proj.wcx() - proj.w() / 2.0 / proj.scale()) / Mercator.TILE), tx1 = (int) Math.floor((proj.wcx() + proj.w() / 2.0 / proj.scale()) / Mercator.TILE);
        int ty0 = (int) Math.floor((proj.wcy() - proj.h() / 2.0 / proj.scale()) / Mercator.TILE), ty1 = (int) Math.floor((proj.wcy() + proj.h() / 2.0 / proj.scale()) / Mercator.TILE);
        for (int ty = Math.max(0, ty0); ty <= Math.min(n - 1, ty1); ty++) {
            int sy0 = (int) Math.floor(proj.screenY(ty * Mercator.TILE));
            int sy1 = (int) Math.floor(proj.screenY((ty + 1) * Mercator.TILE));
            for (int tx = tx0; tx <= tx1; tx++) {
                int sx0 = (int) Math.floor(proj.screenX(tx * Mercator.TILE));
                int sx1 = (int) Math.floor(proj.screenX((tx + 1) * Mercator.TILE));
                BufferedImage img = cache.get(src, proj.zi(), tx, ty, tileReady);
                if (img != null) {
                    g.drawImage(img, sx0, sy0, sx1, sy1, 0, 0, img.getWidth(), img.getHeight(), null);
                    continue;
                }
                // not in yet: the parent tile's quarter, blown up, until it is
                tilesMissing = true;
                BufferedImage parent = proj.zi() > 0 ? cache.get(src, proj.zi() - 1, Math.floorDiv(Math.floorMod(tx, n), 2), ty / 2, tileReady) : null;
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

    /** The asleep drones' moon, the overwatching ones' eye: blue-violet and teal, never the lost red or the unknown grey. */
    static final Color ASLEEP_COLOR = new Color(0x9fa8da), OVERWATCH_COLOR = new Color(0x4db6ac), LOST_COLOR = new Color(0xef5350);
    static final Color CRASHED_COLOR = new Color(0x8a8a8a), STANDBY_COLOR = new Color(0x9ccc65);

    /** A sign's colour (the flown one aside: the map draws it yellow). */
    static Color kindColor(Kind k) {
        return switch (k) {
            case ACTIVE -> new Color(0x4fc3f7);
            case STANDBY -> STANDBY_COLOR;
            case ASLEEP -> ASLEEP_COLOR;
            case OVERWATCH -> OVERWATCH_COLOR;
            case LOST -> LOST_COLOR;
            case UNKNOWN -> Theme.MUTED;
            case CRASHED -> CRASHED_COLOR;
        };
    }

    // ---- the drone picked in FLEET MANAGEMENT (1.0.52, the pilot: "make the drone be highlighted in the map, if the
    // drone has a FOV sector, make this also be highlighted")

    /** The drone picked in the fleet list, or null; rings pulse out from it for PULSE_MS, then one ring stays. */
    private volatile String highlighted;
    private long highlightedAt;
    private static final long PULSE_MS = 2400;
    private final javax.swing.Timer pulse = new javax.swing.Timer(40, e -> {
        repaint();
        if (System.currentTimeMillis() - highlightedAt > PULSE_MS) ((javax.swing.Timer) e.getSource()).stop();
    });

    /** Picks the drone to stand out (null: none). */
    void highlight(String drone) {
        highlighted = drone;
        highlightedAt = System.currentTimeMillis();
        if (drone != null) pulse.restart();
        else pulse.stop();
        repaint();
    }

    String highlighted() {
        return highlighted;
    }

    /** Where a drone on the network or in overwatch stands {lat, lon}, or null when the map has no place for it. */
    double[] positionOf(String drone) {
        if (drone == null) return null;
        for (OverwatchMark m : overwatchMarks) if (m.drone().equalsIgnoreCase(drone)) return new double[]{m.lat(), m.lon()};
        for (NetDrone d : netDrones) if (d.name().equalsIgnoreCase(drone)) return new double[]{d.lat(), d.lon()};
        return null;
    }

    /** The picked drone: its camera's ground outlined bright (when it has one), rings round it and its name. */
    private void paintHighlight(Graphics2D g0, MapProjection proj) {
        String drone = highlighted;
        double[] at = positionOf(drone);
        if (at == null) return;
        Graphics2D g = (Graphics2D) g0.create();
        try {
            for (OverwatchMark m : overwatchMarks) {
                if (!m.drone().equalsIgnoreCase(drone) || m.view() == null) continue;
                Path2D shape = GroundViewLayer.outline(m.view().outline(), proj);
                g.setColor(Theme.alpha(Color.WHITE, 40));
                g.fill(shape);
                g.setColor(Theme.alpha(Color.WHITE, 230));
                g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(shape);
            }
            Point2D s = proj.toScreen(at[0], at[1]);
            long t = System.currentTimeMillis() - highlightedAt;
            if (t < PULSE_MS) { // rings going out from it, fading
                for (int i = 0; i < 2; i++) {
                    double p = ((t + i * 500) % 1000) / 1000.0;
                    double r = 14 + 34 * p;
                    g.setColor(Theme.alpha(Color.WHITE, (int) (200 * (1 - p))));
                    g.setStroke(new BasicStroke(2f));
                    g.draw(new java.awt.geom.Ellipse2D.Double(s.getX() - r, s.getY() - r, 2 * r, 2 * r));
                }
            }
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2f));
            g.draw(new java.awt.geom.Ellipse2D.Double(s.getX() - 15, s.getY() - 15, 30, 30));
            g.setFont(Theme.headingFont(11f));
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(drone) + 10;
            int bx = (int) s.getX() + 18, by = (int) s.getY() - fm.getHeight() / 2 - 1;
            if (bx + tw > proj.w() - 4) bx = (int) s.getX() - 18 - tw;
            g.setColor(BOX);
            g.fillRect(bx, by, tw, fm.getHeight() + 2);
            g.setColor(Color.WHITE);
            g.drawString(drone, bx + 5, by + fm.getAscent() + 1);
        } finally {
            g.dispose();
        }
    }

    /** The other drones on the network: a sign each by what it is doing (Kind), its name and state. */
    private void paintNetDrones(Graphics2D g, MapProjection proj) {
        for (NetDrone d : netDrones) {
            Point2D s = proj.toScreen(d.lat(), d.lon());
            if (s.getX() < -40 || s.getY() < -40 || s.getX() > proj.w() + 40 || s.getY() > proj.h() + 40) continue;
            Color c = d.kind() == Kind.ACTIVE && d.flown() ? Theme.WARN : kindColor(d.kind());
            if (!Double.isNaN(d.headingDeg())) paintNetHeading(g, c, s.getX(), s.getY(), d.headingDeg());
            paintNetSign(g, d.kind(), c, s.getX(), s.getY());
            if (d.queued() != null) paintQueued(g, d.queued(), s.getX(), s.getY());
            if (!d.name().equals(hoveredNet)) continue; // the sign alone; its name and state while the mouse is on it
            g.setFont(Theme.headingFont(11f));
            FontMetrics fm = g.getFontMetrics();
            String line1 = d.name(), line2 = d.state();
            int tw = Math.max(fm.stringWidth(line1), line2 == null ? 0 : fm.stringWidth(line2)) + 10;
            int bx = (int) s.getX() + 13, by = (int) s.getY() - fm.getHeight();
            if (bx + tw > proj.w() - 4) bx = (int) s.getX() - 13 - tw; // near the right edge: the label on the left
            g.setColor(BOX);
            g.fillRect(bx, by, tw, (line2 == null ? 1 : 2) * fm.getHeight() + 2);
            g.setColor(c);
            g.drawString(line1, bx + 5, by + fm.getAscent());
            if (line2 != null) {
                g.setColor(d.kind() == Kind.LOST ? LOST_COLOR : d.kind() == Kind.CRASHED ? CRASHED_COLOR : Theme.MUTED);
                g.drawString(line2, bx + 5, by + fm.getHeight() + fm.getAscent());
            }
        }
    }

    /**
     * 1.0.64: where a resting drone's camera looks (its sleep notice): a short pointer out of its sign, so the pilot sees
     * which way it faces before waking it.
     */
    static void paintNetHeading(Graphics2D g, Color c, double x, double y, double headingDeg) {
        double a = Math.toRadians(headingDeg);
        double dx = Math.sin(a), dy = -Math.cos(a);
        double x0 = x + dx * 10, y0 = y + dy * 10, x1 = x + dx * 22, y1 = y + dy * 22;
        g.setColor(new Color(0, 0, 0, 150));
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Line2D.Double(x0, y0, x1, y1));
        g.setColor(c);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Line2D.Double(x0, y0, x1, y1));
        Path2D head = new Path2D.Double();
        head.moveTo(x1 + dx * 5, y1 + dy * 5);
        head.lineTo(x1 - dy * 4, y1 + dx * 4);
        head.lineTo(x1 + dy * 4, y1 - dx * 4);
        head.closePath();
        g.fill(head);
    }

    /**
     * A queued mode, beside the sign at x, y: a yellow arrow from it to the mode's sign, smaller (2026-11-10, the pilot:
     * "When a mode is cued but not set make a arrow ... from the current mode symbol to the set mode symbol").
     */
    static void paintQueued(Graphics2D g, Kind to, double x, double y) {
        double ax = x + 11, bx = x + 19;
        g.setColor(new Color(0, 0, 0, 150));
        g.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Line2D.Double(ax, y, bx, y));
        g.setColor(Theme.WARN);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Line2D.Double(ax, y, bx - 2, y));
        Path2D head = new Path2D.Double();
        head.moveTo(bx + 2, y);
        head.lineTo(bx - 3, y - 3.5);
        head.lineTo(bx - 3, y + 3.5);
        head.closePath();
        g.fill(head);
        java.awt.geom.AffineTransform saved = g.getTransform();
        g.translate(bx + 11, y);
        g.scale(0.72, 0.72);
        paintNetSign(g, to, kindColor(to), 0, 0);
        g.setTransform(saved);
    }

    /**
     * One drone's sign, 18 px across, told apart by shape as well as colour: an arrow (ACTIVE: awake, flown), a diamond
     * (STANDBY), a circle with a crescent moon (ASLEEP: deep sleep) or an eye (OVERWATCH), a red triangle with "!"
     * (LOST), a circle with "?" (UNKNOWN), a grey square with an X (CRASHED).
     */
    static void paintNetSign(Graphics2D g, Kind kind, Color c, double x, double y) { // also the fleet cards' badge (1.0.52)
        java.awt.Shape body;
        boolean solid = kind == Kind.ACTIVE || kind == Kind.STANDBY || kind == Kind.LOST || kind == Kind.CRASHED;
        switch (kind) {
            case ACTIVE -> { // an arrow, pointing up: the drone in flight
                Path2D arrow = new Path2D.Double();
                arrow.moveTo(x, y - 9.5);
                arrow.lineTo(x + 7.5, y + 8);
                arrow.lineTo(x, y + 4);
                arrow.lineTo(x - 7.5, y + 8);
                arrow.closePath();
                body = arrow;
            }
            case STANDBY -> {
                Path2D diamond = new Path2D.Double();
                diamond.moveTo(x, y - 9);
                diamond.lineTo(x + 9, y);
                diamond.lineTo(x, y + 9);
                diamond.lineTo(x - 9, y);
                diamond.closePath();
                body = diamond;
            }
            case LOST -> {
                Path2D triangle = new Path2D.Double();
                triangle.moveTo(x, y - 9.5);
                triangle.lineTo(x + 9.5, y + 7.5);
                triangle.lineTo(x - 9.5, y + 7.5);
                triangle.closePath();
                body = triangle;
            }
            case CRASHED -> body = new java.awt.geom.Rectangle2D.Double(x - 7.5, y - 7.5, 15, 15);
            default -> body = new java.awt.geom.Ellipse2D.Double(x - 9, y - 9, 18, 18);
        }
        g.setStroke(new BasicStroke(3f));
        g.setColor(new Color(0, 0, 0, 150));
        g.draw(body);
        g.setColor(Theme.alpha(c, solid ? 200 : 90));
        g.fill(body);
        g.setColor(kind == Kind.ACTIVE || kind == Kind.STANDBY ? Color.WHITE : c);
        g.setStroke(new BasicStroke(kind == Kind.ACTIVE || kind == Kind.STANDBY ? 1.2f : 1.6f));
        g.draw(body);
        switch (kind) {
            case ASLEEP -> { // a crescent moon: a disc with a disc taken out of its right side
                java.awt.geom.Area moon = new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(x - 5.5, y - 5.5, 11, 11));
                moon.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(x - 2, y - 7.5, 11, 11)));
                g.setColor(Color.WHITE);
                g.fill(moon);
            }
            case OVERWATCH -> { // an eye: two arcs and the pupil
                Path2D eye = new Path2D.Double();
                eye.moveTo(x - 6.5, y);
                eye.quadTo(x, y - 6.5, x + 6.5, y);
                eye.quadTo(x, y + 6.5, x - 6.5, y);
                eye.closePath();
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(1.4f));
                g.draw(eye);
                g.fill(new java.awt.geom.Ellipse2D.Double(x - 2.2, y - 2.2, 4.4, 4.4));
            }
            case CRASHED -> { // an X: down, no longer with us
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new java.awt.geom.Line2D.Double(x - 3.8, y - 3.8, x + 3.8, y + 3.8));
                g.draw(new java.awt.geom.Line2D.Double(x + 3.8, y - 3.8, x - 3.8, y + 3.8));
            }
            case LOST, UNKNOWN -> {
                g.setFont(Theme.headingFont(kind == Kind.LOST ? 11f : 12f));
                FontMetrics fm = g.getFontMetrics();
                String mark = kind == Kind.LOST ? "!" : "?";
                g.setColor(Color.WHITE);
                double my = kind == Kind.LOST ? y + 2.5 : y; // the triangle's middle sits low
                g.drawString(mark, (float) (x - fm.stringWidth(mark) / 2.0), (float) (my + fm.getAscent() / 2.0 - 1.5));
            }
            default -> {
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
                    List<double[]> cone, boolean highlighted, boolean selected, boolean motion) {}

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
    private void paintObserved(Graphics2D g0, MapProjection proj) {
        List<Observed> marks = observed;
        observedShapes.clear();
        observedTiles.clear();
        if (marks.isEmpty()) return;
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setFont(Theme.headingFont(11f));
            for (Observed m : marks) {
                Point2D c = proj.toScreen(m.lat(), m.lon());
                boolean strong = m.highlighted() || m.selected();
                if (m.cone().size() >= 3) {
                    Path2D shape = new Path2D.Double();
                    shape.moveTo(c.getX(), c.getY());
                    for (double[] ll : m.cone()) {
                        Point2D p = proj.toScreen(ll[0], ll[1]);
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
                if (m.motion()) { // 1.0.50 (the pilot): its phone sees something move - the drone stands out, in the alert red
                    double phase = (System.currentTimeMillis() % 1200) / 1200.0;
                    double rr = r + 6 + 10 * phase;
                    g.setColor(Theme.alpha(ALERT, (int) Math.round(230 * (1 - phase))));
                    g.setStroke(new BasicStroke(2.5f));
                    g.draw(new Ellipse2D.Double(c.getX() - rr, c.getY() - rr, 2 * rr, 2 * rr));
                    g.setColor(ALERT);
                    g.setStroke(new BasicStroke(3f));
                    g.draw(new Ellipse2D.Double(c.getX() - r - 4, c.getY() - r - 4, 2 * r + 8, 2 * r + 8));
                    FontMetrics lm = g.getFontMetrics();
                    String label = "MOTION";
                    int lx = (int) c.getX() + (int) r + 8, ly = (int) c.getY() - lm.getHeight() / 2;
                    g.setColor(BOX);
                    g.fillRect(lx, ly, lm.stringWidth(label) + 10, lm.getHeight() + 2);
                    g.setColor(ALERT);
                    g.drawString(label, lx + 5, ly + lm.getAscent() + 1);
                }
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

    /** Moving at least this fast (km/h), the GPS course says where it goes; slower it is only the fix's wander. */
    static final double COURSE_KMH = 4;

    /**
     * The arrow's way (-1: none known, a round mark): the heading MainWindow gives (the FC's compass or the phone's
     * sensor; when no longer connected, the last one) - unless the drone moves on a stale link, then its GPS course.
     * 1.0.62: a GPS course of a drone standing still, or Betaflight's yaw without a compass, pointed anywhere.
     */
    static int arrowHeading(int headingDeg, boolean stale, FlightTrack.Point last) {
        boolean moving = last.speedKmh() >= COURSE_KMH;
        if (headingDeg >= 0 && !(stale && moving)) return headingDeg;
        return moving ? last.courseDeg() : -1;
    }

    private void paintDrone(Graphics2D g, FlightTrack.Point last, MapProjection proj) {
        Point2D c = proj.toScreen(last.lat(), last.lon());
        if (liveAsleep && !connected) { // asleep on time, or heard of since: its newer sign says so, never LAST KNOWN
            for (NetDrone d : netDrones) {
                if (proj.toScreen(d.lat(), d.lon()).distance(c) < 60) return; // its sign stands in
            }
            for (OverwatchMark m : overwatchMarks) {
                if (proj.toScreen(m.lat(), m.lon()).distance(c) < 60) return; // its alert stands in
            }
        }
        paintAccuracy(g, last, c);
        long age = System.currentTimeMillis() - last.timeMs();
        boolean stale = age > STALE_MS || (fcLost && !last.phone()) || !connected;
        int heading = arrowHeading(headingDeg, stale, last);
        AffineTransform saved = g.getTransform();
        g.translate(c.getX(), c.getY());
        java.awt.Shape arrow;
        if (heading < 0) { // which way it looks isn't known: a round mark, never a wrong way (1.0.62)
            arrow = new Ellipse2D.Double(-8, -8, 16, 16);
        } else {
            g.rotate(Math.toRadians(heading));
            Path2D a = new Path2D.Double();
            a.moveTo(0, -13);
            a.lineTo(8.5, 10);
            a.lineTo(0, 5.5);
            a.lineTo(-8.5, 10);
            a.closePath();
            arrow = a;
        }
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
        if (stale && !(liveAsleep && !connected)) badge(g, "LAST KNOWN  " + ageText(age), (int) c.getX(), (int) c.getY() - 24);
    }

    private volatile boolean virtualDrone;
    /**
     * The live drone was put to sleep (STANDBY, DEEP SLEEP, OVERWATCH) and checks in on time, or was heard of after its
     * track's last point (an overwatch alert, a check, its phone's position): no LAST KNOWN on it (1.0.50).
     */
    private volatile boolean liveAsleep;

    void setLiveAsleep(boolean on) {
        if (liveAsleep == on) return;
        liveAsleep = on;
        repaint();
    }

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
        String text = !connected ? "NO DRONE POSITION" : "WAITING FOR GPS FIX";
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

    /**
     * LOADING MAP (1.0.56, the pilot: "create an indication that the map is loading"): a small chip at the top,
     * a turning dot in it, while tiles on the screen are still coming; gone with the last of them.
     */
    private void paintLoading(Graphics2D g0, int w) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            String text = LOADING_TEXT;
            g.setFont(Theme.headingFont(11f));
            FontMetrics fm = g.getFontMetrics();
            int dot = 12, pad = 8, tw = fm.stringWidth(text);
            int bw = pad + dot + 6 + tw + pad, bh = Math.max(dot, fm.getHeight()) + 8;
            int x = (w - bw) / 2, y = 50; // under NO DRONE POSITION (top middle, y 12-42)
            g.setColor(BOX);
            g.fillRect(x, y, bw, bh);
            g.setColor(Theme.LINE);
            g.drawRect(x, y, bw - 1, bh - 1);
            int cx = x + pad + dot / 2, cy = y + bh / 2;
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Theme.MUTED);
            g.drawOval(cx - dot / 2, cy - dot / 2, dot, dot);
            double phase = (System.currentTimeMillis() % 1000) / 1000.0 * Math.PI * 2;
            g.setColor(Theme.ACCENT);
            g.drawArc(cx - dot / 2, cy - dot / 2, dot, dot, (int) Math.toDegrees(-phase), 100);
            g.setColor(Theme.TEXT);
            g.drawString(text, x + pad + dot + 6, cy - fm.getHeight() / 2 + fm.getAscent());
            loadingChip = new Rectangle(x, y, bw, bh);
        } finally {
            g.dispose();
        }
        Rectangle r = loadingChip;
        repaint(100, r.x, r.y, r.width, r.height); // turns; a tile coming in repaints the whole map (and ends it)
    }

    /** Where LOADING MAP was last painted (tests). */
    Rectangle loadingChip;

    static final String LOADING_TEXT = "LOADING MAP";

    private void paintAttribution(Graphics2D g, TileSource src, int w, int h) {
        String text = src.attribution + (cache.isOffline() ? "   SAVED TILES ONLY" : "");
        if (cache.isHeld()) { // 1.0.50: say why nothing new loads (PossiblyFlying)
            String why = de.droiddrone.pc.PossiblyFlying.reasonNow();
            text = src.attribution + "   SAVED TILES ONLY - " + (why == null ? "drone may be flying" : why);
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
