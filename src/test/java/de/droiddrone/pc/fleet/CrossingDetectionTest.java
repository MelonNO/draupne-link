package de.droiddrone.pc.fleet;

import de.droiddrone.common.MotionDetector;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The detector on the virtual fleet's own pictures, as the drone feeds it (luma and colour, 8 a second), its target sent
 * while the detector is still young - a phone just put to overwatch (1.0.52, the pilot: "it is using a long time to start
 * detecting, so once the car is almost halfway across the screen it then notices it", and the box sat beside it).
 */
class CrossingDetectionTest {
    /** Where the target is: the picture with it against the same picture without it. */
    static float[] truth(BufferedImage with, BufferedImage without) {
        int x0 = 9999, y0 = 9999, x1 = -1, y1 = -1;
        for (int y = 0; y < with.getHeight(); y++) {
            for (int x = 0; x < with.getWidth(); x++) {
                int a = with.getRGB(x, y), b = without.getRGB(x, y);
                int d = Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) + Math.abs((a & 255) - (b & 255));
                if (d < 30) continue;
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                x1 = Math.max(x1, x);
                y1 = Math.max(y1, y);
            }
        }
        if (x1 < 0) return null;
        float w = with.getWidth(), h = with.getHeight();
        return new float[]{x0 / w, y0 / h, (x1 - x0 + 1) / w, (y1 - y0 + 1) / h};
    }

    static float iou(float[] a, MotionDetector.Detection b) {
        float ix = Math.max(0, Math.min(a[0] + a[2], b.x + b.w) - Math.max(a[0], b.x));
        float iy = Math.max(0, Math.min(a[1] + a[3], b.y + b.h) - Math.max(a[1], b.y));
        float i = ix * iy;
        return i / (a[2] * a[3] + b.w * b.h - i);
    }

    /** {ms from sending to the first box, frames with a box, of them on the target (IoU over 0.5)}. */
    static long[] run(Scene.Kind kind, long sendAfterMs) {
        long t0 = 1_000_000;
        Scene s = new Scene("P", 7), empty = new Scene("P", 7);
        Scene.View v = new Scene.View(0, 0, 1.0, 0, 0, 0);
        BufferedImage img = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB), bare = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        long first = -1, boxed = 0, on = 0;
        for (long t = 0; t <= sendAfterMs + 20_000; t += 125) {
            if (t == sendAfterMs) s.addTarget(kind, v, t0 + t);
            s.render(img, v, t0 + t, null, false);
            int[] size = new int[2];
            int[] px = Scene.pixels(img);
            byte[] y = Scene.luma(px, 320, 180, 320, size);
            byte[][] uv = Scene.chroma(px, 320, 180);
            MotionDetector.Result r = d.process(y, size[0], size[1], uv[0], uv[1], t0 + t);
            if (t < sendAfterMs) {
                assertTrue(r.detections.isEmpty(), kind + ": nothing before it is sent (" + t + " ms)");
                continue;
            }
            if (r.detections.isEmpty()) continue;
            empty.render(bare, v, t0 + t, null, false);
            float[] tr = truth(img, bare);
            if (tr == null) continue;
            if (first < 0) first = t - sendAfterMs;
            boxed++;
            if (iou(tr, r.detections.get(0)) > 0.5f) on++;
        }
        return new long[]{first, boxed, on};
    }

    @Test
    void theBtrIsFoundAsItComesInAndTheBoxSitsOnIt() {
        long[] r = run(Scene.Kind.VEHICLE, 6_000); // 3 s after the warm-up: the detector young
        assertTrue(r[0] >= 0 && r[0] <= 6_500, "the BTR found " + r[0] + " ms after it was sent");
        assertTrue(r[1] >= 40 && r[2] >= 0.8 * r[1], "the box on it: " + r[2] + " of " + r[1] + " boxes");
    }

    @Test
    void theSoldierIsFoundSoonAndTheBoxSitsOnHim() {
        long[] r = run(Scene.Kind.WALKER, 6_000);
        assertTrue(r[0] >= 0 && r[0] <= 3_500, "the soldier found " + r[0] + " ms after he was sent");
        assertTrue(r[1] >= 60 && r[2] >= 0.85 * r[1], "the box on him: " + r[2] + " of " + r[1] + " boxes");
    }
}
