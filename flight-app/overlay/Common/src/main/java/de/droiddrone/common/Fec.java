/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone.
 *
 *  DroidDrone is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DroidDrone is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidDrone.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.common;

import java.util.Arrays;

/**
 * DRAUPNE Flight / DRAUPNE Link: Reed-Solomon parity for the packets of one video frame. The XOR parity of pc.26
 * (VIDEO_PARITY_GROUP, one packet per 8) mends one lost packet of a group; two lost in one group and the frame is
 * damaged. Here a block of k packets gets m parity packets and any m of the k + m may be lost - the phone makes them,
 * the PC rebuilds with them. Pure, static, no state: the phone's video sender and the PC's FrameAssembler call it, the
 * PC's tests check it.
 * <ul>
 * <li>The frame's packets as the phone cuts them ({@link #fragments}, {@link #offsetOf}, {@link #lengthOf}): the first
 * carries {@link #FIRST_SEGMENT} bytes, every later one {@link #SEGMENT}, the last what is left.</li>
 * <li>Blocks ({@link #blocks}, {@link #blockFirst}, {@link #blockK}; {@link #plan} for all of them at once): a frame
 * of up to {@link #MAX_DATA} packets is one block; a bigger one is cut into the fewest blocks that hold it, their
 * sizes at most one apart - 40 packets are 20 + 20, not 32 + 8 - so every block has the same share of parity, and each
 * block's parity goes out right behind it. Both sides find a block's place from the frame's size alone.</li>
 * <li>The code: systematic (the data packets go out as they are) over GF(256), polynomial 0x11D. Parity i of a block
 * is the sum over its packets j of c(i, j) x packet j, with c a Cauchy matrix 1 / ((32 + i) xor j) whose columns are
 * scaled to make row 0 all ones: every square part of it can be inverted, so any k of the k + m rebuild the block,
 * and the first parity is the plain XOR of the block (m = 1 costs no multiplication). c(i, j) depends on i and j
 * only, not on k or m: one table for every block, and a parity packet stays good if m changes on the way.</li>
 * <li>Lengths: the maths wants shards of one length, and the first packet is 4 bytes short and the last shorter - they
 * count as filled up with zeros. A block's parity is as long as its longest packet ({@link #shardLength}); what a
 * rebuilt packet really holds comes from the frame's size, which every parity packet carries ({@link #lengthOf}).</li>
 * <li>Cost: tables made once (64 KB of products), no allocation in {@link #encode(byte[], int, int, int, int,
 * byte[][], int)} and none in a decode that has nothing to do; a 40-packet frame with m = 4 encodes in well under a
 * millisecond on a PC (FecTest prints it).</li>
 * </ul>
 * The wire, as planned (the ids go into PacketIds with the wiring):
 * <ul>
 * <li>{@code VIDEO_PROFILE} bit 4 (16): the PC understands this parity. Bit 1 stays set beside it, for a phone that
 * knows the XOR groups only.</li>
 * <li>{@code VIDEO_FEC [0x54][mPlain u8][mKey u8]} PC to phone: parity packets per block for plain frames and for key
 * frames, 0-{@link #MAX_PARITY} each (the PC's FecPolicy picks them). 0, or bit 4 off: the XOR groups as before.</li>
 * <li>{@code VIDEO_PARITY_RS [0x55][frameNum i16][frameSize u24][keyFrame 1 bit | block 7 bits][k u8][m 4 bits |
 * index 4 bits][parity]} phone to PC, {@link #HEADER} bytes before the parity ({@link #putHeader}, {@link #valid} and
 * the readers beside it). Not the 12 bytes of one field a byte each ([frameSize i32][keyFrame][block][k][m][index]):
 * with a full packet's 1091 bytes of parity that is 1103, over the link's {@link UdpCommon#packetLength} (no packet
 * is longer, and upstream's relay cuts a datagram there); this one is 1100 at most, as VIDEO_PARITY_GROUP. 24 bits
 * hold every frame the PC takes (3.2 MB at most), 7 bits every block of it.</li>
 * </ul>
 */
