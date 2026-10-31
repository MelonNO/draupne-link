package de.droiddrone.pc;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static de.droiddrone.common.Log.log;

/**
 * The pilot PC's side of spectator mode (see {@link Spectate}): lets up to {@link #MAX_SPECTATORS} other PCs watch.
 *
 * Latency first - the pilot's sticks and picture must not notice: the phone sends nothing extra; the link's receive
 * thread only copies each packet into a queue ({@link #forward}, nothing at all with no spectator); sending, resending
 * and the spectators' packets run on two threads of their own, below the link's priority, from a socket of their own
 * with default (not low-delay) traffic class. And when this PC's link to the phone slows down for a second (its ping
 * well over the usual), the spectators' stream pauses for a few seconds, in case it is what clogs this PC's upload.
 */
final class SpectatorHost implements AutoCloseable {
    static final int MAX_SPECTATORS = 2;
    /** A spectator not heard from for this long has gone. */
    static final long TIMEOUT_MS = 3000;
    /** Packets kept for resending: about 5 s of a 5 Mbit/s stream. */
    static final int RING = 4096;
    /** Ping this much over the lowest of the last 10 s, for CONGESTION_HOLD_MS: pause the spectators. */
    static final int CONGESTION_MS = 80;
    static final long CONGESTION_HOLD_MS = 1000;
    static final long PAUSE_MS = 3000;
    /** At most this many resends a second per spectator. */
    static final int RESEND_BUDGET = 150;

    private static final class Viewer {
        final InetAddress ip;
        final int port;
        volatile long lastHeard = System.currentTimeMillis();
        long lastSnapshot;
        long budgetWindow;
        int resends;

        Viewer(InetAddress ip, int port) {
            this.ip = ip;
            this.port = port;
        }

        boolean is(InetAddress ip, int port) {
            return this.ip.equals(ip) && this.port == port;
        }
    }

    private final String key;
    private volatile boolean allowed;
    private final DatagramSocket linkSocket;
    private final DatagramSocket socket;
    private final BooleanSupplier flying;
    private final IntSupplier ping;
    private final Consumer<String> events;
    private final long start = System.currentTimeMillis();
    private final AtomicInteger seq = new AtomicInteger();
    private final AtomicReferenceArray<byte[]> ring = new AtomicReferenceArray<>(RING);
    private final ArrayBlockingQueue<byte[]> outgoing = new ArrayBlockingQueue<>(1024);
    private volatile Viewer[] viewers = new Viewer[0];
    private volatile boolean paused;
    private volatile boolean running = true;
    private volatile byte[] videoConfig, fcInfo, boxNames, boxIds;

    /**
     * @param linkSocket the link's socket: HELLO comes in there (the port the firewall opens), WELCOME goes back
     * @param flying     the link to the phone is up and configured: spectators are let in only then
     */
    SpectatorHost(String key, boolean allowed, DatagramSocket linkSocket, BooleanSupplier flying, IntSupplier ping,
                  Consumer<String> events) throws IOException {
        this.key = key;
        this.allowed = allowed;
        this.linkSocket = linkSocket;
        this.flying = flying;
        this.ping = ping;
        this.events = events;
        socket = new DatagramSocket(0);
        socket.setSendBufferSize(1 << 18);
        start(this::sendLoop, "spectatorSend");
        start(this::receiveLoop, "spectatorReceive");
    }

