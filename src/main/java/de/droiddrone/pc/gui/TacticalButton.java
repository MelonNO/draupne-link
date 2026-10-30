package de.droiddrone.pc.gui;

import javax.swing.JButton;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The app's buttons, with a military feel: on hover, corner brackets close in on the button and one scan line sweeps
 * across it; on click, a short flash, like a target being locked. The timer only runs while something animates.
 */
class TacticalButton extends JButton {
    private static final long HOVER_MS = 140;
    private static final long SWEEP_MS = 280;
    private static final long FLASH_MS = 220;

    private final boolean primary;
    private float hover;          // 0..1, eases toward hoverTarget
    private boolean hoverTarget;
    private long sweepStart;      // 0 = no sweep running
    private long flashStart;      // 0 = no flash running
    private long lastTick;
    private final Timer timer = new Timer(15, e -> tick());

    TacticalButton(String text, boolean primary) {
        super(text);
        this.primary = primary;
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (!isEnabled()) return;
                hoverTarget = true;
                sweepStart = System.currentTimeMillis();
                start();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverTarget = false;
                start();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (!isEnabled()) return;
                flashStart = System.currentTimeMillis();
                start();
            }
        });
    }

    private void start() {
        lastTick = System.currentTimeMillis();
        if (!timer.isRunning()) timer.start();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        float step = (now - lastTick) / (float) HOVER_MS;
        lastTick = now;
        hover = hoverTarget ? Math.min(1, hover + step) : Math.max(0, hover - step);
        if (sweepStart != 0 && now - sweepStart > SWEEP_MS) sweepStart = 0;
        if (flashStart != 0 && now - flashStart > FLASH_MS) flashStart = 0;
        repaint();
        boolean settled = (hoverTarget ? hover >= 1 : hover <= 0) && sweepStart == 0 && flashStart == 0;
        if (settled) timer.stop();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (!isEnabled() || hover <= 0 && sweepStart == 0 && flashStart == 0) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            int w = getWidth(), h = getHeight();
            float ease = 1 - (1 - hover) * (1 - hover);
            Color mark = primary ? Color.WHITE : Theme.ACCENT;
            // corner brackets closing in
            if (ease > 0) {
                int inset = Math.round(5 - 2 * ease);
                int arm = Math.round(3 + 7 * ease);
                g.setComposite(AlphaComposite.SrcOver.derive(0.35f + 0.65f * ease));
                g.setColor(mark);
                g.setStroke(new BasicStroke(2f));
                int r = w - 1 - inset, b = h - 1 - inset;
                g.drawLine(inset, inset, inset + arm, inset);
                g.drawLine(inset, inset, inset, inset + arm);
                g.drawLine(r, inset, r - arm, inset);
                g.drawLine(r, inset, r, inset + arm);
                g.drawLine(inset, b, inset + arm, b);
                g.drawLine(inset, b, inset, b - arm);
                g.drawLine(r, b, r - arm, b);
                g.drawLine(r, b, r, b - arm);
            }
            long now = System.currentTimeMillis();
            // one scan line across, on hover
            if (sweepStart != 0) {
                float t = Math.min(1, (now - sweepStart) / (float) SWEEP_MS);
                int band = Math.max(12, h);
                float x = -band + (w + 2 * band) * t;
                Color clear = new Color(255, 255, 255, 0), lit = new Color(255, 255, 255, primary ? 70 : 45);
                g.setComposite(AlphaComposite.SrcOver);
                g.setPaint(new GradientPaint(x - band, 0, clear, x, 0, lit));
                g.fillRect(Math.round(x - band), 0, band, h);
                g.setPaint(new GradientPaint(x, 0, lit, x + band / 3f, 0, clear));
                g.fillRect(Math.round(x), 0, Math.max(1, band / 3), h);
            }
            // lock-on flash, on click
            if (flashStart != 0) {
                float t = Math.min(1, (now - flashStart) / (float) FLASH_MS);
                g.setComposite(AlphaComposite.SrcOver.derive((1 - t) * (primary ? 0.45f : 0.35f)));
                g.setColor(primary ? Color.WHITE : Theme.ACCENT);
                g.fillRect(0, 0, w, h);
            }
        } finally {
            g.dispose();
        }
    }
}
