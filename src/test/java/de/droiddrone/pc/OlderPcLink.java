package de.droiddrone.pc;

import de.droiddrone.common.SettingsCommon;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.common.UdpPacketData;

import java.io.IOException;
import java.net.InetAddress;

/**
 * A PC before 1.0.69 as the phone sees it on the wire, for the tests: the same link, but it asks for nothing 1.0.69
 * added - its video profile carries bits 0-2 only (no frame times, no RS parity) and no VIDEO_FEC goes out, its
 * Connect ends with the version (no session id). Everything else of the link is the code of today, which is the point: what a phone app of now sends to a PC
 * that never asked.
 */
public class OlderPcLink extends ControlLink {
    private final String key;

    public OlderPcLink(PcConfig config, InetAddress remoteIp, int remotePort, int localPort) {
        super(config, remoteIp, remotePort, localPort);
        key = config.getKey();
    }

    /** As 1.0.66's profileFlags: 0 outside the weak tiers, bits 0-2 in them. */
    @Override
    int profileFlags() {
        return super.profileFlags() & (PROFILE_KEY_FRAMES_TWICE | PROFILE_GROUP_PARITY | PROFILE_AVC);
    }

    /** 1.0.66 mended with the XOR groups only: no profile bit 4, no VIDEO_FEC. */
    @Override
    boolean mendsRs() {
        return false;
    }

    /** As 1.0.66's sendConnect, to the byte: [8][num i16 = -1][client type u8 = 1][key UTF][version i16]. */
    @Override
    byte[] buildConnectPacket(long now, long roundMs) throws IOException {
        return connect166(key);
    }

    /** The Connect of DRAUPNE Link 1.0.66 and before (ControlLink.sendConnect at 7e11612). */
    public static byte[] connect166(String key) throws IOException {
        UdpPacketData packetData = new UdpPacketData(UdpCommon.Connect, -1);
        packetData.daos.writeByte(1);
        packetData.daos.writeUTF(key);
        packetData.daos.writeShort(SettingsCommon.versionCompatibleCode);
        return packetData.getData();
    }
}
