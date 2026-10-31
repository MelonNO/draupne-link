package de.droiddrone.pc.gui;

import javax.swing.BorderFactory;
import javax.swing.JMenuItem;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Rows of the SETTINGS menu that say what a click does: a switch row flips a setting where it is (the switch slides,
 * the menu stays open), a window row opens a window of its own (a chevron at its right edge).
 */
final class MenuRows {
    private MenuRows() {
    }

    /** Title in capitals, what it does muted under it. */
    static String html(String title, String detail) {
        return "<html><b>" + title.toUpperCase() + "</b><br><span style='color:" + Theme.MUTED_HEX + "'>" + detail + "</span></html>";
    }

    private static void style(JMenuItem item, int right) {
        item.setFont(Theme.font(Font.PLAIN, 13.5f));
        item.setForeground(Theme.TEXT);
        item.setBackground(Theme.PANEL_2);
        item.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, right));
    }

    /** A row that opens a window: a chevron at its right edge says so. */
    static final class WindowRow extends JMenuItem {
        WindowRow(String title, String detail, Runnable open) {
            super(html(title, detail));
            style(this, 44);
            addActionListener(e -> open.run());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int cx = getWidth() - 22, cy = getHeight() / 2;
                g.setColor(!isEnabled() ? Theme.LINE : isArmed() ? Color.WHITE : Theme.MUTED); // hovered rows turn red
                g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(cx - 4, cy - 7, cx + 3, cy);
                g.drawLine(cx + 3, cy, cx - 4, cy + 7);
            } finally {
                g.dispose();
            }
        }
    }

    /** An on/off setting: a switch at the right edge that slides when clicked; the menu stays open. */
    static final class SwitchRow extends JMenuItem {
        private static final int SW = 40, SH = 20;
        private static final long SLIDE_MS = 160;
        private final Consumer<Boolean> changed;
        private boolean on;
        private float knob; // 0 off .. 1 on, while sliding
        private final Timer slide = new Timer(15, null);
        private long slideStart;

        SwitchRow(String title, String detail, boolean on, Consumer<Boolean> changed) {
            super(html(title, detail));
            this.on = on;
            this.knob = on ? 1 : 0;
            this.changed = changed;
            style(this, 14 + SW + 50); // room for the switch and ON / OFF beside it
            slide.addActionListener(e -> {
                float t = Math.min(1f, (System.currentTimeMillis() - slideStart) / (float) SLIDE_MS);
                float ease = 1 - (1 - t) * (1 - t);
                knob = this.on ? ease : 1 - ease;
                repaint();
                if (t >= 1) slide.stop();
            });
        }

        /** A release flips it here instead of "clicking" the item, which would close the menu. */
        @Override
        protected void processMouseEvent(MouseEvent e) {
            if (e.getID() == MouseEvent.MOUSE_RELEASED && isEnabled() && contains(e.getPoint())) {
                on = !on;
                slideStart = System.currentTimeMillis();
                slide.restart();
                changed.accept(on);
                e.consume();
                return;
            }
            super.processMouseEvent(e);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int x = getWidth() - 14 - SW, y = (getHeight() - SH) / 2;
                Color track = blend(Theme.LINE, Theme.ACCENT, knob);
                g.setColor(track);
                g.fillRoundRect(x, y, SW, SH, SH, SH);
                int d = SH - 6;
                int kx = Math.round(x + 3 + knob * (SW - d - 6));
                g.setColor(blend(Theme.MUTED, Color.WHITE, knob));
                g.fillOval(kx, y + 3, d, d);
                g.setFont(Theme.headingFont(10f));
                g.setColor(knob > 0.5f ? Theme.TEXT : Theme.MUTED);
                String state = on ? "ON" : "OFF";
                g.drawString(state, x - 8 - g.getFontMetrics().stringWidth(state), y + SH / 2 + 4);
            } finally {
                g.dispose();
            }
        }

        private static Color blend(Color a, Color b, float t) {
            return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t), Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                    Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
        }
    }
}
