package de.droiddrone.pc.gui;

import de.droiddrone.pc.air.virt.VirtualFleetPhone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD RT-9 / FD-6 on loopback UDP: two drones DISPATCHed one after the other, their ways home crossing - each gets
 * its RTL_ALT before it leaves (FleetCmd RETURN, read back in its reports), the second a layer over the first; a phone
 * that never confirms one (an older app) stays on the ground and the pilot is told. Kinematic copters; places made up.
 */
class ReturnHeightsLoopbackTest {
    static final double LAT = 60.0, LON = 10.0, GROUND = 0;
    private final Map<String, VirtualFleetPhone> phones = new LinkedHashMap<>();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private FleetOps ops;

    @AfterEach
    void close() {
        if (ops != null) ops.close();
        for (VirtualFleetPhone p : phones.values()) p.close();
    }

    private VirtualFleetPhone drone(String id, double e, double n) throws Exception {
        VirtualFleetPhone p = new VirtualFleetPhone(id, LAT, LON, GROUND, e, n, 0, "DD");
        p.copter.climb = 8;
        p.copter.guidedSpeed = 20;
        p.copter.maxSpeed = 20;
        p.landed();
        phones.put(id, p);
        return p;
    }

    private void until(BooleanSupplier ok, long ms, String what) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            javax.swing.SwingUtilities.invokeAndWait(ops::tickReserves);
            if (ok.getAsBoolean()) return;
            Thread.sleep(200);
        }
        fail(what + " " + log);
    }

    private void mark(String n) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> ops.items(n).stream().filter(i -> i.getText().startsWith("SAFE TO AUTOLAUNCH: mark"))
                .findFirst().orElseThrow().doClick());
    }

    private void dispatch(double e, double n) throws Exception {
        double[] t = de.droiddrone.common.group.GeoLocal.offset(LAT, LON, e, n);
        javax.swing.SwingUtilities.invokeAndWait(() -> ops.dispatch(t[0], t[1]));
    }

    @Test
    void crossingWaysHomeALayerApartEachConfirmedBeforeItArmsAndAnUnconfirmedOneStays() throws Exception {
        VirtualFleetPhone a = drone("A", -300, 0), b = drone("B", -150, -150);
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
        mark("A");
        Thread.sleep(1500);
        dispatch(0, 0); // A east, home west along the same line
        until(() -> a.arms.size() == 1, 30_000, "A launched");
        assertEquals(FleetOps.DISPATCH_HEIGHT_M, ops.returnHeightOf("A"), 0.01, "flat ground: its route height");
        assertEquals(ops.returnHeightOf("A"), a.rtlAltM(), 0.01, "on its FC before it armed");
        assertTrue(ops.returnOnDrone("A"));

        mark("B");
        Thread.sleep(1500);
        dispatch(-150, 150); // B north: its way home crosses A's
        until(() -> b.arms.size() == 1, 30_000, "B launched");
        assertEquals(FleetOps.DISPATCH_HEIGHT_M + 10, ops.returnHeightOf("B"), 0.01, "a layer over A where the ways cross");
        assertEquals(ops.returnHeightOf("B"), b.rtlAltM(), 0.01);
        assertEquals(FleetOps.DISPATCH_HEIGHT_M, a.rtlAltM(), 0.01, "A kept its own");

        // an older phone: RETURN unknown, no RTL_ALT in its reports - held, the pilot told
        VirtualFleetPhone c = drone("C", 400, 0);
        c.ignoreReturn = true;
        mark("C");
        Thread.sleep(1500);
        dispatch(400, 300);
        until(() -> log.stream().anyMatch(s -> s.startsWith("! C: return height not confirmed")), 30_000, "C held, said");
        assertTrue(c.arms.isEmpty(), "C never armed");
        assertTrue(c.copter.landed);
    }
}
