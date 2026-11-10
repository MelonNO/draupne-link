package de.droiddrone.pc.fleet;

import de.droiddrone.common.MotionDetector;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-10, the pilot: the motion detection found neither the infantry on patrol nor the sentry by the camouflaged
 * BTR - and making the backgrounds light is no fix, "real life does not have a white background". Seen as a tour drone
 * sees them (on the ground, BACK_M back), through the motion detection on HIGH as the virtual drone runs it (320 x 180, eight
 * pictures a second; the tour sets HIGH), with the real dark treeline behind them (REAL_M, as up to 1.0.57) and as the tour shows
 * them: boxes in most pictures while they walk. Up to 1.0.57 the patrol had a box in an eighth of its pictures and the
 * sentry crossing the BTR's front in none: camouflage on camouflage, averaged down to 160 px wide, was the trees (on
 * NORMAL still so; HIGH judges the full picture too).
 */
class TourSceneDetectionTest {
    /** Trees this near the action stay real (not the memory's ghosts): the treelines behind the men too. */
    static final double REAL_M = 12;

    @Test
    void thePatrolIsFoundInFrontOfTheRealTreeline() {
        double share = boxed(ShowScene.Kind.PATROL, REAL_M, 10, 26);
        assertTrue(share > 0.8, "the patrol walking along the treeline: boxes in " + share + " of the pictures");
    }

    @Test
    void theSentryCrossingTheHidesFrontIsFound() {
        // his beat: 12 m, 10 s each way, 3 s standing at each end; a minute of it
        double share = boxed(ShowScene.Kind.HIDE, REAL_M, 10, 70);
        assertTrue(share > 0.7, "the sentry walking his beat in front of the BTR: boxes in " + share + " of the pictures");
    }

    @Test
    void bothAreFoundAsTheTourShowsThem() {
        double patrol = boxed(ShowScene.Kind.PATROL, ShowScene.REAL_TREES_M, 10, 26);
        double sentry = boxed(ShowScene.Kind.HIDE, ShowScene.REAL_TREES_M, 10, 70);
        assertTrue(patrol > 0.8 && sentry > 0.7, "boxes: the patrol in " + patrol + ", the sentry in " + sentry + " of the pictures");
    }

    /** The share of pictures from fromS to toS with a box, the detector running from 0. */
    static double boxed(ShowScene.Kind k, double realTreesM, double fromS, double toS) {
        ShowScene s = new ShowScene(k, 0, 0, 30, 0, realTreesM);
        Scene.View v = new Scene.View(0, 0, 0, 0, 0, 30);
        MotionDetector d = new MotionDetector(MotionDetector.HIGH);
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
