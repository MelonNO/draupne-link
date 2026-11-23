package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The raw MAVLink packet (26) a session may send: upstream's phone app wrote what it carried to the flight controller's
 * port as it came, on an MSP flight controller too, past the stick path's rules. Now the phone writes nothing to the
 * flight controller that is not a stick or a request of its own, unless it is set up to carry MAVLink for it.
 */
class MavlinkRawTest {
    private static final int DISABLED = SettingsCommon.MavlinkUdpBridge.disabled, CONNECTED_IP = SettingsCommon.MavlinkUdpBridge.connectedIp,
            SPECIFIC_IP = SettingsCommon.MavlinkUdpBridge.specificIp, FROM_CONTROL = SettingsCommon.MavlinkUdpBridge.redirectFromControlDevice;

    @Test
    void anMspFlightControllerGetsNoneOfItWhateverTheBridgeSays() {
        for (int bridge : new int[]{DISABLED, CONNECTED_IP, SPECIFIC_IP, FROM_CONTROL, 99, -1}) {
            assertFalse(MavlinkRaw.mayWrite(false, bridge), "bridge " + bridge);
        }
    }

    @Test
    void aMavlinkFlightControllerGetsItOnlyWithThePhonesBridgeSetToCarryIt() {
        assertTrue(MavlinkRaw.mayWrite(true, FROM_CONTROL));
        assertFalse(MavlinkRaw.mayWrite(true, DISABLED), "the default");
        assertFalse(MavlinkRaw.mayWrite(true, CONNECTED_IP), "a bridge of the phone's own socket: not the link's packets");
        assertFalse(MavlinkRaw.mayWrite(true, SPECIFIC_IP));
    }

    /** The phone app cannot be run here: its sources are read, as FcPortLatchTest and UpdateWithdrawTest do. */
    @Test
    void thePhoneAsksTheRuleBeforeItWritesThePacketsMavlink() throws Exception {
        Path root = root();
        String udp = text(root.resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java"));
        assertEquals(1, count(udp, "mavlink.processReceivedUdpData("), "the packet's one way to the flight controller");
        String raw = udp.substring(udp.indexOf("case UdpCommon.MavlinkRawPacket:"));
        raw = raw.substring(0, raw.indexOf("mavlink.processReceivedUdpData("));
        assertTrue(raw.contains("MavlinkRaw.mayWrite(mavlink.isInitialized(), config.getMavlinkUdpBridge())"),
                "asked with the flight controller's protocol and the phone's own bridge setting, before the write");
    }

    @Test
    void nothingOfThePcsNeedsIt() throws Exception {
        Path root = root();
        // DRAUPNE Link (and its virtual fleet's phone) never sends nor takes the packet
        try (Stream<Path> files = Files.walk(root.resolve("src/main/java"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                assertFalse(text(f).contains("MavlinkRawPacket"), root.relativize(f) + " uses the raw MAVLink packet");
            }
        }
        // its Config asks for the protocol by AUTO (fleet AP-1: MSP or MAVLink by what the FC is) and the bridge off
        String link = text(root.resolve("src/main/java/de/droiddrone/pc/ControlLink.java"));
        String config = link.substring(link.indexOf("byte[] buildConfigPacket()"));
        config = config.substring(0, config.indexOf("\n    }"));
        assertTrue(config.contains("writeByte(FcCommon.FC_PROTOCOL_AUTO)"));
        assertTrue(config.contains("writeByte(SettingsCommon.MavlinkUdpBridge.disabled)"));
    }

    private static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        return root;
    }

    private static int count(String s, String what) {
        return s.split(java.util.regex.Pattern.quote(what), -1).length - 1;
    }

    private static String text(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
