package de.droiddrone.pc.route;

import de.droiddrone.common.MissionItem;
import de.droiddrone.common.group.GeoLocal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet PRD FD-3 / AC-2 - AC-4: DISPATCH planned on made-up ground, places made up. */
class DispatchTest {
    static final TerrainSource FLAT = (lat, lon) -> 100.0;

    @Test
    void eachDroneASlotALayerAndARouteOfItsOwn() throws Exception {
        List<Dispatch.Drone> d = List.of(new Dispatch.Drone("A", 60.000, 10.000, 100), new Dispatch.Drone("B", 60.0005, 10.000, 100),
                new Dispatch.Drone("C", 60.001, 10.000, 100));
        List<Dispatch.Leg> legs = Dispatch.plan(d, 60.02, 10.0, 60, FLAT, RoutePlanner.Limits.ARDUCOPTER_DEFAULT);
        assertEquals(3, legs.size());
        for (int i = 0; i < 3; i++) {
            Dispatch.Leg l = legs.get(i);
            assertEquals(i * 10.0, l.layerM(), 1e-9, "drone " + i + "'s own layer");
            assertEquals(i * Dispatch.STAGGER_S, l.launchAfterS(), 1e-9, "launches one after another");
            assertTrue(l.climbToM() >= 60 + i * 10 - 1e-6, "climbs to its layer first");
            for (MissionItem m : l.mission().items()) {
                if (m.seq > 0 && m.command == MissionItem.NAV_WAYPOINT) assertTrue(m.z >= 100 + 60 + i * 10 - 0.5, "never under its layer (item 0 is ArduPilot's home): " + m);
            }
            for (int j = i + 1; j < 3; j++) {
                assertTrue(GeoLocal.distance(l.slotLat(), l.slotLon(), legs.get(j).slotLat(), legs.get(j).slotLon()) >= Dispatch.SLOT_GAP_M - 0.5,
                        "slots apart");
            }
            assertTrue(GeoLocal.distance(60.02, 10.0, l.slotLat(), l.slotLon()) < 100, "round the target");
        }
        assertEquals(1, Dispatch.slots(60, 10, 1).size());
        assertEquals(0, Dispatch.slots(60, 10, 0).size());
    }

    @Test
    void missingTerrainIsNamedNotGuessed() {
        TerrainSource none = (lat, lon) -> Double.NaN;
        RoutePlanner.MissingTerrain m = assertThrows(RoutePlanner.MissingTerrain.class, () -> Dispatch.plan(
                List.of(new Dispatch.Drone("A", 60, 10, 100)), 60.01, 10, 60, none, RoutePlanner.Limits.ARDUCOPTER_DEFAULT));
        assertFalse(m.nodes().isEmpty());
    }
}
