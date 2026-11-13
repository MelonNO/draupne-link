package de.droiddrone.pc.video;

import de.droiddrone.common.Fec;
import de.droiddrone.pc.ControlLink.Tier;
import org.junit.jupiter.api.Test;

import static de.droiddrone.pc.video.FecPolicy.CALM_MS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The parity the PC asks the phone for, tick by tick (ControlLink's session thread ticks every 50 ms): the frames in
 * each test are plain ones of 4 packets and key frames of 28, one key frame to 60 plain ones, unless it says so.
 */
class FecPolicyTest {
    static final long TICK = 50;
    static final int PLAIN = Fec.FIRST_SEGMENT + 2 * Fec.SEGMENT + 500, KEY = Fec.FIRST_SEGMENT + 26 * Fec.SEGMENT + 500;

    final FecPolicy p = new FecPolicy();
    long now = 100_000;

    /** A second of the usual frames. */
    void frames() {
        frames(PLAIN, KEY);
    }

    void frames(int plainBytes, int keyBytes) {
        for (int n = 0; n < 4; n++) {
            p.onFrame(true, keyBytes);
            for (int i = 0; i < 60; i++) p.onFrame(false, plainBytes);
        }
    }

    /** Ticks for ms at that loss; the choice at the end. */
    FecPolicy.Choice run(long ms, Tier tier, double loss) {
        FecPolicy.Choice c = p.choice();
        for (long end = now + ms; now < end; now += TICK) c = p.tick(now, tier, loss);
        return c;
    }

    @Test
    void noParityOutsideTheWeakTiers() {
        frames();
        for (Tier tier : new Tier[]{Tier.NORMAL, Tier.SHARP}) {
            FecPolicy.Choice c = run(5000, tier, 0.3);
            assertEquals(0, c.mPlain(), tier + ": whatever is lost");
            assertEquals(0, c.mKey());
            assertEquals(0, c.share());
        }
        assertEquals(0, new FecPolicy().choice().mPlain(), "and none before the first tick");
    }

    @Test
    void theWeakTiersStartWithWhatTheXorGave() {
        frames();
        for (Tier tier : new Tier[]{Tier.ROBUST, Tier.SURVIVAL}) {
            FecPolicy.Choice c = new FecPolicy().tick(now, tier, 0);
            assertEquals(FecPolicy.MIN_PLAIN, c.mPlain(), tier + ": one loss a frame mended, as the XOR group did");
            assertEquals(FecPolicy.MIN_KEY, c.mKey());
        }
    }

    @Test
    void itStepsUpUnderLoss() {
        frames();
        assertEquals(1, run(2000, Tier.ROBUST, 0.01).mPlain());
        FecPolicy.Choice c = p.tick(now, Tier.ROBUST, 0.03);
        assertEquals(2, c.mPlain(), "the tick the loss asks for it");
        assertTrue(c.mKey() > FecPolicy.MIN_KEY, "a key frame's 28 packets need more: " + c.mKey());
        int keyBefore = c.mKey();
        c = p.tick(now + TICK, Tier.ROBUST, 0.08);
        assertEquals(3, c.mPlain());
        assertTrue(c.mKey() >= keyBefore);
        c = p.tick(now + 2 * TICK, Tier.ROBUST, 0.15);
        assertEquals(4, c.mPlain());
    }

    @Test
    void itStepsDownOnlyAfterACalmPeriodAndOneAtATime() {
        frames();
        run(1000, Tier.ROBUST, 0.08);
        assertEquals(3, p.choice().mPlain());
        int key = p.choice().mKey();
        FecPolicy.Choice c = run(CALM_MS - TICK, Tier.ROBUST, 0);
        assertEquals(3, c.mPlain(), "the loss gone, but not for long enough");
        assertEquals(key, c.mKey());
        c = run(2 * TICK, Tier.ROBUST, 0);
        assertEquals(2, c.mPlain(), "calm for " + CALM_MS + " ms: one less");
        assertEquals(key - 1, c.mKey());
        c = run(CALM_MS - 3 * TICK, Tier.ROBUST, 0);
        assertEquals(2, c.mPlain(), "the next one waits as long again");
        c = run(3 * TICK, Tier.ROBUST, 0);
        assertEquals(1, c.mPlain());
        c = run(10 * CALM_MS, Tier.ROBUST, 0);
        assertEquals(FecPolicy.MIN_PLAIN, c.mPlain(), "never under the tier's least");
        assertEquals(FecPolicy.MIN_KEY, c.mKey());
    }

    @Test
    void aLossThatComesAndGoesHoldsTheParityUp() {
        frames();
        run(1000, Tier.ROBUST, 0.08);
        int changes = 0;
        FecPolicy.Choice last = p.choice();
        for (int i = 0; i < 20; i++) { // 3 s calm, 1 s of loss, for over a minute
            for (FecPolicy.Choice c : new FecPolicy.Choice[]{run(3000, Tier.ROBUST, 0), run(1000, Tier.ROBUST, 0.08)}) {
                if (!c.sameParity(last)) changes++;
                last = c;
            }
        }
        assertEquals(0, changes, "it doesn't flap");
        assertEquals(3, last.mPlain());
    }

    @Test
    void itNeverGoesAboveTheCap() {
        frames();
        for (double loss : new double[]{0.2, 0.5, 0.9, 1, 7}) {
            FecPolicy.Choice c = run(1000, Tier.SURVIVAL, loss);
            assertEquals(FecPolicy.MAX_PLAIN, c.mPlain(), "loss " + loss);
            assertEquals(FecPolicy.MAX_KEY, c.mKey());
            // as many parity packets as packets at the very most - in bytes a little over half, the parity
            // being as long as a block's longest packet
            assertTrue(c.share() < FecPolicy.MAX_SHARE + 0.05, "share " + c.share());
        }
        assertTrue(FecPolicy.MAX_KEY <= Fec.MAX_PARITY, "no more than the code makes");
    }

    @Test
    void aSmallFrameNeverGetsMoreParityThanItHasPackets() {
        frames(300, 2000); // one packet a plain frame, two a key frame (the survival tier on a still scene)
        FecPolicy.Choice c = run(1000, Tier.SURVIVAL, 0.3);
        assertEquals(1, c.mPlain(), "a copy of the one packet, no more");
        assertEquals(2, c.mKey());
        assertEquals(0.5, c.share(), 0.02, "half the upload at the very most");
    }

    @Test
    void leavingTheWeakTiersEndsItAtOnceAndComingBackStartsAfresh() {
        frames();
        run(1000, Tier.ROBUST, 0.08);
        FecPolicy.Choice c = p.tick(now, Tier.NORMAL, 0.08);
        assertEquals(0, c.mPlain());
        assertEquals(0, c.mKey());
        c = p.tick(now + TICK, Tier.ROBUST, 0);
        assertEquals(FecPolicy.MIN_PLAIN, c.mPlain(), "not what the last weak spell ended on");
        assertEquals(FecPolicy.MIN_KEY, c.mKey());
    }

    @Test
    void aLossNotKnownChangesNothing() {
        frames();
        run(1000, Tier.ROBUST, 0.08);
        FecPolicy.Choice before = p.choice();
        assertTrue(run(3 * CALM_MS, Tier.ROBUST, -1).sameParity(before), "no picture to count by: held");
        assertTrue(run(3 * CALM_MS, Tier.ROBUST, Double.NaN).sameParity(before));
        assertTrue(run(CALM_MS - TICK, Tier.ROBUST, 0).sameParity(before), "the calm counts from when the loss is known again");
        assertEquals(before.mPlain() - 1, run(2 * TICK, Tier.ROBUST, 0).mPlain());
        // and a weak tier entered with nothing known starts with its least
        FecPolicy.Choice fresh = new FecPolicy().tick(now, Tier.ROBUST, -1);
        assertEquals(FecPolicy.MIN_PLAIN, fresh.mPlain());
        assertEquals(FecPolicy.MIN_KEY, fresh.mKey());
    }

    @Test
    void theShareIsTheParitysPartOfTheUpload() {
        frames();
        FecPolicy.Choice c = run(1000, Tier.ROBUST, 0.03);
        assertEquals(2, c.mPlain());
        double parity = Fec.parityBytes(KEY, c.mKey()) + 60.0 * Fec.parityBytes(PLAIN, 2);
        double data = KEY + 60.0 * PLAIN;
        assertEquals(parity / (data + parity), c.share(), 0.005);
        // 4 packets and 2 parity a plain frame: about a third - the encoder at two thirds of the step keeps the upload
        assertEquals(0.33, c.share(), 0.03);
        double step = 1_000_000, encoder = step * (1 - c.share());
        assertEquals(step, encoder + encoder * c.share() / (1 - c.share()), 1, "picture and parity together are the step's bitrate");
        // more parity, a bigger share
        assertTrue(run(1000, Tier.ROBUST, 0.15).share() > c.share());
    }

    @Test
    void theShareBeforeAnyFrameIsForTheUsualFrame() {
        FecPolicy.Choice c = new FecPolicy().tick(now, Tier.ROBUST, 0);
        assertEquals(FecPolicy.share(FecPolicy.FIRST_PLAIN_BYTES, c.mPlain(), FecPolicy.FIRST_KEY_BYTES, c.mKey(),
                FecPolicy.FIRST_PLAIN_PER_KEY), c.share(), 1e-9);
        assertTrue(c.share() > 0.1 && c.share() < 0.3, "one parity to 4 packets: " + c.share());
    }

    @Test
    void theSizesFollowTheFramesAndAResetForgetsThem() {
        frames(300, 2000);
        assertEquals(1, run(1000, Tier.ROBUST, 0.3).mPlain());
        frames(); // the picture got bigger: blocks of 4 packets take more
        assertEquals(4, run(1000, Tier.ROBUST, 0.3).mPlain());
        p.reset();
        assertEquals(0, p.choice().mPlain());
        assertEquals(FecPolicy.MIN_PLAIN, p.tick(now, Tier.ROBUST, 0).mPlain());
    }

    /** The chance of more than m of n lost, counted packet by packet: nothing of the class's own sum in it. */
    private static double moreLostByCounting(int n, int m, double p) {
        double[] lost = new double[n + 1];
        lost[0] = 1;
        for (int packet = 0; packet < n; packet++) {
            for (int j = packet + 1; j >= 1; j--) lost[j] = lost[j] * (1 - p) + lost[j - 1] * p;
            lost[0] *= 1 - p;
        }
        double more = 0;
        for (int j = m + 1; j <= n; j++) more += lost[j];
        return more;
    }

    @Test
    void theParityIsTheLeastThatKeepsABlockWholeOftenEnough() {
        assertEquals(1, FecPolicy.parityFor(4, 0.01, 1, 4));
        assertEquals(2, FecPolicy.parityFor(4, 0.03, 1, 4));
        assertEquals(3, FecPolicy.parityFor(4, 0.08, 1, 4));
        assertEquals(4, FecPolicy.parityFor(4, 0.15, 1, 4));
        assertEquals(1, FecPolicy.parityFor(4, 0, 1, 4));
        assertEquals(2, FecPolicy.parityFor(28, 0, 2, 8), "the least asked for");
        for (int k : new int[]{1, 2, 3, 4, 8, 20, 28, 32}) {
            int before = 0;
            for (double loss = 0; loss <= 0.4; loss += 0.0037) {
                int m = FecPolicy.parityFor(k, loss, 1, 8);
                assertTrue(m >= before, "more loss never asks for less: k " + k + ", loss " + loss);
                before = m;
                int cap = Math.min(8, k);
                assertTrue(m >= 1 && m <= cap);
                if (m < cap) assertTrue(moreLostByCounting(k + m, m, loss) <= FecPolicy.BLOCK_FAILS + 1e-12, "enough: k " + k + ", loss " + loss);
                if (m > 1) assertTrue(moreLostByCounting(k + m - 1, m - 1, loss) > FecPolicy.BLOCK_FAILS - 1e-12, "and one less isn't: k " + k + ", loss " + loss);
                assertEquals(moreLostByCounting(k + m, m, loss), FecPolicy.moreLost(k + m, m, loss), 1e-9);
            }
        }
    }

    @Test
    void thePacketLossBehindAFrameLoss() {
        assertEquals(0, FecPolicy.fragmentLoss(0, 4));
        assertEquals(0, FecPolicy.fragmentLoss(-1, 4));
        assertEquals(0, FecPolicy.fragmentLoss(Double.NaN, 4));
        assertEquals(1, FecPolicy.fragmentLoss(1, 4));
        assertEquals(0.1, FecPolicy.fragmentLoss(0.1, 1), 1e-12, "one packet a frame: the same");
        assertEquals(0.1, FecPolicy.fragmentLoss(0.19, 2), 1e-12, "two packets, each lost one time in ten: 19 % of the frames hit");
        assertEquals(0.05, FecPolicy.fragmentLoss(1 - Math.pow(0.95, 4), 4), 1e-12);
        assertEquals(0.1, FecPolicy.fragmentLoss(0.1, 0), 1e-12, "no fewer than one packet a frame");
    }
}
