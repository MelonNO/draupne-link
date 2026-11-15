package de.droiddrone.pc;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.SessionGate;
import de.droiddrone.common.SettingsCommon;
import de.droiddrone.common.Takeover;
import de.droiddrone.common.UdpCommon;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the phones and PCs in the field do with the bytes this version sends: their own parsing, copied here from the
 * released code (the tag and file are named at each copy), run over the packets as they are built now. A phone on
 * pc.52 or older must take a 1.0.69 PC's Connect and video profile exactly as it took 1.0.66's - it is updated through
 * that very link - and a PC on 1.0.66 must make nothing of what a newer phone could send, and take a 1.0.69
 * spectator's HELLO as a 1.0.66 one's.
 */
class OldPeersTest {
    private static final String KEY = "DD";

    private static ControlLink newLink() throws Exception {
        return new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), 1, 0); // never started: its packets only
    }

    // ------------------------------------------------------------------ the Connect with its session id

    /**
     * upstream Common/ReceiverBuffer.addPacket (commit 473608d, every phone app up to pc.25 and upstream's relay; the
     * overlay's copies of pc.26 - pc.52 read the same fields in the same way): what it takes of a Connect, and the
     * copy it keeps - which checkPacket sends back whole as the answer ({@code udpSender.sendPacket(packet.data)}).
     * @return the bytes that go back as the answer, or null for a Connect it doesn't take
     */
    private static byte[] upstreamTakesConnect(byte[] datagram, int packetSize, String controlKey) {
        byte[] data = new byte[packetSize];
        System.arraycopy(datagram, 0, data, 0, packetSize);
        DataReader buffer = new DataReader(data, true);
        byte packetName = buffer.readByte();
        if (!UdpCommon.isPacketNumbered(packetName)) return null;
        short num = buffer.readShort();
        if (num < -1) return null;
        if (packetName != UdpCommon.Connect) return null;
        byte clientType = buffer.readByte();
        String key = buffer.readUTF();
        if (key == null) return null;
        if ((clientType == 0 || clientType == 1) && key.equals(controlKey)) return data;
        return null;
    }

    /** DRAUPNE Flight pc.19 - pc.41, Udp.keyMatches (flight-2.3.0-pc.19 Udp.java:353; SessionGate.keyMatches from pc.42). */
    private static boolean keyMatchesPc19(String key, byte[] d, int len, int keyAt) {
        if (len < keyAt + 2) return false;
        int n = ((d[keyAt] & 0xff) << 8) | (d[keyAt + 1] & 0xff);
        return keyAt + 2 + n <= len && key.equals(new String(d, keyAt + 2, n, StandardCharsets.UTF_8));
    }

    /** DRAUPNE Flight pc.11 - pc.18, Udp.answerSpectator's own check of a Connect's key (flight-2.3.0-pc.11 Udp.java:329). */
    private static boolean spectatorKeyPc11(String key, byte[] d, int len) {
        int keyAt = 4;
        if (len < keyAt + 2) return false;
        int n = ((d[keyAt] & 0xff) << 8) | (d[keyAt + 1] & 0xff);
        if (keyAt + 2 + n > len || !key.equals(new String(d, keyAt + 2, n, StandardCharsets.UTF_8))) return false;
        return true;
    }

    /** The phone's gate as released in pc.42 - pc.52 (SessionGate at 7e11612), to the line. */
    private static final class GatePc52 {
        static final long SAME_SESSION_MS = 1000;
        private final String key;
        private long lastPilotMs, sessionStartedMs;

        GatePc52(String key) {
            this.key = key;
        }

        boolean keyMatches(byte[] d, int len, int keyAt) {
            if (len < keyAt + 2) return false;
            int n = ((d[keyAt] & 0xff) << 8) | (d[keyAt + 1] & 0xff);
            return keyAt + 2 + n <= len && key.equals(new String(d, keyAt + 2, n, StandardCharsets.UTF_8));
        }

        long silentMs(long now) {
            return now - lastPilotMs;
        }

        SessionGate.Verdict fromPilot(byte[] d, int len, long now) {
            lastPilotMs = now;
            if (d[0] != UdpCommon.Connect || !keyMatches(d, len, 4)) return SessionGate.Verdict.TAKE;
            if (now - sessionStartedMs < SAME_SESSION_MS) return SessionGate.Verdict.SAME_SESSION;
            sessionStartedMs = now;
            return SessionGate.Verdict.NEW_SESSION;
        }

        SessionGate.Verdict fromOther(byte[] d, int len, boolean sameAddress, boolean disarmed, long now) {
            if (d[0] != UdpCommon.Connect) return SessionGate.Verdict.SPECTATOR;
            if (!keyMatches(d, len, 4)) return SessionGate.Verdict.WRONG_KEY;
            if (!Takeover.allowed(sameAddress, disarmed, silentMs(now))) return SessionGate.Verdict.REFUSED;
            sessionStartedMs = now;
            return SessionGate.Verdict.NEW_SESSION;
        }

        SessionGate.Verdict noSession(byte[] d, int len, boolean pilotKnown, boolean samePc, boolean sameAddress,
                                      BooleanSupplier disarmed, long now) {
            if (d[0] != UdpCommon.Connect) return SessionGate.Verdict.DROP;
            if (!keyMatches(d, len, 4)) return SessionGate.Verdict.WRONG_KEY;
            if (pilotKnown && !samePc && !Takeover.allowed(sameAddress, disarmed.getAsBoolean(), silentMs(now))) return SessionGate.Verdict.SPECTATOR;
            lastPilotMs = now;
            sessionStartedMs = now;
            return SessionGate.Verdict.NEW_SESSION;
        }
    }

    @Test
    void theConnectIsTheOldOneWithFiveBytesBehindIt() throws Exception {
        byte[] now = newLink().buildConnectPacket(1000, 0);
        byte[] before = OlderPcLink.connect166(PcConfig.defaults().getKey());
        assertEquals(before.length + 5, now.length, "[sessionId u32][byId u8]");
        assertArrayEquals(before, Arrays.copyOf(now, before.length), "everything of before, byte for byte, in front");
        assertEquals(0, now[now.length - 1], "the PC sends byId 0");
        assertEquals(Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(now, before.length, 4).getInt()), SessionGate.sessionId(now, now.length));
        assertTrue(now.length < 64, "a small datagram: every phone's receive buffer (1100 bytes) takes it whole");
    }

    @Test
    void everyReleasedPhoneTakesTheLongerConnectAndSendsItBackWhole() throws Exception {
        String key = PcConfig.defaults().getKey();
        byte[] connect = newLink().buildConnectPacket(1000, 0);
        // into a receive buffer with an older, longer packet's bytes behind it (the phone's 1100-byte array is reused)
        byte[] datagram = new byte[UdpCommon.packetLength];
        Arrays.fill(datagram, (byte) 0x5a);
        System.arraycopy(connect, 0, datagram, 0, connect.length);
        // pc.1 - pc.52 and upstream's relay: ReceiverBuffer takes type, number, client type and key - and answers with the lot
        byte[] answer = upstreamTakesConnect(datagram, connect.length, key);
        assertNotNull(answer, "taken");
        assertArrayEquals(connect, answer, "sent back whole: the session id comes home");
        assertNull(upstreamTakesConnect(datagram, connect.length, "another key"), "the key is still checked");
        // the phone's own checks in front of the buffer
        assertTrue(keyMatchesPc19(key, datagram, connect.length, 4), "pc.19 - pc.41");
        assertTrue(spectatorKeyPc11(key, datagram, connect.length), "pc.11 - pc.18 (a second PC's Connect: PILOT_AT as ever)");
        assertTrue(new GatePc52(key).keyMatches(datagram, connect.length, 4), "pc.42 - pc.52");
        assertFalse(keyMatchesPc19("XX", datagram, connect.length, 4));
        // upstream's relay (Server/Udp.receiverRun) reads the fields one after the other and stops at the version
        DataReader r = new DataReader(Arrays.copyOf(datagram, connect.length), true);
        assertEquals(UdpCommon.Connect, r.readByte());
        assertEquals(-1, r.readShort());
        assertEquals(1, r.readByte(), "the controller");
        assertEquals(key, r.readUTF());
        assertEquals(SettingsCommon.versionCompatibleCode, r.readShort());
        assertEquals(5, r.getRemaining(), "what it never looks at");
    }

    /** The released gate over Connects with and without the tail, the same moments: verdict for verdict the same. */
    @Test
    void theReleasedGateJudgesTheLongerConnectAsTheShortOne() throws Exception {
        String key = PcConfig.defaults().getKey();
        ControlLink link = newLink();
        byte[] old = OlderPcLink.connect166(key);
        byte[] wrongNew = Arrays.copyOf(OlderPcLink.connect166("XX"), old.length + 5);
        byte[] wrongOld = OlderPcLink.connect166("XX");
        byte[] ping = {UdpCommon.Ping, 1, 0, 0, 0, 0, 0, 0, 0, 0};
        GatePc52 a = new GatePc52(key), b = new GatePc52(key);
        Random r = new Random(11);
        long now = 1000;
        for (int i = 0; i < 20_000; i++) {
            now += r.nextInt(4) == 0 ? r.nextInt(12_000) : r.nextInt(400);
            int what = r.nextInt(3);
            // the 1.0.69 PC's Connect of this moment (its id changes as the tries go by) against 1.0.66's
            byte[] longer = what == 0 ? link.buildConnectPacket(now, r.nextInt(3) == 0 ? 1800 : 0) : what == 1 ? wrongNew : ping;
            byte[] shorter = what == 0 ? old : what == 1 ? wrongOld : ping;
            boolean sameAddress = r.nextBoolean(), disarmed = r.nextBoolean(), pilotKnown = r.nextBoolean(), samePc = sameAddress && r.nextBoolean();
            switch (r.nextInt(3)) {
                case 0 -> assertEquals(a.fromPilot(shorter, shorter.length, now), b.fromPilot(longer, longer.length, now), "step " + i);
                case 1 -> assertEquals(a.fromOther(shorter, shorter.length, sameAddress, disarmed, now),
                        b.fromOther(longer, longer.length, sameAddress, disarmed, now), "step " + i);
                default -> assertEquals(a.noSession(shorter, shorter.length, pilotKnown, samePc, sameAddress, () -> disarmed, now),
                        b.noSession(longer, longer.length, pilotKnown, samePc, sameAddress, () -> disarmed, now), "step " + i);
            }
            assertEquals(a.silentMs(now), b.silentMs(now));
        }
    }

    /** And the gate of now, given an older PC's Connect, is the released one: the same packets through both. */
    @Test
    void anOlderPcsConnectIsJudgedByTheGateOfNowAsByTheReleasedOne() throws Exception {
        String key = PcConfig.defaults().getKey();
        byte[] old = OlderPcLink.connect166(key), wrong = OlderPcLink.connect166("XX");
        byte[] ping = {UdpCommon.Ping, 1, 0, 0, 0, 0, 0, 0, 0, 0};
        GatePc52 released = new GatePc52(key);
        SessionGate gate = new SessionGate(key);
        Random r = new Random(12);
        long now = 1000;
        for (int i = 0; i < 20_000; i++) {
            now += r.nextInt(4) == 0 ? r.nextInt(12_000) : r.nextInt(400);
            byte[] d = new byte[][]{old, old, wrong, ping}[r.nextInt(4)];
            boolean sameAddress = r.nextBoolean(), disarmed = r.nextBoolean(), pilotKnown = r.nextBoolean(), samePc = sameAddress && r.nextBoolean();
            long id = SessionGate.sessionId(d, d.length); // as the phone's receive thread reads it: none
            assertEquals(SessionGate.NO_ID, id);
            byte[] before = d.clone();
            switch (r.nextInt(3)) {
                case 0 -> assertEquals(released.fromPilot(d, d.length, now), gate.fromPilot(d, d.length, now, id), "step " + i);
                case 1 -> assertEquals(released.fromOther(d, d.length, sameAddress, disarmed, now),
                        gate.fromOther(d, d.length, sameAddress, disarmed, now, id), "step " + i);
                default -> assertEquals(released.noSession(d, d.length, pilotKnown, samePc, sameAddress, () -> disarmed, now),
                        gate.noSession(d, d.length, pilotKnown, samePc, sameAddress, () -> disarmed, now, id), "step " + i);
            }
            assertEquals(released.silentMs(now), gate.silentMs(now));
            SessionGate.markAnswer(d, d.length); // what the phone does before its buffer answers
            assertArrayEquals(before, d, "an older PC's Connect goes back as it came, to the byte");
        }
    }

    // ------------------------------------------------------------------ the video profile's new bits

    /** DRAUPNE Flight pc.15 - pc.25, Udp.processData case VIDEO_PROFILE (flight-2.3.0-pc.15 Udp.java:454): bit 0 alone. */
    private static boolean[] profilePc15(byte[] packet) {
        DataReader buffer = new DataReader(packet, true);
        buffer.readByte();
        boolean twice = buffer.getRemaining() > 0 && (buffer.readByte() & 1) != 0;
        return new boolean[]{twice};
    }

    /** DRAUPNE Flight pc.26 - pc.52 (7e11612 Udp.java:1689): bits 0-2 by mask. */
    private static boolean[] profilePc52(byte[] packet) {
        DataReader buffer = new DataReader(packet, true);
        buffer.readByte();
        int flags = buffer.getRemaining() > 0 ? buffer.readByte() : 0;
        boolean twice = (flags & 1) != 0;
        boolean groups = (flags & 2) != 0;
        boolean avc = (flags & 4) != 0;
        return new boolean[]{twice, groups, avc};
    }

    @Test
    void anOlderPhoneReadsTheProfileAsBefore() {
        // every profile 1.0.66 could send (bits 0-2) with every combination of what 1.0.69 adds on top (bits 3 and 4)
        for (int old = 0; old < 8; old++) {
            for (int added : new int[]{0, ControlLink.PROFILE_FRAME_TIMES, 16, ControlLink.PROFILE_FRAME_TIMES | 16}) {
                byte[] before = {ControlLink.VIDEO_PROFILE, (byte) old}, now = {ControlLink.VIDEO_PROFILE, (byte) (old | added)};
                assertArrayEquals(profilePc15(before), profilePc15(now), "pc.15 - pc.25, flags " + (old | added));
                assertArrayEquals(profilePc52(before), profilePc52(now), "pc.26 - pc.52, flags " + (old | added));
            }
        }
    }

    // ------------------------------------------------------------------ what an older PC makes of the new packets

    /** DRAUPNE Link 1.0.66, ControlLink.handleVideoFragment's test (7e11612 ControlLink.java:664): is it a video packet? */
    private static boolean videoPacket166(byte first) {
        return first == UdpCommon.VideoFrame || first == UdpCommon.KeyFrame || first == ControlLink.VIDEO_PARITY || first == ControlLink.VIDEO_PARITY_GROUP;
    }

    // ------------------------------------------------------------------ the spectator's HELLO with its flags

    /**
     * DRAUPNE Link 1.0.17 - 1.0.66, a pilot PC taking a spectator's HELLO: ControlLink.onForeignPacket routes it by its
     * first byte alone (7e11612 ControlLink.java:558), SpectatorHost.onHello takes the key with Spectate.readKey and
     * compares it (7e11612 SpectatorHost.java:194-195; Spectate.java:130-135, the same since 108f0ab).
     * @return the key it took, or null for a HELLO it drops
     */
    private static String pilotTakesHello166(byte[] d, int len, String ownKey) {
        if (len < 1 || d[0] != Spectate.HELLO) return null;
        if (len < 3) return null;
        int n = ((d[1] & 0xff) << 8) | (d[2] & 0xff);
        if (3 + n > len) return null;
        String k = new String(d, 3, n, StandardCharsets.UTF_8);
        return k.equals(ownKey) ? k : null;
    }

    /** DRAUPNE Link 1.0.17 - 1.0.66, SpectatorClient's HELLO (7e11612 SpectatorClient.java:324): Spectate.keyed. */
    private static byte[] hello166(String key) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        out.writeByte(Spectate.HELLO);
        out.writeUTF(key);
        return bytes.toByteArray();
    }

    @Test
    void anOlderPilotPcTakesTheSpectatorsHelloWithItsFlagsAsBefore() throws Exception {
        for (String key : new String[]{KEY, "A-LONGER-KEY-0123456789", ""}) {
            byte[] old = hello166(key), now = Spectate.hello(key, Spectate.HELLO_MENDS_RS);
            assertArrayEquals(old, Arrays.copyOf(now, old.length), "1.0.66's HELLO, the flags behind it");
            assertEquals(old.length + 1, now.length);
            assertEquals(key, pilotTakesHello166(old, old.length, key));
            assertEquals(key, pilotTakesHello166(now, now.length, key), "a 1.0.66 pilot PC takes it as the old one");
            assertNull(pilotTakesHello166(now, now.length, key + "X"), "and drops it for another key, as the old one");
            // in a receive buffer longer than the datagram, as the link reads it
            byte[] buf = Arrays.copyOf(now, 1500);
            assertEquals(key, pilotTakesHello166(buf, now.length, key));
        }
        // with no flag set the HELLO is 1.0.66's to the byte; this version reads no flags from it (an older spectator)
        assertArrayEquals(hello166(KEY), Spectate.hello(KEY, 0));
        byte[] old = hello166(KEY);
        assertEquals(0, Spectate.helloFlags(old, old.length));
        byte[] now = Spectate.hello(KEY, Spectate.HELLO_MENDS_RS);
        assertEquals(Spectate.HELLO_MENDS_RS, Spectate.helloFlags(now, now.length));
        assertEquals(KEY, Spectate.readKey(now, now.length), "this version takes the key as before");
    }

    @Test
    void theNewPacketsAreNoneAnOlderPeerKnows() {
        // FRAME_TIME and VIDEO_PARITY_RS are sent to a PC that asked only (PictureDelayEndToEndTest, the parity's
        // tests). Should one reach a 1.0.66 PC all the same: no video packet, not numbered - it goes through the
        // buffer to processData's default, which does nothing (ControlLink.java:1014 there); VIDEO_FEC likewise on
        // a phone (Udp.processData has no default)
        for (byte id : new byte[]{de.droiddrone.common.PacketIds.FRAME_TIME, de.droiddrone.common.PacketIds.VIDEO_FEC, de.droiddrone.common.PacketIds.VIDEO_PARITY_RS}) {
            assertFalse(videoPacket166(id), "0x" + Integer.toHexString(id));
            assertFalse(UdpCommon.isPacketNumbered(id), "unnumbered: nothing is acknowledged or asked for again");
            assertFalse(UdpCommon.isSendPacketReceived(id));
            assertNotEquals(UdpCommon.RcFrame, id);
        }
    }
}
