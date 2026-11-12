package de.droiddrone.pc.gui;

import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.overwatch.AlertCard;
import de.droiddrone.pc.overwatch.ClipIndex;
import de.droiddrone.pc.overwatch.OverwatchHub;
import de.droiddrone.pc.video.Klv;
import de.droiddrone.pc.video.VideoPipeline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * The STANAG 4609 streams (1.0.65, the pilot: "make it so there is one stream for each drone"): a {@link StanagFeed}
 * for every phone in the name map while the switch is on, each to its own port; once a second (and at once when
 * something changes) each is told what it shows - {@link StanagShow} - and the one whose video this PC has (the piloted
 * drone, the OBSERVATION tiles) is made that video's tap. All of it is made on this PC from what it has anyway: no
 * phone is asked for anything.
 * <p>
 * Ports: the first port is SEND TO's; a drone's is that plus its name's letter (MobileAlpha +0, MobileBravo +1 ...),
 * a renamed phone's the first free one from +26, kept in the settings - a player's saved address always shows the same
 * drone. Several PCs streaming to one address would mix their streams on the same ports: each PC tells the others
 * that it streams and where (PC to PC, in the name reply), and a clash is said in the log and the settings - a
 * warning only, the pilot's choice.
 */
final class StanagController {
    static final String BITRATE_KEY = "stanag.bitrateKbps", SLOT_KEY = "stanag.slot.";
    /** "stanag.to.<name>": a drone's own address (host:port), typed by the pilot in the settings' list; none: automatic. */
    static final String TO_KEY = "stanag.to.";
    static final int DEFAULT_KBPS = 2000;
    /** A renamed phone (no letter in its name) gets a port from this offset on. */
    static final int FIRST_FREE_SLOT = Names.LETTERS.length;
    /** A clip counts as whole once its event ended and nothing more of it has been coming for this long. */
    static final long CLIP_QUIET_MS = 3000;

    /** An OBSERVATION tile: the phone, its video, its OSD's figures now and its metadata. */
    record Tile(String phone, VideoPipeline video, Supplier<OsdRenderer.Data> osd, LongFunction<Klv.Frame> klv) {}

    /**
     * One line of the settings' list.
     * @param slot the drone's place after SEND TO's port (its automatic address)
     * @param own its address is one the pilot typed, not the automatic one
     */
    record Row(String name, String target, String showing, int slot, boolean own) {
        Row(String name, String target, String showing) {
            this(name, target, showing, 0, false);
        }
    }

    /** What the main window knows. Called on the thread that calls {@link #refresh} (the Swing thread). */
    interface Host {
        /** FLEET's card of every phone in the name map, or null before there is a fleet. */
        List<FleetCards.Card> cards();

        /** The phone whose video this PC's link has - flown here, or watched as a spectator - or null. */
        String livePhone();

        /** That link is a spectator's. */
        boolean spectating();

        /** Its video, or null. */
        VideoPipeline liveVideo();

        /** The screen's OSD (the feed reads it on its own thread). */
        StanagFeed.Osd liveOsd();

        /** The piloted drone's metadata: the camera's view as on the map. */
        LongFunction<Klv.Frame> liveKlv(String phone);

        List<Tile> tiles();

        List<OverwatchHub.View> views();

        ClipIndex clips();

        /** Where the drone is, and - overwatching - its sector. */
        StanagKlv.Place place(String phone, boolean overwatching);

        /** Another PC flies it: that PC's name ("" when not known which); null otherwise. */
        String flownBy(String phone);

        boolean upsideDown(String phone);

        boolean virtual(String phone);

        OsdLayout osdLayout();

        /** The other PCs online that stream, by name: their SEND TO. */
        Map<String, String> otherStreams();

        void log(String line);

        void save(Map<String, String> settings);
    }

    private static final class Entry {
        final String name;
        final StanagFeed feed;
        final String target;
        int slot;
        boolean own;
        final StanagClips clips = new StanagClips();
        VideoPipeline tapped;
        StanagFeed.Alert alert;
        StanagFeed.Clip clip;
        StanagShow.Page page;
        /** Since when nothing of the newest event's clip has been on its way (wall ms), -1 while something is. */
        long quietSince = -1;
        long quietEvent = Long.MIN_VALUE;

