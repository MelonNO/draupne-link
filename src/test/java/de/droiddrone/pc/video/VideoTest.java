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
