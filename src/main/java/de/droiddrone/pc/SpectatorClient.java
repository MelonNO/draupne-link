package de.droiddrone.pc;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static de.droiddrone.common.Log.log;

/**
 * A spectator PC's side of spectator mode (see {@link Spectate}): finds the PC that flies, and plays what it passes
 * on a fixed time behind it - for a good picture rather than a quick one. That time is spent on:
 * <ul>
 * <li>asking the pilot PC again for anything lost on the way (it keeps the last few seconds), so no broken frames
 * are added on the way from the pilot to here;</li>
 * <li>evening out the network: everything plays at the pilot's arrival time plus a fixed delay, not when it lands
 * here;</li>
 * <li>spacing out frames that came bunched up over 4G, so the picture moves steadily.</li>
 * </ul>
 * This PC never asks the phone to connect while it watches, so it can't take the drone over when the pilot's link
 * drops for a few seconds. To fly, disconnect and connect again once nobody flies.
 */
final class SpectatorClient implements AutoCloseable {
    /** The spectator's picture runs this far behind the pilot's by default (spectatorDelayMs in the settings). */
    static final int DEFAULT_DELAY_MS = 600;
    /** Nothing from the pilot PC for this long: it has gone. */
    static final long PILOT_GONE_MS = 3000;
    static final long HELLO_MS = 500;
    static final long KEEP_MS = 250;
    static final long QUERY_MS = 1000;
    /** A gap is asked for after this long (packets can come a little out of order), then again every NACK_RETRY_MS. */
    static final long NACK_FIRST_MS = 15;
    static final long NACK_RETRY_MS = 150;
    /** Frames that came bunched are spread out by up to this much. */
    static final long SMOOTH_MAX_MS = 250;
    /** ...to at least this share of the usual frame interval apart (frames from the 4G link often come in pairs). */
    static final double SMOOTH_SPACING = 0.95;

    /** What the spectator is doing, for the main window. */
    enum Phase { JOINING, WATCHING, REFUSED_NOT_FLYING, REFUSED_FULL, REFUSED_OFF, NO_PILOT }

    private record Item(byte[] data, long arrived, int pilotMs) {}

    private final DatagramSocket socket;
    private final InetAddress phoneIp;
    private final int phonePort;
    private final String key;
    private final int delayMs;
    private final Consumer<byte[]> deliver;
    private final Thread thread;
    private volatile boolean running = true;

    private final Object lock = new Object();
    private volatile Spectate.Address pilot;
    private volatile int streamPort = -1;
    private volatile Phase phase = Phase.JOINING;
    private volatile long lastFromPilot;
    private volatile long lastStream;
    private volatile boolean haveSnapshot;

    // under lock
    private final TreeMap<Integer, Item> pending = new TreeMap<>();
    private final Map<Integer, long[]> gaps = new HashMap<>(); // seq -> {first seen missing, last asked}
    private int nextSeq = -1;
    private int highestSeq = -1;
    private long offsetNow = Long.MAX_VALUE, offsetBefore = Long.MAX_VALUE, offsetWindow;
    private final ConcurrentLinkedQueue<byte[]> snapshots = new ConcurrentLinkedQueue<>();
    private final Map<Byte, byte[]> appliedSnapshots = new HashMap<>();

    // playout thread only
    private long lastFrameRelease;
    private int lastFramePilotMs = -1;
    private double frameIntervalMs;
    private long lost;

