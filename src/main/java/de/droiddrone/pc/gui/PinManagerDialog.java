package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.Elevation;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Mercator;
import de.droiddrone.pc.map.Pins;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * PINS (the pilot's list of 2026-11-08): a sheet with a header - NAME, GRID, AMSL, BRG and DIST from the launch
 * point - one row per pin. A click on a row (or its go-to mark) centres the map on it; the copy mark (or Ctrl+C) puts
 * its grid on the clipboard. EDIT LIST (top right) brings out what changes the list: an X on the row under the mouse
 * to remove it, the name to click to rename it (underlined, a pencil), a "+" row last (a typed grid, or a click on the
 * map) and REMOVE ALL with its warning. Everything that comes or goes slides: the X column, the + row, REMOVE ALL, and a
 * pin's row as it is added or removed. Pins dropped by right-click on a map show up too (the list looks once a second).
 */
final class PinManagerDialog extends JDialog {
    static final int ROW = 28, HEADER = 26;
    private static final int ANIM_MS = 200;

    private final Pins pins;
    private final MapView map;
    private final Supplier<Coordinates.Format> format;
    private final Supplier<FlightTrack> track;
    private final Elevation elevation;
    final Sheet sheet = new Sheet();
    final EditToggle edit = new EditToggle();
    private final JPanel removeAllBar;
    private final Timer refresh = new Timer(1000, e -> refresh());
    private final JLabel none = Theme.label("No pins. Right-click the map.", Theme.font(Font.PLAIN, 12.5f), Theme.MUTED);

    PinManagerDialog(Window owner, Pins pins, MapView map, Supplier<Coordinates.Format> format, Supplier<FlightTrack> track, Elevation elevation) {
        super(owner, "Pins", ModalityType.MODELESS);
        this.pins = pins;
        this.map = map;
        this.format = format;
        this.track = track;
        this.elevation = elevation;
        if (owner != null) setIconImages(owner.getIconImages());
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        setContentPane(root);

        JPanel card = Theme.card(new BorderLayout(0, 8));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(Theme.sectionTitle("Pins"), BorderLayout.WEST);
        edit.setOnChange(on -> {
            sheet.setEditing(on); // REMOVE ALL slides with it (the sheet's animation)
        });
        top.add(edit, BorderLayout.EAST);
        card.add(top, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(sheet);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        scroll.getViewport().setBackground(Theme.PANEL);
        scroll.getVerticalScrollBar().setUnitIncrement(ROW);
        scroll.setPreferredSize(new Dimension(660, 300));
        card.add(scroll, BorderLayout.CENTER);
        card.add(none, BorderLayout.SOUTH);
        root.add(card, BorderLayout.CENTER);

        // REMOVE ALL: only in EDIT LIST, sliding up from under the card with its warning mark
        removeAllBar = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(10, Math.round(40 * eased(sheet.editShown)));
            }
        };
        removeAllBar.setOpaque(false);
        RemoveAllButton removeAll = new RemoveAllButton();
        removeAllBar.add(removeAll, BorderLayout.EAST);
        root.add(removeAllBar, BorderLayout.SOUTH);

        javax.swing.KeyStroke ctrlC = javax.swing.KeyStroke.getKeyStroke("ctrl C");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ctrlC, "copyPin");
        root.getActionMap().put("copyPin", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (sheet.selected != null) copy(sheet.selected);
            }
        });
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "endEdit");
        root.getActionMap().put("endEdit", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (sheet.renaming != null) sheet.endRename(false);
                else if (edit.on) edit.set(false);
            }
        });

        refresh();
        sheet.finishAnimations();
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
                sheet.anim.stop();
            }

            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                refresh.stop();
            }
        });
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    static float eased(float t) {
        return t * t * (3 - 2 * t);
    }

    // ------------------------------------------------------------------ actions

    void goTo(Pins.Pin p) {
        sheet.selected = p;
        map.centreOn(p.lat(), p.lon(), Math.max(map.zoom(), 15));
        sheet.repaint();
    }

    void copy(Pins.Pin p) {
        MapView.copyPosition(format.get(), p);
        sheet.copiedAt = System.currentTimeMillis();
        sheet.copied = p;
        sheet.anim.start();
    }

    void remove(Pins.Pin p) {
        if (map.isExamplePin(p)) map.changeExamplePin(p, null);
        else pins.remove(p);
        refresh();
    }

    void rename(Pins.Pin p, String name) {
        if (name == null || name.isBlank() || name.trim().equals(p.name())) return;
        if (map.isExamplePin(p)) map.changeExamplePin(p, name.trim());
        else pins.rename(p, name.trim());
        refresh();
    }

    /** "+": a typed grid, or a click on the map. */
    void addPin(java.awt.Component at, int x, int y) {
        javax.swing.JPopupMenu menu = TacticalCombo.menu();
        menu.setBackground(Theme.PANEL_2);
        javax.swing.JMenuItem typed = new javax.swing.JMenuItem("Enter grid");
        typed.addActionListener(e -> addAtGrid());
        menu.add(typed);
        javax.swing.JMenuItem click = new javax.swing.JMenuItem("Click on map");
        click.addActionListener(e -> {
            Window mapWindow = SwingUtilities.getWindowAncestor(map);
            if (mapWindow != null) mapWindow.toFront();
            map.pickPointNext(ll -> {
                if (ll == null) return;
                pins.add(null, ll[0], ll[1]);
                refresh();
                toFront();
            });
        });
        menu.add(click);
        TacticalCombo.show(menu, at, x, y);
    }

    /** "+ > Enter grid": a name and a position in any format the map writes; the pin goes there, the map with it. */
    private void addAtGrid() {
        JTextField name = new JTextField(pins.nextName(), 16);
        JTextField position = new JTextField(28);
        JLabel hint = Theme.label("MGRS, UTM or lat, lon", Theme.font(Font.PLAIN, 12f), Theme.MUTED);
        JPanel box = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        box.setOpaque(false);
        box.add(Theme.label("Name", Theme.font(Font.BOLD, 12f), Theme.TEXT));
        box.add(name);
        box.add(Theme.label("Grid", Theme.font(Font.BOLD, 12f), Theme.TEXT));
        box.add(position);
        box.add(hint);
        position.addAncestorListener(new javax.swing.event.AncestorListener() { // the cursor in the grid, ready to paste
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
            if (JOptionPane.showConfirmDialog(this, box, "Add pin", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            double[] ll = Coordinates.parse(position.getText());
            if (ll == null) {
                hint.setText("Unreadable grid. MGRS, UTM or lat, lon.");
                hint.setForeground(Theme.BAD);
                continue;
            }
            pins.add(name.getText().isBlank() ? null : name.getText().trim(), ll[0], ll[1]);
            map.centreOn(ll[0], ll[1], Math.max(map.zoom(), 15));
            refresh();
            return;
        }
    }

    private void removeAllPins() {
        List<Pins.Pin> all = map.allPins();
        if (all.isEmpty()) return;
        if (JOptionPane.showConfirmDialog(this, "Remove all " + all.size() + " pins? This can't be undone.", "Remove all",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        for (Pins.Pin p : all) {
            if (map.isExamplePin(p)) map.changeExamplePin(p, null);
            else pins.remove(p);
        }
        refresh();
    }

    void refresh() {
        List<Pins.Pin> now = map.allPins(); // the tour's example pins too, while they are on the map
        sheet.setPins(now);
        none.setVisible(now.isEmpty() && !edit.on);
    }

    // ------------------------------------------------------------------ the cells

    /** The launch point (where the drone armed), or null. */
    private FlightTrack.Point launch() {
        FlightTrack t = track.get();
        return t == null ? null : t.home();
    }

    String gridText(Pins.Pin p) {
        return Coordinates.format(format.get(), p.lat(), p.lon());
    }

    String brgText(Pins.Pin p) {
        FlightTrack.Point l = launch();
        return l == null ? "-" : String.format(Locale.ROOT, "%03d°", Mercator.bearingDeg(l.lat(), l.lon(), p.lat(), p.lon()));
    }

    String distText(Pins.Pin p) {
        FlightTrack.Point l = launch();
        return l == null ? "-" : MapView.distanceText(Mercator.distanceM(l.lat(), l.lon(), p.lat(), p.lon()));
    }

    private final Map<String, Double> heights = new HashMap<>();

    /** The terrain's height at the pin, metres above sea level: asked once, "..." until it comes, "-" without one. */
    String amslText(Pins.Pin p) {
        String key = String.format(Locale.ROOT, "%.6f,%.6f", p.lat(), p.lon());
        if (heights.containsKey(key)) {
            Double m = heights.get(key);
            return m == null || m.isNaN() ? "-" : Math.round(m) + " m";
        }
        if (elevation == null) return "-";
        heights.put(key, Double.NaN);
        elevation.lookup(p.lat(), p.lon(), z -> SwingUtilities.invokeLater(() -> {
            heights.put(key, z);
            sheet.repaint();
        }));
        return "...";
    }

    /** For tests: a height known at once. */
    void knowHeight(Pins.Pin p, double m) {
        heights.put(String.format(Locale.ROOT, "%.6f,%.6f", p.lat(), p.lon()), m);
    }

    // ------------------------------------------------------------------ the sheet

    /** One row as it comes and goes: its height share 0..1, and whether it is on its way out. */
    static final class RowState {
        final Pins.Pin pin;
        float shown;
        boolean leaving;

        RowState(Pins.Pin pin, float shown) {
            this.pin = pin;
            this.shown = shown;
        }
    }

    /** The list drawn as a sheet: header, rows, the + row; the X column and the + row slide with EDIT LIST. */
    final class Sheet extends JComponent {
        private static final int GO_W = 26, COPY_W = 26, X_W = 30;
        final List<RowState> rows = new ArrayList<>();
        Pins.Pin selected, copied;
        long copiedAt;
        private boolean editing;
        float editShown;
        private int hoverRow = -1;
        private String hoverPart;
        Pins.Pin renaming;
        private JTextField renameField;
        private long lastTick = System.currentTimeMillis();
        final Timer anim = new Timer(15, e -> tick());

        Sheet() {
            setLayout(null);
            setOpaque(true);
            setBackground(Theme.PANEL);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    hover(e.getX(), e.getY());
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hoverRow = -1;
                    hoverPart = null;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return;
                    click(e.getX(), e.getY(), e);
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        void setEditing(boolean on) {
            editing = on;
            if (!on && renaming != null) endRename(true);
            lastTick = System.currentTimeMillis();
            anim.start();
            none.setVisible(rows.isEmpty() && !on);
        }

        void setPins(List<Pins.Pin> now) {
            boolean changed = false;
            for (RowState r : rows) {
                if (!r.leaving && !now.contains(r.pin)) {
                    r.leaving = true;
                    changed = true;
                }
            }
            for (int i = 0; i < now.size(); i++) {
                Pins.Pin p = now.get(i);
                boolean known = false;
                for (RowState r : rows) {
                    if (r.pin.equals(p) && !r.leaving) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    rows.add(Math.min(rows.size(), indexFor(now, i)), new RowState(p, 0));
                    changed = true;
                }
            }
            if (selected != null && !now.contains(selected)) selected = null;
            if (changed) {
                lastTick = System.currentTimeMillis();
                anim.start();
            }
            revalidate();
            repaint(); // the distances move with the launch point, the heights come in
        }

        /** Where pin i of the new list goes among the rows (after the row of the pin before it). */
        private int indexFor(List<Pins.Pin> now, int i) {
            if (i == 0) return 0;
            Pins.Pin before = now.get(i - 1);
            for (int k = 0; k < rows.size(); k++) if (rows.get(k).pin.equals(before)) return k + 1;
            return rows.size();
        }

        void finishAnimations() {
            editShown = editing ? 1 : 0;
            rows.removeIf(r -> r.leaving);
            for (RowState r : rows) r.shown = 1;
            anim.stop();
            revalidate();
            repaint();
            removeAllBar.revalidate();
        }

        private void tick() {
            long now = System.currentTimeMillis();
            float step = Math.min(1f, (now - lastTick) / (float) ANIM_MS);
            lastTick = now;
            boolean busy = false;
            float target = editing ? 1 : 0;
            if (editShown != target) {
                editShown = target > editShown ? Math.min(target, editShown + step) : Math.max(target, editShown - step);
                busy = true;
                removeAllBar.revalidate();
                removeAllBar.repaint();
            }
            for (RowState r : new ArrayList<>(rows)) {
                if (r.leaving) {
                    r.shown = Math.max(0, r.shown - step);
                    if (r.shown == 0) rows.remove(r);
                    else busy = true;
                } else if (r.shown < 1) {
                    r.shown = Math.min(1, r.shown + step);
                    busy = true;
                }
            }
            if (copied != null && now - copiedAt < 1500) busy = true;
            else copied = null;
            revalidate();
            repaint();
            if (!busy) anim.stop();
        }

        @Override
        public Dimension getPreferredSize() {
            float h = HEADER;
            for (RowState r : rows) h += ROW * eased(r.shown);
            h += ROW * eased(editShown) + 4;
            return new Dimension(620, Math.round(h));
        }

        /** The columns' left edges for this width: name, grid, AMSL, BRG, DIST, go-to, copy, X; and the end. */
        int[] columns() {
            int w = getWidth() > 0 ? getWidth() : getPreferredSize().width;
            int x = Math.round(X_W * eased(editShown));
            int marks = GO_W + COPY_W + 8;
            int rest = w - marks - x - 10;
            int name = Math.max(110, Math.round(rest * 0.24f));
            int grid = Math.max(150, Math.round(rest * 0.36f));
            int amsl = Math.max(60, Math.round(rest * 0.13f));
            int brg = Math.max(48, Math.round(rest * 0.11f));
            int c0 = 10, c1 = c0 + name, c2 = c1 + grid, c3 = c2 + amsl, c4 = c3 + brg;
            int c5 = w - x - marks;
            return new int[]{c0, c1, c2, c3, c4, c5, c5 + GO_W, w - x, w};
        }

        /** The row under y and where it starts; -1 for the header, rows.size() for the + row, -2 below everything. */
        private int[] rowAt(int y) {
            if (y < HEADER) return new int[]{-1, 0};
            float top = HEADER;
            for (int i = 0; i < rows.size(); i++) {
                float h = ROW * eased(rows.get(i).shown);
                if (y < top + h) return new int[]{rows.get(i).leaving ? -2 : i, Math.round(top)};
                top += h;
            }
            if (editShown > 0 && y < top + ROW * eased(editShown)) return new int[]{rows.size(), Math.round(top)};
            return new int[]{-2, 0};
        }

        private String partAt(int x) {
            int[] c = columns();
            if (x >= c[7] && editing) return "x";
            if (x >= c[6] && x < c[7]) return "copy";
            if (x >= c[5] && x < c[6]) return "go";
            if (x >= c[0] && x < c[1]) return "name";
            return "row";
        }

        private void hover(int x, int y) {
            int r = rowAt(y)[0];
            String part = r >= 0 ? partAt(x) : r == -1 ? "header" : null;
            if (r != hoverRow || !java.util.Objects.equals(part, hoverPart)) {
                hoverRow = r;
                hoverPart = part;
                boolean hand = r == rows.size() || (r >= 0 && r < rows.size() && ("go".equals(part) || "copy".equals(part)
                        || "x".equals(part) || "row".equals(part) || "name".equals(part)));
                setCursor(hand ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                setToolTipText(r >= 0 && r < rows.size() ? switch (part) {
                    case "go" -> "Go to";
                    case "copy" -> "Copy grid (Ctrl+C)";
                    case "x" -> "Remove";
                    case "name" -> editing ? "Rename" : "Go to";
                    default -> "Go to";
                } : r == rows.size() ? "Add pin" : r == -1 ? "BRG, DIST: from launch" : null);
                repaint();
            }
        }

        void click(int x, int y, MouseEvent e) {
            int[] at = rowAt(y);
            int r = at[0];
            if (r == rows.size() && editing) {
                addPin(this, x, at[1] + ROW);
                return;
            }
            if (r < 0 || r >= rows.size()) return;
            Pins.Pin p = rows.get(r).pin;
            String part = partAt(x);
            switch (part) {
                case "x" -> PinManagerDialog.this.remove(p);
                case "copy" -> {
                    selected = p;
                    copy(p);
                }
                case "name" -> {
                    if (editing) startRename(r, at[1]);
                    else goTo(p);
                }
                default -> goTo(p);
            }
        }

        /** The name's cell becomes a field: Enter keeps the new name, Esc or a click elsewhere the old one. */
        void startRename(int r, int top) {
            if (renaming != null) endRename(true);
            renaming = rows.get(r).pin;
            int[] c = columns();
            renameField = new JTextField(renaming.name());
            renameField.setFont(Theme.font(Font.BOLD, 12.5f));
            renameField.setBounds(c[0] - 4, top + 2, c[1] - c[0] - 4, ROW - 4);
            renameField.addActionListener(e -> endRename(true));
            renameField.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    if (renaming != null) endRename(true);
                }
            });
            add(renameField);
            renameField.selectAll();
            renameField.requestFocusInWindow();
            repaint();
        }

        void endRename(boolean keep) {
            Pins.Pin p = renaming;
            JTextField f = renameField;
            renaming = null;
            renameField = null;
            if (f != null) {
                remove(f);
                repaint();
            }
            if (keep && p != null && f != null) rename(p, f.getText());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int w = getWidth();
                g.setColor(Theme.PANEL);
                g.fillRect(0, 0, w, getHeight());
                int[] c = columns();
                // the header, like a spreadsheet's
                g.setColor(Theme.PANEL_2);
                g.fillRect(0, 0, w, HEADER);
                g.setColor(Theme.LINE);
                g.drawLine(0, HEADER - 1, w, HEADER - 1);
                g.setFont(Theme.headingFont(10.5f));
                g.setColor(Theme.MUTED);
                String[] names = {"NAME", "GRID", "AMSL", "BRG", "DIST"};
                FontMetrics hf = g.getFontMetrics();
                for (int i = 0; i < names.length; i++) {
                    g.drawString(names[i], c[i], (HEADER + hf.getAscent()) / 2 - 1);
                    if (i > 0) g.drawLine(c[i] - 8, 5, c[i] - 8, HEADER - 6);
                }
                float top = HEADER;
                for (int i = 0; i < rows.size(); i++) {
                    RowState r = rows.get(i);
                    float share = eased(r.shown);
                    int h = Math.round(ROW * share);
                    if (h > 0) paintRow(g, r, i, Math.round(top), h, share, c, w);
                    top += ROW * share;
                }
                if (editShown > 0) paintPlusRow(g, Math.round(top), Math.round(ROW * eased(editShown)), eased(editShown), w);
            } finally {
                g.dispose();
            }
        }

        private void paintRow(Graphics2D g, RowState r, int i, int y, int h, float share, int[] c, int w) {
            Graphics2D rg = (Graphics2D) g.create();
            try {
                rg.clipRect(0, y, w, h);
                int base = y + h - ROW; // the row slides out from under the one above
                rg.setComposite(java.awt.AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, share))));
                boolean hover = i == hoverRow;
                boolean sel = r.pin.equals(selected);
                if (sel || hover) {
                    rg.setColor(sel ? new Color(0x221012) : new Color(0x121212));
                    rg.fillRect(0, base, w, ROW);
                }
                if (sel) {
                    rg.setColor(Theme.ACCENT);
                    rg.fillRect(0, base, 3, ROW);
                }
                rg.setColor(new Color(0x1d1d1a));
                rg.drawLine(0, base + ROW - 1, w, base + ROW - 1);
                int text = base + (ROW + rg.getFontMetrics(Theme.font(Font.BOLD, 12.5f)).getAscent()) / 2 - 2;
                // name: in EDIT LIST it reads as something to click (underline, a pencil)
                rg.setFont(Theme.font(Font.BOLD, 12.5f));
                rg.setColor(Theme.TEXT);
                String name = fit(rg, r.pin.name().toUpperCase(Locale.ROOT), c[1] - c[0] - 26);
                if (r.pin != renaming) {
                    rg.drawString(name, c[0], text);
                    if (editShown > 0) {
                        int nw = rg.getFontMetrics().stringWidth(name);
                        rg.setColor(withAlpha(Theme.WARN, eased(editShown) * (hover && "name".equals(hoverPart) ? 1f : 0.65f)));
                        rg.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{2f, 2f}, 0f));
                        rg.drawLine(c[0], text + 3, c[0] + nw, text + 3);
                        pencil(rg, c[0] + nw + 7, base + ROW / 2);
                    }
                }
                rg.setFont(Theme.monoFont(Font.BOLD, 12f));
                rg.setColor(Theme.TEXT);
                rg.drawString(fit(rg, gridText(r.pin), c[2] - c[1] - 10), c[1], text);
                rg.setColor(Theme.MUTED);
                rg.drawString(amslText(r.pin), c[2], text);
                rg.drawString(brgText(r.pin), c[3], text);
                rg.drawString(distText(r.pin), c[4], text);
                // go to, copy
                boolean goHover = hover && "go".equals(hoverPart), copyHover = hover && "copy".equals(hoverPart);
                goMark(rg, c[5] + Sheet.GO_W / 2, base + ROW / 2, goHover ? Color.WHITE : Theme.TEXT); // not red (1.0.52, the pilot)
                boolean justCopied = r.pin.equals(copied);
                copyMark(rg, c[6] + Sheet.COPY_W / 2, base + ROW / 2, justCopied ? Theme.OK : copyHover ? Color.WHITE : Theme.MUTED, justCopied);
                // X: the column slides in with EDIT LIST; the mark shows on the row under the mouse
                if (editShown > 0 && hover) {
                    float a = eased(editShown);
                    int cx = c[7] + Math.round(Sheet.X_W * a) / 2, cy = base + ROW / 2;
                    boolean on = "x".equals(hoverPart);
                    rg.setColor(withAlpha(on ? Theme.ACCENT : Theme.BAD, a));
                    if (on) rg.fillOval(cx - 9, cy - 9, 18, 18);
                    rg.setColor(withAlpha(on ? Color.WHITE : Theme.BAD, a));
                    rg.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    rg.drawLine(cx - 4, cy - 4, cx + 4, cy + 4);
                    rg.drawLine(cx + 4, cy - 4, cx - 4, cy + 4);
                }
            } finally {
                rg.dispose();
            }
        }

        private void paintPlusRow(Graphics2D g, int y, int h, float a, int w) {
            Graphics2D rg = (Graphics2D) g.create();
            try {
                rg.clipRect(0, y, w, h);
                int base = y + h - ROW;
                rg.setComposite(java.awt.AlphaComposite.SrcOver.derive(a));
                boolean hover = hoverRow == rows.size();
                rg.setColor(hover ? new Color(0x121212) : Theme.PANEL);
                rg.fillRect(0, base, w, ROW);
                int cx = 22, cy = base + ROW / 2;
                rg.setColor(hover ? Color.WHITE : Theme.ACCENT);
                rg.setStroke(new BasicStroke(1.6f));
                rg.drawOval(cx - 9, cy - 9, 18, 18);
                rg.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                rg.drawLine(cx - 4, cy, cx + 4, cy);
                rg.drawLine(cx, cy - 4, cx, cy + 4);
                rg.setFont(Theme.headingFont(11f));
                rg.drawString("ADD PIN", cx + 16, cy + rg.getFontMetrics().getAscent() / 2 - 1);
            } finally {
                rg.dispose();
            }
        }
    }

    private static String fit(Graphics2D g, String s, int width) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= width) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > width) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    static Color withAlpha(Color c, float a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(255 * Math.max(0, Math.min(1, a))));
    }

    /** Go to: a crosshair in a ring. */
    private static void goMark(Graphics2D g, int cx, int cy, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(1.6f));
        g.drawOval(cx - 6, cy - 6, 12, 12);
        g.drawLine(cx, cy - 9, cx, cy - 4);
        g.drawLine(cx, cy + 4, cx, cy + 9);
        g.drawLine(cx - 9, cy, cx - 4, cy);
        g.drawLine(cx + 4, cy, cx + 9, cy);
        g.fillOval(cx - 1, cy - 1, 3, 3);
    }

    /** Copy: two sheets; a tick when it has just been copied. */
    private static void copyMark(Graphics2D g, int cx, int cy, Color color, boolean done) {
        g.setColor(color);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (done) {
            g.drawLine(cx - 5, cy, cx - 1, cy + 4);
            g.drawLine(cx - 1, cy + 4, cx + 6, cy - 4);
            return;
        }
        g.drawRect(cx - 5, cy - 3, 8, 9);
        g.drawLine(cx - 2, cy - 6, cx + 6, cy - 6);
        g.drawLine(cx + 6, cy - 6, cx + 6, cy + 3);
    }

    private static void pencil(Graphics2D g, int x, int cy) {
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D p = new Path2D.Double();
        p.moveTo(x, cy + 5);
        p.lineTo(x + 1.5, cy + 1);
        p.lineTo(x + 7, cy - 4.5);
        p.lineTo(x + 9, cy - 2.5);
        p.lineTo(x + 3.5, cy + 3);
        p.closePath();
        g.draw(p);
    }

    /** EDIT LIST: a box to tick, top right. */
    final class EditToggle extends JComponent {
        boolean on;
        private java.util.function.Consumer<Boolean> onChange;

        EditToggle() {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Add, rename, remove pins");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    set(!on);
                }
            });
        }

        void setOnChange(java.util.function.Consumer<Boolean> c) {
            onChange = c;
        }

        void set(boolean value) {
            if (on == value) return;
            on = value;
            repaint();
            if (onChange != null) onChange.accept(value);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(112, 24);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int bx = 2, by = (getHeight() - 14) / 2;
                g.setColor(on ? Theme.ACCENT : Theme.BG);
                g.fillRect(bx, by, 14, 14);
                g.setColor(on ? Theme.ACCENT : Theme.MUTED);
                g.setStroke(new BasicStroke(1.4f));
                g.drawRect(bx, by, 14, 14);
                if (on) {
                    g.setColor(Color.BLACK);
                    g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawLine(bx + 3, by + 7, bx + 6, by + 10);
                    g.drawLine(bx + 6, by + 10, bx + 11, by + 4);
                }
                g.setFont(Theme.headingFont(11f));
                g.setColor(on ? Theme.TEXT : Theme.MUTED);
                g.drawString("EDIT LIST", bx + 22, getHeight() / 2 + g.getFontMetrics().getAscent() / 2 - 1);
            } finally {
                g.dispose();
            }
        }
    }

    /** REMOVE ALL: a warning triangle and the words, red; asks first. */
    private final class RemoveAllButton extends JComponent {
        private boolean hover;

        RemoveAllButton() {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Remove every pin");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (sheet.editShown > 0.5f) removeAllPins();
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(150, 32);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                float a = eased(sheet.editShown);
                g.setComposite(java.awt.AlphaComposite.SrcOver.derive(a));
                int h = Math.min(32, getHeight()), y = getHeight() - h;
                Rectangle box = new Rectangle(0, y + 4, getWidth() - 1, h - 5);
                g.setColor(hover ? Theme.ACCENT : Theme.BG);
                g.fill(box);
                g.setColor(Theme.ACCENT);
                g.draw(box);
                int tx = 14, ty = box.y + box.height / 2;
                Path2D tri = new Path2D.Double();
                tri.moveTo(tx, ty - 7);
                tri.lineTo(tx + 8, ty + 6);
                tri.lineTo(tx - 8, ty + 6);
                tri.closePath();
                g.setColor(hover ? Color.BLACK : Theme.WARN);
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(tri);
                g.drawLine(tx, ty - 2, tx, ty + 1);
                g.fillOval(tx - 1, ty + 3, 2, 2);
                g.setFont(Theme.headingFont(11f));
                g.setColor(hover ? Color.BLACK : Theme.ACCENT);
                g.drawString("REMOVE ALL", tx + 16, ty + g.getFontMetrics().getAscent() / 2 - 1);
            } finally {
                g.dispose();
            }
        }
    }
}
