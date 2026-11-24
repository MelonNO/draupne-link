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
    /** The room the name and the two values take under the box. */
    private static final int TEXT_HEIGHT = 46;
    /**
     * F-090: a box smaller than this, with the words under it, is a dot in a square (the bench at 200 %, 960 x 564: 36
     * px). Then the name goes over the box and the values beside it, if that lets the dot travel further.
     */
    static final int SMALL_BOX = 72;
    private static final int TEXT_GAP = 8;

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

    /**
     * The box (x, y, size), how far the dot travels from its middle, the dot's radius and its glow's, and whether the
     * words are beside the box (small) or under it, at the view's present size.
     */
    record Face(int x0, int y0, int size, float reach, int dot, int glow, boolean small) {
    }

    Face face() {
        int w = getWidth(), h = getHeight();
        int size = Math.min(w, h - TEXT_HEIGHT) - 4;
        Face under = new Face((w - size) / 2, 2, size, size / 2f - 16, 10, 16, false); // as it always was
        if (size >= SMALL_BOX) return under;
        FontMetrics name = getFontMetrics(Theme.headingFont(13f));
        int top = 2 + name.getHeight() + 2;
        int words = valuesWidth();
        int small = Math.min(h - top - 2, w - TEXT_GAP - words - 4);
        // the dot and its glow smaller in the small box, so its travel still shows
        int dot = Math.max(5, Math.min(10, small / 9));
        int glow = dot * 8 / 5;
        Face beside = new Face((w - small - TEXT_GAP - words) / 2, top, small, small / 2f - glow, dot, glow, true);
        return beside.reach() > under.reach() ? beside : under; // whichever lets the dot travel further
    }

    /** The wider of the two value lines as they stand beside a small box. */
    private int valuesWidth() {
        FontMetrics fm = getFontMetrics(Theme.font(java.awt.Font.PLAIN, 12f));
        int widest = 0;
        for (String name : new String[]{xName, yName}) {
            widest = Math.max(widest, Math.max(fm.stringWidth(name + " 0000"), fm.stringWidth(name + " ----")));
        }
        return widest;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        Face face = face();
        int size = face.size();
        int x0 = face.x0();
        int y0 = face.y0();
        float cx = x0 + size / 2f;
        float cy = y0 + size / 2f;
        float reach = face.reach();
        int dot = face.dot(), glow = face.glow();

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
        if (searching) paintSearching(g, cx, cy, dot);

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
            g.fillOval(Math.round(px - glow), Math.round(py - glow), glow * 2, glow * 2);
            g.setColor(Theme.ACCENT);
            g.fillOval(Math.round(px - dot), Math.round(py - dot), dot * 2, dot * 2);
            g.setColor(Theme.TEXT);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(Math.round(px - dot), Math.round(py - dot), dot * 2, dot * 2);
        }

        String x = xName + " " + (xValue == null ? "----" : xValue), y = yName + " " + (yValue == null ? "----" : yValue);
        g.setFont(Theme.headingFont(13f));
        g.setColor(Theme.TEXT);
        if (face.small()) {
            // the name over the box (NO CONTROLLER in its place), the values beside the box, one under the other
            if (searching) g.setColor(Theme.BAD);
            drawCentered(g, searching ? "NO CONTROLLER" : title.toUpperCase(), 2 + g.getFontMetrics().getAscent());
            g.setFont(Theme.font(java.awt.Font.PLAIN, 12f));
            g.setColor(Theme.MUTED);
            FontMetrics fm = g.getFontMetrics();
            int left = x0 + size + TEXT_GAP;
            int first = y0 + (size - 2 * fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(x, left, first);
            g.drawString(y, left, first + fm.getHeight());
            g.dispose();
            return;
        }
        drawCentered(g, title.toUpperCase(), y0 + size + 20);
        g.setFont(Theme.font(java.awt.Font.PLAIN, 12f));
        g.setColor(searching ? Theme.BAD : Theme.MUTED);
        String values = searching ? "NO CONTROLLER" : x + "     " + y;
        drawCentered(g, values, y0 + size + 38);
        g.dispose();
    }

    private void paintSearching(Graphics2D g, float cx, float cy, int r) {
        long now = System.currentTimeMillis();
        // where the stick should be: an empty, blinking marker
        float blink = 0.5f + 0.5f * (float) Math.sin(now / 160.0);
        g.setColor(Theme.alpha(Theme.BAD, Math.round(80 + 175 * blink)));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
        g.drawOval(Math.round(cx - r), Math.round(cy - r), r * 2, r * 2);
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
