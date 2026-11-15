package de.droiddrone.pc.video;

import de.droiddrone.pc.MonoClock;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The STANAG streams' encoders in a process of their own (1.0.68, the pilot: "fix the crash, do the separate
 * process"): {@link EncoderWorker}, started from this app's own command. A crash in the native encoder ends that
 * process, not the app - the feeds see an IOException, send nothing for a moment, and their next encoder starts it
 * again (sooner the first time, later when it keeps crashing). Its crash log (hs_err_pid*.log) is written in the
 * folder it runs in, the logs folder.
 * <p>
 * Each encoder is a loopback connection: the picture goes over (4 bytes a pixel), the packets come back. Only this
 * app's process can use it: it is told a random token, which every connection sends first.
 */
public final class EncoderProcess implements PictureEncoder.Factory, AutoCloseable {
    /** The worker's first words on the app's port. */
    static final int HELLO = 0x444C5345;
    /** A JVM's start: the worker has this long to say hello. */
    static final long START_WAIT_MS = 20_000;
    /** An answer takes milliseconds; none for this long: the worker hangs and is ended. */
    static final int ANSWER_WAIT_MS = 5_000;
    /** Ended this soon after its start: a quick death; each in a row waits longer before the next start. */
    static final long QUICK_DEATH_MS = 60_000;
    static final long[] RESTART_WAIT_MS = {0, 2_000, 5_000, 15_000, 30_000};

    private final Supplier<List<String>> command;
    private final Path folder;
    private final Consumer<String> log;
    private final byte[] token = new byte[16];

    private final Object lock = new Object();
    private volatile boolean closed;
    private volatile Process process;
    private volatile Socket hello;
    private volatile ServerSocket listening;
    private int port;
    private long startedAt, nextStartAt;
    private int quickDeaths, starts;
    private boolean startFailSaid;
    private String endedBy;

    /**
     * @param command this app, its arguments to come after it (the installed exe; from source, this Java with the classpath)
     * @param folder where the process runs: its crash log and its few lines (stanag-encoder.log) go there
     */
    public EncoderProcess(Supplier<List<String>> command, Path folder, Consumer<String> log) {
        this.command = command;
        this.folder = folder;
        this.log = log;
        new SecureRandom().nextBytes(token);
    }

