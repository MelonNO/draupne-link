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

    /**
     * A fragment of key frame 1 that comes again a few frames later (4G doubles and reorders): not a restart - before,
     * it threw away what was pending (frame 6's first half here) and released frame 1 once more after frame 5.
     */
    @Test
    void aLateFragmentOfKeyFrameOneIsNoRestart() {
        FrameAssembler a = new FrameAssembler();
        byte[] key = bytes(7, 300);
        assertNull(a.put(true, (short) 1, 0, 300, key, 0, 100, 1000));
        assertNull(a.put(true, (short) 1, 100, 0, key, 100, 100, 1000));
        assertNotNull(a.put(true, (short) 1, 200, 0, key, 200, 100, 1000));
        for (short n = 2; n <= 5; n++) assertNotNull(a.put(false, n, 0, 10, bytes(n, 10), 0, 10, 1000 + n * 33));
        byte[] six = bytes(6, 200);
        assertNull(a.put(false, (short) 6, 0, 200, six, 0, 100, 1200));
        assertNull(a.put(true, (short) 1, 100, 0, key, 100, 100, 1210), "the middle again");
        assertNull(a.put(true, (short) 1, 0, 300, key, 0, 100, 1215), "the start again");
        FrameAssembler.Frame f = a.put(false, (short) 6, 100, 0, six, 100, 100, 1220);
        assertNotNull(f, "frame 6's first half was kept");
        assertFalse(f.damaged());
        assertFalse(a.startedAfresh(), "no restart was seen");
        // a real restart is still one: a new key frame 1 of another size
        FrameAssembler.Frame restart = a.put(true, (short) 1, 0, 120, bytes(9, 120), 0, 120, 1300);
        assertNotNull(restart);
        assertTrue(restart.streamStart());
    }

    /**
     * After a long blackout the phone's counter may stand anywhere: a frame that looks up to ORDER_WINDOW behind the
     * last one released is taken once nothing has been released for ORDER_RESET_MS (before: dropped for good).
     */
    @Test
    void theOrderIsForgottenAfterALongGap() {
        FrameAssembler a = new FrameAssembler();
        assertNotNull(a.put(true, (short) 800, 0, 10, bytes(0, 10), 0, 10, 1000));
        assertNull(a.put(false, (short) 300, 0, 10, bytes(0, 10), 0, 10, 1500), "half a second later: an old frame");
        assertNotNull(a.put(false, (short) 300, 0, 10, bytes(0, 10), 0, 10, 1000 + FrameAssembler.ORDER_RESET_MS + 1),
                "after the gap: the stream goes on from here");
        assertNotNull(a.put(false, (short) 301, 0, 10, bytes(0, 10), 0, 10, 1000 + FrameAssembler.ORDER_RESET_MS + 34));
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

    /** A frame lost whole (every packet: a one-packet P-frame) is counted lost once a newer one is released (1.0.49). */
    @Test
    void aFrameLostWholeIsCounted() {
        FrameAssembler a = new FrameAssembler();
        for (short n = 1; n <= 3; n++) assertNotNull(a.put(n == 1, n, 0, 10, bytes(n, 10), 0, 10));
        assertNotNull(a.put(false, (short) 5, 0, 10, bytes(5, 10), 0, 10)); // 4 never came
        assertEquals(1, a.getLostFrames(), "frame 4: before 1.0.49 nothing counted it");
        assertNull(a.put(false, (short) 4, 0, 10, bytes(4, 10), 0, 10), "4 comes late (reordered): too late, not decoded");
        assertNotNull(a.put(false, (short) 6, 0, 10, bytes(6, 10), 0, 10));
        assertEquals(1, a.getLostFrames(), "counted once");
        // a frame with only some packets in, overtaken: lost once too (not again for the gap)
        assertNull(a.put(false, (short) 7, 100, 0, new byte[100], 0, 100)); // 7: its end only
        assertNotNull(a.put(false, (short) 9, 0, 10, bytes(9, 10), 0, 10)); // 8 never came
        assertEquals(3, a.getLostFrames(), "7 and 8");
    }

    @Test
    void lostFramesAcrossTheCounterWrapping() {
        FrameAssembler a = new FrameAssembler();
        assertNotNull(a.put(true, (short) 32766, 0, 10, bytes(0, 10), 0, 10));
        assertNotNull(a.put(false, (short) 32767, 0, 10, bytes(0, 10), 0, 10));
        assertNotNull(a.put(false, (short) 2, 0, 10, bytes(0, 10), 0, 10)); // pc.26 wraps to 2: 0 and 1 don't exist
        assertEquals(0, a.getLostFrames());
        assertNotNull(a.put(false, (short) 4, 0, 10, bytes(0, 10), 0, 10));
        assertEquals(1, a.getLostFrames());
        FrameAssembler b = new FrameAssembler();
        assertNotNull(b.put(true, (short) 32766, 0, 10, bytes(0, 10), 0, 10));
        assertNotNull(b.put(false, (short) 3, 0, 10, bytes(0, 10), 0, 10)); // 32767 and 2 lost
        assertEquals(2, b.getLostFrames());
        assertEquals(0, FrameAssembler.skipped((short) 32767, (short) 1), "older apps wrap to 1");
    }

    @Test
    void aRestartOrABlackoutCountsNothingLost() {
        FrameAssembler a = new FrameAssembler();
        assertNotNull(a.put(true, (short) 1, 0, 10, bytes(1, 10), 0, 10, 1000));
        assertNotNull(a.put(false, (short) 2, 0, 10, bytes(2, 10), 0, 10, 1033));
        assertNotNull(a.put(true, (short) 1, 0, 10, bytes(7, 10), 0, 10, 1066)); // a restart: from 1 again
        assertNotNull(a.put(false, (short) 2, 0, 10, bytes(8, 10), 0, 10, 1100));
        assertNotNull(a.put(false, (short) 200, 0, 10, bytes(9, 10), 0, 10, 1100 + FrameAssembler.ORDER_RESET_MS + 1));
        assertEquals(0, a.getLostFrames(), "after the order was forgotten the gap isn't counted frame by frame");
    }

    /**
     * M5: a late frame of the stream before a restart (here the old stream stood at 300; its frame 299 comes after the
     * new stream's 5) is dropped - before, it was released and every new frame up to 299 counted as older: the picture
     * froze until ORDER_RESET_MS.
     */
    @Test
    void aLateFrameOfTheOldStreamIsDropped() {
        FrameAssembler a = new FrameAssembler();
        long t = 1000;
        for (short n = 1; n <= 300; n++, t += 33) assertNotNull(a.put(n == 1, n, 0, 10, bytes(n, 10), 0, 10, t));
        assertNotNull(a.put(true, (short) 1, 0, 12, bytes(50, 12), 0, 12, t)); // restarted
        for (short n = 2; n <= 5; n++) assertNotNull(a.put(false, n, 0, 10, bytes(n, 10), 0, 10, t += 33));
        assertNull(a.put(false, (short) 299, 0, 10, bytes(1, 10), 0, 10, t + 5), "the old stream's, late");
        assertNull(a.put(false, (short) 60, 0, 10, bytes(1, 10), 0, 10, t + 6), "near the old stream's numbers, far ahead");
        for (short n = 6; n <= 10; n++) assertNotNull(a.put(false, n, 0, 10, bytes(n, 10), 0, 10, t += 33), "the new stream goes on: " + n);
        assertNull(a.put(false, (short) 5000, 0, 10, bytes(1, 10), 0, 10, t + 5), "anything far ahead");
        assertNotNull(a.put(false, (short) 40, 0, 10, bytes(1, 10), 0, 10, t + 1000), "a second's loss is no reason to drop");
    }

    @Test
    void aBrokenSizeIsNotAllocated() {
        FrameAssembler a = new FrameAssembler();
        assertNull(a.put(true, (short) 3, 0, 6 << 20, new byte[100], 0, 100), "6 MB: more than a 1080p picture raw");
        assertNull(a.put(true, (short) 3, 7 << 20, 0, new byte[100], 0, 100), "an offset beyond that too");
        for (short n = 10; n < 60; n++) a.put(false, n, 0, FrameAssembler.PLAUSIBLE_FRAME_BYTES, new byte[100], 0, 100);
        assertTrue(a.pendingBytes() <= FrameAssembler.PENDING_BUDGET, "50 broken sizes hold at most the budget: " + a.pendingBytes());
    }

    // ------------------------------------------------------------------ BitrateControl

    /** A request decided under BitrateControl's lock is sent after it is let go (the Swing thread reads step()). */
    @Test
    void bitrateRequestsAreSentOutsideTheLock() {
        BitrateControl[] b = new BitrateControl[1];
        List<Boolean> heldLock = new ArrayList<>();
        b[0] = new BitrateControl(new BitrateControl.Sink() {
            public void changeBitRate(boolean increase) {
                heldLock.add(Thread.holdsLock(b[0]));
            }

            public void setStep(int step, int from) {
                heldLock.add(Thread.holdsLock(b[0]));
            }
        });
        long t = 0;
        for (int i = 0; i < 300; i++, t += 17) b[0].onFrame(i % 30 == 0, t, 60, 50); // calm: raised
        for (int i = 0; i < 7; i++) b[0].onBadFrame(false);
        b[0].onFrame(false, t += 17, 60, 50); // trouble: lowered
        for (long end = t + 600; t < end; t += 33) b[0].onFrame(false, t, 30, 1500); // a filling queue: SET_BITRATE
        assertTrue(heldLock.size() >= 2, "requests were made: " + heldLock);
        assertFalse(heldLock.contains(true), "none while holding the lock");
    }

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

    /** 2026-11-01: the ping went from ~80 ms to 3 s and 7.7 s at 6 Mbit/s - down within ~0.3 s, then every second. */
    @Test
    void aFillingQueueStepsDownAtOnceAndAgainEverySecond() {
        List<Long> downAt = new ArrayList<>();
        long[] now = {0};
        BitrateControl b = new BitrateControl(up -> { if (!up) downAt.add(now[0]); });
        b.onPhoneTarget(6_000_000);
        for (; now[0] < 20_000; now[0] += 33) b.onFrame(now[0] % 2000 == 0, now[0], 30, 80);
        long start = now[0];
        int ping = 400;
        for (; now[0] < start + 1500; now[0] += 33, ping += 100) b.onFrame(false, now[0], 30, ping);
        assertFalse(downAt.isEmpty(), "stepped down");
        assertTrue(downAt.get(0) - start <= BitrateControl.SPIKE_HOLD_MS + 50, "within ~0.3 s, was " + (downAt.get(0) - start));
        assertEquals(BitrateControl.FLOOR_STEP, b.step(), "to the floor in one go (1.0.33)");
        assertEquals(1, downAt.size());
        for (; now[0] < start + 3500; now[0] += 33, ping += 100) b.onFrame(false, now[0], 30, ping);
        assertTrue(downAt.size() >= 2, "under the floor, a step a second, once the queue has held " + BitrateControl.SPIKE_DEEP_MS + " ms: " + downAt.size());
        assertTrue(b.step() < BitrateControl.FLOOR_STEP);
        assertTrue(b.isCongested(now[0]), "congested: no key frame requests now");
        for (; now[0] < start + 3500 + BitrateControl.CONGESTED_FOR_MS + 100; now[0] += 33) b.onFrame(false, now[0], 30, 80);
        assertFalse(b.isCongested(now[0]), "cleared");
    }

    @Test
    void aSpikeMayGoBelowTheFloorAndTheRescueWaits() {
        List<Boolean> changes = new ArrayList<>();
        long[] now = {0};
        BitrateControl b = new BitrateControl(changes::add);
        b.onPhoneTarget(1_000_000); // at the floor
        for (; now[0] < 20_000; now[0] += 33) b.onFrame(false, now[0], 30, 80);
        changes.clear();
        b.onPhoneTarget(1_000_000); // the phone reports it is at the floor when the queue starts filling
        long start = now[0];
        for (; now[0] < start + BitrateControl.SPIKE_DEEP_MS + 1500; now[0] += 33) b.onFrame(false, now[0], 30, 2000);
        assertTrue(b.step() < BitrateControl.FLOOR_STEP, "below 1 Mbit/s while the queue stays full");
        for (int s = 0; s < 4; s++) b.onReceivedRate(0.2, now[0] + s * 1000L);
        assertFalse(changes.contains(true), "no 'ask it up' while congested");
    }

    @Test
    void neverAsksAboveTheTopAndStepsDownToIt() {
        List<Boolean> changes = new ArrayList<>();
        BitrateControl b = new BitrateControl(changes::add);
        b.setMaxStep(5);
        long t = 0;
        for (; t < 60_000; t += 33) b.onFrame(t % 2000 == 0, t, 30, 80);
        assertEquals(5, b.step(), "raised up to the top only");
        long ups = changes.stream().filter(c -> c).count();
        for (; t < 120_000; t += 33) b.onFrame(t % 2000 == 0, t, 30, 80);
        assertEquals(ups, changes.stream().filter(c -> c).count(), "no more asks at the top");
        b.setMaxStep(3); // bad coverage mode
        for (long end = t + 3000; t < end; t += 33) b.onFrame(false, t, 30, 80);
        assertEquals(3, b.step(), "down to 1 Mbit/s, a step a second");
    }

    /** A jittery 4G link (pings all over 80-400 ms) is not a queue: no stepping down, and raises still come. */
    @Test
    void jitterAloneIsNoSpikeAndDoesNotPinTheBitrate() {
        List<Boolean> changes = new ArrayList<>();
        BitrateControl b = new BitrateControl(changes::add);
        b.onPhoneTarget(2_000_000);
        java.util.Random r = new java.util.Random(7);
        long t = 0;
        for (int i = 0; i < 30 * 40; i++, t += 33) b.onFrame(i % 60 == 0, t, 30, 80 + r.nextInt(320)); // 40 s
        assertFalse(changes.contains(false), "never stepped down on jitter: " + changes);
        assertTrue(changes.contains(true), "raised while it holds");
    }

    /** A ping a full second over the quietest is a queue whatever the jitter (a steady 2 s ping is no "typical"). */
    @Test
    void aSteadyHugePingIsAQueue() {
        List<Boolean> changes = new ArrayList<>();
        BitrateControl b = new BitrateControl(changes::add);
        b.onPhoneTarget(4_000_000);
        long t = 0;
        for (; t < 20_000; t += 33) b.onFrame(false, t, 30, 80);
        changes.clear();
        for (long end = t + 1000; t < end; t += 33) b.onFrame(false, t, 30, 2000);
        assertTrue(changes.contains(false), "down: " + changes);
        assertEquals(BitrateControl.FLOOR_STEP, b.step());
    }

    @Test
    void aDropoutWithoutFramesStillStepsDown() {
        List<Boolean> changes = new ArrayList<>();
        BitrateControl b = new BitrateControl(changes::add);
        b.onPhoneTarget(4_000_000);
        long t = 0;
        for (; t < 20_000; t += 33) b.onFrame(false, t, 30, 80);
        changes.clear();
        for (long end = t + 3000; t < end; t += 200) b.onTick(t, -1); // no frames, no pongs for 3 s
        assertTrue(changes.stream().filter(c -> !c).count() >= 2, "stepped down during the dropout: " + changes);
    }

    /** A frame as the phone splits it, and its parity (the XOR of the payloads, each padded to SEGMENT). */
    private static byte[][] split(byte[] frame) {
        java.util.List<byte[]> parts = new java.util.ArrayList<>();
        for (int at = 0; at < frame.length; ) {
            int n = Math.min(at == 0 ? FrameAssembler.FIRST_SEGMENT : FrameAssembler.SEGMENT, frame.length - at);
            parts.add(java.util.Arrays.copyOfRange(frame, at, at + n));
            at += n;
        }
        byte[] parity = new byte[FrameAssembler.SEGMENT];
        for (byte[] p : parts) for (int i = 0; i < p.length; i++) parity[i] ^= p[i];
        parts.add(parity);
        return parts.toArray(new byte[0][]);
    }

    private static FrameAssembler.Frame feed(FrameAssembler a, short num, byte[] frame, int lose1, int lose2) {
        byte[][] parts = split(frame);
        FrameAssembler.Frame out = null;
        int at = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            if (i != lose1 && i != lose2) {
                FrameAssembler.Frame f = a.put(false, num, at, at == 0 ? frame.length : 0, parts[i], 0, parts[i].length);
                if (f != null) out = f;
            }
            at += parts[i].length;
        }
        FrameAssembler.Frame f = a.putParity(false, num, frame.length, parts[parts.length - 1], 0, FrameAssembler.SEGMENT);
        return f != null ? f : out;
    }

    /** The phone's group parity (pc.26): the XOR of each PARITY_GROUP packets, each padded to SEGMENT. */
    private static List<byte[]> groupParities(byte[][] parts) {
        List<byte[]> out = new ArrayList<>();
        for (int g = 0; g * FrameAssembler.PARITY_GROUP < parts.length - 1; g++) {
            byte[] parity = new byte[FrameAssembler.SEGMENT];
            for (int i = g * FrameAssembler.PARITY_GROUP; i < Math.min(parts.length - 1, (g + 1) * FrameAssembler.PARITY_GROUP); i++) {
                for (int j = 0; j < parts[i].length; j++) parity[j] ^= parts[i][j];
            }
            out.add(parity);
        }
        return out;
    }

    /** A 20-packet frame losing one packet in each of its three groups: whole again from the group parities. */
    @Test
    void theGroupParityRebuildsOneLostPacketPerGroup() {
        byte[] frame = bytes(0, FrameAssembler.FIRST_SEGMENT + 18 * FrameAssembler.SEGMENT + 300); // 20 packets
        byte[][] parts = split(frame);
        List<byte[]> parities = groupParities(parts);
        assertEquals(3, parities.size());
        FrameAssembler a = new FrameAssembler();
        a.setWaitForWholeKeyFrames(true);
        java.util.Set<Integer> lost = java.util.Set.of(2, 9, 19);
        FrameAssembler.Frame out = null;
        int at = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!lost.contains(i)) {
                FrameAssembler.Frame f = a.put(true, (short) 5, at, at == 0 ? frame.length : 0, parts[i], 0, parts[i].length);
                if (f != null) out = f;
            }
            at += parts[i].length;
            if (i % FrameAssembler.PARITY_GROUP == FrameAssembler.PARITY_GROUP - 1 || i == parts.length - 2) {
                FrameAssembler.Frame f = a.putParityGroup(true, (short) 5, frame.length, i / FrameAssembler.PARITY_GROUP,
                        parities.get(i / FrameAssembler.PARITY_GROUP), 0, FrameAssembler.SEGMENT, 0);
                if (f != null) out = f;
            }
        }
        assertNotNull(out, "whole once the last group's parity is in");
        assertFalse(out.damaged());
        assertArrayEquals(frame, out.data());
        assertEquals(1, a.getRecoveredFrames());
    }

    /** Two packets lost in one group: that group can't be rebuilt; the frame goes out damaged with the next one. */
    @Test
    void twoLostInOneGroupIsBeyondTheGroupParity() {
        byte[] frame = bytes(0, FrameAssembler.FIRST_SEGMENT + 10 * FrameAssembler.SEGMENT); // 11 packets, 2 groups
        byte[][] parts = split(frame);
        List<byte[]> parities = groupParities(parts);
        FrameAssembler a = new FrameAssembler();
        a.setWaitForWholeKeyFrames(true);
        int at = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            if (i != 3 && i != 4) assertNull(a.put(false, (short) 5, at, at == 0 ? frame.length : 0, parts[i], 0, parts[i].length));
            at += parts[i].length;
        }
        assertNull(a.putParityGroup(false, (short) 5, frame.length, 0, parities.get(0), 0, FrameAssembler.SEGMENT, 0), "waits for the last group's parity");
        FrameAssembler.Frame out = a.putParityGroup(false, (short) 5, frame.length, 1, parities.get(1), 0, FrameAssembler.SEGMENT, 0);
        assertNotNull(out, "every parity in and still not whole: out damaged now, not with the next frame");
        assertTrue(out.damaged());
        assertEquals(0, a.getRecoveredFrames());
    }

    /** The phone's frame counter wrapping (apps before pc.26 wrap to 1): not a restarted stream. */
    @Test
    void aWrappedCounterIsNotARestart() {
        FrameAssembler a = new FrameAssembler();
        assertNotNull(a.put(true, (short) 32766, 0, 10, bytes(0, 10), 0, 10));
        assertNotNull(a.put(false, (short) 32767, 0, 10, bytes(0, 10), 0, 10));
        FrameAssembler.Frame f = a.put(true, (short) 1, 0, 10, bytes(0, 10), 0, 10);
        assertNotNull(f);
        assertFalse(f.streamStart(), "the counter wrapped: the stream goes on");
        assertNotNull(a.put(false, (short) 2, 0, 10, bytes(0, 10), 0, 10));
        FrameAssembler b = new FrameAssembler();
        assertNotNull(b.put(true, (short) 500, 0, 10, bytes(0, 10), 0, 10));
        FrameAssembler.Frame restart = b.put(true, (short) 1, 0, 10, bytes(0, 10), 0, 10);
        assertNotNull(restart);
        assertTrue(restart.streamStart(), "from 500 to 1: a restart");
    }

    @Test
    void theParityRebuildsAnyOneLostPacket() {
        byte[] frame = bytes(0, FrameAssembler.FIRST_SEGMENT + 2 * FrameAssembler.SEGMENT + 300); // 4 packets
        for (int lose = 0; lose < 4; lose++) {
            FrameAssembler a = new FrameAssembler();
            a.setWaitForWholeKeyFrames(true); // bad coverage mode
            FrameAssembler.Frame f = feed(a, (short) 3, frame, lose, -1);
            assertNotNull(f, "packet " + lose + " lost");
            assertFalse(f.damaged(), "rebuilt, packet " + lose);
            assertArrayEquals(frame, f.data());
            assertEquals(1, a.getRecoveredFrames());
        }
    }

    @Test
    void twoLostPacketsCantBeRebuiltAndTheFrameGoesOutDamagedOnceItsParityIsIn() {
        byte[] frame = bytes(0, FrameAssembler.FIRST_SEGMENT + 2 * FrameAssembler.SEGMENT + 300);
        FrameAssembler a = new FrameAssembler();
        a.setWaitForWholeKeyFrames(true);
        FrameAssembler.Frame f = feed(a, (short) 3, frame, 1, 2);
        assertNotNull(f, "the parity came and can't help: out at once, no waiting for nothing");
        assertTrue(f.damaged());
        assertEquals(0, a.getRecoveredFrames());
    }

    @Test
    void inBadCoverageModeAKeyFrameWaitsForItsSecondCopy() {
        FrameAssembler a = new FrameAssembler();
        a.setWaitForWholeKeyFrames(true);
        byte[] whole = bytes(0, 300);
        assertNull(a.put(true, (short) 5, 0, 300, whole, 0, 100));
        assertNull(a.put(true, (short) 5, 200, 0, whole, 200, 100), "start and end in, the middle missing: wait");
        FrameAssembler.Frame f = a.put(true, (short) 5, 100, 0, whole, 100, 100); // from the second copy
        assertNotNull(f);
        assertFalse(f.damaged(), "whole after all");
        assertArrayEquals(whole, f.data());
        assertNull(a.put(true, (short) 5, 0, 300, whole, 0, 100), "the rest of the second copy: dropped");
    }

    @Test
    void aKeyFrameWhoseSecondCopyAlsoMissesGoesOutWithTheNextFrame() {
        FrameAssembler a = new FrameAssembler();
        a.setWaitForWholeKeyFrames(true);
        byte[] whole = bytes(0, 300);
        a.put(true, (short) 5, 0, 300, whole, 0, 100);
        a.put(true, (short) 5, 200, 0, whole, 200, 100);
        assertNotNull(a.put(false, (short) 6, 0, 50, whole, 0, 50));
        List<FrameAssembler.Frame> earlier = a.takeFlushed();
        assertEquals(1, earlier.size());
        assertTrue(earlier.get(0).damaged());
    }

    @Test
    void thePhonesOwnReportSetsTheStep() {
        BitrateControl b = new BitrateControl(c -> { });
        b.onPhoneTarget(500_000);
        assertEquals(2, b.step());
        b.onPhoneTarget(150_000);
        assertEquals(0, b.step(), "the survival tier's floor (phone app pc.26)");
        b.onPhoneTarget(6_000_000);
        assertEquals(6, b.step());
        b.onPhoneTarget(20_000_000);
        assertEquals(7, b.step(), "above the table: the top (8 Mbit/s, the sharp tier's, 1.0.34)");
        b.onPhoneTarget(-1);
        assertEquals(7, b.step(), "no report: unchanged");
    }

    /** The step outright to the floor (SET_BITRATE) when the queue fills; the relative command for the rest. */
    @Test
    void aFillingQueueAsksForTheFloorInOneGo() {
        List<String> sent = new ArrayList<>();
        BitrateControl b = new BitrateControl(new BitrateControl.Sink() {
            @Override
            public void changeBitRate(boolean increase) {
                sent.add(increase ? "up" : "down");
            }

            @Override
            public void setStep(int step, int from) {
                sent.add("set " + step + " from " + from);
            }
        });
        b.onPhoneTarget(6_000_000);
        b.setMaxStep(6); // the set limit, 6 Mbit/s: the calm below raises no further
        long t = 0;
        for (; t < 20_000; t += 33) b.onFrame(false, t, 30, 80);
        sent.clear();
        for (long end = t + 600; t < end; t += 33) b.onFrame(false, t, 30, 1500);
        assertEquals(List.of("set " + BitrateControl.FLOOR_STEP + " from 6"), sent);
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

    /** Bad coverage mode holds the phone at 1 Mbit/s; a calm scene arrives well under 0.5 there and is no reason to ask up. */
    @Test
    void aCalmSceneAtTheBadCoverageLimitIsNotRescuedUpAndDown() {
        List<Boolean> changes = new ArrayList<>();
        BitrateControl b = new BitrateControl(changes::add);
        b.setMaxStep(BitrateControl.FLOOR_STEP);
        b.onPhoneTarget(1_000_000);
        long t = 0;
        for (; t < 30_000; t += 33) {
            b.onFrame(t % 2000 == 0, t, 30, 80);
            if (t % 1000 == 0) b.onReceivedRate(0.3, t);
        }
        assertFalse(changes.contains(true), "no rescue at the limit: " + changes);
        assertEquals(BitrateControl.FLOOR_STEP, b.step());
    }

    /** The phone reports it went below the floor by itself: asked up, in bad coverage mode too (1 Mbit/s is its limit). */
    @Test
    void aPhoneReportingBelowTheFloorIsAskedUpWhateverArrives() {
        List<Boolean> changes = new ArrayList<>();
        BitrateControl b = new BitrateControl(changes::add);
        b.setMaxStep(BitrateControl.FLOOR_STEP);
        b.onPhoneTarget(500_000);
        long t = 0;
        for (; t < 30_000; t += 33) {
            b.onFrame(t % 2000 == 0, t, 30, 80);
            if (t % 1000 == 0) b.onReceivedRate(0.45, t);
        }
        long ups = changes.stream().filter(c -> c).count();
        assertTrue(ups >= 1 && ups <= 30_000 / BitrateControl.RESCUE_MS, "asked up every " + BitrateControl.RESCUE_MS + " ms: " + ups);
        changes.clear();
        b.onPhoneTarget(1_000_000); // it listened
        for (long end = t + 12_000; t < end; t += 33) {
            b.onFrame(t % 2000 == 0, t, 30, 80);
            if (t % 1000 == 0) b.onReceivedRate(0.45, t);
        }
        assertFalse(changes.contains(true), "at the floor and the limit: done");
    }

    @Test
    void keyFrameRequestsBackOffWhileTheyComeDamaged() {
        VideoPipeline.KeyFrameBackoff k = new VideoPipeline.KeyFrameBackoff();
        assertTrue(k.shouldSend(0));
        assertFalse(k.shouldSend(1400), "the link's own spacing");
        k.onKeyFrame(true); // the requested one came damaged
        assertEquals(3000, k.spacingMs());
        assertFalse(k.shouldSend(2000));
        assertTrue(k.shouldSend(3000));
        k.onKeyFrame(true);
        assertEquals(6000, k.spacingMs());
        assertTrue(k.shouldSend(9000));
        k.onKeyFrame(true);
        assertEquals(6000, k.spacingMs(), "no further than 6 s");
        k.onKeyFrame(false); // a whole one
        assertEquals(VideoPipeline.KeyFrameBackoff.BASE_MS, k.spacingMs(), "back to the start");
        k.onKeyFrame(true); // damaged, but not one that was asked for: a scheduled key frame lost on the way
        assertEquals(VideoPipeline.KeyFrameBackoff.BASE_MS, k.spacingMs());
        k.setRobust(true);
        assertEquals(VideoPipeline.KeyFrameBackoff.ROBUST_BASE_MS, k.spacingMs(), "bad coverage: the second copy first");
    }

    @Test
    void theColourMatrixFollowsThePictureAndHdIsBt709() {
        assertEquals(org.bytedeco.ffmpeg.global.swscale.SWS_CS_ITU709, VideoDecoder.coefficients(org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT709, 240));
        assertEquals(org.bytedeco.ffmpeg.global.swscale.SWS_CS_ITU601, VideoDecoder.coefficients(org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT470BG, 1080));
        assertEquals(org.bytedeco.ffmpeg.global.swscale.SWS_CS_ITU709, VideoDecoder.coefficients(org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_UNSPECIFIED, 720));
        assertEquals(org.bytedeco.ffmpeg.global.swscale.SWS_CS_ITU601, VideoDecoder.coefficients(org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_UNSPECIFIED, 480));
    }

    @Test
    void aFragmentBeyondTheFrameIsClippedAndTheFrameStillCompletes() {
        FrameAssembler a = new FrameAssembler();
        byte[] whole = bytes(0, 250);
        assertNull(a.put(false, (short) 4, 0, 250, whole, 0, 100, 1234));
        assertNull(a.put(false, (short) 4, 100, 0, whole, 100, 100, 1235));
        FrameAssembler.Frame f = a.put(false, (short) 4, 200, 0, new byte[80], 0, 80, 1236); // 30 bytes too many
        assertNotNull(f);
        assertEquals(250, f.data().length);
        assertEquals(1236, f.arrivedMs(), "the time of the fragment that completed it");
        assertFalse(f.damaged());
    }

    // ------------------------------------------------------------------ VideoPipeline (fake sink)

    /** A screen that counts: pictures presented and stream starts, with a gate to hold the decode thread in beginFrame. */
    static final class FakeScreen implements VideoPipeline.FrameSink {
        final List<int[]> starts = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger presented = new java.util.concurrent.atomic.AtomicInteger();
        volatile java.util.concurrent.CountDownLatch gate;
        final java.util.concurrent.CountDownLatch inBeginFrame = new java.util.concurrent.CountDownLatch(1);
        private int[] buffer = new int[0];

        public void videoStarted(int width, int height, boolean mirror) {
            starts.add(new int[]{width, height});
        }

        public int[] targetSize() {
            return new int[]{320, 240};
        }

        public int[] beginFrame(int width, int height) {
            inBeginFrame.countDown();
            java.util.concurrent.CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await();
                } catch (InterruptedException ignored) {
                }
            }
            if (buffer.length < width * height) buffer = new int[width * height];
            return buffer;
        }

        public void presentFrame() {
            presented.incrementAndGet();
        }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition, long ms) throws InterruptedException {
        for (long end = System.currentTimeMillis() + ms; System.currentTimeMillis() < end; Thread.sleep(10)) {
            if (condition.getAsBoolean()) return true;
        }
        return condition.getAsBoolean();
    }

    /** H8: a one-packet P-frame lost whole: a key frame is asked for with the next frame (before 1.0.49: nothing). */
    @Test
    void aLostOnePacketFrameAsksForAKeyFrame() throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 240, 12, 30);
        FakeScreen screen = new FakeScreen();
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        try (VideoPipeline p = new VideoPipeline(screen, () -> 40)) {
            p.setKeyFrameSender(requests::incrementAndGet);
            p.onVideoConfig(320, 240, false, false, s.codecConfig());
            feed(p, s, 0, 6, 1); // frames 1-6, each one packet
            assertTrue(waitFor(() -> screen.presented.get() >= 1, 2000));
            assertEquals(0, requests.get(), "a clean stream asks for nothing");
            feed(p, s, 7, 9, 8); // frame 7 never came
            assertEquals(1, p.stats().lost());
            assertTrue(requests.get() >= 1, "asked for a key frame with frame 8");
        }
    }

    private static void feed(VideoPipeline p, TestStreams.Stream s, int from, int to, int firstNumber) {
        for (int i = from; i < to; i++) {
            byte[] d = s.packets().get(i).data();
            p.onVideoFragment(s.packets().get(i).keyFrame(), (short) (firstNumber + i - from), 0, d.length, d, 0, d.length);
        }
    }

    /** A restarted stream's key frame 1 often comes before its config: held for it, not decoded under the old one. */
    @Test
    void aRestartedStreamsFirstKeyFrameWaitsForItsConfig() throws Exception {
        TestStreams.Stream a = TestStreams.h264(320, 240, 10, 5);
        TestStreams.Stream b = TestStreams.h264(160, 120, 10, 5);
        FakeScreen screen = new FakeScreen();
        try (VideoPipeline p = new VideoPipeline(screen, () -> 40)) {
            p.onVideoConfig(320, 240, false, false, a.codecConfig());
            feed(p, a, 0, 10, 1);
            assertTrue(waitFor(() -> screen.presented.get() >= 1, 2000), "the first stream shows");
            assertTrue(waitFor(() -> p.framesDone() >= 10, 5000), "the decode thread is done with the 10 frames");
            int shown = screen.presented.get();
            feed(p, b, 0, 1, 1); // the new stream's key frame 1, before its config
            Thread.sleep(300);
            assertEquals(shown, screen.presented.get(), "not decoded under the old config");
            assertEquals(1, screen.starts.size());
            p.onVideoConfig(160, 120, false, false, b.codecConfig());
            assertTrue(waitFor(() -> screen.presented.get() > shown, 2000), "the held key frame shows once its config is in");
            assertEquals(2, screen.starts.size());
            assertArrayEquals(new int[]{160, 120}, screen.starts.get(1));
        }
    }

    /** No config after the restart (one lost): decoded under the old one after CONFIG_WAIT_MS rather than frozen. */
    @Test
    void aHeldKeyFrameIsReleasedWhenNoConfigComes() throws Exception {
        TestStreams.Stream a = TestStreams.h264(320, 240, 10, 5);
        FakeScreen screen = new FakeScreen();
        try (VideoPipeline p = new VideoPipeline(screen, () -> 40)) {
            p.onVideoConfig(320, 240, false, false, a.codecConfig());
            feed(p, a, 0, 10, 1);
            assertTrue(waitFor(() -> screen.presented.get() >= 1, 2000));
            // the decode thread may still be drawing frames 2-10 (T1): waited for until it is done with them - a count
            // still for 300 ms (settled) wasn't, on a loaded PC
            assertTrue(waitFor(() -> p.framesDone() >= 10, 5000), "the decode thread is done with the 10 frames");
            int shown = screen.presented.get();
            feed(p, a, 5, 6, 1); // "restarted": key frame 1 again (the same size, so it decodes under the old config)
            Thread.sleep(VideoPipeline.CONFIG_WAIT_MS + 200);
            assertEquals(shown, screen.presented.get(), "held");
            feed(p, a, 6, 7, 2);
            assertTrue(waitFor(() -> screen.presented.get() > shown, 2000), "released with the next frame");
            assertEquals(1, screen.starts.size());
        }
    }

    /**
     * Held frames let go without their config are CONFIG_WAIT_MS old: they waited for the config, not for the decoder,
     * and are no backlog. Before 1.0.54 they were cut as one; when the decoder took the key frame in that moment, a
     * FLUSH went in behind it and nothing was shown until the next key frame (the test above failed under load).
     */
    @Test
    void heldFramesLetGoLateAreNotABacklog() throws Exception {
        TestStreams.Stream a = TestStreams.h264(320, 240, 10, 5);
        FakeScreen screen = new FakeScreen();
        try (VideoPipeline p = new VideoPipeline(screen, () -> 40)) {
            p.onVideoConfig(320, 240, false, false, a.codecConfig());
            feed(p, a, 0, 9, 1);
            assertTrue(waitFor(() -> p.framesDone() >= 9, 5000), "the decode thread is done with 9 frames");
            int shown = screen.presented.get();
            screen.gate = new java.util.concurrent.CountDownLatch(1);
            feed(p, a, 9, 10, 10); // the decoder stops at this picture: what comes next stays in the queue
            feed(p, a, 5, 6, 1); // "restarted": key frame 1, held for a config that never comes
            Thread.sleep(VideoPipeline.CONFIG_WAIT_MS + 200);
            feed(p, a, 6, 7, 2); // lets the held key frame go, with this frame behind it
            assertEquals(0, p.decoderBehindDrops(), "not taken for a decoder behind");
            screen.gate.countDown();
            assertTrue(waitFor(() -> screen.presented.get() >= shown + 2, 2000), "frame 10, then the restart's second frame");
            assertEquals(1, screen.starts.size());
        }
    }

    /** A backlog is dropped by flushing the decoder, not by rebuilding it (a black gap); then from the next key frame. */
    @Test
    void aDroppedBacklogKeepsTheDecoder() throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 240, 50, 45); // key frames 0 and 45
        FakeScreen screen = new FakeScreen();
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        try (VideoPipeline p = new VideoPipeline(screen, () -> 40)) {
            p.setKeyFrameSender(requests::incrementAndGet);
            screen.gate = new java.util.concurrent.CountDownLatch(1);
            p.onVideoConfig(320, 240, false, false, s.codecConfig());
            feed(p, s, 0, 1, 1);
            assertTrue(screen.inBeginFrame.await(2, java.util.concurrent.TimeUnit.SECONDS), "the decoder is held in beginFrame");
            feed(p, s, 1, 45, 2); // 44 frames pile up behind it: more than the backlog allows
            screen.gate.countDown();
            assertTrue(waitFor(() -> screen.presented.get() == 1, 2000));
            Thread.sleep(300);
            assertEquals(1, screen.presented.get(), "the frames after the drop wait for a key frame");
            assertEquals(1, screen.starts.size(), "the decoder was not rebuilt");
            assertTrue(requests.get() >= 1, "a key frame was asked for");
            feed(p, s, 45, 50, 46);
            assertTrue(waitFor(() -> screen.presented.get() >= 2, 2000), "decoding again from the key frame");
            assertEquals(1, screen.starts.size());
        }
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

    /** The screen is bigger than the camera picture: 720p scaled up to 1080p BGRA, the scaler's heaviest job here. */
    @Test
    void convertTo1080pTime() {
        TestStreams.Stream s = TestStreams.h264(1280, 720, 40, 20, true);
        long convertNs = 0;
        int n = 0;
        try (VideoDecoder d = new VideoDecoder(false, s.codecConfig())) {
            int[] rgb = new int[1920 * 1080];
            for (TestStreams.Packet p : s.packets()) {
                if (!d.decode(p.data(), p.data().length)) continue;
                long t1 = System.nanoTime();
                d.convert(rgb, 1920, 1080);
                long t2 = System.nanoTime();
                if (n++ >= 5) convertNs += t2 - t1;
            }
        }
        double conv = convertNs / 1e6 / (n - 5);
        // measured 2026-11-02: 3.09 ms with one scaler thread, 2.43 ms with one per core
        System.out.printf("720p -> 1080p BGRA, scaler threads %s: convert+copy %.2f ms per frame%n",
                VideoDecoder.SCALE_THREADS == 0 ? "one per core" : Integer.toString(VideoDecoder.SCALE_THREADS), conv);
        assertTrue(conv < 12, "must leave room in a 16 ms frame: " + conv);
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
