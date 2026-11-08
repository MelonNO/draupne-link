package de.droiddrone.pc.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * Components left to right with a gap between (none before the first), wrapping to further lines when the width runs
 * out, and asking for the height of the lines it really needs at the width it has (1.0.52: Drone setup's flight mode
 * lists wrapped on a narrow window and ran over the rows under them - FlowLayout asks for one line's height).
 */
final class FlowWrap implements LayoutManager {
    private final int gap, lineGap;
    /** The width the last layout had: a change asks the parents for a new layout (the height may differ). */
    private int lastWidth = -1;

    FlowWrap(int gap, int lineGap) {
        this.gap = gap;
        this.lineGap = lineGap;
    }

    @Override
    public void addLayoutComponent(String name, Component comp) {
    }

    @Override
    public void removeLayoutComponent(Component comp) {
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        int w = parent.getWidth();
        return size(parent, w > 0 ? w - parent.getInsets().left - parent.getInsets().right : available(parent));
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        int w = 0, h = 0;
        for (Component c : parent.getComponents()) {
            if (!c.isVisible()) continue;
            Dimension d = c.getPreferredSize();
            w = Math.max(w, d.width);
            h += d.height + lineGap;
        }
        Insets in = parent.getInsets();
        return new Dimension(w + in.left + in.right, Math.max(0, h - lineGap) + in.top + in.bottom);
    }

    @Override
    public void layoutContainer(Container parent) {
        Insets in = parent.getInsets();
        int width = parent.getWidth() - in.left - in.right;
        int x = 0, y = 0, lineH = 0;
        for (Component c : parent.getComponents()) {
            if (!c.isVisible()) continue;
            Dimension d = c.getPreferredSize();
            if (x > 0 && x + d.width > width) {
                x = 0;
                y += lineH + lineGap;
                lineH = 0;
            }
            c.setBounds(in.left + x, in.top + y, d.width, d.height);
            x += d.width + gap;
            lineH = Math.max(lineH, d.height);
        }
        // asked for its size before it had a width: at the width it got, the lines may need another height
        if (parent.getWidth() != lastWidth) {
            lastWidth = parent.getWidth();
            if (size(parent, width).height != parent.getHeight() && parent instanceof javax.swing.JComponent j) {
                javax.swing.SwingUtilities.invokeLater(j::revalidate);
            }
        }
    }

    /** The width to fill: this container's once laid out, else its parent's, else one line. */
    private static int available(Container parent) {
        int w = parent.getWidth();
        for (Container c = parent.getParent(); w <= 0 && c != null; c = c.getParent()) w = c.getWidth();
        return w <= 0 ? Integer.MAX_VALUE : w - parent.getInsets().left - parent.getInsets().right;
    }

    private Dimension size(Container parent, int width) {
        int x = 0, w = 0, y = 0, lineH = 0;
        for (Component c : parent.getComponents()) {
            if (!c.isVisible()) continue;
            Dimension d = c.getPreferredSize();
            if (x > 0 && x + d.width > width) {
                x = 0;
                y += lineH + lineGap;
                lineH = 0;
            }
            x += d.width + gap;
            w = Math.max(w, x - gap);
            lineH = Math.max(lineH, d.height);
        }
        Insets in = parent.getInsets();
        return new Dimension(w + in.left + in.right, y + lineH + in.top + in.bottom);
    }
}
