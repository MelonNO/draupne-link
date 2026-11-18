package de.droiddrone.pc;

import java.net.InetAddress;

/**
 * A PC before 1.0.70 as the phone sees it on the wire, for the tests: the same link, but it asks for nothing 1.0.70
 * added - its video profile carries bits 0-2 only (no frame times). Everything else of the link is the code of today,
 * which is the point: what a phone app of now sends to a PC that never asked.
 */
public class OlderPcLink extends ControlLink {
    public OlderPcLink(PcConfig config, InetAddress remoteIp, int remotePort, int localPort) {
        super(config, remoteIp, remotePort, localPort);
    }

    /** As 1.0.66's profileFlags: 0 outside the weak tiers, bits 0-2 in them. */
    @Override
    int profileFlags() {
        return super.profileFlags() & (PROFILE_KEY_FRAMES_TWICE | PROFILE_GROUP_PARITY | PROFILE_AVC);
    }
}
