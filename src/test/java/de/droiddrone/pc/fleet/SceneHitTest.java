package de.droiddrone.pc.fleet;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The panel's targets (1.0.52, the pilot): VEHICLE is the realistic BTR on patrol, and a drone flying into it or a
 * soldier hits it - the target is gone.
 */
class SceneHitTest {
    @Test
    void aDroneFlyingIntoTheSoldierHitsHimAndHeIsGone() {
        Scene.View v = new Scene.View(0, 0, 1.5, 0, 0, 0);
        Scene scene = new Scene("MobileZulu", 1);
        scene.addTarget(Scene.Kind.WALKER, v, 0, 10);
        long t = 4000;
        double[] at = scene.targets(t).get(0).at(t);
        assertNull(scene.hit(at[0], at[1], 6, t), "well over his head: no");
        assertNull(scene.hit(at[0] + 3, at[1], 1, t), "three metres off: no");
        Scene.Target hit = scene.hit(at[0], at[1], 1.2, t);
        assertNotNull(hit, "into his chest");
        assertEquals(Scene.Kind.WALKER, hit.kind);
        assertFalse(scene.hasTargets(t), "hit: gone");
    }

    @Test
    void theVehicleIsTheBtrOnPatrolAndCanBeHit() {
        Scene.View v = new Scene.View(0, 0, 1.5, 0, 0, 90);
        Scene scene = new Scene("MobileZulu", 1);
        scene.addTarget(Scene.Kind.VEHICLE, v, 0);
        Scene.Target btr = scene.targets(0).get(0);
        assertNotNull(btr.btr, "the BTR, not a drawn car");
        assertNotNull(btr.sound, "with its real sound");
        long t = 15_000;
        double[] at = btr.at(t);
        double ahead = at[1]; // looking east
        assertTrue(ahead > Scene.VEHICLE_NEAR_M - 25 && ahead < Scene.VEHICLE_FAR_M + 25, "on patrol ahead: " + ahead + " m");
        // in the picture: its olive hull darker than the pale world
        BufferedImage img = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        scene.render(img, v, t, null, false);
        int olive = 0;
        for (int c : Scene.pixels(img)) {
            int r = c >> 16 & 0xff, g = c >> 8 & 0xff, b = c & 0xff;
            if ((66 * r + 129 * g + 25 * b + 128 >> 8) + 16 < 120 && r >= b && g >= b) olive++;
        }
        assertTrue(olive > 20, "the BTR is drawn: " + olive + " of its pixels");
        assertNull(scene.hit(at[0], at[1], 8, t), "over it: no");
        assertSame(btr, scene.hit(at[0], at[1], 1.5, t), "into its hull");
        assertFalse(scene.hasTargets(t), "hit: gone");
    }

    @Test
    void theWorldIsTheSimulationEverywhereAndDarkAtNight() {
        Scene.View v = new Scene.View(0, 0, 1.5, 0, 0, 0);
        BufferedImage day = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB), night = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Scene a = new Scene("Z", 1), b = new Scene("Z", 1);
        b.setNight(true);
        a.render(day, v, 1000, null, false);
        b.render(night, v, 1000, null, false);
        assertTrue(meanLuma(day) > 170, "pale by day: " + meanLuma(day));
        assertTrue(meanLuma(night) < 60, "dark at night: " + meanLuma(night));
    }

    private static double meanLuma(BufferedImage img) {
        long sum = 0;
        int[] px = Scene.pixels(img);
        for (int c : px) sum += (66 * (c >> 16 & 0xff) + 129 * (c >> 8 & 0xff) + 25 * (c & 0xff) + 128 >> 8) + 16;
        return (double) sum / px.length;
    }
}
