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
 * DRAUPNE Flight: which stick frame the phone takes, on its receive thread (moved out of Udp, rules unchanged). Pure:
 * the phone's Udp hands it the packet and its monotonic clock, the PC's tests check it.
 * <ul>
 * <li>The frame: [24][count u8][count x i16], then by the PC's build [seq u8] (pc.19), [seq u16] (pc.26) or
 * [seq u16][sentMs u16][maxLagCs u8] (pc.31); the oldest PCs send no sequence.</li>
 * <li>The sequence: a frame older than (or the same as) the last one taken - a late packet after a newer one - is
 * dropped. 8 bits: anything goes after {@link #SEQ_RESET_MS} without a frame (a frame 128 or more behind looked
 * newer, and the fresh frames after it were dropped until the count passed it). 16 bits: a newer frame is always
 * newer, so only a newer one is taken ({@link Seq16}, F-067: for as long as older and newer can be told apart - a
 * lone old frame no longer passes for fresh after a second without one); no frame waits for this, it only
 * refuses.</li>
 * <li>The lag (pc.31): the phone's clock minus sentMs is the one-way time plus a fixed offset; the smallest over the
 * last two buckets of {@link #LAG_BUCKET_MS} is the best the link did, and a frame later than that by more than the
 * limit (0 = never drop) is dropped - sticks seconds old (a 4G queue flushed) end in Betaflight's failsafe instead of
 * flying the drone late. Buckets of 10 s (20 s seen at most): long enough that a queue building up isn't taken as the
 * new normal within a few seconds, short enough that a path that got slower for good (a relay) is learned.</li>
 * <li>pc.50: a new session from the same PC keeps the best transit ({@link #restart(long, boolean)}): wiped, the
 * seconds-old frames a 4G queue flushes after an outage were the new baseline, and none was dropped.</li>
 * <li>pc.50: {@link IdleGuard} - by the PC's last stick frame, whether the idle sticks may be fed at all.</li>
 * </ul>
 * Read and written on the receive thread only, but for the figures the stats thread reports (volatile) and the time
 * of the last frame written, which the idle sticks' thread reads (written by the caller under its write lock).
 */
public final class RcPath {
    /** 8-bit sequence: no frame taken for this long, the next one is taken whatever its sequence. */
    public static final long SEQ_RESET_MS = 300;
    /**
     * 16-bit sequence: frames that are not newer than the last one taken, coming as a steady stream for this long, are
     * the PC counting anew ({@link Seq16}). Before F-067: no frame taken for this long, anything was taken.
     */
    public static final long SEQ16_FORGET_MS = 1000;
    /**
     * F-067, 16-bit sequence: a frame that is not newer than the last one taken stays old for this long after it. The
     * PC sends 250 frames a second at its fastest (ControlLink.MAX_RC_RATE): 30 000 in this time, short of the 32 768
     * from where "older" and "newer" can no longer be told apart; the rest, 11 s of frames, is room for a last frame
     * that was itself late. With no frame taken for longer, anything is taken, as it was after a second.
     */
    public static final long SEQ16_OLD_MS = 120_000;
    public static final long LAG_BUCKET_MS = 10_000;
    /** pc.50: a best transit last measured against longer ago than this isn't kept (the two clocks drift apart). */
    public static final long LAG_KEEP_MS = 120_000;
    /** pc.50: a new session's frame later than the kept best by more than this is another clock, not a late frame. */
    public static final int LAG_KEEP_SPAN_MS = 5000;
    /** pc.50: no frame near the kept best for this long: the path is slower for good (or the clock another) - learned anew. */
    public static final long LAG_TRIAL_MS = 2000;
    /** pc.50: "near" the kept best while the PC sets no limit. */
    public static final int LAG_TRIAL_NEAR_MS = 250;

    /** The channels of the last frame taken, filled in place: no allocation per frame. */
    private final short[] frame = new short[FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT];
    private volatile int lastSeq = -1; // volatile: the stats thread reports it
    private boolean seqValid;
    private final Seq16 seq16 = new Seq16();
    /** When the last frame was taken; volatile: the idle sticks' thread reads it. */
    private volatile long lastTakenMs;
    private int lagMinNow = -1, lagMinBefore = -1;
    private long lagBucketAt;
    /** pc.50: when a frame's transit was last measured (0: never), and the kept best on trial (see restart). */
    private long lagLastAt;
    private boolean lagOnTrial;
    private long lagTrialSince;
    private volatile int lagMs = -1;
    private volatile int lagMaxMs = -1;
    private volatile int lagDropped;
    /** The last frame carried sentMs: the PC knows the PHONE_RC_LAG record (sent only then). */
    private volatile boolean lagFromPc;

    /**
     * A stick frame: the number of channels taken into {@link #frame()}, or -1 when it is dropped (cut short, too
     * many or too few channels, old by its sequence, too late). The caller then calls {@link #taken} with the same
     * time as it writes the frame.
     */
    public int take(byte[] d, int len, long now) {
        if (len < 2) return -1;
        int count = d[1] & 0xff;
        if (count < 4 || count > FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT) return -1;
        int remaining = len - 2;
        if (remaining == count * 2 + 5) { // pc.31: [seq u16][sentMs u16][maxLagCs u8]
            int seq = ((d[len - 5] & 0xff) << 8) | (d[len - 4] & 0xff);
            int sentMs = ((d[len - 3] & 0xff) << 8) | (d[len - 2] & 0xff);
            int maxLagMs = (d[len - 1] & 0xff) * 10;
            lagFromPc = true;
            if (!takeSeq16(seq, now)) return -1;
            if (tooLate(sentMs, maxLagMs, now)) return -1;
            lastSeq = seq;
            seqValid = true;
        } else if (remaining == count * 2 + 2) { // pc.26: a 16-bit sequence
            int seq = ((d[len - 2] & 0xff) << 8) | (d[len - 1] & 0xff);
            lagFromPc = false;
            if (!takeSeq16(seq, now)) return -1;
            lastSeq = seq;
            seqValid = true;
        } else if (remaining == count * 2 + 1) {
            int seq = d[len - 1] & 0xff;
            lagFromPc = false;
            if (seqValid && now - lastTakenMs > SEQ_RESET_MS) seqValid = false; // a gap: whatever comes next is newest
            if (seqValid && (byte) (seq - lastSeq) <= 0) return -1;
            lastSeq = seq;
            seqValid = true;
        } else if (remaining == count * 2) {
            lagFromPc = false;
            lastSeq = -1;
        } else {
            return -1;
        }
        int at = 2;
        for (int i = 0; i < count; i++, at += 2) {
            frame[i] = (short) (((d[at] & 0xff) << 8) | (d[at + 1] & 0xff));
        }
        return count;
    }

    /** The frame {@link #take} took was written at now (under the caller's write lock, with the idle sticks' check). */
    public void taken(long now) {
        lastTakenMs = now;
    }

    public short[] frame() {
        return frame;
    }

    public long lastTakenMs() {
        return lastTakenMs;
    }

    /** A new session (or none): the PC's sequence starts over, its clock may be another, the drop count is per session. */
    public void restart(long now) {
        restart(now, false);
    }

    /**
     * pc.50: a new session. From the same PC (its address and port) the best transit of the session before is kept
     * for one bucket more, as long as it was measured against within {@link #LAG_KEEP_MS}: its clock is the same, and
     * after an outage the first frames are the ones a 4G queue held back - with the best wiped they were the new
     * baseline, and seconds-old sticks flew the drone whatever the limit. The kept best is on trial: a frame near it
     * confirms it; one more than {@link #LAG_KEEP_SPAN_MS} later (another clock), or none near it for
     * {@link #LAG_TRIAL_MS} (a path slower for good), and it is forgotten - so the limit never holds the sticks back
     * for longer than that after a reconnect.
     */
    public void restart(long now, boolean samePc) {
        seqValid = false;
        int kept = -1;
        if (samePc && lagLastAt != 0 && now - lagLastAt <= LAG_KEEP_MS) {
            kept = lagMinNow;
            if (kept < 0 || lagMinBefore >= 0 && (short) (lagMinBefore - kept) < 0) kept = lagMinBefore;
        }
        lagMinNow = -1;
        lagMinBefore = kept;
        lagBucketAt = now;
        lagOnTrial = kept >= 0;
        lagTrialSince = 0;
        lagMs = -1;
        lagMaxMs = -1;
        lagDropped = 0;
    }

    /** The sequence of the last frame taken, -1 for none or one without. */
    public int lastSeq() {
        return lastSeq;
    }

    public boolean lagFromPc() {
        return lagFromPc;
    }

    public int lagMs() {
        return lagMs;
    }

    public int lagDropped() {
        return lagDropped;
    }

    /** The most a frame was late since the last call (the stats thread, once a second), -1 for none. */
    public int takeLagMaxMs() {
        int max = lagMaxMs;
        lagMaxMs = -1;
        return max;
    }

    /**
     * The idle sticks (pc.33, [0x78][count u8][count x i16]): a new array, never written after, or null when the
     * packet isn't one.
     */
    public static short[] idleFrame(byte[] d, int len) {
        if (len < 2) return null;
        int count = d[1] & 0xff;
        if (count < 4 || count > FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT || len != 2 + count * 2) return null;
        short[] frame = new short[count];
        for (int i = 0, at = 2; i < count; i++, at += 2) frame[i] = (short) (((d[at] & 0xff) << 8) | (d[at + 1] & 0xff));
        return frame;
    }

    /** The 16-bit sequence rule ({@link Seq16}): newer than the last frame taken - or nothing to compare it with. */
    private boolean takeSeq16(int seq, long now) {
        return seq16.takes(seqValid, lastSeq, lastTakenMs, seq, now);
    }

    /**
     * F-067: which frame with a 16-bit sequence is taken - the phone's rule, and the PC's made-up phone's.
     * <p>
     * Before, a second without a frame taken made the next one welcome whatever its number ("the PC may have started
     * counting again", pc.31). With a session up, the drone disarmed and the pilot's frames stopped - standby, the
     * radio at rest - one frame that came late or twice was then taken and written: ARM on and the throttle low, from
     * before the landing, and the drone armed where it stood. Nothing in DRAUPNE Link starts the count anew inside a
     * session: the count is the link's own from its first frame to its last, a new link sends a Connect before its
     * first frame, and the session that Connect starts on the phone forgets the sequence ({@link #restart}). The
     * second was there for a phone on upstream's relay, which sees no Connect when the PC behind it starts again (no
     * phone app since pc.28 can be set to that), and so that no slip could ever lock the sticks out.
     * <p>
     * Now a frame that is not newer than the last one taken is old for {@link #SEQ16_OLD_MS} after that one - as long
     * as the two can be told apart. What the second was for is kept without it: frames that are not newer but come as
     * a stream - each newer than the one before, none more than {@link #SEQ_RESET_MS} after it, for
     * {@link #SEQ16_FORGET_MS} with no newer frame between - are a PC that counts from elsewhere, and the stream is
     * taken from there. So whatever sends a live stream has its sticks at the flight controller within about a second,
     * as before; a late frame, a doubled one, a bunch a queue let go (over in milliseconds) and a trickle with gaps
     * are not a stream.
     * <p>
     * Receive thread only. For a frame that is newer - every frame of a link that flies - it costs what it cost (two
     * comparisons) and one store; nothing is allocated.
     */
    public static final class Seq16 {
        /** A stream of frames that are not newer is being followed: since when, and its newest frame and when it came. */
        private boolean stream;
        private long streamSince, streamAt;
        private int streamSeq;

        /**
         * @param valid       a frame with a sequence has been taken in this session
         * @param last        the last one's sequence
         * @param lastTakenMs when the last frame was taken
         * @return whether the frame with this sequence, here at now, is taken
         */
        public boolean takes(boolean valid, int last, long lastTakenMs, int seq, long now) {
            if (!valid || (short) (seq - last) > 0 || now - lastTakenMs > SEQ16_OLD_MS) {
                stream = false;
                return true;
            }
            if (stream && now - streamAt > SEQ_RESET_MS) stream = false; // it broke off: what comes now begins another
            if (!stream) {
                stream = true;
                streamSince = streamAt = now;
                streamSeq = seq;
                return false;
            }
            if ((short) (seq - streamSeq) <= 0) return false; // a copy, or one overtaken within the stream: says nothing
            streamSeq = seq;
            streamAt = now;
            if (now - streamSince < SEQ16_FORGET_MS) return false;
            stream = false;
            return true;
        }
    }

    /**
     * Updates the best transit seen with this frame's and says whether the frame is later than that by more than
     * maxLagMs (0 = no limit). The 16-bit times compare across the wrap.
     */
    private boolean tooLate(int sentMs, int maxLagMs, long nowMs) {
        int transit = (int) ((nowMs - sentMs) & 0xFFFF);
        lagLastAt = nowMs;
        if (nowMs - lagBucketAt >= LAG_BUCKET_MS) {
            lagMinBefore = lagMinNow;
            lagMinNow = -1;
            lagBucketAt = nowMs;
            lagOnTrial = false; // pc.50: a kept best has aged out with its bucket
        }
        if (lagOnTrial) tryKeptBest(transit, maxLagMs, nowMs);
        if (lagMinNow < 0 || (short) (transit - lagMinNow) < 0) lagMinNow = transit;
        int best = lagMinNow;
        if (lagMinBefore >= 0 && (short) (lagMinBefore - best) < 0) best = lagMinBefore;
        int lag = Math.max(0, (short) (transit - best));
        lagMs = lag;
        if (lag > lagMaxMs) lagMaxMs = lag;
        if (maxLagMs > 0 && lag > maxLagMs) {
            lagDropped++;
            return true;
        }
        return false;
    }

    /**
     * pc.50: the best transit kept from the session before (lagMinBefore, see {@link #restart(long, boolean)})
     * against a frame of the new one: confirmed by a frame near it (within the PC's limit), forgotten when the frame
     * is far later than a late frame can be or nothing came near it for LAG_TRIAL_MS. Forgotten, the new session's own
     * frames so far are the baseline, as in any session.
     */
    private void tryKeptBest(int transit, int maxLagMs, long nowMs) {
        if (lagTrialSince == 0) lagTrialSince = nowMs;
        int late = (short) (transit - lagMinBefore);
        if (late <= (maxLagMs > 0 ? maxLagMs : LAG_TRIAL_NEAR_MS)) {
            lagOnTrial = false;
        } else if (late > LAG_KEEP_SPAN_MS || nowMs - lagTrialSince >= LAG_TRIAL_MS) {
            lagOnTrial = false;
            lagMinBefore = -1;
        }
    }

    /**
     * pc.50: by the PC's last stick frame, whether the idle sticks (pc.33) may stand in for the PC's at all. They say
     * "arm switch off", and that ends Betaflight's lockout after a failsafe (or a crash it disarmed on): the link lost
     * in the air, the drone down and disarmed by its failsafe, the idle sticks fed - and the pilot's own frames, ARM
     * still on and throttle low, armed it on the ground when the link came back. So they are fed only while the
     * pilot's sticks last said the same: the last frame taken came while the FC read DISARMED (a fresh status; armed
     * or unknown: no) and could not turn ARM on by the FC's own ranges (after the failsafe the link may come and go
     * with the switch still on: each of those frames comes at a disarmed FC). No frame yet: fed, as before (a PC
     * without a radio). Pure; the caller's write lock orders {@link #frame} against {@link #check}.
     * <p>
     * F-066: "no frame yet" is also an app that has just started anew - after an error, or ended by Android - while
     * the link is out, the drone down and disarmed by its failsafe and the pilot's switch still on. This guard is as
     * old as the app's process: it knows none of that, feeds, and the lockout is gone as above. The idle sticks cannot
     * wait for the pilot there (a drone that rests after the app started would beep for its lost receiver until a
     * pilot with a radio came: what they are for, pc.33 and pc.54). So the phone that says "ARM off" on its own
     * account keeps what that ended: from the first idle frame fed before any stick frame of a PC's, a stick frame
     * that could arm is not written ({@link #holds}) until one has come that cannot - the pilot's own ARM off, in
     * this process. Betaflight's own rule, kept by the phone for as long as the phone has undone it; a flight
     * controller that reads ARMED gets the pilot's sticks whatever is owed.
     */
    public static final class IdleGuard {
        /** Fed; held back: the FC armed (or not known disarmed) at the PC's last frame; that frame's own ARM on. */
        public static final int FEED = 0, HELD_FC_ARMED = 1, HELD_ARM_ON = 2;
        /** The PC's last stick frame (a copy, its own length); null: none yet. */
        private short[] last;
        private boolean fcDisarmed;
        /**
         * F-066: idle sticks were fed before any stick frame of a PC's, and no frame that cannot arm has come since.
         * Volatile: the stick path reads it for every frame, without a lock; written under the caller's write lock.
         */
        private volatile boolean armOffOwed;

        /** A PC stick frame was taken, with the FC reading DISARMED in a fresh status then - or not. */
        public synchronized void frame(short[] channels, int count, boolean fcDisarmed) {
            if (channels == null || count < 0 || count > channels.length) return;
            if (last == null || last.length != count) last = new short[count];
            System.arraycopy(channels, 0, last, 0, count);
            this.fcDisarmed = fcDisarmed;
        }

        /**
         * The caller's last question before it feeds: on FEED the idle frame is written. F-066: fed before any stick
         * frame of a PC's, the pilot's own ARM off is owed from here ({@link #holds}).
         * @param mayArm whether a frame could turn the FC's ARM mode on ({@link ArmRanges#mayArm}; ranges unread: yes)
         * @return FEED, or why the idle sticks are held back
         */
        public synchronized int check(java.util.function.Predicate<short[]> mayArm) {
            if (last == null) {
                armOffOwed = true;
                return FEED;
            }
            if (!fcDisarmed) return HELD_FC_ARMED;
            return mayArm.test(last) ? HELD_ARM_ON : FEED;
        }

        /**
         * F-066: whether the pilot's own ARM off is owed - all the stick path reads while it is not (one volatile
         * read a frame); only then does it ask {@link #holds}.
         */
        public boolean armOffOwed() {
            return armOffOwed;
        }

        /**
         * F-066, for a PC's stick frame before it is written, while {@link #armOffOwed}: whether it is held back -
         * not written to the flight controller. The caller goes on as with any frame taken ({@link #frame}: it is the
         * pilot's last word, so the idle sticks stop too, and Betaflight is left with its lost receiver and its own
         * rule again). No lock, nothing allocated; under the caller's write lock, as {@link #check}.
         * @param mayArm  this frame could turn the FC's ARM mode on ({@link ArmRanges#mayArm}; ranges unread: yes)
         * @param fcArmed the FC reads ARMED in a fresh status now: never held - a drone that flies has its pilot's
         *                sticks, whatever this guard thinks it is owed
         */
        public boolean holds(boolean mayArm, boolean fcArmed) {
            if (!armOffOwed) return false;
            if (!mayArm) {
                armOffOwed = false; // the pilot's own ARM off: from here the switch is the pilot's again
                return false;
            }
            return !fcArmed;
        }
    }
}
