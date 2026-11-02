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
                // the dropdown's box shows nothing before the scan bar: a picture of the window lies over it
                Object popup = combo.getUI().getAccessibleChild(combo, 0);
                if (popup instanceof JPopupMenu p) {
                    RevealCover.arm(p, () -> {
                        JList<?> list = popupList(combo);
                        if (list == null) return new java.awt.Rectangle[0];
                        java.awt.Rectangle[] rows = new java.awt.Rectangle[list.getModel().getSize()];
                        for (int i = 0; i < rows.length; i++) {
                            java.awt.Rectangle r = list.getCellBounds(i, i);
                            rows[i] = r == null ? new java.awt.Rectangle() : javax.swing.SwingUtilities.convertRectangle(list, r, p);
                        }
                        return rows;
                    });
                }
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

    /**
     * An empty popup menu, its ordinary grey self, that the scan bar reveals row by row - frame, background and
     * text together: a picture of the window as it was lies over it ({@link RevealCover}) and the bar wipes that
     * away, so nothing of the menu shows before the bar has passed.
     */
    static JPopupMenu menu() {
        JPopupMenu menu = new JPopupMenu();
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                RevealCover.arm(menu, () -> {
                    java.awt.Rectangle[] rows = new java.awt.Rectangle[menu.getComponentCount()];
                    for (int i = 0; i < rows.length; i++) rows[i] = menu.getComponent(i).getBounds();
                    return rows;
                });
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
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
     * A menu or dropdown opening: a picture of the window as it was, taken just before, lies over the popup on the
     * window's layered pane, and the scan bar wipes it away row by row from the left (the first row first), so the
     * popup - frame, grey background, text - shows only where the bar has been. Gone when the last row is bare.
     * Takes no mouse events. (A popup that reaches outside the window is a window of its own, which this can't
     * cover: it simply appears.)
     */
    private static final class RevealCover extends javax.swing.JComponent {
        private final java.awt.image.BufferedImage behind;
        private final java.awt.Rectangle[] rows;
        private final long startedAt = System.currentTimeMillis();
        private final Timer timer = new Timer(15, e -> tick());

        private RevealCover(java.awt.image.BufferedImage behind, java.awt.Rectangle[] rows) {
            this.behind = behind;
            this.rows = rows;
            setOpaque(false);
        }

        /** Called as the popup is about to show: the window is photographed now, the cover laid once it is up. */
        static void arm(JPopupMenu popup, java.util.function.Supplier<java.awt.Rectangle[]> rows) {
            Component invoker = popup.getInvoker();
            javax.swing.JRootPane root = invoker == null ? null : javax.swing.SwingUtilities.getRootPane(invoker);
            if (root == null) return;
            javax.swing.JLayeredPane layer = root.getLayeredPane();
            if (layer.getWidth() <= 0 || layer.getHeight() <= 0) return;
            java.awt.image.BufferedImage snap = new java.awt.image.BufferedImage(layer.getWidth(), layer.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = snap.createGraphics();
            try {
                layer.paint(g);
            } finally {
                g.dispose();
            }
            // runs before the popup's first paint: posted now, the paint is queued later, when the popup is up
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (!popup.isShowing() || popup.getWidth() == 0) return;
                java.awt.Point at = popup.getLocationOnScreen();
                javax.swing.SwingUtilities.convertPointFromScreen(at, layer);
                java.awt.Rectangle b = new java.awt.Rectangle(at.x, at.y, popup.getWidth(), popup.getHeight())
                        .intersection(new java.awt.Rectangle(0, 0, layer.getWidth(), layer.getHeight()));
                if (b.isEmpty()) return;
                java.awt.Rectangle[] r = rows.get();
                for (int i = 0; i < r.length; i++) r[i] = new java.awt.Rectangle(r[i].x + at.x - b.x, r[i].y + at.y - b.y, r[i].width, r[i].height);
                RevealCover cover = new RevealCover(snap.getSubimage(b.x, b.y, b.width, b.height), r);
                cover.setBounds(b);
                layer.add(cover, javax.swing.JLayeredPane.DRAG_LAYER);
                cover.timer.start();
            });
        }

        private long totalMs() {
            return STAGGER_MS * Math.max(0, rows.length - 1) + REVEAL_MS;
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
            int n = rows.length, w = getWidth(), h = getHeight();
            if (n == 0) return;
            for (int i = 0; i < n; i++) {
                float p = Math.max(0, Math.min(1, (since - i * STAGGER_MS) / (float) REVEAL_MS));
                float ease = 1 - (1 - p) * (1 - p);
                int edge = Math.round(w * ease);
                // a row's strip runs to the next row's top, the first from the very top, the last to the very bottom:
                // the frame and the gaps come with the rows
                int top = i == 0 ? 0 : rows[i].y;
                int bottom = i == n - 1 ? h : rows[i + 1].y;
                if (bottom <= top) continue;
                if (edge < w) {
                    Graphics c = g.create(edge, top, w - edge, bottom - top);
                    try {
                        c.drawImage(behind, -edge, -top, null);
                    } finally {
                        c.dispose();
                    }
                }
                if (p > 0 && p < 1) {
                    g.setColor(Theme.ACCENT);
                    g.fillRect(Math.max(0, edge - 2), rows[i].y + 2, 3, rows[i].height - 4);
                }
            }
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
