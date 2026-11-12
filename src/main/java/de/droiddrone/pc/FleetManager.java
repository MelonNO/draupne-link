package de.droiddrone.pc;

import de.droiddrone.common.FleetProtocol;
import de.droiddrone.common.SleepCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * FLEET MANAGEMENT (1.0.52, phone app pc.40): a power mode for a phone this PC has no link to, queued until the phone
 * listens - its name reply at a scan (awake), or its sleep hello (asleep) - and sent as FLEET_MODE then, again at each
 * chance until the phone's status says it took that token. Also every phone's last word on how it is doing
 * (FleetProtocol.Status) and its app's build, for the fleet cards. Thread-safe: the name service's thread, the link's
 * receive thread and the Swing thread all come here.
 * <p>
 * 1.0.64: a queued mode doesn't wait for ever ({@link #KEEP_MS}, or two of the phone's check-ins), and never lands on a
 * drone a pilot flies - this PC's or another's.
 */
public final class FleetManager {
    static final String QUEUE_KEY = "fleet.queue";
    /**
     * 1.0.64: a queued mode waits this long for its phone - two of its check-ins when that is longer. It was kept for
     * ever, restarts included: a DEEP SLEEP queued for a phone out of reach went to it days later, at its first answer.
     */
    public static final long KEEP_MS = 15 * 60_000L;
    /** A phone's word that a pilot flies it counts this long (awake, it answers every scan). */
    static final long FLOWN_FRESH_MS = 30_000;

    /** A mode asked for and not yet taken. mode: ControlLink.POWER_STANDBY / POWER_DEEP_SLEEP / POWER_OVERWATCH. */
    public record Wanted(String phone, int token, int mode, int intervalMin, int sensitivity, int flags, int owToken, long queuedAt) {}

    /** A phone's last status and when it came (wall ms); pcBuild -1 unknown. */
    public record Heard(FleetProtocol.Status status, long at, int pcBuild) {}

    private final Map<String, Wanted> queue = new ConcurrentHashMap<>();
    private final Map<String, Heard> heard = new ConcurrentHashMap<>();
    private final Map<String, Integer> builds = new ConcurrentHashMap<>();
    /** 1.0.64: when each phone last said a pilot flies it (wall ms). */
    private final Map<String, Long> flownAt = new ConcurrentHashMap<>();
    /** 1.0.64: the token of the last mode each phone took from this PC. */
    private final Map<String, Integer> lastTaken = new ConcurrentHashMap<>();
    private final String key;
    private final int pcPort;
    private final Consumer<Map<String, String>> saver;
    private final Consumer<String> log;
    /** Told (any thread) when a queued mode was taken: the phone and what it took. */
    private volatile Consumer<Wanted> onTaken = w -> {
    };
    /** 1.0.64: told (any thread) when a queued mode left the queue untaken: cancelled, replaced, too old, a pilot flies. */
    private volatile Consumer<Wanted> onDropped = w -> {
    };
    /** 1.0.64: how often this phone checks in, as this PC's sleep book has it (SleepCheck's byte; 0 not known). */
    private volatile java.util.function.ToIntFunction<String> checkEvery = phone -> 0;
    /** 1.0.64: whether this PC's own pilot link is to this phone. */
    private volatile java.util.function.Predicate<String> pilotHere = phone -> false;

    /**
     * @param key    the link key the requests carry
     * @param pcPort this PC's link port: the phone checks in there from then on
     */
    public FleetManager(String key, int pcPort, String saved, Consumer<Map<String, String>> saver, Consumer<String> log) {
        this.key = key;
        this.pcPort = pcPort;
        this.saver = saver;
        this.log = log;
        // 1.0.64: what is too old by now goes at the first scan (expire), before anything is asked or sent
        for (Wanted w : parse(saved)) queue.put(id(w.phone()), w);
    }

    public void setOnTaken(Consumer<Wanted> listener) {
        onTaken = listener == null ? w -> {
        } : listener;
    }

    /** 1.0.64: see {@link #onDropped}. */
    public void setOnDropped(Consumer<Wanted> listener) {
        onDropped = listener == null ? w -> {
        } : listener;
    }

    /** 1.0.64: the phone's check-in interval as this PC knows it (0 unknown), for how long a queued mode waits. */
    public void setCheckEvery(java.util.function.ToIntFunction<String> intervalMinOf) {
        checkEvery = intervalMinOf == null ? phone -> 0 : intervalMinOf;
    }

    /** 1.0.64: whether this PC's pilot link is to the phone - a mode queued for it is dropped then, never sent. */
    public void setPilotHere(java.util.function.Predicate<String> connected) {
        pilotHere = connected == null ? phone -> false : connected;
    }

    private static String id(String phone) {
        return phone.toLowerCase(Locale.ROOT);
    }

