package de.droiddrone.pc.gui;

import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.RcMapper;

import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * Horizontal 1000..2000 bars, one per RC channel. Sticks fill from the center, throttle/AUX from the left. In a scroll
 * pane (the CONTROLLER card's, F-090) it takes the pane's width and its height when the rows fit, else it scrolls.
 */
class ChannelBars extends JComponent implements Scrollable {
    private static final int ROW = 24;
    private static final int LABEL_WIDTH = 78;
    private static final int VALUE_WIDTH = 46;
    /** The least track a row's bar is given room for (F-090). */
    static final int LEAST_TRACK = 40;
    /** A track shorter than this gets no bar at all: the name and the number stay, never a tick over the number. */
    private static final int LEAST_PAINTED_TRACK = 12;

    private short[] values;
    private int rows = 8;
    /** What Drone setup put on each AUX ("ARM", "flight modes"), index 0 = AUX1; null entries are plain AUX. */
    private de.droiddrone.pc.setup.ModeSwitch.Purpose[] purposes = new de.droiddrone.pc.setup.ModeSwitch.Purpose[0];
    private int labelWidth = LABEL_WIDTH;

    ChannelBars() {
        setOpaque(false);
        updateSize();
    }

    /** Names the AUX rows after their purpose ("AUX1 ARM"), so the pilot sees which switch does what. */
    void setPurposes(de.droiddrone.pc.setup.ModeSwitch.Purpose[] purposes) {
        this.purposes = purposes == null ? new de.droiddrone.pc.setup.ModeSwitch.Purpose[0] : purposes;
        int widest = LABEL_WIDTH;
        java.awt.FontMetrics fm = getFontMetrics(Theme.headingFont(11.5f));
        for (int i = 0; i < rows; i++) widest = Math.max(widest, fm.stringWidth(label(i)) + 8);
        labelWidth = widest;
        revalidate(); // F-090: the width the rows need changed with the names
        repaint();
    }

    private String label(int row) {
        String name = PcConfig.CHANNEL_NAMES[row].toUpperCase();
        int aux = row - 4;
        if (aux >= 0 && aux < purposes.length && purposes[aux] != null) return name + " " + purposes[aux].title().toUpperCase();
        return name;
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

    /** The least width at which every row shows its name, a bar of LEAST_TRACK and its number side by side. */
    int leastWidth() {
        return labelWidth + LEAST_TRACK + VALUE_WIDTH + 8;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        int trackX = labelWidth;
        int trackW = getWidth() - labelWidth - VALUE_WIDTH - 8;
        // F-090: a list narrower than its name column and number column together (96 px at 200 %; the names 78 px, 142
        // with Drone setup's AUX names) made the track negative: its start and its middle fell in the number's column,
        // and the bar's 3 px least width drew a red tick over the number there. A track too short for a bar gets none.
        boolean bar = trackW >= LEAST_PAINTED_TRACK;
        for (int i = 0; i < rows; i++) {
            int y = i * ROW;
            Integer value = values != null && i < values.length ? (int) values[i] : null;
            g.setFont(Theme.font(Font.BOLD, 12f));
            String text = value == null ? "----" : value.toString();
            int textX = getWidth() - g.getFontMetrics().stringWidth(text);

            g.setFont(Theme.headingFont(11.5f));
            g.setColor(Theme.MUTED);
            Graphics2D name = (Graphics2D) g.create();
            name.clipRect(0, y, Math.max(0, textX - 4), ROW); // a name never runs into the number
            name.drawString(label(i), 0, y + 16);
            name.dispose();

            if (bar) {
                g.setColor(Theme.PANEL_2);
                g.fillRect(trackX, y + 6, trackW, 12);
                int mid = trackX + trackW / 2;
                g.setColor(Theme.LINE);
                g.fillRect(mid - 1, y + 4, 2, 16);
            }

            if (bar && value != null) {
                int mid = trackX + trackW / 2;
                int pos = trackX + Math.round((value - RcMapper.MIN_CHANNEL_LEVEL) / 1000f * trackW);
                pos = Math.max(trackX, Math.min(trackX + trackW, pos));
                boolean fromCenter = i < PcConfig.THROTTLE_INDEX;
                int start = fromCenter ? Math.min(mid, pos) : trackX;
                int end = fromCenter ? Math.max(mid, pos) : pos;
                g.setColor(Theme.ACCENT);
                g.fillRect(start, y + 6, Math.max(3, end - start), 12);
            }
            g.setFont(Theme.font(Font.BOLD, 12f));
            g.setColor(value == null ? Theme.LINE : Theme.TEXT);
            g.drawString(text, textX, y + 16);
        }
        g.dispose();
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return ROW;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? Math.max(ROW, visible.height / ROW * ROW) : visible.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true; // never sideways: the names, bars and numbers fit the width they are given
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        // all rows in sight: as tall as the pane, as it was beside the sticks; more rows than room: they scroll
        return !(getParent() instanceof JViewport v) || v.getHeight() >= getPreferredSize().height;
    }
}
