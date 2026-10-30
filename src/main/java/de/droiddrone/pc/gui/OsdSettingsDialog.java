package de.droiddrone.pc.gui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.WindowConstants;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * SETTINGS > OSD, laid out like Betaflight Configurator's OSD tab: the elements with a switch each on the left, a
 * preview in the middle where they are dragged into place, the chosen element on the right. The preview is the real
 * OSD drawn from sample flight data. Every change goes to the live OSD at once and is saved.
 */
final class OsdSettingsDialog extends JFrame {
    private static final int SNAP_X = 320, SNAP_Y = 180; // positions snap to 4 x 4 units of the 1280 x 720 OSD

    private final MainWindow main;
    private OsdLayout layout;
    private OsdElement selected;
    private OsdElement hovered;
    private final Preview preview = new Preview();
    private final List<Row> rows = new ArrayList<>();
    private final JLabel countLabel = Theme.label(" ", Theme.headingFont(11f), Theme.MUTED);
    private final JLabel infoTitle = Theme.label(" ", Theme.headingFont(20f), Theme.TEXT);
    private final JLabel infoHelp = Theme.label(" ", Theme.font(Font.PLAIN, 13f), Theme.TEXT);
    private final JLabel infoState = Theme.label(" ", Theme.font(Font.BOLD, 12.5f), Theme.MUTED);
    private final JButton resetElement = Theme.secondaryButton("Reset element");
    private final Timer ticker = new Timer(500, e -> preview.repaint()); // the sample's timers run
    /** The one open, if any: opening it again brings that one forward. */
    private static OsdSettingsDialog open;

