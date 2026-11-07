package de.droiddrone.pc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * One flag for the whole PC (1.0.49, the pilot's decision after the 1.0.48 review): a drone on this PC may be in the
 * air. True while any drone link's flight controller says armed, and for the lost-drone window
 * ({@link #LOST_DRONE_WINDOW_MS}) after a link that last said armed went quiet or was closed - two seconds of silent
 * telemetry is no proof of landing (a failsafe, a 4G gap). An armed state the link reports as unknown counts as armed.
 * <p>
 * While it holds, nothing on this PC competes with the sticks and the picture on the network: map tiles and their
 * prefetch, height lookups, OpenCelliD, area downloads, overwatch clips and previews and the phone's remote update
 * wait; overwatch alerts come quietly and raise no window. Everyone reads {@link #now()}; the links attach their
 * flight controller's word ({@link #attach}) and the flag works it out when asked, so it never waits for a refresh.
 */
public final class PossiblyFlying {
    /** How long after the last armed word a quiet or closed link still counts as possibly flying. */
    public static final long LOST_DRONE_WINDOW_MS = 10 * 60_000;

    /**
     * What a link last heard from its flight controller: armed (null = the link says it doesn't know), and when
     * (MonoClock ms; 0 = nothing heard yet).
     */
    public record Word(Boolean armed, long at) {
        public static final Word NOTHING = new Word(false, 0);
    }

    private static final PossiblyFlying PC = new PossiblyFlying(MonoClock::ms);

    private final LongSupplier clock;
    private final Map<Object, Supplier<Word>> links = new ConcurrentHashMap<>();
    /** The last word of links that ended: an armed one keeps the flag up for the rest of its window. */
    private final Map<Object, Word> ended = new ConcurrentHashMap<>();

    PossiblyFlying(LongSupplier clock) {
        this.clock = clock;
    }

    /** This PC's flag. */
    public static PossiblyFlying pc() {
        return PC;
    }

    /** Whether a drone on this PC may be flying now (this PC's flag). */
    public static boolean now() {
        return PC.holds();
    }

    /** A drone link: its flight controller's last word, read whenever the flag is asked. Again with the same key replaces it. */
    public void attach(Object link, Supplier<Word> word) {
        ended.remove(link);
        links.put(link, word);
    }

    /** The link ended: its last word is kept for the rest of the lost-drone window. */
    public void detach(Object link) {
        Supplier<Word> w = links.remove(link);
        if (w == null) return;
        Word last = read(w);
        if (counts(last, clock.getAsLong())) ended.put(link, last);
    }

    public boolean holds() {
        long now = clock.getAsLong();
        for (Supplier<Word> w : links.values()) if (counts(read(w), now)) return true;
        boolean any = false;
        for (Map.Entry<Object, Word> e : ended.entrySet()) {
            if (counts(e.getValue(), now)) any = true;
            else ended.remove(e.getKey(), e.getValue()); // its window is over
        }
        return any;
    }

    /** Armed (or unknown) and heard within the window: fresh means flying, quiet means it may still be. */
    static boolean counts(Word w, long now) {
        return w != null && w.at() != 0 && !Boolean.FALSE.equals(w.armed()) && now - w.at() < LOST_DRONE_WINDOW_MS;
    }

    private Word read(Supplier<Word> w) {
        try {
            Word word = w.get();
            return word == null ? Word.NOTHING : word;
        } catch (RuntimeException e) {
            return new Word(null, clock.getAsLong()); // a link that can't say counts as one that may fly
        }
    }

    /** The flight controller's word from a link's telemetry (FcTelemetry.Status: armed, when received). */
    public static Word of(FcTelemetry.Status st) {
        return st == null ? Word.NOTHING : new Word(st.armed(), st.receivedAt());
    }
}
