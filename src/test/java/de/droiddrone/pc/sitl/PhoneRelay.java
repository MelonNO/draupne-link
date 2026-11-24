package de.droiddrone.pc.sitl;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The SITL lab: an emulator's phone at a loopback address of its own (127.0.0.x, as the virtual fleet's drones), so
 * several emulator phones can be announced at once (VirtualFleet tells them apart by address). DRAUPNE Link sends to
 * {@link #address()}; the relay passes each datagram on to the emulator's forwarded port - from a socket of its own for
 * each of DRAUPNE Link's sockets (its link, its name service, its fleet commands), so the phone sees them as a real
 * phone sees a PC's sockets and its answers come back to the socket that asked. The bench's Wire has one socket for
 * every PC socket (its answers go to whoever spoke last): fine for one link, not for a fleet drone.
 */
final class PhoneRelay implements AutoCloseable {
    private final DatagramSocket front;
    private final InetSocketAddress phone;
    /** One socket towards the phone per DRAUPNE Link socket. */
    private final Map<SocketAddress, DatagramSocket> back = new ConcurrentHashMap<>();
    private volatile boolean open = true;

    /**
     * @param bind  the loopback address DRAUPNE Link is to reach this phone at
     * @param phone where the emulator takes datagrams for the phone's link port (127.0.0.1 and the forwarded port)
     */
    PhoneRelay(InetAddress bind, InetSocketAddress phone) throws IOException {
        this.phone = phone;
        front = socket(bind);
        thread(() -> pump(front, null), "phoneRelayIn");
    }

    InetSocketAddress address() {
        return new InetSocketAddress(front.getLocalAddress(), front.getLocalPort());
    }

    private static DatagramSocket socket(InetAddress bind) throws IOException {
        DatagramSocket s = new DatagramSocket(0, bind);
        s.setReceiveBufferSize(1 << 20);
        s.setSendBufferSize(1 << 20);
        return s;
    }

    private static void thread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    /** from == null: DRAUPNE Link's datagrams to the phone; else the phone's answers on the socket for that PC socket. */
    private void pump(DatagramSocket in, SocketAddress pc) {
        byte[] buf = new byte[65_536];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (open) {
            try {
                p.setLength(buf.length);
                in.receive(p);
                if (pc == null) {
                    SocketAddress from = p.getSocketAddress();
                    DatagramSocket out = back.get(from);
                    if (out == null) {
                        out = socket(InetAddress.getLoopbackAddress());
                        DatagramSocket s = out;
                        back.put(from, s);
                        thread(() -> pump(s, from), "phoneRelayOut");
                    }
                    out.send(new DatagramPacket(buf, p.getLength(), phone));
                } else {
                    front.send(new DatagramPacket(buf, p.getLength(), pc));
                }
            } catch (IOException | RuntimeException e) {
                if (!open) return;
            }
        }
    }

    @Override
    public void close() {
        open = false;
        front.close();
        for (DatagramSocket s : back.values()) s.close();
    }
}
