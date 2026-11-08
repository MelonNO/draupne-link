package de.droiddrone.pc.gui;

import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;

/**
 * Windows that fit the screen they open on (1.0.52, the pilot on a smaller screen: cut-off text and buttons). A
 * 1920x1080 screen at Windows' 150 % scaling is 1280x720 for the program, about 680 high above the taskbar - less than
 * the 740-760 most windows asked as their least. Each window still asks its wish; on a screen too small for it the
 * least size and the size are cut down to the screen, and the window's own layout (scrolling where it can't fit)
 * takes the rest.
 */
final class ScreenFit {
    private ScreenFit() {
    }

    /** The screen's room above the taskbar, in the program's (scaled) pixels; null off-screen (headless). */
    static Rectangle usable(Window w) {
        if (GraphicsEnvironment.isHeadless()) return null;
        GraphicsConfiguration gc = w != null && w.getGraphicsConfiguration() != null ? w.getGraphicsConfiguration()
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle r = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(r.x + in.left, r.y + in.top, r.width - in.left - in.right, r.height - in.top - in.bottom);
    }

    /** The least size: the wish, but never more than the screen has. */
    static Dimension least(Dimension wish, Rectangle screen) {
        if (screen == null) return wish;
        return new Dimension(Math.min(wish.width, screen.width), Math.min(wish.height, screen.height));
    }

    /** Sets the window's least size and size within the screen; the size is the wish where it fits. */
    static void apply(Window w, int leastW, int leastH, int wishW, int wishH) {
        Rectangle screen = usable(w);
        w.setMinimumSize(least(new Dimension(leastW, leastH), screen));
        w.setSize(least(new Dimension(Math.max(leastW, wishW), Math.max(leastH, wishH)), screen));
    }

    /** The least size only (the window's size is set elsewhere, e.g. restored from last time). */
    static void least(Window w, int leastW, int leastH) {
        w.setMinimumSize(least(new Dimension(leastW, leastH), usable(w)));
    }

    /** A size kept from last time, cut down to the screen it now opens on. */
    static void clampToScreen(Window w) {
        Rectangle screen = usable(w);
        if (screen == null) return;
        Rectangle b = w.getBounds();
        int width = Math.min(b.width, screen.width), height = Math.min(b.height, screen.height);
        int x = Math.max(screen.x, Math.min(b.x, screen.x + screen.width - width));
        int y = Math.max(screen.y, Math.min(b.y, screen.y + screen.height - height));
        if (width != b.width || height != b.height || x != b.x || y != b.y) w.setBounds(x, y, width, height);
    }
}
