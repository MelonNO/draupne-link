package de.droiddrone.pc.gui;

import de.droiddrone.pc.CalibrationWizard;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * Raw joystick axes during calibration: swept range band, live position, captured center, and which role each
 * axis got. A row of buttons underneath lights up when pressed.
 */
class RawAxesView extends JComponent {
    private static final int ROW = 30;
    private static final int BUTTON_SIZE = 18;
    private static final int BUTTON_GAP = 6;

    private Data snapshot = null;

    /** What to draw, captured each frame from the wizard. */
    record Data(float[] axes, boolean[] buttons, float[] min, float[] max, float[] center,
                    String[] axisRoles, String[] buttonRoles, int highlightAxis, int highlightButton,
                    float highlightProgress, boolean showRange) {}

    RawAxesView() {
        setOpaque(false);
    }

    void setData(Data data) {
        boolean resize = snapshot == null || data == null
                || snapshot.axes().length != data.axes().length || snapshot.buttons().length != data.buttons().length;
        snapshot = data;
        if (resize) {
            int axes = data == null ? 8 : data.axes().length;
            setPreferredSize(new Dimension(560, axes * ROW + 70));
            revalidate();
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Data d = snapshot;
        if (d == null) return;
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        int labelW = 70;
        int roleW = 110;
        int trackX = labelW;
        int trackW = getWidth() - labelW - roleW - 30;
        for (int i = 0; i < d.axes().length; i++) {
            int y = i * ROW;
            boolean highlight = i == d.highlightAxis();
            if (highlight) {
                g.setColor(Theme.alpha(Theme.ACCENT, 40));
                g.fillRect(-2, y + 1, getWidth() + 4, ROW - 2);
            }
            g.setFont(Theme.headingFont(11.5f));
            g.setColor(highlight ? Theme.ACCENT : Theme.MUTED);
            g.drawString("AXIS " + i, 6, y + 20);

            g.setColor(Theme.PANEL_2);
            g.fillRect(trackX, y + 9, trackW, 12);
            if (d.showRange() && d.min()[i] <= d.max()[i]) {
                int a = toX(d.min()[i], trackX, trackW);
                int b = toX(d.max()[i], trackX, trackW);
                g.setColor(Theme.alpha(Theme.ACCENT, 110));
                g.fillRect(a, y + 9, Math.max(3, b - a), 12);
            }
            if (d.center() != null && i < d.center().length) {
                g.setColor(Theme.WARN);
                int c = toX(d.center()[i], trackX, trackW);
                g.fillRect(c - 1, y + 5, 3, 20);
            }
            int pos = toX(d.axes()[i], trackX, trackW);
            g.setColor(Theme.TEXT);
            g.fillRect(pos - 3, y + 5, 6, 20);

            int textX = trackX + trackW + 14;
            if (d.axisRoles()[i] != null) {
                g.setFont(Theme.headingFont(12f));
                g.setColor(Theme.ACCENT);
                g.drawString(d.axisRoles()[i].toUpperCase(), textX, y + 20);
            } else if (d.showRange() && d.max()[i] - d.min()[i] >= CalibrationWizard.FULL_RANGE) {
                paintCheck(g, textX + 4, y + 15);
            }
            if (highlight && d.highlightProgress() > 0) {
                g.setColor(Theme.ACCENT);
                g.fillRect(trackX, y + ROW - 4, Math.round(trackW * d.highlightProgress()), 3);
            }
        }

        int by = d.axes().length * ROW + 18;
        g.setFont(Theme.headingFont(11.5f));
        g.setColor(Theme.MUTED);
        g.drawString("BUTTONS", 6, by + 13);
        int x = labelW;
        for (int i = 0; i < d.buttons().length; i++) {
            if (x + BUTTON_SIZE > getWidth()) {
                x = labelW;
                by += BUTTON_SIZE + BUTTON_GAP;
            }
            boolean pressed = d.buttons()[i];
            boolean assigned = d.buttonRoles()[i] != null;
            g.setColor(pressed ? Theme.ACCENT : Theme.PANEL_2);
            g.fillOval(x, by, BUTTON_SIZE, BUTTON_SIZE);
            if (assigned || i == d.highlightButton()) {
                g.setColor(Theme.ACCENT);
                g.setStroke(new BasicStroke(2f));
                g.drawOval(x - 2, by - 2, BUTTON_SIZE + 4, BUTTON_SIZE + 4);
            }
            g.setFont(Theme.font(Font.PLAIN, 9f));
            g.setColor(pressed ? Theme.BG : Theme.MUTED);
            FontMetrics fm = g.getFontMetrics();
            String n = Integer.toString(i);
            g.drawString(n, x + (BUTTON_SIZE - fm.stringWidth(n)) / 2, by + 13);
            x += BUTTON_SIZE + BUTTON_GAP;
        }
        g.dispose();
    }

    private static int toX(float value, int trackX, int trackW) {
        float v = Math.max(-1f, Math.min(1f, value));
        return trackX + Math.round((v + 1f) / 2f * trackW);
    }

    private static void paintCheck(Graphics2D g, int x, int y) {
        g.setColor(Theme.OK);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(x, y, x + 5, y + 5);
        g.drawLine(x + 5, y + 5, x + 14, y - 5);
    }
}
