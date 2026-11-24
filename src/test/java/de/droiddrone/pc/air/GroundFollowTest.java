package de.droiddrone.pc.air;

import de.droiddrone.pc.air.virt.VirtualAirspace;
import de.droiddrone.pc.air.virt.VirtualCopter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AC-3: the group's base follows the ground under it as it goes - every layer climbs (or comes down) together, slowly
 * enough that each drone keeps up: the layers keep their order, none is ever pushed through another. Not while a drone
 * that doesn't follow it (cut off, without GNSS) holds its height among them.
 */
class GroundFollowTest {
    @Test
    void theLayersClimbTogetherOverRisingGround() {
        VirtualAirspace a = Groups.group(16, 9); // two layers
        a.pilot = Groups.goTo(0, 300, Groups.GROUND + Groups.leadAgl(a.params, 16) + 30);
        a.run(10);
        Map<String, double[]> before = new java.util.HashMap<>();
        for (String id : a.gc.members().keySet()) before.put(id, a.gc.cellPlace(id));
        a.gc.setGroundTarget(Groups.GROUND + 25);
        List<String> ids = new ArrayList<>(a.gc.members().keySet());
        for (int s = 0; s < 600; s++) { // 60 s
            a.step();
            for (String x : ids) {
                for (String y : ids) {
                    GroupController.Member mx = a.gc.members().get(x), my = a.gc.members().get(y);
                    if (mx.place == null || my.place == null || mx.place.layer() >= my.place.layer()) continue;
                    VirtualCopter cx = a.copters.get(x), cy = a.copters.get(y);
                    assertTrue(cy.u - cx.u > 5, "layer order kept: " + y + " over " + x + " by " + (cy.u - cx.u) + " m at " + a.t() + " s");
                }
            }
        }
        for (String id : ids) {
            double[] now = a.gc.cellPlace(id);
            if (now == null || before.get(id) == null) continue;
            assertEquals(before.get(id)[2] + 25, now[2], 0.01, id + "'s layer 25 m higher");
            assertEquals(now[2], a.copters.get(id).u, 3, id + " followed it");
        }
        assertTrue(a.separated(), Groups.describe(a));
    }

    @Test
    void notWhileADroneHoldsItsHeightAmongThem() {
        VirtualAirspace a = Groups.group(8, 4);
        a.pilot = Groups.goTo(0, 100, Groups.GROUND + Groups.leadAgl(a.params, 8));
        a.run(10);
        String cut = a.gc.members().keySet().iterator().next();
        a.setLink(cut, false);
        a.run(3);
        double layer = a.gc.layerAmsl(0);
        a.gc.setGroundTarget(Groups.GROUND + 25);
        a.run(20);
        assertEquals(layer, a.gc.layerAmsl(0), 1e-9, "the base waits while " + cut + " is cut off");
        a.setLink(cut, true);
        a.run(40);
        assertEquals(layer + 25, a.gc.layerAmsl(0), 0.01, "and follows once it is back");
    }
}
