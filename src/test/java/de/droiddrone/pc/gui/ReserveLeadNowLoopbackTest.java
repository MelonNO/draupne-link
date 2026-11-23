package de.droiddrone.pc.gui;

import de.droiddrone.common.launch.LaunchSequence;
import de.droiddrone.pc.air.virt.VirtualFleetPhone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD AL-10 and AL-11 on loopback UDP, as {@link AutolaunchLoopbackTest}: a reserve called and climbing when its
 * group is lost joins whoever is the lead now (the drone the pilot flies by then); a drone without a 3D fix is not
 * marked, and SAFE TO AUTOLAUNCH is greyed out for it. Kinematic copters stand in for ArduPilot; names made up.
 */
class ReserveLeadNowLoopbackTest {
    static final double LAT = 60.0, LON = 10.0, GROUND = 0;
    private final Map<String, VirtualFleetPhone> phones = new HashMap<>();
    private final List<String> log = new CopyOnWriteArrayList<>();
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

    private FleetOps ops() {
        return new FleetOps(new FleetOps.Host() {
            public String flownPhone() {
                return flown;
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
                return 0;
            }

            public de.droiddrone.pc.route.TerrainSource terrain() { // RT-9: a reserve's way home over flat ground
                return (la, lo) -> GROUND;
            }

            public boolean confirm(String question) {
                return true;
            }
        });
    }

    /** The window's once-a-second tick, on Swing's thread. */
    private void tick() {
        try {
            javax.swing.SwingUtilities.invokeAndWait(ops::tickReserves);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JMenuItem item(String drone, String startsWith) {
        return ops.items(drone).stream().filter(i -> i.getText().startsWith(startsWith)).findFirst().orElseThrow();
    }

    @Test
    void aReserveWhoseGroupIsLostOnTheWayJoinsTheLeadNow() throws Exception {
        VirtualFleetPhone lead = phone("Lead", 200, 0, 90);
        lead.copter.pilot(200, 0, 90);
        phone("F1", -30, -30, 60);
        phone("F2", 30, 30, 70);
        VirtualFleetPhone reserve = phone("R1", -150, 60, 0);
        reserve.landed();
        ops = ops();
        item("F1", "FOLLOW GROUP: add").doClick();
        item("F2", "FOLLOW GROUP: add").doClick();
        item("F2", "FOLLOW GROUP: start").doClick();
        until(() -> ops.picture() != null, 8000, "the group formed " + log);

        item("R1", "SAFE TO AUTOLAUNCH: mark").doClick();
        until(() -> ops.isMarked("R1"), 2000, "marked");
        Thread.sleep(1500);
        assertTrue(item("Lead", "CALL RESERVE").isEnabled(), "CALL RESERVE offered: R1 has a fix " + log);
        javax.swing.SwingUtilities.invokeAndWait(ops::callReserve);
        for (int i = 0; i < 300 && reserve.launchState() != LaunchSequence.CLIMBING; i++) {
            javax.swing.SwingUtilities.invokeAndWait(ops::tickReserves);
            Thread.sleep(100);
        }
        assertEquals(LaunchSequence.CLIMBING, reserve.launchState(), "climbing " + log);

        // on the way: the group is lost (ended), and the pilot flies F1 now
        javax.swing.SwingUtilities.invokeAndWait(ops::end);
        flown = "F1";
        assertNull(ops.picture(), "no group");
        until(() -> {
            tick();
            return reserve.isFollower();
        }, 30_000, "R1 follows the lead now " + log);
        until(() -> ops.picture() != null && "F1".equals(ops.picture().lead()), 5000, "a group round F1 " + log);
        assertTrue(log.stream().anyMatch(l -> l.contains("R1: group lost - joins F1")), log.toString());
        assertEquals(List.of("R1 marked"), reserve.arms, "armed once, on its mark");
    }

    @Test
    void withoutA3dFixSafeToAutolaunchIsGreyedOut() throws Exception {
        VirtualFleetPhone lead = phone("Lead", 200, 0, 90);
        lead.copter.pilot(200, 0, 90);
        VirtualFleetPhone r = phone("R2", -150, 100, 0);
        r.landed();
        r.copter.gnssOk = false;
        ops = ops();
        // its fix not known yet: offered; its phone refuses the mark, its first report says why
        item("R2", "SAFE TO AUTOLAUNCH: mark").doClick();
        until(() -> {
            tick();
            return !ops.isMarked("R2");
        }, 5000, "the refused mark dropped " + log);
        assertTrue(log.stream().anyMatch(l -> l.contains("R2 has no GPS fix")), log.toString());
        JMenuItem m = item("R2", "SAFE TO AUTOLAUNCH: mark");
        assertFalse(m.isEnabled(), "greyed out");
        assertTrue(m.getText().endsWith("(no GPS fix)"), m.getText());
        assertEquals(List.of(), r.arms);
    }
}
