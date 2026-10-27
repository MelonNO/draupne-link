package de.droiddrone.pc.gui;

import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.RcMapper;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;

/** Horizontal 1000..2000 bars, one per RC channel. Sticks fill from the center, throttle/AUX from the left. */
class ChannelBars extends JComponent {
    private static final int ROW = 24;
    private static final int LABEL_WIDTH = 78;
    private static final int VALUE_WIDTH = 46;

    private short[] values;
    private int rows = 8;

    ChannelBars() {
        setOpaque(false);
        updateSize();
    }

    /** @param values channel values in AETR+AUX order, or null when nothing is mapped */
    void setValues(short[] values, int rows) {
        this.values = values;
        if (rows != this.rows) {
            this.rows = rows;
            updateSize();
            revalidate();
        }
        repaint();
    }

    private void updateSize() {
        setPreferredSize(new Dimension(360, rows * ROW));
        setMinimumSize(new Dimension(200, rows * ROW));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        int trackX = LABEL_WIDTH;
        int trackW = getWidth() - LABEL_WIDTH - VALUE_WIDTH - 8;
        for (int i = 0; i < rows; i++) {
            int y = i * ROW;
            g.setFont(Theme.headingFont(11.5f));
            g.setColor(Theme.MUTED);
            g.drawString(PcConfig.CHANNEL_NAMES[i].toUpperCase(), 0, y + 16);

            g.setColor(Theme.PANEL_2);
            g.fillRect(trackX, y + 6, trackW, 12);
            int mid = trackX + trackW / 2;
            g.setColor(Theme.LINE);
            g.fillRect(mid - 1, y + 4, 2, 16);

            Integer value = values != null && i < values.length ? (int) values[i] : null;
            if (value != null) {
                int pos = trackX + Math.round((value - RcMapper.MIN_CHANNEL_LEVEL) / 1000f * trackW);
                boolean fromCenter = i < PcConfig.THROTTLE_INDEX;
                int start = fromCenter ? Math.min(mid, pos) : trackX;
                int end = fromCenter ? Math.max(mid, pos) : pos;
                g.setColor(Theme.ACCENT);
                g.fillRect(start, y + 6, Math.max(3, end - start), 12);
            }
            g.setFont(Theme.font(Font.BOLD, 12f));
            g.setColor(value == null ? Theme.LINE : Theme.TEXT);
            String text = value == null ? "----" : value.toString();
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, getWidth() - fm.stringWidth(text), y + 16);
        }
        g.dispose();
    }
}
