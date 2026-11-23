package de.droiddrone.pc.air;

import de.droiddrone.common.FleetStatus;
import de.droiddrone.common.group.FollowerBrain;
import de.droiddrone.pc.air.virt.VirtualFleetPhone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD FD-1 / FL-1 / AC-37 on the wire: the PC's GroupLink and virtual phones (the phone's fleet role with a
 * kinematic copter) over real UDP on loopback - FOLLOW GROUP formed from where the drones are, every follower taking
 * its orders, separation kept all the while, and one follower cut off holding by itself while the rest go on. Places
 * and names are made up; real time, about 15 s.
 */
class GroupLinkLoopbackTest {
    static final double LAT = 60.0, LON = 10.0, GROUND = 0;
    private final List<VirtualFleetPhone> phones = new ArrayList<>();
    private GroupLink link;

    @AfterEach
    void close() {
        if (link != null) link.close();
        for (VirtualFleetPhone p : phones) p.close();
    }

    private VirtualFleetPhone phone(String id, double e, double n, double alt) throws Exception {
        VirtualFleetPhone p = new VirtualFleetPhone(id, LAT, LON, GROUND, e, n, alt, "DD");
        phones.add(p);
        link.phone(id, p.address());
        return p;
    }

    /** Formed, flown, apart: in the normal suite. */
    @Test
    void aGroupIsFormedAndFlownApart() throws Exception {
        flown(false);
    }

    /**
     * One follower cut off for 4 s: the pairs came to 9.4 m sideways and 8.9 m in height (2026-11-23) - the give-way of
     * single drones is not built yet (the airspace work's open point). A finding: fails until fixed (gradlew benchFindings).
     */
    @Test
    @org.junit.jupiter.api.Tag("finding")
    void aFollowerCutOffKeepsApart() throws Exception {
        flown(true);
    }

    private void flown(boolean cutOne) throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        link = new GroupLink("DD", new GroupLink.Listener() {
            public void event(String text) {
                events.add(text);
            }

            public void newLead(String drone) {
                events.add("NEW LEAD " + drone);
            }
        });
        VirtualFleetPhone lead = phone("Lead", 150, 0, 80);
        lead.copter.pilot(150, 0, 80);
        List<VirtualFleetPhone> followers = new ArrayList<>();
        String[] names = {"F1", "F2", "F3", "F4"};
        double[][] at = {{-30, -30, 60}, {30, -30, 70}, {-30, 30, 80}, {30, 30, 90}};
        for (int i = 0; i < names.length; i++) followers.add(phone(names[i], at[i][0], at[i][1], at[i][2]));
        List<String> all = new ArrayList<>(List.of(names));
        all.add("Lead");
        link.track(all);
        long until = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < until && all.stream().anyMatch(d -> link.status(d) == null)) Thread.sleep(50);
        for (String d : all) assertNotNull(link.status(d), d + " reported");

        assertNull(link.form("Lead", List.of(names), GROUND), "formed");
        double closest = Double.MAX_VALUE;
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            closest = Math.min(closest, closestBreach(phones));
            Thread.sleep(100);
        }
        for (VirtualFleetPhone f : followers) assertTrue(f.isFollower(), "a follower now: " + f.copter.id);
        assertTrue(closest > 0, "no pair inside 10 m sideways and 10 m in height: " + closest);
        assertTrue(events.stream().anyMatch(e -> e.startsWith("FOLLOW GROUP")), events.toString());
        if (!cutOne) return;

        // F2 cut off: it holds by itself (its brain's HOLD), the others carry on following
        VirtualFleetPhone cut = followers.get(1);
        cut.setLink(false);
        end = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < end) {
            closest = Math.min(closest, closestBreach(phones));
            Thread.sleep(100);
        }
        cut.setLink(true);
        Thread.sleep(1500);
        FleetStatus s = link.status("F2");
        assertNotNull(s);
        assertTrue(s.phase == FollowerBrain.HOLD || s.phase == FollowerBrain.NORMAL, "held, then back with the group: phase " + s.phase);
        assertTrue(closest > 0, "separation kept through the cut: " + closest);
        assertTrue(events.stream().anyMatch(e -> e.startsWith("FOLLOW GROUP")), events.toString());
    }

    /**
     * The smallest margin of any pair against the separation rule: positive while every pair is at least 10 m apart
     * sideways or 10 m apart in height (less 0.5 m for the stacked columns, as the suite allows), negative inside both.
     */
    static double closestBreach(List<VirtualFleetPhone> ps) {
        double worst = Double.MAX_VALUE;
        for (int i = 0; i < ps.size(); i++) {
            for (int j = i + 1; j < ps.size(); j++) {
                var a = ps.get(i).copter;
                var b = ps.get(j).copter;
                double h = Math.hypot(a.e - b.e, a.n - b.n), v = Math.abs(a.u - b.u);
                double margin = Math.max(h - 10, v - 9.5);
                worst = Math.min(worst, margin);
            }
        }
        return worst;
    }
}
