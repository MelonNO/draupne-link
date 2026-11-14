package de.droiddrone.pc.video;

import de.droiddrone.common.Fec;
import de.droiddrone.pc.ControlLink.Tier;

/**
 * How much Reed-Solomon parity ({@link Fec}) the phone adds to its video: parity packets per block for plain frames
 * and for key frames, from the packet loss measured over the last seconds. Pure: no clock of its own, no sockets; the
 * tests tick it with any time. {@link #onFrame} comes from the receive thread, {@link #tick} from whoever sends the
 * choice on (ControlLink's session thread, beside TierPolicy); {@link #choice} may be read from any thread.
 * <p>
 * None in the NORMAL and SHARP tiers (as the XOR parity before it: bad coverage mode and under only). In ROBUST and
 * SURVIVAL at least {@link #MIN_PLAIN} a block for plain frames - what the XOR group gave, one loss a frame mended -
 * and {@link #MIN_KEY} for key frames. More with the loss: the least parity with which a block of the size the frames
 * now have fails no more often than {@link #BLOCK_FAILS}, the losses taken as independent ({@link #parityFor}). For a
 * plain frame of 4 packets (1 Mbit/s at 30 frames a second) that is 1 up to 1.8 % of the packets lost, 2 up to
 * 5.5 %, 3 up to 10 %, then 4; for a key frame of 28 packets 2 up to 1 %, one more for about every 1.3 % after, 8
 * from 7.5 %. Never over {@link #MAX_PLAIN} / {@link #MAX_KEY}, and never more parity than data in a block
 * ({@link #MAX_SHARE}: a one-packet frame gets one).
 * <p>
 * Up at once, down slowly: more parity is given the tick the loss asks for it; less only after {@link #CALM_MS} in
 * which less was wanted all the time, and then one packet at a time - a loss that comes and goes every few seconds
 * holds the parity up instead of switching it. A loss that isn't known (no picture to count by: pass a negative)
 * changes nothing.
 * <p>
 * What it reads, all of it in VideoPipeline already: the tier (ControlLink.getTier, as isRobust there); each frame's
 * kind and size (accept); the loss - frameLossRatio (the share of the last 3 s of frames lost, damaged or rebuilt
 * from parity) turned into a packet loss by {@link #fragmentLoss} with the packets a frame has (megabitsPerSecond and
 * receivedFps give them), or the packets counted outright once the assembler counts them. Rebuilt frames count as
 * hit, so the parity doesn't hide the loss it is measured by.
 * <p>
 * The share ({@link Choice#share}): the parity's part of the video's upload with the frames as they now are. The
 * phone lowers its encoder by its own count of the same ({@link Fec#parityBytes} per frame over frame plus parity),
 * so the upload stays what the bitrate step says; it goes on reporting the step's own bitrate (record 0x40F2) - the
 * PC's count of the steps and TierPolicy's limits read that figure.
 * <p>
 * The wire, as planned: {@code VIDEO_PROFILE} bit 4 = this PC understands the parity; {@code VIDEO_FEC [0x54]
 * [mPlain u8][mKey u8]} PC to phone, unnumbered: at once when the choice changes (a few times, as a key frame
 * request) and once a second after, 0 0 outside the weak tiers. The phone answers with {@code VIDEO_PARITY_RS [0x55]}
 * packets (their layout: {@link Fec}); one that doesn't know 0x54 goes on with its XOR groups.
 */
public final class FecPolicy {
    /** The most parity packets a block of a plain frame and of a key frame is given. */
    public static final int MAX_PLAIN = 4, MAX_KEY = Fec.MAX_PARITY;
    /** The least in the weak tiers. */
    static final int MIN_PLAIN = 1, MIN_KEY = 2;
    /**
     * How often a block may fail with the loss as measured: 1 in 300 - at 30 frames a second a damaged frame in ten
     * seconds. Real losses come in runs, which this sum takes for less than they are; the target is strict for it.
     */
    static final double BLOCK_FAILS = 1.0 / 300;
    /** The parity is never more than this part of a block's packets (in bytes a little over: it is as long as the block's longest). */
    static final double MAX_SHARE = 0.5;
    /** Less parity only after this long with less wanted, and one packet less each time. */
    static final long CALM_MS = 10_000;
    /** Before the first frames: a plain frame at 1 Mbit/s and 30 frames a second, a key frame, and how many plain frames to one. */
    static final int FIRST_PLAIN_BYTES = 4000, FIRST_KEY_BYTES = 30_000, FIRST_PLAIN_PER_KEY = 60;

    /**
     * The parity packets a block for plain frames and for key frames, and the part of the video's upload the parity
     * then is (0-1; the encoder's bitrate comes down by it).
     */
    public record Choice(int mPlain, int mKey, double share) {
        /** No RS parity: the XOR groups and key frames twice in the weak tiers, as before. */
        public static final Choice NONE = new Choice(0, 0, 0);

        /** The same parity as before: nothing new for the phone (the share moves with the frames' sizes). */
        public boolean sameParity(Choice other) {
            return other != null && mPlain == other.mPlain && mKey == other.mKey;
        }
    }

    /** One of the two figures: up at once, down a packet at a time after CALM_MS of less wanted. */
    private static final class Held {
        int m;
        long lessSince = -1;

