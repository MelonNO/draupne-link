package de.droiddrone.pc.gui;

import de.droiddrone.common.FleetStatus;
import de.droiddrone.common.launch.LaunchSequence;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.air.GroupLink;
import de.droiddrone.pc.air.virt.VirtualFleetPhone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fleet's flows through the window's own controls (FleetOps' right-click items pressed on Swing's thread, its
 * once-a-second tick) against virtual fleet phones on loopback: a drone used twice (dispatched or called again after it
 * landed), a reserve call cancelled, a group lost while a reserve climbs, TRAFFIC of drones gone quiet, a flying
 * follower not taken as a reserve. Kinematic copters stand in for ArduPilot; names and places made up; real time.
 */
class FleetFlowLoopbackTest {
    static final double LAT = 60.0, LON = 10.0, GROUND = 0;
    private final Map<String, VirtualFleetPhone> phones = new LinkedHashMap<>();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private final Set<String> noAddress = ConcurrentHashMap.newKeySet();
    private volatile String flown = "Lead";
    private FleetOps ops;

    @AfterEach
    void close() {
        if (ops != null) ops.close();
        for (VirtualFleetPhone p : phones.values()) p.close();
    }

    private VirtualFleetPhone phone(String id, double e, double n, double alt) throws Exception {
        VirtualFleetPhone p = new VirtualFleetPhone(id, LAT, LON, GROUND, e, n, alt, "DD");
        p.copter.climb = 8;
        p.copter.guidedSpeed = 15;
        phones.put(id, p);
        return p;
    }

    private VirtualFleetPhone onTheGround(String id, double e, double n) throws Exception {
        VirtualFleetPhone p = phone(id, e, n, GROUND);
        p.landed();
        return p;
    }

    private FleetOps ops() {
        return new FleetOps(new FleetOps.Host() {
            public String flownPhone() {
                return flown;
            }

            public InetSocketAddress addressOf(String phone) {
                VirtualFleetPhone p = phones.get(phone);
                return p == null || noAddress.contains(phone) ? null : p.address();
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
                flown = phone;
            }

            public long checkInWaitMs(String phone) {
                return 0;
            }

            public boolean confirm(String question) {
                return true;
            }

            public boolean confirmTitled(String title, String text) {
                return true;
            }

            public de.droiddrone.pc.route.TerrainSource terrain() {
                return (lat, lon) -> GROUND;
            }
        });
    }

