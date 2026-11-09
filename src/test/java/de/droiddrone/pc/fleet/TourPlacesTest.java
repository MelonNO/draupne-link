package de.droiddrone.pc.fleet;

import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.FlightTrack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pilot (1.0.52): "the landing spot is not following the grid you were told to put them in". Each tour drone's
 * REPORTED position (its name reply, what the map draws, read back as MGRS) is the pilot's grid, and each flight
 * starts at the launch point and lands on that grid.
 */
class TourPlacesTest {
    private TourScenario scenario;
    private DatagramSocket pc;

    @AfterEach
    void tearDown() {
        if (scenario != null) scenario.close();
        if (pc != null) pc.close();
    }

    private static double metres(double[] a, double[] b) {
        return Math.hypot((a[0] - b[0]) * 111_320, (a[1] - b[1]) * 111_320 * Math.cos(Math.toRadians(a[0])));
    }

    @Test
    void eachDroneSaysItIsOnItsGridAndItsFlightLandsThere() throws Exception {
        PcConfig config = PcConfig.defaults();
        scenario = TourScenario.start(config, null, m -> { }, null);
        pc = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        pc.setSoTimeout(200);
        double[] launch = Coordinates.parse(FleetLayout.LAUNCH);
        for (int i = 0; i < 4; i++) {
            VirtualDrone d = scenario.drones().get(i);
            String grid = FleetLayout.PLACES.get(i).at();
            double[] want = Coordinates.parse(grid);
            byte[] q = Names.query(config.getKey());
            Names.Reply reply = null;
            long end = System.currentTimeMillis() + 3000;
            while (reply == null && System.currentTimeMillis() < end) {
                pc.send(new DatagramPacket(q, q.length, d.address(), d.port()));
                byte[] buf = new byte[1500];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    pc.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (buf[0] == Names.NAME_REPLY) reply = Names.parseReply(buf, p.getLength(), config.getKey());
            }
            assertNotNull(reply, d.name() + " answers");
            assertNotNull(reply.position(), d.name() + " says where it is");
            double[] said = {reply.position().lat(), reply.position().lon()};
            assertTrue(metres(said, want) < 5, d.name() + " reports " + Coordinates.mgrs(said[0], said[1], 5) + ", given " + grid
                    + " (" + Math.round(metres(said, want)) + " m off)");
            String look = FleetLayout.PLACES.get(i).lookAt();
            if (look != null) { // its camera (the map's cone) looks at the pilot's point
                double want2 = FleetLayout.bearing(want, Coordinates.parse(look));
                double diff = Math.abs(((scenario.pose(d)[2] - want2) % 360 + 540) % 360 - 180);
                assertTrue(diff < 2, d.name() + " looks " + Math.round(scenario.pose(d)[2]) + " deg, its point is at " + Math.round(want2));
            }
            List<FlightTrack.Point> t = scenario.flights().get(i).points();
            FlightTrack.Point first = t.get(0), last = t.get(t.size() - 1);
            assertTrue(metres(new double[]{first.lat(), first.lon()}, launch) < 5, d.name() + "'s flight starts at the launch point: "
                    + Coordinates.mgrs(first.lat(), first.lon(), 5));
            assertTrue(metres(new double[]{last.lat(), last.lon()}, want) < 5, d.name() + "'s flight lands on " + grid + ": "
                    + Coordinates.mgrs(last.lat(), last.lon(), 5));
        }
    }