    /**
     * Why this mode can't be queued for the phone now, or null: it can. armed: its drone's last known state (null
     * unknown); flyingHere: this PC flies (or may still fly) that drone; connectedHere: this PC has a link to it.
     */
    public static String refusal(Boolean armed, boolean flyingHere, boolean connectedHere, int pcBuild) {
        return refusal(null, armed, flyingHere, connectedHere, false, pcBuild);
    }

    /**
     * As above. flownElsewhere (1.0.64): the phone says a pilot flies it and it isn't this PC - a mode queued then went
     * to the drone the moment that pilot's link dropped.
     */
    public static String refusal(String phone, Boolean armed, boolean flyingHere, boolean connectedHere, boolean flownElsewhere, int pcBuild) {
        if (flyingHere) return "Armed: land and disarm first.";
        if (flownElsewhere && !connectedHere) return (phone == null || phone.isEmpty() ? "It" : phone) + " is flown by another PC.";
        if (Boolean.TRUE.equals(armed)) return "Armed: land and disarm first.";
        if (connectedHere) return "Connected: use the LINK card.";
        if (pcBuild >= 0 && pcBuild < FleetProtocol.MIN_PC_BUILD) return "Needs phone app pc." + FleetProtocol.MIN_PC_BUILD + ".";
        return null;
    }

    /** 1.0.64: the phone said lately that a pilot PC flies it (its name reply, with or without a position). */
    public boolean flown(String phone, long now) {
        Long at = phone == null ? null : flownAt.get(id(phone));
        return at != null && now - at <= FLOWN_FRESH_MS;
    }

    /** Queues a mode (replacing what was queued); it goes at the phone's next chance. */
    public Wanted queue(String phone, int mode, int intervalMin, int sensitivity, int flags, int owToken, long now) {
        int token;
        do {
            token = new java.security.SecureRandom().nextInt();
        } while (token == 0);
        Wanted w = new Wanted(phone, token, mode, intervalMin, sensitivity, flags, owToken, now);
        Wanted before = queue.put(id(phone), w);
        save();
        if (before != null) onDropped.accept(before);
        return w;
    }

    public void cancel(String phone) {
        Wanted w = phone == null ? null : queue.remove(id(phone));
        if (w == null) return;
        save();
        onDropped.accept(w);
    }

    /** 1.0.64: a queued mode dropped with a line for the log ("MobileAlpha: queued DEEP SLEEP dropped - why."). */
    private void drop(Wanted w, String why) {
        if (!queue.remove(id(w.phone()), w)) return;
        save();
        log.accept(w.phone() + ": queued " + modeName(w.mode()) + " dropped - " + why + ".");
        onDropped.accept(w);
    }

    public Wanted wanted(String phone) {
        return queue.get(id(phone));
    }

    public Heard heard(String phone) {
        return heard.get(id(phone));
    }

    /** The phone's app build as last heard (-1 unknown). */
    public int pcBuild(String phone) {
        return builds.getOrDefault(id(phone), -1);
    }

    /** 1.0.64: whether this token is of a mode this PC queued for the phone and the phone took last, or still has queued. */
    public boolean ownToken(String phone, int token) {
        if (phone == null || token == 0) return false;
        Wanted w = queue.get(id(phone));
        Integer taken = lastTaken.get(id(phone));
        return w != null && w.token() == token || taken != null && taken == token;
    }

    /**
     * 1.0.64: how long a mode queued for this phone waits: {@link #KEEP_MS}, or two of its check-ins when it sleeps
     * with a longer interval (this PC's sleep book, else its own last status).
     */
    long keepMs(String phone) {
        int every = checkEvery.applyAsInt(phone);
        Heard h = heard.get(id(phone));
        if (every <= 0 && h != null && h.status() != null && (h.status().mode == 2 || h.status().mode == 3)) every = h.status().intervalMin;
        return every <= 0 ? KEEP_MS : Math.max(KEEP_MS, 2L * SleepCheck.checkInMinutes(every) * 60_000L);
    }

    /**
     * 1.0.64: once a scan (and before anything is sent): queued modes whose phone wasn't reached in time are dropped,
     * each with a line in the log.
     */
    public void expire(long now) {
        for (Wanted w : new ArrayList<>(queue.values())) expired(w, now);
    }

    /** Drops w when it waited too long. @return true when dropped */
    private boolean expired(Wanted w, long now) {
        long keep = keepMs(w.phone());
        if (now - w.queuedAt() <= keep) return false;
        drop(w, "not reached in " + keep / 60_000 + " min");
        return true;
    }

