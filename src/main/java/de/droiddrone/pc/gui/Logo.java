package de.droiddrone.pc.gui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/**
 * The DRAUPNE Link mark, drawn in code from its SVG (a 512 x 512 design): four corner brackets, an X between two
 * bars (the link), a crosshair in the middle. Drawn whole for the window icon's fallback, and piece by piece by
 * {@link BootReveal}, which takes it apart into the window's corners.
 */
final class Logo {
    static final Color RED = new Color(0xFB1921);
    static final Color DARK = new Color(0x121212);
    static final double BOX = 512;

    enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private Logo() {
    }

    /** The whole mark with its top-left corner at x, y and this size (pixels). */
    static void paint(Graphics2D g, double x, double y, double size, float alpha) {
        for (Corner c : Corner.values()) paintBracket(g, c, x, y, size, alpha);
        paintLink(g, x, y, size, alpha);
        paintCrosshair(g, x, y, size, alpha);
    }

    private static Graphics2D at(Graphics2D g, double x, double y, double size, float alpha) {
        Graphics2D c = Theme.smooth((Graphics2D) g.create());
        c.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, alpha))));
        c.transform(AffineTransform.getTranslateInstance(x, y));
        c.transform(AffineTransform.getScaleInstance(size / BOX, size / BOX));
        return c;
    }

    /** One corner bracket of the mark whose box is at x, y with this size. */
    static void paintBracket(Graphics2D g, Corner corner, double x, double y, double size, float alpha) {
        Graphics2D c = at(g, x, y, size, alpha);
        try {
            c.setColor(RED);
            c.setStroke(new BasicStroke(22f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            Path2D p = new Path2D.Double();
            switch (corner) {
                case TOP_LEFT -> { p.moveTo(72, 130); p.lineTo(72, 72); p.lineTo(130, 72); }
                case TOP_RIGHT -> { p.moveTo(440, 130); p.lineTo(440, 72); p.lineTo(382, 72); }
                case BOTTOM_LEFT -> { p.moveTo(72, 382); p.lineTo(72, 440); p.lineTo(130, 440); }
                case BOTTOM_RIGHT -> { p.moveTo(440, 382); p.lineTo(440, 440); p.lineTo(382, 440); }
            }
            c.draw(p);
        } finally {
            c.dispose();
        }
    }

    /** The X between the two bars. */
    static void paintLink(Graphics2D g, double x, double y, double size, float alpha) {
        Graphics2D c = at(g, x, y, size, alpha);
        try {
            c.setColor(RED);
            c.clip(new Rectangle2D.Double(137, 138, 238, 236));
            c.setStroke(new BasicStroke(42f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            c.draw(new Line2D.Double(106.888, 76.457, 405.112, 435.543));
            c.draw(new Line2D.Double(106.888, 435.543, 405.112, 76.457));
            c.setClip(null);
            c.fill(new Rectangle2D.Double(137, 138, 42, 236));
            c.fill(new Rectangle2D.Double(333, 138, 42, 236));
        } finally {
            c.dispose();
        }
    }

    /** The crosshair, on its dark disc. */
    static void paintCrosshair(Graphics2D g, double x, double y, double size, float alpha) {
        Graphics2D c = at(g, x, y, size, alpha);
        try {
            c.setColor(DARK);
            c.fill(new Ellipse2D.Double(256 - 44, 256 - 44, 88, 88));
            c.setColor(RED);
            c.setStroke(new BasicStroke(10f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            c.draw(new Ellipse2D.Double(256 - 24, 256 - 24, 48, 48));
            c.draw(new Line2D.Double(267, 256, 295, 256));
            c.draw(new Line2D.Double(256, 267, 256, 295));
            c.draw(new Line2D.Double(245, 256, 217, 256));
            c.draw(new Line2D.Double(256, 245, 256, 217));
        } finally {
            c.dispose();
        }
    }
}
