package de.droiddrone.pc.fleet;

/**
 * The tour's BTR as the virtual drone's microphone hears it, in stereo, from real recordings (1.0.52, the pilot: "use
 * the sound of a real armoured vehicle"): a military vehicle's diesel standing (btr-idle) and an armoured vehicle
 * pulling away (btr-drive), mixed by how fast it drives, both revving up with the speed. Where it is decides how it
 * sounds: nearer is louder (one over the distance) and brighter (air takes the highs with distance), its bearing from
 * the drone's nose pans it between left and right (equal power) and muffles it from behind, and its speed towards the
 * drone shifts the pitch (Doppler). 48 kHz, frames of {@link AacEncoder#FRAME}.
 */
final class BtrSound {
    private static final double C = 343; // speed of sound, m/s
    /** Its top speed for the mix: at this the driving recording alone. */
    private static final double FULL_DRIVE_MS = 7;
    private final int rate = AacEncoder.SAMPLE_RATE;
    private final SoundSample.Voice idle = new SoundSample.Voice(SoundSample.of("btr-idle"), true);
    private final SoundSample.Voice drive = new SoundSample.Voice(SoundSample.of("btr-drive"), true);
    private double lpL, lpR;
    /** The gains, mix and filter of the frame before: each frame glides from them to its own (no zipper noise). */
    private double gL, gR, cut = 0.5, mix;

    BtrSound(long seed) {
    }

    /**
     * Adds one frame of the vehicle to left and right.
     * @param distM      how far it is
     * @param bearingRad its direction from the drone's nose, right positive (-pi..pi)
     * @param towardsMs  its speed towards the drone (negative: going away)
     * @param speedMs    how fast it drives (the revs, the mix)
     */
    void add(float[] left, float[] right, double distM, double bearingRad, double towardsMs, double speedMs) {
        double dist = Math.max(3, distM);
        double level = Math.min(1.2, 30 / dist) * 0.55;
        double pan = Math.sin(bearingRad); // -1 left .. 1 right
        double behind = Math.cos(bearingRad) < 0 ? 0.65 + 0.35 * (1 + Math.cos(bearingRad)) : 1;
        double angle = (pan + 1) * Math.PI / 4;
        double toL = level * behind * Math.cos(angle), toR = level * behind * Math.sin(angle);
        // the air (and the head-shadow from behind): a one-pole low-pass whose corner falls with distance
        double corner = (500 + 7500 * Math.exp(-dist / 120)) * (behind < 1 ? 0.6 : 1);
        double newCut = 1 - Math.exp(-2 * Math.PI * corner / rate);
        double doppler = C / Math.max(C * 0.5, C - towardsMs);
        double newMix = Math.max(0, Math.min(1, speedMs / FULL_DRIVE_MS));
        double revs = (0.92 + 0.16 * newMix) * doppler;
        int n = left.length;
        for (int i = 0; i < n; i++) {
            double k = (double) i / n;
            double l = gL + (toL - gL) * k, r = gR + (toR - gR) * k, a = cut + (newCut - cut) * k, m = mix + (newMix - mix) * k;
            double s = idle.next(revs) * Math.cos(m * Math.PI / 2) + drive.next(revs) * Math.sin(m * Math.PI / 2);
            lpL += (s - lpL) * a;
            lpR += (s - lpR) * a;
            left[i] = clamp(left[i] + (float) (lpL * l));
            right[i] = clamp(right[i] + (float) (lpR * r));
        }
        gL = toL;
        gR = toR;
        cut = newCut;
        mix = newMix;
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}
