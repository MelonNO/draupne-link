package de.droiddrone.pc.gui;

import de.droiddrone.pc.ControlLink;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-10, the pilot's modes round: Moon = deep sleep, eye = overwatch, diamond = standby, arrow = piloted, as
 * confirmed; a queued mode an arrow to its sign with how long ago; FLEET's four modes on top (the one it is in grey, a
 * press on it shakes), CLIPS and the check-in list under them.
 */
class ModesRoundTest {
    @Test
    void standbyHasItsOwnSignNotDeepSleepsMoon() {
        // "When entering standby mode from being in pilot mode, the icon on the map jumps to the deep sleep mode"
        assertEquals(MapView.Kind.STANDBY, FleetCards.badge(false, false, false, 0, true, -1, -1, true, false).kind());
        assertEquals(MapView.Kind.STANDBY, FleetCards.badge(false, false, false, 0, false, 1, 5_000, true, false).kind(), "its own word");
        assertEquals(MapView.Kind.ASLEEP, FleetCards.badge(false, false, false, 0, false, 2, 5_000, true, false).kind());
        assertEquals(MapView.Kind.OVERWATCH, FleetCards.badge(false, false, false, 0, false, 3, 5_000, true, false).kind());
        assertEquals(MapView.Kind.ACTIVE, FleetCards.badge(true, false, false, 0, false, -1, -1, true, false).kind(), "flown here: the arrow");
    }

    @Test
    void aQueuedModeLeavesTheSignAsConfirmedAndGoesBesideIt() {
        assertEquals(MapView.Kind.STANDBY, MapView.queuedKind(ControlLink.POWER_STANDBY));
        assertEquals(MapView.Kind.ASLEEP, MapView.queuedKind(ControlLink.POWER_DEEP_SLEEP));
        assertEquals(MapView.Kind.OVERWATCH, MapView.queuedKind(ControlLink.POWER_OVERWATCH));
        assertNull(MapView.queuedKind(ControlLink.POWER_NORMAL));
        assertTrue(FleetCards.showsQueued(new FleetCards.Badge(MapView.Kind.UNKNOWN, "UNKNOWN")));
        assertFalse(FleetCards.showsQueued(new FleetCards.Badge(MapView.Kind.LOST, "LOST")), "a lost drone takes nothing");
        assertFalse(FleetCards.showsQueued(new FleetCards.Badge(MapView.Kind.CRASHED, "CRASHED")));
        assertFalse(FleetCards.showsQueued(new FleetCards.Badge(MapView.Kind.ACTIVE, "CONNECTED")), "this PC's link says it");
        assertEquals("now", FleetCards.queuedAgo(1_000));
        assertEquals("40s", FleetCards.queuedAgo(40_000));
        assertEquals("12m", FleetCards.queuedAgo(12 * 60_000));
        assertEquals("3h", FleetCards.queuedAgo(3 * 3_600_000));
        assertEquals("", FleetCards.queuedAgo(-1));
        assertEquals(MapView.Kind.ASLEEP, card("STANDBY", "DEEP SLEEP", 30_000).queuedKind());
    }

    /** Each sign drawn alone: which pixels it covers. */
    private static boolean[] mask(MapView.Kind k) {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = Theme.smooth(img.createGraphics());
        MapView.paintNetSign(g, k, java.awt.Color.WHITE, 20, 20);
        g.dispose();
        boolean[] m = new boolean[40 * 40];
        for (int i = 0; i < m.length; i++) m[i] = (img.getRGB(i % 40, i / 40) >>> 24) > 128;
        return m;
    }

    @Test
    void everySignHasAShapeOfItsOwn() {
        // "make the icons better differentiated for what they are": the same colour, still told apart by shape
        MapView.Kind[] all = MapView.Kind.values();
        for (int a = 0; a < all.length; a++) {
            for (int b = a + 1; b < all.length; b++) {
                boolean[] x = mask(all[a]), y = mask(all[b]);
                int differ = 0;
                for (int i = 0; i < x.length; i++) if (x[i] != y[i]) differ++;
                assertTrue(differ > 25, all[a] + " and " + all[b] + " differ in " + differ + " px");
            }
        }
    }

