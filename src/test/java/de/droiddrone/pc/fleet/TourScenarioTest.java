package de.droiddrone.pc.fleet;

import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tour's scenario end to end, on loopback (ephemeral ports): the four drones where the pilot put them and in their
 * modes, nothing moving until the BTR is sent in, then the real detection on the watcher's picture calling the PC, and
 * a drone that flies into the BTR breaking.
 */
class TourScenarioTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> closing = new CopyOnWriteArrayList<>();
    private volatile boolean sending;
    private volatile int armValue = 1000;

    @AfterEach
    void tearDown() throws Exception {
        sending = false;
        for (AutoCloseable c : closing) c.close();
    }

    @Test
    void theWatcherFindsTheBtrDrivingInAndCallsThePcWithItsToken() throws Exception {
        AtomicReference<VirtualDrone> alerted = new AtomicReference<>();
        TourScenario s = TourScenario.start(PcConfig.defaults(), null, log::add, new TourScenario.Listener() {
            @Override
            public void overwatchAlert(VirtualDrone w) {
                alerted.set(w);
            }
        });
        closing.add(s);
        assertEquals(4, s.drones().stream().map(VirtualDrone::name).distinct().count(), "four names");
        // where the pilot put them, looking where the pilot said
        for (int i = 0; i < 4; i++) {
            double[] want = de.droiddrone.pc.map.Coordinates.parse(TourScenario.PLACES.get(i).at());
            double[] at = s.drones().get(i).position();
            assertTrue(Math.hypot((at[0] - want[0]) * 111_320, (at[1] - want[1]) * 55_000) < 3, "drone " + i + " in its place");
        }
        double[] p0 = de.droiddrone.pc.map.Coordinates.parse(TourScenario.PLACES.get(0).at()),
                l0 = de.droiddrone.pc.map.Coordinates.parse(TourScenario.PLACES.get(0).lookAt());
        assertEquals(TourScenario.bearing(p0, l0), s.watcherPose()[2], 0.5, "the watcher looks at its point");
        // each flew from the launch point to where it sits, and they are all different
        double[] launch = TourScenario.launch();
        java.util.Set<Long> cells = new java.util.HashSet<>();
        for (int i = 0; i < 4; i++) {
            List<de.droiddrone.pc.map.FlightTrack.Point> t = s.flights().get(i).points();
            assertTrue(t.size() > 20, "a real flight's worth of fixes: " + t.size());
            assertTrue(Math.hypot((t.get(0).lat() - launch[0]) * 111_320, (t.get(0).lon() - launch[1]) * 55_000) < 8, "from the launch point");
            assertTrue(t.stream().mapToInt(de.droiddrone.pc.map.FlightTrack.Point::altM).max().getAsInt() >= 30, "flew at height");
            cells.add(Math.round(s.drones().get(i).cellVolts() * 100));
        }
        assertEquals(4, cells.size(), "four different packs");
        DatagramSocket pc = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        closing.add(pc);
        pc.setSoTimeout(500);
        int token = 0x70e7b7f;
        java.util.Map<String, Integer> tokens = new java.util.HashMap<>();
        for (VirtualDrone d : s.overwatchers()) tokens.put(d.name(), d == s.watcher() ? token : token + 1);
        s.placeModes(new InetSocketAddress(InetAddress.getLoopbackAddress(), pc.getLocalPort()), tokens, 2);
        waitFor(() -> s.sleeper().mode() == VirtualDrone.DEEP_SLEEP
                && s.overwatchers().stream().allMatch(d -> d.mode() == VirtualDrone.OVERWATCH), 3000, "the modes");
        // nothing moves until the BTR is sent: no alert in the detection's strict start and after it
        long placed = System.currentTimeMillis();
        byte[] buf = new byte[65536];
        while (System.currentTimeMillis() - placed < TourScenario.DRIVE_IN_AFTER_MS) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                pc.receive(p);
                assertFalse(p.getLength() > 4 && p.getData()[0] == OverwatchProtocol.ALERT, "an alert before the BTR moved");
            } catch (SocketTimeoutException ignored) {
                // quiet, as it should be
            }
        }
        long sent = System.currentTimeMillis();
        s.driveInNow();
        long alertAt = -1;
        while (alertAt < 0 && System.currentTimeMillis() - sent < 40_000) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                pc.receive(p);
                byte[] d = java.util.Arrays.copyOf(p.getData(), p.getLength());
                if (d.length > 4 && d[0] == OverwatchProtocol.ALERT && OverwatchProtocol.token(d) == token) alertAt = System.currentTimeMillis();
            } catch (SocketTimeoutException ignored) {
                // nothing yet
            }
        }
        assertTrue(alertAt > 0, "no overwatch alert came\n" + String.join("\n", log));
        waitFor(() -> alerted.get() == s.watcher(), 2000, "the listener heard of it");
    }

    /**
     * The pilot: SETUP > Tour again skipped the BTR part. A second scenario after the first's clean-up (its names left
     * as removed entries in the name map) takes the same four names again and its watcher still alerts on the BTR.
     */
    @Test
    void aSecondRunAfterTheFirstsCleanUpHasTheSameDronesAndAlertsAgain() throws Exception {
        PcConfig config = PcConfig.defaults();
        TourScenario first = TourScenario.start(config, null, log::add, new TourScenario.Listener() { });
        List<String> names = first.drones().stream().map(VirtualDrone::name).toList();
        first.close();
        for (String n : names) config.set("name." + n, ""); // what the tour's clean-up leaves (Names.remove)

        AtomicReference<VirtualDrone> alerted = new AtomicReference<>();
        TourScenario second = TourScenario.start(config, null, log::add, new TourScenario.Listener() {
            @Override
            public void overwatchAlert(VirtualDrone w) {
                alerted.set(w);
            }
        });
        closing.add(second);
        assertEquals(names, second.drones().stream().map(VirtualDrone::name).toList(), "the same four drones again");
        DatagramSocket pc = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        closing.add(pc);
        java.util.Map<String, Integer> tokens = new java.util.HashMap<>();
        for (VirtualDrone d : second.overwatchers()) tokens.put(d.name(), 0x5ec0 + tokens.size());
        second.placeModes(new InetSocketAddress(InetAddress.getLoopbackAddress(), pc.getLocalPort()), tokens, 2);
        waitFor(() -> second.overwatchers().stream().allMatch(d -> d.mode() == VirtualDrone.OVERWATCH), 3000, "the modes");
        second.driveInNow();
        waitFor(() -> alerted.get() == second.watcher(), 40_000, "the second run's alert\n" + String.join("\n", log));
    }

    @Test
    void armedInTheBtrsWayTheDroneIsHitBreaksAndItsLinkGoesSilent() throws Exception {
        AtomicReference<VirtualDrone> connected = new AtomicReference<>(), crashed = new AtomicReference<>();
        PcConfig config = PcConfig.defaults();
        TourScenario s = TourScenario.start(config, null, log::add, new TourScenario.Listener() {
            @Override
            public void connected(VirtualDrone w) {
                connected.set(w);
            }

            @Override
            public void crashed(VirtualDrone w) {
                crashed.set(w);
            }
        });
        closing.add(s);
        VirtualDrone w = s.watcher();
        // on the BTR's way, where it will be 9 s after it sets off
        double[] q = s.btr().pose(s.btr().drivesFrom() + 9_000);
        assertTrue(w.fc.moveTo(q[0], q[1]), "placed while disarmed");
        ControlLink l = new ControlLink(config, w.address(), w.port(), 0);
        closing.add(l::close);
        l.start();
        waitFor(() -> l.getState() == ControlLink.State.READY, 5000, "the link READY");
        waitFor(() -> connected.get() == w, 2000, "the listener heard of the connection");
        FcTelemetry t = l.getTelemetry();
        startSticks(l);
        waitFor(() -> w.fc.hadSticks(), 2000, "the sticks reach the virtual FC");
        Thread.sleep(FlightModel.BOOT_GRACE_MS + 700);
        armValue = 1900;
        waitFor(() -> t.isArmedNow(MonoClock.ms(), 1000), 3000, "armed");
        s.driveInNow();
        waitFor(() -> crashed.get() == w, 20_000, "the BTR running into it");
        assertTrue(w.isBroken());
        assertFalse(w.fc.isArmed(), "disarmed by the hit");
        assertTrue(w.fc.crashed());
        waitFor(() -> l.getState() != ControlLink.State.READY, 4000, "the PC counts the link lost");
        assertTrue(log.stream().anyMatch(x -> x.contains("flew into the BTR")), String.join("\n", log));
    }

    private void startSticks(ControlLink l) {
        sending = true;
        Thread sticks = new Thread(() -> {
            while (sending) {
                l.sendRc(new short[]{1500, 1500, 1500, 1000, (short) armValue, 1500, 1000, 1000});
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "testSticks");
        sticks.setDaemon(true);
        sticks.start();
    }

    private void waitFor(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("Timed out waiting for " + what + "\n" + String.join("\n", log));
            Thread.sleep(20);
        }
    }
}
