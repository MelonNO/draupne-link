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
        volatile boolean hold = true;

        @Override
        public void videoStarted(int width, int height, boolean mirror) {
        }

        @Override
        public int[] targetSize() {
            if (hold) {
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
     * 35 frames queue up while the screen is held (key frames every 10): the backlog is cut back to its newest
     * whole key frame, not thrown away - the pictures from frame 21 on are shown, not only those from the next
     * key frame to come (31).
     */
    @Test
    void aBacklogKeepsItsNewestKeyFrame() throws Exception {
        TestStreams.Stream stream = TestStreams.h264(320, 240, 35, 10);
        HoldingSink sink = new HoldingSink();
        AtomicInteger keyRequests = new AtomicInteger();
        try (VideoPipeline p = new VideoPipeline(sink, () -> 30)) {
            p.setDrawEveryFrame(true);
            p.setKeyFrameSender(keyRequests::incrementAndGet);
            p.onVideoConfig(320, 240, false, false, stream.codecConfig());
            short num = 0;
            for (TestStreams.Packet packet : stream.packets()) feed(p, packet, ++num);
            Thread.sleep(200); // the decode thread sits in targetSize with frame 1; everything else is queued
            sink.hold = false;
            sink.held.countDown();
            long end = System.currentTimeMillis() + 5000;
            while (sink.presented.get() < 15 && System.currentTimeMillis() < end) Thread.sleep(20);
            // from the newest key frame in the backlog (21) to 35: 15 pictures (16 with frame 1, when the decode
            // thread had taken it before the rest came)
            int shown = sink.presented.get();
            assertTrue(shown == 15 || shown == 16, "21-35 (and maybe 1); a thrown-away backlog would show 31-35 only: " + shown);
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
            for (int i = 0; i < 40; i++) { // from another key frame: a new stream's key frame 1 is never the old one's bytes
                feed(p, stream.packets().get(10 + i % 30), ++num);
                Thread.sleep(33);
                if (configRequests.get() > 0 && System.currentTimeMillis() - start > VideoPipeline.CONFIG_WAIT_MS + 300) break;
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
            for (int i = 0; i < 20; i++) {
                feed(p, stream.packets().get(i), ++num);
                Thread.sleep(10);
            }
            long end = System.currentTimeMillis() + 3000;
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
            for (int i = 0; i < 20; i++) {
                feed(p, stream.packets().get(i), ++num);
                Thread.sleep(33);
            }
            long end = System.currentTimeMillis() + 2000;
            while (configRequests.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(20);
            assertTrue(configRequests.get() >= 1, "the config was asked for");
            assertTrue(keyRequests.get() >= 1, "and a key frame");
            assertEquals(0, sink.presented.get());
        }
    }
}
