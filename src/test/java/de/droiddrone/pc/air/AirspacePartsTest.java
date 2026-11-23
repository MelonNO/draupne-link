package de.droiddrone.pc.air;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The airspace's parts (PRD area K): the cube's geometry, the transit areas, conflicts, layers, battery order, columns. */
class AirspacePartsTest {
    final AirParams p = AirParams.defaults();
    final HollowCube cube = new HollowCube(p);

    @Test
    void theCubesRingsAreAProtectionRadiusApartInEveryLayer() {
        // cells 20 m apart, never less than the radius + 5 (AC-6); the lane a radius outside the cube; shafts and the
        // hop ring more than a radius outside the lane; the core empty (AC-16)
        for (int a = 0; a < HollowCube.CELLS; a++) {
            for (int b = a + 1; b < HollowCube.CELLS; b++) assertTrue(cube.cell(a).dist(cube.cell(b)) >= p.radiusM() + 5);
            assertTrue(cube.cell(a).length() >= p.cellM() - 1e-9, "nothing in the core");
            for (HollowCube.Face f : HollowCube.Face.values()) {
                assertTrue(V2.segDist(cube.cell(a), cube.lanePoint(f, new V2(-p.laneM(), -p.laneM())), cube.lanePoint(f, new V2(p.laneM(), p.laneM()))) >= p.radiusM(),
                        "the lane is a radius from every cell");
                assertTrue(cube.cell(a).dist(cube.shaft(f)) > 2 * p.radiusM());
            }
        }
        for (HollowCube.Face f : HollowCube.Face.values()) {
            assertTrue(cube.shaft(f).dist(cube.laneCentre(f)) > p.radiusM(), "a drone in a shaft is more than a radius from the lane");
            assertTrue(V2.segDist(cube.laneCentre(f), cube.shaft(f), cube.hopCorner(f, f.next())) >= p.radiusM());
        }
        assertEquals(82, p.keepOutBoundaryM(), 1e-9);
        assertNull(p.sizeProblem(), "the pilot's numbers fit together (AC-35)");
        AirParams tight = new AirParams(20, 10, 10, 20, 85, 70, 40, 8, 15, 2, 30_000, 20, 120_000, 10_000, 30_000, 300_000, 20_000, Double.NaN, 3);
        assertEquals("Distance from the lead at least 82 m (AC-35). Leash at least 92 m (E12).", tight.sizeProblem(), "the smallest values that work");
    }

    @Test
    void layersSitOverTheFloorAndTheAbortLayerAtIt() {
        assertEquals(40, cube.abortAlt());
        assertEquals(50, cube.layerAlt(0), "the cube one layer over the floor (K14)");
        assertEquals(70, cube.layerAlt(2));
        assertEquals(3, HollowCube.layersFor(20), "20 followers: 8 + 8 + 4");
        assertEquals(List.of(HollowCube.Face.N, HollowCube.Face.E), HollowCube.faces(2));
        assertEquals(List.of(HollowCube.Face.N), HollowCube.faces(1));
    }

    @Test
    void aMoveGoesOutThroughItsShaftRoundTheRingAndInChangingHeightOnlyInAShaft() {
        List<HollowCube.Stage> m = cube.move(0, 1, 2, 5, 0); // north middle, bottom row -> south middle, top
        assertTrue(m.get(0).resources().contains(HollowCube.shaftRes(HollowCube.Face.N)));
        assertEquals(4, m.size(), "out, two hops, in");
        double layer0 = cube.layerAlt(0);
        for (int s = 0; s < m.size() - 1; s++) for (HollowCube.Point pt : m.get(s).points()) assertEquals(layer0, pt.alt(), "own height until the last shaft");
        HollowCube.Stage in = m.get(3);
        assertEquals(cube.shaft(HollowCube.Face.S), in.points().get(0).at());
        assertEquals(cube.layerAlt(2), in.points().get(0).alt(), "the height changed in the shaft");
        assertEquals(cube.cell(5), in.points().get(in.points().size() - 1).at());
        // a corner cell next door: no hop
        assertEquals(2, cube.move(1, 2, 1, 3, 0).size());
        // the abort exit: down from the cell, under the corner, out diagonally past the keep-out zone
        HollowCube.Stage exit = cube.abortExit(1);
        assertEquals(p.floorM(), exit.points().get(0).alt());
        V2 out = exit.points().get(exit.points().size() - 1).at();
        assertTrue(cube.outsideEdge(out) > p.keepOutM(), "beyond the keep-out zone");
        assertTrue(exit.resources().contains(HollowCube.cornerExit(2)));
    }

