package de.droiddrone.common;

import de.droiddrone.common.launch.LaunchSequence;
import de.droiddrone.common.launch.LaunchWire;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fleet's wire formats under garbage and at their edges: every new packet's reader is fed random bytes, cut-short
 * and over-long packets, lengths that claim more than the array holds, and wrong type bytes - none may throw, loop or
 * allocate absurdly - and every valid value at the edges must round-trip exactly.
 */
class FleetWireFuzzTest {
    static final int ROUNDS = 20_000;

    /** Every reader, each on (data, claimed length). None may throw. */
    static void readAll(byte[] d, int len) {
        FcCommand.read(d, len);
        FleetCmd.read(d, len);
        FleetStatus.decode(d, len);
        MissionBlock.read(d, 1, len - 1);
        MissionBlock.read(d, 0, len);
        MissionItem.unpack(d, 0, len);
        MavTunnel.split(d, 0, len, (f, o, l) -> {
            MavTunnel.msgId(f, o, l);
            MavTunnel.toFc(f, o, l);
        });
        MavTunnel.msgId(d, 0, len);
        LaunchWire.decodeOrder(d, 0, len);
        LaunchWire.decodeStatus(d, 0, len);
        LaunchWire.decodeOrder(d, 1, len - 1);
        new MavlinkSniff().feed(d, len);
        FleetCmd c = FleetCmd.read(d, len);
        if (c != null) {
            assertTrue(c.at + c.length <= d.length, "the inner packet lies inside the array");
            FleetCmd.readClear(c);
            FleetCmd.readLeadHeight(c);
        }
    }

    @Test
    void randomBytesNeverThrow() {
        Random r = new Random(7);
        byte[] firsts = {FcCommand.PACKET, MavTunnel.PACKET, MissionBlock.PACKET, FleetCmd.PACKET, FleetStatus.PACKET,
                FleetCmd.LAUNCH_STATUS, (byte) 0xFE, (byte) 0xFD, 1, 0};
        for (int i = 0; i < ROUNDS; i++) {
            byte[] d = new byte[r.nextInt(80)];
            r.nextBytes(d);
            if (d.length > 0 && r.nextBoolean()) d[0] = firsts[r.nextInt(firsts.length)];
            int len = d.length;
            readAll(d, len);
            readAll(d, len + 1 + r.nextInt(5000)); // a length that lies: more than the array holds
            if (len > 0) readAll(d, r.nextInt(len)); // cut short
        }
    }

    @Test
    void validPacketsCutShortOrOverLongNeverThrow() {
        List<byte[]> valid = validPackets();
        for (byte[] p : valid) {
            for (int len = 0; len <= p.length + 3; len++) readAll(p, len);
            byte[] longer = Arrays.copyOf(p, p.length + 50);
            readAll(longer, longer.length);
            // every single byte flipped
            for (int i = 0; i < p.length; i++) {
                byte[] f = p.clone();
                f[i] ^= (byte) 0xA5;
                readAll(f, f.length);
            }
        }
    }

    static List<byte[]> validPackets() {
        List<byte[]> v = new ArrayList<>();
        v.add(FcCommand.mode(5));
        v.add(FcCommand.simple(FcCommand.PAUSE));
        v.add(FcCommand.goLook(59.9, 10.7));
        v.add(MissionBlock.packet(3, MissionUpload.TYPE_FENCE, route(4)));
        v.add(MissionBlock.packet(65535, MissionUpload.TYPE_RALLY, new ArrayList<>()));
        v.add(FleetCmd.wrap("k", FleetCmd.clear(59.9, 10.7, 120)));
        v.add(FleetCmd.wrap("key", FleetCmd.lead(true, 80)));
        v.add(FleetCmd.wrap("key", MissionBlock.packet(1, route(2))));
        FleetStatus s = new FleetStatus();
        s.lat = 59.9;
        s.lon = 10.7;
        v.add(s.encode());
        LaunchWire.Order o = new LaunchWire.Order();
        o.markId = 9;
        o.floorM = 30;
        o.approachHeightM = 40;
        v.add(LaunchWire.encode(o));
        return v;
    }

