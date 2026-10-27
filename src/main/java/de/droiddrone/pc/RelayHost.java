package de.droiddrone.pc;

import de.droiddrone.server.Config;
import de.droiddrone.server.Udp;

/**
 * Runs upstream's relay server (Server/src) inside this app, for LinkMode.HOST_RELAY: the 4G phone connects to this
 * PC's public address, and the PC's own ControlLink connects to the relay over loopback.
 * Upstream's server keeps its settings in config.cfg in the working directory.
 */
public class RelayHost {
    private Udp udp;

    public void start(int port, String key) {
        Config config = new Config();
        if (!config.getKey().equals(key) && !config.setKey(key)) {
            throw new IllegalArgumentException("Relay key must be at most 16 characters");
        }
        if (config.getPort() != port && !config.setPort(port)) {
            throw new IllegalArgumentException("Relay port must be 1024..65535");
        }
        udp = new Udp(config);
        if (!udp.initialize()) {
            udp = null;
            throw new IllegalStateException("Could not open UDP port " + port + " for the relay (already in use?)");
        }
    }

    public boolean isRunning() {
        return udp != null;
    }

    public void stop() {
        if (udp != null) udp.close();
        udp = null;
    }
}
