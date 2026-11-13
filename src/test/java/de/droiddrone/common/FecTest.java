package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Reed-Solomon parity of a video frame's packets: any m of a block's k + m lost and the block comes back to the
 * byte; one more and it says so and touches nothing. Shards of made-up bytes; the frame tests cut a frame as the
 * phone does.
 */
class FecTest {
    private static byte[] bytes(Random r, int n) {
        byte[] b = new byte[n];
        r.nextBytes(b);
        return b;
    }

    private static byte[][] shards(Random r, int k, int length) {
        byte[][] data = new byte[k][];
        for (int j = 0; j < k; j++) data[j] = bytes(r, length);
        return data;
    }

    /** The k data shards and their m parity shards in one array, as decode takes them. */
    private static byte[][] block(byte[][] data, int m) {
        byte[][] all = Arrays.copyOf(data, data.length + m);
        System.arraycopy(Fec.encode(data, m), 0, all, data.length, m);
        return all;
    }

    /** Loses the shards of the bit mask, decodes, and says whether the data is back to the byte. */
    private static void loseAndMend(byte[][] all, int k, int m, long lostMask) {
        byte[][] got = all.clone();
        for (int s = 0; s < k + m; s++) if ((lostMask >> s & 1) != 0) got[s] = null;
        assertTrue(Fec.decode(got, k, m), () -> "k " + k + ", m " + m + ", lost " + Long.toBinaryString(lostMask));
        for (int j = 0; j < k; j++) {
            int at = j;
            assertArrayEquals(all[j], got[j], () -> "k " + k + ", m " + m + ", lost " + Long.toBinaryString(lostMask) + ", shard " + at);
        }
    }

    @Test
    void everyLossOfUpToMShardsIsMended() {
        Random r = new Random(1);
        int patterns = 0;
        for (int k : new int[]{1, 2, 3, 4, 5, 8, 12}) {
            for (int m = 1; m <= 4; m++) {
                byte[][] all = block(shards(r, k, 40), m);
                for (long mask = 0; mask < 1L << (k + m); mask++) {
                    if (Long.bitCount(mask) > m) continue;
                    loseAndMend(all, k, m, mask);
                    patterns++;
                }
            }
        }
        // the most parity a block gets: 8 for 8, every way of losing up to 8 of the 16
        byte[][] all = block(shards(r, 8, 24), 8);
        for (long mask = 0; mask < 1L << 16; mask++) {
            if (Long.bitCount(mask) > 8) continue;
            loseAndMend(all, 8, 8, mask);
            patterns++;
        }
        assertTrue(patterns > 40_000, "patterns tried: " + patterns);
    }

    @Test
    void theBiggestBlockIsMendedFromAnyLossesTried() {
        Random r = new Random(2);
        int k = Fec.MAX_DATA, m = Fec.MAX_PARITY;
        byte[][] all = block(shards(r, k, 64), m);
        for (int a = 0; a < k + m; a++) { // every pair
            for (int b = a + 1; b < k + m; b++) loseAndMend(all, k, m, 1L << a | 1L << b);
        }
        for (int n = 0; n < 3000; n++) { // and 8 at random
            long mask = 0;
            while (Long.bitCount(mask) < m) mask |= 1L << r.nextInt(k + m);
            loseAndMend(all, k, m, mask);
        }
        loseAndMend(all, k, m, 0xffL); // the first 8 packets in a row: a burst
    }

    @Test
    void oneLossTooManyIsToldAndNothingIsTouched() {
        Random r = new Random(3);
        for (int k : new int[]{1, 2, 4, 8, 20}) {
            for (int m = 0; m <= 4; m++) {
                byte[][] all = block(shards(r, k, 50), m);
                for (int n = 0; n < 200; n++) {
                    long mask = 0;
                    mask |= 1L << r.nextInt(k); // one data shard at least: lost parity alone is nothing to mend
                    while (Long.bitCount(mask) < m + 1) mask |= 1L << r.nextInt(k + m);
                    byte[][] got = new byte[k + m][];
                    byte[][] before = new byte[k + m][];
                    for (int s = 0; s < k + m; s++) {
                        if ((mask >> s & 1) != 0) continue;
                        got[s] = all[s].clone();
                        before[s] = got[s];
                    }
                    assertFalse(Fec.decode(got, k, m), "k " + k + ", m " + m + ": " + (m + 1) + " lost");
                    for (int s = 0; s < k + m; s++) {
                        assertSame(before[s], got[s], "no shard put in or taken out");
                        if (got[s] != null) assertArrayEquals(all[s], got[s], "no shard written to");
                    }
                }
            }
        }
    }

