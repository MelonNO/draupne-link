package de.droiddrone.pc.fleet;

import java.util.Random;

/**
 * A virtual phone's microphone, made to be pleasant to listen to while testing (the pilot asked for it cuter): a soft
 * breeze all the time, now and then a little bird's chirp, and now and then a "voice" - a small critter babbling in
 * short gliding bip-boop syllables, well over the background, so OBSERVATION's speaking highlight has something to
 * light up for. The motors are a real FPV quad (1.0.52, the pilot: "use a real sound"): at the arming its motors
 * spinning up, then its flight, faster and louder with the throttle - heard until DRAUPNE Link mutes the armed drone
 * (a moment after the arming). 48 kHz mono, frames of 1024 samples.
 */
final class SoundMaker {
    private final Random random;
    private double wind, windSlow;
    private long sample;
    private volatile boolean voices = true;
    /** No bird and no critter (the tour's drones: only the breeze, the motors and what they hear). */
    private volatile boolean quiet;
    /** The motors: the spin-up once at each arming, then the flight's loop. */
    private final SoundSample.Voice spinUp = new SoundSample.Voice(SoundSample.of("fpv-spinup"), false);
    private final SoundSample.Voice flight = new SoundSample.Voice(SoundSample.of("fpv-flight"), true);
    private boolean wasArmed;
    private double motorLevel;

    /** The voice: when the next babble starts, when it ends, and the syllable being said. */
    private long nextVoiceAt, voiceUntil;
    private long syllableStart, syllableEnd, nextSyllableAt;
    private double syllableFrom, syllableTo, voicePhase;

    /** The bird: when it chirps next, and the chirp being sung (a fast rising whistle, two or three in a row). */
    private long nextBirdAt, chirpStart, chirpEnd;
    private int chirpsLeft;
    private double chirpFrom, birdPhase;

    SoundMaker(long seed) {
        random = new Random(seed);
        nextVoiceAt = AacEncoder.SAMPLE_RATE * (6 + random.nextInt(8));
        nextBirdAt = AacEncoder.SAMPLE_RATE * (2 + random.nextInt(5));
    }

    /** The occasional voice on or off (the panel). */
    void setVoices(boolean on) {
        voices = on;
    }

    /** Only the breeze and the motors (the tour's drones). */
    void setQuiet(boolean on) {
        quiet = on;
    }

    /** A voice now (the panel's SPEAK). */
    void speakNow() {
        nextVoiceAt = sample;
    }

    /**
     * The next frame.
     * @param throttle 0..1 while armed, -1 disarmed (motors off)
     */
    float[] next(double throttle) {
        float[] out = new float[AacEncoder.FRAME];
        int rate = AacEncoder.SAMPLE_RATE;
        for (int i = 0; i < out.length; i++, sample++) {
            // a soft breeze: noise through two low-passes, its level drifting slowly
            windSlow += (random.nextGaussian() * 0.02 - windSlow) * 0.0005;
            wind += (random.nextGaussian() - wind) * 0.02;
            double v = wind * (0.006 + Math.abs(windSlow) * 0.15);
            v += motors(throttle);
            if (!quiet) {
                v += bird(rate);
                v += voice(rate);
            }
            out[i] = (float) Math.max(-1, Math.min(1, v));
        }
        return out;
    }

    /** The real quad: its spin-up at the arming, then its flight sped up and louder with the throttle. */
    private double motors(double throttle) {
        boolean armed = throttle >= 0;
        if (armed && !wasArmed) spinUp.restart();
        wasArmed = armed;
        double target = armed ? 0.35 + 0.65 * throttle : 0;
        motorLevel += (target - motorLevel) * 0.0005; // glides, no clicks
        double v = 0;
        if (armed && !spinUp.done()) v += 0.9 * spinUp.next(1);
        // the flight comes in under the spin-up, as the motors settle
        if (motorLevel > 0.001) v += motorLevel * 0.8 * flight.next(0.85 + 0.35 * Math.max(0, throttle));
        return v;
    }

    /** A little bird, quietly: two or three quick rising chirps, every few seconds. */
    private double bird(int rate) {
        if (sample >= nextBirdAt && chirpsLeft == 0 && sample >= chirpEnd) {
            chirpsLeft = 2 + random.nextInt(2);
            nextBirdAt = sample + (long) rate * (4 + random.nextInt(8));
        }
        if (chirpsLeft > 0 && sample >= chirpEnd + rate / 20) { // the next chirp after a 50 ms breath
            chirpStart = sample;
            chirpEnd = sample + rate * (60 + random.nextInt(50)) / 1000;
            chirpFrom = 2600 + random.nextInt(900);
            chirpsLeft--;
        }
        if (sample >= chirpEnd) return 0;
        double t = (double) (sample - chirpStart) / (chirpEnd - chirpStart);
        birdPhase += 2 * Math.PI * chirpFrom * (1 + 0.45 * t) / rate; // a rising whistle
        double env = Math.sin(Math.PI * t);
        return 0.035 * env * env * Math.sin(birdPhase);
    }

    /** The critter's babble: syllables of 80-170 ms, each a pure tone gliding up or down, with soft edges. */
    private double voice(int rate) {
        if (voices && sample >= nextVoiceAt) {
            voiceUntil = sample + rate * (8 + random.nextInt(10)) / 10;
            nextVoiceAt = sample + (long) rate * (9 + random.nextInt(9));
            nextSyllableAt = sample;
        }
        if (sample >= voiceUntil && sample >= syllableEnd) return 0;
        if (sample >= nextSyllableAt && sample < voiceUntil && sample >= syllableEnd) {
            syllableStart = sample;
            syllableEnd = sample + rate * (80 + random.nextInt(90)) / 1000;
            nextSyllableAt = syllableEnd + rate * (25 + random.nextInt(60)) / 1000;
            syllableFrom = 520 + random.nextInt(420);
            double glide = random.nextBoolean() ? 1.35 + random.nextDouble() * 0.3 : 0.7 + random.nextDouble() * 0.1; // "bip?" or "boop"
            syllableTo = syllableFrom * glide;
        }
        if (sample >= syllableEnd) return 0;
        double t = (double) (sample - syllableStart) / (syllableEnd - syllableStart);
        double f = syllableFrom + (syllableTo - syllableFrom) * t * t;
        voicePhase += 2 * Math.PI * f / rate;
        double env = Math.sin(Math.PI * t);
        return 0.16 * env * (Math.sin(voicePhase) + 0.25 * Math.sin(voicePhase * 2));
    }
}
