package de.droiddrone.pc.gui;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * The SETUP menu's reveal (see {@link TacticalCombo#menu()}) for whole windows and wizard pages: the area is uncovered
 * in bands, top to bottom one after another, each wiped from left to right behind the red scan bar. Drawn on the
 * window's layered pane; takes no mouse events, so nothing waits for it. Gone when done.
 */
final class ScanReveal extends JComponent {
    private static final long STAGGER_MS = 45; // as the SETUP menu's items
    private static final long REVEAL_MS = 170;
    private static final int BAND = 90;         // about one menu row per band, 3 to 8 bands

    private final JComponent target;
    private final Color background;
    private final Timer timer = new Timer(15, e -> tick());
    private long startedAt; // 0 = still covered: the window isn't on screen yet

    private ScanReveal(JComponent target) {
        this.target = target;
        this.background = background(target);
    }

    /** The window opens covered and is revealed as soon as it is on screen. */
    static void onOpen(RootPaneContainer window) {
        ScanReveal reveal = new ScanReveal((JComponent) window.getContentPane());
        if (!reveal.attach(window.getRootPane())) return;
        ((Window) window).addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                reveal.start();
            }
        });
    }

    /** Part of a window on screen, e.g. a wizard's next page. */
    static void play(JComponent target) {
        if (!target.isShowing()) return;
        ScanReveal reveal = new ScanReveal(target);
        if (reveal.attach(SwingUtilities.getRootPane(target))) reveal.start();
    }

    private boolean attach(JRootPane root) {
        if (root == null) return false;
        root.getLayeredPane().add(this, JLayeredPane.DRAG_LAYER);
        follow();
        return true;
    }

    private void start() {
        startedAt = System.currentTimeMillis();
        timer.start();
    }

    /** Over the target, also when the window is resized meanwhile. */
    private void follow() {
        Component layer = getParent();
        if (layer == null) return;
        Rectangle r = target.getParent() == null ? target.getBounds()
                : SwingUtilities.convertRectangle(target.getParent(), target.getBounds(), layer);
        if (!r.equals(getBounds())) setBounds(r);
    }

    private int bands() {
        return Math.max(3, Math.min(8, getHeight() / BAND));
    }

    private void tick() {
        follow();
        if (System.currentTimeMillis() - startedAt > STAGGER_MS * (bands() - 1) + REVEAL_MS + 30) {
            timer.stop();
            Component layer = getParent();
            if (layer != null) {
                Rectangle r = getBounds();
                ((JComponent) layer).remove(this);
                layer.repaint(r.x, r.y, r.width, r.height);
            }
            return;
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        int w = getWidth(), h = getHeight(), n = bands();
        long since = startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
        for (int i = 0; i < n; i++) {
            int top = h * i / n, bottom = h * (i + 1) / n;
            float p = startedAt == 0 ? 0 : (since - i * STAGGER_MS) / (float) REVEAL_MS;
            if (p >= 1) continue;
            p = Math.max(0, p);
            float ease = 1 - (1 - p) * (1 - p);
            int x = Math.round(w * ease);
            g.setColor(background);
            g.fillRect(x, top, w - x, bottom - top);
            if (p > 0) {
                g.setColor(Theme.ACCENT);
                g.fillRect(Math.max(0, x - 2), top + 2, 3, bottom - top - 4);
            }
        }
    }

    private static Color background(Component c) {
        while (c != null && !c.isOpaque()) c = c.getParent();
        return c == null ? Theme.BG : c.getBackground();
    }
}
