package de.droiddrone.pc.air;

import de.droiddrone.common.FleetCmd;
import de.droiddrone.common.group.Traffic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet PRD FD-8 / AC-11: TRAFFIC (FleetCmd 0x3F, group.Traffic) on the wire - the other drones as ADS-B targets. */
class TrafficCodecTest {

    @Test
    void eightDronesGoThereAndBack() {
        List<Traffic.Entry> in = new ArrayList<>();
        for (int i = 0; i < Traffic.MAX; i++) {
            in.add(Traffic.Entry.of(Traffic.address("F" + i), 60.0 + i * 1e-4, -10.5 - i * 1e-4, -20 + 37.3 * i,
                    i - 3.5, 2.25 - i, i % 2 == 0 ? -1.5 : 2.5, i % 4, i * 30));
        }
        byte[] bytes = new Traffic(in).encode();
        assertEquals(2 + 8 * Traffic.ENTRY_BYTES, bytes.length, "194 bytes at most");
        byte[] wire = FleetCmd.wrap("DD", FleetCmd.traffic(bytes));
        FleetCmd c = FleetCmd.read(wire, wire.length);
        assertNotNull(c);
        assertEquals(FleetCmd.TRAFFIC, c.inner());
        Traffic out = Traffic.decode(c.data, c.at + 1, c.length - 1);
        assertNotNull(out);
        assertEquals(in.size(), out.entries.size());
        for (int i = 0; i < in.size(); i++) {
            Traffic.Entry a = in.get(i), b = out.entries.get(i);
            assertEquals(a.address, b.address);
            assertEquals(a.lat, b.lat, 1e-7);
            assertEquals(a.lon, b.lon, 1e-7);
            assertEquals(a.altAmsl, b.altAmsl, 0.05);
            assertEquals(a.headingDeg, b.headingDeg, 0.01);
            assertEquals(a.horSpeedMs, b.horSpeedMs, 0.005);
            assertEquals(a.verSpeedMs, b.verSpeedMs, 0.005);
            assertEquals(a.flags, b.flags);
            assertEquals(Math.min(255, a.ageS), b.ageS);
        }
    }

    @Test
    void theCourseComesFromTheVelocity() {
        assertEquals(0, Traffic.Entry.of(1, 0, 0, 0, 0, 5, 0, 0, 0).headingDeg, 1e-9, "north");
        assertEquals(90, Traffic.Entry.of(1, 0, 0, 0, 5, 0, 0, 0, 0).headingDeg, 1e-9, "east");
        assertEquals(270, Traffic.Entry.of(1, 0, 0, 0, -5, 0, 0, 0, 0).headingDeg, 1e-9, "west");
        assertEquals(5 * Math.sqrt(2), Traffic.Entry.of(1, 0, 0, 0, -5, -5, 0, 0, 0).horSpeedMs, 1e-9);
        Traffic t = Traffic.decode(new Traffic(List.of(Traffic.Entry.of(1, 0, 0, 0, -1e-9, 3, 0, 0, 0))).encode(), 0, 2 + 24);
        assertNotNull(t);
        assertTrue(t.entries.get(0).headingDeg < 360, "359.99... wraps to 0, never 360");
    }

    @Test
    void badBytesAreRefused() {
        byte[] ok = new Traffic(List.of(Traffic.Entry.of(7, 1, 2, 3, 0, 0, 0, 0, 0))).encode();
        assertNotNull(Traffic.decode(ok, 0, ok.length));
        assertNull(Traffic.decode(ok, 0, ok.length - 1), "cut short");
        byte[] v = ok.clone();
        v[0] = 2;
        assertNull(Traffic.decode(v, 0, v.length), "another version");
        byte[] many = ok.clone();
        many[1] = 9;
        assertNull(Traffic.decode(many, 0, many.length), "more than 8");
        byte[] off = ok.clone();
        off[2 + 4] = (byte) 0x7f; // lat far off the globe
        assertNull(Traffic.decode(off, 0, off.length));
        List<Traffic.Entry> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) nine.add(Traffic.Entry.of(i, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Traffic(nine));
        byte[] none = new Traffic(List.of()).encode();
        assertEquals(0, Traffic.decode(none, 0, none.length).entries.size());
    }

    @Test
    void addressesAre24BitsSteadyAndApart() {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            long a = Traffic.address(String.format("F%02d", i));
            assertTrue(a > 0 && a < 0xffffffL, "24 bits, not 0 or all ones: " + a);
            assertEquals(a, Traffic.address(String.format("F%02d", i)), "the same every time");
            seen.add(a);
        }
        assertEquals(200, seen.size(), "200 names, 200 addresses");
        assertNotEquals(Traffic.address("Lead"), Traffic.address("Lead2"));
    }
}
