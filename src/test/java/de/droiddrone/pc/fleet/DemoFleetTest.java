package de.droiddrone.pc.fleet;

import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DEMO mode's one fleet (2026-11-10, the pilot: "all the virtual drones with the simulation environments are spawned"):
 * the six with their scenes, a virtual pilot PC flying and the scenario's four run side by side - in the tour each part
 * had a fleet of its own and only one could run - and the scenario goes again without touching the rest.
 */
class DemoFleetTest {
    private Fleet fleet;
    private VirtualPilotPc pilot;

    @AfterEach
    void tearDown() {
        if (pilot != null) pilot.close();
        if (fleet != null) fleet.close();
    }

    @Test
    void everyDemoDroneRunsInOneFleetAndTheScenarioGoesAgainAlone() throws Exception {
        PcConfig config = PcConfig.defaults();
        List<String> log = new ArrayList<>();
        fleet = new Fleet(config, null, log::add);
        fleet.startHeartbeat();
        TourShowcase six = TourShowcase.startIn(fleet);
        pilot = new VirtualPilotPc(fleet, config, "PCBravo", log::add);
        pilot.fly(Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON + 0.001);
        TourScenario four = TourScenario.startIn(fleet, new TourScenario.Listener() { });
        assertEquals(6 + 1 + 4, fleet.drones().size(), "the six, the pilot PC's own drone and the scenario's four");
        assertTrue(fleet.drones().containsAll(six.drones()));
        assertTrue(fleet.drones().containsAll(four.drones()));
        List<String> names = new ArrayList<>();
        for (VirtualDrone d : fleet.drones()) names.add(d.name());
        assertEquals(names.size(), names.stream().distinct().count(), "every drone a name of its own: " + names);

        // SCENARIO AGAIN: the four go, the rest stays, the fleet runs on
        four.close();
        assertEquals(7, fleet.drones().size());
        assertTrue(fleet.drones().containsAll(six.drones()), "the six stay");
        assertNotNull(pilot.drone(), "the pilot PC still flies");
        TourScenario again = TourScenario.startIn(fleet, new TourScenario.Listener() { });
        assertEquals(11, fleet.drones().size(), "four new ones");
        for (VirtualDrone d : again.drones()) assertFalse(four.drones().contains(d));

        // END DEMO: each part goes, then the fleet
        again.close();
        six.close();
        assertEquals(1, fleet.drones().size(), "the pilot PC's drone alone");
    }
}