    /** 1.0.64: a rename (the name map's): the phone's queued mode, its last status and build under its new name. */
    public void rename(String from, String to) {
        if (from == null || to == null || from.equalsIgnoreCase(to)) return;
        Heard h = heard.remove(id(from));
        if (h != null) heard.put(id(to), h);
        Integer b = builds.remove(id(from));
        if (b != null) builds.put(id(to), b);
        Long f = flownAt.remove(id(from));
        if (f != null) flownAt.put(id(to), f);
        Integer t = lastTaken.remove(id(from));
        if (t != null) lastTaken.put(id(to), t);
        Wanted w = queue.remove(id(from));
        if (w == null) return;
        queue.put(id(to), new Wanted(to, w.token(), w.mode(), w.intervalMin(), w.sensitivity(), w.flags(), w.owToken(), w.queuedAt()));
        save();
    }

    /**
     * A phone answered a name scan (the name service's thread): its status filed, a queued mode taken or not, and
     * the FLEET_MODE to send it now - or null.
     */
    public byte[] onReply(Names.Reply r, long now) {
        if (r == null || r.kind() != Names.Kind.PHONE || r.name().isEmpty()) return null;
        int build = FleetProtocol.pcBuild(r.version());
        builds.put(id(r.name()), build);
        if (r.status() != null) heard.put(id(r.name()), new Heard(r.status(), now, build));
        if (r.flown()) flownAt.put(id(r.name()), now);
        else flownAt.remove(id(r.name()));
        return next(r.name(), r.status(), build, now);
    }

    /** A sleeping phone's check-in: the same, before the PC answers it. */
    public byte[] onHello(String phone, ControlLink.SleepHello h, long now) {
        if (phone == null || h == null) return null;
        int build = pcBuild(phone);
        if (h.status() != null) {
            if (build < FleetProtocol.MIN_PC_BUILD) builds.put(id(phone), build = FleetProtocol.MIN_PC_BUILD); // a status: pc.40 on
            heard.put(id(phone), new Heard(h.status(), now, build));
        }
        flownAt.remove(id(phone)); // asleep: nobody flies it
        return next(phone, h.status(), build, now);
    }

    private byte[] next(String phone, FleetProtocol.Status status, int build, long now) {
        Wanted w = queue.get(id(phone));
        if (w == null) return null;
        if (status != null && status.token == w.token()) {
            if (queue.remove(id(phone), w)) {
                lastTaken.put(id(phone), w.token());
                save();
                log.accept("Fleet: " + phone + " took " + modeName(w.mode()) + ".");
                onTaken.accept(w);
            }
            return null;
        }
        // 1.0.64: never onto a drone a pilot flies - the phone refuses it while the pilot's link holds and took it the
        // moment that link dropped; nor one this PC is connecting to (the Connect and the mode raced)
        if (pilotHere.test(phone)) {
            drop(w, "this PC flies it");
            return null;
        }
        if (flown(phone, now)) {
            drop(w, "flown by another PC");
            return null;
        }
        if (expired(w, now)) return null;
        if (build >= 0 && build < FleetProtocol.MIN_PC_BUILD) return null; // it can't take it: the card says why
        return FleetProtocol.request(key, new FleetProtocol.Request(w.token(), w.mode(), w.intervalMin(), w.sensitivity(), w.flags(), w.owToken(), pcPort));
    }

    public static String modeName(int mode) {
        return switch (mode) {
            case ControlLink.POWER_STANDBY -> "STANDBY";
            case ControlLink.POWER_DEEP_SLEEP -> "DEEP SLEEP";
            case ControlLink.POWER_OVERWATCH -> "OVERWATCH";
            default -> "NORMAL";
        };
    }

    // ------------------------------------------------------------------ kept in the settings

    private void save() {
        StringBuilder b = new StringBuilder();
        for (Wanted w : queue.values()) {
            if (b.length() > 0) b.append(';');
            b.append(w.phone().replace("|", "").replace(";", "")).append('|').append(Integer.toHexString(w.token())).append('|')
                    .append(w.mode()).append('|').append(w.intervalMin()).append('|').append(w.sensitivity()).append('|')
                    .append(w.flags()).append('|').append(Integer.toHexString(w.owToken())).append('|').append(w.queuedAt());
        }
        saver.accept(Map.of(QUEUE_KEY, b.toString()));
    }

    static List<Wanted> parse(String saved) {
        List<Wanted> out = new ArrayList<>();
        if (saved == null || saved.isBlank()) return out;
        for (String one : saved.split(";")) {
            String[] p = one.split("\\|");
            if (p.length != 8 || p[0].isBlank()) continue;
            try {
                out.add(new Wanted(p[0], Integer.parseUnsignedInt(p[1], 16), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseUnsignedInt(p[6], 16), Long.parseLong(p[7])));
            } catch (NumberFormatException ignored) {
                // a broken entry: left out
            }
        }
        return out;
    }
}
