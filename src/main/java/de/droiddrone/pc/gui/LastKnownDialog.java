package de.droiddrone.pc.gui;

import de.droiddrone.common.CellVolts;
import de.droiddrone.pc.LastKnown;
import de.droiddrone.pc.map.Coordinates;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * MAP TOOLS > DATA > LAST KNOWN (1.0.71; the pilot, 2026-11-18: "always store a last known grid for a phone so that
 * if a drone is lost and runs out of battery it is still possible to find it"): every phone this PC knows something
 * of, the one heard of last first - its last grid and when, its batteries as last read, the mode it was last
 * confirmed in. COPY puts the chosen row's name, grid and time on the clipboard; SHOW ON MAP goes there.
 * What it shows is {@link LastKnown}'s (on disk); the list follows it once a second.
 */
final class LastKnownDialog extends JDialog {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static final String[] COLUMNS = {"PHONE", "GRID", "AT", "AGO", "PHONE BATT", "DRONE", "LAST MODE"};

    private final LastKnown store;
    private final Supplier<Coordinates.Format> format;
    private final Predicate<String> outOfAction;
    private List<LastKnown.Entry> rows = List.of();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel none = Theme.label("No phone heard yet.", Theme.font(Font.PLAIN, 12.5f), Theme.MUTED);
    private final Timer follow = new Timer(1000, e -> refresh());

    /**
     * @param format      how grids are written (the map's choice: MGRS unless changed)
     * @param outOfAction whether a phone is marked OUT OF ACTION (said in its LAST MODE)
     * @param onShow      SHOW ON MAP: lat, lon
     */
    LastKnownDialog(Window owner, LastKnown store, Supplier<Coordinates.Format> format, Predicate<String> outOfAction,
                    BiConsumer<Double, Double> onShow) {
        super(owner, "Last known", ModalityType.MODELESS);
        this.store = store;
        this.format = format;
        this.outOfAction = outOfAction;
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        root.add(Theme.sectionTitle("Last known"), BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        table.setFont(Theme.monoFont(Font.PLAIN, 12f));
        table.getTableHeader().setFont(Theme.headingFont(10.5f));
        table.getTableHeader().setReorderingAllowed(false);
        int[] widths = {110, 190, 130, 60, 90, 60, 150};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        scroll.setPreferredSize(new Dimension(820, 300));
        root.add(scroll, BorderLayout.CENTER);
        JPanel foot = new JPanel(new BorderLayout());
        foot.setOpaque(false);
        foot.add(none, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton copy = Theme.secondaryButton("Copy");
        copy.setToolTipText("Name, grid and time to the clipboard");
        copy.addActionListener(e -> {
            String text = copyText(selected(), format.get());
            if (text != null) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        });
        JButton show = Theme.secondaryButton("Show on map");
        show.addActionListener(e -> {
            LastKnown.Entry k = selected();
            if (k != null && k.hasPlace() && onShow != null) onShow.accept(k.lat(), k.lon());
        });
        JButton close = Theme.secondaryButton("Close");
        close.addActionListener(e -> dispose());
        buttons.add(copy);
        buttons.add(show);
        buttons.add(close);
        foot.add(buttons, BorderLayout.EAST);
        root.add(foot, BorderLayout.SOUTH);
        setContentPane(root);
        refresh();
        pack();
        setLocationRelativeTo(owner);
        follow.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                follow.stop();
            }
        });
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private LastKnown.Entry selected() {
        int row = table.getSelectedRow();
        return row < 0 || row >= rows.size() ? null : rows.get(row);
    }

    private void refresh() {
        LastKnown.Entry keep = selected();
        rows = store == null ? List.of() : store.all();
        model.fireTableDataChanged();
        if (keep != null) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).name().equalsIgnoreCase(keep.name())) table.setRowSelectionInterval(i, i);
            }
        }
        none.setVisible(rows.isEmpty());
    }

    /** "MobileAlpha 32V NM 97254 42617 (2026-11-18 14:05)": what COPY puts on the clipboard; null without a place. */
    static String copyText(LastKnown.Entry k, Coordinates.Format format) {
        if (k == null || !k.hasPlace()) return null;
        return k.name() + " " + Coordinates.format(format == null ? Coordinates.Format.MGRS : format, k.lat(), k.lon()) + " (" + when(k.placeAt()) + ")";
    }

    private static String when(long ms) {
        return ms <= 0 ? "-" : LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(WHEN);
    }

    /** One cell's text. */
    static String cell(LastKnown.Entry k, int column, Coordinates.Format format, boolean outOfAction, long now) {
        return switch (column) {
            case 0 -> k.name();
            case 1 -> k.hasPlace() ? Coordinates.format(format == null ? Coordinates.Format.MGRS : format, k.lat(), k.lon()) : "-";
            case 2 -> k.hasPlace() ? when(k.placeAt()) : "-";
            case 3 -> k.hasPlace() ? LastKnown.age(now - k.placeAt()) : "-";
            case 4 -> k.phonePercent() < 0 ? "-" : k.phonePercent() + " %";
            case 5 -> Double.isNaN(k.cellVolts()) ? "-" : CellVolts.percent(k.cellVolts()) + " %";
            default -> outOfAction ? "OUT OF ACTION" : k.mode().isEmpty() ? "-" : k.mode();
        };
    }

    private final class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            LastKnown.Entry k = rows.get(row);
            return cell(k, column, format.get(), outOfAction != null && outOfAction.test(k.name()), System.currentTimeMillis());
        }
    }
}
