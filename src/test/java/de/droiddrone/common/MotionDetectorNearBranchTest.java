package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static de.droiddrone.common.MotionScenes.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The motion detector's close-up rule (1.0.56) with a branch right in front of the camera.
 * One of MotionDetectorTest's long scenes, in a class of its own so that the build's forks share them
 * (MotionDetectorTest says why).
 */
class MotionDetectorNearBranchTest {
    /**
     * A leafy branch right in front of the camera, filling much of the picture and swaying in gusts, the camera shaking:
     * the close-up rule (1.0.56) never takes it - it asks for a place that was calm before, and leaves flicker in place.
     * (The travel rules, made for trees 30 m off, do raise alarms on a branch this close: they did before 1.0.56 as well
     * - about as many in these scenes. Not solved here: keep foliage out of the picture's near part.)
     */
    @Test
    void aBranchCloseToTheCameraIsNeverTakenForSomethingClose() {
        for (long seed : new long[]{51, 53}) {
            for (int sens = MotionDetector.NORMAL; sens <= MotionDetector.HIGH; sens++) {
                SyntheticScene scene = new SyntheticScene(W, H, seed);
                scene.patches.add(new SyntheticScene.Patch(110, 80, 110, 70, 20f, 0.5f, 0.7f));
                scene.patches.add(new SyntheticScene.Patch(280, 30, 50, 40, 24f, 0.9f, 2.3f));
                scene.shake = 3;
                MotionDetector d = new MotionDetector(sens);
                run(scene, d, 0, 180, null);
                assertEquals(0, d.nearMade, "seed " + seed + ", sensitivity " + sens + ": taken for something close");
            }
        }
    }
}
