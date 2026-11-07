package de.droiddrone.pc.audio;

/**
 * The arithmetic of OBSERVATION's mix (1.0.48): samples -1..1, mono. A source is added with its gain moving from the
 * last period's to this one's over the period (a MUTE or volume change doesn't click), and the sum is limited to -1..1.
 */
public final class AudioMixer {
    private AudioMixer() {
    }

    /**
     * Adds count samples of src into mix, the gain going from `from` to `to` across them; the sum is clipped to -1..1.
     * A source with fewer samples than the mix adds silence for the rest.
     */
    public static void mixInto(float[] mix, float[] src, int count, float from, float to) {
        int n = Math.min(count, Math.min(mix.length, src.length));
        for (int i = 0; i < n; i++) {
            float g = n <= 1 ? to : from + (to - from) * i / (n - 1);
            float v = mix[i] + src[i] * g;
            mix[i] = v > 1f ? 1f : v < -1f ? -1f : v;
        }
    }

    /** Interleaved channels to one, averaged. */
    public static float[] toMono(float[] interleaved, int channels) {
        if (channels <= 1) return interleaved;
        int frames = interleaved.length / channels;
        float[] out = new float[frames];
        for (int f = 0; f < frames; f++) {
            float sum = 0;
            for (int c = 0; c < channels; c++) sum += interleaved[f * channels + c];
            out[f] = sum / channels;
        }
        return out;
    }

    /** Linear resampling to another rate (the phones all send 48 kHz; this keeps an odd one in tune). */
    public static float[] resample(float[] in, int fromRate, int toRate) {
        if (fromRate == toRate || fromRate <= 0 || toRate <= 0 || in.length == 0) return in;
        int n = (int) Math.round(in.length * (double) toRate / fromRate);
        float[] out = new float[n];
        double step = (double) fromRate / toRate;
        for (int i = 0; i < n; i++) {
            double x = i * step;
            int a = (int) x;
            double t = x - a;
            float va = in[Math.min(a, in.length - 1)], vb = in[Math.min(a + 1, in.length - 1)];
            out[i] = (float) (va + (vb - va) * t);
        }
        return out;
    }
}
