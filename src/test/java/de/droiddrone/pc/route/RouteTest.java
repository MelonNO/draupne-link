package de.droiddrone.pc.route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet PRD RT-1 / RT-15: the route model, its file, and the terrain kept on disk (RT-2, "Offline"). */
class RouteTest {
    @Test
    void editingARoute() {
        Route r = Ground.route(new double[]{0, 100}, new double[]{0, 200}, new double[]{0, 300});
        assertEquals(Route.DEFAULT_HEIGHT_M, r.heightM());
        assertEquals(Route.DEFAULT_SMOOTHING_M, r.smoothingM());
        double[] mid = Ground.at(50, 150);
        r.insert(1, mid[0], mid[1]);
        assertEquals(4, r.size());
        assertEquals(50, Ground.east(r.point(1).lon()), 0.01);
        double[] moved = Ground.at(10, 300);
        r.move(3, moved[0], moved[1]);
        assertEquals(10, Ground.east(r.point(3).lon()), 0.01);
        r.setOwnHeight(2, 90);
        assertEquals(90, r.heightAt(2));
        assertEquals(60, r.heightAt(0));
        r.move(2, mid[0], mid[1]);
        assertEquals(90, r.heightAt(2), "a moved point keeps its own height");
        r.delete(1);
        assertEquals(3, r.size());
        r.reverse();
        assertEquals(10, Ground.east(r.point(0).lon()), 0.01);
        r.setOwnHeight(0, 3);
        assertEquals(Route.MIN_HEIGHT_M, r.heightAt(0), "never under the least height");
        r.setOwnHeight(0, Double.NaN);
        assertFalse(r.point(0).hasOwnHeight());
        r.setHeightM(4);
        assertEquals(Route.MIN_HEIGHT_M, r.heightM());
        Route c = r.copy();
        r.delete(0);
        assertEquals(3, c.size(), "the planner's copy is its own");
    }

    @Test
    void aSavedRouteReadsBackTheSame(@TempDir Path dir) throws Exception {
        Route r = Ground.route(new double[]{0, 100}, new double[]{250, 400});
        r.setName("RIDGE NORTH");
        r.setHeightM(75);
        r.setSmoothingM(8);
        r.setOwnHeight(1, 120);
        Path f = dir.resolve("ridge.route");
        r.save(f);
        Route back = Route.load(f);
        assertEquals("RIDGE NORTH", back.name());
        assertEquals(75, back.heightM());
        assertEquals(8, back.smoothingM());
        assertEquals(2, back.size());
        assertEquals(r.point(0).lat(), back.point(0).lat(), 1e-7);
        assertEquals(r.point(1).lon(), back.point(1).lon(), 1e-7);
        assertFalse(back.point(0).hasOwnHeight());
        assertEquals(120, back.point(1).heightM());
        assertEquals(r.toText(), back.toText());
    }

    @Test
    void whatIsNotARouteIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Route.fromText("hello"));
        assertThrows(IllegalArgumentException.class, () -> Route.fromText(Route.MAGIC + "\npoint 95 10 -\n"));
        assertThrows(IllegalArgumentException.class, () -> Route.fromText(Route.MAGIC + "\npoint x y\n"));
    }

    @Test
    void terrainLookedUpOnceIsThereOfflineAfterARestart(@TempDir Path dir) throws Exception {
        TerrainStore a = new TerrainStore(dir);
        double[] p = Ground.at(100, 100);
        assertTrue(Double.isNaN(a.heightM(p[0], p[1])));
        a.put(p[0], p[1], 412.3);
        assertEquals(412.3, a.heightM(p[0], p[1]), 1e-3);
        double[] close = Ground.at(101, 102);
        assertEquals(412.3, a.heightM(close[0], close[1]), 1e-3, "the nearest grid node's");
        a.flush();
        assertTrue(Files.list(dir).anyMatch(f -> f.getFileName().toString().endsWith(".txt")));
        TerrainStore b = new TerrainStore(dir);
        assertEquals(412.3, b.heightM(p[0], p[1]), 0.05, "read from disk, no network");
    }

    @Test
    void missingListsEachNodeOnce() {
        TerrainStore s = new TerrainStore(null);
        List<double[]> samples = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) samples.add(Ground.at(i, 0));
        List<double[]> missing = s.missing(samples);
        assertTrue(missing.size() >= 17 && missing.size() <= 20, "100 m east at ~5.6 m a node: " + missing.size());
        for (double[] n : missing) s.put(n[0], n[1], 10);
        assertTrue(s.missing(samples).isEmpty());
    }

    @Test
    void aFillStopsWhenCancelledAndCountsWhatItFound() {
        TerrainStore s = new TerrainStore(null);
        List<double[]> nodes = List.of(Ground.at(0, 0), Ground.at(50, 0), Ground.at(100, 0), Ground.at(150, 0));
        int[] calls = {0};
        int found = TerrainFill.fill(s, nodes, (la, lo) -> ++calls[0] == 2 ? null : 5.0, null, () -> calls[0] >= 3);
        assertEquals(3, calls[0]);
        assertEquals(2, found);
    }
}
