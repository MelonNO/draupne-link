package de.droiddrone.pc.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The phone's microphone on this PC's default sound device (1.0.45; phone app pc.34 sends it while the drone is
 * disarmed). Everything runs on one thread of its own at the lowest priority: the link's threads only drop each frame
 * into a queue ({@link #onFrame}), and the decoding (FFmpeg's AAC), the jitter buffer and the sound card's blocking
 * writes happen here - never on the Swing thread, the video's or the sticks'. MUTE silences at once (the sound
 * card's buffer is thrown away); an alert sound playing lowers the stream (ducks it) so the warning is heard.
 */
public final class LiveAudio implements AutoCloseable {
    /** The jitter buffer plays once this much sound is in hand, and fills to it again after running dry. */
    static final double TARGET_MS = 200;
    /** More than this in hand (clock drift, a burst after a stall): the oldest frames go, back to TARGET_MS. */
    static final double MAX_MS = 450;
    /** The sound card's own buffer: about three AAC frames, part of the TARGET_MS once playing. */
    static final double LINE_MS = 60;
    /** The stream's level while an alert sound plays. */
    static final float DUCK_GAIN = 0.2f;
    /** No sound device: tried again this much later. */
    static final long REOPEN_MS = 5000;

    private final ArrayBlockingQueue<Object> incoming = new ArrayBlockingQueue<>(256);
    private final BooleanSupplier duck;
    private final Consumer<String> log;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile boolean muted;
    private volatile long lastFrameNs;

    /**
     * @param duck true while an alert sound plays
     * @param log  where "no sound device" and the like go
     */
    public LiveAudio(BooleanSupplier duck, Consumer<String> log) {
        this.duck = duck;
        this.log = log;
        thread = new Thread(this::run, "liveAudio");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    // ------------------------------------------------------------------ the link's threads (must not wait)

    /** The stream's config (AudioInitialFrame): a new decoder when it differs. */
    public void onConfig(AudioConfig config) {
        put(config);
    }

    /** One raw AAC frame, as the phone sent it. */
    public void onFrame(byte[] aac) {
        lastFrameNs = System.nanoTime();
        put(aac);
    }

    private void put(Object o) {
        if (!incoming.offer(o)) { // the thread is stuck (seconds behind): the oldest goes
            incoming.poll();
            incoming.offer(o);
        }
    }

    // ------------------------------------------------------------------ any thread

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public boolean isMuted() {
        return muted;
    }

    /** Sound came from the phone within the last second (played, or held back by MUTE). */
    public boolean isReceiving() {
        long last = lastFrameNs;
        return last != 0 && System.nanoTime() - last < 1_000_000_000L;
    }

    // ------------------------------------------------------------------ its own thread

    private AudioConfig config;
    private AacDecoder decoder;
    private SourceDataLine line;
    private int lineRate, lineChannels;
    private long nextOpenAt;
    private boolean lineEmpty = true;
    private boolean saidNoDevice;
    private float gain = 1f;
    private final JitterBuffer<byte[]> jitter = new JitterBuffer<>(TARGET_MS, MAX_MS);

    private void run() {
        try {
            while (running) {
                try {
                    Object o;
                    while ((o = incoming.poll()) != null) take(o);
                    byte[] frame = muted || decoder == null ? null : jitter.poll();
                    if (frame == null) {
                        if (muted || decoder == null) silence();
                        o = incoming.poll(10, TimeUnit.MILLISECONDS);
                        if (o != null) take(o);
                        continue;
                    }
                    play(frame);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    log.accept("Live audio: " + e.getMessage()); // this frame only; the next one is played as usual
                }
            }
        } finally {
            closeLine();
            if (decoder != null) decoder.close();
        }
    }

    private void take(Object o) {
        if (o instanceof AudioConfig c) {
            if (c.sameAs(config)) return;
            config = c;
            jitter.clear();
            if (decoder != null) decoder.close();
            decoder = null;
            try {
                decoder = new AacDecoder(c);
            } catch (IllegalStateException e) {
                log.accept("Live audio: " + e.getMessage());
            }
        } else if (o instanceof byte[] f && config != null) {
            jitter.offer(f, config.frameMs());
        }
    }

    /** Muted (or no stream): what is queued goes, and the sound card's buffer is emptied - silent at once. */
    private void silence() {
        jitter.clear();
        if (line != null && !lineEmpty) {
            line.flush();
            lineEmpty = true;
        }
    }

    private void play(byte[] frame) {
        float[] samples = decoder.decode(frame);
        if (samples == null || samples.length == 0) return;
        int channels = decoder.channels();
        if (line == null || lineRate != config.sampleRate() || lineChannels != channels) {
            if (System.currentTimeMillis() < nextOpenAt) return;
            openLine(config.sampleRate(), channels);
            if (line == null) return;
        }
        float want = duck.getAsBoolean() ? DUCK_GAIN : 1f;
        byte[] pcm = toPcm16(samples, channels, gain, want);
        gain = want;
        line.write(pcm, 0, pcm.length); // blocks while the card's buffer is full: this thread's pace
        lineEmpty = false;
    }

    private void openLine(int rate, int channels) {
        closeLine();
        try {
            AudioFormat format = new AudioFormat(rate, 16, channels, true, false);
            int bytes = (int) (rate * LINE_MS / 1000) * channels * 2;
            SourceDataLine l = AudioSystem.getSourceDataLine(format);
            l.open(format, bytes);
            l.start();
            line = l;
            lineRate = rate;
            lineChannels = channels;
            lineEmpty = true;
            saidNoDevice = false;
        } catch (Exception | LinkageError e) {
            nextOpenAt = System.currentTimeMillis() + REOPEN_MS;
            if (!saidNoDevice) log.accept("Live audio: no sound device (" + e.getMessage() + ").");
            saidNoDevice = true;
        }
    }

    private void closeLine() {
        if (line == null) return;
        try {
            line.close();
        } catch (RuntimeException ignored) {
        }
        line = null;
    }

    /**
     * Samples (-1..1, channels interleaved) to 16-bit little-endian PCM, the gain going from `from` to `to` over the
     * frame - a duck that starts or ends doesn't click.
     */
    static byte[] toPcm16(float[] samples, int channels, float from, float to) {
        byte[] out = new byte[samples.length * 2];
        int frames = samples.length / Math.max(1, channels);
        for (int i = 0; i < samples.length; i++) {
            int n = i / Math.max(1, channels);
            float g = frames <= 1 ? to : from + (to - from) * n / (frames - 1);
            int v = Math.round(samples[i] * g * 32767f);
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }
}
