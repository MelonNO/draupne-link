package de.droiddrone.pc.air;

import de.droiddrone.pc.air.virt.VirtualAirspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AC-10: a follower that got its own rally point before it left the ground (DISPATCH, a reserve's mark) keeps it in the
 * group's exit plans. Before, every follower's point was laid along a line from where the lead was when the group
 * formed - in the air - and on real ArduPilot SITL a follower's RTL came down under the old lead hovering there.
 */
class OwnRallyTest {
    @Test
    void aFollowerKeepsItsOwnRallyPoint() {
        VirtualAirspace a = Groups.group(4, 3);
        V2 own = new V2(300, 250);
        a.gc.setOutsideRallies(Map.of("F01", own, "SOLO", new V2(-300, -300)));
        assertEquals(0, a.gc.rallies().get("F01").dist(own), 1e-9, "its own point");
        for (Map.Entry<String, V2> e : a.gc.rallies().entrySet()) {
            for (Map.Entry<String, V2> f : a.gc.rallies().entrySet()) {
                if (e.getKey().compareTo(f.getKey()) < 0) assertTrue(e.getValue().dist(f.getValue()) >= 20 - 1e-6, e + " " + f);
            }
        }
        a.run(5);
        assertEquals(own.e(), a.gc.rallies().get("F01").e(), 1e-9, "kept");
    }
}
