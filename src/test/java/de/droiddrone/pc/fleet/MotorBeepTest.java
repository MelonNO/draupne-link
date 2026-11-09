package de.droiddrone.pc.fleet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The virtual drone "speaks" with its motors' beeps (1.0.52, the pilot), and beeps as its Betaflight does. */
class MotorBeepTest {
    /** Power at this frequency in the samples (Goertzel). */
    private static double power(float[] x, int from, int to, double hz) {
        double w = 2 * Math.PI * hz / AacEncoder.SAMPLE_RATE, c = 2 * Math.cos(w), s1 = 0, s2 = 0;
        for (int i = from; i < to; i++) {
            double s = x[i] + c * s1 - s2;
            s2 = s1;
            s1 = s;
        }
        return (s1 * s1 + s2 * s2 - c * s1 * s2) / Math.max(1, to - from);
    }

    private static float[] seconds(SoundMaker m, double s, String beeping) {
        int frames = (int) Math.ceil(s * AacEncoder.SAMPLE_RATE / AacEncoder.FRAME);
        float[] out = new float[frames * AacEncoder.FRAME];
        for (int f = 0; f < frames; f++) System.arraycopy(m.next(-1, beeping), 0, out, f * AacEncoder.FRAME, AacEncoder.FRAME);
        return out;
    }

    private static double rms(float[] x, int from, int to) {
        double e = 0;
        for (int i = from; i < to; i++) e += x[i] * x[i];
        return Math.sqrt(e / Math.max(1, to - from));
    }

    @Test
    void itsSpeakingIsTheEscTuneThroughTheMotors() {
        SoundMaker m = new SoundMaker(7);
        seconds(m, 0.5, null);
        m.speakNow();
        float[] x = seconds(m, 1.2, null);
        int rate = AacEncoder.SAMPLE_RATE;
        // the first note: a tone in the ESCs' range with its odd harmonic, well over the breeze; not the old babble's 500-900 Hz
        int a = rate / 50, b = rate * 12 / 100;
        double best = 0, bestHz = 0;
        for (double hz = 400; hz <= 4000; hz += 25) {
            double p = power(x, a, b, hz);
            if (p > best) {
                best = p;
                bestHz = hz;
            }
        }
        assertTrue(bestHz >= 1400 && bestHz <= 2600, "the tune's first note at " + bestHz + " Hz");
        assertTrue(power(x, a, b, bestHz * 3) > power(x, a, b, bestHz * 2) * 4, "square-ish: the 3rd harmonic, not the 2nd");
        assertTrue(rms(x, a, b) > 0.05, "well over the breeze: " + rms(x, a, b));
    }

    @Test
    void rxLostBeepsHalfASecondOnAndOff() {
        SoundMaker m = new SoundMaker(7);
        m.setVoices(false);
        m.setQuiet(true);
        float[] x = seconds(m, 2.0, "RX_LOST");
        int rate = AacEncoder.SAMPLE_RATE;
        double on = rms(x, rate / 10, rate * 4 / 10), off = rms(x, rate * 6 / 10, rate * 9 / 10), on2 = rms(x, rate * 11 / 10, rate * 14 / 10);
        assertTrue(on > 0.08 && on2 > 0.08, "on: " + on + ", " + on2);
        assertTrue(off < on / 5, "off between: " + off);
        float[] y = seconds(m, 1.0, null);
        assertTrue(rms(y, rate / 10, rate * 9 / 10) < 0.03, "stops when Betaflight stops beeping");
    }
}