    @Test
    void twoLossesInOneGroupAreMended() {
        // 8 packets and 2 lost: the XOR parity of pc.26 (one packet per group of 8) gives the frame up; m = 2 mends it
        Random r = new Random(4);
        byte[][] data = shards(r, 8, Fec.SEGMENT);
        byte[][] all = block(data, 2);
        for (int a = 0; a < 8; a++) {
            for (int b = a + 1; b < 8; b++) loseAndMend(all, 8, 2, 1L << a | 1L << b);
        }
        // the XOR over the group is one equation: with packets 2 and 5 gone it gives their XOR only
        byte[][] got = all.clone();
        got[2] = null;
        got[5] = null;
        got[9] = null; // and with the second parity gone too, this code can do no more than the XOR
        assertFalse(Fec.decode(got, 8, 2));
    }

    @Test
    void theFirstParityIsThePlainXor() {
        Random r = new Random(5);
        byte[][] data = shards(r, 7, 100);
        byte[] xor = new byte[100];
        for (byte[] d : data) for (int i = 0; i < 100; i++) xor[i] ^= d[i];
        assertArrayEquals(xor, Fec.encode(data, 1)[0]);
        assertArrayEquals(xor, Fec.encode(data, 3)[0], "whatever m is: a parity packet doesn't change with it");
        assertArrayEquals(Fec.encode(data, 2)[1], Fec.encode(data, 5)[1]);
    }

    @Test
    void shardsOfUnequalLengthCountAsZeroFilled() {
        Random r = new Random(6);
        // as a frame's packets: the first 4 bytes short, the last shorter
        byte[][] data = {bytes(r, Fec.FIRST_SEGMENT), bytes(r, Fec.SEGMENT), bytes(r, Fec.SEGMENT), bytes(r, 301)};
        byte[][] parity = Fec.encode(data, 2);
        assertEquals(Fec.SEGMENT, parity[0].length, "as long as the longest");
        for (int a = 0; a < 4; a++) {
            for (int b = a; b < 4; b++) {
                byte[][] got = {data[0], data[1], data[2], data[3], parity[0], parity[1]};
                got[a] = null;
                got[b] = null;
                assertTrue(Fec.decode(got, 4, 2));
                for (int j = 0; j < 4; j++) {
                    assertEquals(j == a || j == b ? Fec.SEGMENT : data[j].length, got[j].length, "a rebuilt shard has the parity's length");
                    assertArrayEquals(data[j], Arrays.copyOf(got[j], data[j].length), "its true length of it is the packet");
                    for (int i = data[j].length; i < got[j].length; i++) assertEquals(0, got[j][i], "and zeros after");
                }
            }
        }
        // the short one alone: its parity is as short
        byte[][] one = {bytes(r, 301)};
        assertEquals(301, Fec.encode(one, 1)[0].length);
    }

    @Test
    void aBlockOfOnePacket() {
        Random r = new Random(7);
        byte[][] data = {bytes(r, 500)};
        byte[][] parity = Fec.encode(data, 3);
        assertArrayEquals(data[0], parity[0], "the first parity of one packet is the packet");
        for (int i = 0; i < 3; i++) {
            byte[][] got = new byte[4][];
            got[1 + i] = parity[i];
            assertTrue(Fec.decode(got, 1, 3), "from parity " + i + " alone");
            assertArrayEquals(data[0], got[0]);
        }
        assertFalse(Fec.decode(new byte[4][], 1, 3), "nothing came");
    }

    @Test
    void noParityMendsNothing() {
        Random r = new Random(8);
        byte[][] data = shards(r, 5, 30);
        assertEquals(0, Fec.encode(data, 0).length);
        assertTrue(Fec.decode(data.clone(), 5, 0), "all there");
        byte[][] got = data.clone();
        got[3] = null;
        assertFalse(Fec.decode(got, 5, 0));
        assertNull(got[3]);
    }

