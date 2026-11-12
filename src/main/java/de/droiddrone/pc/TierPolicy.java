package de.droiddrone.pc;

import de.droiddrone.pc.ControlLink.Tier;
import de.droiddrone.pc.ControlLink.VideoMode;

import java.util.ArrayDeque;

/**
 * Which video tier the link gets (see {@link VideoMode}, {@link Tier}): what the link showed at one tick in - its
 * stalls, the phone's bitrate, a relay, spectators, this PC's decoder - the tier and why out. Pure: no sockets, no clock
 * of its own; ControlLink's session thread calls {@link #tick} once a tick, the tests with any time. Out of
 * ControlLink.adjustResolution, where the sharp tier's first-tick bug of 1.0.52 hid. {@link #tier} may be read from
 * any thread.
 *
 * AUTO, down: the normal picture goes to bad coverage mode at a low bitrate ({@link #LOW_RES_BPS} for
 * {@link #LOW_RES_AFTER_MS}), {@link #STALLS_FOR_ROBUST} stalls within {@link #STALLS_WINDOW_MS}, a relay, or (1.0.64)
 * frames lost or damaged ({@link #LOSSY_FRAMES} for {@link #LOSSY_AFTER_MS}); bad
 * coverage mode to the survival tier the same way, at {@link #SURVIVAL_BPS}, once it has had
 * {@link #SURVIVAL_AFTER_MS}. Up: a tier back after {@link #ROBUST_HOLD_MS} calm (doubled, up to
 * {@link #MAX_ROBUST_HOLD_MS}, after a quick return). The sharp tier after {@link #SHARP_AFTER_MS} calm at the normal
 * top bitrate, never while watched, never on a new link's first tick (calm is counted from {@link #sessionStart});
 * it ends at a stall, a relay, a spectator, or {@link #SHARP_DROP_MS} under the top or with the decoder behind.
 */
final class TierPolicy {
    /** The bitrate at or under which bad coverage mode goes to the survival tier (after LOW_RES_AFTER_MS). */
    static final int SURVIVAL_BPS = 250_000;
    /** Bad coverage mode has to have had this long before the survival tier is judged. */
    static final long SURVIVAL_AFTER_MS = 5000;
    /** AUTO goes to bad coverage when the phone's bitrate stays at or under LOW_RES_BPS for LOW_RES_AFTER_MS... */
    static final int LOW_RES_BPS = 500_000;
    static final long LOW_RES_AFTER_MS = 5000;
    /** ...or the link stalls (no pong for STALL_EVENT_MS) STALLS_FOR_ROBUST times within STALLS_WINDOW_MS. */
    static final long STALL_EVENT_MS = 500;
    static final int STALLS_FOR_ROBUST = 3;
    static final long STALLS_WINDOW_MS = 30_000;
    /** Back to normal after this long calm in bad coverage mode; doubled (up to 5 min) when it had to go back soon. */
    static final long ROBUST_HOLD_MS = 20_000;
    static final long MAX_ROBUST_HOLD_MS = 300_000;
    /** The normal picture held this long since bad coverage: the next bad spell's hold starts short again. */
    static final long HOLD_FORGET_MS = 600_000;
    /**
     * AUTO goes to the sharp tier after this long calm (no stall) at the normal top bitrate; doubled (up to 5 min)
     * when it had to leave the sharp tier within a minute, back to this after a sharp spell that lasted.
     */
    static final long SHARP_AFTER_MS = 20_000;
    /** The sharp tier ends when the phone's bitrate sat under the normal top for this long: the link can't carry it. */
    static final long SHARP_DROP_MS = 5000;
    /**
     * 1.0.64: AUTO goes to bad coverage when more than this share of the frames came lost, damaged or rebuilt from
     * parity for LOSSY_AFTER_MS, and stays there while it lasts. Steady loss with a normal ping never got there: the
     * bitrate control stops at 1 Mbit/s, so the LOW_RES_BPS rule never fired, and the picture stayed on single key
     * frames without parity with most frames damaged.
     */
    static final double LOSSY_FRAMES = 0.10;
    static final long LOSSY_AFTER_MS = 5000;
    /**
     * 1.0.64: just up from the survival tier the bitrate still sits at that tier's top; the bitrate control gets this
     * long to raise it before a low rate counts against bad coverage mode (5 s after the way up it went straight down
     * again, and the hold doubled each time).
     */
    static final long UP_SETTLE_MS = 15_000;