    @Test
    void theMapDrawsAnArrowToTheQueuedSign() {
        BufferedImage plain = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB), queued = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = Theme.smooth(plain.createGraphics());
        MapView.paintNetSign(g, MapView.Kind.STANDBY, MapView.STANDBY_COLOR, 15, 20);
        g.dispose();
        g = Theme.smooth(queued.createGraphics());
        MapView.paintNetSign(g, MapView.Kind.STANDBY, MapView.STANDBY_COLOR, 15, 20);
        MapView.paintQueued(g, MapView.Kind.ASLEEP, 15, 20);
        g.dispose();
        int yellow = 0, changedRight = 0;
        for (int y = 0; y < 40; y++) {
            for (int x = 26; x < 80; x++) {
                int q = queued.getRGB(x, y);
                if (q != plain.getRGB(x, y)) changedRight++;
                if ((q >> 16 & 255) > 200 && (q >> 8 & 255) > 150 && (q & 255) < 120) yellow++;
            }
        }
        assertTrue(yellow > 15, "a yellow arrow: " + yellow + " px");
        assertTrue(changedRight > 120, "and the queued mode's sign after it: " + changedRight + " px");
    }

    @Test
    void theSpectatorsMirrorCarriesTheQueuedModeAndItsAge() {
        List<MapView.NetDrone> drones = List.of(
                new MapView.NetDrone("MobileZulu", 59.91, 10.75, "STANDBY", MapView.Kind.STANDBY, false, null, MapView.Kind.OVERWATCH),
                new MapView.NetDrone("MobileYankee", 59.92, 10.76, "IDLE", MapView.Kind.ACTIVE, false, null));
        assertEquals(drones, MirrorCodec.drones(MirrorCodec.drones(drones)));
        List<FleetCards.Card> cards = List.of(card("STANDBY", "OVERWATCH", 95_000), card("ASLEEP", null, -1));
        assertEquals(cards, MirrorCodec.cards(MirrorCodec.cards(cards)));
        // from an older pilot PC (no fields after the lists): read as before, nothing queued beside the signs
        byte[] withNew = MirrorCodec.drones(drones);
        byte[] old = java.util.Arrays.copyOf(withNew, withNew.length - drones.size());
        List<MapView.NetDrone> fromOld = MirrorCodec.drones(old);
        assertEquals(2, fromOld.size());
        assertNull(fromOld.get(0).queued());
        byte[] cardsNew = MirrorCodec.cards(cards);
        List<FleetCards.Card> cardsOld = MirrorCodec.cards(java.util.Arrays.copyOf(cardsNew, cardsNew.length - 8 * cards.size()));
        assertEquals(-1, cardsOld.get(0).queuedAgoMs());
        assertEquals("OVERWATCH", cardsOld.get(0).queued());
    }

    private static FleetCards.Card card(String badge, String queued, long queuedAgo) {
        MapView.Kind k = switch (badge) {
            case "STANDBY" -> MapView.Kind.STANDBY;
            case "ASLEEP" -> MapView.Kind.ASLEEP;
            case "OVERWATCH" -> MapView.Kind.OVERWATCH;
            default -> MapView.Kind.ACTIVE;
        };
        return new FleetCards.Card("MobileZulu", false, k, badge, 3.8, 0, 60, false, -1, 5, 2_000, 3, 1, 9, 0, 0,
                FleetCards.NO_TEMP, FleetCards.NO_TEMP, queued, null, queuedAgo);
    }

    private static AbstractButton find(Container c, String text) {
        for (Component k : c.getComponents()) {
            if (k instanceof AbstractButton b && text.equals(b.getText())) return b;
            if (k instanceof Container inner) {
                AbstractButton f = find(inner, text);
                if (f != null) return f;
            }
        }
        return null;
    }

    /** What FLEET asked for. */
    private static final class Asked implements FleetManagementPanel.Actions {
        final List<String> calls = new ArrayList<>();

        @Override
        public void mode(String phone, int mode, int intervalMin) {
            calls.add("mode " + mode);
        }

        @Override
        public void clips(String phone) {
            calls.add("clips");
        }

        @Override
        public void cancel(String phone) {
            calls.add("cancel");
        }

        @Override
        public void connect(String phone) {
            calls.add("connect");
        }
    }

    @Test
    void fleetHasFourModesOnTopTheCurrentGreyAndAPressOnItDoesNothing() throws Exception {
        Theme.install();
        Asked asked = new Asked();
        FleetManagementPanel p = new FleetManagementPanel(asked);
        javax.swing.SwingUtilities.invokeAndWait(() -> p.setCards(List.of(card("STANDBY", null, -1))));
        AbstractButton piloted = find(p, "PILOTED"), standby = find(p, "STANDBY"), watch = find(p, "OVERWATCH"), sleep = find(p, "DEEP SLEEP");
        assertNotNull(piloted);
        assertNotNull(standby);
        assertNotNull(watch);
        assertNotNull(sleep);
        assertNull(find(p, "CANCEL"), "no CANCEL: the queued mode's button drops it");
        assertNull(find(p, "PILOT UAV"), "PILOTED is the fourth mode");
        // in the pilot's order, along one row
        assertEquals(piloted.getParent(), sleep.getParent());
        Container row = piloted.getParent();
        assertEquals(List.of(piloted, standby, watch, sleep), List.of(row.getComponents()));
        assertEquals(Theme.MUTED, standby.getForeground(), "the mode it is in: grey text");
        assertEquals(Theme.ACCENT, sleep.getForeground(), "the others red");
        javax.swing.SwingUtilities.invokeAndWait(standby::doClick);
        assertTrue(asked.calls.isEmpty(), "STANDBY on a phone in standby: not possible, nothing asked " + asked.calls);
        javax.swing.SwingUtilities.invokeAndWait(sleep::doClick);
        assertEquals(List.of("mode " + ControlLink.POWER_DEEP_SLEEP), asked.calls);
        // queued: yellow, and pressed again it is dropped
        javax.swing.SwingUtilities.invokeAndWait(() -> p.setCards(List.of(card("STANDBY", "DEEP SLEEP", 20_000))));
        assertEquals(Theme.WARN, sleep.getForeground());
        javax.swing.SwingUtilities.invokeAndWait(sleep::doClick);
        assertEquals("cancel", asked.calls.get(asked.calls.size() - 1));
        // the check-in interval is a list, not a button
        assertTrue(java.util.Arrays.stream(sleep.getParent().getParent().getComponents())
                .anyMatch(c -> c instanceof Container k && java.util.Arrays.stream(k.getComponents()).anyMatch(x -> x instanceof javax.swing.JComboBox)),
                "CHECK EVERY as a list beside CLIPS");
    }

    @Test
    void aPressThatCantShakesAndComesToRest() {
        assertEquals(0, NotPossible.offset(0));
        assertEquals(0, NotPossible.offset(NotPossible.MS));
        int most = 0;
        for (long t = 0; t < NotPossible.MS; t += 5) most = Math.max(most, Math.abs(NotPossible.offset(t)));
        assertTrue(most >= 4, "it visibly shakes: " + most + " px");
        assertTrue(Math.abs(NotPossible.offset(NotPossible.MS - 20)) <= 1, "and dies away");
    }
}
