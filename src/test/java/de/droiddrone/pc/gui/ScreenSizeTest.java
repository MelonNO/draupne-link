package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;

import static org.junit.jupiter.api.Assertions.*;

/** 1.0.52, the pilot on a smaller screen: cut-off words, POP OUT MAP gone, windows bigger than the screen. */
class ScreenSizeTest {
    @Test
    void headingsGetTheWidthTheyPaintAtEveryScale() {
        Theme.install();
        for (String s : new String[]{"CHECK EVERY", "COPY", "PHONE", "POP OUT MAP"}) {
            Font f = Theme.headingFont(11f);
            JLabel l = Theme.label(s, f, Theme.MUTED);
            for (double scale : new double[]{1.25, 1.5, 2.0}) {
                double painted = f.getStringBounds(s, new FontRenderContext(AffineTransform.getScaleInstance(scale, scale), true, false)).getWidth();
                assertTrue(l.getPreferredSize().width >= painted, s + " at " + scale + ": " + l.getPreferredSize().width + " < " + painted);
            }
        }
        JButton copy = new JButton("COPY");
        copy.setFont(Theme.headingFont(11f));
        copy.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        assertTrue(copy.getPreferredSize().width >= Math.ceil(Theme.headingFont(11f).getStringBounds("COPY",
                new FontRenderContext(AffineTransform.getScaleInstance(1.5, 1.5), true, false)).getWidth()));
    }

    @Test
    void popOutMapStaysWhenTheRowsDoNotFit() {
        JPanel rows = new LinkCardLayout.Rows(null);
        rows.setPreferredSize(new Dimension(300, 700)); // the sleep row, a warning: taller than a short screen has
        JScrollPane scroll = new JScrollPane(rows);
        JPanel link = new JPanel(new java.awt.BorderLayout());
        link.add(scroll);
        JPanel mini = new JPanel();
        JButton pop = new JButton("Pop out map");
        JPanel map = new JPanel(new LinkCardLayout(mini, pop)); // the MAP card of its own (1.0.52)
        map.add(mini);
        map.add(pop);
        JPanel log = new JPanel();
        JPanel column = new JPanel(new LinkCardLayout.Column(link, map, log));
        column.add(link);
        column.add(map);
        column.add(log);
        column.setSize(340, 560);
        column.doLayout();
        map.doLayout();
        Rectangle m = map.getBounds(), b = pop.getBounds();
        assertTrue(m.y + m.height <= 560 - LinkCardLayout.Column.LOG_MIN, "the MAP card above the log: " + m);
        assertTrue(b.height > 0 && b.y + b.height <= m.height, "POP OUT MAP inside its card: " + b);
        assertTrue(link.getHeight() < 700 && link.getY() == 0, "the rows scroll from the top: " + link.getBounds());
        assertTrue(mini.getHeight() >= LinkCardLayout.MINI_MIN, "the minimap keeps some room: " + mini.getBounds());
        map.setVisible(false); // the map popped out: the whole card goes, the LINK card and the log take its room
        column.doLayout();
        assertTrue(link.getHeight() > 300 && log.getY() > link.getHeight() && log.getY() + log.getHeight() == 560);
    }

    @Test
    void theColumnFollowsTheWindow() {
        assertEquals(LinkCardLayout.Column.LINK_MIN_W, LinkCardLayout.Column.width(1093));
        assertEquals(LinkCardLayout.Column.LINK_MAX_W, LinkCardLayout.Column.width(2560));
        assertEquals(LinkCardLayout.Column.LOG_MIN, LinkCardLayout.Column.logHeight(500));
        assertEquals(LinkCardLayout.Column.LOG_MAX, LinkCardLayout.Column.logHeight(1300));
    }

    @Test
    void theControllerRowWrapsWhenNarrow() {
        JPanel left = new JPanel(), middle = new JPanel(), right = new JPanel();
        left.setPreferredSize(new Dimension(500, 32));
        left.setMinimumSize(new Dimension(300, 32));
        middle.setPreferredSize(new Dimension(300, 40));
        right.setPreferredSize(new Dimension(80, 20));
        JPanel row = new JPanel();
        WrapRowLayout layout = new WrapRowLayout(left, middle, right, 10, 8);
        row.setLayout(layout);
        row.add(left);
        row.add(middle);
        row.add(right);
        row.setSize(1000, 100);
        row.doLayout();
        assertEquals(left.getY(), 4, "one line, centred");
        assertEquals(middle.getX(), 510);
        row.setSize(700, 100);
        row.doLayout();
        assertTrue(middle.getY() >= 32 && middle.getWidth() == 700, "the status lines under the buttons: " + middle.getBounds());
        assertTrue(right.getX() + right.getWidth() <= 700 && left.getWidth() <= 700 - 80 - 10, "nothing runs off the row");
    }

    @Test
    void windowsNeverAskMoreThanTheScreen() {
        Rectangle screen = new Rectangle(0, 0, 1280, 672); // 1920x1080 at 150 %, taskbar off
        assertEquals(new Dimension(960, 560), ScreenFit.least(new Dimension(960, 560), screen));
        assertEquals(new Dimension(1100, 672), ScreenFit.least(new Dimension(1100, 760), screen));
    }

    @Test
    void anHtmlLabelGetsTheHeightOfItsWrappedLines() {
        Theme.install();
        JLabel l = Theme.label("<html>Cell voltage with a bar, pack voltage, mAh used, current and more words.</html>",
                Theme.font(Font.PLAIN, 13f), Theme.TEXT);
        int oneLine = l.getPreferredSize().height;
        l.setSize(120, oneLine);
        assertTrue(l.getPreferredSize().height > oneLine * 2, "laid out narrow, it asks for its lines: " + l.getPreferredSize());
    }
}
