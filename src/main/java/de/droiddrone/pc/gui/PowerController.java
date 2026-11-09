package de.droiddrone.pc.gui;

import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.common.PowerFloor;
import de.droiddrone.common.SleepCheck;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.Names;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The phone's power modes as this PC keeps them (STANDBY / DEEP SLEEP / OVERWATCH): what the link asked for and what
 * the phone is in, and the phones known to be asleep with their check-ins. No Swing: the name service's and the link's
 * threads call {@link #checkIn}, MainWindow's refresh reads the rest and draws it.
 */
final class PowerController {
    /** What the asleep book needs from the window. Each may be called on any thread: it hops to the EDT itself. */
    interface Host {
        /** The name map's name at this address, or null. */
        String nameAt(String ip);

        /** OBSERVATION waits for this drone: it joins the grid at its check-in. */
        boolean observed(String name);

        /** An observed drone checked in: woken for observation. */
        void observedCheckIn(String ip, String name);

        /** A line for the log. */
        void logLater(String line);

        /** The asleep phones changed: their saved "name|ip|since|interval[|ow];...". */
        void saveAsleep(String saved);
    }

    /** The mode this PC asked for (ControlLink.POWER_*); the phone's PHONE_POWER record says what it is in. */
    volatile int wanted = ControlLink.POWER_NORMAL; // read on the stick thread (sticksPaused)
    long sentAt, askedAt;
    /** The phone said DEEP SLEEP and the link dropped: it sleeps, and this PC keeps quiet (ControlLink.setDormant). */
    volatile boolean phoneAsleep;
    /** The phone said OVERWATCH and the link dropped: it watches, and this PC keeps quiet as in deep sleep. */
    volatile boolean phoneOverwatching;
    /**
     * Waiting for a sleeping phone (DEEP SLEEP / OVERWATCH) to wake: no warning beep until the link is READY once (the
     * pilot, 1.0.51: the countdown to its check-in is no link loss).
     */
    volatile boolean awaitingWake;
    /** When the phone went to sleep: its listening windows come every check interval from then on. */
    long sleepStartAt;
    int checkIntervalMin = 5;
    /** The interval asked of the phone with the last sleep: CHECK EVERY, or 1 min for "0 min" to an app older than pc.39. */
    int sentIntervalMin = 5;
    /** The last "still here" from the sleeping phone that went to the log (one line per check). */
    long lastHelloLogged;

    /**
     * A phone left in deep sleep when this PC disconnected, or heard checking in while no link was open (1.0.41; the
     * pilot: "if a pilot sets a phone to deep sleep then disconnects there is no way to remotely wake it up"). It stays
     * on the LINK card with WAKE; its check-ins are answered "sleep on" (NameService) until WAKE connects, and the link
     * then answers the next one "wake". Kept in the settings, so it outlives a restart. Wall-clock times.
     */
    record Asleep(String name, String ip, long since, int intervalMin, boolean overwatch) {
        Asleep(String name, String ip, long since, int intervalMin) {
            this(name, ip, since, intervalMin, false);
        }

        /** What it is doing, for the lists: OVERWATCH (1.0.47) or DEEP SLEEP. */
        String mode() {
            return overwatch ? "OVERWATCH" : "DEEP SLEEP";
        }

        /** CHECK EVERY "0 min" (1.0.50, phone pc.39): its listening window never closes - WAKE and CONNECT reach it at once. */
        boolean listening() {
            return SleepCheck.isAlways(intervalMin);
        }
    }

    private final Host host;
    private final LongSupplier wall;
    /** When this window started (wall ms): a sleeping phone gets one check-in interval from here before it is late. */
    private final long startedAtWall;
    /** Every phone known to be asleep, by address; and its last check-in heard (receivedAt: wall clock). */
    private final Map<String, Asleep> asleepPhones = new ConcurrentHashMap<>();
    private final Map<String, ControlLink.SleepHello> asleepHellos = new ConcurrentHashMap<>();
    /** An always-listening phone's hello last logged (wall ms): its hellos come once a minute, the log hears one in ten. */
    private final Map<String, Long> listeningLogged = new ConcurrentHashMap<>();

    PowerController(Host host, LongSupplier wall) {
        this.host = host;
        this.wall = wall;
        this.startedAtWall = wall.getAsLong();
    }

    /** In STANDBY no sticks go out: the session sees "no radio" and, on waking, waits for a low throttle again. */
    boolean sticksPaused() {
        return wanted != ControlLink.POWER_NORMAL || phoneAsleep;
    }

    /** No warning beep while a sleeping phone is waited for and the link isn't READY yet. */
    static boolean quietWhileWaking(boolean awaitingWake, ControlLink.State state) {
        return awaitingWake && state != ControlLink.State.READY;
    }

    /**
     * How long until the waited-for sleeping phone checks in (ms), or -1 when it listens all along or is unknown.
     * @param hello the link's last sleep hello, or null
     */
    long wakeInMs(long now, ControlLink.SleepHello hello) {
        if (!phoneAsleep || SleepCheck.isAlways(sentIntervalMin)) return -1;
        long interval = SleepCheck.checkInMinutes(sentIntervalMin) * 60_000L;
        if (interval <= 0) return -1;
        long next = sleepStartAt + interval;
        if (hello != null && hello.receivedAt() >= sleepStartAt - 5000 && hello.nextCheckMin() > 0) {
            next = hello.receivedAt() + SleepCheck.checkInMinutes(hello.nextCheckMin()) * 60_000L;
        }
        while (next < now) next += interval;
        return next - now;
    }

    // ------------------------------------------------------------------ phones asleep while no link is open

    /** The saved list, read back at the start. */
    void load(String saved) {
        for (Asleep a : parseAsleep(saved)) asleepPhones.put(a.ip(), a);
    }

    /** The saved "name|ip|since|interval;...". */
    static List<Asleep> parseAsleep(String saved) {
        List<Asleep> out = new ArrayList<>();
        if (saved == null || saved.isBlank()) return out;
        for (String one : saved.split(";")) {
            String[] p = one.split("\\|");
            try {
                if ((p.length == 4 || p.length == 5) && !p[1].isBlank()) {
                    out.add(new Asleep(p[0], p[1], Long.parseLong(p[2]), Integer.parseInt(p[3]), p.length == 5 && "ow".equals(p[4])));
                }
            } catch (NumberFormatException ignored) {
                // a broken entry: left out
            }
        }
        return out;
    }

    /** The asleep phones as saved: name|ip|since|interval[|ow];... */
    String saved() {
        StringBuilder b = new StringBuilder();
        for (Asleep a : asleepPhones.values()) {
            if (b.length() > 0) b.append(';');
            b.append(a.name()).append('|').append(a.ip()).append('|').append(a.since()).append('|').append(a.intervalMin());
            if (a.overwatch()) b.append("|ow");
        }
        return b.toString();
    }

    private void save() {
        host.saveAsleep(saved());
    }

    /** The phone asleep at this address, or null. */
    Asleep asleep(String ip) {
        return asleepPhones.get(ip);
    }

    boolean isAsleep(String ip) {
        return asleepPhones.containsKey(ip);
    }

    boolean noneAsleep() {
        return asleepPhones.isEmpty();
    }

    Collection<Asleep> allAsleep() {
        return asleepPhones.values();
    }

    /** Its last check-in heard, or null. */
    ControlLink.SleepHello hello(String ip) {
        return asleepHellos.get(ip);
    }

    void remember(Asleep a) {
        asleepPhones.put(a.ip(), a);
        save();
    }

    void clear(String ip) {
        if (ip == null || asleepPhones.remove(ip) == null) return;
        asleepHellos.remove(ip);
        save();
    }

    /** Forgets every phone of this name (the tour's drones). */
    void forgetNamed(String name) {
        if (asleepPhones.values().removeIf(a -> name.equalsIgnoreCase(a.name()))) save();
    }

    /** How long a sleeping phone that answers name queries may go without a check-in before it counts as woken on the phone. */
    static long awakeAfterMs(Asleep a) {
        return a.listening() ? 150_000 : 60_000; // always listening: it answers queries all along, and says hello once a minute
    }

    /** When this PC last heard a sleeping phone: its last check-in, else when it went to sleep (wall ms). */
    long lastHeard(Asleep a) {
        ControlLink.SleepHello h = asleepHellos.get(a.ip());
        return h != null ? Math.max(h.receivedAt(), a.since()) : a.since();
    }

    /**
     * How long a sleeping phone is past the time it should have checked in by (ms; 0 or less: on time). "Late" is a
     * whole check-in missed and a minute more: the check it said it would make, then one interval of grace. A PC
     * started meanwhile gives it one interval from its own start before calling it late. Always listening (CHECK EVERY
     * 0 min) counts as a check every minute - it says hello once a minute.
     */
    long checkInOverdue(Asleep a, long now) {
        return checkInOverdue(a, asleepHellos.get(a.ip()), startedAtWall, now);
    }

    static long checkInOverdue(Asleep a, ControlLink.SleepHello h, long startedAtWall, long now) {
        long interval = SleepCheck.checkInMinutes(a.intervalMin()) * 60_000L;
        long expected = h != null && h.nextCheckMin() > 0
                ? h.receivedAt() + SleepCheck.checkInMinutes(h.nextCheckMin()) * 60_000L : a.since() + interval;
        expected = Math.max(expected, startedAtWall + interval);
        return now - (expected + interval + 60_000L);
    }

    /** When this sleeping phone checks for this PC next (wall clock). */
    long nextCheck(Asleep a, long now) {
        ControlLink.SleepHello h = asleepHellos.get(a.ip());
        long interval = SleepCheck.checkInMinutes(a.intervalMin()) * 60_000L;
        long next = h != null && h.nextCheckMin() > 0
                ? h.receivedAt() + SleepCheck.checkInMinutes(h.nextCheckMin()) * 60_000L : a.since() + interval;
        while (next < now) next += interval;
        return next;
    }

    /** For the lists: the countdown to its next check, or "listening" (always listening: no countdown). */
    String checkText(Asleep a, long now) {
        return a.listening() ? "listening" : clock(nextCheck(a, now) - now);
    }

    static String clock(long ms) {
        long left = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60);
    }

    /**
     * A sleeping phone's check-in that isn't this PC's link's own phone (the name service's thread with no link open,
     * or the link's for another phone): remembered for the LINK card and the PHONE list, and answered "sleep on" - WAKE
     * connects, and the link answers the next one "wake". A phone not known to be asleep is taken as asleep when it is
     * one of ours (it went to sleep under an older version, or before a restart).
     * @return false (sleep on), true (wake: observation wants it), or null for a phone that isn't ours (no answer)
     */
    Boolean checkIn(String ip, byte[] d) {
        Asleep a = asleepPhones.get(ip);
        if (a == null) {
            String name = host.nameAt(ip);
            if (name == null || Names.kindOf(name) != Names.Kind.PHONE) return null;
            a = new Asleep(name, ip, wall.getAsLong(), Math.max(1, d[3] & 0xff),
                    d.length >= 7 && (d[6] & OverwatchProtocol.HELLO_OVERWATCH) != 0);
            remember(a);
        }
        ControlLink.SleepHello h = ControlLink.SleepHello.read(d, d.length, wall.getAsLong());
        if (h.nextCheckMin() > 0 && h.nextCheckMin() != a.intervalMin()) { // the phone's own word: listening, or its interval
            a = new Asleep(a.name(), a.ip(), a.since(), h.nextCheckMin(), a.overwatch());
            remember(a);
        }
        if (d.length >= 7 && h.overwatch() != a.overwatch()) { // into overwatch, or out of it by itself (the floor, 1.0.47)
            Asleep was = a;
            a = new Asleep(a.name(), a.ip(), a.since(), a.intervalMin(), h.overwatch());
            remember(a);
            if (was.overwatch()) {
                String why = h.floorReason() != 0 ? PowerFloor.describe(h.floorReason()) : "it left overwatch";
                host.logLater("OVERWATCH ended on " + was.name() + ": " + why + " - now in deep sleep.");
            }
        }
        ControlLink.SleepHello before = asleepHellos.put(ip, h);
        if (host.observed(a.name())) { // OBSERVATION (1.0.48): it joins the grid at its check-in
            host.observedCheckIn(ip, a.name());
            return true;
        }
        // one line per check window; always listening (a hello a minute), one line in ten minutes
        long quiet = a.listening() ? 10 * 60_000L : 20_000;
        if (before == null || h.receivedAt() - before.receivedAt() > quiet
                || a.listening() && h.receivedAt() - listeningLogged.getOrDefault(ip, 0L) > quiet) {
            if (a.listening()) listeningLogged.put(ip, h.receivedAt());
            host.logLater(a.name() + (a.overwatch() ? " overwatching" : " asleep") + ": battery " + (h.batteryPercent() >= 0 ? h.batteryPercent() + " %" : "unknown")
                    + (h.charging() ? ", charging" : "") + (a.listening() ? ", listening." : ", next check in " + h.nextCheckMin() + " min."));
        }
        return false;
    }

    // ------------------------------------------------------------------ words for the LINK card

    static String modeWord(int mode) {
        return mode == ControlLink.POWER_OVERWATCH ? "OVERWATCH" : mode == ControlLink.POWER_DEEP_SLEEP ? "DEEP SLEEP"
                : mode == ControlLink.POWER_STANDBY ? "STANDBY" : "NORMAL";
    }

    /** "Checks in every 5 min", or "Always listening" (0 min). */
    static String listensText(int intervalMin) {
        return SleepCheck.isAlways(intervalMin) ? "Always listening: PILOT UAV reaches it at once"
                : "Checks in every " + intervalMin + " min";
    }

    /**
     * The LINK card's top button (1.0.52, the pilot: "only ever three buttons"): PILOT UAV to fly; STANDBY MODE while
     * connected (the phone is left in standby); REGAINING CONTROL IN x MIN while a sleeping phone is waited for (pressing
     * it lets the phone sleep on). Watching and observing keep their own words.
     */
    static String connectText(boolean connected, boolean spectatorLink, boolean observing, boolean observerRole, boolean spectatorRole,
                              boolean waitingForWake, long wakeInMs) {
        if (observing) return "STOP OBSERVING";
        if (!connected) return observerRole ? "OBSERVE" : spectatorRole ? "WATCH" : "PILOT UAV";
        if (spectatorLink) return "STOP WATCHING";
        if (waitingForWake) {
            if (wakeInMs < 0) return "REGAINING CONTROL...";
            return "REGAINING CONTROL IN " + Math.max(1, (wakeInMs + 59_999) / 60_000) + " MIN";
        }
        return "STANDBY MODE";
    }

    /** The mode the chosen phone is in now, or -1 when unknown. */
    static int shownPowerMode(ControlLink link, Asleep chosenAsleep, boolean asleep, boolean overwatching) {
        if (link == null) {
            return chosenAsleep == null ? -1 : chosenAsleep.overwatch() ? ControlLink.POWER_OVERWATCH : ControlLink.POWER_DEEP_SLEEP;
        }
        if (link.isSpectator()) return -1;
        if (asleep) return overwatching ? ControlLink.POWER_OVERWATCH : ControlLink.POWER_DEEP_SLEEP;
        FcTelemetry.PhonePower pp = link.getTelemetry().getPhonePower();
        return pp == null ? -1 : pp.mode();
    }
}
