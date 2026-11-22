package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The phone's settings from the PC no longer include the serial port path, the choice of the phone's own serial port,
 * nor the MAVLink UDP bridge: upstream's phone app took them from every Config packet and saved them. They stay as
 * they are on the phone; the packet is read past them as before. (ConfigPacketTest: what DRAUPNE Link sends.)
 */
class PhoneOwnConfigTest {
    private static final PhoneOwnConfig PHONE = PhoneOwnConfig.defaults();

    @Test
    void aConfigAskingForWhatThePhoneHasLeavesNothingToSay() {
        assertNull(PHONE.notTaken(PhoneOwnConfig.defaults()));
        assertNull(PHONE.notTaken(new PhoneOwnConfig(false, "/dev/ttyS0", SettingsCommon.MavlinkUdpBridge.disabled, "", 14550)),
                "upstream's defaults, as the phone has them");
        assertNull(PHONE.notTaken(new PhoneOwnConfig(false, "/dev/ttyS0", SettingsCommon.MavlinkUdpBridge.disabled, null, 14550)),
                "an empty address reads as null from the packet (DataReader.readUTF): the phone's \"\"");
    }

    @Test
    void aSerialPathWithALineBreakAndTheBridgeOnAreNotTakenAndNotRepeated() {
        PhoneOwnConfig asked = new PhoneOwnConfig(true, "/dev/ttyS1\nreboot", SettingsCommon.MavlinkUdpBridge.specificIp, "203.0.113.9", 14551);
        String said = PHONE.notTaken(asked);
        assertEquals("the FC's serial port and the MAVLink bridge", said);
        assertFalse(said.contains("ttyS1") || said.contains("reboot") || said.contains("203.0.113"), "the names of the settings, never their values");
        // the phone's own stay as they were
        assertFalse(PHONE.useNativeSerialPort);
        assertEquals("/dev/ttyS0", PHONE.nativeSerialPort);
        assertEquals(SettingsCommon.MavlinkUdpBridge.disabled, PHONE.mavlinkUdpBridge);
    }

    @Test
    void eachSettingAloneIsNamed() {
        assertEquals("the FC's serial port", PHONE.notTaken(new PhoneOwnConfig(true, "/dev/ttyS0", 0, "", 14550)));
        assertEquals("the FC's serial port", PHONE.notTaken(new PhoneOwnConfig(false, "/dev/ttyUSB0", 0, "", 14550)));
        assertEquals("the FC's serial port", PHONE.notTaken(new PhoneOwnConfig(false, null, 0, "", 14550)), "a path that could not be read");
        assertEquals("the MAVLink bridge", PHONE.notTaken(new PhoneOwnConfig(false, "/dev/ttyS0", SettingsCommon.MavlinkUdpBridge.connectedIp, "", 14550)));
        assertEquals("the MAVLink bridge", PHONE.notTaken(new PhoneOwnConfig(false, "/dev/ttyS0", 0, "198.51.100.1", 14550)));
        assertEquals("the MAVLink bridge", PHONE.notTaken(new PhoneOwnConfig(false, "/dev/ttyS0", 0, "", 1)));
    }

    /** The phone app cannot be run here: its sources are read, as FcPortLatchTest and UpdateWithdrawTest do. */
    @Test
    void thePhonesConfigReadsPastThemAndKeepsItsOwn() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        String config = new String(Files.readAllBytes(root.resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Config.java")), StandardCharsets.UTF_8);
        String received = config.substring(config.indexOf("public int processReceivedConfig(DataReader buffer){"));
        received = received.substring(0, received.indexOf("\n    }"));
        for (String field : new String[]{"useNativeSerialPort", "nativeSerialPort", "mavlinkUdpBridge", "mavlinkUdpBridgeIp", "mavlinkUdpBridgePort"}) {
            assertFalse(received.contains("this." + field + " = "), "a Config from the network sets " + field);
            assertTrue(config.contains("        " + field + " = preferences."), field + " is the phone's own, loaded from its settings");
        }
        assertFalse(received.contains("mavlinkUdpBridgeConfigChanged = true"), "the bridge is never restarted for a Config");
        // read past in upstream's order: the fields after them are still where the packet has them
        int bridgeIp = received.indexOf("String mavlinkUdpBridgeIp = buffer.readUTF();");
        assertTrue(received.indexOf("boolean useNativeSerialPort = buffer.readBoolean();") > 0
                && received.indexOf("String nativeSerialPort = buffer.readUTF();") > received.indexOf("boolean useNativeSerialPort = buffer.readBoolean();")
                && received.indexOf("int fcProtocol = buffer.readByte();") > received.indexOf("String nativeSerialPort = buffer.readUTF();")
                && bridgeIp > received.indexOf("int mavlinkUdpBridge = buffer.readUnsignedByteAsInt();")
                && received.indexOf("int mavlinkUdpBridgePort = buffer.readUnsignedShortAsInt();") > bridgeIp, "every field still read, in upstream's order");
        assertTrue(received.contains(".notTaken(new PhoneOwnConfig(useNativeSerialPort, nativeSerialPort, mavlinkUdpBridge, mavlinkUdpBridgeIp, mavlinkUdpBridgePort))"),
                "what was asked for is held against the phone's own, for its one log line");
    }
}
