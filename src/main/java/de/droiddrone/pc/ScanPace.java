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

    private static final class Entry {
        long askedAt = Long.MIN_VALUE / 2, answeredAt = Long.MIN_VALUE / 2;
        int missed;
        boolean resting;
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
            if (e.intervalMin <= 0 || SleepCheck.isAlways(e.intervalMin)) return now - e.askedAt >= SLOW_ASK_MS;
            long period = e.intervalMin * 60_000L, since = now - e.answeredAt;
            long phase = Math.floorMod(since, period);
            boolean near = since >= period - WINDOW_EARLY_MS && (phase >= period - WINDOW_EARLY_MS || phase <= WINDOW_LATE_MS);
            return near || now - e.askedAt >= period;
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
        e.resting = rests(powerMode);
        e.mode = powerMode;
        e.intervalMin = intervalMin;
        e.holdMs = e.resting ? holdMs(intervalMin) : 0;
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
