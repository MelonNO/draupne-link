package de.droiddrone.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.util.HashSet;
import java.util.Set;

/**
 * Free UDP ports for the tests that must name one (a link's own port, a fake phone's): asked of the system by
 * binding port 0, so test classes running side by side (the build's forks) and a DRAUPNE Link running on this PC
 * never meet on a fixed number. A test takes its ports when it starts and binds them at once - a port is only
 * known to be free at the moment it is handed out.
 */
public final class Ports {
    private static final Set<Integer> given = new HashSet<>();

    private Ports() {
    }

    /** A port free on every address of this PC just now, and not handed out before in this run's fork. */
    public static synchronized int free() {
        for (int i = 0; i < 100; i++) {
            int port;
            try (DatagramSocket probe = new DatagramSocket(0)) {
                port = probe.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (given.add(port)) return port;
        }
        throw new IllegalStateException("no free UDP port");
    }
}