    @Test
    void shardsThatCantBeOneBlocksAreRefused() {
        Random r = new Random(9);
        byte[][] data = shards(r, 3, 30);
        byte[][] parity = Fec.encode(data, 2);
        byte[][] got = {data[0], null, data[2], parity[0], Arrays.copyOf(parity[1], 20)};
        assertFalse(Fec.decode(got, 3, 2), "parity of two lengths");
        got = new byte[][]{bytes(r, 31), null, data[2], parity[0], null};
        assertFalse(Fec.decode(got, 3, 2), "data longer than its parity");
        assertThrows(IllegalArgumentException.class, () -> Fec.encode(new byte[Fec.MAX_DATA + 1][1], 1));
        assertThrows(IllegalArgumentException.class, () -> Fec.encode(new byte[4][1], Fec.MAX_PARITY + 1));
        assertThrows(IllegalArgumentException.class, () -> Fec.encode(new byte[0][], 1));
    }

    @Test
    void aFramesPacketsAreCountedAsThePhoneCutsThem() {
        assertEquals(0, Fec.fragments(0));
        assertEquals(1, Fec.fragments(1));
        assertEquals(1, Fec.fragments(Fec.FIRST_SEGMENT));
        assertEquals(2, Fec.fragments(Fec.FIRST_SEGMENT + 1));
        assertEquals(2, Fec.fragments(Fec.FIRST_SEGMENT + Fec.SEGMENT));
        assertEquals(3, Fec.fragments(Fec.FIRST_SEGMENT + Fec.SEGMENT + 1));
        int size = Fec.FIRST_SEGMENT + 2 * Fec.SEGMENT + 17;
        assertEquals(0, Fec.offsetOf(0));
        assertEquals(Fec.FIRST_SEGMENT, Fec.offsetOf(1));
        assertEquals(Fec.FIRST_SEGMENT + Fec.SEGMENT, Fec.offsetOf(2));
        assertEquals(Fec.FIRST_SEGMENT, Fec.lengthOf(0, size));
        assertEquals(Fec.SEGMENT, Fec.lengthOf(2, size));
        assertEquals(17, Fec.lengthOf(3, size));
        assertEquals(0, Fec.lengthOf(4, size), "no such packet");
        assertEquals(1087, Fec.FIRST_SEGMENT, "docs/PROTOCOL.md: 1087 bytes in the first packet");
        assertEquals(1091, Fec.SEGMENT, "and 1091 in the rest");
    }

    @Test
    void thePlanSpreadsTheParityEvenlyThroughTheFrame() {
        assertEquals(0, Fec.plan(0, 2).length);
        Fec.Block[] one = Fec.plan(1, 2);
        assertEquals(1, one.length);
        assertEquals(0, one[0].first);
        assertEquals(1, one[0].k);
        assertEquals(2, one[0].m);
        assertEquals(1, Fec.plan(32, 1).length, "32 packets are one block still");
        Fec.Block[] forty = Fec.plan(40, 4);
        assertEquals(2, forty.length, "40 packets: 20 + 20, not 32 + 8");
        assertEquals(20, forty[0].k);
        assertEquals(20, forty[1].first);
        assertEquals(20, forty[1].k);
        assertEquals(4, forty[1].m);
        assertEquals(Fec.MAX_PARITY, Fec.plan(4, 99)[0].m, "more parity than a block takes: the most it takes");
        assertEquals(0, Fec.plan(4, -1)[0].m);
        for (int n = 1; n <= Fec.MAX_BLOCKS * Fec.MAX_DATA; n += n < 200 ? 1 : 37) {
            Fec.Block[] plan = Fec.plan(n, 3);
            assertEquals((n + Fec.MAX_DATA - 1) / Fec.MAX_DATA, plan.length, "the fewest blocks that hold " + n);
            int next = 0, least = Integer.MAX_VALUE, most = 0;
            for (int b = 0; b < plan.length; b++) {
                assertEquals(next, plan[b].first, "the blocks follow each other");
                assertEquals(plan[b].first, Fec.blockFirst(n, b));
                assertEquals(plan[b].k, Fec.blockK(n, b));
                next += plan[b].k;
                least = Math.min(least, plan[b].k);
                most = Math.max(most, plan[b].k);
            }
            assertEquals(n, next, "every packet is in a block");
            assertTrue(most <= Fec.MAX_DATA && most - least <= 1, n + " packets: blocks of " + least + " to " + most);
        }
        assertEquals(0, Fec.plan(Fec.MAX_BLOCKS * Fec.MAX_DATA + 1, 2).length, "too big for the scheme: bare");
        assertEquals(-1, Fec.blockFirst(40, 2));
        assertEquals(0, Fec.blockK(40, 2));
    }

