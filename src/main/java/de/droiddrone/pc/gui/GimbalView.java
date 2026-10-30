package de.droiddrone.pc.gui;

import de.droiddrone.pc.CalibrationWizard.Direction;
import de.droiddrone.pc.RcMapper;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayDeque;
import java.util.Deque;

/** One stick gimbal: a square with crosshair, a live dot with a short trail, and optional "move here" arrow. */
class GimbalView extends JComponent {
    private static final int TRAIL_LENGTH = 24;

    private final String title;
    private final String xName;
    private final String yName;
    private Integer xValue;
    private Integer yValue;
    private Direction arrow = Direction.NONE;
    private boolean disconnected;
    private final Deque<Point2D.Float> trail = new ArrayDeque<>();

    GimbalView(String title, String xName, String yName) {
        this.title = title;
        this.xName = xName;
        this.yName = yName;
        setPreferredSize(new Dimension(220, 262));
        setMinimumSize(new Dimension(150, 190));
        setOpaque(false);
    }

    /** Channel values 1000..2000, or null to hide the dot. */
    void setValues(Integer x, Integer y) {
        xValue = x;
        yValue = y;
        if (x != null && y != null) {
            trail.addLast(new Point2D.Float(norm(x), norm(y)));
            while (trail.size() > TRAIL_LENGTH) trail.removeFirst();
        } else {
            trail.clear();
        }
        repaint();
    }

    /** No controller: an empty, blinking marker where the stick should be. */
    void setDisconnected(boolean disconnected) {
        if (disconnected != this.disconnected) {
            this.disconnected = disconnected;
            repaint();
        }
    }

    void setArrow(Direction direction) {
        arrow = direction;
        repaint();
    }

    private static float norm(int channel) {
        return (channel - RcMapper.MID_CHANNEL_LEVEL) / 500f;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        int textHeight = 46;
        int size = Math.min(getWidth(), getHeight() - textHeight) - 4;
        int x0 = (getWidth() - size) / 2;
        int y0 = 2;
        float cx = x0 + size / 2f;
        float cy = y0 + size / 2f;
        float reach = size / 2f - 16;

        g.setColor(Theme.PANEL_2);
        g.fillRect(x0, y0, size, size);
        g.setColor(Theme.LINE);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRect(x0, y0, size, size);
        g.drawLine((int) cx, y0 + 10, (int) cx, y0 + size - 10);
        g.drawLine(x0 + 10, (int) cy, x0 + size - 10, (int) cy);
        g.drawOval((int) (cx - reach), (int) (cy - reach), (int) (reach * 2), (int) (reach * 2));

        if (arrow != Direction.NONE) paintArrow(g, cx, cy, reach);
        boolean searching = disconnected && (xValue == null || yValue == null);
        if (searching) paintSearching(g, cx, cy);

        int i = 0;
        for (Point2D.Float p : trail) {
            int alpha = 20 + 120 * i++ / TRAIL_LENGTH;
            g.setColor(Theme.alpha(Theme.ACCENT, alpha));
            float px = cx + p.x * reach;
            float py = cy - p.y * reach;
            g.fillOval(Math.round(px - 3), Math.round(py - 3), 6, 6);
        }
        if (xValue != null && yValue != null) {
            float px = cx + norm(xValue) * reach;
            float py = cy - norm(yValue) * reach;
            g.setColor(Theme.alpha(Theme.ACCENT, 60));
            g.fillOval(Math.round(px - 16), Math.round(py - 16), 32, 32);
            g.setColor(Theme.ACCENT);
            g.fillOval(Math.round(px - 10), Math.round(py - 10), 20, 20);
            g.setColor(Theme.TEXT);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(Math.round(px - 10), Math.round(py - 10), 20, 20);
        }

        g.setFont(Theme.headingFont(13f));
        g.setColor(Theme.TEXT);
        drawCentered(g, title.toUpperCase(), y0 + size + 20);
        g.setFont(Theme.font(java.awt.Font.PLAIN, 12f));
        g.setColor(searching ? Theme.BAD : Theme.MUTED);
        String values = searching ? "NO CONTROLLER" : xName + " " + (xValue == null ? "----" : xValue) + "     " + yName + " " + (yValue == null ? "----" : yValue);
        drawCentered(g, values, y0 + size + 38);
        g.dispose();
    }

    private void paintSearching(Graphics2D g, float cx, float cy) {
        long now = System.currentTimeMillis();
        // where the stick should be: an empty, blinking marker
        float blink = 0.5f + 0.5f * (float) Math.sin(now / 160.0);
        g.setColor(Theme.alpha(Theme.BAD, Math.round(80 + 175 * blink)));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
        g.drawOval(Math.round(cx - 10), Math.round(cy - 10), 20, 20);
        // no repaint here: the main window feeds the gimbals 30 times a second, which keeps this moving
    }

    private void paintArrow(Graphics2D g, float cx, float cy, float reach) {
        // pulse so the eye is drawn to the stick that has to move
        double phase = (System.currentTimeMillis() % 1000) / 1000.0;
        int alpha = (int) (150 + 105 * Math.sin(phase * Math.PI * 2));
        g.setColor(Theme.alpha(Theme.ACCENT, Math.max(40, Math.min(255, alpha))));
        g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float ex = arrow == Direction.RIGHT ? cx + reach : cx;
        float ey = arrow == Direction.UP ? cy - reach : cy;
        g.drawLine(Math.round(cx), Math.round(cy), Math.round(ex), Math.round(ey));
        Path2D head = new Path2D.Float();
        if (arrow == Direction.UP) {
            head.moveTo(ex - 14, ey + 16);
            head.lineTo(ex, ey);
            head.lineTo(ex + 14, ey + 16);
        } else {
            head.moveTo(ex - 16, ey - 14);
            head.lineTo(ex, ey);
            head.lineTo(ex - 16, ey + 14);
        }
        g.draw(head);
        repaint(50);
    }

    private void drawCentered(Graphics2D g, String text, int baseline) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, baseline);
    }
}
