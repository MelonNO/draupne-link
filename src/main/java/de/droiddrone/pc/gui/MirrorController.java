package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.Mirror;
import de.droiddrone.pc.MirrorClient;
import de.droiddrone.pc.MirrorHost;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.audio.ObservationAudio;
import de.droiddrone.pc.map.Kml;
import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.observe.ObservationSession;
import de.droiddrone.pc.observe.ObserverLink;
import de.droiddrone.pc.observe.TileFeed;
import de.droiddrone.pc.video.VideoPipeline;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Container;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spectator mode as a mirror of the pilot PC (1.0.56, the pilot: "this is just mirroring what the pilot PC is doing, so
 * everything on the map can be seen in spectator mode in the same way as it is on the pilot PC, the fleet management
 * can be seen as well and the camera feeds - so if the pilot is in observation mode the spectator can see this").
 * <ul>
 * <li>Pilot PC: a {@link MirrorHost} for the window's whole life; once a second (at once for the mode) its sections -
 * the mode and phone, the FLEET cards, the map's drones, the observed drones' marks, pins and drawings - and in
 * OBSERVER mode every tile's packets as its phone sends them.</li>
 * <li>Spectator: a {@link MirrorClient} while it watches a PC; the FLEET list and the map's drones, pins and drawings
 * are the pilot's (its own come back when it stops watching), and while the pilot observes, its tiles take the video
 * area - each with its own decoder, OSD and sound (MUTE and volume here only). The drone the pilot flies comes as
 * before ({@link de.droiddrone.pc.Spectate}).</li>
 * </ul>
 */
final class MirrorController implements AutoCloseable {
    /** What the mirror needs from the window. */
    interface Host {
        void logLine(String text);

        /** SETTINGS > Allow spectators. */
        boolean spectatorsAllowed();

        String key();

        /** This PC's own mode: Mirror.IDLE, PILOT (a pilot link) or OBSERVER (the grid). */
        int mode();

        /** The phone it flies (PILOT), or "". */
        String phone();

        /** OBSERVER: the tiles' feeds in order (empty otherwise). */
        List<TileFeed> tileFeeds();

        int selectedTile();

        int enlargedTile();

        /** The FLEET list's cards now (null: no fleet). */
        List<FleetCards.Card> fleetCards();

        Pins pins();

        MapGraphics graphics();

        // ---- the spectator's side

        /** The video area and the single picture the grid stands in for. */
        Container videoHome();

        JComponent videoView();

        ObservationGrid newGrid(ObservationGrid.Listener listener);

        MapView miniMap();

        /** The map window, or null before it was first opened. */
        MapWindow mapWindowIfOpened();

        boolean invertPitch();
    }

    private final Host host;
    private MirrorHost mirrorHost;
    // ---- pilot side (Swing thread)
    private volatile List<MapView.NetDrone> pilotDrones = List.of();
    private volatile List<MapView.Observed> pilotObserved = List.of();
    private long slowAt;
    private List<TileFeed> tapped = List.of();
    // ---- spectator side (Swing thread unless said)
    private MirrorClient client;
    private volatile List<TileFeed> feeds = List.of();
    private ObservationGrid grid;
    private ObservationAudio audio;
    private List<String> gridTiles = List.of();
    private List<MapView.NetDrone> mirroredDrones;
    private List<FleetCards.Card> mirroredCards;
    private List<MapView.Observed> mirroredObserved = List.of();
    private Mirror.Mode mirroredMode;
    private String mapText;
    private Pins mirroredPins;
    private MapGraphics mirroredGraphics;
    private final Map<MapView, Boolean> mapsShowingMirror = new IdentityHashMap<>();
    private final boolean[] tileMuted = new boolean[MirrorHost.MAX_TILES];
    private final int[] tileVolume = {100, 100, 100, 100, 100, 100, 100, 100};
    private long tilesAt;

    MirrorController(Host host) {
        this.host = host;
    }

    /** The pilot PC's side: from now on spectators can ask for this PC's view. Null if no socket could be had. */
    MirrorHost start() {
        if (mirrorHost != null) return mirrorHost;
        try {
            mirrorHost = new MirrorHost(host.key(), host::spectatorsAllowed, line -> SwingUtilities.invokeLater(() -> host.logLine(line)));
        } catch (IOException e) {
            host.logLine("Spectators can't mirror this PC: " + e.getMessage());
        }
        return mirrorHost;
    }

    MirrorHost mirrorHost() {
        return mirrorHost;
    }

    /** Watching a PC and its view mirrored here. */
    boolean isMirroring() {
        return client != null;
    }

    // ------------------------------------------------------------------ the pilot PC

    /** What this PC's map shows of the other drones (the window's refresh): kept for the mirror; the shown list back. */
    List<MapView.NetDrone> drones(List<MapView.NetDrone> own) {
        pilotDrones = own == null ? List.of() : List.copyOf(own);
        List<MapView.NetDrone> m = mirroredDrones;
        return client != null && m != null ? m : own;
    }

    /** The observed drones' marks this PC draws (OBSERVER), for the mirror. */
    void observed(List<MapView.Observed> marks) {
        pilotObserved = marks == null ? List.of() : List.copyOf(marks);
    }

    /** The FLEET cards to show: the pilot PC's while mirroring, else this PC's own. */
    List<FleetCards.Card> fleetShown(List<FleetCards.Card> own) {
        List<FleetCards.Card> m = mirroredCards;
        return client != null && m != null ? m : own;
    }

    /** Every refresh (Swing thread, ~30 a second): the mode at once, the rest once a second, tiles while watched. */
    void tick() {
        long now = MonoClock.ms();
        MirrorHost h = mirrorHost;
        if (h != null && client == null) pilotTick(h, now);
        if (client != null && now - tilesAt >= 100) {
            tilesAt = now;
            refreshTiles(now);
        }
    }

    private void pilotTick(MirrorHost h, long now) {
        int mode = host.mode();
        List<TileFeed> fs = mode == Mirror.OBSERVER ? host.tileFeeds() : List.of();
        List<String> tiles = new ArrayList<>();
        for (TileFeed f : fs) tiles.add(f.phone());
        h.setSection(Mirror.MODE, new Mirror.Mode(mode, mode == Mirror.PILOT ? host.phone() : "", tiles,
                mode == Mirror.OBSERVER ? host.selectedTile() : -1, mode == Mirror.OBSERVER ? host.enlargedTile() : -1).encode());
        if (!sameFeeds(fs, tapped)) { // the grid changed: its taps and key frame asks follow
            for (TileFeed f : tapped) f.link().setTap(null);
            List<TileFeed> taps = List.copyOf(fs);
            for (int i = 0; i < taps.size() && i < MirrorHost.MAX_TILES; i++) {
                final int tile = i;
                taps.get(i).link().setTap((d, len) -> h.tile(tile, d, len));
            }
            h.setTiles(i -> {
                if (i < taps.size()) taps.get(i).link().askVideoConfig();
            });
            tapped = taps;
        }
        if (h.count() == 0 || now - slowAt < 1000) return;
        slowAt = now;
        h.setSection(MirrorCodec.FLEET, MirrorCodec.cards(host.fleetCards()));
        h.setSection(MirrorCodec.DRONES, MirrorCodec.drones(pilotDrones));
        h.setSection(MirrorCodec.OBSERVED, MirrorCodec.observed(mode == Mirror.OBSERVER ? pilotObserved : List.of()));
        Pins p = host.pins();
        MapGraphics g = host.graphics();
        h.setSection(MirrorCodec.MAP, Mirror.text(Kml.write(g == null ? List.of() : g.list(), p == null ? List.of() : p.list(), "DRAUPNE Link")));
    }

    private static boolean sameFeeds(List<TileFeed> a, List<TileFeed> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    // ------------------------------------------------------------------ the spectator

    /** Watching the PC at this address (its link port): its view mirrored here until {@link #unwatch}. */
    void watch(InetAddress pilotIp, int pilotPort) {
        unwatch();
        try {
            client = new MirrorClient(pilotIp, pilotPort, host.key(), new MirrorClient.Listener() {
                @Override
                public void state(Map<String, byte[]> sections) {
                    SwingUtilities.invokeLater(() -> apply(sections));
                }

                @Override
                public void tile(int tile, byte[] d, int len) {
                    List<TileFeed> fs = feeds;
                    if (tile < fs.size()) fs.get(tile).link().offer(d, len);
                }

                @Override
                public void joined(boolean on, String why) {
                    SwingUtilities.invokeLater(() -> host.logLine(on ? "Spectator: the pilot PC's view mirrored here."
                            : "Spectator: no mirror - " + why + "."));
                }
            });
        } catch (IOException e) {
            client = null;
            host.logLine("Spectator: no mirror - " + e.getMessage());
        }
    }

    /** Back to this PC's own view: its fleet, drones, pins and drawings; the grid gone. */
    void unwatch() {
        MirrorClient c = client;
        client = null;
        if (c != null) c.close();
        removeGrid();
        mirroredDrones = null;
        mirroredCards = null;
        mirroredObserved = List.of();
        mirroredMode = null;
        mapText = null;
        mirroredPins = null;
        mirroredGraphics = null;
        for (MapView m : mapsShowingMirror.keySet()) {
            m.setPins(host.pins());
            m.setGraphics(host.graphics());
            m.setObserved(List.of(), null);
            m.repaint();
        }
        mapsShowingMirror.clear();
    }

    /** The pilot PC's tiles shown here (tests). */
    List<TileFeed> feedsForTest() {
        return feeds;
    }

    /** The pilot PC's mode as last mirrored, or null. */
    Mirror.Mode mirroredMode() {
        return mirroredMode;
    }

    /** A state from the pilot PC (Swing thread). */
    private void apply(Map<String, byte[]> s) {
        if (client == null) return;
        Mirror.Mode mode = Mirror.Mode.decode(s.get(Mirror.MODE));
        Mirror.Mode before = mirroredMode;
        mirroredMode = mode;
        if (mode != null && (before == null || before.mode() != mode.mode())) {
            host.logLine("Spectator: the pilot PC is in " + (mode.mode() == Mirror.PILOT ? "PILOT" : mode.mode() == Mirror.OBSERVER ? "OBSERVER" : "no") + " mode.");
        }
        if (mode != null && mode.mode() == Mirror.OBSERVER && !mode.tiles().isEmpty()) {
            showGrid(mode.tiles());
            if (grid != null) grid.setSelection(mode.selected(), mode.enlarged());
        } else {
            removeGrid();
        }
        List<FleetCards.Card> cards = MirrorCodec.cards(s.get(MirrorCodec.FLEET));
        if (cards != null) mirroredCards = cards;
        List<MapView.NetDrone> drones = MirrorCodec.drones(s.get(MirrorCodec.DRONES));
        if (drones != null) mirroredDrones = drones;
        List<MapView.Observed> obs = MirrorCodec.observed(s.get(MirrorCodec.OBSERVED));
        mirroredObserved = obs == null || mode == null || mode.mode() != Mirror.OBSERVER ? List.of() : obs;
        String text = Mirror.text(s.get(MirrorCodec.MAP));
        if (text != null && !text.equals(mapText)) {
            mapText = text;
            Kml.Parsed parsed = Kml.read(text);
            Pins p = new Pins(null);
            for (Pins.Pin pin : parsed.pins()) p.add(pin.name(), pin.lat(), pin.lon());
            MapGraphics g = new MapGraphics(null);
            g.addAll(parsed.items());
            mirroredPins = p;
            mirroredGraphics = g;
            mapsShowingMirror.clear(); // every map takes the new ones
        }
        List<MapView> maps = new ArrayList<>();
        maps.add(host.miniMap());
        MapWindow w = host.mapWindowIfOpened();
        if (w != null) maps.add(w.map());
        for (MapView m : maps) {
            if (m == null) continue;
            if (mirroredPins != null && !mapsShowingMirror.containsKey(m)) {
                m.setPins(mirroredPins);
                m.setGraphics(mirroredGraphics);
                mapsShowingMirror.put(m, true);
            }
            if (mirroredDrones != null) m.setNetDrones(mirroredDrones, (name, watch) -> {
            });
            m.setObserved(mirroredObserved, null);
            m.repaint();
        }
        if (w != null && mirroredCards != null && w.isShown()) w.setFleet(mirroredCards);
    }

    // ---- the pilot's tiles here

    private void showGrid(List<String> tiles) {
        if (grid != null && tiles.equals(gridTiles)) return;
        removeGrid();
        ObservationAudio a = new ObservationAudio(AlertSound::isPlaying, line -> SwingUtilities.invokeLater(() -> host.logLine(line)));
        ObservationGrid g = host.newGrid(new GridListener());
        g.setPhones(tiles);
        List<TileFeed> fs = new ArrayList<>();
        for (int i = 0; i < tiles.size() && i < MirrorHost.MAX_TILES; i++) {
            final int tile = i;
            TileFeed f = TileFeed.relayed(tiles.get(i), g.sink(i), a, line -> SwingUtilities.invokeLater(() -> host.logLine(line)), () -> {
                MirrorClient c = client;
                if (c != null) c.askKey(tile);
            });
            a.setMuted(f.audioId(), tileMuted[i]);
            a.setVolume(f.audioId(), tileVolume[i] / 100f);
            fs.add(f);
        }
        feeds = List.copyOf(fs);
        audio = a;
        grid = g;
        gridTiles = List.copyOf(tiles);
        Container home = host.videoHome();
        home.remove(host.videoView());
        home.add(g, BorderLayout.CENTER);
        home.revalidate();
        home.repaint();
        MirrorClient c = client;
        if (c != null) for (int i = 0; i < fs.size(); i++) c.askKey(i);
    }

    private void removeGrid() {
        ObservationGrid g = grid;
        List<TileFeed> fs = feeds;
        grid = null;
        feeds = List.of();
        gridTiles = List.of();
        for (TileFeed f : fs) {
            f.stopStream();
            f.release();
        }
        if (audio != null) audio.close();
        audio = null;
        if (g == null) return;
        g.dispose();
        Container parent = g.getParent();
        if (parent != null) {
            parent.remove(g);
            parent.add(host.videoView(), BorderLayout.CENTER);
            parent.revalidate();
            parent.repaint();
        }
    }

    /** Each tile's status, sound, boxes and OSD, as the pilot's own grid shows them. */
    private void refreshTiles(long now) {
        ObservationGrid g = grid;
        List<TileFeed> fs = feeds;
        if (g == null) return;
        boolean invert = host.invertPitch();
        for (int i = 0; i < fs.size(); i++) {
            TileFeed f = fs.get(i);
            ObserverLink l = f.link();
            long last = l.lastPacketAt();
            String status = ObservationSession.tileStatus(l.state(), l.stateAt(), last, false, () -> {
                VideoPipeline.Stats vs = f.video().stats();
                return new int[]{vs.width(), vs.height(), vs.shownFps()};
            }, false, now);
            FcTelemetry t = l.telemetry();
            OsdRenderer.Data osd = ObservationOsd.data(t, f.video().stats(), last, invert, now);
            ObservationAudio a = audio;
            g.update(i, new ObservationGrid.TileState(status, a != null && a.isSpeaking(f.audioId()), tileMuted[i], tileVolume[i],
                    null, t.getMotion().shown(now), osd));
        }
    }

    /** The spectator's grid: sound here only; the pilot PC chooses what is selected. */
    private final class GridListener implements ObservationGrid.Listener {
        @Override
        public void select(int tile) {
        }

        @Override
        public void enlarge(int tile) {
        }

        @Override
        public void connect(int tile) {
            host.logLine("Spectator: watch only.");
        }

        @Override
        public void mute(int tile, boolean muted) {
            if (tile < 0 || tile >= MirrorHost.MAX_TILES) return;
            tileMuted[tile] = muted;
            List<TileFeed> fs = feeds;
            if (audio != null && tile < fs.size()) audio.setMuted(fs.get(tile).audioId(), muted);
        }

        @Override
        public void volume(int tile, int volume, boolean done) {
            if (tile < 0 || tile >= MirrorHost.MAX_TILES) return;
            tileVolume[tile] = volume;
            List<TileFeed> fs = feeds;
            if (audio != null && tile < fs.size()) audio.setVolume(fs.get(tile).audioId(), volume / 100f);
        }

        @Override
        public void hover(int tile) {
        }
    }

    @Override
    public void close() {
        unwatch();
        MirrorHost h = mirrorHost;
        mirrorHost = null;
        if (h != null) h.close();
    }
}
