package de.droiddrone.pc.audio;

import java.util.ArrayDeque;

/**
 * The live audio's jitter buffer: frames come over 4G bunched and with gaps, the sound card takes them at a steady
 * rate. Play starts once targetMs of sound is in hand; a buffer that runs dry waits for targetMs again (one gap,
 * rather than a stutter at every late frame); one that grows past maxMs (the phone's clock a little faster than the
 * sound card's, or a burst after a stall) drops its oldest frames down to targetMs - the sound stays about targetMs
 * behind, never seconds. Not thread-safe: the player's thread alone.
 */
public final class JitterBuffer<T> {
    private record Entry<T>(T item, double ms) {}

    private final ArrayDeque<Entry<T>> queue = new ArrayDeque<>();
    private final double targetMs, maxMs;
    private double bufferedMs;
    private boolean playing;
    private long underruns, dropped;

    public JitterBuffer(double targetMs, double maxMs) {
        if (targetMs <= 0 || maxMs < targetMs) throw new IllegalArgumentException("target " + targetMs + ", max " + maxMs);
        this.targetMs = targetMs;
        this.maxMs = maxMs;
    }

    /** A frame that plays for ms. */
    public void offer(T item, double ms) {
        queue.addLast(new Entry<>(item, ms));
        bufferedMs += ms;
        if (bufferedMs > maxMs) {
            while (bufferedMs > targetMs && queue.size() > 1) {
                bufferedMs -= queue.pollFirst().ms();
                dropped++;
            }
        }
    }

    /** The next frame to play, or null: still filling to targetMs, or just run dry (it fills again first). */
    public T poll() {
        if (!playing) {
            if (bufferedMs < targetMs - 1e-6) return null;
            playing = true;
        }
        Entry<T> e = queue.pollFirst();
        if (e == null) {
            playing = false;
            underruns++;
            bufferedMs = 0;
            return null;
        }
        bufferedMs -= e.ms();
        if (queue.isEmpty()) bufferedMs = 0; // no rounding left over
        return e.item();
    }

    /** Everything out (muted, a new stream): it fills to targetMs again. */
    public void clear() {
        queue.clear();
        bufferedMs = 0;
        playing = false;
    }

    public double bufferedMs() {
        return bufferedMs;
    }

    public boolean isPlaying() {
        return playing;
    }

    /** How often it ran dry while playing. */
    public long underruns() {
        return underruns;
    }

    /** Frames dropped because it grew past maxMs. */
    public long dropped() {
        return dropped;
    }
}
