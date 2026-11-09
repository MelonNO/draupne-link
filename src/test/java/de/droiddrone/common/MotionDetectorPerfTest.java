package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the motion detection costs a picture (1.0.53, with the approach watch): the pictures made first, only the
 * detector timed. The phone runs it a few times a second on its own thread; on this PC it takes about 1 ms a picture.
 * The budget is ten times that, to catch a slip without failing on a busy release runner.
 */
class MotionDetectorPerfTest {
    @Test
    void aPictureCostsAMillisecondOrSo() {
        SyntheticScene scene = MotionDetectorTest.woodsEdge(3);
        scene.approachers.add(new SyntheticScene.Approacher(0.5, 40, 1.4, 5, 40, 90, 30));
        int n = 160;
        byte[][] frames = new byte[n][];
        for (int i = 0; i < n; i++) frames[i] = scene.frame(i / 8.0)[0];
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        // warm the JIT on a first pass, then time the second (the same pictures, a later clock)
        for (int i = 0; i < n; i++) d.process(frames[i], 320, 180, null, null, Math.round(i * 125.0));
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) d.process(frames[i], 320, 180, null, null, Math.round((n + i) * 125.0));
        double ms = (System.nanoTime() - start) / 1e6 / n;
        System.out.printf("motion detection: %.2f ms a 320 x 180 picture%n", ms);
        assertTrue(ms < 10, "a picture took " + ms + " ms");
    }
}
