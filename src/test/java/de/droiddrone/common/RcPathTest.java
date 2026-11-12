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

    // ---- pc.50: the best transit across a new session from the same PC

    /** A session whose best transit is 100 ms, its last frame at 1100 on the phone's clock. */
    private static RcPath flownWithBest100() {
        RcPath p = new RcPath();
        p.restart(0);
        assertTrue(take(p, lagged(1, 0, 10), 100) > 0);
        assertTrue(take(p, lagged(2, 1000, 10), 1100) > 0);
        return p;
    }

    @Test
    void aNewSessionFromTheSamePcStillDropsTheFramesAnOutageHeldBack() {
        RcPath p = flownWithBest100();
        // 6 s without the link; the PC connects again and the queue's frames come first, 2 s and 1 s old
        p.restart(7000, true);
        assertEquals(-1, take(p, lagged(1, 5000, 10), 7100), "sent 2.1 s ago, 2 s over the best: not the new baseline");
        assertEquals(-1, take(p, lagged(2, 6000, 10), 7110), "1.01 s late");
        assertEquals(2, p.lagDropped(), "counted in the new session");
        assertTrue(take(p, lagged(3, 7020, 10), 7130) > 0, "a fresh frame, 10 ms over the best");
        assertEquals(10, p.lagMs());
        assertEquals(-1, take(p, lagged(4, 7030, 10), 7400), "and the limit holds on against the kept best");
    }

    @Test
    void aNewSessionFromAnotherPcStartsFromItsFirstFrame() {
        RcPath p = flownWithBest100();
        p.restart(7000, false);
        assertTrue(take(p, lagged(1, 5000, 10), 7100) > 0, "another PC's clock: nothing to compare with");
        assertEquals(0, p.lagMs());
        RcPath q = flownWithBest100();
        q.restart(7000);
        assertTrue(take(q, lagged(1, 5000, 10), 7100) > 0, "restart(now) forgets, as before");
    }

    @Test
    void aKeptBestNoFrameComesNearIsForgottenAfterTheTrial() {
        RcPath p = flownWithBest100();
        p.restart(7000, true);
        // the path is 400 ms slower for good (or the PC's clock was set): every frame 400 ms over the kept best
        long t = 7100;
        assertEquals(-1, take(p, lagged(1, (int) (t - 500), 10), t));
        assertEquals(-1, take(p, lagged(2, (int) (t + 500), 10), t + 1000), "still on trial");
        long after = t + RcPath.LAG_TRIAL_MS;
        assertTrue(take(p, lagged(3, (int) (after - 500), 10), after) > 0, "learned: the sticks are not held back for longer");
        assertEquals(0, p.lagMs());
        assertTrue(take(p, lagged(4, (int) (after - 480), 10), after + 20) > 0);
    }

    @Test
    void aTransitFarFromTheKeptBestIsAnotherClock() {
        RcPath p = flownWithBest100();
        p.restart(7000, true);
        // 20 s "late": no queue holds a frame that long - the PC counts from elsewhere
        assertTrue(take(p, lagged(1, 7100 - 20_100, 10), 7100) > 0);
        assertEquals(0, p.lagMs());
        assertTrue(take(p, lagged(2, 7120 - 20_100, 10), 7120) > 0);
    }

    @Test
    void aBestMeasuredLongAgoIsNotKept() {
        RcPath p = flownWithBest100();
        long later = 1100 + RcPath.LAG_KEEP_MS + 1;
        p.restart(later, true);
        int sent = (int) ((later - 2000) & 0xffff); // 2 s over the old best: the clocks may have drifted, nobody knows
        assertTrue(take(p, lagged(1, sent, 10), later + 100) > 0);
        assertEquals(0, p.lagMs());
    }

    @Test
    void aKeptBestAgesOutWithItsBucketWhenNoFrameComes() {
        RcPath p = flownWithBest100();
        p.restart(7000, true); // connected again, but no sticks (standby) for longer than a bucket
        long t = 7000 + RcPath.LAG_BUCKET_MS + 100;
        assertTrue(take(p, lagged(1, (int) (t - 2100), 10), t) > 0, "nothing of the last two buckets to compare with");
        assertEquals(0, p.lagMs());
    }

    @Test
    void aSecondConnectOfTheSameReconnectKeepsTheBestToo() {
        RcPath p = flownWithBest100();
        p.restart(7000, true);
        assertEquals(-1, take(p, lagged(1, 5000, 10), 7100));
        p.restart(7200, true); // the PC's next Connect, taken as a new session
        assertEquals(-1, take(p, lagged(1, 6000, 10), 7300), "1.2 s late against the best of two sessions ago");
        assertTrue(take(p, lagged(2, 7250, 10), 7360) > 0);
    }

    // ---- pc.50: the idle sticks' guard

    /** ARM on AUX1, high = armed (Betaflight's slot: mode 0, aux 0, 1700..2100). */
    private static final int[][] ARM_AUX1 = {{0, 1700, 2100}};
    private static final short[] ARM_OFF = {1500, 1500, 1500, 1000, 1000, 1500, 1500, 1000};
    private static final short[] ARM_ON = {1500, 1500, 1500, 1000, 2000, 1500, 1500, 1000};

    private static int check(RcPath.IdleGuard g, int[][] slots) {
        return g.check(f -> ArmRanges.mayArm(f, f.length, slots));
    }

    @Test
    void idleSticksAreFedBeforeAnyStickFrame() {
        assertEquals(RcPath.IdleGuard.FEED, check(new RcPath.IdleGuard(), ARM_AUX1), "a PC without a radio, as before");
    }

    @Test
    void noIdleSticksAfterTheSticksStoppedOnAnArmedDrone() {
        RcPath.IdleGuard g = new RcPath.IdleGuard();
        g.frame(ARM_ON, ARM_ON.length, false); // flying; the link is lost here
        assertEquals(RcPath.IdleGuard.HELD_FC_ARMED, check(g, ARM_AUX1),
                "whatever the FC reads later (its failsafe disarmed it): the lockout stays Betaflight's");
        g.frame(ARM_OFF, ARM_OFF.length, false);
        assertEquals(RcPath.IdleGuard.HELD_FC_ARMED, check(g, ARM_AUX1), "the switch went off, but the FC wasn't read disarmed yet (or unknown)");
    }

    @Test
    void theLinkBackWithArmStillOnDoesNotBringTheIdleSticksBack() {
        RcPath.IdleGuard g = new RcPath.IdleGuard();
        g.frame(ARM_ON, ARM_ON.length, false);
        // the failsafe disarmed the drone; the link is back, the pilot's switch still on, and is lost again
        g.frame(ARM_ON, ARM_ON.length, true);
        assertEquals(RcPath.IdleGuard.HELD_ARM_ON, check(g, ARM_AUX1), "idle sticks now would say ARM off for the pilot");
        g.frame(ARM_OFF, ARM_OFF.length, true); // the pilot's own switch off, at a disarmed FC
        assertEquals(RcPath.IdleGuard.FEED, check(g, ARM_AUX1));
    }

    @Test
    void disarmedByThePilotThenTheSticksStopIsFedAsBefore() {
        RcPath.IdleGuard g = new RcPath.IdleGuard();
        g.frame(ARM_ON, ARM_ON.length, false);
        g.frame(ARM_OFF, ARM_OFF.length, false); // the switch off; the next status says DISARMED
        g.frame(ARM_OFF, ARM_OFF.length, true);
        assertEquals(RcPath.IdleGuard.FEED, check(g, ARM_AUX1), "standby after a flight");
        assertEquals(RcPath.IdleGuard.HELD_ARM_ON, check(g, null), "ARM ranges unread: the frame may arm for all the phone knows");
    }

    @Test
    void theGuardKeepsItsOwnCopyOfTheFramesChannels() {
        RcPath.IdleGuard g = new RcPath.IdleGuard();
        short[] buffer = new short[18]; // RcPath's frame: longer than the count, written again by the next frame
        System.arraycopy(ARM_OFF, 0, buffer, 0, ARM_OFF.length);
        g.frame(buffer, ARM_OFF.length, true);
        buffer[4] = 2000;
        assertEquals(RcPath.IdleGuard.FEED, check(g, ARM_AUX1));
        int[][] armOnAux9 = {{8, 1700, 2100}};
        assertEquals(RcPath.IdleGuard.HELD_ARM_ON, check(g, armOnAux9), "judged by the channels sent: AUX9 wasn't");
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