    @Test
    void oneDroneAShaftAtMostThreeFromOutsideAHopMayTakeTheFourth() {
        TransitScheduler s = new TransitScheduler();
        HollowCube.Face[] f = HollowCube.Face.values();
        for (int i = 0; i < 3; i++) s.ask("D" + i, cube.out(0, 2 * i + 1, f[i]).get(0), TransitScheduler.PRIO_WAITING, 0);
        assertEquals(3, s.decide().size());
        s.ask("D3", cube.out(1, 7, HollowCube.Face.W).get(0), TransitScheduler.PRIO_WAITING, 0);
        assertTrue(s.decide().isEmpty(), "a fourth from outside: no (AC-23)");
        s.ask("D0", cube.hopsFrom(HollowCube.Face.N, HollowCube.Face.W, -1, 50).get(0), TransitScheduler.PRIO_TRANSIT, 0);
        assertEquals(Set.of("D0"), s.decide(), "a hop into the free one: yes (AC-29)");
        s.release("D0", Set.of(HollowCube.shaftRes(HollowCube.Face.N)));
        s.ask("D4", cube.landThroughShaft(1, 1, HollowCube.Face.N).get(0), TransitScheduler.PRIO_LANDING, 0);
        assertEquals(Set.of("D4"), s.decide(), "a drone at 3 % may take the fourth (K10)");
        assertEquals(4, s.shaftsInUse());
        s.ask("D5", cube.out(2, 1, HollowCube.Face.N).get(0), TransitScheduler.PRIO_WAITING, 0);
        assertTrue(s.decide().isEmpty(), "one drone a shaft");
    }

    @Test
    void priorityOrderAndNoLockUpRoundTheRing() {
        TransitScheduler s = new TransitScheduler();
        s.ask("WAITER", cube.out(0, 1, HollowCube.Face.N).get(0), TransitScheduler.PRIO_WAITING, 0);
        s.ask("LANDER", cube.landThroughShaft(1, 1, HollowCube.Face.N).get(0), TransitScheduler.PRIO_LANDING, 5);
        assertEquals(Set.of("LANDER"), s.decide(), "landing before waiting (AC-9)");
        // random drones moving between faces through the shafts, turning round after waiting: everyone gets through
        Random r = new Random(7);
        for (int round = 0; round < 50; round++) {
            TransitScheduler t = new TransitScheduler();
            int n = 2 + r.nextInt(6);
            List<List<HollowCube.Stage>> moves = new ArrayList<>();
            int[] at = new int[n];
            int[] waited = new int[n];
            for (int i = 0; i < n; i++) moves.add(new ArrayList<>(cube.move(r.nextInt(3), r.nextInt(8), r.nextInt(3), r.nextInt(8), r.nextBoolean() ? 1 : -1)));
            Map<Integer, Set<String>> heldBefore = new HashMap<>();
            int steps = 0;
            boolean done = false;
            while (!done && steps++ < 2000) {
                done = true;
                for (int i = 0; i < n; i++) {
                    if (at[i] >= moves.get(i).size()) continue;
                    done = false;
                    t.ask("M" + i, moves.get(i).get(at[i]), TransitScheduler.PRIO_WAITING, steps);
                }
                Set<String> g = t.decide();
                for (int i = 0; i < n; i++) {
                    if (at[i] >= moves.get(i).size()) continue;
                    if (g.contains("M" + i)) {
                        Set<String> prev = heldBefore.getOrDefault(i, Set.of());
                        Set<String> drop = new HashSet<>(prev);
                        drop.removeAll(moves.get(i).get(at[i]).resources());
                        t.release("M" + i, drop);
                        heldBefore.put(i, moves.get(i).get(at[i]).resources());
                        at[i]++;
                        waited[i] = 0;
                        if (at[i] == moves.get(i).size()) t.releaseAll("M" + i);
                    } else if (++waited[i] > 20 && at[i] > 0 && at[i] < moves.get(i).size() - 1) {
                        // the other way round from the shaft it holds (AC-29)
                        HollowCube.Face cur = null;
                        for (String res : t.heldBy("M" + i)) if (res.startsWith("shaft:")) cur = HollowCube.Face.valueOf(res.substring(6));
                        HollowCube.Stage last = moves.get(i).get(moves.get(i).size() - 1);
                        HollowCube.Face goal = HollowCube.Face.valueOf(last.resources().stream().filter(x -> x.startsWith("shaft:")).findFirst().get().substring(6));
                        if (cur != null) {
                            List<HollowCube.Stage> redo = new ArrayList<>(moves.get(i).subList(0, at[i]));
                            int dir = r.nextBoolean() ? 1 : -1;
                            redo.addAll(cube.hopsFrom(cur, goal, dir, 50));
                            redo.add(last);
                            moves.set(i, redo);
                        }
                        waited[i] = 0;
                        t.forgetWait("M" + i);
                    }
                }
            }
            assertTrue(done, "round " + round + ": everyone through (no lock-up)");
        }
    }

