package de.droiddrone.pc;

import de.droiddrone.common.SleepCheck;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 1.0.64, how often the name scan ({@link NameService}) asks each address. Every PC asked every Tailscale device every
 * 5 s, sleeping phones too: each query woke the phone's radio and Tailscale, so a phone in deep sleep never rested,
 * and one listening answered every one. Now:
 * <ul>
 * <li>a phone in standby is asked once a minute (every scan while FLEET has a mode waiting for it);</li>
 * <li>a phone in deep sleep or overwatch only as often as it checks in (the pilot: "for deep sleep and overwatch
 * phones no command from the PC's need to be faster than the check every interval"): one that checks in every N min
 * round its expected check-in (its last answer plus N min, {@link #WINDOW_EARLY_MS} before to {@link #WINDOW_LATE_MS}
 * after - its socket is closed in between), and at most once in N min otherwise; one listening once a minute;</li>
 * <li>one whose own check-in (its sleep hello) reaches this PC isn't asked ahead of it: only when that hello is
 * {@link #HELLO_GRACE_MS} late;</li>
 * <li>an address that didn't answer {@link #SILENT_AFTER} scans in a row is asked once a minute;</li>
 * <li>a resting phone keeps its last answer (online on the LINK card and the map) for its own check-in interval plus
 * {@link #HOLD_MARGIN_MS}, at least {@link #MIN_HOLD_MS} (the pilot: "at least 5 as the wake check can be set to
 * 5") - between check-ins its socket is closed and it answers nothing.</li>
 * </ul>
 * An awake device is asked every scan, as before. One thread (the scan's).
 */
final class ScanPace {
    static final long SLOW_ASK_MS = 60_000;
    static final int SILENT_AFTER = 3;
    static final long MIN_HOLD_MS = 5 * 60_000L, HOLD_MARGIN_MS = 2 * 60_000L;
    /** Power modes as the phones send them (PowerMode): standby, deep sleep, overwatch rest. */
    static final int STANDBY = 1, DEEP_SLEEP = 2, OVERWATCH = 3;

    /** Round a timed sleeper's expected check-in: its window opens late by up to its own 20 s, and alarms drift. */
    static final long WINDOW_EARLY_MS = 10_000, WINDOW_LATE_MS = 30_000;
    /**
     * 1.0.64: a sleeper whose hellos come here says its check-in itself (0.2, 1.7, 3.2 s into its window): asked only
     * when none came this long after it was due - the asks 10 and 5 s ahead woke its radio for nothing.
     */
    static final long HELLO_GRACE_MS = 5000;
    /**
     * 1.0.64: a resting phone's last word on its mode is fresh for {@link #SLOW_ASK_MS} and this long: it is asked once
     * in that time, and the scan and the answer take a moment.
     */
    static final long FRESH_MARGIN_MS = 15_000;

    private static final class Entry {
        long askedAt = Long.MIN_VALUE / 2, answeredAt = Long.MIN_VALUE / 2, heardAt = Long.MIN_VALUE / 2;
        int missed;
        boolean resting;
        /** 1.0.64: its last word was its own check-in (a sleep hello), not an answer to a query of this PC's. */
        boolean byHello;
        long holdMs;
        String name;
        int mode = Names.POWER_UNKNOWN, intervalMin;
    }

    private final Map<String, Entry> byIp = new HashMap<>();

    static boolean rests(int powerMode) {
        return powerMode == STANDBY || powerMode == DEEP_SLEEP || powerMode == OVERWATCH;
    }

    /** How long a resting phone with this check-in interval (SleepCheck's byte; 0 unknown) keeps its last answer. */
    static long holdMs(int intervalMin) {
        long own = intervalMin > 0 ? SleepCheck.checkInMinutes(intervalMin) * 60_000L : 0;
        return Math.max(MIN_HOLD_MS, own + HOLD_MARGIN_MS);
    }

    /** Whether to ask this address in the scan at now. */
    boolean due(String ip, long now) {
        Entry e = byIp.get(ip);
        if (e == null) return true;
        if (e.mode == DEEP_SLEEP || e.mode == OVERWATCH) {
            if (e.intervalMin <= 0 || SleepCheck.isAlways(e.intervalMin)) {
                // 1.0.64: its own hello came lately (one a minute while it listens): that is its answer - a query
                // of this PC's own woke its radio a second time each minute
                return now - e.heardAt >= HEARD_QUIET_MS && now - e.askedAt >= SLOW_ASK_MS;
            }
            long period = e.intervalMin * 60_000L, since = now - e.answeredAt;
            long phase = Math.floorMod(since, period);
            // 1.0.64: its hellos come here - nothing asked ahead of the check-in, only once its hello is late
            boolean early = !e.byHello && phase >= period - WINDOW_EARLY_MS;
            boolean late = phase <= WINDOW_LATE_MS && (!e.byHello || phase >= HELLO_GRACE_MS);
            boolean near = since >= period - WINDOW_EARLY_MS && (early || late);
            return near || now - e.askedAt >= (e.byHello ? period + HELLO_GRACE_MS : period);
        }
        if (e.resting || e.missed >= SILENT_AFTER) return now - e.askedAt >= SLOW_ASK_MS;
        return true;
    }

    /** Whether FLEET's waiting mode may have it asked at once: not a phone in deep sleep or overwatch (its check-in will do). */
    boolean mayHurry(String ip) {
        Entry e = byIp.get(ip);
        return e == null || e.mode != DEEP_SLEEP && e.mode != OVERWATCH;
    }

    void asked(String ip, long now) {
        byIp.computeIfAbsent(ip, k -> new Entry()).askedAt = now;
    }

    /** It answered: its power mode ({@link Names#POWER_UNKNOWN} for a PC or an older phone) and check-in interval. */
    void answered(String ip, long now, int powerMode, int intervalMin, String name) {
        Entry e = byIp.computeIfAbsent(ip, k -> new Entry());
        e.name = name;
        e.answeredAt = now;
        e.missed = 0;
        e.byHello = false;
        e.resting = rests(powerMode);
        e.mode = powerMode;
        e.intervalMin = intervalMin;
        e.holdMs = e.resting ? holdMs(intervalMin) : 0;
    }

    /** A listening sleeper whose hello came within this long isn't asked: the next hello is due a minute after. */
    static final long HEARD_QUIET_MS = 75_000;

    /**
     * 1.0.64: its check-in (the sleep hello, with its mode) reached this PC: as good as an answer to a query - and a
     * better mark of when a timed sleeper's window opens.
     * @param intervalMin the hello's own "next check in" (SleepCheck's byte; 0 not said: what is known stays). Without
     *                    it a timed sleeper known only by its hellos (this PC restarted) counted as listening and was
     *                    asked every minute
     */
    void heard(String ip, long now, int powerMode, int intervalMin) {
        Entry e = byIp.computeIfAbsent(ip, k -> new Entry());
        e.heardAt = now;
        e.askedAt = Math.max(e.askedAt, now); // nothing to ask: it just said it all
        e.answeredAt = now;
        e.missed = 0;
        if (rests(powerMode)) {
            e.resting = true;
            e.mode = powerMode;
            if (intervalMin > 0) e.intervalMin = intervalMin;
            e.byHello = powerMode != STANDBY;
            e.holdMs = holdMs(e.intervalMin);
        }
    }

    /** How long a phone's own word on this power mode counts as fresh (the badge, the map): see {@link #FRESH_MARGIN_MS}. */
    static long freshMs(int powerMode) {
        return rests(powerMode) ? SLOW_ASK_MS + FRESH_MARGIN_MS : AWAKE_FRESH_MS;
    }

    /** An awake phone answers every scan: its word is old after this (as the badge and the map had it for every mode). */
    static final long AWAKE_FRESH_MS = 30_000;

    /** The check-in interval it is paced by (SleepCheck's byte; 0 unknown). */
    int every(String ip) {
        Entry e = byIp.get(ip);
        return e == null ? 0 : e.intervalMin;
    }

    /** The name it last answered with, or null. */
    String name(String ip) {
        Entry e = byIp.get(ip);
        return e == null ? null : e.name;
    }

    /** Asked in this scan and not answered. */
    void unanswered(String ip) {
        Entry e = byIp.get(ip);
        if (e != null) e.missed++;
    }

    /** Resting phones that didn't answer this scan (or weren't asked) but are still counted online. */
    Set<String> held(long now) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Entry> m : byIp.entrySet()) {
            Entry e = m.getValue();
            if (e.resting && now - e.answeredAt <= e.holdMs) out.add(m.getKey());
        }
        return out;
    }

    /** Addresses no longer on the network are forgotten. */
    void retain(Set<String> online) {
        byIp.keySet().retainAll(online);
    }
}
