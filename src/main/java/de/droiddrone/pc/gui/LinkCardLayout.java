package de.droiddrone.pc.gui;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * The MAP card under the LINK card (1.0.52, the pilot: "Have the map be in its own box and not inside the Link box. The
 * entire box should be removed when the pop out map is pressed."): POP OUT MAP always at the bottom, the minimap every
 * pixel above it. (1.0.52's first round kept the minimap in the LINK card, the card's rows scrolling to leave it
 * MINI_MIN; before that, a 768-high screen lost POP OUT MAP altogether.)
 */
final class LinkCardLayout implements LayoutManager {
    /**
     * F-010 / F-043, the pilot (2026-11-20): "hide the mini-map when the window is too low; POP OUT MAP stays". Below
     * this height the minimap is not shown at all and the MAP card is POP OUT MAP alone. Up to then the LINK card's rows
     * scrolled to leave the minimap 70 px - a strip with its LAST KNOWN line and zoom button cut, and PILOT UAV half
     * hidden in the scrolled rows.
     */
    static final int MINI_SHOWN_MIN = 140;
    /** The gap between the minimap and POP OUT MAP. */
    static final int BUTTON_GAP = 8;

    private final Component miniMap, popMap;

    LinkCardLayout(Component miniMap, Component popMap) {
        this.miniMap = miniMap;
        this.popMap = popMap;
    }

    @Override
    public void addLayoutComponent(String name, Component comp) {
    }

    @Override
    public void removeLayoutComponent(Component comp) {
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        Insets in = parent.getInsets();
        Dimension m = miniMap.getPreferredSize(), b = popMap.getPreferredSize();
        return new Dimension(Math.max(m.width, b.width) + in.left + in.right, m.height + BUTTON_GAP + b.height + in.top + in.bottom);
    }

    /** The least: POP OUT MAP alone (F-010: the minimap goes before the LINK card's rows scroll for it). */
    @Override
    public Dimension minimumLayoutSize(Container parent) {
        Insets in = parent.getInsets();
        return new Dimension(in.left + in.right + 100, in.top + in.bottom + popMap.getPreferredSize().height);
    }

    @Override
    public void layoutContainer(Container parent) {
        Insets in = parent.getInsets();
        int x = in.left, w = Math.max(0, parent.getWidth() - in.left - in.right);
        int top = in.top, bottom = parent.getHeight() - in.bottom;
        int buttonH = popMap.getPreferredSize().height;
        popMap.setBounds(x, Math.max(top, bottom - buttonH), w, Math.min(buttonH, bottom - top));
        int miniH = bottom - buttonH - BUTTON_GAP - top;
        // too low to be of use: not shown at all (no zero-high map that still loads its tiles)
        boolean shown = miniH >= MINI_SHOWN_MIN;
        miniMap.setBounds(x, top, w, shown ? miniH : 0);
        if (miniMap.isVisible() != shown) miniMap.setVisible(shown);
    }

    /**
     * F-075: a line that opens at the foot of the LINK card's rows, where a low window cut it in half at the card's edge
     * (QUEUED: OVERWATCH at 1093 x 584): the rows scroll it into sight, the top of the view at the top of a row, never
     * through one. Nothing scrolls when it is in sight already.
     */
    static void reveal(JComponent line) {
        JViewport v = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, line);
        if (v == null || !(v.getView() instanceof Container rows) || line.getHeight() <= 0) return;
        JRootPane root = SwingUtilities.getRootPane(line);
        if (root != null) root.validate(); // the layout the opening asked for, done
        for (Component p = line; p != rows && p.getParent() != null; p = p.getParent()) {
            if (!p.isVisible() || p.getY() + p.getHeight() > p.getParent().getHeight()) return; // in a fold that is shut
        }
        Rectangle r = SwingUtilities.convertRectangle(line.getParent(), line.getBounds(), rows);
        int y = revealY(v.getViewPosition().y, v.getExtentSize().height, rows.getHeight(), r.y, r.y + r.height, rowTops(rows));
        if (y != v.getViewPosition().y) v.setViewPosition(new Point(v.getViewPosition().x, y));
    }

    /**
     * Where the view's top goes for the part from top to bottom to be in sight: as little scrolled as will do, then on
     * to the next row's top (rowTops ascending), as long as the part stays in sight.
     */
    static int revealY(int at, int viewH, int contentH, int top, int bottom, int[] rowTops) {
        if (top >= at && bottom <= at + viewH) return at;
        int least = bottom - viewH, y;
        if (least < at) { // above the view: up to its row's top
            y = top;
            int best = Integer.MIN_VALUE;
            for (int t : rowTops) if (t <= top && t >= least) best = t;
            if (best != Integer.MIN_VALUE) y = best;
        } else { // below: down as little as will do, then on to the next row's top
            y = least;
            for (int t : rowTops) {
                if (t < y) continue;
                if (t <= top) y = t;
                break;
            }
        }
        return Math.max(0, Math.min(y, Math.max(0, contentH - viewH)));
    }

    /** The tops of the rows and of the parts of a row of several (PHONE, its list, CONNECT AS ...), ascending. */
    private static int[] rowTops(Container rows) {
        java.util.TreeSet<Integer> tops = new java.util.TreeSet<>();
        for (Component c : rows.getComponents()) {
            if (!c.isVisible() || c.getHeight() <= 0) continue;
            tops.add(c.getY());
            if (c instanceof JPanel p && !(c instanceof Fold)) {
                for (Component part : p.getComponents()) if (part.isVisible() && part.getHeight() > 0) tops.add(c.getY() + part.getY());
            }
        }
        return tops.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The LINK card's rows: as wide as the card (never a sideways scroll), as tall as they want. */
    static final class Rows extends JPanel implements Scrollable {
        Rows(LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(16, visibleRect.height - 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /**
     * The link column: wider on a wide window (a quarter of it, LINK_MIN_W to LINK_MAX_W). Top to bottom: the LINK card
     * as tall as its rows (they scroll when that would leave the MAP card less than its least), the MAP card every pixel
     * left, the LOG card at most LOG_MAX high and less on a short window (a fifth of it, at least LOG_MIN). With the map
     * popped out the MAP card is gone: the LINK card as tall as its rows, the log the rest.
     */
    static final class Column implements LayoutManager {
        static final int LINK_MIN_W = 330, LINK_MAX_W = 460, LOG_MIN = 110, LOG_MAX = 190, GAP = 14;
        private final Component link, map, log;

        Column(Component link, Component map, Component log) {
            this.link = link;
            this.map = map;
            this.log = log;
        }

        static int width(int windowWidth) {
            return Math.max(LINK_MIN_W, Math.min(LINK_MAX_W, windowWidth / 4));
        }

        static int logHeight(int columnHeight) {
            return Math.max(LOG_MIN, Math.min(LOG_MAX, columnHeight / 5));
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Container window = parent.getParent();
            return new Dimension(width(window == null ? 0 : window.getWidth()), 100);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(LINK_MIN_W, 100);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            int x = in.left, w = parent.getWidth() - in.left - in.right;
            int top = in.top, h = parent.getHeight() - in.top - in.bottom;
            if (map.isVisible()) {
                int room = Math.max(0, h - logHeight(h) - 2 * GAP);
                int linkH = Math.max(0, Math.min(link.getPreferredSize().height, room - map.getMinimumSize().height));
                int mapH = room - linkH;
                link.setBounds(x, top, w, linkH);
                map.setBounds(x, top + linkH + GAP, w, mapH);
                int logY = top + linkH + GAP + mapH + GAP;
                log.setBounds(x, logY, w, Math.max(0, top + h - logY));
                return;
            }
            int linkH = Math.max(0, Math.min(link.getPreferredSize().height, h - GAP - LOG_MIN));
            link.setBounds(x, top, w, linkH);
            log.setBounds(x, top + linkH + GAP, w, Math.max(0, h - linkH - GAP));
        }
    }
}
