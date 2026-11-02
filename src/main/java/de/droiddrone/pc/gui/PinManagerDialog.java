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
 * GO TO (the map centres on it), RENAME, REMOVE, ADD AT DRONE, ADD AT LAUNCH and REMOVE ALL. Pins dropped by
 * right-click on a map show up here too (the list refreshes once a second).
 */
final class PinManagerDialog extends JDialog {
    private final Pins pins;
    private final MapView map;
    private final Supplier<Coordinates.Format> format;
    private final Supplier<FlightTrack> track;
    private final DefaultListModel<Pins.Pin> model = new DefaultListModel<>();
    private final JList<Pins.Pin> list = new JList<>(model);
    private final JLabel none = Theme.label("No pins yet. Right-click on the map, or ADD AT DRONE / ADD AT LAUNCH.", Theme.font(Font.PLAIN, 12.5f), Theme.MUTED);
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
        go.setToolTipText("Centre the map on the pin (also a double-click)");
        go.addActionListener(e -> goTo());
        buttons.add(go);
        JButton rename = Theme.secondaryButton("Rename");
        rename.addActionListener(e -> {
            Pins.Pin p = list.getSelectedValue();
            if (p == null) return;
            String name = (String) JOptionPane.showInputDialog(this, "New name", "Rename " + p.name(), JOptionPane.PLAIN_MESSAGE, null, null, p.name());
            if (name != null && !name.isBlank()) {
                pins.rename(p, name);
                refresh();
            }
        });
        buttons.add(rename);
        JButton remove = Theme.secondaryButton("Remove");
        remove.addActionListener(e -> {
            Pins.Pin p = list.getSelectedValue();
            if (p == null) return;
            pins.remove(p);
            refresh();
        });
        buttons.add(remove);
        buttons.add(Box.createHorizontalStrut(10));
        JButton atDrone = Theme.secondaryButton("Add at drone");
        atDrone.setToolTipText("A pin where the drone is (its last known position)");
        atDrone.addActionListener(e -> {
            FlightTrack t = track.get();
            FlightTrack.Point last = t == null ? null : t.last();
            if (last == null) return;
            pins.add(null, last.lat(), last.lon());
            refresh();
        });
        buttons.add(atDrone);
        JButton atLaunch = Theme.secondaryButton("Add at launch");
        atLaunch.setToolTipText("A pin where the drone armed");
        atLaunch.addActionListener(e -> {
            FlightTrack t = track.get();
            FlightTrack.Point h = t == null ? null : t.home();
            if (h == null) return;
            pins.add(null, h.lat(), h.lon());
            refresh();
        });
        buttons.add(atLaunch);
        buttons.add(Box.createHorizontalStrut(10));
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

    private void goTo() {
        Pins.Pin p = list.getSelectedValue();
        if (p != null) map.centreOn(p.lat(), p.lon(), Math.max(map.zoom(), 15));
    }

    /** One row: the name, the position, and the way to it from the drone. */
    private String line(Pins.Pin p) {
        FlightTrack t = track.get();
        FlightTrack.Point last = t == null ? null : t.last();
        String way = last == null ? "" : String.format(Locale.ROOT, "   %s  %03d°",
                MapView.distanceText(Mercator.distanceM(last.lat(), last.lon(), p.lat(), p.lon())), Mercator.bearingDeg(last.lat(), last.lon(), p.lat(), p.lon()));
        return String.format(Locale.ROOT, "%-14s %s%s", p.name().toUpperCase(Locale.ROOT), Coordinates.format(format.get(), p.lat(), p.lon()), way);
    }

    private void refresh() {
        List<Pins.Pin> now = pins.list();
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
