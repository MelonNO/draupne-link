package de.droiddrone.pc.gui;

import de.droiddrone.pc.Mirror;
import de.droiddrone.pc.MirrorHost;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.fleet.Fleet;
import de.droiddrone.pc.fleet.VirtualDrone;
import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.observe.ObserverLink;
import de.droiddrone.pc.observe.TileFeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The mirror as the windows use it (1.0.56), on loopback with two virtual drones: a pilot PC observing them, and a
 * spectator whose video area takes the pilot's tiles - each tile's relayed stream decoded in a pipeline of its own -
 * and whose FLEET list is the pilot's; when the pilot stops observing, the spectator's single picture comes back.
 */
class MirrorControllerTest {
    private final List<AutoCloseable> open = new ArrayList<>();
    private volatile boolean running = true;
    private javax.swing.Timer ticks;

    @AfterEach
    void tearDown() throws Exception {
        running = false;
        if (ticks != null) SwingUtilities.invokeAndWait(ticks::stop);
        for (int i = open.size() - 1; i >= 0; i--) open.get(i).close();
    }

    /** A window's side, without the window: what the mirror reads and the video area it puts the grid in. */
    private static class FakeHost implements MirrorController.Host {
        volatile int mode = Mirror.IDLE;
        volatile List<TileFeed> feeds = List.of();
        volatile List<FleetCards.Card> cards;
        final JPanel home = new JPanel(new BorderLayout());
        final JPanel video = new JPanel();
        final PcConfig config;

        FakeHost(PcConfig config) {
            this.config = config;
            home.add(video, BorderLayout.CENTER);
        }

        @Override public void logLine(String text) { }
        @Override public boolean spectatorsAllowed() { return true; }
        @Override public String key() { return config.getKey(); }
        @Override public int mode() { return mode; }
        @Override public String phone() { return ""; }
        @Override public List<TileFeed> tileFeeds() { return feeds; }
        @Override public int selectedTile() { return 0; }
        @Override public int enlargedTile() { return -1; }
        @Override public List<FleetCards.Card> fleetCards() { return cards; }
        @Override public Pins pins() { return new Pins(null); }
        @Override public MapGraphics graphics() { return new MapGraphics(null); }
        @Override public Container videoHome() { return home; }
        @Override public JComponent videoView() { return video; }
        @Override public ObservationGrid newGrid(ObservationGrid.Listener listener) { return new ObservationGrid(listener); }
        @Override public MapView miniMap() { return null; }
        @Override public MapWindow mapWindowIfOpened() { return null; }
        @Override public boolean invertPitch() { return false; }
    }

