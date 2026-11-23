package de.droiddrone.pc.setup.ardu;

import com.MAVLink.MAVLinkPacket;
import com.MAVLink.Parser;
import com.MAVLink.common.msg_command_ack;
import com.MAVLink.common.msg_param_request_read;
import com.MAVLink.minimal.msg_heartbeat;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The PC's MAVLink session at its edges: whose heartbeat it takes, its sequence numbers' wraparound, MAVLink 2's
 * trimmed payloads, listeners added and removed; and TunnelWire's queue and packets larger than a read.
 */
class MavSessionEdgeTest {
    /** A wire the test feeds, and every frame the session wrote, parsed. */
    static final class TestWire implements MavSession.Wire {
        final BlockingQueue<byte[]> toSession = new LinkedBlockingQueue<>();
        final List<MAVLinkPacket> written = java.util.Collections.synchronizedList(new ArrayList<>());
        private final Parser parser = new Parser();

        @Override
        public void write(byte[] data) {
            synchronized (parser) {
                for (byte b : data) {
                    MAVLinkPacket p = parser.mavlink_parse_char(b & 0xff);
                    if (p != null) written.add(p);
                }
            }
        }

        @Override
        public int read(byte[] buf, int timeoutMs) {
            try {
                byte[] f = toSession.poll(timeoutMs, TimeUnit.MILLISECONDS);
                if (f == null) return 0;
                System.arraycopy(f, 0, buf, 0, f.length);
                return f.length;
            } catch (InterruptedException e) {
                return -1;
            }
        }

        @Override
        public void close() {
        }

        void feed(MAVLinkPacket p) {
            toSession.add(p.encodePacket());
        }
    }

    static MAVLinkPacket heartbeat(int type, int autopilot, int baseMode, long customMode, int sysId, int compId) {
        return new msg_heartbeat(customMode, (short) type, (short) autopilot, (short) baseMode, (short) 4, (short) 3, sysId, compId, true).pack();
    }

    /**
     * ArduPilot routes the heartbeats of what hangs on its other ports to USB. A gimbal's (MAV_AUTOPILOT_INVALID, base
     * mode 0, component 154) was taken as the FC's: DISARMED, STABILIZE, and every command went to component 154.
     */
    @Test
    void aGimbalsHeartbeatIsNotTheFlightControllers() throws Exception {
        TestWire w = new TestWire();
        try (MavSession s = new MavSession(w, false)) {
            w.feed(heartbeat(2, 3, 128 | 1, 5, 7, 1)); // the FC: armed, LOITER
            de.droiddrone.testing.Waits.waitFor(() -> s.fcSysId() == 7, 3000, "the FC's heartbeat");
            w.feed(heartbeat(26, 8, 0, 0, 7, 154)); // its gimbal
            w.feed(heartbeat(27, 8, 0, 0, 9, 156)); // an ADS-B receiver
            w.feed(heartbeat(6, 8, 0, 0, 255, 190)); // another ground station
            Thread.sleep(200);
            assertTrue(s.armedFlag(), "still armed");
            assertEquals(5, s.customMode(), "still LOITER");
            assertEquals(7, s.fcSysId());
            s.send(new msg_param_request_read((short) -1, (short) 0, (short) 0, MavSession.id16("RTL_ALT"), 0, 0, true).pack());
            // what the session addresses itself
            s.readParam("RTL_ALT", 100);
            MAVLinkPacket last = w.written.get(w.written.size() - 1);
            msg_param_request_read r = new msg_param_request_read(MavSession.pad(last));
            assertEquals(7, r.target_system);
            assertEquals(1, r.target_component, "the FC's component, not the gimbal's");
        }
    }

