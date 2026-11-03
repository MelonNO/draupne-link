package de.droiddrone.pc;

import de.droiddrone.common.ReceiverBuffer;
import de.droiddrone.common.SavedPacket;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.common.UdpPacketData;
import de.droiddrone.common.UdpSender;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The patched copies of upstream's UdpSender and ReceiverBuffer (flight-app/overlay/Common), compiled into both the
 * PC and the phone app: the resend timer that died after the first reconnect, and the client taking the answer to
 * its own Connect while it still counts the old session as up.
 */
class TransportTest {
    /** Acknowledged packets are resent until acknowledged - also from the second session of a sender on. */
    @Test
    void ackedPacketsAreResentAfterAReconnect() throws Exception {
        try (DatagramSocket out = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket in = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            AtomicInteger received = new AtomicInteger();
            Thread receiver = new Thread(() -> {
                byte[] buf = new byte[2000];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    while (true) {
                        in.receive(p);
                        if (p.getData()[0] == UdpCommon.FcInfo) received.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
            });
            receiver.setDaemon(true);
            receiver.start();
            UdpSender sender = new UdpSender(out);
            sender.connect(InetAddress.getLoopbackAddress(), in.getLocalPort());
            sender.connect(InetAddress.getLoopbackAddress(), in.getLocalPort()); // a reconnect: upstream's timer died here
            sender.sendPacket(new UdpPacketData(UdpCommon.FcInfo).getData()); // acknowledged: resent every ~1.2 x ping
            Thread.sleep(600);
            assertTrue(received.get() >= 3, "sent and resent while unacknowledged: " + received.get());
            sender.close();
            int atClose = received.get();
            Thread.sleep(300);
            assertEquals(atClose, received.get(), "nothing after close");
        }
    }

    /** The client side takes the far side's answer to its Connect while it still counts the old session as up. */
    @Test
    void aClientTakesTheAnswerToItsOwnReconnect() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            UdpSender sender = new UdpSender(socket);
            InetAddress far = InetAddress.getLoopbackAddress();
            sender.connect(far, 50_000);
            ReceiverBuffer client = new ReceiverBuffer(sender, false, "DD", "DD");
            client.addPacket(connect(far, 50_000));
            assertTrue(client.isConnected());
            drain(client);
            // the first session's packets 0..2 taken
            for (int n = 0; n < 3; n++) client.addPacket(numbered(UdpCommon.FcInfo, n, far, 50_000));
            assertEquals(3, drain(client));
            // the far side starts a new session (it took this side's Connect) and answers it: its packets count
            // from 0 again. Upstream dropped the answer while connected, and the packets then sat out of order
            client.addPacket(connect(far, 50_000));
            drain(client);
            client.addPacket(numbered(UdpCommon.FcInfo, 0, far, 50_000));
            assertEquals(1, drain(client), "the new session's first packet is taken");
            // the server side keeps upstream's rule: a Connect while connected is dropped
            ReceiverBuffer server = new ReceiverBuffer(sender, true, "DD", "DD");
            server.addPacket(connect(far, 50_001));
            drain(server);
            for (int n = 0; n < 3; n++) server.addPacket(numbered(UdpCommon.FcInfo, n, far, 50_001));
            assertEquals(3, drain(server));
            server.addPacket(connect(far, 50_001));
            server.addPacket(numbered(UdpCommon.FcInfo, 3, far, 50_001));
            assertEquals(1, drain(server), "the server's numbering goes on (the Connect was dropped)");
        }
    }

    private static int drain(ReceiverBuffer b) {
        int n = 0;
        SavedPacket p;
        while ((p = b.getNextPacket()) != null) {
            if (p.packetName != UdpCommon.Connect) n++;
        }
        return n;
    }

    private static DatagramPacket connect(InetAddress ip, int port) throws Exception {
        UdpPacketData d = new UdpPacketData(UdpCommon.Connect, -1);
        d.daos.writeByte(0);
        d.daos.writeUTF("DD");
        d.daos.writeShort(1);
        byte[] data = d.getData();
        return new DatagramPacket(data, data.length, ip, port);
    }

    private static DatagramPacket numbered(byte name, int num, InetAddress ip, int port) throws Exception {
        UdpPacketData d = new UdpPacketData(name, num);
        byte[] data = d.getData();
        return new DatagramPacket(data, data.length, ip, port);
    }
}
