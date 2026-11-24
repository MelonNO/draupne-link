package de.droiddrone.pc;

import de.droiddrone.common.ReceiverBuffer;
import de.droiddrone.common.SavedPacket;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.common.UdpPacketData;
import de.droiddrone.common.UdpSender;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pc.31 / 1.0.39 transport fixes in flight-app/overlay/Common (compiled into the PC and the phone): a timed-out
 * session that a stray packet brought back, gaps skipped at once (the buffer settled at one packet), the nearest
 * packet after a gap picked across the number wrap, and Connects resent by the acknowledgement timer.
 */
class TransportRobustnessTest {
    private static final InetAddress FAR = InetAddress.getLoopbackAddress();

    /** 4 s with no packet: the session is down, and stays down. */
    @Test
    void aTimedOutSessionIsNotBroughtBackByAStrayPacket() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, FAR)) {
            AtomicLong clock = new AtomicLong();
            ReceiverBuffer server = connectedServer(socket, clock);
            tick(server, clock, 4000);
            assertFalse(server.isConnected(), "timed out");
            // any device on the network, no key: before, this revived the session (addPacket read the field)
            server.addPacket(numbered(UdpCommon.MavlinkRawPacket, 0));
            server.addPacket(unnumbered(UdpCommon.Ping));
            assertFalse(server.isConnected(), "still down without a Connect");
            assertEquals(0, drain(server), "nothing taken");
        }
    }

    /**
     * 1.0.53: the resend timer and the gap wait compare against the packets' stamps, which were the wall clock - a
     * time sync setting it back stopped every resend and gap skip until it caught up. They are on the monotonic clock
     * now, as everything else on the link (MonoClock, UdpSender.pingClockMs).
     */
    @Test
    void savedPacketsAreStampedOnTheMonotonicClock() {
        long before = System.nanoTime() / 1_000_000;
        SavedPacket p = new SavedPacket(UdpCommon.FcInfo, (short) 0, new byte[]{UdpCommon.FcInfo, 0, 0}, FAR, 1);
        long after = System.nanoTime() / 1_000_000;
        assertTrue(p.timestampCreated >= before && p.timestampCreated <= after, "stamped on nanoTime's ms: " + p.timestampCreated);
        assertEquals(p.timestampCreated, p.timestamp);
        long now = SavedPacket.nowMs();
        assertTrue(now >= after && now - after < 1000, "the clock the comparisons use");
    }

    /**
     * A numbered packet cut before its number, or a Connect whose key runs past its end (found by the protocol fuzz,
     * 2026-11-24): dropped. Their reads threw on the receive thread, both apps' (logged there, the packet lost).
     */
    @Test
    void aNumberedPacketCutShortIsDroppedWithoutAThrow() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, FAR)) {
            AtomicLong clock = new AtomicLong();
            byte[] c = connect().getData(); // [8][-1 i16][type][key UTF "DD"][version i16]
            for (ReceiverBuffer b : new ReceiverBuffer[]{connectedClient(socket, clock), connectedServer(socket, clock)}) {
                byte[][] cut = {{UdpCommon.FcInfo}, {UdpCommon.Config, 0}, {UdpCommon.Connect, -1}, java.util.Arrays.copyOf(c, 4),
                        java.util.Arrays.copyOf(c, 7), {UdpCommon.Connect, -1, -1, 0, 0x7f, (byte) 0xff, 'D', 'D'}};
                for (byte[] d : cut) {
                    assertDoesNotThrow(() -> b.addPacket(new DatagramPacket(d, d.length, FAR, 50_000)), java.util.Arrays.toString(d));
                }
                assertTrue(b.isConnected(), "the session goes on");
                b.addPacket(numbered(UdpCommon.FcInfo, 0));
                assertEquals(1, drain(b), "and takes its next packet");
            }
        }
    }

    /** On sparse traffic the buffer size averaged down to 1: every gap was skipped at once, never asked for. */
    @Test
    void aGapIsWaitedForBeforeItIsSkipped() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, FAR)) {
            AtomicLong clock = new AtomicLong();
            ReceiverBuffer client = connectedClient(socket, clock);
            tick(client, clock, 1000); // ten ping windows with nothing: the size settles
            client.addPacket(numbered(UdpCommon.FcInfo, 0));
            assertEquals(1, drain(client));
            client.addPacket(numbered(UdpCommon.FcInfo, 2)); // 1 lost, or only late
            assertEquals(0, drain(client), "the gap is waited for (and asked for)");
            client.addPacket(numbered(UdpCommon.FcInfo, 1)); // the late one comes in time
            assertEquals(2, drain(client), "1 and 2, in order");
            client.addPacket(numbered(UdpCommon.FcInfo, 4)); // 3 lost for good
            assertEquals(0, drain(client));
            Thread.sleep(250); // past 1.5 x ping (100 ms by default)
            assertEquals(1, drain(client), "then skipped");
        }
    }

    /** The nearest packet after a gap was the lowest number: across the wrap, 0 came before 32762. */
    @Test
    void theNearestPacketAfterAGapIsFoundAcrossTheWrap() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, FAR)) {
            AtomicLong clock = new AtomicLong();
            ReceiverBuffer client = connectedClient(socket, clock);
            tick(client, clock, 1000);
            for (int n = 0; n <= 32760; n++) {
                client.addPacket(numbered(UdpCommon.FcInfo, n));
                assertNotNull(next(client), "packet " + n);
            }
            // 32761 lost; 32762, 32763 and (after the wrap) 0 waiting
            client.addPacket(numbered(UdpCommon.FcInfo, 32762));
            client.addPacket(numbered(UdpCommon.FcInfo, 32763));
            client.addPacket(numbered(UdpCommon.FcInfo, 0));
            Thread.sleep(250);
            int[] order = new int[3];
            for (int i = 0; i < 3; i++) {
                SavedPacket p = next(client);
                assertNotNull(p, "packet " + i + " after the gap");
                order[i] = p.packetNum;
            }
            assertArrayEquals(new int[]{32762, 32763, 0}, order);
        }
    }

    /** A Connect isn't kept for the resend timer: the apps send it on their own schedule, and each is answered. */
    @Test
    void aConnectGoesOnceNotAgainFromTheResendTimer() throws Exception {
        try (DatagramSocket out = new DatagramSocket(0, FAR); DatagramSocket in = new DatagramSocket(0, FAR)) {
            UdpSender sender = new UdpSender(out);
            sender.connect(FAR, in.getLocalPort());
            sender.sendPacket(connect().getData());
            in.setSoTimeout(100);
            int connects = 0;
            long until = System.currentTimeMillis() + 700;
            byte[] buf = new byte[2000];
            while (System.currentTimeMillis() < until) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    in.receive(p);
                    if (p.getData()[0] == UdpCommon.Connect) connects++;
                } catch (SocketTimeoutException ignored) {
                }
            }
            sender.close();
            assertEquals(1, connects, "sent once");
        }
    }

    /**
     * The timers count the clock's milliseconds, not calls: a session is up while the 4 s have not passed, however
     * often (or seldom) the buffer thread gets to run - one call a ms counted 6 s and more on a busy phone.
     */
    @Test
    void theTimersCountMillisecondsNotCalls() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, FAR)) {
            AtomicLong clock = new AtomicLong();
            ReceiverBuffer server = connectedServer(socket, clock);
            for (int i = 0; i < 20_000; i++) server.processTimer(); // a fast loop: no time went by
            assertTrue(server.isConnected(), "20000 calls in no time: still up");
            for (int i = 0; i < 39; i++) {
                clock.addAndGet(100); // a slow loop: one call every 100 ms
                server.processTimer();
            }
            assertTrue(server.isConnected(), "3.9 s");
            clock.addAndGet(100);
            server.processTimer();
            assertFalse(server.isConnected(), "4 s");
        }
    }

    /** A stall of the buffer thread itself counts at most MAX_STEP_MS: a packet that came meanwhile finds its session. */
    @Test
    void aStallOfTheBufferThreadIsNotTheFarSidesSilence() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, FAR)) {
            AtomicLong clock = new AtomicLong();
            ReceiverBuffer server = connectedServer(socket, clock);
            clock.addAndGet(10_000);
            server.processTimer();
            assertTrue(server.isConnected());
        }
    }

    /**
     * F-057 (the bench's bad-link table, 2026-11-21: a 3 s one-way cut, LINK READY 1.06 s after the phone was heard,
     * 0.86 s before F-001): the PC's Connect began the phone's new session and the answer was lost on the dead way
     * back. When it opens, the phone's new session's first numbers come - the PC took them as strangers to its old
     * session until recoverCounter mended it. With a Connect out unanswered they start the session at once.
     */
    @Test
    void theFarSidesNewSessionIsTakenWithoutItsLostAnswer() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, FAR)) {
            AtomicLong clock = new AtomicLong();
            ReceiverBuffer client = sessionAt(socket, clock, 2000);
            client.awaitAnswer(); // the link lost, a Connect sent: its answer lost
            client.addPacket(numbered(UdpCommon.FcInfo, 0)); // the new session's first
            assertEquals(1, drain(client), "taken at once, not left for recoverCounter");
            assertEquals(1, client.getUnansweredStarts());
            client.addPacket(numbered(UdpCommon.FcInfo, 1));
            assertEquals(1, drain(client), "and the session's numbering goes on from there");
            assertTrue(client.isConnected());
        }
        try (DatagramSocket socket = new DatagramSocket(0, FAR)) { // the same without a Connect out: as before
            AtomicLong clock = new AtomicLong();
            ReceiverBuffer client = sessionAt(socket, clock, 2000);
            client.addPacket(numbered(UdpCommon.FcInfo, 0));
            assertEquals(0, drain(client), "no Connect of ours out: a stranger, as ever");
            assertEquals(0, client.getUnansweredStarts());
        }
    }

    /** F-057's rule starts nothing new where the old session goes on: a late copy, or the session's own numbers first. */
    @Test
    void aLateCopyOrTheOldSessionGoingOnStartsNoNewOne() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, FAR)) {
            AtomicLong clock = new AtomicLong();
            ReceiverBuffer client = sessionAt(socket, clock, 600);
            client.awaitAnswer();
            client.addPacket(numbered(UdpCommon.FcInfo, 500)); // 100 behind: a late copy of this session's
            assertEquals(0, client.getUnansweredStarts(), "a late copy is no new session");
            client.addPacket(numbered(UdpCommon.FcInfo, 600)); // the session's own next number: it goes on (a two-way cut)
            assertEquals(1, drain(client));
            client.addPacket(numbered(UdpCommon.FcInfo, 0)); // then a small number: not the far side's new session
            assertEquals(0, client.getUnansweredStarts(), "the Connect is no longer out: the old session answered for it");
        }
        try (DatagramSocket socket = new DatagramSocket(0, FAR)) { // the far side (the phone) never takes it
            AtomicLong clock = new AtomicLong();
            UdpSender sender = new UdpSender(socket);
            ReceiverBuffer server = new ReceiverBuffer(sender, true, "DD", "DD", clock::get);
            server.awaitAnswer();
            byte[] c = connect().getData();
            server.addPacket(new DatagramPacket(c, c.length, FAR, 50_000));
            drain(server);
            for (int n = 0; n < 2000; n++) {
                server.addPacket(numbered(UdpCommon.FcInfo, n));
                drain(server);
            }
            server.addPacket(numbered(UdpCommon.FcInfo, 3));
            assertEquals(0, server.getUnansweredStarts(), "the phone's side: no such rule");
        }
    }

    /** A connected client whose session has delivered numbers 0 .. next-1. */
    private static ReceiverBuffer sessionAt(DatagramSocket socket, AtomicLong clock, int next) throws Exception {
        ReceiverBuffer client = connectedClient(socket, clock);
        for (int n = 0; n < next; n++) {
            client.addPacket(numbered(UdpCommon.FcInfo, n));
            assertEquals(1, drain(client));
        }
        return client;
    }

    private static void tick(ReceiverBuffer b, AtomicLong clock, int ms) {
        for (int i = 0; i < ms; i++) {
            clock.incrementAndGet();
            b.processTimer();
        }
    }

    private static ReceiverBuffer connectedServer(DatagramSocket socket, AtomicLong clock) throws Exception {
        UdpSender sender = new UdpSender(socket);
        ReceiverBuffer b = new ReceiverBuffer(sender, true, "DD", "DD", clock::get);
        b.addPacket(new DatagramPacket(connect().getData(), connect().getData().length, FAR, 50_000));
        assertTrue(b.isConnected());
        drain(b);
        return b;
    }

    private static ReceiverBuffer connectedClient(DatagramSocket socket, AtomicLong clock) throws Exception {
        UdpSender sender = new UdpSender(socket);
        sender.connect(FAR, 50_000);
        ReceiverBuffer b = new ReceiverBuffer(sender, false, "DD", "DD", clock::get);
        byte[] c = connect().getData();
        b.addPacket(new DatagramPacket(c, c.length, FAR, 50_000));
        assertTrue(b.isConnected());
        drain(b);
        return b;
    }

    private static SavedPacket next(ReceiverBuffer b) {
        SavedPacket p;
        while ((p = b.getNextPacket()) != null) {
            if (p.packetName != UdpCommon.Connect) return p;
        }
        return null;
    }

    private static int drain(ReceiverBuffer b) {
        int n = 0;
        while (next(b) != null) n++;
        return n;
    }

    private static UdpPacketData connect() throws Exception {
        UdpPacketData d = new UdpPacketData(UdpCommon.Connect, -1);
        d.daos.writeByte(0);
        d.daos.writeUTF("DD");
        d.daos.writeShort(1);
        return d;
    }

    private static DatagramPacket numbered(byte name, int num) throws Exception {
        byte[] data = new UdpPacketData(name, num).getData();
        return new DatagramPacket(data, data.length, FAR, 50_000);
    }

    private static DatagramPacket unnumbered(byte name) throws Exception {
        UdpPacketData d = new UdpPacketData(name);
        d.daos.writeBoolean(false);
        d.daos.writeLong(System.currentTimeMillis());
        byte[] data = d.getData();
        return new DatagramPacket(data, data.length, FAR, 50_000);
    }
}