    static List<MissionItem> route(int n) {
        List<MissionItem> r = new ArrayList<>();
        for (int i = 0; i < n; i++) r.add(MissionItem.at(i, MissionItem.NAV_WAYPOINT, 59.9 + i * 1e-4, 10.7, 100 + i));
        return r;
    }

    @Test
    void aMissionItemRoundTripsEveryEdgeValue() {
        float[] floats = {0f, -0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE, -Float.MAX_VALUE,
                Float.MIN_VALUE, 1e-30f, -12345.678f};
        int[] ints = {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 900_000_000, -1_800_000_000};
        int[] u8 = {0, 1, 127, 128, 255};
        int[] u16 = {0, 1, 255, 256, 32767, 32768, 65535};
        List<MissionItem> items = new ArrayList<>();
        int seq = 0;
        for (float f : floats) for (int x : ints) items.add(new MissionItem(seq++, u8[seq % u8.length], u16[seq % u16.length], seq % 2 == 0, f, -f, f, f, x, -x, f));
        byte[] packed = MissionItem.pack(items);
        assertEquals(2 + items.size() * MissionItem.PACKED_SIZE, packed.length);
        List<MissionItem> back = MissionItem.unpack(packed, 0, packed.length);
        assertNotNull(back);
        assertEquals(items.size(), back.size());
        for (int i = 0; i < items.size(); i++) {
            MissionItem a = items.get(i), b = back.get(i);
            assertEquals(i, b.seq);
            assertEquals(a.frame, b.frame);
            assertEquals(a.command, b.command);
            assertEquals(a.autocontinue, b.autocontinue);
            assertEquals(Float.floatToIntBits(a.param1), Float.floatToIntBits(b.param1));
            assertEquals(Float.floatToIntBits(a.param2), Float.floatToIntBits(b.param2));
            assertEquals(Float.floatToIntBits(a.param4), Float.floatToIntBits(b.param4));
            assertEquals(a.x, b.x);
            assertEquals(a.y, b.y);
            assertEquals(Float.floatToIntBits(a.z), Float.floatToIntBits(b.z));
        }
    }

    @Test
    void aMissionBlockWhoseCountDisagreesWithItsLengthIsRefused() {
        byte[] p = MissionBlock.packet(5, route(3));
        assertNotNull(MissionBlock.read(p, 1, p.length - 1));
        // one item's bytes more than the count says: a route cut to fewer points than were sent must never be flown
        byte[] longer = MissionBlock.packet(5, route(4));
        longer[14] = 3; // items in the part: 3 claimed, 4 sent
        assertNull(MissionBlock.read(longer, 1, longer.length - 1), "more bytes than the count");
        assertNull(MissionItem.unpack(Arrays.copyOf(MissionItem.pack(route(2)), 2 + 2 * MissionItem.PACKED_SIZE + 1), 0,
                2 + 2 * MissionItem.PACKED_SIZE + 1), "a stray byte after the last item");
        assertNull(MissionBlock.read(p, 1, p.length - 2), "cut short");
        assertNull(MissionBlock.read(null, 0, 10));
        assertNull(MissionItem.unpack(null, 0, 10));
        // the most a block may claim is refused without allocating it
        byte[] huge = {MissionBlock.PACKET, 0, 1, 0, (byte) 0xff, (byte) 0xff};
        assertNull(MissionBlock.read(huge, 1, 5));
        // a wrong mission type
        byte[] t = MissionBlock.packet(5, route(1));
        t[3] = 3;
        assertNull(MissionBlock.read(t, 1, t.length - 1));
        // all three types round-trip; an empty fence / rally list (clearing it) is a block, an empty route is not
        for (int type = 0; type <= 2; type++) {
            byte[] b = MissionBlock.packet(65535, type, route(2));
            MissionBlock m = MissionBlock.read(b, 1, b.length - 1);
            assertEquals(65535, m.blockId);
            assertEquals(type, m.missionType);
            assertEquals(2, m.items.size());
        }
        byte[] emptyRoute = MissionBlock.packet(1, MissionUpload.TYPE_MISSION, new ArrayList<>());
        assertNull(MissionBlock.read(emptyRoute, 1, emptyRoute.length - 1));
        byte[] emptyFence = MissionBlock.packet(1, MissionUpload.TYPE_FENCE, new ArrayList<>());
        assertEquals(0, MissionBlock.read(emptyFence, 1, emptyFence.length - 1).items.size());
    }

