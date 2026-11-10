package de.droiddrone.pc.fleet;

import de.droiddrone.common.MotionDetector;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-10, the pilot: the motion detection found neither the infantry on patrol nor the sentry by the camouflaged
 * BTR. Seen as a tour drone sees them (on the ground, BACK_M back), through the motion detection as the virtual drone
 * runs it (320 x 180, eight pictures a second, NORMAL): boxes in most pictures while they walk. Before, the patrol had a
 * box in an eighth of its pictures, the sentry in none: they walked in front of real, dark treelines and the BTR's net,
 * camouflage on camouflage a few pixels wide. Now the treelines behind are the memory's light ghosts and the sentry
 * walks beside the hide; the detection itself is as it was.
 */
class TourSceneDetectionTest {
    @Test
    void thePatrolIsFound() {
        double share = boxed(ShowScene.Kind.PATROL, 10, 26);
        assertTrue(share > 0.8, "the patrol walking along the treeline: boxes in " + share + " of the pictures");
    }

    @Test
    void theSentryByTheHideIsFound() {
        // his beat: 10 m, 8.3 s each way, 3 s standing at each end; a minute of it
        double share = boxed(ShowScene.Kind.HIDE, 10, 70);
        assertTrue(share > 0.6, "the sentry walking his beat by the BTR: boxes in " + share + " of the pictures");
    }

    /** The share of pictures from fromS to toS with a box, the detector running from 0. */
    private static double boxed(ShowScene.Kind k, double fromS, double toS) {
        ShowScene s = new ShowScene(k, 0, 0, 30, 0);
        Scene.View v = new Scene.View(0, 0, 0, 0, 0, 30);
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        BufferedImage img = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        int pictures = 0, withBox = 0;
        for (long t = 0; t < toS * 1000; t += 125) {
            s.render(img, v, t, true);
            int[] size = new int[2];
            byte[] y = Scene.luma(Scene.pixels(img), 320, 180, 320, size);
            MotionDetector.Result r = d.process(y, size[0], size[1], null, null, 1_000_000 + t);
            if (t < fromS * 1000) continue;
            pictures++;
            if (!r.detections.isEmpty()) withBox++;
        }
        return withBox / (double) pictures;
    }
}
