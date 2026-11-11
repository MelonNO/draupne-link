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
    /** CHECK EVERY for DEEP SLEEP (5 min unless chosen). */
    int checkIntervalMin = 5;
    /** CHECK EVERY for OVERWATCH (1.0.56, the pilot: Constant unless chosen - an alert wakes it, PILOT UAV reaches it at once). */
    int overwatchIntervalMin = defaultInterval(ControlLink.POWER_OVERWATCH);
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
        return wakeInMs(now, System.currentTimeMillis(), hello);
    }

    long wakeInMs(long now, long wallNow, ControlLink.SleepHello hello) {
        if (!phoneAsleep || SleepCheck.isAlways(sentIntervalMin)) return -1;
        long next = nextCheckMono(now, wallNow, hello);
        return next < 0 ? -1 : next - now;
    }

    /** The phone PILOT UAV waits for (its IP), or null: its check-in heard before the link opened counts too. */
    volatile String awaitedIp;

    /**
     * When the waited-for phone checks in next (MonoClock), or -1 (always listening, no interval). One answer for the
     * LINK card's button and line and FLEET's card (2026-11-11, the pilot: they differed after this PC had been off the
     * internet and the phone had checked in once before PILOT UAV): the newest check-in heard - the link's own hello
     * or the one the name service took with no link open - else the windows every interval from the sleep's start.
     * @param linkHello the link's last sleep hello (MonoClock), or null
     */
    long nextCheckMono(long now, long wallNow, ControlLink.SleepHello linkHello) {
        long interval = SleepCheck.checkInMinutes(sentIntervalMin) * 60_000L;
        if (SleepCheck.isAlways(sentIntervalMin) || interval <= 0) return -1;
        long next = sleepStartAt + interval, heardAt = Long.MIN_VALUE;
        if (linkHello != null && linkHello.receivedAt() >= sleepStartAt - 5000 && linkHello.nextCheckMin() > 0) {
            heardAt = linkHello.receivedAt();
            next = heardAt + SleepCheck.checkInMinutes(linkHello.nextCheckMin()) * 60_000L;
        }
        String ip = awaitedIp;
        ControlLink.SleepHello before = ip == null ? null : asleepHellos.get(ip);
        if (before != null && before.nextCheckMin() > 0) {
            long at = now - (wallNow - before.receivedAt()); // heard with no link open: wall clock
            if (at >= sleepStartAt - 5000 && at > heardAt) next = at + SleepCheck.checkInMinutes(before.nextCheckMin()) * 60_000L;
        }
        while (next < now) next += interval;
        return next;
    }

    /**
     * The link's hello from the waited-for phone, kept as its last check-in too (wall clock), so FLEET's card counts
     * from the same one as the LINK card.
     */
    /**
     * 1.0.64: whether the link holds its Connects back while waking a phone that checks in every intervalMin: yes
     * except from {@link #CONNECT_EARLY_MS} before its expected check-in to {@link #CONNECT_LATE_MS} after it (its window
     * opens late by up to its own length, and an alarm drifts). Listening, or not waking it: never held.
     * @param next the expected check-in (nextCheckMono), past now
     */
    static boolean holdConnects(boolean awaitingWake, boolean listening, int intervalMin, long now, long next) {
        if (!awaitingWake || listening || intervalMin <= 0 || next <= 0) return false;
        long period = intervalMin * 60_000L;
        boolean near = now >= next - CONNECT_EARLY_MS || now <= next - period + CONNECT_LATE_MS;
        return !near;
    }

    static final long CONNECT_EARLY_MS = 5000, CONNECT_LATE_MS = 40_000;

    void linkHello(String ip, ControlLink.SleepHello h, long now, long wallNow) {
        if (ip == null || h == null) return;
        long wallAt = wallNow - (now - h.receivedAt());
        ControlLink.SleepHello before = asleepHellos.get(ip);
        if (before != null && before.receivedAt() >= wallAt - 1000) return;
        asleepHellos.put(ip, new ControlLink.SleepHello(h.batteryPercent(), h.charging(), h.nextCheckMin(), h.milliVolts(), wallAt,
                h.owFlags(), h.floorReason(), h.status()));
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

    /** An answer to a name query this fresh is the phone's own, not one the scan holds for a resting phone. */
    static final long ANSWER_FRESH_MS = 20_000;

    /**
     * A phone filed asleep that was woken on the phone itself: it answers name queries now, and hasn't checked in (nor
     * gone to sleep) for {@link #awakeAfterMs}. 1.0.64: "answers now" is a real answer within {@link #ANSWER_FRESH_MS}
     * - the scan keeps a resting phone online between its check-ins (ScanPace), and that alone made every timed
     * sleeper "awake again" a minute after each check-in, its sleep state and countdown gone. And never when that
     * answer itself said deep sleep or overwatch (saidMode; a PC the phone's hellos don't go to hears only those).
     */
    static boolean wokenOnThePhone(Asleep a, de.droiddrone.pc.Names.Entry e, ControlLink.SleepHello h, int saidMode, long now) {
        if (a == null || e == null || !e.online() || now - e.lastSeen() > ANSWER_FRESH_MS) return false;
        if (saidMode == 2 || saidMode == 3) return false; // PowerMode's DEEP_SLEEP, OVERWATCH
        long quiet = awakeAfterMs(a);
        return (h == null || now - h.receivedAt() > quiet) && now - a.since() > quiet;
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

    /** When each phone's sleep notice was last said (it comes three times). */
    private final Map<String, Long> noticeSaid = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 1.0.64 / phone pc.50, the sleep notice (FleetProtocol.Notice, in the phone's countdown): it sleeps from now, its
     * next check counts from now, its battery and mode are known at once - not at its first check-in, N minutes on.
     * Standby isn't asleep (it stays reachable): only said. Said once per notice (three come).
     * @return false (sleep on - the phone takes no answer in its countdown), or null for a phone that isn't ours
     */
    private Boolean notice(String ip, byte[] d) {
        Asleep old = asleepPhones.get(ip);
        String name = old != null ? old.name() : host.nameAt(ip);
        if (name == null || Names.kindOf(name) != Names.Kind.PHONE) return null;
        long now = wall.getAsLong();
        ControlLink.SleepHello h = ControlLink.SleepHello.read(d, d.length, now);
        int mode = h.status() != null && h.status().mode >= 0 ? h.status().mode : h.overwatch() ? 3 : 2;
        Long said = noticeSaid.put(ip, now);
        boolean say = said == null || now - said > 20_000;
        String battery = h.batteryPercent() >= 0 ? ", battery " + h.batteryPercent() + " %" : "";
        if (mode == 1) { // standby
            if (say) host.logLater(name + ": standby" + battery + ".");
            return null;
        }
        boolean overwatch = mode == 3;
        Asleep a = new Asleep(name, ip, old != null && say == false ? old.since() : now, Math.max(1, d[3] & 0xff), overwatch);
        remember(a);
        asleepHellos.put(ip, h); // the countdown from now
        if (say) {
            host.logLater(name + (overwatch ? ": overwatch" : ": deep sleep") + (a.listening() ? ", listening" : ", next check " + checkText(a, now))
                    + battery + ".");
        }
        return false;
    }

    /**
     * A sleeping phone's check-in that isn't this PC's link's own phone (the name service's thread with no link open,
     * or the link's for another phone): remembered for the LINK card and the PHONE list, and answered "sleep on" - WAKE
     * connects, and the link answers the next one "wake". A phone not known to be asleep is taken as asleep when it is
     * one of ours (it went to sleep under an older version, or before a restart).
     * @return false (sleep on), true (wake: observation wants it), or null for a phone that isn't ours (no answer)
     */
    Boolean checkIn(String ip, byte[] d) {
        if (d.length >= 7 && (d[6] & OverwatchProtocol.HELLO_NOTICE) != 0) return notice(ip, d);
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
        // pc.47: an overwatching phone whose camera gives its motion detection no pictures sees nothing - said at once
        // and when it changes, not in ten minutes
        boolean blind = h.watchesBlind(), wasBlind = before != null && before.watchesBlind();
        if (blind != wasBlind && (blind || before != null)) {
            host.logLater(a.name() + (blind ? ": OVERWATCH SEES NOTHING - its camera gives the motion detection no pictures. PILOT UAV and OVERWATCH again."
                    : ": overwatch's camera gives pictures again."));
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

    /** CHECK EVERY when none was chosen: Constant for OVERWATCH, 5 min for the rest. */
    static int defaultInterval(int mode) {
        return mode == ControlLink.POWER_OVERWATCH ? SleepCheck.ALWAYS : 5;
    }

    /** The interval this mode goes with. */
    int intervalFor(int mode) {
        return mode == ControlLink.POWER_OVERWATCH ? overwatchIntervalMin : checkIntervalMin;
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
     * connected (the phone is left in standby); REGAINING CONTROL IN m:ss while a sleeping phone is waited for (pressing
     * it lets the phone sleep on). Watching and observing keep their own words.
     */
    static String connectText(boolean connected, boolean spectatorLink, boolean observing, boolean observerRole, boolean spectatorRole,
                              boolean waitingForWake, long wakeInMs) {
        if (observing) return "STOP OBSERVING";
        if (!connected) return observerRole ? "OBSERVE" : spectatorRole ? "WATCH" : "PILOT UAV";
        if (spectatorLink) return "STOP WATCHING";
        if (waitingForWake) {
            if (wakeInMs < 0) return "REGAINING CONTROL...";
            // counting down in seconds (2026-11-10, the pilot; was whole minutes)
            long s = Math.max(1, (wakeInMs + 999) / 1000);
            return "REGAINING CONTROL IN " + s / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60);
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
