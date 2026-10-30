package de.droiddrone.pc.gui;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/**
 * A small notice under a locked button saying why it is locked. It slides in, a bar along its bottom drains to show
 * how long it stays, then it fades and folds away; a click closes it at once. It never covers the video: it is placed
 * under the button only where it stays clear of the area it must avoid (and inside the window), and is not shown, or
 * closes, when there is no such room - also when the layout changes while it is up.
 * Drawn in the window's popup layer; its timer runs only while it is up.
 */
final class LockedNotice extends JComponent {
    private static final long IN_MS = 160;
    private static final long SHOW_MS = 4000;
    private static final long OUT_MS = 260;
    private static final int WIDTH = 380;
    private static final int PAD = 10;
    private static final int POINTER = 7;  // the tip pointing up at the button
    private static final int GAP = 4;      // between the button and the tip

    private final JComponent anchor;
    private final Component avoid;
    private final String title;
    private final List<String> lines;
    private final Timer timer = new Timer(16, e -> tick());
    private long shownAt;
    private boolean closing;
    private long closingAt;

    /**
     * @param anchor the locked button the notice points at
     * @param avoid  the component the notice must never cover (the video), or null
     */
    LockedNotice(JComponent anchor, Component avoid, String title, String text) {
        this.anchor = anchor;
        this.avoid = avoid;
        this.title = title;
        setOpaque(false);
        lines = wrap(text, getFontMetrics(textFont()), WIDTH - 2 * PAD);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                close();
            }
        });
    }

    /** Shows the notice, or starts its time again if it is already up. */
    void pop() {
        JLayeredPane layer = layer();
        if (layer == null) return;
        boolean up = getParent() == layer && !closing && shownAt != 0;
        if (getParent() != layer) layer.add(this, JLayeredPane.POPUP_LAYER);
        // already up: its full time again, without sliding in again
        shownAt = up ? System.currentTimeMillis() - IN_MS : System.currentTimeMillis();
        closing = false;
        if (!place()) {
            dismiss();
            return;
        }
        setVisible(true);
        timer.start();
        repaint();
    }

    private void close() {
        if (closing) return;
        closing = true;
        closingAt = System.currentTimeMillis();
    }

    private void dismiss() {
        timer.stop();
        shownAt = 0;
        closing = false;
        Component parent = getParent();
        if (parent != null) {
            Rectangle r = getBounds();
            ((JComponent) parent).remove(this);
            parent.repaint(r.x, r.y, r.width, r.height);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (!closing && now - shownAt >= IN_MS + SHOW_MS) close();
        if (closing && now - closingAt >= OUT_MS) {
            dismiss();
            return;
        }
        if (!anchor.isShowing() || !place()) { // window or layout changed: never end up over the video
            dismiss();
            return;
        }
        repaint();
    }

    private JLayeredPane layer() {
        JRootPane root = SwingUtilities.getRootPane(anchor);
        return root == null || !anchor.isShowing() ? null : root.getLayeredPane();
    }

    /** Puts the notice under the anchor; false when it would cover the avoided area or leave the window. */
    private boolean place() {
        Component layer = getParent();
        if (layer == null) return false;
        Rectangle a = SwingUtilities.convertRectangle(anchor.getParent(), anchor.getBounds(), layer);
        int height = POINTER + 2 * PAD + titleHeight() + 6 + lines.size() * lineHeight() + 4;
        int x = Math.max(0, Math.min(a.x, layer.getWidth() - WIDTH));
        Rectangle r = new Rectangle(x, a.y + a.height + GAP, WIDTH, height);
        if (r.x + r.width > layer.getWidth() || r.y + r.height > layer.getHeight()) return false;
        if (avoid != null && avoid.isShowing()) {
            Rectangle v = SwingUtilities.convertRectangle(avoid.getParent(), avoid.getBounds(), layer);
            if (r.intersects(v)) return false;
        }
        if (!r.equals(getBounds())) setBounds(r);
        return true;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        long now = System.currentTimeMillis();
        float in = Math.min(1, (now - shownAt) / (float) IN_MS);
        float out = closing ? Math.min(1, (now - closingAt) / (float) OUT_MS) : 0;
        float life = closing ? 0 : 1 - Math.min(1, Math.max(0, now - shownAt - IN_MS) / (float) SHOW_MS);
        float easeIn = 1 - (1 - in) * (1 - in);
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        try {
            g.setComposite(AlphaComposite.SrcOver.derive(Math.max(0, easeIn * (1 - out))));
            // slides down out of the button; on the way out it folds up into it
            int w = getWidth(), h = getHeight();
            g.translate(0, Math.round(-6 * (1 - easeIn)));
            if (out > 0) g.clipRect(0, 0, w, Math.round(h * (1 - out * out)));
            int top = POINTER, boxH = h - POINTER - 1;
            int tipX = Math.min(Math.max(anchorCentre() - getX(), PAD + POINTER), w - PAD - POINTER);
            Path2D box = new Path2D.Float();
            box.moveTo(0, top);
            box.lineTo(tipX - POINTER, top);
            box.lineTo(tipX, 0);
            box.lineTo(tipX + POINTER, top);
            box.lineTo(w - 1, top);
            box.lineTo(w - 1, top + boxH);
            box.lineTo(0, top + boxH);
            box.closePath();
            g.setColor(Theme.PANEL_2);
            g.fill(box);
            g.setColor(Theme.ACCENT);
            g.setStroke(new BasicStroke(1f));
            g.draw(box);
            // red bar on the left, like the cards' section marks
            g.fillRect(0, top, 3, boxH);

            int y = top + PAD;
            g.setFont(Theme.headingFont(12.5f));
            FontMetrics fm = g.getFontMetrics();
            padlock(g, PAD + 2, y + fm.getAscent() - 7);
            g.setColor(Theme.ACCENT);
            g.drawString(title, PAD + 16, y + fm.getAscent());
            y += titleHeight() + 6;
            g.setFont(textFont());
            fm = g.getFontMetrics();
            g.setColor(Theme.TEXT);
            for (String line : lines) {
                g.drawString(line, PAD + 2, y + fm.getAscent());
                y += lineHeight();
            }
            // time left: drains from right to left, then the notice goes
            int barW = Math.round((w - 4) * life);
            g.setColor(Theme.LINE);
            g.fillRect(3, top + boxH - 3, w - 4, 3);
            g.setColor(Theme.ACCENT);
            g.fillRect(3, top + boxH - 3, barW, 3);
        } finally {
            g.dispose();
        }
    }

    private int anchorCentre() {
        Component layer = getParent();
        if (layer == null) return getX() + PAD + POINTER;
        Rectangle a = SwingUtilities.convertRectangle(anchor.getParent(), anchor.getBounds(), layer);
        return a.x + a.width / 2;
    }

    private static void padlock(Graphics2D g, int x, int y) {
        g.setColor(Theme.ACCENT);
        g.setStroke(new BasicStroke(1.5f));
        g.drawArc(x + 1, y - 6, 6, 8, 0, 180);
        g.drawLine(x + 1, y - 2, x + 1, y);
        g.drawLine(x + 7, y - 2, x + 7, y);
        g.fillRect(x, y, 9, 7);
    }

    private static Font textFont() {
        return Theme.font(Font.PLAIN, 12.5f);
    }

    private int titleHeight() {
        return getFontMetrics(Theme.headingFont(12.5f)).getHeight();
    }

    private int lineHeight() {
        return getFontMetrics(textFont()).getHeight();
    }

    private static List<String> wrap(String text, FontMetrics fm, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && fm.stringWidth(next) > width) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }
}