    private OsdSettingsDialog(Window owner, MainWindow main) {
        super("OSD - DroidDrone PC"); // a window of its own: Windows' minimise, maximise and close
        setIconImages(AppIcon.images());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        this.main = main;
        this.layout = main.osdLayout();
        JPanel root = new JPanel(new BorderLayout(14, 14));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        setContentPane(root);

        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        head.add(Theme.label("OSD", Theme.headingFont(26f), Theme.TEXT), BorderLayout.NORTH);
        head.add(Theme.label("Switch elements on or off on the left. Drag them in the preview to move them. Changes show on the live OSD at once and are saved.",
                Theme.font(Font.PLAIN, 13f), Theme.MUTED), BorderLayout.SOUTH);
        root.add(head, BorderLayout.NORTH);

        root.add(elementsCard(), BorderLayout.WEST);
        JPanel previewCard = Theme.card(new BorderLayout(0, 10));
        JPanel previewHead = new JPanel(new BorderLayout());
        previewHead.setOpaque(false);
        previewHead.add(Theme.sectionTitle("Preview"), BorderLayout.WEST);
        previewHead.add(Theme.label("SAMPLE FLIGHT DATA", Theme.headingFont(11f), Theme.MUTED), BorderLayout.EAST);
        previewCard.add(previewHead, BorderLayout.NORTH);
        previewCard.add(preview, BorderLayout.CENTER);
        previewCard.add(Theme.label("<html>Drag to move. An element lines up by the third of the picture it is in: "
                + "left-aligned, centred or right-aligned, so it grows away from the edge.</html>", Theme.font(Font.PLAIN, 12.5f), Theme.MUTED), BorderLayout.SOUTH);
        root.add(previewCard, BorderLayout.CENTER);
        root.add(infoCard(), BorderLayout.EAST);

        JButton close = Theme.primaryButton("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(close);
        root.add(buttons, BorderLayout.SOUTH);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().setDefaultButton(close);

        select(OsdElement.BATTERY);
        ticker.start();
        setMinimumSize(new Dimension(1100, 680));
        setSize(1300, 780);
        setLocationRelativeTo(owner); // where "restore down" puts it
        setExtendedState(MAXIMIZED_BOTH); // opens maximised: the biggest preview
        ScanReveal.onOpen(this);
    }

    static void open(Window owner, MainWindow main) {
        if (open != null && open.isDisplayable()) {
            if ((open.getExtendedState() & ICONIFIED) != 0) open.setExtendedState(open.getExtendedState() & ~ICONIFIED);
            open.toFront();
            return;
        }
        open = new OsdSettingsDialog(owner, main);
        open.setVisible(true);
    }

    @Override
    public void dispose() {
        ticker.stop();
        if (open == this) open = null;
        super.dispose();
    }

    // ------------------------------------------------------------------ changes

    private void apply(OsdLayout next) {
        if (next.equals(layout)) return;
        layout = next;
        main.setOsdLayout(next);
        refresh();
    }

    private void select(OsdElement e) {
        selected = e;
        refresh();
    }

    private void refresh() {
        long on = java.util.Arrays.stream(OsdElement.values()).filter(layout::isOn).count();
        countLabel.setText(on + " / " + OsdElement.values().length + " ON");
        OsdElement e = selected;
        OsdLayout.Placement p = layout.get(e);
        infoTitle.setText(e.title.toUpperCase());
        infoHelp.setText("<html>" + e.help + "</html>");
        String where = !e.movable ? "Fixed in the middle"
                : switch (p.align()) {
                    case LEFT -> "Left-aligned";
                    case CENTER -> "Centred";
                    case RIGHT -> "Right-aligned";
                } + String.format(java.util.Locale.ROOT, ", %.0f%% across, %.0f%% down", p.x() * 100, p.y() * 100);
        infoState.setText("<html>" + (p.on() ? "<span style='color:#4fd08a'>ON</span>" : "OFF")
                + (e.defaultOn ? "" : " &nbsp;(off by default)") + "<br>" + where + "</html>");
        resetElement.setEnabled(!p.equals(OsdLayout.defaultPlacement(e)));
        for (Row r : rows) r.repaint();
        preview.repaint();
    }

    // ------------------------------------------------------------------ left: elements

    private JComponent elementsCard() {
        JPanel card = Theme.card(new BorderLayout(0, 10));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(Theme.sectionTitle("Elements"), BorderLayout.WEST);
        head.add(countLabel, BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);
        JPanel list = new JPanel();
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        OsdElement[] sorted = OsdElement.values().clone();
        java.util.Arrays.sort(sorted, Comparator.comparing(e -> e.title));
        for (OsdElement e : sorted) {
            Row row = new Row(e);
            rows.add(row);
            list.add(row);
        }
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        card.add(scroll, BorderLayout.CENTER);
        card.setPreferredSize(new Dimension(290, 10));
        return card;
    }

    /** One element: Betaflight-style switch, its name, and FIXED / ALWAYS ON when it can't move or be hidden. */
    private final class Row extends JComponent {
        private static final int H = 30, SW = 34, SH = 16;
        private final OsdElement element;

        Row(OsdElement element) {
            this.element = element;
            setPreferredSize(new Dimension(250, H));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, H));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(element.help);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent ev) {
                    if (ev.getX() < 8 + SW + 8 && element.hideable) { // the switch
                        OsdLayout.Placement p = layout.get(element);
                        apply(layout.with(element, new OsdLayout.Placement(!p.on(), p.x(), p.y())));
                    }
                    select(element);
                }

                @Override
                public void mouseEntered(MouseEvent ev) {
                    hovered = element;
                    repaint();
                    preview.repaint();
                }

