package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;

import static org.junit.jupiter.api.Assertions.*;

/** M4 (1.0.48 review): a probe connected as a pilot and could take a session that had just lapsed in a 4G gap. */
class LinkProbeTest {
    @Test
    void theProbeWatchesNeverFlies() throws Exception {
        int port;
        try (DatagramSocket free = new DatagramSocket(0)) {
            port = free.getLocalPort();
        }
        PcConfig config = PcConfig.defaults();
        config.set("ip", "127.0.0.1");
        config.set("port", Integer.toString(port));
        config.set(Connection.CONNECT_AS_KEY, "pilot"); // whatever the settings say
        Connection c = LinkProbe.connect(config, null);
        try {
            ControlLink link = c.getLink();
            assertTrue(link.isWatchOnly(), "a spectator's link");
            assertFalse(link.sticksWanted(), "no sticks, no pilot's session");
            assertFalse(link.sendRc(new short[]{1500, 1500, 1500, 1000}));
        } finally {
            c.close();
        }
    }
}
