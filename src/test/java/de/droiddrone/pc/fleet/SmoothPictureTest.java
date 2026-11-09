package de.droiddrone.pc.fleet;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The virtual picture moves smoothly (1.0.52, the pilot: "the BTR is very jittery"): a fleeing BTR between its 0.1 s
 * steps, the drone's camera between its model's ticks, every frame encoded, and frames kept evenly apart by the fixed
 * link delay.
 */
class SmoothPictureTest {
    private static final long FRAME_MS = 33;

    @Test
    void aFleeingBtrMovesSmoothlyFromPictureToPicture() {
        BtrScene b = new BtrScene(0, 0, 0, 0);
        long t0 = 10_000;
        double worstJerk = 0, worstTurn = 0;
        double[] p0 = null, p1 = null;
        // the drone hovers over its start, ticks every 20 ms; the picture reads the BTR every 33 ms (another clock)
        long tick = t0;
        for (long t = t0; t <= t0 + 30_000; t += FRAME_MS) {
            while (tick <= t) {
                b.threat(0, 0, true, tick);
                tick += 20;
            }
            double[] q = b.pose(t);
            if (p0 != null) {
                // the second difference: what changes from one picture's step to the next (a jump shows here)
                double jn = q[0] - 2 * p1[0] + p0[0], je = q[1] - 2 * p1[1] + p0[1];
                worstJerk = Math.max(worstJerk, Math.hypot(jn, je));
            }
            if (p1 != null) worstTurn = Math.max(worstTurn, Math.abs(angle(q[2] - p1[2])));
            p0 = p1;
            p1 = q;
        }
        assertTrue(b.escaping(), "it fled");
        // 13 m/s at most and 1.8-2.2 m/s2: a smooth path changes its step by centimetres; 0.1 s steps read straight
        // jumped by over a metre
        assertTrue(worstJerk < 0.12, "steps change smoothly: worst " + worstJerk + " m");
        assertTrue(worstTurn < 2.0, "it turns at most 30 deg/s: worst " + worstTurn + " deg a picture");
    }

    @Test
    void theCameraIsCarriedOnBetweenTheModelsTicks() {
        // the model at 19 m/s north, turning 30 deg/s, ticking every 25 ms; the picture every 33 ms
        double v = 19, rate = 30;
        Scene.View prev = null;
        double lastStep = -1, worst = 0;
        for (long t = 1000; t <= 3000; t += FRAME_MS) {
            long q = (t / 25) * 25, p = q - 25; // the last two ticks before the picture
            Scene.View a = at(p, v, rate), b = at(q, v, rate);
            Scene.View now = VirtualDrone.carryOn(p, a, q, b, t);
            if (prev != null) {
                double step = now.north() - prev.north();
                if (lastStep >= 0) worst = Math.max(worst, Math.abs(step - lastStep));
                lastStep = step;
            }
            prev = now;
            assertEquals(v * t / 1000.0, now.north(), 1e-6, "where it is at the picture's moment");
            assertEquals(rate * t / 1000.0, now.headingDeg(), 1e-6);
        }
        assertTrue(worst < 1e-6, "each picture moves the same: worst difference " + worst + " m");
        // read straight from the model, a picture moved one tick (0.48 m) or two (0.95 m)
        Scene.View straightA = at(1000, v, rate), straightB = at(1025, v, rate);
        assertEquals(0.475, straightB.north() - straightA.north(), 1e-9);
    }

    @Test
    void theCarryOnStopsAtOneTickAheadAndTurnsTheShortWay() {
        Scene.View a = new Scene.View(0, 0, 10, 0, 0, 359), b = new Scene.View(1, 0, 10, 0, 0, 1);
        Scene.View far = VirtualDrone.carryOn(0, a, 20, b, 1000); // a stale model: no flying off
        assertEquals(1 + 40.0 / 20, far.north(), 1e-9, "40 ms ahead at most");
        Scene.View half = VirtualDrone.carryOn(0, a, 20, b, 30);
        assertEquals(2.0, half.headingDeg(), 1e-9, "359 -> 1 is +2 degrees, not -358");
    }

    @Test
    void everyPictureIsEncodedNoneSkipped() throws Exception {
        BtrScene b = new BtrScene(0, 0, 0, 0);
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        int skipped = 0;
        try (H264Encoder enc = new H264Encoder(1280, 720, 30, 1_000_000)) { // a low rate: the old encoder skipped here
            for (int i = 0; i < 90; i++) {
                long t = 30_000 + i * FRAME_MS;
                b.threat(0, 0, true, t);
                double[] q = b.pose(t);
                double h = Math.toRadians(q[2]);
                b.render(img, new Scene.View(q[0] - Math.cos(h) * 12, q[1] - Math.sin(h) * 12, 6, 0, 20, q[2]), t, false);
                if (enc.encode(Scene.pixels(img), i == 0).isEmpty()) skipped++;
            }
        }
        assertEquals(0, skipped, "a phone's encoder sends every frame");
    }

    @Test
    void theFixedDelayKeepsFramesEvenlyApart() {
        LinkImpairment link = new LinkImpairment();
        link.setBaseMs(30);
        long last = -1;
        for (long sent = 0; sent < 3000; sent += FRAME_MS) {
            long delay = link.decide(true, sent);
            assertEquals(30, delay, "the same delay for every packet");
            long arrives = sent + delay;
            if (last >= 0) assertEquals(FRAME_MS, arrives - last, "frames arrive as evenly as they were sent");
            last = arrives;
        }
    }

    private static Scene.View at(long ms, double v, double rate) {
        return new Scene.View(v * ms / 1000.0, 0, 10, 0, 0, rate * ms / 1000.0);
    }

    private static double angle(double d) {
        return Math.atan2(Math.sin(Math.toRadians(d)), Math.cos(Math.toRadians(d))) * 180 / Math.PI;
    }
}
