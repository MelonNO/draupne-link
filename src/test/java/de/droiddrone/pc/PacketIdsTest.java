package de.droiddrone.pc;

import de.droiddrone.common.FleetProtocol;
import de.droiddrone.common.Observe;
import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.common.PcAudio;
import de.droiddrone.common.UdpCommon;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The first byte of every datagram on the link's port says what it is. The codes live in eight classes on the PC, and
 * the phone app and the virtual fleet keep copies of their own (the phone's sources aren't compiled here): no two
 * packets may share a code, and every copy must say what the PC's constant says.
 */
class PacketIdsTest {
    /** Every packet code on the link port, by name. */
    static Map<String, Byte> pcCodes() throws Exception {
        Map<String, Byte> c = new LinkedHashMap<>();
        for (Field f : UdpCommon.class.getFields()) {
            if (f.getType() == byte.class && Modifier.isStatic(f.getModifiers())) c.put("UdpCommon." + f.getName(), f.getByte(null));
        }
        c.put("Spectate.PILOT_QUERY", Spectate.PILOT_QUERY);
        c.put("Spectate.PILOT_AT", Spectate.PILOT_AT);
        c.put("Spectate.NO_PILOT", Spectate.NO_PILOT);
        c.put("Spectate.HELLO", Spectate.HELLO);
        c.put("Spectate.WELCOME", Spectate.WELCOME);
        c.put("Spectate.REFUSED", Spectate.REFUSED);
        c.put("Spectate.STREAM", Spectate.STREAM);
        c.put("Spectate.BYE", Spectate.BYE);
        c.put("Spectate.KEEP", Spectate.KEEP);
        c.put("Spectate.SNAPSHOT", Spectate.SNAPSHOT);
        c.put("Spectate.PAUSE", Spectate.PAUSE);
        c.put("Spectate.PILOT_VIEW", Spectate.PILOT_VIEW);
        c.put("Names.NAME_QUERY", Names.NAME_QUERY);
        c.put("Names.NAME_REPLY", Names.NAME_REPLY);
        c.put("Names.NAME_ASSIGN", Names.NAME_ASSIGN);
        c.put("ControlLink.KEY_FRAME_REQUEST", ControlLink.KEY_FRAME_REQUEST);
        c.put("ControlLink.VIDEO_PROFILE", ControlLink.VIDEO_PROFILE);
        c.put("ControlLink.VIDEO_PARITY", ControlLink.VIDEO_PARITY);
        c.put("ControlLink.POWER_MODE", ControlLink.POWER_MODE);
        c.put("ControlLink.SLEEP_HELLO", ControlLink.SLEEP_HELLO);
        c.put("ControlLink.SLEEP_ANSWER", ControlLink.SLEEP_ANSWER);
        c.put("ControlLink.VIDEO_PARITY_GROUP", ControlLink.VIDEO_PARITY_GROUP);
        c.put("ControlLink.SET_BITRATE", ControlLink.SET_BITRATE);
        c.put("ControlLink.REMOTE_UPDATE", ControlLink.REMOTE_UPDATE);
        c.put("ControlLink.IDLE_STICKS", ControlLink.IDLE_STICKS);
        c.put("ControlLink.DETECTION_SET", ControlLink.DETECTION_SET);
        c.put("PcAudio.PACKET", PcAudio.PACKET);
        c.put("Observe.OBSERVE", Observe.OBSERVE);
        c.put("Observe.STATE", Observe.STATE);
        c.put("Observe.STOP", Observe.STOP);
        c.put("FleetProtocol.MODE", FleetProtocol.MODE);
        c.put("OverwatchProtocol.ALERT", OverwatchProtocol.ALERT);
        c.put("OverwatchProtocol.COMMAND", OverwatchProtocol.COMMAND);
        c.put("OverwatchProtocol.PREVIEW", OverwatchProtocol.PREVIEW);
        c.put("OverwatchProtocol.CLIP", OverwatchProtocol.CLIP);
        c.put("OverwatchProtocol.STATUS", OverwatchProtocol.STATUS);
        return c;
    }

