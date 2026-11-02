package de.droiddrone.pc.gui;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Supplier;

/**
 * The start: a black window, the mark fades in fast in the middle, then comes apart - the four brackets fly to the
 * window's corners (where the cards' bracket borders are), the crosshair to the middle of the video, the link
 * between the bars folds away - as the black lifts and the UI shows through; the pieces fade out on arrival. About
 * 3 s; a click or key skips it. Drawn on the window's layered pane over everything, taking the mouse meanwhile.
 */
final class BootReveal extends JComponent {
    private static final long FADE_IN_MS = 560, HOLD_MS = 320, FLY_MS = 1560, OUT_MS = 560; // 3 s in all
    private static final long TOTAL_MS = FADE_IN_MS + HOLD_MS + FLY_MS + OUT_MS;
    /** The brackets' size on arrival (the card borders' brackets are about this big). */
    private static final double CORNER_SIZE = 150;

    private final Supplier<Rectangle> videoBounds;
    private final Timer timer = new Timer(15, e -> tick());
    private long startedAt; // 0 = not started: all black
    private boolean done;

    private BootReveal(Supplier<Rectangle> videoBounds) {
        this.videoBounds = videoBounds;
        setOpaque(false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                finish();
            }
        });
        registerKeyboardAction(e -> finish(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), WHEN_IN_FOCUSED_WINDOW);
        registerKeyboardAction(e -> finish(), KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), WHEN_IN_FOCUSED_WINDOW);
        registerKeyboardAction(e -> finish(), KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), WHEN_IN_FOCUSED_WINDOW);
    }

    /**
     * Covers the window before it shows and plays once it is on screen.
     * @param videoBounds where the video is, in the layered pane's coordinates (the crosshair flies to its middle);
     *                    null for the window's middle
     */
    static void onOpen(JFrame window, Supplier<Rectangle> videoBounds) {
        BootReveal reveal = new BootReveal(videoBounds);
        JLayeredPane layer = window.getRootPane().getLayeredPane();
        layer.add(reveal, JLayeredPane.DRAG_LAYER);
        reveal.follow();
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                reveal.startedAt = System.currentTimeMillis();
                reveal.timer.start();
            }
        });
    }

    private void follow() {
        Component layer = getParent();
        if (layer == null) return;
        Rectangle r = new Rectangle(0, 0, layer.getWidth(), layer.getHeight());
        if (!r.equals(getBounds())) setBounds(r);
    }

    private void tick() {
        follow();
        if (System.currentTimeMillis() - startedAt >= TOTAL_MS) {
            finish();
            return;
        }
        repaint();
    }

    private void finish() {
        if (done) return;
        done = true;
        timer.stop();
        Component layer = getParent();
        if (layer != null) {
            ((JComponent) layer).remove(this);
            layer.repaint();
        }
    }

    private static double ease(double p) {
        p = Math.max(0, Math.min(1, p));
        return p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        try {
            int w = getWidth(), h = getHeight();
            long t = startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
            double size = Math.min(w, h) * 0.42;
            double lx = (w - size) / 2, ly = (h - size) / 2;

            if (t < FADE_IN_MS + HOLD_MS) {
                g.setColor(Theme.BG);
                g.fillRect(0, 0, w, h);
                float alpha = (float) Math.min(1, t / (double) FADE_IN_MS);
                Logo.paint(g, lx, ly, size, alpha);
                return;
            }
            double fly = ease((t - FADE_IN_MS - HOLD_MS) / (double) FLY_MS);
            double out = t < FADE_IN_MS + HOLD_MS + FLY_MS ? 0 : (t - FADE_IN_MS - HOLD_MS - FLY_MS) / (double) OUT_MS;
            float pieceAlpha = (float) (1 - out);

            // the black lifts as the pieces fly
            g.setComposite(AlphaComposite.SrcOver.derive((float) (1 - fly)));
            g.setColor(Theme.BG);
            g.fillRect(0, 0, w, h);
            g.setComposite(AlphaComposite.SrcOver);

            // the link between the bars folds into the middle and goes
            double linkSize = size * (1 - fly);
            if (linkSize > 2) Logo.paintLink(g, (w - linkSize) / 2, (h - linkSize) / 2, linkSize, (float) ((1 - fly) * pieceAlpha));

            // the brackets to the window's corners
            double cs = size + (CORNER_SIZE - size) * fly;
            double inset = -CORNER_SIZE * 72 / Logo.BOX + 6; // the bracket's own margin in its box, minus a little
            for (Logo.Corner c : Logo.Corner.values()) {
                double tx = switch (c) {
                    case TOP_LEFT, BOTTOM_LEFT -> inset;
                    default -> w - cs - inset;
                };
                double ty = switch (c) {
                    case TOP_LEFT, TOP_RIGHT -> inset;
                    default -> h - cs - inset;
                };
                Logo.paintBracket(g, c, lx + (tx - lx) * fly, ly + (ty - ly) * fly, cs, pieceAlpha);
            }

            // the crosshair to the middle of the video, where it splits and becomes the OSD's crosshair (same shape,
            // same size, same place), then turns the OSD's white: the real one is drawn there underneath
            Rectangle v = videoBounds == null ? null : videoBounds.get();
            // exactly where VideoView's no-video screen puts its crosshair: the view's middle in whole pixels
            double vcx = v == null || v.width == 0 ? w / 2 : v.x + v.width / 2;
            double vcy = v == null || v.height == 0 ? h / 2 : v.y + v.height / 2;
            float unit = (v == null || v.height == 0 ? h : v.height) / 720f;
            double travel = ease(Math.min(1, fly / 0.6));
            double morph = ease(Math.max(0, (fly - 0.55) / 0.45));
            double chSize = size + (size * 0.45 - size) * travel;
            double cx = w / 2.0 + (vcx - w / 2.0) * travel, cy = h / 2.0 + (vcy - h / 2.0) * travel;
            morphCrosshair(g, cx, cy, chSize / Logo.BOX, unit, morph, out);
        } finally {
            g.dispose();
        }
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /**
     * The mark's crosshair (ring, four ticks, on its dark disc; s = pixels per logo unit) turning into the OSD's
     * (OsdRenderer.crosshair: two arms with down-ticks at their inner ends and a centre dot, at unit = picture
     * height / 720). m 0..1: the side ticks grow into the arms, the top and bottom ticks swing down to become the
     * inner down-ticks, the ring shrinks to the dot, the disc goes. white 0..1: red to the OSD's white with its dark edge.
     */
    private static void morphCrosshair(Graphics2D g, double cx, double cy, double s, float unit, double m, double white) {
        if (m >= 1) { // arrived: the OSD's own drawing, so it sits pixel for pixel on the real one, only the colour runs
            int red = Logo.RED.getRed(), green = Logo.RED.getGreen(), blue = Logo.RED.getBlue();
            java.awt.Color color = new java.awt.Color((int) lerp(red, 255, white), (int) lerp(green, 255, white), (int) lerp(blue, 255, white));
            java.awt.Color edge = white <= 0 ? null : new java.awt.Color(0, 0, 0, (int) Math.round(200 * Math.min(1, white)));
            OsdRenderer.crosshair(g, (float) cx, (float) cy, unit, color, edge);
            return;
        }
        double in = 14 * unit, out = 44 * unit, tick = 10 * unit;
        double width = Math.max(2f, 3f * unit), dot = Math.max(3f, 5f * unit);
        java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
        double a1 = lerp(11 * s, in, m), a2 = lerp(39 * s, out, m);
        p.moveTo(cx - a2, cy);
        p.lineTo(cx - a1, cy);
        p.moveTo(cx + a2, cy);
        p.lineTo(cx + a1, cy);
        // the top tick swings down to the left arm's inner end, the bottom one to the right arm's
        p.moveTo(lerp(cx, cx - in, m), lerp(cy - 11 * s, cy, m));
        p.lineTo(lerp(cx, cx - in, m), lerp(cy - 39 * s, cy + tick, m));
        p.moveTo(lerp(cx, cx + in, m), lerp(cy + 11 * s, cy, m));
        p.lineTo(lerp(cx, cx + in, m), lerp(cy + 39 * s, cy + tick, m));
        double r = lerp(24 * s, dot / 2, m);
        java.awt.geom.Ellipse2D.Double ring = new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
        float stroke = (float) lerp(10 * s, width, m);
        Graphics2D c = (Graphics2D) g.create();
        try {
            if (m < 1) {
                double dr = 44 * s * (1 - m);
                c.setComposite(AlphaComposite.SrcOver.derive((float) (1 - m)));
                c.setColor(Logo.DARK);
                c.fill(new java.awt.geom.Ellipse2D.Double(cx - dr, cy - dr, 2 * dr, 2 * dr));
                c.setComposite(AlphaComposite.SrcOver);
            }
            if (white > 0) { // the OSD's dark edge under the white
                c.setComposite(AlphaComposite.SrcOver.derive((float) Math.min(1, white)));
                c.setColor(new java.awt.Color(0, 0, 0, 200));
                c.setStroke(new java.awt.BasicStroke(stroke + 2.5f, java.awt.BasicStroke.CAP_SQUARE, java.awt.BasicStroke.JOIN_MITER));
                c.draw(p);
                c.draw(ring);
                c.setComposite(AlphaComposite.SrcOver);
            }
            int red = Logo.RED.getRed(), green = Logo.RED.getGreen(), blue = Logo.RED.getBlue();
            c.setColor(new java.awt.Color((int) lerp(red, 255, white), (int) lerp(green, 255, white), (int) lerp(blue, 255, white)));
            c.setStroke(new java.awt.BasicStroke(stroke, m >= 1 ? java.awt.BasicStroke.CAP_SQUARE : java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER));
            c.draw(p);
            if (m >= 1) c.fill(ring); else c.draw(ring);
        } finally {
            c.dispose();
        }
    }

    /** For tests: whether it is still on. */
    boolean isRunning() {
        return !done;
    }

    static boolean isShowingOn(JFrame window) {
        for (Component c : window.getRootPane().getLayeredPane().getComponents()) if (c instanceof BootReveal r && r.isRunning()) return true;
        return false;
    }

    /** For tests: a frame of the animation at this time, painted into an image this big. */
    static java.awt.image.BufferedImage frame(long atMs, int w, int h, Rectangle video) {
        BootReveal r = new BootReveal(() -> video);
        r.setSize(w, h);
        r.startedAt = System.currentTimeMillis() - atMs;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        r.paintComponent(g);
        g.dispose();
        return img;
    }
}
