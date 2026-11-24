package de.droiddrone.bench.phone;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BS1's reading of the phone's socket tables and addresses ({@link Sockets}), on lines as Android writes them. The
 * addresses are made up: Tailscale's own well-known service address (100.100.100.100, fd7a:115c:a1e0::53), the
 * documentation ranges (192.0.2.0/24, 2001:db8::/32) and loopback - and one carrier address of Tailscale's range.
 */
class SocketsTest {
    private static final String UDP_HEAD = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops\n";

    /** A table line: addresses as the kernel prints them (each 32-bit word little-endian), ports in hex. */
    private static String line(int sl, String local, int port, String remote, int remotePort, int state, int uid) {
        return String.format("%5d: %s:%04X %s:%04X %02X 00000000:00000000 00:00000000 00000000 %5d        0 512345 2 0000000000000000 0%n",
                sl, local, port, remote, remotePort, state, uid);
    }

    private static final String V4_ANY = "00000000", V4_LOOP = "0100007F", V4_DOC = "0A0200C0", V4_TS = "64646464";
    private static final String V6_ANY = "0".repeat(32), V6_LOOP = "00000000000000000000000001000000",
            V6_MAPPED_LOOP = "0000000000000000FFFF00000100007F", V6_TS = "5C117AFD0000E0A10000000053000000";

    @Test
    void anAddressIsReadWordByWordBackwards() {
        assertArrayEquals(new byte[]{127, 0, 0, 1}, Sockets.address(V4_LOOP));
        assertArrayEquals(new byte[]{(byte) 192, 0, 2, 10}, Sockets.address(V4_DOC));
        assertArrayEquals(Sockets.numbers("fd7a:115c:a1e0::53", true), Sockets.address(V6_TS));
        assertArrayEquals(Sockets.numbers("::1", true), Sockets.address(V6_LOOP));
        assertArrayEquals(new byte[]{127, 0, 0, 1}, Sockets.plain(Sockets.address(V6_MAPPED_LOOP)));
    }

    @Test
    void eachAddressHasItsKind() {
        assertEquals(Sockets.Where.EVERY_ADDRESS, Sockets.where(Sockets.address(V4_ANY)));
        assertEquals(Sockets.Where.EVERY_ADDRESS, Sockets.where(Sockets.address(V6_ANY)));
        assertEquals(Sockets.Where.LOOPBACK, Sockets.where(Sockets.address(V4_LOOP)));
        assertEquals(Sockets.Where.LOOPBACK, Sockets.where(Sockets.address(V6_LOOP)));
        assertEquals(Sockets.Where.LOOPBACK, Sockets.where(Sockets.address(V6_MAPPED_LOOP)));
        assertEquals(Sockets.Where.TAILSCALE, Sockets.where(Sockets.address(V4_TS)));
        assertEquals(Sockets.Where.TAILSCALE, Sockets.where(Sockets.address(V6_TS)));
        assertEquals(Sockets.Where.OTHER, Sockets.where(Sockets.address(V4_DOC)));
        assertEquals(Sockets.Where.OTHER, Sockets.where(Sockets.numbers("2001:db8::7", true)));
        assertEquals(Sockets.Where.LOOPBACK, Sockets.where(new byte[]{127, 9, 8, 7}), "all of 127/8 is the phone's own");
    }

    @Test
    void theTablesAreReadWithEachSocketsOwner() {
        String udp = UDP_HEAD
                + line(7843, V4_TS, 6286, V4_ANY, 0, 0x07, 10180)       // the link, on Tailscale
                + line(7850, V4_LOOP, 40231, V4_ANY, 0, 0x07, 10180)    // overwatch, parked
                + line(7851, V4_ANY, 5353, V4_ANY, 0, 0x07, 1000)       // somebody else's, on every address
                + line(7852, V4_DOC, 41000, V4_DOC, 443, 0x01, 10180);  // connected to one peer
        List<Sockets.Sock> all = Sockets.parse("udp", udp);
        assertEquals(4, all.size(), "the header is no socket");
        List<Sockets.Sock> mine = Sockets.of(all, 10180);
        assertEquals(3, mine.size());
        Sockets.Sock link = mine.get(0);
        assertEquals(6286, link.localPort());
        assertEquals(Sockets.Where.TAILSCALE, link.where());
        assertTrue(link.listens());
        assertTrue(mine.get(1).listens());
        assertFalse(mine.get(2).listens(), "a UDP socket connected to one peer takes datagrams from it alone");
        assertEquals(Sockets.Where.EVERY_ADDRESS, all.get(2).where());

        String udp6 = "  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops\n"
                + line(1022, V6_ANY, 6286, V6_ANY, 0, 0x07, 10181);
        Sockets.Sock wide = Sockets.parse("udp6", udp6).get(0);
        assertEquals(Sockets.Where.EVERY_ADDRESS, wide.where());
        assertTrue(wide.listens());
        assertEquals(10181, wide.uid());

        String tcp = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
                + line(0, V4_LOOP, 5761, V4_LOOP, 41234, 0x01, 10180)     // the bench build's flight controller, a connection out
                + line(1, V4_ANY, 8080, V4_ANY, 0, Sockets.TCP_LISTEN, 10180);
        List<Sockets.Sock> t = Sockets.parse("tcp", tcp);
        assertFalse(t.get(0).listens(), "a TCP connection is no listener");
        assertTrue(t.get(1).listens());
        assertTrue(t.get(1).tcp());

        assertEquals(List.of(), Sockets.parse("udp", ""));
        assertEquals(List.of(), Sockets.parse("udp", "cat: /proc/net/udp: Permission denied"));
        assertEquals(List.of(), Sockets.parse("udp", null));
    }

