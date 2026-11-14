package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static de.droiddrone.common.MotionScenes.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The motion detector on HIGH in the gale, the swaying leaves boxed LOW or OFF (1.0.58).
 * One of MotionDetectorTest's long scenes, in a class of its own so that the build's forks share them
 * (MotionDetectorTest says why).
 */
class MotionDetectorLeavesBoxedTest {
    /**
     * 1.0.58: the price of HIGH, and what areas are for. The camera still on the ground in the gale, no one there: with
     * the foliage boxed LOW or OFF nothing in five minutes; the same unboxed raises alarms - leaves a gust starts after a
     * calm travel like someone walking for a few seconds.
     */
    @Test
    void onHighTheSwayingLeavesBoxedLowOrOffGiveNoAlarm() {
        int unboxed = 0;
        for (long seed : new long[]{5, 17}) {
            for (int level : new int[]{MotionDetector.LOW, MotionDetector.OFF}) {
                SyntheticScene scene = windyWoods(seed);
                scene.shake = 0;
                MotionDetector d = new MotionDetector(MotionDetector.HIGH);
                d.setAreas(foliage(scene, level));
                Run r = run(scene, d, 0, 300, null);
                assertEquals(Set.of(), r.ids, "seed " + seed + ", leaves boxed " + DetectionAreas.word(level) + ": false alarms " + r.reportsPerId);
            }
            SyntheticScene scene = windyWoods(seed);
            scene.shake = 0;
            unboxed += run(scene, new MotionDetector(MotionDetector.HIGH), 0, 300, null).ids.size();
        }
        assertTrue(unboxed > 0, "unboxed, HIGH finds the leaves a gust starts (what the areas are for)");
    }
}