    /** A frame's parity, block by block, as the phone would make it: [block][index] -> the shard. */
    private static byte[][][] parityOf(byte[] frame, int m) {
        int n = Fec.fragments(frame.length);
        byte[][][] parity = new byte[Fec.blocks(n)][m][];
        for (int b = 0; b < parity.length; b++) {
            int first = Fec.blockFirst(n, b), k = Fec.blockK(n, b);
            for (int i = 0; i < m; i++) parity[b][i] = new byte[Fec.shardLength(frame.length, first, k)];
            assertEquals(parity[b][0].length, Fec.encode(frame, frame.length, first, k, m, parity[b], 0));
        }
        return parity;
    }

    @Test
    void aFrameInOneArrayIsMendedInPlace() {
        Random r = new Random(10);
        int[] sizes = {1, 300, Fec.FIRST_SEGMENT, Fec.FIRST_SEGMENT + 1, Fec.FIRST_SEGMENT + Fec.SEGMENT,
                Fec.FIRST_SEGMENT + 3 * Fec.SEGMENT + 500, Fec.FIRST_SEGMENT + 38 * Fec.SEGMENT + 300, Fec.FIRST_SEGMENT + 70 * Fec.SEGMENT + 1};
        for (int size : sizes) {
            byte[] frame = bytes(r, size);
            int n = Fec.fragments(size);
            for (int m = 1; m <= 4; m++) {
                byte[][][] parity = parityOf(frame, m);
                for (int round = 0; round < 30; round++) {
                    byte[] got = frame.clone();
                    for (int b = 0; b < parity.length; b++) {
                        int first = Fec.blockFirst(n, b), k = Fec.blockK(n, b);
                        boolean[] have = new boolean[k];
                        Arrays.fill(have, true);
                        byte[][] p = parity[b].clone();
                        // m of the block's k + m lost, anywhere: what didn't come is zeros in the frame, as in the assembler
                        for (int lost = 0; lost < m; ) {
                            int s = r.nextInt(k + m);
                            if (s < k ? !have[s] : p[s - k] == null) continue;
                            if (s < k) {
                                have[s] = false;
                                Arrays.fill(got, Fec.offsetOf(first + s), Fec.offsetOf(first + s) + Fec.lengthOf(first + s, size), (byte) 0);
                            } else {
                                p[s - k] = null;
                            }
                            lost++;
                        }
                        assertTrue(Fec.decode(got, size, first, k, have, p, 0, m), "size " + size + ", m " + m + ", block " + b);
                    }
                    assertArrayEquals(frame, got, "size " + size + ", m " + m);
                }
            }
        }
    }

    @Test
    void aFramesBlockWithTooMuchLostIsLeftAsItWas() {
        Random r = new Random(11);
        int size = Fec.FIRST_SEGMENT + 8 * Fec.SEGMENT + 40; // 10 packets
        byte[] frame = bytes(r, size);
        assertEquals(10, Fec.fragments(size));
        byte[][] parity = parityOf(frame, 2)[0];
        boolean[] have = new boolean[10];
        Arrays.fill(have, true);
        byte[] got = frame.clone();
        for (int lost : new int[]{0, 4, 9}) {
            have[lost] = false;
            Arrays.fill(got, Fec.offsetOf(lost), Fec.offsetOf(lost) + Fec.lengthOf(lost, size), (byte) 0x55);
        }
        byte[] before = got.clone();
        assertFalse(Fec.decode(got, size, 0, 10, have, parity, 0, 2), "3 lost, 2 parity");
        assertArrayEquals(before, got);
        have[4] = true; // two lost, but one parity is another block's (too short)
        System.arraycopy(frame, Fec.offsetOf(4), got, Fec.offsetOf(4), Fec.SEGMENT);
        before = got.clone();
        assertFalse(Fec.decode(got, size, 0, 10, have, new byte[][]{parity[0], new byte[100]}, 0, 2));
        assertArrayEquals(before, got);
        assertTrue(Fec.decode(got, size, 0, 10, have, parity, 0, 2), "with its own: mended, whatever stood where the packets belong");
        assertArrayEquals(frame, got);
    }

