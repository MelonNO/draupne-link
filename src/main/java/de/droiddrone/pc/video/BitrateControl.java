package de.droiddrone.pc.video;

/**
 * Decides when to ask the phone's encoder for a lower or higher bitrate (it steps through 0.15 (phone app pc.26),
 * 0.25, 0.5, 1, 2, 4, 6 Mbit/s). Lower when the link is in real trouble - frames going missing, a long gap, the
 * ping climbing well above its own usual - and raise again after a short calm. On 4G a too-high bitrate builds
 * queues, and queues are latency.
 * <p>
 * 1.0.33: a spike is judged against the median of the last second's pings, not the quietest ping of the last 30 s -
 * on a jittery 4G link every second ping was a "spike" by that rule, the steps went to the bottom and a raise never
 * came (the calm it needs never came either). Short spikes no longer count as trouble for the calm; a spike that
 * holds goes straight to the floor in one SET_BITRATE (phone app pc.26) instead of a step a second.
 * <p>
 * Tuned for far-away 4G (2026-10-31: the phone 100-200 ms away with spikes to 1.8 s): the old rules (any ping over
 * 300 ms, a damaged key frame counting five, 10 s of calm before raising) held the encoder at 0.25 Mbit/s for good.
 * Damaged key frames come from bursts lost on the way, not from too much data, so they no longer lower the rate on
 * their own; the ping is judged against its own usual level only; raising waits 3 s (longer after a raise that went
 * wrong); and the PC never lowers below 1 Mbit/s. The phone also steps down by itself (when a key frame is slow to
 * send) and then ignores raises for 5 s, so the PC's count of the steps drifts: what arrives decides instead - under
 * RESCUE_BELOW_MBPS for RESCUE_MS, the PC asks the phone up and takes it as at the floor.
 */
public class BitrateControl {
    public interface Sink {
        void changeBitRate(boolean increase);

        /** The step outright (SET_BITRATE, phone app pc.26); older phones get one relative step towards it. */
        default void setStep(int step, int from) {
            changeBitRate(step > from);
        }
    }

    private static final int PAUSE_MS = 2000;
    /** A gap this long between frames is trouble (4G jitter alone stays under it). */
    private static final int FRAME_GAP_MS = 300;
    /** Raise after this long without trouble; doubled (up to MAX_CALM_MS) after a raise that brought trouble at once. */
    static final int CALM_MS = 3000;
    private static final int MAX_CALM_MS = 30_000;
    /** Ping this far above its recent minimum means packets are queueing on the way. */
    static final int QUEUE_DELAY_MS = 100;
    /** ...for at least this long: a single slow ping is just 4G jitter. */
    static final int QUEUE_HOLD_MS = 1000;
    private static final int BASELINE_WINDOW_MS = 15_000;
    /** The phone's steps; it starts on 2 Mbit/s (step 4). The PC doesn't lower below step 3, 1 Mbit/s. */
    static final int START_STEP = 4;
    static final int FLOOR_STEP = 3;
    private static final int TOP_STEP = 6;
    /**
     * A filling uplink queue (2026-11-01: the ping went to 3 s, then 7.7 s, at 6 Mbit/s): ping SPIKE_MS over the usual
     * (the median of the last second) for SPIKE_HOLD_MS, or no pong at all for a while, goes to the floor at once
     * (one step at a time on a phone app before pc.26) and, while it lasts SPIKE_DEEP_MS and more, one step further
     * down every SPIKE_PAUSE_MS - below the 1 Mbit/s floor if need be. A live, softer picture beats a sharp one
     * seconds old.
     */
    static final int SPIKE_MS = 250;
    /** A ping this far over the quietest of the last 30 s is a queue whatever the jitter (a steady 2 s ping is no "typical"). */
    static final int SPIKE_ABSOLUTE_MS = 1000;
    static final int SPIKE_HOLD_MS = 200;
    static final int SPIKE_PAUSE_MS = 1000;
    static final int SPIKE_DEEP_MS = 2000;
    /** A raise waits this long after the last spike (and the calm after the last real trouble). */
    static final int SPIKE_CALM_MS = 2000;
    /** The pings a spike is judged against: the median of the last this many (about a second of frames). */
    private static final int TYPICAL_PINGS = 15;
    /** Congested (spike, queueing or no pong) this recently: no key frame requests - each is the biggest burst. */
    static final int CONGESTED_FOR_MS = 1000;
    /** Less than this arriving for RESCUE_MS while at the floor: the phone went lower by itself; ask it up. */
    static final double RESCUE_BELOW_MBPS = 0.5;
    static final int RESCUE_MS = 5000;

