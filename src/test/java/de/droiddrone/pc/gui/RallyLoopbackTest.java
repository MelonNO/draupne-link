package de.droiddrone.pc.gui;

import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.pc.air.virt.VirtualFleetPhone;
import de.droiddrone.pc.launch.RallyPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD AC-10 on loopback UDP: drones marked SAFE TO AUTOLAUNCH get their own rally point as soon as their place is
 * known - long before a DISPATCH - each read back on its phone, never two within twice the protection radius; a drone
 * standing alone keeps its own spot. None leaves the ground without it, and an unchanged one is not sent again.
 */
class RallyLoopbackTest {
    static final double LAT = 60.0, LON = 10.0, GROUND = 0;
    private final Map<String, VirtualFleetPhone> phones = new LinkedHashMap<>();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private FleetOps ops;

    @AfterEach
    void close() {
        if (ops != null) ops.close();
        for (VirtualFleetPhone p : phones.values()) p.close();
    }

    private FleetOps ops() {
        return new FleetOps(new FleetOps.Host() {
            public String flownPhone() {
                return null;
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

            public boolean confirm(String question) {
                return true;
            }

            public de.droiddrone.pc.route.TerrainSource terrain() {
                return (lat, lon) -> GROUND;
            }
        });
    }

    private void tick(long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            javax.swing.SwingUtilities.invokeAndWait(ops::tickReserves);
            Thread.sleep(200);
        }
    }

    @Test
    void eachDroneSentOutHasItsOwnRallyPointReadBackBeforeItLeavesTheGround() throws Exception {
        // three standing 8 m apart (one site, crowded), one alone 200 m east
        double[][] at = {{0, 0}, {8, 0}, {16, 0}, {200, 0}};
        String[] names = {"D1", "D2", "D3", "D4"};
        for (int i = 0; i < names.length; i++) {
            VirtualFleetPhone p = new VirtualFleetPhone(names[i], LAT, LON, GROUND, at[i][0], at[i][1], 0, "DD");
            p.copter.climb = 8;
            p.copter.guidedSpeed = 20;
            p.copter.maxSpeed = 20;
            p.landed();
            phones.put(names[i], p);
        }
        ops = ops();
        for (String n : names) {
            javax.swing.SwingUtilities.invokeAndWait(() -> ops.items(n).stream().filter(i -> i.getText().startsWith("SAFE TO AUTOLAUNCH: mark"))
                    .findFirst().orElseThrow().doClick());
        }
        tick(3000); // their reports, with where they stand: the rally points go up now, before any call
        List<double[]> points = new ArrayList<>();
        for (String n : names) {
            VirtualFleetPhone p = phones.get(n);
            assertNotNull(p.rally(), n + " has its rally point on the FC before any DISPATCH: " + log);
            assertTrue(ops.rallyOnDrone(n), n + ": read back");
            double[] r = ops.rallyOf(n);
            assertEquals(r[0], p.rally().get(0).x / 1e7, 1e-6, "the point sent is the point given");
            points.add(r);
        }
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                double d = GeoLocal.distance(points.get(i)[0], points.get(i)[1], points.get(j)[0], points.get(j)[1]);
                assertTrue(d >= RallyPlan.MIN_APART_M, names[i] + " and " + names[j] + " " + Math.round(d) + " m apart");
            }
        }
        double[] alone = GeoLocal.offset(LAT, LON, 200, 0);
        assertEquals(0, GeoLocal.distance(alone[0], alone[1], points.get(3)[0], points.get(3)[1]), 1, "alone: its own spot");
        assertEquals(0, GeoLocal.distance(LAT, LON, points.get(0)[0], points.get(0)[1]), 1, "the first at a site: its own spot");

        // unchanged: not sent again (the phone has heard each block once, read it back, and that is all)
        Map<String, Integer> sent = new LinkedHashMap<>();
        for (String n : names) sent.put(n, (int) phones.get(n).rallyBlocks.stream().distinct().count());
        tick(2000);
        for (String n : names) {
            assertEquals(1, sent.get(n), n + ": one rally block");
            assertEquals(1, phones.get(n).rallyBlocks.stream().distinct().count(), n + ": the same one, not a new one");
        }

        // DISPATCH: every one leaves the ground with its rally point on it
        double[] target = GeoLocal.offset(LAT, LON, 40, 250);
        javax.swing.SwingUtilities.invokeAndWait(() -> ops.dispatch(target[0], target[1]));
        long end = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < end && !phones.values().stream().allMatch(p -> p.arms.size() == 1)) tick(200);
        for (VirtualFleetPhone p : phones.values()) assertEquals(List.of(p.copter.id + " marked"), p.arms, "armed once, with its rally point: " + log);
        assertTrue(log.stream().noneMatch(l -> l.contains("rally point not confirmed")), log.toString());
    }

    @Test
    void placesKeepTwiceTheProtectionRadius() {
        List<double[]> taken = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double[] p = RallyPlan.place(LAT, LON + i * 1e-6, taken); // twelve on one spot
            assertTrue(RallyPlan.free(p[0], p[1], taken));
            taken.add(p);
        }
        assertEquals(20, RallyPlan.MIN_APART_M, 1e-9, "twice the protection radius");
        assertEquals(12, taken.size());
    }
}
