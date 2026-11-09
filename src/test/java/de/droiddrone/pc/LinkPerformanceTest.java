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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the link's hot paths cost on this PC, with bounds far above the measured figures (a loaded test runner must
 * not fail them): a stick frame built (250 a second, on the input thread - latency first) and a numbered packet
 * through the receive buffer (telemetry, pongs). The figures are printed for the record.
 */
class LinkPerformanceTest {
    @Test
    void aStickFrameIsBuiltInMicroseconds() throws Exception {
        short[] radio = {1500, 1500, 1000, 1500, 1000, 2000, 1000, 1000, 1500, 1500, 1000, 1000};
        int n = 200_000;
        long sink = 0;
        for (int round = 0; round < 2; round++) { // the first round warms the JIT up
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                short[] frame = RcSession.withBeeper(radio, 3 + 14, (i & 64) != 0);
                byte[] data = ControlLink.rcFrame(ControlLink.padForRxMap(frame), i & 0xffff, i, 400);
                sink += data[data.length - 1];
            }
            double us = (System.nanoTime() - t0) / 1000.0 / n;
            if (round == 1) {
                System.out.printf("stick frame built: %.2f us (%d)%n", us, sink & 1);
                assertTrue(us < 50, "a stick frame took " + us + " us to build");
            }
        }
    }

    @Test
    void aNumberedPacketGoesThroughTheReceiveBufferQuickly() throws Exception {
        InetAddress far = InetAddress.getLoopbackAddress();
        try (DatagramSocket socket = new DatagramSocket(0, far)) {
            UdpSender sender = new UdpSender(socket);
            ReceiverBuffer buffer = new ReceiverBuffer(sender, false, "k", "k");
            UdpPacketData connect = new UdpPacketData(UdpCommon.Connect, -1);
            connect.daos.writeByte(1);
            connect.daos.writeUTF("k");
            connect.daos.writeShort(1);
            byte[] c = connect.getData();
            buffer.addPacket(new DatagramPacket(c, c.length, far, 9));
            while (buffer.getNextPacket() != null) {
                // the Connect
            }
            byte[] body = new byte[200]; // telemetry-sized
            int n = 100_000;
            int got = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                body[0] = UdpCommon.TelemetryData;
                short num = (short) (i % 32768);
                body[1] = (byte) (num >> 8);
                body[2] = (byte) num;
                buffer.addPacket(new DatagramPacket(body, body.length, far, 9));
                SavedPacket p = buffer.getNextPacket();
                if (p != null) got++;
            }
            double us = (System.nanoTime() - t0) / 1000.0 / n;
            System.out.printf("receive buffer: %.2f us a packet, %d of %d delivered%n", us, got, n);
            sender.close();
            assertTrue(got >= n - 2, "every packet in order delivered: " + got);
            assertTrue(us < 100, "a packet through the buffer took " + us + " us");
        }
    }
}
