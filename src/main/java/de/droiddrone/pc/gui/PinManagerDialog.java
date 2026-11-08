package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Mercator;
import de.droiddrone.pc.map.Pins;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * PINS: every pin in a list - name, position in the chosen format, distance and bearing from the drone - with
 * GO TO (the map centres on it), COPY (its position to the clipboard, also Ctrl+C), RENAME, REMOVE, ADD AT GRID
 * (a position typed or pasted in any format GO TO reads), ADD AT DRONE, ADD AT LAUNCH and REMOVE ALL. Pins dropped by
 * right-click on a map show up here too (the list refreshes once a second).
 */
final class PinManagerDialog extends JDialog {
    private final Pins pins;
    private final MapView map;
    private final Supplier<Coordinates.Format> format;
    private final Supplier<FlightTrack> track;
    private final DefaultListModel<Pins.Pin> model = new DefaultListModel<>();
    private final JList<Pins.Pin> list = new JList<>(model);
    private final JLabel none = Theme.label("No pins. Right-click the map, or ADD AT GRID / DRONE / LAUNCH.", Theme.font(Font.PLAIN, 12.5f), Theme.MUTED);
    private final Timer refresh = new Timer(1000, e -> refresh());
    private List<Pins.Pin> shown = List.of();

    PinManagerDialog(Window owner, Pins pins, MapView map, Supplier<Coordinates.Format> format, Supplier<FlightTrack> track) {
        super(owner, "Pins", ModalityType.MODELESS);
        this.pins = pins;
        this.map = map;
        this.format = format;
        this.track = track;
        setIconImages(owner.getIconImages());
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        setContentPane(root);

        JPanel card = Theme.card(new BorderLayout(0, 8));
        card.add(Theme.sectionTitle("Pins"), BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Theme.PANEL);
        list.setForeground(Theme.TEXT);
        list.setSelectionBackground(Theme.PANEL_2);
        list.setSelectionForeground(Theme.TEXT);
        list.setFont(Theme.monoFont(Font.BOLD, 12.5f));
        list.setFixedCellHeight(26);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, selected, false);
                c.setText(line((Pins.Pin) value));
                c.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, selected ? 3 : 0, 0, 0, Theme.ACCENT), BorderFactory.createEmptyBorder(0, selected ? 7 : 10, 0, 10)));
                return c;
            }
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) goTo();
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        scroll.getViewport().setBackground(Theme.PANEL);
        scroll.setPreferredSize(new Dimension(620, 260));
        card.add(scroll, BorderLayout.CENTER);
        card.add(none, BorderLayout.SOUTH);
        root.add(card, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        JButton go = Theme.primaryButton("Go to");
        go.setToolTipText("Centre on the pin (or double-click)");
        go.addActionListener(e -> goTo());
        buttons.add(go);
        JButton copy = Theme.secondaryButton("Copy");
        copy.setToolTipText("Copy the position (Ctrl+C)");
        Timer copied = new Timer(1500, e -> copy.setText("Copy"));
        copied.setRepeats(false);
        Runnable copySelected = () -> {
            Pins.Pin p = list.getSelectedValue();
            if (p == null) return;
            MapView.copyPosition(format.get(), p);
            copy.setText("Copied");
            copied.restart();
        };
        copy.addActionListener(e -> copySelected.run());
        list.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ctrl C"), "copyPosition");
        list.getActionMap().put("copyPosition", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                copySelected.run();
            }
        });
        buttons.add(copy);
        JButton rename = Theme.secondaryButton("Rename");
        rename.addActionListener(e -> {
            Pins.Pin p = list.getSelectedValue();
            if (p == null) return;
            String name = (String) JOptionPane.showInputDialog(this, "New name", "Rename " + p.name(), JOptionPane.PLAIN_MESSAGE, null, null, p.name());
            if (name != null && !name.isBlank()) {
                if (map.isExamplePin(p)) map.changeExamplePin(p, name);
                else pins.rename(p, name);
                refresh();
            }
        });
        buttons.add(rename);
        JButton remove = Theme.secondaryButton("Remove");
        remove.addActionListener(e -> {
            Pins.Pin p = list.getSelectedValue();
            if (p == null) return;
            if (map.isExamplePin(p)) map.changeExamplePin(p, null);
            else pins.remove(p);
            refresh();
        });
        buttons.add(remove);
        buttons.add(Box.createHorizontalStrut(10));
        JButton atGrid = Theme.secondaryButton("Add at grid");
        atGrid.setToolTipText("Pin at a typed position: MGRS, UTM or lat, lon");
        atGrid.addActionListener(e -> addAtGrid());
        buttons.add(atGrid);
        JButton atDrone = Theme.secondaryButton("Add at drone");
        atDrone.setToolTipText("Pin at the drone's last known position");
        atDrone.addActionListener(e -> {
            FlightTrack t = track.get();
            FlightTrack.Point last = t == null ? null : t.last();
            if (last == null) return;
            pins.add(null, last.lat(), last.lon());
            refresh();
        });
        buttons.add(atDrone);
        JButton atLaunch = Theme.secondaryButton("Add at launch");
        atLaunch.setToolTipText("Pin where the drone armed");
        atLaunch.addActionListener(e -> {
            FlightTrack t = track.get();
            FlightTrack.Point h = t == null ? null : t.home();
            if (h == null) return;
            pins.add(null, h.lat(), h.lon());
            refresh();
        });
        buttons.add(atLaunch);
        buttons.add(Box.createHorizontalStrut(10));
        JButton export = Theme.secondaryButton("Export GPX");
        export.setToolTipText("All pins as GPX waypoints");
        export.addActionListener(e -> exportGpx());
        buttons.add(export);
        JButton clear = Theme.secondaryButton("Remove all");
        clear.addActionListener(e -> {
            if (pins.list().isEmpty()) return;
            if (JOptionPane.showConfirmDialog(this, "Remove all " + pins.list().size() + " pins?", "Pins", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            for (Pins.Pin p : pins.list()) pins.remove(p);
            refresh();
        });
        buttons.add(clear);
        root.add(buttons, BorderLayout.SOUTH);

        refresh();
        pack();
        setLocationRelativeTo(owner);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                refresh.start();
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                refresh.stop();
            }

            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                refresh.stop();
            }
        });
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    /** ADD AT GRID: a name and a position in any format the map writes; the pin goes there and the map centres on it. */
    private void addAtGrid() {
        javax.swing.JTextField name = new javax.swing.JTextField(pins.nextName(), 16);
        javax.swing.JTextField position = new javax.swing.JTextField(28);
        JLabel hint = Theme.label("MGRS (32V NM 97254 42617), UTM (32V 597254 6642617) or 59.9139, 10.7522", Theme.font(Font.PLAIN, 12f), Theme.MUTED);
        JPanel box = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        box.setOpaque(false);
        box.add(Theme.label("Name", Theme.font(Font.BOLD, 12f), Theme.TEXT));
        box.add(name);
        box.add(Theme.label("Position (grid)", Theme.font(Font.BOLD, 12f), Theme.TEXT));
        box.add(position);
        box.add(hint);
        position.addAncestorListener(new javax.swing.event.AncestorListener() { // the cursor in the position, ready to paste
            @Override
            public void ancestorAdded(javax.swing.event.AncestorEvent e) {
                position.requestFocusInWindow();
            }

            @Override
            public void ancestorRemoved(javax.swing.event.AncestorEvent e) {
            }

            @Override
            public void ancestorMoved(javax.swing.event.AncestorEvent e) {
            }
        });
        while (true) {
            if (JOptionPane.showConfirmDialog(this, box, "Add pin at grid", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            double[] ll = Coordinates.parse(position.getText());
            if (ll == null) {
                hint.setText("Unreadable position. MGRS, UTM or lat, lon.");
                hint.setForeground(Theme.BAD);
                continue;
            }
            pins.add(name.getText().isBlank() ? null : name.getText().trim(), ll[0], ll[1]);
            map.centreOn(ll[0], ll[1], Math.max(map.zoom(), 15));
            refresh();
            return;
        }
    }

    private void goTo() {
        Pins.Pin p = list.getSelectedValue();
        if (p != null) map.centreOn(p.lat(), p.lon(), Math.max(map.zoom(), 15));
    }

    /** EXPORT GPX: the pins as waypoints, to a file the pilot names (Documents\DRAUPNE Link\pins.gpx to begin with). */
    private void exportGpx() {
        if (pins.list().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No pins to export.", "Pins", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("Export pins as GPX");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("GPX files", "gpx"));
        try {
            chooser.setSelectedFile(Pins.defaultFile().resolveSibling("pins.gpx").toFile());
        } catch (java.io.IOException ignored) {
        }
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        java.nio.file.Path target = chooser.getSelectedFile().toPath();
        if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gpx")) target = target.resolveSibling(target.getFileName() + ".gpx");
        java.nio.file.Path file = target;
        new Thread(() -> { // the disk, off the Swing thread
            String problem = null;
            try {
                pins.exportGpx(file);
            } catch (java.io.IOException e) {
                problem = e.getMessage();
            }
            String why = problem;
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (why == null) JOptionPane.showMessageDialog(this, "Pins written to " + file.getFileName() + ".", "Pins", JOptionPane.INFORMATION_MESSAGE);
                else JOptionPane.showMessageDialog(this, "Write failed: " + why, "Pins", JOptionPane.WARNING_MESSAGE);
            });
        }, "pinsExport").start();
    }

    /** One row: the name, the position, and the way to it from the drone (D) and from the launch point (L). */
    private String line(Pins.Pin p) {
        FlightTrack t = track.get();
        FlightTrack.Point last = t == null ? null : t.last();
        FlightTrack.Point home = t == null ? null : t.home();
        String way = last == null ? "" : "   " + (home == null ? "" : "D ") + MapView.wayText(last.lat(), last.lon(), p);
        if (home != null) way += "   L " + MapView.wayText(home.lat(), home.lon(), p);
        return String.format(Locale.ROOT, "%-14s %s%s", p.name().toUpperCase(Locale.ROOT), Coordinates.format(format.get(), p.lat(), p.lon()), way);
    }

    private void refresh() {
        List<Pins.Pin> now = map.allPins(); // the tour's example pins too, while they are on the map
        Pins.Pin selected = list.getSelectedValue();
        if (!now.equals(shown)) {
            shown = now;
            model.clear();
            for (Pins.Pin p : now) model.addElement(p);
            if (selected != null) list.setSelectedValue(selected, true);
        } else {
            list.repaint(); // the distances move with the drone
        }
        none.setVisible(now.isEmpty());
    }
}
