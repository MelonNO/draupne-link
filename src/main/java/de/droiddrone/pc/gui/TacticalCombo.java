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
                    Ghost.play(p, rows);
                }
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
    }

    /**
     * An empty popup menu, its ordinary grey self. Open it with {@link #show}: a picture of it is wiped in row by
     * row by the scan bar, frame and all, and only then does the real menu take its place; closing runs the same
     * backwards.
     */
    static JPopupMenu menu() {
        JPopupMenu menu = new JPopupMenu();
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                // the rows go the way they came, over the window, once the menu itself is gone
                java.awt.Rectangle[] rows = new java.awt.Rectangle[menu.getComponentCount()];
                for (int i = 0; i < rows.length; i++) rows[i] = menu.getComponent(i).getBounds();
                Ghost.play(menu, rows);
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
     * Opens a menu the way the program's menus open: first a picture of it, wiped in row by row from the left by
     * the scan bar (frame, grey background and text together: nothing of it shows before the bar has been there),
     * then the real menu in its place, pixel for pixel. The closing is the same backwards ({@link Ghost}).
     * @param x the place under the invoker, as for JPopupMenu.show
     */
    static void show(JPopupMenu menu, Component invoker, int x, int y) {
        javax.swing.JRootPane root = javax.swing.SwingUtilities.getRootPane(invoker);
        java.awt.Dimension size = menu.getPreferredSize();
        if (root == null || !invoker.isShowing() || size.width <= 0 || size.height <= 0 || menu.getComponentCount() == 0) {
            menu.show(invoker, x, y);
            return;
        }
        menu.setSize(size);
        menu.doLayout();
        for (Component c : menu.getComponents()) if (c instanceof java.awt.Container container) container.doLayout();
        java.awt.image.BufferedImage picture = new java.awt.image.BufferedImage(size.width, size.height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = picture.createGraphics();
        try {
            menu.paint(g);
        } finally {
            g.dispose();
        }
        java.awt.Rectangle[] rows = new java.awt.Rectangle[menu.getComponentCount()];
        for (int i = 0; i < rows.length; i++) rows[i] = menu.getComponent(i).getBounds();
        javax.swing.JLayeredPane layer = root.getLayeredPane();
        if (!OPENING.add(layer)) return; // a second press while a menu is still wiping in: nothing
        java.awt.Point at = javax.swing.SwingUtilities.convertPoint(invoker, x, y, layer);
        Ghost.play(layer, new java.awt.Rectangle(at.x, at.y, size.width, size.height), picture, rows, true, () -> {
            OPENING.remove(layer);
            if (invoker.isShowing()) menu.show(invoker, x, y);
        });
    }

    /** The windows in which a menu is wiping in right now. */
    private static final java.util.Set<javax.swing.JLayeredPane> OPENING = new java.util.HashSet<>();

    /**
     * A menu opening or closing: a picture of it on the window's layered pane whose rows the scan bar wipes in
     * from the left, first row first (opening), or wipes away towards the left, last row first (closing). A row's
     * strip runs from the row above it to the row below, the first from the top edge and the last to the bottom
     * edge, so the frame comes and goes with the rows. Takes no mouse events, so whatever the pilot clicks next
     * gets the click.
     */
    private static final class Ghost extends javax.swing.JComponent {
        private static final long WIPE_MS = 130, STAGGER = 28;

        private final java.awt.image.BufferedImage picture;
        private final java.awt.Rectangle[] rows;
        private final boolean opening;
        private final Runnable done;
        private final long startedAt = System.currentTimeMillis();
        private final Timer timer = new Timer(15, e -> tick());

        private Ghost(java.awt.image.BufferedImage picture, java.awt.Rectangle[] rows, boolean opening, Runnable done) {
            this.picture = picture;
            this.rows = rows;
            this.opening = opening;
            this.done = done;
            setOpaque(false);
        }

        static void play(javax.swing.JLayeredPane layer, java.awt.Rectangle bounds, java.awt.image.BufferedImage picture,
                         java.awt.Rectangle[] rows, boolean opening, Runnable done) {
            Ghost ghost = new Ghost(picture, rows, opening, done);
            ghost.setBounds(bounds);
            layer.add(ghost, javax.swing.JLayeredPane.DRAG_LAYER);
            ghost.timer.start();
        }

        /** A popup that is closing: its picture as it is now, from where it is. */
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
            play(layer, new java.awt.Rectangle(at.x, at.y, menu.getWidth(), menu.getHeight()), picture, rows, false, null);
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
                if (done != null) done.run();
                return;
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            long since = System.currentTimeMillis() - startedAt;
            int n = rows.length, w = getWidth(), h = getHeight();
            for (int i = 0; i < n; i++) {
                int order = opening ? i : n - 1 - i; // opening: the top row first; closing: the bottom row first
                float p = Math.max(0, Math.min(1, (since - order * STAGGER) / (float) WIPE_MS));
                float ease = 1 - (1 - p) * (1 - p);
                int keep = Math.round(w * (opening ? ease : 1 - ease)); // what shows of the row, from its left edge
                int top = i == 0 ? 0 : rows[i].y;
                int bottom = i == n - 1 ? h : rows[i + 1].y;
                if (keep <= 0 || bottom <= top) continue;
                Graphics c = g.create(0, top, keep, bottom - top);
                try {
                    c.drawImage(picture, 0, -top, null);
                } finally {
                    c.dispose();
                }
                if (p > 0 && p < 1) {
                    g.setColor(Theme.ACCENT);
                    g.fillRect(Math.max(0, keep - 2), rows[i].y + 2, 3, rows[i].height - 4);
                }
            }
        }
    }
}
