package de.droiddrone.pc.video;

import de.droiddrone.common.Fec;
import de.droiddrone.common.FecSender;
import de.droiddrone.common.SessionGate;
import de.droiddrone.common.UdpCommon;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the link round of 1.0.67 (phone app pc.53) costs per packet and per frame, on this PC: the phone's session id
 * check on every stick frame, the video sender's RS parity per frame (and that it allocates nothing), the PC's look at
 * a parity packet it doesn't need and the mending of a frame that needs it. Warmed first, then timed; the budgets are
 * ten times what this PC takes or more, to catch a slip without failing on a busy runner. A phone is slower than
 * this PC - the figures are for comparing, not the phone's own.
 */
@Tag("perf")
class LinkPackCostTest {
    private static byte[] frame(int size, long seed) {
        byte[] f = new byte[size];
        new Random(seed).nextBytes(f);
        return f;
    }

    /** The phone's receive thread on a stick frame from the pilot: the gate with the session id read, against without. */
    @Test
    void theSessionIdCostsAStickFrameNothingToSpeakOf() {
        byte[] rc = new byte[40];
        rc[0] = UdpCommon.RcFrame;
        SessionGate a = new SessionGate("DD"), b = new SessionGate("DD");
        int n = 2_000_000;
        long sink = 0;
        for (int round = 0; round < 2; round++) { // the first warms the JIT
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) sink += a.fromPilot(rc, rc.length, i).ordinal();
            long t1 = System.nanoTime();
            for (int i = 0; i < n; i++) sink += b.fromPilot(rc, rc.length, i, SessionGate.sessionId(rc, rc.length)).ordinal();
            long t2 = System.nanoTime();
            if (round == 1) {
                double before = (t1 - t0) / (double) n, now = (t2 - t1) / (double) n;
                System.out.printf("the phone's gate on a stick frame: %.1f ns before, %.1f ns with the session id read (%d)%n", before, now, sink % 2);
                assertTrue(now < 200, "a stick frame's gate took " + now + " ns");
            }
        }
    }

    /** The video sender's RS parity: per frame, plain and key, and nothing allocated once it runs. */
    @Test
    void theParityCostsAFrameLittleAndAllocatesNothing() {
        byte[] plain = frame(4000, 1), key = frame(30_000, 2);
        FecSender s = new FecSender();
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        double plainUs = 0, keyUs = 0;
        long allocated = 0;
        for (int round = 0; round < 3; round++) {
            int frames = 2000;
            long bytesBefore = mx.getThreadAllocatedBytes(tid);
            long t0 = System.nanoTime();
            for (int f = 0; f < frames; f++) sendAll(s, plain, f, false, 2);
            long t1 = System.nanoTime();
            for (int f = 0; f < frames / 10; f++) sendAll(s, key, f, true, 8);
            long t2 = System.nanoTime();
            allocated = mx.getThreadAllocatedBytes(tid) - bytesBefore;
            plainUs = (t1 - t0) / 1e3 / frames;
            keyUs = (t2 - t1) / 1e3 / (frames / 10.0);
        }
        System.out.printf("RS parity on the sender: %.1f us a plain frame (4 packets, m 2), %.0f us a key frame (28 packets, m 8); %d bytes allocated over 2200 frames%n",
                plainUs, keyUs, allocated);
        assertTrue(plainUs < 200, "a plain frame's parity took " + plainUs + " us");
        assertTrue(keyUs < 5000, "a key frame's parity took " + keyUs + " us");
        assertTrue(allocated < 4096, "allocated per run: " + allocated + " bytes (nothing per frame or packet)");
    }

    private static long sinkBytes;

    /** As the video sender does it: begin, and after each packet the block's parity if one ends there. */
    private static void sendAll(FecSender s, byte[] f, int num, boolean keyFrame, int m) {
        s.begin(f, f.length, num, keyFrame, m);
        for (int i = 0; i < Fec.fragments(f.length); i++) {
            int block = s.blockEndingAt(i);
            if (block < 0) continue;
            int length = s.encode(block);
            for (int j = 0; j < s.m(); j++) sinkBytes += s.packet(j)[length - 1];
        }
        s.sent(f.length, 0, num);
    }

    /** The PC: a parity packet for a frame already out (no loss - the usual case), and a frame mended from it. */
    @Test
    void thePcLooksAtAParityItDoesNotNeedForAMomentAndMendsQuickly() {
        byte[] f = frame(Fec.FIRST_SEGMENT + 7 * Fec.SEGMENT - 100, 3); // 8 packets
        int frames = 4000;
        List<byte[]> parity = new ArrayList<>();
        FecSender s = new FecSender();
        s.begin(f, f.length, 0, false, 2);
        for (int i = 0; i < 8; i++) {
            int block = s.blockEndingAt(i);
            if (block < 0) continue;
            int length = s.encode(block);
            for (int j = 0; j < 2; j++) parity.add(Arrays.copyOf(s.packet(j), length));
        }
        double unneededUs = 0, mendUs = 0, plainUs = 0;
        for (int round = 0; round < 3; round++) {
            // whole frames, then their parity: out at the last packet, the parity dropped unread
            FrameAssembler a = new FrameAssembler();
            a.setWaitForWholeKeyFrames(true);
            long look = 0, plainNs = 0;
            for (int n = 1; n <= frames; n++) {
                long t0 = System.nanoTime();
                for (int i = 0; i < 8; i++) a.put(false, (short) n, Fec.offsetOf(i), i == 0 ? f.length : 0, f, Fec.offsetOf(i), Fec.lengthOf(i, f.length), 1000 + n);
                long t1 = System.nanoTime();
                for (byte[] p : parity) {
                    p[1] = (byte) (n >> 8);
                    p[2] = (byte) n;
                    a.putParityRs(p, p.length, 1000 + n);
                }
                look += System.nanoTime() - t1;
                plainNs += t1 - t0;
            }
            unneededUs = look / 1e3 / frames / parity.size();
            plainUs = plainNs / 1e3 / frames / 8;
            // two packets of each frame lost, mended from its two parity packets
            FrameAssembler b = new FrameAssembler();
            b.setWaitForWholeKeyFrames(true);
            long t0 = System.nanoTime();
            for (int n = 1; n <= frames; n++) {
                for (int i = 0; i < 8; i++) {
                    if (i == 3 || i == 4) continue;
                    b.put(false, (short) n, Fec.offsetOf(i), i == 0 ? f.length : 0, f, Fec.offsetOf(i), Fec.lengthOf(i, f.length), 1000 + n);
                }
                FrameAssembler.Frame out = null;
                for (byte[] p : parity) {
                    p[1] = (byte) (n >> 8);
                    p[2] = (byte) n;
                    FrameAssembler.Frame got = b.putParityRs(p, p.length, 1000 + n);
                    if (got != null) out = got;
                }
                assertNotNull(out, "frame " + n + " mended");
            }
            mendUs = (System.nanoTime() - t0) / 1e3 / frames;
            assertEquals(frames, b.getRecoveredFrames());
        }
        System.out.printf("the PC: %.2f us a data packet, %.2f us a parity packet for a frame already out, %.1f us a frame of 8 put together with two lost and mended%n",
                plainUs, unneededUs, mendUs);
        assertTrue(unneededUs < 20, "an unneeded parity packet took " + unneededUs + " us");
        assertTrue(mendUs < 2000, "a mended frame took " + mendUs + " us");
    }
}
