package de.droiddrone.pc;

import de.droiddrone.common.UdpCommon;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static de.droiddrone.common.Log.log;

/**
 * The pilot PC's side of the mirror ({@link Mirror}): lives as long as the window, flying or not. Latency first, as
 * {@link SpectatorHost}: the threads that hand it something (an observed phone's receive thread) only copy into a
 * queue, and only while someone watches; sending runs on a thread of its own below normal priority, from a socket of
 * its own.
 * <p>
 * S-LINK-2: a socket per side - this PC's Tailscale address for spectators on Tailscale, loopback for this PC's own -
 * each spectator served from the one on its side.
 */
public final class MirrorHost implements AutoCloseable {
    public static final int MAX_VIEWERS = 4;
    /** A spectator not heard from for this long has gone. */
    static final long TIMEOUT_MS = 3000;
    /** The state goes out this often, and at once (at most every CHANGE_MS) when a section changed. */
    static final long STATE_MS = 1000, CHANGE_MS = 100;
    /** A tile's key frame asked of its phone at most this often. */
    static final long KEY_ASK_MS = 1000;
    public static final int MAX_TILES = 8;

    private static final class Viewer {
        final InetAddress ip;
        final int port;
        /** The socket on its side (S-LINK-2). */
        final DatagramSocket via;
        volatile long lastHeard = MonoClock.ms();

        Viewer(InetAddress ip, int port, DatagramSocket via) {
            this.ip = ip;
            this.port = port;
            this.via = via;
        }
    }

    private final String key;
    private final BooleanSupplier allowed;
    private final Consumer<String> events;
    /** S-LINK-2: one socket per side (loopback, this PC's Tailscale address); a fixed address: that one alone. */
    private final PeerSocket[] sockets;
    private final ArrayBlockingQueue<byte[]> outgoing = new ArrayBlockingQueue<>(2048);
    private volatile Viewer[] viewers = new Viewer[0];
    private volatile boolean running = true;

    private final Map<String, byte[]> sections = new LinkedHashMap<>();
    private volatile boolean dirty = true;
    private int seq;
    /** Per tile: the stream's config packets as the phone sent them (video, sound, FC), for a spectator joining. */
    private final byte[][][] configs = new byte[MAX_TILES][3][];
    private final long[] keyAskedAt = new long[MAX_TILES];
    private volatile IntConsumer askKey = i -> {
    };

    /**
     * @param allowed SETTINGS > Allow spectators, read at each HELLO
     * @param events  joined, left: the window's log
     */
    public MirrorHost(String key, BooleanSupplier allowed, Consumer<String> events) throws IOException {
        this(key, allowed, events, null);
    }

    /** @param bind the socket's address (the virtual fleet's pilot PC: its loopback one), null: one per side */
    public MirrorHost(String key, BooleanSupplier allowed, Consumer<String> events, InetAddress bind) throws IOException {
        this(key, allowed, events, bind, OwnTailscale.pc());
    }

    MirrorHost(String key, BooleanSupplier allowed, Consumer<String> events, InetAddress bind, OwnTailscale own) throws IOException {
        this.key = key;
        this.allowed = allowed;
        this.events = events;
        PeerSocket.Setup setup = s -> s.setSendBufferSize(1 << 18);
        if (bind != null) {
            sockets = new PeerSocket[]{PeerSocket.at(bind, 0, setup)};
        } else {
            PeerSocket loopback = PeerSocket.onSide(LinkPlace.Side.LOOPBACK, 0, own, setup);
            try {
                sockets = new PeerSocket[]{loopback, PeerSocket.onSide(LinkPlace.Side.TAILSCALE, 0, own, setup)};
            } catch (IOException | RuntimeException e) {
                loopback.close();
                throw e;
            }
        }
        start(this::sendLoop, "mirrorSend");
        for (PeerSocket s : sockets) start(() -> receiveLoop(s), "mirrorReceive");
    }

