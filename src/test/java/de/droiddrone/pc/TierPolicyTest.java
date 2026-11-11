package de.droiddrone.pc;

import de.droiddrone.pc.ControlLink.Tier;
import de.droiddrone.pc.ControlLink.VideoMode;
import org.junit.jupiter.api.Test;

import static de.droiddrone.pc.TierPolicy.LOW_RES_AFTER_MS;
import static de.droiddrone.pc.TierPolicy.ROBUST_HOLD_MS;
import static de.droiddrone.pc.TierPolicy.SHARP_AFTER_MS;
import static de.droiddrone.pc.TierPolicy.SHARP_DROP_MS;
import static de.droiddrone.pc.TierPolicy.SURVIVAL_AFTER_MS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AUTO's tier choice on its own, tick by tick (ControlLink's session thread ticks every 50 ms). The link in each test
 * is a few fields changed from a calm one at the normal top of 4 Mbit/s with a sharp tier to go to.
 */
class TierPolicyTest {
    static final int TOP = 4_000_000;
    static final long TICK = 50;

    final TierPolicy p = new TierPolicy();
    long now = 100_000;
    TierPolicy.Change last;

    /** A calm link at the normal top, nobody watching, a sharp tier set. */
    static TierPolicy.Link calm() {
        return new TierPolicy.Link(20, TOP, TOP, false, false, 0.3, true);
    }

    static TierPolicy.Link bps(int bps) {
        return new TierPolicy.Link(20, bps, TOP, false, false, 0.3, true);
    }

    static TierPolicy.Link stalled() {
        return new TierPolicy.Link(TierPolicy.STALL_EVENT_MS + 1, TOP, TOP, false, false, 0.3, true);
    }

    static TierPolicy.Link relayed() {
        return new TierPolicy.Link(20, TOP, TOP, true, false, 0.3, true);
    }

    static TierPolicy.Link watched() {
        return new TierPolicy.Link(20, TOP, TOP, false, true, 0.3, true);
    }

    static TierPolicy.Link decodeLoad(double load) {
        return new TierPolicy.Link(20, TOP, TOP, false, false, load, true);
    }

    /** Ticks for ms with this link; the first change (or null) is kept in last. Returns the tier at the end. */
    Tier run(long ms, TierPolicy.Link l) {
        return run(ms, VideoMode.AUTO, l);
    }

    Tier run(long ms, VideoMode mode, TierPolicy.Link l) {
        last = null;
        for (long end = now + ms; now < end; now += TICK) {
            TierPolicy.Change c = p.tick(now, mode, l);
            if (c != null && last == null) last = c;
        }
        return p.tier();
    }

    /** n separate stalls, each one tick long with a calm tick between. */
    void stalls(int n) {
        for (int i = 0; i < n; i++) {
            p.tick(now, VideoMode.AUTO, stalled());
            now += TICK;
            p.tick(now, VideoMode.AUTO, calm());
            now += TICK;
        }
    }

    /** From a fresh session to the sharp tier: calm at the top for the wait. */
    void toSharp() {
        p.sessionStart(now, VideoMode.AUTO);
        assertEquals(Tier.SHARP, run(SHARP_AFTER_MS + 2 * TICK, calm()));
    }

    void toRobust() {
        p.sessionStart(now, VideoMode.AUTO);
        assertEquals(Tier.ROBUST, run(LOW_RES_AFTER_MS + 2 * TICK, bps(500_000)));
    }

    @Test
    void startsOnTheNormalPicture() {
        assertEquals(Tier.NORMAL, p.tier());
        assertNull(p.sessionStart(now, VideoMode.AUTO), "AUTO on the normal picture already: no change");
    }

    @Test
    void notSharpOnANewLinksFirstTickEvenAfterALongNormalSpell() {
        // the policy has been NORMAL since 0 (tierSince 0): only the session start's calm count holds it back
        p.sessionStart(now, VideoMode.AUTO);
        assertNull(p.tick(now + TICK, VideoMode.AUTO, calm()), "first tick of a new link");
        assertEquals(Tier.NORMAL, run(SHARP_AFTER_MS - 2 * TICK, calm()), "not before the calm has lasted");
        assertEquals(Tier.SHARP, run(4 * TICK, calm()), "sharp once it has");
        assertEquals(Tier.NORMAL, last.from());
        assertEquals("calm at 4 Mbit/s for 20 s", last.why());
    }

    @Test
    void notSharpWhenItGivesNothingOrUnderTheTopOrWatched() {
        p.sessionStart(now, VideoMode.AUTO);
        assertEquals(Tier.NORMAL, run(SHARP_AFTER_MS * 2, new TierPolicy.Link(20, TOP, TOP, false, false, 0.3, false)), "no sharp tier set");
        assertEquals(Tier.NORMAL, run(SHARP_AFTER_MS * 2, bps(2_000_000)), "under the top");
        assertEquals(Tier.NORMAL, run(SHARP_AFTER_MS * 2, new TierPolicy.Link(20, 0, TOP, false, false, 0.3, true)), "no bitrate reported");
        assertEquals(Tier.NORMAL, run(SHARP_AFTER_MS * 2, watched()), "never sharp while watched");
        assertEquals(Tier.SHARP, run(2 * TICK, calm()), "sharp at once once nobody watches: the calm went on meanwhile");
    }

