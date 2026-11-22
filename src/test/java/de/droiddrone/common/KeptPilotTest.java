package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static de.droiddrone.common.SessionGate.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The phone keeps who its pilot was across a start of its app anew (KeptPilot, SessionGate.remembered): its PC gets
 * the session back at once, another PC only by the takeover rule, the moved pilot's rule as it is. Before, a restarted
 * app knew no pilot, and the first keyed Connect started a session, armed or not. (KeptPilotEndToEndTest: the virtual
 * fleet's phone, armed in the air.)
 */
class KeptPilotTest {
    private static final String KEY = "DD";
    private static final byte[] PILOT = {100, 64, 1, 2}, OTHER_IP6 = new byte[16];
    private static final long T = 50_000;

    /** [Connect][num i16][client type][key UTF][version i16][session id u32][byId u8]. */
    private static byte[] connect(long id) {
        byte[] k = KEY.getBytes(StandardCharsets.UTF_8);
        byte[] d = new byte[6 + k.length + 2 + 5];
        d[0] = UdpCommon.Connect;
        d[1] = -1;
        d[2] = -1;
        d[3] = 1;
        d[5] = (byte) k.length;
        System.arraycopy(k, 0, d, 6, k.length);
        int at = 6 + k.length + 2;
        Bytes.putInt(d, at, (int) id);
        return d;
    }

    private static SessionGate.Verdict noSession(SessionGate g, boolean samePc, boolean sameAddress, boolean disarmed, long now, long id) {
        byte[] c = connect(id);
        return g.noSession(c, c.length, true, samePc, sameAddress, () -> disarmed, now, SessionGate.sessionId(c, c.length));
    }

    private static SessionGate restarted(long keptId) {
        SessionGate g = new SessionGate(KEY);
        KeptPilot k = KeptPilot.decode(KeptPilot.encode(PILOT, 6286, keptId));
        assertNotNull(k);
        g.remembered(k.sessionId, T);
        return g;
    }

    @Test
    void aKeptLineComesBackAsItWasKept() {
        KeptPilot k = KeptPilot.decode(KeptPilot.encode(PILOT, 51234, 0xFFFFFFFFL));
        assertArrayEquals(PILOT, k.address);
        assertEquals(51234, k.port);
        assertEquals(0xFFFFFFFFL, k.sessionId);
        OTHER_IP6[0] = (byte) 0xfd;
        OTHER_IP6[15] = 7;
        KeptPilot six = KeptPilot.decode(KeptPilot.encode(OTHER_IP6, 1, SessionGate.NO_ID));
        assertArrayEquals(OTHER_IP6, six.address);
        assertEquals(SessionGate.NO_ID, six.sessionId, "a PC that sends no id");
        assertEquals("64400102|6286|0", KeptPilot.encode(PILOT, 6286, 0));
        assertEquals(SessionGate.NO_ID, KeptPilot.decode(KeptPilot.encode(PILOT, 6286, 1L << 32)).sessionId, "no id is made up");
    }

    @Test
    void aLineThatIsNotWholeIsNoPilot() {
        for (String bad : new String[]{null, "", "|", "64400102|6286", "64400102|6286|1|2", "6440010|6286|1", "6440010g|6286|1",
                "64400102|0|1", "64400102|65536|1", "64400102|x|1", "64400102|6286|-2", "64400102|6286|4294967296", "644001020|6286|1"}) {
            assertNull(KeptPilot.decode(bad), String.valueOf(bad));
        }
        assertNull(KeptPilot.encode(null, 6286, 1));
        assertNull(KeptPilot.encode(new byte[5], 6286, 1));
        assertNull(KeptPilot.encode(PILOT, 0, 1));
    }

    @Test
    void afterARestartThePilotsOwnPcGetsItsSessionAtOnceArmedOrNot() {
        SessionGate g = restarted(1000);
        assertEquals(NEW_SESSION, noSession(g, true, true, false, T + 100, 1003), "its next try's id; the FC not known disarmed");
        assertEquals(1003, g.liveSessionId(), "the id of the session it started is the one kept next");
    }

    @Test
    void afterARestartAnotherPcGetsInOnlyByTheTakeoverRule() {
        SessionGate g = restarted(1000);
        long other = 0x5eed0000L;
        assertEquals(SPECTATOR, noSession(g, false, false, false, T + 60_000, other), "never while the FC is not known disarmed");
        assertEquals(SPECTATOR, noSession(g, false, false, true, T + Takeover.PILOT_ALIVE_MS - 1, other),
                "disarmed: the pilot's silence counted from the app's start");
        assertEquals(NEW_SESSION, noSession(g, false, false, true, T + Takeover.PILOT_ALIVE_MS, other));
        // the pilot's address on another port: its own rule (disarmed, or the pilot silent 2 s), as ever
        SessionGate h = restarted(1000);
        assertEquals(SPECTATOR, noSession(h, false, true, false, T + Takeover.SAME_IP_SILENT_MS - 1, other));
        assertEquals(NEW_SESSION, noSession(h, false, true, false, T + Takeover.SAME_IP_SILENT_MS, other));
    }

    @Test
    void thePilotsPcAtANewAddressIsKnownByItsNextIdsAsBefore() {
        // SessionGate.pilotMoved, unchanged: ids 1 to 32 ahead of the live session's, its old address silent 2 s
        assertEquals(SPECTATOR, noSession(restarted(1000), false, false, false, T + Takeover.SAME_IP_SILENT_MS - 1, 1001));
        assertEquals(NEW_SESSION, noSession(restarted(1000), false, false, false, T + Takeover.SAME_IP_SILENT_MS, 1001), "armed or not");
        assertEquals(NEW_SESSION, noSession(restarted(1000), false, false, false, T + Takeover.SAME_IP_SILENT_MS, 1032));
        assertEquals(SPECTATOR, noSession(restarted(1000), false, false, false, T + 60_000, 1033), "not its next ids");
        assertEquals(SPECTATOR, noSession(restarted(1000), false, false, false, T + 60_000, 999), "an older try");
        assertEquals(SPECTATOR, noSession(restarted(1000), false, false, false, T + 60_000, 1000), "the kept session's own");
        assertEquals(SPECTATOR, noSession(restarted(SessionGate.NO_ID), false, false, false, T + 60_000, 1001),
                "a pilot of a PC that sends no id: no ids to follow");
    }

    /** The phone app cannot be run here: its sources are read, as FcPortLatchTest and UpdateWithdrawTest do. */
    @Test
    void thePhoneKeepsItsPilotWhileItFliesAndForgetsItAtDisconnectAndStandby() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        Path flight = root.resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight");
        String udp = text(flight.resolve("Udp.java")), service = text(flight.resolve("DDService.java")), store = text(flight.resolve("PilotStore.java"));
        // read at the service's start, before the link's socket takes anything
        String start = service.substring(service.indexOf("Thread t1 = new Thread(() -> {"));
        assertTrue(start.indexOf("udp.rememberPilot(getApplicationContext());") >= 0
                && start.indexOf("udp.rememberPilot(getApplicationContext());") < start.indexOf("udp.initialize()"));
        String remember = udp.substring(udp.indexOf("void rememberPilot(android.content.Context c) {"));
        remember = remember.substring(0, remember.indexOf("\n    }"));
        assertTrue(remember.contains("PilotStore.read(") && remember.contains("lastPcIp = ") && remember.contains("lastPcPort = ")
                && remember.contains("gate.remembered("), "the address, the port and the session's rules come back");
        // kept once a session, in normal power mode, from the telemetry thread - never the receive thread's sticks
        String tick = udp.substring(udp.indexOf("private void keepPilotTick() {"));
        tick = tick.substring(0, tick.indexOf("\n    }"));
        assertTrue(tick.contains("if (pilotKept || !isConnected() || PowerMode.mode() != PowerMode.NORMAL) return;"));
        assertTrue(tick.contains("KeptPilot.encode(") && tick.contains("gate.liveSessionId()"));
        String telemetry = udp.substring(udp.indexOf("private final Runnable telemetrySenderRun"), udp.indexOf("private final Runnable bufferRun"));
        assertTrue(telemetry.contains("keepPilotTick();"));
        String receive = udp.substring(udp.indexOf("private final Runnable receiverRun"), udp.indexOf("private void handleRcFrame("));
        assertFalse(receive.contains("PilotStore") || receive.contains("keepPilot"), "nothing of it on the receive thread");
        String asks = udp.substring(udp.indexOf("private void sessionAsksAnew() {"));
        assertTrue(asks.substring(0, asks.indexOf("\n    }")).contains("pilotKept = false;"), "every new session is kept anew");
        // forgotten: the PC's Disconnect, and standby
        String disconnect = udp.substring(udp.indexOf("case UdpCommon.Disconnect:"));
        assertTrue(disconnect.substring(0, disconnect.indexOf("break;")).contains("forgetPilot("));
        String standby = service.substring(service.indexOf("void powerStandby(boolean on) {"));
        assertTrue(standby.substring(0, standby.indexOf("\n    }")).contains("if (on) udp.pilotToStandby();"));
        // the app's own storage, written off the caller's thread
        assertTrue(store.contains("Context.MODE_PRIVATE") && store.contains("writer.execute("));
    }

    private static String text(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
