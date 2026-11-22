package de.droiddrone.pc.fleet;

import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static de.droiddrone.testing.Waits.waitFor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The virtual fleet's phone takes a NAME_ASSIGN as the phone app does (NameAssignGate): the flight controller known
 * disarmed, from the pilot's PC while one is connected, else from a PC that asked its name lately. Before, any keyed
 * NAME_ASSIGN renamed it, in any state.
 */
class VirtualNameAssignTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private VirtualDrone drone;
    private ControlLink link;

    @AfterEach
    void tearDown() {
        if (link != null) link.close();
        if (drone != null) drone.stop();
    }

    private void start(PcConfig config) throws Exception {
        drone = new VirtualDrone(0, "MobileZulu", config.getKey(), InetAddress.getLoopbackAddress(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0,
                new FlightModel.Setup(), log::add);
        drone.link().setBaseMs(0);
        drone.start(0);
    }

    private void send(DatagramSocket from, byte[] d) throws Exception {
        from.send(new DatagramPacket(d, d.length, new InetSocketAddress(InetAddress.getLoopbackAddress(), drone.port())));
    }

    @Test
    void anAssignFromAPcThatNeverAskedIsNotTakenOneAfterAQueryIs() throws Exception {
        PcConfig config = PcConfig.defaults();
        start(config);
        try (DatagramSocket pc = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            send(pc, Names.assign(config.getKey(), "MobileYankee"));
            Thread.sleep(300);
            assertEquals("MobileZulu", drone.name(), "an assign with nothing asked before it");
            send(pc, Names.assign(config.getKey(), "MobileYankee"));
            Thread.sleep(300);
            assertEquals("MobileZulu", drone.name(), "nor a second: an assign is no question");
            send(pc, Names.query(config.getKey()));
            send(pc, Names.assign(config.getKey(), "MobileYankee"));
            waitFor(() -> "MobileYankee".equals(drone.name()), 2000, "named by the PC that asked", log);
        }
    }

    @Test
    void anAssignIsNotTakenWhileTheFlightControllerIsNotKnownDisarmed() throws Exception {
        PcConfig config = PcConfig.defaults();
        start(config);
        drone.setFcLost(true); // nothing from its flight controller: not known disarmed
        try (DatagramSocket pc = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            send(pc, Names.query(config.getKey()));
            send(pc, Names.assign(config.getKey(), "MobileYankee"));
            Thread.sleep(400);
            assertEquals("MobileZulu", drone.name());
            drone.setFcLost(false);
            waitFor(() -> {
                try {
                    send(pc, Names.assign(config.getKey(), "MobileYankee"));
                } catch (Exception e) {
                    return false;
                }
                return "MobileYankee".equals(drone.name());
            }, 3000, "taken once it is read disarmed", log);
        }
    }

    @Test
    void withAPilotConnectedOnlyThePilotsPcNamesIt() throws Exception {
        PcConfig config = PcConfig.defaults();
        start(config);
        link = new ControlLink(config, InetAddress.getLoopbackAddress(), drone.port(), 0);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "the link READY", log);
        try (DatagramSocket other = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.2"), 0));
             DatagramSocket pilotPc = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            send(other, Names.query(config.getKey()));
            send(other, Names.assign(config.getKey(), "MobileYankee"));
            Thread.sleep(400);
            assertEquals("MobileZulu", drone.name(), "another PC, while the pilot's flies it");
            // the pilot PC's name service: its address, a socket of its own
            send(pilotPc, Names.assign(config.getKey(), "MobileX-ray"));
            waitFor(() -> "MobileX-ray".equals(drone.name()), 2000, "named by the pilot's PC", log);
        }
    }
}
