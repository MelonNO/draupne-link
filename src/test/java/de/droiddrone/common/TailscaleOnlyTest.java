package de.droiddrone.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Q-006 (the pilot, 2026-11-22: "Tailscale only"): the phone answers over Tailscale and nothing else. Its link socket is
 * bound to the phone's own Tailscale address - an address in Tailscale's ranges on a VPN's interface, never a carrier's
 * 100.64.0.0/10 on mobile data - and takes and sends only from and to Tailscale's addresses; with no Tailscale it is
 * parked and silent, and when the address comes, changes or goes it is opened anew where it belongs.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TailscaleOnlyTest {
    private static final InetAddress LOCAL = InetAddress.getLoopbackAddress();
    private final List<DatagramSocket> open = new CopyOnWriteArrayList<>();

    private static InetAddress ip(String s) {
        try {
            return InetAddress.getByName(s); // numbers only: nothing is looked up
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(s, e);
        }
    }

    private static TailscaleOnly.Seen seen(String iface, boolean vpn, String address) {
        return new TailscaleOnly.Seen(iface, vpn, ip(address));
    }

    private <T extends DatagramSocket> T keep(T s) {
        open.add(s);
        return s;
    }

    @AfterEach
    void closeAll() {
        for (DatagramSocket s : open) s.close();
    }

    // ------------------------------------------------------------------ the addresses

    @Test
    void tailscalesRangesAreTheCgnatBlockAndItsOwnIpv6Block() {
        for (String in : List.of("100.64.0.0", "100.64.0.1", "100.101.102.103", "100.127.255.255", "fd7a:115c:a1e0::1", "fd7a:115c:a1e0:ab12:4843:cd96:6258:b240")) {
            assertTrue(TailscaleOnly.peer(ip(in)), in);
        }
        for (String out : List.of("100.63.255.255", "100.128.0.0", "10.0.2.2", "192.168.1.20", "192.168.43.1", "172.20.10.1", "127.0.0.1", "0.0.0.0",
                "8.8.8.8", "::1", "fe80::1", "fd7a:115c:a1e1::1", "fd7a:115d:a1e0::1", "2a02:2121::1")) {
            assertFalse(TailscaleOnly.peer(ip(out)), out);
        }
        assertFalse(TailscaleOnly.peer(null));
    }

    @Test
    void androidsVpnInterfacesAreTunAndANumber() {
        assertTrue(TailscaleOnly.vpnName("tun0"));
        assertTrue(TailscaleOnly.vpnName("tun12"));
        for (String no : new String[]{"tun", "tunnel0", "rmnet_data0", "ccmni0", "wlan0", "eth0", "lo", "ap0", "rndis0", "", null}) {
            assertFalse(TailscaleOnly.vpnName(no), String.valueOf(no));
        }
    }

    @Test
    void theCarriersOwnCgnatAddressOnMobileDataIsNeverThePhonesTailscaleAddress() {
        // mobile data with a carrier address in 100.64.0.0/10, Wi-Fi, a hotspot: no Tailscale
        assertNull(TailscaleOnly.own(List.of(seen("rmnet_data0", false, "100.70.12.34"), seen("wlan0", false, "192.168.1.20"),
                seen("ap0", false, "192.168.43.1"), seen("lo", false, "127.0.0.1"))));
        // ... and with Tailscale up beside it: Tailscale's, on its VPN
        assertEquals(ip("100.101.102.103"), TailscaleOnly.own(List.of(seen("rmnet_data0", false, "100.70.12.34"),
                seen("tun0", true, "100.101.102.103"))));
    }

    @Test
    void anotherVpnIsNotTailscaleAndIpv4ComesFirst() {
        assertNull(TailscaleOnly.own(List.of(seen("tun0", true, "10.8.0.2"))), "a VPN of another kind");
        assertEquals(ip("fd7a:115c:a1e0::77"), TailscaleOnly.own(List.of(seen("tun0", true, "fe80::1"), seen("tun0", true, "fd7a:115c:a1e0::77"))),
                "IPv6 only: that one");
        assertEquals(ip("100.88.1.2"), TailscaleOnly.own(List.of(seen("tun0", true, "fd7a:115c:a1e0::77"), seen("tun0", true, "100.88.1.2"))),
                "IPv4 first: the address the PC connects to");
        assertNull(TailscaleOnly.own(null));
        assertNull(TailscaleOnly.own(List.of()));
    }

    // ------------------------------------------------------------------ where the socket is bound, and who passes

    @Test
    void noTailscaleParksTheSocketAndNothingPassesNotEvenATailscaleAddress() {
        TailscaleOnly.Place p = TailscaleOnly.place(null, false);
        assertSame(TailscaleOnly.Place.PARKED, p);
        assertTrue(p.parked);
        for (String a : List.of("100.101.102.103", "fd7a:115c:a1e0::1", "10.0.2.2", "127.0.0.1")) assertFalse(p.passes(ip(a)), a);
    }

    @Test
    void onTailscaleOnlyTailscalesAddressesPass() {
        TailscaleOnly.Place p = TailscaleOnly.place(ip("100.101.102.103"), false);
        assertFalse(p.parked);
        assertEquals(ip("100.101.102.103"), p.address);
        assertTrue(p.passes(ip("100.90.1.1")), "the PC's Tailscale address");
        assertTrue(p.passes(ip("fd7a:115c:a1e0::9")));
        for (String a : List.of("10.0.2.2", "192.168.1.20", "192.168.43.2", "127.0.0.1", "8.8.8.8")) assertFalse(p.passes(ip(a)), a);
    }

    @Test
    void widenedTheSocketIsEverywhereAndAnyonePasses() {
        // the test rig's build in the emulator alone: the rule as it was before Q-006
        TailscaleOnly.Place p = TailscaleOnly.place(null, true);
        assertSame(TailscaleOnly.Place.EVERYWHERE, p);
        assertSame(TailscaleOnly.Place.EVERYWHERE, TailscaleOnly.place(ip("100.101.102.103"), true));
        for (String a : List.of("10.0.2.2", "127.0.0.1", "100.90.1.1")) assertTrue(p.passes(ip(a)), a);
    }

    @Test
    void theParkedSocketIsOnTheOwnLoopbackOnAPortOfTheSystemsChoosing() throws IOException {
        DatagramSocket s = keep(TailscaleOnly.Place.PARKED.open(6286));
        assertTrue(s.getLocalAddress().isLoopbackAddress(), "on loopback: nothing from outside the phone reaches it");
        assertNotEquals(6286, s.getLocalPort(), "never the link port: it is no listener, and never in the way of one");
    }

    // ------------------------------------------------------------------ the watch: when to open the socket anew

    @Test
    void theWatchRebindsWhenTailscaleComesChangesOrGoes() {
        TailscaleOnly.Watch w = new TailscaleOnly.Watch();
        assertTrue(w.silent(), "before anything is bound: parked");
        assertFalse(w.rebindDue(TailscaleOnly.Place.PARKED), "no Tailscale, parked: nothing to do");
        assertFalse(w.allows(ip("100.90.1.1")), "parked: silent to everyone");

        TailscaleOnly.Place a = TailscaleOnly.place(ip("100.101.102.103"), false);
        assertTrue(w.rebindDue(a), "Tailscale up: opened anew on its address");
        w.opened(a);
        assertFalse(w.silent());
        assertFalse(w.rebindDue(TailscaleOnly.place(ip("100.101.102.103"), false)), "the same address again (a VPN that reconnected): left as it is");
        assertTrue(w.allows(ip("100.90.1.1")));
        assertFalse(w.allows(ip("192.168.1.20")));

        TailscaleOnly.Place b = TailscaleOnly.place(ip("100.77.0.5"), false);
        assertTrue(w.rebindDue(b), "another address: opened anew there");
        w.opened(b);
        assertTrue(w.rebindDue(TailscaleOnly.Place.PARKED), "Tailscale gone: parked");
        w.opened(TailscaleOnly.Place.PARKED);
        assertTrue(w.silent());
        assertFalse(w.rebindDue(null), "nothing known: left as it is");
    }

    // ------------------------------------------------------------------ the link socket with its peers

    private static DatagramPacket to(DatagramSocket s, String text) {
        byte[] d = text.getBytes();
        return new DatagramPacket(d, d.length, LOCAL, s.getLocalPort());
    }

    private static String take(DatagramSocket s, int withinMs) throws IOException {
        s.setSoTimeout(withinMs);
        DatagramPacket p = new DatagramPacket(new byte[64], 64);
        s.receive(p);
        return new String(p.getData(), 0, p.getLength());
    }

    @Test
    void whatThePeersRefuseNeverReachesTheAppAndNothingLeavesForThem() throws Exception {
        DatagramSocket pc = keep(new DatagramSocket(0, LOCAL));
        AtomicReference<Boolean> allowed = new AtomicReference<>(false);
        LinkSocket link = keep(new LinkSocket(() -> new DatagramSocket(0, LOCAL), a -> allowed.get()));
        pc.send(to(link, "from a refused address"));
        assertThrows(SocketTimeoutException.class, () -> take(link, 500), "dropped in the socket: the app never sees it");
        assertEquals(1, link.refusedIn());
        link.send(to(pc, "to a refused address"));
        assertThrows(SocketTimeoutException.class, () -> take(pc, 500), "and nothing leaves for one");
        assertEquals(1, link.refusedOut());
        assertEquals(0, link.stuckMs(), "a send refused is no send standing still");

        allowed.set(true);
        pc.send(to(link, "from an allowed one"));
        assertEquals("from an allowed one", take(link, 2000));
        link.send(to(pc, "back"));
        assertEquals("back", take(pc, 2000));
    }

    @Test
    void aRefusedDatagramDoesNotEndTheReceiveThatWaitsTheNextOneComesToIt() throws Exception {
        DatagramSocket pc = keep(new DatagramSocket(0, LOCAL));
        AtomicInteger asked = new AtomicInteger();
        LinkSocket link = keep(new LinkSocket(() -> new DatagramSocket(0, LOCAL), a -> asked.getAndIncrement() > 0));
        AtomicReference<String> got = new AtomicReference<>();
        Thread receiver = new Thread(() -> {
            try {
                DatagramPacket p = new DatagramPacket(new byte[64], 64);
                link.receive(p);
                got.set(new String(p.getData(), 0, p.getLength()));
            } catch (IOException e) {
                got.set("error: " + e);
            }
        });
        receiver.start();
        pc.send(to(link, "first: refused"));
        Thread.sleep(200);
        pc.send(to(link, "second: taken"));
        receiver.join(5000);
        assertEquals("second: taken", got.get());
        assertEquals(1, link.refusedIn());
    }

    /**
     * The phone's link socket as Udp opens it: where the watch's rule wants it. Here this PC's loopback stands in for
     * the phone's Tailscale address (a port of its own for each "address"), and the PC's packets from loopback for a
     * Tailscale peer's - who passes is the watch's word that the phone is on Tailscale.
     */
    @Test
    void tailscaleOffIsSilentAndWhenItComesUpTheWaitingLinkAnswersWithoutARestartThenFollowsAChange() throws Exception {
        DatagramSocket pc = keep(new DatagramSocket(0, LOCAL));
        TailscaleOnly.Watch watch = new TailscaleOnly.Watch();
        AtomicReference<TailscaleOnly.Place> wanted = new AtomicReference<>(TailscaleOnly.Place.PARKED);
        InetAddress first = ip("100.101.102.103"), second = ip("100.77.0.5");
        int portFirst, portSecond;
        try (DatagramSocket a = new DatagramSocket(0, LOCAL); DatagramSocket b = new DatagramSocket(0, LOCAL)) {
            portFirst = a.getLocalPort();
            portSecond = b.getLocalPort();
        }
        LinkSocket link = keep(new LinkSocket(() -> {
            TailscaleOnly.Place p = wanted.get();
            DatagramSocket s = p.parked ? p.open(6286)
                    : new DatagramSocket(p.address.equals(first) ? portFirst : portSecond, LOCAL);
            watch.opened(p);
            return s;
        }, a -> !watch.silent()));
        assertTrue(watch.silent(), "no Tailscale at the start: parked");
        assertTrue(link.getLocalAddress().isLoopbackAddress());

        // the link's receive thread waits all along, as Udp's does
        CopyOnWriteArrayList<String> heard = new CopyOnWriteArrayList<>();
        Thread receiver = new Thread(() -> {
            DatagramPacket p = new DatagramPacket(new byte[64], 64);
            while (!link.isClosed()) {
                try {
                    link.receive(p);
                    heard.add(new String(p.getData(), 0, p.getLength()));
                } catch (IOException e) {
                    // closed, or a reopen under it
                }
            }
        });
        receiver.setDaemon(true);
        receiver.start();

        // silent: a datagram to the parked socket is not taken, and the phone sends nothing
        pc.send(to(link, "while parked"));
        link.send(to(pc, "hello while parked"));
        assertThrows(SocketTimeoutException.class, () -> take(pc, 400), "nothing left the phone");
        Thread.sleep(200);
        assertEquals(List.of(), heard, "nothing taken while parked");

        // Tailscale up: the next look finds it, the socket is opened anew there - the same receive goes on
        wanted.set(TailscaleOnly.place(first, false));
        assertTrue(watch.rebindDue(wanted.get()));
        assertTrue(link.reopen());
        assertFalse(watch.rebindDue(wanted.get()));
        assertEquals(portFirst, link.getLocalPort());
        pc.send(new DatagramPacket("connect".getBytes(), 7, LOCAL, portFirst));
        long end = System.currentTimeMillis() + 3000;
        while (heard.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20);
        assertEquals(List.of("connect"), heard, "taken by the receive that waited since before Tailscale came");
        link.send(to(pc, "answer"));
        assertEquals("answer", take(pc, 2000));

        // the address changes: opened anew on the new one, the old one takes nothing more
        wanted.set(TailscaleOnly.place(second, false));
        assertTrue(watch.rebindDue(wanted.get()));
        assertTrue(link.reopen());
        assertEquals(portSecond, link.getLocalPort());
        pc.send(new DatagramPacket("to the old".getBytes(), 10, LOCAL, portFirst));
        pc.send(new DatagramPacket("to the new".getBytes(), 10, LOCAL, portSecond));
        end = System.currentTimeMillis() + 3000;
        while (heard.size() < 2 && System.currentTimeMillis() < end) Thread.sleep(20);
        assertEquals(List.of("connect", "to the new"), heard);

        // Tailscale gone: parked and silent again
        wanted.set(TailscaleOnly.Place.PARKED);
        assertTrue(watch.rebindDue(wanted.get()));
        assertTrue(link.reopen());
        assertTrue(watch.silent());
        link.send(to(pc, "after Tailscale went"));
        assertThrows(SocketTimeoutException.class, () -> take(pc, 400));
        assertEquals(3, link.reopens());
    }
}
