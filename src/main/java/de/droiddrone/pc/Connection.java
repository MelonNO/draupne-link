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
        ControlLink link = new ControlLink(config, remoteIp, port, localPort);
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
            case CLOSED -> "Link closed.";
        };
    }

    public void close() {
        link.close();
        if (relay != null) relay.stop();
    }
}
