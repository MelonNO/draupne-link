package de.droiddrone.pc.gui;

import de.droiddrone.pc.air.virt.VirtualFleetPhone;
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
 * Fleet PRD phase 3 in miniature on loopback UDP: four drones marked SAFE TO AUTOLAUNCH on one site, DISPATCHed to a
 * place 250 m north over flat ground - each a route block of its own (read back on its phone), a staggered launch
 * straight up to its own layer, its route in AUTO, held at its own slot at the end; never two drones inside 10 m
 * sideways and 10 m in height. Kinematic copters stand in for ArduPilot; names and places made up; real time.
 */
class DispatchLoopbackTest {
    static final double LAT = 60.0, LON = 10.0, GROUND = 0;
    private final Map<String, VirtualFleetPhone> phones = new LinkedHashMap<>();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private FleetOps ops;

    @AfterEach
    void close() {
        if (ops != null) ops.close();
        for (VirtualFleetPhone p : phones.values()) p.close();
    }

    @Test
    void fourDronesEachToTheirOwnSlotAndLayerAndApart() throws Exception {
        String[] names = {"D1", "D2", "D3", "D4"};
        for (int i = 0; i < names.length; i++) {
            VirtualFleetPhone p = new VirtualFleetPhone(names[i], LAT, LON, GROUND, i * 25, 0, 0, "DD");
            p.copter.climb = 8;
            p.copter.guidedSpeed = 20;
            p.copter.maxSpeed = 20;
            p.landed();
            phones.put(names[i], p);
        }
        ops = new FleetOps(new FleetOps.Host() {
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
        for (String n : names) {
            javax.swing.SwingUtilities.invokeAndWait(() -> ops.items(n).stream().filter(i -> i.getText().startsWith("SAFE TO AUTOLAUNCH: mark"))
                    .findFirst().orElseThrow().doClick());
        }
        Thread.sleep(1500); // their reports, with where they stand
        double[] target = de.droiddrone.common.group.GeoLocal.offset(LAT, LON, 40, 250);
        javax.swing.SwingUtilities.invokeAndWait(() -> ops.dispatch(target[0], target[1]));
        double worst = Double.MAX_VALUE;
        long end = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < end && !phones.values().stream().allMatch(VirtualFleetPhone::routeDone)) {
            javax.swing.SwingUtilities.invokeAndWait(ops::tickReserves);
            worst = Math.min(worst, closest(new ArrayList<>(phones.values())));
            Thread.sleep(200);
        }
        for (VirtualFleetPhone p : phones.values()) {
            assertTrue(p.routeDone(), p.copter.id + " at its slot " + log);
            assertEquals(1, p.arms.size(), p.copter.id + " armed once: " + p.arms);
        }
        assertTrue(worst > 0, "no two inside 10 m sideways and 10 m in height: " + worst);
        List<Double> heights = new ArrayList<>();
        for (VirtualFleetPhone p : phones.values()) heights.add((double) Math.round(p.copter.u));
        assertEquals(4, heights.stream().distinct().count(), "each its own layer: " + heights);
    }

    static double closest(List<VirtualFleetPhone> ps) {
        double worst = Double.MAX_VALUE;
        for (int i = 0; i < ps.size(); i++) {
            for (int j = i + 1; j < ps.size(); j++) {
                var a = ps.get(i).copter;
                var b = ps.get(j).copter;
                if (a.landed && b.landed) continue; // side by side on the ground before the launch
                worst = Math.min(worst, Math.max(Math.hypot(a.e - b.e, a.n - b.n) - 10, Math.abs(a.u - b.u) - 10));
            }
        }
        return worst;
    }
}
