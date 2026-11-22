package de.droiddrone.pc.fleet;

import de.droiddrone.pc.Connection;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static de.droiddrone.testing.Waits.waitFor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F-074 end to end on loopback (the bench's rescue-link-lost, 1.0.71): the log asked "Waiting for the phone...
 * (DRAUPNE Flight open? On Tailscale? Same key?)" of a link that was up and was lost in flight. The questions are for
 * the wait for the first link; a link that was up is lost: "Link lost - reconnecting." (the header and the card:
 * LinkReviewRoundTest).
 */
class LinkLostWordsEndToEndTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private VirtualDrone drone;
    private ControlLink link;
    private Wire wire;

    @AfterEach
    void tearDown() {
        if (link != null) link.close();
        if (drone != null) drone.stop();
        if (wire != null) wire.close();
    }

    @Test
    void theQuestionsAreForTheFirstLinkALinkThatWasUpIsLost() throws Exception {
        PcConfig config = PcConfig.defaults();
        drone = new VirtualDrone(0, "MobileZulu", config.getKey(), InetAddress.getLoopbackAddress(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0,
                new FlightModel.Setup(), log::add);
        drone.start(0);
        wire = new Wire(InetAddress.getLoopbackAddress(), drone.port());
        link = new ControlLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0);
        wire.rule((toPhone, d, len) -> Wire.Rule.LOST); // nothing through yet: the link can't be up
        link.start();
        assertEquals("Waiting for the phone... (DRAUPNE Flight open? On Tailscale? Same key?)",
                Connection.describe(link, ControlLink.State.CONNECTING), "no link up yet: the questions");
        assertFalse(link.wasEverReady(), "I-010: no link up yet - the LINK card's button says STOP");
        wire.clear();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "the link READY", log);
        assertTrue(link.wasEverReady(), "I-010: up - STANDBY MODE");

        wire.rule((toPhone, d, len) -> Wire.Rule.LOST); // the cut
        waitFor(() -> link.getState() == ControlLink.State.CONNECTING, 8000, "the link counted lost", log);
        assertEquals("Link lost - reconnecting.", Connection.describe(link, link.getState()));
        assertTrue(link.wasEverReady(), "I-010: up once and lost - STANDBY MODE still, not STOP");

        wire.clear();
        waitFor(() -> link.getState() == ControlLink.State.READY, 10_000, "the link back", log);
        assertEquals("Settings accepted - link ready.", Connection.describe(link, link.getState()));
    }
}