    @Test
    void theInterfaceOfAnAddressIsFoundInIpsList() {
        String ip = "1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever preferred_lft forever\n"
                + "1: lo    inet6 ::1/128 scope host \\       valid_lft forever preferred_lft forever\n"
                + "14: wlan0    inet 192.0.2.10/24 brd 192.0.2.255 scope global wlan0\\       valid_lft forever preferred_lft forever\n"
                + "22: rmnet_data2    inet 100.64.0.1/30 scope global rmnet_data2\\       valid_lft forever preferred_lft forever\n"
                + "31: tun0    inet 100.100.100.100/32 scope global tun0\\       valid_lft forever preferred_lft forever\n"
                + "31: tun0    inet6 fd7a:115c:a1e0::53/128 scope global \\       valid_lft forever preferred_lft forever\n"
                + "40: eth0@if41    inet6 2001:db8::7/64 scope global \\       valid_lft forever preferred_lft forever\n";
        List<Sockets.Address> phone = Sockets.addresses(ip);
        assertEquals(7, phone.size());
        assertEquals("tun0", Sockets.ifaceOf(phone, Sockets.address(V4_TS)));
        assertEquals("tun0", Sockets.ifaceOf(phone, Sockets.address(V6_TS)));
        assertEquals("wlan0", Sockets.ifaceOf(phone, Sockets.address(V4_DOC)));
        assertEquals("lo", Sockets.ifaceOf(phone, Sockets.address(V6_MAPPED_LOOP)), "an IPv4 address inside IPv6 is the IPv4 one");
        assertEquals("eth0", Sockets.ifaceOf(phone, Sockets.numbers("2001:db8::7", true)));
        // a carrier's address of Tailscale's own range: its kind says Tailscale, its interface says it is not
        byte[] carrier = Sockets.numbers("100.64.0.1", false);
        assertEquals(Sockets.Where.TAILSCALE, Sockets.where(carrier));
        assertEquals("rmnet_data2", Sockets.ifaceOf(phone, carrier));
        assertEquals("", Sockets.ifaceOf(phone, Sockets.numbers("192.0.2.99", false)));
        assertEquals(List.of(), Sockets.addresses("ip: not found"));
        assertNull(Sockets.numbers("1::2::3", true));
        assertNull(Sockets.numbers("300.1.1.1", false));
    }

    @Test
    void theFilmGetsKindsAndInterfacesNotThePhonesNumbers() {
        Sockets.Sock link = Sockets.parse("udp", line(1, V4_TS, 6286, V4_ANY, 0, 7, 10180)).get(0);
        String said = Sockets.said(link, "tun0");
        assertEquals("UDP on a Tailscale address (on tun0), port 6286", said);
        assertFalse(said.contains("100."), said);
        Sockets.Sock other = Sockets.parse("udp", line(1, V4_DOC, 6286, V4_ANY, 0, 7, 10180)).get(0);
        assertEquals("UDP on ANOTHER ADDRESS OF THE PHONE'S (on wlan0), port 6286", Sockets.said(other, "wlan0"));
        Sockets.Sock any6 = Sockets.parse("udp6", line(1, V6_ANY, 6286, V6_ANY, 0, 7, 10180)).get(0);
        assertEquals("UDP on EVERY ADDRESS (::), port 6286", Sockets.said(any6, ""));
        Sockets.Sock parked = Sockets.parse("udp6", line(1, V6_MAPPED_LOOP, 40231, V6_ANY, 0, 7, 10180)).get(0);
        assertEquals("UDP on the phone's own loopback (127.0.0.1), port 40231", Sockets.said(parked, "lo"));
    }

    @Test
    void theProcessSaysItsOwnerAndThreads() {
        String status = "Name:\tdroiddrone.flight.pc.bench\nUmask:\t0077\nState:\tS (sleeping)\nTgid:\t4711\nPid:\t4711\nUid:\t10180\t10180\t10180\t10180\n"
                + "Gid:\t10180\t10180\t10180\t10180\nVmRSS:\t  201344 kB\nThreads:\t87\n";
        assertEquals(10180, Sockets.uid(status));
        assertEquals(87, Sockets.threads(status));
        assertEquals(201344, Sockets.rssKb(status));
        assertEquals(-1, Sockets.uid("cat: /proc/4711/status: No such file or directory"));
        assertEquals("4711", Sockets.firstPid("4711 4712\n"));
        assertEquals("", Sockets.firstPid(""));
        assertEquals("", Sockets.firstPid("error: no devices"));
    }
}