    private void start(Runnable run, String name) {
        Thread t = new Thread(run, name);
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setUncaughtExceptionHandler((th, e) -> log(name + " died: " + e));
        t.start();
    }

    /** S-LINK-2: the socket for a spectator at this address - on its side; null when none is (it gets no answer). */
    private PeerSocket socketFor(InetAddress ip) {
        for (PeerSocket s : sockets) {
            LinkPlace at = s.bound();
            if (at != null && at.side == LinkPlace.sideOf(ip)) return s;
        }
        return null;
    }

    public int count() {
        return viewers.length;
    }

    /** A section of the state (null removes it); sent at once if it changed. Any thread. */
    public void setSection(String name, byte[] value) {
        synchronized (sections) {
            byte[] old = sections.get(name);
            if (value == null) {
                if (old == null) return;
                sections.remove(name);
            } else {
                if (old != null && java.util.Arrays.equals(old, value)) return;
                sections.put(name, value);
            }
        }
        dirty = true;
    }

    /** The observation's tiles changed: what each one remembered goes; askKey(i) asks tile i's phone for a key frame. */
    public void setTiles(IntConsumer askKey) {
        synchronized (configs) {
            for (byte[][] c : configs) java.util.Arrays.fill(c, null);
        }
        this.askKey = askKey == null ? i -> {
        } : askKey;
        if (askKey != null && viewers.length > 0) {
            for (int i = 0; i < MAX_TILES; i++) askKey(i);
        }
    }

    /** A packet tile i's phone sent (its receive thread): never blocks, nothing without spectators. */
    public void tile(int i, byte[] d, int len) {
        if (i < 0 || i >= MAX_TILES || len < 1) return;
        byte t = d[0];
        int slot = t == UdpCommon.VideoInitialFrame ? 0 : t == UdpCommon.AudioInitialFrame ? 1 : t == UdpCommon.FcInfo ? 2 : -1;
        if (slot >= 0) {
            byte[] copy = java.util.Arrays.copyOf(d, len);
            synchronized (configs) {
                configs[i][slot] = copy;
            }
        }
        if (viewers.length == 0) return;
        outgoing.offer(Mirror.tile(i, d, len));
    }

    /** A MIRROR_HELLO on the link port (the link's or the name service's socket): the answer goes back the same way. */
    public void onHello(DatagramPacket packet, DatagramSocket via) {
        String k = Spectate.readKey(packet.getData(), packet.getLength());
        if (k == null || !k.equals(key)) return;
        InetAddress ip = packet.getAddress();
        int port = packet.getPort();
        PeerSocket socket = socketFor(ip);
        if (socket == null) return;
        byte[] answer;
        if (!allowed.getAsBoolean()) {
            answer = Mirror.welcome(0, Mirror.REFUSED_OFF);
        } else {
            Viewer v = find(ip, port);
            if (v == null) {
                synchronized (this) {
                    v = find(ip, port);
                    if (v == null && viewers.length < MAX_VIEWERS) {
                        v = new Viewer(ip, port, socket);
                        List<Viewer> list = new ArrayList<>(List.of(viewers));
                        list.add(v);
                        viewers = list.toArray(new Viewer[0]);
                        events.accept("Spectator mirroring this PC from " + ip.getHostAddress() + ".");
                        dirty = true;
                        for (int i = 0; i < MAX_TILES; i++) askKey(i);
                    }
                }
            }
            if (v == null) return; // full: it keeps asking
            v.lastHeard = MonoClock.ms();
            answer = Mirror.welcome(socket.getLocalPort(), 0);
        }
        try {
            if (via != null) via.send(new DatagramPacket(answer, answer.length, ip, port));
            socket.send(new DatagramPacket(answer, answer.length, ip, port)); // opens the spectator's firewall to us
        } catch (IOException e) {
            // it asks again
        }
    }

    private void askKey(int i) {
        long now = MonoClock.ms();
        synchronized (keyAskedAt) {
            if (now - keyAskedAt[i] < KEY_ASK_MS) return;
            keyAskedAt[i] = now;
        }
        try {
            askKey.accept(i);
        } catch (RuntimeException e) {
            log("Mirror key frame ask: " + e);
        }
    }

