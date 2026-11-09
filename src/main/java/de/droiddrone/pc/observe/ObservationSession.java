package de.droiddrone.pc.observe;

import de.droiddrone.common.Observe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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

    /** The muted tile asks its phone to stop the sound after this long (as the pilot's MUTE does). */
    public static final long MUTED_STOP_MS = 10_000;

    private final ObserveSet set;
    private final List<Feed> feeds;
    private final Runnable stopSound;
    private boolean stopped;
    /** When each observed phone's tile was muted (MonoClock); absent: not muted. */
    private final Map<String, Long> mutedAt = new HashMap<>();

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

    /**
     * A tile muted (now, or back at the start - then it asks no sound from the first look) or unmuted.
     * @param at when (MonoClock)
     */
    public synchronized void setMuted(String phone, boolean muted, long at) {
        if (muted) mutedAt.put(phone, at);
        else mutedAt.remove(phone);
    }

    public synchronized boolean isMuted(String phone) {
        return mutedAt.containsKey(phone);
    }

    /** The phone's sound is still asked for: not muted, or muted less than {@link #MUTED_STOP_MS} ago. */
    public synchronized boolean audioWanted(String phone, long now) {
        Long at = mutedAt.get(phone);
        return at == null || now - at < MUTED_STOP_MS;
    }

    /** A tile's volume as saved (0..100; nothing saved or a broken value: 100). */
    public static int volume(String saved) {
        try {
            return saved.isEmpty() ? 100 : Math.max(0, Math.min(100, Integer.parseInt(saved)));
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    /**
     * The line under a tile: a phone that says it isn't streaming (for 3 s after it said so), one not heard yet (asleep:
     * it joins at its check-in), one gone quiet, else the picture's size and rate.
     * @param state the phone's last OBSERVE state, or null; stateAt when it came, lastPacketAt the last packet (0 none)
     * @param picture {width (0: none yet), height, frames shown a second}, asked for only while it streams
     */
    public static String tileStatus(Observe.State state, long stateAt, long lastPacketAt, boolean asleep, Supplier<int[]> picture,
                                    boolean sharp, long now) {
        if (state != null && state.state != Observe.STREAMING && now - stateAt < 3000) {
            return "NOT STREAMING: " + Observe.stateName(state.state);
        }
        if (lastPacketAt == 0) return asleep ? "DEEP SLEEP - joins at next check-in" : "Asking the phone...";
        if (now - lastPacketAt > ObserverLink.SILENT_MS) return "NO SIGNAL for " + (now - lastPacketAt) / 1000 + " s";
        int[] p = picture.get();
        String status = p[0] > 0 ? p[0] + "x" + p[1] + "  " + p[2] + " FPS" : "Waiting for the picture...";
        if (sharp) status += "  SHARP";
        return status;
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
