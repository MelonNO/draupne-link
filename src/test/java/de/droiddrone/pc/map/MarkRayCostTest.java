package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MARK casts one ray at each click, on the Swing thread: what the longest one costs over terrain as the app holds it
 * (Terrarium tiles round the drone, four heights read at each of its some 480 steps). Measured 2026-11-14 on the PC
 * it was written on: 0.04 ms; the budget is far above, to catch a change that makes it many times dearer.
 */
class MarkRayCostTest {
    @Test
    void theLongestRayOverTerrainTilesIsFarUnderAMillisecond() {
        double lat = 60, lon = 10;
        RadioHorizon.Data terrain = new RadioHorizon.Data(lat, lon, true);
        BufferedImage tile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = tile.createGraphics();
        g.setColor(new java.awt.Color(0x80, 0xc8, 0)); // Terrarium: 32768 + 200 m
        g.fillRect(0, 0, 256, 256);
        g.dispose();
        for (int[] t : RadioHorizon.tilesAround(lat, lon, RadioHorizon.TERRAIN_ZOOM, 5000)) terrain.putTerrain(t[0], t[1], tile);
        // 300 m over the ground, looking 6 degrees down: the ground 2.85 km out, just inside the camera's reach
        CameraFootprint.Pose p = new CameraFootprint.Pose(lat, lon, 500, 40, -2, 0, -4, 70, 43);
        CameraFootprint.Ray ray = CameraFootprint.ray(p, 0, 0, terrain, 200);
        assertNotNull(ray.ground());
        assertFalse(ray.flatGround(), "every height from the tiles");
        assertEquals(300 / Math.sin(Math.toRadians(6)), ray.slantRangeM(), 15);
        for (int i = 0; i < 300; i++) CameraFootprint.ray(p, 0, i * 1e-4, terrain, 200);
        int n = 500;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) CameraFootprint.ray(p, 0, i * 1e-4, terrain, 200);
        double ms = (System.nanoTime() - t0) / 1e6 / n;
        System.out.printf("MARK's longest ray over terrain tiles: %.3f ms%n", ms);
        assertTrue(ms < 5, "a ray: " + ms + " ms");
    }
}
