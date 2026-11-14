package de.droiddrone.pc.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A window's content looked at without the window ever being shown: laid out at a size, checked for parts that stick
 * out of what holds them, and painted into a picture - to $DEBRIEF_SHOTS when that is set (a folder; the debrief
 * round's tests: PREVIOUS FLIGHTS and the pins sheet with made-up flights and pins).
 */
final class Offscreen {
    private Offscreen() {
    }

    /**
     * The window's size set and everything in it laid out - a few times, every size asked anew each time, as the
     * window on the screen does when a wrapping row asks for another layout once it knows its width.
     */
    static void layout(java.awt.Window w, int width, int height) {
        w.setSize(width, height);
        for (int i = 0; i < 4; i++) {
            invalidateAll(w);
            w.validate();
        }
    }

    private static void invalidateAll(Component c) {
        c.invalidate();
        if (c instanceof Container k) for (Component x : k.getComponents()) invalidateAll(x);
    }

    /** Every visible part with a size lies inside its parent: nothing cut off at this window size. */
    static void assertNothingClipped(Container root, String at) {
        for (Component c : root.getComponents()) {
            if (!c.isVisible()) continue;
            Rectangle b = c.getBounds();
            boolean viewport = root instanceof javax.swing.JViewport; // what scrolls may be bigger than its window on it
            if (!viewport && b.width > 0 && b.height > 0) {
                assertTrue(b.x >= 0 && b.y >= 0 && b.x + b.width <= root.getWidth() && b.y + b.height <= root.getHeight(),
                        at + ": " + describe(c) + " " + b + " sticks out of " + describe(root) + " " + root.getWidth() + "x" + root.getHeight());
            }
            if (c instanceof Container k && !(c instanceof javax.swing.JScrollBar)) assertNothingClipped(k, at);
        }
    }

    private static String describe(Component c) {
        String text = c instanceof javax.swing.JLabel l ? " \"" + l.getText() + "\"" : c instanceof javax.swing.AbstractButton b ? " \"" + b.getText() + "\"" : "";
        return c.getClass().getSimpleName() + text;
    }

    /** The content painted at the window's size; kept as name.png in $DEBRIEF_SHOTS when that is set. */
    static BufferedImage shot(javax.swing.RootPaneContainer w, String name) throws java.io.IOException {
        Component root = w.getRootPane();
        BufferedImage img = new BufferedImage(Math.max(1, root.getWidth()), Math.max(1, root.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        root.paint(g);
        g.dispose();
        String dir = System.getenv("DEBRIEF_SHOTS");
        if (dir != null && !dir.isBlank()) {
            Files.createDirectories(Path.of(dir));
            javax.imageio.ImageIO.write(img, "png", Path.of(dir, name + ".png").toFile());
        }
        return img;
    }
}
