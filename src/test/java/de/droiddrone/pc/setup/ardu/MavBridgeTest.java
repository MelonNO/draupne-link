package de.droiddrone.pc.setup.ardu;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet AP-11: the drone's answers to a local program, the program's frames to the tunnel, the tunnel kept open. */
class MavBridgeTest {
    @Test
    void framesGoBothWays() throws Exception {
        List<Consumer<byte[]>> listeners = new CopyOnWriteArrayList<>();
        List<byte[]> toDrone = new CopyOnWriteArrayList<>();
        MavBridge.Tunnel t = new MavBridge.Tunnel() {
            public void addListener(Consumer<byte[]> l) {
                listeners.add(l);
            }

            public void removeListener(Consumer<byte[]> l) {
                listeners.remove(l);
            }

            public boolean send(byte[] frames) {
                toDrone.add(frames);
                return true;
            }
        };
        try (DatagramSocket program = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            int inPort;
            try (DatagramSocket probe = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                inPort = probe.getLocalPort();
            }
            try (MavBridge b = new MavBridge(t, inPort, program.getLocalPort())) {
                Thread.sleep(200);
                assertTrue(toDrone.size() >= 1, "the keep-open ask went at once");
                byte[] fc = {(byte) 0xFD, 9, 0, 0, 1, 1, 1, 0, 0, 0};
                for (Consumer<byte[]> l : listeners) l.accept(fc);
                program.setSoTimeout(2000);
                DatagramPacket p = new DatagramPacket(new byte[64], 64);
                program.receive(p);
                assertEquals(fc.length, p.getLength(), "the FC's frames to the program");
                byte[] mp = {(byte) 0xFD, 1, 2, 3};
                program.send(new DatagramPacket(mp, mp.length, new InetSocketAddress(InetAddress.getLoopbackAddress(), inPort)));
                long end = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < end && toDrone.stream().noneMatch(x -> x.length == 4)) Thread.sleep(20);
                assertTrue(toDrone.stream().anyMatch(x -> x.length == 4), "the program's frames to the tunnel (the phone filters)");
            }
            assertTrue(listeners.isEmpty(), "closed: no longer listening");
        }
    }
}
