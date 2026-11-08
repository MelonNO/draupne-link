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
 * The tour's scenario end to end, on loopback (ephemeral ports): the three drones in their modes, the BTR driving in and
 * the real detection on the watcher's picture calling the PC, and a drone that flies into the BTR breaking.
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
        assertEquals(3, java.util.Set.of(s.watcher().name(), s.sleeper().name(), s.active().name()).size(), "three names");
        DatagramSocket pc = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        closing.add(pc);
        pc.setSoTimeout(500);
        int token = 0x70e7b7f;
        long placed = System.currentTimeMillis();
        s.placeModes(new InetSocketAddress(InetAddress.getLoopbackAddress(), pc.getLocalPort()), token, 2);
        waitFor(() -> s.sleeper().mode() == VirtualDrone.DEEP_SLEEP && s.watcher().mode() == VirtualDrone.OVERWATCH, 3000, "the modes");
        assertEquals(VirtualDrone.NORMAL, s.active().mode(), "the active one stays awake");
        long alertAt = -1;
        byte[] buf = new byte[65536];
        while (alertAt < 0 && System.currentTimeMillis() - placed < TourScenario.DRIVE_IN_AFTER_MS + 20_000) {
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
        assertTrue(alertAt - placed >= TourScenario.DRIVE_IN_AFTER_MS, "nothing moved before the BTR set off");
        waitFor(() -> alerted.get() == s.watcher(), 2000, "the listener heard of it");
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
        // on the BTR's way in: its near side, 22 m ahead, 20 m to the left of the drone's start
        assertTrue(w.fc.moveTo(BtrScene.AHEAD_M - BtrScene.DEPTH_M, -20), "placed while disarmed");
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
        waitFor(() -> crashed.get() == w, 12_000, "the BTR running into it");
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