        Entry(String name, StanagFeed feed, String target) {
            this.name = name;
            this.feed = feed;
            this.target = target;
        }
    }

    private final PcConfig config;
    private final Host host;
    private final Consumer<Runnable> later;
    private final Map<String, Entry> feeds = new LinkedHashMap<>();
    private final Map<VideoPipeline, Entry> tappedBy = new HashMap<>();
    private final Set<String> clashSaid = new HashSet<>(), portSaid = new HashSet<>();
    private boolean on;
    private String target = "";
    /** What the name reply says of this PC's streams (read on the network's threads, never under this object's lock). */
    private volatile String announced;
    private List<String> clashes = List.of();
    /** Tests: no wait. */
    long clipQuietMs = CLIP_QUIET_MS;
    /** Tests: where a drone's stream goes instead of host:port (a file each). */
    BiFunction<String, Integer, String> targetOf;

    /** @param later runs something on the caller's thread a moment later (SwingUtilities::invokeLater) */
    StanagController(PcConfig config, Host host, Consumer<Runnable> later) {
        this.config = config;
        this.host = host;
        this.later = later;
    }

    synchronized boolean isOn() {
        return on;
    }

    /** The switch: on = a stream per drone from now; off = all of them closed. */
    synchronized void setOn(boolean wanted) {
        if (wanted == on) return;
        on = wanted;
        if (!on) {
            closeAll();
            announce();
            return;
        }
        target = StanagDialog.target(config);
        announce();
        refresh();
        sayOn();
    }

    private void sayOn() {
        long own = feeds.values().stream().filter(e -> e.own).count();
        host.log("STANAG 4609 streams on: " + feeds.size() + " to " + hostOf(target) + ", from port " + portOf(target)
                + (own == 1 ? " (1 to an address of its own)." : own > 1 ? " (" + own + " to addresses of their own)." : "."));
    }

    /** The settings changed (SEND TO, the bitrate): the streams again, at their new ports. */
    synchronized void restart() {
        if (!on) return;
        closeAll();
        target = StanagDialog.target(config);
        announce();
        refresh();
        sayOn(); // said: the pilot switched them off and on to be sure the new address was taken
    }

    /** The app closes. */
    synchronized void close() {
        on = false;
        closeAll();
        announce();
    }

    private void announce() {
        announced = on && !loopback(hostOf(target)) && portOf(target) > 0 ? target : null;
    }

    private void closeAll() {
        for (Entry e : feeds.values()) {
            untap(e);
            e.feed.close();
        }
        feeds.clear();
        tappedBy.clear();
        clashSaid.clear();
        clashes = List.of();
    }

    /** "host:port" -> host ("" when there is none). */
    static String hostOf(String target) {
        int colon = target == null ? -1 : target.lastIndexOf(':');
        return colon <= 0 ? "" : target.substring(0, colon).trim();
    }

