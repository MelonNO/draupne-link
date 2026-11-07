package de.droiddrone.pc.observe;

import java.util.ArrayList;
import java.util.List;

/**
 * The grid while observing (1.0.48): one feed per tile, which tile wants the sharp picture, and CONNECT - every stream
 * stopped at once (the sound first, then each phone told OBSERVE_STOP and its socket closed: latency first, the pilot
 * connection that follows gets the PC's whole link) before the pilot connection opens to that phone. The decoders are
 * let go afterwards, off the caller's thread.
 */
public final class ObservationSession {
    /** One tile's stream: its phone, its picture and sound. */
    public interface Feed {
        String phone();

        /** The selected or enlarged tile's sharp picture, or the grid's. */
        void setSharp(boolean sharp);

        /** Stops the stream now: the phone told, nothing more received or played. Fast, never blocks. */
        void stopStream();

        /** Lets the decoder and the rest go (may take a moment). */
        void release();
    }

    private final ObserveSet set;
    private final List<Feed> feeds;
    private final Runnable stopSound;
    private boolean stopped;

    /**
     * @param set       the grid's phones (the feeds in its order) and its selection
     * @param stopSound the shared mix silenced (null: none)
     */
    public ObservationSession(ObserveSet set, List<Feed> feeds, Runnable stopSound) {
        this.set = set;
        this.feeds = new ArrayList<>(feeds);
        this.stopSound = stopSound;
        applyQuality();
    }

    public ObserveSet set() {
        return set;
    }

    public synchronized List<Feed> feeds() {
        return List.copyOf(feeds);
    }

    public synchronized void select(int i) {
        set.select(i);
        applyQuality();
    }

    public synchronized void toggleEnlarged(int i) {
        set.toggleEnlarged(i);
        applyQuality();
    }

    private void applyQuality() {
        for (int i = 0; i < feeds.size(); i++) feeds.get(i).setSharp(set.wantsSharp(i));
    }

    /**
     * A phone leaves the grid (a pilot connected to it, or the pilot took it out): its stream stopped, the tiles after
     * it move up. @return it was in the grid
     */
    public synchronized boolean remove(String phone) {
        int i = set.indexOf(phone);
        if (i < 0 || i >= feeds.size()) return false;
        Feed f = feeds.remove(i);
        set.remove(phone);
        f.stopStream();
        release(List.of(f));
        applyQuality();
        return true;
    }

    /**
     * CONNECT on tile i: every stream stops now - the sound, then the other tiles, then this one (its phone gets the
     * pilot connection next). @return the phone to fly, or null for no such tile
     */
    public synchronized String connect(int i) {
        if (stopped || i < 0 || i >= feeds.size()) return null;
        String phone = feeds.get(i).phone();
        stopAll(i);
        return phone;
    }

    /** STOP OBSERVING: every stream stopped, the decoders let go. */
    public synchronized void stop() {
        if (!stopped) stopAll(-1);
    }

    public synchronized boolean isStopped() {
        return stopped;
    }

    private void stopAll(int last) {
        stopped = true;
        if (stopSound != null) stopSound.run();
        for (int i = 0; i < feeds.size(); i++) if (i != last) feeds.get(i).stopStream();
        if (last >= 0) feeds.get(last).stopStream();
        release(new ArrayList<>(feeds));
        feeds.clear();
    }

    private static void release(List<Feed> gone) {
        Thread t = new Thread(() -> {
            for (Feed f : gone) {
                try {
                    f.release();
                } catch (RuntimeException ignored) {
                }
            }
        }, "observeRelease");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
}
