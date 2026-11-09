package de.droiddrone.pc.video;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the video path costs on this PC, from the packet to the picture handed to the screen (1.0.53, the pilot's
 * performance review): the fragments put back together, and the whole way through the pipeline's own threads. The
 * figures are printed for the log; the limits are loose, for a slow PC under a busy test run.
 */
class PipelinePerfTest {
    /** The phone's fragments of one frame: {offset, length} pairs as it splits them. */
    static List<int[]> split(int size) {
        List<int[]> out = new ArrayList<>();
        for (int at = 0; at < size; ) {
            int n = Math.min(at == 0 ? FrameAssembler.FIRST_SEGMENT : FrameAssembler.SEGMENT, size - at);
            out.add(new int[]{at, n});
            at += n;
        }
        return out;
    }

    /** 1080p at 8 Mbit/s: a 200 KB key frame every 30, P-frames of 30 KB - 30 000 fragments or so. */
    @Test
    void puttingFramesTogetherCostsMicrosecondsAFragment() {
        byte[] key = new byte[200_000], p = new byte[30_000];
        new java.util.Random(1).nextBytes(key);
        new java.util.Random(2).nextBytes(p);
        List<int[]> keyParts = split(key.length), pParts = split(p.length);
        FrameAssembler a = new FrameAssembler();
        long fragments = 0, frames = 0, ns = 0;
        for (int round = 0; round < 2; round++) { // the first round warms up
            fragments = frames = 0;
            long t0 = System.nanoTime();
            for (int f = 0; f < 3000; f++) {
                short num = (short) ((round * 3000 + f) % 32767 + 1);
                boolean isKey = f % 30 == 0 || num == 1;
                byte[] data = isKey ? key : p;
                FrameAssembler.Frame out = null;
                for (int[] part : isKey ? keyParts : pParts) {
                    FrameAssembler.Frame done = a.put(isKey, num, part[0], part[0] == 0 ? data.length : 0, data, part[0], part[1], 1_000 + f * 33L);
                    if (done != null) out = done;
                    fragments++;
                }
                if (out != null && !out.damaged()) frames++;
            }
            ns = System.nanoTime() - t0;
        }
        double perFragmentUs = ns / 1e3 / fragments;
        System.out.printf("FrameAssembler: %d fragments, %.2f us a fragment, %.3f ms a 200 KB key frame%n",
                fragments, perFragmentUs, perFragmentUs * split(key.length).size() / 1e3);
        assertEquals(3000, frames, "every frame whole");
        assertTrue(perFragmentUs < 20, "a fragment in microseconds: " + perFragmentUs);
    }

    /**
     * The pipeline's own delay: from the fragment that completes a 720p frame to the picture handed to the screen
     * (decoded, scaled to 1280x720, presented), frames coming at 30 a second as the phone sends them.
     */
    @Test
    void fromTheLastFragmentToThePictureOnScreen() throws Exception {
        TestStreams.Stream s = TestStreams.h264(1280, 720, 90, 30, true);
        AtomicLong completedAt = new AtomicLong();
        List<Long> delays = java.util.Collections.synchronizedList(new ArrayList<>());
        VideoPipeline.FrameSink sink = new VideoPipeline.FrameSink() {
            final int[] px = new int[1280 * 720];

            public void videoStarted(int width, int height, boolean mirror) {
            }

            public int[] targetSize() {
                return new int[]{1280, 720};
            }

            public int[] beginFrame(int width, int height) {
                return px;
            }

            public void presentFrame() {
                delays.add(System.nanoTime() - completedAt.get());
            }
        };
        try (VideoPipeline p = new VideoPipeline(sink, () -> 30)) {
            p.setKeyFrameSender(() -> { });
            p.onVideoConfig(1280, 720, false, false, s.codecConfig());
            for (int i = 0; i < s.packets().size(); i++) {
                long next = System.nanoTime() + 33_333_333L;
                byte[] d = s.packets().get(i).data();
                List<int[]> parts = split(d.length);
                for (int k = 0; k < parts.size(); k++) {
                    int[] part = parts.get(k);
                    if (k == parts.size() - 1) completedAt.set(System.nanoTime());
                    p.onVideoFragment(s.packets().get(i).keyFrame(), (short) (i + 1), part[0], part[0] == 0 ? d.length : 0, d, part[0], part[1]);
                }
                while (System.nanoTime() < next) Thread.onSpinWait();
            }
            long end = System.currentTimeMillis() + 2000;
            while (p.framesDone() < s.packets().size() && System.currentTimeMillis() < end) Thread.sleep(5);
        }
        long[] ms = delays.stream().skip(10).mapToLong(Long::longValue).toArray(); // past the warm-up
        Arrays.sort(ms);
        double median = ms[ms.length / 2] / 1e6, p95 = ms[(int) (ms.length * 0.95)] / 1e6;
        System.out.printf("Pipeline, 720p at 30 fps: last fragment -> presented %.2f ms median, %.2f ms 95th percentile (%d pictures)%n",
                median, p95, ms.length);
        assertTrue(ms.length >= 70, "nearly every picture drawn: " + ms.length);
        assertTrue(median < 15, "a frame through in well under a frame time: " + median);
    }
}
