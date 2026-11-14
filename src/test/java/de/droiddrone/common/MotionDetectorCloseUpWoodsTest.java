package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static de.droiddrone.common.MotionScenes.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The motion detector's close-up rule (1.0.56) in the windy woods and at the wood's edge.
 * One of MotionDetectorTest's long scenes, in a class of its own so that the build's forks share them
 * (MotionDetectorTest says why).
 */
class MotionDetectorCloseUpWoodsTest {
    /** The windy woods and the wood's edge: the close-up rule takes nothing there either. */
    @Test
    void theWoodsAreNeverTakenForSomethingClose() {
        for (int sens = MotionDetector.LOW; sens <= MotionDetector.HIGH; sens++) {
            MotionDetector d = new MotionDetector(sens);
            run(woodsEdge(23 + sens), d, 0, 300, null);
            assertEquals(0, d.nearMade, "sensitivity " + sens);
        }
    }
}
