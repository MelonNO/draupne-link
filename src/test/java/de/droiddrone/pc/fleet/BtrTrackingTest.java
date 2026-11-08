package de.droiddrone.pc.fleet;

import de.droiddrone.common.MotionDetector;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The detector's box on a vehicle on patrol - bends, speed changes, halts (1.0.52, the pilot's screen recording: "the
 * box does not follow the moving BTR"). It used to drop the box at every turn (the straight-line rules that keep leaves out), shrink it to one
 * piece where the vehicle broke up, and never see a far vehicle lying on the horizon line (the 3 x 3 test and the
 * other-light test both took it for the ground).
 */
class BtrTrackingTest {
    /** The vehicle's dark or sand-coloured pixels in the analysis picture: neither the pale world nor its blue lines. */
    private static boolean vehicle(int rgb) {
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        return Math.max(r, Math.max(g, b)) < 150 && b < g + 10;
    }

    /** The vehicle's true box, 0..1, or null when it is out of the picture. */
    private static float[] truth(BufferedImage img) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (!vehicle(img.getRGB(x, y))) continue;
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                x1 = Math.max(x1, x);
                y1 = Math.max(y1, y);
            }
        }
        if (x1 < 0) return null;
        float w = img.getWidth(), h = img.getHeight();
        return new float[]{x0 / w, y0 / h, (x1 - x0 + 1) / w, (y1 - y0 + 1) / h};
    }

    private static float iou(float[] a, MotionDetector.Detection b) {
        float ix = Math.max(0, Math.min(a[0] + a[2], b.x + b.w) - Math.max(a[0], b.x));
        float iy = Math.max(0, Math.min(a[1] + a[3], b.y + b.h) - Math.max(a[1], b.y));
        float i = ix * iy;
        return i / (a[2] * a[3] + b.w * b.h - i);
    }

    /**
     * {frames with the vehicle in the picture after the lock-on, frames with a box, boxes on it (IoU over minIou)}; the
     * camera back metres behind the route's drone (negative: nearer the route), alt up.
     */
    private static int[] run(double back, double alt, float minIou) {
        long t0 = 2_000_000, drive = 26_000;
        BtrScene s = new BtrScene(0, 0, 0, t0);
        s.driveFrom(t0 + drive);
        Scene.View v = new Scene.View(-back, 0, alt, 0, 0, 0);
        BufferedImage img = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        int seen = 0, boxed = 0, on = 0;
        long lockedAt = -1;
        for (long t = 0; t <= 90_000; t += 125) {
            s.render(img, v, t0 + t, true);
            int[] size = new int[2];
            byte[] y = Scene.luma(Scene.pixels(img), 320, 180, 320, size);
            MotionDetector.Result r = d.process(y, size[0], size[1], null, null, t0 + t);
            float[] tr = truth(img);
            if (t < drive) {
                assertTrue(r.detections.isEmpty(), "nothing before the vehicle drives in (" + t + " ms)");
                continue;
            }
            if (lockedAt < 0 && !r.detections.isEmpty()) lockedAt = t;
            if (lockedAt < 0 || t < lockedAt + 2000 || tr == null) continue;
            seen++;
            if (r.detections.isEmpty()) continue;
            boxed++;
            if (iou(tr, r.detections.get(0)) > minIou) on++;
        }
        assertTrue(lockedAt >= 0, "the vehicle was never found");
        return new int[]{seen, boxed, on};
    }

    @Test
    void theBoxStaysOnTheVehicleThroughItsTurns() {
        // 25 m nearer the route than the tour's drone: 25-70 m off, through its bends and halts
        int[] r = run(-25, 0, 0.5f);
        assertTrue(r[1] >= 0.9 * r[0], "a box in most pictures after the lock-on: " + r[1] + " of " + r[0]);
        // the patrol halts and turns sharply (the loop of 1.0.51 never stopped): measured 82 %
        assertTrue(r[2] >= 0.75 * r[0], "the box on the vehicle (IoU > 0.5): " + r[2] + " of " + r[0]);
    }

    @Test
    void aVehicleFarOffOnTheHorizonLineIsFoundAndBoxed() {
        // the camera 1.5 m up, 25 m nearer the route than the tour's drone: the vehicle 25-70 m off lies along the
        // horizon, a few of the detector's pixels (from the tour's drone itself, 50-95 m, the detector loses it there)
        int[] r = run(-25, 1.5, 0.3f);
        assertTrue(r[1] >= 0.1 * r[0], "found in some of the pictures: " + r[1] + " of " + r[0]);
        assertTrue(r[2] >= 0.9 * r[1], "and the box on it when shown: " + r[2] + " of " + r[1]);
    }
}