    @Test
    void writersNeverWrapAHugeOrNaNPlaceIntoAnotherRealOne() {
        // lon 400 * 1e7 does not fit an int: the cast wrapped it to -29.5 degrees, a real place
        assertNull(FcCommand.read(FcCommand.goLook(10, 400), 10));
        assertNull(FcCommand.read(FcCommand.goLook(Double.NaN, 10), 10));
        assertNull(FcCommand.read(FcCommand.goLook(10, Double.POSITIVE_INFINITY), 10));
        FcCommand edge = FcCommand.read(FcCommand.goLook(-90, 180), 10);
        assertEquals(-90, edge.lat, 1e-9);
        assertEquals(180, edge.lon, 1e-9);
        // FleetCmd.clear: nonsense is sent as no place, and the phone refuses it
        assertNull(FleetCmd.readClear(FleetCmd.read(FleetCmd.wrap("k", FleetCmd.clear(Double.NaN, Double.NaN, 100)), 100)));
        assertNull(FleetCmd.readClear(FleetCmd.read(FleetCmd.wrap("k", FleetCmd.clear(10, 400, 100)), 100)));
        assertNull(FleetCmd.readClear(FleetCmd.read(FleetCmd.wrap("k", FleetCmd.clear(0, 0, 100)), 100)));
        assertNull(FleetCmd.readClear(FleetCmd.read(FleetCmd.wrap("k", FleetCmd.clear(59.9, 10.7, Double.NaN)), 100)), "no height");
        double[] c = FleetCmd.readClear(FleetCmd.read(FleetCmd.wrap("k", FleetCmd.clear(59.9, 10.7, 123.4)), 100));
        assertArrayEquals(new double[]{59.9, 10.7, 123.4}, c, 1e-7);
        // FleetStatus: heights past an int's decimetres saturate instead of wrapping to the other sign
        FleetStatus s = new FleetStatus();
        s.lat = 59.9;
        s.lon = 10.7;
        s.altAmsl = 1e12;
        s.homeAmsl = -1e12;
        s.velE = Double.NaN;
        s.battery = 255;
        FleetStatus b = FleetStatus.decode(s.encode(), FleetStatus.BYTES);
        assertTrue(b.altAmsl > 1e8, "saturated high, not wrapped: " + b.altAmsl);
        assertTrue(b.homeAmsl < -1e8, "saturated low, not wrapped: " + b.homeAmsl);
        assertEquals(255, b.battery, "battery not known stays not known");
        assertEquals(0, b.velE);
        // nonsense in FleetStatus' own place is no report
        s.lat = 400;
        assertNull(FleetStatus.decode(s.encode(), FleetStatus.BYTES));
    }

