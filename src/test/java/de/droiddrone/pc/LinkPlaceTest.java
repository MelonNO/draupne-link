package de.droiddrone.pc;

import de.droiddrone.testing.Ports;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S-LINK-2: where the PC's sockets for phones and other PCs are bound - by the peer: loopback for this PC's own,
 * this PC's Tailscale address for phones and PCs (parked while there is none, following it when it comes, changes or
 * goes), nothing for any other address - and never on every address.
 * <p>
 * This PC has no Tailscale address to spare for a test: where a socket is bound to "this PC's Tailscale address", a
 * second loopback address stands in for it (127.0.0.2, 127.0.0.3), the peer being a made-up Tailscale address.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LinkPlaceTest {
    private static final InetAddress LOCAL = ip("127.0.0.1"), STAND_IN = ip("127.0.0.2"), STAND_IN_2 = ip("127.0.0.3");
    private static final InetAddress PHONE = ip("100.64.0.9");
    private final List<AutoCloseable> open = new CopyOnWriteArrayList<>();

    private static InetAddress ip(String s) {
        try {
            return InetAddress.getByName(s); // numbers only: nothing is looked up
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(s, e);
        }
    }

    private <T extends AutoCloseable> T keep(T c) {
        open.add(c);
        return c;
    }

    @AfterEach
    void closeAll() throws Exception {
        for (AutoCloseable c : open) c.close();
    }

    /** Whether something on this PC holds this address and port now (a bind there is refused). */
    static boolean held(InetAddress a, int port) {
        try (DatagramSocket probe = new DatagramSocket(new InetSocketAddress(a, port))) {
            return false;
        } catch (BindException e) {
            return true;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------ the choice

    @Test
    void theSideIsThePeers() {
        for (String loop : List.of("127.0.0.1", "127.0.0.11", "127.255.255.254", "::1")) {
            assertEquals(LinkPlace.Side.LOOPBACK, LinkPlace.sideOf(ip(loop)), loop);
        }
        for (String ts : List.of("100.64.0.9", "100.100.100.100", "100.64.0.254", "fd7a:115c:a1e0::9")) {
            assertEquals(LinkPlace.Side.TAILSCALE, LinkPlace.sideOf(ip(ts)), ts);
        }
        for (String other : List.of("192.0.2.5", "198.51.100.7", "203.0.113.9", "0.0.0.0", "2001:db8::5")) {
            assertNull(LinkPlace.sideOf(ip(other)), other);
            LinkPlace.NotTailscale refused = assertThrows(LinkPlace.NotTailscale.class, () -> LinkPlace.side(ip(other)), other);
            assertEquals("Not a Tailscale address: " + ip(other).getHostAddress(), refused.getMessage());
        }
        assertNull(LinkPlace.sideOf(null));
    }

    @Test
    void whereEachSideIsBound() {
        assertEquals(LOCAL, LinkPlace.of(LinkPlace.Side.LOOPBACK, false, STAND_IN).address, "a loopback peer: 127.0.0.1, never the Tailscale address");
        assertEquals(ip("::1"), LinkPlace.of(LinkPlace.Side.LOOPBACK, true, null).address, "an IPv6 loopback peer: ::1");
        assertEquals(PHONE, LinkPlace.of(LinkPlace.Side.TAILSCALE, false, PHONE).address, "a Tailscale peer: this PC's Tailscale address");
        LinkPlace parked = LinkPlace.of(LinkPlace.Side.TAILSCALE, false, null);
        assertTrue(parked.parked(), "none: parked");
        assertEquals("No Tailscale", parked.toString());
    }

    @Test
    void eachSideTakesItsOwnAddressesOnly() {
        LinkPlace loop = LinkPlace.of(LinkPlace.Side.LOOPBACK, false, null);
        assertTrue(loop.passes(ip("127.0.0.11")));
        assertFalse(loop.passes(PHONE));
        assertFalse(loop.passes(ip("192.0.2.5")));
        LinkPlace ts = LinkPlace.of(LinkPlace.Side.TAILSCALE, false, STAND_IN);
        assertTrue(ts.passes(PHONE));
        assertTrue(ts.passes(ip("fd7a:115c:a1e0::9")));
        assertFalse(ts.passes(LOCAL), "a Tailscale socket takes nothing from loopback");
        assertFalse(ts.passes(ip("192.0.2.5")), "nor from a LAN");
        LinkPlace parked = LinkPlace.parked(LinkPlace.Side.TAILSCALE);
        assertFalse(parked.passes(PHONE), "parked: nothing at all");
        assertFalse(parked.passes(LOCAL));
    }

    @Test
    void noSocketIsEverOnEveryAddress() throws Exception {
        int port = Ports.free();
        for (LinkPlace at : List.of(LinkPlace.of(LinkPlace.Side.LOOPBACK, false, null), LinkPlace.fixed(STAND_IN), LinkPlace.parked(LinkPlace.Side.TAILSCALE))) {
            try (DatagramSocket s = at.open(at.parked() ? 0 : port)) {
                assertFalse(s.getLocalAddress().isAnyLocalAddress(), at + ": bound to one address");
                if (!at.parked()) assertEquals(new InetSocketAddress(at.address, port), s.getLocalSocketAddress());
                else assertTrue(s.getLocalAddress().isLoopbackAddress(), "parked on loopback");
            }
        }
    }

    // ------------------------------------------------------------------ one address taken, the others not

    /**
     * A socket bound to one address of this PC takes nothing sent to another address of it at the same port - here
     * 127.0.0.1 and 127.0.0.2, as the Tailscale address and a LAN address. On Windows too.
     */
    @Test
    void aSocketOnOneAddressDoesNotTakeWhatIsSentToAnother() throws Exception {
        int port = Ports.free();
        PeerSocket link = keep(PeerSocket.forPeer(ip("127.0.0.11"), port, new OwnTailscale(() -> OwnTailscale.Found.NONE, 1000), null));
        assertEquals(new InetSocketAddress(LOCAL, port), link.getLocalSocketAddress(), "a loopback peer: on 127.0.0.1 only");
        link.setSoTimeout(400);
        try (DatagramSocket sender = new DatagramSocket(new InetSocketAddress(LOCAL, 0))) {
            sender.send(new DatagramPacket(new byte[]{1}, 1, STAND_IN, port));
            DatagramPacket in = new DatagramPacket(new byte[8], 8);
            assertThrows(SocketTimeoutException.class, () -> link.receive(in), "sent to 127.0.0.2: not taken");
            sender.send(new DatagramPacket(new byte[]{2}, 1, LOCAL, port));
            link.receive(in);
            assertEquals(2, in.getData()[0], "sent to 127.0.0.1: taken");
        }
        assertFalse(held(STAND_IN, port), "the other address at the same port is anybody's");
    }

    @Test
    void twoCopiesNeverShareAnAddressAndPort() throws Exception {
        int port = Ports.free();
        OwnTailscale none = keep(new OwnTailscale(() -> OwnTailscale.Found.NONE, 1000));
        keep(PeerSocket.onSide(LinkPlace.Side.LOOPBACK, port, none, null));
        assertThrows(BindException.class, () -> PeerSocket.onSide(LinkPlace.Side.LOOPBACK, port, none, null),
                "a second copy of the app is refused, not handed half the packets");
    }

    @Test
    void aPeerOnNeitherSideGetsNoSocket() throws Exception {
        int port = Ports.free();
        OwnTailscale own = keep(new OwnTailscale(() -> new OwnTailscale.Found(STAND_IN, null), 1000));
        for (String lan : List.of("192.0.2.5", "198.51.100.7", "203.0.113.10")) {
            IOException e = assertThrows(LinkPlace.NotTailscale.class, () -> PeerSocket.forPeer(ip(lan), port, own, null), lan);
            assertTrue(e.getMessage().startsWith("Not a Tailscale address"), e.getMessage());
        }
        try (DatagramSocket every = new DatagramSocket(port)) { // nothing was opened: the port is free on every address
            assertTrue(every.isBound());
        }
    }

    // ------------------------------------------------------------------ following the address

    @Test
    void noTailscaleWaitsThenFollowsTheAddressAsItComesChangesAndGoes() throws Exception {
        int port = Ports.free();
        OwnTailscale.Found[] now = {OwnTailscale.Found.NONE};
        OwnTailscale own = keep(new OwnTailscale(() -> now[0], 20));
        List<LinkPlace> moves = new CopyOnWriteArrayList<>();
        PeerSocket s = keep(PeerSocket.forPeer(PHONE, port, own, null));
        s.onMoved(moves::add);
        assertTrue(s.waitsForTailscale(), "no Tailscale: parked");
        assertTrue(s.getLocalAddress().isLoopbackAddress() && s.getLocalPort() != port, "parked on loopback, not at the port");
        s.send(new DatagramPacket(new byte[]{1}, 1, PHONE, 9));
        assertEquals(1, s.refusedOut(), "and nothing leaves it");

        InetSocketAddress first = new InetSocketAddress(STAND_IN, port), second = new InetSocketAddress(STAND_IN_2, port);
        now[0] = new OwnTailscale.Found(STAND_IN, null); // the address comes
        Waits.waitFor(() -> first.equals(s.getLocalSocketAddress()), 3000, "the socket opened on the address when it came");
        assertFalse(s.waitsForTailscale());
        assertTrue(held(STAND_IN, port));
        assertEquals(List.of(LinkPlace.of(LinkPlace.Side.TAILSCALE, false, STAND_IN)), moves, "said where it went");

        now[0] = new OwnTailscale.Found(STAND_IN_2, null); // it changes
        Waits.waitFor(() -> second.equals(s.getLocalSocketAddress()), 3000, "opened anew on the new address");
        assertFalse(held(STAND_IN, port), "the old one let go");

        now[0] = OwnTailscale.Found.NONE; // it goes
        Waits.waitFor(s::waitsForTailscale, 3000, "parked again when it went");
        assertFalse(held(STAND_IN_2, port));
        assertTrue(moves.get(moves.size() - 1).parked());
        assertEquals(3, moves.size(), "one move for each change, on the address watch's thread: " + moves);

        s.close();
        assertEquals(0, own.following(), "a closed socket follows nothing");
    }

    @Test
    void aSocketAtPortZeroKeepsItsPortWhenTheAddressChanges() throws Exception {
        OwnTailscale.Found[] now = {new OwnTailscale.Found(STAND_IN, null)};
        OwnTailscale own = keep(new OwnTailscale(() -> now[0], 20));
        PeerSocket s = keep(PeerSocket.onSide(LinkPlace.Side.TAILSCALE, 0, own, a -> a.setSendBufferSize(1 << 16)));
        int port = s.getLocalPort();
        assertEquals(STAND_IN, s.getLocalAddress());
        PeerSocket beside = keep(PeerSocket.beside(s, 0, null));
        assertEquals(STAND_IN, beside.getLocalAddress(), "a socket beside it: on its address");
        now[0] = new OwnTailscale.Found(STAND_IN_2, null);
        Waits.waitFor(() -> STAND_IN_2.equals(s.getLocalAddress()) && STAND_IN_2.equals(beside.getLocalAddress()), 3000, "both follow the address");
        assertEquals(port, s.getLocalPort(), "the port the system gave, kept: what peers were told stays true");
        assertEquals(1 << 16, s.getSendBufferSize(), "its options set on the new socket too");
    }

    @Test
    void anAddressThatCannotBeBoundIsTriedAgain() throws Exception {
        int port = Ports.free();
        OwnTailscale.Found[] now = {OwnTailscale.Found.NONE};
        OwnTailscale own = keep(new OwnTailscale(() -> now[0], 20));
        PeerSocket s = keep(PeerSocket.forPeer(PHONE, port, own, null));
        DatagramSocket other = keep(new DatagramSocket(new InetSocketAddress(STAND_IN, port))); // something else holds it
        now[0] = new OwnTailscale.Found(STAND_IN, null);
        Thread.sleep(200); // several looks: each finds the port held
        assertTrue(s.waitsForTailscale(), "the port held: parked, nothing taken from what holds it");
        other.close();
        Waits.waitFor(() -> !s.waitsForTailscale(), 3000, "bound once it is free");
        assertEquals(new InetSocketAddress(STAND_IN, port), s.getLocalSocketAddress());
    }

    @Test
    void theFirstBindRefusedIsSaid() throws Exception {
        int port = Ports.free();
        OwnTailscale own = keep(new OwnTailscale(() -> new OwnTailscale.Found(STAND_IN, null), 1000));
        keep(new DatagramSocket(new InetSocketAddress(STAND_IN, port)));
        assertThrows(BindException.class, () -> PeerSocket.forPeer(PHONE, port, own, null), "at the start the caller hears it (the port held)");
    }
}
