package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Routes, fences and rally points of any length reach the phone: through real UDP on loopback into a receive buffer of
 * the phone's own size (UdpCommon.packetLength, 1100 - longer datagrams are cut there, as the phone's Udp receives),
 * on the pilot-session path (0x33 as it is) and the fleet path (inside a FleetCmd with the longest key), then put
 * together as the phone's Mavlink.missionPart does. Before, a block went as one datagram: over ~33 items it was cut
 * and dropped, nothing said.
 */
class MissionBlockPartsTest {
    static final String KEY = "k".repeat(64);

    static List<MissionItem> route(int n, double shift) {
        List<MissionItem> r = new ArrayList<>();
        for (int i = 0; i < n; i++) r.add(MissionItem.at(i, MissionItem.NAV_WAYPOINT, 59.9 + shift + i * 1e-5, 10.7, 100 + i % 50));
        return r;
    }

    /** The phone's receive path: one datagram into the phone's buffer, its 0x33 payload (from a FleetCmd or as it is). */
    static final class PhoneSocket implements AutoCloseable {
        final DatagramSocket rx = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        final DatagramSocket tx = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        final MissionBlock.Assembler parts = new MissionBlock.Assembler();
        final boolean fleet;

        PhoneSocket(boolean fleet) throws Exception {
            this.fleet = fleet;
            rx.setSoTimeout(2000);
            rx.setReceiveBufferSize(1 << 20);
        }

        /** Sends one part as the PC does on this path. */
        void send(byte[] part) throws Exception {
            byte[] d = fleet ? FleetCmd.wrap(KEY, part) : part;
            assertTrue(d.length <= UdpCommon.packetLength, d.length + " bytes: over the phone's " + UdpCommon.packetLength);
            tx.send(new DatagramPacket(d, d.length, rx.getLocalSocketAddress()));
        }

        /** Receives one datagram as the phone's Udp does and offers its part. */
        MissionBlock receive() throws Exception {
            byte[] buf = new byte[UdpCommon.packetLength];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            rx.receive(p);
            if (fleet) {
                FleetCmd c = FleetCmd.read(p.getData(), p.getLength());
                assertNotNull(c);
                assertEquals(MissionBlock.PACKET, c.inner());
                return parts.offer(MissionBlock.readPart(c.data, c.at + 1, c.length - 1));
            }
            assertEquals(MissionBlock.PACKET, buf[0]);
            return parts.offer(MissionBlock.readPart(buf, 1, p.getLength() - 1));
        }

        @Override
        public void close() {
            rx.close();
            tx.close();
        }
    }

    static void sameItems(List<MissionItem> sent, MissionBlock got) {
        assertNotNull(got);
        assertEquals(sent.size(), got.items.size());
        for (int i = 0; i < sent.size(); i++) assertTrue(sent.get(i).sameAs(got.items.get(i)), "item " + i);
    }

    @Test
    void everyLengthArrivesOnBothPaths() throws Exception {
        for (boolean fleet : new boolean[]{false, true}) {
            for (int n : new int[]{1, 33, 34, 100, 300, 1500}) {
                List<MissionItem> r = route(n, 0);
                List<byte[]> parts = MissionBlock.packets(4000 + n, MissionUpload.TYPE_MISSION, r);
                assertEquals(Math.max(1, (n + 29) / 30), parts.size());
                try (PhoneSocket phone = new PhoneSocket(fleet)) {
                    MissionBlock got = null;
                    for (byte[] p : parts) {
                        phone.send(p);
                        MissionBlock b = phone.receive();
                        if (b != null) {
                            assertNull(got, "whole once");
                            got = b;
                        }
                    }
                    sameItems(r, got);
                    assertEquals(4000 + n, got.blockId);
                }
            }
            // fences and rally points too, and an empty one (clearing it): one part
            for (int type : new int[]{MissionUpload.TYPE_FENCE, MissionUpload.TYPE_RALLY}) {
                try (PhoneSocket phone = new PhoneSocket(fleet)) {
                    List<MissionItem> r = route(77, 0.01);
                    MissionBlock got = null;
                    for (byte[] p : MissionBlock.packets(9, type, r)) {
                        phone.send(p);
                        MissionBlock b = phone.receive();
                        if (b != null) got = b;
                    }
                    sameItems(r, got);
                    assertEquals(type, got.missionType);
                    phone.send(MissionBlock.packets(10, type, new ArrayList<>()).get(0));
                    assertEquals(0, phone.receive().items.size());
                }
            }
        }
    }

    /** What went wrong before: a 111-item route in one datagram is cut at the phone's 1100 bytes. */
    @Test
    void oneDatagramForALongBlockWasCut() throws Exception {
        List<MissionItem> r = route(111, 0);
        byte[] whole = new byte[1 + 14 + 111 * MissionItem.PACKED_SIZE];
        assertTrue(whole.length > UdpCommon.packetLength, "3123 bytes: one datagram would be cut");
        assertThrows(IllegalArgumentException.class, () -> MissionBlock.packet(1, r), "a long block goes in parts only");
        assertThrows(IllegalArgumentException.class, () -> FleetCmd.wrap(KEY, new byte[UdpCommon.packetLength]),
                "no FleetCmd over the phone's limit is ever sent");
        for (byte[] p : MissionBlock.packets(1, MissionUpload.TYPE_MISSION, route(1500, 0))) {
            assertTrue(FleetCmd.wrap(KEY, p).length <= UdpCommon.packetLength);
            assertTrue(p.length <= 1200, "under a 1280 MTU path (Tailscale) with its headers: " + p.length);
        }
    }

