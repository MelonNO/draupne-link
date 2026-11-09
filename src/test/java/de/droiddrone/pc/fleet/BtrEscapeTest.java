package de.droiddrone.pc.fleet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BtrEscapeTest {
    private static double dist(double[] q, double n, double e) {
        return Math.hypot(q[0] - n, q[1] - e);
    }

    @Test
    void onTheGroundItKeepsItsPatrol() {
        BtrScene b = new BtrScene(0, 0, 0, 0);
        double[] before = b.pose(20_000);
        for (long t = 0; t <= 20_000; t += 100) b.threat(0, 0, false, t);
        assertFalse(b.escaping());
        assertArrayEquals(before, b.pose(20_000), 1e-9, "a drone on the ground changes nothing");
    }

    @Test
    void aDroneInTheAirMakesItFleeInSeveralDirectionsThenItCalmsDown() {
        BtrScene b = new BtrScene(0, 0, 0, 0);
        long t0 = 10_000;
        double[] start = b.pose(t0);
        double startDist = dist(start, 0, 0);
        int turnsLeft = 0, turnsRight = 0;
        double lastHeading = start[2], turning = 0;
        int sideChanges = 0;
        double maxFromStart = 0, maxSpeed = 0, minSpeedMoving = 99;
        // the drone hovers over its start point for 60 s
        for (long t = t0; t <= t0 + 60_000; t += 100) {
            b.threat(0, 0, true, t);
            double[] q = b.pose(t);
            double d = Math.atan2(Math.sin(Math.toRadians(q[2] - lastHeading)), Math.cos(Math.toRadians(q[2] - lastHeading)));
            if (Math.abs(d) > 1e-3) {
                double side = Math.signum(d);
                if (turning != 0 && side != turning) sideChanges++;
                turning = side;
                if (side > 0) turnsRight++; else turnsLeft++;
            }
            lastHeading = q[2];
            maxFromStart = Math.max(maxFromStart, dist(q, 0, 0));
            double v = b.speedAt(t);
            maxSpeed = Math.max(maxSpeed, v);
            if (t > t0 + 5000) minSpeedMoving = Math.min(minSpeedMoving, v);
        }
        assertTrue(b.escaping());
        double[] end = b.pose(t0 + 60_000);
        assertTrue(dist(end, 0, 0) > startDist + 80, "it got away: " + startDist + " -> " + dist(end, 0, 0));
        assertTrue(turnsLeft > 20 && turnsRight > 20, "it turned both ways: left " + turnsLeft + ", right " + turnsRight);
        assertTrue(sideChanges >= 6, "a zig-zag, not one curve: " + sideChanges + " changes of side");
        assertTrue(maxFromStart <= BtrScene.ESCAPE_RANGE_M * 1.05, "it stays in the area: " + maxFromStart);
        assertTrue(maxSpeed >= 9.5, "a dash now and then: " + maxSpeed);
        assertTrue(minSpeedMoving >= 4, "it keeps going while chased: " + minSpeedMoving);

        // the drone down: it slows to a stop where it is
        long down = t0 + 60_000;
        for (long t = down; t <= down + 20_000; t += 100) b.threat(0, 0, false, t);
        assertEquals(0, b.speedAt(down + 20_000), 1e-6, "calm again");
        double[] stopped = b.pose(down + 20_000);
        assertEquals(stopped[0], b.pose(down + 25_000)[0], 1e-6);
    }

    @Test
    void chasedForMinutesItNeverLeavesTheArea() {
        BtrScene b = new BtrScene(0, 0, 0, 0);
        double dn = 0, de = 0;
        for (long t = 10_000; t <= 190_000; t += 100) {
            double[] q = b.pose(t);
            // the drone flies at it at 15 m/s
            double toN = q[0] - dn, toE = q[1] - de, d = Math.hypot(toN, toE);
            if (d > 3) {
                dn += toN / d * 1.5;
                de += toE / d * 1.5;
            }
            b.threat(dn, de, true, t);
            assertTrue(dist(b.pose(t), 0, 0) <= BtrScene.ESCAPE_RANGE_M * 1.05, "out of the area at " + t + ": " + dist(b.pose(t), 0, 0));
        }
    }
}
