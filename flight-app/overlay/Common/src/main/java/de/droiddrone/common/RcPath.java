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
 * newer, so only a newer one is taken until {@link #SEQ16_FORGET_MS} without one (the PC may have started counting
 * again); no frame waits for this, it only refuses.</li>
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
    /** 16-bit sequence: no frame taken for this long, anything is taken. */
    public static final long SEQ16_FORGET_MS = 1000;
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

    /** The 16-bit sequence rule: newer than the last frame taken, or anything after SEQ16_FORGET_MS without one. */
    private boolean takeSeq16(int seq, long now) {
        if (seqValid && now - lastTakenMs > SEQ16_FORGET_MS) seqValid = false;
        return !seqValid || (short) (seq - lastSeq) > 0;
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
     */
    public static final class IdleGuard {
        /** Fed; held back: the FC armed (or not known disarmed) at the PC's last frame; that frame's own ARM on. */
        public static final int FEED = 0, HELD_FC_ARMED = 1, HELD_ARM_ON = 2;
        /** The PC's last stick frame (a copy, its own length); null: none yet. */
        private short[] last;
        private boolean fcDisarmed;

        /** A PC stick frame was taken, with the FC reading DISARMED in a fresh status then - or not. */
        public synchronized void frame(short[] channels, int count, boolean fcDisarmed) {
            if (channels == null || count < 0 || count > channels.length) return;
            if (last == null || last.length != count) last = new short[count];
            System.arraycopy(channels, 0, last, 0, count);
            this.fcDisarmed = fcDisarmed;
        }

        /**
         * @param mayArm whether a frame could turn the FC's ARM mode on ({@link ArmRanges#mayArm}; ranges unread: yes)
         * @return FEED, or why the idle sticks are held back
         */
        public synchronized int check(java.util.function.Predicate<short[]> mayArm) {
            if (last == null) return FEED;
            if (!fcDisarmed) return HELD_FC_ARMED;
            return mayArm.test(last) ? HELD_ARM_ON : FEED;
        }
    }
}
