package de.droiddrone.pc;

import de.droiddrone.common.FleetProtocol;

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
 */
public final class FleetManager {
    static final String QUEUE_KEY = "fleet.queue";

    /** A mode asked for and not yet taken. mode: ControlLink.POWER_STANDBY / POWER_DEEP_SLEEP / POWER_OVERWATCH. */
    public record Wanted(String phone, int token, int mode, int intervalMin, int sensitivity, int flags, int owToken, long queuedAt) {}

    /** A phone's last status and when it came (wall ms); pcBuild -1 unknown. */
    public record Heard(FleetProtocol.Status status, long at, int pcBuild) {}

    private final Map<String, Wanted> queue = new ConcurrentHashMap<>();
    private final Map<String, Heard> heard = new ConcurrentHashMap<>();
    private final Map<String, Integer> builds = new ConcurrentHashMap<>();
    private final String key;
    private final int pcPort;
    private final Consumer<Map<String, String>> saver;
    private final Consumer<String> log;
    /** Told (any thread) when a queued mode was taken: the phone and what it took. */
    private volatile Consumer<Wanted> onTaken = w -> {
    };

    /**
     * @param key    the link key the requests carry
     * @param pcPort this PC's link port: the phone checks in there from then on
     */
    public FleetManager(String key, int pcPort, String saved, Consumer<Map<String, String>> saver, Consumer<String> log) {
        this.key = key;
        this.pcPort = pcPort;
        this.saver = saver;
        this.log = log;
        for (Wanted w : parse(saved)) queue.put(id(w.phone()), w);
    }

    public void setOnTaken(Consumer<Wanted> listener) {
        onTaken = listener == null ? w -> {
        } : listener;
    }

    private static String id(String phone) {
        return phone.toLowerCase(Locale.ROOT);
    }

    /**
     * Why this mode can't be queued for the phone now, or null: it can. armed: its drone's last known state (null
     * unknown); flyingHere: this PC flies (or may still fly) that drone; connectedHere: this PC has a link to it.
     */
    public static String refusal(Boolean armed, boolean flyingHere, boolean connectedHere, int pcBuild) {
        if (Boolean.TRUE.equals(armed) || flyingHere) return "Armed: land and disarm first.";
        if (connectedHere) return "Connected: use the LINK card.";
        if (pcBuild >= 0 && pcBuild < FleetProtocol.MIN_PC_BUILD) return "Needs phone app pc." + FleetProtocol.MIN_PC_BUILD + ".";
        return null;
    }

    /** Queues a mode (replacing what was queued); it goes at the phone's next chance. */
    public Wanted queue(String phone, int mode, int intervalMin, int sensitivity, int flags, int owToken, long now) {
        int token;
        do {
            token = new java.security.SecureRandom().nextInt();
        } while (token == 0);
        Wanted w = new Wanted(phone, token, mode, intervalMin, sensitivity, flags, owToken, now);
        queue.put(id(phone), w);
        save();
        return w;
    }

    public void cancel(String phone) {
        if (queue.remove(id(phone)) != null) save();
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

    /**
     * A phone answered a name scan (the name service's thread): its status filed, a queued mode taken or not, and
     * the FLEET_MODE to send it now - or null.
     */
    public byte[] onReply(Names.Reply r, long now) {
        if (r == null || r.kind() != Names.Kind.PHONE || r.name().isEmpty()) return null;
        int build = FleetProtocol.pcBuild(r.version());
        builds.put(id(r.name()), build);
        if (r.status() != null) heard.put(id(r.name()), new Heard(r.status(), now, build));
        return next(r.name(), r.status(), build);
    }

    /** A sleeping phone's check-in: the same, before the PC answers it. */
    public byte[] onHello(String phone, ControlLink.SleepHello h, long now) {
        if (phone == null || h == null) return null;
        int build = pcBuild(phone);
        if (h.status() != null) {
            if (build < FleetProtocol.MIN_PC_BUILD) builds.put(id(phone), build = FleetProtocol.MIN_PC_BUILD); // a status: pc.40 on
            heard.put(id(phone), new Heard(h.status(), now, build));
        }
        return next(phone, h.status(), build);
    }

    private byte[] next(String phone, FleetProtocol.Status status, int build) {
        Wanted w = queue.get(id(phone));
        if (w == null) return null;
        if (status != null && status.token == w.token()) {
            if (queue.remove(id(phone), w)) {
                save();
                log.accept("Fleet: " + phone + " took " + modeName(w.mode()) + ".");
                onTaken.accept(w);
            }
            return null;
        }
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
