package de.droiddrone.pc.fleet;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A virtual drone's 4G: decides for each datagram, both ways, whether it is lost and how late it arrives - so the
 * PC's real link, video and failsafe paths can be tried against a bad network on one PC. Pure but for its random
 * numbers (seeded in tests): the caller does the waiting ({@link ImpairedSocket}).
 * <ul>
 * <li>Weak link: a share of the packets lost at random, a jitter (each packet up to that much later, so they also
 * overtake each other), a fixed extra delay, and bunching - packets held and let go together every so many ms, as a
 * 4G uplink does.</li>
 * <li>4G loss: nothing at all either way for a while.</li>
 * <li>Handover (a cell change): the phone's packets held for a moment and then let go at once, what is sent to it in
 * that moment lost, and a slower path for a while after.</li>
 * </ul>
 */
public final class LinkImpairment {
    /** After a handover the path is this much slower for {@link #HANDOVER_SLOW_MS}. */
    static final int HANDOVER_EXTRA_MS = 80;
    static final long HANDOVER_SLOW_MS = 15_000;

    private final Random random;
    private volatile double lossPercent;
    private volatile int jitterMs, delayMs, bunchMs;
    private volatile long silentUntil, holdUntil, slowUntil;
    private final AtomicLong lost = new AtomicLong(), delayed = new AtomicLong();

    public LinkImpairment() {
        this(new Random());
    }

    LinkImpairment(Random random) {
        this.random = random;
    }

    /** The weak link's figures: loss 0-100 %, jitter, extra one-way delay and bunching period in ms (0 = none). */
    public void set(double lossPercent, int jitterMs, int delayMs, int bunchMs) {
        this.lossPercent = Math.max(0, Math.min(100, lossPercent));
        this.jitterMs = Math.max(0, jitterMs);
        this.delayMs = Math.max(0, delayMs);
        this.bunchMs = Math.max(0, bunchMs);
    }

    /** 4G gone for this long from now: nothing either way. */
    public void silence(long nowMs, long forMs) {
        silentUntil = nowMs + Math.max(0, forMs);
    }

    /** A cell change now: the phone's packets held for holdMs, then let go together; a slower path after. */
    public void handover(long nowMs, long holdMs) {
        holdUntil = nowMs + Math.max(0, holdMs);
        slowUntil = holdUntil + HANDOVER_SLOW_MS;
    }

    /** Everything back to a perfect link. */
    public void clear() {
        set(0, 0, 0, 0);
        silentUntil = 0;
        holdUntil = 0;
        slowUntil = 0;
    }

    /**
     * One datagram's fate.
     * @param fromPhone the phone sends it (uplink), else it is on its way to the phone
     * @return how many ms later it arrives (0 = now), or -1: lost
     */
    public long decide(boolean fromPhone, long nowMs) {
        if (nowMs < silentUntil) return lostOne();
        long delay = 0;
        if (nowMs < holdUntil) {
            if (!fromPhone) return lostOne();
            delay = holdUntil - nowMs; // held in the modem, let go at once when the new cell takes over
        }
        double loss = lossPercent;
        if (loss > 0 && random.nextDouble() * 100 < loss) return lostOne();
        delay += delayMs;
        if (nowMs < slowUntil) delay += HANDOVER_EXTRA_MS;
        int jitter = jitterMs;
        if (jitter > 0) delay += random.nextInt(jitter + 1);
        int bunch = bunchMs;
        if (bunch > 0) {
            long at = nowMs + delay;
            delay += (bunch - Math.floorMod(at, bunch)) % bunch; // to the next release
        }
        if (delay > 0) delayed.incrementAndGet();
        return delay;
    }

    private long lostOne() {
        lost.incrementAndGet();
        return -1;
    }

    /** Anything set that could lose or delay a packet now. */
    public boolean active(long nowMs) {
        return lossPercent > 0 || jitterMs > 0 || delayMs > 0 || bunchMs > 0 || nowMs < silentUntil || nowMs < slowUntil;
    }

    public boolean silent(long nowMs) {
        return nowMs < silentUntil;
    }

    public long silentForMs(long nowMs) {
        return Math.max(0, silentUntil - nowMs);
    }

    public double lossPercent() {
        return lossPercent;
    }

    public int jitterMs() {
        return jitterMs;
    }

    public int delayMs() {
        return delayMs;
    }

    public int bunchMs() {
        return bunchMs;
    }

    public long lostCount() {
        return lost.get();
    }

    public long delayedCount() {
        return delayed.get();
    }

    /** The link quality Betaflight would report (0-1023, MSP_ANALOG's RSSI over MSP): falls with the loss. */
    public int rssi(long nowMs) {
        if (silent(nowMs)) return 0;
        return (int) Math.round(1023 * Math.max(0, 1 - lossPercent / 100.0));
    }

    /** The phone's 4G signal for DD_NETWORK_STATE, 0-100 %. */
    public int signalPercent(long nowMs) {
        if (silent(nowMs)) return 0;
        int s = 85 - (int) Math.round(lossPercent * 1.5) - jitterMs / 20;
        return Math.max(5, Math.min(100, s));
    }
}
