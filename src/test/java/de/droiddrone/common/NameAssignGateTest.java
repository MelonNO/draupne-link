package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static de.droiddrone.common.NameAssignGate.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A NAME_ASSIGN is taken only while the flight controller is known disarmed, and only from the pilot's PC while one is
 * connected, else from a PC that asked the phone's name lately (the naming master asks before it names). Before, the
 * phone took one in any state from any address that had the key. (VirtualNameAssignTest: the virtual fleet's phone.)
 */
class NameAssignGateTest {
    private static final boolean DISARMED = true, NOT_KNOWN_DISARMED = false;

    @Test
    void neverWhileTheFlightControllerIsNotKnownDisarmed() {
        for (boolean connected : new boolean[]{false, true}) {
            for (boolean fromPilot : new boolean[]{false, true}) {
                for (boolean asked : new boolean[]{false, true}) {
                    assertEquals(REFUSED_NOT_DISARMED, verdict(NOT_KNOWN_DISARMED, connected, fromPilot, asked));
                }
            }
        }
    }

    @Test
    void withAPilotConnectedOnlyFromThePilotsPc() {
        assertEquals(TAKEN, verdict(DISARMED, true, true, false));
        assertEquals(REFUSED_SENDER, verdict(DISARMED, true, false, true), "another PC that asked: not while a pilot flies it");
    }

    @Test
    void withNoPilotOnlyFromAPcThatAskedThePhonesName() {
        assertEquals(TAKEN, verdict(DISARMED, false, false, true));
        assertEquals(REFUSED_SENDER, verdict(DISARMED, false, false, false));
    }

    @Test
    void theAskersBookSaysWhoAskedLately() {
        KnownPcs<String> asked = KnownPcs.forNotice();
        assertFalse(asked.askedWithin("pc", 1000));
        asked.asked("pc", 1000);
        assertTrue(asked.askedWithin("pc", 1000 + KnownPcs.NOTICE_MS));
        assertFalse(asked.askedWithin("pc", 1001 + KnownPcs.NOTICE_MS));
        assertFalse(asked.askedWithin("other", 1000));
        assertFalse(asked.askedWithin(null, 1000));
    }

    /** The phone app cannot be run here: its sources are read, as FcPortLatchTest and UpdateWithdrawTest do. */
    @Test
    void thePhoneAsksTheRuleBeforeItTakesAName() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        String udp = new String(Files.readAllBytes(root.resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Udp.java")), StandardCharsets.UTF_8);
        String answer = udp.substring(udp.indexOf("private void answerName(DatagramPacket packet, byte[] d, int len) {"));
        answer = answer.substring(0, answer.indexOf("\n    }"));
        int rule = answer.indexOf("NameAssignGate.verdict(fcDisarmed.getAsBoolean(), connected, connected && from.equals(udpSender.getIp()), namedBy.askedWithin(from, now))");
        assertTrue(rule >= 0, "asked with the FC's state, the pilot and the askers' book");
        assertTrue(rule < answer.indexOf("MainActivity.setPhoneName(name);"), "before the name is taken");
        assertTrue(answer.contains("if (v == NameAssignGate.TAKEN"));
        // only a query puts its sender in the askers' books - an assign never vouches for the next one
        int note = answer.indexOf("namedBy.asked(");
        assertTrue(note > 0 && answer.lastIndexOf("if (d[0] == NAME_QUERY) {", note) >= 0 && answer.indexOf("namedBy.asked(") == answer.lastIndexOf("namedBy.asked("));
    }
}