    @Test
    void aStallRestartsTheWaitForSharp() {
        p.sessionStart(now, VideoMode.AUTO);
        run(SHARP_AFTER_MS - 1000, calm());
        stalls(1);
        assertEquals(Tier.NORMAL, run(SHARP_AFTER_MS - 1000, calm()), "the stall counts for its window: no sharp tier yet");
        assertEquals(Tier.SHARP, run(TierPolicy.STALLS_WINDOW_MS, calm()), "once the stall has left the window");
    }

    @Test
    void sharpEndsAtAStallARelayOrASpectator() {
        toSharp();
        stalls(1);
        assertEquals(Tier.NORMAL, p.tier());
        toSharp2(relayed(), "relayed");
        toSharp2(watched(), "a spectator: upload carries the picture");
    }

    /** From the normal picture back to sharp (a long calm), then this link must end it at once. */
    void toSharp2(TierPolicy.Link l, String why) {
        assertEquals(Tier.SHARP, run(10 * SHARP_AFTER_MS, calm()));
        assertEquals(Tier.NORMAL, run(TICK, l));
        assertEquals(why, last.why());
        assertEquals(Tier.SHARP, last.from());
    }

    @Test
    void sharpEndsAfterFiveSecondsUnderTheTop() {
        toSharp();
        assertEquals(Tier.SHARP, run(SHARP_DROP_MS - TICK, bps(2_000_000)), "a dip under the top is allowed");
        assertEquals(Tier.SHARP, run(TICK, calm()), "back at the top: the count starts again");
        assertEquals(Tier.SHARP, run(SHARP_DROP_MS - TICK, bps(2_000_000)));
        assertEquals(Tier.NORMAL, run(2 * TICK, bps(2_000_000)));
        assertEquals("under 4 Mbit/s for 5 s", last.why());
    }

    @Test
    void sharpEndsWhenThisPcCantDecodeIt() {
        toSharp();
        assertEquals(Tier.SHARP, run(SHARP_DROP_MS - TICK, decodeLoad(1.2)));
        assertEquals(Tier.NORMAL, run(2 * TICK, decodeLoad(1.2)));
        assertEquals("decoder too slow", last.why());
    }

    @Test
    void sharpLeftWithinAMinuteWaitsTwiceAsLongNextTime() {
        toSharp();
        stalls(1); // left after a moment (going down forgets the stalls: the wait counts from the stall)
        assertEquals(Tier.NORMAL, run(2 * SHARP_AFTER_MS - 2 * TICK, calm()), "40 s wait now");
        assertEquals(Tier.SHARP, run(4 * TICK, calm()));
        // a spell that lasts: the usual wait again
        run(60_000, calm());
        stalls(1);
        assertEquals(Tier.NORMAL, p.tier());
        assertEquals(Tier.NORMAL, run(SHARP_AFTER_MS - 2 * TICK, calm()));
        assertEquals(Tier.SHARP, run(4 * TICK, calm()));
    }

    @Test
    void lowBitrateOrThreeStallsOrARelayGoToBadCoverage() {
        p.sessionStart(now, VideoMode.AUTO);
        assertEquals(Tier.NORMAL, run(LOW_RES_AFTER_MS - TICK, bps(500_000)), "not before 5 s");
        assertEquals(Tier.ROBUST, run(2 * TICK, bps(500_000)));
        assertEquals("bitrate at 500 kbit/s", last.why());

        TierPolicy q = new TierPolicy();
        q.sessionStart(now, VideoMode.AUTO);
        for (int i = 0; i < 2; i++) {
            q.tick(now += TICK, VideoMode.AUTO, stalled());
            q.tick(now += TICK, VideoMode.AUTO, calm());
        }
        assertEquals(Tier.NORMAL, q.tier(), "two stalls aren't enough");
        q.tick(now += TICK, VideoMode.AUTO, stalled());
        assertEquals(Tier.ROBUST, q.tier());

        TierPolicy r = new TierPolicy();
        TierPolicy.Change c = r.tick(now, VideoMode.AUTO, relayed());
        assertNotNull(c);
        assertEquals(new TierPolicy.Change(Tier.ROBUST, Tier.NORMAL, "relayed"), c);
    }

    @Test
    void aLongStallCountsOnce() {
        p.sessionStart(now, VideoMode.AUTO);
        assertEquals(Tier.NORMAL, run(10_000, stalled()), "one stall, however long");
    }

    @Test
    void badCoverageGoesToSurvivalWhenNotEnough() {
        toRobust();
        assertEquals(Tier.ROBUST, run(SURVIVAL_AFTER_MS - 2 * TICK, bps(250_000)), "bad coverage mode has its 5 s first");
        assertEquals(Tier.SURVIVAL, run(4 * TICK, bps(250_000)));
        assertEquals("still 250 kbit/s", last.why());
    }

