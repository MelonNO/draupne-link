package de.droiddrone.pc.gui;

import de.droiddrone.pc.setup.ModeSwitch.Purpose;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-090 (the bench's looks at 200 % text scaling, 1920 x 1080: a 960 x 564 screen): the CONTROLLER card open, each stick
 * was the red dot in a small square - no circle, no travel - and the channel list was cut, THROTTLE half there, the AUX
 * rows gone, each bar a red tick over its number. At 1280 x 752 and up it was whole, and stays as it was.
 */
class ControllerSticksTest {
    /** The card's lower part at 960 x 564: the card's inside width there, the least height F-010 leaves the sticks. */
    private static final int SMALL_W = 554, SMALL_H = ControllerSticks.STICKS_MIN_HEIGHT;
    /** And at 1280 x 752: the card's inside width, the sticks' own height. */
    private static final int NORMAL_W = 874, NORMAL_H = ControllerSticks.STICKS_HEIGHT;
    private static final short[] CHANNELS = {1500, 1620, 1380, 1000, 2000, 1000, 1500, 1000};

    private static Purpose[] benchPurposes() { // Drone setup's names on the AUX rows: wider labels
        return new Purpose[]{new Purpose("ARM", ""), new Purpose("flight modes", ""), new Purpose("GPS rescue", ""), new Purpose("PREARM", "")};
    }

    private record Part(ControllerSticks sticks, GimbalView left, GimbalView right, ChannelBars bars) {
    }

    private static Part part(Purpose[] purposes) {
        Theme.install();
        GimbalView left = new GimbalView("Throttle / Yaw", "YAW", "THR"), right = new GimbalView("Pitch / Roll", "ROLL", "PIT");
        ChannelBars bars = new ChannelBars();
        if (purposes != null) bars.setPurposes(purposes);
        bars.setValues(CHANNELS, 8);
        left.setValues((int) CHANNELS[2], (int) CHANNELS[3]);
        right.setValues((int) CHANNELS[0], (int) CHANNELS[1]);
        return new Part(new ControllerSticks(left, right, bars), left, right, bars);
    }

    private static void layOut(Component c, int width, int height) {
        c.setSize(width, height);
        for (int i = 0; i < 3; i++) layOutTree(c);
    }

    private static void layOutTree(Component c) {
        if (!(c instanceof Container k)) return;
        k.doLayout();
        for (Component x : k.getComponents()) layOutTree(x);
    }

    private static BufferedImage paint(Component c) {
        BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Theme.PANEL);
        g.fillRect(0, 0, c.getWidth(), c.getHeight());
        c.paint(g);
        g.dispose();
        return img;
    }

    @Test
    void atTwoHundredPercentTheSticksKeepTheirCircleAndEveryChannelRowItsBar() {
        // the bench's size, and a little either way of it
        int[][] sizes = {{SMALL_W, SMALL_H}, {540, SMALL_H}, {600, SMALL_H}, {SMALL_W, 100}, {SMALL_W, 120}};
        for (int[] size : sizes) for (Purpose[] purposes : new Purpose[][]{null, benchPurposes()}) {
            Part p = part(purposes);
            String at = size[0] + " x " + size[1] + (purposes == null ? ", plain AUX names" : ", Drone setup's AUX names");
            layOut(p.sticks(), size[0], size[1]);

            // the sticks: a box with room for the circle and the dot's travel, the words whole
            for (GimbalView v : new GimbalView[]{p.left(), p.right()}) {
                GimbalView.Face f = v.face();
                assertTrue(f.size() >= 56, at + ": a stick box, not a dot in a square: " + f + " in " + v.getSize());
                assertTrue(f.reach() >= 18 && f.reach() > f.dot(), at + ": the dot travels well past its own size: " + f);
                assertTrue(f.x0() >= 0 && f.y0() >= 0 && f.x0() + f.size() <= v.getWidth() && f.y0() + f.size() <= v.getHeight(),
                        at + ": the box inside its view: " + f + " in " + v.getSize());
                assertTrue(v.getFontMetrics(Theme.headingFont(13f)).stringWidth("THROTTLE / YAW") <= v.getWidth(), at + ": the name fits");
            }
            GimbalView.Face f = p.left().face();
            assertTrue(size[1] > SMALL_H || f.small(), at + ": at the bench's height the values beside the box: " + f);

            // the channel list: the width every row needs beside the sticks, the rows that do not fit scroll
            JScrollPane list = (JScrollPane) p.sticks().channelList();
            ChannelBars bars = p.bars();
            assertEquals(size[0], list.getX() + list.getWidth(), at + ": the list to the card's edge");
            assertTrue(bars.getWidth() >= bars.leastWidth(), at + ": each row's name, bar and number fit: " + bars.getWidth() + " < " + bars.leastWidth());
            assertTrue(bars.getHeight() >= 8 * 24, at + ": all 8 rows, the AUX rows too: " + bars.getHeight());
            assertTrue(list.getVerticalScrollBar().isVisible() && list.getViewport().getHeight() < bars.getHeight(),
                    at + ": what does not fit is reached by scrolling");
            assertTrue(bars.getWidth() <= list.getViewport().getWidth(), at + ": the numbers left of the scroll bar, not under it");
            assertBarsBesideTheirNumbers(bars, at, true);
        }
    }

    /** No bar pixel at or right of its number's left edge in any row; with room for bars, each row has one. */
    private static void assertBarsBesideTheirNumbers(ChannelBars bars, String at, boolean barsShown) {
        BufferedImage img = paint(bars);
        int accent = Theme.ACCENT.getRGB() & 0xffffff;
        java.awt.FontMetrics fm = bars.getFontMetrics(Theme.font(Font.BOLD, 12f));
        for (int row = 0; row < 8; row++) {
            int numberX = bars.getWidth() - fm.stringWidth(Short.toString(CHANNELS[row]));
            int rightmost = -1;
            for (int y = row * 24 + 6; y < row * 24 + 18; y++) {
                for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) & 0xffffff) == accent) rightmost = Math.max(rightmost, x);
            }
            assertTrue(rightmost < numberX - 2, at + ", row " + row + ": the bar ends at " + rightmost + ", over its number from " + numberX);
            if (barsShown) assertTrue(rightmost >= 0, at + ", row " + row + ": a bar");
        }
    }

    /** The cause of the ticks: a list narrower than its names and numbers drew a 3 px bar over the number. */
    @Test
    void aListTooNarrowForBarsDrawsNoneOverTheNumbers() {
        Theme.install();
        ChannelBars bars = new ChannelBars();
        bars.setPurposes(benchPurposes());
        bars.setValues(CHANNELS, 8);
        bars.setSize(96, 8 * 24); // as wide as the bench's list was at 960 x 564, beside 440 px of sticks
        assertBarsBesideTheirNumbers(bars, "96 px wide", false);
    }

    @Test
    void atTheCardsOwnSizeTheSticksAndTheListAreLaidOutAndPaintedAsBefore() {
        for (int width : new int[]{NORMAL_W, 700, 1200, 1700}) {
            Part p = part(null);
            layOut(p.sticks(), width, NORMAL_H);
            // as the card's BorderLayout(18, 12) laid them out before: the sticks WEST 440 wide, the bars CENTER
            assertEquals(new Rectangle(0, 0, 440, NORMAL_H), p.sticks().gimbals().getBounds(), "at " + width);
            assertEquals(new Rectangle(0, 0, 211, NORMAL_H), p.left().getBounds());
            assertEquals(new Rectangle(229, 0, 211, NORMAL_H), p.right().getBounds());
            Rectangle bars = SwingUtilities.convertRectangle(p.bars().getParent(), p.bars().getBounds(), p.sticks());
            assertEquals(new Rectangle(458, 0, width - 458, NORMAL_H), bars, "at " + width);
            assertFalse(((JScrollPane) p.sticks().channelList()).getVerticalScrollBar().isVisible(), "nothing to scroll at " + width);
            // the stick boxes as big as ever, the words under them
            GimbalView.Face f = p.left().face();
            int size = Math.min(211, NORMAL_H - 46) - 4;
            assertEquals(new GimbalView.Face((211 - size) / 2, 2, size, size / 2f - 16, 10, 16, false), f);

            // pixel for pixel the old arrangement of the same parts
            Part q = part(null);
            JPanel old = new JPanel(new BorderLayout(18, 12));
            old.setOpaque(false);
            JPanel gimbals = new JPanel(new GridLayout(1, 2, 18, 0));
            gimbals.setOpaque(false);
            gimbals.add(q.left());
            gimbals.add(q.right());
            gimbals.setPreferredSize(new Dimension(440, 262));
            old.add(gimbals, BorderLayout.WEST);
            old.add(q.bars(), BorderLayout.CENTER);
            layOut(old, width, NORMAL_H);
            assertEquals(old.getPreferredSize(), p.sticks().getPreferredSize(), "the card asks for the same room");
            BufferedImage now = paint(p.sticks()), before = paint(old);
            int different = 0;
            for (int y = 0; y < NORMAL_H; y++) for (int x = 0; x < width; x++) if (now.getRGB(x, y) != before.getRGB(x, y)) different++;
            assertEquals(0, different, "pixels unlike the old layout at " + width + " x " + NORMAL_H);
        }
    }
}