public final class Fec {
    /** The most packets in a block (k) and the most parity packets a block gets (m). */
    public static final int MAX_DATA = 32, MAX_PARITY = 8;
    /** The most blocks a frame is cut into (the header's 7 bits): 4096 packets, 4.4 MB - no encoded frame is. */
    public static final int MAX_BLOCKS = 128;
    /** The frame bytes in a frame's first packet and in every later one (upstream's sums: 13 and 9 bytes of header). */
    public static final int FIRST_SEGMENT = UdpCommon.packetLength - 13;
    public static final int SEGMENT = UdpCommon.packetLength - 9;
    /** The bytes of a VIDEO_PARITY_RS packet before its parity. */
    public static final int HEADER = 9;

    private static final int POLYNOMIAL = 0x11d;
    /** GF(256): powers of 2 (twice over, so a sum of two logarithms needs no modulo) and logarithms. */
    private static final int[] EXP = new int[510];
    private static final int[] LOG = new int[256];
    /** Every product: MUL[a][b] = a x b. */
    private static final byte[][] MUL = new byte[256][256];
    /** c(i, j): parity i's factor for packet j of a block. Row 0 is all ones. */
    private static final byte[][] COEF = new byte[MAX_PARITY][MAX_DATA];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            EXP[i + 255] = x;
            LOG[x] = i;
            x <<= 1;
            if (x >= 256) x ^= POLYNOMIAL;
        }
        for (int a = 1; a < 256; a++) {
            for (int b = 1; b < 256; b++) MUL[a][b] = (byte) EXP[LOG[a] + LOG[b]];
        }
        // 32 + i and j never meet (j < 32), so no 1 / 0; each column divided by its row-0 value
        for (int j = 0; j < MAX_DATA; j++) {
            int top = MAX_DATA ^ j;
            for (int i = 0; i < MAX_PARITY; i++) COEF[i][j] = (byte) mul(inv((MAX_DATA + i) ^ j), top);
        }
    }

    private Fec() {
    }

    private static int mul(int a, int b) {
        return a == 0 || b == 0 ? 0 : EXP[LOG[a] + LOG[b]];
    }

    private static int inv(int a) {
        return EXP[255 - LOG[a]];
    }

    /** dst ^= c x src over n bytes. */
    private static void addMul(byte[] dst, int dstAt, byte[] src, int srcAt, int n, int c) {
        if (c == 0 || n <= 0) return;
        if (c == 1) {
            for (int i = 0; i < n; i++) dst[dstAt + i] ^= src[srcAt + i];
            return;
        }
        byte[] row = MUL[c];
        for (int i = 0; i < n; i++) dst[dstAt + i] ^= row[src[srcAt + i] & 0xff];
    }

    private static void check(int k, int m) {
        if (k < 1 || k > MAX_DATA || m < 0 || m > MAX_PARITY) throw new IllegalArgumentException("k " + k + ", m " + m);
    }

    // ---- the frame's packets and its blocks

    /** How many packets the phone cuts a frame of frameSize bytes into; 0 for no frame. */
    public static int fragments(int frameSize) {
        if (frameSize <= 0) return 0;
        return frameSize <= FIRST_SEGMENT ? 1 : 1 + (frameSize - FIRST_SEGMENT + SEGMENT - 1) / SEGMENT;
    }

    /** Where packet number fragment (0, 1, ...) begins in its frame. */
    public static int offsetOf(int fragment) {
        return fragment == 0 ? 0 : FIRST_SEGMENT + (fragment - 1) * SEGMENT;
    }

    /** How many bytes of the frame that packet carries: this, not the parity's length, is a rebuilt packet's size. */
    public static int lengthOf(int fragment, int frameSize) {
        return Math.max(0, Math.min(fragment == 0 ? FIRST_SEGMENT : SEGMENT, frameSize - offsetOf(fragment)));
    }

    /** How many blocks a frame of that many packets has; 0 for none, or more than {@link #MAX_BLOCKS} would take. */
    public static int blocks(int fragments) {
        if (fragments <= 0 || fragments > MAX_BLOCKS * MAX_DATA) return 0;
        return (fragments + MAX_DATA - 1) / MAX_DATA;
    }

    /** The first packet of a block: the blocks follow each other, the bigger ones (by one packet) first. */
    public static int blockFirst(int fragments, int block) {
        int n = blocks(fragments);
        if (n == 0 || block < 0 || block >= n) return -1;
        return block * (fragments / n) + Math.min(block, fragments % n);
    }

    /** How many packets a block holds (its k); 0 for a block the frame doesn't have. */
    public static int blockK(int fragments, int block) {
        int n = blocks(fragments);
        if (n == 0 || block < 0 || block >= n) return 0;
        return fragments / n + (block < fragments % n ? 1 : 0);
    }

    /** How long the parity of packets first .. first + k - 1 is: the longest of them (the others count as zero-filled). */
    public static int shardLength(int frameSize, int first, int k) {
        return Math.max(lengthOf(first, frameSize), k > 1 ? lengthOf(first + 1, frameSize) : 0);
    }

    /** The parity bytes a frame gets with m parity packets a block (headers not counted): what the parity adds to the upload. */
    public static int parityBytes(int frameSize, int m) {
        int n = fragments(frameSize), bytes = 0;
        for (int b = blocks(n) - 1; b >= 0; b--) bytes += m * shardLength(frameSize, blockFirst(n, b), blockK(n, b));
        return bytes;
    }

    /** One block of a frame: its first packet, how many it holds, and its parity packets. */
    public static final class Block {
        public final int first, k, m;

        Block(int first, int k, int m) {
            this.first = first;
            this.k = k;
            this.m = m;
        }
    }

    /**
     * The blocks of a frame of that many packets, m parity packets each (taken into 0..{@link #MAX_PARITY}): what
     * {@link #blocks}, {@link #blockFirst} and {@link #blockK} say, in one array (the sender's loop can ask those
     * three and allocate nothing). Empty for no frame, or one too big for the scheme - that one goes out bare.
     */
    public static Block[] plan(int fragments, int m) {
        int n = blocks(fragments);
        int parity = Math.max(0, Math.min(MAX_PARITY, m));
        Block[] plan = new Block[n];
        for (int b = 0; b < n; b++) plan[b] = new Block(blockFirst(fragments, b), blockK(fragments, b), parity);
        return plan;
    }

    // ---- shards as arrays of their own

    /**
     * The m parity shards of k = data.length data shards: new arrays, each as long as the longest data shard (a
     * shorter one counts as zero-filled).
     */
    public static byte[][] encode(byte[][] data, int m) {
        check(data.length, m);
        int length = 0;
        for (byte[] d : data) length = Math.max(length, d.length);
        byte[][] parity = new byte[m][length];
        encode(data, data.length, m, parity, 0);
        return parity;
    }

    /**
     * The same into arrays in hand: parity[i] gets shard i from index at on, the longest data shard's length of it.
     * @return that length
     */
    public static int encode(byte[][] data, int k, int m, byte[][] parity, int at) {
        check(k, m);
        int length = 0;
        for (int j = 0; j < k; j++) length = Math.max(length, data[j].length);
        for (int i = 0; i < m; i++) {
            byte[] p = parity[i], coef = COEF[i];
            Arrays.fill(p, at, at + length, (byte) 0);
            for (int j = 0; j < k; j++) addMul(p, at, data[j], 0, data[j].length, coef[j] & 0xff);
        }
        return length;
    }

    /**
     * Rebuilds the lost data shards. shards holds the k data shards, then the m parity shards; null = lost. With as
     * many parity shards in hand as data shards are lost, each lost one becomes a new array of the parity's length
     * (the caller cuts it to its true length) and the answer is true. Otherwise false, and nothing is touched: more
     * lost than parity came, or shards that can't be one block's (parity of two lengths, data longer than its parity).
     * Lost parity is not rebuilt. Nothing lost: true at once, nothing allocated.
     */
    public static boolean decode(byte[][] shards, int k, int m) {
        check(k, m);
        int lostCount = 0;
        for (int j = 0; j < k; j++) if (shards[j] == null) lostCount++;
        if (lostCount == 0) return true;
        int length = -1, parityCount = 0;
        for (int i = 0; i < m; i++) {
            byte[] p = shards[k + i];
            if (p == null) continue;
            if (length < 0) length = p.length;
            else if (p.length != length) return false;
            parityCount++;
        }
        if (lostCount > parityCount) return false;
        for (int j = 0; j < k; j++) if (shards[j] != null && shards[j].length > length) return false;
        int[] lost = new int[lostCount], rows = new int[lostCount];
        for (int j = 0, n = 0; j < k; j++) if (shards[j] == null) lost[n++] = j;
        for (int i = 0, n = 0; i < m && n < lostCount; i++) if (shards[k + i] != null) rows[n++] = i;
        int[] solved = invert(rows, lost, lostCount);
        if (solved == null) return false;
        byte[][] rebuilt = new byte[lostCount][length];
        for (int b = 0; b < lostCount; b++) {
            for (int a = 0; a < lostCount; a++) addMul(rebuilt[b], 0, shards[k + rows[a]], 0, length, solved[b * lostCount + a]);
            for (int j = 0; j < k; j++) {
                if (shards[j] != null) addMul(rebuilt[b], 0, shards[j], 0, shards[j].length, factor(solved, rows, lostCount, b, j));
            }
        }
        for (int b = 0; b < lostCount; b++) shards[lost[b]] = rebuilt[b];
        return true;
    }

    // ---- a block of a frame that lies in one array (the phone's encoder output, the PC's frame being put together)

    /**
     * The parity of packets first .. first + k - 1 of the frame, straight from its array: parity[i] gets shard i
     * from index at on ({@link #HEADER} for a packet built in place). Nothing is allocated.
     * @return the shards' length ({@link #shardLength})
     */
    public static int encode(byte[] frame, int frameSize, int first, int k, int m, byte[][] parity, int at) {
        check(k, m);
        int length = shardLength(frameSize, first, k);
        for (int i = 0; i < m; i++) {
            byte[] p = parity[i], coef = COEF[i];
            Arrays.fill(p, at, at + length, (byte) 0);
            for (int j = 0; j < k; j++) addMul(p, at, frame, offsetOf(first + j), lengthOf(first + j, frameSize), coef[j] & 0xff);
        }
        return length;
    }

    /**
     * Rebuilds the packets of a block that didn't come, in the frame's own array (at least frameSize long), each at
     * its place and in its true length.
     * @param have   have[j]: packet first + j is in the frame, whole; left as it is (the caller marks the rebuilt ones)
     * @param parity parity[i]: shard i from index at on, at least {@link #shardLength} of it; null = not come
     * @return true if every packet of the block is in the frame now. False - more lost than parity came, or a parity
     * too short to be this block's - and the frame is as it was.
     */
    public static boolean decode(byte[] frame, int frameSize, int first, int k, boolean[] have, byte[][] parity, int at, int m) {
        check(k, m);
        int lostCount = 0;
        for (int j = 0; j < k; j++) if (!have[j]) lostCount++;
        if (lostCount == 0) return true;
        int length = shardLength(frameSize, first, k), parityCount = 0;
        for (int i = 0; i < m; i++) {
            if (parity[i] == null) continue;
            if (parity[i].length < at + length) return false;
            parityCount++;
        }
        if (lostCount > parityCount || frame.length < frameSize) return false;
        int[] lost = new int[lostCount], rows = new int[lostCount];
        for (int j = 0, n = 0; j < k; j++) if (!have[j]) lost[n++] = j;
        for (int i = 0, n = 0; i < m && n < lostCount; i++) if (parity[i] != null) rows[n++] = i;
        int[] solved = invert(rows, lost, lostCount);
        if (solved == null) return false;
        for (int b = 0; b < lostCount; b++) {
            int to = offsetOf(first + lost[b]), n = lengthOf(first + lost[b], frameSize);
            Arrays.fill(frame, to, to + n, (byte) 0);
            for (int a = 0; a < lostCount; a++) addMul(frame, to, parity[rows[a]], at, n, solved[b * lostCount + a]);
            for (int j = 0; j < k; j++) {
                if (!have[j]) continue;
                addMul(frame, to, frame, offsetOf(first + j), Math.min(n, lengthOf(first + j, frameSize)), factor(solved, rows, lostCount, b, j));
            }
        }
        return true;
    }

    /**
     * The parity rows in hand against the lost packets is a square matrix A: row a says parity rows[a] = the sum of
     * c(rows[a], lost[b]) x lost packet b, once what came is taken out. Its inverse (Gauss-Jordan, at most 8 x 8),
     * row b after row b; null if it has none - with this code's matrix it always has.
     */
    private static int[] invert(int[] rows, int[] lost, int e) {
        int w = 2 * e;
        int[] g = new int[e * w];
        for (int a = 0; a < e; a++) {
            for (int b = 0; b < e; b++) g[a * w + b] = COEF[rows[a]][lost[b]] & 0xff;
            g[a * w + e + a] = 1;
        }
        for (int col = 0; col < e; col++) {
            int pivot = col;
            while (pivot < e && g[pivot * w + col] == 0) pivot++;
            if (pivot == e) return null;
            if (pivot != col) {
                for (int c = 0; c < w; c++) {
                    int t = g[col * w + c];
                    g[col * w + c] = g[pivot * w + c];
                    g[pivot * w + c] = t;
                }
            }
            int scale = inv(g[col * w + col]);
            for (int c = 0; c < w; c++) g[col * w + c] = mul(g[col * w + c], scale);
            for (int r = 0; r < e; r++) {
                int f = g[r * w + col];
                if (r == col || f == 0) continue;
                for (int c = 0; c < w; c++) g[r * w + c] ^= mul(f, g[col * w + c]);
            }
        }
        int[] solved = new int[e * e];
        for (int b = 0; b < e; b++) {
            for (int a = 0; a < e; a++) solved[b * e + a] = g[b * w + e + a];
        }
        return solved;
    }

    /**
     * Lost packet b = the sum of solved(b, a) x (parity rows[a] - what the packets that came put into it): this is
     * the factor of packet j, which came, in that sum - so a lost packet is added up straight from the parity and the
     * data in hand, with no array in between.
     */
    private static int factor(int[] solved, int[] rows, int e, int b, int j) {
        int c = 0;
        for (int a = 0; a < e; a++) c ^= mul(solved[b * e + a], COEF[rows[a]][j] & 0xff);
        return c;
    }

    // ---- the VIDEO_PARITY_RS packet's header (d[0] is the packet's id, written by the sender)

    /** Writes d[1..8]: [frameNum i16][frameSize u24][keyFrame 1 bit | block 7 bits][k u8][m 4 bits | index 4 bits]. */
    public static void putHeader(byte[] d, int frameNum, int frameSize, boolean keyFrame, int block, int k, int m, int index) {
        d[1] = (byte) (frameNum >> 8);
        d[2] = (byte) frameNum;
        d[3] = (byte) (frameSize >> 16);
        d[4] = (byte) (frameSize >> 8);
        d[5] = (byte) frameSize;
        d[6] = (byte) ((keyFrame ? 0x80 : 0) | (block & 0x7f));
        d[7] = (byte) k;
        d[8] = (byte) ((m << 4) | (index & 0x0f));
    }

    public static short frameNum(byte[] d) {
        return (short) (((d[1] & 0xff) << 8) | (d[2] & 0xff));
    }

    public static int frameSize(byte[] d) {
        return ((d[3] & 0xff) << 16) | ((d[4] & 0xff) << 8) | (d[5] & 0xff);
    }

    public static boolean keyFrame(byte[] d) {
        return (d[6] & 0x80) != 0;
    }

    public static int block(byte[] d) {
        return d[6] & 0x7f;
    }

    public static int k(byte[] d) {
        return d[7] & 0xff;
    }

    public static int m(byte[] d) {
        return (d[8] & 0xff) >> 4;
    }

    /** Which of the block's parity packets this is: 0 .. m - 1. */
    public static int index(byte[] d) {
        return d[8] & 0x0f;
    }

    /**
     * Whether a VIDEO_PARITY_RS packet of len bytes agrees with itself: a frame size with that block in it, the k
     * this side counts for the block, an index under an m that can be, and exactly the block's parity length behind
     * the header. One that doesn't is dropped - a rebuilt packet has no checksum to catch it later.
     */
    public static boolean valid(byte[] d, int len) {
        if (len <= HEADER) return false;
        int frameSize = frameSize(d), n = fragments(frameSize), block = block(d), k = k(d), m = m(d);
        if (k == 0 || k != blockK(n, block) || m < 1 || m > MAX_PARITY || index(d) >= m) return false;
        return len - HEADER == shardLength(frameSize, blockFirst(n, block), k);
    }
}
