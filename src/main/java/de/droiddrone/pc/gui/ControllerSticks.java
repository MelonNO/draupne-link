package de.droiddrone.pc.gui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.LayoutManager;

/**
 * The CONTROLLER card's lower part: the two stick boxes on the left, the channel list beside them.
 * <p>
 * F-090 (the bench at 200 % text scaling, a 960 x 564 screen): the card short and narrow, each stick was the red dot in
 * a small square and the channel bars red ticks over their numbers, THROTTLE half there, the AUX rows gone. The list
 * now keeps the width its rows need and scrolls when there are more rows than height; the sticks get the rest of the
 * width and, short, put their words beside the box (GimbalView). At the size the card asks for - every window of
 * 1280 x 752 and up - it is laid out as the card's BorderLayout did before: the sticks 440 wide on the left, the list
 * from 18 px beside them to the card's edge, both as tall as the part.
 */
final class ControllerSticks extends JPanel {
    /** The stick boxes' height, and the least the card squeezes them to in a small window (F-010). */
    static final int STICKS_HEIGHT = 262, STICKS_MIN_HEIGHT = 86;
    /** The stick boxes' width together, and the gap between them and to the list. */
    static final int STICKS_WIDTH = 440, GAP = 18;

    private final JPanel gimbals = new JPanel(new GridLayout(1, 2, GAP, 0));
    private final ChannelBars bars;
    private final JScrollPane list;

    ControllerSticks(GimbalView left, GimbalView right, ChannelBars bars) {
        this.bars = bars;
        setOpaque(false);
        gimbals.setOpaque(false);
        gimbals.add(left);
        gimbals.add(right);
        gimbals.setPreferredSize(new Dimension(STICKS_WIDTH, STICKS_HEIGHT));
        list = new JScrollPane(bars, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
            @Override
            public boolean isValidateRoot() {
                return false; // more or fewer channels lay the whole card out again, as before
            }
        };
        list.setBorder(BorderFactory.createEmptyBorder());
        list.setOpaque(false);
        list.getViewport().setOpaque(false);
        setLayout(new Layout());
        add(gimbals);
        add(list);
    }

    /** The channel list as it scrolls: away while the card folds (the bars would run into the status lines). */
    JComponent channelList() {
        return list;
    }

    JComponent gimbals() {
        return gimbals;
    }

    /** The width the stick boxes get: what the list's rows (and its scroll bar) leave, half at least, 440 at most. */
    private int gimbalsWidth(int width, int height) {
        int least = bars.leastWidth();
        if (bars.getPreferredSize().height > height) least += list.getVerticalScrollBar().getPreferredSize().width;
        return Math.min(gimbals.getPreferredSize().width, Math.max(width / 2, width - GAP - least));
    }

    private final class Layout implements LayoutManager {
        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            // as the card's BorderLayout asked: the sticks' size, the list's beside them, the taller of the two
            Dimension g = gimbals.getPreferredSize();
            if (!list.isVisible()) return new Dimension(g);
            Dimension l = list.getPreferredSize();
            return new Dimension(g.width + GAP + l.width, Math.max(g.height, l.height));
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            Dimension g = gimbals.getMinimumSize(), l = list.getMinimumSize();
            return new Dimension(g.width + GAP + l.width, g.height);
        }

        @Override
        public void layoutContainer(Container parent) {
            int w = parent.getWidth(), h = parent.getHeight();
            int gw = gimbalsWidth(w, h);
            gimbals.setBounds(0, 0, gw, h);
            list.setBounds(gw + GAP, 0, Math.max(0, w - gw - GAP), h);
        }
    }
}
