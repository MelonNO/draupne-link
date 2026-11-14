package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static de.droiddrone.common.MotionScenes.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The motion detector at the wood's edge in the gale, without colour.
 * One of MotionDetectorTest's long scenes, in a class of its own so that the build's forks share them
 * (MotionDetectorTest says why).
 */
class MotionDetectorEdgeWindTest {
    /** The wood's edge in the gale for twenty minutes, without colour: nothing - the growing test must not take leaves. */
    @Test
    void theWoodsEdgeInTheWindGivesNoAlarmWithoutColour() {
        for (int sens = MotionDetector.LOW; sens <= MotionDetector.HIGH; sens++) {
            Run r = runGrey(woodsEdge(23 + sens), new MotionDetector(sens), 0, sens == MotionDetector.NORMAL ? 1200 : 300);
            assertEquals(Set.of(), r.ids, "sensitivity " + sens + ": false alarms " + r.reportsPerId);
        }
    }
}
