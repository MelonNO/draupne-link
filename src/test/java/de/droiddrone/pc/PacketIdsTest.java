package de.droiddrone.pc;

import de.droiddrone.common.FleetProtocol;
import de.droiddrone.common.Observe;
import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.common.PacketIds;
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
 * The first byte of every datagram on the link's port says what it is. The codes live in PacketIds, UdpCommon and the
 * Common protocol classes; the phone app still keeps copies of its own (its sources aren't compiled here): no two
 * packets may share a code, and every copy must say what PacketIds says.
 */
class PacketIdsTest {
    /** Every packet code on the link port, by name. */
    static Map<String, Byte> pcCodes() throws Exception {
        Map<String, Byte> c = new LinkedHashMap<>();
        for (Class<?> k : new Class<?>[]{UdpCommon.class, PacketIds.class}) {
            for (Field f : k.getFields()) {
                if (f.getType() == byte.class && Modifier.isStatic(f.getModifiers())) c.put(k.getSimpleName() + "." + f.getName(), f.getByte(null));
            }
        }
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
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "SPECTATE_PILOT_QUERY", "PacketIds.SPECTATE_PILOT_QUERY"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "SPECTATE_PILOT_AT", "PacketIds.SPECTATE_PILOT_AT"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "SPECTATE_NO_PILOT", "PacketIds.SPECTATE_NO_PILOT"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "NAME_QUERY", "PacketIds.NAME_QUERY"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "NAME_REPLY", "PacketIds.NAME_REPLY"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "NAME_ASSIGN", "PacketIds.NAME_ASSIGN"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "VIDEO_PROFILE", "PacketIds.VIDEO_PROFILE"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "VIDEO_PARITY_GROUP", "PacketIds.VIDEO_PARITY_GROUP"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "SET_BITRATE", "PacketIds.SET_BITRATE"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "VIDEO_PARITY", "PacketIds.VIDEO_PARITY"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java", "KEY_FRAME_REQUEST", "PacketIds.KEY_FRAME_REQUEST"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/PowerMode.java", "PACKET", "PacketIds.POWER_MODE"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/PowerMode.java", "SLEEP_HELLO", "PacketIds.SLEEP_HELLO"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/PowerMode.java", "SLEEP_ANSWER", "PacketIds.SLEEP_ANSWER"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/RemoteUpdate.java", "PACKET", "PacketIds.REMOTE_UPDATE"},
            {"flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/MotionWatch.java", "PACKET", "PacketIds.DETECTION_SET"},
    };

    @Test
    void thePhoneAppsCopiesMatch() throws Exception {
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
