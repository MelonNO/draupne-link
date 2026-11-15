package de.droiddrone.testing;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A stretch of network for the tests, on loopback: it stands between a PC's link and a (virtual or fake) phone and
 * passes every datagram on - or loses it, or holds it back, as the test's {@link Rule} says for that very packet (a
 * given fragment of a given frame, the second Connect, everything for 3 s). It counts what passes each way by the
 * packet's first byte and, when asked, keeps copies: what a side was sent is then a fact of the test, not a guess.
 * <p>
 * The link is given {@link #port()} as the phone's port; the phone sees the wire's other socket as the PC (one address
 * and port, as a PC's), so its session rules run as on a real network. Two threads, one each way; held packets go out
 * from a third. Ports are the system's own choice (port 0).
 */
public final class Wire implements AutoCloseable {
    /** What happens to one datagram. */
    public interface Rule {
        /** Lost on the way. */
        long LOST = -1;

        /**
         * @param toPhone from the PC to the phone (else from the phone to the PC)
         * @param d       the datagram (its first len bytes; not to be changed)
         * @return {@link #LOST}, 0 to pass it at once, or the milliseconds it is held back
         */
        long decide(boolean toPhone, byte[] d, int len);
    }

    private final DatagramSocket pcSide, phoneSide;
    private final SocketAddress phone;
    private volatile SocketAddress pc;
    private volatile Rule rule = (toPhone, d, len) -> 0;
    private volatile boolean running = true, keeping;
    private final AtomicLongArray toPhoneCount = new AtomicLongArray(256), toPcCount = new AtomicLongArray(256);
    private final List<byte[]> toPhoneKept = new ArrayList<>(), toPcKept = new ArrayList<>();
    private final ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wireLater");
        t.setDaemon(true);
        return t;
    });

    /** A wire to the phone at that address and port. */
    public Wire(InetAddress phoneAddress, int phonePort) throws IOException {
        phone = new InetSocketAddress(phoneAddress, phonePort);
        pcSide = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        phoneSide = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        for (DatagramSocket s : new DatagramSocket[]{pcSide, phoneSide}) {
            s.setReceiveBufferSize(1 << 20);
            s.setSendBufferSize(1 << 20);
        }
        start(() -> pump(pcSide, true), "wireToPhone");
        start(() -> pump(phoneSide, false), "wireToPc");
    }

    private static void start(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    /** The port the PC's link takes for the phone's (on loopback). */
    public int port() {
        return pcSide.getLocalPort();
    }

    /** The port the phone sees the PC at. */
    public int pcPortSeenByPhone() {
        return phoneSide.getLocalPort();
    }

    /** From now on: what happens to each datagram. */
    public void rule(Rule r) {
        rule = r;
    }

    /** Every datagram passes again. */
    public void clear() {
        rule = (toPhone, d, len) -> 0;
    }

    /** Copies of what passes are kept from now on ({@link #sentToPc}, {@link #sentToPhone}): what was lost is not among them. */
    public void keep(boolean on) {
        keeping = on;
    }

    private void pump(DatagramSocket in, boolean toPhone) {
        byte[] buf = new byte[4096];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                p.setLength(buf.length);
                in.receive(p);
                if (toPhone) pc = p.getSocketAddress();
                SocketAddress to = toPhone ? phone : pc;
                if (to == null) continue; // the phone spoke before any PC did
                int len = p.getLength();
                long hold = rule.decide(toPhone, buf, len);
                if (hold < 0) continue;
                byte[] d = Arrays.copyOf(buf, len);
                DatagramSocket out = toPhone ? phoneSide : pcSide;
                if (hold == 0) pass(out, d, to, toPhone);
                else later.schedule(() -> pass(out, d, to, toPhone), hold, TimeUnit.MILLISECONDS);
            } catch (IOException | RuntimeException e) {
                if (!running) return;
            }
        }
    }

    private void pass(DatagramSocket out, byte[] d, SocketAddress to, boolean toPhone) {
        if (!running) return;
        if (d.length > 0) (toPhone ? toPhoneCount : toPcCount).incrementAndGet(d[0] & 0xff);
        if (keeping) {
            List<byte[]> kept = toPhone ? toPhoneKept : toPcKept;
            synchronized (kept) {
                kept.add(d);
            }
        }
        try {
            out.send(new DatagramPacket(d, d.length, to));
        } catch (IOException ignored) {
            // as lost
        }
    }

    /** How many datagrams with that first byte went on to the PC so far. */
    public long toPc(int type) {
        return toPcCount.get(type & 0xff);
    }

    /** How many datagrams with that first byte went on to the phone so far. */
    public long toPhone(int type) {
        return toPhoneCount.get(type & 0xff);
    }

    /** What went on to the PC since {@link #keep}, in order (copies). */
    public List<byte[]> sentToPc() {
        synchronized (toPcKept) {
            return new ArrayList<>(toPcKept);
        }
    }

    /** What went on to the phone since {@link #keep}, in order (copies). */
    public List<byte[]> sentToPhone() {
        synchronized (toPhoneKept) {
            return new ArrayList<>(toPhoneKept);
        }
    }

    @Override
    public void close() {
        running = false;
        later.shutdownNow();
        pcSide.close();
        phoneSide.close();
    }
}
