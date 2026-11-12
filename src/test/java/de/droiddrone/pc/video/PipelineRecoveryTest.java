package de.droiddrone.pc.video;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The pipeline's ways out of trouble (1.0.33): a backlog, a config that never came, a decoder that gives nothing. */
class PipelineRecoveryTest {
    /** A sink the test can hold on its first picture, so the decode thread stands still and the queue fills. */
    static final class HoldingSink implements VideoPipeline.FrameSink {
        final int[] pixels = new int[160 * 120];
        final AtomicInteger presented = new AtomicInteger();
        final CountDownLatch held = new CountDownLatch(1);
        /** The decode thread has made its decoder (taken the config). */
        final CountDownLatch started = new CountDownLatch(1);
        /** The decode thread is held in targetSize with its first picture. */
        final CountDownLatch holding = new CountDownLatch(1);
        volatile boolean hold = true;

        @Override
        public void videoStarted(int width, int height, boolean mirror) {
            started.countDown();
        }

        @Override
        public int[] targetSize() {
            if (hold) {
                holding.countDown();
                try {
                    held.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
            return new int[]{160, 120};
        }

        @Override
        public int[] beginFrame(int width, int height) {
            return pixels;
        }

        @Override
        public void presentFrame() {
            presented.incrementAndGet();
        }
    }

    /** Feeds a frame as the phone splits it. */
    static void feed(VideoPipeline p, TestStreams.Packet packet, short num) {
        byte[] buf = packet.data();
        int offset = 0;
        while (offset < buf.length) {
            int dataSize = de.droiddrone.common.UdpCommon.packetLength - (offset == 0 ? 13 : 9);
            int n = Math.min(dataSize, buf.length - offset);
            p.onVideoFragment(packet.keyFrame(), num, offset, offset == 0 ? buf.length : 0, buf, offset, n);
            offset += n;
        }
    }

    /**
     * 35 frames queue up while the screen is held on frame 1 (key frames every 12: 1, 13, 25): when the 32nd comes,
     * the backlog (2-31) is cut back to its newest whole key frame, not thrown away - frame 1, then the pictures from
     * 25 on are shown; a thrown-away backlog would show nothing more before the next key frame (37, never sent).
     * <p>
     * T1 (1.0.49): the test waits for the decoder to be made and for frame 1 to be held before the rest comes, and
     * reads the count once it has settled. Before, it fed everything at once and read the count while the decode
     * thread still drew; where the cut fell depended on how far that thread had got (15 or 16 expected, sometimes
     * neither).
     */
    @Test
    void aBacklogKeepsItsNewestKeyFrame() throws Exception {
        TestStreams.Stream stream = TestStreams.h264(320, 240, 35, 12);
        HoldingSink sink = new HoldingSink();
        AtomicInteger keyRequests = new AtomicInteger();
        try (VideoPipeline p = new VideoPipeline(sink, () -> 30)) {
            p.setDrawEveryFrame(true);
            p.setKeyFrameSender(keyRequests::incrementAndGet);
            p.onVideoConfig(320, 240, false, false, stream.codecConfig());
            assertTrue(sink.started.await(5, TimeUnit.SECONDS), "the decoder is made");
            feed(p, stream.packets().get(0), (short) 1);
            // the decode thread sits in targetSize with frame 1 (waited for: a fixed 200 ms sleep was too short under
            // load - frame 1 still queued was then cut with the backlog, 11 shown instead of 12)
            assertTrue(sink.holding.await(5, TimeUnit.SECONDS), "frame 1 is held");
            for (int i = 1; i < 35; i++) feed(p, stream.packets().get(i), (short) (i + 1));
            sink.hold = false;
            sink.held.countDown();
            long end = System.currentTimeMillis() + 5000;
            while (p.framesDone() < 12 && System.currentTimeMillis() < end) Thread.sleep(20); // 1, then 25-35
            int shown = sink.presented.get();
            assertEquals(12, shown, "frame 1, then 25-35");
        }
    }

    /**
     * A backlog with no whole key frame in it is cut all the same (1.0.49): before, its frames were put back behind
     * the FLUSH, nothing went, and every frame after it put another FLUSH in.
     */
    @Test
    void aBacklogWithoutAKeyFrameIsStillCut() throws Exception {
        TestStreams.Stream stream = TestStreams.h264(320, 240, 45, 45); // one key frame, at the start
        HoldingSink sink = new HoldingSink();
        try (VideoPipeline p = new VideoPipeline(sink, () -> 30)) {
            p.setKeyFrameSender(() -> { });
            p.onVideoConfig(320, 240, false, false, stream.codecConfig());
            assertTrue(sink.started.await(5, TimeUnit.SECONDS));
            feed(p, stream.packets().get(0), (short) 1);
            // the decode thread sits in targetSize with frame 1 (waited for: a fixed 200 ms sleep was too short under
            // load - frame 1 still queued was then cut with the backlog, 11 shown instead of 12)
            assertTrue(sink.holding.await(5, TimeUnit.SECONDS), "frame 1 is held");
            for (int i = 1; i < 45; i++) feed(p, stream.packets().get(i), (short) (i + 1));
            assertTrue(p.queuedForDecoder() <= VideoPipeline.MAX_BACKLOG, "cut back: " + p.queuedForDecoder());
            sink.hold = false;
            sink.held.countDown();
        }
    }

    /** A restarted stream whose config never comes: asked for after CONFIG_ASK_MS, and decoded under the old one after CONFIG_WAIT_MS. */
    @Test
    void aMissingConfigIsAskedFor() throws Exception {
        TestStreams.Stream stream = TestStreams.h264(320, 240, 40, 10);
        HoldingSink sink = new HoldingSink();
        sink.hold = false;
        AtomicInteger configRequests = new AtomicInteger();
        try (VideoPipeline p = new VideoPipeline(sink, () -> 30)) {
            p.setDrawEveryFrame(true); // every picture counted, bunched or not
            p.setConfigRequester(configRequests::incrementAndGet);
            p.setKeyFrameSender(() -> { });
            p.onVideoConfig(320, 240, false, false, stream.codecConfig());
            short num = 0;
            for (int i = 0; i < 10; i++) feed(p, stream.packets().get(i), ++num);
            long end = System.currentTimeMillis() + 3000;
            while (sink.presented.get() < 10 && System.currentTimeMillis() < end) Thread.sleep(20);
            assertEquals(10, sink.presented.get());
            assertEquals(0, configRequests.get());
            // the phone restarts its stream (key frame 1 again) and its VideoInitialFrame goes lost
            num = 0;
            long start = System.currentTimeMillis();
            // from another key frame: a new stream's key frame 1 is never the old one's bytes. Fed until the pictures show
            // (1.0.64: was until 1.3 s - on a busy PC, with each 33 ms a little longer, that was too few frames after the
            // key frame the held ones are let go from, and 19 or 20 showed for the 25 asked)
            for (int i = 0; i < 120 && (sink.presented.get() < 25 || System.currentTimeMillis() - start <= VideoPipeline.CONFIG_WAIT_MS + 300); i++) {
                feed(p, stream.packets().get(10 + i % 30), ++num);
                Thread.sleep(33);
            }
            assertTrue(configRequests.get() >= 1, "the config was asked for");
            end = System.currentTimeMillis() + 2000;
            while (sink.presented.get() < 25 && System.currentTimeMillis() < end) Thread.sleep(20);
            assertTrue(sink.presented.get() >= 25, "and the new stream shows under the old config meanwhile: " + sink.presented.get());
        }
    }

    /**
     * The config in hand comes again (GetVideoConfig's cached copy after a late original): the decoder goes on - it
     * was rebuilt, and the picture froze until the next key frame (here none: all 20 frames are one group).
     */
    @Test
    void theSameConfigAgainKeepsTheDecoder() throws Exception {
        TestStreams.Stream stream = TestStreams.h264(320, 240, 20, 20);
        HoldingSink sink = new HoldingSink();
        sink.hold = false;
        AtomicInteger made = new AtomicInteger();
        try (VideoPipeline p = new VideoPipeline(sink, () -> 30)) {
            p.setDrawEveryFrame(true);
            p.setKeyFrameSender(() -> { });
            p.setDecoderFactory((hevc, config, hw) -> {
                made.incrementAndGet();
                return new VideoDecoder(hevc, config, false);
            });
            p.onVideoConfig(320, 240, false, false, stream.codecConfig());
            short num = 0;
            for (int i = 0; i < 10; i++) feed(p, stream.packets().get(i), ++num);
            long end = System.currentTimeMillis() + 3000;
            while (sink.presented.get() < 10 && System.currentTimeMillis() < end) Thread.sleep(20);
            p.onVideoConfig(320, 240, false, false, stream.codecConfig().clone());
            for (int i = 10; i < 20; i++) feed(p, stream.packets().get(i), ++num);
            end = System.currentTimeMillis() + 3000;
            while (sink.presented.get() < 20 && System.currentTimeMillis() < end) Thread.sleep(20);
            assertEquals(20, sink.presented.get(), "frames 11-20 decoded on the same decoder");
            assertEquals(1, made.get(), "one decoder");
        }
    }

    /** No decoder could be made at the config: tried again at the next key frame (before: nothing until the next config). */
    @Test
    void aDecoderThatCouldNotBeMadeIsTriedAgainAtTheNextKeyFrame() throws Exception {
        TestStreams.Stream stream = TestStreams.h264(320, 240, 20, 10);
        HoldingSink sink = new HoldingSink();
        sink.hold = false;
        AtomicInteger made = new AtomicInteger();
        try (VideoPipeline p = new VideoPipeline(sink, () -> 30)) {
            p.setDrawEveryFrame(true);
            p.setKeyFrameSender(() -> { });
            p.setDecoderFactory((hevc, config, hw) -> {
                if (made.incrementAndGet() == 1) throw new IllegalStateException("Could not open the decoder (-1)");
                return new VideoDecoder(hevc, config, false);
            });
            p.onVideoConfig(320, 240, false, false, stream.codecConfig());
            short num = 0;
            long end = System.currentTimeMillis() + 3000;
            for (int i = 0; i < 20; i++) {
                feed(p, stream.packets().get(i), ++num);
                while (p.framesDone() <= i && System.currentTimeMillis() < end) Thread.sleep(2); // one at a time
            }
            while (sink.presented.get() < 20 && System.currentTimeMillis() < end) Thread.sleep(20);
            assertEquals(20, sink.presented.get(), "from key frame 1 on, with the second decoder");
            assertEquals(2, made.get());
        }
    }

    /**
     * The decoder stands still while frames keep coming at their pace: once the oldest waiting is a second old, the
     * queue is cut back to its newest key frame (11) - a count of frames alone (30) would let it drift on, every
     * frame shown late.
     */
    @Test
    void aDecoderFarBehindIsCutBackByAgeNotOnlyByCount() throws Exception {
        TestStreams.Stream stream = TestStreams.h264(320, 240, 20, 10);
        HoldingSink sink = new HoldingSink();
        try (VideoPipeline p = new VideoPipeline(sink, () -> 30)) {
            p.setDrawEveryFrame(true);
            p.setKeyFrameSender(() -> { });
            p.onVideoConfig(320, 240, false, false, stream.codecConfig());
            short num = 0;
            for (int i = 0; i < 20; i++) { // 15 fps: 1.4 s, never 30 waiting
                feed(p, stream.packets().get(i), ++num);
                Thread.sleep(70);
            }
            sink.hold = false;
            sink.held.countDown();
            long end = System.currentTimeMillis() + 3000;
            while (sink.presented.get() < 11 && System.currentTimeMillis() < end) Thread.sleep(20);
            Thread.sleep(300);
            assertTrue(p.decoderBehindDrops() >= 1, "cut back by age");
            int shown = sink.presented.get();
            assertTrue(shown >= 11 && shown <= 12, "frame 1, then 11-20 (2-10 were a second late): " + shown);
        }
    }

    /** A key frame that gives no picture (the config in hand isn't the stream's): the config and a key frame are asked for. */
    @Test
    void aKeyFrameWithoutAPictureAsksForTheConfig() throws Exception {
        TestStreams.Stream stream = TestStreams.h264(320, 240, 20, 10);
        HoldingSink sink = new HoldingSink();
        sink.hold = false;
        AtomicInteger configRequests = new AtomicInteger();
        AtomicInteger keyRequests = new AtomicInteger();
        try (VideoPipeline p = new VideoPipeline(sink, () -> 30)) {
            p.setConfigRequester(configRequests::incrementAndGet);
            p.setKeyFrameSender(keyRequests::incrementAndGet);
            p.onVideoConfig(320, 240, false, false, new byte[0]); // no SPS/PPS: nothing decodes
            short num = 0;
            long end = System.currentTimeMillis() + 3000;
            for (int i = 0; i < 20; i++) {
                feed(p, stream.packets().get(i), ++num);
                while (p.framesDone() <= i && System.currentTimeMillis() < end) Thread.sleep(2); // one at a time
            }
            while (configRequests.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(20);
            assertTrue(configRequests.get() >= 1, "the config was asked for");
            assertTrue(keyRequests.get() >= 1, "and a key frame");
            assertEquals(0, sink.presented.get());
        }
    }
}
