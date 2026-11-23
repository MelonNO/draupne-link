package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet PRD FD-1: the fleet command's wrapper, the drone's report and who may command a drone (FleetCommandGate). */
class FleetLinkCodecTest {
    @Test
    void commandsWrapAndUnwrap() {
        byte[] p = FleetCmd.wrap("DD", FleetCmd.track(20));
        FleetCmd c = FleetCmd.read(p, p.length);
        assertEquals("DD", c.key);
        assertEquals(FleetCmd.TRACK, c.inner());
        assertEquals(20, c.data[c.at + 1]);
        byte[] fc = FleetCmd.wrap("DD", FcCommand.mode(5));
        FleetCmd k = FleetCmd.read(fc, fc.length);
        assertEquals(5, FcCommand.read(java.util.Arrays.copyOfRange(k.data, k.at, k.at + k.length), k.length).mode);
        assertNull(FleetCmd.read(new byte[]{FleetCmd.PACKET, 5, 'D'}, 3), "cut short");
        assertNull(FleetCmd.read(new byte[]{FleetCmd.PACKET, 2, 'D', 'D'}, 4), "no inner packet");
        Random r = new Random(4);
        for (int i = 0; i < 1000; i++) {
            byte[] junk = new byte[r.nextInt(40)];
            r.nextBytes(junk);
            if (junk.length > 0) junk[0] = FleetCmd.PACKET;
            FleetCmd.read(junk, junk.length); // never throws
        }
    }

    @Test
    void reportsRoundTrip() {
        FleetStatus s = new FleetStatus();
        s.lat = 60.1234567;
        s.lon = 10.7654321;
        s.altAmsl = 312.4;
        s.velE = -3.25;
        s.velN = 12.5;
        s.velU = 1.5;
        s.battery = 64;
        s.flags = FleetStatus.ARMED | FleetStatus.GNSS;
        s.mode = 4;
        s.phase = 1;
        s.errM = 2.5;
        s.homeAmsl = 120.3;
        s.routeBlock = 777;
        s.routeState = 3;
        s.routePercent = 100;
        byte[] d = s.encode();
        assertEquals(FleetStatus.BYTES, d.length);
        FleetStatus b = FleetStatus.decode(d, d.length);
        assertEquals(60.1234567, b.lat, 1e-7);
        assertEquals(10.7654321, b.lon, 1e-7);
        assertEquals(312.4, b.altAmsl, 1e-9);
        assertEquals(-3.25, b.velE, 1e-9);
        assertEquals(64, b.battery);
        assertTrue(b.has(FleetStatus.ARMED) && b.has(FleetStatus.GNSS) && !b.has(FleetStatus.LANDED));
        assertEquals(4, b.mode);
        assertEquals(1, b.phase);
        assertEquals(777, b.routeBlock);
        assertNull(FleetStatus.decode(d, d.length - 1));
        d[1] = 9;
        assertNull(FleetStatus.decode(d, d.length), "another version");
    }

    @Test
    void onlyThePilotOrTheCommanderAndAnotherOnlyOnTheGround() {
        int A = FleetCommandGate.ACCEPT, R = FleetCommandGate.REFUSE, N = FleetCommandGate.NEW_COMMANDER;
        assertEquals(A, FleetCommandGate.decide(true, true, true, true, 0, false), "the pilot in its session, armed or not");
        assertEquals(R, FleetCommandGate.decide(true, false, true, true, 0, true), "in a session nobody else");
        assertEquals(A, FleetCommandGate.decide(false, false, true, true, 5000, false), "the commander, in the air");
        assertEquals(R, FleetCommandGate.decide(false, false, true, false, 60_000, false), "another PC never takes a drone in the air");
        assertEquals(R, FleetCommandGate.decide(false, false, true, false, 5000, true), "on the ground, the commander heard lately");
        assertEquals(N, FleetCommandGate.decide(false, false, true, false, 10_000, true), "on the ground, the commander silent 10 s");
        assertEquals(N, FleetCommandGate.decide(false, false, false, false, Long.MAX_VALUE, true), "no commander yet, on the ground");
        assertEquals(R, FleetCommandGate.decide(false, false, false, false, Long.MAX_VALUE, false), "no commander, not known disarmed");
    }
}
