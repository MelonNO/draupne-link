package de.droiddrone.bench.sitl;

import de.droiddrone.bench.hw.Msp;
import de.droiddrone.common.MspFrames;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * SITL's UART1 as a receiver and a ground station have it at once: stick frames (MSP_SET_RAW_RC) 25 times a second
 * from a thread of their own, never waiting for an answer, and questions answered in between - for watching what
 * Betaflight does when the sticks stop, which a request-and-answer port (BetaflightUsb) cannot: its sticks would stop
 * whenever it waits. The channels are AETR1234, as the drone's map: roll, pitch, throttle, yaw, AUX1 (ARM) ...
 * <p>
 * Why 25 and not more: Betaflight takes one MSP request per run of its serial task, and that task runs 100 times a
 * second (serial_update_rate_hz) - on SITL as on a board. At 100 stick frames a second nothing else on the port was
 * ever answered (2026-11-22, 4.4.1); at 25 three quarters are left for the questions.
 */
final class SitlLink implements AutoCloseable {
    /** A stick frame's period: 25 a second (Betaflight's RXLOSS comes after 100 ms without one). */
    static final long PERIOD_NS = 40_000_000;

    private record Answer(long atNs, byte[] payload, boolean error) {
    }

    private final Socket socket = new Socket();
    private final OutputStream out;
    private final Map<Integer, Answer> answers = new HashMap<>();
    private volatile boolean sending, closed, gone;
    private volatile int throttle = 1000, arm = 1000;
    private volatile long lastStickNs;
    private Thread sticks;

    SitlLink() throws IOException {
        this(Sitl.port());
    }

    /** On another of SITL's UARTs (TCP 5760 + n), one that has MSP on it: a receiver beside a ground station on UART1. */
    SitlLink(int port) throws IOException {
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(Sitl.HOST, port), 2000);
        out = socket.getOutputStream();
        Thread reader = new Thread(this::read, "sitlLinkReader");
        reader.setDaemon(true);
        reader.start();
    }

    private void read() {
        MspFrames frames = new MspFrames();
        byte[] buf = new byte[4096];
        try {
            InputStream in = socket.getInputStream();
            for (int n = in.read(buf); n >= 0; n = in.read(buf)) {
                long now = System.nanoTime();
                synchronized (answers) {
                    for (MspFrames.Frame f : frames.feed(buf, n)) {
                        if (f.type == MspFrames.REQUEST) continue;
                        answers.put(f.code, new Answer(now, f.payload == null ? new byte[0] : f.payload, f.isError()));
                    }
                    answers.notifyAll();
                }
            }
        } catch (IOException e) {
            // closed, or Betaflight restarted
        }
        gone = true;
        synchronized (answers) {
            answers.notifyAll();
        }
    }

    private void send(byte[] frame) throws IOException {
        synchronized (out) {
            out.write(frame);
            out.flush();
        }
    }

    /** One question and its answer's payload; the stick frames go on meanwhile. */
    byte[] ask(int code, byte[] payload, long ms) throws IOException {
        long asked = System.nanoTime();
        send(MspFrames.v1Request(code, payload));
        long end = System.currentTimeMillis() + ms;
        synchronized (answers) {
            while (true) {
                Answer a = answers.get(code);
                if (a != null && a.atNs() >= asked) {
                    if (a.error()) throw new IOException("Betaflight refused MSP " + code);
                    return a.payload();
                }
                long left = end - System.currentTimeMillis();
                if (gone || closed) throw new IOException("SITL's connection ended");
                if (left <= 0) throw new IOException("no answer to MSP " + code + " within " + ms + " ms");
                try {
                    answers.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("interrupted");
                }
            }
        }
    }

    /** SITL closed the connection (it restarted: save, the CLI's exit, MSP's reboot) or this side did. */
    boolean gone() {
        return gone || closed;
    }

    /** The throttle and the ARM switch from the next frame on (roll, pitch, yaw centred, AUX2-4 low). */
    SitlLink sticks(int throttle, int arm) {
        this.throttle = throttle;
        this.arm = arm;
        return this;
    }

    static byte[] stickPayload(int throttle, int arm) {
        int[] channels = {1500, 1500, throttle, 1500, arm, 1000, 1000, 1000};
        byte[] p = new byte[channels.length * 2];
        for (int i = 0; i < channels.length; i++) Msp.putU16(p, 2 * i, channels[i]);
        return p;
    }

    /** Stick frames from now on, until {@link #stopSticks}. */
    SitlLink startSticks() {
        if (sending) return this;
        sending = true;
        sticks = new Thread(() -> {
            long next = System.nanoTime();
            while (sending && !closed && !gone) {
                try {
                    send(MspFrames.v1Request(Msp.SET_RAW_RC, stickPayload(throttle, arm)));
                    lastStickNs = System.nanoTime();
                } catch (IOException e) {
                    return;
                }
                next += PERIOD_NS;
                long wait = next - System.nanoTime();
                if (wait > 0) LockSupport.parkNanos(wait);
                else if (wait < -50_000_000) next = System.nanoTime();
            }
        }, "sitlLinkSticks");
        sticks.setDaemon(true);
        sticks.start();
        return this;
    }

    /** No stick frame after this returns: as a receiver that has lost its radio. The time of the last frame sent. */
    long stopSticks() throws InterruptedException {
        sending = false;
        if (sticks != null) sticks.join(2000);
        return lastStickNs;
    }

    @Override
    public void close() {
        closed = true;
        sending = false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
