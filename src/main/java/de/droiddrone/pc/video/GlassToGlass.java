package de.droiddrone.pc.video;

import de.droiddrone.common.UdpSender;

import java.util.Arrays;

/**
 * The pilot's glass-to-glass time, estimated for every picture shown: from the camera's exposure to the picture on
 * this PC's screen, without a code filmed off the screen (LatencyMeter measures; this runs all flight). Pure: no
 * clock of its own, no sockets; the tests hand it any times. Three parts:
 * <ul>
 * <li>capture to send - the phone's own figure for the frame (Common's CaptureClock);</li>
 * <li>the network time of the frame's first packet - estimated here, see below;</li>
 * <li>arrival to shown - this PC: the rest of the frame's packets, the decoder, the drawing. The screen's own delay
 * (a refresh, the panel) is not in it.</li>
 * </ul>
 * The network time. The two clocks are never compared: the phone echoes a Ping instead. The PC stamps a Ping with
 * its send time s; it reaches the phone after d. The phone holds it for h - the time until it sends the frame's first
 * packet, on its own clock, a difference only - and that packet takes u to arrive, at a on the PC's clock:
 * <pre>
 *   a = s + d + h + u      so      r = a - s - h = d + u
 * </pre>
 * r is a round trip made of that Ping's way there and this frame's way back. Wanted is u; r is all there is, so the
 * way there is taken as half of the link at its best: with r0 the lowest r of the last two {@link #WINDOW_MS},
 * <pre>
 *   network = r - r0 / 2
 * </pre>
 * How wrong that is: let the link's best be D there and U back (r0 = D + U), and this Ping and this frame late over
 * that by qd and qu (d = D + qd, u = U + qu). Then network = (D + U) / 2 + qd + qu against the true U + qu:
 * <pre>
 *   network - u = (D - U) / 2 + qd
 * </pre>
 * - half the difference between the two ways at the link's best (long where the way to the phone is the slower,
 * short where the way back is; nothing measured from one end can tell them apart), and long by what this one Ping
 * waited on its way to the phone. The PC's packets are few and small, so qd is small beside the picture's own wait,
 * and the smoothed figure evens it out; what the frame itself waited (qu) is all in the figure, which is the point.
 * r0 is right only if the link was at its best once in the last 10 to 20 s: a queue that stood all that time is
 * half missing from the figure. And every stamp is a whole millisecond: a figure is good to 2 ms at best.
 * <p>
 * A figure is given once {@link #MIN_SAMPLES} round trips have come since the last {@link #reset}; none
 * ({@link #UNKNOWN}) before that, from a phone that sends no FRAME_TIME, or when the last picture with one is over
 * {@link #STALE_MS} old. A round trip that can't be - a frame here before its Ping left, or over
 * {@link #MAX_ROUND_MS} - is another clock's stamp (another PC's session): everything is forgotten.
 * <p>
 * The wire, as planned: {@code VIDEO_PROFILE} bit 3 (8) = frame times wanted; the phone then sends {@code FRAME_TIME
 * [0x53][frameNum i16][captureToSendMs u16][pingEcho u32][pingHoldMs u16]} right before each frame's first packet
 * (65535 in a u16 = not known; pingEcho the low 32 bits of the Ping's stamp). The receive thread hands it to
 * {@link #onFrameTime} with its arrival, the decode thread calls {@link #onShown} after presentFrame; the session
 * start calls {@link #reset}. Every PC time is read on {@link #clockMs} - the clock the Pings are stamped with, not
 * MonoClock.ms (that one starts at the wall clock: a constant apart, and every round trip would be nonsense).
 */
public final class GlassToGlass {
    public static final int UNKNOWN = -1;
    /** The link's best is the lowest round trip of this long, and of as long before it. */
    static final long WINDOW_MS = 10_000;
    /** Round trips before the first figure: fewer, and the lowest of them is too likely one that waited. */
    static final int MIN_SAMPLES = 10;
    /** No picture with a figure for this long: none shown any more. */
    static final long STALE_MS = 2000;
    /** A Ping held longer than this says the Pings no longer reach the phone: nothing is counted by it. */
    static final int MAX_HOLD_MS = 5000;
    /** A round trip over this, or a frame more than EARLY_MS (the stamps' rounding) ahead of its Ping: another clock. */
    static final int MAX_ROUND_MS = 30_000, EARLY_MS = 2;
    /** A frame not shown within this long of its arrival never gives a figure. */
    static final long MAX_SHOW_MS = 10_000;
    /** The frames between arrival and shown that are kept (by the low bits of their number). */
    private static final int PENDING = 64;
    private static final int NONE = Integer.MAX_VALUE;

    private int bestNow = NONE, bestBefore = NONE;
    private long bucketAt;
    private boolean bucketOpen;
    private int samples;
    private final short[] pendingNum = new short[PENDING];
    private final long[] pendingArrive = new long[PENDING];
    private final int[] pendingCapture = new int[PENDING], pendingNetwork = new int[PENDING];
    private final boolean[] pending = new boolean[PENDING];
    private int lastMs = UNKNOWN, lastCaptureMs = UNKNOWN, lastNetworkMs = UNKNOWN, lastPcMs = UNKNOWN, maxMs = UNKNOWN;
    private long lastAt;
    private double smoothed = UNKNOWN;

