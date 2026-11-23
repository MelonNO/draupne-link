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
    void eachLegsReturnHeightClearsTheGroundOnTheWayToItsRallyPointByItsLayer() throws Exception {
        // a 150 m ridge east of the start: not under the route north, but on the straight line to a rally point east
        double[] rally = GeoLocal.offset(60, 10, 300, 0);
        TerrainSource ridge = (lat, lon) -> {
            double e = GeoLocal.east(60, 10, lat, lon);
            return e > 140 && e < 160 ? 250.0 : 100.0;
        };
        List<Dispatch.Drone> d = List.of(new Dispatch.Drone("A", 60, 10, 100), new Dispatch.Drone("B", 60.0005, 10, 100));
        List<Dispatch.Leg> home = Dispatch.plan(d, 60.01, 10.0, 60, ridge, RoutePlanner.Limits.ARDUCOPTER_DEFAULT);
        assertEquals(60, home.get(0).rtlMinM(), 0.01, "home: flat, its route height");
        assertEquals(70, home.get(1).rtlMinM(), 0.01, "the second its layer higher");
        List<Dispatch.Leg> legs = Dispatch.plan(d, 60.01, 10.0, 60, ridge, RoutePlanner.Limits.ARDUCOPTER_DEFAULT,
                java.util.Map.of("A", rally));
        assertEquals(150 + 60, legs.get(0).rtlMinM(), 0.01, "over the ridge on the way to its rally point, by its route height");
        // FD-6 with the mission's own way home: over flat ground the return legs keep the layers apart in the same order
        // as the RTL heights (the RTL goes no lower than the layer its return leg flies)
        List<Dispatch.Leg> flat = Dispatch.plan(d, 60.01, 10.0, 60, FLAT, RoutePlanner.Limits.ARDUCOPTER_DEFAULT);
        for (Dispatch.Leg l : flat) {
            MissionBuilder.Mission m = l.mission();
            for (int i = m.returnSeq(); i <= m.endSeq(); i++) {
                MissionItem it = m.items().get(i);
                if (it.command == MissionItem.NAV_WAYPOINT || it.command == MissionItem.NAV_LOITER_UNLIM) {
                    assertEquals(100 + 60 + l.layerM(), it.z, 0.6, "the way home in its own layer: " + it);
                    assertTrue(l.rtlMinM() >= it.z - 100 - 0.6, "RTL not under its return leg");
                }
            }
        }
        assertEquals(Dispatch.LAYER_M, flat.get(1).rtlMinM() - flat.get(0).rtlMinM(), 0.01);
    }

    @Test
    void missingTerrainIsNamedNotGuessed() {
        TerrainSource none = (lat, lon) -> Double.NaN;
        RoutePlanner.MissingTerrain m = assertThrows(RoutePlanner.MissingTerrain.class, () -> Dispatch.plan(
                List.of(new Dispatch.Drone("A", 60, 10, 100)), 60.01, 10, 60, none, RoutePlanner.Limits.ARDUCOPTER_DEFAULT));
        assertFalse(m.nodes().isEmpty());
    }
}
