package de.droiddrone.pc.video;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VideoTest {
    private static byte[] bytes(int from, int count) {
        byte[] b = new byte[count];
        for (int i = 0; i < count; i++) b[i] = (byte) (from + i);
        return b;
    }

    // ------------------------------------------------------------------ FrameAssembler

    @Test
    void singlePacketFrameIsReleasedAtOnce() {
        FrameAssembler a = new FrameAssembler();
        byte[] data = bytes(0, 100);
        FrameAssembler.Frame f = a.put(true, (short) 1, 0, 100, data, 0, 100);
        assertNotNull(f);
        assertArrayEquals(data, f.data());
        assertTrue(f.keyFrame());
        assertFalse(f.damaged());
    }

    @Test
    void fragmentsInAnyOrderMakeTheFrame() {
        FrameAssembler a = new FrameAssembler();
        byte[] whole = bytes(0, 250);
        assertNull(a.put(false, (short) 7, 200, 0, whole, 200, 50));   // end first
        assertNull(a.put(false, (short) 7, 100, 0, whole, 100, 100));
        FrameAssembler.Frame f = a.put(false, (short) 7, 0, 250, whole, 0, 100); // start (with size) last
        assertNotNull(f, "upstream would have thrown away fragments that came before the first one");
        assertArrayEquals(whole, f.data());
        assertFalse(f.damaged());
    }

    @Test
    void missingMiddleIsNotWaitedFor() {
        FrameAssembler a = new FrameAssembler();
        byte[] whole = bytes(0, 300);
        assertNull(a.put(false, (short) 3, 0, 300, whole, 0, 100));
        FrameAssembler.Frame f = a.put(false, (short) 3, 200, 0, whole, 200, 100);
        assertNotNull(f, "start and end are in: release now, the decoder conceals the gap");
        assertTrue(f.damaged());
        assertEquals(0, f.data()[150], "gap is zero-filled");
        assertEquals(1, a.getDamagedFrames());
    }

    @Test
    void newerFrameDropsUnfinishedOlderOnes() {
        FrameAssembler a = new FrameAssembler();
        byte[] whole = bytes(0, 200);
        a.put(false, (short) 10, 0, 200, whole, 0, 100);          // frame 10 never finishes
        assertNotNull(a.put(false, (short) 11, 0, 50, whole, 0, 50));
        assertEquals(1, a.getLostFrames());
        assertNull(a.put(false, (short) 10, 100, 0, whole, 100, 100), "too late for frame 10");
    }

    @Test
    void lateAndDuplicateFragmentsAreDropped() {
        FrameAssembler a = new FrameAssembler();
        byte[] whole = new byte[300];
        assertNotNull(a.put(false, (short) 5, 0, 50, whole, 0, 50));
        assertNull(a.put(false, (short) 5, 0, 50, whole, 0, 50), "the same datagram twice: not decoded twice");
        assertNull(a.put(false, (short) 4, 0, 50, whole, 0, 50), "an older frame after a newer one: not decoded");
        // frame 6 released with its middle missing; its middle comes late: no zombie, nothing counted lost for it
        assertNull(a.put(false, (short) 6, 0, 300, whole, 0, 100));
        assertNotNull(a.put(false, (short) 6, 200, 0, whole, 200, 100));
        long lost = a.getLostFrames();
        assertNull(a.put(false, (short) 6, 100, 0, whole, 100, 100));
        assertNotNull(a.put(false, (short) 7, 0, 50, whole, 0, 50));
        assertEquals(lost, a.getLostFrames());
    }

    @Test
    void aRestartedStreamStartsAgainAtKeyFrameOne() {
        FrameAssembler a = new FrameAssembler();
        byte[] whole = new byte[100];
        assertNotNull(a.put(false, (short) 300, 0, 50, whole, 0, 50));
        FrameAssembler.Frame f = a.put(true, (short) 1, 0, 50, whole, 0, 50);
        assertNotNull(f, "frame 1 after 300: a new stream, not an old frame");
        assertTrue(f.streamStart());
        assertTrue(a.startedAfresh(), "the assembler already holds the new stream");
        assertNotNull(a.put(false, (short) 2, 0, 50, whole, 0, 50));
    }

    @Test
    void absurdFrameSizesAreNotAllocated() {
        assertNull(new FrameAssembler().put(true, (short) 1, 0, Integer.MAX_VALUE, new byte[10], 0, 10));
    }

    @Test
    void hardwareDecodingGivesTheSamePictureOrFallsBackToSoftware() {
        TestStreams.Stream s = TestStreams.h264(320, 240, 12, 6);
        try (VideoDecoder d = new VideoDecoder(false, s.codecConfig(), true)) {
            boolean any = false;
            for (TestStreams.Packet p : s.packets()) any |= d.decode(p.data(), p.data().length);
            assertTrue(any);
            int[] px = new int[320 * 240];
            d.convert(px, 320, 240);
            int c = px[120 * 320 + 160] & 0xffffff;
            assertTrue(((c >> 16) & 255) > 200 && ((c >> 8) & 255) < 60 && (c & 255) < 60, "red, on the GPU or not: " + Integer.toHexString(c));
            System.out.println("hardware decoding on this PC: " + d.isHardware());
        }
    }

    @Test
    void frameNumbersWrapAround() {
        assertTrue(FrameAssembler.isOlder((short) 32766, (short) 2));
        assertFalse(FrameAssembler.isOlder((short) 2, (short) 32766));
        assertTrue(FrameAssembler.isOlder((short) 5, (short) 6));
        assertFalse(FrameAssembler.isOlder((short) 6, (short) 6));
    }

    // ------------------------------------------------------------------ BitrateControl

    @Test
    void bitrateGoesDownOnTroubleAndUpWhenCalm() {
        List<Boolean> changes = new ArrayList<>();
        BitrateControl b = new BitrateControl(changes::add);
        long t = 0;
        for (int i = 0; i < 200; i++, t += 17) b.onFrame(i % 30 == 0, t, 60, 50); // ~3.4 s of clean 60 fps
        assertTrue(changes.isEmpty(), "clean stream: no change yet");
        b.onBadFrame(true); // a lost key frame counts 5
        b.onFrame(false, t += 17, 60, 50);
        assertEquals(List.of(false), changes);
        b.onBadFrame(true);
        b.onFrame(false, t += 17, 60, 50);
        assertEquals(1, changes.size(), "decreases are at least 2 s apart");
        for (int i = 0; i < 700; i++, t += 17) b.onFrame(i % 30 == 0, t, 60, 50); // ~12 s clean
        assertEquals(Boolean.TRUE, changes.get(changes.size() - 1));
    }

    @Test
    void lateFramesAndHighPingLowerTheBitrate() {
        List<Boolean> changes = new ArrayList<>();
        BitrateControl b = new BitrateControl(changes::add);
        b.onFrame(true, 0, 60, 50);
        b.onFrame(true, 2500, 60, 50); // 2.5 s gap
        assertEquals(List.of(false), changes);
        b.onFrame(true, 5000, 60, 400); // ping 400 ms
        assertEquals(List.of(false, false), changes);
    }

    @Test
    void risingPingLowersTheBitrateBeforeFramesAreLost() {
        List<Boolean> changes = new ArrayList<>();
        BitrateControl b = new BitrateControl(changes::add);
        long t = 0;
        for (int i = 0; i < 180; i++, t += 17) b.onFrame(i % 30 == 0, t, 60, 70 + i % 20); // ~3 s at 70-90 ms
        b.onFrame(false, t += 17, 60, 70 + BitrateControl.QUEUE_DELAY_MS + 30); // one slow ping: 4G jitter
        for (int i = 0; i < 10; i++, t += 17) b.onFrame(false, t, 60, 80);
        assertTrue(changes.isEmpty(), "a single slow ping is ignored");
        long until = t + BitrateControl.QUEUE_HOLD_MS + 100;
        for (; t < until; t += 17) b.onFrame(false, t, 60, 180); // the uplink queues: ping stays ~110 ms higher
        assertEquals(List.of(false), changes, "lowered while still under upstream's 300 ms limit");
    }

    // ------------------------------------------------------------------ LatencyMeter

    /** A grey "camera picture" of the screen with the code painted at (x, y), modules of the given size. */
    private static int[] frameWithCode(com.google.zxing.common.BitMatrix code, int w, int h, int x, int y, int scale) {
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, 0x404040);
        int size = (code.getWidth() + 8) * scale;
        for (int yy = 0; yy < size; yy++) {
            for (int xx = 0; xx < size; xx++) {
                int mx = xx / scale - 4, my = yy / scale - 4;
                boolean dark = mx >= 0 && my >= 0 && mx < code.getWidth() && my < code.getHeight() && code.get(mx, my);
                px[(y + yy) * w + x + xx] = dark ? 0x101010 : 0xf0f0f0;
            }
        }
        return px;
    }

    @Test
    void latencyMeterReadsTheTimeCodeBack() {
        try (LatencyMeter m = new LatencyMeter()) {
            long painted = System.nanoTime(); // the code's own time is a little later (encoding it takes a moment)
            var code = m.codeNow();
            int[] frame = frameWithCode(code, 640, 360, 100, 40, 9);
            double ms = m.read(frame, 640, 360, painted + 120_000_000L);
            assertTrue(ms > 100 && ms <= 120.1, "expected just under 120 ms, got " + ms);
            assertTrue(Double.isNaN(m.read(new int[640 * 360], 640, 360, painted)), "no code in the picture");
        }
    }

    @Test
    void latencyMeterWorksOnLargeFramesInTheBackground() throws Exception {
        try (LatencyMeter m = new LatencyMeter()) {
            long painted = System.nanoTime();
            var code = m.codeNow();
            int[] frame = frameWithCode(code, 1600, 900, 300, 100, 22); // copied at half size
            m.offer(frame, 1600, 900, painted + 80_000_000L);
            for (int i = 0; i < 200 && m.result().count() == 0; i++) Thread.sleep(10);
            assertEquals(1, m.result().count());
            assertTrue(m.result().medianMs() > 60 && m.result().medianMs() <= 80.1, m.toString());
        }
    }

    // ------------------------------------------------------------------ VideoDecoder (real FFmpeg)

    @Test
    void decodesRealH264AndConvertsColours() {
        TestStreams.Stream s = TestStreams.h264(320, 240, 10, 5);
        assertTrue(s.packets().get(0).keyFrame());
        int pictures = 0;
        try (VideoDecoder d = new VideoDecoder(false, s.codecConfig())) {
            int[] rgb = new int[160 * 120];
            for (TestStreams.Packet p : s.packets()) {
                if (d.decode(p.data(), p.data().length)) {
                    pictures++;
                    assertEquals(320, d.width());
                    d.convert(rgb, 160, 120); // scaled down while converting
                }
            }
            int px = rgb[60 * 160 + 80];
            int r = (px >> 16) & 0xff, g = (px >> 8) & 0xff, b = px & 0xff;
            assertTrue(r > 200 && g < 60 && b < 60, "expected red, got " + Integer.toHexString(px));
        }
        assertEquals(10, pictures, "LOW_DELAY: one picture out per frame in, nothing held back");
    }

    @Test
    void decodeAndDrawTimeAt720p() {
        TestStreams.Stream s = TestStreams.h264(1280, 720, 60, 30, true);
        long decodeNs = 0, convertNs = 0;
        int n = 0;
        try (VideoDecoder d = new VideoDecoder(false, s.codecConfig())) {
            int[] rgb = new int[1280 * 720];
            for (TestStreams.Packet p : s.packets()) {
                long t0 = System.nanoTime();
                boolean got = d.decode(p.data(), p.data().length);
                long t1 = System.nanoTime();
                if (!got) continue;
                d.convert(rgb, 1280, 720);
                long t2 = System.nanoTime();
                if (n++ >= 5) { // skip warm-up
                    decodeNs += t1 - t0;
                    convertNs += t2 - t1;
                }
            }
        }
        double dec = decodeNs / 1e6 / (n - 5), conv = convertNs / 1e6 / (n - 5);
        long bytes = s.packets().stream().mapToLong(p -> p.data().length).sum();
        System.out.printf("720p H.264 (textured, %.1f Mbit/s at 30 fps): decode %.2f ms, convert+copy %.2f ms per frame%n",
                bytes * 8.0 * 30 / s.packets().size() / 1e6, dec, conv);
        assertTrue(dec + conv < 16, "must keep up with 60 fps with room to spare");
    }

    @Test
    void decoderWaitsForKeyFrameNotGarbage() {
        TestStreams.Stream s = TestStreams.h264(160, 120, 6, 6);
        try (VideoDecoder d = new VideoDecoder(false, s.codecConfig())) {
            byte[] junk = new byte[64];
            Arrays.fill(junk, (byte) 0x55);
            assertFalse(d.decode(junk, junk.length), "garbage must not crash or produce a picture");
            assertTrue(d.decode(s.packets().get(0).data(), s.packets().get(0).data().length));
        }
    }
}
