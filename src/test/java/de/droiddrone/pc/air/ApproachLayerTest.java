package de.droiddrone.pc.air;

import de.droiddrone.pc.air.virt.VirtualAirspace;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AC-5 / AC-13: the reserve's approach layer the group flies is the one GROUP DESIGN checks against the ceiling. Before,
 * the designer counted it a layer spacing lower than the group flew it: a design "at the ceiling" sent reserves over it.
 */
class ApproachLayerTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 8, 9, 16, 20})
    void theDesignerAndTheGroupAgreeOnTheApproachLayer(int followers) {
        VirtualAirspace a = Groups.group(followers, 5);
        AirParams p = a.params;
        assertEquals(GroupDesign.topM(p, followers, 1), a.gc.nextApproachAlt() - Groups.GROUND, 1e-9);
        assertEquals(GroupDesign.topM(p, followers, 0) + 2 * p.layerM(), GroupDesign.topM(p, followers, 1), 1e-9);
        assertEquals(GroupDesign.topM(p, followers, 1) + p.layerM(), GroupDesign.topM(p, followers, 2), 1e-9, "one more each");
    }
}