    private final Sink sink;
    private int badFrames;
    private long lastFrame;
    private long lastKeyFrame;
    private long lastChange;
    private long lastTrouble;
    private long windowStart;
    private boolean started;
    private int windowMinPing = Integer.MAX_VALUE;
    private int previousMinPing = Integer.MAX_VALUE;
    private long baselineStart;
    private long queueingSince = -1;
    /** Where the PC thinks the phone's encoder is (the phone also steps down by itself, so only an estimate). */
    private int step = START_STEP;
    private int calmMs = CALM_MS;
    private long lastRaise = -1;
    /** The last raise brought trouble at once: the next one waits the longer calm. */
    private boolean raiseFailed;
    private long lowSince = -1;
    private long lastRescue;
    private long spikeSince = -1;
    private long lastSpike = Long.MIN_VALUE / 2;
    private long lastCongested = Long.MIN_VALUE / 2;
    private final int[] recentPings = new int[TYPICAL_PINGS];
    private int recentCount, recentAt;
    /** The highest step to ask for: the set limit, 1 Mbit/s in bad coverage mode (see setMaxStep). */
    private int maxStep = TOP_STEP;
    /** The phone's last report of its bitrate setting (bit/s), or -1 from a phone that doesn't report. */
    private int phoneTargetBps = -1;

    public BitrateControl(Sink sink) {
        this.sink = sink;
    }

    /** The highest step to ask for (the set limit, or bad coverage mode's 1 Mbit/s); above it, it steps down. */
    public synchronized void setMaxStep(int max) {
        maxStep = Math.max(0, Math.min(TOP_STEP, max));
    }

    /**
     * Every 200 ms or so while the stream runs, frames or not: in a dropout no frame comes, and the steps down must not
     * wait for one (2026-11-01: the second step came 4.8 s after the first).
     */
    public synchronized void onTick(long now, int ping) {
        if (!started) return;
        evaluate(false, false, now, 30, ping);
    }

    /** A frame was lost or arrived damaged. */
    public synchronized void onBadFrame(boolean keyFrame) {
        badFrames++;
    }

    /**
     * A frame was received.
     * @param fps   current frame rate (allowed bad frames per second scale with it)
     * @param ping  round trip in ms, -1 if unknown (no pong lately: a stall)
     */
    public synchronized void onFrame(boolean keyFrame, long now, int fps, int ping) {
        if (!started) {
            started = true;
            lastFrame = lastKeyFrame = lastChange = lastTrouble = windowStart = baselineStart = now;
        }
        evaluate(true, keyFrame, now, fps, ping);
    }

    private void evaluate(boolean frame, boolean keyFrame, long now, int fps, int ping) {
        boolean queueing = queueing(ping, now);
        int typical = typical();
        if (ping >= 0) {
            recentPings[recentAt] = ping;
            recentAt = (recentAt + 1) % TYPICAL_PINGS;
            if (recentCount < TYPICAL_PINGS) recentCount++;
        }
        boolean spikeNow = ping < 0 || (typical >= 0 && ping > typical + SPIKE_MS)
                || (baseline() != Integer.MAX_VALUE && ping > baseline() + SPIKE_ABSOLUTE_MS);
        spikeSince = spikeNow ? (spikeSince < 0 ? now : spikeSince) : -1;
        if (spikeNow || queueing) lastCongested = now;
        if (spikeSince >= 0 && now - spikeSince >= SPIKE_HOLD_MS) {
            lastSpike = now;
            if (lastRaise >= 0 && now - lastRaise < PAUSE_MS && !raiseFailed) { // raised too soon: wait longer next time
                raiseFailed = true;
                calmMs = Math.min(MAX_CALM_MS, calmMs * 2);
            }
            boolean deep = now - spikeSince >= SPIKE_DEEP_MS;
            if (deep) lastTrouble = now;
            if (now - lastChange >= SPIKE_PAUSE_MS && step > 0 && (step > FLOOR_STEP || deep)) {
                badFrames = 0;
                lastChange = now;
                int to = step > FLOOR_STEP ? Math.min(FLOOR_STEP, maxStep) : step - 1; // to the floor in one go
                sink.setStep(to, step);
                step = to;
            }
            if (frame) lastFrame = now;
            if (keyFrame) lastKeyFrame = now;
            return;
        }
        if (step > maxStep && now - lastChange >= SPIKE_PAUSE_MS) { // over the top (bad coverage mode came on): down
            lastChange = now;
            step--;
            sink.changeBitRate(false);
        }
        boolean trouble = queueing
                || badFrames > Math.max(2, Math.round(Math.max(fps, 1) / 10f))
                || now > lastFrame + FRAME_GAP_MS
                || ping < 0; // no rule on key frames: since phone app pc.13 they come every 2-10 s, and on request
        if (trouble) {
            lastTrouble = now;
            if (lastRaise >= 0 && now - lastRaise < PAUSE_MS && !raiseFailed) { // raised too soon: wait longer next time
                raiseFailed = true;
                calmMs = Math.min(MAX_CALM_MS, calmMs * 2);
            }
            if (now - lastChange > PAUSE_MS && step > FLOOR_STEP) {
                badFrames = 0;
                lastChange = now;
                step--;
                sink.changeBitRate(false);
            }
        } else if (now - lastTrouble >= calmMs && now - lastChange > PAUSE_MS && step < maxStep // never over the top
                && spikeSince < 0 && now - lastSpike >= SPIKE_CALM_MS) { // nor while a spike is building up, or just after one
            if (!raiseFailed) calmMs = CALM_MS; // the last raise held: back to quick
            raiseFailed = false;
            lastTrouble = now;
            lastRaise = now; // not lastChange: if the higher rate brings trouble, back off at once
            step++;
            sink.changeBitRate(true);
        }
        if (frame) lastFrame = now;
        if (keyFrame) lastKeyFrame = now;
        if (now - windowStart >= 1000) {
            badFrames = 0;
            windowStart = now;
        }
    }

