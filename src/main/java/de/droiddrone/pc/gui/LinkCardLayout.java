package de.droiddrone.pc.gui;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;

/**
 * The LINK card on any screen (1.0.52, the pilot on a smaller screen: POP OUT MAP gone, the card's top cut off). Up to
 * 1.0.51 the card was one grid: too short for its rows (the sleep row and a warning on a 768-high screen), the grid
 * centred them and cut both ends, and the minimap with POP OUT MAP under it got nothing. Now: POP OUT MAP always at
 * the bottom; the rows above as tall as they want, but scrolling when that would leave the minimap less than
 * MINI_MIN; the minimap every pixel between.
 */
final class LinkCardLayout implements LayoutManager {
    /** The least the minimap keeps before the rows start to scroll. */
    static final int MINI_MIN = 70;
    /** The gaps round the minimap. */
    static final int GAP = 10, BUTTON_GAP = 8;

    private final JScrollPane rowsScroll;
    private final Component rows, miniMap, popMap;

    LinkCardLayout(JScrollPane rowsScroll, Component rows, Component miniMap, Component popMap) {
        this.rowsScroll = rowsScroll;
        this.rows = rows;
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
        Dimension r = rows.getPreferredSize(), b = popMap.getPreferredSize();
        int h = r.height + GAP + (miniMap.isVisible() ? miniMap.getPreferredSize().height + BUTTON_GAP : 0) + b.height;
        return new Dimension(Math.max(r.width, b.width) + in.left + in.right, h + in.top + in.bottom);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        Insets in = parent.getInsets();
        return new Dimension(in.left + in.right + 100, in.top + in.bottom + popMap.getPreferredSize().height + 60);
    }

    @Override
    public void layoutContainer(Container parent) {
        Insets in = parent.getInsets();
        int x = in.left, w = Math.max(0, parent.getWidth() - in.left - in.right);
        int top = in.top, bottom = parent.getHeight() - in.bottom;
        int buttonH = popMap.getPreferredSize().height;
        popMap.setBounds(x, Math.max(top, bottom - buttonH), w, Math.min(buttonH, bottom - top));
        int room = Math.max(0, bottom - buttonH - top);
        int mini = miniMap.isVisible() ? MINI_MIN + BUTTON_GAP : 0;
        int rowsH = Math.max(0, Math.min(rows.getPreferredSize().height, room - GAP - mini));
        rowsScroll.setBounds(x, top, w, rowsH);
        rowsScroll.validate(); // the scroll bar comes and goes with the room now, not at the next layout
        if (miniMap.isVisible()) {
            int y = top + rowsH + GAP;
            miniMap.setBounds(x, y, w, Math.max(0, bottom - buttonH - BUTTON_GAP - y));
        }
    }

    /** The rows: as wide as the card (never a sideways scroll), as tall as they want. */
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
     * The link column: wider on a wide window (a quarter of it, LINK_MIN_W to LINK_MAX_W), the LOG card under the LINK
     * card at most LOG_MAX high and less on a short window (a fifth of it, at least LOG_MIN), so the LINK card keeps its
     * room. With the map popped out (no minimap) the LINK card is as tall as its rows and the log takes the rest.
     */
    static final class Column implements LayoutManager {
        static final int LINK_MIN_W = 330, LINK_MAX_W = 460, LOG_MIN = 110, LOG_MAX = 190, GAP = 14;
        private final Component link, log, miniMap;

        Column(Component link, Component log, Component miniMap) {
            this.link = link;
            this.log = log;
            this.miniMap = miniMap;
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
            int linkH;
            if (miniMap.isVisible()) {
                linkH = h - GAP - logHeight(h);
            } else {
                linkH = Math.min(link.getPreferredSize().height, h - GAP - LOG_MIN);
            }
            linkH = Math.max(0, linkH);
            link.setBounds(x, top, w, linkH);
            log.setBounds(x, top + linkH + GAP, w, Math.max(0, h - linkH - GAP));
        }
    }
}
