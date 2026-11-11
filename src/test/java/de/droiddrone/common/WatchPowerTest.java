package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * pc.50, overwatch's power (2026-11-11, measured on the pilot's test phone): its picture alone was 1280x720 at 30 fps
 * (the camera HAL at 1.6 cores in the dark, 0.8-1 A) for 8 pictures a second of 320x180. Now the smallest size whose
 * centred cut is the video's view, at the camera's lowest frame-rate range; and resends that slow down unanswered.
 */
class WatchPowerTest {
    /** The pilot's phone's YUV sizes (dumpsys media.camera), and its frame-rate ranges. */
    static final int[][] TEST_PHONE = {{4080, 3060}, {4080, 2296}, {4080, 1836}, {3840, 2160}, {3264, 1836}, {3264, 1468}, {3056, 3056},
            {2576, 1932}, {2400, 1080}, {1920, 1080}, {1920, 864}, {1600, 720}, {1440, 1440}, {1440, 1080}, {1280, 960}, {1280, 720},
            {1088, 1088}, {960, 960}, {960, 720}, {720, 720}, {720, 480}, {640, 480}, {352, 288}, {320, 240}, {176, 144}};
    static final int[][] TEST_PHONE_FPS = {{15, 15}, {15, 24}, {24, 24}, {15, 30}, {30, 30}};

    @Test
    void thePilotsPhoneWatchesAt320x240Cut169() {
        assertArrayEquals(new int[]{1280, 720}, AnalysisSize.pick(TEST_PHONE, 1280, 720), "the video's own shape: what it had");
        assertArrayEquals(new int[]{320, 240}, AnalysisSize.pickWatch(TEST_PHONE, 1280, 720));
        assertArrayEquals(new int[]{320, 240}, AnalysisSize.pickWatch(TEST_PHONE, 1920, 1080));
        assertArrayEquals(new int[]{0, 30, 320, 180}, AnalysisSize.centreCrop(320, 240, 1280, 720), "the middle 180 rows: the 16:9 view");
        assertArrayEquals(new int[]{0, 0, 1280, 720}, AnalysisSize.centreCrop(1280, 720, 1920, 1080), "the same shape: whole");
        assertArrayEquals(new int[]{15, 15}, AnalysisSize.watchFps(TEST_PHONE_FPS));
    }

    @Test
    void otherPhonesAndShapes() {
        int[][] small169 = {{1920, 1080}, {1280, 720}, {640, 480}, {640, 360}, {320, 240}, {320, 180}, {176, 144}};
        assertArrayEquals(new int[]{320, 180}, AnalysisSize.pickWatch(small169, 1280, 720), "a small 16:9: fewer pixels, kept");
        int[][] only169 = {{1920, 1080}, {1280, 720}};
        assertArrayEquals(new int[]{1280, 720}, AnalysisSize.pickWatch(only169, 1280, 720), "nothing smaller: as before");
        int[][] no320 = {{1920, 1080}, {1280, 720}, {640, 480}, {176, 144}};
        assertArrayEquals(new int[]{640, 480}, AnalysisSize.pickWatch(no320, 1280, 720), "640x480 cut to 640x360");
        assertArrayEquals(new int[]{0, 60, 640, 360}, AnalysisSize.centreCrop(640, 480, 1280, 720));
        assertArrayEquals(new int[]{320, 240}, AnalysisSize.pickWatch(TEST_PHONE, 640, 480), "a 4:3 video: 320x240 whole");
        assertArrayEquals(new int[]{0, 0, 320, 240}, AnalysisSize.centreCrop(320, 240, 640, 480));
        assertArrayEquals(new int[]{40, 0, 240, 240}, AnalysisSize.centreCrop(320, 240, 1, 1), "a square from a wide one: its sides cut");
        assertNull(AnalysisSize.pickWatch(null, 1280, 720));
        assertNull(AnalysisSize.watchFps(new int[][]{{5, 8}}), "too slow for 8 pictures a second: the video's");
        assertArrayEquals(new int[]{10, 15}, AnalysisSize.watchFps(new int[][]{{7, 30}, {10, 15}, {30, 30}}));
        assertArrayEquals(new int[]{24, 24}, AnalysisSize.watchFps(new int[][]{{24, 24}, {15, 30}}));
    }

    /** MotionWatch's copy from the cut: an offset to its first byte, then the rows as before. */
    @Test
    void theCutIsCopiedFromItsFirstRow() {
        int w = 320, h = 240, stride = 336; // a row stride wider than the picture, as cameras give
        byte[] plane = new byte[stride * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) plane[y * stride + x] = (byte) y;
        int[] cut = AnalysisSize.centreCrop(w, h, 16, 9);
        byte[] out = new byte[cut[2] * cut[3]];
        AnalysisSize.shrink(ByteBuffer.wrap(plane), cut[1] * stride + cut[0], stride, 1, 1, out, cut[2], cut[3]);
        assertEquals(30, out[0] & 0xff, "its first row is the picture's 30th");
        assertEquals(209, out[(cut[3] - 1) * cut[2] + 5] & 0xff, "its last the 209th");
        byte[] half = new byte[160 * 90];
        AnalysisSize.shrink(ByteBuffer.wrap(plane), cut[1] * stride, stride, 1, 2, half, 160, 90);
        assertEquals((30 + 31) / 2, half[0] & 0xff, "averaged from the cut too");
    }

    @Test
    void resendsSlowDownUnanswered() {
        assertEquals(1000, OverwatchProtocol.resendEvery(0, OverwatchProtocol.ALERT_RESEND_MS, OverwatchProtocol.ALERT_SLOW_AFTER_MS, OverwatchProtocol.ALERT_SLOW_MS));
        assertEquals(1000, OverwatchProtocol.resendEvery(29_999, 1000, OverwatchProtocol.ALERT_SLOW_AFTER_MS, OverwatchProtocol.ALERT_SLOW_MS));
        assertEquals(5000, OverwatchProtocol.resendEvery(30_000, 1000, OverwatchProtocol.ALERT_SLOW_AFTER_MS, OverwatchProtocol.ALERT_SLOW_MS));
        assertEquals(60_000, OverwatchProtocol.resendEvery(61_000, OverwatchProtocol.INFO_EVERY_MS, OverwatchProtocol.INFO_SLOW_AFTER_MS, OverwatchProtocol.INFO_SLOW_MS));
    }
}