    /** The pilot's link port: MIRROR_HELLO to its host, as the link or the name service hand it on. */
    private int linkPort(MirrorHost host) throws Exception {
        DatagramSocket s = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        s.setSoTimeout(100);
        open.add(s::close);
        Thread t = new Thread(() -> {
            byte[] buf = new byte[1500];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            while (running) {
                try {
                    p.setLength(buf.length);
                    s.receive(p);
                    if (buf[0] == Mirror.HELLO) host.onHello(p, s);
                } catch (java.net.SocketTimeoutException e) {
                    // again
                } catch (Exception e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return s.getLocalPort();
    }

    private static void waitFor(BooleanSupplier ok, long ms, String what) throws Exception {
        long until = System.currentTimeMillis() + ms;
        while (true) {
            boolean[] r = new boolean[1];
            SwingUtilities.invokeAndWait(() -> r[0] = ok.getAsBoolean());
            if (r[0]) return;
            if (System.currentTimeMillis() > until) fail("waited " + ms + " ms for " + what);
            Thread.sleep(20);
        }
    }

    @Test
    void theSpectatorShowsThePilotsTilesAndFleetAndItsOwnPictureAgainAfter() throws Exception {
        PcConfig config = PcConfig.defaults();
        Fleet fleet = new Fleet(config, null, line -> {
        });
        open.add(fleet);
        VirtualDrone a = fleet.add(fleet.freeName(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0);
        VirtualDrone b = fleet.add(fleet.freeName(), Fleet.DEFAULT_LAT + 0.001, Fleet.DEFAULT_LON, 90);

        // the pilot PC observes both (its tiles' pictures decoded nowhere here: only passed on)
        FakeHost pilotHost = new FakeHost(config);
        MirrorController pilot = new MirrorController(pilotHost);
        open.add(pilot);
        MirrorHost host = pilot.start();
        List<TileFeed> feeds = new ArrayList<>();
        ObservationGrid pilotGrid = new ObservationGrid(new NoGrid());
        pilotGrid.setPhones(List.of(a.name(), b.name()));
        for (VirtualDrone d : List.of(a, b)) {
            TileFeed f = new TileFeed(d.name(), d.address(), d.port(), config.getKey(), pilotGrid.sink(feeds.size()), null,
                    ObserverLink.Quality.GRID, line -> {
            });
            feeds.add(f);
            open.add(() -> {
                f.stopStream();
                f.release();
            });
        }
        pilotHost.feeds = List.copyOf(feeds);
        pilotHost.mode = Mirror.OBSERVER;
        pilotHost.cards = List.of(new FleetCards.Card(a.name(), true, MapView.Kind.ACTIVE, "IDLE", 3.9, 0, 80, false, -1, 0, 1000,
                3, 1, 9, 0, 0, FleetCards.NO_TEMP, FleetCards.NO_TEMP, null, null));

        FakeHost watcherHost = new FakeHost(config);
        MirrorController watcher = new MirrorController(watcherHost);
        open.add(watcher);
        int port = linkPort(host);
        SwingUtilities.invokeAndWait(() -> {
            watcher.watch(InetAddress.getLoopbackAddress(), port);
            ticks = new javax.swing.Timer(33, e -> {
                pilot.tick();
                watcher.tick();
            });
            ticks.start();
        });

        // the pilot's grid takes the spectator's video area, a tile a drone, each decoding its relayed stream
        waitFor(() -> grid(watcherHost) != null && grid(watcherHost).count() == 2, 5000, "the pilot's two tiles");
        waitFor(() -> pilotHost.cards.equals(watcher.fleetShown(List.of())), 5000, "the pilot's FLEET list");
        waitFor(() -> watcher.mirroredMode() != null && watcher.mirroredMode().tiles().equals(List.of(a.name(), b.name())), 3000, "the tiles' names");
        waitFor(() -> decoding(watcher) == 2, 15_000, "both tiles' pictures decoded here");

        // the pilot stops observing: the spectator's own picture is back
        pilotHost.mode = Mirror.IDLE;
        waitFor(() -> grid(watcherHost) == null, 3000, "the grid gone");
        SwingUtilities.invokeAndWait(() -> assertSame(watcherHost.video, watcherHost.home.getComponent(0)));
        // and stopping watching gives back this PC's own FLEET list
        SwingUtilities.invokeAndWait(watcher::unwatch);
        List<FleetCards.Card> own = List.of();
        assertSame(own, watcher.fleetShown(own));
        assertEquals(false, watcher.isMirroring());
    }

    private static ObservationGrid grid(FakeHost h) {
        for (Component c : h.home.getComponents()) if (c instanceof ObservationGrid g) return g;
        return null;
    }

    /**
     * How many of the spectator's tiles decode their relayed stream: frames coming at the drone's rate, decoded, none
     * damaged (shown is 0 here on both sides: a grid never laid out on a screen draws nothing).
     */
    private static int decoding(MirrorController m) {
        int n = 0;
        for (TileFeed f : m.feedsForTest()) {
            de.droiddrone.pc.video.VideoPipeline.Stats s = f.video().stats();
            if (s.receivedFps() >= 10 && s.decodeMs() > 0 && s.damaged() == 0 && s.error() == null) n++;
        }
        return n;
    }

    private static final class NoGrid implements ObservationGrid.Listener {
        @Override public void select(int tile) { }
        @Override public void enlarge(int tile) { }
        @Override public void connect(int tile) { }
        @Override public void mute(int tile, boolean muted) { }
        @Override public void volume(int tile, int volume, boolean done) { }
        @Override public void hover(int tile) { }
    }
}
