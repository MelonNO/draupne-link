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
        if (key == null || key.length() > 16) throw new IllegalArgumentException("Relay key must be at most 16 characters");
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Relay port must be 1024..65535");
        Config config = new Config();
        // the setters take the value, then save config.cfg - which can fail in a read-only working folder; the values
        // are in use either way, so their result isn't an error
        config.setKey(key);
        config.setPort(port);
        // no viewers: upstream lets two in with its default key "DD", and they would get the video and GPS position
        config.setViewerCount(0);
        config.setViewerKey(randomKey());
        udp = new Udp(config);
        if (!udp.initialize()) {
            udp = null;
            throw new IllegalStateException("Could not open UDP port " + port + " for the relay (already in use?)");
        }
    }

    private static String randomKey() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    public boolean isRunning() {
        return udp != null;
    }

    public void stop() {
        if (udp != null) udp.close();
        udp = null;
    }
}
