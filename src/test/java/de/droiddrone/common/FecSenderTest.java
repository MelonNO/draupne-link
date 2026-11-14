package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The video sender's RS parity (pc.53, FecSender): its packets are Fec's own - the header as Fec.putHeader writes it,
 * the parity as Fec.encode makes it, right behind each block - and they rebuild what a block lost; the share they take
 * comes off the encoder's bitrate, never off the step that is reported.
 */
class FecSenderTest {
    static byte[] frame(int size, long seed) {
        byte[] f = new byte[size];
        new Random(seed).nextBytes(f);
        return f;
    }

    /** The frame's packets in the order the phone sends them: Integer = data packet i, byte[] = a parity packet. */
    static List<Object> sendOrder(FecSender s, byte[] f, int frameNum, boolean keyFrame, int m) {
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

    @Test
    void itsPacketsAreFecsOwnRightBehindEachBlock() {
        byte[] f = frame(Fec.FIRST_SEGMENT + 39 * Fec.SEGMENT - 300, 1); // 40 packets: two blocks of 20
        FecSender s = new FecSender();
        assertEquals(2 * 3, s.begin(f, f.length, 77, true, 3), "3 a block, two blocks");
        List<Object> order = sendOrder(s, f, 77, true, 3);
        assertEquals(40 + 6, order.size());
        assertEquals(19, order.get(19), "block 0: packets 0-19");
        for (int at : new int[]{20, 21, 22, 43, 44, 45}) assertInstanceOf(byte[].class, order.get(at), "parity at " + at);
        assertEquals(20, order.get(23), "block 1 after block 0's parity");
        int n = Fec.fragments(f.length);
        for (int b = 0; b < 2; b++) {
            int first = Fec.blockFirst(n, b), k = Fec.blockK(n, b);
            byte[][] expected = new byte[3][Fec.HEADER + Fec.SEGMENT];
            int length = Fec.encode(f, f.length, first, k, 3, expected, Fec.HEADER);
            for (int i = 0; i < 3; i++) {
                byte[] p = (byte[]) order.get(b == 0 ? 20 + i : 43 + i);
                assertEquals(Fec.HEADER + length, p.length);
                assertEquals(PacketIds.VIDEO_PARITY_RS, p[0]);
                assertTrue(Fec.valid(p, p.length), "Fec.valid");
                assertEquals(77, Fec.frameNum(p));
                assertEquals(f.length, Fec.frameSize(p));
                assertTrue(Fec.keyFrame(p));
                assertEquals(b, Fec.block(p));
                assertEquals(k, Fec.k(p));
                assertEquals(3, Fec.m(p));
                assertEquals(i, Fec.index(p));
                assertArrayEquals(Arrays.copyOfRange(expected[i], Fec.HEADER, Fec.HEADER + length), Arrays.copyOfRange(p, Fec.HEADER, p.length));
            }
        }
        // any 3 of a block lost: rebuilt from them
        byte[] got = f.clone();
        boolean[] have = new boolean[20];
        Arrays.fill(have, true);
        for (int lost : new int[]{2, 3, 17}) {
            have[lost] = false;
            Arrays.fill(got, Fec.offsetOf(lost), Fec.offsetOf(lost) + Fec.lengthOf(lost, f.length), (byte) 0);
        }
        byte[][] parity = new byte[3][];
        for (int i = 0; i < 3; i++) parity[i] = Arrays.copyOfRange((byte[]) order.get(20 + i), Fec.HEADER, ((byte[]) order.get(20 + i)).length);
        assertTrue(Fec.decode(got, f.length, 0, 20, have, parity, 0, 3));
        assertArrayEquals(f, got);
    }

    @Test
    void noParityWithMZeroAndAFrameTooBigGoesBare() {
        FecSender s = new FecSender();
        byte[] f = frame(5000, 2);
        assertEquals(0, s.begin(f, f.length, 1, false, 0));
        for (int i = 0; i < Fec.fragments(f.length); i++) assertEquals(-1, s.blockEndingAt(i));
        assertEquals(Fec.MAX_PARITY, new FecSender().begin(f, f.length, 1, false, 99) / Fec.blocks(Fec.fragments(f.length)), "taken into 0..8");
        byte[] huge = new byte[Fec.FIRST_SEGMENT + Fec.MAX_BLOCKS * Fec.MAX_DATA * Fec.SEGMENT];
        assertEquals(0, s.begin(huge, huge.length, 1, true, 4), "more blocks than the header holds: bare");
        assertEquals(8, FecSender.parityOf((byte) 200));
        assertEquals(3, FecSender.parityOf((byte) 3));
    }

    /** A second of frames of one size and parity into the sender's count, each second's frames apart from the next's. */
    private static final class Seconds {
        final FecSender s = new FecSender();
        long t = 1000;
        boolean started;

        void second(int frameBytes, int parityBytes) {
            if (!started) s.sent(frameBytes, parityBytes, t); // the first frame opens the count
            started = true;
            for (int i = 0; i < 10; i++) s.sent(frameBytes, parityBytes, t += 100);
        }
    }

    @Test
    void theShareLowersTheEncoderNotTheStep() {
        Seconds c = new Seconds();
        FecSender s = c.s;
        // plain frames of 4000 bytes with one 1091-byte parity packet each: 21 % of the upload
        c.second(4000, Fec.SEGMENT);
        assertEquals(1000L * Fec.SEGMENT / (4000 + Fec.SEGMENT), s.sharePermille());
        int step = 1_000_000;
        int encoder = FecSender.encoderBitRate(step, s.sharePermille());
        assertEquals(step * (1000L - s.sharePermille()) / 1000, encoder);
        assertTrue(encoder < step, "the encoder under the step: " + encoder);
        // frame and parity together at the step's figure
        assertEquals(step, encoder / (1 - s.sharePermille() / 1000.0), step * 0.002);
        // a wobble under 2 % leaves it as it is; a real change moves it
        int was = s.sharePermille();
        c.second(3900, Fec.SEGMENT);
        assertEquals(was, s.sharePermille(), "under SHARE_STEP_PERMILLE: kept");
        c.second(2000, Fec.SEGMENT);
        assertEquals(1000L * Fec.SEGMENT / (2000 + Fec.SEGMENT), s.sharePermille());
        // never more than half: the encoder keeps at least half the step
        c.second(300, 4 * Fec.SEGMENT);
        assertEquals(FecSender.MAX_SHARE_PERMILLE, s.sharePermille());
        assertEquals(step / 2, FecSender.encoderBitRate(step, 900));
        // a second without parity: 0; and stop() is 0 without waiting
        c.second(4000, 0);
        assertEquals(0, s.sharePermille());
        c.second(4000, Fec.SEGMENT);
        assertTrue(s.sharePermille() > 0);
        s.stop();
        assertEquals(0, s.sharePermille());
        assertEquals(step, FecSender.encoderBitRate(step, 0), "without parity the step itself");
    }
}
