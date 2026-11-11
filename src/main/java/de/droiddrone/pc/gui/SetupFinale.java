package de.droiddrone.pc.gui;

import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The moment the first-time setup is finished: a short animation over the main window - the mark draws itself, its
 * brackets close in, SETUP COMPLETE slides up, a bar drains - then it goes by itself (or on a click) and the next
 * thing runs, if any (the offer of the tour).
 */
final class SetupFinale extends JComponent {
    private static final long MARK_MS = 700;
    private static final long TEXT_MS = 500;
    private static final long HOLD_MS = 4400;
    private static final long OUT_MS = 350;
    private static final int W = 560, H = 320;

    private final long startedAt = System.currentTimeMillis();
    private final JDialog dialog;
    private final Runnable then;
    private boolean closing;
    private long closingAt;
    private final Timer timer = new Timer(16, e -> tick());

    private SetupFinale(JDialog dialog, Runnable then) {
        this.dialog = dialog;
        this.then = then;
        setPreferredSize(new Dimension(W, H));
        setOpaque(true);
        setBackground(Color.BLACK);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                close();
            }
        });
    }

    /** Plays over the main window, then runs {@code then} (or nothing) on the Swing thread. */
    static void play(JFrame owner, Runnable then) {
        JDialog d = new JDialog(owner, "Setup complete", java.awt.Dialog.ModalityType.MODELESS);
        d.setUndecorated(true);
        SetupFinale f = new SetupFinale(d, then);
        d.setContentPane(f);
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        f.timer.start();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (!closing && now - startedAt >= MARK_MS + TEXT_MS + HOLD_MS) close();
        if (closing && now - closingAt >= OUT_MS) {
            timer.stop();
            dialog.dispose();
            if (then != null) then.run();
            return;
        }
        repaint();
    }

    private void close() {
        if (closing) return;
        closing = true;
        closingAt = System.currentTimeMillis();
    }

    private static double ease(double p) {
        p = Math.max(0, Math.min(1, p));
        return 1 - (1 - p) * (1 - p) * (1 - p);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        long t = System.currentTimeMillis() - startedAt;
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        try {
            int w = getWidth(), h = getHeight();
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            float out = closing ? (float) ease((System.currentTimeMillis() - closingAt) / (double) OUT_MS) : 0;
            g.setComposite(AlphaComposite.SrcOver.derive(1 - out));
            // no frame line or accent bar: on the black window they read as stray lines

            // the mark: grows in, and its brackets close in from outside
            double mark = ease(t / (double) MARK_MS);
            double size = 120;
            double cx = w / 2.0, cy = 118;
            double grow = 0.6 + 0.4 * mark;
            Logo.paintMark(g, cx - size * grow / 2, cy - size * grow / 2, size * grow, (float) mark);
            double spread = (1 - mark) * 60;
            double bs = size + 28 + spread * 2;
            for (Logo.Corner c : Logo.Corner.values()) {
                Logo.paintBracket(g, c, cx - bs / 2, cy - bs / 2, bs, (float) Math.min(1, mark * 1.4));
            }

            // SETUP COMPLETE slides up under it
            double text = ease((t - MARK_MS) / (double) TEXT_MS);
            if (text > 0) {
                g.setComposite(AlphaComposite.SrcOver.derive((float) (text * (1 - out))));
                g.setFont(Theme.headingFont(34f));
                FontMetrics fm = g.getFontMetrics();
                String s = "SETUP COMPLETE";
                int y = (int) Math.round(232 + (1 - text) * 18);
                g.setColor(Theme.TEXT);
                g.drawString(s, (w - fm.stringWidth(s)) / 2f, y);
                g.setFont(Theme.font(java.awt.Font.PLAIN, 14f));
                fm = g.getFontMetrics();
                String s2 = "Press PILOT UAV when drone and phone are ready.";
                g.setColor(Theme.MUTED);
                g.drawString(s2, (w - fm.stringWidth(s2)) / 2f, y + 30);
            }
            // time left: drains, then it goes
            double life = 1 - Math.max(0, t - MARK_MS - TEXT_MS) / (double) HOLD_MS;
            g.setComposite(AlphaComposite.SrcOver.derive(1 - out));
            g.setColor(Theme.LINE);
            g.fillRect(3, h - 4, w - 4, 3);
            g.setColor(Theme.ACCENT);
            g.fillRect(3, h - 4, (int) Math.round((w - 4) * Math.max(0, Math.min(1, life))), 3);
        } finally {
            g.dispose();
        }
    }
}