    private void start(Runnable run, String name) {
        Thread t = new Thread(run, name);
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    /** SETTINGS > Allow spectators, while flying: off sends everyone watching away. */
    void setAllowed(boolean on) {
        allowed = on;
        if (on) return;
        Viewer[] gone;
        synchronized (this) {
            gone = viewers;
            viewers = new Viewer[0];
        }
        for (Viewer v : gone) send(new byte[]{Spectate.BYE}, v);
        if (gone.length > 0) events.accept("Spectators sent away (Allow spectators is off).");
    }

    int streamPort() {
        return socket.getLocalPort();
    }

    int count() {
        return viewers.length;
    }

    boolean isPaused() {
        return paused;
    }

    // ------------------------------------------------------------------ link threads (must not wait)

    /** A packet from the phone the spectators get too, as the phone sent it. Never blocks; drops when behind. */
    void forward(byte[] packet, int len) {
        if (viewers.length == 0 || paused) return;
        int s = seq.getAndIncrement();
        byte[] p = Spectate.stream(s, (int) (System.currentTimeMillis() - start), packet, len);
        ring.set(s & (RING - 1), p);
        outgoing.offer(p);
    }

    /** Kept for spectators joining later: what the phone sends once per stream or connection. */
    void rememberVideoConfig(byte[] packet) {
        videoConfig = packet;
    }

    void rememberFcInfo(byte[] packet) {
        fcInfo = packet;
    }

    void rememberBoxNames(byte[] packet) {
        boxNames = packet;
    }

    void rememberBoxIds(byte[] packet) {
        boxIds = packet;
    }

    /** A HELLO on the link's socket (receive thread: a small answer, a few times a second at most). */
    void onHello(DatagramPacket packet) {
        String k = Spectate.readKey(packet.getData(), packet.getLength());
        if (k == null || !k.equals(key)) return;
        InetAddress ip = packet.getAddress();
        int port = packet.getPort();
        byte refuse = 0;
        Viewer known = find(ip, port);
        if (!allowed) {
            refuse = Spectate.REFUSED_OFF;
        } else if (!flying.getAsBoolean()) {
            refuse = Spectate.REFUSED_NOT_FLYING;
        } else if (known == null) {
            synchronized (this) {
                if (viewers.length >= MAX_SPECTATORS) {
                    refuse = Spectate.REFUSED_FULL;
                } else {
                    known = new Viewer(ip, port);
                    List<Viewer> list = new ArrayList<>(List.of(viewers));
                    list.add(known);
                    viewers = list.toArray(new Viewer[0]);
                    events.accept("Spectator watching from " + ip.getHostAddress() + " (" + viewers.length + " of "
                            + MAX_SPECTATORS + "). It gets the video through this PC's upload.");
                }
            }
        }
        try {
            if (refuse != 0) {
                linkSocket.send(new DatagramPacket(new byte[]{Spectate.REFUSED, refuse}, 2, ip, port));
                return;
            }
            known.lastHeard = System.currentTimeMillis();
            byte[] welcome = Spectate.withPort(Spectate.WELCOME, streamPort());
            linkSocket.send(new DatagramPacket(welcome, welcome.length, ip, port));
            // the same from the stream port: lets the spectator's packets back in through this PC's firewall
            socket.send(new DatagramPacket(welcome, welcome.length, ip, port));
        } catch (IOException e) {
            log("Spectator answer error: " + e);
        }
    }

    // ------------------------------------------------------------------ own threads

    private void sendLoop() {
        long nextCheck = 0;
        long[] lowest = new long[10];
        int slot = 0;
        long slotStart = System.currentTimeMillis();
        java.util.Arrays.fill(lowest, Long.MAX_VALUE);
        long highSince = 0;
        long pausedUntil = 0;
        while (running) {
            try {
                byte[] p = outgoing.poll(100, TimeUnit.MILLISECONDS);
                if (p != null) {
                    for (Viewer v : viewers) send(p, v);
                }
                long now = System.currentTimeMillis();
                if (now < nextCheck) continue;
                nextCheck = now + 100;
                dropSilentViewers(now);
                // ping watch: the lowest ping per second over the last 10 s is "the usual"
                int pingMs = ping.getAsInt();
                if (now - slotStart >= 1000) {
                    slot = (slot + 1) % lowest.length;
                    lowest[slot] = Long.MAX_VALUE;
                    slotStart = now;
                }
                if (pingMs >= 0) lowest[slot] = Math.min(lowest[slot], pingMs);
                long usual = Long.MAX_VALUE;
                for (long l : lowest) usual = Math.min(usual, l);
                boolean high = pingMs >= 0 && usual != Long.MAX_VALUE && pingMs > usual + CONGESTION_MS;
                highSince = high ? (highSince == 0 ? now : highSince) : 0;
                if (!paused && viewers.length > 0 && highSince != 0 && now - highSince >= CONGESTION_HOLD_MS) {
                    paused = true;
                    pausedUntil = now + PAUSE_MS;
                    outgoing.clear();
                    events.accept("Spectator video paused for " + PAUSE_MS / 1000 + " s: the link to the drone slowed down"
                            + " (ping " + pingMs + " ms, usually " + usual + ").");
                } else if (paused && now >= pausedUntil) {
                    paused = false;
                    highSince = 0;
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log("Spectator send error: " + e);
            }
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[3 + 4 * Spectate.MAX_NACKS + 16];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                packet.setLength(buf.length);
                socket.receive(packet);
                Viewer v = find(packet.getAddress(), packet.getPort());
                int len = packet.getLength();
                if (v == null || len < 1) continue;
                v.lastHeard = System.currentTimeMillis();
                if (buf[0] == Spectate.BYE) {
                    remove(v, "Spectator " + v.ip.getHostAddress() + " left.");
                } else if (buf[0] == Spectate.KEEP && len >= 3) {
                    if ((buf[1] & Spectate.FLAG_NEED_SNAPSHOT) != 0) sendSnapshot(v);
                    int n = Math.min(buf[2] & 0xff, (len - 3) / 4);
                    for (int i = 0; i < n; i++) resend(v, Spectate.readInt(buf, 3 + 4 * i));
                }
            } catch (IOException e) {
                if (running) log("Spectator receive error: " + e);
            }
        }
    }

    private void resend(Viewer v, int s) {
        long now = System.currentTimeMillis();
        if (now - v.budgetWindow >= 1000) {
            v.budgetWindow = now;
            v.resends = 0;
        }
        if (v.resends >= RESEND_BUDGET || paused) return;
        byte[] p = ring.get(s & (RING - 1));
        if (p == null || Spectate.readInt(p, 1) != s) return; // too old: overwritten
        v.resends++;
        send(p, v);
    }

    private void sendSnapshot(Viewer v) {
        long now = System.currentTimeMillis();
        if (now - v.lastSnapshot < 300) return;
        v.lastSnapshot = now;
        for (byte[] p : new byte[][]{videoConfig, fcInfo, boxNames, boxIds}) {
            if (p != null) send(Spectate.snapshot(p), v);
        }
    }

    private void send(byte[] p, Viewer v) {
        try {
            socket.send(new DatagramPacket(p, p.length, v.ip, v.port));
        } catch (IOException e) {
            // a spectator gone off the network: it times out
        }
    }

    private void dropSilentViewers(long now) {
        for (Viewer v : viewers) {
            if (now - v.lastHeard > TIMEOUT_MS) remove(v, "Spectator " + v.ip.getHostAddress() + " stopped answering.");
        }
    }

    private synchronized void remove(Viewer v, String why) {
        List<Viewer> list = new ArrayList<>(List.of(viewers));
        if (!list.remove(v)) return;
        viewers = list.toArray(new Viewer[0]);
        events.accept(why);
    }

    private Viewer find(InetAddress ip, int port) {
        for (Viewer v : viewers) {
            if (v.is(ip, port)) return v;
        }
        return null;
    }

    @Override
    public void close() {
        running = false;
        for (Viewer v : viewers) send(new byte[]{Spectate.BYE}, v);
        viewers = new Viewer[0];
        socket.close();
    }
}
