package de.droiddrone.pc.fleet;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A virtual phone's socket: every datagram it sends goes through its {@link LinkImpairment} - lost, late, or straight
 * out. The shared Common code (UdpSender) sends through it unchanged, so the transport's own resends and the PC's
 * loss handling meet a bad link the way they meet 4G. What arrives is judged by the receiving loop
 * ({@link VirtualPhone}).
 */
final class ImpairedSocket extends DatagramSocket {
    /** One thread for every late datagram of the fleet (both ways), at the lowest priority. */
    static final ScheduledExecutorService LATER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fleetLinkDelay");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private final LinkImpairment impairment;

    ImpairedSocket(SocketAddress bind, LinkImpairment impairment) throws SocketException {
        super(bind);
        this.impairment = impairment;
    }

    @Override
    public void send(DatagramPacket p) throws IOException {
        long delay = impairment.decide(true, System.currentTimeMillis());
        if (delay < 0) return; // lost on the way
        if (delay == 0) {
            super.send(p);
            return;
        }
        byte[] copy = java.util.Arrays.copyOfRange(p.getData(), p.getOffset(), p.getOffset() + p.getLength());
        SocketAddress to = p.getSocketAddress();
        LATER.schedule(() -> {
            try {
                if (!isClosed()) super.send(new DatagramPacket(copy, copy.length, to));
            } catch (IOException ignored) {
                // as lost
            }
        }, delay, TimeUnit.MILLISECONDS);
    }
}
