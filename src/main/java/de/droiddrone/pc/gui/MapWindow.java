package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.Elevation;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Mercator;
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
import java.util.Map;
import java.util.function.Consumer;

/**
 * The map in a window of its own, for a second screen (MAP > Map window, F3). Hidden until opened; remembers where
 * it was. A toolbar in the program's style above the map (FOLLOW, FIT FLIGHT, HOME, FIND DRONE; the map picture and
 * the coordinate format), the figures under it: the place under the mouse and the terrain height there (m and ft),
 * the drone's position, altitude, distance and bearing from home, and which GPS the position comes from.
 */
final class MapWindow extends JFrame {
    static final String BOUNDS_KEY = "mapWindow";
    private static final String[] PICTURES = {"AUTO", "TOPO", "PAPER", "SATELLITE", "OSM"};
    private static final TileSource[] SOURCES = {null, TileSource.KARTVERKET_TOPO, TileSource.KARTVERKET_RASTER, TileSource.SATELLITE, TileSource.OSM};

    private final MapView map;
    private final Elevation elevation;
    private final Consumer<Map<String, String>> saver;
    private final JButton followButton = Theme.secondaryButton("Follow: on");
    private final JComboBox<String> pictureCombo = new JComboBox<>(PICTURES);
    private final JComboBox<String> formatCombo = new JComboBox<>();
    private final JLabel cursorValue = value(), terrainValue = value(), droneValue = value(), altitudeValue = value(),
            homeValue = value(), sourceValue = value();
    private Coordinates.Format format;
    private boolean preferPhone;
    private double[] cursor;
    private long cursorSerial;
    private final Timer terrainLookup = new Timer(250, e -> lookupTerrain());
    private final Timer saveBounds = new Timer(800, e -> saveBounds());
    private boolean settingCombos;

    MapWindow(JFrame owner, TileCache cache, Elevation elevation, TileSource source, Coordinates.Format format, String savedBounds,
              Consumer<Map<String, String>> saver, Consumer<TileSource> onSource, Consumer<Coordinates.Format> onFormat, Runnable findDrone) {
        super("DRAUPNE Link map");
        this.elevation = elevation;
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
        map.setSource(source);
        map.setOnSourceChosen(s -> {
            onSource.accept(s);
            syncCombos();
        });
        map.setOnCursor(this::cursorAt);

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
        JButton fit = Theme.secondaryButton("Fit flight");
        fit.setToolTipText("The whole flight and home in view (also a double-click on the map)");
        fit.addActionListener(e -> {
            map.fitTrack();
            syncFollow();
        });
        left.add(fit);
        JButton home = Theme.secondaryButton("Home");
        home.setToolTipText("Centre on where the drone armed");
        home.addActionListener(e -> {
            FlightTrack.Point h = track == null ? null : track.home();
            if (h != null) map.centreOn(h.lat(), h.lon(), Math.max(map.zoom(), 15));
            syncFollow();
        });
        left.add(home);
        JButton find = Theme.primaryButton("Find drone");
        find.setToolTipText("The last known position, with the coordinates to copy and map links");
        find.addActionListener(e -> findDrone.run());
        left.add(find);
        bar.add(left, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
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

        root.add(map, BorderLayout.CENTER);

        // ---- the figures
        JPanel stats = Theme.card(new GridLayout(1, 6, 14, 0));
        stat(stats, "CURSOR", cursorValue);
        stat(stats, "TERRAIN HEIGHT", terrainValue);
        stat(stats, "DRONE", droneValue);
        stat(stats, "ALTITUDE (GPS)", altitudeValue);
        stat(stats, "HOME", homeValue);
        stat(stats, "POSITION FROM", sourceValue);
        root.add(stats, BorderLayout.SOUTH);
        clearStats();
        syncCombos();
        syncFollow();

        if (!restoreBounds(savedBounds)) {
            setSize(1120, 780);
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
    }

    private static JLabel value() {
        return Theme.label("-", Theme.monoFont(Font.BOLD, 12.5f), Theme.TEXT);
    }

    private static void stat(JPanel panel, String name, JLabel value) {
        JPanel cell = new JPanel(new GridLayout(2, 1, 0, 3));
        cell.setOpaque(false);
        cell.add(Theme.label(name, Theme.headingFont(11f), Theme.MUTED));
        cell.add(value);
        panel.add(cell);
    }

    MapView map() {
        return map;
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
        String source = last.phone() ? "PHONE GPS" : "DRONE GPS";
        if (last.phone() != preferPhone) source += "  (FALLBACK)";
        sourceValue.setText(source);
    }

    // ------------------------------------------------------------------ the mouse

    private void cursorAt(double[] latLon) {
        cursor = latLon;
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

    private boolean restoreBounds(String saved) {
        if (saved == null || saved.isBlank()) return false;
        try {
            String[] p = saved.split(",");
            Rectangle r = new Rectangle(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
            if (r.width < 400 || r.height < 300) return false;
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle screen = d.getDefaultConfiguration().getBounds();
                Rectangle on = screen.intersection(r);
                if (on.width >= 200 && on.height >= 150) { // enough of it on a screen that is still there
                    setBounds(r);
                    return true;
                }
            }
        } catch (RuntimeException e) {
            // not ours to parse
        }
        return false;
    }

    private void saveBounds() {
        if (!isVisible() || (getExtendedState() & MAXIMIZED_BOTH) != 0) return;
        Rectangle r = getBounds();
        saver.accept(Map.of(BOUNDS_KEY, r.x + "," + r.y + "," + r.width + "," + r.height));
    }

    /** Wide enough for the toolbar's buttons and both choices, and the six figures. */
    @Override
    public Dimension getMinimumSize() {
        return new Dimension(960, 560);
    }
}
