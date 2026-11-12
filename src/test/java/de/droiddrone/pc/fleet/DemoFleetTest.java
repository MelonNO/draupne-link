package de.droiddrone.pc.fleet;

import de.droiddrone.common.UdpCommon;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.Mirror;
import de.droiddrone.pc.MirrorClient;
import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DEMO mode's one fleet (2026-11-10, the pilot: "all the virtual drones with the simulation environments are spawned"):
 * the six with their scenes, a virtual pilot PC flying and the scenario's four run side by side - in the tour each part
 * had a fleet of its own and only one could run - and the scenario goes again without touching the rest.
 * <p>
 * The pilot's second round: SET UP SCENARIO said "at most 12 virtual drones" (the pilot PC's three plain drones made
 * 6 + 1 + 3 + 4 = 14); "no drones without interesting scenes"; the observed tiles stayed empty; every drone had the
 * piloted sign and the same battery.
 */
class DemoFleetTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private Fleet fleet;
    private VirtualPilotPc pilot;
    private ControlLink spectator;
    private MirrorClient mirror;

    @AfterEach
    void tearDown() {
        if (mirror != null) mirror.close();
        if (spectator != null) spectator.close();
        if (pilot != null) pilot.close();
        if (fleet != null) fleet.close();
    }

    private static void waitFor(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > until) fail("waited " + ms + " ms for " + what);
            Thread.sleep(20);
        }
    }

    @Test
    void everyDemoDroneRunsInOneFleetAndTheScenarioGoesAgainAlone() throws Exception {
        PcConfig config = PcConfig.defaults();
        fleet = new Fleet(config, null, log::add);
        fleet.startHeartbeat();
        TourShowcase six = TourShowcase.startIn(fleet);
        pilot = new VirtualPilotPc(fleet, config, "PCBravo", log::add);
        pilot.fly(Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON + 0.001);
        TourScenario four = TourScenario.startIn(fleet, new TourScenario.Listener() { });
        assertEquals(6 + 1 + 4, fleet.drones().size(), "the six, the pilot PC's own drone and the scenario's four");
        assertTrue(fleet.drones().size() <= Fleet.MAX_DRONES, "room for them all: the scenario set up");
        assertTrue(fleet.drones().containsAll(six.drones()));
        assertTrue(fleet.drones().containsAll(four.drones()));
        assertTrue(six.drones().containsAll(six.forOtherPilot()), "the other pilot observes drones with scenes");
        assertEquals(3, six.forOtherPilot().size());
        // 2026-11-12, the pilot: SET UP SCENARIO "spawned another set of drones on top of the already existing drones" -
        // the six had the layout's first places, and the scenario's four are put exactly there
        for (VirtualDrone a : fleet.drones()) {
            for (VirtualDrone b : fleet.drones()) {
                if (a == b) continue;
                double dn = (a.position()[0] - b.position()[0]) * 111_320.0;
                double de = (a.position()[1] - b.position()[1]) * 111_320.0 * Math.cos(Math.toRadians(a.position()[0]));
                assertTrue(Math.hypot(dn, de) >= 10, a.name() + " and " + b.name() + " stand " + Math.round(Math.hypot(dn, de)) + " m apart");
            }
        }
        for (VirtualDrone s : six.drones()) {
            for (VirtualDrone f : four.drones()) {
                double dn = (s.position()[0] - f.position()[0]) * 111_320.0;
                double de = (s.position()[1] - f.position()[1]) * 111_320.0 * Math.cos(Math.toRadians(s.position()[0]));
                assertTrue(Math.hypot(dn, de) >= 60, s.name() + " is clear of the scenario's " + f.name() + ": " + Math.round(Math.hypot(dn, de)) + " m");
            }
        }
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

    @Test
    void theSixTakeVariedModesAndNoneOverwatches() throws Exception {
        PcConfig config = PcConfig.defaults();
        fleet = new Fleet(config, null, log::add);
        TourShowcase six = TourShowcase.startIn(fleet);
        try (DatagramSocket pc = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            Set<Integer> seen = new HashSet<>();
            List<String> said = six.placeDemoModes(new InetSocketAddress(InetAddress.getLoopbackAddress(), pc.getLocalPort()), new Random(5));
            assertEquals(6, said.size(), "each one said: " + said);
            waitFor(() -> six.drones().stream().filter(d -> d.mode() != VirtualDrone.NORMAL).count() >= 5, 3000, "five in a mode");
            int idle = 0;
            for (VirtualDrone d : six.drones()) {
                seen.add(d.mode());
                assertNotEquals(VirtualDrone.OVERWATCH, d.mode(), "no alert before START SCENARIO: " + d.name());
                if (d.mode() == VirtualDrone.NORMAL) idle++;
            }
            assertEquals(1, idle, "one idle, the rest standing by or asleep: " + said);
            assertTrue(seen.contains(VirtualDrone.STANDBY) && seen.contains(VirtualDrone.DEEP_SLEEP), "both: " + said);

            // observed here: woken at once, standby ones too
            List<String> all = six.names();
            List<String> woken = six.wake(all, "observed");
            assertEquals(5, woken.size(), "the five not idle: " + woken);
            for (VirtualDrone d : six.drones()) assertEquals(VirtualDrone.NORMAL, d.mode(), d.name() + " awake");
        }
    }

    @Test
    void batteriesVaryFromDroneToDrone() throws Exception {
        fleet = new Fleet(PcConfig.defaults(), null, log::add);
        Set<Integer> phones = new HashSet<>();
        Set<Long> packs = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            VirtualDrone d = fleet.add(fleet.freeName(), Fleet.DEFAULT_LAT + i * 0.0001, Fleet.DEFAULT_LON, 0);
            int p = d.phoneBatteryPercent();
            assertTrue(p >= Fleet.PHONE_LOW && p <= Fleet.PHONE_HIGH, "phone " + p);
            double cell = d.cellVolts();
            assertTrue(cell >= Fleet.PACK_LOW_V - 0.05 && cell <= 4.25, "pack per cell " + cell);
            phones.add(p);
            packs.add(Math.round(cell * 100));
        }
        assertTrue(phones.size() >= 3, "phones vary: " + phones);
        assertTrue(packs.size() >= 3, "packs vary: " + packs);
    }

    /**
     * The other pilot switches to observation: its tiles are three of the six - asleep or standing by, woken as it
     * observes - and a spectator gets each tile's stream (the pilot: "none of the videos show").
     */
    @Test
    void theOtherPilotObservesThreeOfTheSixAndASpectatorGetsTheirStreams() throws Exception {
        PcConfig config = PcConfig.defaults();
        fleet = new Fleet(config, null, log::add);
        TourShowcase six = TourShowcase.startIn(fleet);
        List<VirtualDrone> watched = six.forOtherPilot();
        try (DatagramSocket pc = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            InetSocketAddress at = new InetSocketAddress(InetAddress.getLoopbackAddress(), pc.getLocalPort());
            watched.get(0).requestMode(VirtualDrone.STANDBY, 0, "test");
            watched.get(1).placeInMode(at, VirtualDrone.DEEP_SLEEP, 2, 0, 0, 0, "test");
            waitFor(() -> watched.get(0).mode() == VirtualDrone.STANDBY && watched.get(1).mode() == VirtualDrone.DEEP_SLEEP, 3000, "placed");

            pilot = new VirtualPilotPc(fleet, config, "PCBravo", log::add);
            pilot.fly(Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON + 0.001);
            InetAddress host = InetAddress.getByName(pilot.address());
            spectator = new ControlLink(config, host, pilot.port(), 0);
            spectator.enableSpectators(false, 300);
            spectator.watchOnly(true);
            spectator.setVideoListener(new ControlLink.VideoListener() {
                @Override
                public void onVideoConfig(int width, int height, boolean hevc, boolean frontCamera, byte[] codecConfig) {
                }

                @Override
                public void onVideoFragment(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length) {
                }
            }, false);
            spectator.start();
            AtomicReference<Map<String, byte[]>> state = new AtomicReference<>();
            List<Integer> tiles = new CopyOnWriteArrayList<>();
            mirror = new MirrorClient(host, pilot.port(), config.getKey(), new MirrorClient.Listener() {
                @Override
                public void state(Map<String, byte[]> sections) {
                    state.set(sections);
                }

                @Override
                public void tile(int tile, byte[] d, int len) {
                    if (d[0] == UdpCommon.VideoInitialFrame) tiles.add(tile);
                }
            });
            waitFor(() -> spectator.getState() == ControlLink.State.SPECTATING, 8000, "spectating the other pilot");

            pilot.observe(watched);
            for (VirtualDrone d : watched) assertEquals(VirtualDrone.NORMAL, d.mode(), d.name() + " woken to be observed");
            waitFor(() -> mode(state) != null && mode(state).mode() == Mirror.OBSERVER, 3000, "OBSERVER mode mirrored");
            List<String> wantNames = new ArrayList<>();
            for (VirtualDrone d : watched) wantNames.add(d.name());
            assertEquals(wantNames, mode(state).tiles());
            waitFor(() -> tiles.contains(0) && tiles.contains(1) && tiles.contains(2), 10_000, "all three tiles' streams relayed: " + tiles);
        }
    }

    private static Mirror.Mode mode(AtomicReference<Map<String, byte[]>> s) {
        Map<String, byte[]> m = s.get();
        return m == null ? null : Mirror.Mode.decode(m.get(Mirror.MODE));
    }
}
