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

/**
 * DRAUPNE Flight pc.53 / DRAUPNE Link 1.0.71: the video sender's side of the Reed-Solomon parity ({@link Fec}), for
 * the phone's Udp and the virtual fleet's drone alike. One frame at a time, on the video sender's thread:
 * <ul>
 * <li>{@link #begin} with the frame and the parity packets a block gets ({@link #m}; 0 = none);</li>
 * <li>after each of the frame's packets sent, {@link #blockEndingAt}: where a block ends, {@link #encode} builds its
 * parity packets - VIDEO_PARITY_RS, the header as {@link Fec#putHeader} writes it - and {@link #packet} hands them
 * out, to go right behind that block, paced as the frame's own packets. The parity of block b is out before block b
 * + 1 begins, so the PC can mend a block while the rest of the frame is still on its way.</li>
 * </ul>
 * Nothing is allocated per frame or packet: the parity packets are built in arrays held for good, the longest a
 * block's parity can be. The caller sends {@code packet(i)[0 .. length)} and none of them twice at once.
 * <p>
 * The share: what the parity adds to the video's upload, counted over a second of frames sent ({@link #sent}) - the
 * parity's bytes over frame and parity bytes, as {@link Fec#parityBytes}. The encoder is set that much lower
 * ({@link #encoderBitRate}), so frame and parity together leave at the bitrate step's figure; the step's own figure
 * is what the PC is told (record 0x40F2 - its count of the steps and its tier limits read that). The figure moves only
 * by {@link #SHARE_STEP_PERMILLE} or more, or to 0, so the encoder is not set anew for every wobble of the frames'
 * sizes; never over {@link #MAX_SHARE_PERMILLE} (FecPolicy gives no block more parity than data).
 */
public final class FecSender {
    /** VIDEO_FEC from the PC: [0x54][mPlain u8][mKey u8]. */
    public static final int VIDEO_FEC_BYTES = 3;
    /** The share is counted over this long of frames. */
    public static final long SHARE_WINDOW_MS = 1000;
    /** It moves by at least this much (or to 0), per mille. */
    public static final int SHARE_STEP_PERMILLE = 20;
    /** And never past this. */
    public static final int MAX_SHARE_PERMILLE = 500;

    private final byte[][] parity = new byte[Fec.MAX_PARITY][Fec.HEADER + Fec.SEGMENT];
    private byte[] frame;
    private int size, fragments, blocks, m, frameNum, nextBlock, nextBlockEnd;
    private boolean keyFrame;
    private long windowStartMs = -1, windowFrameBytes, windowParityBytes;
    private volatile int sharePermille;

    /** The parity packets a block of a frame of that kind gets: VIDEO_FEC's figure, taken into 0..{@link Fec#MAX_PARITY}. */
    public static int parityOf(byte figure) {
        return Math.min(Fec.MAX_PARITY, figure & 0xff);
    }

    /**
     * A frame begins.
     * @param frame its bytes from index 0, at least size of them (the encoder's output as it is sent)
     * @param m     parity packets a block; 0 - or a frame too big for the scheme ({@link Fec#blocks} 0) - goes bare
     * @return the parity packets the frame gets in all (the pacing spreads the frame and them together)
     */
    public int begin(byte[] frame, int size, int frameNum, boolean keyFrame, int m) {
        this.frame = frame;
        this.size = size;
        this.frameNum = frameNum;
        this.keyFrame = keyFrame;
        fragments = Fec.fragments(size);
        blocks = Fec.blocks(fragments);
        this.m = blocks == 0 ? 0 : Math.max(0, Math.min(Fec.MAX_PARITY, m));
        nextBlock = 0;
        nextBlockEnd = this.m == 0 ? -1 : Fec.blockK(fragments, 0) - 1;
        return blocks * this.m;
    }

    /** The parity packets a block of the frame in hand gets (0: none). */
    public int m() {
        return m;
    }

    /** After the frame's packet number fragment (0, 1, ...) went out: the block it ends, or -1. In order, once each. */
    public int blockEndingAt(int fragment) {
        if (m == 0 || nextBlock >= blocks || fragment != nextBlockEnd) return -1;
        int block = nextBlock++;
        nextBlockEnd = nextBlock < blocks ? Fec.blockFirst(fragments, nextBlock) + Fec.blockK(fragments, nextBlock) - 1 : -1;
        return block;
    }

    /**
     * The block's m parity packets, built ({@link #packet} 0 .. m - 1): the header and the parity, straight from the
     * frame's array.
     * @return each packet's length
     */
    public int encode(int block) {
        int first = Fec.blockFirst(fragments, block), k = Fec.blockK(fragments, block);
        int length = Fec.encode(frame, size, first, k, m, parity, Fec.HEADER);
        for (int i = 0; i < m; i++) {
            byte[] p = parity[i];
            p[0] = PacketIds.VIDEO_PARITY_RS;
            Fec.putHeader(p, frameNum, size, keyFrame, block, k, m, i);
        }
        return Fec.HEADER + length;
    }

    /** Parity packet index of the block last encoded (the array is reused for the next block). */
    public byte[] packet(int index) {
        return parity[index];
    }

    /**
     * A frame went out with this much parity (0 without): counted for the share. On the video sender's thread.
     * @param parityBytes its parity packets' payloads ({@link Fec#parityBytes} for a frame sent whole)
     */
    public void sent(int frameBytes, int parityBytes, long nowMs) {
        if (windowStartMs < 0) windowStartMs = nowMs;
        windowFrameBytes += Math.max(0, frameBytes);
        windowParityBytes += Math.max(0, parityBytes);
        if (nowMs - windowStartMs < SHARE_WINDOW_MS) return;
        long all = windowFrameBytes + windowParityBytes;
        int share = all <= 0 ? 0 : (int) Math.min(MAX_SHARE_PERMILLE, windowParityBytes * 1000 / all);
        if (share == 0 || Math.abs(share - sharePermille) >= SHARE_STEP_PERMILLE) sharePermille = share;
        windowStartMs = nowMs;
        windowFrameBytes = windowParityBytes = 0;
    }

    /** The parity's part of the upload now, per mille (read from any thread). */
    public int sharePermille() {
        return sharePermille;
    }

    /** No parity from now on (a new session, observers): the share is 0 at once, not after a second. */
    public void stop() {
        sharePermille = 0;
        windowStartMs = -1;
        windowFrameBytes = windowParityBytes = 0;
    }

    /**
     * What the encoder is set to: the bitrate step's figure less the parity's share. The step's own figure is still
     * the one reported and paced by.
     */
    public static int encoderBitRate(int stepBps, int sharePermille) {
        int share = Math.max(0, Math.min(MAX_SHARE_PERMILLE, sharePermille));
        return (int) ((long) stepBps * (1000 - share) / 1000);
    }
}
