package de.droiddrone.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-056, the sleep notice that never holds the power modes' thread. What the bench saw in the emulator (2026-11-21):
 * the notice stood 138 s in a send on a full socket, the phone stayed awake with its camera on while the PC showed it
 * asleep; another time all three notices went into the socket being opened anew and the PC was never told. Here a
 * socket whose sends never return until it is closed stands in for the full one.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SleepSendsTest {
    private static final InetAddress LOCAL = InetAddress.getLoopbackAddress();
    private final List<DatagramSocket> open = new CopyOnWriteArrayList<>();
    private final List<SleepSends> senders = new CopyOnWriteArrayList<>();

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

    private <T extends DatagramSocket> T keep(T s) {
        open.add(s);
        return s;
    }

    private SleepSends sends() {
        SleepSends s = new SleepSends("test notice");
        senders.add(s);
        return s;
    }

    @AfterEach
    void closeAll() {
        for (SleepSends s : senders) s.shutdown();
        for (DatagramSocket s : open) s.close();
    }

    /** The phone's notice on the link socket: true when it left, as Udp.sendSleepNotice. */
    private static SleepSends.Send notice(LinkSocket link, DatagramSocket peer, String text) {
        return () -> {
            try {
                byte[] d = text.getBytes();
                link.send(new DatagramPacket(d, d.length, LOCAL, peer.getLocalPort()));
                return true;
            } catch (IOException e) {
                return false;
            }
        };
    }

    private static String take(DatagramSocket s, int withinMs) throws IOException {
        s.setSoTimeout(withinMs);
        DatagramPacket p = new DatagramPacket(new byte[64], 64);
        s.receive(p);
        return new String(p.getData(), 0, p.getLength());
    }

    /** First a wedged socket, after a reopen a good one on the same port. */
    private LinkSocket wedgedThenGood() throws IOException {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger port = new AtomicInteger();
        return keep(new LinkSocket(() -> {
            DatagramSocket s = opened.getAndIncrement() == 0 ? new Wedged(0) : new DatagramSocket(port.get(), LOCAL);
            port.set(s.getLocalPort());
            return s;
        }));
    }

    @Test
    void aNoticeStandingInAFullSocketDoesNotHoldItsCaller() throws Exception {
        DatagramSocket peer = keep(new DatagramSocket(0, LOCAL));
        LinkSocket link = wedgedThenGood();
        SleepSends sends = sends();
        sends.begin();
        long t0 = System.nanoTime();
        assertTrue(sends.offer(notice(link, peer, "notice 1")));
        long tookMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(tookMs < 200, "the countdown goes on at once: " + tookMs + " ms");
        Thread.sleep(400);
        assertTrue(link.stuckMs() >= 300, "the notice does stand in the full socket");
        assertFalse(sends.offer(notice(link, peer, "notice 2")), "the next one is dropped, not queued behind it");
        assertFalse(sends.delivered());
    }

    @Test
    void beforeTheSleepTheJammedSocketIsOpenedAnewAndOneNoticeLeaves() throws Exception {
        DatagramSocket peer = keep(new DatagramSocket(0, LOCAL));
        LinkSocket link = wedgedThenGood();
        SleepSends sends = sends();
        sends.begin();
        sends.offer(notice(link, peer, "countdown notice"));
        Thread.sleep(400); // stands in the full socket, as in round 27 of the night run

        long t0 = System.nanoTime();
        assertTrue(sends.lastChance(link, notice(link, peer, "last notice"), 1500), "a notice left before the sleep");
        long tookMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(tookMs < 1500, "within the bound: " + tookMs + " ms");
        assertEquals(1, link.reopens(), "the socket opened anew");
        assertEquals("last notice", take(peer, 2000), "the PC gets it");
        assertTrue(sends.delivered());
    }

    @Test
    void aNoticeThatLeftInTheCountdownIsEnough() throws Exception {
        DatagramSocket peer = keep(new DatagramSocket(0, LOCAL));
        LinkSocket link = keep(new LinkSocket(() -> new DatagramSocket(0, LOCAL)));
        SleepSends sends = sends();
        sends.begin();
        assertTrue(sends.offer(notice(link, peer, "countdown notice")));
        assertEquals("countdown notice", take(peer, 2000));
        long until = System.currentTimeMillis() + 2000;
        while (!sends.delivered() && System.currentTimeMillis() < until) Thread.sleep(5);
        assertTrue(sends.lastChance(link, notice(link, peer, "last notice"), 1500));
        assertEquals(0, link.reopens(), "nothing opened anew");
        peer.setSoTimeout(300);
        assertThrows(java.net.SocketTimeoutException.class, () -> peer.receive(new DatagramPacket(new byte[64], 64)), "and no second notice");
    }

    @Test
    void aNoticeThatWentIntoTheReopenIsSentAgain() throws Exception {
        // round 12 of the night run: the notices sent while the socket was being opened anew - each an error
        DatagramSocket peer = keep(new DatagramSocket(0, LOCAL));
        LinkSocket link = keep(new LinkSocket(() -> new DatagramSocket(0, LOCAL)));
        SleepSends sends = sends();
        sends.begin();
        sends.offer(() -> false); // "Socket is closed"
        Thread.sleep(100);
        assertFalse(sends.delivered());
        assertTrue(sends.lastChance(link, notice(link, peer, "last notice"), 1500));
        assertEquals("last notice", take(peer, 2000));
        assertEquals(0, link.reopens(), "a socket that moves is not opened anew");
    }

    @Test
    void aSocketThatStaysJammedStillLetsThePhoneSleepInTime() throws Exception {
        DatagramSocket peer = keep(new DatagramSocket(0, LOCAL));
        LinkSocket link = keep(new LinkSocket(() -> new Wedged(0))); // every socket it opens is full at once
        SleepSends sends = sends();
        sends.begin();
        sends.offer(notice(link, peer, "countdown notice"));
        Thread.sleep(400);
        long t0 = System.nanoTime();
        assertFalse(sends.lastChance(link, notice(link, peer, "last notice"), 1000), "nothing left - and it is said");
        long tookMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(tookMs >= 900 && tookMs < 2500, "the sleep comes after the bound, not never: " + tookMs + " ms");
    }

    @Test
    void aNewCountdownStartsUntold() throws Exception {
        SleepSends sends = sends();
        sends.begin();
        sends.offer(() -> true);
        long until = System.currentTimeMillis() + 2000;
        while (!sends.delivered() && System.currentTimeMillis() < until) Thread.sleep(5);
        assertTrue(sends.delivered());
        sends.begin();
        assertFalse(sends.delivered(), "the last countdown's notice does not count for this one");
    }

    @Test
    void aSendThatThrowsDoesNotStopTheNextOnes() throws Exception {
        SleepSends sends = sends();
        sends.begin();
        sends.offer(() -> {
            throw new IllegalStateException("battery service gone");
        });
        long until = System.currentTimeMillis() + 2000;
        boolean taken = false;
        while (!taken && System.currentTimeMillis() < until) {
            taken = sends.offer(() -> true);
            if (!taken) Thread.sleep(5);
        }
        assertTrue(taken, "the thread lives on");
        until = System.currentTimeMillis() + 2000;
        while (!sends.delivered() && System.currentTimeMillis() < until) Thread.sleep(5);
        assertTrue(sends.delivered());
    }
}
