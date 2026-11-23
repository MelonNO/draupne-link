package de.droiddrone.pc.air;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet PRD FD-6: the return heights of drones out on their own keep crossing ways home a layer apart. */
class SoloReturnsTest {
    final AirParams p = AirParams.defaults();

    @Test
    void twoCrossingWaysHomeGoALayerApart() {
        List<SoloReturns.Solo> s = List.of(
                new SoloReturns.Solo("A", new V2(-200, 0), new V2(200, 0), 0, 60),
                new SoloReturns.Solo("B", new V2(0, -200), new V2(0, 200), 0, 60));
        Map<String, Double> h = SoloReturns.heights(s, p);
        assertEquals(60, h.get("A"), 1e-9, "the first keeps its own");
        assertEquals(70, h.get("B"), 1e-9, "the other a layer up");
    }

    @Test
    void waysHomeFarApartKeepTheirOwnHeights() {
        List<SoloReturns.Solo> s = List.of(
                new SoloReturns.Solo("A", new V2(0, 0), new V2(0, 500), 0, 60),
                new SoloReturns.Solo("B", new V2(100, 0), new V2(100, 500), 0, 60),
                new SoloReturns.Solo("C", new V2(-30, 0), new V2(-30, 500), 0, 30));
        Map<String, Double> h = SoloReturns.heights(s, p);
        assertEquals(60, h.get("A"), 1e-9);
        assertEquals(60, h.get("B"), 1e-9);
        assertEquals(p.floorM(), h.get("C"), 1e-9, "never under the floor");
    }

    @Test
    void differentHomesAreComparedAboveSeaLevel() {
        List<SoloReturns.Solo> s = List.of( // B's home 10 m higher: 60 over its home is A's 70 - a layer clear already
                new SoloReturns.Solo("A", new V2(-200, 0), new V2(200, 0), 0, 60),
                new SoloReturns.Solo("B", new V2(0, -200), new V2(0, 200), 10, 60));
        Map<String, Double> h = SoloReturns.heights(s, p);
        assertEquals(60, h.get("A"), 1e-9);
        assertEquals(70, h.get("B") + 10, 1e-9, "B at 70 above sea level: a layer over A, not under it");
    }

    @Test
    void anyNumberOfWaysHomeEveryNearPairALayerApart() {
        Random r = new Random(5);
        for (int run = 0; run < 50; run++) {
            List<SoloReturns.Solo> s = new ArrayList<>();
            int n = 2 + r.nextInt(10);
            for (int i = 0; i < n; i++) {
                V2 from = new V2(r.nextInt(1200) - 600, r.nextInt(1200) - 600);
                V2 to = new V2(r.nextInt(200) - 100, r.nextInt(200) - 100); // most land at one site
                s.add(new SoloReturns.Solo("D" + i, from, to, r.nextInt(20), 40 + r.nextInt(80)));
            }
            Map<String, Double> h = SoloReturns.heights(s, p);
            for (SoloReturns.Solo a : s) {
                assertTrue(h.get(a.id()) >= Math.max(a.minRtlAltRelM(), p.floorM()) - 1e-9, a.id() + " under its lowest");
                for (SoloReturns.Solo b : s) {
                    if (a.id().compareTo(b.id()) >= 0) continue;
                    if (SoloReturns.segmentDistance(a.pos(), a.rally(), b.pos(), b.rally()) >= 2 * p.radiusM()) continue;
                    double dz = Math.abs(a.homeAmsl() + h.get(a.id()) - b.homeAmsl() - h.get(b.id()));
                    assertTrue(dz >= p.layerM() - ExitPlanner.VERTICAL_TOLERANCE_M, "run " + run + ": " + a.id() + "-" + b.id() + " " + dz + " m apart in height");
                }
            }
        }
    }
}
