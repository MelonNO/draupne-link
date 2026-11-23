package de.droiddrone.pc.air;

import de.droiddrone.pc.ArmSwitch;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.Joystick;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.RcMapper;
import de.droiddrone.pc.RcSession;
import de.droiddrone.pc.air.virt.VirtualFleetPhone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD FL-6 and the NFR on hand-overs: DISCARD LEAD puts the pilot's sticks on the new lead within 500 ms. End to
 * end on loopback: the PC's GroupLink, ControlLink and RcSession (the stick gate) against virtual fleet phones that
 * keep the phone's session rules (SessionGate and the hand-over's FleetCommandGate.leadConnect) and send ArduPilot's
 * armed word once a second, as its heartbeat comes, over a mobile network's round trip ({@link #ROUND_TRIP_MS}). Timed
 * from DISCARD LEAD to the first stick frame the new lead's phone takes. And a stranger PC with the key gets no drone
 * of the group, in the hand-over's moment either.
 */
class LeadHandoverLoopbackTest {
    static final double LAT = 60.0, LON = 10.0, GROUND = 0;
    static final long HANDOVER_MAX_MS = 500;
    /** The pilot sessions' round trip, a mobile network's on a good day: the virtual phones hold each packet that long. */
    static final int ROUND_TRIP_MS = 120;
    private static final InetAddress LOOP = InetAddress.getLoopbackAddress();

    private final PcConfig config;
    private final Map<String, VirtualFleetPhone> phones = new LinkedHashMap<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final RcMapper mapper = new RcMapper(new String[]{"axis0", "axis1", "axis2", "axis3", "axis4"});
    /** ARM on AUX1 (the frame's 5th channel), as Drone setup puts it on an ArduPilot drone. */
    private final ArmSwitch arm = new ArmSwitch(4, List.of(new int[]{1700, 2100}));
    private GroupLink group;
    private int pcPort;
    private final List<ControlLink> links = new ArrayList<>();
    private volatile RcSession flying;
    private ControlLink current;
    private volatile boolean sticksOn = true;
    private Thread sticks;

    LeadHandoverLoopbackTest() throws java.io.IOException {
        config = PcConfig.defaults();
    }

    @AfterEach
    void close() {
        sticksOn = false;
        if (sticks != null) sticks.interrupt();
        sticks = null;
        flying = null;
        current = null;
        for (ControlLink l : links) l.close();
        links.clear();
        if (group != null) group.close();
        group = null;
        for (VirtualFleetPhone p : phones.values()) p.close();
        phones.clear();
    }

    /** DISCARD LEAD with the hand-over: the new lead has the sticks within 500 ms, three groups running. */
    @Test
    void discardLeadPutsTheSticksOnTheNewLeadWithin500ms() throws Exception {
        List<Long> took = handOvers(true, 3, false);
        for (long t : took) assertTrue(t < HANDOVER_MAX_MS, "the new lead's sticks within " + HANDOVER_MAX_MS + " ms: " + took + "\n" + events);
    }

    /**
     * The followers were flown last by another PC (reserves set up on the ground crew's PC, then commanded from this
     * one): the session rules alone refuse this PC an armed drone; the hand-over's rule lets the group's own PC in.
     */
    @Test
    void theGroupsOwnPcTakesAFollowerAnotherPcFlewLast() throws Exception {
        List<Long> took = handOvers(true, 1, true);
        assertTrue(took.get(0) < HANDOVER_MAX_MS, "within " + HANDOVER_MAX_MS + " ms: " + took + "\n" + events);
    }

    /** For the record (perf): the hand-over as it was - a new session that waits for ArduPilot's armed word twice. */
    @Tag("perf")
    @Test
    void theColdHandOverForTheRecord() throws Exception {
        List<Long> cold = handOvers(false, 5, false);
        List<Long> fast = handOvers(true, 5, false);
        System.out.println("Hand-over, cold (a new session as before): " + cold + " ms; with the hand-over: " + fast + " ms");
    }

