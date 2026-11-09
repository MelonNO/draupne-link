package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The phone's stick-frame rules (moved out of its Udp): the sequence of each PC build, the lag limit, the channels. */
class RcPathTest {
    private static final short[] STICKS = {1500, 1500, 1000, 1500, 1000, 2000};

    /** [24][count][count x i16] and the tail the PC's build adds. */
    private static byte[] frame(short[] ch, int... tail) {
        byte[] d = new byte[2 + ch.length * 2 + tail.length];
        d[0] = UdpCommon.RcFrame;
        d[1] = (byte) ch.length;
        for (int i = 0; i < ch.length; i++) {
            d[2 + i * 2] = (byte) (ch[i] >> 8);
            d[3 + i * 2] = (byte) ch[i];
        }
        for (int i = 0; i < tail.length; i++) d[2 + ch.length * 2 + i] = (byte) tail[i];
        return d;
    }

    private static byte[] seq16(int seq) {
        return frame(STICKS, seq >> 8, seq);
    }

    /** pc.31: [seq u16][sentMs u16][maxLagCs u8]. */
    private static byte[] lagged(int seq, int sentMs, int maxLagCs) {
        return frame(STICKS, seq >> 8, seq, sentMs >> 8, sentMs, maxLagCs);
    }

    private static int take(RcPath p, byte[] d, long now) {
        int n = p.take(d, d.length, now);
        if (n > 0) p.taken(now);
        return n;
    }

    @Test
    void theChannelsAreReadInPlace() {
        RcPath p = new RcPath();
        assertEquals(STICKS.length, take(p, frame(STICKS), 1000));
        for (int i = 0; i < STICKS.length; i++) assertEquals(STICKS[i], p.frame()[i]);
        assertEquals(-1, p.lastSeq(), "no sequence from the oldest PCs");
        assertEquals(1000, p.lastTakenMs());
    }

    @Test
    void aCutOrOddFrameIsDropped() {
        RcPath p = new RcPath();
        assertEquals(-1, p.take(new byte[]{UdpCommon.RcFrame}, 1, 0));
        byte[] three = frame(new short[]{1, 2, 3});
        assertEquals(-1, p.take(three, three.length, 0), "fewer than 4 channels");
        byte[] many = frame(new short[19]);
        assertEquals(-1, p.take(many, many.length, 0), "more than the FC takes");
        byte[] odd = frame(STICKS, 1, 2, 3);
        assertEquals(-1, p.take(odd, odd.length, 0), "a tail no build sends");
    }

    @Test
    void aSixteenBitSequenceTakesOnlyNewerFramesUntilASecondWithout() {
        RcPath p = new RcPath();
        assertTrue(take(p, seq16(100), 0) > 0);
        assertEquals(-1, take(p, seq16(100), 10), "the same");
        assertEquals(-1, take(p, seq16(99), 20), "older");
        assertTrue(take(p, seq16(101), 30) > 0);
        assertEquals(101, p.lastSeq());
        assertEquals(-1, take(p, seq16(50), 30 + RcPath.SEQ_RESET_MS + 1), "300 ms is no reason for 16 bits");
        assertTrue(take(p, seq16(50), 30 + RcPath.SEQ16_FORGET_MS + 1) > 0, "a second without one: anything");
    }

    @Test
    void aSixteenBitSequenceComparesAcrossTheWrap() {
        RcPath p = new RcPath();
        assertTrue(take(p, seq16(65535), 0) > 0);
        assertTrue(take(p, seq16(0), 10) > 0, "0 follows 65535");
        assertEquals(-1, take(p, seq16(65535), 20));
    }

    @Test
    void anEightBitSequenceStartsOverAfterAGap() {
        RcPath p = new RcPath();
        assertTrue(take(p, frame(STICKS, 10), 0) > 0);
        assertEquals(-1, take(p, frame(STICKS, 9), 10));
        assertTrue(take(p, frame(STICKS, 11), 20) > 0);
        assertEquals(-1, take(p, frame(STICKS, 200), 20 + RcPath.SEQ_RESET_MS), "200 is 245 behind 11+256: older");
        assertTrue(take(p, frame(STICKS, 5), 20 + RcPath.SEQ_RESET_MS + 1) > 0, "a gap: whatever comes next is newest");
    }

    @Test
    void aRestartForgetsTheSequence() {
        RcPath p = new RcPath();
        assertTrue(take(p, seq16(1000), 0) > 0);
        p.restart(10);
        assertTrue(take(p, seq16(1), 20) > 0, "a new session's PC counts from the start");
    }

    @Test
    void aFrameLaterThanTheBestTransitByMoreThanTheLimitIsDropped() {
        RcPath p = new RcPath();
        p.restart(0);
        // sent at 0 on the PC's clock, here at 1000: the offset (one-way time + clocks) is 1000
        assertTrue(take(p, lagged(1, 0, 10), 1000) > 0);
        assertEquals(0, p.lagMs());
        assertTrue(p.lagFromPc());
        assertTrue(take(p, lagged(2, 100, 10), 1150) > 0, "50 ms later than the best: within 100");
        assertEquals(50, p.lagMs());
        assertEquals(-1, take(p, lagged(3, 200, 10), 1350), "150 ms late: over the limit");
        assertEquals(1, p.lagDropped());
        assertEquals(150, p.takeLagMaxMs());
        assertEquals(-1, p.takeLagMaxMs(), "the most since the last call");
        assertTrue(take(p, lagged(4, 300, 0), 1800) > 0, "0: never dropped");
        assertEquals(4, p.lastSeq());
    }

    @Test
    void theBestTransitIsForgottenAfterTwoBuckets() {
        RcPath p = new RcPath();
        p.restart(0);
        assertTrue(take(p, lagged(1, 0, 10), 100) > 0); // best: 100
        long t = RcPath.LAG_BUCKET_MS;
        // the path got 300 ms slower for good (a relay): dropped while the old best is in the last two buckets
        assertEquals(-1, take(p, lagged(2, (int) t, 10), t + 400));
        assertEquals(-1, take(p, lagged(3, (int) (2 * t - 10), 10), 2 * t + 390));
        assertTrue(take(p, lagged(4, (int) (3 * t), 10), 3 * t + 400) > 0, "learned");
    }

    @Test
    void aRestartClearsTheLagFigures() {
        RcPath p = new RcPath();
        p.restart(0);
        take(p, lagged(1, 0, 1), 100);
        take(p, lagged(2, 0, 1), 500);
        assertEquals(1, p.lagDropped());
        p.restart(600);
        assertEquals(0, p.lagDropped());
        assertEquals(-1, p.lagMs());
        assertEquals(-1, p.takeLagMaxMs());
    }

    @Test
    void anOlderFrameIsNoLongerFlaggedAsFromALagPc() {
        RcPath p = new RcPath();
        take(p, lagged(1, 0, 10), 100);
        assertTrue(p.lagFromPc());
        take(p, seq16(2), 110);
        assertFalse(p.lagFromPc(), "a pc.26 frame: the PC doesn't know the lag record");
    }

    @Test
    void idleSticksAreReadIntoANewArrayOrRefused() {
        byte[] d = frame(STICKS);
        d[0] = 0x78;
        short[] idle = RcPath.idleFrame(d, d.length);
        assertArrayEquals(STICKS, idle);
        assertNotSame(idle, RcPath.idleFrame(d, d.length));
        assertNull(RcPath.idleFrame(d, d.length - 1), "cut");
        assertNull(RcPath.idleFrame(new byte[]{0x78}, 1));
    }
}
