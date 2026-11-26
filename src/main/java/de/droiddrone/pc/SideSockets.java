package de.droiddrone.pc;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * S-LINK-2: for what talks with peers on both sides - this PC's own on loopback (the virtual fleet), phones and PCs on
 * Tailscale - one {@link PeerSocket} per side, each opened when first needed at a port of the system's choice. A peer
 * on neither side gets nothing ({@link LinkPlace.NotTailscale}).
 */
public final class SideSockets implements AutoCloseable {
    /** A receive waiting on one side gives the other its turn after this long, ms. */
    static final int TURN_MS = 20;

    private final OwnTailscale own;
    private final PeerSocket.Setup setup;
    /** By side and kind (IPv4, IPv6). */
    private final PeerSocket[] sockets = new PeerSocket[LinkPlace.Side.values().length * 2];
    private int turn;
    private boolean closed;

    public SideSockets(OwnTailscale own, PeerSocket.Setup setup) {
        this.own = own;
        this.setup = setup;
    }

    /** The socket for this peer's side, opened now when it isn't yet. */
    public synchronized PeerSocket of(InetAddress peer) throws IOException {
        LinkPlace.Side side = LinkPlace.side(peer);
        int i = side.ordinal() * 2 + (peer instanceof Inet6Address ? 1 : 0);
        PeerSocket s = sockets[i];
        if (s == null) {
            if (closed) throw new SocketException("closed");
            s = PeerSocket.forPeer(peer, 0, own, setup);
            sockets[i] = s;
        }
        return s;
    }

    /** Sent from the socket of its address's side. */
    public void send(DatagramPacket p) throws IOException {
        of(p.getAddress()).send(p);
    }

    private synchronized List<PeerSocket> opened() {
        List<PeerSocket> out = new ArrayList<>();
        for (PeerSocket s : sockets) if (s != null) out.add(s);
        return out;
    }

    /**
     * The next datagram from any side within timeoutMs: the sides take turns, TURN_MS each (what comes meanwhile waits
     * in its socket).
     * @throws SocketTimeoutException when none came in that time
     */
    public void receive(DatagramPacket p, int timeoutMs) throws IOException {
        long end = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            long left = (end - System.nanoTime()) / 1_000_000;
            if (left <= 0) throw new SocketTimeoutException("nothing came");
            List<PeerSocket> open = opened();
            if (open.isEmpty()) { // nothing sent yet: nothing to come
                try {
                    Thread.sleep(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SocketTimeoutException("interrupted");
                }
                continue;
            }
            PeerSocket s = open.get(Math.floorMod(turn++, open.size()));
            s.setSoTimeout((int) Math.max(1, open.size() == 1 ? left : Math.min(left, TURN_MS)));
            try {
                s.receive(p);
                return;
            } catch (SocketTimeoutException e) {
                // the other side's turn
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (int i = 0; i < sockets.length; i++) {
            if (sockets[i] != null) sockets[i].close();
            sockets[i] = null;
        }
    }
}
