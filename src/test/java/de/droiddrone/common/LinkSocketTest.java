package de.droiddrone.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The phone's link socket that can be opened anew (pc.55). What the bench saw in the emulator on 2026-11-19: the
 * socket's send buffer full for good, all four sender threads of the phone app standing in their send for the rest of
 * the app's life, the phone still hearing the PC. Here a socket whose sends never return stands in for it.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LinkSocketTest {
    private static final InetAddress LOCAL = InetAddress.getLoopbackAddress();
    private final List<DatagramSocket> open = new CopyOnWriteArrayList<>();

    /** A socket whose sends wait until it is closed - the full send buffer nothing drains. */
    private static final class Wedged extends DatagramSocket {
        Wedged(int port) throws SocketException {
            super(port, LOCAL);
        }

        @Override
        public void send(DatagramPacket p) throws IOException {
            while (!isClosed()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            throw new SocketException("Socket closed");
        }
    }

    /** A socket whose every send takes that long: a slow uplink that moves. */
    private static final class Slow extends DatagramSocket {
        private final long ms;

        Slow(long ms) throws SocketException {
            super(0, LOCAL);
            this.ms = ms;
        }

        @Override
        public synchronized void send(DatagramPacket p) throws IOException {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            super.send(p);
        }
    }

    private <T extends DatagramSocket> T keep(T s) {
        open.add(s);
        return s;
    }

    @AfterEach
    void closeAll() {
        for (DatagramSocket s : open) s.close();
    }

    private static DatagramPacket to(DatagramSocket peer, String text) {
        byte[] d = text.getBytes();
        return new DatagramPacket(d, d.length, LOCAL, peer.getLocalPort());
    }

    private static String take(DatagramSocket s, int withinMs) throws IOException {
        s.setSoTimeout(withinMs);
        DatagramPacket p = new DatagramPacket(new byte[64], 64);
        s.receive(p);
        return new String(p.getData(), 0, p.getLength());
    }

    @Test
    void untilSomethingStandsStillItIsTheSocketItHolds() throws Exception {
        DatagramSocket peer = keep(new DatagramSocket(0, LOCAL));
        LinkSocket link = keep(new LinkSocket(() -> new DatagramSocket(0, LOCAL)));
        assertFalse(link.isClosed());
        assertTrue(link.getLocalPort() > 0);
        link.send(to(peer, "up"));
        assertEquals("up", take(peer, 2000));
        peer.send(to(link, "down"));
        DatagramPacket p = new DatagramPacket(new byte[64], 64);
        link.receive(p);
        assertEquals("down", new String(p.getData(), 0, p.getLength()));
        assertEquals(peer.getLocalPort(), p.getPort());
        assertEquals(0, link.stuckMs(), "nothing waits");
        assertEquals(0, link.reopens());
        assertFalse(link.broken());
        link.close();
        assertTrue(link.isClosed());
        assertThrows(IOException.class, () -> link.send(to(peer, "late")));
    }

    @Test
    void sendsThatStandStillAreCountedAndLetGoByAReopenAndTheNextOnesGetOut() throws Exception {
        DatagramSocket peer = keep(new DatagramSocket(0, LOCAL));
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger port = new AtomicInteger();
        LinkSocket link = keep(new LinkSocket(() -> {
            DatagramSocket s = opened.getAndIncrement() == 0 ? new Wedged(0) : new DatagramSocket(port.get(), LOCAL);
            port.set(s.getLocalPort());
            return s;
        }));
        int portBefore = link.getLocalPort();
        // the phone's four senders: telemetry, video, sound, the answers
        AtomicInteger letGo = new AtomicInteger();
        List<Thread> senders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                try {
                    link.send(to(peer, "stuck"));
                } catch (IOException e) {
                    letGo.incrementAndGet();
                }
            }, "sender" + i);
            t.start();
            senders.add(t);
        }
        Thread.sleep(700);
        long stuck = link.stuckMs();
        assertTrue(stuck >= 500 && stuck < 3000, "the standing still is counted: " + stuck + " ms");
        for (Thread t : senders) assertTrue(t.isAlive(), "a send waits as long as the socket lets it");

        long t0 = System.nanoTime();
        assertTrue(link.reopen());
        for (Thread t : senders) t.join(2000);
        long tookMs = (System.nanoTime() - t0) / 1_000_000;
        for (Thread t : senders) assertFalse(t.isAlive(), "every waiting send is let go");
        assertEquals(4, letGo.get(), "each with an error: its datagram is lost, as on any network");
        assertTrue(tookMs < 2000, "at once: " + tookMs + " ms");
        assertEquals(0, link.stuckMs());
        assertEquals(1, link.reopens());
        assertEquals(portBefore, link.getLocalPort(), "the same port: the PC answers where it always did");

        link.send(to(peer, "free"));
        assertEquals("free", take(peer, 2000), "and the next datagram gets out");
    }

    @Test
    void aReceiveThatWaitsCarriesOnWithTheNewSocket() throws Exception {
        DatagramSocket peer = keep(new DatagramSocket(0, LOCAL));
        AtomicInteger port = new AtomicInteger();
        LinkSocket link = keep(new LinkSocket(() -> {
            DatagramSocket s = new DatagramSocket(port.get(), LOCAL);
            port.set(s.getLocalPort());
            return s;
        }));
        AtomicReference<String> got = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread receiver = new Thread(() -> {
            try {
                for (int i = 0; i < 2; i++) {
                    DatagramPacket p = new DatagramPacket(new byte[64], 64);
                    link.receive(p);
                    got.set(new String(p.getData(), 0, p.getLength()));
                }
            } catch (Throwable t) {
                failed.set(t);
            }
        }, "receiver");
        receiver.start();
        peer.send(to(link, "before"));
        long end = System.currentTimeMillis() + 2000;
        while (!"before".equals(got.get()) && System.currentTimeMillis() < end) Thread.sleep(10);
        assertEquals("before", got.get());
        Thread.sleep(100); // the receiver is waiting in its second receive
        assertTrue(link.reopen());
        Thread.sleep(100);
        assertNull(failed.get(), "the reopen is no error to the one receiving");
        assertTrue(receiver.isAlive());
        peer.send(to(link, "after"));
        receiver.join(3000);
        assertNull(failed.get());
        assertEquals("after", got.get(), "what arrives on the new socket goes to the receive that was waiting");
    }

    @Test
    void aSlowUplinkThatMovesIsNotStandingStill() throws Exception {
        DatagramSocket peer = keep(new DatagramSocket(0, LOCAL));
        LinkSocket link = keep(new LinkSocket(() -> new Slow(150)));
        AtomicInteger worst = new AtomicInteger();
        List<Thread> senders = new ArrayList<>();
        long until = System.currentTimeMillis() + 2500;
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                try {
                    while (System.currentTimeMillis() < until) link.send(to(peer, "slow"));
                } catch (IOException ignored) {
                }
            });
            t.start();
            senders.add(t);
        }
        while (System.currentTimeMillis() < until) {
            worst.accumulateAndGet((int) link.stuckMs(), Math::max);
            Thread.sleep(20);
        }
        for (Thread t : senders) t.join(3000);
        // every send of the four waits its turn - 0.6 s each - but one leaves every 150 ms: the count starts again
        assertTrue(worst.get() < 1000, "four senders on a slow socket never look stuck: at most " + worst.get() + " ms");
        assertEquals(0, link.stuckMs(), "and nothing counts once none waits");
    }

    @Test
    void aReopenThatCannotBindIsKnownAndTriedAgain() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger failUntil = new AtomicInteger(Integer.MAX_VALUE);
        LinkSocket link = keep(new LinkSocket(() -> {
            int n = opened.getAndIncrement();
            if (n >= 1 && n < failUntil.get()) throw new SocketException("Address already in use");
            return new DatagramSocket(0, LOCAL);
        }));
        assertFalse(link.reopen(), "no socket could be opened");
        assertTrue(link.broken(), "and that is known");
        assertFalse(link.isClosed(), "the link's socket is not closed for that: its users go on asking");
        DatagramSocket peer = keep(new DatagramSocket(0, LOCAL));
        assertThrows(IOException.class, () -> link.send(to(peer, "nowhere")));
        long t0 = System.nanoTime();
        assertThrows(IOException.class, () -> link.receive(new DatagramPacket(new byte[8], 8)));
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 300, "a receive on it waits a while before its error: no loop at full speed");
        failUntil.set(0);
        assertTrue(link.reopen());
        assertFalse(link.broken());
        link.send(to(peer, "back"));
        assertEquals("back", take(peer, 2000));
    }

    /**
     * The review's catch: the port is not free the moment the old socket is closed - every thread that stood in a send
     * or a receive on it holds it in the system until it has left. Five tries in 80 ms gave up too soon; the phone
     * then had no socket, and heard no sticks, until the watch tried again a second later.
     */
    @Test
    void aPortThatIsFreeOnlyAMomentLaterIsWaitedFor() throws Exception {
        DatagramSocket peer = keep(new DatagramSocket(0, LOCAL));
        AtomicInteger opened = new AtomicInteger();
        long[] freeAt = {0};
        LinkSocket link = keep(new LinkSocket(() -> {
            if (opened.getAndIncrement() > 0 && System.nanoTime() < freeAt[0]) throw new java.net.BindException("Address already in use");
            return new DatagramSocket(0, LOCAL);
        }));
        freeAt[0] = System.nanoTime() + 400_000_000L; // the old socket's threads take 0.4 s to leave it
        long t0 = System.nanoTime();
        assertTrue(link.reopen(), "the reopen waits for the port instead of giving up");
        long tookMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(tookMs >= 350 && tookMs < 900, "bound as soon as the port was free: " + tookMs + " ms");
        assertTrue(opened.get() > 20, "tried every few milliseconds: " + opened.get() + " tries");
        assertFalse(link.broken());
        link.send(to(peer, "bound"));
        assertEquals("bound", take(peer, 2000));
    }

    /** One that waits before it is counted: the watch must never see "a send waits" against the last send's old stamp. */
    @Test
    void aSendAfterALongQuietTimeIsNotTakenForOneThatStoodAllThatTime() throws Exception {
        DatagramSocket peer = keep(new DatagramSocket(0, LOCAL));
        LinkSocket link = keep(new LinkSocket(() -> new Slow(300)));
        link.send(to(peer, "first"));
        Thread.sleep(1200); // nothing to send for a while (standby: a name answer now and then)
        Thread sender = new Thread(() -> {
            try {
                link.send(to(peer, "second"));
            } catch (IOException ignored) {
            }
        });
        sender.start();
        long worst = 0;
        while (sender.isAlive()) worst = Math.max(worst, link.stuckMs());
        assertTrue(worst < 1000, "the wait counts from the send's own start, not from the last send's end: " + worst + " ms");
    }

    @Test
    void closedForGoodItStaysClosed() throws Exception {
        LinkSocket link = keep(new LinkSocket(() -> new DatagramSocket(0, LOCAL)));
        AtomicReference<Throwable> ended = new AtomicReference<>();
        Thread receiver = new Thread(() -> {
            try {
                link.receive(new DatagramPacket(new byte[8], 8));
            } catch (Throwable t) {
                ended.set(t);
            }
        });
        receiver.start();
        Thread.sleep(150);
        link.close();
        receiver.join(2000);
        assertFalse(receiver.isAlive(), "a waiting receive ends when the socket is closed for good (deep sleep)");
        assertTrue(ended.get() instanceof IOException, String.valueOf(ended.get()));
        assertFalse(ended.get() instanceof SocketTimeoutException);
        assertFalse(link.reopen(), "and nothing opens it again");
        assertFalse(link.broken());
        assertTrue(link.isClosed());
    }

    @Test
    void anOpenerThatFailsAtTheStartFailsTheStart() {
        assertThrows(IOException.class, () -> new LinkSocket(() -> {
            throw new SocketException("Address already in use");
        }));
    }
}
