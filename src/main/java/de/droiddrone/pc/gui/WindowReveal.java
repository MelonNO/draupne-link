package de.droiddrone.pc.gui;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Fades things in: a window that opens (OSD editor, motor test, calibration, RC check, setup guides) and a setup
 * guide's page on Next or Back. Drawn on the window's layered pane over what it reveals; takes no mouse events, so
 * nothing waits for it. Gone when done.
 */
final class WindowReveal extends JComponent {
    private static final long FADE_MS = 220;

    private final JComponent target;
    private final Color background;
    private final Timer timer = new Timer(15, e -> tick());
    private long startedAt; // 0 = still covered: the window isn't on screen yet

    private WindowReveal(JComponent target) {
        this.target = target;
        this.background = background(target);
    }

    /** The window opens covered and fades in as soon as it is on screen. */
    static void onOpen(RootPaneContainer window) {
        WindowReveal reveal = new WindowReveal((JComponent) window.getContentPane());
        if (!reveal.attach(window.getRootPane())) return;
        ((Window) window).addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                reveal.start();
            }
        });
    }

    /** Part of a window on screen fades in: a setup guide's page on Next or Back. */
    static void fadeIn(JComponent target) {
        if (!target.isShowing()) return;
        WindowReveal reveal = new WindowReveal(target);
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

    private float progress() {
        return startedAt == 0 ? 0 : Math.min(1f, (System.currentTimeMillis() - startedAt) / (float) FADE_MS);
    }

    private void tick() {
        follow();
        if (progress() >= 1) {
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

    /** The background over the content, less and less of it (covered until the window is on screen). */
    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            float p = progress();
            float cover = 1 - (1 - (1 - p) * (1 - p) * (1 - p));
            g.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, cover)));
            g.setColor(background);
            g.fillRect(0, 0, getWidth(), getHeight());
        } finally {
            g.dispose();
        }
    }

    private static Color background(Component c) {
        while (c != null && !c.isOpaque()) c = c.getParent();
        return c == null ? Theme.BG : c.getBackground();
    }
}
