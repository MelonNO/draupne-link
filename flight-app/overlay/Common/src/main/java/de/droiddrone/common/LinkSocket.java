/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone.
 *
 *  DroidDrone is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DroidDrone is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidDrone.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.common;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DRAUPNE Flight: the link's UDP socket, one that can be opened anew under everything that uses it (pc.55).
 * <p>
 * Found by the test bench (2026-11-19, the real app in the emulator): every sender of the phone - telemetry, video,
 * sound, the answers to the PC's Connect - shares one socket, and a send waits while the socket's send buffer is full.
 * When the network under it kept what was sent and never let go of it, the buffer stayed full for good: all four
 * sender threads stood in their send for the rest of the app's life, while the phone still heard the PC and started a
 * new session at every Connect it could not answer. Nothing but ending the app brought the link back.
 * <p>
 * This socket knows how long its sends have been standing still ({@link #stuckMs}), and {@link #reopen} closes the
 * real socket - which lets every waiting send go, with an error, as lost - and opens a new one on the same port.
 * Who holds this socket notices nothing else: a receive that was waiting goes on waiting on the new one.
 * {@link SendWatch} decides when. Plain java.net: the PC's tests check it.
 * <p>
 * Q-006 (Tailscale only): with {@link Peers}, a datagram from an address they refuse is dropped here - its receive
 * goes on waiting, nobody above ever sees it - and one to such an address does not leave (as lost, without an error).
 * {@link TailscaleOnly.Watch} is the phone's: Tailscale's addresses, nothing at all while parked.
 */
public class LinkSocket extends DatagramSocket {
    /** Opens the real socket, bound and set up; called again by {@link #reopen}. */
    public interface Opener {
        DatagramSocket open() throws IOException;
    }

    /** Q-006: who may be heard and sent to through this socket. */
    public interface Peers {
        boolean allows(InetAddress address);
    }

    /** How long a reopen tries to bind the port again before it gives up (and is {@link #broken}), ms. */
    static final long REBIND_MS = 1000;
    private final Opener opener;
    private final Peers peers;
    private final Object swap = new Object();
    private volatile DatagramSocket real;
    private volatile boolean closed;
    private final AtomicInteger sending = new AtomicInteger();
    private volatile long progressAtNs = System.nanoTime();
    private volatile int reopens;
    private final AtomicInteger refusedIn = new AtomicInteger(), refusedOut = new AtomicInteger();

    public LinkSocket(Opener opener) throws IOException {
        this(opener, null);
    }

    /** @param peers who may be heard and sent to; null: anyone */
    public LinkSocket(Opener opener, Peers peers) throws IOException {
        super((SocketAddress) null); // this object itself is never bound: all goes to the real socket
        this.opener = opener;
        this.peers = peers;
        try {
            real = opener.open();
        } catch (IOException | RuntimeException e) {
            super.close();
            throw e;
        }
    }

    @Override
    public void send(DatagramPacket p) throws IOException {
        // Q-006: to an address the peers refuse nothing leaves - as a datagram lost on the way (no error to log)
        if (peers != null && !closed && p.getAddress() != null && !peers.allows(p.getAddress())) {
            refusedOut.incrementAndGet();
            return;
        }
        DatagramSocket r = real;
        // the wait, if there is one, starts now - stamped before it is counted, or the watch could read "one waits"
        // against the stamp of the last send, seconds old
        if (sending.get() == 0) progressAtNs = System.nanoTime();
        sending.incrementAndGet();
        try {
            r.send(p);
        } finally {
            sending.decrementAndGet();
            progressAtNs = System.nanoTime(); // one got out (or was let go): the socket moves
        }
    }

    /**
     * For how long sends have been waiting without a single one getting out, ms; 0 while none waits. A send that takes
     * its time on a slow uplink does not count up: every datagram that leaves starts the count again.
     */
    public long stuckMs() {
        return sending.get() > 0 ? Math.max(0, (System.nanoTime() - progressAtNs) / 1_000_000) : 0;
    }

    /** The real socket is closed though nobody closed this one (a reopen that could not bind): to be opened again. */
    public boolean broken() {
        return !closed && real.isClosed();
    }

    /** How many times the real socket was opened anew. */
    public int reopens() {
        return reopens;
    }

    /** Q-006: datagrams dropped so far, that came from (in) or were to go to (out) an address the peers refuse. */
    public int refusedIn() {
        return refusedIn.get();
    }

    public int refusedOut() {
        return refusedOut.get();
    }

    /**
     * The real socket closed and a new one opened on the same port. Every send that was waiting returns with an error
     * (its datagram is lost, as on any network); a waiting receive carries on with the new socket.
     * @return whether a new socket is open (false: closed for good, or the port could not be bound - {@link #broken})
     */
    public boolean reopen() {
        synchronized (swap) {
            if (closed) return false;
            real.close();
            IOException last = null;
            // The port is free only when every thread that stood in a send or a receive on the old socket has left
            // it: each holds the socket in the system until then. They are let go by the close and leave within
            // moments - tried every 5 ms for a second, since the phone hears no sticks while this lasts.
            long giveUpAt = System.nanoTime() + REBIND_MS * 1_000_000L;
            while (!closed) {
                try {
                    real = opener.open();
                    reopens++;
                    swap.notifyAll();
                    return true;
                } catch (IOException | RuntimeException e) {
                    last = e instanceof IOException ? (IOException) e : new IOException(e);
                    if (System.nanoTime() >= giveUpAt) break;
                    try {
                        swap.wait(5);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (last != null) Log.log("Link socket: could not be opened anew: " + last);
            return false;
        }
    }

    @Override
    public void receive(DatagramPacket p) throws IOException {
        while (true) {
            DatagramSocket r = real;
            try {
                r.receive(p);
                // Q-006: from an address the peers refuse - dropped, the receive goes on
                if (peers != null && !peers.allows(p.getAddress())) {
                    refusedIn.incrementAndGet();
                    continue;
                }
                return;
            } catch (SocketTimeoutException e) {
                throw e;
            } catch (IOException e) {
                if (closed) throw e;
                synchronized (swap) { // closed under this receive by a reopen: wait for the new one
                    if (real == r && !closed && r.isClosed()) {
                        try {
                            swap.wait(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (real == r) throw e; // no reopen (or one that failed): the error is the caller's
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (swap) {
            // this object's own unused socket first: Android's close() asks isClosed() and would leave it open
            // once the flag below is set - one descriptor lost at every deep-sleep check
            super.close();
            closed = true;
            DatagramSocket r = real;
            if (r != null) r.close();
            swap.notifyAll();
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean isBound() {
        return real.isBound();
    }

    @Override
    public int getLocalPort() {
        return real.getLocalPort();
    }

    @Override
    public InetAddress getLocalAddress() {
        return real.getLocalAddress();
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return real.getLocalSocketAddress();
    }

    @Override
    public void setSoTimeout(int timeout) throws SocketException {
        real.setSoTimeout(timeout);
    }

    @Override
    public int getSoTimeout() throws SocketException {
        return real.getSoTimeout();
    }

    @Override
    public int getSendBufferSize() throws SocketException {
        return real.getSendBufferSize();
    }

    @Override
    public int getReceiveBufferSize() throws SocketException {
        return real.getReceiveBufferSize();
    }
}
