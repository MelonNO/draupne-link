package de.droiddrone.pc.audio;

import de.droiddrone.common.VoiceActivity;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * OBSERVATION's sound (1.0.48): every observed phone heard at once, mixed into one line on this PC's default sound
 * device. Per phone the 1.0.45 path - FFmpeg's AAC decoder ({@link AacDecoder}) and a {@link JitterBuffer} of its own
 * - then its MUTE and volume, and a {@link VoiceActivity} for the tile's speaking highlight (heard muted too). One thread
 * at the lowest priority does all of it, a period of {@link #PERIOD} samples at a time (the AAC frame's 21 ms at 48
 * kHz); the sound card's blocking write keeps the pace, silence where a phone has nothing. The links' threads only drop
 * frames into its queue.
 */
public final class ObservationAudio implements AutoCloseable {
    /** The mix's rate and period: the phones' own (pc.34 sends 48 kHz mono AAC, 1024 samples a frame). */
    static final int RATE = 48_000, PERIOD = 1024;
    static final double PERIOD_MS = PERIOD * 1000.0 / RATE;

    private final ArrayBlockingQueue<Object[]> incoming = new ArrayBlockingQueue<>(512);
    private final Map<Integer, Source> sources = new ConcurrentHashMap<>();
    private final BooleanSupplier duck;
    private final Consumer<String> log;
    private final Thread thread;
    private volatile boolean running = true;
    private int nextId;

    /** One phone's sound. The volume and MUTE are set from Swing, the rest is the audio thread's. */
    private static final class Source {
        volatile boolean muted;
        volatile float volume = 1f;
        volatile boolean speaking;
        volatile double levelDb = VoiceActivity.SILENCE_DB;
        volatile long lastFrameNs;
        /** Taken out of the mix (removeSource): it goes at the thread's next period, whatever the queue dropped. */
        volatile boolean removed;
        /** Its decoder was let go while it was silent (SILENT_NS): made again by the next frame. */
        boolean asleep;
        AudioConfig config;
        AacDecoder decoder;
        final JitterBuffer<byte[]> jitter = new JitterBuffer<>(LiveAudio.TARGET_MS, LiveAudio.MAX_MS);
        final VoiceActivity voice = new VoiceActivity();
        float[] fifo = new float[PERIOD * 4];
        int fifoLength;
        float gain = 1f;
    }

