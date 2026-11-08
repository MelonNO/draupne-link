package de.droiddrone.pc.fleet;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A real recording the virtual fleet plays (1.0.52, the pilot: real sounds, not made-up ones): one of the 16 kHz
 * mono mu-law files in /sound (tools/sound/prepare_sounds.py makes them from freesound.org CC0 recordings, see
 * THIRD-PARTY.md), read once with javax.sound and kept as floats. {@link Voice} plays one at any speed (the pitch with
 * it) at the encoder's 48 kHz, looped or once. A missing file plays silence.
 */
final class SoundSample {
    static final int RATE = 16_000;
    private static final Map<String, SoundSample> CACHE = new ConcurrentHashMap<>();

    final float[] data;

    private SoundSample(float[] data) {
        this.data = data;
    }

    /** /sound/{name}.wav, read once. */
    static SoundSample of(String name) {
        return CACHE.computeIfAbsent(name, SoundSample::load);
    }

    private static SoundSample load(String name) {
        try (InputStream raw = SoundSample.class.getResourceAsStream("/sound/" + name + ".wav")) {
            if (raw == null) return new SoundSample(new float[1]);
            try (AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(raw));
                 AudioInputStream pcm = AudioSystem.getAudioInputStream(
                         new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, in.getFormat().getSampleRate(), 16, 1, 2,
                                 in.getFormat().getSampleRate(), false), in)) {
                byte[] b = pcm.readAllBytes();
                float[] f = new float[b.length / 2];
                for (int i = 0; i < f.length; i++) f[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8)) / 32768f;
                return new SoundSample(f.length == 0 ? new float[1] : f);
            }
        } catch (Exception e) {
            return new SoundSample(new float[1]);
        }
    }

    /** Seconds long. */
    double seconds() {
        return (double) data.length / RATE;
    }

    /** One playing of a sample: where it is, at what speed. Not shared between threads. */
    static final class Voice {
        private final SoundSample s;
        private final boolean loop;
        private double pos;

        Voice(SoundSample s, boolean loop) {
            this.s = s;
            this.loop = loop;
        }

        /** Back to the start (a one-shot plays again). */
        void restart() {
            pos = 0;
        }

        boolean done() {
            return !loop && pos >= s.data.length - 1;
        }

        /**
         * The next output sample at 48 kHz.
         * @param speed 1 as recorded; 1.2 a fifth faster and higher
         */
        float next(double speed) {
            float[] d = s.data;
            if (d.length < 2 || done()) return 0;
            int i = (int) pos;
            double f = pos - i;
            int j = i + 1 < d.length ? i + 1 : loop ? 0 : i;
            float v = (float) (d[i] + (d[j] - d[i]) * f);
            pos += speed * RATE / AacEncoder.SAMPLE_RATE;
            if (loop && pos >= d.length) pos -= d.length;
            return v;
        }
    }
}