    /**
     * The link at one tick: ms since the last pong, the phone's bitrate (0 = it reports none), the normal top bitrate
     * the settings allow, a relayed way to the phone, spectators watching, this PC's decode load (1 = all its time;
     * 0 without a picture), whether the settings give the sharp tier anything over the normal picture, and the share
     * of the last seconds' frames the link didn't deliver whole (0 = none, or no picture to judge by).
     */
    record Link(long sincePongMs, int bps, int normalTopBps, boolean relayed, boolean watched, double decodeLoad,
                boolean sharpAvailable, double frameLoss) {
        /** Without the frame loss: none. */
        Link(long sincePongMs, int bps, int normalTopBps, boolean relayed, boolean watched, double decodeLoad,
             boolean sharpAvailable) {
            this(sincePongMs, bps, normalTopBps, relayed, watched, decodeLoad, sharpAvailable, 0);
        }
    }

    /** A tier change: to, from, and why (for the log). */
    record Change(Tier to, Tier from, String why) {
    }

    private volatile Tier tier = Tier.NORMAL;
    private long lowRateSince = -1;
    private long survivalRateSince = -1;
    private long tierSince;
    private final ArrayDeque<Long> stalls = new ArrayDeque<>();
    private boolean inStall;
    private long lastCalmEnd;
    private long robustHoldMs = ROBUST_HOLD_MS;
    private long leftRobustAt = -1;
    private long sharpHoldMs = SHARP_AFTER_MS;
    /** Since when the phone's bitrate has been under the normal top (-1: it isn't), for the sharp tier's end. */
    private long belowTopSince = -1;
    /** Since when this PC's decoder hasn't kept up with the sharp picture (decodeLoad over 1); -1 = it keeps up. */
    private long decodeSlowSince = -1;
    /** Since when the frame loss has been over LOSSY_FRAMES (-1: it isn't), and when it last was. */
    private long lossySince = -1;
    private long lastLossyAt = Long.MIN_VALUE / 2;
    /** The tier was reached from a lower one (the way up): its bitrate starts at that one's top (see UP_SETTLE_MS). */
    private boolean cameUp;

    Tier tier() {
        return tier;
    }

    /** Tests: AUTO's wait before the sharp tier. */
    void setSharpAfterMs(long ms) {
        sharpHoldMs = ms;
    }

    /**
     * A session (re)starts: nothing calm about the link has been seen yet (1.0.53: the calm counted from 0, and a phone
     * already at the normal top went sharp on the first tick). A chosen tier is taken at once (the change, for the
     * first config to carry); AUTO starts on the normal picture and earns the sharp tier again (no change reported).
     */
    Change sessionStart(long now, VideoMode mode) {
        lastCalmEnd = now;
        Tier forced = mode.forced();
        if (forced != null && tier != forced) {
            Tier was = tier;
            tier = forced;
            entered(now, false);
            return new Change(forced, was, "VIDEO is " + mode.name());
        }
        if (forced == null && tier == Tier.SHARP) {
            tier = Tier.NORMAL;
            entered(now, false);
        }
        return null;
    }

    /**
     * A tier begins (1.0.64): it is judged on its own time. The low-rate and frame-loss counts ran on from the tier
     * before (only belowTopSince was reset), so a tier could be left the moment its own wait was over.
     * @param up reached from a lower tier: the bitrate starts at that one's top (see UP_SETTLE_MS)
     */
    private void entered(long now, boolean up) {
        tierSince = now;
        belowTopSince = -1;
        lowRateSince = -1;
        survivalRateSince = -1;
        lossySince = -1;
        cameUp = up;
    }

