package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.FleetSwitches;
import de.droiddrone.pc.Mirror;
import de.droiddrone.pc.MirrorHost;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.air.GroupLink;
import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.observe.TileFeed;
import de.droiddrone.pc.route.Route;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.geom.Point2D;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static de.droiddrone.testing.Waits.waitForOnSwing;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD GU-8: a spectator or an observation PC shows the fleet's new map and OSD elements - the group, routes, drone
 * states, TRAFFIC - and never gets its new controls: no right-click fleet items, no DISPATCH, no CALL RESERVE, no mode
 * buttons, no route sending. GU-9: what the paint reads of the fleet is worked out beforehand, and the group's picture
 * never waits for the group's tick.
 */
class FleetWatchOnlyTest {
    static final double LAT = 59.91, LON = 10.73;
    private final List<AutoCloseable> open = new ArrayList<>();
    private volatile boolean running = true;
    private javax.swing.Timer ticks;

    @AfterEach
    void tearDown() throws Exception {
        running = false;
        if (ticks != null) SwingUtilities.invokeAndWait(ticks::stop);
        for (int i = open.size() - 1; i >= 0; i--) open.get(i).close();
    }

    /** The window's side of FleetOps: flies "Lead" (an ArduPilot drone), every phone on loopback, every question a yes. */
    private static final class Host implements FleetOps.Host {
        volatile boolean watchOnly;
        final List<String> log = new CopyOnWriteArrayList<>();
        final AtomicInteger asked = new AtomicInteger();

        public String flownPhone() {
            asked.incrementAndGet();
            return "Lead";
        }

        public InetSocketAddress addressOf(String phone) {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 9); // discard: nothing answers
        }

        public String key() {
            return "DD";
        }

        public void log(String line) {
            log.add(line);
        }

        public void alert(String text) {
            log.add("! " + text);
        }

        public void flyNow(String phone) {
        }

        public boolean confirm(String question) {
            return true;
        }

