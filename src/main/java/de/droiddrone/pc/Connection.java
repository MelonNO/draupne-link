package de.droiddrone.pc;

import java.io.IOException;
import java.net.InetAddress;

/** A ControlLink straight to the phone (or, watching, to the pilot PC) at the address in the settings. */
public class Connection {
    private final ControlLink link;

    private Connection(ControlLink link) {
        this.link = link;
    }

    public static Connection open(PcConfig config) throws IOException {
        return open(config, null);
    }

    /** @param video receives the camera stream (requested as H.265 or H.264 per the videoCodec setting); null = no video */
    public static Connection open(PcConfig config, ControlLink.VideoListener video) throws IOException {
        int port = config.getPort();
        if (config.getIp().isEmpty()) {
            throw new IllegalArgumentException(watchOnly(config) ? "Pick the pilot PC to watch first." : "Pick the phone first.");
        }
        InetAddress remoteIp = InetAddress.getByName(config.getIp());
        boolean watch = watchOnly(config);
        ControlLink link = new ControlLink(config, remoteIp, port, port);
        link.enableSpectators(spectatorsAllowed(config), spectatorDelay(config));
        // watching: the address is the pilot PC's (its DRAUPNE name, 1.0.35), asked straight for its stream
        if (watch) link.watchOnly(Names.kindOf(config.getString(Names.PHONE_NAME_KEY)) != Names.Kind.PHONE);
        if (video != null) link.setVideoListener(video, !"avc".equalsIgnoreCase(config.getString("videoCodec")));
        try {
            link.start();
        } catch (IOException | RuntimeException e) {
            link.close();
            throw e;
        }
        return new Connection(link);
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


    public String describe(ControlLink.State state) {
        return switch (state) {
            case CONNECTING -> link.isWatchOnly() ? "Waiting for the pilot PC... (is it connected, with Allow spectators on?)"
                    : "Waiting for the phone... (DRAUPNE Flight open on it, the phone on Tailscale, the same key?)";
            case CONFIGURING -> "Connected. Sending settings...";
            case READY -> "Flight phone accepted settings - link ready.";
            case VERSION_MISMATCH -> "VERSION MISMATCH: install DRAUPNE Flight (or DroidDrone Flight 2.3.0) on the phone.";
            case SPECTATING, SPECTATOR_WAITING -> link.spectatorStatus();
            case CLOSED -> "Link closed.";
        };
    }

    public void close() {
        link.close();
    }
}
