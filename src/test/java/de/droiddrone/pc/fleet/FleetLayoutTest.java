package de.droiddrone.pc.fleet;

import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.map.Coordinates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** One layout for the tour's drones and the virtual fleet's (1.0.52, the pilot). */
class FleetLayoutTest {
    private static double metres(double[] a, double[] b) {
        double dn = (a[0] - b[0]) * 111_320.0, de = (a[1] - b[1]) * 111_320.0 * Math.cos(Math.toRadians(a[0]));
        return Math.hypot(dn, de);
    }

    @Test
    void theFleetStartsWhereThePilotPutTheTourDronesLookingTheirWays() throws Exception {
        assertEquals(FleetLayout.LAUNCH, Fleet.DEFAULT_PLACE);
        assertSame(FleetLayout.PLACES, TourScenario.PLACES, "one definition, not two");
        try (Fleet fleet = new Fleet(PcConfig.defaults(), null, s -> { })) {
            for (int i = 0; i < FleetLayout.PLACES.size(); i++) {
                double[] at = fleet.nextPlace(Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON);
                VirtualDrone d = fleet.add(fleet.freeName(), at[0], at[1], at[2]);
                FleetLayout.Place p = FleetLayout.PLACES.get(i);
                double[] want = Coordinates.parse(p.at());
                assertTrue(metres(d.position(), want) < 2, d.name() + " stands at " + p.at() + ": " + metres(d.position(), want) + " m off");
                double look = p.lookAt() != null ? FleetLayout.bearing(want, Coordinates.parse(p.lookAt()))
                        : FleetLayout.bearing(FleetLayout.launch(), want);
                assertEquals(look, d.headingDeg(), 0.5, d.name() + " looks its way");
            }
            // past the four: near the launch point
            double[] extra = fleet.nextPlace(Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON);
            double off = metres(new double[]{extra[0], extra[1]}, FleetLayout.launch());
            assertTrue(off > 5 && off < 25, "a fifth stands by the launch point: " + off + " m");
        }
    }

    @Test
    void startPlaceMovesTheWholeSetKeepingItsShape() {
        double[] home = FleetLayout.launch();
        double[] moved = Coordinates.parse("33W XN 12345 67890");
        for (int i = 0; i < FleetLayout.PLACES.size(); i++) {
            double[] a = FleetLayout.place(i, home[0], home[1]);
            double[] b = FleetLayout.place(i, moved[0], moved[1]);
            double da = metres(a, home), db = metres(b, moved);
            assertEquals(da, db, 1.0, "drone " + i + " keeps its distance from the launch point");
            assertEquals(a[2], b[2], 1e-9, "and its heading");
        }
    }

    @Test
    void lookTurnsTheDroneOnTheGroundOnly() throws Exception {
        try (Fleet fleet = new Fleet(PcConfig.defaults(), null, s -> { })) {
            VirtualDrone d = fleet.add(fleet.freeName(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 10);
            assertTrue(d.lookTo(275));
            assertEquals(275, d.headingDeg(), 1e-9);
            assertTrue(d.lookTo(-90));
            assertEquals(270, d.headingDeg(), 1e-9);
        }
    }
}
