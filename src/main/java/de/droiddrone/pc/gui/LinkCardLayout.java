package de.droiddrone.pc.gui;

import javax.swing.JPanel;
import javax.swing.Scrollable;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
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
