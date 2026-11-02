package de.droiddrone.pc.gui;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

/**
 * The header's one button: MENU. Pressed, the other buttons (SETUP, VIDEO, OSD, MAP, LINK, TOOLS, and UPDATE when
 * there is one) slide out from behind it, to its left, and stay until it is pressed again. Laid out by hand: the
 * panel is as wide as what has slid out so far, so the rest is clipped behind the edge.
 */
final class SlideMenu extends JPanel {
    private static final long SLIDE_MS = 260;
    private static final int GAP = 8;

    private final JButton main;
    private final List<JButton> items = new ArrayList<>();
    private boolean open;
    private float progress; // 0 shut .. 1 open
    private long lastTick;
    private final Timer timer = new Timer(15, e -> tick());

    SlideMenu(JButton main) {
        super(null);
        this.main = main;
        setOpaque(false);
        add(main);
        main.addActionListener(e -> setOpen(!open));
    }

    /** Added at the right: the first added sits nearest the MENU button. */
    void addItem(JButton item) {
        items.add(item);
        add(item);
    }

    boolean isOpen() {
        return open;
    }

    void setOpen(boolean open) {
        if (this.open == open) return;
        this.open = open;
        lastTick = System.currentTimeMillis();
        if (!timer.isRunning()) timer.start();
    }

    /** Straight to the end state (tests, and when the window is built shut). */
    void finishNow() {
        timer.stop();
        progress = open ? 1 : 0;
        relayout();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        float step = (now - lastTick) / (float) SLIDE_MS;
        lastTick = now;
        progress = open ? Math.min(1, progress + step) : Math.max(0, progress - step);
        if (progress == (open ? 1 : 0)) timer.stop();
        relayout();
    }

    private void relayout() {
        revalidate();
        Component parent = getParent();
        if (parent instanceof JPanel p) p.revalidate();
        repaint();
    }

    private float eased() {
        return open ? 1 - (1 - progress) * (1 - progress) : progress * progress;
    }

    private int slidWidth() {
        int total = 0;
        for (JButton b : items) if (b.isVisible()) total += b.getPreferredSize().width + GAP;
        return total;
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension m = main.getPreferredSize();
        int h = m.height;
        for (JButton b : items) h = Math.max(h, b.getPreferredSize().height);
        return new Dimension(m.width + Math.round(slidWidth() * eased()), h);
    }

    @Override
    public void doLayout() {
        int w = getWidth(), h = getHeight();
        Dimension m = main.getPreferredSize();
        main.setBounds(w - m.width, (h - m.height) / 2, m.width, m.height);
        int x = w - m.width;
        for (JButton b : items) {
            if (!b.isVisible()) continue;
            Dimension d = b.getPreferredSize();
            x -= d.width + GAP;
            b.setBounds(x, (h - d.height) / 2, d.width, d.height);
        }
    }

    /** For tests: where an item sits (negative x = still behind the edge). */
    int itemX(JButton item) {
        return item.getX();
    }
}