    static byte[] token(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    @Override
    public PictureEncoder open(int width, int height, int fps, int bitRate, int gop) throws IOException {
        int at = port();
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), at), 2_000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(ANSWER_WAIT_MS);
            Remote r = new Remote(s, width, height);
            r.out.write(token);
            r.out.writeInt(width);
            r.out.writeInt(height);
            r.out.writeInt(fps);
            r.out.writeInt(bitRate);
            r.out.writeInt(gop);
            r.out.flush();
            r.answer();
            r.config = new byte[r.in.readInt()];
            r.in.readFully(r.config);
            return r;
        } catch (SocketTimeoutException e) {
            close(s);
            hung();
            throw new Down("the encoder process didn't answer");
        } catch (Failed e) {
            close(s);
            throw e;
        } catch (IOException e) {
            close(s);
            throw e instanceof Down ? e : new Down("the encoder process: " + e.getMessage());
        }
    }

    /** The worker's port; started (again) first when it isn't running. */
    private int port() throws IOException {
        synchronized (lock) {
            if (closed) throw new Down("closed");
            Process p = process;
            if (p != null && p.isAlive()) return port;
            if (p != null) ended(p);
            long now = MonoClock.ms();
            if (now < nextStartAt) throw new Down("the encoder process starts again in " + ((nextStartAt - now + 999) / 1000) + " s");
            try {
                start();
                startFailSaid = false;
            } catch (IOException e) {
                if (!startFailSaid && !closed) log.accept("STANAG 4609: the encoder process couldn't start (" + e.getMessage() + "). Trying again.");
                startFailSaid = true;
                throw new Down(e.getMessage());
            }
            return port;
        }
    }

    /** It ended (a crash, most likely): said once, and when the next start may be. */
    private void ended(Process p) {
        long lived = MonoClock.ms() - startedAt;
        quickDeaths = lived < QUICK_DEATH_MS ? quickDeaths + 1 : 0;
        nextStartAt = MonoClock.ms() + RESTART_WAIT_MS[Math.min(quickDeaths, RESTART_WAIT_MS.length - 1)];
        String why = endedBy != null ? endedBy : "exit code " + exitCode(p);
        log.accept("STANAG 4609: the encoder process stopped (" + why + "). The streams start again."
                + (endedBy == null && crashed(p) ? " Crash log: hs_err_pid" + p.pid() + ".log in the logs folder." : ""));
        endedBy = null;
        process = null;
        close(hello);
        hello = null;
    }

    /** Ended by Windows for an exception (0xC0000005 an access violation ...), not by an exit of its own. */
    static boolean crashed(Process p) {
        try {
            int code = p.exitValue();
            return code < 0 || code > 0xFFFF;
        } catch (IllegalThreadStateException e) {
            return false;
        }
    }

    static String exitCode(Process p) {
        try {
            int code = p.exitValue();
            return code < 0 || code > 0xFFFF ? String.format(Locale.ROOT, "0x%08X", code) : Integer.toString(code);
        } catch (IllegalThreadStateException e) {
            return "?";
        }
    }

    private void start() throws IOException {
        Files.createDirectories(folder);
        ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        listening = server;
        Process p = null;
        try {
            List<String> cmd = new ArrayList<>(command.get());
            cmd.add("stanag-encoder");
            cmd.add(Integer.toString(server.getLocalPort()));
            cmd.add(HexFormat.of().formatHex(token));
            p = new ProcessBuilder(cmd).directory(folder.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(folder.resolve("stanag-encoder.log").toFile())).start();
            startedAt = MonoClock.ms();
            starts++;
            server.setSoTimeout(250);
            long deadline = startedAt + START_WAIT_MS;
            while (true) {
                if (closed) throw new IOException("closed");
                if (!p.isAlive()) throw new IOException("the encoder process ended at its start (exit code " + exitCode(p) + ")");
                if (MonoClock.ms() > deadline) throw new IOException("the encoder process didn't start");
                Socket s;
                try {
                    s = server.accept();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                try {
                    s.setSoTimeout(2_000);
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    byte[] got = new byte[token.length];
                    if (in.readInt() != HELLO) throw new IOException("not the worker");
                    in.readFully(got);
                    if (!Arrays.equals(got, token)) throw new IOException("not the worker");
                    port = in.readInt();
                    s.setSoTimeout(0);
                    hello = s; // open while this app runs: its end is the worker's
                    process = p;
                    return;
                } catch (IOException e) {
                    close(s);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (p != null) p.destroyForcibly();
            quickDeaths++;
            nextStartAt = MonoClock.ms() + RESTART_WAIT_MS[Math.min(quickDeaths, RESTART_WAIT_MS.length - 1)];
            throw e instanceof IOException io ? io : new IOException(e.toString());
        } finally {
            listening = null;
            close(server);
        }
    }

    /** No answer in time: the worker is ended, and started again for the next encoder. */
    private void hung() {
        Process p = process;
        if (p == null) return;
        synchronized (lock) {
            if (process == p && endedBy == null) endedBy = "it stopped answering";
        }
        p.destroyForcibly();
    }

    /** How many times it was started (tests). */
    public int starts() {
        synchronized (lock) {
            return starts;
        }
    }

    /** The worker now, or null (tests). */
    public Process process() {
        return process;
    }

    /** Ends the worker; open encoders fail from now. Any thread, at once. */
    @Override
    public void close() {
        closed = true;
        close(listening);
        close(hello); // the worker ends itself on this
        Process p = process;
        if (p != null) p.destroyForcibly(); // nothing of it is kept: no wait (the Swing thread calls this)
    }

    private static void close(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
            // going anyway
        }
    }

    /** One encoder in the worker: its connection. Not thread-safe, as H264Encoder. */
    private final class Remote implements PictureEncoder {
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        private final int pixels;
        private final byte[] raw;
        private final ByteBuffer rawBuffer;
        private byte[] config;
        private boolean closed;

        Remote(Socket socket, int width, int height) throws IOException {
            this.socket = socket;
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1 << 16));
            pixels = width * height;
            raw = new byte[pixels * 4];
            rawBuffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        }

        @Override
        public byte[] config() {
            return config;
        }

        @Override
        public List<H264Encoder.Packet> encode(int[] picture, boolean keyFrame) throws IOException {
            if (closed) throw new IOException("closed");
            rawBuffer.clear();
            rawBuffer.asIntBuffer().put(picture, 0, pixels);
            return call(EncoderWorker.OP_ENCODE, keyFrame, true);
        }

        @Override
        public List<H264Encoder.Packet> encodeAgain(boolean keyFrame) throws IOException {
            if (closed) throw new IOException("closed");
            return call(EncoderWorker.OP_AGAIN, keyFrame, false);
        }

        private List<H264Encoder.Packet> call(int op, boolean keyFrame, boolean picture) throws IOException {
            try {
                out.writeByte(op);
                out.writeBoolean(keyFrame);
                if (picture) out.write(raw);
                out.flush();
                answer();
                int n = in.readInt();
                List<H264Encoder.Packet> packets = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    boolean key = in.readBoolean();
                    byte[] data = new byte[in.readInt()];
                    in.readFully(data);
                    packets.add(new H264Encoder.Packet(data, key));
                }
                return packets;
            } catch (SocketTimeoutException e) {
                close();
                hung();
                throw new Down("the encoder process didn't answer");
            } catch (Failed e) {
                throw e; // the encoder said no; the connection is fine
            } catch (IOException e) {
                close();
                throw new Down("the encoder process stopped");
            }
        }

        /** OK, or the worker's reason as an IOException. */
        void answer() throws IOException {
            if (in.readInt() == EncoderWorker.OK) return;
            byte[] message = new byte[in.readInt()];
            in.readFully(message);
            throw new Failed(new String(message, StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            EncoderProcess.close(socket);
        }
    }

    /**
     * The process stopped, hangs or isn't running (yet): this class says so once in the log ({@link #ended}), so the
     * streams that see it don't each say it again. They wait, and their next encoder starts it again.
     */
    public static final class Down extends IOException {
        Down(String message) {
            super(message);
        }
    }

    /** The worker's encoder failed (the worker itself is fine). */
    private static final class Failed extends IOException {
        Failed(String message) {
            super(message);
        }
    }
}
