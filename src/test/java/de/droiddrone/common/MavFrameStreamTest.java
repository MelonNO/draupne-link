package de.droiddrone.common;

import com.MAVLink.MAVLinkPacket;
import com.MAVLink.Parser;
import com.MAVLink.common.msg_command_ack;
import com.MAVLink.common.msg_mission_request_int;
import com.MAVLink.minimal.msg_heartbeat;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The phone's FC bytes as a stream: frames split across two USB reads at every byte position, a read that begins
 * inside a frame, noise with start bytes in it. The check is MAVLink's own CRC (upstream's Parser), as the phone's
 * parsePackets checks it.
 */
class MavFrameStreamTest {
    /** Taken when the library's parser reads exactly one good frame from the bytes. */
    static final class Sink implements MavFrameStream.Frame {
        final List<Integer> ids = new ArrayList<>();

        @Override
        public boolean take(byte[] d, int off, int len) {
            // as the phone's parsePackets: the header from the first byte, then the checksum (no searching inside)
            boolean v2 = (d[off] & 0xff) == 0xFD;
            int n = d[off + 1] & 0xff, header = v2 ? 10 : 6;
            if (v2 && (d[off + 2] & 0xff) != 0) return false; // signed: the phone takes none
            MAVLinkPacket p = new MAVLinkPacket(n, v2);
            p.msgid = v2 ? (d[off + 7] & 0xff) | (d[off + 8] & 0xff) << 8 | (d[off + 9] & 0xff) << 16 : d[off + 5] & 0xff;
            p.payload = new com.MAVLink.Messages.MAVLinkPayload();
            for (int i = 0; i < n; i++) p.payload.add(d[off + header + i]);
            p.seq = d[off + (v2 ? 4 : 2)] & 0xff;
            p.sysid = d[off + (v2 ? 5 : 3)] & 0xff;
            p.compid = d[off + (v2 ? 6 : 4)] & 0xff;
            if (v2) {
                p.incompatFlags = 0;
                p.compatFlags = d[off + 3] & 0xff;
            }
            if (!p.generateCRC(n)) return false;
            if (p.crc.getLSB() != (d[off + header + n] & 0xff) || p.crc.getMSB() != (d[off + header + n + 1] & 0xff)) return false;
            ids.add(p.msgid);
            return true;
        }
    }

    static byte[] stream() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(new msg_heartbeat(5, (short) 2, (short) 3, (short) 129, (short) 4, (short) 3, 1, 1, true).pack().encodePacket());
        b.writeBytes(new msg_mission_request_int(7, (short) 255, (short) 190, (short) 0, 1, 1, true).pack().encodePacket()); // trimmed
        b.writeBytes(new msg_command_ack(176, (short) 0, (short) 0, 0, (short) 255, (short) 190, 1, 1, false).pack().encodePacket()); // v1
        b.writeBytes(new msg_heartbeat(5, (short) 2, (short) 3, (short) 1, (short) 4, (short) 3, 1, 1, false).pack().encodePacket());
        return b.toByteArray();
    }

    static final List<Integer> IDS = List.of(0, 51, 77, 0);

    @Test
    void framesSplitAtEveryBytePositionAllArrive() {
        byte[] s = stream();
        for (int cut = 0; cut <= s.length; cut++) {
            MavFrameStream m = new MavFrameStream();
            Sink k = new Sink();
            m.feed(java.util.Arrays.copyOfRange(s, 0, cut), cut, k);
            m.feed(java.util.Arrays.copyOfRange(s, cut, s.length), s.length - cut, k);
            assertEquals(IDS, k.ids, "cut at " + cut);
            assertEquals(0, m.pending());
        }
        // and every two cuts: three reads
        for (int a = 0; a < s.length; a += 3) {
            for (int c = a; c <= s.length; c += 5) {
                MavFrameStream m = new MavFrameStream();
                Sink k = new Sink();
                m.feed(java.util.Arrays.copyOfRange(s, 0, a), a, k);
                m.feed(java.util.Arrays.copyOfRange(s, a, c), c - a, k);
                m.feed(java.util.Arrays.copyOfRange(s, c, s.length), s.length - c, k);
                assertEquals(IDS, k.ids, "cuts at " + a + ", " + c);
            }
        }
        // byte by byte
        MavFrameStream m = new MavFrameStream();
        Sink k = new Sink();
        for (byte b : s) m.feed(new byte[]{b}, 1, k);
        assertEquals(IDS, k.ids);
    }

    /** A read that begins inside a frame (the port opened mid-stream): the rest of that read was lost with it. */
    @Test
    void aReadBeginningInsideAFrameKeepsWhatFollows() {
        byte[] s = stream();
        int first = new msg_heartbeat(5, (short) 2, (short) 3, (short) 129, (short) 4, (short) 3, 1, 1, true).pack().encodePacket().length;
        for (int start = 1; start < first; start++) {
            MavFrameStream m = new MavFrameStream();
            Sink k = new Sink();
            m.feed(java.util.Arrays.copyOfRange(s, start, s.length), s.length - start, k);
            assertEquals(IDS.subList(1, 4), k.ids, "began at " + start);
        }
    }

    @Test
    void noiseWithStartBytesNeverHidesAFrame() {
        Random r = new Random(5);
        byte[] s = stream();
        for (int round = 0; round < 500; round++) {
            byte[] noise = new byte[r.nextInt(40)];
            r.nextBytes(noise);
            for (int i = 0; i < noise.length; i += 7) noise[i] = (byte) (r.nextBoolean() ? 0xFE : 0xFD);
            MavFrameStream m = new MavFrameStream();
            Sink k = new Sink();
            m.feed(noise, noise.length, k);
            // a noise "frame" claiming a long length may wait for more bytes: the real stream then follows
            byte[] both = s;
            int cut = r.nextInt(s.length + 1);
            m.feed(java.util.Arrays.copyOfRange(both, 0, cut), cut, k);
            m.feed(java.util.Arrays.copyOfRange(both, cut, both.length), both.length - cut, k);
            // a noise start byte claiming a long frame waits for that many bytes (a real stream goes on): then the
            // checksum refuses it and every real frame behind it is found, none lost
            for (int more = 0; more < 4; more++) m.feed(s, s.length, k);
            // (noise can pass a 16-bit checksum now and then, 1 in 65536 a candidate - the phone's parser checks no more)
            List<Integer> real = new ArrayList<>(k.ids);
            real.removeIf(id -> !IDS.contains(id));
            if (real.size() < k.ids.size()) { // such a fake frame takes the bytes it claims: what it covered is lost
                assertTrue(real.size() >= 16, "at most one stream under a fake frame: " + k.ids);
                continue;
            }
            assertEquals(IDS, real.subList(0, 4), "round " + round + ": the first stream whole: " + k.ids);
            assertEquals(20, real.size(), "five streams: " + k.ids);
            assertTrue(m.pending() < MavFrameStream.MAX_FRAME);
        }
        MavFrameStream m = new MavFrameStream();
        m.feed(null, 5, new Sink());
        m.feed(new byte[3], 100, new Sink());
        assertEquals(0, m.pending());
    }
}
