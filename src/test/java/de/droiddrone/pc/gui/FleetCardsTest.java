package de.droiddrone.pc.gui;

import de.droiddrone.common.SleepCheck;
import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.Elevation;
import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * FLEET MANAGEMENT (1.0.52): the badge rules, the texts, unknown shown as unknown, a row painted, and ten phones in the
 * map window without scrolling on small screens (the pilot: "it must be possible to view 10 phones without scrolling").
 */
class FleetCardsTest {
    @Test
    void theBadgeFollowsTheMapsRules() {
        assertEquals("CONNECTED", FleetCards.badge(true, true, false, 0, false, 2, 0, true, false).text(), "the link first");
        assertEquals(MapView.Kind.OVERWATCH, FleetCards.badge(false, true, true, -5, false, -1, -1, false, false).kind());
        assertEquals("UNKNOWN", FleetCards.badge(false, true, false, 1, false, -1, -1, false, false).text(), "a missed check-in");
        assertEquals("STANDBY", FleetCards.badge(false, false, false, 0, true, -1, -1, true, false).text());
        assertEquals("ASLEEP", FleetCards.badge(false, false, false, 0, false, 2, 10_000, true, false).text(), "its own word, fresh");
        assertEquals("ACTIVE", FleetCards.badge(false, false, false, 0, false, 2, 60_000, true, false).text(), "an old word doesn't count");
        assertEquals(MapView.Kind.LOST, FleetCards.badge(false, false, false, 0, false, -1, -1, false, true).kind());
        assertEquals(MapView.Kind.UNKNOWN, FleetCards.badge(false, false, false, 0, false, -1, -1, false, false).kind());
    }

    @Test
    void textsAndUnknowns() {
        assertEquals("0:20 ago", FleetCards.ago(20_000));
        assertEquals("12 min ago", FleetCards.ago(12 * 60_000));
        assertEquals("-", FleetCards.ago(-1));
        assertEquals("1:40", FleetCards.countdown(100_000));
        assertEquals("LISTEN", FleetCards.countdown(FleetCards.LISTENING));
        assertEquals("-", FleetCards.countdown(-1));
        assertEquals(0.5, FleetCards.ringFill(60_000, 2), 1e-9);
        assertEquals(1, FleetCards.ringFill(FleetCards.LISTENING, SleepCheck.ALWAYS), 1e-9);
        assertEquals(0, FleetCards.ringFill(-1, 5), 1e-9);
        assertEquals("31°C", FleetCards.tempDeci(312));
        assertEquals("-", FleetCards.tempDeci(FleetCards.NO_TEMP));
        assertEquals("-", FleetCards.cell(Double.NaN));
        assertEquals("3.82 V", FleetCards.cell(3.82));
        assertTrue(Double.isNaN(FleetCards.cellFill(Double.NaN)));
        assertEquals("12 SAT FIX", FleetCards.gps(1, 12));
        assertEquals("-", FleetCards.gps(-1, -1));
        assertEquals("20s", FleetManagementPanel.heard(20_000));
        assertEquals("12m", FleetManagementPanel.heard(12 * 60_000));
        assertEquals("-", FleetManagementPanel.heard(-1));
        assertEquals("SLEEP", FleetManagementPanel.shortMode("DEEP SLEEP"));
    }

    static List<FleetCards.Card> sample() {
        long now = System.currentTimeMillis();
        return List.of(
                new FleetCards.Card("MobileAlpha", false, MapView.Kind.OVERWATCH, "OVERWATCH", 3.82, 12 * 60_000, 88, false, 100_000, 5,
                        20_000, 3, 1, 14, 3, now - 3_600_000, 312, FleetCards.NO_TEMP, "DEEP SLEEP", null),
                new FleetCards.Card("MobileBravo", true, MapView.Kind.ASLEEP, "ASLEEP", Double.NaN, -1, 41, true, FleetCards.LISTENING,
                        SleepCheck.ALWAYS, 45_000, 1, 0, 4, 0, 0, 280, 47, null, null),
                new FleetCards.Card("MobileCharlie", false, MapView.Kind.UNKNOWN, "UNKNOWN", Double.NaN, -1, -1, false, -1, 0, -1,
                        -1, -1, -1, 0, 0, FleetCards.NO_TEMP, FleetCards.NO_TEMP, null, "Needs phone app pc.40"));
    }

