package de.droiddrone.pc;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * <p>
 * 1.0.50: an ended link's word is kept per DRONE (its name), not per link object - any later link to the same drone
 * that hears a fresh DISARMED ends it at once (its flight controller says it is down). A drone lost armed and never
 * heard again still holds the window. The pilot who knows it is down ends it with IT'S DOWN - RESUME ({@link #resume}),
 * which never touches a link open now. {@link #reason} says in plain words what holds it.
 */
public final class PossiblyFlying {
    /** How long after the last armed word a quiet or closed link still counts as possibly flying. */
    public static final long LOST_DRONE_WINDOW_MS = 10 * 60_000;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    /**
     * What a link last heard from its flight controller: armed (null = the link says it doesn't know), and when
     * (MonoClock ms; 0 = nothing heard yet).
     */
    public record Word(Boolean armed, long at) {
        public static final Word NOTHING = new Word(false, 0);
    }

    /**
     * What holds the flag: the drone (its name; "A drone" when the link had none), whether its link is open now, when
     * its armed word was heard and when the window ends (MonoClock ms).
     */
    public record Hold(String drone, boolean live, long heardAt, long until) {}

    private record Live(Object key, String drone, Supplier<Word> word) {}

    private record Ended(String drone, Word word) {}

    private static final PossiblyFlying PC = new PossiblyFlying(MonoClock::ms);

    private final LongSupplier clock;
    private final Map<Object, Live> links = new ConcurrentHashMap<>();
    /** The last word of drones whose link ended, by drone: an armed one keeps the flag up for the rest of its window. */
    private final Map<Object, Ended> ended = new ConcurrentHashMap<>();
    /**
     * Drones the pilot marked CRASHED (1.0.52), by drone key -> when (MonoClock ms): a word heard before that no longer
     * counts - it is down for good; a word heard after it (the phone answers again) counts as usual.
     */
    private final Map<Object, Long> downAt = new ConcurrentHashMap<>();

    /** A flag of its own (tests; everything else reads this PC's, {@link #pc()}). */
    public PossiblyFlying(LongSupplier clock) {
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

    /** This PC's reason, in plain words (null: it doesn't hold). */
    public static String reasonNow() {
        return PC.reason();
    }

    /** A drone link whose drone isn't known: its word is kept under the link itself. */
    public void attach(Object link, Supplier<Word> word) {
        attach(link, null, word);
    }

    /**
     * A drone link: its flight controller's last word, read whenever the flag is asked. Again with the same link
     * replaces it.
     * @param drone the drone's name (or address); null: not known, the link itself is the key
     */
    public void attach(Object link, String drone, Supplier<Word> word) {
        links.put(link, new Live(keyOf(link, drone), drone, word));
    }

    /** The link ended: its last word is kept, under its drone, for the rest of the lost-drone window. */
    public void detach(Object link) {
        Live l = links.remove(link);
        if (l == null) return;
        Word last = read(l.word());
        Ended before = ended.get(l.key());
        if (counts(last, clock.getAsLong())) {
            if (before == null || before.word().at() <= last.at()) ended.put(l.key(), new Ended(l.drone(), last));
        } else if (freshDisarmed(last, before)) {
            ended.remove(l.key(), before); // it ended saying DISARMED after the armed word: down
        }
    }

    public boolean holds() {
        return why() != null;
    }

    /**
     * What holds the flag now (null: nothing). An open link that says armed comes first, else the ended drone whose
     * window runs longest. On the way, an open link to a kept drone that heard a fresh DISARMED ends its kept word.
     */
    public Hold why() {
        long now = clock.getAsLong();
        Hold live = null;
        for (Live l : links.values()) {
            Word w = read(l.word());
            Ended e = ended.get(l.key());
            if (freshDisarmed(w, e)) ended.remove(l.key(), e); // connected again and it says DISARMED: it is down
            if (live == null && counts(w, now) && !markedDown(l.key(), w)) live = new Hold(nameOf(l.drone()), true, w.at(), w.at() + LOST_DRONE_WINDOW_MS);
        }
        if (live != null) return live;
        Hold best = null;
        for (Map.Entry<Object, Ended> e : ended.entrySet()) {
            Word w = e.getValue().word();
            if (!counts(w, now) || markedDown(e.getKey(), w)) {
                ended.remove(e.getKey(), e.getValue()); // its window is over
                continue;
            }
            long until = w.at() + LOST_DRONE_WINDOW_MS;
            if (best == null || until > best.until()) best = new Hold(nameOf(e.getValue().drone()), false, w.at(), until);
        }
        return best;
    }

    /** IT'S DOWN - RESUME would end it: only the kept words of ended links hold the flag, no open link says armed. */
    public boolean resumable() {
        Hold h = why();
        return h != null && !h.live();
    }

    /**
     * The pilot's IT'S DOWN - RESUME: the kept words of ended links are forgotten. An open link is never touched (one
     * that says armed keeps the flag up); nothing here touches arming or the sticks.
     * @return the drones whose words were forgotten
     */
    public List<String> resume() {
        long now = clock.getAsLong();
        List<String> out = new ArrayList<>();
        for (Map.Entry<Object, Ended> e : ended.entrySet()) {
            if (ended.remove(e.getKey(), e.getValue()) && counts(e.getValue().word(), now)) out.add(nameOf(e.getValue().drone()));
        }
        return out;
    }

    /**
     * The pilot marked this drone CRASHED (1.0.52): it no longer flies - its armed words so far, kept or on a link still
     * open and waiting, stop holding the flag. A newer word from it (heard again) counts as usual.
     * @return whether it held the flag
     */
    public boolean markDown(String drone) {
        if (drone == null || drone.isBlank()) return false;
        Hold before = why();
        Object key = keyOf(null, drone);
        downAt.put(key, clock.getAsLong());
        ended.remove(key);
        return before != null && !java.util.Objects.equals(before, why());
    }

    /** A word from before the drone was marked CRASHED. */
    private boolean markedDown(Object key, Word w) {
        Long at = downAt.get(key);
        return at != null && w.at() <= at;
    }

    /**
     * Why, in plain words, for whatever it holds back - e.g. "MobileZulu was lost armed 7 min ago; a drone here may be
     * flying (until 00:06)". Null when it doesn't hold.
     */
    public String reason() {
        Hold h = why();
        return h == null ? null : describe(h, clock.getAsLong(), System.currentTimeMillis());
    }

    static String describe(Hold h, long nowMono, long nowWall) {
        long ago = Math.max(0, nowMono - h.heardAt());
        if (h.live() && ago < 2000) return h.drone() + " is armed: a drone here is flying";
        String until = CLOCK.format(Instant.ofEpochMilli(nowWall + (h.until() - nowMono)).atZone(ZoneId.systemDefault()));
        String when = ago < 60_000 ? (ago / 1000) + " s ago" : (ago / 60_000) + " min ago";
        return h.drone() + (h.live() ? " went quiet armed " : " was lost armed ") + when + "; a drone here may be flying (until " + until + ")";
    }

    /** Armed (or unknown) and heard within the window: fresh means flying, quiet means it may still be. */
    static boolean counts(Word w, long now) {
        return w != null && w.at() != 0 && !Boolean.FALSE.equals(w.armed()) && now - w.at() < LOST_DRONE_WINDOW_MS;
    }

    /** A DISARMED heard after the kept armed word. */
    private static boolean freshDisarmed(Word w, Ended kept) {
        return kept != null && w != null && w.at() != 0 && Boolean.FALSE.equals(w.armed()) && w.at() > kept.word().at();
    }

    private static Object keyOf(Object link, String drone) {
        return drone == null || drone.isBlank() ? link : drone.trim().toLowerCase(Locale.ROOT);
    }

    private static String nameOf(String drone) {
        return drone == null || drone.isBlank() ? "A drone" : drone.trim();
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