    @Test
    void theParityIsBuiltStraightIntoItsPacket() {
        Random r = new Random(12);
        int size = Fec.FIRST_SEGMENT + 39 * Fec.SEGMENT; // 40 full packets
        byte[] frame = bytes(r, size);
        int n = Fec.fragments(size);
        assertEquals(40, n);
        int first = Fec.blockFirst(n, 1), k = Fec.blockK(n, 1);
        byte[][] packets = new byte[4][UdpCommon.packetLength];
        for (byte[] p : packets) Arrays.fill(p, (byte) 0x77); // whatever was in the buffer
        int length = Fec.encode(frame, size, first, k, 4, packets, Fec.HEADER);
        assertEquals(Fec.SEGMENT, length);
        assertEquals(UdpCommon.packetLength, Fec.HEADER + length, "a full parity packet is the link's longest packet, not more");
        for (int i = 0; i < 4; i++) {
            packets[i][0] = 0x55;
            Fec.putHeader(packets[i], 12345, size, true, 1, k, 4, i);
            assertTrue(Fec.valid(packets[i], Fec.HEADER + length));
        }
        // the PC's side: the packets as they came, the parity read where it lies
        boolean[] have = new boolean[k];
        Arrays.fill(have, true);
        byte[] got = frame.clone();
        for (int lost : new int[]{0, 7, 8, 19}) {
            have[lost] = false;
            Arrays.fill(got, Fec.offsetOf(first + lost), Fec.offsetOf(first + lost) + Fec.SEGMENT, (byte) 0);
        }
        assertTrue(Fec.decode(got, size, first, k, have, packets, Fec.HEADER, 4));
        assertArrayEquals(frame, got);
    }

    @Test
    void theHeaderGoesThereAndBack() {
        int size = Fec.FIRST_SEGMENT + 70 * Fec.SEGMENT + 1; // 72 packets: 3 blocks of 24
        byte[] d = new byte[Fec.HEADER + Fec.SEGMENT];
        Fec.putHeader(d, -2, size, false, 2, 24, 8, 7);
        assertEquals((short) -2, Fec.frameNum(d));
        assertEquals(size, Fec.frameSize(d));
        assertFalse(Fec.keyFrame(d));
        assertEquals(2, Fec.block(d));
        assertEquals(24, Fec.k(d));
        assertEquals(8, Fec.m(d));
        assertEquals(7, Fec.index(d));
        assertTrue(Fec.valid(d, d.length));
        Fec.putHeader(d, 32767, size, true, 2, 24, 1, 0);
        assertEquals(32767, Fec.frameNum(d));
        assertTrue(Fec.keyFrame(d));
        assertEquals(2, Fec.block(d), "the key frame's bit is not the block's");
        assertTrue(Fec.valid(d, d.length));
        // the biggest frame the PC takes fits the size's 24 bits and the block's 7
        int biggest = 1920 * 1080 * 3 / 2 + (64 << 10);
        int blocks = Fec.blocks(Fec.fragments(biggest));
        assertTrue(biggest < 1 << 24 && blocks > 0 && blocks <= Fec.MAX_BLOCKS, blocks + " blocks");
        Fec.putHeader(d, 1, biggest, true, blocks - 1, Fec.blockK(Fec.fragments(biggest), blocks - 1), 2, 1);
        assertEquals(biggest, Fec.frameSize(d));
        assertEquals(blocks - 1, Fec.block(d));
    }