    /** The clock every time handed in is read on: the one the PC's Pings are stamped with, ms. */
    public static long clockMs() {
        return UdpSender.pingClockMs();
    }

    /**
     * A frame's FRAME_TIME came.
     * @param captureToSendMs the phone's figure, 65535 = it knows none
     * @param pingEcho        the stamp of the last Ping the phone had taken, its low 32 bits
     * @param pingHoldMs      how long the phone held that Ping before this, 65535 = it has taken none
     * @param arriveMs        when this packet came ({@link #clockMs})
     */
    public synchronized void onFrameTime(short frameNum, int captureToSendMs, long pingEcho, int pingHoldMs, long arriveMs) {
        if (pingHoldMs < 0 || pingHoldMs > MAX_HOLD_MS) return;
        int round = (int) (arriveMs - pingEcho) - pingHoldMs; // 32 bits: right across the stamps' wrap
        if (round < -EARLY_MS || round > MAX_ROUND_MS) {
            reset();
            return;
        }
        round = Math.max(0, round);
        best(round, arriveMs);
        samples++;
        int slot = frameNum & (PENDING - 1);
        pending[slot] = false;
        if (samples < MIN_SAMPLES || captureToSendMs < 0 || captureToSendMs >= 0xFFFF) return;
        pending[slot] = true;
        pendingNum[slot] = frameNum;
        pendingArrive[slot] = arriveMs;
        pendingCapture[slot] = captureToSendMs;
        pendingNetwork[slot] = round - Math.min(bestNow, bestBefore) / 2;
    }

    /**
     * A round trip measured another way (a Pong: ControlLink's ping): never under the link's best, so it may be the
     * lowest of its window. Not needed; it helps where every frame waits and a Pong for once does not.
     */
    public synchronized void onRoundTrip(int ms, long now) {
        if (ms >= 0 && ms <= MAX_ROUND_MS) best(ms, now);
    }

    private void best(int round, long now) {
        if (!bucketOpen) {
            bucketOpen = true;
            bucketAt = now;
        }
        long age = now - bucketAt;
        if (age >= WINDOW_MS) {
            bestBefore = age < 2 * WINDOW_MS ? bestNow : NONE;
            bestNow = NONE;
            bucketAt = now;
            if (bestBefore == NONE) samples = 0; // nothing for two windows: the link is learned anew
        }
        if (round < bestNow) bestNow = round;
    }

    /**
     * The frame is on the screen ({@link #clockMs}, right after presentFrame).
     * @return its glass-to-glass time, ms, or {@link #UNKNOWN}: no FRAME_TIME for it, or not enough round trips yet
     */
    public synchronized int onShown(short frameNum, long shownMs) {
        int slot = frameNum & (PENDING - 1);
        if (!pending[slot] || pendingNum[slot] != frameNum) return UNKNOWN;
        pending[slot] = false;
        long pc = shownMs - pendingArrive[slot];
        if (pc < 0 || pc > MAX_SHOW_MS) return UNKNOWN;
        int total = pendingCapture[slot] + pendingNetwork[slot] + (int) pc;
        smoothed = lastMs == UNKNOWN || shownMs - lastAt > STALE_MS ? total : smoothed * 0.9 + total * 0.1;
        lastMs = total;
        lastCaptureMs = pendingCapture[slot];
        lastNetworkMs = pendingNetwork[slot];
        lastPcMs = (int) pc;
        lastAt = shownMs;
        if (total > maxMs) maxMs = total;
        return total;
    }

    /** For the screen: the last figures evened out (each new one counts a tenth), or {@link #UNKNOWN}. */
    public synchronized int smoothedMs(long now) {
        return fresh(now) ? (int) Math.round(smoothed) : UNKNOWN;
    }

    /** The last picture's figure, or {@link #UNKNOWN}. */
    public synchronized int lastMs(long now) {
        return fresh(now) ? lastMs : UNKNOWN;
    }

    /** Its three parts (for the link log): the phone's capture to send, the network, this PC; {@link #UNKNOWN} each without a figure. */
    public synchronized int lastCaptureToSendMs(long now) {
        return fresh(now) ? lastCaptureMs : UNKNOWN;
    }

    public synchronized int lastNetworkMs(long now) {
        return fresh(now) ? lastNetworkMs : UNKNOWN;
    }

    public synchronized int lastPcMs(long now) {
        return fresh(now) ? lastPcMs : UNKNOWN;
    }

    private boolean fresh(long now) {
        return lastMs != UNKNOWN && now - lastAt <= STALE_MS;
    }

    /** For the link log, once a second: the highest figure since the last call, or {@link #UNKNOWN} for none. */
    public synchronized int takeMaxMs() {
        int max = maxMs;
        maxMs = UNKNOWN;
        return max;
    }

    /** The link's best round trip as the figure takes it (the lowest of the last 10 to 20 s), ms; {@link #UNKNOWN} before the first. */
    public synchronized int bestRoundTripMs() {
        int best = Math.min(bestNow, bestBefore);
        return best == NONE ? UNKNOWN : best;
    }

    /** A new session, or a stream from another phone: nothing known. */
    public synchronized void reset() {
        bestNow = bestBefore = NONE;
        bucketOpen = false;
        samples = 0;
        Arrays.fill(pending, false);
        lastMs = lastCaptureMs = lastNetworkMs = lastPcMs = maxMs = UNKNOWN;
        smoothed = UNKNOWN;
    }
}
