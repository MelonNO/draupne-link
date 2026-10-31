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

    /** A key frame whose last packet was lost: out (damaged) before the newer frame, not dropped without a word. */
    @Test
    void keyFrameThatLostItsLastPacketGoesOutBeforeTheNextFrame() {
        FrameAssembler a = new FrameAssembler();
        byte[] whole = bytes(0, 300);
        assertNull(a.put(true, (short) 10, 0, 300, whole, 0, 100));
        assertNull(a.put(true, (short) 10, 100, 0, whole, 100, 100)); // its last 100 bytes never come
        FrameAssembler.Frame next = a.put(false, (short) 11, 0, 50, whole, 0, 50);
        assertNotNull(next);
        List<FrameAssembler.Frame> earlier = a.takeFlushed();
        assertEquals(1, earlier.size(), "the key frame goes out first: the next frames are built on it");
        assertTrue(earlier.get(0).keyFrame());
        assertTrue(earlier.get(0).damaged());
        assertEquals(10, earlier.get(0).number());
        assertEquals(300, earlier.get(0).data().length);
        assertEquals(whole[150], earlier.get(0).data()[150]);
        assertEquals(0, a.getLostFrames());
        assertTrue(a.takeFlushed().isEmpty(), "taken once");
        assertNull(a.put(true, (short) 10, 200, 0, whole, 200, 100), "too late for frame 10 now");
    }

    @Test
    void newerFrameReleasesOlderOnesOldestFirstAndLosesThoseWithoutABeginning() {
        FrameAssembler a = new FrameAssembler();
        byte[] whole = bytes(0, 200);
        a.put(false, (short) 9, 100, 0, whole, 100, 100);         // frame 9: only its end - can't be decoded
        a.put(false, (short) 10, 0, 200, whole, 0, 100);          // frame 10: beginning only
        a.put(false, (short) 8, 0, 200, whole, 0, 100);           // frame 8: beginning only
        assertNotNull(a.put(false, (short) 11, 0, 50, whole, 0, 50));
        List<FrameAssembler.Frame> earlier = a.takeFlushed();
        assertEquals(List.of((short) 8, (short) 10), earlier.stream().map(FrameAssembler.Frame::number).toList());
        assertEquals(1, a.getLostFrames(), "frame 9");
        assertNull(a.put(false, (short) 10, 100, 0, whole, 100, 100), "too late for frame 10");
    }

    /**
     * Why a damaged key frame goes out rather than being dropped: the decoder decodes it up to the gap and conceals the
     * rest, and the frames after it stay much closer to the real picture than when the key frame is left out (what
     * happened when its last packet was lost). Measured with a detailed test picture, H.264, 1000 bytes lost.
     */
    @Test
    void aDamagedKeyFrameBeatsLeavingItOut() {
        TestStreams.Stream s = TestStreams.h264(320, 240, 30, 10, true);
        int damagedAt = 10; // the second key frame
        TestStreams.Packet key = s.packets().get(damagedAt);
        assertTrue(key.keyFrame() && key.data().length > 2000, "a key frame of several packets");
        byte[] damaged = key.data().clone();
        java.util.Arrays.fill(damaged, damaged.length / 3, damaged.length / 3 + 1000, (byte) 0);
        for (int upTo : new int[]{12, 15}) {
            int[] truth = decodeSequence(s, upTo, damagedAt, null, false);
            long decoded = difference(truth, decodeSequence(s, upTo, damagedAt, damaged, false));
            long leftOut = difference(truth, decodeSequence(s, upTo, damagedAt, null, true));
            assertTrue(decoded < leftOut * 0.85, "frame " + upTo + ": damaged key frame " + decoded + ", left out " + leftOut);
        }
    }

    /** Frames 0..last; frame damagedAt replaced (damaged) or left out (skip). */
    private static int[] decodeSequence(TestStreams.Stream s, int last, int damagedAt, byte[] damaged, boolean skip) {
        try (VideoDecoder d = new VideoDecoder(false, s.codecConfig())) {
            for (int i = 0; i <= last; i++) {
                if (i == damagedAt && skip) continue;
                byte[] f = i == damagedAt && damaged != null ? damaged : s.packets().get(i).data();
                d.decode(f, f.length);
            }
            int[] px = new int[320 * 240];
            d.convert(px, 320, 240);
            return px;
        }
    }

    private static long difference(int[] a, int[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            for (int sh = 0; sh <= 16; sh += 8) sum += Math.abs(((a[i] >> sh) & 255) - ((b[i] >> sh) & 255));
        }
        return sum;
    }

    @Test
    void aNewKeyFrameMakesOlderUnfinishedFramesLost() {
        FrameAssembler a = new FrameAssembler();
        byte[] whole = bytes(0, 200);
        a.put(false, (short) 10, 0, 200, whole, 0, 100);
        assertNotNull(a.put(true, (short) 11, 0, 50, whole, 0, 50));
        assertTrue(a.takeFlushed().isEmpty(), "a key frame needs nothing before it");
        assertEquals(1, a.getLostFrames());
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
        for (int i = 0; i < 140; i++, t += 17) b.onFrame(i % 30 == 0, t, 60, 50); // ~2.4 s of clean 60 fps
        assertTrue(changes.isEmpty(), "clean stream: no change yet");
        for (int i = 0; i < 7; i++) b.onBadFrame(false); // 7 frames lost in a second: real trouble
        b.onFrame(false, t += 17, 60, 50);
        assertEquals(List.of(false), changes);
        for (int i = 0; i < 7; i++) b.onBadFrame(false);
        b.onFrame(false, t += 17, 60, 50);
        assertEquals(1, changes.size(), "decreases are at least 2 s apart");
        for (int i = 0; i < 300; i++, t += 17) b.onFrame(i % 30 == 0, t, 60, 50); // ~5 s clean
        assertEquals(Boolean.TRUE, changes.get(changes.size() - 1), "raised after 3 s of calm (was 10 s)");
    }

    @Test
    void lateFramesLowerTheBitrateButAPingSpikeAloneDoesNot() {
        List<Boolean> changes = new ArrayList<>();
        BitrateControl b = new BitrateControl(changes::add);
        b.onFrame(true, 0, 60, 50);
        b.onFrame(true, 2500, 60, 50); // 2.5 s gap
        assertEquals(List.of(false), changes);
        b.onFrame(true, 5000, 60, 400); // one ping of 400 ms: far 4G jitter, not a queue
        assertEquals(List.of(false), changes);
    }

    /** The far 4G link of 2026-10-31: ping 100-200 ms with spikes, two damaged key frames a second. */
    @Test
    void aFarLinkWithDamagedKeyFramesIsNotPinnedToTheBottom() {
        List<Boolean> changes = new ArrayList<>();
        BitrateControl b = new BitrateControl(changes::add);
        long t = 0;
        java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < 60 * 60; i++, t += 17) { // a minute at 60 fps
            int ping = 100 + r.nextInt(100) + (i % 600 == 300 ? 700 : 0); // a spike every 10 s
            if (i % 30 == 0) b.onBadFrame(true); // each key frame arrives damaged
            b.onFrame(i % 30 == 0, t, 60, ping);
        }
        long down = changes.stream().filter(c -> !c).count();
        assertTrue(down <= 1, "lowered " + down + " times");
        assertTrue(b.step() >= BitrateControl.FLOOR_STEP, "never below 1 Mbit/s from the PC");
        assertTrue(changes.contains(true), "raised while it holds");
    }

    @Test
    void thePhonesOwnReportSetsTheStep() {
        BitrateControl b = new BitrateControl(c -> { });
        b.onPhoneTarget(500_000);
        assertEquals(1, b.step());
        b.onPhoneTarget(6_000_000);
        assertEquals(5, b.step());
        b.onPhoneTarget(20_000_000);
        assertEquals(5, b.step(), "above the table: the top");
        b.onPhoneTarget(-1);
        assertEquals(5, b.step(), "no report: unchanged");
    }

    @Test
    void keyFramesComingRarelyAreNoTrouble() {
        List<Boolean> changes = new ArrayList<>();
        BitrateControl b = new BitrateControl(changes::add);
        long t = 0;
        for (int i = 0; i < 30 * 12; i++, t += 33) b.onFrame(i == 0, t, 30, 100); // 12 s, one key frame
        assertFalse(changes.contains(false), "pc.13 sends key frames every 2-10 s");
    }

    /** The PC thought the phone was on 4-6 Mbit/s while 0.5 arrived: what arrives decides. */
    @Test
    void littleArrivingAsksThePhoneUpWhateverThePcCounted() {
        List<Boolean> changes = new ArrayList<>();
        BitrateControl b = new BitrateControl(changes::add);
        long t = 0;
        for (int i = 0; i < 60 * 8; i++, t += 17) b.onFrame(i % 30 == 0, t, 60, 100); // calm: raised to the top
        assertTrue(b.step() > BitrateControl.FLOOR_STEP);
        changes.clear();
        for (int s = 0; s < 4; s++) b.onReceivedRate(0.4, t + s * 1000L);
        assertTrue(changes.isEmpty(), "not before " + BitrateControl.RESCUE_MS + " ms");
        for (int s = 4; s < 11; s++) b.onReceivedRate(0.4, t + s * 1000L);
        assertEquals(List.of(true, true), changes, "asked up every " + BitrateControl.RESCUE_MS + " ms");
        assertEquals(BitrateControl.FLOOR_STEP, b.step(), "counted as at the floor: the PC won't lower it further");
        changes.clear();
        b.onReceivedRate(1.2, t + 11_000);
        b.onReceivedRate(0.4, t + 12_000);
        assertTrue(changes.isEmpty(), "enough arrived: starts counting again");
    }

    @Test
    void neverLowersBelowTheFloorAndAsksThePhoneUpWhenItWentLowerByItself() {
        List<Boolean> changes = new ArrayList<>();
        BitrateControl b = new BitrateControl(changes::add);
        long t = 0;
        for (int i = 0; i < 20; i++, t += 2500) { // a 2.5 s gap every time: trouble each time
            b.onFrame(true, t, 30, 100);
        }
        assertEquals(BitrateControl.FLOOR_STEP, b.step());
        assertEquals(BitrateControl.START_STEP - BitrateControl.FLOOR_STEP, changes.stream().filter(c -> !c).count());
        changes.clear();
        for (int s = 0; s < 6; s++) b.onReceivedRate(0.25, t + s * 1000L); // the phone sits at 0.25 by itself
        assertEquals(List.of(true), changes, "asked up after " + BitrateControl.RESCUE_MS + " ms");
    }

    @Test
    void risingPingLowersTheBitrateBeforeFramesAreLost() {
        List<Boolean> changes = new ArrayList<>();
        BitrateControl b = new BitrateControl(changes::add);
        long t = 0;
        for (int i = 0; i < 180; i++, t += 17) b.onFrame(i % 30 == 0, t, 60, 70 + i % 20); // ~3 s at 70-90 ms
        b.onFrame(false, t += 17, 60, 70 + BitrateControl.QUEUE_DELAY_MS + 30); // one slow ping: 4G jitter
        for (int i = 0; i < 10; i++, t += 17) b.onFrame(false, t, 60, 80);
        assertFalse(changes.contains(false), "a single slow ping is ignored");
        int before = changes.size();
        long until = t + BitrateControl.QUEUE_HOLD_MS + 100;
        for (; t < until; t += 17) b.onFrame(false, t, 60, 180); // the uplink queues: ping stays ~110 ms higher
        assertEquals(List.of(false), changes.subList(before, changes.size()), "lowered while still under upstream's 300 ms limit");
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
