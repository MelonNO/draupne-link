package de.droiddrone.pc.gui;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * A row of three - left, middle, right - that puts the middle on a line of its own under the other two when the row
 * is too narrow for all three (1.0.52: the controller card's status lines were cut to nothing and RC CHECK ran off
 * the card on a smaller screen). The left part may shrink to its minimum width before that.
 */
final class WrapRowLayout implements LayoutManager {
    private final Component left, middle, right;
    private final int gap, lineGap;
    private boolean wrapped;

    WrapRowLayout(Component left, Component middle, Component right, int gap, int lineGap) {
        this.left = left;
        this.middle = middle;
        this.right = right;
        this.gap = gap;
        this.lineGap = lineGap;
    }

    @Override
    public void addLayoutComponent(String name, Component comp) {
    }

    @Override
    public void removeLayoutComponent(Component comp) {
    }

    /** Whether all three fit on one line this wide. */
    boolean fitsOneLine(int width) {
        return left.getPreferredSize().width + gap + middle.getPreferredSize().width + gap + right.getPreferredSize().width <= width;
    }

    private int innerWidth(Container parent) {
        Insets in = parent.getInsets();
        return parent.getWidth() - in.left - in.right;
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        Insets in = parent.getInsets();
        Dimension l = left.getPreferredSize(), m = middle.getPreferredSize(), r = right.getPreferredSize();
        int line = Math.max(l.height, r.height);
        int width = parent.getWidth() > 0 ? innerWidth(parent) : l.width + m.width + r.width + 2 * gap;
        int h = fitsOneLine(width) ? Math.max(line, m.height) : line + lineGap + m.height;
        return new Dimension(l.width + m.width + r.width + 2 * gap + in.left + in.right, h + in.top + in.bottom);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        Insets in = parent.getInsets();
        Dimension l = left.getMinimumSize(), r = right.getPreferredSize();
        return new Dimension(l.width + gap + r.width + in.left + in.right,
                Math.max(l.height, r.height) + lineGap + middle.getPreferredSize().height + in.top + in.bottom);
    }

    @Override
    public void layoutContainer(Container parent) {
        Insets in = parent.getInsets();
        int x = in.left, y = in.top, w = innerWidth(parent);
        Dimension l = left.getPreferredSize(), m = middle.getPreferredSize(), r = right.getPreferredSize();
        boolean one = fitsOneLine(w);
        int line = one ? Math.max(Math.max(l.height, r.height), m.height) : Math.max(l.height, r.height);
        right.setBounds(x + w - r.width, y + (line - r.height) / 2, r.width, r.height);
        int leftW = Math.max(left.getMinimumSize().width, Math.min(l.width, w - r.width - gap));
        left.setBounds(x, y + (line - l.height) / 2, leftW, l.height);
        if (one) {
            int mx = x + l.width + gap;
            middle.setBounds(mx, y + (line - m.height) / 2, Math.max(0, x + w - r.width - gap - mx), m.height);
        } else {
            middle.setBounds(x, y + line + lineGap, w, m.height);
        }
        if (one == wrapped) {
            wrapped = !one;
            SwingUtilities.invokeLater(parent::revalidate); // one line more or less: the card's height follows
        }
    }
}