    /** Ten phones, every kind of row: queued, virtual, lost, unknown, missing data. */
    static List<FleetCards.Card> ten() {
        List<FleetCards.Card> cards = new ArrayList<>(sample());
        String[] names = {"MobileDelta", "MobileEcho", "MobileFoxtrot", "MobileGolf", "MobileHotel", "MobileIndia", "MobileJuliett"};
        MapView.Kind[] kinds = {MapView.Kind.ACTIVE, MapView.Kind.ASLEEP, MapView.Kind.LOST, MapView.Kind.OVERWATCH,
                MapView.Kind.ASLEEP, MapView.Kind.ACTIVE, MapView.Kind.ASLEEP};
        String[] badges = {"ACTIVE", "STANDBY", "LOST", "OVERWATCH", "ASLEEP", "CONNECTED", "ASLEEP"};
        for (int i = 0; i < names.length; i++) {
            cards.add(new FleetCards.Card(names[i], false, kinds[i], badges[i], i == 2 ? Double.NaN : 3.5 + i * 0.1, 30_000, 15 + i * 12,
                    false, i == 2 ? -1 : 20_000 + i * 40_000, 2, i == 2 ? 40 * 60_000 : 5_000 + i * 9_000, i % 5, 1, 9 + i, i, 0,
                    250 + i * 10, FleetCards.NO_TEMP, i == 4 ? "OVERWATCH" : null, null));
        }
        return cards;
    }

    private static FleetManagementPanel panel() {
        return new FleetManagementPanel(new FleetManagementPanel.Actions() {
            public void mode(String phone, int mode, int intervalMin) {
            }

            public void clips(String phone) {
            }

            public void cancel(String phone) {
            }
        });
    }

