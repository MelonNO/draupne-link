package de.droiddrone.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * A web server on this PC that behaves as the internet does on a bad day (2026-11-19, the pilot: "the map is stuck
 * loading even though the computer has internet"): it answers, or takes the request and says nothing, or sends half
 * an answer and stops, or hangs up, or says 503, or is not there at all. What it does to each request is the test's
 * to say ({@link #each}); what it was asked is counted.
 */
public final class TroubledServer implements AutoCloseable {
    public enum Mode {
        /** The body, whole. */
        OK,
        /** The request is read and never answered; the connection stays open. */
        SILENT,
        /** The answer's head and half its body, then nothing; the connection stays open. */
        HALF,
        /** The body whole, but in {@link #pieces} pieces with {@link #pieceEveryMs} between them: a slow link that works. */
        SLOW,
        /** The connection is closed without a word. */
        HANG_UP,
        /** 503 Service Unavailable. */
        BUSY,
        /** 404 Not Found. */
        NOT_THERE
    }

    private final byte[] body;
    private final String contentType;
    private final int port;
    private volatile ServerSocket server;
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private volatile IntFunction<Mode> each = n -> Mode.OK;
    private volatile boolean closed;
    /** {@link Mode#SLOW}: in how many pieces the body goes out, and the pause before each. */
    public volatile int pieces = 10;
    public volatile long pieceEveryMs = 150;

    /** Up at once, on a free port of this PC. */
    public TroubledServer(byte[] body, String contentType) throws IOException {
        this.body = body;
        this.contentType = contentType;
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        port = server.getLocalPort();
        accept(server);
    }

    public String address() {
        return "http://127.0.0.1:" + port;
    }

    /** What happens to the n-th request (the first is 1). */
    public void each(IntFunction<Mode> mode) {
        each = mode;
    }

    public void always(Mode mode) {
        each = n -> mode;
    }

    /** Requests read so far, whatever became of them. */
    public int requests() {
        return requests.get();
    }

    public List<String> paths() {
        return List.copyOf(paths);
    }

    /** The server gone: nothing listens on its port (a connection is refused), what was open is closed. */
    public void down() throws IOException {
        ServerSocket s = server;
        server = null;
        if (s != null) s.close();
        for (Socket k : open) closeQuietly(k);
        open.clear();
    }

    /** There again, on the same port. */
    public void up() throws IOException {
        if (server != null) return;
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
        server = s;
        accept(s);
    }

    private void accept(ServerSocket s) {
        Thread t = new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    Socket k = s.accept();
                    open.add(k);
                    Thread c = new Thread(() -> serve(k), "troubledServer-connection");
                    c.setDaemon(true);
                    c.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "troubledServer");
        t.setDaemon(true);
        t.start();
    }

    private void serve(Socket k) {
        try (k) {
            InputStream in = k.getInputStream();
            OutputStream out = k.getOutputStream();
            while (!closed) {
                String head = readHead(in);
                if (head == null) return;
                int n = requests.incrementAndGet();
                String[] first = head.split("\r\n", 2)[0].split(" ");
                paths.add(first.length > 1 ? first[1] : "");
                switch (each.apply(n)) {
                    case OK -> {
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(body);
                        out.flush();
                    }
                    case SILENT -> {
                        park(k);
                        return;
                    }
                    case HALF -> {
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(body, 0, body.length / 2);
                        out.flush();
                        park(k);
                        return;
                    }
                    case SLOW -> {
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        int n0 = Math.max(1, pieces), sent = 0;
                        for (int i = 1; i <= n0 && !closed; i++) {
                            try {
                                Thread.sleep(pieceEveryMs);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            int upTo = (int) ((long) body.length * i / n0);
                            out.write(body, sent, upTo - sent);
                            out.flush();
                            sent = upTo;
                        }
                    }
                    case HANG_UP -> {
                        return;
                    }
                    case BUSY -> {
                        out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                    case NOT_THERE -> {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                }
            }
        } catch (IOException ignored) {
            // the client hung up, or the server went down
        } finally {
            open.remove(k);
        }
    }

    /** Holds the connection open and says nothing, until the other side or the test closes it. */
    private void park(Socket k) {
        try {
            while (!closed && k.getInputStream().read() >= 0) {
                // whatever more it sends is not answered
            }
        } catch (IOException ignored) {
        }
    }

    /** A request's head up to its empty line; null when the connection ended. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        for (int b; (b = in.read()) >= 0; ) {
            head.write(b);
            state = b == '\r' ? (state == 2 ? 3 : 1) : b == '\n' ? (state == 1 ? 2 : state == 3 ? 4 : 0) : 0;
            if (state == 4) return head.toString(StandardCharsets.US_ASCII);
        }
        return null;
    }

    private static void closeQuietly(Socket k) {
        try {
            k.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        down();
    }
}