    @Test
    void sequenceNumbersWrapRoundAt256() throws Exception {
        TestWire w = new TestWire();
        try (MavSession s = new MavSession(w, false)) {
            for (int i = 0; i < 600; i++) s.send(heartbeat(6, 8, 0, 0, 255, 190));
        }
        assertEquals(600, w.written.size());
        for (int i = 0; i < 600; i++) assertEquals(i & 0xff, w.written.get(i).seq, "frame " + i);
    }

    /** A MAVLink 2 COMMAND_ACK with its trailing zeros left out (accepted, progress 0 ...) reads as the whole message. */
    @Test
    void aTrimmedPayloadReadsAsZeros() throws Exception {
        TestWire w = new TestWire();
        try (MavSession s = new MavSession(w, false)) {
            w.feed(heartbeat(2, 3, 1, 0, 1, 1));
            de.droiddrone.testing.Waits.waitFor(() -> s.fcSysId() == 1, 3000, "the FC's heartbeat");
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
                MAVLinkPacket ack = new msg_command_ack(176, (short) 0, (short) 0, 0, (short) 0, (short) 0, 1, 1, true).pack();
                w.feed(ack);
            });
            t.start();
            byte[] raw = new msg_command_ack(176, (short) 0, (short) 0, 0, (short) 0, (short) 0, 1, 1, true).pack().encodePacket();
            assertTrue((raw[1] & 0xff) < 10, "trimmed: " + (raw[1] & 0xff) + " payload bytes");
            assertEquals(0, s.command(176, 1, 5, 0, 0, 0, 0, 0, 2000), "MAV_RESULT_ACCEPTED");
        }
    }

    @Test
    void listenersAreRemovedAfterEachCall() throws Exception {
        TestWire w = new TestWire();
        try (MavSession s = new MavSession(w, false)) {
            java.lang.reflect.Field f = MavSession.class.getDeclaredField("listeners");
            f.setAccessible(true);
            List<?> l = (List<?>) f.get(s);
            assertEquals(0, l.size());
            assertEquals(-1, s.command(176, 1, 5, 0, 0, 0, 0, 0, 50));
            assertTrue(Float.isNaN(s.readParam("X", 50)));
            assertThrows(IOException.class, () -> s.setParam("X", 1, 20));
            assertThrows(IOException.class, () -> s.readAllParams(50));
            assertEquals(0, l.size(), "none left behind by a call that timed out or threw");
        }
    }

    /** TunnelWire: a packet larger than the reader's buffer was cut, its tail lost; the queue is capped. */
    @Test
    void tunnelWireGivesALargePacketInPartsAndCapsItsQueue() throws Exception {
        ControlLink link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), 9, 0);
        try {
            TunnelWire t = new TunnelWire(link);
            java.lang.reflect.Field f = ControlLink.class.getDeclaredField("tunnelListeners");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Consumer<byte[]>> ls = (List<Consumer<byte[]>>) f.get(link);
            assertEquals(1, ls.size());
            byte[] big = new byte[700];
            for (int i = 0; i < big.length; i++) big[i] = (byte) i;
            ls.get(0).accept(big);
            byte[] buf = new byte[256];
            byte[] got = new byte[700];
            int at = 0;
            while (at < 700) {
                int n = t.read(buf, 100);
                assertTrue(n > 0);
                System.arraycopy(buf, 0, got, at, n);
                at += n;
            }
            assertArrayEquals(big, got);
            assertEquals(0, t.read(buf, 10), "nothing more");
            for (int i = 0; i < TunnelWire.MAX_QUEUED + 100; i++) ls.get(0).accept(new byte[]{(byte) i, (byte) (i >> 8)});
            int first = t.read(buf, 10);
            assertEquals(2, first);
            assertEquals(100, (buf[0] & 0xff) | (buf[1] & 0xff) << 8, "the oldest were dropped");
            t.close();
            assertEquals(0, ls.size(), "the listener goes with the wire");
            assertEquals(-1, t.read(buf, 10));
            assertThrows(IOException.class, () -> t.write(new byte[]{1}));
        } finally {
            link.close();
        }
    }
}
