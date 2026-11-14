package de.droiddrone.pc.video;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** A picture kept for a still: the decoder's own, at the stream's size, held apart from the decoder. */
class VideoStillTest {
    private static void assertRed(int[] px, int w, int x, int y) {
        int c = px[y * w + x] & 0xffffff;
        assertTrue(((c >> 16) & 255) > 200 && ((c >> 8) & 255) < 60 && (c & 255) < 60, "red at " + x + "," + y + ": " + Integer.toHexString(c));
    }

    @Test
    void aKeptPictureHasTheStreamsSizeAndOutlivesTheDecoder() {
        TestStreams.Stream s = TestStreams.h264(320, 240, 12, 6);
        VideoDecoder.Kept kept;
        try (VideoDecoder d = new VideoDecoder(false, s.codecConfig())) {
            assertNull(d.keep(), "nothing decoded yet: nothing to keep");
            boolean any = false;
            for (TestStreams.Packet p : s.packets().subList(0, 8)) any |= d.decode(p.data(), p.data().length);
            assertTrue(any);
            int[] screen = new int[160 * 120];
            d.convert(screen, 160, 120); // the screen's copy, scaled to a small view
            kept = d.keep();
            assertNotNull(kept);
            // the video goes on: more frames, the screen's copy again, a flush
            for (TestStreams.Packet p : s.packets().subList(8, s.packets().size())) d.decode(p.data(), p.data().length);
            d.convert(screen, 160, 120);
            assertRed(screen, 160, 80, 60);
            d.flush();
        }
        assertEquals(320, kept.width(), "the stream's size, not the view's");
        assertEquals(240, kept.height());
        int[] px = new int[320 * 240];
        kept.rgb(px);
        assertRed(px, 320, 160, 120);
        assertRed(px, 320, 2, 2);
        assertRed(px, 320, 317, 237);
        kept.rgb(px); // and again: nothing was used up
        assertRed(px, 320, 160, 120);
        kept.close();
        kept.close(); // let go twice: nothing
        assertEquals(0, kept.width());
        assertThrows(IllegalStateException.class, () -> kept.rgb(px));
    }

    @Test
    void decodedOnTheGpuOrNotTheKeptPictureIsTheOneTheScreenGot() {
        TestStreams.Stream s = TestStreams.h264(320, 240, 12, 6);
        try (VideoDecoder d = new VideoDecoder(false, s.codecConfig(), true)) {
            boolean any = false;
            for (TestStreams.Packet p : s.packets()) any |= d.decode(p.data(), p.data().length);
            assertTrue(any);
            d.convert(new int[320 * 240], 320, 240); // on the GPU: brought back for the screen here
            try (VideoDecoder.Kept kept = d.keep()) {
                assertNotNull(kept);
                assertEquals(320, kept.width());
                assertEquals(240, kept.height());
                int[] px = new int[320 * 240];
                kept.rgb(px);
                assertRed(px, 320, 160, 120);
                System.out.println("a still's picture decoded on the GPU on this PC: " + d.isHardware());
            }
        }
    }

    /** A view 160 x 120 big, telling when a picture was handed to it. */
    private static final class Screen implements VideoPipeline.FrameSink {
        final CountDownLatch shown = new CountDownLatch(1);

        public void videoStarted(int width, int height, boolean mirror) {}
        public int[] targetSize() { return new int[]{160, 120}; }
        public int[] beginFrame(int width, int height) { return new int[width * height]; }
        public void presentFrame() { shown.countDown(); }
    }

    @Test
    void thePipelineHandsItsPictureOverOnTheDecodeThreadOrSaysThereIsNone() throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 240, 12, 6);
        Screen screen = new Screen();
        LinkedBlockingQueue<Optional<VideoDecoder.Kept>> handed = new LinkedBlockingQueue<>();
        String[] thread = {null};
        VideoDecoder.Kept kept;
        try (VideoPipeline pipeline = new VideoPipeline(screen, () -> 40)) {
            pipeline.keepPicture(k -> handed.add(Optional.ofNullable(k)));
            Optional<VideoDecoder.Kept> none = handed.poll(20, TimeUnit.SECONDS);
            assertNotNull(none, "answered without a stream too: the decode thread looks five times a second");
            assertTrue(none.isEmpty(), "no stream: no picture");

            pipeline.onVideoConfig(320, 240, false, false, s.codecConfig());
            short n = 0;
            for (TestStreams.Packet p : s.packets()) {
                byte[] d = p.data();
                pipeline.onVideoFragment(p.keyFrame(), ++n, 0, d.length, d, 0, d.length);
            }
            assertTrue(screen.shown.await(30, TimeUnit.SECONDS), "a picture on the screen");
            pipeline.keepPicture(k -> {
                thread[0] = Thread.currentThread().getName();
                handed.add(Optional.ofNullable(k));
            });
            Optional<VideoDecoder.Kept> got = handed.poll(20, TimeUnit.SECONDS);
            assertTrue(got != null && got.isPresent(), "the picture in hand");
            assertEquals("videoDecode", thread[0]);
            kept = got.get();
            pipeline.keepPicture(k -> {
                throw new IllegalStateException("a caller that fails");
            });
            pipeline.keepPicture(k -> handed.add(Optional.ofNullable(k))); // asked again before the hand-over: this one is answered
            Optional<VideoDecoder.Kept> again = handed.poll(20, TimeUnit.SECONDS);
            assertTrue(again != null && again.isPresent());
            again.get().close();
            assertNull(pipeline.stats().error(), "none of it is the video's error");
        }
        // the connection ended, the decoder with it: the picture kept is still whole
        assertEquals(320, kept.width(), "the stream's size, though the screen showed it at 160 x 120");
        assertEquals(240, kept.height());
        int[] px = new int[320 * 240];
        kept.rgb(px);
        assertRed(px, 320, 160, 120);
        kept.close();
    }

    @Test
    void keepingCostsTheDecodeThreadNextToNothing() {
        TestStreams.Stream s = TestStreams.h264(1280, 720, 4, 4);
        try (VideoDecoder d = new VideoDecoder(false, s.codecConfig())) {
            for (TestStreams.Packet p : s.packets()) d.decode(p.data(), p.data().length);
            for (int i = 0; i < 200; i++) d.keep().close(); // warm
            int n = 2000;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) d.keep().close();
            double us = (System.nanoTime() - t0) / 1e3 / n;
            System.out.printf("keeping and letting go a 1280x720 picture: %.2f us%n", us);
            assertTrue(us < 500, "a reference, not a copy: " + us + " us"); // a copy of 1280x720 is some hundreds of us
            try (VideoDecoder.Kept kept = d.keep()) {
                int[] px = new int[1280 * 720];
                long t1 = System.nanoTime();
                kept.rgb(px);
                System.out.printf("its pixels (the saver's thread): %.1f ms%n", (System.nanoTime() - t1) / 1e6);
                assertRed(px, 1280, 640, 360);
            }
        }
    }
}
