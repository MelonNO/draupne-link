package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static de.droiddrone.common.MotionScenes.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The motion detector in twenty minutes of wind in the trees.
 * One of MotionDetectorTest's long scenes, in a class of its own so that the build's forks share them
 * (MotionDetectorTest says why).
 */
class MotionDetectorLongWindTest {
    @Test
    void windInTheTreesForTwentyMinutesGivesNoAlarm() {
        SyntheticScene scene = windyWoods(11);
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        Run r = run(scene, d, 0, 20 * 60, null);
        assertEquals(Set.of(), r.ids, "false alarms in 20 minutes of wind");
        assertTrue(d.tracksMade() >= 10, "the wind made candidates for the judges: " + d.tracksMade());
    }
}
