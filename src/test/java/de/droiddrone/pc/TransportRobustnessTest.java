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