    /** A stranger PC with the key: no follower's session, no lead order, not in the hand-over's moment either. */
    @Test
    void aStrangerPcCantTakeADroneOfTheGroup() throws Exception {
        formGroup(false);
        InetAddress strangerAt = InetAddress.getByName("127.0.0.2");
        // its own fleet commands: LEAD to every follower (an armed drone with a commander heard: refused)
        try (GroupLink stranger = new GroupLink(config.getKey(), quiet(), new DatagramSocket(0, strangerAt))) {
            for (Map.Entry<String, VirtualFleetPhone> p : phones.entrySet()) {
                if (p.getKey().equals("Lead")) continue;
                stranger.phone(p.getKey(), p.getValue().address());
                stranger.command(p.getKey(), de.droiddrone.common.FleetCmd.lead(true, 0));
            }
            Thread.sleep(200);
            // the pilot's DISCARD LEAD: the new lead's order is this PC's - the stranger connects first, and is refused
            String next = group.discardLead(System.currentTimeMillis());
            assertNotNull(next, "a new lead from the bottom row");
            VirtualFleetPhone target = phones.get(next);
            ControlLink s = new ControlLink(config, LOOP, target.address().getPort(), 0);
            s.setLocalAddress(strangerAt);
            links.add(s);
            s.start();
            long until = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < until) {
                s.sendRc(new short[]{1500, 1500, 1500, 1500, 1900, 1500, 1000, 1000});
                Thread.sleep(20);
            }
            assertEquals(0, target.sessions(), "the stranger got no session on " + next);
            assertEquals(0, target.sticksTaken(), "no stick frame of the stranger's taken");
            assertNotEquals(ControlLink.State.READY, s.getState());
            for (Map.Entry<String, VirtualFleetPhone> p : phones.entrySet()) {
                if (!p.getKey().equals("Lead") && !p.getKey().equals(next)) assertEquals(0, p.getValue().sessions(), p.getKey());
            }
            s.close();
            // this PC, the group's: in at once
            long t0 = System.currentTimeMillis();
            handOver(next, true);
            assertTrue(waitSticks(target, 3000), "the group's PC has the new lead's sticks\n" + events);
            assertTrue(target.firstStickAt() - t0 < HANDOVER_MAX_MS, "within " + HANDOVER_MAX_MS + " ms: " + (target.firstStickAt() - t0));
        }
    }

    /**
     * After DISCARD LEAD the pilot flies the new lead down and out: the group lets it go (it leaves the group), and a
     * second DISCARD LEAD later finds the next one. Before, the new lead's reports went to the group as the lead's while
     * the group still had it leaving: it never left, the group stood still for good and every later DISCARD LEAD (and the
     * silent lead's take-over) found nobody.
     */
    @Test
    void afterDiscardLeadTheNewLeadLeavesAndTheNextDiscardWorks() throws Exception {
        formGroup(false);
        Thread.sleep(1500);
        String next = group.discardLead(System.currentTimeMillis());
        assertNotNull(next, "a new lead\n" + events);
        handOver(next, true);
        VirtualFleetPhone p = phones.get(next);
        assertTrue(waitSticks(p, 5000), "sticks\n" + events);
        GroupLink.Picture pic = group.picture();
        double[] c = {de.droiddrone.common.group.GeoLocal.east(LAT, LON, pic.lat(), pic.lon()),
                de.droiddrone.common.group.GeoLocal.north(LAT, LON, pic.lat(), pic.lon())};
        double outE, outN;
        synchronized (p.copter) {
            outE = p.copter.e - c[0];
            outN = p.copter.n - c[1];
            p.copter.pilot(p.copter.e, p.copter.n, GROUND + 40); // down into the abort layer
        }
        Thread.sleep(6000);
        double len = Math.hypot(outE, outN);
        synchronized (p.copter) {
            p.copter.pilot(c[0] + outE / len * 140, c[1] + outN / len * 140, GROUND + 40); // and out past the keep-out zone
        }
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until && group.memberState(next) != null) Thread.sleep(100);
        assertNull(group.memberState(next), next + " has left the group: " + group.memberState(next) + "\n" + events);
        Thread.sleep(1000);
        String again = group.discardLead(System.currentTimeMillis());
        assertNotNull(again, "the next DISCARD LEAD finds a new lead\n" + events);
        assertNotEquals(next, again);
    }

    /** The window's hand-overs (listener.newLead): "drone@wall clock ms". */
    private final List<String> handedTo = new CopyOnWriteArrayList<>();

    /**
     * FL-5: the lead silent 30 s - the follower DISCARD LEAD would pick becomes the lead at once and the pilot gets its
     * sticks, as with DISCARD LEAD. Before, the pilot got them only once that follower had flown the whole abort exit
     * by itself, a quarter of a minute later; and the window was told only once.
     */
    @Test
    void aSilentLeadIsTakenOverAtOnce() throws Exception {
        formGroup(false);
        Thread.sleep(1000);
        long cut = System.currentTimeMillis();
        phones.get("Lead").setLink(false);
        long until = cut + 45_000;
        while (System.currentTimeMillis() < until && handedTo.isEmpty()) Thread.sleep(100);
        assertEquals(1, handedTo.size(), "one hand-over\n" + events);
        long at = Long.parseLong(handedTo.get(0).split("@")[1]);
        long silent = AirParams.defaults().leadSilentMs();
        assertTrue(at - cut < silent + 3000, "the pilot has the new lead " + (at - cut) + " ms after the cut\n" + events);
        String next = handedTo.get(0).split("@")[0];
        assertEquals(next, group.lead());
        Thread.sleep(20_000); // it leaves; nobody is handed over twice
        assertEquals(1, handedTo.size(), "still one hand-over: " + handedTo + "\n" + events);
    }

    // ------------------------------------------------------------------ the flight

    /** times groups formed and flown, each with one DISCARD LEAD: how long each took to the new lead's first stick frame. */
    private List<Long> handOvers(boolean fast, int times, boolean otherPcFlewThem) throws Exception {
        List<Long> took = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            if (i > 0) close();
            sticksOn = true;
            formGroup(otherPcFlewThem);
            Thread.sleep(600 + 197L * i); // the group flies; the hand-over falls on another point of the heartbeat each time
            long t0 = System.currentTimeMillis();
            String next = group.discardLead(t0);
            assertNotNull(next, "a new lead from the bottom row\n" + events);
            handOver(next, fast);
            VirtualFleetPhone p = phones.get(next);
            assertTrue(waitSticks(p, 5000), next + " takes the sticks\n" + events);
            took.add(p.firstStickAt() - t0);
        }
        return took;
    }

    private void formGroup(boolean otherPcFlewThem) throws Exception {
        try (DatagramSocket s = new DatagramSocket(0)) {
            pcPort = s.getLocalPort(); // the PC's link port, the same for every link (as config.getPort() in the app)
        }
        group = new GroupLink(config.getKey(), new GroupLink.Listener() {
            public void event(String text) {
                events.add(text);
            }

            public void newLead(String drone) {
                events.add("NEW LEAD " + drone);
                handedTo.add(drone + "@" + System.currentTimeMillis());
            }
        });
        VirtualFleetPhone lead = phone("Lead", 150, 0, 80);
        lead.copter.pilot(150, 0, 80);
        String[] names = {"F1", "F2", "F3", "F4"};
        double[][] at = {{-30, -30, 60}, {30, -30, 70}, {-30, 30, 80}, {30, 30, 90}};
        InetSocketAddress pc = new InetSocketAddress(LOOP, pcPort);
        for (int i = 0; i < names.length; i++) {
            VirtualFleetPhone f = phone(names[i], at[i][0], at[i][1], at[i][2]);
            f.flownBy(pc); // this PC flew it up and commands it
            if (otherPcFlewThem) f.lastPilot(new InetSocketAddress(InetAddress.getByName("127.0.0.3"), pcPort));
        }
        startSticks();
        handOver("Lead", false); // the first lead: an ordinary connect
        assertTrue(waitSticks(lead, 5000), "the lead flown\n" + events);
        List<String> all = new ArrayList<>(phones.keySet());
        group.track(all);
        long until = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < until && all.stream().anyMatch(d -> group.status(d) == null)) Thread.sleep(50);
        for (String d : all) assertNotNull(group.status(d), d + " reported");
        assertNull(group.form("Lead", List.of(names), GROUND), "formed");
        until = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < until && !followersAll(names)) Thread.sleep(50);
        assertTrue(followersAll(names), "every one a follower");
    }

    private boolean followersAll(String[] names) {
        for (String n : names) if (!phones.get(n).isFollower()) return false;
        return true;
    }

    /**
     * What the window does on a new lead (MainWindow.handOver): the link to the old lead ends, one to the new lead opens
     * on the same port, and its stick gate takes the group's word that it is armed and flying (fast only).
     */
    private void handOver(String drone, boolean fast) throws Exception {
        RcSession was = flying;
        flying = null;
        if (current != null) current.close();
        ControlLink l = new ControlLink(config, LOOP, phones.get(drone).address().getPort(), pcPort);
        if (fast) l.handOver();
        current = l;
        links.add(l);
        l.start();
        RcSession s = new RcSession(l, mapper, events::add, arm);
        if (fast && group.reportsFlying(drone, System.currentTimeMillis())) s.handedOver();
        flying = s;
    }

    /** The pilot's radio, 250 frames a second: flying, ARM on, the throttle above the middle. */
    private void startSticks() {
        Joystick.State state = new Joystick.State(new float[]{0, 0, 0, 0.2f, 1f}, new boolean[0]);
        sticks = new Thread(() -> {
            while (sticksOn) {
                RcSession s = flying;
                if (s != null) s.onInput(state);
                try {
                    Thread.sleep(4);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "testRadio");
        sticks.setDaemon(true);
        sticks.start();
    }

    private static boolean waitSticks(VirtualFleetPhone p, long maxMs) throws InterruptedException {
        long until = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < until) {
            if (p.firstStickAt() != 0) return true;
            Thread.sleep(2);
        }
        return false;
    }

    private VirtualFleetPhone phone(String id, double e, double n, double alt) throws Exception {
        VirtualFleetPhone p = new VirtualFleetPhone(id, LAT, LON, GROUND, e, n, alt, config.getKey());
        p.setSessionDelayMs(ROUND_TRIP_MS);
        phones.put(id, p);
        group.phone(id, p.address());
        return p;
    }

    private GroupLink.Listener quiet() {
        return new GroupLink.Listener() {
            public void event(String text) {
            }

            public void newLead(String drone) {
            }
        };
    }
}