        void want(long now, int wanted) {
            if (wanted >= m) {
                m = wanted;
                lessSince = -1;
            } else if (lessSince < 0) {
                lessSince = now;
            } else if (now - lessSince >= CALM_MS) {
                m--;
                lessSince = m > wanted ? now : -1;
            }
        }

        void clear() {
            m = 0;
            lessSince = -1;
        }
    }

    private final Held plain = new Held(), key = new Held();
    /** The frames' sizes as they now come (smoothed; 0 before the first) and the plain frames to a key frame. */
    private double plainBytes, keyBytes, plainPerKey;
    private int plainSinceKey;
    private volatile Choice choice = Choice.NONE;

    /** A frame came (whole or not): its kind and size, for the blocks' size and the share. */
    public synchronized void onFrame(boolean keyFrame, int bytes) {
        if (bytes <= 0) return;
        if (keyFrame) {
            keyBytes = keyBytes == 0 ? bytes : keyBytes * 0.7 + bytes * 0.3;
            if (plainSinceKey > 0) plainPerKey = plainPerKey == 0 ? plainSinceKey : plainPerKey * 0.7 + plainSinceKey * 0.3;
            plainSinceKey = 0;
        } else {
            plainBytes = plainBytes == 0 ? bytes : plainBytes * 0.9 + bytes * 0.1;
            plainSinceKey++;
        }
    }

    /**
     * One tick: the parity for the tier and the loss now.
     * @param fragmentLoss the part of the video's packets lost over the last seconds, 0-1; negative or NaN: not known
     */
    public synchronized Choice tick(long now, Tier tier, double fragmentLoss) {
        if (tier.ordinal() < Tier.ROBUST.ordinal()) {
            plain.clear();
            key.clear();
            return choice = Choice.NONE;
        }
        int plainSize = plainBytes > 0 ? (int) Math.round(plainBytes) : FIRST_PLAIN_BYTES;
        int keySize = keyBytes > 0 ? (int) Math.round(keyBytes) : FIRST_KEY_BYTES;
        boolean known = fragmentLoss >= 0; // false for NaN too
        // not known: what is held stays, but a tier just entered starts with its least
        plain.want(now, known ? parityFor(firstBlock(plainSize), fragmentLoss, MIN_PLAIN, MAX_PLAIN) : Math.max(plain.m, MIN_PLAIN));
        key.want(now, known ? parityFor(firstBlock(keySize), fragmentLoss, MIN_KEY, MAX_KEY) : Math.max(key.m, MIN_KEY));
        return choice = new Choice(plain.m, key.m, share(plainSize, plain.m, keySize, key.m, plainPerKey > 0 ? plainPerKey : FIRST_PLAIN_PER_KEY));
    }

    /** The last tick's choice. */
    public Choice choice() {
        return choice;
    }

    /** A new session (or the stream started again): the sizes are learned anew, the parity starts at the tier's least. */
    public synchronized void reset() {
        plain.clear();
        key.clear();
        plainBytes = keyBytes = plainPerKey = 0;
        plainSinceKey = 0;
        choice = Choice.NONE;
    }

    /** The packets in a frame's first block (every block of a frame is that size or one less). */
    private static int firstBlock(int frameBytes) {
        return Math.max(1, Fec.blockK(Fec.fragments(frameBytes), 0));
    }

    /**
     * The least parity, from least to most, with which a block of k packets fails no more often than BLOCK_FAILS at
     * that loss - and never more parity than MAX_SHARE of the block (but one: a block of one packet gets its copy).
     */
    static int parityFor(int k, double loss, int least, int most) {
        int cap = Math.min(most, Math.max(1, (int) Math.floor(k * MAX_SHARE / (1 - MAX_SHARE))));
        int m = Math.min(least, cap);
        while (m < cap && moreLost(k + m, m, loss) > BLOCK_FAILS) m++;
        return m;
    }

    /** The chance that more than m of n packets are lost, each lost with chance p on its own. */
    static double moreLost(int n, int m, double p) {
        if (p <= 0) return 0;
        if (p >= 1) return m < n ? 1 : 0;
        double q = 1 - p, term = Math.pow(q, n), sum = term; // none lost; then 1, 2, ... m lost
        for (int i = 1; i <= m; i++) {
            term *= (double) (n - i + 1) / i * p / q;
            sum += term;
        }
        return Math.max(0, 1 - sum);
    }

    /**
     * The parity's part of the upload: per key frame and the plain frames that follow it, the parity bytes over frame
     * and parity bytes (packet headers left out on both sides).
     */
    static double share(int plainSize, int mPlain, int keySize, int mKey, double plainPerKey) {
        double parity = Fec.parityBytes(keySize, mKey) + plainPerKey * Fec.parityBytes(plainSize, mPlain);
        double data = keySize + plainPerKey * plainSize;
        return parity <= 0 ? 0 : parity / (data + parity);
    }

    /**
     * The packet loss behind a frame loss: with each packet lost on its own with chance p, a frame of n packets is
     * hit with 1 - (1 - p)^n - so p from the share of frames hit (VideoPipeline.frameLossRatio) and the packets a
     * frame has on average. Losses in runs hit fewer frames than this takes them to: the figure is the least the loss
     * can be.
     */
    public static double fragmentLoss(double frameLoss, double fragmentsPerFrame) {
        if (!(frameLoss > 0)) return 0;
        if (frameLoss >= 1) return 1;
        return 1 - Math.pow(1 - frameLoss, 1 / Math.max(1, fragmentsPerFrame));
    }
}
