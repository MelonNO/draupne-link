package de.droiddrone.pc.video;

/**
 * Decides when to ask the phone's encoder for a lower or higher bitrate (it steps through 0.25, 0.5, 1, 2, 4, 6 Mbit/s).
 * Lower when the link is in real trouble - frames going missing, a long gap, the ping climbing well above its own
 * usual - and raise again after a short calm. On 4G a too-high bitrate builds queues, and queues are latency.
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
    /** The phone's steps; it starts on 2 Mbit/s (step 3). The PC doesn't lower below step 2, 1 Mbit/s. */
    static final int START_STEP = 3;
    static final int FLOOR_STEP = 2;
    private static final int TOP_STEP = 5;
    /**
     * A filling uplink queue (2026-11-01: the ping went to 3 s, then 7.7 s, at 6 Mbit/s): ping SPIKE_MS over the usual
     * for SPIKE_HOLD_MS, or no pong at all for a while, steps down at once and again every SPIKE_PAUSE_MS while it lasts
     * (the phone takes one step down a second) - below the 1 Mbit/s floor if need be. A live, softer picture beats a
     * sharp one seconds old.
     */
    static final int SPIKE_MS = 250;
    static final int SPIKE_HOLD_MS = 200;
    static final int SPIKE_PAUSE_MS = 1000;
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
    private long lastCongested = Long.MIN_VALUE / 2;
    /** The highest step to ask for: the set limit, 1 Mbit/s in bad coverage mode (see setMaxStep). */
    private int maxStep = TOP_STEP;

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
        boolean spikeNow = ping < 0 || (ping > baseline() + SPIKE_MS && baseline() != Integer.MAX_VALUE);
        spikeSince = spikeNow ? (spikeSince < 0 ? now : spikeSince) : -1;
        if (spikeNow || queueing) lastCongested = now;
        if (spikeSince >= 0 && now - spikeSince >= SPIKE_HOLD_MS) {
            lastTrouble = now;
            if (now - lastChange >= SPIKE_PAUSE_MS && step > 0) {
                badFrames = 0;
                lastChange = now;
                step--;
                sink.changeBitRate(false);
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
                && spikeSince < 0) { // nor while a spike is building up
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
     * Once a second: the video arriving, Mbit/s. Under RESCUE_BELOW_MBPS for RESCUE_MS, whatever the PC's count of the
     * steps says (2026-10-31: it said 4-6 Mbit/s while 0.5 arrived), the phone is lower than it should be: ask it up,
     * every RESCUE_MS (it ignores raises for 5 s after stepping down), and count it as at the floor from now on. A still
     * picture may also arrive under it; then a raise changes nothing worse than a steady picture.
     */
    public synchronized void onReceivedRate(double mbps, long now) {
        if (mbps >= RESCUE_BELOW_MBPS) {
            lowSince = -1;
            return;
        }
        if (lowSince < 0) lowSince = now;
        if (now - lowSince < RESCUE_MS || now - lastTrouble < RESCUE_MS) return; // congested: little is meant to arrive
        step = Math.min(step, FLOOR_STEP);
        if (now - lastRescue >= RESCUE_MS) {
            lastRescue = now;
            sink.changeBitRate(true);
        }
    }

    /** The phone's steps, bit/s. */
    public static final int[] STEPS_BPS = {250_000, 500_000, 1_000_000, 2_000_000, 4_000_000, 6_000_000};

    /**
     * The phone's own report of its bitrate setting (phone app 2.3.0-pc.13 on): the PC's count of the steps follows it,
     * instead of drifting when the phone stepped by itself.
     */
    public synchronized void onPhoneTarget(int bps) {
        if (bps <= 0) return;
        int s = 0;
        while (s < STEPS_BPS.length - 1 && STEPS_BPS[s + 1] <= bps) s++;
        step = s;
    }

    /** The step the PC thinks the phone is on: 0-5 for 0.25-6 Mbit/s (tests, and the link log). */
    public synchronized int step() {
        return step;
    }

    /** The link is congested now or was a moment ago: don't add key frames (see CONGESTED_FOR_MS). */
    public synchronized boolean isCongested(long now) {
        return now - lastCongested < CONGESTED_FOR_MS;
    }

    /** The usual ping: the lowest of the last 15-30 s (Integer.MAX_VALUE before the first). */
    private int baseline() {
        return Math.min(windowMinPing, previousMinPing);
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
