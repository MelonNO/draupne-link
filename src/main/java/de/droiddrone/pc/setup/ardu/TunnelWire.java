package de.droiddrone.pc.setup.ardu;

import de.droiddrone.pc.ControlLink;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Fleet: a {@link MavSession.Wire} through the pilot's link and the phone's tunnel (MavTunnel) - routes, parameters
 * and targets for an ArduPilot drone in the air, with the same code the bench uses over USB. What the tunnel doesn't
 * carry is dropped by the phone (the session's own heartbeats among it: the phone is the drone's ground station).
 * Queue capped: answers nobody reads don't pile up.
 */
public final class TunnelWire implements MavSession.Wire {
    static final int MAX_QUEUED = 512;

    private final ControlLink link;
    private final BlockingQueue<byte[]> in = new LinkedBlockingQueue<>(MAX_QUEUED);
    private volatile boolean closed;
    /** What a read with a smaller buffer left of the last packet: given first by the next read (the reader thread's own). */
    private byte[] rest;
    private int restAt;
    private java.util.function.Consumer<byte[]> listener;

    public TunnelWire(ControlLink link) {
        this.link = link;
        link.addTunnelListener(listener = frames -> {
            if (!in.offer(frames)) {
                in.poll();
                in.offer(frames);
            }
        });
    }

    @Override
    public void write(byte[] data) throws IOException {
        if (closed) throw new IOException("closed");
        // a frame the link refuses (no pilot session, an older phone app) is simply not sent: the session asks again
        link.sendTunnel(data);
    }

    @Override
    public int read(byte[] buf, int timeoutMs) {
        if (closed) return -1;
        try {
            byte[] f = rest;
            int at = restAt;
            if (f == null) {
                f = in.poll(timeoutMs, TimeUnit.MILLISECONDS);
                at = 0;
            }
            if (f == null) return 0;
            // a packet larger than the buffer is given in parts (it was cut and its tail lost: a broken frame)
            int n = Math.min(buf.length, f.length - at);
            System.arraycopy(f, at, buf, 0, n);
            if (at + n < f.length) {
                rest = f;
                restAt = at + n;
            } else {
                rest = null;
            }
            return n;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    @Override
    public void close() {
        closed = true;
        link.removeTunnelListener(listener);
    }
}
