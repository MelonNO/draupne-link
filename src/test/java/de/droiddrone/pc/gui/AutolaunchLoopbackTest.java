package de.droiddrone.pc.gui;

import de.droiddrone.common.launch.LaunchSequence;
import de.droiddrone.pc.air.virt.VirtualFleetPhone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD phase 5 in miniature, on loopback UDP: a follower group flying, a landed reserve marked SAFE TO AUTOLAUNCH
 * through the right-click menu, CALL RESERVE - the reserve's phone runs the shared LaunchSequence (checks, 2 s warning,
 * arm, straight climb), passes the floor and joins the group as a follower; an unmarked drone on the ground beside it
 * never arms (AL-12). Kinematic copters stand in for ArduPilot. Names and places made up; real time, about 40 s.
 */
class AutolaunchLoopbackTest {
    static final double LAT = 60.0, LON = 10.0, GROUND = 0;
    private final Map<String, VirtualFleetPhone> phones = new HashMap<>();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private FleetOps ops;

    @AfterEach
    void close() {
        if (ops != null) ops.close();
        for (VirtualFleetPhone p : phones.values()) p.close();
    }

    private VirtualFleetPhone phone(String id, double e, double n, double alt) throws Exception {
        VirtualFleetPhone p = new VirtualFleetPhone(id, LAT, LON, GROUND, e, n, alt, "DD");
        p.copter.climb = 8; // a quicker climb: the test's time, not the rules', is shortened
        phones.put(id, p);
        return p;
    }

    private static void until(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (ok.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("waited " + ms + " ms for: " + what);
    }

    /** Climbing or past it on the way in - not ended. */
    private static boolean flying(VirtualFleetPhone p) {
        int s = p.launchState();
        return s >= LaunchSequence.CLIMBING && s <= LaunchSequence.IN_GROUP;
    }

    @Test
    void aMarkedReserveIsCalledLaunchesAndJoinsAndAnUnmarkedOneNeverArms() throws Exception {
        VirtualFleetPhone lead = phone("Lead", 200, 0, 90);
        lead.copter.pilot(200, 0, 90);
        phone("F1", -30, -30, 60);
        phone("F2", 30, 30, 70);
        VirtualFleetPhone reserve = phone("R1", -150, 60, 0);
        reserve.landed();
        VirtualFleetPhone bystander = phone("R2", -150, 100, 0);
        bystander.landed();
        ops = new FleetOps(new FleetOps.Host() {
            public String flownPhone() {
                return "Lead";
            }

            public InetSocketAddress addressOf(String phone) {
                VirtualFleetPhone p = phones.get(phone);
                return p == null ? null : p.address();
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

            public long checkInWaitMs(String phone) {
                return 0; // awake (listening)
            }

            public de.droiddrone.pc.route.TerrainSource terrain() { // RT-9: a reserve's way home over flat ground
                return (la, lo) -> GROUND;
            }

            public boolean confirm(String question) {
                return true;
            }
        });
        // FOLLOW GROUP: F1, F2 follow the lead
        ops.items("F1").get(1).doClick();
        ops.items("F2").get(1).doClick();
        List<String> start = new ArrayList<>();
        for (var i : ops.items("F2")) start.add(i.getText());
        ops.items("F2").stream().filter(i -> i.getText().startsWith("FOLLOW GROUP: start")).findFirst().orElseThrow().doClick();
        until(() -> ops.picture() != null, 8000, "the group formed " + log);

        // SAFE TO AUTOLAUNCH on R1 (its phone marks where it stands), then CALL RESERVE
        ops.items("R1").stream().filter(i -> i.getText().startsWith("SAFE TO AUTOLAUNCH: mark")).findFirst().orElseThrow().doClick();
        until(() -> ops.isMarked("R1"), 2000, "marked");
        Thread.sleep(1500); // its report with where it is
        javax.swing.SwingUtilities.invokeAndWait(ops::callReserve);
        for (int i = 0; i < 300 && !flying(reserve); i++) {
            javax.swing.SwingUtilities.invokeAndWait(ops::tickReserves);
            Thread.sleep(100);
        }
        assertTrue(flying(reserve), "climbing: state " + LaunchSequence.stateWord(reserve.launchState()) + " (" + reserve.launchReason() + ") " + log);
        until(() -> reserve.copter.u > GROUND + 45, 20_000, "past the floor (" + reserve.copter.u + " m)");
        until(reserve::isFollower, 15_000, "joined the group as a follower " + log);
        assertEquals(List.of("R1 marked"), reserve.arms, "armed once, marked");
        assertTrue(bystander.arms.isEmpty() && !bystander.armed(), "the unmarked drone never armed");
        assertTrue(log.stream().anyMatch(l -> l.contains("R1 joins the group")), log.toString());
    }
}
