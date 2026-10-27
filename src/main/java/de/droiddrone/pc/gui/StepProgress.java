package de.droiddrone.pc.gui;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

/** Segmented progress strip across the top of the calibration wizard. */
class StepProgress extends JComponent {
    private int count = 1;
    private int current;

    StepProgress() {
        setOpaque(false);
        setPreferredSize(new Dimension(400, 6));
    }

    void setProgress(int current, int count) {
        this.current = current;
        this.count = Math.max(1, count);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        int gap = 4;
        float segment = (getWidth() - gap * (count - 1)) / (float) count;
        for (int i = 0; i < count; i++) {
            g.setColor(i < current ? Theme.ACCENT : i == current ? Theme.TEXT : Theme.PANEL_2);
            g.fillRect(Math.round(i * (segment + gap)), 0, Math.round(segment), getHeight());
        }
        g.dispose();
    }
}
