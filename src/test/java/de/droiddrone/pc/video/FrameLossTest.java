package de.droiddrone.pc.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.0.64: the share of frames the link didn't deliver whole, as AUTO's tier choice reads it
 * (VideoPipeline.frameLossRatio, TierPolicy.LOSSY_FRAMES). No config is ever sent: the frames only pass the assembler.
 */
class FrameLossTest {
    private static final class NoScreen implements VideoPipeline.FrameSink {
        @Override
        public void videoStarted(int width, int height, boolean mirror) {
        }

        @Override
        public int[] targetSize() {
            return new int[]{0, 0};
        }

        @Override
        public int[] beginFrame(int width, int height) {
            return new int[0];
        }

        @Override
        public void presentFrame() {
        }
    }

    @Test
    void framesLostOrRebuiltFromParityCountAndAStoppedPictureSaysNothing() throws Exception {
        byte[] small = new byte[200];
        // a frame of two packets: the first full, the second 100 bytes - and their parity (the XOR of the two)
        byte[] two = new byte[FrameAssembler.FIRST_SEGMENT + 100];
        for (int i = 0; i < two.length; i++) two[i] = (byte) (i * 31 + 7);
        byte[] parity = new byte[FrameAssembler.FIRST_SEGMENT];
        System.arraycopy(two, 0, parity, 0, FrameAssembler.FIRST_SEGMENT);
        for (int i = 0; i < 100; i++) parity[i] ^= two[FrameAssembler.FIRST_SEGMENT + i];
        try (VideoPipeline lossy = new VideoPipeline(new NoScreen(), () -> 30);
             VideoPipeline mended = new VideoPipeline(new NoScreen(), () -> 30);
             VideoPipeline clean = new VideoPipeline(new NoScreen(), () -> 30)) {
            assertEquals(0, lossy.frameLossRatio(), "no picture: nothing to say");
            long end = System.currentTimeMillis() + 2400;
            for (short n = 1; System.currentTimeMillis() < end; n++) {
                // every third frame lost whole
                if (n % 3 != 0) lossy.onVideoFragment(n == 1, n, 0, small.length, small, 0, small.length);
                // every frame's second packet lost, and rebuilt from its parity: whole on screen, lost on the link
                mended.onVideoFragment(n == 1, n, 0, two.length, two, 0, FrameAssembler.FIRST_SEGMENT);
                mended.onVideoParity(n == 1, n, two.length, parity, 0, parity.length);
                clean.onVideoFragment(n == 1, n, 0, small.length, small, 0, small.length);
                Thread.sleep(10);
            }
            double third = lossy.frameLossRatio();
            assertTrue(third > 0.25 && third < 0.42, "a third of the frames lost: " + third);
            assertEquals(0, lossy.stats().damaged());
            assertTrue(mended.stats().recovered() > 50, "rebuilt: " + mended.stats());
            assertEquals(0, mended.stats().lost() + mended.stats().damaged(), "every frame whole in the end");
            assertTrue(mended.frameLossRatio() > 0.9, "and every one of them hit by loss: " + mended.frameLossRatio());
            assertEquals(0, clean.frameLossRatio(), "nothing lost");
            Thread.sleep(VideoPipeline.LOSS_STALE_MS + 300);
            assertEquals(0, lossy.frameLossRatio(), "the picture stopped: the old figure says nothing now");
        }
    }
}