    @Test
    void fleetStatusRoundTripsItsEdges() {
        FleetStatus s = new FleetStatus();
        s.lat = -90;
        s.lon = 180;
        s.altAmsl = -429.9;
        s.velE = 327.67;
        s.velN = -327.68;
        s.velU = 0.01;
        s.battery = 0;
        s.flags = 0xff;
        s.mode = 254;
        s.phase = 255;
        s.errM = 6553.5;
        s.homeAmsl = 8848.8;
        s.routeBlock = 65535;
        s.routeState = 4;
        s.routePercent = 100;
        byte[] e = s.encode();
        assertEquals(FleetStatus.WITH_RTL_BYTES, e.length);
        FleetStatus b = FleetStatus.decode(e, e.length);
        assertEquals(-90, b.lat, 1e-9);
        assertEquals(180, b.lon, 1e-9);
        assertEquals(-429.9, b.altAmsl, 1e-9);
        assertEquals(327.67, b.velE, 1e-9);
        assertEquals(-327.68, b.velN, 1e-9);
        assertEquals(0, b.battery);
        assertEquals(0xff, b.flags);
        assertEquals(254, b.mode);
        assertEquals(255, b.phase);
        assertEquals(6553.5, b.errM, 1e-9);
        assertEquals(8848.8, b.homeAmsl, 1e-9);
        assertEquals(65535, b.routeBlock);
        assertEquals(4, b.routeState);
        assertEquals(100, b.routePercent);
        // another version, cut short, the wrong type byte
        e[1] = 2;
        assertNull(FleetStatus.decode(e, e.length));
        e[1] = 1;
        assertNull(FleetStatus.decode(e, FleetStatus.BYTES - 1));
        assertEquals(-1, FleetStatus.decode(e, e.length - 1).rtlAltDm, "RTL_ALT cut short: not known");
        e[0] = 0x39;
        assertNull(FleetStatus.decode(e, e.length));
    }

    @Test
    void fleetCmdKeyEdges() {
        String k64 = "k".repeat(64);
        FleetCmd c = FleetCmd.read(FleetCmd.wrap(k64, FleetCmd.disband()), 200);
        assertEquals(1, c.length, "a length claimed past the array is cut to it: 2 + 64 + 1 bytes");
        byte[] p = FleetCmd.wrap(k64, FleetCmd.disband());
        c = FleetCmd.read(p, p.length);
        assertEquals(k64, c.key);
        assertEquals(FleetCmd.DISBAND, c.inner());
        assertEquals(1, c.length);
        assertThrows(IllegalArgumentException.class, () -> FleetCmd.wrap("k".repeat(65), FleetCmd.disband()));
        assertThrows(IllegalArgumentException.class, () -> FleetCmd.wrap("", FleetCmd.disband()), "an empty key is never read");
        // the key's length byte past the packet
        assertNull(FleetCmd.read(new byte[]{FleetCmd.PACKET, 10, 1, 2}, 4));
        // LEAD: heights saturate, NaN and negative are 0 (RTL_ALT as it is)
        assertEquals(6553.5, FleetCmd.readLeadHeight(FleetCmd.read(FleetCmd.wrap("k", FleetCmd.lead(true, 1e9)), 100)), 1e-9);
        assertEquals(0, FleetCmd.readLeadHeight(FleetCmd.read(FleetCmd.wrap("k", FleetCmd.lead(true, Double.NaN)), 100)));
        assertEquals(0, FleetCmd.readLeadHeight(FleetCmd.read(FleetCmd.wrap("k", FleetCmd.lead(true, -5)), 100)));
        assertEquals(-1, FleetCmd.readLeadHeight(FleetCmd.read(FleetCmd.wrap("k", new byte[]{FleetCmd.LEAD, 1, 0}), 100)), "cut short");
    }

