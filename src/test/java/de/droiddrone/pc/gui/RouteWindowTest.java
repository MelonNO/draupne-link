package de.droiddrone.pc.gui;

import de.droiddrone.common.FcCommand;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.MissionBlock;
import de.droiddrone.common.MissionItem;
import de.droiddrone.common.MissionUpload;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.route.Route;
import de.droiddrone.pc.route.TerrainFill;
import de.droiddrone.pc.route.TerrainStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.geom.Point2D;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static de.droiddrone.testing.Waits.waitForOnSwing;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet area B through the ROUTE window's buttons and the map's right-click: draw, edit, plan over made-up ground,
 * UPLOAD to an ArduPilot drone whose phone answers (or doesn't, or says it failed), GO, FENCE - and what goes wrong:
 * an edit while a plan is out, an upload while one runs, a drone that moved since the plan, a watching PC, a route
 * over the antimeridian, no ground heights to be had. Never shown on screen; nothing leaves the PC.
 */
class RouteWindowTest {
    static final double LAT = 59.91, LON = 10.73;

    @TempDir
    Path dir;
    private RouteWindow window;
    private MapView map;
    private StandInLink link;
    private final List<double[]> lookedUp = new CopyOnWriteArrayList<>();
    private volatile boolean offline;
    private volatile boolean watching;

    /** The phone's side of the upload as the tests want it. */
    enum Phone { ANSWERS, SLOW, SILENT, FAILS }

    /** Ground heights as the phone reports them; what the route window reads of the drone. */
    static final class StandInTelemetry extends FcTelemetry {
        volatile FcTelemetry.Gps gps;
        volatile FcTelemetry.Home home;
        volatile boolean armed;
        volatile FcTelemetry.RouteUpload upload;

        @Override
        public Gps getGps() {
            return gps;
        }

        @Override
        public Home getHome() {
            return home;
        }

        @Override
        public boolean isArmedNow(long nowMs, long maxAgeMs) {
            return armed;
        }

        @Override
        public RouteUpload getRouteUpload() {
            return upload;
        }
    }

    /** This PC's link to an ArduPilot drone; its phone answers a route block as the real one does (never started: no socket). */
    static final class StandInLink extends ControlLink {
        final StandInTelemetry t = new StandInTelemetry();
        final List<byte[]> blocks = new CopyOnWriteArrayList<>();
        final List<byte[]> commands = new CopyOnWriteArrayList<>();
        volatile Phone phone = Phone.ANSWERS;

        StandInLink() throws java.io.IOException {
            super(PcConfig.defaults(), InetAddress.getLoopbackAddress(), 1, 0);
        }

        @Override
        public FcTelemetry getTelemetry() {
            return t;
        }

        @Override
        public FcInfo getFcInfo() {
            return new FcInfo(FcInfo.FC_VARIANT_ARDUPILOT, 4, 6, 0, 0, 0, 3, 2);
        }

        @Override
        public boolean sendMissionBlock(byte[] packet) {
            blocks.add(packet);
            int block = (packet[1] & 0xff) << 8 | (packet[2] & 0xff);
            Phone p = phone;
            if (p == Phone.SILENT) return true;
            Thread answer = new Thread(() -> {
                try {
                    t.upload = new FcTelemetry.RouteUpload(block, MissionUpload.UPLOADING, 30, "", MonoClock.ms());
                    Thread.sleep(p == Phone.SLOW ? 1500 : 50);
                    t.upload = p == Phone.FAILS ? new FcTelemetry.RouteUpload(block, MissionUpload.FAILED, 40, "item 3 refused", MonoClock.ms())
                            : new FcTelemetry.RouteUpload(block, MissionUpload.DONE, 100, "", MonoClock.ms());
                } catch (InterruptedException ignored) {
                }
            });
            answer.setDaemon(true);
            answer.start();
            return true;
        }

        @Override
        public boolean sendFcCommand(byte[] command) {
            commands.add(command);
            return true;
        }

        void at(double lat, double lon) {
            t.gps = new FcTelemetry.Gps(2, 12, 0, 0, -1, 0, lat, lon, 0, 0, 0, MonoClock.ms(), true, false);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        link = new StandInLink();
        link.at(LAT, LON);
        link.t.home = new FcTelemetry.Home(LAT, LON, MonoClock.ms());
        TerrainFill.Lookup ground = (lat, lon) -> {
            lookedUp.add(new double[]{lat, lon});
            return offline ? null : 100.0 + 20 * Math.sin(lat * 3000); // made-up hills
        };
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            map = new MapView(new TileCache(dir, "test", false));
            map.setSize(800, 600);
            map.setFollow(false);
            map.centreOn(LAT, LON, 15);
            window = new RouteWindow(null, map, new TerrainStore(null), ground, () -> link, () -> null, () -> watching);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        SwingUtilities.invokeAndWait(() -> window.dispose());
    }

    private JButton upload() {
        return window.sendButtons()[0];
    }

    private JButton go() {
        return window.sendButtons()[1];
    }

    private JButton fence() {
        return window.sendButtons()[2];
    }

    private void route(double[]... points) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Route r = new Route("Test");
            for (double[] p : points) r.add(p[0], p[1]);
            window.setRoute(r);
        });
    }

    private void planned() throws Exception {
        waitForOnSwing(() -> window.planned() && upload().isEnabled(), 20_000, "planned: " + window.statusText());
    }

    private void click(JButton b) throws Exception {
        SwingUtilities.invokeAndWait(b::doClick);
    }

    private JMenuItem menuItem(double lat, double lon, String startsWith) {
        Point2D at = map.projection().toScreen(lat, lon);
        for (Component c : map.menu((int) at.getX(), (int) at.getY()).getComponents()) {
            if (c instanceof JMenuItem i && i.getText().startsWith(startsWith)) return i;
        }
        throw new AssertionError("no " + startsWith);
    }

    private static List<MissionItem> items(byte[] block) {
        return MissionBlock.read(block, 1, block.length - 1).items;
    }

    // ------------------------------------------------------------------ the pilot's way

    @Test
    void drawUploadGoOnTheGround() throws Exception {
        route(new double[]{LAT + 0.002, LON}, new double[]{LAT + 0.002, LON + 0.003});
        planned();
        assertFalse(go().isEnabled(), "GO only after UPLOAD");
        click(upload());
        waitForOnSwing(() -> go().isEnabled(), 10_000, "uploaded: " + window.statusText());
        assertTrue(window.statusText().startsWith("Route on the drone, read back"), window.statusText());
        List<MissionItem> m = items(link.blocks.get(0));
        assertEquals(MissionItem.NAV_TAKEOFF, m.get(1).command, "planned on the ground: it takes off");
        assertEquals(MissionItem.NAV_LAND, m.get(m.size() - 1).command, "and lands where it took off");
        click(go());
        assertEquals(FcCommand.MISSION_START, FcCommand.read(link.commands.get(0), link.commands.get(0).length).kind);
    }

    @Test
    void aPointDeletedOnTheMapIsNeverUploadedFromTheOldPlan() throws Exception {
        double[] a = {LAT + 0.002, LON}, b = {LAT + 0.002, LON + 0.003}, c = {LAT + 0.004, LON + 0.003};
        route(a, b, c);
        planned();
        SwingUtilities.invokeAndWait(() -> {
            menuItem(c[0], c[1], "Route point 3: delete").doClick();
            assertFalse(window.planned(), "the plan of three points is gone with the third");
            assertFalse(upload().isEnabled(), "no UPLOAD until the two points are planned");
            upload().doClick();
        });
        assertTrue(link.blocks.isEmpty(), "nothing sent from the old plan");
        planned();
        click(upload());
        waitForOnSwing(() -> !link.blocks.isEmpty(), 5000, "sent");
        for (MissionItem m : items(link.blocks.get(0))) {
            assertTrue(Math.abs(m.x / 1e7 - c[0]) > 1e-4 || Math.abs(m.y / 1e7 - c[1]) > 1e-4, "point 3 is not in it: " + m.x + " " + m.y);
        }
    }

    @Test
    void theLastPointDeletedLeavesNothingToUpload() throws Exception {
        double[] a = {LAT + 0.002, LON};
        route(a);
        planned();
        SwingUtilities.invokeAndWait(() -> menuItem(a[0], a[1], "Route point 1: delete").doClick());
        Thread.sleep(500);
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(window.planned());
            assertFalse(upload().isEnabled());
            assertFalse(go().isEnabled());
        });
    }

    @Test
    void clearWhileThePlanIsOutLeavesNoPlan() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Route r = new Route("Test");
            r.add(LAT + 0.002, LON);
            r.add(LAT + 0.004, LON + 0.004);
            window.setRoute(r);
            window.setRoute(null); // CLEAR at once
        });
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(window.planned(), "the old route's plan came in after CLEAR: " + window.statusText());
            assertFalse(upload().isEnabled());
            assertNull(map.route());
        });
    }

    @Test
    void aPointsOwnHeightIsFlown() throws Exception {
        double[] a = {LAT + 0.002, LON}, b = {LAT + 0.002, LON + 0.003};
        route(a, b);
        planned();
        SwingUtilities.invokeAndWait(() -> menuItem(b[0], b[1], "Route point 2: its own height").doClick());
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JSpinner point = all(window.getContentPane(), javax.swing.JSpinner.class).get(2);
            assertTrue(point.isEnabled(), "point 2 selected");
            point.setValue(150.0);
            assertEquals(150, map.route().point(1).heightM(), 1e-9);
        });
        planned();
        click(upload());
        waitForOnSwing(() -> go().isEnabled(), 10_000, window.statusText());
        List<MissionItem> m = items(link.blocks.get(0));
        MissionItem hold = m.stream().filter(x -> x.command == MissionItem.NAV_LOITER_UNLIM).findFirst().orElseThrow();
        assertTrue(hold.z > 100 + 150 - 25, "the hold at point 2 is 150 m over its ground: " + hold.z);
    }

    // ------------------------------------------------------------------ what goes wrong

    @Test
    void aSecondUploadWhileTheFirstRunsSendsNothingAndGoWaits() throws Exception {
        route(new double[]{LAT + 0.002, LON}, new double[]{LAT + 0.002, LON + 0.003});
        planned();
        click(upload());
        waitForOnSwing(() -> go().isEnabled(), 10_000, "a first route up");
        link.phone = Phone.SLOW;
        link.blocks.clear();
        SwingUtilities.invokeAndWait(() -> {
            upload().doClick();
            assertFalse(go().isEnabled(), "no GO on the route before while a new one goes up");
            assertFalse(upload().isEnabled());
            assertFalse(fence().isEnabled(), "no FENCE in the middle of it");
            upload().doClick();
            go().doClick();
            fence().doClick();
        });
        waitForOnSwing(() -> go().isEnabled(), 10_000, "the second up: " + window.statusText());
        assertEquals(1, link.blocks.stream().map(b -> (b[1] & 0xff) << 8 | (b[2] & 0xff)).distinct().count(), "one block");
        assertTrue(link.commands.isEmpty(), "GO sent nothing meanwhile");
    }

    @Test
    void thePhoneSilentOrFailingLeavesNoGo() throws Exception {
        route(new double[]{LAT + 0.002, LON}, new double[]{LAT + 0.002, LON + 0.003});
        planned();
        link.phone = Phone.FAILS;
        click(upload());
        waitForOnSwing(() -> window.statusText().startsWith("Route upload failed"), 10_000, window.statusText());
        assertTrue(window.statusText().contains("item 3 refused"));
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(go().isEnabled());
            assertTrue(upload().isEnabled(), "UPLOAD again");
        });
        link.phone = Phone.SILENT;
        click(upload());
        waitForOnSwing(() -> window.statusText().startsWith("The phone didn't answer"), 25_000, window.statusText());
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(go().isEnabled());
            assertTrue(upload().isEnabled());
        });
    }

    @Test
    void theDroneTookOffAndMovedSinceThePlan() throws Exception {
        route(new double[]{LAT + 0.004, LON}, new double[]{LAT + 0.004, LON + 0.003});
        planned(); // on the ground at LAT, LON
        link.t.armed = true;
        link.at(LAT + 0.002, LON + 0.001); // in the air, 230 m on
        click(upload());
        assertTrue(link.blocks.isEmpty(), "not the ground plan from the old start");
        assertTrue(window.statusText().contains("moved"), window.statusText());
        planned();
        click(upload());
        waitForOnSwing(() -> !link.blocks.isEmpty(), 5000, "sent");
        List<MissionItem> m = items(link.blocks.get(0));
        assertTrue(m.stream().noneMatch(x -> x.command == MissionItem.NAV_TAKEOFF), "in the air: no take-off");
        assertNotEquals(MissionItem.NAV_LAND, m.get(m.size() - 1).command, "and it holds at the end, not lands");
    }

    @Test
    void aWatchingPcSendsNothingEvenAtAClick() throws Exception {
        route(new double[]{LAT + 0.002, LON}, new double[]{LAT + 0.002, LON + 0.003});
        planned();
        watching = true;
        waitForOnSwing(() -> !upload().isVisible(), 3000, "hidden");
        SwingUtilities.invokeAndWait(() -> {
            for (JButton b : window.sendButtons()) {
                assertFalse(b.isEnabled(), b.getText() + ": grey too (a hidden button still clicks)");
                b.doClick();
            }
        });
        Thread.sleep(300);
        assertTrue(link.blocks.isEmpty());
        assertTrue(link.commands.isEmpty());
    }

    @Test
    void aHundredAndTwentyPoints() throws Exception {
        double[][] pts = new double[120][];
        for (int i = 0; i < pts.length; i++) pts[i] = new double[]{LAT + 0.001 + (i % 2) * 0.0008, LON + i * 0.0004};
        route(pts);
        planned();
        click(upload());
        waitForOnSwing(() -> go().isEnabled(), 15_000, window.statusText());
        assertTrue(items(link.blocks.get(0)).size() > 120, "every point in it");
    }

    @Test
    void overTheAntimeridianTheGroundIsLookedUpThereOnly() throws Exception {
        link.at(-16.75, 179.9990);
        link.t.home = new FcTelemetry.Home(-16.75, 179.9990, MonoClock.ms());
        route(new double[]{-16.75, 179.9996}, new double[]{-16.75, -179.9996});
        planned();
        assertFalse(lookedUp.isEmpty());
        for (double[] p : lookedUp) assertTrue(Math.abs(p[1]) > 179.99, "looked up at " + p[0] + ", " + p[1] + ": the long way round");
    }

    @Test
    void noGroundHeightsToBeHadStopsAsking() throws Exception {
        offline = true;
        route(new double[]{LAT + 0.002, LON}, new double[]{LAT + 0.002, LON + 0.003});
        waitForOnSwing(() -> window.statusText().contains("can't plan"), 10_000, window.statusText());
        int asked = lookedUp.size();
        Thread.sleep(1500);
        assertEquals(asked, lookedUp.size(), "not asked again and again");
        assertFalse(window.planned());
    }

    @Test
    void aRouteFileCantGoUnderTheFloorOrOffTheScale() {
        Route r = Route.fromText("draupne-route 1\nname x\nheight Infinity\nsmoothing NaN\npoint 59.9 10.7 2.0\npoint 59.91 10.71 -\n");
        assertEquals(Route.DEFAULT_HEIGHT_M, r.heightM(), "an infinite height is not taken");
        assertEquals(Route.DEFAULT_SMOOTHING_M, r.smoothingM());
        assertEquals(Route.MIN_HEIGHT_M, r.point(0).heightM(), "2 m from a file: the floor, as on the map");
        assertFalse(r.point(1).hasOwnHeight());
        assertThrows(IllegalArgumentException.class, () -> Route.fromText("draupne-route 1\npoint NaN 10 -\n"));
        assertThrows(IllegalArgumentException.class, () -> Route.fromText("draupne-route 1\npoint 59 10 Infinity\n"));
        r.setOwnHeight(1, Double.POSITIVE_INFINITY);
        assertFalse(r.point(1).hasOwnHeight());
    }

    private static <T extends Component> List<T> all(Component root, Class<T> type) {
        List<T> out = new java.util.ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T extends Component> void collect(Component c, Class<T> type, List<T> out) {
        if (type.isInstance(c)) out.add(type.cast(c));
        if (c instanceof java.awt.Container k) for (Component x : k.getComponents()) collect(x, type, out);
    }

}
