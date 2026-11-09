package de.droiddrone.pc;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static de.droiddrone.common.Log.log;

/**
 * A spectator PC's side of the mirror ({@link Mirror}): asks the pilot PC at its link port, then keeps its mirror
 * stream alive and hands on what comes - the state's sections, the observation tiles' packets. Played at once (no
 * buffer): the state is a picture of the window, the tiles' video has its own pipeline per tile.
 */
public final class MirrorClient implements AutoCloseable {
    static final long HELLO_MS = 500, KEEP_MS = 250;
    /** Nothing from the pilot PC for this long: it has gone (asked again). */
    static final long GONE_MS = 3000;

    /** What comes, on this class's threads. */
    public interface Listener {
        /** The pilot PC's whole state (its sections by name). */
        void state(Map<String, byte[]> sections);

        /** A packet tile i's phone sent the pilot PC. */
        void tile(int tile, byte[] d, int len);

        /** Joined (true), or the pilot PC refused / went quiet (false). */
        default void joined(boolean on, String why) {
        }
    }

    private final DatagramSocket socket;
    private final InetAddress pilotIp;
    private final int pilotPort;
    private final String key;
    private final Listener listener;
    private volatile boolean running = true;
    private volatile int mirrorPort = -1;
    private volatile long lastHeard;
    private volatile boolean haveState;
    private final AtomicInteger keyAsks = new AtomicInteger();
    private final Thread ticker, receiver;
    // the state being put together (receive thread)
    private int partsSeq = -1;
    private byte[][] parts;
    private int partsHave;

    public MirrorClient(InetAddress pilotIp, int pilotPort, String key, Listener listener) throws IOException {
        this.pilotIp = pilotIp;
        this.pilotPort = pilotPort;
        this.key = key;
        this.listener = listener;
        socket = new DatagramSocket(0);
        socket.setReceiveBufferSize(1 << 20);
        receiver = new Thread(this::receiveLoop, "mirrorClient");
        receiver.setDaemon(true);
        receiver.setUncaughtExceptionHandler((t, e) -> log("Mirror client died: " + e));
        receiver.start();
        ticker = new Thread(this::tickLoop, "mirrorClientTick");
        ticker.setDaemon(true);
        ticker.start();
    }

    /** Joined and hearing from the pilot PC. */
    public boolean isJoined() {
        return mirrorPort > 0 && MonoClock.ms() - lastHeard <= GONE_MS;
    }

    /** Tile i's stream needs its config and a key frame (its decoder starts, or lost a packet). Asked at once. */
    public void askKey(int tile) {
        if (tile < 0 || tile >= MirrorHost.MAX_TILES) return;
        keyAsks.getAndUpdate(a -> a | (1 << tile));
        ticker.interrupt();
    }

    private void tickLoop() {
        long nextHello = 0, nextKeep = 0;
        while (running) {
            try {
                long now = MonoClock.ms();
                if (mirrorPort > 0 && now - lastHeard > GONE_MS) {
                    mirrorPort = -1;
                    haveState = false;
                    listener.joined(false, "the pilot PC stopped answering");
                }
                if (mirrorPort <= 0) {
                    if (now >= nextHello) {
                        send(Mirror.hello(key), pilotPort);
                        nextHello = now + HELLO_MS;
                    }
                } else if (now >= nextKeep || keyAsks.get() != 0) {
                    send(Mirror.keep(haveState ? 0 : Mirror.FLAG_NEED_STATE, keyAsks.getAndSet(0)), mirrorPort);
                    nextKeep = now + KEEP_MS;
                }
                Thread.sleep(50);
            } catch (InterruptedException e) {
                if (!running) return;
            } catch (RuntimeException e) {
                log("Mirror client: " + e);
            }
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[de.droiddrone.common.UdpCommon.packetLength + 64];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                p.setLength(buf.length);
                socket.receive(p);
                if (!p.getAddress().equals(pilotIp)) continue;
                int len = p.getLength();
                if (len < 1) continue;
                onPacket(buf, len);
            } catch (IOException e) {
                if (running) log("Mirror client receive: " + e.getMessage());
            } catch (RuntimeException e) {
                log("Mirror client: " + e);
            }
        }
    }

    void onPacket(byte[] d, int len) {
        long now = MonoClock.ms();
        switch (d[0]) {
            case Mirror.WELCOME -> {
                if (len < 3) return;
                int port = ((d[1] & 0xff) << 8) | (d[2] & 0xff);
                if (port == 0) {
                    mirrorPort = -1;
                    listener.joined(false, "spectators not allowed there");
                    return;
                }
                lastHeard = now;
                if (mirrorPort != port) {
                    mirrorPort = port;
                    haveState = false;
                    listener.joined(true, null);
                    send(Mirror.keep(Mirror.FLAG_NEED_STATE, 0xff), port); // opens this PC's firewall; every tile's config
                }
            }
            case Mirror.STATE -> {
                if (len < 6) return;
                lastHeard = now;
                int seq = ((d[1] & 0xff) << 8) | (d[2] & 0xff);
                int part = d[3] & 0xff, count = d[4] & 0xff;
                if (count == 0 || part >= count) return;
                if (seq != partsSeq || parts == null || parts.length != count) {
                    partsSeq = seq;
                    parts = new byte[count][];
                    partsHave = 0;
                }
                if (parts[part] == null) {
                    parts[part] = java.util.Arrays.copyOfRange(d, 5, len);
                    partsHave++;
                }
                if (partsHave == count) {
                    int total = 0;
                    for (byte[] x : parts) total += x.length;
                    byte[] z = new byte[total];
                    int at = 0;
                    for (byte[] x : parts) {
                        System.arraycopy(x, 0, z, at, x.length);
                        at += x.length;
                    }
                    parts = null;
                    Map<String, byte[]> s = Mirror.decodeState(z);
                    if (s != null) {
                        haveState = true;
                        listener.state(s);
                    }
                }
            }
            case Mirror.TILE -> {
                if (len < 3) return;
                lastHeard = now;
                listener.tile(d[1] & 0xff, java.util.Arrays.copyOfRange(d, 2, len), len - 2);
            }
            case Mirror.BYE -> {
                if (mirrorPort > 0) {
                    mirrorPort = -1;
                    haveState = false;
                    listener.joined(false, "the pilot PC closed");
                }
            }
            default -> {
            }
        }
    }

    private void send(byte[] d, int port) {
        try {
            socket.send(new DatagramPacket(d, d.length, pilotIp, port));
        } catch (IOException e) {
            // the network is away: again next round
        }
    }

    @Override
    public void close() {
        if (!running) return;
        running = false;
        int port = mirrorPort;
        if (port > 0) send(new byte[]{Mirror.BYE}, port);
        socket.close();
        ticker.interrupt();
    }
}
