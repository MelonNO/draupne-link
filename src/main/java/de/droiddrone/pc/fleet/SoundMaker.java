package de.droiddrone.pc.fleet;

import java.util.Random;

/**
 * A virtual phone's microphone, made to be pleasant to listen to while testing (the pilot asked for it cuter): a soft
 * breeze all the time, now and then a little bird's chirp, the motors' round hum while armed (higher with the
 * throttle), and now and then a "voice" - a small critter babbling in short gliding bip-boop syllables, well over the
 * background, so OBSERVATION's speaking highlight has something to light up for. 48 kHz mono, frames of 1024 samples.
 */
final class SoundMaker {
    private final Random random;
    private double wind, windSlow, phase, phase2;
    private long sample;
    private volatile boolean voices = true;

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
            if (throttle >= 0) { // motors: a round hum at the props' rate with a gentle octave, no rasp
                double f = 140 + 360 * throttle;
                phase += 2 * Math.PI * f / rate;
                phase2 += 2 * Math.PI * f * 2 / rate;
                double amp = 0.04 + 0.16 * throttle;
                v += amp * (Math.sin(phase) * 0.75 + Math.sin(phase2) * 0.2);
            }
            v += bird(rate);
            v += voice(rate);
            out[i] = (float) Math.max(-1, Math.min(1, v));
        }
        return out;
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
