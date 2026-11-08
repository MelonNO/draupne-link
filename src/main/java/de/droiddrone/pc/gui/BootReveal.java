package de.droiddrone.pc.gui;

import javax.swing.JComponent;
import javax.swing.JFrame;
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
 * between the bars morphs into the big cards' outlines - over black all the while; then the black lifts and the UI
 * fades in where the pieces are, as they fade out. About 4 s; a click or key skips it. The window's glass pane,
 * taking the mouse meanwhile.
 */
final class BootReveal extends JComponent {
    private static final long FADE_IN_MS = 560, HOLD_MS = 500, FLY_MS = 2400, OUT_MS = 700; // 4.2 s in all: the flight unhurried
    private static final long TOTAL_MS = FADE_IN_MS + HOLD_MS + FLY_MS + OUT_MS;
    /** The brackets' size on arrival (the card borders' brackets are about this big). */
    private static final double CORNER_SIZE = 150;

    private final Supplier<Rectangle> videoBounds;
    /**
     * What the mark's "D" morphs into: its two bars the two big cards on the left (LINK and LOG), its two diagonals
     * the card at the bottom (the controller), each piece growing into the card's outline; null = the D folds away
     * in the middle as before.
     */
    private final Supplier<Rectangle[]> rightTargets, bottomTargets;
    private final Timer timer = new Timer(15, e -> tick());
    private long startedAt; // 0 = not started: all black
    private long fixedNow = -1; // tests: the clock stands still at this time
    private boolean done;

    private long now() {
        return fixedNow >= 0 ? fixedNow : System.currentTimeMillis();
    }

    private BootReveal(Supplier<Rectangle> videoBounds) {
        this(videoBounds, null, null);
    }