        public boolean watchOnly() {
            return watchOnly;
        }
    }

    private static List<String> texts(List<JMenuItem> items) {
        return items.stream().map(JMenuItem::getText).toList();
    }

    private static List<String> texts(JPopupMenu menu) {
        List<String> out = new ArrayList<>();
        for (Component c : menu.getComponents()) if (c instanceof JMenuItem i) out.add(i.getText());
        return out;
    }

    private static MapView map(Path dir) {
        MapView map = new MapView(new TileCache(dir, "test", false));
        map.setSize(400, 300);
        map.setFollow(false);
        map.centreOn(LAT, LON, 15);
        return map;
    }

    private static GroupLink.Picture picture() {
        return new GroupLink.Picture(LAT, LON, List.of(new double[]{LAT + 0.0002, LON}, new double[]{LAT, LON + 0.0003}),
                List.of(new double[]{LAT + 0.0005, LON}), 30, 50, 120, 10, 40, 6, "Lead");
    }

    @Test
    void aWatchingPcGetsNoneOfTheFleetsControls(@TempDir Path dir) throws Exception {
        Host host = new Host();
        FleetOps ops = new FleetOps(host);
        open.add(ops::close);
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            // the pilot's PC: SAFE TO AUTOLAUNCH and FOLLOW GROUP on another drone; marked, the map offers DISPATCH
            assertTrue(texts(ops.items("MobileB")).contains("SAFE TO AUTOLAUNCH: mark MobileB"), texts(ops.items("MobileB")).toString());
            assertTrue(texts(ops.items("MobileB")).contains("FOLLOW GROUP: add MobileB"));
            ops.items("MobileB").stream().filter(i -> i.getText().startsWith("SAFE TO AUTOLAUNCH: mark")).findFirst().orElseThrow().doClick();
            assertTrue(ops.isMarked("MobileB"));
            assertTrue(texts(ops.placeItems(LAT, LON)).get(0).startsWith("DISPATCH 1 marked drone here"));

            // a spectator's or an observation PC's: nothing of it
            host.watchOnly = true;
            assertEquals(List.of(), ops.items("MobileB"), "no right-click fleet items");
            assertEquals(List.of(), ops.items("MobileC"));
            assertEquals(List.of(), ops.placeItems(LAT, LON), "no DISPATCH");

            // the map's right-click on a drone and on a place, as the map window wires them
            MapView map = map(dir);
            map.setFleetItems(ops::items);
            map.setFleetPlaceItems(ops::placeItems);
            map.setNetDrones(List.of(new MapView.NetDrone("MobileB", LAT, LON, "ACTIVE - IDLE - IN GROUP", MapView.Kind.ACTIVE, false, null)),
                    (n, w) -> { });
            Point2D at = map.projection().toScreen(LAT, LON);
            List<String> onDrone = texts(map.menu((int) at.getX(), (int) at.getY()));
            assertTrue(onDrone.stream().noneMatch(t -> t.startsWith("SAFE TO AUTOLAUNCH") || t.startsWith("FOLLOW GROUP")
                    || t.startsWith("TAKE CONTROL") || t.startsWith("VIEW ") || t.startsWith("Floor")), onDrone.toString());
            List<String> onPlace = texts(map.menu(20, 20));
            assertTrue(onPlace.stream().noneMatch(t -> t.startsWith("DISPATCH")), onPlace.toString());
            host.watchOnly = false;
            List<String> pilots = texts(map.menu((int) at.getX(), (int) at.getY()));
            assertTrue(pilots.contains("SAFE TO AUTOLAUNCH: clear MobileB"), "the same map on the pilot's PC: " + pilots);
            assertTrue(texts(map.menu(20, 20)).stream().anyMatch(t -> t.startsWith("DISPATCH")));
            host.watchOnly = true;

            // the elements still show: the group from above, the drone's state on its label
            map.setGroup(picture());
            assertNotNull(map.group(), "the group drawn");
            assertTrue(map.netDrones().get(0).state().contains("IN GROUP"));
        });

        // the radio's switches do nothing on a watching PC: no CALL RESERVE, no DISCARD LEAD
        ops.onSwitch(FleetSwitches.CALL_RESERVE);
        ops.onSwitch(FleetSwitches.DISCARD_LEAD);
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(host.log.stream().noneMatch(l -> l.contains("CALL RESERVE") || l.contains("DISCARD LEAD")), host.log.toString());
        host.watchOnly = false;
        ops.onSwitch(FleetSwitches.CALL_RESERVE);
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(host.log.contains("! CALL RESERVE: no follower group to join."), "the pilot's PC answers it: " + host.log);
    }

    @Test
    void noModeButtonsAndNoRouteSendingOnAWatchingPc(@TempDir Path dir) throws Exception {
        List<byte[]> sent = new ArrayList<>();
        ArduModeBar bar = new ArduModeBar(c -> sent.add(c));
        FcTelemetry.Status ardu = new FcTelemetry.Status(true, List.of("ARM", "LOITER"), List.of(), 0, 0, 0, true, "LOITER");
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            bar.update(ardu, de.droiddrone.pc.ArduModes.LOITER, null, 0, false);
            assertFalse(bar.isVisible(), "a spectator's video: no mode buttons");
            for (Component c : bar.getComponents()) if (c instanceof JButton b) b.doClick();
            assertTrue(sent.isEmpty(), "and a press sends nothing");
            bar.update(ardu, de.droiddrone.pc.ArduModes.LOITER, null, 1, true);
            assertTrue(bar.isVisible(), "the pilot's own ArduPilot drone: shown");

            boolean[] watching = {true};
            MapView map = map(dir);
            RouteWindow routes = new RouteWindow(null, map, null, () -> null, () -> null, () -> watching[0]);
            try {
                Route r = new Route("Ridge");
                r.add(LAT, LON);
                r.add(LAT + 0.01, LON);
                routes.setRoute(r);
                for (JButton b : routes.sendButtons()) assertFalse(b.isVisible(), b.getText() + ": not on a watching PC");
                assertSame(r, map.route(), "the route is drawn all the same");
                watching[0] = false;
                routes.setRoute(r);
                for (JButton b : routes.sendButtons()) assertTrue(b.isVisible(), b.getText() + " on the pilot's PC");
            } finally {
                routes.dispose();
            }
        });
    }

    // ------------------------------------------------------------------ the spectator sees the pilot's

    /** A window's side for the mirror, without the window. */
    private static class MirrorSide implements MirrorController.Host {
        final PcConfig config;
        final JPanel home = new JPanel(new BorderLayout()), video = new JPanel();
        volatile GroupLink.Picture group;
        volatile OsdFleet osd;
        volatile MapView routeMap;

        MirrorSide(PcConfig config) {
            this.config = config;
            home.add(video, BorderLayout.CENTER);
        }

        @Override public void logLine(String text) { }
        @Override public boolean spectatorsAllowed() { return true; }
        @Override public String key() { return config.getKey(); }
        @Override public int mode() { return Mirror.PILOT; }
        @Override public String phone() { return "Lead"; }
        @Override public List<TileFeed> tileFeeds() { return List.of(); }
        @Override public int selectedTile() { return 0; }
        @Override public int enlargedTile() { return -1; }
        @Override public List<FleetCards.Card> fleetCards() { return List.of(); }
        @Override public Pins pins() { return new Pins(null); }
        @Override public MapGraphics graphics() { return new MapGraphics(null); }
        @Override public Container videoHome() { return home; }
        @Override public JComponent videoView() { return video; }
        @Override public ObservationGrid newGrid(ObservationGrid.Listener listener) { return new ObservationGrid(listener); }
        @Override public MapView miniMap() { return null; }
        @Override public MapWindow mapWindowIfOpened() { return null; }
        @Override public boolean invertPitch() { return false; }
        @Override public GroupLink.Picture fleetGroup() { return group; }
        @Override public OsdFleet fleetOsd() { return osd; }
        @Override public MapView routeMap() { return routeMap; }
    }

    /** The pilot's link port: MIRROR_HELLO to its host, as the link hands it on. */
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

    @Test
    void theSpectatorShowsThePilotsGroupFleetOsdAndRoute(@TempDir Path dir) throws Exception {
        PcConfig config = PcConfig.defaults();
        MirrorSide pilotSide = new MirrorSide(config);
        pilotSide.group = picture();
        pilotSide.osd = new OsdFleet("+1 RESERVES: R1 1:20", "GROUP 6", 42, 40, "GNSS", "TRAFFIC 14 M NE  +3 M", "LEFT 6:20", false, false,
                List.of(new OsdFleet.Mini("R1", null, "CLIMBING", 12, 40, false)));
        Route route = new Route("Ridge");
        route.add(LAT, LON);
        route.add(LAT + 0.01, LON + 0.01);
        SwingUtilities.invokeAndWait(() -> {
            pilotSide.routeMap = map(dir);
            pilotSide.routeMap.setRoute(route, List.of(new double[]{LAT, LON}, new double[]{LAT + 0.01, LON + 0.01}));
        });
        MirrorController pilot = new MirrorController(pilotSide);
        open.add(pilot);
        MirrorHost host = pilot.start();
        MirrorSide watcherSide = new MirrorSide(config);
        MirrorController watcher = new MirrorController(watcherSide);
        open.add(watcher);
        int port = linkPort(host);
        SwingUtilities.invokeAndWait(() -> {
            assertNull(watcher.groupShown(null), "not watching: its own (none)");
            watcher.watch(InetAddress.getLoopbackAddress(), port);
            ticks = new javax.swing.Timer(33, e -> {
                pilot.tick();
                watcher.tick();
            });
            ticks.start();
        });
        waitForOnSwing(() -> watcher.groupShown(null) != null, 5000, "the pilot's group");
        waitForOnSwing(() -> watcher.fleetOsdShown() != null, 3000, "the pilot's fleet OSD figures");
        waitForOnSwing(() -> watcher.mirroredRouteForTest() != null, 3000, "the pilot's route");
        SwingUtilities.invokeAndWait(() -> {
            GroupLink.Picture g = watcher.groupShown(null);
            assertEquals(6, g.followers());
            assertEquals("Lead", g.lead());
            assertEquals(2, g.cells().size());
            assertEquals(40, g.floorM());
            assertEquals(pilotSide.osd, watcher.fleetOsdShown(), "reserves, group, TRAFFIC, HOME as the pilot's OSD has them");
            assertEquals(route.toText(), watcher.mirroredRouteForTest().route().toText());
            assertEquals(2, watcher.mirroredRouteForTest().planned().size());
        });
        // the group ends on the pilot PC: gone here too; watching ends: this PC's own again
        pilotSide.group = null;
        waitForOnSwing(() -> watcher.groupShown(null) == null, 3000, "the group gone");
        SwingUtilities.invokeAndWait(() -> {
            watcher.unwatch();
            GroupLink.Picture own = picture();
            assertSame(own, watcher.groupShown(own));
            assertNull(watcher.fleetOsdShown());
        });
    }

    @Test
    void theMirrorsFleetSectionsReadBack() {
        GroupLink.Picture p = picture();
        GroupLink.Picture back = MirrorCodec.group(MirrorCodec.group(p));
        assertEquals(p.lat(), back.lat());
        assertEquals(p.keepOutHalfM(), back.keepOutHalfM());
        assertArrayEquals(p.shafts().get(0), back.shafts().get(0));
        assertNull(MirrorCodec.group(MirrorCodec.group((GroupLink.Picture) null)));
        assertNull(MirrorCodec.group(new byte[]{1, 2}), "broken bytes: none");

        OsdFleet none = MirrorCodec.osd(MirrorCodec.osd((OsdFleet) null));
        assertEquals(OsdFleet.NONE, none);
        OsdFleet f = OsdFleet.sample();
        assertEquals(f, MirrorCodec.osd(MirrorCodec.osd(f)), "the launch view's words and bar; its picture stays on the pilot PC");
        assertNull(MirrorCodec.route(MirrorCodec.route(null, null)));
        assertNull(MirrorCodec.route(null));
    }

    // ------------------------------------------------------------------ GU-9

    /** The OSD asks at the picture's rate: it reads the figures worked out in the refresh, at most 4 times a second. */
    @Test
    void thePaintReadsTheFleetsFiguresAndWorksOutNothing() throws Exception {
        Host host = new Host();
        FleetOps ops = new FleetOps(host);
        open.add(ops::close);
        SwingUtilities.invokeAndWait(() -> {
            assertSame(OsdFleet.NONE, ops.osd());
            ops.refreshOsd(null, 10_000);
            int work = host.asked.get();
            assertTrue(work > 0, "worked out in the refresh");
            OsdFleet f = ops.osd();
            for (int i = 0; i < 1000; i++) assertSame(f, ops.osd()); // a second at a 1000 Hz picture
            assertEquals(work, host.asked.get(), "the paint does no work");
            ops.refreshOsd(null, 10_000 + FleetOps.OSD_EVERY_MS - 1);
            assertEquals(work, host.asked.get(), "the refresh at most every " + FleetOps.OSD_EVERY_MS + " ms");
            ops.refreshOsd(null, 10_000 + FleetOps.OSD_EVERY_MS);
            assertTrue(host.asked.get() > work);
        });
    }

    /** The group's tick (its planner) holds the group's lock: the map and FLEET read the last tick's view, never wait. */
    @Test
    void theGroupsViewNeverWaitsForItsTick() throws Exception {
        GroupLink link = new GroupLink("DD", new GroupLink.Listener() {
            public void event(String text) {
            }

            public void newLead(String drone) {
            }
        });
        open.add(link);
        CountDownLatch held = new CountDownLatch(1), done = new CountDownLatch(1);
        Thread tick = new Thread(() -> {
            synchronized (link) { // as a long tick holds it
                held.countDown();
                try {
                    done.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
        });
        tick.start();
        assertTrue(held.await(5, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        assertNull(link.picture());
        assertNull(link.memberState("F1"));
        assertTrue(link.members().isEmpty());
        assertFalse(link.active());
        assertTrue(Double.isNaN(link.nextApproachAlt()));
        link.setFloor(50);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        done.countDown();
        tick.join(5000);
        assertTrue(ms < 1000, "read while the tick held the lock: " + ms + " ms");
    }
}
