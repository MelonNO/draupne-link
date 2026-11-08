package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Mercator;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * FIND DRONE: the drone's last known position on the map, with the figures to go and get it (coordinates to copy,
 * distance and bearing from home, how old the position is) and links that open the spot in Google Maps or
 * OpenStreetMap on this PC, to send on to a phone. Reads the connection's track (or the last one) once a second
 * while open, so a drone that is still reporting moves on the map; without a position in this session it falls back
 * to the newest track file in the Tracks folder (a restart after a crash).
 */
final class FindDroneDialog extends JDialog {
    private final MapView map;
    private final Supplier<FlightTrack> session;
    private final BooleanSupplier connected, fcLost;
    private final JTextArea text = new JTextArea();
    private final JLabel none = Theme.label("", Theme.font(Font.BOLD, 13f), Theme.WARN);
    private final JButton copy = Theme.secondaryButton("Copy position"), copyMgrs = Theme.secondaryButton("Copy MGRS"),
            google = Theme.secondaryButton("Google Maps"), osm = Theme.secondaryButton("OpenStreetMap");
    private final Timer refresh = new Timer(1000, e -> refresh());
    private FlightTrack shown;
    private boolean fromFile;
    private FlightTrack.Point last;

    private FindDroneDialog(Window owner, Supplier<FlightTrack> session, BooleanSupplier connected, BooleanSupplier fcLost, TileCache cache,
                            TileSource source, Pins pins) {
        super(owner, "Find drone", ModalityType.MODELESS);
        this.session = session;
        this.connected = connected;
        this.fcLost = fcLost;
        setIconImages(owner.getIconImages());
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        setContentPane(root);

        JPanel card = Theme.card(new BorderLayout(0, 8));
        card.add(Theme.sectionTitle("Find drone"), BorderLayout.NORTH);
        text.setEditable(false);
        text.setFocusable(false);
        text.setFont(Theme.monoFont(Font.PLAIN, 12.5f));
        text.setForeground(Theme.TEXT);
        text.setBackground(Theme.PANEL);
        text.setBorder(null);
        card.add(text, BorderLayout.CENTER);
        none.setVisible(false);
        card.add(none, BorderLayout.SOUTH);
        root.add(card, BorderLayout.NORTH);

        map = new MapView(cache);
        map.setPins(pins);
        map.setSource(source);
        map.setPreferredSize(new Dimension(640, 420));
        root.add(map, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        copy.addActionListener(e -> {
            if (last == null) return;
            clip(String.format(Locale.ROOT, "%.6f, %.6f", last.lat(), last.lon()));
            copy.setText("COPIED");
        });
        buttons.add(copy);
        copyMgrs.addActionListener(e -> {
            if (last == null) return;
            clip(Coordinates.mgrs(last.lat(), last.lon(), 5));
            copyMgrs.setText("COPIED");
        });
        buttons.add(copyMgrs);
        google.addActionListener(e -> {
            if (last != null) browse(FlightTrack.googleMapsUrl(last.lat(), last.lon()));
        });
        buttons.add(google);
        osm.addActionListener(e -> {
            if (last != null) browse(FlightTrack.openStreetMapUrl(last.lat(), last.lon()));
        });
        buttons.add(osm);
        JButton folder = Theme.secondaryButton("Tracks folder");
        folder.addActionListener(e -> {
            try {
                java.awt.Desktop.getDesktop().open(FlightTrack.defaultDir().toFile());
            } catch (Exception ignored) {
            }
        });
        buttons.add(folder);
        buttons.add(Box.createHorizontalStrut(8));
        JButton close = Theme.primaryButton("Close");
        close.addActionListener(e -> dispose());
        buttons.add(close);
        root.add(buttons, BorderLayout.SOUTH);

        refresh.setRepeats(true);
        refresh();
        pack();
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                refresh.start();
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                refresh.stop();
            }
        });
    }

    /** Opens it; the session's track is read again every second (its supplier), or the last file when it has none. */
    static FindDroneDialog open(Window owner, Supplier<FlightTrack> session, BooleanSupplier connected, BooleanSupplier fcLost, TileCache cache,
                                TileSource source, Pins pins) {
        FindDroneDialog d = new FindDroneDialog(owner, session, connected, fcLost, cache, source, pins);
        d.setVisible(true);
        return d;
    }

    /** The MGRS of the position shown, or null. */
    String mgrs() {
        return last == null ? null : Coordinates.mgrs(last.lat(), last.lon(), 5);
    }

    /** The session's track when it has a position, else the newest track file; the text, buttons and map follow it. */
    private void refresh() {
        FlightTrack t = session.get();
        boolean file = false;
        if (t == null || t.isEmpty()) {
            if (shown == null || !fromFile) {
                try {
                    Path newest = FlightTrack.newest(FlightTrack.defaultDir());
                    t = newest == null ? null : FlightTrack.read(newest);
                } catch (java.io.IOException e) {
                    t = null;
                }
            } else {
                t = shown; // read once: the file doesn't change
            }
            file = t != null && !t.isEmpty();
        }
        shown = t;
        fromFile = file;
        last = t == null ? null : t.last();
        text.setText(describe(last, t == null ? null : t.home(), t == null ? null : t.file(), file));
        boolean has = last != null;
        for (JButton b : new JButton[]{copy, copyMgrs, google, osm}) b.setEnabled(has);
        none.setText(has ? "" : "No position yet. Last flight's: last-position.txt in Tracks.");
        if (none.isVisible() != !has) {
            none.setVisible(!has);
            pack();
        }
        // from a file nothing is live; otherwise the real link state, so a still-reporting drone isn't LAST KNOWN.
        // The map centres itself on the first position it gets and follows a drone that still moves
        map.update(t, null, -1, !file && fcLost.getAsBoolean(), !file && connected.getAsBoolean());
    }

    static String describe(FlightTrack.Point last, FlightTrack.Point home, Path file, boolean fromFile) {
        if (last == null) return "No position yet.";
        StringBuilder sb = new StringBuilder();
        long age = System.currentTimeMillis() - last.timeMs();
        sb.append(String.format(Locale.ROOT, "Last position   %.6f, %.6f%n", last.lat(), last.lon()));
        sb.append("MGRS            ").append(Coordinates.mgrs(last.lat(), last.lon(), 5)).append('\n');
        sb.append("Seen            ").append(LocalDateTime.ofInstant(Instant.ofEpochMilli(last.timeMs()), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern(age > 20 * 3600_000L ? "yyyy-MM-dd HH:mm:ss" : "HH:mm:ss"))).append("  (")
                .append(MapView.ageText(age).toLowerCase(Locale.ROOT)).append(")\n");
        String from = last.phone() ? "(phone GPS" + (last.accuracyM() > 0 ? ", ± " + last.accuracyM() + " m" : "") + ")" : "Satellites " + last.sats();
        sb.append(String.format(Locale.ROOT, "Altitude        %d m GPS     Speed %.0f km/h     %s     %s%n",
                last.altM(), last.speedKmh(), from, last.armed() ? "armed" : "not armed"));
        if (home != null) {
            sb.append(String.format(Locale.ROOT, "From launch     %s, bearing %03d°%n",
                    MapView.distanceText(Mercator.distanceM(home.lat(), home.lon(), last.lat(), last.lon())).toLowerCase(Locale.ROOT),
                    Mercator.bearingDeg(home.lat(), home.lon(), last.lat(), last.lon())));
        }
        if (file != null) sb.append(fromFile ? "From the file   " : "Track file      ").append(file.getFileName()).append(fromFile ? "  (none this session)" : "").append('\n');
        return sb.toString().stripTrailing();
    }

    private static void clip(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    private static void browse(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ignored) {
        }
    }
}