    /**
     * @param deliver gets every packet the phone sent, in order and on time, on this class's thread
     */
    SpectatorClient(DatagramSocket socket, InetAddress phoneIp, int phonePort, String key, int delayMs,
                    Spectate.Address pilot, Consumer<byte[]> deliver) {
        this.socket = socket;
        this.phoneIp = phoneIp;
        this.phonePort = phonePort;
        this.key = key;
        this.delayMs = delayMs;
        this.pilot = pilot;
        this.deliver = deliver;
        thread = new Thread(this::run, "spectator");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY);
        thread.start();
    }

    Phase phase() {
        return phase;
    }

    Spectate.Address pilot() {
        return pilot;
    }

    int delayMs() {
        return delayMs;
    }

    long lostPackets() {
        synchronized (lock) {
            return lost;
        }
    }

    // ------------------------------------------------------------------ the link's receive thread

    /** The phone says which PC flies (again): a different one starts afresh. */
    void onPilotAt(Spectate.Address a) {
        Spectate.Address old = pilot;
        if (old != null && old.equals(a)) return;
        pilot = a;
        streamPort = -1;
        phase = Phase.JOINING;
        resetStream();
    }

    void onNoPilot() {
        if (streamPort < 0) phase = Phase.NO_PILOT;
    }

    /** A packet from anywhere but the phone. */
    void onPacket(DatagramPacket packet) {
        Spectate.Address p = pilot;
        if (p == null || !packet.getAddress().equals(p.ip())) return;
        byte[] d = packet.getData();
        int len = packet.getLength();
        if (len < 1) return;
        long now = System.currentTimeMillis();
        switch (d[0]) {
            case Spectate.WELCOME -> {
                int port = Spectate.readPort(d, len);
                if (port <= 0) return;
                lastFromPilot = now;
                if (streamPort != port) {
                    streamPort = port;
                    resetStream();
                    sendKeep(); // straight away: opens this PC's firewall to the stream
                }
                if (phase != Phase.WATCHING) phase = Phase.WATCHING;
            }
            case Spectate.REFUSED -> {
                if (len < 2) return;
                lastFromPilot = now;
                streamPort = -1;
                phase = switch (d[1]) {
                    case Spectate.REFUSED_FULL -> Phase.REFUSED_FULL;
                    case Spectate.REFUSED_OFF -> Phase.REFUSED_OFF;
                    default -> Phase.REFUSED_NOT_FLYING;
                };
            }
            case Spectate.BYE -> pilotGone();
            case Spectate.STREAM -> {
                if (len <= Spectate.STREAM_HEADER || packet.getPort() != streamPort) return;
                lastFromPilot = now;
                lastStream = now;
                int seq = Spectate.readInt(d, 1);
                int pilotMs = Spectate.readInt(d, 5);
                byte[] data = Arrays.copyOfRange(d, Spectate.STREAM_HEADER, len);
                synchronized (lock) {
                    if (nextSeq >= 0 && seq < nextSeq) return; // played already, or given up on
                    if (highestSeq >= 0 && seq - highestSeq > SpectatorHost.RING) { // can't be filled in: start afresh
                        pending.clear();
                        gaps.clear();
                        nextSeq = -1;
                        highestSeq = -1;
                    }
                    pending.putIfAbsent(seq, new Item(data, now, pilotMs));
                    gaps.remove(seq);
                    if (seq > highestSeq) {
                        for (int s = Math.max(highestSeq + 1, nextSeq < 0 ? seq : nextSeq); s < seq; s++) {
                            gaps.putIfAbsent(s, new long[]{now, 0});
                        }
                        highestSeq = seq;
                    }
                    // the quickest way here over the last 10-20 s: what the network adds on top of it is evened out
                    if (now - offsetWindow >= 10_000) {
                        offsetBefore = offsetNow;
                        offsetNow = Long.MAX_VALUE;
                        offsetWindow = now;
                    }
                    offsetNow = Math.min(offsetNow, now - pilotMs);
                }
            }
            case Spectate.SNAPSHOT -> {
                if (len < 2) return;
                lastFromPilot = now;
                haveSnapshot = true;
                snapshots.add(Arrays.copyOfRange(d, 1, len));
            }
            default -> {
            }
        }
    }

    private void pilotGone() {
        streamPort = -1;
        phase = Phase.JOINING;
        resetStream();
    }

    private void resetStream() {
        haveSnapshot = false; // a new pilot or stream: its config and FC info again
        synchronized (lock) {
            pending.clear();
            gaps.clear();
            nextSeq = -1;
            highestSeq = -1;
            offsetNow = offsetBefore = Long.MAX_VALUE;
        }
    }

    // ------------------------------------------------------------------ own thread

    private void run() {
        long nextHello = 0, nextKeep = 0, nextQuery = 0;
        while (running) {
            try {
                long now = System.currentTimeMillis();
                Spectate.Address p = pilot;
                if (streamPort >= 0 && now - lastFromPilot > PILOT_GONE_MS) {
                    log("Spectator: the pilot PC stopped sending");
                    pilotGone();
                }
                if (streamPort < 0) {
                    if (p != null && now >= nextHello) {
                        send(Spectate.keyed(Spectate.HELLO, key), p.ip(), p.port());
                        nextHello = now + (phase == Phase.JOINING ? HELLO_MS : 2000);
                    }
                    // nobody answers, or nobody flies: ask the phone (never a Connect - that could take the drone)
                    if ((p == null || now - lastFromPilot > PILOT_GONE_MS || phase == Phase.NO_PILOT) && now >= nextQuery) {
                        send(Spectate.keyed(Spectate.PILOT_QUERY, key), phoneIp, phonePort);
                        nextQuery = now + QUERY_MS;
                    }
                } else if (now >= nextKeep) {
                    sendKeep();
                    nextKeep = now + KEEP_MS;
                }
                byte[] s;
                while ((s = snapshots.poll()) != null) applySnapshot(s);
                for (byte[] packet : due(now)) deliver.accept(packet);
                if (streamPort >= 0 && askForGaps(now)) nextKeep = now + KEEP_MS;
                Thread.sleep(2);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log("Spectator error: " + e);
            }
        }
    }

    /** A snapshot packet is played once: sent again (until it arrives) it would restart the video each time. */
    private void applySnapshot(byte[] packet) {
        byte name = packet[0];
        if (Arrays.equals(appliedSnapshots.get(name), packet)) return;
        appliedSnapshots.put(name, packet);
        deliver.accept(packet);
    }

    /** What is due now, in order. A gap is given up on once the packet after it is due. */
    private List<byte[]> due(long now) {
        List<byte[]> out = new ArrayList<>();
        synchronized (lock) {
            if (pending.isEmpty()) return out;
            if (nextSeq < 0) nextSeq = pending.firstKey();
            long offset = Math.min(offsetNow, offsetBefore);
            while (true) {
                Item it = pending.get(nextSeq);
                if (it == null) {
                    Map.Entry<Integer, Item> later = pending.ceilingEntry(nextSeq);
                    if (later == null || now < dueAt(later.getValue(), offset, false)) break;
                    lost += later.getKey() - nextSeq;
                    for (int s = nextSeq; s < later.getKey(); s++) gaps.remove(s);
                    nextSeq = later.getKey();
                    continue;
                }
                boolean frameStart = isFrameStart(it.data());
                long at = dueAt(it, offset, frameStart);
                if (now < at) break;
                if (frameStart) {
                    if (lastFramePilotMs >= 0) {
                        int gap = it.pilotMs() - lastFramePilotMs;
                        if (gap > 0 && gap < 200) frameIntervalMs = frameIntervalMs == 0 ? gap : frameIntervalMs * 0.95 + gap * 0.05;
                    }
                    lastFramePilotMs = it.pilotMs();
                    lastFrameRelease = now;
                }
                pending.remove(nextSeq);
                nextSeq++;
                out.add(it.data());
            }
        }
        return out;
    }

    /** The pilot's arrival time + the network's quickest + the delay; a frame no sooner than ~one frame after the last. */
    private long dueAt(Item it, long offset, boolean frameStart) {
        long at = it.pilotMs() + offset + delayMs;
        if (frameStart && frameIntervalMs > 0 && lastFrameRelease > 0) {
            long spaced = lastFrameRelease + (long) (frameIntervalMs * SMOOTH_SPACING);
            if (spaced > at && spaced - at <= SMOOTH_MAX_MS) at = spaced;
        }
        return at;
    }

    /** The first fragment of a video frame: [1|2][frameNum i16][offset i32 = 0]... */
    private static boolean isFrameStart(byte[] d) {
        return d.length >= 7 && (d[0] == de.droiddrone.common.UdpCommon.VideoFrame || d[0] == de.droiddrone.common.UdpCommon.KeyFrame)
                && d[3] == 0 && d[4] == 0 && d[5] == 0 && d[6] == 0;
    }

    /** Asks for packets missing for a moment. @return true if a KEEP went out */
    private boolean askForGaps(long now) {
        int[] missing = new int[Spectate.MAX_NACKS];
        int n = 0;
        synchronized (lock) {
            for (var e : gaps.entrySet()) {
                long[] t = e.getValue();
                boolean ask = t[1] == 0 ? now - t[0] >= NACK_FIRST_MS : now - t[1] >= NACK_RETRY_MS;
                if (ask && n < missing.length) {
                    missing[n++] = e.getKey();
                    t[1] = now;
                }
            }
        }
        if (n == 0) return false;
        sendKeep(missing, n);
        return true;
    }

    private void sendKeep() {
        sendKeep(new int[0], 0);
    }

    private void sendKeep(int[] missing, int n) {
        Spectate.Address p = pilot;
        int port = streamPort;
        if (p == null || port < 0) return;
        send(Spectate.keep(haveSnapshot ? 0 : Spectate.FLAG_NEED_SNAPSHOT, missing, n), p.ip(), port);
    }

    private void send(byte[] d, InetAddress ip, int port) {
        try {
            socket.send(new DatagramPacket(d, d.length, ip, port));
        } catch (IOException e) {
            // the network is away: tried again on the next round
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        Spectate.Address p = pilot;
        if (p != null && streamPort >= 0) send(new byte[]{Spectate.BYE}, p.ip(), streamPort);
    }
}
