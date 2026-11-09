package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mirror's window sections (1.0.56): the FLEET cards, the map's drones and the observed marks survive the way. */
class MirrorCodecTest {
    @Test
    void theFleetListComesBackAsThePilotPcHasIt() {
        List<FleetCards.Card> cards = List.of(
                new FleetCards.Card("MobileZulu", true, MapView.Kind.OVERWATCH, "OVERWATCH", 3.74, 120_000, 63, false, 45_000, 2,
                        3_000, 3, 1, 9, 2, 1_700_000_000_000L, 85, FleetCards.NO_TEMP, "DEEP SLEEP", null),
                new FleetCards.Card("MobileYankee", false, MapView.Kind.ACTIVE, "CONNECTED", Double.NaN, -1, -1, true, -1, 0,
                        -1, -1, -1, -1, 0, 0, FleetCards.NO_TEMP, 41, null, "Connected: use the LINK card"));
        assertEquals(cards, MirrorCodec.cards(MirrorCodec.cards(cards)));
        assertEquals(List.of(), MirrorCodec.cards(MirrorCodec.cards((List<FleetCards.Card>) null)));
        assertNull(MirrorCodec.cards(new byte[]{0, 5, 1}), "broken bytes read as nothing");
    }

    @Test
    void theMapsDronesComeBackAsThePilotPcDrawsThem() {
        List<MapView.NetDrone> drones = List.of(
                new MapView.NetDrone("MobileZulu", 59.91, 10.75, "OVERWATCH - LISTENING", MapView.Kind.OVERWATCH, false, null),
                new MapView.NetDrone("MobileX-ray", 59.92, 10.76, "FLOWN BY PilotPC", MapView.Kind.ACTIVE, true, "PilotPC"));
        assertEquals(drones, MirrorCodec.drones(MirrorCodec.drones(drones)));
    }

    @Test
    void theObservedMarksComeBackWithTheirCones() {
        List<MapView.Observed> marks = List.of(new MapView.Observed(1, new Color(0x4fc3f7), 59.9, 10.7, 45, true,
                List.of(new double[]{59.91, 10.71}, new double[]{59.92, 10.72}), false, true, true));
        List<MapView.Observed> back = MirrorCodec.observed(MirrorCodec.observed(marks));
        assertEquals(1, back.size());
        MapView.Observed o = back.get(0);
        assertEquals(1, o.tile());
        assertEquals(new Color(0x4fc3f7), o.colour());
        assertEquals(45, o.headingDeg());
        assertTrue(o.approximate() && o.selected() && o.motion());
        assertArrayEquals(new double[]{59.92, 10.72}, o.cone().get(1));
    }
}
