package de.droiddrone.pc.fleet;

import de.droiddrone.common.MotionDetector;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detector on the virtual fleet's own pictures (1.0.53, the pilot: "the movement detection should be able to find a
 * person moving ... directly towards the camera"): the 3D soldier walking straight at a grey camera (no colour) - one on
 * the ground, as a landed drone's, and one 1.5 m up. Found while he is still 15 m off at least, the box on him.
 */
class ApproachDetectionTest {
    /** {metres off when first found (-1: never), boxes shown, of them on him}. */
    static double[] run(double alt) {
        long t0 = 3_000_000, sendAt = 8_000;
        double from = 40, speed = 1.4;
        Scene s = new Scene("P", 7), empty = new Scene("P", 7);
        Scene.View v = new Scene.View(0, 0, alt, 0, 0, 0);
        BufferedImage img = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB), bare = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        double foundAt = -1;
        int boxes = 0, on = 0;
        for (long t = 0; t <= sendAt + 26_000; t += 125) {
            if (t == sendAt) s.addApproacher(v, t0 + t, from, 1.0);
            s.render(img, v, t0 + t, null, false);
            int[] size = new int[2];
            byte[] y = Scene.luma(Scene.pixels(img), 320, 180, 320, size);
            MotionDetector.Result r = d.process(y, size[0], size[1], null, null, t0 + t);
            if (t < sendAt) {
                assertTrue(r.detections.isEmpty(), "alt " + alt + ": nothing before he is sent (" + t + " ms)");
                continue;
            }
            if (r.detections.isEmpty()) continue;
            empty.render(bare, v, t0 + t, null, false);
            float[] tr = CrossingDetectionTest.truth(img, bare);
            if (tr == null) continue;
            if (foundAt < 0) foundAt = from - speed * (t - sendAt) / 1000.0;
            boxes++;
            MotionDetector.Detection b = r.detections.get(0);
            if (b.centreX() >= tr[0] - 0.02f && b.centreX() <= tr[0] + tr[2] + 0.02f) on++;
        }
        return new double[]{foundAt, boxes, on};
    }

    @Test
    void aSoldierWalkingStraightAtACameraOnTheGroundIsFound() {
        double[] r = run(0.3);
        assertTrue(r[0] >= 15, "found " + r[0] + " m off");
        assertTrue(r[2] >= 0.9 * r[1], "the box on him: " + r[2] + " of " + r[1]);
    }

    @Test
    void aSoldierWalkingStraightAtACameraUpAPoleIsFound() {
        double[] r = run(1.5);
        assertTrue(r[0] >= 15, "found " + r[0] + " m off");
        assertTrue(r[2] >= 0.9 * r[1], "the box on him: " + r[2] + " of " + r[1]);
    }
}