    @Test
    void badCoverageStaysWhileRelayedAndLeavesAfterTheCalm() {
        toRobust();
        assertEquals(Tier.ROBUST, run(3 * ROBUST_HOLD_MS, relayed()), "relayed: held");
        assertEquals(Tier.ROBUST, run(ROBUST_HOLD_MS, bps(500_000)), "calm, but the bitrate struggles under 1 Mbit/s");
        assertEquals(Tier.NORMAL, run(TICK, bps(1_000_000)), "calm and the bitrate free");
        assertEquals("calm for 20 s", last.why());
    }

    /** 1.0.64: the doubled hold never came down again - after a few quick returns it was 5 min for the rest of the link. */
    @Test
    void aLongGoodSpellForgetsTheDoubledHold() {
        toRobust();
        assertEquals(Tier.NORMAL, run(ROBUST_HOLD_MS + TICK, bps(1_000_000)));
        assertEquals(Tier.ROBUST, run(LOW_RES_AFTER_MS + 2 * TICK, bps(500_000)), "back within a minute: the hold is 40 s now");
        assertEquals(Tier.NORMAL, run(2 * ROBUST_HOLD_MS + 2 * TICK, bps(1_000_000)));
        run(TierPolicy.HOLD_FORGET_MS + TICK, VideoMode.NORMAL, bps(1_000_000)); // ten good minutes on the normal picture
        assertEquals(Tier.ROBUST, run(LOW_RES_AFTER_MS + 2 * TICK, bps(500_000)));
        assertEquals(Tier.NORMAL, run(ROBUST_HOLD_MS + 2 * TICK, bps(1_000_000)), "20 s again, not 40");
    }

    @Test
    void backToBadCoverageSoonDoublesTheHold() {
        toRobust();
        assertEquals(Tier.NORMAL, run(ROBUST_HOLD_MS + TICK, bps(1_000_000)));
        assertEquals(Tier.ROBUST, run(LOW_RES_AFTER_MS + 2 * TICK, bps(500_000)), "back within a minute");
        assertEquals(Tier.ROBUST, run(2 * ROBUST_HOLD_MS - 2 * TICK, bps(1_000_000)), "now 40 s");
        assertEquals(Tier.NORMAL, run(4 * TICK, bps(1_000_000)));
    }

    @Test
    void survivalGoesBackToBadCoverageAfterTheCalm() {
        toRobust();
        assertEquals(Tier.SURVIVAL, run(SURVIVAL_AFTER_MS + TICK, bps(200_000)));
        assertEquals(Tier.SURVIVAL, run(ROBUST_HOLD_MS - 2 * TICK, bps(200_000)));
        assertEquals(Tier.ROBUST, run(4 * TICK, bps(200_000)), "calm (no stall): one tier up, whatever the bitrate");
    }

    @Test
    void aChosenTierHoldsWhateverTheLink() {
        TierPolicy.Change c = p.sessionStart(now, VideoMode.SURVIVAL);
        assertEquals(new TierPolicy.Change(Tier.SURVIVAL, Tier.NORMAL, "VIDEO is SURVIVAL"), c, "taken at the session's start");
        assertEquals(Tier.SURVIVAL, run(10 * ROBUST_HOLD_MS, VideoMode.SURVIVAL, calm()));
        assertEquals(Tier.SHARP, run(TICK, VideoMode.SHARP, stalled()), "chosen while connected: at once");
        assertEquals("VIDEO is SHARP", last.why());
        assertEquals(Tier.SHARP, run(10 * SHARP_DROP_MS, VideoMode.SHARP, relayed()));
        assertEquals(Tier.NORMAL, run(TICK, VideoMode.NORMAL, calm()));
    }

    @Test
    void aChosenSharpTierLeftDoesntDoubleAutosWait() {
        p.sessionStart(now, VideoMode.SHARP);
        run(TICK, VideoMode.SHARP, calm());
        run(TICK, VideoMode.NORMAL, calm()); // left after a moment, by choice
        assertEquals(Tier.SHARP, run(SHARP_AFTER_MS + 2 * TICK, calm()), "AUTO's wait is the usual one");
    }

    @Test
    void aNewSessionStartsAutoOnTheNormalPictureSilently() {
        toSharp();
        assertNull(p.sessionStart(now, VideoMode.AUTO), "no change reported");
        assertEquals(Tier.NORMAL, p.tier());
        assertEquals(Tier.NORMAL, run(SHARP_AFTER_MS - 2 * TICK, calm()), "earns the sharp tier again");
        assertEquals(Tier.SHARP, run(4 * TICK, calm()));
    }

    @Test
    void aNewSessionKeepsBadCoverage() {
        toRobust();
        assertNull(p.sessionStart(now, VideoMode.AUTO));
        assertEquals(Tier.ROBUST, p.tier(), "a weak link stays in bad coverage mode across a reconnect");
    }
}
