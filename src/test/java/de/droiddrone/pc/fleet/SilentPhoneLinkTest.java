package de.droiddrone.pc.fleet;

import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static de.droiddrone.testing.Waits.waitFor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Q-009 end to end on loopback, a virtual drone with the phone's own SessionGate: what "the phone has not answered at
 * all" is on the PC's link. A phone with another key stays silent (on purpose): not heard. The right key is answered;
 * so is a second PC's PILOT UAV while another PC flies (it is told who flies) - heard, so that PC keeps its own words
 * and never gets "DRAUPNE Flight closed, or another key?".
 */
class SilentPhoneLinkTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private VirtualDrone drone;
    private ControlLink link, second;

    @AfterEach
    void tearDown() {
        if (second != null) second.close();
        if (link != null) link.close();
        if (drone != null) drone.stop();
    }

    private void startDrone(String key) throws Exception {
        drone = new VirtualDrone(0, "MobileZulu", key, InetAddress.getLoopbackAddress(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0,
                new FlightModel.Setup(), log::add);
        drone.start(0);
    }

    private ControlLink startLink(PcConfig config) throws Exception {
        ControlLink l = new ControlLink(config, InetAddress.getLoopbackAddress(), drone.port(), 0);
        l.enableSpectators(false, 300);
        l.start();
        return l;
    }

    @Test
    void aPhoneWithAnotherKeyIsNeverHeard() throws Exception {
        PcConfig config = PcConfig.defaults();
        startDrone(config.getKey() + "X");
        link = startLink(config);
        assertFalse(link.wasEverHeard(), "nothing yet");
        waitFor(() -> log.stream().anyMatch(l -> l.contains("tried another key")), 5000, "the phone dropping the Connect", log);
        waitFor(() -> log.stream().filter(l -> l.contains("tried another key")).count() >= 3, 5000, "three Connects dropped", log);
        assertFalse(link.wasEverHeard(), "the phone stays silent to another key");
        assertEquals(ControlLink.State.CONNECTING, link.getState());
    }

    @Test
    void theRightKeyIsHeard() throws Exception {
        PcConfig config = PcConfig.defaults();
        startDrone(config.getKey());
        link = startLink(config);
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "the link READY", log);
        assertTrue(link.wasEverHeard());
    }

    @Test
    void anotherPcFlyingIsAnAnswer() throws Exception {
        PcConfig config = PcConfig.defaults();
        startDrone(config.getKey());
        link = startLink(config);
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "the first PC flying", log);
        second = startLink(config); // a second PC's PILOT UAV, while the first flies
        waitFor(() -> second.wasEverHeard(), 5000, "the phone's answer to the second PC", log);
        assertNotEquals(ControlLink.State.READY, second.getState(), "it does not fly");
        assertEquals(ControlLink.State.READY, link.getState(), "the first still does");
    }
}
