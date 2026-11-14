package de.droiddrone.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Set;

/**
 * Free ports for the tests that must name one (a link's own UDP port, a fake phone's; the TCP port of a web server):
 * asked of the system by binding port 0, so test classes running side by side (the build's forks) and a DRAUPNE Link
 * running on this PC never meet on a fixed number. A test takes its ports when it starts and binds them at once - a
 * port is only known to be free at the moment it is handed out.
 */
public final class Ports {
    private static final Set<Integer> given = new HashSet<>(), givenTcp = new HashSet<>();

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

    /** The same for TCP: a port nothing listens on just now, and not handed out before in this run's fork. */
    public static synchronized int freeTcp() {
        for (int i = 0; i < 100; i++) {
            int port;
            try (ServerSocket probe = new ServerSocket(0)) {
                port = probe.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (givenTcp.add(port)) return port;
        }
        throw new IllegalStateException("no free TCP port");
    }
}
