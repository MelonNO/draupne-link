package de.droiddrone.pc.gui;

import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.ListCellRenderer;
import javax.swing.Timer;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Graphics;

/**
 * A dropdown's options appear one after another when it opens, each uncovered by a red scan bar, like a readout
 * coming in. Call {@link #install} after setting the dropdown's renderer. {@link #menu} makes a popup menu (SETUP's)
 * whose items come in the same way.
 */
final class TacticalCombo {
    private static final long STAGGER_MS = 45;
    private static final long REVEAL_MS = 170;

    private TacticalCombo() {
    }

    @SuppressWarnings("unchecked")
    static <T> void install(JComboBox<T> combo) {
        long[] openedAt = {0};
        ListCellRenderer<? super T> base = combo.getRenderer();
        Reveal cell = new Reveal();
        combo.setRenderer((list, value, index, selected, focus) -> {
            Component c = base.getListCellRendererComponent(list, value, index, selected, focus);
            if (index < 0 || openedAt[0] == 0) return c; // the closed dropdown itself
            float p = (System.currentTimeMillis() - openedAt[0] - index * STAGGER_MS) / (float) REVEAL_MS;
            if (p >= 1) return c;
            cell.show(c, Math.max(0, p), list.getBackground());
            return cell;
        });
        Timer timer = new Timer(15, null);
        timer.addActionListener(e -> {
            JList<?> list = popupList(combo);
            if (list != null) list.repaint();
            long total = STAGGER_MS * Math.max(0, combo.getItemCount() - 1) + REVEAL_MS + 30;
            if (System.currentTimeMillis() - openedAt[0] > total) timer.stop();
        });
        combo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                openedAt[0] = System.currentTimeMillis();
                timer.restart();
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                timer.stop();
                // the dropdown's options go the way they came, like a menu's rows
                Object popup = combo.getUI().getAccessibleChild(combo, 0);
                JList<?> list = popupList(combo);
                if (popup instanceof JPopupMenu p && list != null && list.getModel().getSize() > 0) {
                    java.awt.Rectangle[] rows = new java.awt.Rectangle[list.getModel().getSize()];
                    for (int i = 0; i < rows.length; i++) {
                        java.awt.Rectangle r = list.getCellBounds(i, i);
                        rows[i] = r == null ? new java.awt.Rectangle() : javax.swing.SwingUtilities.convertRectangle(list, r, p);
                    }
                    ClosingGhost.play(p, rows);
                }
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
    }

    /** An empty popup menu whose items appear one after another, like a dropdown's options. */
    static JPopupMenu menu() {
        long[] openedAt = {0};
        JPopupMenu menu = new JPopupMenu() {
            /**
             * Row by row, the scan bar reveals everything - frame, background and text together: left of the bar
             * the menu is painted, right of it nothing at all, so the window shows through until the bar has passed.
             */
            @Override
            public void paint(Graphics g) {
                long since = System.currentTimeMillis() - openedAt[0];
                int n = getComponentCount();
                if (openedAt[0] == 0 || n == 0 || since >= STAGGER_MS * (n - 1) + REVEAL_MS) {
                    super.paint(g);
                    return;
                }
                java.awt.geom.Area shown = new java.awt.geom.Area();
                int[] bars = new int[n];
                for (int i = 0; i < n; i++) {
                    Component item = getComponent(i);
                    float p = Math.max(0, Math.min(1, (since - i * STAGGER_MS) / (float) REVEAL_MS));
                    float ease = 1 - (1 - p) * (1 - p);
                    int edge = Math.round(getWidth() * ease);
                    bars[i] = p > 0 && p < 1 ? edge : -1;
                    // a row's strip runs to the next row's top (the gaps between them belong to someone), the first
                    // from the very top and the last to the very bottom: the frame comes with them
                    int top = i == 0 ? 0 : item.getY();
                    int bottom = i == n - 1 ? getHeight() : getComponent(i + 1).getY();
                    if (edge > 0) shown.add(new java.awt.geom.Area(new java.awt.Rectangle(0, top, edge, bottom - top)));
                }
                java.awt.Graphics2D c = (java.awt.Graphics2D) g.create();
                try {
                    c.clip(shown);
                    super.paint(c);
                } finally {
                    c.dispose();
                }
                g.setColor(Theme.ACCENT);
                for (int i = 0; i < n; i++) {
                    if (bars[i] < 0) continue;
                    Component item = getComponent(i);
                    g.fillRect(Math.max(0, bars[i] - 2), item.getY() + 2, 3, item.getHeight() - 4);
                }
            }
        };
        menu.setOpaque(false); // see-through where the scan bar hasn't been (a lightweight popup, inside the window)
        // the whole menu each tick: an item repainted on its own (hovered) would otherwise skip the cover
        Timer timer = new Timer(15, null);
        timer.addActionListener(e -> {
            menu.repaint();
            long total = STAGGER_MS * Math.max(0, menu.getComponentCount() - 1) + REVEAL_MS + 30;
            if (System.currentTimeMillis() - openedAt[0] > total) timer.stop();
        });
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                openedAt[0] = System.currentTimeMillis();
                timer.restart();
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                timer.stop();
                // the rows go the way they came, over the window, once the menu itself is gone
                java.awt.Rectangle[] rows = new java.awt.Rectangle[menu.getComponentCount()];
                for (int i = 0; i < rows.length; i++) rows[i] = menu.getComponent(i).getBounds();
                ClosingGhost.play(menu, rows);
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        return menu;
    }