                @Override
                public void mouseExited(MouseEvent ev) {
                    if (hovered == element) hovered = null;
                    repaint();
                    preview.repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int w = getWidth();
                boolean on = layout.isOn(element);
                if (selected == element) {
                    g.setColor(Theme.PANEL_2);
                    g.fillRect(0, 0, w, H);
                    g.setColor(Theme.ACCENT);
                    g.fillRect(0, 0, 3, H);
                } else if (hovered == element) {
                    g.setColor(Theme.alpha(Theme.PANEL_2, 160));
                    g.fillRect(0, 0, w, H);
                }
                // switch
                int sx = 10, sy = (H - SH) / 2;
                Color track = !element.hideable ? Theme.alpha(Theme.ACCENT, 110) : on ? Theme.ACCENT : Theme.LINE;
                g.setColor(track);
                g.fillRoundRect(sx, sy, SW, SH, SH, SH);
                int knob = SH - 4;
                g.setColor(on ? Color.WHITE : Theme.MUTED);
                g.fillOval(on ? sx + SW - knob - 2 : sx + 2, sy + 2, knob, knob);
                // name
                g.setFont(Theme.font(Font.BOLD, 13f));
                g.setColor(on ? Theme.TEXT : Theme.MUTED);
                int base = H / 2 + g.getFontMetrics().getAscent() / 2 - 2;
                g.drawString(element.title, sx + SW + 10, base);
                String tag = !element.hideable ? "ALWAYS ON" : !element.movable ? "FIXED" : null;
                if (tag != null) {
                    g.setFont(Theme.headingFont(10.5f));
                    g.setColor(Theme.MUTED);
                    g.drawString(tag, w - 10 - g.getFontMetrics().stringWidth(tag), base);
                }
            } finally {
                g.dispose();
            }
        }
    }

    // ------------------------------------------------------------------ right: the chosen element

    private JComponent infoCard() {
        JPanel column = new JPanel(new BorderLayout(0, 14));
        column.setOpaque(false);
        column.setPreferredSize(new Dimension(270, 10));

        JPanel card = Theme.card(null);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.add(left(Theme.sectionTitle("Element")));
        card.add(left(gap(10)));
        card.add(left(infoTitle));
        card.add(left(gap(6)));
        card.add(left(infoHelp));
        card.add(left(gap(10)));
        card.add(left(infoState));
        card.add(left(gap(14)));
        resetElement.setToolTipText("Put this element back where it was, on or off as it was");
        resetElement.addActionListener(e -> apply(layout.with(selected, OsdLayout.defaultPlacement(selected))));
        card.add(left(resetElement));
        column.add(card, BorderLayout.NORTH);

        JPanel all = Theme.card(null);
        all.setLayout(new BoxLayout(all, BoxLayout.Y_AXIS));
        all.add(left(Theme.sectionTitle("Layout")));
        all.add(left(gap(8)));
        all.add(left(Theme.label("<html>Back to the standard OSD: every element in its place, the extras off.</html>",
                Theme.font(Font.PLAIN, 12.5f), Theme.MUTED)));
        all.add(left(gap(10)));
        JButton resetAll = Theme.secondaryButton("Reset all");
        resetAll.addActionListener(e -> {
            if (layout.equals(OsdLayout.defaults())) return;
            int answer = JOptionPane.showConfirmDialog(this, "Put every OSD element back to the standard layout?", "Reset OSD",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.OK_OPTION) apply(OsdLayout.defaults());
        });
        all.add(left(resetAll));
        column.add(all, BorderLayout.SOUTH);
        return column;
    }

    private static JComponent gap(int h) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(1, h));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        return p;
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        if (c instanceof JLabel) c.setMaximumSize(new Dimension(240, Integer.MAX_VALUE));
        return c;
    }

    // ------------------------------------------------------------------ middle: preview

    /** The OSD over a stand-in camera picture; elements are dragged here. */
    private final class Preview extends JComponent {
        private final OsdRenderer renderer = new OsdRenderer();
        private final OsdPreview sample = new OsdPreview();
        private final Map<OsdElement, Rectangle2D> bounds = new EnumMap<>(OsdElement.class);
        private Rectangle picture = new Rectangle();
        private OsdElement dragging;
        private Point pressAt;
        private Rectangle2D pressBox;
        private OsdLayout.Placement pressPlacement;
        private OsdLayout dragLayout; // the layout while dragging; applied (and saved) on release

        Preview() {
            setPreferredSize(new Dimension(640, 360));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    OsdElement hit = hit(e.getPoint());
                    if (hit == null) return;
                    select(hit);
                    if (!hit.movable) return;
                    dragging = hit;
                    pressAt = e.getPoint();
                    pressBox = bounds.get(hit);
                    pressPlacement = layout.get(hit);
                    dragLayout = layout;
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragging == null) return;
                    dragLayout = layout.with(dragging, dragged(e.getPoint()));
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (dragging == null) return;
                    OsdLayout next = dragLayout;
                    dragging = null;
                    dragLayout = null;
                    apply(next);
                    repaint();
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    OsdElement hit = hit(e.getPoint());
                    setCursor(Cursor.getPredefinedCursor(hit != null && hit.movable ? Cursor.MOVE_CURSOR : Cursor.DEFAULT_CURSOR));
                    if (hit != hovered) {
                        hovered = hit;
                        for (Row r : rows) r.repaint();
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (dragging == null && hovered != null) {
                        hovered = null;
                        for (Row r : rows) r.repaint();
                        repaint();
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        /**
         * Where the element goes for the mouse at p: moved with the mouse, kept inside the picture, snapped to the grid.
         * While it stays in its third, its anchor simply moves; into another third, it takes that third's alignment.
         */
        private OsdLayout.Placement dragged(Point p) {
            double pw = picture.width, ph = picture.height;
            double dx = p.x - pressAt.x, dy = p.y - pressAt.y;
            dx = Math.max(-pressBox.getMinX(), Math.min(pw - pressBox.getMaxX(), dx));
            dy = Math.max(-pressBox.getMinY(), Math.min(ph - pressBox.getMaxY(), dy));
            double centre = (pressBox.getCenterX() + dx) / pw;
            OsdLayout.Align align = centre < 1 / 3.0 ? OsdLayout.Align.LEFT : centre > 2 / 3.0 ? OsdLayout.Align.RIGHT : OsdLayout.Align.CENTER;
            double x = align == pressPlacement.align() ? pressPlacement.x() + dx / pw : switch (align) {
                case LEFT -> (pressBox.getMinX() + dx + 1.5) / pw;
                case CENTER -> centre;
                case RIGHT -> (pressBox.getMaxX() + dx - 1.5) / pw;
            };
            double y = pressPlacement.y() + dy / ph;
            x = Math.round(x * SNAP_X) / (double) SNAP_X;
            y = Math.round(y * SNAP_Y) / (double) SNAP_Y;
            // snapping must not move it into another third
            if (align == OsdLayout.Align.LEFT) x = Math.min(x, 1 / 3.0 - 1e-6);
            if (align == OsdLayout.Align.RIGHT) x = Math.max(x, 2 / 3.0 + 1e-6);
            if (align == OsdLayout.Align.CENTER) x = Math.max(1 / 3.0 + 1e-6, Math.min(2 / 3.0 - 1e-6, x));
            return new OsdLayout.Placement(pressPlacement.on(), x, y);
        }

        /** The smallest movable or fixed element under p (so a small one on top of a big one can be picked). */
        private OsdElement hit(Point p) {
            double px = p.x - picture.x, py = p.y - picture.y;
            OsdElement best = null;
            double bestArea = Double.MAX_VALUE;
            for (Map.Entry<OsdElement, Rectangle2D> e : bounds.entrySet()) {
                Rectangle2D r = e.getValue();
                Rectangle2D grown = new Rectangle2D.Double(r.getX() - 3, r.getY() - 3, r.getWidth() + 6, r.getHeight() + 6);
                double area = r.getWidth() * r.getHeight();
                if (grown.contains(px, py) && area < bestArea) {
                    best = e.getKey();
                    bestArea = area;
                }
            }
            return best;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int w = getWidth(), h = getHeight();
                int pw = Math.min(w, h * 16 / 9), ph = pw * 9 / 16;
                picture = new Rectangle((w - pw) / 2, (h - ph) / 2, pw, ph);
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, w, h);
                Graphics2D pg = (Graphics2D) g.create(picture.x, picture.y, pw, ph);
                OsdRenderer.Data data = sample.data();
                cameraPicture(pg, pw, ph, data);
                OsdLayout shown = dragLayout != null ? dragLayout : layout;
                if (dragging != null) guides(pg, pw, ph);
                bounds.clear();
                renderer.paint(pg, 0, 0, pw, ph, data, shown, bounds);
                fixedBounds(shown, pw, ph);
                highlight(pg, hovered, false);
                if (selected != hovered) highlight(pg, selected, true);
                pg.dispose();
            } finally {
                g.dispose();
            }
        }

        /** The crosshair and horizon aren't placed elements; give them a box so they can be picked. */
        private void fixedBounds(OsdLayout shown, int pw, int ph) {
            float u = ph / 720f;
            if (shown.isOn(OsdElement.CROSSHAIR)) bounds.put(OsdElement.CROSSHAIR, new Rectangle2D.Float(pw / 2f - 48 * u, ph / 2f - 8 * u, 96 * u, 22 * u));
        }

        /** A plain stand-in for the camera: sky and ground, tilted like the sample's attitude. */
        private void cameraPicture(Graphics2D g, int pw, int ph, OsdRenderer.Data data) {
            g.setPaint(new GradientPaint(0, 0, new Color(0x50606e), 0, ph, new Color(0x8d9aa3)));
            g.fillRect(0, 0, pw, ph);
            var att = data.telemetry().getAttitude();
            AffineTransform saved = g.getTransform();
            if (att != null) {
                g.rotate(Math.toRadians(-att.rollDeg()), pw / 2.0, ph / 2.0);
                g.translate(0, -att.pitchDeg() * ph / 60.0);
            }
            g.setPaint(new GradientPaint(0, ph / 2f, new Color(0x4a5236), 0, ph * 1.4f, new Color(0x23281a)));
            g.fillRect(-pw, ph / 2, pw * 3, ph * 2);
            g.setTransform(saved);
        }

        /** While dragging: a faint grid and the thirds that decide the alignment. */
        private void guides(Graphics2D g, int pw, int ph) {
            g.setColor(new Color(255, 255, 255, 22));
            g.setStroke(new BasicStroke(1f));
            for (int i = 1; i < 32; i++) g.drawLine(pw * i / 32, 0, pw * i / 32, ph);
            for (int i = 1; i < 18; i++) g.drawLine(0, ph * i / 18, pw, ph * i / 18);
            g.setColor(Theme.alpha(Theme.ACCENT, 120));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6, 6}, 0));
            g.drawLine(pw / 3, 0, pw / 3, ph);
            g.drawLine(pw * 2 / 3, 0, pw * 2 / 3, ph);
            g.setFont(Theme.headingFont(11f));
            g.setColor(Theme.alpha(Color.WHITE, 150));
            String[] zones = {"LEFT-ALIGNED", "CENTRED", "RIGHT-ALIGNED"};
            for (int i = 0; i < 3; i++) {
                int zx = pw * i / 3 + pw / 6 - g.getFontMetrics().stringWidth(zones[i]) / 2;
                g.drawString(zones[i], zx, ph - 8);
            }
        }

        /** Hovered: dashed frame and name tag; selected: a solid frame. */
        private void highlight(Graphics2D g, OsdElement e, boolean solid) {
            if (e == null) return;
            Rectangle2D r = bounds.get(e);
            if (r == null) return;
            Rectangle box = new Rectangle((int) Math.floor(r.getX()) - 3, (int) Math.floor(r.getY()) - 3,
                    (int) Math.ceil(r.getWidth()) + 6, (int) Math.ceil(r.getHeight()) + 6);
            g.setColor(Theme.ACCENT);
            g.setStroke(solid ? new BasicStroke(1.5f)
                    : new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4, 3}, 0));
            g.drawRect(box.x, box.y, box.width, box.height);
            if (solid) return;
            g.setFont(Theme.headingFont(11f));
            String tag = e.title.toUpperCase();
            int tw = g.getFontMetrics().stringWidth(tag) + 10, th = 16;
            int tx = Math.max(0, Math.min(picture.width - tw, box.x));
            int ty = box.y - th >= 0 ? box.y - th : box.y + box.height;
            g.fillRect(tx, ty, tw, th);
            g.setColor(Color.WHITE);
            g.drawString(tag, tx + 5, ty + 12);
        }
    }
}
