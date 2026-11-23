package de.droiddrone.pc.setup.ardu;

import de.droiddrone.pc.ControlLink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

/**
 * Fleet PRD AP-11: Mission Planner or QGroundControl on this PC, for tuning an ArduPilot drone over the link - a local
 * UDP relay of the phone's tunnel (MavTunnel). What the drone's FC answers goes to 127.0.0.1:{@link #DEFAULT_OUT} (where
 * those programs listen); what they send comes in on 127.0.0.1:{@link #DEFAULT_IN} and goes to the phone, which still
 * writes only what MavTunnel lists: parameters and missions, not sticks, arming, calibration or reboot. The tunnel is
 * kept open (its answers flow) by a REQUEST_MESSAGE for the heartbeat every 10 s. Loopback only: nothing from the network.
 */
public final class MavBridge implements AutoCloseable {
    public static final int DEFAULT_OUT = 14550, DEFAULT_IN = 14551;
    static final long KEEP_OPEN_MS = 10_000;

    /** The tunnel as the bridge needs it (ControlLink's, or a test's). */
    public interface Tunnel {
        void addListener(Consumer<byte[]> l);

        void removeListener(Consumer<byte[]> l);

        boolean send(byte[] frames);
    }

    private final Tunnel link;
    private final DatagramSocket socket;
    private final InetSocketAddress out;
    private final Consumer<byte[]> fromFc;
    private volatile boolean open = true;
    private volatile long sent, received;

    public MavBridge(ControlLink link, int inPort, int outPort) throws IOException {
        this(new Tunnel() {
            public void addListener(Consumer<byte[]> l) {
                link.addTunnelListener(l);
            }

            public void removeListener(Consumer<byte[]> l) {
                link.removeTunnelListener(l);
            }

            public boolean send(byte[] frames) {
                return link.sendTunnel(frames);
            }
        }, inPort, outPort);
    }

    public MavBridge(Tunnel link, int inPort, int outPort) throws IOException {
        this.link = link;
        socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), inPort));
        out = new InetSocketAddress(InetAddress.getLoopbackAddress(), outPort);
        fromFc = frames -> {
            try {
                socket.send(new DatagramPacket(frames, frames.length, out));
                received++;
            } catch (IOException ignored) {
                // the program isn't listening yet
            }
        };
        link.addListener(fromFc);
        Thread r = new Thread(this::receive, "mavBridgeIn");
        r.setDaemon(true);
        r.start();
        Thread k = new Thread(this::keepOpen, "mavBridgeKeepOpen");
        k.setDaemon(true);
        k.start();
    }

    /** Frames from the drone to the program, and back, so far. */
    public long toProgram() {
        return received;
    }

    public long toDrone() {
        return sent;
    }

    private void receive() {
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (open) {
            try {
                socket.receive(p);
            } catch (IOException e) {
                if (!open) return;
                continue;
            }
            if (!p.getAddress().isLoopbackAddress()) continue; // this PC's own programs only
            byte[] frames = java.util.Arrays.copyOf(p.getData(), p.getLength());
            if (link.send(frames)) sent++;
        }
    }

    private void keepOpen() {
        while (open) {
            try {
                byte[] ask = new com.MAVLink.common.msg_command_long(0, 0, 0, 0, 0, 0, 0, 512, (short) 1, (short) 1, (short) 0,
                        MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack().encodePacket(); // REQUEST_MESSAGE: HEARTBEAT
                link.send(ask);
                Thread.sleep(KEEP_OPEN_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    @Override
    public void close() {
        open = false;
        link.removeListener(fromFc);
        socket.close();
    }
}
