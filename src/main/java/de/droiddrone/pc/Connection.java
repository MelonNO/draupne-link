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
        return open(config, video, false);
    }

    /**
     * A probe's link (LinkProbe, 1.0.49): watch-only whatever the settings say - it asks the phone at the address who
     * flies and watches through that PC, never sends a pilot's Connect, so it can't take a session that just lapsed in
     * a 4G gap. Nobody flying: it waits for a pilot.
     */
    static Connection openWatching(PcConfig config, ControlLink.VideoListener video) throws IOException {
        return open(config, video, true);
    }

    private static Connection open(PcConfig config, ControlLink.VideoListener video, boolean probe) throws IOException {
        int port = config.getPort();
        boolean watch = probe || watchOnly(config);
        if (config.getIp().isEmpty()) {
            throw new IllegalArgumentException(watch && !probe ? "Pick the pilot PC to watch first." : "Pick the phone first.");
        }
        InetAddress remoteIp = InetAddress.getByName(config.getIp());
        // a virtual drone (the virtual fleet on this PC) listens on a port of its own; a real phone on the link port
        int remotePort = de.droiddrone.pc.fleet.VirtualFleet.portOf(config.getIp(), port);
        ControlLink link = new ControlLink(config, remoteIp, remotePort, port);
        link.enableSpectators(!probe && spectatorsAllowed(config), spectatorDelay(config));
        // watching: the address is the pilot PC's (its DRAUPNE name, 1.0.35), asked straight for its stream; a probe's
        // is the phone's, asked who flies
        if (watch) link.watchOnly(!probe && Names.kindOf(config.getString(Names.PHONE_NAME_KEY)) != Names.Kind.PHONE);
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
