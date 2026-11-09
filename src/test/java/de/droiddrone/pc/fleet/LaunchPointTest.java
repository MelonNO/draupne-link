package de.droiddrone.pc.fleet;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Mercator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The map's LAUNCH for a layout drone is the layout's launch point, not where the drone stands (1.0.52, the pilot):
 * its flight controller's home is there, and the PC's track takes Betaflight's home once armed.
 */
class LaunchPointTest {
    private static final long T0 = 1_000_000;

    private static short[] sticks(int throttle, int arm) {
        return new short[]{1500, 1500, 1500, (short) throttle, (short) arm, 1500, 1000, 1000};
    }

    @Test
    void everyLayoutDroneLaunchedFromTheLaunchPoint() {
        double[] launch = Coordinates.parse(FleetLayout.LAUNCH);
        for (int i = 0; i < FleetLayout.PLACES.size(); i++) {
            double[] at = FleetLayout.place(i, launch[0], launch[1]);
            FlightModel m = new FlightModel(new FlightModel.Setup(), at[0], at[1], at[2], T0);
            m.setLaunch(launch[0], launch[1]);
            // armed where it stands, as the pilot does after CONNECT: its home stays the launch point
            long t = T0;
            for (; t < T0 + 30_000; t += 20) { // the GPS fix first, then the arm switch on, throttle low
                m.sticks(sticks(1000, t < T0 + 25_000 ? 1000 : 1900), 8, t);
                m.step(t, 0.02);
            }
            assertTrue(m.isArmed(), "drone " + (i + 1) + " armed");
            FlightTrack track = new FlightTrack(null);
            for (int k = 0; k < 3; k++) {
                FcTelemetry.Gps gps = new FcTelemetry.Gps(2, 12, 0, 0, (int) Math.round(m.homeDistanceM()), (int) Math.round(m.homeDirectionDeg()),
                        m.latDeg(), m.lonDeg(), 0, 0, 0.9, T0 + k + 1);
                track.sample(gps, true);
            }
            FlightTrack.Point home = track.home();
            assertNotNull(home);
            double off = Mercator.distanceM(home.lat(), home.lon(), launch[0], launch[1]);
            // Betaflight's MSP_COMP_GPS says the way home in whole degrees and metres: the launch can be placed no closer
            // than that lets (half a degree at the drone's distance, half a metre), as with a real drone
            double allowed = m.homeDistanceM() * Math.sin(Math.toRadians(0.5)) + 1.5;
            assertTrue(off <= allowed, "drone " + (i + 1) + ": the map's launch is " + off + " m (allowed " + allowed + ") from " + FleetLayout.LAUNCH
                    + " (" + Coordinates.mgrs(home.lat(), home.lon(), 5) + ")");
            // disarmed and armed again where it is: a new flight, home here (Betaflight sets home at arming)
            for (long u = t; u < t + 2000; u += 20) {
                m.sticks(sticks(1000, 1000), 8, u);
                m.step(u, 0.02);
            }
            t += 2000;
            assertFalse(m.isArmed());
            for (long u = t; u < t + 2000; u += 20) {
                m.sticks(sticks(1000, 1900), 8, u);
                m.step(u, 0.02);
            }
            assertTrue(m.isArmed(), "drone " + (i + 1) + " armed again");
            assertTrue(m.homeDistanceM() < 1, "drone " + (i + 1) + ": a new arming sets home where it is: " + m.homeDistanceM());
        }
    }
}
