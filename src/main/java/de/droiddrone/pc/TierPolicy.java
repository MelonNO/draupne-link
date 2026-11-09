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
 * {@link #LOW_RES_AFTER_MS}), {@link #STALLS_FOR_ROBUST} stalls within {@link #STALLS_WINDOW_MS}, or a relay; bad
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
    /**
     * AUTO goes to the sharp tier after this long calm (no stall) at the normal top bitrate; doubled (up to 5 min)
     * when it had to leave the sharp tier within a minute, back to this after a sharp spell that lasted.
     */
    static final long SHARP_AFTER_MS = 20_000;
    /** The sharp tier ends when the phone's bitrate sat under the normal top for this long: the link can't carry it. */
    static final long SHARP_DROP_MS = 5000;

    /**
     * The link at one tick: ms since the last pong, the phone's bitrate (0 = it reports none), the normal top bitrate
     * the settings allow, a relayed way to the phone, spectators watching, this PC's decode load (1 = all its time;
     * 0 without a picture), and whether the settings give the sharp tier anything over the normal picture.
     */
    record Link(long sincePongMs, int bps, int normalTopBps, boolean relayed, boolean watched, double decodeLoad,
                boolean sharpAvailable) {
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
            tierSince = now;
            return new Change(forced, was, "VIDEO is " + mode.name());
        }
        if (forced == null && tier == Tier.SHARP) {
            tier = Tier.NORMAL;
            tierSince = now;
        }
        return null;
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
        survivalRateSince = bps > 0 && bps <= SURVIVAL_BPS ? (survivalRateSince < 0 ? now : survivalRateSince) : -1;
        int normalTopBps = l.normalTopBps();
        belowTopSince = bps > 0 && bps < normalTopBps ? (belowTopSince < 0 ? now : belowTopSince) : -1;
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
            if (lowRate || stalls3 || relayed) {
                want = Tier.ROBUST;
                why = lowRate ? "bitrate at " + bps / 1000 + " kbit/s"
                        : stalls3 ? stalls.size() + " stalls in " + STALLS_WINDOW_MS / 1000 + " s" : "relayed";
                if (leftRobustAt >= 0 && now - leftRobustAt < 60_000) {
                    robustHoldMs = Math.min(MAX_ROBUST_HOLD_MS, robustHoldMs * 2); // back too soon: stay longer next time
                }
            } else {
                // calm at the normal top bitrate for sharpHoldMs, no stall in that time: the sharp tier (a phone that
                // reports no bitrate setting never gets there)
                boolean atTop = bps >= normalTopBps;
                boolean calm = now - tierSince >= sharpHoldMs && now - lastCalmEnd >= sharpHoldMs && stalls.isEmpty();
                want = l.sharpAvailable() && atTop && calm && !l.watched() ? Tier.SHARP : Tier.NORMAL;
                why = "calm at " + normalTopBps / 1_000_000 + " Mbit/s for " + sharpHoldMs / 1000 + " s";
            }
        } else if (tier == Tier.ROBUST) {
            // bad coverage mode isn't enough: the bitrate sits at the bottom or the stalls go on - the survival tier
            boolean lowRate = survivalRateSince >= 0 && now - survivalRateSince >= LOW_RES_AFTER_MS;
            boolean stalls3 = stalls.size() >= STALLS_FOR_ROBUST;
            boolean worse = now - tierSince >= SURVIVAL_AFTER_MS && (lowRate || stalls3);
            if (worse) {
                want = Tier.SURVIVAL;
                why = lowRate ? "still " + bps / 1000 + " kbit/s" : "still " + stalls.size() + " stalls";
                if (leftRobustAt >= 0 && now - leftRobustAt < 60_000) robustHoldMs = Math.min(MAX_ROBUST_HOLD_MS, robustHoldMs * 2);
            } else if (relayed) {
                want = Tier.ROBUST;
                why = "relayed";
            } else {
                // calm long enough in bad coverage mode: no stall, and the bitrate not struggling under its 1 Mbit/s
                boolean calm = now - tierSince >= robustHoldMs && now - lastCalmEnd >= robustHoldMs
                        && (bps <= 0 || bps > LOW_RES_BPS);
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
        tierSince = now;
        belowTopSince = -1;
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
