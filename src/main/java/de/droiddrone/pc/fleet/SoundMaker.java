package de.droiddrone.pc.fleet;

import java.util.Random;

/**
 * A virtual phone's microphone: wind (filtered noise) all the time, the motors' hum and buzz while armed (louder and
 * higher with the throttle), and now and then a "voice" - a second or so of a voice-like sound well over the background,
 * so OBSERVATION's speaking highlight has something to light up for. 48 kHz mono, frames of 1024 samples.
 */
final class SoundMaker {
    private final Random random;
    private double wind, windSlow, phase, phase2, voicePhase;
    private long sample;
    private long nextVoiceAt, voiceUntil;
    private volatile boolean voices = true;

    SoundMaker(long seed) {
        random = new Random(seed);
        nextVoiceAt = AacEncoder.SAMPLE_RATE * (6 + random.nextInt(8));
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
            // wind: noise through two low-passes, its level drifting
            windSlow += (random.nextGaussian() * 0.02 - windSlow) * 0.0005;
            wind += (random.nextGaussian() - wind) * 0.04;
            double v = wind * (0.012 + Math.abs(windSlow) * 0.3);
            if (throttle >= 0) { // motors: a hum at the props' rate and its buzz
                double f = 140 + 360 * throttle;
                phase += 2 * Math.PI * f / rate;
                phase2 += 2 * Math.PI * f * 2.02 / rate;
                double amp = 0.05 + 0.25 * throttle;
                v += amp * (Math.sin(phase) * 0.6 + Math.sin(phase2) * 0.25 + (random.nextDouble() - 0.5) * 0.3);
            }
            if (voices && sample >= nextVoiceAt) {
                voiceUntil = sample + rate * (8 + random.nextInt(10)) / 10;
                nextVoiceAt = sample + (long) rate * (9 + random.nextInt(9));
            }
            if (sample < voiceUntil) { // a voice: a buzzing vowel at about 130 Hz, syllables 5 a second
                voicePhase += 2 * Math.PI * (125 + 15 * Math.sin(sample * 2 * Math.PI * 0.7 / rate)) / rate;
                double syllable = 0.5 + 0.5 * Math.sin(sample * 2 * Math.PI * 5.0 / rate);
                double vowel = Math.sin(voicePhase) + 0.6 * Math.sin(voicePhase * 3) + 0.4 * Math.sin(voicePhase * 5) + 0.2 * Math.sin(voicePhase * 7);
                v += 0.12 * syllable * vowel;
            }
            out[i] = (float) Math.max(-1, Math.min(1, v));
        }
        return out;
    }
}
