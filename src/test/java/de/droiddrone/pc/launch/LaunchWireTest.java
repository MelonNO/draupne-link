package de.droiddrone.pc.launch;

import de.droiddrone.common.launch.LaunchSequence;
import de.droiddrone.common.launch.LaunchWire;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet PRD area F on the wire: the LAUNCH order and the launch status, both ways, and what garbage does (nothing). */
class LaunchWireTest {
    @Test
    void anOrderComesBackAsItWent() {
        LaunchWire.Order o = LaunchSequenceTest.order(123456, 65535);
        o.joinLayer = 2;
        o.joinCell = 7;
        o.minBatteryPercent = 45;
        byte[] b = LaunchWire.encode(o);
        assertEquals(LaunchWire.ORDER_SIZE, b.length);
        LaunchWire.Order d = LaunchWire.decodeOrder(b, 0, b.length);
        assertNotNull(d);
        assertEquals(LaunchWire.KIND_LAUNCH, d.kind);
        assertEquals(123456, d.markId);
        assertEquals(65535, d.callId);
        assertEquals(60, d.approachHeightM, 1e-9);
        assertEquals(40, d.floorM, 1e-9);
        assertEquals(o.columnLat, d.columnLat, 1e-7);
        assertEquals(o.columnLon, d.columnLon, 1e-7);
        assertEquals(3, d.leadSysId);
        assertEquals(2, d.joinLayer);
        assertEquals(7, d.joinCell);
        assertEquals(45, d.minBatteryPercent);

        byte[] in = new byte[b.length + 5];
        System.arraycopy(b, 0, in, 3, b.length);
        assertNotNull(LaunchWire.decodeOrder(in, 3, b.length), "inside a bigger packet");

        LaunchWire.Order c = LaunchSequenceTest.cancel(9);
        LaunchWire.Order dc = LaunchWire.decodeOrder(LaunchWire.encode(c), 0, LaunchWire.ORDER_SIZE);
        assertEquals(LaunchWire.KIND_CANCEL, dc.kind);
        assertEquals(9, dc.callId);
    }

    @Test
    void ordersThatMakeNoSenseAreNotOrders() {
        LaunchWire.Order o = LaunchSequenceTest.order(5, 1);
        o.approachHeightM = 30; // under the 40 m floor
        assertNull(LaunchWire.decodeOrder(LaunchWire.encode(o), 0, LaunchWire.ORDER_SIZE));
        o = LaunchSequenceTest.order(0, 1);
        assertNull(LaunchWire.decodeOrder(LaunchWire.encode(o), 0, LaunchWire.ORDER_SIZE), "no mark id");
        o = LaunchSequenceTest.order(5, 1);
        o.floorM = 0;
        assertNull(LaunchWire.decodeOrder(LaunchWire.encode(o), 0, LaunchWire.ORDER_SIZE));
        byte[] b = LaunchWire.encode(LaunchSequenceTest.order(5, 1));
        b[6] ^= 1;
        assertNull(LaunchWire.decodeOrder(b, 0, b.length), "one bit flipped: the CRC");
        b = LaunchWire.encode(LaunchSequenceTest.order(5, 1));
        assertNull(LaunchWire.decodeOrder(b, 0, b.length - 1), "short");
        assertNull(LaunchWire.decodeOrder(null, 0, 0));
        assertNull(LaunchWire.decodeOrder(b, 3, b.length), "past the end");
    }

    @Test
    void aStatusComesBackAsItWent() {
        LaunchWire.Status s = new LaunchWire.Status();
        s.callId = 300;
        s.markId = -5;
        s.state = LaunchSequence.CLIMBING;
        s.reason = 24;
        s.heightM = 37.4;
        s.climbMps = -0.35;
        s.passedFloor = true;
        s.connected = true;
        s.armed = true;
        LaunchWire.Status d = LaunchWire.decodeStatus(LaunchWire.encode(s), 0, LaunchWire.STATUS_SIZE);
        assertNotNull(d);
        assertEquals(300, d.callId);
        assertEquals(-5, d.markId);
        assertEquals(LaunchSequence.CLIMBING, d.state);
        assertEquals(24, d.reason);
        assertEquals(37.4, d.heightM, 1e-9);
        assertEquals(-0.35, d.climbMps, 1e-9);
        assertTrue(d.passedFloor && d.connected && d.armed);
        assertFalse(d.markValid);
    }

    @Test
    void garbageNeverThrowsAndAlmostNeverDecodes() {
        Random rnd = new Random(7);
        int orders = 0, statuses = 0;
        for (int i = 0; i < 200_000; i++) {
            byte[] b = new byte[rnd.nextInt(40)];
            rnd.nextBytes(b);
            if (rnd.nextBoolean() && b.length > 0) b[0] = 1; // the right version byte half the time
            int off = b.length == 0 ? 0 : rnd.nextInt(b.length);
            if (LaunchWire.decodeOrder(b, off, b.length - off) != null) orders++;
            if (LaunchWire.decodeStatus(b, off, b.length - off) != null) statuses++;
        }
        assertTrue(orders < 200, "a CRC-8 lets about 1 in 256 through, the order's own checks fewer: " + orders);
        assertTrue(statuses < 500, "" + statuses);
    }
}
