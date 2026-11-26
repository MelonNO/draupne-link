package de.droiddrone.pc;

import de.droiddrone.common.TailscaleOnly;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * S-LINK-2: where one of this PC's sockets for phones and other PCs is bound, chosen by the peer it talks with (the
 * phone app does the same since pc.58, TailscaleOnly):
 * <ul>
 * <li>a loopback peer - this PC's own: the virtual fleet, DEMO, the test bench, an emulator's forwarded port - on
 * loopback;</li>
 * <li>a Tailscale peer on this PC's own Tailscale address ({@link OwnTailscale}); while there is none, parked: on
 * loopback at a port of the system's choice, taking and sending nothing ({@link #NO_TAILSCALE});</li>
 * <li>any other peer (an address of a LAN or Wi-Fi) on nothing: {@link NotTailscale}.</li>
 * </ul>
 * Never on every address: what comes to this PC's other addresses (Wi-Fi, LAN, a hotspot) reaches none of them.
 */
public final class LinkPlace {
    /** Which of this PC's addresses a socket is for. */
    public enum Side { LOOPBACK, TAILSCALE }

    /** The short words while this PC has no Tailscale address: the link waits for it. */
    public static final String NO_TAILSCALE = "No Tailscale";
    /** The short words for a peer whose address is neither this PC's own nor a Tailscale one. */
    public static final String NOT_TAILSCALE = "Not a Tailscale address";

    /** A peer at an address that is neither loopback nor Tailscale: no socket is opened for it. */
    public static final class NotTailscale extends IOException {
        public NotTailscale(InetAddress peer) {
            super(NOT_TAILSCALE + ": " + (peer == null ? "-" : peer.getHostAddress()));
        }
    }

    static final InetAddress LOOPBACK_V4 = numbers("localhost", new byte[]{127, 0, 0, 1});
    static final InetAddress LOOPBACK_V6 = numbers("localhost", new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});

    private static InetAddress numbers(String name, byte[] a) {
        try {
            return InetAddress.getByAddress(name, a);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The side a peer is on: loopback, Tailscale's ranges (100.64.0.0/10, fd7a:115c:a1e0::/48); null for any other. */
    public static Side sideOf(InetAddress peer) {
        if (peer == null) return null;
        if (peer.isLoopbackAddress()) return Side.LOOPBACK;
        if (TailscaleOnly.inRange(peer)) return Side.TAILSCALE;
        return null;
    }

    /** As {@link #sideOf}; a peer on neither side is refused. */
    public static Side side(InetAddress peer) throws NotTailscale {
        Side s = sideOf(peer);
        if (s == null) throw new NotTailscale(peer);
        return s;
    }

    public final Side side;
    /** Where the socket is bound; null while parked. */
    public final InetAddress address;

    private LinkPlace(Side side, InetAddress address) {
        this.side = side;
        this.address = address;
    }

    /**
     * Where a socket for a peer on this side goes now: loopback (127.0.0.1, or ::1 for an IPv6 peer), or this PC's own
     * Tailscale address of the peer's kind - parked while it has none.
     */
    public static LinkPlace of(Side side, boolean v6, InetAddress ownTailscale) {
        if (side == Side.LOOPBACK) return new LinkPlace(side, v6 ? LOOPBACK_V6 : LOOPBACK_V4);
        return new LinkPlace(Side.TAILSCALE, ownTailscale);
    }

    /** One address of this PC's own, fixed (the virtual fleet's pilot PC: a loopback address of its own). */
    public static LinkPlace fixed(InetAddress address) {
        return new LinkPlace(address.isLoopbackAddress() ? Side.LOOPBACK : Side.TAILSCALE, address);
    }

    public static LinkPlace parked(Side side) {
        return new LinkPlace(side, null);
    }

    /** No Tailscale address on this PC: the socket takes and sends nothing. */
    public boolean parked() {
        return address == null;
    }

    /** Whether a datagram from or to this address passes a socket bound here: its side's addresses; nothing while parked. */
    public boolean passes(InetAddress a) {
        if (a == null || parked()) return false;
        return side == Side.LOOPBACK ? a.isLoopbackAddress() : TailscaleOnly.peer(a);
    }

    /**
     * The socket bound here, at this port (0: the system's choice); parked: on loopback at a port of the system's choice.
     * <p>
     * The bind to one address is what keeps the socket to that address: it relies on Windows' strong host model, under
     * which a datagram reaches a socket bound to the Tailscale address only when it came in on Tailscale's adapter
     * addressed to it, and one sent from it leaves by that adapter. That holds on a PC that passes no packets on between
     * its adapters - not with Internet Connection Sharing, Hyper-V's forwarding, or this PC as a Tailscale subnet
     * router or exit node.
     */
    public DatagramSocket open(int port) throws IOException {
        if (parked()) return new DatagramSocket(new InetSocketAddress(LOOPBACK_V4, 0));
        return new DatagramSocket(new InetSocketAddress(address, port));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LinkPlace p && side == p.side && Objects.equals(address, p.address);
    }

    @Override
    public int hashCode() {
        return side.hashCode() * 31 + Objects.hashCode(address);
    }

    @Override
    public String toString() {
        return parked() ? NO_TAILSCALE : address.getHostAddress();
    }
}
