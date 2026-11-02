package de.droiddrone.pc.gui;

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

/**
 * FIND DRONE: the drone's last known position on the map, with the figures to go and get it (coordinates to copy,
 * distance and bearing from home, how old the position is) and links that open the spot in Google Maps or
 * OpenStreetMap on this PC, to send on to a phone. Reads the current (or last) connection's track.
 */
final class FindDroneDialog extends JDialog {
    private final MapView map;

    private FindDroneDialog(Window owner, FlightTrack track, TileCache cache, TileSource source, Pins pins) {
        super(owner, "Find drone", ModalityType.MODELESS);
        setIconImages(owner.getIconImages());
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        setContentPane(root);

        FlightTrack.Point last = track == null ? null : track.last();
        FlightTrack.Point home = track == null ? null : track.home();

        JPanel card = Theme.card(new BorderLayout(0, 8));
        card.add(Theme.sectionTitle("Find drone"), BorderLayout.NORTH);
        JTextArea text = new JTextArea(describe(last, home, track == null ? null : track.file()));
        text.setEditable(false);
        text.setFocusable(false);
        text.setFont(Theme.monoFont(Font.PLAIN, 12.5f));
        text.setForeground(Theme.TEXT);
        text.setBackground(Theme.PANEL);
        text.setBorder(null);
        card.add(text, BorderLayout.CENTER);
        root.add(card, BorderLayout.NORTH);

        map = new MapView(cache);
        map.setPins(pins);
        map.setSource(source);
        map.setPreferredSize(new Dimension(640, 420));
        map.update(track, null, -1, true, false);
        if (last != null) map.centreOn(last.lat(), last.lon(), 16);
        root.add(map, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        if (last != null) {
            String coords = String.format(Locale.ROOT, "%.6f, %.6f", last.lat(), last.lon());
            JButton copy = Theme.secondaryButton("Copy position");
            copy.addActionListener(e -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(coords), null);
                copy.setText("COPIED");
            });
            buttons.add(copy);
            JButton google = Theme.secondaryButton("Google Maps");
            google.addActionListener(e -> browse(FlightTrack.googleMapsUrl(last.lat(), last.lon())));
            buttons.add(google);
            JButton osm = Theme.secondaryButton("OpenStreetMap");
            osm.addActionListener(e -> browse(FlightTrack.openStreetMapUrl(last.lat(), last.lon())));
            buttons.add(osm);
        }
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
        if (last == null) {
            JLabel none = Theme.label("No GPS position has come from the drone in this session.", Theme.font(Font.BOLD, 13f), Theme.WARN);
            card.add(none, BorderLayout.SOUTH);
        }
        pack();
        setLocationRelativeTo(owner);
    }

    static FindDroneDialog open(Window owner, FlightTrack track, TileCache cache, TileSource source, Pins pins) {
        FindDroneDialog d = new FindDroneDialog(owner, track, cache, source, pins);
        d.setVisible(true);
        return d;
    }

    static String describe(FlightTrack.Point last, FlightTrack.Point home, Path file) {
        if (last == null) return "Nothing to show yet: the map fills in once the drone's GPS has a fix.";
        StringBuilder sb = new StringBuilder();
        long age = System.currentTimeMillis() - last.timeMs();
        sb.append(String.format(Locale.ROOT, "Last position   %.6f, %.6f%n", last.lat(), last.lon()));
        sb.append("Seen            ").append(LocalDateTime.ofInstant(Instant.ofEpochMilli(last.timeMs()), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"))).append("  (").append(MapView.ageText(age).toLowerCase(Locale.ROOT)).append(")\n");
        sb.append(String.format(Locale.ROOT, "Altitude        %d m GPS     Speed %.0f km/h     Satellites %d     %s%n",
                last.altM(), last.speedKmh(), last.sats(), last.armed() ? "armed" : "not armed"));
        if (home != null) {
            sb.append(String.format(Locale.ROOT, "From launch     %s, bearing %03d°%n",
                    MapView.distanceText(Mercator.distanceM(home.lat(), home.lon(), last.lat(), last.lon())).toLowerCase(Locale.ROOT),
                    Mercator.bearingDeg(home.lat(), home.lon(), last.lat(), last.lon())));
        }
        if (file != null) sb.append("Track file      ").append(file.getFileName()).append('\n');
        return sb.toString().stripTrailing();
    }

    private static void browse(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ignored) {
        }
    }
}
