package de.droiddrone.pc.gui;

import de.droiddrone.common.SleepCheck;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** FLEET MANAGEMENT's cards (1.0.52): the badge rules, the texts, unknown shown as unknown, and a card painted. */
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

    @Test
    void aCardPaintsWithAndWithoutData() {
        for (FleetCards.Card c : sample()) {
            BufferedImage img = new BufferedImage(FleetManagementPanel.BODY_WIDTH - 16, FleetManagementPanel.FACE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            FleetManagementPanel.paintFace(g, c, img.getWidth());
            g.dispose();
            int lit = 0;
            for (int y = 0; y < img.getHeight(); y += 2) for (int x = 0; x < img.getWidth(); x += 2) if ((img.getRGB(x, y) >>> 24) != 0) lit++;
            assertTrue(lit > 300, c.name() + ": something drawn");
        }
    }

    @Test
    void thePanelKeepsOneCardPerPhoneAndMarksTheCurrentMode() {
        FleetManagementPanel p = new FleetManagementPanel(new FleetManagementPanel.Actions() {
            public void mode(String phone, int mode, int intervalMin) {
            }

            public void clips(String phone) {
            }

            public void cancel(String phone) {
            }
        });
        p.setCards(sample());
        assertNotNull(p.cardOf("MobileAlpha"));
        javax.swing.JComponent a = p.cardOf("MobileAlpha");
        p.setCards(sample().subList(0, 2));
        assertSame(a, p.cardOf("MobileAlpha"), "kept, not rebuilt every second");
        assertNull(p.cardOf("MobileCharlie"), "gone from the list: gone");
    }
}