    /** Covers the part of a row not yet revealed (progress 0..1), with the scan bar at its edge. */
    private static void cover(Graphics g, int w, int h, float progress, java.awt.Color background) {
        float ease = 1 - (1 - progress) * (1 - progress);
        int x = Math.round(w * ease);
        g.setColor(background);
        g.fillRect(x, 0, w - x, h);
        g.setColor(Theme.ACCENT);
        g.fillRect(Math.max(0, x - 2), 2, 3, h - 4);
    }

    private static JList<?> popupList(JComboBox<?> combo) {
        Object popup = combo.getUI().getAccessibleChild(combo, 0);
        return popup instanceof ComboPopup p ? p.getList() : null;
    }

    /** The option, with the part not yet revealed covered and the scan bar at the edge. */
    private static final class Reveal extends JPanel {
        private float progress;

        Reveal() {
            super(new BorderLayout());
        }

        void show(Component option, float progress, java.awt.Color background) {
            this.progress = progress;
            if (getComponentCount() == 0 || getComponent(0) != option) {
                removeAll();
                add(option, BorderLayout.CENTER);
            }
            setBackground(background);
        }

        @Override
        public void paint(Graphics g) {
            super.paint(g);
            cover(g, getWidth(), getHeight(), progress, getBackground());
        }
    }

    /**
     * A menu closing: a picture of it stays on the window's layered pane for a moment and its rows wipe away from
     * the right, the scan bar at the edge, the bottom row first - the opening in reverse. Takes no mouse events, so
     * whatever the pilot clicks next gets the click.
     */
    private static final class ClosingGhost extends javax.swing.JComponent {
        private static final long WIPE_MS = 130, STAGGER = 28;

        private final java.awt.image.BufferedImage picture;
        private final java.awt.Rectangle[] rows;
        private final long startedAt = System.currentTimeMillis();
        private final Timer timer = new Timer(15, e -> tick());

        private ClosingGhost(java.awt.image.BufferedImage picture, java.awt.Rectangle[] rows) {
            this.picture = picture;
            this.rows = rows;
            setOpaque(false);
        }

        /** @param rows the rows that wipe away, in the popup's coordinates (a menu's items, a dropdown's options) */
        static void play(JPopupMenu menu, java.awt.Rectangle[] rows) {
            if (!menu.isShowing() || menu.getWidth() == 0 || menu.getHeight() == 0 || menu.getInvoker() == null) return;
            javax.swing.JRootPane root = javax.swing.SwingUtilities.getRootPane(menu.getInvoker());
            if (root == null) return;
            java.awt.image.BufferedImage picture = new java.awt.image.BufferedImage(menu.getWidth(), menu.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = picture.createGraphics();
            try {
                menu.paint(g);
            } finally {
                g.dispose();
            }
            javax.swing.JLayeredPane layer = root.getLayeredPane();
            java.awt.Point at = menu.getLocationOnScreen();
            javax.swing.SwingUtilities.convertPointFromScreen(at, layer);
            ClosingGhost ghost = new ClosingGhost(picture, rows);
            ghost.setBounds(at.x, at.y, menu.getWidth(), menu.getHeight());
            layer.add(ghost, javax.swing.JLayeredPane.POPUP_LAYER);
            ghost.timer.start();
        }

        private long totalMs() {
            return STAGGER * Math.max(0, rows.length - 1) + WIPE_MS;
        }

        private void tick() {
            if (System.currentTimeMillis() - startedAt >= totalMs()) {
                timer.stop();
                Component layer = getParent();
                if (layer != null) {
                    java.awt.Rectangle r = getBounds();
                    ((javax.swing.JComponent) layer).remove(this);
                    layer.repaint(r.x, r.y, r.width, r.height);
                }
                return;
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            long since = System.currentTimeMillis() - startedAt;
            int n = rows.length;
            for (int i = 0; i < n; i++) {
                java.awt.Rectangle r = rows[i];
                float p = (since - (n - 1 - i) * STAGGER) / (float) WIPE_MS; // the bottom row goes first
                float ease = 1 - (1 - Math.max(0, Math.min(1, p))) * (1 - Math.max(0, Math.min(1, p)));
                int keep = Math.round(r.width * (1 - ease)); // what is left of the row, from its left edge
                if (keep <= 0) continue;
                Graphics c = g.create(r.x, r.y, keep, r.height);
                try {
                    c.drawImage(picture, -r.x, -r.y, null);
                } finally {
                    c.dispose();
                }
                if (p > 0 && p < 1) {
                    g.setColor(Theme.ACCENT);
                    g.fillRect(r.x + Math.max(0, keep - 2), r.y + 2, 3, r.height - 4);
                }
            }
            // the frame goes with the last row
            float frame = 1 - Math.max(0, Math.min(1, (since - (n - 1) * STAGGER) / (float) WIPE_MS));
            if (frame > 0) {
                g.setColor(Theme.alpha(Theme.ACCENT, Math.round(255 * frame)));
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            }
        }
    }
}