    @Test
    void aRowAndTheDetailPaintWithAndWithoutData() {
        for (FleetCards.Card c : sample()) {
            for (int h : new int[]{FleetManagementPanel.ROW, FleetManagementPanel.MIN_ROW}) {
                BufferedImage img = new BufferedImage(FleetManagementPanel.BODY_WIDTH - 16, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                FleetManagementPanel.paintRow(g, c, img.getWidth(), h, false);
                g.dispose();
                assertEquals(0xff, img.getRGB(img.getWidth() - 1, 1) >>> 24, c.name() + ": the row is filled");
            }
            BufferedImage img = new BufferedImage(FleetManagementPanel.BODY_WIDTH - 16, 40, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            FleetManagementPanel.paintDetail(g, c, img.getWidth());
            g.dispose();
            int lit = 0;
            for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) >>> 24) != 0) lit++;
            assertTrue(lit > 200, c.name() + ": details drawn");
        }
    }

    @Test
    void thePanelKeepsOneRowPerPhoneAndOneSelection() {
        FleetManagementPanel p = panel();
        p.setCards(sample());
        assertNotNull(p.cardOf("MobileAlpha"));
        assertEquals("MobileAlpha", p.selected(), "the first is selected");
        javax.swing.JComponent a = p.cardOf("MobileAlpha");
        p.select("MobileCharlie");
        p.setCards(sample().subList(0, 2));
        assertSame(a, p.cardOf("MobileAlpha"), "kept, not rebuilt every second");
        assertNull(p.cardOf("MobileCharlie"), "gone from the list: gone");
        assertEquals("MobileAlpha", p.selected(), "a selected phone that is gone: the first again");
    }

    /** 1.0.52, the pilot: a click on a row picks the drone on the map; PILOT UAV flies it. */
    @Test
    void aPickedRowShowsItsDroneAndPilotUavFliesIt(@TempDir Path dir) {
        java.util.List<String> picked = new java.util.ArrayList<>(), flown = new java.util.ArrayList<>();
        FleetManagementPanel p = new FleetManagementPanel(new FleetManagementPanel.Actions() {
            public void mode(String phone, int mode, int intervalMin) {
            }

            public void clips(String phone) {
            }

            public void cancel(String phone) {
            }

            public void picked(String phone) {
                picked.add(phone);
            }

            public void connect(String phone) {
                flown.add(phone);
            }
        });
        p.setCards(sample());
        javax.swing.JComponent row = p.cardOf("MobileBravo");
        row.dispatchEvent(new java.awt.event.MouseEvent(row, java.awt.event.MouseEvent.MOUSE_PRESSED, 0, 0, 5, 5, 1, false));
        assertEquals(java.util.List.of("MobileBravo"), picked);
        assertEquals("MobileBravo", p.selected());
        javax.swing.JButton fly = findButton(p, "PILOT UAV");
        assertNotNull(fly, "PILOT UAV in the strip");
        fly.doClick();
        assertEquals(java.util.List.of("MobileBravo"), flown);

        MapView map = new MapView(new TileCache(dir.resolve("tiles"), "test", false));
        map.setNetDrones(java.util.List.of(new MapView.NetDrone("MobileBravo", 60.5, 10.5, "ASLEEP", MapView.Kind.ASLEEP, false, "")), (n, w) -> {});
        assertArrayEquals(new double[]{60.5, 10.5}, map.positionOf("MobileBravo"), 1e-9);
        assertNull(map.positionOf("MobileZulu"), "no place for a drone the map hasn't seen");
        map.highlight("MobileBravo");
        assertEquals("MobileBravo", map.highlighted());
        map.setSize(600, 400);
        BufferedImage img = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        map.paint(g); // the rings and the name over the map, without throwing
        g.dispose();
    }

    private static javax.swing.JButton findButton(Container c, String text) {
        for (Component k : c.getComponents()) {
            if (k instanceof javax.swing.JButton b && text.equals(b.getText())) return b;
            if (k instanceof Container ct) {
                javax.swing.JButton b = findButton(ct, text);
                if (b != null) return b;
            }
        }
        return null;
    }

    private static void layout(Container c) {
        c.doLayout();
        for (Component k : c.getComponents()) if (k instanceof Container ct) layout(ct);
    }

    /** The map window at the small screens ScreenFit and ScaleSafeUI were checked at: 10 rows, no scrollbar. */
    @Test
    void tenPhonesFitWithoutScrollingOnSmallScreens(@TempDir Path dir) throws Exception {
        Theme.install();
        javax.swing.JFrame owner = new javax.swing.JFrame(); // never shown
        MapWindow w = new MapWindow(owner, new TileCache(dir.resolve("tiles"), "test", false), new Elevation("test", false),
                new Pins(dir.resolve("pins.txt")), new MapGraphics(dir.resolve("graphics.kml")), TileSource.KARTVERKET_TOPO,
                Coordinates.Format.MGRS, null, m -> {}, s -> {}, f -> {}, () -> {});
        try {
            java.lang.reflect.Field f = MapWindow.class.getDeclaredField("fleetPanel");
            f.setAccessible(true);
            FleetManagementPanel p = (FleetManagementPanel) f.get(w);
            p.setCards(ten());
            p.setOpen(true, false);
            for (int[] size : new int[][]{{1366, 728}, {1280, 680}, {1093, 584}}) {
                w.setSize(size[0], size[1]);
                for (int i = 0; i < 3; i++) layout(w); // the viewport's height first, then the rows by it
                Rectangle pb = javax.swing.SwingUtilities.convertRectangle(p.getParent(), p.getBounds(), w.getRootPane());
                assertTrue(pb.x + pb.width <= size[0], size[0] + "x" + size[1] + ": the panel and its tab inside the window: " + pb);
                javax.swing.JScrollPane s = p.listScroll();
                assertFalse(s.getVerticalScrollBar().isVisible(), size[0] + "x" + size[1] + ": no scrollbar, viewport "
                        + s.getViewport().getExtentSize());
                Rectangle view = new Rectangle(s.getViewport().getExtentSize());
                for (FleetCards.Card c : ten()) {
                    Rectangle r = p.cardOf(c.name()).getBounds();
                    assertTrue(r.height >= FleetManagementPanel.MIN_ROW && view.contains(r), size[0] + "x" + size[1] + ": "
                            + c.name() + " in sight: " + r + " in " + view);
                }
                if (System.getenv("FLEET_SHOTS") != null) {
                    BufferedImage img = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = img.createGraphics();
                    w.getRootPane().paint(g);
                    g.dispose();
                    java.nio.file.Files.createDirectories(Path.of(System.getenv("FLEET_SHOTS")));
                    javax.imageio.ImageIO.write(img, "png", Path.of(System.getenv("FLEET_SHOTS"), "fleet-" + size[0] + "x" + size[1] + ".png").toFile());
                }
            }
        } finally {
            w.dispose();
            owner.dispose();
        }
    }
}
