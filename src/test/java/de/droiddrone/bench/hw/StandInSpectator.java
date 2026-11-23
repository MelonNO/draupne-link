package de.droiddrone.bench.hw;

import de.droiddrone.common.PacketIds;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A second PC watching through the pilot's app, as far as the pilot's app can tell (spectator mode, pc/Spectate): on
 * this PC's loopback, it says HELLO to the app's link port with the link key - as a DRAUPNE Link of 1.0.71 does, mending
 * the RS parity itself, so the phone's stream is not changed for it - takes the WELCOME's stream port, and from then on
 * asks for its KEEP four times a second (the first ones for the snapshot) and counts what is passed on. It decodes
 * nothing and plays nothing: what the pilot's app does for a spectator - a copy of each packet into a queue, a thread
 * of its own to send it - is all there is to it. The pilot's app lets it in only with "Allow spectators" on and its
 * link up.
 */
public final class StandInSpectator implements AutoCloseable {
    /** HELLO's flag: this spectator mends frames with the phone's RS parity (Spectate.HELLO_MENDS_RS). */
    static final int MENDS_RS = 1;
    /** Spectate.FLAG_NEED_SNAPSHOT: no stream config yet - send the snapshot. */
    static final int NEED_SNAPSHOT = 1;
    /** How often the KEEP goes, as a spectator sends it (the pilot's app drops one silent for 3 s). */
    static final long KEEP_MS = 250;

    private final DatagramSocket socket;
    private final InetAddress pilot;
    private final int pilotPort;
    private final String key;
    private final Thread receiver, keeper;
    private volatile boolean running = true;
    private volatile int streamPort = -1;
    private volatile int refused = -1;
    private volatile boolean snapshot, bye;
    private final AtomicLong streamPackets = new AtomicLong(), streamBytes = new AtomicLong();
    private volatile long welcomedAt, lastPacketAt;

    /** @param pilotPort the pilot app's link port (its setting "port"), on that address */
    public StandInSpectator(InetAddress pilot, int pilotPort, String key) throws SocketException {
        this.pilot = pilot;
        this.pilotPort = pilotPort;
        this.key = key == null ? "" : key;
        socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        socket.setSoTimeout(200);
        receiver = new Thread(this::receive, "standInSpectator");
        receiver.setDaemon(true);
        receiver.start();
        keeper = new Thread(this::keep, "standInSpectatorKeep");
        keeper.setDaemon(true);
        keeper.start();
    }

    /** HELLO [0x63][key: u16 length, bytes][flags]: as Spectate.hello builds it. */
    static byte[] hello(String key, int flags) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] d = new byte[3 + k.length + (flags == 0 ? 0 : 1)];
        d[0] = PacketIds.SPECTATE_HELLO;
        d[1] = (byte) (k.length >> 8);
        d[2] = (byte) k.length;
        System.arraycopy(k, 0, d, 3, k.length);
        if (flags != 0) d[3 + k.length] = (byte) flags;
        return d;
    }

    /**
     * HELLO, again every half second, until the pilot's app says WELCOME (true) or REFUSED, or the time is up (false).
     */
    public boolean join(long ms) throws IOException, InterruptedException {
        byte[] h = hello(key, MENDS_RS);
        long end = System.currentTimeMillis() + ms;
        while (streamPort < 0 && refused < 0 && System.currentTimeMillis() < end) {
            socket.send(new DatagramPacket(h, h.length, pilot, pilotPort));
            for (int i = 0; i < 10 && streamPort < 0 && refused < 0; i++) Thread.sleep(50);
        }
        return streamPort >= 0;
    }

    private void keep() {
        while (running) {
            try {
                Thread.sleep(KEEP_MS);
            } catch (InterruptedException e) {
                return;
            }
            int port = streamPort;
            if (port < 0 || bye) continue;
            byte[] k = {PacketIds.SPECTATE_KEEP, (byte) (snapshot ? 0 : NEED_SNAPSHOT), 0}; // no frames asked for again
            try {
                socket.send(new DatagramPacket(k, k.length, pilot, port));
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void receive() {
        byte[] buf = new byte[65536];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                p.setLength(buf.length);
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                return; // closed
            }
            int len = p.getLength();
            if (len < 1) continue;
            byte code = buf[0];
            if (code == PacketIds.SPECTATE_WELCOME && len >= 3) {
                if (streamPort < 0) welcomedAt = System.currentTimeMillis();
                streamPort = ((buf[1] & 0xff) << 8) | (buf[2] & 0xff);
            } else if (code == PacketIds.SPECTATE_REFUSED) {
                refused = len >= 2 ? buf[1] & 0xff : 0;
            } else if (code == PacketIds.SPECTATE_STREAM) {
                streamPackets.incrementAndGet();
                streamBytes.addAndGet(len);
                lastPacketAt = System.currentTimeMillis();
            } else if (code == PacketIds.SPECTATE_SNAPSHOT) {
                snapshot = true;
            } else if (code == PacketIds.SPECTATE_BYE) {
                bye = true;
            }
        }
    }

    public long streamPackets() {
        return streamPackets.get();
    }

    /** Why the pilot's app said no: 1 not flying, 2 full, 3 spectators not allowed; -1 it did not. */
    public int refusedFor() {
        return refused;
    }

    public boolean snapshotCame() {
        return snapshot;
    }

    /** What it got, in words: the packets passed on, their rate since the WELCOME, whether the snapshot came. */
    public String words() {
        if (refused >= 0) {
            return "REFUSED by the pilot's app (" + switch (refused) {
                case 1 -> "its link is not up";
                case 2 -> "two spectators already";
                case 3 -> "spectators not allowed";
                default -> "reason " + refused;
            } + ")";
        }
        if (streamPort < 0) return "not let in: no WELCOME from the pilot's app";
        long n = streamPackets.get(), bytes = streamBytes.get();
        double s = Math.max(1, (lastPacketAt > 0 ? lastPacketAt : System.currentTimeMillis()) - welcomedAt) / 1000.0;
        return String.format(Locale.ROOT, "%d packets passed on, %.2f Mbit/s, %s", n, bytes * 8 / 1e6 / s, snapshot ? "the snapshot came" : "NO SNAPSHOT");
    }

    /** BYE to the stream port, as a spectator leaving; everything closed. */
    @Override
    public void close() {
        int port = streamPort;
        if (port >= 0 && !socket.isClosed()) {
            try {
                socket.send(new DatagramPacket(new byte[]{PacketIds.SPECTATE_BYE}, 1, pilot, port));
            } catch (IOException ignored) {
            }
        }
        running = false;
        keeper.interrupt();
        socket.close();
        try {
            receiver.join(1000);
            keeper.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
