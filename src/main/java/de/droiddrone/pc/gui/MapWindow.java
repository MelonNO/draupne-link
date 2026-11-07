package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.map.CellCoverage;
import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.Elevation;
import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.map.Kml;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Mercator;
import de.droiddrone.pc.map.OpenCellId;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.map.RadioHorizon;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The map in a window of its own, for a second screen (MAP > Map window, F3). Hidden until opened; remembers where
 * it was (and whether maximized). A toolbar in the program's style above the map (FOLLOW, GO TO, TRACKS, PINS,
 * DOWNLOAD AREA, FIND DRONE; the map picture and the coordinate format), the figures under it: the place under the
 * mouse and the terrain height there (m and ft), the drone's position, altitude, distance and bearing from the
 * launch point, and which GPS the position comes from (with the phone's accuracy). Right-click on the map drops a
 * pin (Alpha, Bravo ...), or renames or removes one; the CURSOR cell's right-click pins where the mouse last was.
 * Keys: + / - zoom, Home the launch point, End the drone, F3 hides the window. DOWNLOAD AREA (or Alt + drag a box on
 * the map) saves the map for offline use, like Mission Planner's prefetch.
 */
final class MapWindow extends JFrame {
    static final String BOUNDS_KEY = "mapWindow";
    private static final String[] PICTURES = {"AUTO", "TOPO", "WORLD TOPO", "PAPER", "SATELLITE", "OSM"};
    private static final TileSource[] SOURCES = {null, TileSource.KARTVERKET_TOPO, TileSource.WORLD_TOPO, TileSource.KARTVERKET_RASTER, TileSource.SATELLITE, TileSource.OSM};

    private final MapView map;
    private final TileCache cache;
    private final Elevation elevation;
    private final MapGraphics graphics;
    private final Pins pins;
    /** MAP TOOLS (1.0.35): the slide-out beside the map, and the elevation profile card under it. */
    private final MapToolsPanel tools;
    private final ElevationProfileView profile = new ElevationProfileView();
    private final JPanel profileCard = Theme.card(new BorderLayout(0, 6));
    private final JLabel profileTitle = Theme.label("ELEVATION PROFILE", Theme.headingFont(11f), Theme.MUTED);
    private final JButton profileFold = Theme.secondaryButton("Hide");
    private boolean profileShown = true;
    private final Consumer<Map<String, String>> saver;
    private final JButton followButton = Theme.secondaryButton("Follow: on");
    private final JButton tracksButton = Theme.secondaryButton("Tracks");
    /** A hidden download's progress, in the toolbar; a click brings its window back. */
    private final JLabel downloadLine = Theme.label("", Theme.headingFont(12f), Theme.ACCENT);
    private JPanel cursorCell, terrainCell, droneCell;
    private final JComboBox<String> pictureCombo = new JComboBox<>(PICTURES);
    private final JComboBox<String> formatCombo = new JComboBox<>();
    private final JLabel cursorValue = value(), terrainValue = value(), droneValue = value(), altitudeValue = value(),
            homeValue = value(), sourceValue = value();
    private Coordinates.Format format;
    private boolean preferPhone;
    private PinManagerDialog pinManager;
    private MapDownloadDialog downloader;
    private double[] cursor;
    private long cursorSerial;
    private final Timer terrainLookup = new Timer(250, e -> lookupTerrain());
    private final Timer saveBounds = new Timer(800, e -> saveBounds());
    private boolean settingCombos;

    MapWindow(JFrame owner, TileCache cache, Elevation elevation, Pins pins, MapGraphics graphics, TileSource source, Coordinates.Format format, String savedBounds,
              Consumer<Map<String, String>> saver, Consumer<TileSource> onSource, Consumer<Coordinates.Format> onFormat, Runnable findDrone) {
        super("DRAUPNE Link map");
        this.cache = cache;
        this.elevation = elevation;
        this.graphics = graphics;
        this.pins = pins;
        this.saver = saver;
        this.format = format;
        setIconImages(owner.getIconImages());
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        terrainLookup.setRepeats(false);
        saveBounds.setRepeats(false);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        map = new MapView(cache);
        map.setPins(pins);
        map.setGraphics(graphics);
        tools = new MapToolsPanel(map);
        map.setSource(source);
        map.setOnSourceChosen(s -> {
            onSource.accept(s);
            syncCombos();
        });
        map.setOnCursor(this::cursorAt);
        map.setOnAreaSelected(this::downloadArea);
        map.setOnSelectModeEnded(() -> {
            if (downloader != null) downloader.selectionEnded();
        });

        // ---- toolbar
        JPanel bar = Theme.card(new BorderLayout(12, 0));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(Theme.sectionTitle("Map"));
        left.add(Box.createHorizontalStrut(6));
        followButton.setToolTipText("Keep the drone in the middle; dragging the map turns it off");
        followButton.addActionListener(e -> {
            map.setFollow(!map.isFollowing());
            syncFollow();
        });
        left.add(followButton);
        // the whole flight: a double-click on the map; the launch point: the Home key (End: the drone)
        tracksButton.setToolTipText("An earlier flight's track on the map, from the Tracks folder");
        tracksButton.addActionListener(e -> TacticalCombo.show(tracksMenu(), tracksButton, 0, tracksButton.getHeight() + 4));
        left.add(tracksButton);
        // MAP TOOLS (1.0.35): GO TO, PINS and DOWNLOAD AREA live in the slide-out, with the ruler, the drawings and the symbols
        tools.goTo.setToolTipText("Centre the map on a position typed or pasted: MGRS, UTM, or latitude, longitude");
        tools.goTo.addActionListener(e -> goToPosition());
        tools.pins.setToolTipText("Every pin: go to, rename, remove, add at the drone or the launch point");
        tools.pins.addActionListener(e -> {
            if (pinManager == null || !pinManager.isDisplayable()) {
                pinManager = new PinManagerDialog(this, pins, map, () -> this.format, () -> track);
            }
            pinManager.setVisible(true);
            pinManager.toFront();
        });
        tools.download.setToolTipText("Save the map for offline use: this view, or a box drawn on the map (SELECT WITH CURSOR, or Alt + drag)");
        tools.download.addActionListener(e -> downloadArea(map.visibleArea()));
        tools.ruler.addActionListener(e -> map.setTool(MapView.Tool.RULER));
        tools.line.addActionListener(e -> map.setTool(MapView.Tool.LINE));
        tools.rectangle.addActionListener(e -> map.setTool(MapView.Tool.AREA));
        tools.arrow.addActionListener(e -> map.setTool(MapView.Tool.ARROW));
        tools.symbol.addActionListener(e -> map.setTool(MapView.Tool.SYMBOL));
        tools.eraser.addActionListener(e -> map.setTool(MapView.Tool.ERASER));
        tools.placeAt.addActionListener(e -> placeAtPosition());
        tools.keep.addActionListener(e -> map.keepMeasurement());
        tools.clearMeasure.addActionListener(e -> map.clearMeasurement());
        tools.export.addActionListener(e -> map.setTool(MapView.Tool.EXPORT_BOX));
        tools.horizon.addActionListener(e -> map.setTool(MapView.Tool.HORIZON));
        tools.gcsSearch.addActionListener(e -> map.setTool(MapView.Tool.SEARCH));
        horizonSettings = tools.horizonSettings();
        tools.onHorizonSettings(() -> {
            horizonSettings = tools.horizonSettings();
            recomputeHorizon();
            recomputeSearch();
        });
        map.setOnHorizonMast(this::horizonMast);
        map.setOnSearchArea(this::searchArea);
        // 4G COVERAGE (1.0.44): its choices from the settings file; worked out each time the view rests
        de.droiddrone.pc.PcConfig config = owner instanceof MainWindow m ? m.config() : null;
        coverageKey = config == null ? "" : config.getString(COVERAGE_KEY);
        tools.coverageOperator.setSelectedIndex(OpenCellId.Operator.parse(config == null ? null : config.getString(COVERAGE_OPERATOR)).ordinal());
        tools.coverageHeight.setSelectedIndex(heightIndex(config == null ? "" : config.getString(COVERAGE_HEIGHT)));
        tools.coverage.addActionListener(e -> setCoverageOn(!coverageOn));
        tools.coverageOperator.addActionListener(e -> coverageChoiceChanged());
        tools.coverageHeight.addActionListener(e -> coverageChoiceChanged());
        tools.coverageKey.addActionListener(e -> askCoverageKey());
        tools.coverageImport.addActionListener(e -> importCells());
        map.setOnViewRested(this::coverageView);
        // the pilot: the tools' overlays (radio horizon, search, measurement) show only while TOOLS is out; closed, they hide (still there until crossed out)
        // and their tool ends, so a click cannot move a mast that is not on screen
        tools.onGroupChanged(() -> {
            boolean shown = tools.toolsShown();
            map.setToolOverlaysShown(shown);
            MapView.Tool t = map.tool();
            if (!shown && (t == MapView.Tool.HORIZON || t == MapView.Tool.SEARCH || t == MapView.Tool.RULER)) map.cancelTool();
            profileCard.setVisible(shown && map.hasMeasurement()); // the measurement's elevation profile with it
            if (shown && coverageOn) coverageView(map.visibleArea()); // nothing is fetched while it is hidden
        });
        map.setToolOverlaysShown(tools.toolsShown());
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentHidden(ComponentEvent e) { // the tool ends; the radio horizon and the search stay until crossed out
                if (map.tool() == MapView.Tool.HORIZON || map.tool() == MapView.Tool.SEARCH) map.cancelTool();
            }
        });
        tools.importButton.addActionListener(e -> importKml());
        tools.clearAll.addActionListener(e -> { // 1.0.37: the pins and the measurement go too (the pilot)
            if (graphics.isEmpty() && pins.list().isEmpty() && !map.hasMeasurement()) return;
            int answer = javax.swing.JOptionPane.showConfirmDialog(this, "Take every drawing, symbol, pin and measurement off the map?",
                    "Clear all", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
            if (answer != javax.swing.JOptionPane.OK_OPTION) return;
            graphics.clear();
            pins.clear();
            map.clearMeasurement();
            map.repaint();
        });
        map.setOnToolChanged(() -> SwingUtilities.invokeLater(tools::syncTools));
        map.setOnMeasurement(m -> SwingUtilities.invokeLater(() -> showProfile(m)));
        map.setOnExportArea(this::exportArea);
        JButton find = Theme.primaryButton("Find drone");
        find.setToolTipText("The last known position, with the coordinates to copy and map links");
        find.addActionListener(e -> findDrone.run());
        left.add(find);
        bar.add(left, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        // a download put away with HIDE goes on here; a click brings its window back
        downloadLine.setVisible(false);
        downloadLine.setToolTipText("A download is running: click for its window");
        downloadLine.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        downloadLine.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (downloader != null) {
                    downloader.setVisible(true);
                    downloader.toFront();
                }
            }
        });
        right.add(downloadLine);
        TacticalCombo.install(pictureCombo);
        pictureCombo.setToolTipText("The map picture. Auto and TOPO: Kartverket's topo map in Norway, the world topo map (OpenTopoMap) elsewhere. WORLD TOPO: OpenTopoMap everywhere. Satellite from Esri");
        pictureCombo.addActionListener(e -> {
            if (settingCombos) return;
            TileSource s = SOURCES[pictureCombo.getSelectedIndex()];
            map.setSource(s);
            onSource.accept(s);
        });
        right.add(pictureCombo);
        for (Coordinates.Format f : Coordinates.Format.values()) formatCombo.addItem(f.title);
        TacticalCombo.install(formatCombo);
        formatCombo.setToolTipText("How positions are written: MGRS (the military grid), UTM, decimal degrees, degrees-minutes-seconds");
        formatCombo.addActionListener(e -> {
            if (settingCombos) return;
            setFormat(Coordinates.Format.values()[formatCombo.getSelectedIndex()]);
            onFormat.accept(this.format);
        });
        right.add(formatCombo);
        bar.add(right, BorderLayout.EAST);
        root.add(bar, BorderLayout.NORTH);

        JPanel centre = new JPanel(new BorderLayout(0, 12));
        centre.setOpaque(false);
        JPanel mapRow = new JPanel(new BorderLayout(6, 0));
        mapRow.setOpaque(false);
        mapRow.add(tools, BorderLayout.WEST);
        mapRow.add(map, BorderLayout.CENTER);
        centre.add(mapRow, BorderLayout.CENTER);
        JPanel profileHead = new JPanel(new BorderLayout(10, 0));
        profileHead.setOpaque(false);
        profileHead.add(profileTitle, BorderLayout.CENTER);
        JPanel profileButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        profileButtons.setOpaque(false);
        profileFold.setFont(Theme.headingFont(11f));
        profileFold.addActionListener(e -> {
            profileShown = !profileShown;
            profile.setVisible(profileShown);
            profileFold.setText(profileShown ? "Hide" : "Show");
            profileCard.revalidate();
        });
        profileButtons.add(profileFold);
        JButton profileClose = Theme.secondaryButton("Close");
        profileClose.setFont(Theme.headingFont(11f));
        profileClose.addActionListener(e -> showProfile(null));
        profileButtons.add(profileClose);
        profileHead.add(profileButtons, BorderLayout.EAST);
        profileCard.add(profileHead, BorderLayout.NORTH);
        profileCard.add(profile, BorderLayout.CENTER);
        profileCard.setVisible(false);
        centre.add(profileCard, BorderLayout.SOUTH);
        root.add(centre, BorderLayout.CENTER);

        // ---- the figures
        JPanel stats = Theme.card(new GridLayout(1, 6, 14, 0));
        cursorCell = stat(stats, "CURSOR", cursorValue);
        cursorCell.setToolTipText("Right-click: a pin where the cursor last was on the map");
        cursorCell.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) TacticalCombo.show(cursorMenu(), cursorCell, e.getX(), e.getY());
            }
        });
        terrainCell = stat(stats, "TERRAIN HEIGHT", terrainValue);
        droneCell = stat(stats, "DRONE", droneValue);
        stat(stats, "ALTITUDE (GPS)", altitudeValue);
        stat(stats, "LAUNCH", homeValue);
        stat(stats, "POSITION FROM", sourceValue);
        root.add(stats, BorderLayout.SOUTH);
        clearStats();
        syncCombos();
        syncFollow();

        if (!restoreBounds(savedBounds)) {
            setSize(1180, 780);
            setLocationRelativeTo(owner);
        }
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                saveBounds.restart();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                saveBounds.restart();
            }
        });
        addWindowStateListener(e -> saveBounds.restart()); // maximized or back: the flag
        // minimized to the taskbar counts as not shown: the main window's LINK card gets its minimap back
        addWindowStateListener(e -> {
            if (((e.getOldState() ^ e.getNewState()) & ICONIFIED) != 0) onShownChanged.run();
        });

        // keys: + / - zoom, Home the launch point, End the drone
        for (int k : new int[]{java.awt.event.KeyEvent.VK_PLUS, java.awt.event.KeyEvent.VK_ADD, java.awt.event.KeyEvent.VK_EQUALS}) {
            bindKey(javax.swing.KeyStroke.getKeyStroke(k, 0), () -> map.zoomBy(1));
        }
        for (int k : new int[]{java.awt.event.KeyEvent.VK_MINUS, java.awt.event.KeyEvent.VK_SUBTRACT}) {
            bindKey(javax.swing.KeyStroke.getKeyStroke(k, 0), () -> map.zoomBy(-1));
        }
        bindKey(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_HOME, 0), () -> {
            map.centreOnHome();
            syncFollow();
        });
        bindKey(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_END, 0), () -> {
            map.centreOnDrone();
            syncFollow();
        });
        // MAP TOOLS: Esc ends the tool (or clears the measurement), Enter ends the line at its last point
        bindKey(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), () -> {
            if (map.tool() != MapView.Tool.NONE) map.cancelTool();
            else map.clearMeasurement();
        });
        bindKey(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), map::finishTool);
    }

    /** The elevation profile of a measurement under the map; null hides it. The terrain is looked up as the card shows. */
    private void showProfile(MapGraphics.Item m) {
        if (m == null) {
            profileCard.setVisible(false);
            profile.set(null, null);
            return;
        }
        String name = m.name().isEmpty() || m.name().equals("Measurement") ? "MEASUREMENT" : m.name().toUpperCase(java.util.Locale.ROOT);
        profileTitle.setText("ELEVATION PROFILE   " + name + "   " + MapGraphics.distanceText(m.lengthM())
                + (m.areaM2() > 0 ? "   AREA " + MapGraphics.areaText(m.areaM2()) : ""));
        List<double[]> path = new ArrayList<>();
        for (MapGraphics.LatLon p : m.points()) path.add(new double[]{p.lat(), p.lon()});
        if (m.closed() && !m.points().isEmpty()) path.add(new double[]{m.points().get(0).lat(), m.points().get(0).lon()});
        profile.set(new double[0], new double[0]);
        profileCard.setVisible(true);
        profileCard.revalidate();
        elevation.profile(path, 20, 80, data -> SwingUtilities.invokeLater(() -> {
            if (profileCard.isVisible()) profile.set(data[0], data[1]);
        }));
    }

    /**
     * EXPORT: the box drawn with the tool as a picture (PNG, the current map type at the current zoom, with the
     * graphics, pins and track on it and the scale bar) and as KML (every drawing, symbol and pin with a point in the
     * box, and the shown track as a line), both under Documents\\DRAUPNE Link\\Exports, named as the pilot says.
     */
    private void exportArea(double[] area) {
        java.nio.file.Path dir;
        try {
            dir = java.nio.file.Files.createDirectories(java.nio.file.Path.of(System.getProperty("user.home"), "Documents", "DRAUPNE Link", "Exports"));
        } catch (java.io.IOException e) {
            javax.swing.JOptionPane.showMessageDialog(this, "No Exports folder: " + e.getMessage(), "Export", javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"));
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(dir.toFile());
        chooser.setDialogTitle("Export the box as a picture and KML");
        chooser.setSelectedFile(dir.resolve("map-" + stamp + ".kml").toFile());
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("KML (a PNG with the same name goes with it)", "kml"));
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        java.nio.file.Path kml = chooser.getSelectedFile().toPath();
        if (!kml.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".kml")) kml = kml.resolveSibling(kml.getFileName() + ".kml");
        java.nio.file.Path png = kml.resolveSibling(kml.getFileName().toString().replaceAll("(?i)\\.kml$", "") + ".png");
        double south = area[0], north = area[1], west = area[2], east = area[3];
        List<MapGraphics.Item> items = graphics.inside(south, north, west, east);
        List<Pins.Pin> inBox = new ArrayList<>();
        for (Pins.Pin p : pins.list()) if (p.lat() >= south && p.lat() <= north && p.lon() >= west && p.lon() <= east) inBox.add(p);
        List<FlightTrack.Point> pts = track == null ? List.of() : track.points();
        List<MapGraphics.LatLon> trackPts = new ArrayList<>();
        for (FlightTrack.Point p : pts) if (p.lat() >= south && p.lat() <= north && p.lon() >= west && p.lon() <= east) trackPts.add(new MapGraphics.LatLon(p.lat(), p.lon()));
        if (trackPts.size() >= 2) items = new ArrayList<>(items) {{ add(MapGraphics.Item.line("Flight track", trackPts, 0xffffff, 2)); }};
        try {
            MapGraphics.writeKml(kml, Kml.write(items, inBox, "DRAUPNE Link export " + stamp));
            // the picture: the box at this zoom, at most 4096 px a side
            double zoom = map.zoom();
            int w = (int) Math.round(de.droiddrone.pc.map.Mercator.x(east, zoom) - de.droiddrone.pc.map.Mercator.x(west, zoom));
            int h = (int) Math.round(de.droiddrone.pc.map.Mercator.y(south, zoom) - de.droiddrone.pc.map.Mercator.y(north, zoom));
            double shrink = Math.max(1, Math.max(w, h) / 4096.0);
            if (shrink > 1) {
                zoom -= Math.log(shrink) / Math.log(2);
                w = (int) Math.round(w / shrink);
                h = (int) Math.round(h / shrink);
            }
            MapView shot = new MapView(cache);
            shot.setPins(pins);
            shot.setGraphics(graphics);
            shot.setSource(map.source());
            shot.setFollow(false);
            if (track != null) shot.update(track, null, -1, false, false);
            shot.centreOn((south + north) / 2, (west + east) / 2, zoom);
            javax.imageio.ImageIO.write(shot.picture(Math.max(64, w), Math.max(64, h)), "png", png.toFile());
            String line = "Exported " + items.size() + " drawings and " + inBox.size() + " pins: " + kml + " and " + png.getFileName();
            javax.swing.JOptionPane.showMessageDialog(this, "<html>Saved:<br>" + kml + "<br>" + png + "</html>", "Export", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            System.out.println(line);
        } catch (java.io.IOException | RuntimeException e) {
            javax.swing.JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage(), "Export", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    /** IMPORT: a KML file's lines, areas, arrows, symbols and points (as pins) onto the map; the map goes to them. */
    private void importKml() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(java.nio.file.Path.of(System.getProperty("user.home"), "Documents", "DRAUPNE Link", "Exports").toFile());
        chooser.setDialogTitle("Import a KML file");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("KML", "kml"));
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        try {
            Kml.Parsed parsed = Kml.read(java.nio.file.Files.readString(chooser.getSelectedFile().toPath(), java.nio.charset.StandardCharsets.UTF_8));
            graphics.addAll(parsed.items());
            for (Pins.Pin p : parsed.pins()) pins.add(p.name(), p.lat(), p.lon());
            double s = 90, n = -90, w = 180, e = -180;
            int points = 0;
            for (MapGraphics.Item it : parsed.items()) {
                double[] b = it.bounds();
                s = Math.min(s, b[0]);
                n = Math.max(n, b[1]);
                w = Math.min(w, b[2]);
                e = Math.max(e, b[3]);
                points++;
            }
            for (Pins.Pin p : parsed.pins()) {
                s = Math.min(s, p.lat());
                n = Math.max(n, p.lat());
                w = Math.min(w, p.lon());
                e = Math.max(e, p.lon());
                points++;
            }
            if (points > 0) {
                map.setFollow(false);
                map.centreOn((s + n) / 2, (w + e) / 2, Math.max(map.zoom(), 12));
                syncFollow();
            }
            javax.swing.JOptionPane.showMessageDialog(this, parsed.items().size() + " drawings and symbols, " + parsed.pins().size() + " pins imported.",
                    "Import", javax.swing.JOptionPane.INFORMATION_MESSAGE);
        } catch (java.io.IOException | RuntimeException e) {
            javax.swing.JOptionPane.showMessageDialog(this, "Import failed: " + e.getMessage(), "Import", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    /** PLACE AT: a position typed or pasted in any format the map writes; a point of the drawing on, else a symbol. */
    private void placeAtPosition() {
        String text = (String) javax.swing.JOptionPane.showInputDialog(this,
                "Position: MGRS (32V PN 35026 85877), UTM, or latitude, longitude", "Place at", javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, "");
        if (text == null || text.isBlank()) return;
        double[] ll = Coordinates.parse(text);
        if (ll == null) {
            javax.swing.JOptionPane.showMessageDialog(this, "Not a position I can read: " + text, "Place at", javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        map.placeAt(ll[0], ll[1]);
        syncFollow();
    }

    // ---- RADIO HORIZON (1.0.39): the data round the mast loads on one thread, the sums run on another

    private final java.util.concurrent.atomic.AtomicInteger horizonRun = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger horizonSum = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ExecutorService horizonLoad = java.util.concurrent.Executors.newSingleThreadExecutor(r -> daemon(r, "radioHorizonLoad"));
    private final java.util.concurrent.ExecutorService horizonMath = java.util.concurrent.Executors.newSingleThreadExecutor(r -> daemon(r, "radioHorizonMath"));
    private volatile RadioHorizon.Data horizonData;
    private volatile RadioHorizon.Settings horizonSettings;
    private volatile String horizonLoading; // "forest map n / m" while it comes in

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    }

    /** A mast placed (or moved): its terrain and forest load, the horizon is drawn as soon as the terrain is in. Null: it went. */
    private void horizonMast(MapGraphics.LatLon mast) {
        int run = horizonRun.incrementAndGet();
        horizonData = null;
        horizonLoading = null;
        if (mast == null) {
            tools.horizonStatus.setText(" ");
            return;
        }
        tools.horizonStatus.setText(status("Loading the terrain..."));
        horizonLoad.execute(() -> {
            if (horizonRun.get() != run) return;
            long[] last = {0};
            RadioHorizon.load(cache, mast.lat(), mast.lon(), (data, terrainDone, terrainTotal, forestDone, forestTotal) -> {
                if (horizonRun.get() != run) return;
                horizonData = data;
                horizonLoading = forestDone < forestTotal ? "forest map " + forestDone + " / " + forestTotal : null;
                long now = System.currentTimeMillis();
                if (forestDone == 0 || forestDone == forestTotal || now - last[0] >= 3000) { // redrawn as the forest fills in
                    last[0] = now;
                    recomputeHorizon();
                }
            }, () -> horizonRun.get() != run);
        });
    }

    /** The horizon again with the data so far and the heights as set; only the newest asked-for sum runs. */
    private void recomputeHorizon() {
        RadioHorizon.Data data = horizonData;
        if (data == null) return;
        int run = horizonRun.get(), sum = horizonSum.incrementAndGet();
        horizonMath.execute(() -> {
            if (horizonSum.get() != sum || horizonRun.get() != run) return;
            RadioHorizon.Result r = RadioHorizon.compute(data.lat(), data.lon(), horizonSettings, data, data);
            String loading = horizonLoading;
            SwingUtilities.invokeLater(() -> {
                if (horizonRun.get() != run) return;
                map.setHorizon(r, horizonMapLine(r, loading));
                tools.horizonStatus.setText(status(horizonPanelText(r, loading)));
            });
        });
    }

    // ---- GCS SEARCH (1.0.39): the same two threads; a run stands for one wanted area

    private final java.util.concurrent.atomic.AtomicInteger searchRun = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger searchSum = new java.util.concurrent.atomic.AtomicInteger();
    private volatile RadioHorizon.Data searchData;
    private volatile double[] searchAreaWanted;
    private volatile String searchLoading;

    /** A wanted area drawn: the data round it loads, the heat map is worked out once the terrain is in and again when the forest is. */
    private void searchArea(double[] area) {
        int run = searchRun.incrementAndGet();
        searchData = null;
        searchLoading = null;
        searchAreaWanted = area;
        if (area == null) {
            tools.searchStatus.setText(" ");
            return;
        }
        tools.searchStatus.setText(status("Loading the terrain..."));
        double cLat = (area[0] + area[1]) / 2, cLon = (area[2] + area[3]) / 2;
        double radius = RadioHorizon.searchRadiusM(area[0], area[1], area[2], area[3]);
        horizonLoad.execute(() -> {
            if (searchRun.get() != run) return;
            RadioHorizon.load(cache, cLat, cLon, radius, (data, terrainDone, terrainTotal, forestDone, forestTotal) -> {
                if (searchRun.get() != run) return;
                searchData = data;
                searchLoading = forestDone < forestTotal ? "forest map " + forestDone + " / " + forestTotal : null;
                if (forestDone == 0 || forestDone == forestTotal) recomputeSearch(); // a first look on the terrain, then the real one
                else SwingUtilities.invokeLater(() -> {
                    if (searchRun.get() == run && map.search() == null) map.setSearch(null, "Loading the " + searchLoading + "...");
                });
            }, () -> searchRun.get() != run);
        });
    }

    /** The heat map again with the data so far and the settings as set; a newer request stops an older sum. */
    private void recomputeSearch() {
        RadioHorizon.Data data = searchData;
        double[] area = searchAreaWanted;
        if (data == null || area == null) return;
        int run = searchRun.get(), sum = searchSum.incrementAndGet();
        horizonMath.execute(() -> {
            if (searchSum.get() != sum || searchRun.get() != run) return;
            String loading = searchLoading;
            int points = RadioHorizon.SEARCH_POINTS * RadioHorizon.SEARCH_POINTS;
            RadioHorizon.Search r = RadioHorizon.search(area[0], area[1], area[2], area[3], horizonSettings, data, data,
                    done -> SwingUtilities.invokeLater(() -> {
                        if (searchRun.get() == run && searchSum.get() == sum) {
                            String line = "Working it out: " + done + " / " + points + " points";
                            map.setSearch(map.search(), line);
                            tools.searchStatus.setText(status(line));
                        }
                    }),
                    () -> searchSum.get() != sum || searchRun.get() != run);
            if (searchSum.get() != sum || searchRun.get() != run) return;
            SwingUtilities.invokeLater(() -> {
                if (searchRun.get() != run) return;
                map.setSearch(r, searchMapLine(r, loading));
                tools.searchStatus.setText(status(searchPanelText(r, loading)));
            });
        });
    }

    // ---- 4G COVERAGE (1.0.44): the cells and the terrain load and the estimate is worked out on a thread of its own

    static final String COVERAGE_KEY = "map.coverage.key", COVERAGE_OPERATOR = "map.coverage.operator", COVERAGE_HEIGHT = "map.coverage.heightM";
    /** Masts this far outside the view still count, and their cells are fetched with the view's. */
    static final double COVERAGE_MARGIN_M = 3000;
    /** A wider view is worked out from the cells already known: fetching it would take hundreds of the key's requests. */
    static final double COVERAGE_FETCH_MAX_M = 25_000;
    /** Boxes fetched for one resting view at most (two requests each, of the key's 1,000 a day); the rest on the next rest. */
    static final int COVERAGE_MAX_FETCH = 60;
    /** An estimate is shown between fetches every this many boxes. */
    private static final int COVERAGE_SHOW_EVERY = 20;
    /** The estimate's points about this many pixels apart, at most 160 x 120 of them. */
    private static final int COVERAGE_PX = 10;

    private final OpenCellId cellData = new OpenCellId(OpenCellId.defaultDir());
    private final java.util.concurrent.ExecutorService coverageWork = java.util.concurrent.Executors.newSingleThreadExecutor(r -> daemon(r, "cellCoverage"));
    private final java.util.concurrent.atomic.AtomicInteger coverageRun = new java.util.concurrent.atomic.AtomicInteger();
    private boolean coverageOn;
    private volatile String coverageKey = "";
    /** The view last worked out, and whether everything in it was fetched (else the same view is tried again). */
    private double[] coverageArea;
    private volatile boolean coverageComplete;

    static int heightIndex(String value) {
        int wanted;
        try {
            wanted = Integer.parseInt(value.trim());
        } catch (RuntimeException e) {
            wanted = CellCoverage.DEFAULT_HEIGHT_M;
        }
        int[] h = CellCoverage.HEIGHTS_M;
        for (int i = 0; i < h.length; i++) if (h[i] == wanted) return i;
        for (int i = 0; i < h.length; i++) if (h[i] == CellCoverage.DEFAULT_HEIGHT_M) return i;
        return 0;
    }

    private OpenCellId.Operator coverageOperator() {
        return OpenCellId.Operator.values()[Math.max(0, tools.coverageOperator.getSelectedIndex())];
    }

    private int coverageHeightM() {
        return CellCoverage.HEIGHTS_M[Math.max(0, tools.coverageHeight.getSelectedIndex())];
    }

    void setCoverageOn(boolean on) {
        coverageOn = on;
        tools.setCoverageOn(on);
        coverageArea = null;
        if (!on) {
            coverageRun.incrementAndGet(); // a fetch under way stops
            map.setCoverage(false, null, null);
            tools.coverageStatus.setText(" ");
            return;
        }
        map.setCoverage(true, null, "Working it out...");
        coverageView(map.visibleArea());
    }

    boolean coverageOn() {
        return coverageOn;
    }

    private void coverageChoiceChanged() {
        saver.accept(Map.of(COVERAGE_OPERATOR, coverageOperator().name(), COVERAGE_HEIGHT, Integer.toString(coverageHeightM())));
        coverageArea = null;
        if (coverageOn) coverageView(map.visibleArea());
    }

    private void askCoverageKey() {
        String text = "<html>The 4G COVERAGE estimate needs an OpenCelliD access token (free).<br>Register at https://opencellid.org, log in, "
                + "and copy the Access Token<br>your account page shows. It is kept in this PC's settings file.<br><br>"
                + "A key has 1,000 requests a day: each 1.8 km box of map takes two, once a month.</html>";
        Object answer = javax.swing.JOptionPane.showInputDialog(this, text, "OpenCelliD key", javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, coverageKey);
        if (answer == null) return;
        coverageKey = answer.toString().trim();
        saver.accept(Map.of(COVERAGE_KEY, coverageKey));
        coverageArea = null;
        if (coverageOn) coverageView(map.visibleArea());
    }

    /** A country file from OpenCelliD's downloads page, read on the coverage thread (Norway's is tens of MB unpacked). */
    private void importCells() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(java.nio.file.Path.of(System.getProperty("user.home"), "Downloads").toFile());
        chooser.setDialogTitle("Import an OpenCelliD cell file (242.csv.gz is Norway)");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("OpenCelliD CSV (.csv, .csv.gz)", "csv", "gz"));
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        java.nio.file.Path file = chooser.getSelectedFile().toPath();
        tools.coverageStatus.setText(status("Importing " + file.getFileName() + "..."));
        coverageWork.execute(() -> {
            String line;
            try {
                line = cellData.importCsv(file) + " 4G / 5G cells imported";
            } catch (java.io.IOException | RuntimeException e) {
                line = "Import failed: " + e.getMessage();
            }
            String done = line;
            SwingUtilities.invokeLater(() -> {
                tools.coverageStatus.setText(status(done));
                coverageArea = null;
                if (coverageOn) coverageView(map.visibleArea());
            });
        });
    }

    /** Two views are the same for the estimate when neither the centre nor the size moved by more than a tenth of it. */
    static boolean sameView(double[] a, double[] b) {
        double h = a[1] - a[0], w = a[3] - a[2];
        return Math.abs((a[0] + a[1]) - (b[0] + b[1])) / 2 < h / 10 && Math.abs((a[2] + a[3]) - (b[2] + b[3])) / 2 < w / 10
                && Math.abs(h - (b[1] - b[0])) < h / 10 && Math.abs(w - (b[3] - b[2])) < w / 10;
    }

    /** The view rested: unless it is the one already done, its estimate is worked out anew (a newer view stops an older one). */
    private void coverageView(double[] area) {
        if (!coverageOn || !map.toolOverlaysShown()) return;
        if (coverageArea != null && coverageComplete && sameView(coverageArea, area)) return;
        coverageArea = area;
        coverageComplete = false;
        int run = coverageRun.incrementAndGet();
        OpenCellId.Operator op = coverageOperator();
        int droneM = coverageHeightM();
        String key = coverageKey;
        int cols = Math.max(8, Math.min(160, map.getWidth() / COVERAGE_PX)), rows = Math.max(8, Math.min(120, map.getHeight() / COVERAGE_PX));
        coverageWork.execute(() -> computeCoverage(run, area, op, droneM, key, cols, rows));
    }

    /**
     * On the coverage thread: the terrain round the view, then the boxes of cells not fetched yet (nearest the middle
     * first, a capped number, an estimate shown every few), then the estimate from everything known.
     */
    private void computeCoverage(int run, double[] area, OpenCellId.Operator op, int droneM, String key, int cols, int rows) {
        java.util.function.BooleanSupplier cancelled = () -> coverageRun.get() != run;
        if (cancelled.getAsBoolean()) return;
        double[] reach = CellCoverage.grow(area, COVERAGE_MARGIN_M);
        double mid = (area[0] + area[1]) / 2;
        double sizeM = Math.max(Mercator.distanceM(mid, area[2], mid, area[3]), Mercator.distanceM(area[0], area[2], area[1], area[2]));
        List<OpenCellId.Tile> tiles = OpenCellId.tiles(reach[0], reach[1], reach[2], reach[3]);
        long now = System.currentTimeMillis();
        List<OpenCellId.Tile> missing = new ArrayList<>();
        for (OpenCellId.Tile t : tiles) if (!cellData.isFresh(t, now)) missing.add(t);
        boolean noKey = key.isBlank();
        boolean tooWide = sizeM > COVERAGE_FETCH_MAX_M;
        int toFetch = noKey || tooWide ? 0 : Math.min(missing.size(), Math.min(COVERAGE_MAX_FETCH, cellData.creditsLeft() / 2));
        coverageLine(run, "Loading the terrain...");
        RadioHorizon.Data terrain = CellCoverage.loadTerrain(cache, reach, cancelled);
        if (cancelled.getAsBoolean()) return;
        String problem = null;
        int fetched = 0;
        if (toFetch > 0) publishCoverage(run, area, reach, tiles, op, droneM, terrain, cols, rows, "fetching cells 0 / " + toFetch + "...", false);
        for (OpenCellId.Tile t : missing.subList(0, toFetch)) {
            if (cancelled.getAsBoolean()) return;
            OpenCellId.Answer a = cellData.fetch(t, key);
            if (a.cells() == null) {
                problem = switch (a.errorCode()) {
                    case 2 -> "OpenCelliD does not know the key";
                    case 7 -> "the key's requests for today are used up";
                    case 0 -> "no connection to OpenCelliD: saved cells only";
                    default -> "OpenCelliD: " + a.error();
                };
                break;
            }
            fetched++;
            if (fetched % COVERAGE_SHOW_EVERY == 0 && fetched < toFetch) {
                publishCoverage(run, area, reach, tiles, op, droneM, terrain, cols, rows, "fetching cells " + fetched + " / " + toFetch + "...", false);
            }
        }
        int left = missing.size() - fetched;
        if (problem == null && left > 0) {
            problem = noKey ? "no OpenCelliD key (MAP TOOLS > TOOLS > Key...)" : tooWide ? "zoom in to fetch the cells here"
                    : cellData.creditsLeft() < 2 ? "the key's requests for today are used up" : left + " boxes not fetched yet: rest the map again";
        }
        publishCoverage(run, area, reach, tiles, op, droneM, terrain, cols, rows, problem, left == 0);
    }

    /** The estimate from the cells known now (fetched boxes, however old, and the imported file), shown with a status line. */
    private void publishCoverage(int run, double[] area, double[] reach, List<OpenCellId.Tile> tiles, OpenCellId.Operator op, int droneM,
                                 RadioHorizon.Data terrain, int cols, int rows, String problem, boolean complete) {
        java.util.Set<OpenCellId.Tile> known = new java.util.HashSet<>();
        List<OpenCellId.Cell> cells = new ArrayList<>(cellData.importedIn(reach[0], reach[1], reach[2], reach[3]));
        for (OpenCellId.Tile t : tiles) {
            List<OpenCellId.Cell> c = cellData.cached(t);
            if (c != null) {
                known.add(t);
                cells.addAll(c);
            } else if (cellData.coveredByImport(t)) {
                known.add(t);
            }
        }
        List<CellCoverage.Site> sites = CellCoverage.sites(cells, op);
        CellCoverage.Grid grid = CellCoverage.predict(area, cols, rows, sites, droneM, op, terrain,
                (lat, lon) -> known.contains(OpenCellId.Tile.at(lat, lon)), () -> coverageRun.get() != run);
        if (grid == null) return;
        String line = coverageText(grid, problem);
        SwingUtilities.invokeLater(() -> {
            if (coverageRun.get() != run) return;
            if (complete) coverageComplete = true;
            map.setCoverage(true, grid, line);
            tools.coverageStatus.setText(status(line.replace("  -  ", "<br>")));
        });
    }

    private void coverageLine(int run, String line) {
        SwingUtilities.invokeLater(() -> {
            if (coverageRun.get() != run) return;
            map.setCoverage(true, map.coverage(), line);
            tools.coverageStatus.setText(status(line));
        });
    }

    /** The legend's status: how many masts, how much of the view is good and how much weak or worse, and what is wrong if anything. */
    static String coverageText(CellCoverage.Grid g, String problem) {
        int[] c = g.counts();
        int all = c[0] + c[1] + c[2] + c[3];
        StringBuilder b = new StringBuilder();
        b.append(g.sites().size()).append(g.sites().size() == 1 ? " mast" : " masts");
        if (all > 0) {
            b.append(String.format(Locale.ROOT, ", %d %% good, %d %% weak or none", Math.round(100.0 * c[CellCoverage.GOOD] / all),
                    Math.round(100.0 * (c[CellCoverage.WEAK] + c[CellCoverage.NONE]) / all)));
        } else {
            b.append(", no cells known here");
        }
        if (g.terrainMissing()) b.append(", some terrain missing");
        if (problem != null) b.append("  -  ").append(problem);
        return b.toString();
    }

    static String searchMapLine(RadioHorizon.Search r, String loading) {
        return "GCS SEARCH  best " + Math.round(r.bestScore() * 100) + " %" + (loading != null ? "  -  " + loading : "");
    }

    /** Short lines for the narrow tools panel (the pilot: the long text was cut off and too small); the colours are in the tooltip. */
    static String searchPanelText(RadioHorizon.Search r, String loading) {
        StringBuilder sb = new StringBuilder();
        double dist = Mercator.distanceM(r.lat(), r.lon(), r.bestLat(), r.bestLon());
        sb.append(String.format(Locale.ROOT, "Best: %d %% of the area<br>%s from its centre, %03d°",
                Math.round(r.bestScore() * 100), km(dist), Mercator.bearingDeg(r.lat(), r.lon(), r.bestLat(), r.bestLon())));
        if (r.settings().treeM() > 0) sb.append(String.format(Locale.ROOT, "<br>Through %.0f m of forest", r.forestDepthM()));
        if (loading != null) sb.append("<br>Terrain only, loading the ").append(loading);
        else if (r.forestUnknownPct() > 0) sb.append("<br>No forest map: ").append(r.forestUnknownPct()).append(" %");
        if (r.terrainMissing()) sb.append("<br>Some terrain missing");
        return sb.toString();
    }

    private static double furthestM(RadioHorizon.Result r) {
        double far = 0;
        for (int b = 0; b < RadioHorizon.BEARINGS; b++) far = Math.max(far, r.reachM(b));
        return far;
    }

    private static String km(double m) {
        return String.format(Locale.ROOT, "%.1f km", m / 1000);
    }

    static String horizonMapLine(RadioHorizon.Result r, String loading) {
        return "RADIO HORIZON  up to " + km(furthestM(r)) + (loading != null ? "  -  " + loading : "");
    }

    static String horizonPanelText(RadioHorizon.Result r, String loading) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Seen up to %s<br>Ground at the mast %.0f m", km(furthestM(r)), r.groundM()));
        if (r.settings().treeM() > 0) sb.append(String.format(Locale.ROOT, "<br>Through %.0f m of forest", r.forestDepthM()));
        if (loading != null) sb.append("<br>Loading the ").append(loading);
        else if (r.forestUnknownPct() > 0) sb.append("<br>No forest map: ").append(r.forestUnknownPct()).append(" %");
        if (r.terrainMissing()) sb.append("<br>Some terrain missing");
        return sb.toString();
    }

    private static String status(String text) {
        return "<html><div style='width:" + (MapToolsPanel.BODY_WIDTH - 60) + "px'>" + text + "</div></html>"; // room for the scrollbar
    }

    /** The tour's MAP TOOLS tab (opens the tools), and the tools inside it. */
    javax.swing.JComponent tourTools() {
        return tools.tabButton();
    }

    /** The tour's DRAW / TOOLS / DATA tabs. */
    javax.swing.JComponent tourGroups() {
        return tools.groupButton(MapToolsPanel.Group.TOOLS);
    }

    javax.swing.JComponent tourHorizon() {
        tools.showGroup(MapToolsPanel.Group.TOOLS, false);
        return tools.horizon;
    }

    javax.swing.JComponent tourCoverage() {
        tools.showGroup(MapToolsPanel.Group.TOOLS, false);
        return tools.coverage;
    }

    javax.swing.JComponent tourSearch() {
        tools.showGroup(MapToolsPanel.Group.TOOLS, false);
        return tools.gcsSearch;
    }

    void openTools(boolean open) {
        tools.setOpen(open, true);
    }

    javax.swing.JComponent tourRuler() {
        tools.showGroup(MapToolsPanel.Group.TOOLS, false);
        return tools.ruler;
    }

    javax.swing.JComponent tourDraw() {
        tools.showGroup(MapToolsPanel.Group.DRAW, false);
        return tools.line;
    }

    javax.swing.JComponent tourSymbol() {
        tools.showGroup(MapToolsPanel.Group.DRAW, false);
        return tools.symbol;
    }

    javax.swing.JComponent tourExport() {
        tools.showGroup(MapToolsPanel.Group.DATA, false);
        return tools.export;
    }

    /** Called when the window is minimized or brought back (the LINK card's minimap depends on it); set by the owner. */
    private Runnable onShownChanged = () -> { };

    void setOnShownChanged(Runnable listener) {
        onShownChanged = listener;
    }

    private static JLabel value() {
        return Theme.label("-", Theme.monoFont(Font.BOLD, 12.5f), Theme.TEXT);
    }

    private static JPanel stat(JPanel panel, String name, JLabel value) {
        JPanel cell = new JPanel(new GridLayout(2, 1, 0, 3));
        cell.setOpaque(false);
        cell.add(Theme.label(name, Theme.headingFont(11f), Theme.MUTED));
        cell.add(value);
        panel.add(cell);
        return cell;
    }

    MapView map() {
        return map;
    }

    // ---- what the tour points at
    javax.swing.JComponent tourFollow() {
        return followButton;
    }

    javax.swing.JComponent tourPins() {
        tools.showGroup(MapToolsPanel.Group.DRAW, false);
        return tools.pins;
    }

    javax.swing.JComponent tourDownload() {
        tools.showGroup(MapToolsPanel.Group.DATA, false);
        return tools.download;
    }

    javax.swing.JComponent tourTerrain() {
        return terrainCell;
    }

    javax.swing.JComponent tourCursor() {
        return cursorCell;
    }

    javax.swing.JComponent tourDrone() {
        return droneCell;
    }

    /** The MAP TYPE combo (the map picture). */
    javax.swing.JComponent tourMapType() {
        return pictureCombo;
    }

    /** The coordinate format combo. */
    javax.swing.JComponent tourFormat() {
        return formatCombo;
    }

    /** DOWNLOAD AREA, the menu or a box drawn on the map: the download window for that area, with the map type shown. */
    private void downloadArea(double[] area) {
        if (downloader == null || !downloader.isDisplayable()) {
            downloader = new MapDownloadDialog(this, cache, map.effectiveSource(), map.zoom(), area, map::selectAreaNext, line -> {
                downloadLine.setText(line == null ? "" : line);
                downloadLine.setVisible(line != null && downloader != null && !downloader.isVisible());
            });
        } else {
            downloader.setArea(area, map.effectiveSource(), map.zoom());
        }
        downloader.setVisible(true);
        downloader.toFront();
    }

    TileSource source() {
        return map.source();
    }

    void setSource(TileSource source) {
        map.setSource(source);
        syncCombos();
    }

    void setFormat(Coordinates.Format format) {
        this.format = format;
        syncCombos();
        if (cursor != null) cursorValue.setText(Coordinates.format(format, cursor[0], cursor[1]));
        refreshStats();
    }

    /** Which GPS the pilot chose, for the POSITION FROM figure. */
    void setPreferPhone(boolean preferPhone) {
        this.preferPhone = preferPhone;
        refreshStats();
    }

    private void syncCombos() {
        settingCombos = true;
        int i = 0;
        for (int k = 0; k < SOURCES.length; k++) if (SOURCES[k] == map.source()) i = k;
        pictureCombo.setSelectedIndex(i);
        formatCombo.setSelectedIndex(format.ordinal());
        settingCombos = false;
    }

    private void syncFollow() {
        followButton.setText(map.isFollowing() ? "FOLLOW: ON" : "FOLLOW: OFF");
    }

    private FlightTrack track;

    /** The camera's view on the ground (1.0.39), or null: drawn with the next update. */
    void setCameraView(de.droiddrone.pc.map.CameraFootprint.View view) {
        map.setCameraView(view);
    }

    void update(FlightTrack track, FcTelemetry.Gps gps, int headingDeg, boolean fcLost, boolean connected) {
        this.track = track;
        map.update(track, gps, headingDeg, fcLost, connected);
        syncFollow();
        refreshStats();
    }

    private void clearStats() {
        cursorValue.setText("-");
        terrainValue.setText("-");
        droneValue.setText("-");
        altitudeValue.setText("-");
        homeValue.setText("-");
        sourceValue.setText("-");
    }

    private void refreshStats() {
        FlightTrack.Point last = track == null ? null : track.last();
        if (last == null) {
            droneValue.setText("-");
            altitudeValue.setText("-");
            homeValue.setText("-");
            sourceValue.setText("-");
            return;
        }
        droneValue.setText(Coordinates.format(format, last.lat(), last.lon()));
        altitudeValue.setText(last.altM() + " M  /  " + Coordinates.feet(last.altM()));
        FlightTrack.Point h = track.home();
        homeValue.setText(h == null ? "NOT ARMED YET" : MapView.distanceText(Mercator.distanceM(h.lat(), h.lon(), last.lat(), last.lon()))
                + "   BRG " + String.format(Locale.ROOT, "%03d°", Mercator.bearingDeg(h.lat(), h.lon(), last.lat(), last.lon())));
        // short: the cell has room for about 22 characters
        String source = last.phone() ? "PHONE" + (last.accuracyM() > 0 ? " ±" + last.accuracyM() + " M" : " GPS") : "DRONE GPS";
        if (last.phone() != preferPhone) source += "  (FALLBACK)";
        sourceValue.setText(source);
    }

    // ------------------------------------------------------------------ GO TO, TRACKS, the CURSOR cell

    /** GO TO: a position pasted in any format the map writes; the map centres there, with a pin if asked. */
    private void goToPosition() {
        javax.swing.JTextField field = new javax.swing.JTextField(28);
        JLabel hint = Theme.label("MGRS (32V NM 97254 42617), UTM (32V 597254 6642617) or 59.9139, 10.7522", Theme.font(Font.PLAIN, 12f), Theme.MUTED);
        JPanel box = new JPanel(new BorderLayout(0, 6));
        box.setOpaque(false);
        box.add(field, BorderLayout.NORTH);
        box.add(hint, BorderLayout.SOUTH);
        Object[] options = {"Go", "Go and pin", "Cancel"};
        while (true) {
            int answer = javax.swing.JOptionPane.showOptionDialog(this, box, "Go to position", javax.swing.JOptionPane.DEFAULT_OPTION,
                    javax.swing.JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (answer != 0 && answer != 1) return;
            double[] ll = Coordinates.parse(field.getText());
            if (ll == null) {
                hint.setText("Not a position I can read. " + hint.getText());
                hint.setForeground(Theme.BAD);
                continue;
            }
            map.centreOn(ll[0], ll[1], Math.max(map.zoom(), 15));
            syncFollow();
            if (answer == 1) map.addPinAt(ll[0], ll[1], null);
            return;
        }
    }

    /** TRACKS: the earlier flights in the Tracks folder, newest first; one on the map at a time, or none. */
    private javax.swing.JPopupMenu tracksMenu() {
        javax.swing.JPopupMenu menu = TacticalCombo.menu();
        menu.setBackground(Theme.PANEL_2);
        java.util.List<java.nio.file.Path> files = java.util.List.of();
        try {
            files = FlightTrack.files(FlightTrack.defaultDir());
        } catch (java.io.IOException ignored) {
        }
        if (files.isEmpty()) {
            javax.swing.JMenuItem none = new javax.swing.JMenuItem("No earlier flights in the Tracks folder");
            none.setEnabled(false);
            menu.add(none);
        }
        int shown = 0;
        for (java.nio.file.Path f : files) {
            if (++shown > 25) break;
            String name = f.getFileName().toString();
            javax.swing.JMenuItem item = new javax.swing.JMenuItem(trackTitle(name));
            item.addActionListener(e -> showOldTrack(f));
            menu.add(item);
        }
        if (map.hasOldTrack()) {
            menu.addSeparator();
            javax.swing.JMenuItem fit = new javax.swing.JMenuItem("Fit the earlier flight");
            fit.addActionListener(e -> {
                map.fitOldTrack();
                syncFollow();
            });
            menu.add(fit);
            javax.swing.JMenuItem clear = new javax.swing.JMenuItem("Clear the earlier flight");
            clear.addActionListener(e -> map.setOldTrack(null, null));
            menu.add(clear);
        }
        return menu;
    }

    /** "2026-11-02 14:05" from track-20261102-140512.gpx; the file's name when it isn't one of ours. */
    static String trackTitle(String fileName) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("track-(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})\\d{2}\\.gpx").matcher(fileName);
        return m.matches() ? m.group(1) + "-" + m.group(2) + "-" + m.group(3) + " " + m.group(4) + ":" + m.group(5) : fileName;
    }

    private void showOldTrack(java.nio.file.Path file) {
        try {
            FlightTrack t = FlightTrack.read(file);
            if (t.isEmpty()) {
                javax.swing.JOptionPane.showMessageDialog(this, "No positions in " + file.getFileName() + ".", "Tracks", javax.swing.JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            map.setOldTrack(t.points(), trackTitle(file.getFileName().toString()));
            map.fitOldTrack();
            syncFollow();
        } catch (java.io.IOException e) {
            javax.swing.JOptionPane.showMessageDialog(this, "Can't read " + file.getFileName() + ": " + e.getMessage(), "Tracks", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    /** The CURSOR cell's right-click: a pin where the mouse last was on the map (it has to leave the map to get here). */
    private javax.swing.JPopupMenu cursorMenu() {
        javax.swing.JPopupMenu menu = TacticalCombo.menu();
        menu.setBackground(Theme.PANEL_2);
        double[] at = lastCursor;
        javax.swing.JMenuItem pin = new javax.swing.JMenuItem(at == null ? "Move the mouse over the map first"
                : "Add pin at " + Coordinates.format(format, at[0], at[1]));
        pin.setEnabled(at != null);
        pin.addActionListener(e -> map.addPinAt(at[0], at[1], null));
        menu.add(pin);
        return menu;
    }

    // ------------------------------------------------------------------ the mouse

    /** Where the mouse last was on the map; stays when it leaves (the CURSOR cell's pin). */
    private double[] lastCursor;

    private void cursorAt(double[] latLon) {
        cursor = latLon;
        if (latLon != null) lastCursor = latLon;
        cursorSerial++;
        if (latLon == null) {
            cursorValue.setText("-");
            terrainValue.setText("-");
            terrainLookup.stop();
            return;
        }
        cursorValue.setText(Coordinates.format(format, latLon[0], latLon[1]));
        terrainLookup.restart();
    }

    private void lookupTerrain() {
        double[] c = cursor;
        if (c == null) return;
        long serial = cursorSerial;
        elevation.lookup(c[0], c[1], z -> SwingUtilities.invokeLater(() -> {
            if (serial != cursorSerial) return; // the mouse moved on
            terrainValue.setText(z == null ? "?" : String.format(Locale.ROOT, "%.0f M  /  %s", z, Coordinates.feet(z)));
        }));
    }

    // ------------------------------------------------------------------ where the window is

    /**
     * A window's place between runs, as "x,y,width,height[,max]": the un-maximized bounds and whether it was
     * maximized. Minimized is never saved (Windows reports -32000,-32000 then), and bounds off every screen that is
     * still there are not restored. For this window and the main one.
     */
    static final class Bounds {
        private final JFrame frame;
        private Rectangle normal; // the last bounds seen while neither maximized nor minimized

        Bounds(JFrame frame) {
            this.frame = frame;
        }

        /** Puts the frame where it was; false (nothing changed) when there is nothing usable to restore. */
        boolean restore(String saved) {
            if (saved == null || saved.isBlank()) return false;
            try {
                String[] p = saved.split(",");
                Rectangle r = new Rectangle(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
                boolean max = p.length > 4 && "max".equals(p[4].trim());
                if (r.width < 400 || r.height < 300) return false;
                for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                    Rectangle screen = d.getDefaultConfiguration().getBounds();
                    Rectangle on = screen.intersection(r);
                    if (on.width >= 200 && on.height >= 150) { // enough of it on a screen that is still there
                        frame.setBounds(r);
                        normal = r;
                        if (max) frame.setExtendedState(frame.getExtendedState() | JFrame.MAXIMIZED_BOTH);
                        return true;
                    }
                }
            } catch (RuntimeException e) {
                // not ours to parse
            }
            return false;
        }

        /** The value to save now, or null when the window's state says nothing worth keeping. */
        String current() {
            if (!frame.isVisible()) return null;
            int state = frame.getExtendedState();
            if ((state & JFrame.ICONIFIED) != 0) return null;
            boolean max = (state & JFrame.MAXIMIZED_BOTH) != 0;
            if (!max) normal = frame.getBounds();
            Rectangle r = normal;
            if (r == null) return null;
            return r.x + "," + r.y + "," + r.width + "," + r.height + (max ? ",max" : "");
        }
    }

    private final Bounds bounds = new Bounds(this);

    private boolean restoreBounds(String saved) {
        return bounds.restore(saved);
    }

    private void saveBounds() {
        String value = bounds.current();
        if (value != null) saver.accept(Map.of(BOUNDS_KEY, value));
    }

    /** True when the window is open on screen: not hidden, not minimized to the taskbar. */
    boolean isShown() {
        return isVisible() && (getExtendedState() & ICONIFIED) == 0;
    }

    /** A key that works anywhere in this window (F3, the map's keys). */
    void bindKey(javax.swing.KeyStroke key, Runnable action) {
        getRootPane().registerKeyboardAction(e -> action.run(), key, javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    /** Every opening fades the window in, like the program's other windows (not only the first). */
    @Override
    public void setVisible(boolean visible) {
        boolean opening = visible && !isVisible();
        super.setVisible(visible);
        if (opening) WindowReveal.fadeIn((javax.swing.JComponent) getContentPane());
    }

    /** Wide enough for the toolbar's buttons and both choices, and the six figures. */
    @Override
    public Dimension getMinimumSize() {
        return new Dimension(1120, 560);
    }
}
