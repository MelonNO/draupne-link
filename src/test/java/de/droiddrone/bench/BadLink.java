package de.droiddrone.bench;

import de.droiddrone.testing.Wire;

import java.util.List;
import java.util.Random;

/**
 * A bad network, by name: what a {@link Wire} between the PC and a phone does to each datagram. The figures are
 * seeded, so a run can be repeated packet for packet. Parts can be combined: a fixed delay each way, jitter (each
 * packet up to that much later - they overtake each other), loss at random, loss in bursts (a good and a bad state,
 * as coverage really is), doubled packets, a narrow band (what does not fit is queued, then dropped), and a cut -
 * nothing for a while, both ways or one way only.
 */
public final class BadLink implements Wire.Rule {
    public enum Way { BOTH, TO_PHONE, TO_PC }

    public final String name;
    private final Random random;
    private int delayMs, jitterMs;
    private double lossPercent, doubledPercent;
    /** Bursts: the chance per packet to fall into the bad state and to leave it, and the loss while in it. */
    private double intoBad, outOfBad, lossInBadPercent;
    private final boolean[] bad = new boolean[2];
    /** Narrow band, bits a second, each way; 0 = none. Up to queueMs of it waits, the rest is dropped. */
    private long bandBps;
    private int queueMs = 400;
    private final double[] bandFreeAtMs = new double[2];
    private volatile long cutFrom, cutUntil;
    private volatile Way cutWay = Way.BOTH;
    private volatile boolean off;
    private long lost, passed, doubled;

    public BadLink(String name, long seed) {
        this.name = name;
        this.random = new Random(seed);
    }

    public BadLink delay(int oneWayMs) {
        delayMs = oneWayMs;
        return this;
    }

    public BadLink jitter(int ms) {
        jitterMs = ms;
        return this;
    }

    public BadLink loss(double percent) {
        lossPercent = percent;
        return this;
    }

    /** Loss in bursts: mean lengths of the good and the bad stretches in packets, and the loss in the bad ones. */
    public BadLink bursts(double meanGoodPackets, double meanBadPackets, double lossInBad) {
        intoBad = 1 / meanGoodPackets;
        outOfBad = 1 / meanBadPackets;
        lossInBadPercent = lossInBad;
        return this;
    }

    public BadLink doubled(double percent) {
        doubledPercent = percent;
        return this;
    }

    public BadLink band(long bitsPerSecond, int queueMs) {
        bandBps = bitsPerSecond;
        this.queueMs = queueMs;
        return this;
    }

    /** Nothing passes that way from now for so long. */
    public void cut(long forMs, Way way) {
        cutWay = way;
        cutFrom = System.currentTimeMillis();
        cutUntil = cutFrom + forMs;
    }

    public boolean cutNow() {
        return System.currentTimeMillis() < cutUntil;
    }

    /** The network is good from now on (the fixed delay stays: it is the path, not a fault). */
    public void heal() {
        off = true;
        cutUntil = 0;
    }

    public synchronized String counts() {
        return "passed " + passed + ", lost " + lost + ", doubled " + doubled;
    }

    @Override
    public synchronized long decide(boolean toPhone, byte[] d, int len) {
        long now = System.currentTimeMillis();
        if (now < cutUntil && (cutWay == Way.BOTH || cutWay == Way.TO_PHONE == toPhone)) {
            lost++;
            return LOST;
        }
        if (off) {
            passed++;
            return delayMs;
        }
        int way = toPhone ? 0 : 1;
        if (intoBad > 0) {
            if (bad[way]) {
                if (random.nextDouble() < outOfBad) bad[way] = false;
            } else if (random.nextDouble() < intoBad) {
                bad[way] = true;
            }
            if (bad[way] && random.nextDouble() * 100 < lossInBadPercent) {
                lost++;
                return LOST;
            }
        }
        if (lossPercent > 0 && random.nextDouble() * 100 < lossPercent) {
            lost++;
            return LOST;
        }
        long hold = delayMs + (jitterMs > 0 ? random.nextInt(jitterMs + 1) : 0);
        if (bandBps > 0) {
            double ms = len * 8 * 1000.0 / bandBps; // how long this packet takes on the narrow band
            double start = Math.max(now, bandFreeAtMs[way]);
            if (start - now > queueMs) {
                lost++;
                return LOST; // the queue is full
            }
            bandFreeAtMs[way] = start + ms;
            hold += Math.round(start + ms - now);
        }
        passed++;
        if (doubledPercent > 0 && hold == 0 && random.nextDouble() * 100 < doubledPercent) {
            doubled++;
            return TWICE;
        }
        return hold;
    }

    // ------------------------------------------------------------------ the named ones

    public static BadLink clean(long seed) {
        return new BadLink("clean (30 ms each way)", seed).delay(30);
    }

    /** The steady kinds of bad network the table runs every scenario on. */
    public static List<BadLink> steady(long seed, boolean quick) {
        List<BadLink> all = List.of(
                clean(seed),
                new BadLink("4G good (35 ms, jitter 15, loss 0.5 %)", seed).delay(35).jitter(15).loss(0.5),
                new BadLink("one bar (60 ms, jitter 60, loss 8 %, bursts)", seed).delay(60).jitter(60).loss(8).bursts(200, 12, 70),
                new BadLink("loss 20 %", seed).delay(30).loss(20),
                new BadLink("loss 50 %", seed).delay(30).loss(50),
                new BadLink("bursts (80 % lost for ~15 packets, every ~100)", seed).delay(30).bursts(100, 15, 80),
                new BadLink("jitter 300 ms (wrong order)", seed).delay(30).jitter(300),
                new BadLink("slow (300 ms each way)", seed).delay(300),
                new BadLink("narrow band 300 kbit/s", seed).delay(30).band(300_000, 400),
                new BadLink("doubled packets 20 %", seed).doubled(20));
        return quick ? List.of(all.get(0), all.get(2), all.get(3)) : all;
    }
}