    private void tick() {
        try {
            SwingUtilities.invokeAndWait(ops::tickReserves);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void until(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            tick();
            if (ok.getAsBoolean()) return;
            Thread.sleep(250);
        }
        fail("waited " + ms / 1000 + " s for: " + what + "\n" + log);
    }

    private List<JMenuItem> items(String drone) throws Exception {
        List<JMenuItem>[] out = new List[1];
        SwingUtilities.invokeAndWait(() -> out[0] = ops.items(drone));
        return out[0];
    }

    private void click(String drone, String startsWith) throws Exception {
        SwingUtilities.invokeAndWait(() -> ops.items(drone).stream().filter(i -> i.getText().startsWith(startsWith)).findFirst()
                .orElseThrow(() -> new AssertionError("no '" + startsWith + "' for " + drone + ": "
                        + ops.items(drone).stream().map(JMenuItem::getText).toList())).doClick());
    }

    private String state(String drone) throws Exception {
        String[] s = new String[1];
        SwingUtilities.invokeAndWait(() -> s[0] = ops.stateOf(drone));
        return s[0];
    }

    /** Down where it is (LAND, quickly), then disarmed: as a drone that came down and was switched off. */
    private void land(VirtualFleetPhone p) throws Exception {
        synchronized (p.copter) {
            p.copter.descent = 15;
            p.copter.landSpeed = 15;
            p.copter.apply(de.droiddrone.common.group.FleetIntent.DOWN, LAT, LON);
        }
        long end = System.currentTimeMillis() + 20_000;
        while (!p.copter.landed && System.currentTimeMillis() < end) Thread.sleep(100);
        assertTrue(p.copter.landed, p.copter.id + " down");
        p.landed();
    }

    /** The lead flown by the pilot (holding east of the group) and two followers, formed with the menu. */
    private void group() throws Exception {
        VirtualFleetPhone lead = phone("Lead", 200, 0, 90);
        lead.copter.pilot(200, 0, 90);
        phone("F1", -30, -30, 60);
        phone("F2", 30, 30, 70);
    }

    private void form(String... followers) throws Exception {
        for (String f : followers) click(f, "FOLLOW GROUP: add");
        click(followers[0], "FOLLOW GROUP: start");
        until(() -> ops.picture() != null, 10_000, "the group formed");
    }

    private void mark(String drone) throws Exception {
        click(drone, "SAFE TO AUTOLAUNCH: mark");
        until(() -> ops.isMarked(drone), 2000, drone + " marked");
        Thread.sleep(1500); // its report, with where it stands
        tick();
    }

    /**
     * A reserve that once settled in a group, landed later and was marked and called again: it joins the new group.
     * Before, it stayed "settled" for good on this PC and never joined any group again - it climbed and hung in its
     * approach layer.
     */
    @Test
    void aReserveCalledASecondTimeJoinsAgain() throws Exception {
        group();
        VirtualFleetPhone r = onTheGround("R1", -150, 60);
        ops = ops();
        form("F1", "F2");
        mark("R1");
        SwingUtilities.invokeAndWait(ops::callReserve);
        until(() -> {
            try {
                String s = state("R1");
                return s != null && s.startsWith("IN GROUP");
            } catch (Exception e) {
                return false;
            }
        }, 150_000, "R1 in its cell");
        tick();
        SwingUtilities.invokeAndWait(() -> ops.refreshOsd(null, MonoClock.ms() + 10_000));
        assertNull(ops.osd().reserves(), "its call is over: no reserve list on the OSD: " + ops.osd().reserves());
        SwingUtilities.invokeAndWait(ops::end);
        Thread.sleep(500);
        land(r); // it came down again, somewhere
        Thread.sleep(1500);
        form("F1", "F2");
        mark("R1");
        SwingUtilities.invokeAndWait(ops::callReserve);
        until(() -> r.isFollower() && ops.memberStateForTests("R1") != null, 60_000, "R1 joins the new group");
        assertEquals(2, r.arms.size(), "armed twice, each on a call: " + r.arms);
    }

    /**
     * A drone dispatched, back on the ground and marked again is offered to DISPATCH (and CALL RESERVE) again. Before,
     * it stayed "dispatched": DISPATCH left it out and so did CALL RESERVE ("none marked").
     */
    @Test
    void aDroneDispatchedOnceCanBeDispatchedAgain() throws Exception {
        VirtualFleetPhone d = onTheGround("D1", 0, 0);
        d.copter.guidedSpeed = 20;
        d.copter.maxSpeed = 20;
        flown = null;
        ops = ops();
        mark("D1");
        double[] target = de.droiddrone.common.group.GeoLocal.offset(LAT, LON, 0, 200);
        SwingUtilities.invokeAndWait(() -> ops.dispatch(target[0], target[1]));
        until(d::routeDone, 90_000, "D1 at its slot");
        land(d);
        Thread.sleep(1500); // its next report says so
        mark("D1");
        List<JMenuItem>[] place = new List[1];
        SwingUtilities.invokeAndWait(() -> place[0] = ops.placeItems(target[0], target[1]));
        assertEquals(1, place[0].size(), "DISPATCH offered again: " + log);
        assertTrue(place[0].get(0).getText().startsWith("DISPATCH 1 marked drone"), place[0].get(0).getText());
        assertEquals("RESERVE", state("D1"), "a reserve again, not an old dispatch's word");
    }

    /** AL-5: the last call is cancelled from the lead's menu before its drone arms; the drone stays marked and callable. */
    @Test
    void theLastReserveCallIsCancelledBeforeItArms() throws Exception {
        group();
        VirtualFleetPhone r = onTheGround("R1", -150, 60);
        ops = ops();
        form("F1", "F2");
        mark("R1");
        assertTrue(items("Lead").stream().noneMatch(i -> i.getText().startsWith("CALL RESERVE: cancel")), "nothing to cancel yet");
        r.setLink(false); // the call doesn't reach it yet
        SwingUtilities.invokeAndWait(ops::callReserve);
        tick();
        click("Lead", "CALL RESERVE: cancel the last");
        assertTrue(log.stream().anyMatch(l -> l.contains("CANCELLED R1")), log.toString());
        r.setLink(true);
        for (int i = 0; i < 12; i++) {
            tick();
            Thread.sleep(250);
        }
        assertEquals(List.of(), r.arms, "never armed");
        assertTrue(ops.isMarked("R1"), "still marked");
        SwingUtilities.invokeAndWait(ops::callReserve);
        assertTrue(log.stream().noneMatch(l -> l.contains("NO RESERVE")), "callable again: " + log);
        until(() -> r.launchState() == LaunchSequence.CLIMBING || !r.arms.isEmpty(), 20_000, "R1 launched on the new call");
    }

    /**
     * AL-10: the group lost while a reserve climbs and the lead now not reachable for a moment: it joins as soon as it
     * is. Before, the failed start was never answered and the reserve stayed "forming" for good - it never joined.
     */
    @Test
    void aReserveWhoseNewLeadIsUnreachableForAMomentJoinsLater() throws Exception {
        group();
        VirtualFleetPhone r = onTheGround("R1", -150, 60);
        ops = ops();
        form("F1", "F2");
        mark("R1");
        SwingUtilities.invokeAndWait(ops::callReserve);
        until(() -> r.launchState() == LaunchSequence.CLIMBING, 30_000, "R1 climbing");
        SwingUtilities.invokeAndWait(ops::end);
        noAddress.add("F1");
        flown = "F1";
        for (int i = 0; i < 40; i++) { // past the floor, it tries to form round F1 - not reachable
            tick();
            Thread.sleep(250);
        }
        noAddress.remove("F1");
        until(() -> r.isFollower() && ops.picture() != null && "F1".equals(ops.picture().lead()), 30_000, "R1 follows F1");
    }

    /**
     * FA20 / AC-5: two presses, two reserves climbing at once - each in an approach layer of its own. Before, both were
     * given the group's next approach layer: the second press was refused (NO LAYER FREE) while the first was on its way.
     */
    @Test
    void twoPressesCallTwoReservesEachInItsOwnLayer() throws Exception {
        group();
        VirtualFleetPhone r1 = onTheGround("R1", -150, 60);
        VirtualFleetPhone r2 = onTheGround("R2", -150, -60);
        ops = ops();
        form("F1", "F2");
        mark("R1");
        mark("R2");
        SwingUtilities.invokeAndWait(ops::callReserve);
        SwingUtilities.invokeAndWait(ops::callReserve);
        assertTrue(log.stream().anyMatch(l -> l.contains("CALLED R1")) && log.stream().anyMatch(l -> l.contains("CALLED R2")),
                "both called: " + log);
        double[] top = {Double.NaN, Double.NaN};
        until(() -> {
            if (Double.isNaN(top[0]) && r1.launchState() == LaunchSequence.ON_THE_WAY) top[0] = r1.copter.u;
            if (Double.isNaN(top[1]) && r2.launchState() == LaunchSequence.ON_THE_WAY) top[1] = r2.copter.u;
            return !Double.isNaN(top[0]) && !Double.isNaN(top[1]);
        }, 90_000, "both at the top of their climbs");
        assertTrue(Math.abs(top[0] - top[1]) >= 9, "each its own approach layer: " + top[0] + " / " + top[1]);
        until(() -> r1.isFollower() && r2.isFollower(), 90_000, "both join");
    }

    /**
     * AC-13: no reserve is called into an approach layer over the ceiling - NO LAYER FREE. Before, CALL RESERVE never
     * looked at the ceiling.
     */
    @Test
    void noReserveAboveTheCeiling() throws Exception {
        group();
        VirtualFleetPhone r = onTheGround("R1", -150, 60);
        ops = ops();
        SwingUtilities.invokeAndWait(() -> ops.setDesignForTests(de.droiddrone.pc.air.GroupDesign.with(
                de.droiddrone.pc.air.AirParams.defaults(), 20, 10, 20, 120, 100, 60, 75)));
        form("F1", "F2"); // the cube's one layer at 70 m, under a 75 m ceiling; a reserve's approach layer at 90 m
        mark("R1");
        SwingUtilities.invokeAndWait(ops::callReserve);
        assertTrue(log.stream().anyMatch(l -> l.contains("NO LAYER FREE")), log.toString());
        for (int i = 0; i < 8; i++) {
            tick();
            Thread.sleep(250);
        }
        assertEquals(List.of(), r.arms, "never armed");
    }

    /** FA1: a follower in the air (its report says armed and flying) is not marked SAFE TO AUTOLAUNCH. */
    @Test
    void aFlyingFollowerIsNotMarked() throws Exception {
        group();
        ops = ops();
        form("F1", "F2");
        click("F1", "SAFE TO AUTOLAUNCH: mark");
        assertFalse(ops.isMarked("F1"), "not marked: " + log);
        assertTrue(log.stream().anyMatch(l -> l.contains("Not known landed")), log.toString());
        assertTrue(items("Lead").stream().anyMatch(i -> i.getText().equals("CALL RESERVE (none marked)")),
                "and no reserve: " + items("Lead").stream().map(JMenuItem::getText).toList());
    }

    /**
     * AC-8: TRAFFIC on the OSD only for drones heard lately (or a cut-off follower where its plan has it). Before, a
     * drone gone quiet stayed TRAFFIC where it was last heard, for good.
     */
    @Test
    void trafficForgetsADroneGoneQuiet() throws Exception {
        try (DatagramSocket a = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket b = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             GroupLink l = new GroupLink("DD", new GroupLink.Listener() {
                 public void event(String text) {
                 }

                 public void newLead(String drone) {
                 }
             })) {
            l.phone("Me", new InetSocketAddress(InetAddress.getLoopbackAddress(), a.getLocalPort()));
            l.phone("Other", new InetSocketAddress(InetAddress.getLoopbackAddress(), b.getLocalPort()));
            l.track(List.of("Me", "Other"));
            InetSocketAddress pc = learn(a);
            learn(b);
            double[] there = de.droiddrone.common.group.GeoLocal.offset(LAT, LON, 12, 0);
            report(a, pc, LAT, LON, 100);
            report(b, pc, there[0], there[1], 103);
            long until = System.currentTimeMillis() + 3000;
            while ((l.status("Me") == null || l.status("Other") == null) && System.currentTimeMillis() < until) Thread.sleep(20);
            long now = MonoClock.ms();
            String t = FleetOps.traffic(l, List.of("Other"), "Me", l.status("Me"), now);
            assertNotNull(t, "a drone 12 m off is TRAFFIC");
            assertTrue(t.startsWith("TRAFFIC 12 M E"), t);
            long quietUntil = System.currentTimeMillis() + GroupLink.TRAFFIC_STALE_MS + 1500;
            while (System.currentTimeMillis() < quietUntil) { // only this one goes on reporting
                report(a, pc, LAT, LON, 100);
                Thread.sleep(500);
            }
            assertNull(FleetOps.traffic(l, List.of("Other"), "Me", l.status("Me"), MonoClock.ms()), "not heard for long: no TRAFFIC");
        }
    }

    private static InetSocketAddress learn(DatagramSocket s) throws Exception {
        byte[] buf = new byte[512];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        s.setSoTimeout(3000);
        s.receive(p);
        return new InetSocketAddress(p.getAddress(), p.getPort());
    }

    private static void report(DatagramSocket s, InetSocketAddress to, double lat, double lon, double alt) throws Exception {
        FleetStatus st = new FleetStatus();
        st.lat = lat;
        st.lon = lon;
        st.altAmsl = alt;
        st.battery = 80;
        st.flags = FleetStatus.ARMED | FleetStatus.GNSS;
        byte[] d = st.encode();
        s.send(new DatagramPacket(d, d.length, to.getAddress(), to.getPort()));
    }
}
