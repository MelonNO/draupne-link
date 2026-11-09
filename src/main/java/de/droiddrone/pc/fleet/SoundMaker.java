package de.droiddrone.pc.fleet;

import java.util.Random;

/**
 * A virtual phone's microphone, made to be pleasant to listen to while testing (the pilot asked for it cuter): a soft
 * breeze all the time, now and then a little bird's chirp, and now and then the drone "speaks" - its motors beeping as
 * Betaflight makes them beep through the ESCs (1.0.52, the pilot: "the motor betaflight buzzer sound"; it was a
 * critter's babble), well over the background, so OBSERVATION's speaking highlight has something to light up for;
 * and while its Betaflight beeps (BEEPER held, RX_LOST, the landing failsafe) those beeps in Betaflight's rhythm. The motors are a real FPV quad (1.0.52, the pilot: "use a real sound"): at the arming its motors
 * spinning up, then its flight, faster and louder with the throttle - heard until DRAUPNE Link mutes the armed drone
 * (a moment after the arming). 48 kHz mono, frames of 1024 samples.
 */
final class SoundMaker {
    private final Random random;
    private double wind, windSlow;
    private long sample;
    private volatile boolean voices = true;
    /** No bird and no critter (the finale's drones: only the breeze, the motors and what they hear). */
    private volatile boolean quiet;
    /** No beeping through the motors - no tune, no Betaflight beeps (the tour's showcase: the pilot, 2026-11-09). */
    private volatile boolean beeperless;
    /** The motors: the spin-up once at each arming, then the flight's loop. */
    private final SoundSample.Voice spinUp = new SoundSample.Voice(SoundSample.of("fpv-spinup"), false);
    private final SoundSample.Voice flight = new SoundSample.Voice(SoundSample.of("fpv-flight"), true);
    private boolean wasArmed;
    private double motorLevel;

    /** The "speaking": when the next motor tune starts, and the notes being played {start, end, Hz} (samples). */
    private long nextVoiceAt;
    private final java.util.ArrayDeque<long[]> notes = new java.util.ArrayDeque<>();
    /** Each motor's phase: four motors a little apart in pitch, so the tone beats as a real quad's does. */
    private final double[] motorPhase = new double[4];
    private static final double[] MOTOR_DETUNE = {1.0, 1.004, 0.997, 1.007};
    /** Betaflight's own beeping: the pattern's start (sample) and which one. */
    private String beepingNow;
    private long beepingFrom;

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

    /** Only the breeze and the motors (the finale's drones). */
    void setQuiet(boolean on) {
        quiet = on;
    }

    /** No motor beeps; the breeze, the birds and the motors stay (the tour's showcase drones). */
    void setBeeperless(boolean on) {
        beeperless = on;
    }

    /** A voice now (the panel's SPEAK). */
    void speakNow() {
        nextVoiceAt = sample;
    }

    /** The next frame, Betaflight not beeping. */
    float[] next(double throttle) {
        return next(throttle, null);
    }

    /**
     * The next frame.
     * @param throttle 0..1 while armed, -1 disarmed (motors off)
     * @param beeping  what Betaflight beeps for now (FlightModel.beeping: BEEPER, RX_LOST, RX_LOST_LANDING), or null
     */
    float[] next(double throttle, String beeping) {
        if (beeping == null ? beepingNow != null : !beeping.equals(beepingNow)) {
            beepingNow = beeping;
            beepingFrom = sample;
        }
        float[] out = new float[AacEncoder.FRAME];
        int rate = AacEncoder.SAMPLE_RATE;
        for (int i = 0; i < out.length; i++, sample++) {
            // a soft breeze: noise through two low-passes, its level drifting slowly
            windSlow += (random.nextGaussian() * 0.02 - windSlow) * 0.0005;
            wind += (random.nextGaussian() - wind) * 0.02;
            double v = wind * (0.006 + Math.abs(windSlow) * 0.15);
            v += motors(throttle);
            if (!quiet) v += bird(rate);
            if (!quiet && !beeperless) v += voice(rate);
            if (!beeperless) v += betaflightBeeps(rate);
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

    /**
     * The drone "speaking" now and then: its ESCs' start-up tune through the motors - three rising notes, a breath, then
     * one or two high beeps - as a quad plays when its battery goes in.
     */
    private double voice(int rate) {
        if (voices && sample >= nextVoiceAt && notes.isEmpty()) {
            nextVoiceAt = sample + (long) rate * (9 + random.nextInt(9));
            long t = sample;
            double base = 1500 + random.nextInt(250);
            for (double step : new double[]{1.0, 1.26, 1.5}) {
                long end = t + rate * 140L / 1000;
                notes.add(new long[]{t, end, Math.round(base * step)});
                t = end + rate * 30L / 1000;
            }
            t += rate * 250L / 1000;
            int beeps = 1 + random.nextInt(2);
            for (int b = 0; b < beeps; b++) {
                long end = t + rate * 110L / 1000;
                notes.add(new long[]{t, end, Math.round(base * 1.68)});
                t = end + rate * 130L / 1000;
            }
        }
        while (!notes.isEmpty() && sample >= notes.peekFirst()[1]) notes.pollFirst();
        long[] n = notes.peekFirst();
        if (n == null || sample < n[0]) return 0;
        return 0.32 * motorTone(n[2], sample - n[0], n[1] - n[0], rate);
    }

    /**
     * Betaflight's beeps through the motors while it beeps for something: BEEPER (held) a steady quick beat, RX_LOST
     * half a second on and off, the landing failsafe fast short beeps.
     */
    private double betaflightBeeps(int rate) {
        String b = beepingNow;
        if (b == null) return 0;
        long onMs, offMs;
        double hz;
        switch (b) {
            case "RX_LOST" -> { onMs = 500; offMs = 500; hz = 1900; }
            case "RX_LOST_LANDING" -> { onMs = 100; offMs = 100; hz = 2200; }
            default -> { onMs = 250; offMs = 250; hz = 2400; } // BEEPER
        }
        long period = rate * (onMs + offMs) / 1000, on = rate * onMs / 1000;
        long at = Math.floorMod(sample - beepingFrom, period);
        if (at >= on) return 0;
        return 0.36 * motorTone(hz, at, on, rate);
    }

    /**
     * A tone as an ESC plays it through a motor: a square-ish wave (its first odd harmonics), each of the four motors a
     * little apart so they beat, a slight rattle of the bell at the coil's drive, and soft 3 ms edges.
     */
    private double motorTone(double hz, long at, long length, int rate) {
        double v = 0;
        for (int m = 0; m < motorPhase.length; m++) {
            motorPhase[m] += 2 * Math.PI * hz * MOTOR_DETUNE[m] / rate;
            double p = motorPhase[m];
            v += Math.sin(p) + Math.sin(3 * p) / 3 * 0.8 + Math.sin(5 * p) / 5 * 0.5;
        }
        v /= motorPhase.length;
        double rattle = 1 + 0.25 * Math.sin(2 * Math.PI * 48 * at / rate);
        long edge = rate * 3L / 1000;
        double env = Math.min(1, Math.min((double) at / edge, (double) (length - at) / edge));
        return env * rattle * v;
    }

}
