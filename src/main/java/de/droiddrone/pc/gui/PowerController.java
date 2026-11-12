package de.droiddrone.pc.gui;

import de.droiddrone.common.FleetProtocol;
import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.common.PowerFloor;
import de.droiddrone.common.SleepCheck;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.Names;
import de.droiddrone.pc.overwatch.OverwatchHub;

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
 * threads call {@link #checkIn}, the name scan's {@link #nameReply} (1.0.64: each phone's own word on its mode), MainWindow's
 * refresh reads the rest and draws it.
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
     * <p>
     * setHere (1.0.64): this PC set the sleep (its link's command, its FLEET mode) - the phone checks in with it, and
     * only it answers "sleep on". False for a sleep this PC only heard of: another PC's, by the phone's sleep notice,
     * its hello (pc.50 says hello to every PC that knows it) or its answer to a name query.
     */
    record Asleep(String name, String ip, long since, int intervalMin, boolean overwatch, boolean setHere) {
        Asleep(String name, String ip, long since, int intervalMin, boolean overwatch) {
            this(name, ip, since, intervalMin, overwatch, true);
        }

        Asleep(String name, String ip, long since, int intervalMin) {
            this(name, ip, since, intervalMin, false, true);
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

    /** 1.0.64: told of each sleeping phone's check-in (any thread): its address, its mode (2, 3) and its "next check in". */
    interface CheckIns {
        void heard(String ip, int powerMode, int nextCheckMin);
    }

    private volatile CheckIns checkIns;
    /** 1.0.64: the phone app's build by the phone's name (-1 unknown). */
    private volatile java.util.function.ToIntFunction<String> buildOf = name -> -1;
    /** 1.0.64: whether a FLEET token (the phone's status says the last mode it took) is one of this PC's. */
    private volatile java.util.function.BiPredicate<String, Integer> ownToken = (name, token) -> false;

    /** The name scan paces its queries by the check-ins (NameService.heardCheckIn). */
    void setCheckIns(CheckIns listener) {
        checkIns = listener;
    }

    void setBuildOf(java.util.function.ToIntFunction<String> builds) {
        buildOf = builds == null ? name -> -1 : builds;
    }

    void setOwnToken(java.util.function.BiPredicate<String, Integer> own) {
        ownToken = own == null ? (name, token) -> false : own;
    }

    private void tellCheckIn(String ip, int powerMode, int nextCheckMin) {
        CheckIns l = checkIns;
        if (l != null) l.heard(ip, powerMode, nextCheckMin);
    }

    /**
     * 1.0.64: the first phone app that says its sleep hello to every PC that knows it. An older one says it only to the
     * PC that set its sleep - so a hello from one proves this PC is that PC.
     */
    static final int HELLO_TO_ALL_PC_BUILD = 51; // the release; pc.50 was test builds only, made before this (they say it to one PC)

    /** Whether a hello from this phone reaches every PC that knows it (so proves nothing): pc.50 on; never the virtual fleet's drones. */
    private boolean helloToAll(String ip, String name) {
        return !de.droiddrone.pc.fleet.VirtualFleet.isVirtual(ip) && buildOf.applyAsInt(name) >= HELLO_TO_ALL_PC_BUILD;
    }

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
        if (!phoneAsleep) waitKey = null; // asked every refresh: the next wait is a new one
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
        firstCheck = next;
        while (next < now) next += interval;
        return next;
    }

    /** The check-in nextCheckMono counted from before it rolled on (MonoClock); the caller's thread only. */
    private long firstCheck;
    /** A check-in this late was missed (the phone's window opens up to some seconds late, and its hello takes a moment). */
    static final long MISSED_AFTER_MS = 30_000;

    /**
     * 1.0.64: the check-ins the waited-for phone has missed - the countdown ran to 0:01 and started again at 4:59
     * without a word, as if nothing had happened. 0 while it is on time, listens all along or isn't waited for.
     */
    int missedCheckIns(long now, long wallNow, ControlLink.SleepHello linkHello) {
        if (!phoneAsleep) waitKey = null;
        if (!phoneAsleep || nextCheckMono(now, wallNow, linkHello) < 0) return 0;
        long interval = SleepCheck.checkInMinutes(sentIntervalMin) * 60_000L;
        // 1.0.64: only check-ins this PC waited for. After a restart, or on a PC the phone doesn't check in with, the
        // first one counted from lay far back (the sleep's start, a hello from before): "missed its check-in (12 now)"
        // the moment PILOT UAV was pressed. Counted from that one only when it was still to come as the wait began;
        // else the phone gets one whole interval from then
        long since = waitingSince(now);
        long first = firstCheck >= since ? firstCheck : since + interval;
        long late = now - first - MISSED_AFTER_MS;
        return late < 0 ? 0 : (int) (late / interval) + 1;
    }

    /** 1.0.64: which wait this is (the phone and its sleep), and when this PC began it (MonoClock); the caller's thread only. */
    private String waitKey;
    private long waitBegan;

    private long waitingSince(long now) {
        String key = awaitedIp + "|" + sleepStartAt;
        if (!key.equals(waitKey)) {
            waitKey = key;
            waitBegan = now;
        }
        return waitBegan;
    }

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

    /**
     * 1.0.64: as above, and never held once a check-in was missed. The expected check-in is the last hello rolled on by
     * whole intervals; the phone's next alarm counts from where its window closed - an unanswered window (10 s; 20 s
     * before pc.50) later each time - and Doze holds alarms back: after a few unheard check-ins no Connect met a
     * window any more, and the phone couldn't be woken. Missed: Connects all along until it is heard again.
     * @param missed the check-ins missed ({@link #missedCheckIns})
     */
    static boolean holdConnects(boolean awaitingWake, boolean listening, int intervalMin, long now, long next, int missed) {
        return missed <= 0 && holdConnects(awaitingWake, listening, intervalMin, now, next);
    }

    /**
     * 1.0.64: the whole rule for the link, each refresh while a sleeping phone is waited for: held only once its own
     * check-in was heard on this link (the window known), not once one was missed, and not while the phone answers
     * this PC's name queries awake (woken elsewhere: nothing to wait for).
     * @param linkHello the link's last sleep hello (MonoClock), or null
     */
    boolean holdConnects(long now, long wallNow, ControlLink.SleepHello linkHello) {
        if (!phoneAsleep || linkHello == null || linkHello.receivedAt() < sleepStartAt - 5000) return false;
        String ip = awaitedIp;
        if (ip != null && saidAwake.containsKey(ip)) return false;
        long next = nextCheckMono(now, wallNow, linkHello);
        return holdConnects(awaitingWake, SleepCheck.isAlways(sentIntervalMin), sentIntervalMin, now, next, missedCheckIns(now, wallNow, linkHello));
    }

    /**
     * Late side: the window opens late by up to its own length and an alarm drifts (40 s) - and (1.0.64) one unanswered
     * window more, which sets the next alarm from its own close (20 s before pc.50).
     */
    static final long CONNECT_EARLY_MS = 5000, CONNECT_LATE_MS = 60_000;

    /**
     * The link's hello from the waited-for phone, kept as its last check-in too (wall clock), so FLEET's card counts
     * from the same one as the LINK card.
     */
    void linkHello(String ip, ControlLink.SleepHello h, long now, long wallNow) {
        if (ip == null || h == null) return;
        long wallAt = wallNow - (now - h.receivedAt());
        ControlLink.SleepHello before = asleepHellos.get(ip);
        if (before != null && before.receivedAt() >= wallAt - 1000) return;
        asleepHellos.put(ip, new ControlLink.SleepHello(h.batteryPercent(), h.charging(), h.nextCheckMin(), h.milliVolts(), wallAt,
                h.owFlags(), h.floorReason(), h.status()));
        saidAwake.remove(ip);
        tellCheckIn(ip, h.overwatch() ? 3 : 2, h.nextCheckMin()); // 1.0.64: the scan paces its queries by it
    }

    // ------------------------------------------------------------------ phones asleep while no link is open

    /** The saved list, read back at the start. */
    void load(String saved) {
        for (Asleep a : parseAsleep(saved)) asleepPhones.put(a.ip(), a);
    }

    /** The saved "name|ip|since|interval[|ow][|other];..." (other, 1.0.64: not set by this PC; without it: set here, as all were). */
    static List<Asleep> parseAsleep(String saved) {
        List<Asleep> out = new ArrayList<>();
        if (saved == null || saved.isBlank()) return out;
        for (String one : saved.split(";")) {
            String[] p = one.split("\\|");
            try {
                if (p.length >= 4 && p.length <= 6 && !p[1].isBlank()) {
                    boolean overwatch = false, other = false;
                    for (int i = 4; i < p.length; i++) {
                        if ("ow".equals(p[i])) overwatch = true;
                        if ("other".equals(p[i])) other = true;
                    }
                    out.add(new Asleep(p[0], p[1], Long.parseLong(p[2]), Integer.parseInt(p[3]), overwatch, !other));
                }
            } catch (NumberFormatException ignored) {
                // a broken entry: left out
            }
        }
        return out;
    }

    /** The asleep phones as saved: name|ip|since|interval[|ow][|other];... */
    String saved() {
        StringBuilder b = new StringBuilder();
        for (Asleep a : asleepPhones.values()) {
            if (b.length() > 0) b.append(';');
            b.append(a.name()).append('|').append(a.ip()).append('|').append(a.since()).append('|').append(a.intervalMin());
            if (a.overwatch()) b.append("|ow");
            if (!a.setHere()) b.append("|other");
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
        // 1.0.64: the same sleep (its start) filed again keeps whose it is - the window copies a record without that
        Asleep old = asleepPhones.get(a.ip());
        if (old != null && !old.setHere() && a.setHere() && old.since() == a.since()) {
            a = new Asleep(a.name(), a.ip(), a.since(), a.intervalMin(), a.overwatch(), false);
        }
        file(a);
    }

    /** The record as it is, into the book and the settings. */
    private void file(Asleep a) {
        asleepPhones.put(a.ip(), a);
        save();
    }

    void clear(String ip) {
        forget(ip);
    }

    /** @return true when a phone was asleep at this address */
    private boolean forget(String ip) {
        if (ip == null || asleepPhones.remove(ip) == null) return false;
        asleepHellos.remove(ip);
        blindSaid.remove(ip);
        floorDropped.remove(ip);
        windowFirstAt.remove(ip);
        save();
        return true;
    }

    /** Forgets every phone of this name (the tour's drones). */
    void forgetNamed(String name) {
        if (asleepPhones.values().removeIf(a -> name.equalsIgnoreCase(a.name()))) save();
    }

    /** 1.0.64: a rename (the name map's): its sleep record under the new name - it stayed under the old one. */
    void renamed(String from, String to) {
        if (from == null || to == null || from.equalsIgnoreCase(to)) return;
        boolean any = false;
        for (Asleep a : asleepPhones.values()) {
            if (!a.name().equalsIgnoreCase(from)) continue;
            asleepPhones.put(a.ip(), new Asleep(to, a.ip(), a.since(), a.intervalMin(), a.overwatch(), a.setHere()));
            any = true;
        }
        if (any) save();
    }

    // ------------------------------------------------------------------ the phones' own word to the name scan (1.0.64)

    /** A sleeping phone that says "awake" this soon after its sleep began or its check-in: an answer from just before. */
    static final long AWAKE_WORD_AFTER_MS = 2000;
    /** One check window's hellos and answers come within this of its first (it is open 10 s; 20 s before pc.50). */
    static final long WINDOW_MS = 30_000;
    /** When each phone last answered a name query awake (normal, standby), wall ms; gone once it sleeps again. */
    private final Map<String, Long> saidAwake = new ConcurrentHashMap<>();

    /**
     * 1.0.64: a phone's own, fresh answer to this PC's name query (the scan's thread; never an answer the scan only
     * holds for a resting phone), for ANY phone - the books of a PC that didn't set the sleep went wrong:
     * <ul>
     * <li>it says deep sleep or overwatch: a check-in heard. A timed sleeper answers only in its check window, so its
     * next check counts from now; the record stays alive (it read MISSED CHECK-IN / UNKNOWN after two intervals on a
     * PC its hellos don't go to, though it answered that PC every window). Not in the book yet: filed, as its hello
     * would be - not as set here;</li>
     * <li>it says normal or standby: woken by another PC or on the phone - the record goes (it stayed ASLEEP, then
     * UNKNOWN, while the phone answered every 5 s; only the chosen phone's was ever cleared).</li>
     * </ul>
     * @param mode   the power mode it says (0-3; anything else: an older phone that says none - nothing done)
     * @param status its fleet status (pc.40 on), or null
     */
    void nameReply(String ip, String name, int mode, FleetProtocol.Status status) {
        if (ip == null) return;
        long now = wall.getAsLong();
        Asleep a = asleepPhones.get(ip);
        if (mode == 0 || mode == 1) {
            saidAwake.put(ip, now);
            if (a == null || now - lastHeard(a) < AWAKE_WORD_AFTER_MS || !forget(ip)) return;
            // this PC's own link is waking it: the window says "Phone awake"
            if (!(phoneAsleep && ip.equals(awaitedIp))) host.logLater(mode == 1 ? a.name() + ": standby." : a.name() + " is awake again.");
            return;
        }
        if (mode != 2 && mode != 3) return;
        saidAwake.remove(ip);
        boolean overwatch = mode == 3;
        int said = status != null && status.intervalMin > 0 ? status.intervalMin : 0;
        ControlLink.SleepHello before = asleepHellos.get(ip);
        int battery = status != null ? status.batteryPercent : before != null ? before.batteryPercent() : -1;
        if (a == null) {
            // an older phone says its mode but not its interval: nothing to count a check-in by
            if (said <= 0 || name == null || Names.kindOf(name) != Names.Kind.PHONE) return;
            a = new Asleep(name, ip, now, said, overwatch, false);
            file(a);
            host.logLater(name + (overwatch ? ": overwatch" : ": deep sleep")
                    + (a.listening() ? ", listening" : ", next check " + clock(SleepCheck.checkInMinutes(said) * 60_000L))
                    + (battery >= 0 ? ", battery " + battery + " %" : "") + ".");
        } else if (said > 0 && said != a.intervalMin() || overwatch != a.overwatch()) {
            if (a.overwatch() && !overwatch) {
                floorDropped.put(ip, 0);
                host.logLater("OVERWATCH ended on " + a.name() + ": it left overwatch - now in deep sleep.");
            }
            if (overwatch) floorDropped.remove(ip);
            a = new Asleep(a.name(), ip, a.since(), said > 0 ? said : a.intervalMin(), overwatch, a.setHere());
            file(a);
        }
        // one check-in a window: its hello, or an answer earlier in the same window, counts already
        if (before != null && now - before.receivedAt() < WINDOW_MS) return;
        // what its last hello said of its camera and its floor stays said (the answer says nothing of them)
        int flags = before == null ? 0 : before.owFlags() & ~(OverwatchProtocol.HELLO_OVERWATCH | OverwatchProtocol.HELLO_NOTICE);
        asleepHellos.put(ip, new ControlLink.SleepHello(battery, status != null ? status.charging : before != null && before.charging(),
                a.intervalMin(), before == null ? 0 : before.milliVolts(), now, flags | (overwatch ? OverwatchProtocol.HELLO_OVERWATCH : 0),
                before == null ? 0 : before.floorReason(), status));
    }

    // ------------------------------------------------------------------ what its check-ins say of its overwatch (1.0.64)

    /** Whether each overwatching phone's last hello that said so called its camera blind, and when (wall ms). */
    private final Map<String, long[]> blindSaid = new ConcurrentHashMap<>();
    /** Phones that left overwatch for deep sleep by themselves (the floor): PowerFloor's reason, 0 not said. */
    private final Map<String, Integer> floorDropped = new ConcurrentHashMap<>();

    /**
     * 1.0.64, for the lists: what this sleeping phone's check-ins say is wrong with its overwatch - BLIND (it
     * overwatches and its camera gives the motion detection no pictures, pc.47), DROPPED (it left overwatch for deep
     * sleep by itself), else NONE; with PowerFloor's reason for DROPPED and when it was said (wall ms). Null when the
     * check-ins say nothing (not asleep here, or no hello yet). Any thread. The hub's own word comes at once
     * ({@link OverwatchHub#word}); FleetController.overwatchTrouble takes the newer of the two.
     */
    OverwatchHub.Word watchWord(String ip) {
        Asleep a = ip == null ? null : asleepPhones.get(ip);
        if (a == null) return null;
        Integer dropped = floorDropped.get(ip);
        if (!a.overwatch()) return dropped == null ? null : new OverwatchHub.Word(OverwatchHub.Trouble.DROPPED, dropped, lastHeard(a));
        long[] blind = blindSaid.get(ip);
        return blind == null ? null : new OverwatchHub.Word(blind[0] != 0 ? OverwatchHub.Trouble.BLIND : OverwatchHub.Trouble.NONE, 0, blind[1]);
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
            if (old != null) clear(ip); // 1.0.64: no longer asleep - its DEEP SLEEP stayed on the LINK card
            return null;
        }
        boolean overwatch = mode == 3;
        // whose sleep: a new one is this PC's when the mode the phone took last is one this PC queued (FLEET); the
        // link's own command files it again as set here (the window, at its disconnect). The notice's repeats keep it
        boolean here = say || old == null ? h.status() != null && ownToken.test(name, h.status().token) : old.setHere();
        Asleep a = new Asleep(name, ip, old != null && say == false ? old.since() : now, Math.max(1, d[3] & 0xff), overwatch, here);
        file(a);
        asleepHellos.put(ip, h); // the countdown from now
        saidAwake.remove(ip);
        if (say) { // a new sleep: what the last one's check-ins said is over
            blindSaid.remove(ip);
            floorDropped.remove(ip);
        }
        tellCheckIn(ip, mode, a.intervalMin());
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
     * @return false (sleep on), true (wake: observation wants it), or null - no answer: a phone that isn't ours, or
     * (1.0.64) a "sleep on" that isn't this PC's to give, or not yet ({@link #sleepOn})
     */
    Boolean checkIn(String ip, byte[] d) {
        if (d.length >= 7 && (d[6] & OverwatchProtocol.HELLO_NOTICE) != 0) return notice(ip, d);
        Asleep a = asleepPhones.get(ip);
        if (a == null) {
            String name = host.nameAt(ip);
            if (name == null || Names.kindOf(name) != Names.Kind.PHONE) return null;
            // 1.0.64: whose sleeper - an older phone says hello only to the PC that set its sleep; pc.50 to every PC
            a = new Asleep(name, ip, wall.getAsLong(), Math.max(1, d[3] & 0xff),
                    d.length >= 7 && (d[6] & OverwatchProtocol.HELLO_OVERWATCH) != 0, !helloToAll(ip, name));
            file(a);
        } else if (!a.setHere() && !helloToAll(ip, a.name())) {
            a = new Asleep(a.name(), a.ip(), a.since(), a.intervalMin(), a.overwatch(), true);
            file(a);
        }
        ControlLink.SleepHello h = ControlLink.SleepHello.read(d, d.length, wall.getAsLong());
        if (h.nextCheckMin() > 0 && h.nextCheckMin() != a.intervalMin()) { // the phone's own word: listening, or its interval
            a = new Asleep(a.name(), a.ip(), a.since(), h.nextCheckMin(), a.overwatch(), a.setHere());
            file(a);
        }
        if (d.length >= 7 && h.overwatch() != a.overwatch()) { // into overwatch, or out of it by itself (the floor, 1.0.47)
            Asleep was = a;
            a = new Asleep(a.name(), a.ip(), a.since(), a.intervalMin(), h.overwatch(), a.setHere());
            file(a);
            if (was.overwatch()) {
                String why = h.floorReason() != 0 ? PowerFloor.describe(h.floorReason()) : "it left overwatch";
                host.logLater("OVERWATCH ended on " + was.name() + ": " + why + " - now in deep sleep.");
                floorDropped.put(ip, h.floorReason());
            } else {
                floorDropped.remove(ip);
            }
        }
        ControlLink.SleepHello before = asleepHellos.put(ip, h);
        saidAwake.remove(ip);
        tellCheckIn(ip, a.overwatch() ? 3 : 2, a.intervalMin()); // 1.0.64: the scan paces its queries by it
        if (host.observed(a.name())) { // OBSERVATION (1.0.48): it joins the grid at its check-in
            host.observedCheckIn(ip, a.name());
            return true;
        }
        // pc.47: an overwatching phone whose camera gives its motion detection no pictures sees nothing - said at once
        // and when it changes, not in ten minutes. 1.0.64: kept by itself (blindSaid) - a name reply counted as a
        // check-in says nothing of the camera, and the lists ask for it (watchWord)
        long[] was = blindSaid.get(ip);
        boolean blind = h.watchesBlind(), wasBlind = was != null && was[0] != 0;
        if (blind != wasBlind && (blind || before != null) && (blind || a.overwatch())) {
            host.logLater(a.name() + (blind ? ": OVERWATCH SEES NOTHING - its camera gives the motion detection no pictures. PILOT UAV and OVERWATCH again."
                    : ": overwatch's camera gives pictures again."));
        }
        if (!a.overwatch()) blindSaid.remove(ip);
        else if ((h.owFlags() & OverwatchProtocol.HELLO_PICTURES) != 0) blindSaid.put(ip, new long[]{blind ? 1 : 0, h.receivedAt()});
        // one line per check window; always listening (a hello a minute), one line in ten minutes
        long quiet = a.listening() ? 10 * 60_000L : 20_000;
        if (before == null || h.receivedAt() - before.receivedAt() > quiet
                || a.listening() && h.receivedAt() - listeningLogged.getOrDefault(ip, 0L) > quiet) {
            if (a.listening()) listeningLogged.put(ip, h.receivedAt());
            host.logLater(a.name() + (a.overwatch() ? " overwatching" : " asleep") + ": battery " + (h.batteryPercent() >= 0 ? h.batteryPercent() + " %" : "unknown")
                    + (h.charging() ? ", charging" : "") + (a.listening() ? ", listening." : ", next check in " + h.nextCheckMin() + " min."));
        }
        return sleepOn(a, ip, h.receivedAt());
    }

    /** The PC that set the sleep answers "sleep on" this long after the window's first hello, not at once. */
    static final long SLEEP_ON_AFTER_MS = 2000;
    /** A FLEET mode sent for a hello counts for that hello's answer this long. */
    private static final long MODE_WENT_MS = 2000;
    /** The first hello of each phone's check window now open (wall ms), and a queued FLEET mode just sent for its hello. */
    private final Map<String, Long> windowFirstAt = new ConcurrentHashMap<>(), modeWentAt = new ConcurrentHashMap<>();

    /** 1.0.64: FLEET sent a queued mode for this phone's hello (the same thread, just before {@link #checkIn}). */
    void modeWent(String ip) {
        if (ip != null) modeWentAt.put(ip, wall.getAsLong());
    }

    /**
     * 1.0.64, the answer to a sleeping phone's hello now that every PC that knows it hears it (pc.50): "sleep on"
     * closes the phone's window at once, so
     * <ul>
     * <li>none behind a queued FLEET mode: were the mode lost and the answer not, the mode waited another interval; the
     * window's later hellos (1.5 s apart) have it sent again;</li>
     * <li>none from a PC that didn't set the sleep and has nothing to say: the first "sleep on" shut the window in
     * about 0.3 s, before another PC's queued mode could land;</li>
     * <li>the PC that set it waits {@link #SLEEP_ON_AFTER_MS} from the window's first hello (its third hello, at 3.2 s):
     * the others get their chance. A listening phone's window never closes: answered as ever.</li>
     * </ul>
     * A window nobody answers closes by itself at its end (10 s), and a late "sleep on" finds it closed: no harm.
     * @return false ("sleep on") or null (no answer)
     */
    private Boolean sleepOn(Asleep a, String ip, long now) {
        Long first = windowFirstAt.get(ip);
        if (first == null || now < first || now - first > WINDOW_MS) windowFirstAt.put(ip, first = now);
        Long went = modeWentAt.remove(ip);
        if (went != null && Math.abs(now - went) < MODE_WENT_MS) return null;
        if (!a.setHere()) return null;
        if (a.listening()) return false;
        return now - first >= SLEEP_ON_AFTER_MS ? Boolean.FALSE : null;
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
        return connectText(connected, spectatorLink, observing, observerRole, spectatorRole, waitingForWake, wakeInMs, false);
    }

    /** As above; missed: the phone didn't check in when it should have (1.0.64) - the button says so while it counts on. */
    static String connectText(boolean connected, boolean spectatorLink, boolean observing, boolean observerRole, boolean spectatorRole,
                              boolean waitingForWake, long wakeInMs, boolean missed) {
        if (observing) return "STOP OBSERVING";
        if (!connected) return observerRole ? "OBSERVE" : spectatorRole ? "WATCH" : "PILOT UAV";
        if (spectatorLink) return "STOP WATCHING";
        if (waitingForWake) {
            if (wakeInMs < 0) return "REGAINING CONTROL...";
            // counting down in seconds (2026-11-10, the pilot; was whole minutes)
            long s = Math.max(1, (wakeInMs + 999) / 1000);
            return (missed ? "MISSED CHECK - NEXT " : "REGAINING CONTROL IN ") + s / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60);
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