    /**
     * Once a second: the video arriving, Mbit/s. The phone below the floor for RESCUE_MS - by its own report (phone app
     * pc.13 on), or, from a phone that doesn't report, under RESCUE_BELOW_MBPS arriving whatever the PC's count of the
     * steps says (2026-10-31: it said 4-6 Mbit/s while 0.5 arrived) - means it stepped down by itself: ask it up, every
     * RESCUE_MS (it ignores raises for 5 s after stepping down), and count it as at the floor from now on. Never above
     * maxStep: in bad coverage mode a calm scene arrives well under 0.5 Mbit/s at the 1 Mbit/s setting, and a rescue
     * there pushed it to 2 and back every 5 s (the reported target tells the two apart).
     */
    public synchronized void onReceivedRate(double mbps, long now) {
        boolean low = phoneTargetBps > 0 ? step < FLOOR_STEP : mbps < RESCUE_BELOW_MBPS;
        if (!low) {
            lowSince = -1;
            return;
        }
        if (lowSince < 0) lowSince = now;
        if (now - lowSince < RESCUE_MS || now - lastTrouble < RESCUE_MS) return; // congested: little is meant to arrive
        step = Math.min(step, FLOOR_STEP);
        if (step >= maxStep) return; // where it is meant to be
        if (now - lastRescue >= RESCUE_MS) {
            lastRescue = now;
            sink.changeBitRate(true);
        }
    }

    /** The phone's steps, bit/s (150 kbit/s from phone app pc.26; an older phone stops at 250). */
    public static final int[] STEPS_BPS = {150_000, 250_000, 500_000, 1_000_000, 2_000_000, 4_000_000, 6_000_000};

    /**
     * The phone's own report of its bitrate setting (phone app 2.3.0-pc.13 on): the PC's count of the steps follows it,
     * instead of drifting when the phone stepped by itself.
     */
    public synchronized void onPhoneTarget(int bps) {
        if (bps <= 0) return;
        phoneTargetBps = bps;
        int s = 0;
        while (s < STEPS_BPS.length - 1 && STEPS_BPS[s + 1] <= bps) s++;
        step = s;
    }

    /** The step the PC thinks the phone is on: 0-5 for 0.25-6 Mbit/s (tests, and the link log). */
    public synchronized int step() {
        return step;
    }

    /** The link's own usual ping: the lowest of the last 15-30 s, ms; -1 before the first (the OSD colours by it). */
    public synchronized int baselinePingMs() {
        int b = baseline();
        return b == Integer.MAX_VALUE ? -1 : b;
    }

    /** The link is congested now or was a moment ago: don't add key frames (see CONGESTED_FOR_MS). */
    public synchronized boolean isCongested(long now) {
        return now - lastCongested < CONGESTED_FOR_MS;
    }

    /** The usual ping: the lowest of the last 15-30 s (Integer.MAX_VALUE before the first). */
    private int baseline() {
        return Math.min(windowMinPing, previousMinPing);
    }

    /** The ping of the moment: the median of the last TYPICAL_PINGS pongs; -1 before the first. */
    private int typical() {
        if (recentCount == 0) return -1;
        int[] sorted = java.util.Arrays.copyOf(recentPings, recentCount);
        java.util.Arrays.sort(sorted);
        return sorted[recentCount / 2];
    }

    /** Tests and the link log: the ping of the moment the spike rule judges by (-1 before the first pong). */
    public synchronized int typicalPingMs() {
        return typical();
    }

    /** True once the ping has stayed QUEUE_DELAY_MS above its minimum of the last 15-30 s for QUEUE_HOLD_MS. */
    private boolean queueing(int ping, long now) {
        if (ping < 0) {
            queueingSince = -1;
            return false;
        }
        if (now - baselineStart >= BASELINE_WINDOW_MS) {
            previousMinPing = windowMinPing;
            windowMinPing = Integer.MAX_VALUE;
            baselineStart = now;
        }
        windowMinPing = Math.min(windowMinPing, ping);
        int baseline = Math.min(windowMinPing, previousMinPing);
        if (ping <= baseline + QUEUE_DELAY_MS) {
            queueingSince = -1;
            return false;
        }
        if (queueingSince < 0) queueingSince = now;
        return now - queueingSince >= QUEUE_HOLD_MS;
    }
}
