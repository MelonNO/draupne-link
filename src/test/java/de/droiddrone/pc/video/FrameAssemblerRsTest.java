package de.droiddrone.pc.video;

import de.droiddrone.common.Fec;
import de.droiddrone.common.FecSender;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.69, the frame assembler with Reed-Solomon parity (phone app pc.53, bad coverage mode): the packets in the order
 * the phone's video sender sends them (FecSender: each block's parity right behind it), some of them lost, and what
 * comes out when. A whole frame is never held for its parity; a frame with a gap goes out the moment its block's
 * parity has made it whole; what can't be mended goes out damaged - so the pipeline asks for a key frame, as ever.
 */
class FrameAssemblerRsTest {
    private static byte[] frame(int size, long seed) {
        byte[] f = new byte[size];
        new Random(seed).nextBytes(f);
        return f;
    }

    /** The frame's packets as the phone sends them: Integer = data packet i, byte[] = a VIDEO_PARITY_RS packet. */
    private static List<Object> sendOrder(byte[] f, int frameNum, boolean keyFrame, int m) {
        FecSender s = new FecSender();
        List<Object> out = new ArrayList<>();
        s.begin(f, f.length, frameNum, keyFrame, m);
        for (int i = 0; i < Fec.fragments(f.length); i++) {
            out.add(i);
            int block = s.blockEndingAt(i);
            if (block < 0) continue;
            int length = s.encode(block);
            for (int j = 0; j < s.m(); j++) out.add(Arrays.copyOf(s.packet(j), length));
        }
        return out;
    }

    /** One packet into the assembler; the frame it lets out, or null. */
    private static FrameAssembler.Frame feed(FrameAssembler a, Object packet, byte[] f, short num, boolean keyFrame) {
        if (packet instanceof byte[] p) return a.putParityRs(p, p.length, 1000);
        int i = (Integer) packet, at = Fec.offsetOf(i);
        return a.put(keyFrame, num, at, i == 0 ? f.length : 0, f, at, Fec.lengthOf(i, f.length), 1000);
    }

    private static FrameAssembler weakTier() {
        FrameAssembler a = new FrameAssembler();
        a.setWaitForWholeKeyFrames(true); // bad coverage mode, as the pipeline sets it
        return a;
    }

    /** Feeds the frame's packets but the lost ones; returns the index in send order at which the frame came out (-1: not). */
    private static int run(FrameAssembler a, List<Object> order, Set<Integer> lost, byte[] f, short num, boolean keyFrame, FrameAssembler.Frame[] out) {
        for (int at = 0; at < order.size(); at++) {
            Object p = order.get(at);
            if (p instanceof Integer i && lost.contains(i)) continue;
            FrameAssembler.Frame got = feed(a, p, f, num, keyFrame);
            if (got != null) {
                out[0] = got;
                return at;
            }
        }
        return -1;
    }