    // ------------------------------------------------------------------ own threads

    private void sendLoop() {
        long nextState = 0, lastState = 0, nextCheck = 0;
        while (running) {
            try {
                byte[] p = outgoing.poll(20, TimeUnit.MILLISECONDS);
                Viewer[] vs = viewers;
                if (p != null) for (Viewer v : vs) send(p, v);
                long now = MonoClock.ms();
                if (vs.length > 0 && (now >= nextState || dirty && now - lastState >= CHANGE_MS)) {
                    dirty = false;
                    lastState = now;
                    nextState = now + STATE_MS;
                    sendState(vs, null);
                }
                if (now >= nextCheck) {
                    nextCheck = now + 200;
                    for (Viewer v : vs) {
                        if (now - v.lastHeard > TIMEOUT_MS) remove(v, "Spectator " + v.ip.getHostAddress() + " stopped mirroring.");
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log("Mirror send error: " + e);
            }
        }
    }

    private void sendState(Viewer[] to, Viewer only) {
        Map<String, byte[]> copy;
        synchronized (sections) {
            copy = new LinkedHashMap<>(sections);
        }
        byte[] z = Mirror.encodeState(copy);
        List<byte[]> parts = Mirror.stateParts(seq = (seq + 1) & 0xffff, z);
        if (parts == null) { // too big: the text sections (pins, drawings) left out
            copy.keySet().removeIf(k -> k.startsWith("text."));
            parts = Mirror.stateParts(seq, Mirror.encodeState(copy));
            if (parts == null) return;
        }
        for (byte[] part : parts) {
            if (only != null) send(part, only);
            else for (Viewer v : to) send(part, v);
        }
    }

    private void receiveLoop(DatagramSocket socket) {
        byte[] buf = new byte[64];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                packet.setLength(buf.length);
                socket.receive(packet);
                Viewer v = find(packet.getAddress(), packet.getPort());
                int len = packet.getLength();
                if (v == null || len < 1) continue;
                v.lastHeard = MonoClock.ms();
                if (buf[0] == Mirror.BYE) {
                    remove(v, "Spectator " + v.ip.getHostAddress() + " left.");
                } else if (buf[0] == Mirror.KEEP && len >= 3) {
                    if ((buf[1] & Mirror.FLAG_NEED_STATE) != 0) dirty = true;
                    int asks = buf[2] & 0xff;
                    for (int i = 0; i < MAX_TILES && asks != 0; i++) {
                        if ((asks & (1 << i)) == 0) continue;
                        byte[][] c;
                        synchronized (configs) {
                            c = configs[i].clone();
                        }
                        for (byte[] cfg : c) if (cfg != null) send(Mirror.tile(i, cfg, cfg.length), v);
                        askKey(i);
                    }
                }
            } catch (IOException e) {
                if (running) log("Mirror receive error: " + e);
            } catch (RuntimeException e) {
                log("Mirror receive error: " + e);
            }
        }
    }

    private void send(byte[] p, Viewer v) {
        try {
            v.via.send(new DatagramPacket(p, p.length, v.ip, v.port));
        } catch (IOException e) {
            // gone off the network: it times out
        }
    }

    private synchronized void remove(Viewer v, String why) {
        List<Viewer> list = new ArrayList<>(List.of(viewers));
        if (!list.remove(v)) return;
        viewers = list.toArray(new Viewer[0]);
        events.accept(why);
    }

    private Viewer find(InetAddress ip, int port) {
        for (Viewer v : viewers) if (v.ip.equals(ip) && v.port == port) return v;
        return null;
    }

    @Override
    public void close() {
        running = false;
        for (Viewer v : viewers) send(new byte[]{Mirror.BYE}, v);
        viewers = new Viewer[0];
        for (PeerSocket s : sockets) s.close();
    }
}