    private BootReveal(Supplier<Rectangle> videoBounds, Supplier<Rectangle[]> rightTargets, Supplier<Rectangle[]> bottomTargets) {
        this.videoBounds = videoBounds;
        this.rightTargets = rightTargets;
        this.bottomTargets = bottomTargets;
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
     * @param videoBounds where the video is, in the glass pane's coordinates (the crosshair flies to its middle);
     *                    null for the window's middle
     */
    static void onOpen(JFrame window, Supplier<Rectangle> videoBounds) {
        onOpen(window, videoBounds, null, null);
    }

    /** @param rightTargets  the two cards on the left the D's bars become; bottomTargets the card(s) at the bottom for its diagonals (glass pane coordinates too) */
    static void onOpen(JFrame window, Supplier<Rectangle> videoBounds, Supplier<Rectangle[]> rightTargets, Supplier<Rectangle[]> bottomTargets) {
        BootReveal reveal = new BootReveal(videoBounds, rightTargets, bottomTargets);
        // the cover is the root pane's glass pane: the root pane's own layout sizes it with the window on every
        // layout, before any paint - a component on the layered pane got its size a frame after a resize (the window
        // opens maximized: it grows right after it shows), and in that frame the UI showed before the black
        window.getRootPane().setGlassPane(reveal);
        reveal.setVisible(true);
        window.getContentPane().setBackground(Theme.BG);
        window.getRootPane().setBackground(Theme.BG);
        window.setBackground(Theme.BG); // the native window too: Windows shows that before Swing's first paint
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                reveal.startedAt = System.currentTimeMillis();
                reveal.timer.start();
            }
        });
    }

    // No sizing of its own: the root pane's layout sizes the glass pane (the layered pane's size, inside the root
    // pane's insets) on every layout. A follow() here that set it to the root pane's full size fought that layout:
    // the height flipped by the insets (23 px) on every frame, and with it the mark and the brackets - the jitter
    // the pilot saw at every start (1.0.38).
    private void tick() {
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
        Component parent = getParent();
        if (parent instanceof javax.swing.JRootPane root && root.getGlassPane() == this) {
            setVisible(false);
            javax.swing.JPanel plain = new javax.swing.JPanel(); // the usual glass pane: invisible, taking nothing
            plain.setOpaque(false);
            plain.setVisible(false);
            root.setGlassPane(plain);
            root.repaint();
        } else if (parent != null) {
            ((JComponent) parent).remove(this);
            parent.repaint();
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
            long t = startedAt == 0 ? 0 : now() - startedAt;
            double size = Math.min(w, h) * 0.42;
            double lx = (w - size) / 2, ly = (h - size) / 2;

            if (t < FADE_IN_MS + HOLD_MS) {
                g.setColor(Theme.BG);
                g.fillRect(0, 0, w, h);
                float alpha = (float) Math.min(1, t / (double) FADE_IN_MS);
                if (alpha >= 1) Logo.paint(g, lx, ly, size, 1f);
                else paintMarkFading(g, lx, ly, size, alpha);
                return;
            }
            double fly = ease((t - FADE_IN_MS - HOLD_MS) / (double) FLY_MS);
            double out = t < FADE_IN_MS + HOLD_MS + FLY_MS ? 0 : Math.min(1, (t - FADE_IN_MS - HOLD_MS - FLY_MS) / (double) OUT_MS);
            float pieceAlpha = (float) (1 - out);

            // the black stays while the pieces fly: the UI fades in only once the mark has become its outlines
            g.setComposite(AlphaComposite.SrcOver.derive((float) (1 - out)));
            g.setColor(Theme.BG);
            g.fillRect(0, 0, w, h);
            g.setComposite(AlphaComposite.SrcOver);

            // the D comes apart: its bars to the big cards on the left, its diagonals to the one at the bottom -
            // or, without targets, the link between the bars folds into the middle and goes
            Rectangle[] right = rightTargets == null ? null : rightTargets.get();
            Rectangle[] bottom = bottomTargets == null ? null : bottomTargets.get();
            if (right != null && bottom != null && right.length >= 2 && bottom.length >= 1) {
                paintD(g, lx, ly, size, fly, pieceAlpha, right, bottom);
            } else {
                double linkSize = size * (1 - fly);
                if (linkSize > 2) Logo.paintLink(g, (w - linkSize) / 2, (h - linkSize) / 2, linkSize, (float) ((1 - fly) * pieceAlpha));
            }

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

    private java.awt.image.BufferedImage markImage; // the mark painted solid, for the fade-in
    private double markImageSize, markImageDx, markImageDy;

    /**
     * The whole mark at one alpha: painted solid into an image once, and the image drawn translucent. Painted
     * piece by piece at that alpha, the X showed through the bars' ends and the disc through to the X until the
     * fade was over, so the mark came in as parts, not as one thing.
     */
    private void paintMarkFading(Graphics2D g, double lx, double ly, double size, float alpha) {
        int ix = (int) Math.floor(lx), iy = (int) Math.floor(ly);
        double dx = lx - ix, dy = ly - iy; // the mark's place to the pixel, as the direct painting has it
        if (markImage == null || markImageSize != size || markImageDx != dx || markImageDy != dy) {
            int side = (int) Math.ceil(size) + 2;
            markImage = new java.awt.image.BufferedImage(side, side, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D m = Theme.smooth(markImage.createGraphics());
            try {
                Logo.paint(m, dx, dy, size, 1f);
            } finally {
                m.dispose();
            }
            markImageSize = size;
            markImageDx = dx;
            markImageDy = dy;
        }
        Graphics2D c = (Graphics2D) g.create();
        try {
            c.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, alpha))));
            c.drawImage(markImage, ix, iy, null);
        } finally {
            c.dispose();
        }
    }

    /**
     * The D of the mark morphing into the big parts of the window. Each of its four pieces is one shape all the way:
     * a thick line cut by a box. In the mark a bar is a 42-wide line down its middle cut by the bar's own bounds, a
     * diagonal is a 42-wide line of the X cut by the bars' box (exactly what Logo.paintLink draws, so the first frame
     * is the mark itself); a card is a line across its middle as thick as the card, cut by the card's bounds. The line,
     * its thickness and the cut slide from the one to the other, so the bars grow leftwards into the LINK and LOG
     * cards and the diagonals swing level and grow into the controller card and the MAP card (bottom[1], 1.0.52; the
 * controller card twice without it), and from a third of the way the solid
     * piece opens up from the inside until only the card's thin outline is left: no new corners, no cut.
     */
    private void paintD(Graphics2D g, double lx, double ly, double size, double fly, float pieceAlpha, Rectangle[] left, Rectangle[] bottom) {
        double s = size / Logo.BOX;
        double[][] from = { // x1, y1, x2, y2 of the line and the cut box, in the mark's units
                {158, 138, 158, 374, 137, 138, 179, 374}, {354, 138, 354, 374, 333, 138, 375, 374},
                {106.888, 76.457, 405.112, 435.543, 137, 138, 375, 374}, {106.888, 435.543, 405.112, 76.457, 137, 138, 375, 374}};
        Rectangle[] targets = {left[0], left[1], bottom[0], bottom.length > 1 ? bottom[1] : bottom[0]};
        double hollow = ease(Math.max(0, (fly - 0.3) / 0.7));
        Graphics2D c = (Graphics2D) g.create();
        try {
            c.setColor(Logo.RED);
            c.setComposite(AlphaComposite.SrcOver.derive(pieceAlpha));
            for (int i = 0; i < 4; i++) {
                Rectangle to = targets[i];
                boolean bar = i < 2; // a bar stays upright: its line runs top to bottom of the card; a diagonal levels out
                double[] f = from[i];
                double x1 = lerp(lx + f[0] * s, bar ? to.getCenterX() : to.getMinX(), fly);
                double y1 = lerp(ly + f[1] * s, bar ? to.getMinY() : to.getCenterY(), fly);
                double x2 = lerp(lx + f[2] * s, bar ? to.getCenterX() : to.getMaxX(), fly);
                double y2 = lerp(ly + f[3] * s, bar ? to.getMaxY() : to.getCenterY(), fly);
                double width = lerp(42 * s, bar ? to.width : to.height, fly);
                java.awt.geom.Rectangle2D cut = new java.awt.geom.Rectangle2D.Double(
                        lerp(lx + f[4] * s, to.getMinX(), fly), lerp(ly + f[5] * s, to.getMinY(), fly),
                        lerp((f[6] - f[4]) * s, to.width, fly), lerp((f[7] - f[5]) * s, to.height, fly));
                double wall = lerp(width / 2 + 1, 1.5, hollow); // solid until it starts to open
                java.awt.geom.Area piece = thick(x1, y1, x2, y2, width, cut);
                if (width - 2 * wall > 0.5) {
                    java.awt.geom.Rectangle2D in = new java.awt.geom.Rectangle2D.Double(
                            cut.getX() + wall, cut.getY() + wall, cut.getWidth() - 2 * wall, cut.getHeight() - 2 * wall);
                    piece.subtract(thick(x1, y1, x2, y2, width - 2 * wall, in));
                }
                c.fill(piece);
            }
        } finally {
            c.dispose();
        }
    }

    /** A line this thick, cut by a box: the one shape every piece of the D is, in the mark and as a card. */
    private static java.awt.geom.Area thick(double x1, double y1, double x2, double y2, double width, java.awt.geom.Rectangle2D cut) {
        java.awt.geom.Area a = new java.awt.geom.Area(new java.awt.BasicStroke((float) width, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER)
                .createStrokedShape(new java.awt.geom.Line2D.Double(x1, y1, x2, y2)));
        a.intersect(new java.awt.geom.Area(cut));
        return a;
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
            double w = Math.max(0, Math.min(1, white)); // a late first frame (Java2D's first use) can overshoot the phase
            java.awt.Color color = new java.awt.Color((int) lerp(red, 255, w), (int) lerp(green, 255, w), (int) lerp(blue, 255, w));
            java.awt.Color edge = w <= 0 ? null : new java.awt.Color(0, 0, 0, (int) Math.round(200 * w));
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
        if (window.getRootPane().getGlassPane() instanceof BootReveal r && r.isRunning()) return true;
        for (Component c : window.getRootPane().getLayeredPane().getComponents()) if (c instanceof BootReveal r && r.isRunning()) return true;
        return false;
    }

    /** For tests: a frame of the animation at this time, painted into an image this big. */
    static java.awt.image.BufferedImage frame(long atMs, int w, int h, Rectangle video) {
        return frame(atMs, w, h, video, null, null);
    }

    /** For tests: the same with the cards the D morphs into. */
    static java.awt.image.BufferedImage frame(long atMs, int w, int h, Rectangle video, Rectangle[] right, Rectangle[] bottom) {
        BootReveal r = right == null ? new BootReveal(() -> video) : new BootReveal(() -> video, () -> right, () -> bottom);
        r.setSize(w, h);
        // the clock stands still: on a slow build machine Java2D's first use took seconds between here and the paint
        r.startedAt = 1;
        r.fixedNow = 1 + atMs;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        r.paintComponent(g);
        g.dispose();
        return img;
    }
}