    @Test
    void aPacketThatDisagreesWithItselfIsNotValid() {
        int size = Fec.FIRST_SEGMENT + 70 * Fec.SEGMENT + 1;
        byte[] d = new byte[Fec.HEADER + Fec.SEGMENT];
        Fec.putHeader(d, 5, size, false, 1, 24, 2, 1);
        assertTrue(Fec.valid(d, d.length));
        assertFalse(Fec.valid(d, d.length - 1), "cut: not the block's parity length");
        assertFalse(Fec.valid(d, Fec.HEADER), "no parity at all");
        Fec.putHeader(d, 5, size, false, 1, 23, 2, 1);
        assertFalse(Fec.valid(d, d.length), "not the k this side counts for the block");
        Fec.putHeader(d, 5, size, false, 3, 24, 2, 1);
        assertFalse(Fec.valid(d, d.length), "a block the frame doesn't have");
        Fec.putHeader(d, 5, size, false, 1, 24, 2, 2);
        assertFalse(Fec.valid(d, d.length), "an index at or over m");
        Fec.putHeader(d, 5, size, false, 1, 24, 0, 0);
        assertFalse(Fec.valid(d, d.length), "m 0 sends no parity");
        Fec.putHeader(d, 5, size, false, 1, 24, 9, 0);
        assertFalse(Fec.valid(d, d.length), "more parity than a block gets");
        Fec.putHeader(d, 5, 0, false, 0, 1, 1, 0);
        assertFalse(Fec.valid(d, d.length), "no frame");
        // the last block of a short frame: its parity is as long as its longest packet, not a full one
        byte[] small = new byte[Fec.HEADER + 300];
        Fec.putHeader(small, 5, 300, false, 0, 1, 1, 0);
        assertTrue(Fec.valid(small, small.length));
        assertFalse(Fec.valid(small, small.length + 1));
    }

    @Test
    void theParitysShareOfTheUpload() {
        assertEquals(0, Fec.parityBytes(5000, 0));
        assertEquals(300, Fec.parityBytes(300, 1), "one short packet: its parity is a copy");
        assertEquals(2 * Fec.SEGMENT, Fec.parityBytes(Fec.FIRST_SEGMENT + 3 * Fec.SEGMENT, 2));
        assertEquals(2 * 4 * Fec.SEGMENT, Fec.parityBytes(Fec.FIRST_SEGMENT + 39 * Fec.SEGMENT, 4), "two blocks, 4 each");
        assertEquals(Fec.FIRST_SEGMENT, Fec.parityBytes(Fec.FIRST_SEGMENT + 10, 1), "as long as the longer of the two");
    }

    /** What the parity costs: printed, not judged (the phone makes it for every frame, up to 60 a second). */
    @Test
    void whatA40PacketFrameCosts() {
        Random r = new Random(13);
        int size = Fec.FIRST_SEGMENT + 38 * Fec.SEGMENT + 300; // 40 packets
        byte[] frame = bytes(r, size);
        int n = Fec.fragments(size), m = 4, blocks = Fec.blocks(n);
        byte[][][] parity = new byte[blocks][m][Fec.SEGMENT];
        int rounds = 3000;
        long encodeNs = 0;
        for (int pass = 0; pass < 2; pass++) { // the first pass warms the JIT
            long start = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                for (int b = 0; b < blocks; b++) Fec.encode(frame, size, Fec.blockFirst(n, b), Fec.blockK(n, b), m, parity[b], 0);
            }
            encodeNs = System.nanoTime() - start;
        }
        // one parity a block (the plain XOR) for comparison
        long xorNs = 0;
        for (int pass = 0; pass < 2; pass++) {
            long start = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                for (int b = 0; b < blocks; b++) Fec.encode(frame, size, Fec.blockFirst(n, b), Fec.blockK(n, b), 1, parity[b], 0);
            }
            xorNs = System.nanoTime() - start;
        }
        for (int b = 0; b < blocks; b++) Fec.encode(frame, size, Fec.blockFirst(n, b), Fec.blockK(n, b), m, parity[b], 0);
        // the worst a decode has to do: 4 packets of each block gone
        byte[] got = frame.clone();
        boolean[][] have = new boolean[blocks][];
        for (int b = 0; b < blocks; b++) {
            have[b] = new boolean[Fec.blockK(n, b)];
            Arrays.fill(have[b], true);
            for (int lost = 0; lost < m; lost++) have[b][lost * 3] = false;
        }
        long decodeNs = 0;
        for (int pass = 0; pass < 2; pass++) {
            long start = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                for (int b = 0; b < blocks; b++) Fec.decode(got, size, Fec.blockFirst(n, b), Fec.blockK(n, b), have[b], parity[b], 0, m);
            }
            decodeNs = System.nanoTime() - start;
        }
        assertArrayEquals(frame, got);
        System.out.printf("Fec: a %d-packet frame (%d bytes, %d blocks): encode m=4 %.0f us, m=1 %.0f us, decode with 4 lost a block %.0f us%n",
                n, size, blocks, encodeNs / 1e3 / rounds, xorNs / 1e3 / rounds, decodeNs / 1e3 / rounds);
    }
}
