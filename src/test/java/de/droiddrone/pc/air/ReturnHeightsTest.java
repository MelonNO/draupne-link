package de.droiddrone.pc.air;

import de.droiddrone.common.FleetCmd;
import de.droiddrone.common.FleetStatus;
import de.droiddrone.common.group.GeoLocal;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet PRD RT-9 / FD-6: return heights of drones out alone - planned, sent, confirmed only as ArduPilot answered. */
class ReturnHeightsTest {
    static final double LAT = 60, LON = 10;
    final AirParams p = AirParams.defaults();

    private static double[] at(double e, double n) {
        return GeoLocal.offset(LAT, LON, e, n);
    }

    private Map<String, Double> put(ReturnHeights r, String id, double[] far, double[] rally, double min) {
        return r.put(id, far[0], far[1], rally[0], rally[1], 100, min, p);
    }

    @Test
    void crossingWaysHomeGoALayerApartAndALaterDroneMovesTheOthers() {
        ReturnHeights r = new ReturnHeights();
        Map<String, Double> c = put(r, "A", at(0, 400), at(0, 0), 70);
        assertEquals(Map.of("A", 70.0), c, "alone: its own RT-9 height");
        c = put(r, "B", at(-200, 200), at(200, 200), 70.04);
        assertEquals(80, r.heightM("B"), 1e-9, "crossing A's way home at its height: a layer up");
        assertEquals(Map.of("B", 80.0), c);
        // a third whose own lowest is lower: taken first - the others may move
        c = put(r, "C", at(10, 400), at(10, 0), 50);
        assertEquals(50, r.heightM("C"), 1e-9);
        assertEquals(70, r.heightM("A"), 1e-9, "a layer over C already");
        assertTrue(Math.abs(r.heightM("A") - r.heightM("C")) >= p.layerM() - ExitPlanner.VERTICAL_TOLERANCE_M);
        // B back on the ground: the others planned without it
        r.report("B", -1, true, false);
        assertEquals(-1, r.report("B", -1, false, true), "landed after a flight");
        r.remove("B", p);
        assertFalse(r.contains("B"));
    }

    @Test
    void farApartEachKeepsItsOwn() {
        ReturnHeights r = new ReturnHeights();
        put(r, "A", at(0, 400), at(0, 0), 65);
        put(r, "B", at(300, 400), at(300, 0), 65);
        assertEquals(65, r.heightM("A"), 1e-9);
        assertEquals(65, r.heightM("B"), 1e-9);
    }

    @Test
    void confirmedOnlyWhenTheReportSaysThatHeightAndSentAgainUntilThen() {
        ReturnHeights r = new ReturnHeights();
        put(r, "A", at(0, 400), at(0, 0), 72.3);
        assertFalse(r.confirmed("A"));
        assertTrue(r.due("A", 1000));
        assertFalse(r.due("A", 1500), "not twice a second");
        assertTrue(r.due("A", 2000));
        assertEquals(0, r.report("A", 150, false, true), "ArduPilot's old 15 m: not it");
        assertEquals(0, r.report("A", -1, false, true), "an older phone says nothing: not it");
        assertFalse(r.overdue("A", 5000));
        assertTrue(r.overdue("A", 1000 + ReturnHeights.CONFIRM_WAIT_MS), "the pilot told once");
        assertFalse(r.overdue("A", 2000 + ReturnHeights.CONFIRM_WAIT_MS));
        assertEquals(1, r.report("A", 723, false, true));
        assertTrue(r.confirmed("A"));
        assertFalse(r.due("A", 9000), "nothing more to send");
        // a new drone that moves A: A needs its new height read back again
        put(r, "B", at(-200, 200), at(200, 200), 60);
        if (r.heightM("A") != 72.3) assertFalse(r.confirmed("A"));
    }

    @Test
    void theWireCarriesItToTheDecimetreAndAnOlderPhoneSaysNothing() {
        byte[] k = FleetCmd.wrap("DD", FleetCmd.ret(72.31));
        FleetCmd c = FleetCmd.read(k, k.length);
        assertEquals(FleetCmd.RETURN, c.inner());
        assertEquals(72.4, FleetCmd.readReturnHeight(c), 1e-9, "rounded up, never under the plan");
        byte[] cut = java.util.Arrays.copyOf(k, k.length - 1);
        assertEquals(-1, FleetCmd.readReturnHeight(FleetCmd.read(cut, cut.length)));
        FleetStatus s = new FleetStatus();
        s.lat = LAT;
        s.lon = LON;
        assertEquals(-1, FleetStatus.decode(s.encode(), FleetStatus.WITH_RTL_BYTES).rtlAltDm, "not known");
        s.rtlAltDm = 724;
        byte[] d = s.encode();
        assertEquals(724, FleetStatus.decode(d, d.length).rtlAltDm);
        assertEquals(-1, FleetStatus.decode(d, FleetStatus.BYTES).rtlAltDm, "34 bytes (an older phone): not known");
    }
}