    /**
     * 1.0.64: the link was lost and is back - a stall, and more. Stalls were counted only as a late pong while the link
     * was up, so a link that dropped out for seconds again and again never had its three stalls and AUTO never went to
     * bad coverage mode for it.
     */
    void outage(long now) {
        stalls.addLast(now);
        lastCalmEnd = now;
    }

    /** One session tick: the tier the link should have now; null = it stays. */
    Change tick(long now, VideoMode mode, Link l) {
        // stalls: no pong for STALL_EVENT_MS, counted once each
        boolean stalled = l.sincePongMs() > STALL_EVENT_MS;
        if (stalled && !inStall) stalls.addLast(now);
        inStall = stalled;
        while (!stalls.isEmpty() && now - stalls.peekFirst() > STALLS_WINDOW_MS) stalls.pollFirst();
        if (stalled) lastCalmEnd = now;
        int bps = l.bps();
        lowRateSince = bps > 0 && bps <= LOW_RES_BPS ? (lowRateSince < 0 ? now : lowRateSince) : -1;
        // 1.0.64: not while the bitrate control hasn't had its time to raise the rate after the way up (UP_SETTLE_MS)
        boolean settling = cameUp && now - tierSince < UP_SETTLE_MS;
        survivalRateSince = bps > 0 && bps <= SURVIVAL_BPS && !settling ? (survivalRateSince < 0 ? now : survivalRateSince) : -1;
        int normalTopBps = l.normalTopBps();
        belowTopSince = bps > 0 && bps < normalTopBps ? (belowTopSince < 0 ? now : belowTopSince) : -1;
        boolean lossyNow = l.frameLoss() > LOSSY_FRAMES;
        lossySince = lossyNow ? (lossySince < 0 ? now : lossySince) : -1;
        if (lossyNow) lastLossyAt = now;
        boolean relayed = l.relayed();
        Tier want;
        String why;
        Tier forced = mode.forced();
        if (forced != null) { // a tier chosen: held, whatever the link
            want = forced;
            why = "VIDEO is " + mode.name();
        } else if (tier == Tier.SHARP) {
            // the sharp tier holds while the link carries it: a stall, a relay, or the bitrate under the normal top for
            // SHARP_DROP_MS (the bitrate control had to step down) ends it - back to the normal picture first
            boolean cantCarry = belowTopSince >= 0 && now - belowTopSince >= SHARP_DROP_MS;
            // or this PC can't decode 1080p in real time (software decoding): the picture would drift late
            boolean slow = l.decodeLoad() > 1.0;
            if (!slow) decodeSlowSince = -1;
            else if (decodeSlowSince < 0) decodeSlowSince = now;
            boolean cantDecode = decodeSlowSince >= 0 && now - decodeSlowSince >= SHARP_DROP_MS;
            boolean watched = l.watched();
            want = !stalls.isEmpty() || relayed || cantCarry || cantDecode || watched ? Tier.NORMAL : Tier.SHARP;
            why = !stalls.isEmpty() ? "a stall" : relayed ? "relayed"
                    : watched ? "a spectator: upload carries the picture"
                    : cantDecode ? "decoder too slow"
                    : "under " + normalTopBps / 1_000_000 + " Mbit/s for " + SHARP_DROP_MS / 1000 + " s";
        } else if (tier == Tier.NORMAL) {
            boolean lowRate = lowRateSince >= 0 && now - lowRateSince >= LOW_RES_AFTER_MS;
            boolean stalls3 = stalls.size() >= STALLS_FOR_ROBUST;
            // 1.0.64: frames lost or damaged for 5 s, whatever the ping and the bitrate say
            boolean lossy = lossySince >= 0 && now - lossySince >= LOSSY_AFTER_MS;
            if (lowRate || stalls3 || relayed || lossy) {
                want = Tier.ROBUST;
                why = lowRate ? "bitrate at " + bps / 1000 + " kbit/s"
                        : stalls3 ? stalls.size() + " stalls in " + STALLS_WINDOW_MS / 1000 + " s"
                        : relayed ? "relayed" : Math.round(l.frameLoss() * 100) + " % of frames damaged";
                if (leftRobustAt >= 0 && now - leftRobustAt < 60_000) {
                    robustHoldMs = Math.min(MAX_ROBUST_HOLD_MS, robustHoldMs * 2); // back too soon: stay longer next time
                } else if (leftRobustAt >= 0 && now - leftRobustAt >= HOLD_FORGET_MS) {
                    robustHoldMs = ROBUST_HOLD_MS; // 1.0.64: a long good spell since - the hold only ever grew (5 min for the rest of the flight)
                }
            } else {
                // calm at the normal top bitrate for sharpHoldMs, no stall in that time: the sharp tier (a phone that
                // reports no bitrate setting never gets there)
                boolean atTop = bps >= normalTopBps;
                boolean calm = now - tierSince >= sharpHoldMs && now - lastCalmEnd >= sharpHoldMs && stalls.isEmpty()
                        && now - lastLossyAt >= sharpHoldMs;
                want = l.sharpAvailable() && atTop && calm && !l.watched() ? Tier.SHARP : Tier.NORMAL;
                why = "calm at " + normalTopBps / 1_000_000 + " Mbit/s for " + sharpHoldMs / 1000 + " s";
            }
        } else if (tier == Tier.ROBUST) {
            // bad coverage mode isn't enough: the bitrate sits at the bottom or the stalls go on - the survival tier
            boolean lowRate = survivalRateSince >= 0 && now - survivalRateSince >= LOW_RES_AFTER_MS;
            // 1.0.64: the stalls of this spell only - after the way up, three from before it (up to 30 s old) sent it
            // straight down again
            int stallsHere = 0;
            for (long at : stalls) if (at >= tierSince) stallsHere++;
            boolean stalls3 = stallsHere >= STALLS_FOR_ROBUST;
            boolean worse = now - tierSince >= SURVIVAL_AFTER_MS && (lowRate || stalls3);
            if (worse) {
                want = Tier.SURVIVAL;
                why = lowRate ? "still " + bps / 1000 + " kbit/s" : "still " + stallsHere + " stalls";
                if (leftRobustAt >= 0 && now - leftRobustAt < 60_000) robustHoldMs = Math.min(MAX_ROBUST_HOLD_MS, robustHoldMs * 2);
            } else if (relayed) {
                want = Tier.ROBUST;
                why = "relayed";
            } else {
                // calm long enough in bad coverage mode: no stall, and the bitrate not struggling under its 1 Mbit/s -
                // nor (1.0.64) frames still lost or rebuilt from parity: the normal picture has no parity to mend them
                boolean calm = now - tierSince >= robustHoldMs && now - lastCalmEnd >= robustHoldMs
                        && (bps <= 0 || bps > LOW_RES_BPS) && now - lastLossyAt >= robustHoldMs;
                want = calm ? Tier.NORMAL : Tier.ROBUST;
                why = "calm for " + robustHoldMs / 1000 + " s";
            }
        } else {
            boolean calm = now - tierSince >= robustHoldMs && now - lastCalmEnd >= robustHoldMs;
            want = calm ? Tier.ROBUST : Tier.SURVIVAL;
            why = "calm for " + robustHoldMs / 1000 + " s";
        }
        if (want == tier) return null;
        Tier was = tier;
        long held = now - tierSince;
        tier = want;
        entered(now, want.ordinal() < was.ordinal());
        if (want.ordinal() > was.ordinal()) { // down
            stalls.clear();
            if (was == Tier.SHARP && forced == null) { // left within a minute: the next sharp spell waits longer; a spell that lasted: the usual wait
                sharpHoldMs = held < 60_000 ? Math.min(MAX_ROBUST_HOLD_MS, sharpHoldMs * 2) : SHARP_AFTER_MS;
            }
        } else if (was.ordinal() >= Tier.ROBUST.ordinal()) {
            leftRobustAt = now;
        }
        return new Change(want, was, why);
    }
}
