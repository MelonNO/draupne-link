package de.droiddrone.pc.fleet;

import java.util.Random;

/**
 * The tour's BTR as the virtual drone's microphone hears it, in stereo: a V8 diesel's firing (a pulse train at about
 * 110 Hz with uneven cylinders, each firing a short noise burst), the tyres' roar on the ground and a faint turbo
 * whine. Where it is decides how it sounds: nearer is louder (one over the distance) and brighter (air takes the highs
 * with distance), its bearing from the drone's nose pans it between left and right (equal power) and muffles it from
 * behind, and its speed towards the drone shifts the pitch (Doppler). 48 kHz, frames of {@link AacEncoder#FRAME}.
 */
final class BtrSound {
    private static final double C = 343; // speed of sound, m/s
    private final Random random;
    private final int rate = AacEncoder.SAMPLE_RATE;
    private double phase, roar, roarSlow, lpL, lpR, burst, whine;
    private int cylinder;
    private final double[] cylinderGain = new double[8];
    /** The gains and filter of the frame before: each frame glides from them to its own (no zipper noise). */
    private double gL, gR, cut = 0.5;

    BtrSound(long seed) {
        random = new Random(seed);
        for (int i = 0; i < cylinderGain.length; i++) cylinderGain[i] = 0.75 + random.nextDouble() * 0.5;
    }

    /**
     * Adds one frame of the vehicle to left and right.
     * @param distM      how far it is
     * @param bearingRad its direction from the drone's nose, right positive (-pi..pi)
     * @param towardsMs  its speed towards the drone (negative: going away)
     * @param speedMs    how fast it drives (the tyres, the revs)
     */
    void add(float[] left, float[] right, double distM, double bearingRad, double towardsMs, double speedMs) {
        double dist = Math.max(2, distM);
        double level = Math.min(1.4, 14 / dist) * 0.32;
        double pan = Math.sin(bearingRad); // -1 left .. 1 right
        double behind = Math.cos(bearingRad) < 0 ? 0.65 + 0.35 * (1 + Math.cos(bearingRad)) : 1;
        double angle = (pan + 1) * Math.PI / 4;
        double toL = level * behind * Math.cos(angle), toR = level * behind * Math.sin(angle);
        // the air (and the head-shadow from behind): a one-pole low-pass whose corner falls with distance
        double corner = (300 + 7000 * Math.exp(-dist / 90)) * (behind < 1 ? 0.6 : 1);
        double newCut = 1 - Math.exp(-2 * Math.PI * corner / rate);
        double doppler = C / Math.max(C * 0.5, C - towardsMs);
        double rpm = 1500 + speedMs * 70;
        double fire = rpm / 60 * 4 * doppler; // a V8 fires four times a turn
        int n = left.length;
        for (int i = 0; i < n; i++) {
            double k = (double) i / n;
            double l = gL + (toL - gL) * k, r = gR + (toR - gR) * k, a = cut + (newCut - cut) * k;
            // the firing: a pulse each cycle, uneven by cylinder, with a short burst of noise
            double before = phase;
            phase += fire / rate;
            if (Math.floor(phase) != Math.floor(before)) {
                cylinder = (cylinder + 1) % cylinderGain.length;
                burst = cylinderGain[cylinder];
            }
            double frac = phase - Math.floor(phase);
            double pulse = Math.exp(-frac * 7) * burst * 0.9;
            burst *= 0.9993;
            double engine = pulse * (0.6 + 0.4 * random.nextGaussian() * 0.5) + Math.sin(2 * Math.PI * phase / 2) * 0.25 * burst;
            // the tyres: rumbling noise, louder with speed
            roarSlow += (random.nextGaussian() - roarSlow) * 0.002;
            roar += (random.nextGaussian() - roar) * 0.04;
            double tyres = (roar * 0.5 + roarSlow * 2) * (0.15 + speedMs * 0.05);
            whine += 2 * Math.PI * 2900 * doppler / rate;
            double s = engine + tyres + Math.sin(whine) * 0.02;
            lpL += (s - lpL) * a;
            lpR += (s - lpR) * a;
            left[i] = clamp(left[i] + (float) (lpL * l));
            right[i] = clamp(right[i] + (float) (lpR * r));
        }
        gL = toL;
        gR = toR;
        cut = newCut;
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}