    /** "host:port" -> port, -1 when it is none. */
    static int portOf(String target) {
        int colon = target == null ? -1 : target.lastIndexOf(':');
        if (colon < 0) return -1;
        try {
            int p = Integer.parseInt(target.substring(colon + 1).trim());
            return p >= 1 && p <= 65535 ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * A drone's place after the first port: its name's letter; a name without one keeps the place it was given (the
     * first free one from {@link #FIRST_FREE_SLOT}). -1: its place has to be given first ({@link #giveSlot}).
     */
    static int slot(String name, java.util.function.Function<String, String> setting) {
        int letter = Names.letterOf(name);
        if (letter >= 0) return letter;
        try {
            int kept = Integer.parseInt(setting.apply(SLOT_KEY + name).trim());
            return kept >= FIRST_FREE_SLOT ? kept : -1;
        } catch (NumberFormatException | NullPointerException e) {
            return -1;
        }
    }

    /** The first place from {@link #FIRST_FREE_SLOT} none of these has. */
    static int giveSlot(java.util.Collection<Integer> taken) {
        int s = FIRST_FREE_SLOT;
        while (taken.contains(s)) s++;
        return s;
    }

    private int slotFor(String name) {
        int s = slot(name, config::getString);
        if (s >= 0) return s;
        Set<Integer> taken = new HashSet<>();
        for (String key : config.keys()) {
            if (!key.startsWith(SLOT_KEY)) continue;
            try {
                taken.add(Integer.parseInt(config.getString(key).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        s = giveSlot(taken);
        host.save(Map.of(SLOT_KEY + name, Integer.toString(s)));
        return s;
    }

    private int bitrate() {
        int kbps = DEFAULT_KBPS;
        try {
            String s = config.getString(BITRATE_KEY);
            if (!s.isEmpty()) kbps = Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
        }
        return Math.max(300, Math.min(20_000, kbps)) * 1000;
    }

    private static String id(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** At once, when something changed that the streams show (a connection, the grid, an alert, a clip). */
    void refreshSoon() {
        later.accept(this::refresh);
    }

    /** Every drone's stream told what it shows now; new phones get a stream, forgotten ones lose theirs. */
    synchronized void refresh() {
        if (!on) return;
        List<FleetCards.Card> cards = host.cards();
        if (cards == null) return;
        long wall = System.currentTimeMillis();
        String live = host.livePhone();
        VideoPipeline liveVideo = host.liveVideo();
        boolean spectating = host.spectating();
        Map<String, Tile> tiles = new HashMap<>();
        for (Tile t : host.tiles()) tiles.put(id(t.phone()), t);
        Map<String, OverwatchHub.View> views = new HashMap<>();
        for (OverwatchHub.View v : host.views()) views.put(id(v.drone()), v);
        String mission = config.getString(StanagDialog.MISSION_KEY).trim(), country = StanagDialog.country(config);

        Set<String> known = new HashSet<>();
        for (FleetCards.Card card : cards) {
            String name = card.name(), key = id(name);
            known.add(key);
            Entry e = feeds.get(key);
            if (e == null) {
                e = open(name);
                if (e == null) continue;
                feeds.put(key, e);
            }
            boolean isLive = live != null && live.equalsIgnoreCase(name);
            Tile tile = isLive ? null : tiles.get(key);
            tap(e, isLive ? liveVideo : tile == null ? null : tile.video(), isLive, tile);

            OverwatchHub.View v = views.get(key);
            boolean running = v != null && v.shown() == AlertCard.Shown.CARD && !v.ended();
            setAlert(e, running ? v : null, wall);
            setClip(e, card, v, running, wall);

            e.page = StanagShow.page(card, spectating && isLive, isLive ? null : host.flownBy(name), running, wall);
            e.feed.setPage(e.page);
            e.feed.setPlace(host.place(name, "OVERWATCH".equals(card.badge())));
            e.feed.setIds(mission, country);
        }
        for (var it = feeds.entrySet().iterator(); it.hasNext(); ) { // a phone forgotten in the name map: its stream ends
            Map.Entry<String, Entry> en = it.next();
            if (known.contains(en.getKey())) continue;
            untap(en.getValue());
            en.getValue().feed.close();
            it.remove();
        }
        checkOthers();
    }

    /** Between refreshes, eight times a second while an alert runs: the alert cards' newest pictures to their streams. */
    synchronized void alertPictures() {
        if (!on || feeds.isEmpty()) return;
        List<OverwatchHub.View> views = host.views();
        if (views.isEmpty()) return;
        long wall = System.currentTimeMillis();
        for (OverwatchHub.View v : views) {
            Entry e = feeds.get(id(v.drone()));
            if (e == null || e.alert == null) continue; // not running at the last refresh: the refresh starts it
            if (v.shown() == AlertCard.Shown.CARD && !v.ended()) setAlert(e, v, wall);
        }
    }

    /** A drone's own address from the settings (the pilot typed it), or null: the automatic one. */
    static String ownTarget(String name, java.util.function.Function<String, String> setting) {
        String t = StanagDialog.plain(setting.apply(TO_KEY + name));
        return StanagDialog.address(t) ? t : null;
    }

    private Entry open(String name) {
        int base = portOf(target), slot = slotFor(name);
        String to, own = ownTarget(name, config::getString);
        if (targetOf != null) {
            to = targetOf.apply(name, slot);
        } else if (own != null) {
            to = own;
        } else {
            if (base < 0 || base + slot > 65535 || hostOf(target).isEmpty()) {
                if (portSaid.add(id(name))) host.log("STANAG 4609 " + name + ": no port for it (SEND TO " + target + ").");
                return null;
            }
            to = hostOf(target) + ":" + (base + slot);
        }
        Entry[] made = new Entry[1];
        StanagFeed feed = new StanagFeed(name, host.virtual(name), to, bitrate(), () -> host.upsideDown(name),
                clipId -> later.accept(() -> clipEnded(made[0], clipId)), line -> later.accept(() -> host.log(line)));
        made[0] = new Entry(name, feed, to);
        made[0].slot = slot;
        made[0].own = own != null && targetOf == null;
        return made[0];
    }

    private synchronized void clipEnded(Entry e, long clipId) {
        if (e == null || feeds.get(id(e.name)) != e) return;
        e.clips.ended(clipId);
        refresh();
    }

    /** The feed of the drone whose video this pipeline carries is its tap; a feed whose video went has none. */
    private void tap(Entry e, VideoPipeline want, boolean isLive, Tile tile) {
        if (want == e.tapped) return;
        untap(e);
        if (want == null) return;
        Entry before = tappedBy.put(want, e);
        if (before != null && before != e) { // a spectator's pilot flies another phone now: the same video, another drone's stream
            before.tapped = null;
            before.feed.setLive(null);
        }
        e.tapped = want;
        String name = e.name;
        if (isLive) {
            e.feed.setLive(new StanagFeed.Live(host.liveOsd(), false, host.liveKlv(name)));
        } else {
            Supplier<OsdRenderer.Data> osd = tile.osd();
            e.feed.setLive(new StanagFeed.Live(new StanagFeed.Osd() {
                public OsdRenderer.Data data() { return osd == null ? null : osd.get(); }
                public OsdLayout layout() { return host.osdLayout(); }
                public boolean flipX() { return host.upsideDown(name); }
                public boolean flipY() { return host.upsideDown(name); }
                public double displayMs() { return 0; }
            }, true, tile.klv()));
        }
        want.setFrameTap(e.feed); // the config in hand and a key frame asked for: it starts within a moment
    }

    private void untap(Entry e) {
        VideoPipeline p = e.tapped;
        if (p == null) return;
        e.tapped = null;
        if (tappedBy.get(p) == e) {
            tappedBy.remove(p);
            p.setFrameTap(null);
        }
        e.feed.setLive(null);
    }

    private void setAlert(Entry e, OverwatchHub.View v, long wall) {
        if (v == null || v.picture() == null) {
            if (e.alert != null) e.feed.setAlert(null);
            e.alert = null;
            return;
        }
        String bar = StanagShow.alertBar(e.name, v.live(), wall);
        StanagFeed.Alert a = e.alert;
        if (a != null && a.picture() == v.picture() && a.boxes() == v.boxes() && a.bar().equals(bar)) return;
        e.alert = new StanagFeed.Alert(v.picture(), v.boxes(), bar);
        e.feed.setAlert(e.alert);
    }

    private void setClip(Entry e, FleetCards.Card card, OverwatchHub.View v, boolean running, long wall) {
        StanagClips.Alert newest = v == null || v.alert() == null ? null
                : new StanagClips.Alert(v.alert().startedAt, running, v.shown() == AlertCard.Shown.HIDDEN);
        // "nothing more of it coming": its event over and no transfer under way, for a moment (the next part is announced within it)
        if (newest != null) {
            boolean quiet = v.ended() && v.clipLine() == null;
            if (e.quietEvent != newest.startedAt()) {
                e.quietEvent = newest.startedAt();
                e.quietSince = -1;
            }
            if (!quiet) e.quietSince = -1;
            else if (e.quietSince < 0) e.quietSince = wall;
        }
        String name = e.name;
        long play = e.clips.update(card.badge(), e.tapped != null, newest, startedAt -> {
            if (newest != null && newest.startedAt() == startedAt && (e.quietSince < 0 || wall - e.quietSince < clipQuietMs)) return false;
            return !parts(name, startedAt).isEmpty();
        });
        if (play < 0) {
            if (e.clip != null) e.feed.setClip(null);
            e.clip = null;
        } else if (e.clip == null || e.clip.id() != play) {
            e.clip = new StanagFeed.Clip(play, () -> parts(name, play), StanagShow.clipBar(name, play));
            e.feed.setClip(e.clip);
        }
    }

    /** The full-quality clip of the event that started then, in its parts here now; empty when hidden, gone or not here. */
    private List<Path> parts(String drone, long startedAt) {
        ClipIndex index = host.clips();
        if (index == null) return List.of();
        String clipId = ClipIndex.baseId(startedAt);
        ClipIndex.Clip c = index.clip(drone, clipId);
        if (c == null || c.startedAt() != startedAt || index.isHidden(drone, clipId)) return List.of();
        return c.parts();
    }

    // ------------------------------------------------------------------ several PCs

    private static boolean loopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.isEmpty() || h.startsWith("127.") || h.equals("localhost") || h.equals("::1") || h.equals("[::1]");
    }

    /**
     * The other PCs that stream to the address this PC streams to (their ports would be the same drones' as ours):
     * never for this PC's own loopback - each PC has its own.
     */
    static List<String> clashing(String ours, Map<String, String> others) {
        List<String> out = new ArrayList<>();
        String host = hostOf(ours);
        int base = portOf(ours);
        if (loopback(host) || base < 0 || others == null) return out;
        for (Map.Entry<String, String> o : others.entrySet()) {
            int theirs = portOf(o.getValue());
            if (theirs < 0 || !hostOf(o.getValue()).equalsIgnoreCase(host)) continue;
            if (Math.abs(theirs - base) < 2 * FIRST_FREE_SLOT) out.add(o.getKey());
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private void checkOthers() {
        List<String> now = clashing(target, host.otherStreams());
        for (String pc : now) {
            if (clashSaid.add(id(pc))) host.log(pc + " also streams STANAG to " + hostOf(target) + ". Switch one off.");
        }
        Set<String> ids = new HashSet<>();
        for (String pc : now) ids.add(id(pc));
        clashSaid.retainAll(ids); // one that stopped and starts again is said again
        clashes = now;
    }

    /** The settings' red line while another PC streams to the same address, or null. */
    synchronized String warning() {
        if (!on || clashes.isEmpty()) return null;
        return String.join(", ", clashes) + " also stream" + (clashes.size() == 1 ? "s" : "") + " to " + hostOf(target) + ". Switch one off.";
    }

    /** What this PC says of its streams in its name reply: "host:port" while they are on, else null. */
    String announced() {
        return announced;
    }

    /**
     * The streams, for the settings' list: each drone's address and what it shows now - while they are off, every
     * known drone with the address it would have (the pilot sets addresses before switching them on, too).
     */
    synchronized List<Row> rows() {
        List<Row> out = new ArrayList<>();
        if (on) {
            for (Entry e : feeds.values()) {
                StanagShow.Kind k = e.feed.showing();
                out.add(new Row(e.name, e.target, k == StanagShow.Kind.PAGE ? (e.page == null ? "" : e.page.title()) : k.name(), e.slot, e.own));
            }
            return out;
        }
        List<FleetCards.Card> cards = host.cards();
        if (cards == null) return out;
        String base = StanagDialog.target(config);
        for (FleetCards.Card c : cards) {
            int slot = slotFor(c.name());
            String own = ownTarget(c.name(), config::getString);
            out.add(new Row(c.name(), own != null ? own : hostOf(base) + ":" + (portOf(base) + slot), "OFF", slot, own != null));
        }
        return out;
    }

    /** Tests. */
    synchronized StanagFeed feed(String name) {
        Entry e = feeds.get(id(name));
        return e == null ? null : e.feed;
    }
}
