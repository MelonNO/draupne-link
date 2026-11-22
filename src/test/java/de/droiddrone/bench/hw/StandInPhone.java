package de.droiddrone.bench.hw;

import de.droiddrone.common.MspFrames;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * A plain TCP client where the phone app would be, for the keeper's tests and its scenario on Betaflight as a program (bench/sitl): it connects to the keeper's phone port as
 * the bench build's BenchFc does and sends real MSP frames as DRAUPNE Flight frames them (MSP v2) - the sticks 250
 * times a second, a status question twice a second - and reads what comes back with the phone's own reader
 * (common.MspFrames).
 */
public final class StandInPhone implements AutoCloseable {
    /** A stick frame's period: 250 a second. */
    static final long PERIOD_NS = 4_000_000;

    private final Socket socket = new Socket();
    private final OutputStream out;
    private final List<MspFrames.Frame> answers = new ArrayList<>();
    private final AtomicLong sticksSent = new AtomicLong(), bytesIn = new AtomicLong(), framedIn = new AtomicLong();
    private volatile boolean ended, sending, statusToo = true, closed;
    private volatile int throttle = 1000, arm = 1000, prearm = 1000;
    private volatile long periodNs = PERIOD_NS;
    private Thread sticks;

    /**
     * Stick frames this many ms apart instead of 4 (the status question still twice a second). For Betaflight itself
     * (bench/sitl): it takes one MSP request per run of its serial task, 100 runs a second, and 250 stick frames a
     * second leave nothing for anybody else's questions.
     */
    public StandInPhone every(long ms) {
        periodNs = Math.max(1, ms) * 1_000_000L;
        return this;
    }

    public StandInPhone(int phonePort) throws IOException {
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), phonePort), 2000);
        out = socket.getOutputStream();
        Thread reader = new Thread(this::read, "standInPhoneReader");
        reader.setDaemon(true);
        reader.start();
    }

    private void read() {
        MspFrames frames = new MspFrames();
        byte[] buf = new byte[2048];
        try {
            InputStream in = socket.getInputStream();
            for (int n = in.read(buf); n >= 0; n = in.read(buf)) {
                bytesIn.addAndGet(n);
                List<MspFrames.Frame> got = frames.feed(buf, n);
                synchronized (answers) {
                    for (MspFrames.Frame f : got) {
                        answers.add(f);
                        framedIn.addAndGet((f.version == MspFrames.V2 ? MspFrames.V2_HEADER_SIZE : MspFrames.V1_HEADER_SIZE) + f.payloadSize + MspFrames.CRC_SIZE);
                    }
                }
            }
        } catch (IOException e) {
            // the keeper cut it, or this was closed
        }
        ended = true;
    }

    /** The stick frame as the board takes it with the channel map AETR1234: roll, pitch, throttle, yaw, AUX1 (ARM), AUX2, AUX3, AUX4, all else at rest. */
    static byte[] stickFrame(int throttle, int arm) {
        return stickFrame(throttle, arm, 1000);
    }

    /** The same with AUX3, where Drone setup puts PREARM. */
    static byte[] stickFrame(int throttle, int arm, int aux3) {
        int[] channels = {1500, 1500, throttle, 1500, arm, 1000, aux3, 1000};
        byte[] p = new byte[channels.length * 2];
        for (int i = 0; i < channels.length; i++) Msp.putU16(p, 2 * i, channels[i]);
        return MspFrames.v2Request(Msp.SET_RAW_RC, p);
    }

    /** What the sticks say from the next frame on. */
    public StandInPhone sticks(int throttle, int arm) {
        this.throttle = throttle;
        this.arm = arm;
        return this;
    }

    /** AUX3 from the next frame on: the PREARM switch of a board Drone setup has set up. */
    StandInPhone prearm(int aux3) {
        prearm = aux3;
        return this;
    }

    /** Stick frames from now on, 250 a second ({@link #every}), with a status question every half second - until {@link #stopSticks} or the connection ends. */
    public StandInPhone startSticks() {
        sending = true;
        sticks = new Thread(() -> {
            long next = System.nanoTime(), count = 0, period = periodNs, statusEvery = Math.max(1, 500_000_000L / period);
            while (sending && !ended && !closed) {
                try {
                    send(stickFrame(throttle, arm, prearm));
                    sticksSent.incrementAndGet();
                    if (statusToo && count++ % statusEvery == 0) send(MspFrames.v2Request(Msp.STATUS, null));
                } catch (IOException e) {
                    return;
                }
                next += period;
                long wait = next - System.nanoTime();
                if (wait > 0) LockSupport.parkNanos(wait);
                else if (wait < -50_000_000) next = System.nanoTime(); // far behind (a loaded PC): no burst to catch up
            }
        }, "standInPhoneSticks");
        sticks.setDaemon(true);
        sticks.start();
        return this;
    }

    public StandInPhone stopSticks() throws InterruptedException {
        sending = false;
        if (sticks != null) sticks.join(2000);
        return this;
    }

    /** Sticks alone, no status questions between them. */
    StandInPhone sticksOnly() {
        statusToo = false;
        return this;
    }

    public long sticksSent() {
        return sticksSent.get();
    }

    /** Any bytes at all, as they are. */
    void send(byte[] raw) throws IOException {
        synchronized (out) {
            out.write(raw);
            out.flush();
        }
    }

    /** A request as the phone app frames it: MSP v2. */
    void request(int code, byte[] payload) throws IOException {
        send(MspFrames.v2Request(code, payload));
    }

    /** Every frame that came back so far. */
    List<MspFrames.Frame> answers() {
        synchronized (answers) {
            return List.copyOf(answers);
        }
    }

    /** How many answers to this request came (errors too). */
    long answersTo(int code) {
        synchronized (answers) {
            return answers.stream().filter(f -> f.code == code).count();
        }
    }

    /** The newest answer to this request, or null. */
    MspFrames.Frame last(int code) {
        synchronized (answers) {
            for (int i = answers.size() - 1; i >= 0; i--) if (answers.get(i).code == code) return answers.get(i);
            return null;
        }
    }

    /** Bytes that came and were part of no frame. */
    long junkIn() {
        return bytesIn.get() - framedIn.get();
    }

    /** The keeper (or the board behind it) ended the connection. */
    public boolean ended() {
        return ended;
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