    @Test
    void conflictsAreFoundFiveSecondsAheadAndTheLowerPriorityGivesWay() {
        ConflictPredictor c = new ConflictPredictor(p);
        var a = new ConflictPredictor.Track("A", 0, 0, 100, 5, 0, 0, TransitScheduler.PRIO_TRANSIT);
        var b = new ConflictPredictor.Track("B", 40, 0, 105, -5, 0, 0, TransitScheduler.PRIO_WAITING);
        var lead = new ConflictPredictor.Track("L", 40, 0, 103, -5, 0, 0, 0);
        List<ConflictPredictor.Conflict> got = c.predict(List.of(a, b));
        assertEquals(1, got.size());
        assertEquals("B", got.get(0).givesWay());
        assertEquals(3.0, got.get(0).inS(), 0.26);
        assertEquals("A", c.predict(List.of(a, lead)).get(0).givesWay(), "the lead is never moved");
        assertTrue(c.predict(List.of(a, new ConflictPredictor.Track("C", 40, 0, 110, -5, 0, 0, 3))).isEmpty(), "10 m apart in height: separated");
        assertEquals(1, c.traffic(lead, List.of(new ConflictPredictor.Track("D", 55, 0, 118, 0, 0, 0, 3))).size(), "TRAFFIC at twice the radius / spacing");
    }

    @Test
    void layerTableBandsOwnLayersAndTheCeiling() {
        LayerTable t = new LayerTable(p, 300);
        assertEquals(340, t.baseAmsl(), "the floor over the highest ground");
        assertTrue(t.takeBand("G1", 0, 3));
        assertEquals(4, t.aboveBands());
        int lead = t.ownLayer("LEAD", 0, 0, t.aboveBands());
        int res = t.ownLayer("R1", 100, 0, t.aboveBands());
        assertEquals(4, lead);
        assertEquals(5, res, "within 500 m: a layer of its own");
        assertEquals(4, t.ownLayer("FAR", 2000, 0, t.aboveBands()), "farther than 500 m: may share");
        t.release("R1");
        assertEquals(5, t.ownLayer("R2", 50, 0, t.aboveBands()));
        LayerTable low = new LayerTable(p.withCeiling(75), 300);
        assertTrue(low.takeBand("G", 0, 3), "40..70 m fits under 75");
        assertEquals(LayerTable.NO_LAYER, low.ownLayer("X", 0, 0, low.aboveBands()), "NO LAYER FREE (AC-13)");
        assertFalse(new LayerTable(p.withCeiling(60), 300).takeBand("G", 0, 3));
    }

    @Test
    void batteryOrderLowestAtTheBottomMovesOnlyWhenTheLayerChanges() {
        Map<String, BatteryOrder.Place> now = new HashMap<>();
        Map<String, Double> batt = new HashMap<>();
        for (int i = 0; i < 12; i++) {
            now.put("D" + i, new BatteryOrder.Place(i / 8, i % 8));
            batt.put("D" + i, 50.0 + i);
        }
        assertEquals(now, BatteryOrder.assign(now, batt), "already in order: nobody moves");
        batt.put("D9", 20.0); // a top drone drained fastest: to the bottom, the bottom's highest goes up
        Map<String, BatteryOrder.Place> want = BatteryOrder.assign(now, batt);
        assertEquals(0, want.get("D9").layer());
        assertEquals(1, want.get("D7").layer());
        long moved = want.entrySet().stream().filter(e -> !e.getValue().equals(now.get(e.getKey()))).count();
        assertEquals(2, moved, "two moves, no more");
        assertEquals("D9", BatteryOrder.nextLead(want, batt), "the next lead: the bottom row's lowest");
    }

    @Test
    void columnsDontOverlapAndWaitForOthersToClear() {
        ColumnReservations c = new ColumnReservations(p);
        assertTrue(c.reserve("R1", new ColumnReservations.Column(0, 0, 100, 200), List.of()));
        assertFalse(c.reserve("R2", new ColumnReservations.Column(15, 0, 100, 200), List.of()), "overlapping: one after the other (AC-5)");
        assertTrue(c.reserve("R3", new ColumnReservations.Column(30, 0, 100, 200), List.of()));
        assertFalse(c.reserve("R4", new ColumnReservations.Column(300, 0, 100, 200), List.of(new double[]{300, -30, 150, 0, 8, 0})),
                "a drone predicted into it within 5 s");
        c.release("R1");
        assertTrue(c.reserve("R2", new ColumnReservations.Column(-15, 0, 100, 200), List.of()), "clear of R3 and, R1 gone, free");
    }
}
