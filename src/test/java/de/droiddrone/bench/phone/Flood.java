package de.droiddrone.bench.phone;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BS6: one datagram sent again and again - here the name query every DRAUPNE Link's scan sends ({@code Names.query},
 * with the link key) - from many source addresses or from one, at a rate held to so many bytes a second: what is sent
 * and what comes back counted together ({@link Bucket}), so the answers cannot push the total past it. Each source is
 * a socket of its own; the answers are read as they come, counted and dropped.
 */
final class Flood implements AutoCloseable {
    /** The IPv4 and UDP head carried with each datagram (counted with it; Tailscale's own wrapping, some 60 bytes more, is not). */
    static final int HEAD = 28;

    private final String name;
    private final byte[] datagram;
    private final InetSocketAddress to;
    private final Bucket bucket;
    private final List<DatagramChannel> sources = new ArrayList<>();
    private final Selector selector;
    private final List<String> refusedSaid = new ArrayList<>();
    private int refused;
    final AtomicLong sent = new AtomicLong(), sentBytes = new AtomicLong(), answers = new AtomicLong(), answerBytes = new AtomicLong(), errors = new AtomicLong();
    private volatile boolean running;
    private Thread thread;

    /**
     * @param from           the source addresses, a socket on each (its port the system's choice); empty: one socket on
     *                       every address of this PC, the system choosing the way out
     * @param bytesPerSecond what is sent and what comes back, together, at most
     */
    Flood(String name, byte[] datagram, InetSocketAddress to, List<InetAddress> from, long bytesPerSecond) throws IOException {
        this.name = name;
        this.datagram = datagram.clone();
        this.to = to;
        bucket = new Bucket(bytesPerSecond);
        selector = Selector.open();
        if (from.isEmpty()) open(null);
        else for (InetAddress a : from) open(a);
        if (sources.isEmpty()) {
            selector.close();
            throw new IOException("No source address could be had for the flood at " + name + ": " + refusedSaid);
        }
    }

    private void open(InetAddress at) {
        DatagramChannel c = null;
        try {
            c = DatagramChannel.open(StandardProtocolFamily.INET);
            c.configureBlocking(false);
            c.bind(new InetSocketAddress(at, 0));
            c.register(selector, SelectionKey.OP_READ);
            sources.add(c);
        } catch (IOException | RuntimeException e) {
            refused++;
            if (refusedSaid.size() < 3) refusedSaid.add((at == null ? "every address" : at.getHostAddress()) + ": " + e.getMessage());
            if (c != null) {
                try {
                    c.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** How many source addresses it sends from. */
    int sources() {
        return sources.size();
    }

    /** The source addresses that could not be had (their count; the first few said in {@link #refusedSaid}). */
    int refused() {
        return refused;
    }

    List<String> refusedSaid() {
        return List.copyOf(refusedSaid);
    }

    void start() {
        running = true;
        thread = new Thread(this::run, "benchFlood-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        ByteBuffer out = ByteBuffer.wrap(datagram), in = ByteBuffer.allocate(4096);
        int next = 0, size = datagram.length + HEAD;
        while (running) {
            long n = bucket.due(System.nanoTime(), answerBytes.get(), size);
            for (long i = 0; i < n && running; i++) {
                DatagramChannel c = sources.get(next);
                next = (next + 1) % sources.size();
                out.rewind();
                try {
                    if (c.send(out, to) > 0) {
                        sent.incrementAndGet();
                        sentBytes.addAndGet(size);
                    } else {
                        errors.incrementAndGet(); // the socket's buffer full: not sent (charged all the same)
                    }
                } catch (IOException e) {
                    errors.incrementAndGet();
                }
            }
            readAnswers(in);
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void readAnswers(ByteBuffer in) {
        try {
            if (selector.selectNow() == 0) return;
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey k = keys.next();
                keys.remove();
                DatagramChannel c = (DatagramChannel) k.channel();
                for (int i = 0; i < 64; i++) {
                    in.clear();
                    try {
                        if (c.receive(in) == null) break;
                    } catch (IOException e) { // (Windows: an earlier send that found no port)
                        errors.incrementAndGet();
                        break;
                    }
                    answers.incrementAndGet();
                    answerBytes.addAndGet(in.position() + HEAD);
                }
            }
        } catch (IOException | ClosedSelectorException e) {
            errors.incrementAndGet();
        }
    }

    /** Everything it took of the network so far, both ways, bytes. */
    long bytes() {
        return sentBytes.get() + answerBytes.get();
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (DatagramChannel c : sources) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
        try {
            selector.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * n addresses of this PC's own loopback beside 127.0.0.1 - 127.1.0.1, 127.1.0.2 ... (Windows answers on all of
     * 127/8) - so many senders as far as the receiver can tell.
     */
    static List<InetAddress> loopbackSources(int n) {
        List<InetAddress> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            try {
                out.add(InetAddress.getByAddress(new byte[]{127, 1, (byte) (i / 250), (byte) (i % 250 + 1)}));
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    /**
     * Bytes to spend, filled at the rate and holding {@link #capacity} at most (a pause is not made up in a burst). A
     * datagram sent takes its size when it is let go; an answer takes its own when it has come.
     */
    static final class Bucket {
        private final long perSecond, capacity;
        private double bytes;
        private long lastNs;
        private long answersSeen;
        private boolean started;

        Bucket(long perSecond) {
            if (perSecond <= 0) throw new IllegalArgumentException("A flood's rate is more than nothing: " + perSecond);
            this.perSecond = perSecond;
            capacity = Math.max(perSecond / 10, 4096);
        }

        long capacity() {
            return capacity;
        }

        /**
         * How many datagrams of that size may go now - and they are taken.
         *
         * @param nowNs       the clock, ns
         * @param answerBytes all the answers' bytes so far
         */
        long due(long nowNs, long answerBytes, int size) {
            if (!started) {
                started = true;
                lastNs = nowNs;
                answersSeen = answerBytes;
                return 0;
            }
            bytes = Math.min(capacity, bytes + perSecond * ((nowNs - lastNs) / 1e9));
            lastNs = nowNs;
            bytes -= answerBytes - answersSeen;
            answersSeen = answerBytes;
            if (bytes < size) return 0;
            long n = (long) (bytes / size);
            bytes -= (double) n * size;
            return n;
        }
    }
}
