package de.droiddrone.pc.gui;

import de.droiddrone.common.DetectionAreas;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Detection areas on the video (1.0.58, the pilot: "the user can box a certain area to be a different sensitivity in
 * cases where there is a constant movement that is not interesting"). Drawn over the picture as dashed boxes with
 * their level while detection is on; in edit mode (VIDEO > Detection areas) a drag draws one and asks its level, a
 * right-click on one changes or deletes it. Areas are kept in the camera's own picture (DetectionAreas): the picture
 * on screen may be mirrored or turned, so what is drawn is turned back.
 */
final class DetectionAreaLayer extends MouseAdapter {
    private static final Color LINE = new Color(255, 214, 10), OFF_LINE = new Color(255, 255, 255, 200);
    private static final Color SHADE = new Color(0, 0, 0, 70);
    /** A drag shorter than this (px) each way is a click. */
    static final int MIN_DRAG_PX = 8;

    private final VideoView view;
    private final Supplier<DetectionAreas> areas;
    private final Consumer<DetectionAreas> changed;
    private boolean editing, shown;
    private Point from, to;

    DetectionAreaLayer(VideoView view, Supplier<DetectionAreas> areas, Consumer<DetectionAreas> changed) {
        this.view = view;
        this.areas = areas;
        this.changed = changed;
        view.addMouseListener(this);
        view.addMouseMotionListener(this);
    }

    /** Edit mode: drawing and changing areas with the mouse. */
    void setEditing(boolean on) {
        editing = on;
        from = to = null;
        view.setCursor(on ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) : Cursor.getDefaultCursor());
        view.repaint();
    }

    boolean isEditing() {
        return editing;
    }

    /** The areas are drawn (detection on), editing or not. */
    void setShown(boolean on) {
        shown = on;
        view.repaint();
    }

    // ------------------------------------------------------------------ painting (VideoView, Swing thread)

    /** Over the picture at r, flipped as the picture is. */
    void paint(Graphics2D g, Rectangle r, boolean flipX, boolean flipY) {
        if (!(editing || shown) || r.width <= 0 || r.height <= 0) return;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
            FontMetrics fm = g2.getFontMetrics();
            Stroke dash = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 4}, 0);
            for (DetectionAreas.Area a : areas.get().list()) {
                Rectangle s = toScreen(a, r, flipX, flipY);
                if (a.level == 0) {
                    g2.setColor(SHADE);
                    g2.fillRect(s.x, s.y, s.width, s.height);
                }
                g2.setColor(a.level == 0 ? OFF_LINE : LINE);
                g2.setStroke(dash);
                g2.drawRect(s.x, s.y, s.width, s.height);
                label(g2, DetectionAreas.word(a.level), s.x + 4, s.y + fm.getAscent() + 2);
            }
            if (editing) {
                g2.setColor(LINE);
                String hint = "Drag a box. Right-click one to change it.";
                label(g2, hint, r.x + (r.width - fm.stringWidth(hint)) / 2, r.y + r.height - 12);
                if (from != null && to != null) {
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawRect(Math.min(from.x, to.x), Math.min(from.y, to.y), Math.abs(to.x - from.x), Math.abs(to.y - from.y));
                }
            }
        } finally {
            g2.dispose();
        }
    }

    /** Text in the current colour on a dark shadow: readable on any picture. */
    private static void label(Graphics2D g, String text, int x, int y) {
        Color c = g.getColor();
        g.setColor(new Color(0, 0, 0, 170));
        g.drawString(text, x + 1, y + 1);
        g.setColor(c);
        g.drawString(text, x, y);
    }

    /** An area (camera picture, 0..1) on screen. */
    static Rectangle toScreen(DetectionAreas.Area a, Rectangle r, boolean flipX, boolean flipY) {
        float x = flipX ? 1 - a.x - a.w : a.x, y = flipY ? 1 - a.y - a.h : a.y;
        return new Rectangle(r.x + Math.round(x * r.width), r.y + Math.round(y * r.height), Math.round(a.w * r.width), Math.round(a.h * r.height));
    }

    /** A box drawn on screen (two corners) as an area of the camera's own picture. */
    static DetectionAreas.Area toCamera(Point p, Point q, Rectangle r, boolean flipX, boolean flipY, int level) {
        float u0 = (Math.min(p.x, q.x) - r.x) / (float) r.width, u1 = (Math.max(p.x, q.x) - r.x) / (float) r.width;
        float v0 = (Math.min(p.y, q.y) - r.y) / (float) r.height, v1 = (Math.max(p.y, q.y) - r.y) / (float) r.height;
        float x0 = flipX ? 1 - u1 : u0, y0 = flipY ? 1 - v1 : v0;
        return new DetectionAreas.Area(x0, y0, u1 - u0, v1 - v0, level);
    }

    /** A point on screen in the camera's own picture (0..1). */
    static float[] toCamera(Point p, Rectangle r, boolean flipX, boolean flipY) {
        float u = (p.x - r.x) / (float) r.width, v = (p.y - r.y) / (float) r.height;
        return new float[]{flipX ? 1 - u : u, flipY ? 1 - v : v};
    }

    // ------------------------------------------------------------------ the mouse (edit mode only)

    @Override
    public void mousePressed(MouseEvent e) {
        if (!editing) return;
        if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
            areaMenu(e);
            return;
        }
        if (SwingUtilities.isLeftMouseButton(e)) from = to = e.getPoint();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (!editing || from == null) return;
        to = e.getPoint();
        view.repaint();
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (!editing) return;
        if (e.isPopupTrigger()) {
            areaMenu(e);
            return;
        }
        if (from == null) return;
        Point p = from, q = e.getPoint();
        from = to = null;
        view.repaint();
        Rectangle r = view.pictureRect();
        if (Math.abs(q.x - p.x) < MIN_DRAG_PX || Math.abs(q.y - p.y) < MIN_DRAG_PX || r.width <= 0) return;
        JPopupMenu menu = new JPopupMenu();
        for (int level = 0; level <= 3; level++) {
            int l = level;
            JMenuItem item = new JMenuItem(DetectionAreas.word(level));
            item.addActionListener(a -> changed.accept(areas.get().plus(toCamera(p, q, r, view.pictureFlipX(), view.isUpsideDown(), l))));
            menu.add(item);
        }
        menu.show(view, q.x, q.y);
    }

    /** Right-click: the topmost area under the mouse - its level or Delete; on none, Delete all. */
    private void areaMenu(MouseEvent e) {
        Rectangle r = view.pictureRect();
        if (r.width <= 0) return;
        float[] c = toCamera(e.getPoint(), r, view.pictureFlipX(), view.isUpsideDown());
        DetectionAreas now = areas.get();
        int i = now.at(c[0], c[1]);
        JPopupMenu menu = new JPopupMenu();
        if (i < 0) {
            if (now.isEmpty()) return;
            JMenuItem all = new JMenuItem("Delete all areas");
            all.addActionListener(a -> changed.accept(DetectionAreas.NONE));
            menu.add(all);
        } else {
            DetectionAreas.Area area = now.list().get(i);
            for (int level = 0; level <= 3; level++) {
                int l = level;
                JMenuItem item = new JMenuItem(DetectionAreas.word(level) + (level == area.level ? "  (now)" : ""));
                item.addActionListener(a -> changed.accept(areas.get().with(i, area.withLevel(l))));
                menu.add(item);
            }
            menu.addSeparator();
            JMenuItem delete = new JMenuItem("Delete");
            delete.addActionListener(a -> changed.accept(areas.get().with(i, null)));
            menu.add(delete);
        }
        menu.show(view, e.getX(), e.getY());
    }
}
