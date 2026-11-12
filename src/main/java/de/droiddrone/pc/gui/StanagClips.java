package de.droiddrone.pc.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * Which overwatch clip one drone's STANAG 4609 stream plays (1.0.65, the pilot: "live picture then clip. Remember to
 * account for if there comes a new detection or the user changes mode while the clip is playing"). The rules:
 * <ul>
 * <li>an event's clip plays once, when it is whole on this PC; several waiting: the oldest first;</li>
 * <li>never the clip of an alert the pilot dismissed (or whose badge was closed), nor of an event that was over before
 * the streams heard of it (one from before this start of the app, or from before they were switched on);</li>
 * <li>new motion (an alert running) ends the clip that plays - the live picture is what matters; it is not taken up
 * again (it is in CLIPS);</li>
 * <li>the drone piloted or observed here ends it the same way;</li>
 * <li>a change of mode (the FLEET badge: OVERWATCH to STANDBY, DEEP SLEEP, CONNECTED, UNKNOWN ...) ends it, and the
 * clips still waiting are not played either: they belong to the watch that is over.</li>
 * </ul>
 * Pure: one drone, times and facts passed in. Not thread-safe: the Swing thread.
 */
final class StanagClips {
    /** The hub's word on the drone's newest event. */
    record Alert(long startedAt, boolean running, boolean hidden) {}

    private static final class Event {
        final long startedAt;
        boolean hidden, played;

        Event(long startedAt) {
            this.startedAt = startedAt;
        }
    }

    private final List<Event> events = new ArrayList<>();
    private String badge;
    private long playing = -1;

    /**
     * The facts now; the answer is the clip to play (its event's start), or -1 for none.
     * @param badge the drone's mode as FLEET says it
     * @param video this PC pilots or observes it
     * @param alert its newest event, or null when the hub knows none
     * @param whole whether the event that started then has its clip whole on this PC now
     */
    long update(String badge, boolean video, Alert alert, LongPredicate whole) {
        if (this.badge != null && !this.badge.equals(badge)) {
            // another mode: what waited belongs to the watch that is over. Kept as played, not forgotten - the hub
            // still names the last event, and forgotten it would be taken for a new one and played after all
            for (Event e : events) e.played = true;
            playing = -1;
        }
        this.badge = badge;
        Event newest = null;
        if (alert != null) {
            for (Event e : events) if (e.startedAt == alert.startedAt()) newest = e;
            if (newest == null) {
                newest = new Event(alert.startedAt());
                // first heard of when it was over already (the streams switched on hours after it): not played by itself
                if (!alert.running()) newest.played = true;
                events.add(newest);
                events.sort((a, b) -> Long.compare(a.startedAt, b.startedAt));
            }
            if (alert.hidden()) newest.hidden = true;
        }
        boolean running = alert != null && alert.running();
        if (playing >= 0) {
            Event p = event(playing);
            // new motion, a pilot or an observer, the clip gone (REMOVE in CLIPS, its file deleted) or dismissed after all
            if (running || video || p == null || p.hidden || !whole.test(playing)) {
                if (p != null) p.played = true;
                playing = -1;
            }
        }
        if (playing < 0 && !running && !video) {
            for (Event e : events) {
                if (e.played || e.hidden || !whole.test(e.startedAt)) continue;
                playing = e.startedAt;
                break;
            }
        }
        return playing;
    }

    /** The clip that played came to its end. */
    void ended(long startedAt) {
        Event e = event(startedAt);
        if (e != null) e.played = true;
        if (playing == startedAt) playing = -1;
    }

    long playing() {
        return playing;
    }

    private Event event(long startedAt) {
        for (Event e : events) if (e.startedAt == startedAt) return e;
        return null;
    }
}
