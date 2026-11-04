package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.Elevation;
import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.map.Kml;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Mercator;
import de.droiddrone.pc.map.Pins;
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
    private static final String[] PICTURES = {"AUTO", "TOPO", "PAPER", "SATELLITE", "OSM"};
    private static final TileSource[] SOURCES = {null, TileSource.KARTVERKET_TOPO, TileSource.KARTVERKET_RASTER, TileSource.SATELLITE, TileSource.OSM};

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
        pictureCombo.setToolTipText("The map picture. Auto: Kartverket's topo map in Norway, OpenStreetMap elsewhere. Satellite from Esri");
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

    /** The tour's MAP TOOLS tab (opens the tools), and the tools inside it. */
    javax.swing.JComponent tourTools() {
        return tools.tabButton();
    }

    void openTools(boolean open) {
        tools.setOpen(open, true);
    }

    javax.swing.JComponent tourRuler() {
        return tools.ruler;
    }

    javax.swing.JComponent tourDraw() {
        return tools.line;
    }

    javax.swing.JComponent tourSymbol() {
        return tools.symbol;
    }

    javax.swing.JComponent tourExport() {
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
        return tools.pins;
    }

    javax.swing.JComponent tourDownload() {
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
