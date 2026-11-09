package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pc.43: the motion detection's picture on a real phone. Up to pc.42 a phone that lists 16:9 only at 1280x720 and up
 * (the pilot's) had no analysis picture for any 16:9 video: no motion detection live, nothing to watch with in
 * overwatch. The virtual fleet never went through this choice, so every test passed.
 */
class AnalysisSizeTest {
    /** YUV sizes as a phone with no small 16:9 size lists them. */
    static final int[][] NO_SMALL_16_9 = {{4000, 3000}, {1920, 1080}, {1440, 1080}, {1280, 720}, {960, 720},
            {720, 480}, {640, 480}, {352, 288}, {320, 240}, {176, 144}};
    static final int[][] COMMON = {{1920, 1080}, {1280, 720}, {640, 480}, {640, 360}, {320, 240}, {320, 180}, {176, 144}};

    /** pc.35 - pc.42's rule: 240 - 720 wide only. */
    static int[] before(int[][] sizes, int w, int h) {
        double aspect = (double) w / h;
        int[] best = null;
        for (int[] s : sizes) {
            if (s[0] < 240 || s[0] > 720) continue;
            if (Math.abs((double) s[0] / s[1] - aspect) > 0.03) continue;
            if (best == null || s[0] < best[0]) best = s;
        }
        return best;
    }

    @Test
    void aPhoneWithoutASmall169SizeStillGetsAPictureForEvery169Video() {
        for (int[] video : new int[][]{{1280, 720}, {1920, 1080}}) {
            assertNull(before(NO_SMALL_16_9, video[0], video[1]), "pc.42 found none: the bug");
            assertArrayEquals(new int[]{1280, 720}, AnalysisSize.pick(NO_SMALL_16_9, video[0], video[1]));
        }
        assertArrayEquals(new int[]{320, 240}, AnalysisSize.pick(NO_SMALL_16_9, 640, 480), "4:3 as before");
    }

    @Test
    void theSmallestOfTheShapeAsBefore() {
        assertArrayEquals(new int[]{320, 180}, AnalysisSize.pick(COMMON, 1280, 720));
        assertArrayEquals(new int[]{320, 180}, AnalysisSize.pick(COMMON, 1920, 1080));
        assertArrayEquals(new int[]{320, 240}, AnalysisSize.pick(COMMON, 640, 480));
        assertNull(AnalysisSize.pick(new int[][]{{640, 480}, {320, 240}}, 1280, 720), "none of the shape: none");
        assertNull(AnalysisSize.pick(new int[][]{{200, 112}}, 1280, 720), "too small: none");
        assertNull(AnalysisSize.pick(null, 1280, 720));
    }

    @Test
    void shrinkAveragesEachBlockAndKeepsStrides() {
        // 8 x 4, pixel stride 2 (a chroma-like layout), row stride 20: values x + 10 y at even bytes, 255 in between
        int rs = 20;
        ByteBuffer b = ByteBuffer.allocate(rs * 4);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 8; x++) {
                b.put(y * rs + x * 2, (byte) (x + 10 * y));
                b.put(y * rs + x * 2 + 1, (byte) 255);
            }
        }
        byte[] out = new byte[4 * 2];
        AnalysisSize.shrink(b, rs, 2, 2, out, 4, 2);
        // block (0,0): 0, 1, 10, 11 -> 5
        assertEquals(5, out[0] & 0xff);
        assertEquals((6 + 7 + 16 + 17) / 4, out[3] & 0xff);
        assertEquals((20 + 21 + 30 + 31) / 4, out[4] & 0xff);
        byte[] copy = new byte[8 * 4];
        AnalysisSize.shrink(b, rs, 2, 1, copy, 8, 4);
        assertEquals(37, copy[3 * 8 + 7] & 0xff, "step 1 copies");
    }

    // ------------------------------------------------------------------ end to end: a 1280x720 camera picture

    static final int SW = 320, SH = 180, STEP = 4, FW = SW * STEP, FH = SH * STEP;

    /**
     * The scene at the camera's 1280x720: each scene pixel 4 x 4, a fine 1-pixel weave over it (grass, gravel), and the
     * whole picture moved by the lens's stabiliser 0 or 1 pixel each way at random each picture.
     */
    static ByteBuffer cameraPicture(byte[] scene, Random ois) {
        int ox = ois.nextInt(2), oy = ois.nextInt(2);
        byte[] f = new byte[FW * FH];
        for (int y = 0; y < FH; y++) {
            int sy = Math.min(FH - 1, y + oy);
            for (int x = 0; x < FW; x++) {
                int sx = Math.min(FW - 1, x + ox);
                int v = (scene[(sy / STEP) * SW + sx / STEP] & 0xff) + (((sx + sy) & 1) == 0 ? 18 : -18);
                f[y * FW + x] = (byte) Math.max(0, Math.min(255, v));
            }
        }
        return ByteBuffer.wrap(f);
    }

    static MotionDetectorTest.Run run(SyntheticScene scene, double toS, boolean averaged) {
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        MotionDetectorTest.Run r = new MotionDetectorTest.Run();
        Random ois = new Random(4);
        byte[] y = new byte[SW * SH];
        for (long i = 0; i < Math.round(toS * MotionDetectorTest.FPS); i++) {
            double t = i / MotionDetectorTest.FPS;
            ByteBuffer picture = cameraPicture(scene.frame(t)[0], ois);
            if (averaged) {
                AnalysisSize.shrink(picture, FW, 1, STEP, y, SW, SH);
            } else { // pc.42: one pixel in each 4 x 4 block
                for (int yy = 0; yy < SH; yy++) for (int xx = 0; xx < SW; xx++) y[yy * SW + xx] = picture.get(yy * STEP * FW + xx * STEP);
            }
            MotionDetector.Result res = d.process(y, SW, SH, null, null, Math.round(t * 1000));
            for (MotionDetector.Detection det : res.detections) {
                r.ids.add(det.id);
                r.reports++;
                if (r.firstAt < 0) r.firstAt = t;
            }
        }
        return r;
    }

    @Test
    void aWalkerIsFoundInA1280x720PictureAndAStillOneStaysQuiet() {
        SyntheticScene still = new SyntheticScene(SW, SH, 21);
        assertEquals(0, run(still, 20, true).reports, "a still picture with the stabiliser moving: nothing");
        SyntheticScene walk = new SyntheticScene(SW, SH, 21);
        walk.movers.add(new SyntheticScene.Mover(20, 95, 10, 26, 18, 1, 6, 20, 70, 140, 122, true));
        MotionDetectorTest.Run r = run(walk, 20, true);
        assertTrue(r.firstAt >= 6 && r.firstAt <= 10, "the walker found: " + r.firstAt);
    }
}
