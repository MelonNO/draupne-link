package de.droiddrone.pc.air;

import de.droiddrone.pc.air.virt.VirtualAirspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD AC-37 / AC-38: the PC's own network gone (it ticks on and hears nobody - not the PC itself stopped). Every
 * follower holds in its cell; back within the 2 min, the group carries on as it was. Over 2 min: everyone has left
 * along their lanes and nobody is run into when the PC hears them again.
 */
class PcNetworkLossTest {
    private static VirtualAirspace flying() {
        VirtualAirspace a = Groups.group(8, 11);
        a.pilot = Groups.goTo(0, 100, Groups.GROUND + Groups.leadAgl(a.params, 8));
        a.run(20);
        return a;
    }

    private static void links(VirtualAirspace a, boolean up) {
        for (String id : new ArrayList<>(a.copters.keySet())) a.setLink(id, up);
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 45, 100})
    void backWithinTwoMinutesTheGroupCarriesOn(int seconds) {
        VirtualAirspace a = flying();
        links(a, false);
        a.run(seconds);
        links(a, true);
        a.run(60);
        List<String> notInCell = new ArrayList<>();
        for (GroupController.Member m : a.gc.members().values()) if (m.state() != GroupController.State.IN_CELL) notInCell.add(m.id + " " + m.state());
        assertEquals(8, a.gc.members().size(), "all still members: " + a.events);
        assertEquals(List.of(), notInCell, "every follower back in its cell: " + a.events);
        assertTrue(a.events.stream().noneMatch(e -> e.contains("REJOIN") || e.contains("MOVE AWAY")),
                "nobody was left behind or made to rejoin: " + a.events);
        assertTrue(a.separated(), Groups.describe(a));
    }

    /**
     * AC-37 / AC-38: the PC's network back after the 2 min hold - the followers are on their way home by their exit plans
     * and keep going; the PC doesn't route them back into the group (it couldn't: their phones keep to their plans).
     * Before, it made them rejoin like reserves and held transit areas and cells for drones that never came.
     */
    @Test
    void backAfterTheHoldNobodyIsCalledBackByItself() {
        VirtualAirspace a = flying();
        links(a, false);
        a.run(135);
        links(a, true);
        a.run(5);
        assertTrue(a.events.stream().noneMatch(e -> e.contains("REJOIN")), a.events.toString());
        assertTrue(a.events.stream().anyMatch(e -> e.contains("ON ITS WAY HOME")), a.events.toString());
        for (GroupController.Member m : a.gc.members().values()) assertNotEquals(GroupController.State.APPROACH, m.state(), m.id);
    }

    /**
     * AC-41: the PC gone for over 2 min with the lead's home right beside the group (where it hovered, 100 m out on
     * any side): the lead's RTL comes down over its home while the followers on that side fly out along their lanes.
     * Before, the exit plans didn't know where the lead comes down: on the north side 8.4 m sideways, 7.7 m in height.
     * Now its way home is proven with the plans, and a lane under it leaves later.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 45, 90, 135, 180, 225, 270, 315})
    void overTwoMinutesEveryoneLeavesAndKeepsApart(int bearing) {
        VirtualAirspace a = Groups.group(8, 11);
        double e = 100 * Math.sin(Math.toRadians(bearing)), n = 100 * Math.cos(Math.toRadians(bearing));
        a.copters.get("LEAD").rallyE = e; // its home: where it hovers
        a.copters.get("LEAD").rallyN = n;
        a.pilot = Groups.goTo(e, n, Groups.GROUND + Groups.leadAgl(a.params, 8));
        a.run(40);
        links(a, false);
        a.run(160);
        links(a, true);
        a.run(400);
        assertTrue(a.separated(), "lead's home at " + bearing + " deg: " + Groups.describe(a));
    }

    @Test
    void thePcDeadTheLeadsHomeBesideTheGroup() {
        VirtualAirspace a = flying();
        a.pcAlive = false;
        a.run(560);
        assertTrue(a.separated(), Groups.describe(a));
    }
}
