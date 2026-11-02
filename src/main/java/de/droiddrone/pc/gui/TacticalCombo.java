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
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
    }

    /**
     * An empty popup menu that rolls open from the top, the scan bar at its edge, its items appearing one after
     * another as the roll passes them, like a dropdown's options. Nothing of it pops: the part not yet rolled out
     * isn't painted at all (the menu is see-through there).
     */
    static JPopupMenu menu() {
        long[] openedAt = {0};
        JPopupMenu menu = new JPopupMenu() {
            private long rollMs() {
                return STAGGER_MS * Math.max(0, getComponentCount() - 1) + REVEAL_MS;
            }

            @Override
            public void paint(Graphics g) {
                long since = System.currentTimeMillis() - openedAt[0];
                float p = openedAt[0] == 0 ? 1 : Math.min(1f, since / (float) rollMs());
                if (p >= 1) {
                    super.paint(g);
                    return;
                }
                float ease = 1 - (1 - p) * (1 - p);
                int edge = Math.round(getHeight() * ease);
                Graphics c = g.create(0, 0, getWidth(), edge);
                try {
                    super.paint(c);
                } finally {
                    c.dispose();
                }
                g.setColor(Theme.ACCENT);
                g.fillRect(0, Math.max(0, edge - 3), getWidth(), 3);
            }

            @Override
            protected void paintChildren(Graphics g) {
                super.paintChildren(g);
                long since = System.currentTimeMillis() - openedAt[0];
                for (int i = 0; i < getComponentCount(); i++) {
                    float p = (since - i * STAGGER_MS) / (float) REVEAL_MS;
                    if (p >= 1) continue;
                    Component item = getComponent(i);
                    Graphics c = g.create(item.getX(), item.getY(), item.getWidth(), item.getHeight());
                    cover(c, item.getWidth(), item.getHeight(), Math.max(0, p), getBackground());
                    c.dispose();
                }
            }
        };
        menu.setOpaque(false); // see-through where it hasn't rolled out yet (a lightweight popup, inside the window)
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
}
