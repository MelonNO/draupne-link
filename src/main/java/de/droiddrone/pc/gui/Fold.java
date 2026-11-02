package de.droiddrone.pc.gui;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Dimension;

/**
 * A part of a card that folds away and back: its height runs from the content's own to 0 (the content is clipped
 * at the fold) over {@value #MS} ms, easing out, and the layout follows each step. Used by the LINK card: the
 * connection fields fold away when connected and the minimap unfolds in their place.
 */
final class Fold extends JPanel {
    private static final long MS = 320;

    private final JComponent content;
    private boolean shown;
    private float progress; // 0 folded .. 1 shown
    private long lastTick;
    private final Timer timer = new Timer(15, e -> tick());

    Fold(JComponent content, boolean shown) {
        super(null);
        this.content = content;
        this.shown = shown;
        progress = shown ? 1 : 0;
        setOpaque(false);
        add(content);
    }

    boolean isShown() {
        return shown;
    }

    void setShown(boolean shown, boolean animate) {
        if (this.shown == shown && (animate || progress == (shown ? 1 : 0))) return;
        this.shown = shown;
        if (!animate || !isShowing()) {
            timer.stop();
            progress = shown ? 1 : 0;
            relayout();
            return;
        }
        lastTick = System.currentTimeMillis();
        if (!timer.isRunning()) timer.start();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        float step = (now - lastTick) / (float) MS;
        lastTick = now;
        progress = shown ? Math.min(1, progress + step) : Math.max(0, progress - step);
        if (progress == (shown ? 1 : 0)) timer.stop();
        relayout();
    }

    private void relayout() {
        revalidate();
        repaint();
    }

    private float eased() {
        return shown ? 1 - (1 - progress) * (1 - progress) : progress * progress;
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = content.getPreferredSize();
        return new Dimension(d.width, Math.round(d.height * eased()));
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public void doLayout() {
        Dimension d = content.getPreferredSize();
        content.setBounds(0, 0, getWidth(), d.height); // clipped at the fold
    }
}