    /**
     * The pilot: "only one phone spawns in the tour". Asleep before any name scan reached them, the drones were answered
     * at their check-ins but never listed. Now a check-in says who and where (FleetProtocol.Where): all four are in the
     * name map, with their grids, from their first check-ins alone - within 2 s of being put to sleep.
     */
    @Test
    void allFourAreListedFromTheirCheckInsAlone() throws Exception {
        PcConfig config = PcConfig.defaults();
        scenario = TourScenario.start(config, null, m -> { }, null);
        pc = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        pc.setSoTimeout(100);
        Names names = new Names(null, m -> { });
        de.droiddrone.pc.NameService service = new de.droiddrone.pc.NameService(config, names, () -> null, pc.getLocalPort(), false, m -> { }, m -> { });
        java.util.Map<String, Integer> tokens = new java.util.HashMap<>();
        int t = 100;
        for (VirtualDrone d : scenario.overwatchers()) tokens.put(d.name(), t++);
        scenario.placeModes(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), pc.getLocalPort()), tokens, 2);
        long end = System.currentTimeMillis() + 4000; // the modes take 1 s, then each checks in within 1 s
        byte[] buf = new byte[1500];
        while (System.currentTimeMillis() < end && names.list(Names.Kind.PHONE).size() < 4) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                pc.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            }
            if (buf[0] != de.droiddrone.pc.ControlLink.SLEEP_HELLO) continue;
            de.droiddrone.common.FleetProtocol.Where w = de.droiddrone.common.FleetProtocol.Where.read(buf,
                    8 + de.droiddrone.common.FleetProtocol.STATUS_BYTES, p.getLength(), config.getKey());
            assertNotNull(w, "a pc.40 check-in says who and where");
            service.heardAsleep(p.getAddress().getHostAddress(), w, 3);
        }
        assertEquals(4, names.list(Names.Kind.PHONE).size(), "all four listed: " + names.list(Names.Kind.PHONE));
        java.util.Map<String, de.droiddrone.pc.NameService.DronePosition> at = new java.util.HashMap<>();
        for (de.droiddrone.pc.NameService.DronePosition d : service.dronePositions()) at.put(d.name(), d);
        for (int i = 0; i < 4; i++) {
            VirtualDrone d = scenario.drones().get(i);
            assertTrue(names.get(d.name()).isVirtual(), d.name() + " filed as virtual (never saved)");
            de.droiddrone.pc.NameService.DronePosition p = at.get(d.name());
            assertNotNull(p, d.name() + " on the map");
            double[] want = Coordinates.parse(FleetLayout.PLACES.get(i).at());
            assertTrue(metres(new double[]{p.position().lat(), p.position().lon()}, want) < 5, d.name() + " drawn on its grid");
        }
        // a check-in with another key lists nothing
        de.droiddrone.common.FleetProtocol.Where w = new de.droiddrone.common.FleetProtocol.Where("MobileAlpha", 1, 60, 10, 0);
        byte[] other = de.droiddrone.common.FleetProtocol.hello(new byte[8], new de.droiddrone.common.FleetProtocol.Status(50, false, 2, 1, 9, 100, 2, 1, 0), w, "another key");
        assertNull(de.droiddrone.common.FleetProtocol.Where.read(other, 8 + de.droiddrone.common.FleetProtocol.STATUS_BYTES, other.length, config.getKey()));
    }

    @Test
    void theUnitsAreOnTheirGrids() {
        assertEquals(2, TourScenario.UNITS.size());
        assertEquals("32V PN 49558 50584", TourScenario.UNITS.get(0).at());
        assertEquals(de.droiddrone.pc.map.MapGraphics.Affiliation.FRIEND, TourScenario.UNITS.get(0).side());
        assertEquals(de.droiddrone.pc.map.MapGraphics.Echelon.PLATOON, TourScenario.UNITS.get(0).size());
        assertEquals("32V PN 48490 51933", TourScenario.UNITS.get(1).at());
        assertEquals(de.droiddrone.pc.map.MapGraphics.Affiliation.HOSTILE, TourScenario.UNITS.get(1).side());
        assertEquals(de.droiddrone.pc.map.MapGraphics.UnitType.MECHANIZED, TourScenario.UNITS.get(1).type());
        assertEquals(de.droiddrone.pc.map.MapGraphics.Echelon.COMPANY, TourScenario.UNITS.get(1).size());
        for (TourScenario.Unit u : TourScenario.UNITS) {
            double[] p = Coordinates.parse(u.at());
            assertEquals(u.at(), Coordinates.mgrs(p[0], p[1], 5), "the grid reads back as itself");
        }
    }
}
