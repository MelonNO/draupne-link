package de.droiddrone.bench.phone;

import de.droiddrone.common.KnownPcs;
import de.droiddrone.pc.Names;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BS6's flood: held to its rate, sent and answered together ({@link Flood.Bucket}), from as many addresses as it is given. */
class FloodTest {
    private static final long MS = 1_000_000L;

    /** Ten seconds in 5 ms steps; each datagram answered at once with so many bytes (0: never). Returns {sent bytes, answer bytes}. */
    private static long[] tenSeconds(long rate, int size, int answer) {
        Flood.Bucket b = new Flood.Bucket(rate);
        long sent = 0, answers = 0;
        for (long t = 0; t <= 10_000; t += 5) {
            long n = b.due(t * MS, answers, size);
            sent += n * size;
            answers += n * answer;
        }
        return new long[]{sent, answers};
    }

    @Test
    void theRateHoldsWhatIsSentAndWhatComesBackTogether() {
        long[] quiet = tenSeconds(4000, 33, 0);
        assertTrue(Math.abs(quiet[0] - 40_000) <= 4096 + 33, "nothing comes back: 4 kB/s are sent - " + quiet[0] + " in 10 s");
        long[] answered = tenSeconds(4000, 33, 150);
        long both = answered[0] + answered[1];
        assertTrue(both <= 40_000 + 4096 + 150 && both >= 40_000 - 4096 - 183, "every query answered with 150 bytes: 4 kB/s both ways together - " + both);
        assertTrue(answered[0] < quiet[0] / 4, "so far fewer queries go: " + answered[0] + " bytes of them");
        long[] fast = tenSeconds(200_000, 33, 100);
        assertTrue(Math.abs(fast[0] + fast[1] - 2_000_000) <= 20_000 + 133, "on loopback: " + (fast[0] + fast[1]));
    }

    @Test
    void aPauseIsNotMadeUpInABurst() {
        Flood.Bucket b = new Flood.Bucket(4000);
        assertEquals(0, b.due(0, 0, 33), "nothing at the very start");
        long n = b.due(60_000 * MS, 0, 33);
        assertTrue(n * 33 <= b.capacity(), "a minute's pause gives the bucket's fill at most: " + n);
        assertEquals(0, b.due(60_001 * MS, 0, 33), "and then the rate again");
        assertEquals(4096, b.capacity(), "a small rate still holds a few datagrams");
        assertEquals(20_000, new Flood.Bucket(200_000).capacity(), "a big one a tenth of a second");
        assertThrows(IllegalArgumentException.class, () -> new Flood.Bucket(0));
    }

    @Test
    void manySendersOnThisPcsOwnLoopback() {
        List<InetAddress> many = Flood.loopbackSources(4 * KnownPcs.BOOK_MAX);
        assertEquals(4 * KnownPcs.BOOK_MAX, new HashSet<>(many).size(), "each another address");
        for (InetAddress a : many) {
            assertTrue(a.isLoopbackAddress(), a.toString());
            assertFalse(a.equals(InetAddress.getLoopbackAddress()), "never the receiver's own 127.0.0.1");
            assertEquals(127, a.getAddress()[0] & 0xff);
            assertTrue((a.getAddress()[3] & 0xff) != 0, "no address ends in 0");
        }
    }

    /** On this PC's loopback: a receiver that answers every datagram, three senders - all three heard, the answers counted. */
    @Test
    void theFloodIsSentFromEachAddressAndItsAnswersCounted() throws Exception {
        Set<InetAddress> heardFrom = ConcurrentHashMap.newKeySet();
        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            Thread answering = new Thread(() -> {
                byte[] buf = new byte[512];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    while (true) {
                        p.setLength(buf.length);
                        receiver.receive(p);
                        heardFrom.add(p.getAddress());
                        receiver.send(new DatagramPacket(new byte[100], 100, p.getSocketAddress()));
                    }
                } catch (Exception closed) {
                    // the receiver closed: the test is over
                }
            }, "floodTestReceiver");
            answering.setDaemon(true);
            answering.start();
            byte[] query = Names.query("BenchKey");
            InetSocketAddress to = new InetSocketAddress(InetAddress.getLoopbackAddress(), receiver.getLocalPort());
            long t0 = System.nanoTime();
            try (Flood f = new Flood("test", query, to, Flood.loopbackSources(3), 20_000)) {
                assertEquals(3, f.sources());
                assertEquals(0, f.refused());
                f.start();
                Waits.waitFor(() -> heardFrom.size() == 3 && f.answers.get() >= 3, 5000, "each of the three senders heard, and answered");
                f.close();
                long seconds = Math.max(1, (System.nanoTime() - t0) / 1_000_000_000L + 1);
                assertTrue(f.bytes() <= 20_000 * seconds + 4096 + 128, "held to its rate: " + f.bytes() + " bytes in under " + seconds + " s");
                assertEquals(f.sent.get() * (query.length + Flood.HEAD), f.sentBytes.get(), "each datagram counted with its IP and UDP head");
                assertEquals(f.answers.get() * (100 + Flood.HEAD), f.answerBytes.get());
            }
        }
    }
}