    public ObservationAudio(BooleanSupplier duck, Consumer<String> log) {
        this.duck = duck;
        this.log = log;
        thread = new Thread(this::run, "observationAudio");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    // ------------------------------------------------------------------ any thread

    /** A new phone in the mix; its id for the calls below. */
    public synchronized int addSource() {
        int id = nextId++;
        sources.put(id, new Source());
        return id;
    }

    public void removeSource(int id) {
        Source s = sources.get(id);
        if (s != null) s.removed = true; // 1.0.49: a REMOVE dropped by a full queue left the phone in the mix
        put(id, REMOVE);
    }

    public void onConfig(int id, AudioConfig config) {
        put(id, config);
    }

    public void onFrame(int id, byte[] aac) {
        Source s = sources.get(id);
        if (s == null || s.removed) return;
        s.lastFrameNs = System.nanoTime();
        put(id, aac);
    }

    public void setMuted(int id, boolean muted) {
        Source s = sources.get(id);
        if (s != null) s.muted = muted;
    }

    /** 0..1 */
    public void setVolume(int id, float volume) {
        Source s = sources.get(id);
        if (s != null) s.volume = Math.max(0f, Math.min(1f, volume));
    }

    /** The phone hears something now (the tile's highlight). */
    public boolean isSpeaking(int id) {
        Source s = sources.get(id);
        return s != null && s.speaking;
    }

    /** Sound came from this phone within the last second. */
    public boolean isReceiving(int id) {
        Source s = sources.get(id);
        long last = s == null ? 0 : s.lastFrameNs;
        return last != 0 && System.nanoTime() - last < 1_000_000_000L;
    }

    private static final Object REMOVE = new Object();

    private void put(int id, Object o) {
        Object[] item = {id, o};
        if (!incoming.offer(item)) { // the thread is stuck: the oldest goes
            incoming.poll();
            incoming.offer(item);
        }
    }

    // ------------------------------------------------------------------ its own thread

    private SourceDataLine line;
    private long nextOpenAt;
    private boolean saidNoDevice;
    private final float[] mix = new float[PERIOD];

    private void run() {
        try {
            while (running) {
                try {
                    Object[] item;
                    while ((item = incoming.poll()) != null) take((Integer) item[0], item[1]);
                    long nowMs = System.currentTimeMillis();
                    java.util.Arrays.fill(mix, 0f);
                    boolean any = false;
                    float duckGain = duck.getAsBoolean() ? LiveAudio.DUCK_GAIN : 1f;
                    dropRemovedAndSilent(System.nanoTime());
                    for (Source s : sources.values()) {
                        fill(s, nowMs);
                        if (s.fifoLength > 0) any = true;
                        float want = s.muted ? 0f : s.volume * duckGain;
                        int n = Math.min(PERIOD, s.fifoLength);
                        AudioMixer.mixInto(mix, s.fifo, n, s.gain, want);
                        s.gain = want;
                        System.arraycopy(s.fifo, n, s.fifo, 0, s.fifoLength - n);
                        s.fifoLength -= n;
                        s.speaking = s.voice.isActive(nowMs);
                    }
                    if (!any && line == null) { // nothing to play and no line open: wait for sound
                        item = incoming.poll(20, TimeUnit.MILLISECONDS);
                        if (item != null) take((Integer) item[0], item[1]);
                        continue;
                    }
                    play(mix);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    log.accept("Observation sound: " + e.getMessage());
                }
            }
        } finally {
            closeLine();
            for (Source s : sources.values()) if (s.decoder != null) s.decoder.close();
        }
    }

    private void take(int id, Object o) {
        Source s = sources.get(id);
        if (s == null) return;
        if (o == REMOVE) {
            sources.remove(id);
            if (s.decoder != null) s.decoder.close();
            return;
        }
        if (o instanceof AudioConfig c) {
            if (c.sameAs(s.config)) return;
            s.config = c;
            s.jitter.clear();
            s.fifoLength = 0;
            s.voice.reset();
            if (s.decoder != null) s.decoder.close();
            s.decoder = null;
            s.asleep = false;
            try {
                s.decoder = new AacDecoder(c);
            } catch (IllegalStateException e) {
                log.accept("Observation sound: " + e.getMessage());
            }
        } else if (o instanceof byte[] f && s.config != null) {
            if (s.asleep) { // let go while the phone was silent: made again
                s.asleep = false;
                try {
                    s.decoder = new AacDecoder(s.config);
                } catch (IllegalStateException e) {
                    log.accept("Observation sound: " + e.getMessage());
                }
            }
            s.jitter.offer(f, s.config.frameMs());
        }
    }

    /** No frame from a phone for this long: its decoder and what it still held go (its next frame makes them again). */
    static final long SILENT_NS = 30_000_000_000L;

    /** Sources taken out (Source.removed) leave the mix; one silent for SILENT_NS lets its decoder go. */
    void dropRemovedAndSilent(long nowNs) {
        for (java.util.Iterator<Source> it = sources.values().iterator(); it.hasNext(); ) {
            Source s = it.next();
            if (s.removed) {
                it.remove();
                if (s.decoder != null) s.decoder.close();
                s.decoder = null;
            } else if (s.decoder != null && s.lastFrameNs != 0 && nowNs - s.lastFrameNs > SILENT_NS) {
                s.decoder.close();
                s.decoder = null;
                s.asleep = true;
                s.jitter.clear();
                s.fifoLength = 0;
                s.voice.reset();
                s.speaking = false;
            }
        }
    }

    /** Sources in the mix (tests). */
    int sourceCount() {
        return sources.size();
    }

    /** The source's samples at the mix's rate, a period's worth if its jitter buffer has them; the speaking judged. */
    private void fill(Source s, long nowMs) {
        while (s.fifoLength < PERIOD && s.decoder != null) {
            byte[] frame = s.jitter.poll();
            if (frame == null) return;
            float[] decoded = s.decoder.decode(frame);
            if (decoded == null || decoded.length == 0) continue;
            float[] mono = AudioMixer.toMono(decoded, s.decoder.channels());
            double db = VoiceActivity.levelDb(mono, 0, mono.length);
            s.levelDb = db;
            s.voice.frame(db, mono.length * 1000.0 / s.config.sampleRate(), nowMs);
            float[] at48 = AudioMixer.resample(mono, s.config.sampleRate(), RATE);
            if (s.fifoLength + at48.length > s.fifo.length) s.fifo = java.util.Arrays.copyOf(s.fifo, (s.fifoLength + at48.length) * 2);
            System.arraycopy(at48, 0, s.fifo, s.fifoLength, at48.length);
            s.fifoLength += at48.length;
        }
    }

    private void play(float[] samples) throws InterruptedException {
        if (line == null) {
            if (System.currentTimeMillis() >= nextOpenAt) openLine();
            if (line == null) {
                Thread.sleep(Math.round(PERIOD_MS)); // no sound device: the highlights keep their pace
                return;
            }
        }
        byte[] pcm = LiveAudio.toPcm16(samples, 1, 1f, 1f);
        line.write(pcm, 0, pcm.length); // blocks while the card's buffer is full: this thread's pace
    }

    private void openLine() {
        try {
            AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
            int bytes = (int) (RATE * LiveAudio.LINE_MS / 1000) * 2;
            SourceDataLine l = AudioSystem.getSourceDataLine(format);
            l.open(format, bytes);
            l.start();
            line = l;
            saidNoDevice = false;
        } catch (Exception | LinkageError e) {
            nextOpenAt = System.currentTimeMillis() + LiveAudio.REOPEN_MS;
            if (!saidNoDevice) log.accept("Observation sound: no sound device (" + e.getMessage() + ").");
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

    /** Silent at once (CONNECT on a tile: every stream stops): the thread ends, the line closes. */
    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }
}