    /**
     * Two packets lost in one group of eight: the XOR group's single parity can't mend that (the frame went out
     * damaged), the RS block's two parity packets can.
     */
    @Test
    void twoLossesInOneGroupAreMended() {
        byte[] f = frame(Fec.FIRST_SEGMENT + 7 * Fec.SEGMENT - 100, 3); // 8 packets: one XOR group, one RS block
        // the XOR groups (phone app pc.26 - pc.52): packets 3 and 4 lost, the group's parity in - damaged
        FrameAssembler xor = weakTier();
        byte[] groupParity = new byte[FrameAssembler.SEGMENT];
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < Fec.lengthOf(i, f.length); j++) groupParity[j] ^= f[Fec.offsetOf(i) + j];
        }
        for (int i = 0; i < 8; i++) {
            if (i != 3 && i != 4) assertNull(feed(xor, i, f, (short) 9, false));
        }
        FrameAssembler.Frame x = xor.putParityGroup(false, (short) 9, f.length, 0, groupParity, 0, groupParity.length, 1000);
        assertNotNull(x);
        assertTrue(x.damaged(), "XOR: two in one group can't be mended");
        // the same loss with RS parity, two a block: whole
        FrameAssembler rs = weakTier();
        List<Object> order = sendOrder(f, 9, false, 2);
        FrameAssembler.Frame[] out = new FrameAssembler.Frame[1];
        int at = run(rs, order, Set.of(3, 4), f, (short) 9, false, out);
        assertEquals(order.size() - 1, at, "out with the second parity packet, the moment it is whole");
        assertFalse(out[0].damaged());
        assertArrayEquals(f, out[0].data());
        assertEquals(1, rs.getRecoveredFrames());
        assertEquals(0, rs.getDamagedFrames());
    }

    @Test
    void aWholeFrameIsNeverHeldForItsParity() {
        byte[] f = frame(Fec.FIRST_SEGMENT + 3 * Fec.SEGMENT - 10, 4); // 4 packets
        FrameAssembler a = weakTier();
        List<Object> order = sendOrder(f, 10, false, 2);
        FrameAssembler.Frame[] out = new FrameAssembler.Frame[1];
        assertEquals(3, run(a, order, Set.of(), f, (short) 10, false, out), "out with its last packet, before its parity");
        assertFalse(out[0].damaged());
        assertNull(feed(a, order.get(4), f, (short) 10, false), "the parity behind it: dropped, nothing kept");
        assertNull(feed(a, order.get(5), f, (short) 10, false));
        assertEquals(0, a.pendingBytes());
        assertEquals(0, a.getRecoveredFrames());
    }

    /** A burst of m packets in a block is mended; one more and the frame goes out damaged once its parity is in - not later. */
    @Test
    void aBurstWithinMIsMendedBeyondMGoesOutDamagedAsToday() {
        byte[] f = frame(Fec.FIRST_SEGMENT + 27 * Fec.SEGMENT - 500, 5); // a 28-packet key frame
        List<Object> order = sendOrder(f, 11, true, 4);
        FrameAssembler a = weakTier();
        FrameAssembler.Frame[] out = new FrameAssembler.Frame[1];
        assertEquals(order.size() - 1, run(a, order, Set.of(10, 11, 12, 13), f, (short) 11, true, out));
        assertFalse(out[0].damaged(), "four in a row, four parity packets: whole");
        assertArrayEquals(f, out[0].data());
        // five: out damaged at the last parity packet (a key frame with RS parity comes once: nothing more to wait for)
        FrameAssembler b = weakTier();
        out[0] = null;
        List<Object> again = sendOrder(f, 12, true, 4);
        assertEquals(again.size() - 1, run(b, again, Set.of(10, 11, 12, 13, 14), f, (short) 12, true, out));
        assertTrue(out[0].damaged(), "the pipeline asks for a key frame on it, as for any damaged frame");
        assertEquals(1, b.getDamagedFrames());
        assertEquals(0, b.getRecoveredFrames());
    }

    /** A frame of two blocks: block 0 is mended by its own parity while block 1 is still on its way, and the frame is out at its last data packet. */
    @Test
    void aBlockIsMendedWhileTheNextIsOnItsWay() {
        byte[] f = frame(Fec.FIRST_SEGMENT + 39 * Fec.SEGMENT - 70, 6); // 40 packets: 20 + 20
        List<Object> order = sendOrder(f, 13, false, 2);
        FrameAssembler a = weakTier();
        FrameAssembler.Frame[] out = new FrameAssembler.Frame[1];
        int at = run(a, order, Set.of(5), f, (short) 13, false, out);
        assertEquals(order.indexOf(39), at, "whole with block 1's last packet: block 1's parity not waited for");
        assertArrayEquals(f, out[0].data());
        assertEquals(1, a.getRecoveredFrames());
    }

    /** Not mended and the frame's last parity lost: it waits for the next frame, as with the XOR groups - no longer. */
    @Test
    void notMendedItGoesOutWithTheNextFrameAsBefore() {
        byte[] f = frame(Fec.FIRST_SEGMENT + 5 * Fec.SEGMENT - 10, 7); // 6 packets
        List<Object> order = sendOrder(f, 14, false, 1);
        FrameAssembler a = weakTier();
        for (Object p : order) {
            if (p.equals(2) || p.equals(3) || p instanceof byte[]) continue; // two lost and the parity too
            assertNull(feed(a, p, f, (short) 14, false));
        }
        byte[] next = frame(500, 8);
        FrameAssembler.Frame n = a.put(false, (short) 15, 0, next.length, next, 0, next.length, 1033);
        assertNotNull(n, "the next frame, whole");
        List<FrameAssembler.Frame> flushed = a.takeFlushed();
        assertEquals(1, flushed.size(), "the gapped one first, damaged");
        assertTrue(flushed.get(0).damaged());
        assertEquals(14, flushed.get(0).number());
    }

    @Test
    void aParityThatDoesNotAgreeIsDropped() {
        byte[] f = frame(3000, 9);
        List<Object> order = sendOrder(f, 16, false, 2);
        byte[] p = (byte[]) order.get(order.size() - 1);
        FrameAssembler a = weakTier();
        assertNull(a.putParityRs(p, p.length - 1, 1000), "a byte short");
        byte[] wrongK = p.clone();
        wrongK[7]++;
        assertNull(a.putParityRs(wrongK, wrongK.length, 1000), "a k the frame's size doesn't give");
        // the frame's own packets said another size: not its parity
        assertNull(a.put(false, (short) 16, 0, 2500, f, 0, Fec.FIRST_SEGMENT, 1000));
        assertNull(a.putParityRs(p, p.length, 1000));
        assertEquals(0, a.getRecoveredFrames());
    }
}