    @Test
    void lostDuplicatedAndReorderedParts() {
        Random rnd = new Random(3);
        for (int round = 0; round < 200; round++) {
            int n = 1 + rnd.nextInt(400);
            List<MissionItem> r = route(n, round * 1e-3);
            List<byte[]> parts = MissionBlock.packets(round, MissionUpload.TYPE_MISSION, r);
            MissionBlock.Assembler a = new MissionBlock.Assembler();
            MissionBlock got = null;
            int wholes = 0;
            // a few rounds of the PC's resends: each round every part, shuffled, some lost, some twice
            for (int resend = 0; resend < 20 && got == null; resend++) {
                List<byte[]> wire = new ArrayList<>();
                for (byte[] p : parts) {
                    if (rnd.nextInt(4) == 0) continue; // lost
                    wire.add(p);
                    if (rnd.nextInt(5) == 0) wire.add(p); // twice
                }
                Collections.shuffle(wire, rnd);
                for (byte[] p : wire) {
                    MissionBlock b = a.offer(MissionBlock.readPart(p, 1, p.length - 1));
                    if (b != null) {
                        wholes++;
                        got = b;
                    }
                }
                if (got == null) assertTrue(a.percent(round) < 100, "not whole: under 100 % (-1 while none came)");
            }
            sameItems(r, got);
            // late copies of its parts never make it whole again (the queue answers the block with its status)
            for (byte[] p : parts) assertNull(a.offer(MissionBlock.readPart(p, 1, p.length - 1)));
            assertEquals(1, wholes);
        }
    }

    /** A new block arriving while another is still being gathered: each is put together on its own, none mixed. */
    @Test
    void aNewBlockMidWay() {
        MissionBlock.Assembler a = new MissionBlock.Assembler();
        List<MissionItem> r1 = route(100, 0), r2 = route(90, 0.5);
        List<byte[]> p1 = MissionBlock.packets(1, MissionUpload.TYPE_MISSION, r1), p2 = MissionBlock.packets(2, MissionUpload.TYPE_MISSION, r2);
        assertNull(a.offer(MissionBlock.readPart(p1.get(0), 1, p1.get(0).length - 1)));
        assertNull(a.offer(MissionBlock.readPart(p1.get(1), 1, p1.get(1).length - 1)));
        MissionBlock got2 = null;
        for (byte[] p : p2) {
            MissionBlock b = a.offer(MissionBlock.readPart(p, 1, p.length - 1));
            if (b != null) got2 = b;
        }
        sameItems(r2, got2);
        assertEquals(50, a.percent(1), "the first still waits for its last two parts");
        MissionBlock got1 = null;
        for (byte[] p : p1.subList(2, 4)) got1 = a.offer(MissionBlock.readPart(p, 1, p.length - 1));
        sameItems(r1, got1);
        // the same id with another route (a PC started again): its parts start that block anew, never mixed in
        List<MissionItem> r3 = route(100, 0.9);
        List<byte[]> p3 = MissionBlock.packets(7, MissionUpload.TYPE_MISSION, r3);
        List<byte[]> p4 = MissionBlock.packets(7, MissionUpload.TYPE_MISSION, route(100, 0.3));
        a.offer(MissionBlock.readPart(p4.get(0), 1, p4.get(0).length - 1));
        a.offer(MissionBlock.readPart(p4.get(1), 1, p4.get(1).length - 1));
        MissionBlock got3 = null;
        for (byte[] p : p3) {
            MissionBlock b = a.offer(MissionBlock.readPart(p, 1, p.length - 1));
            if (b != null) got3 = b;
        }
        sameItems(r3, got3);
    }

    /** A part whose numbers lie, or whose items don't make the block its CRC names, is never uploaded. */
    @Test
    void partsThatDontAddUpAreRefused() {
        List<byte[]> p = MissionBlock.packets(3, MissionUpload.TYPE_MISSION, route(60, 0));
        byte[] bad = p.get(1).clone();
        bad[bad.length - 1] ^= 1; // an item changed on the way: the CRC-32 of the whole refuses it
        MissionBlock.Assembler a = new MissionBlock.Assembler();
        a.offer(MissionBlock.readPart(p.get(0), 1, p.get(0).length - 1));
        assertNull(a.offer(MissionBlock.readPart(bad, 1, bad.length - 1)));
        byte[] wrongFirst = p.get(1).clone();
        wrongFirst[13] = 0; // first item 0 in part 1
        assertNull(MissionBlock.readPart(wrongFirst, 1, wrongFirst.length - 1));
        byte[] wrongCount = p.get(0).clone();
        wrongCount[5] = 3; // three parts for 60 items
        assertNull(MissionBlock.readPart(wrongCount, 1, wrongCount.length - 1));
        assertNull(MissionBlock.readPart(p.get(0), 1, p.get(0).length - 2), "cut short");
        assertNull(MissionBlock.readPart(java.util.Arrays.copyOf(p.get(0), p.get(0).length + 1), 1, p.get(0).length), "a byte too many");
    }
}