    @Test
    void launchWireRoundTripsAndRefusesGarbage() {
        LaunchWire.Order o = new LaunchWire.Order();
        o.kind = LaunchWire.KIND_LAUNCH;
        o.markId = Integer.MIN_VALUE;
        o.callId = 65535;
        o.floorM = 0.1;
        o.approachHeightM = 6553.5;
        o.columnLat = -90;
        o.columnLon = 180;
        o.leadSysId = 255;
        o.joinLayer = 0;
        o.joinCell = 254;
        o.minBatteryPercent = 100;
        byte[] e = LaunchWire.encode(o);
        LaunchWire.Order b = LaunchWire.decodeOrder(e, 0, e.length);
        assertEquals(Integer.MIN_VALUE, b.markId);
        assertEquals(65535, b.callId);
        assertEquals(0.1, b.floorM, 1e-9);
        assertEquals(6553.5, b.approachHeightM, 1e-9);
        assertEquals(-90, b.columnLat, 1e-9);
        assertEquals(180, b.columnLon, 1e-9);
        assertEquals(255, b.leadSysId);
        assertEquals(254, b.joinCell);
        assertEquals(100, b.minBatteryPercent);
        // NaN / huge heights and places: no order that climbs nowhere or launches toward another real place
        o.floorM = Double.NaN;
        assertNull(LaunchWire.decodeOrder(LaunchWire.encode(o), 0, LaunchWire.ORDER_SIZE));
        o.floorM = 30;
        o.approachHeightM = 40;
        o.columnLon = 400;
        assertTrue(Double.isNaN(LaunchWire.decodeOrder(LaunchWire.encode(o), 0, LaunchWire.ORDER_SIZE).columnLon), "lon 400 must not wrap to -29.5: not known");
        o.columnLon = Double.NaN;
        assertTrue(Double.isNaN(LaunchWire.decodeOrder(LaunchWire.encode(o), 0, LaunchWire.ORDER_SIZE).columnLon), "no column: not known (0 is Greenwich)");
        LaunchWire.Status s = new LaunchWire.Status();
        s.callId = 65535;
        s.markId = -1;
        s.state = LaunchSequence.LAST_STATE;
        s.reason = 255;
        s.heightM = 1e9;
        s.climbMps = Double.NaN;
        s.passedFloor = s.markValid = s.connected = s.armed = true;
        LaunchWire.Status t = LaunchWire.decodeStatus(LaunchWire.encode(s), 0, LaunchWire.STATUS_SIZE);
        assertEquals(65535, t.callId);
        assertEquals(-1, t.markId);
        assertEquals(LaunchSequence.LAST_STATE, t.state);
        assertEquals(3276.7, t.heightM, 1e-9);
        assertEquals(0, t.climbMps);
        assertTrue(t.passedFloor && t.markValid && t.connected && t.armed);
        // a single flipped bit anywhere is caught by the CRC
        byte[] good = LaunchWire.encode(s);
        for (int i = 0; i < good.length; i++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] f = good.clone();
                f[i] ^= (byte) (1 << bit);
                assertNull(LaunchWire.decodeStatus(f, 0, f.length), "byte " + i + " bit " + bit);
            }
        }
        assertNull(LaunchWire.decodeOrder(e, -1, e.length));
        assertNull(LaunchWire.decodeOrder(e, 1, e.length));
    }

    @Test
    void theAckAndStatusRecordsRoundTrip() {
        byte[] a = FcCommand.ack(65535, 255);
        assertEquals(3, a.length);
        assertEquals(65535, (a[0] & 0xff) << 8 | (a[1] & 0xff));
        assertEquals(255, a[2] & 0xff);
        byte[] s = MissionBlock.status(65535, 4, 250, "x".repeat(300));
        assertEquals(5 + 120, s.length, "the reason is cut to 120");
        assertEquals(100, s[3] & 0xff, "percent capped");
        assertEquals(120, s[4] & 0xff);
        byte[] n = MissionBlock.status(1, 3, -5, null);
        assertEquals(5, n.length);
        assertEquals(0, n[3]);
        byte[] u = MissionBlock.status(1, 4, 0, "æøå ✓");
        assertEquals(u.length - 5, u[4] & 0xff, "a reason that isn't ASCII still says its own length");
    }

    @Test
    void theSniffNeverReadsPastTheArray() {
        MavlinkSniff s = new MavlinkSniff();
        byte[] b = {(byte) 0xFE, 9};
        assertFalse(s.feed(b, 100));
    }
}
