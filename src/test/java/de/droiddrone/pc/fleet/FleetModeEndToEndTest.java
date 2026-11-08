package de.droiddrone.pc.fleet;

import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FleetManager;
import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FLEET MANAGEMENT (1.0.52) against a virtual drone on loopback, ephemeral ports both sides: a mode queued for an idle
 * phone goes with the name scan's answer and is confirmed at its first check-in; one queued for a sleeping phone goes
 * ahead of the answer to its hello; a phone with a pilot refuses it.
 */
class FleetModeEndToEndTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private VirtualDrone drone;
    private DatagramSocket pc;
    private ControlLink link;

    @AfterEach
    void tearDown() {
        if (link != null) link.close();
        if (drone != null) drone.stop();
        if (pc != null) pc.close();
    }

    private FleetManager start(PcConfig config) throws Exception {
        drone = new VirtualDrone(0, "MobileZulu", config.getKey(), InetAddress.getLoopbackAddress(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0,
                new FlightModel.Setup(), log::add);
        drone.start(0);
        pc = new DatagramSocket(0, InetAddress.getLoopbackAddress()); // this PC's link port
        pc.setSoTimeout(200);
        return new FleetManager(config.getKey(), pc.getLocalPort(), "", m -> { }, log::add);
    }

    private void send(byte[] d) throws Exception {
        pc.send(new DatagramPacket(d, d.length, InetAddress.getLoopbackAddress(), drone.port()));
    }

    /** The first packet starting with this code, within ms; null none. */
    private byte[] receive(byte code, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        byte[] buf = new byte[1500];
        while (System.currentTimeMillis() < end) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                pc.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            }
            if (p.getLength() > 0 && buf[0] == code) return java.util.Arrays.copyOf(buf, p.getLength());
        }
        return null;
    }

    private Names.Reply ask(PcConfig config) throws Exception {
        send(Names.query(config.getKey()));
        byte[] r = receive(Names.NAME_REPLY, 2000);
        assertNotNull(r, "a name reply");
        return Names.parseReply(r, r.length, config.getKey());
    }

    @Test
    void anIdlePhoneTakesItWithTheScanAndConfirmsAtItsCheckIn() throws Exception {
        PcConfig config = PcConfig.defaults();
        FleetManager fleet = start(config);
        Names.Reply first = ask(config);
        assertNotNull(first.status(), "pc.40: the status block");
        assertEquals(0, first.status().mode);
        List<FleetManager.Wanted> taken = new ArrayList<>();
        fleet.setOnTaken(taken::add);
        FleetManager.Wanted w = fleet.queue("MobileZulu", ControlLink.POWER_DEEP_SLEEP, 1, 0, 0, 0, System.currentTimeMillis());
        byte[] mode = fleet.onReply(first, System.currentTimeMillis());
        assertNotNull(mode);
        send(mode);
        waitFor(() -> drone.mode() == VirtualDrone.DEEP_SLEEP, 3000, "deep sleep asked without a session");
        waitFor(() -> drone.asleep(), 3000, "asleep after the countdown");
        drone.checkNow(); // its check window: the hello goes to the PC that sent the mode
        byte[] hello = receive(ControlLink.SLEEP_HELLO, 3000);
        assertNotNull(hello, "the hello reached this PC's link port");
        ControlLink.SleepHello h = ControlLink.SleepHello.read(hello, hello.length, System.currentTimeMillis());
        assertEquals(w.token(), h.status().token);
        assertNull(fleet.onHello("MobileZulu", h, System.currentTimeMillis()));
        assertEquals(List.of(w), taken, "taken");
        assertNull(fleet.wanted("MobileZulu"));
    }

    @Test
    void aSleepingPhoneGetsTheNextModeAheadOfTheAnswerToItsHello() throws Exception {
        PcConfig config = PcConfig.defaults();
        FleetManager fleet = start(config);
        fleet.queue("MobileZulu", ControlLink.POWER_DEEP_SLEEP, 1, 0, 0, 0, System.currentTimeMillis());
        send(fleet.onReply(ask(config), System.currentTimeMillis()));
        waitFor(() -> drone.asleep(), 4000, "asleep");
        FleetManager.Wanted w = fleet.queue("MobileZulu", ControlLink.POWER_OVERWATCH, 2, 2, 0, 0x5eed0002, System.currentTimeMillis());
        drone.checkNow();
        byte[] hello = receive(ControlLink.SLEEP_HELLO, 3000);
        assertNotNull(hello);
        byte[] next = fleet.onHello("MobileZulu", ControlLink.SleepHello.read(hello, hello.length, System.currentTimeMillis()), System.currentTimeMillis());
        assertNotNull(next, "the overwatch queued meanwhile");
        send(next);
        send(new byte[]{ControlLink.SLEEP_ANSWER, 0}); // then the answer, as the PC sends them
        waitFor(() -> drone.mode() == VirtualDrone.OVERWATCH, 4000, "overwatch taken in the check window");
        assertTrue(log.stream().anyMatch(l -> l.contains("PC fleet")), String.join("\n", log));
        assertEquals(w.token(), drone.fleetStatus().token);
    }

    @Test
    void aPhoneWithAPilotRefusesIt() throws Exception {
        PcConfig config = PcConfig.defaults();
        FleetManager fleet = start(config);
        link = new ControlLink(config, InetAddress.getLoopbackAddress(), drone.port(), 0);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "a pilot connected");
        fleet.queue("MobileZulu", ControlLink.POWER_STANDBY, 5, 0, 0, 0, System.currentTimeMillis());
        Names.Reply r = ask(config);
        send(fleet.onReply(r, System.currentTimeMillis()));
        waitFor(() -> log.stream().anyMatch(l -> l.contains("refused: a pilot is connected")), 3000, "refused");
        assertEquals(VirtualDrone.NORMAL, drone.mode());
        assertNotNull(fleet.wanted("MobileZulu"), "still queued: it goes once the pilot is gone");
    }

    private static void waitFor(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for " + what);
            Thread.sleep(20);
        }
    }
}
