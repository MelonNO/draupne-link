package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
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
        // F-010 / F-043, the pilot (2026-11-20): too low for a minimap of use - none at all, the rows get its room
        assertFalse(mini.isVisible(), "no squashed minimap: " + mini.getBounds());
        assertEquals(0, mini.getHeight());
        assertTrue(m.height < pop.getHeight() + 40, "the MAP card is POP OUT MAP alone: " + m);
        assertTrue(link.getHeight() > 360, "the LINK card's rows get the room: " + link.getBounds());
        map.setVisible(false); // the map popped out: the whole card goes, the LINK card and the log take its room
        column.doLayout();
        assertTrue(link.getHeight() > 300 && log.getY() > link.getHeight() && log.getY() + log.getHeight() == 560);
    }

    @Test
    void theMinimapShowsWhereThereIsRoomForIt() {
        JPanel rows = new LinkCardLayout.Rows(null);
        rows.setPreferredSize(new Dimension(300, 380)); // the LINK card connected, no warning
        JPanel link = new JPanel(new java.awt.BorderLayout());
        link.add(new JScrollPane(rows));
        JPanel mini = new JPanel();
        JButton pop = new JButton("Pop out map");
        JPanel map = new JPanel(new LinkCardLayout(mini, pop));
        map.add(mini);
        map.add(pop);
        JPanel log = new JPanel();
        JPanel column = new JPanel(new LinkCardLayout.Column(link, map, log));
        column.add(link);
        column.add(map);
        column.add(log);
        column.setSize(340, 1000); // a 1080-high screen
        column.doLayout();
        map.doLayout();
        assertTrue(mini.isVisible() && mini.getHeight() >= LinkCardLayout.MINI_SHOWN_MIN, "the minimap, whole: " + mini.getBounds());
        assertTrue(link.getHeight() >= 380, "the LINK card as tall as its rows: " + link.getBounds());
        column.setSize(340, 560); // the window made low: it goes, and comes back when there is room again
        column.doLayout();
        map.doLayout();
        assertFalse(mini.isVisible());
        assertTrue(link.getHeight() >= 380, "PILOT UAV and the rows whole, not scrolled for a strip of map: " + link.getBounds());
        column.setSize(340, 1000);
        column.doLayout();
        map.doLayout();
        assertTrue(mini.isVisible() && mini.getHeight() >= LinkCardLayout.MINI_SHOWN_MIN);
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

    /**
     * F-075 (the bench's menus-windows, 1.0.71): OSD settings' ELEMENT card cut an element's words after the first line
     * ("Cell voltage with a bar, pack voltage,"). Each fits the card's width now and asks for the height of all its lines.
     */
    @Test
    void theOsdElementsWordsAreWholeInTheirCard() {
        Theme.install();
        Font font = Theme.font(Font.PLAIN, 13f);
        int oneLine = Theme.label("<html>Cell voltage</html>", font, Theme.TEXT).getPreferredSize().height;
        int wrapped = 0;
        for (OsdElement e : OsdElement.values()) {
            JLabel help = OsdSettingsDialog.helpLabel();
            help.setText(OsdSettingsDialog.helpHtml(e));
            Dimension d = help.getPreferredSize();
            assertTrue(d.width <= OsdSettingsDialog.INFO_TEXT_WIDTH, e + " as wide as the card at most: " + d.width);
            int textWidth = help.getFontMetrics(font).stringWidth(e.help);
            if (textWidth > OsdSettingsDialog.INFO_TEXT_WIDTH) {
                wrapped++;
                assertTrue(d.height >= 2 * oneLine, e + ": the height of its wrapped lines, " + d.height + " for a line of " + oneLine);
            } else {
                assertTrue(d.height >= oneLine, e + ": one whole line");
            }
        }
        assertTrue(wrapped > 0, "some words are longer than a line");
        JLabel battery = OsdSettingsDialog.helpLabel();
        battery.setText(OsdSettingsDialog.helpHtml(OsdElement.BATTERY));
        assertTrue(battery.getPreferredSize().height >= 2 * oneLine, "BATTERY's words on two lines: " + battery.getPreferredSize());
        // as the card lays it out: the width it has, the height it asks for - nothing cut
        battery.setSize(battery.getPreferredSize());
        assertEquals(battery.getPreferredSize().height, battery.getHeight());
    }

    /**
     * F-075 (the bench's sleep-small-window at 1093 x 584, 1.0.71): QUEUED: OVERWATCH opened under the sleep buttons,
     * cut in half at the card's edge. The LINK card's rows now scroll it into sight as it opens, the view's top at the
     * top of a row (PHONE), never through one. Laid out off screen as the LINK card's rows are.
     */
    @Test
    void aLineThatOpensAtTheFootOfTheLinkCardIsScrolledIntoSightWhole() {
        Theme.install();
        JPanel rows = new LinkCardLayout.Rows(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridx = 0;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new java.awt.Insets(0, 0, 6, 0);
        JLabel title = Theme.sectionTitle("Pilot mode");
        rows.add(title, c);
        JPanel fields = new JPanel(new java.awt.GridLayout(0, 1, 0, 6));
        JLabel phone = Theme.label("PHONE", Theme.headingFont(11f), Theme.MUTED);
        fields.add(phone);
        fields.add(new javax.swing.JComboBox<>(new String[]{"MobileYankee"}));
        fields.add(Theme.label("CONNECT AS", Theme.headingFont(11f), Theme.MUTED));
        fields.add(new javax.swing.JComboBox<>(new String[]{"Pilot - fly the drone"}));
        rows.add(fields, c);
        JButton pilot = Theme.primaryButton("Pilot UAV");
        rows.add(pilot, c);
        JLabel queued = Theme.label("QUEUED: OVERWATCH (AT CHECK-IN)", Theme.headingFont(11f), Theme.WARN);
        Fold fold = new Fold(queued, false);
        rows.add(fold, c);
        rows.add(Theme.label("CHECK EVERY", Theme.headingFont(11f), Theme.MUTED), c); // the rows go on under it
        JPanel stats = new JPanel(new java.awt.GridLayout(2, 2, 8, 6));
        for (String s : new String[]{"PHONE BATTERY", "-", "FLIGHT CONTROLLER", "-"}) stats.add(Theme.label(s, Theme.headingFont(11f), Theme.MUTED));
        rows.add(stats, c);
        JScrollPane scroll = new JScrollPane(rows, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        javax.swing.JFrame frame = new javax.swing.JFrame(); // never shown
        try {
            frame.setContentPane(scroll);
            fold.setShown(true, false);
            frame.pack();
            Offscreen.layout(frame, 320, 2000);
            Rectangle line = SwingUtilities.convertRectangle(queued.getParent(), queued.getBounds(), rows);
            int chrome = frame.getHeight() - scroll.getHeight();
            Offscreen.layout(frame, 320, chrome + line.y + line.height / 2); // the card ends half way down the line
            javax.swing.JViewport v = scroll.getViewport();
            line = SwingUtilities.convertRectangle(queued.getParent(), queued.getBounds(), rows);
            int y0 = v.getViewPosition().y, h = v.getExtentSize().height;
            assertTrue(line.y + line.height > y0 + h, "the line cut at the card's edge, as on the bench: " + line + " in " + y0 + "+" + h);

            LinkCardLayout.reveal(queued);
            int y = v.getViewPosition().y;
            assertTrue(line.y >= y && line.y + line.height <= y + h, "the line whole in sight: " + line + " in " + y + "+" + h);
            int phoneTop = SwingUtilities.convertPoint(fields, phone.getLocation(), rows).y;
            assertTrue(y == fields.getY() + 0 || y == phoneTop || y == pilot.getY(), "the view's top at a row's top, not through one: " + y
                    + " (rows at " + title.getY() + ", " + fields.getY() + ", " + pilot.getY() + ")");
            LinkCardLayout.reveal(queued);
            assertEquals(y, v.getViewPosition().y, "in sight already: nothing scrolls");
        } finally {
            frame.dispose();
        }
    }

    @Test
    void theViewsTopGoesToTheNextRowsTopWhileTheLineStaysInSight() {
        int[] tops = {0, 30, 60, 120, 200};
        assertEquals(30, LinkCardLayout.revealY(0, 200, 230, 205, 220, tops), "20 px wanted: on to the next row's top");
        assertEquals(0, LinkCardLayout.revealY(0, 230, 230, 205, 220, tops), "in sight: as it is");
        assertEquals(20, LinkCardLayout.revealY(0, 200, 300, 205, 220, new int[]{0, 210}), "no row's top before the line: as little as will do");
        assertEquals(25, LinkCardLayout.revealY(0, 200, 225, 205, 220, tops), "not past the rows' end");
        assertEquals(120, LinkCardLayout.revealY(200, 100, 400, 130, 140, tops), "above the view: its row's top at the top");
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
