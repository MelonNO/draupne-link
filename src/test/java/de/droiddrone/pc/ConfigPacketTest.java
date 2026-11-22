package de.droiddrone.pc;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.PhoneOwnConfig;
import de.droiddrone.common.SettingsCommon;
import de.droiddrone.common.UdpCommon;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DRAUPNE Link's Config, read field by field as the phone's Config reads it (upstream's order): what it asks of the
 * settings that stay the phone's own (PhoneOwnConfig) is what the phone has - nothing changes for a PC that sends them,
 * and the phone has nothing to say about them.
 */
class ConfigPacketTest {
    @Test
    void thePcsConfigAsksForThePhonesOwnSerialPortAndBridgeAsTheyAre() throws Exception {
        ControlLink link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), 1, 0); // never started: no socket
        byte[] d = link.buildConfigPacket();
        DataReader b = new DataReader(d, true);
        assertEquals(UdpCommon.Config, b.readByte());
        b.readShort(); // its number
        assertEquals(SettingsCommon.versionCompatibleCode, b.readShort());
        for (int i = 0; i < SettingsCommon.maxCamerasCount; i++) {
            if (i > 0) b.readBoolean();
            b.readUTF();
            b.readShort();
            b.readShort();
            b.readShort();
            b.readShort();
            b.readBoolean();
            b.readByte();
            b.readBoolean();
        }
        b.readUnsignedByteAsInt(); // video
        b.readBoolean();
        b.readByte();
        b.readUnsignedByteAsInt();
        b.readBoolean(); // audio
        b.readShort();
        b.readBoolean();
        b.readShort();
        b.readUnsignedByteAsInt(); // fc
        b.readUnsignedByteAsInt();
        b.readInt();
        b.readByte();
        boolean useNative = b.readBoolean();
        String path = b.readUTF();
        b.readByte();
        b.readUnsignedByteAsInt();
        b.readUnsignedByteAsInt();
        int bridge = b.readUnsignedByteAsInt();
        String bridgeIp = b.readUTF();
        int bridgePort = b.readUnsignedShortAsInt();
        assertEquals(0, b.getRemaining(), "the whole packet read");
        PhoneOwnConfig asked = new PhoneOwnConfig(useNative, path, bridge, bridgeIp, bridgePort);
        assertNull(PhoneOwnConfig.defaults().notTaken(asked), "a phone with upstream's defaults is asked for nothing else");
        assertFalse(useNative);
        assertEquals(SettingsCommon.MavlinkUdpBridge.disabled, bridge);
    }
}
