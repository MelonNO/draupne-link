package de.droiddrone.pc;

import de.droiddrone.common.LinkSocket;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Objects;
import java.util.function.Consumer;

import static de.droiddrone.common.Log.log;

/**
 * S-LINK-2: one of this PC's sockets for phones and other PCs - bound where {@link LinkPlace} says for its peer's side,
 * never on every address, with that side's addresses as its gate: a datagram from another address is dropped, one to
 * another address does not leave ({@link LinkSocket.Peers}).
 * <p>
 * On the Tailscale side it follows this PC's Tailscale address ({@link OwnTailscale}): parked and silent while there is
 * none ("No Tailscale"), opened anew where it belongs when the address comes, changes or goes - on the address
 * watch's thread, never the window's. Who holds the socket notices nothing else: a receive that waits goes on waiting
 * on the new one ({@link LinkSocket#reopen}). A socket at port 0 keeps the port it was given, where it can.
 */
public final class PeerSocket extends LinkSocket {
    /** The socket's options, set on every real socket it opens (a reopen's too). */
    public interface Setup {
        void apply(DatagramSocket s) throws IOException;
    }

    private final Binder binder;
    private volatile Consumer<LinkPlace> moved;

    private PeerSocket(Binder b) throws IOException {
        super(b, b);
        binder = b;
        if (b.follows()) b.own.follow(this);
    }

    /** For talking with this peer: bound by its side (loopback, this PC's Tailscale address), at port (0: the system's choice). */
    public static PeerSocket forPeer(InetAddress peer, int port, OwnTailscale own, Setup setup) throws IOException {
        return new PeerSocket(new Binder(LinkPlace.side(peer), peer instanceof Inet6Address, null, port, own, setup));
    }

    /** For the peers on one side (IPv4): a listener on that side's address of this PC. */
    public static PeerSocket onSide(LinkPlace.Side side, int port, OwnTailscale own, Setup setup) throws IOException {
        return new PeerSocket(new Binder(side, false, null, port, own, setup));
    }

    /** At one fixed address of this PC's own (the virtual fleet's pilot PC: a loopback address of its own). */
    public static PeerSocket at(InetAddress fixed, int port, Setup setup) throws IOException {
        Objects.requireNonNull(fixed);
        return new PeerSocket(new Binder(LinkPlace.fixed(fixed).side, fixed instanceof Inet6Address, fixed, port, null, setup));
    }

    /**
     * Beside another socket: on the same side and address, following it alike, at a port of its own (0: the system's
     * choice). Beside a plain socket bound to one address: at that address.
     */
    public static PeerSocket beside(DatagramSocket other, int port, Setup setup) throws IOException {
        if (other instanceof PeerSocket p) {
            Binder b = p.binder;
            return new PeerSocket(new Binder(b.side, b.v6, b.fixed, port, b.own, setup));
        }
        InetAddress a = other.getLocalAddress();
        if (a == null || a.isAnyLocalAddress()) throw new SocketException("not bound to one address");
        return at(a, port, setup);
    }

    /** Where the socket is bound now. */
    public LinkPlace bound() {
        return binder.bound;
    }

    /** Parked: this PC has no Tailscale address, and the socket takes and sends nothing until it has one. */
    public boolean waitsForTailscale() {
        LinkPlace b = binder.bound;
        return b != null && b.parked();
    }

    /** Told where the socket went after it was opened anew (the address watch's thread). */
    public void onMoved(Consumer<LinkPlace> listener) {
        moved = listener;
    }

    /**
     * Opened anew when it is not bound where it belongs now (the address came, changed or went, or a bind before was
     * refused). Called by the address watch; never on the window's thread.
     * @return whether it was opened anew
     */
    public boolean follow() {
        if (isClosed()) return false;
        LinkPlace was = binder.bound, wanted = binder.wanted();
        if (wanted.equals(was)) return false;
        reopen();
        LinkPlace now = binder.bound;
        Consumer<LinkPlace> m = moved;
        if (m != null && !Objects.equals(was, now)) {
            try {
                m.accept(now);
            } catch (RuntimeException e) {
                log("Peer socket: " + e);
            }
        }
        return true;
    }

    @Override
    public void setSoTimeout(int timeout) throws SocketException {
        binder.timeout = timeout; // a reopen's socket has it too
        super.setSoTimeout(timeout);
    }

    @Override
    public void close() {
        if (binder.own != null) binder.own.unfollow(this);
        super.close();
    }

    /** Opens the real socket where the rule wants it now, and is its gate. */
    private static final class Binder implements LinkSocket.Opener, LinkSocket.Peers {
        final LinkPlace.Side side;
        final boolean v6;
        final InetAddress fixed;
        final int port;
        final OwnTailscale own;
        final Setup setup;
        volatile LinkPlace bound;
        volatile int timeout;
        /** Port 0 asked: the one the system gave, asked for again at a reopen. */
        private volatile int keptPort;
        private boolean opened;
        private long refusedSaidAt = Long.MIN_VALUE / 2;

        Binder(LinkPlace.Side side, boolean v6, InetAddress fixed, int port, OwnTailscale own, Setup setup) {
            this.side = side;
            this.v6 = v6;
            this.fixed = fixed;
            this.port = port;
            this.own = side == LinkPlace.Side.TAILSCALE && fixed == null ? Objects.requireNonNull(own) : own;
            this.setup = setup;
        }

        boolean follows() {
            return fixed == null && side == LinkPlace.Side.TAILSCALE;
        }

        LinkPlace wanted() {
            if (fixed != null) return LinkPlace.fixed(fixed);
            return LinkPlace.of(side, v6, side == LinkPlace.Side.TAILSCALE ? own.of(v6) : null);
        }

        @Override
        public synchronized DatagramSocket open() throws IOException {
            LinkPlace at = wanted();
            DatagramSocket s;
            try {
                s = bind(at);
            } catch (IOException | RuntimeException e) {
                if (!opened || at.parked()) throw e; // the first open: who asked hears why (the port held)
                // opened anew (the address came or changed) where the port is held: parked, tried again at the next look
                long now = MonoClock.ms();
                if (now - refusedSaidAt >= 60_000) {
                    refusedSaidAt = now;
                    log("Peer socket: " + at + ":" + port + " could not be bound (" + e.getMessage() + ") - waiting, tried again");
                }
                at = LinkPlace.parked(side);
                s = at.open(0);
            }
            try {
                if (setup != null) setup.apply(s);
                if (timeout > 0) s.setSoTimeout(timeout);
            } catch (IOException | RuntimeException e) {
                s.close();
                throw e;
            }
            if (port == 0 && !at.parked()) keptPort = s.getLocalPort();
            bound = at;
            opened = true;
            return s;
        }

        private DatagramSocket bind(LinkPlace at) throws IOException {
            if (at.parked() || port != 0) return at.open(port);
            int kept = keptPort;
            if (kept > 0) {
                try {
                    return at.open(kept);
                } catch (IOException e) {
                    // taken meanwhile: another one
                }
            }
            return at.open(0);
        }

        @Override
        public boolean allows(InetAddress a) {
            LinkPlace b = bound;
            return b != null && b.passes(a);
        }
    }
}
