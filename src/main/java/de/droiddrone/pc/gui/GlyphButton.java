package de.droiddrone.pc.gui;

import javax.swing.JButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;

/**
 * A small square button with a drawn sign instead of a word, for the edge of the video: obvious, but taking as little
 * room from the picture as possible. The text is kept as its name (the tour, the tooltip) and never painted.
 */
final class GlyphButton extends JButton {
    enum Glyph { POP_OUT, VIDEO_BACK, FULL_SCREEN, BEEPER, SOUND, MUTED, AREAS }

    private static final int SIZE = 30;
    private Glyph glyph;

    GlyphButton(Glyph glyph, String name) {
        super(name);
        this.glyph = glyph;
        setPreferredSize(new Dimension(SIZE, SIZE));
        setMinimumSize(new Dimension(SIZE, SIZE));
        setMaximumSize(new Dimension(SIZE, SIZE));
        setFocusable(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
    }

    void setGlyph(Glyph glyph) {
        this.glyph = glyph;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        try {
            boolean lit = isSelected(); // a switch that is on (AREAS while drawing)
            boolean hot = getModel().isRollover() || getModel().isPressed() || lit;
            Color line = hot ? Theme.ACCENT : Theme.LINE;
            Color ink = hot ? Theme.ACCENT : Theme.TEXT;
            int w = getWidth(), h = getHeight();
            g.setColor(getModel().isPressed() || lit ? Theme.alpha(Theme.ACCENT, 60) : Theme.PANEL_2);
            g.fillRoundRect(0, 0, w - 1, h - 1, 6, 6);
            g.setColor(line);
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(0, 0, w - 1, h - 1, 6, 6);
            g.setColor(ink);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int m = 8; // margin round the sign
            switch (glyph) {
                case POP_OUT -> {
                    // a window, and an arrow leaving its top-right corner
                    g.drawRect(m, m + 4, w - 2 * m - 4, h - 2 * m - 4);
                    Path2D a = new Path2D.Float();
                    a.moveTo(w - m - 7, m + 1);
                    a.lineTo(w - m, m + 1);
                    a.lineTo(w - m, m + 8);
                    g.draw(a);
                    g.drawLine(w - m, m + 1, w / 2, h / 2 + 1);
                }
                case VIDEO_BACK -> {
                    // the window with the picture sitting in it again (no arrow: at this size one reads both ways)
                    g.drawRect(m, m + 2, w - 2 * m, h - 2 * m - 2);
                    g.fillRect(m + 4, m + 6, w - 2 * m - 8, h - 2 * m - 10);
                }
                case FULL_SCREEN -> {
                    // the mark's four corner brackets: the picture over the whole screen
                    int l = 6;
                    g.drawPolyline(new int[]{m, m, m + l}, new int[]{m + l, m, m}, 3);
                    g.drawPolyline(new int[]{w - m - l, w - m, w - m}, new int[]{m, m, m + l}, 3);
                    g.drawPolyline(new int[]{m, m, m + l}, new int[]{h - m - l, h - m, h - m}, 3);
                    g.drawPolyline(new int[]{w - m - l, w - m, w - m}, new int[]{h - m, h - m, h - m - l}, 3);
                }
                case BEEPER -> {
                    // a bell: the drone's beeper (the loudspeaker is the live sound's MUTE, just below)
                    int cx = w / 2;
                    Path2D bell = new Path2D.Float();
                    bell.moveTo(cx - 7, h - m - 3);
                    bell.curveTo(cx - 5, h - m - 6, cx - 6, m + 1, cx, m + 1);
                    bell.curveTo(cx + 6, m + 1, cx + 5, h - m - 6, cx + 7, h - m - 3);
                    bell.closePath();
                    g.draw(bell);
                    g.drawArc(cx - 2, h - m - 3, 4, 4, 180, 180);
                }
                case AREAS -> {
                    // detection areas (2026-11-10, beside MUTE): a dashed box drawn on the picture, a pen at its corner
                    g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 2.5f}, 0f));
                    g.drawRect(m - 1, m + 1, w - 2 * m - 2, h - 2 * m - 4);
                    g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawLine(w - m - 5, h - m - 1, w - m + 2, h - m - 8);
                    g.fillPolygon(new int[]{w - m - 6, w - m - 3, w - m - 6}, new int[]{h - m, h - m, h - m - 3}, 3);
                }
                case SOUND, MUTED -> {
                    // the live sound (1.0.45): a filled loudspeaker, unlike the beeper's outline one; muted: a cross
                    // instead of the sound arcs, in the warning colour, so a muted PC is seen at a glance
                    int cy = h / 2;
                    boolean muted = glyph == Glyph.MUTED;
                    if (muted && !hot) g.setColor(Theme.WARN);
                    g.fillPolygon(new int[]{m - 1, m + 3, m + 8, m + 8, m + 3, m - 1}, new int[]{cy - 3, cy - 3, cy - 7, cy + 7, cy + 3, cy + 3}, 6);
                    if (muted) {
                        g.drawLine(m + 11, cy - 4, m + 18, cy + 4);
                        g.drawLine(m + 11, cy + 4, m + 18, cy - 4);
                    } else {
                        g.drawArc(m + 6, cy - 5, 8, 10, -50, 100);
                        g.drawArc(m + 6, cy - 9, 14, 18, -50, 100);
                    }
                }
            }
        } finally {
            g.dispose();
        }
    }
}
