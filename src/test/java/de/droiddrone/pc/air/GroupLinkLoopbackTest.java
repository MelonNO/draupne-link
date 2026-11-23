package de.droiddrone.pc.air;

import de.droiddrone.common.FleetStatus;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.common.group.Traffic;
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
     * One follower cut off for 4 s while the group flies. Before the single drones' give-way (Orca, 2026-11-23) the pairs
     * came to 9.4 m sideways and 8.9 m in height here; with it, 3 of 3 runs kept apart.
     */
    @Test
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
        GroupLink.Picture pic = link.picture(); // G4: what the map draws
        assertEquals(8, pic.cells().size(), "the ring round the empty core");
        assertEquals(4, pic.shafts().size());
        assertTrue(pic.keepOutHalfM() > pic.edgeHalfM() && pic.leashM() > pic.keepOutHalfM());
        assertEquals(4, pic.followers());
        assertEquals("Lead", pic.lead());
        double closest = Double.MAX_VALUE;
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            closest = Math.min(closest, closestBreach(phones));
            Thread.sleep(100);
        }
        for (VirtualFleetPhone f : followers) assertTrue(f.isFollower(), "a follower now: " + f.copter.id);
        assertTrue(closest > 0, "no pair inside 10 m sideways and 10 m in height: " + closest);
        assertTrue(events.stream().anyMatch(e -> e.startsWith("FOLLOW GROUP")), events.toString());
        assertTrue(events.stream().noneMatch(e -> e.startsWith("Group: ")), "the tick never throws: " + events);
        trafficToEveryDrone(lead, followers);
        if (!cutOne) return;

        // F2 cut off: it holds by itself (its brain's HOLD), the others carry on following
        VirtualFleetPhone cut = followers.get(1);
        cut.setLink(false);
        end = System.currentTimeMillis() + 4000;
        long f2 = Traffic.address("F2");
        boolean predicted = false, alwaysThere = true;
        long cutAt = System.currentTimeMillis();
        while (System.currentTimeMillis() < end) {
            closest = Math.min(closest, closestBreach(phones));
            Traffic t = lead.traffic;
            Traffic.Entry e = t == null ? null : t.entries.stream().filter(x -> x.address == f2).findFirst().orElse(null);
            if (System.currentTimeMillis() - cutAt > 1200) alwaysThere &= e != null; // the TRAFFIC sent once a second
            if (e != null && (e.flags & Traffic.PREDICTED) != 0) {
                predicted = true;
                double[] ll = cut.latLon();
                double h = Math.hypot(GeoLocal.east(ll[0], ll[1], e.lat, e.lon), GeoLocal.north(ll[0], ll[1], e.lat, e.lon));
                assertTrue(h < 15, "AC-11: the cut-off one where its plan has it, " + h + " m off");
            }
            Thread.sleep(100);
        }
        assertTrue(alwaysThere, "AC-11: the cut-off follower stays on the lead's ADS-B");
        assertTrue(predicted, "AC-11: sent where its exit plan has it (PREDICTED) while unheard");
        cut.setLink(true);
        Thread.sleep(1500);
        FleetStatus s = link.status("F2");
        assertNotNull(s);
        assertTrue(s.phase == FollowerBrain.HOLD || s.phase == FollowerBrain.NORMAL, "held, then back with the group: phase " + s.phase);
        assertTrue(closest > 0, "separation kept through the cut: " + closest);
        assertTrue(events.stream().anyMatch(e -> e.startsWith("FOLLOW GROUP")), events.toString());
    }

    /**
     * FD-8 / AC-11: the lead and each follower get TRAFFIC about once a second - every other drone of the group, never
     * itself, each where its copter is (within what a second's flight and the report's rounding allow).
     */
    private void trafficToEveryDrone(VirtualFleetPhone lead, List<VirtualFleetPhone> followers) throws Exception {
        List<VirtualFleetPhone> all = new ArrayList<>(followers);
        all.add(lead);
        int[] before = all.stream().mapToInt(p -> p.trafficCount.get()).toArray();
        Thread.sleep(2600);
        for (int i = 0; i < all.size(); i++) {
            VirtualFleetPhone p = all.get(i);
            int got = p.trafficCount.get() - before[i];
            assertTrue(got >= 2 && got <= 4, p.copter.id + ": TRAFFIC once a second, " + got + " in 2.6 s");
            Traffic t = p.traffic;
            assertNotNull(t, p.copter.id);
            assertEquals(all.size() - 1, t.entries.size(), p.copter.id + ": every other drone");
            for (VirtualFleetPhone o : all) {
                long a = Traffic.address(o.copter.id);
                Traffic.Entry e = t.entries.stream().filter(x -> x.address == a).findFirst().orElse(null);
                if (o == p) {
                    assertNull(e, p.copter.id + " never gets itself");
                    continue;
                }
                assertNotNull(e, p.copter.id + " gets " + o.copter.id);
                double[] ll = o.latLon();
                double h = Math.hypot(GeoLocal.east(ll[0], ll[1], e.lat, e.lon), GeoLocal.north(ll[0], ll[1], e.lat, e.lon));
                assertTrue(h < 15, o.copter.id + " for " + p.copter.id + ": " + h + " m off");
                assertTrue(Math.abs(e.altAmsl - o.copter.u) < 8, o.copter.id + " height: " + e.altAmsl + " vs " + o.copter.u);
                assertEquals(0, e.flags & Traffic.PREDICTED, "heard, so not predicted");
            }
        }
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
