package de.droiddrone.pc;

import java.io.IOException;
import java.net.InetAddress;

/** A ControlLink set up for the configured LinkMode, plus the in-process relay when hosting one. */
public class Connection {
    private final LinkMode mode;
    private final RelayHost relay;
    private final ControlLink link;

    private Connection(LinkMode mode, RelayHost relay, ControlLink link) {
        this.mode = mode;
        this.relay = relay;
        this.link = link;
    }

    public static Connection open(PcConfig config) throws IOException {
        return open(config, null);
    }

    /** @param video receives the camera stream (requested as H.265 or H.264 per the videoCodec setting); null = no video */
    public static Connection open(PcConfig config, ControlLink.VideoListener video) throws IOException {
        LinkMode mode = config.getLinkMode();
        int port = config.getPort();
        RelayHost relay = null;
        InetAddress remoteIp;
        int localPort = port;
        if (mode == LinkMode.HOST_RELAY) {
            relay = new RelayHost();
            relay.start(port, config.getKey());
            remoteIp = InetAddress.getLoopbackAddress();
            localPort = port + 1; // the relay owns the public port; we talk to it over loopback
        } else {
            if (config.getIp().isEmpty()) {
                throw new IllegalArgumentException("Set the " + (mode == LinkMode.RELAY ? "relay server" : "phone") + " address first.");
            }
            remoteIp = InetAddress.getByName(config.getIp());
        }
        boolean watch = watchOnly(config);
        if (watch && mode != LinkMode.PHONE) {
            throw new IllegalArgumentException("Watching as a spectator needs the connection \"" + LinkMode.PHONE.title
                    + "\": through a relay the phone can't say which PC flies.");
        }
        ControlLink link = new ControlLink(config, remoteIp, port, localPort);
        // only straight to the phone: through a relay the phone can't tell a second PC where the first one is
        if (mode == LinkMode.PHONE) link.enableSpectators(spectatorsAllowed(config), spectatorDelay(config));
        if (watch) link.watchOnly();
        if (video != null) link.setVideoListener(video, !"avc".equalsIgnoreCase(config.getString("videoCodec")));
        try {
            link.start();
        } catch (IOException | RuntimeException e) {
            link.close();
            if (relay != null) relay.stop();
            throw e;
        }
        return new Connection(mode, relay, link);
    }

    /** Connect as: pilot (fly the drone) or spectator (watch another PC's flight). */
    public static final String CONNECT_AS_KEY = "connectAs";

    public static boolean watchOnly(PcConfig config) {
        return "spectator".equalsIgnoreCase(config.getString(CONNECT_AS_KEY));
    }

    /** SETTINGS > Allow spectators: off unless the pilot switches it on. */
    public static final String ALLOW_SPECTATORS_KEY = "allowSpectators";

    public static boolean spectatorsAllowed(PcConfig config) {
        return "true".equalsIgnoreCase(config.getString(ALLOW_SPECTATORS_KEY));
    }

    /** spectatorDelayMs: 200..5000, the default when missing or broken. */
    static int spectatorDelay(PcConfig config) {
        try {
            return Math.max(200, Math.min(5000, Integer.parseInt(config.getString("spectatorDelayMs"))));
        } catch (NumberFormatException e) {
            return SpectatorClient.DEFAULT_DELAY_MS;
        }
    }

    public ControlLink getLink() {
        return link;
    }

    public LinkMode getMode() {
        return mode;
    }

    public String describe(ControlLink.State state) {
        return switch (state) {
            case CONNECTING -> switch (mode) {
                case PHONE -> "Waiting for the Flight phone... (Flight app connected in Direct mode, reachable address, same key?)";
                case RELAY -> "Waiting for the relay server... (address, port and key right?)";
                case HOST_RELAY -> "Starting the relay on this PC...";
            };
            // through a relay, the relay answers our Connect itself; settings are only accepted once the phone is there
            case CONFIGURING -> mode == LinkMode.PHONE
                    ? "Connected. Sending settings..."
                    : "Relay connected - waiting for the Flight phone to join (Flight app: Connect over server)...";
            case READY -> "Flight phone accepted settings - link ready.";
            case VERSION_MISMATCH -> "VERSION MISMATCH: install DRAUPNE Flight (or DroidDrone Flight 2.3.0) on the phone.";
            case SPECTATING, SPECTATOR_WAITING -> link.spectatorStatus();
            case CLOSED -> "Link closed.";
        };
    }

    public void close() {
        link.close();
        if (relay != null) relay.stop();
    }
}