    @Test
    void noTwoPacketsShareACode() throws Exception {
        Map<Byte, String> seen = new LinkedHashMap<>();
        List<String> clashes = new ArrayList<>();
        for (Map.Entry<String, Byte> e : pcCodes().entrySet()) {
            String before = seen.putIfAbsent(e.getValue(), e.getKey());
            if (before != null) clashes.add(String.format("0x%02X: %s and %s", e.getValue(), before, e.getKey()));
        }
        assertEquals(List.of(), clashes);
    }

    /** {source file, its constant, the PC's constant it copies}. */
    static final String[][] COPIES = {
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "SPECTATE_PILOT_QUERY", "Spectate.PILOT_QUERY"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "SPECTATE_PILOT_AT", "Spectate.PILOT_AT"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "SPECTATE_NO_PILOT", "Spectate.NO_PILOT"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "NAME_QUERY", "Names.NAME_QUERY"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "NAME_REPLY", "Names.NAME_REPLY"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "NAME_ASSIGN", "Names.NAME_ASSIGN"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "VIDEO_PROFILE", "ControlLink.VIDEO_PROFILE"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "VIDEO_PARITY_GROUP", "ControlLink.VIDEO_PARITY_GROUP"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "SET_BITRATE", "ControlLink.SET_BITRATE"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "VIDEO_PARITY", "ControlLink.VIDEO_PARITY"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "KEY_FRAME_REQUEST", "ControlLink.KEY_FRAME_REQUEST"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/PowerMode.java", "PACKET", "ControlLink.POWER_MODE"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/PowerMode.java", "SLEEP_HELLO", "ControlLink.SLEEP_HELLO"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/PowerMode.java", "SLEEP_ANSWER", "ControlLink.SLEEP_ANSWER"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/RemoteUpdate.java", "PACKET", "ControlLink.REMOTE_UPDATE"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/MotionWatch.java", "PACKET", "ControlLink.DETECTION_SET"},
            {"src/main/java/de/droiddrone/pc/fleet/VirtualDrone.java", "SPECTATE_PILOT_QUERY", "Spectate.PILOT_QUERY"},
            {"src/main/java/de/droiddrone/pc/fleet/VirtualDrone.java", "SPECTATE_PILOT_AT", "Spectate.PILOT_AT"},
            {"src/main/java/de/droiddrone/pc/fleet/VirtualDrone.java", "SPECTATE_NO_PILOT", "Spectate.NO_PILOT"},
            {"src/main/java/de/droiddrone/pc/fleet/VirtualDrone.java", "NAME_QUERY", "Names.NAME_QUERY"},
            {"src/main/java/de/droiddrone/pc/fleet/VirtualDrone.java", "NAME_REPLY", "Names.NAME_REPLY"},
            {"src/main/java/de/droiddrone/pc/fleet/VirtualDrone.java", "NAME_ASSIGN", "Names.NAME_ASSIGN"},
            {"src/main/java/de/droiddrone/pc/fleet/VirtualDrone.java", "KEY_FRAME_REQUEST", "ControlLink.KEY_FRAME_REQUEST"},
            {"src/main/java/de/droiddrone/pc/fleet/VirtualDrone.java", "VIDEO_PROFILE", "ControlLink.VIDEO_PROFILE"},
            {"src/main/java/de/droiddrone/pc/fleet/VirtualDrone.java", "VIDEO_PARITY_GROUP", "ControlLink.VIDEO_PARITY_GROUP"},
            {"src/main/java/de/droiddrone/pc/fleet/VirtualDrone.java", "SET_BITRATE", "ControlLink.SET_BITRATE"},
    };

    @Test
    void thePhoneAppsAndTheVirtualFleetsCopiesMatch() throws Exception {
        Map<String, Byte> pc = pcCodes();
        // the project's root: the test runs in build/test-work
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        List<String> wrong = new ArrayList<>();
        for (String[] c : COPIES) {
            String src = Files.readString(root.resolve(c[0]));
            Matcher m = Pattern.compile("\\b" + c[1] + "\\s*=\\s*(0x[0-9A-Fa-f]+|\\d+)\\s*[;,]").matcher(src);
            if (!m.find()) {
                wrong.add(c[0] + ": " + c[1] + " not found");
                continue;
            }
            int v = Integer.decode(m.group(1));
            if ((byte) v != pc.get(c[2])) wrong.add(String.format("%s: %s = 0x%02X, %s = 0x%02X", c[0], c[1], v, c[2], pc.get(c[2])));
        }
        assertEquals(List.of(), wrong);
    }
}
