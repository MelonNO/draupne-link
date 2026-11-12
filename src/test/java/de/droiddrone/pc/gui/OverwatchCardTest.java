package de.droiddrone.pc.gui;

import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.overwatch.AlertCard;
import de.droiddrone.pc.overwatch.ClipIndex;
import de.droiddrone.pc.overwatch.OverwatchHub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The overwatch alert card in the map window, painted off screen (1.0.50, the pilot: "only a black bar on the side of
 * the map"). The card's size came from ImageObserver.WIDTH (1), not the panel's 340: one pixel wide, the picture's
 * preferred size negative.
 */
class OverwatchCardTest {
    private static final int SKY = 0x6fa6d8, PANEL_H = 700;

    private static BufferedImage preRoll() {
        BufferedImage img = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 180; y++) for (int x = 0; x < 320; x++) img.setRGB(x, y, y < 90 ? SKY : 0x5b7a3c);
        return img;
    }

    private static final long STARTED = System.currentTimeMillis() - 5000;

    private static OverwatchHub.View view(BufferedImage picture, String held) {
        long now = System.currentTimeMillis();
        OverwatchProtocol.Alert a = new OverwatchProtocol.Alert(1, 1, 0, 0, STARTED, 0.47f, 0.38f, 0.06f, 0.2f, 0, 90,
                611429110, 106897877, 0, 91, "MobileX-ray");
        return new OverwatchHub.View("MobileX-ray", AlertCard.Shown.CARD, false, now - 5000, -1, picture, a, null, null, false, held, 8);
    }

    private static BufferedImage paint(OverwatchPanel p) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            forget(p);
            p.setSize(p.getPreferredSize().width, PANEL_H);
            layout(p);
        });
        BufferedImage img = new BufferedImage(p.getWidth(), PANEL_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        SwingUtilities.invokeAndWait(() -> p.printAll(g));
        g.dispose();
        String out = System.getenv("OVERWATCH_CARD_SHOT"); // to look at it: the PNG goes there
        if (out != null) javax.imageio.ImageIO.write(img, "png", new java.io.File(out));
        return img;
    }

    /** Every layout's sizes forgotten first: off screen nothing is ever valid, so a change's revalidate stops at once. */
    private static void forget(Container c) {
        c.invalidate();
        for (Component k : c.getComponents()) if (k instanceof Container cc) forget(cc);
    }

    private static void layout(Container c) {
        forget(c);
        layoutTree(c);
    }

    private static void layoutTree(Container c) {
        c.doLayout();
        for (Component k : c.getComponents()) if (k instanceof Container cc) layoutTree(cc);
    }

    private static int count(BufferedImage img, int rgb) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) & 0xffffff) == rgb) n++;
        return n;
    }

    private static List<String> texts(Container c, List<String> out) {
        for (Component k : c.getComponents()) {
            if (k instanceof JLabel l && k.isVisible()) out.add(l.getText());
            if (k instanceof JButton b && k.isVisible()) out.add(b.getText());
            if (k instanceof Container cc && k.isVisible()) texts(cc, out);
        }
        return out;
    }

    @Test
    void theCardShowsThePicturesBeforeTheAlertTheTimeAndWhyThePreviewWaits(@TempDir Path dir) throws Exception {
        MapView map = new MapView(new TileCache(dir, "test", false));
        OverwatchPanel p = new OverwatchPanel(map, new ClipIndex(dir), null, () -> de.droiddrone.pc.map.Coordinates.Format.MGRS,
                d -> { }, d -> { }, d -> { }, () -> { });
        String why = "MobileZulu was lost armed 7 min ago; a drone here may be flying (until 00:06)";
        SwingUtilities.invokeAndWait(() -> p.setViews(List.of(view(preRoll(), why))));
        assertTrue(p.getPreferredSize().height > 300, "a card, not a bar: " + p.getPreferredSize());
        BufferedImage img = paint(p);
        Component card = p.cardsBox().getComponent(0);
        assertTrue(card.getWidth() >= OverwatchPanel.PANEL_WIDTH - 10, "the card is the panel's width: " + card.getWidth());
        assertTrue(count(img, SKY) > 10_000, "the pre-roll picture is painted: " + count(img, SKY));
        String all = String.join("\n", texts(p, new ArrayList<>()));
        assertTrue(all.contains("MOTION - MobileX-ray"), all);
        assertTrue(all.contains("LIVE PREVIEW PAUSED - " + why), all);
        assertTrue(all.contains("8 pictures before it"), all);
        assertTrue(all.toLowerCase().contains("it's down - resume"), all);
        assertTrue(all.contains("CONNECT") && all.contains("DISMISS"), all);
    }

    @Test
    void notHeldTheCardOffersNoResumeAndWaitsForThePicture(@TempDir Path dir) throws Exception {
        MapView map = new MapView(new TileCache(dir, "test", false));
        OverwatchPanel p = new OverwatchPanel(map, new ClipIndex(dir), null, () -> de.droiddrone.pc.map.Coordinates.Format.MGRS,
                d -> { }, d -> { }, d -> { }, () -> { });
        SwingUtilities.invokeAndWait(() -> p.setViews(List.of(view(null, null))));
        paint(p);
        String all = String.join("\n", texts(p, new ArrayList<>()));
        assertFalse(all.toLowerCase().contains("resume"), all);
        assertFalse(all.contains("PAUSED"), all);
        Component card = p.cardsBox().getComponent(0);
        assertTrue(card.getHeight() > 250, "the picture's room is there while it comes: " + card.getHeight());
    }

    private static OverwatchHub.View viewOf(String drone, long at) {
        OverwatchProtocol.Alert a = new OverwatchProtocol.Alert(1, 1, 0, 0, at, 0.47f, 0.38f, 0.06f, 0.2f, 0, 90,
                611429110, 106897877, 0, 91, drone);
        return new OverwatchHub.View(drone, AlertCard.Shown.CARD, false, at, -1, preRoll(), a, null, null, false, null, 8);
    }

    /** 1.0.64, the pilot: "minimize the picture so only the drone name is listed". */
    @Test
    void aMinimizedCardIsItsNameAlone(@TempDir Path dir) throws Exception {
        MapView map = new MapView(new TileCache(dir, "test", false));
        OverwatchPanel p = new OverwatchPanel(map, new ClipIndex(dir), null, () -> de.droiddrone.pc.map.Coordinates.Format.MGRS,
                d -> { }, d -> { }, d -> { }, () -> { });
        SwingUtilities.invokeAndWait(() -> p.setViews(List.of(view(preRoll(), null))));
        paint(p);
        int full = p.cardsBox().getComponent(0).getHeight();
        SwingUtilities.invokeAndWait(() -> p.minimize("MobileX-ray", true));
        BufferedImage img = paint(p);
        Component card = p.cardsBox().getComponent(0);
        assertTrue(card.getHeight() < 70 && card.getHeight() < full / 4, "a line, not a card: " + card.getHeight() + " (was " + full + ")");
        assertEquals(0, count(img, SKY), "no picture");
        String all = String.join(" | ", texts(p, new ArrayList<>()));
        assertTrue(all.contains("MOTION - MobileX-ray"), all);
        assertFalse(all.contains("CONNECT") || all.contains("ago"), all);
        assertTrue(all.contains("+"), "the way back: " + all);

        SwingUtilities.invokeAndWait(() -> p.setViews(List.of(view(preRoll(), null)))); // the next refresh keeps it so
        paint(p);
        assertTrue(p.cardsBox().getComponent(0).getHeight() < 70);
        SwingUtilities.invokeAndWait(() -> p.minimize("MobileX-ray", false));
        img = paint(p);
        assertEquals(full, p.cardsBox().getComponent(0).getHeight());
        assertTrue(count(img, SKY) > 10_000);

        // 1.0.64: a new alert from the drone opens its minimized card again
        SwingUtilities.invokeAndWait(() -> p.minimize("MobileX-ray", true));
        paint(p);
        OverwatchHub.View old = view(preRoll(), null);
        OverwatchProtocol.Alert again = new OverwatchProtocol.Alert(1, 2, 0, 0, STARTED + 60_000, 0.47f, 0.38f, 0.06f, 0.2f, 0, 90,
                611429110, 106897877, 0, 91, "MobileX-ray");
        OverwatchHub.View next = new OverwatchHub.View(old.drone(), old.shown(), false, old.alertAtWall() + 60_000, -1, old.picture(), again,
                null, null, false, null, 8);
        SwingUtilities.invokeAndWait(() -> p.setViews(List.of(next)));
        paint(p);
        assertEquals(full, p.cardsBox().getComponent(0).getHeight(), "the next alert shows whole");
    }

    /** 1.0.64, the pilot: "it should be possible to scroll in the clips menu" - cards pushed the clip list off the window. */
    @Test
    void cardsAndTheClipListScrollTogether(@TempDir Path dir) throws Exception {
        MapView map = new MapView(new TileCache(dir, "test", false));
        OverwatchPanel p = new OverwatchPanel(map, new ClipIndex(dir), null, () -> de.droiddrone.pc.map.Coordinates.Format.MGRS,
                d -> { }, d -> { }, d -> { }, () -> { });
        long now = System.currentTimeMillis();
        SwingUtilities.invokeAndWait(() -> {
            p.setViews(List.of(viewOf("MobileAlpha", now - 9000), viewOf("MobileBravo", now - 6000), viewOf("MobileCharlie", now - 3000)));
            p.openClips("MobileAlpha");
        });
        paint(p);
        javax.swing.JScrollPane sp = (javax.swing.JScrollPane) p.getComponent(0);
        java.awt.Component column = sp.getViewport().getView();
        assertTrue(column.getHeight() > PANEL_H, "three cards and the list are taller than the window: " + column.getHeight());
        javax.swing.JScrollBar bar = sp.getVerticalScrollBar();
        assertTrue(bar.isVisible() && bar.getMaximum() - bar.getVisibleAmount() > 0, "and they scroll");
        Component library = ((Container) column).getComponent(1);
        assertTrue(library.isVisible() && library.getY() >= PANEL_H - 50, "the list was below the window: " + library.getY());
        SwingUtilities.invokeAndWait(() -> bar.setValue(bar.getMaximum()));
        Rectangle seen = sp.getViewport().getViewRect();
        assertTrue(seen.y + seen.height >= library.getY() + library.getHeight() - 1, "scrolled down, the whole list shows: " + seen + " " + library.getBounds());
        Component card = p.cardsBox().getComponent(0);
        assertTrue(card.getWidth() >= OverwatchPanel.PANEL_WIDTH - 10, "the cards keep their width beside the bar: " + card.getWidth());

        SwingUtilities.invokeAndWait(() -> {
            for (String d : List.of("MobileAlpha", "MobileBravo", "MobileCharlie")) p.minimize(d, true);
        });
        paint(p);
        assertTrue(column.getHeight() <= sp.getViewport().getHeight(), "minimized, it all fits: " + column.getHeight());
    }

    /** 1.0.52: the box follows the thing - the phone's boxes now; the alert's box only on the pictures from the alert. */
    @Test
    void theCardsBoxIsThePhonesBoxNowNotTheAlertsOverTheLivePicture() {
        OverwatchHub.View v = view(preRoll(), null);
        OverwatchProtocol.Alert a = v.alert();
        assertArrayEquals(new float[]{a.x, a.y, a.w, a.h}, OverwatchPanel.cardBox(v), "a pre-roll picture: the alert's box");
        OverwatchHub.View live = new OverwatchHub.View(v.drone(), v.shown(), false, v.alertAtWall(), -1, v.picture(), a, null, null,
                true, null, 8, null);
        assertNull(OverwatchPanel.cardBox(live), "an older phone's live picture: no box rather than where it was at the alert");
        OverwatchHub.View now = new OverwatchHub.View(v.drone(), v.shown(), false, v.alertAtWall(), -1, v.picture(), a, null, null,
                true, null, 8, new float[][]{{0.6f, 0.45f, 0.1f, 0.05f, 0}});
        assertArrayEquals(new float[]{0.6f, 0.45f, 0.1f, 0.05f}, OverwatchPanel.cardBox(now), "the phone's box now");
        OverwatchHub.View gone = new OverwatchHub.View(v.drone(), v.shown(), false, v.alertAtWall(), -1, v.picture(), a, null, null,
                true, null, 8, new float[0][]);
        assertNull(OverwatchPanel.cardBox(gone), "nothing seen now: no box");
        OverwatchHub.View ended = new OverwatchHub.View(v.drone(), v.shown(), true, v.alertAtWall(), -1, v.picture(), a, null, null,
                false, null, 8, null);
        assertNull(OverwatchPanel.cardBox(ended));
    }
}
