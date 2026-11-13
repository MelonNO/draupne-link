package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whose the phone's camera is, a video's or overwatch's (pc.38, M19; moved out of the phone's Udp). Written from the
 * code as it stood before the move - Udp.java of phone app pc.52 (commit 2047470), where it was two counters,
 * cameraOwner and overwatchCameraOwner = -1 (line 343); each test names the lines it pins.
 */
class CameraOwnerTest {
    /** Lines 343 and 1030-1031: before any overwatch a video start finds nothing of overwatch's on the camera. */
    @Test
    void atFirstTheCameraIsNotOverwatchs() {
        CameraOwner c = new CameraOwner();
        assertFalse(c.isOverwatchs());
        assertFalse(c.videoTakes(), "nothing of overwatch's to take off the camera");
        assertFalse(c.videoTakes());
        assertFalse(c.isOverwatchs());
    }

    /** Lines 1523 (overwatchCameraOwner = ++cameraOwner) and 1509-1513 (its own off: overwatchCameraOwner = -1). */
    @Test
    void overwatchHasTheCameraFromItsOnToItsOff() {
        CameraOwner c = new CameraOwner();
        c.overwatchTakes();
        assertTrue(c.isOverwatchs(), "its event video and its camera-again act (lines 1551, 1587)");
        assertTrue(c.isOverwatchs(), "asking changes nothing");
        c.overwatchLetsGo();
        assertFalse(c.isOverwatchs());
        assertFalse(c.videoTakes(), "closed by overwatch itself: the next video start finds nothing of its on the camera");
    }

    /**
     * Lines 1030-1035 and 1509-1512: a pilot's StartVideo (or an observer's) while overwatch still has the camera takes
     * it - what overwatch set on it goes - and overwatch's late off, camera-again and event video then leave it alone.
     */
    @Test
    void aVideoStartTakesTheCameraFromOverwatchAndItsLateOffLeavesItAlone() {
        CameraOwner c = new CameraOwner();
        c.overwatchTakes();
        assertTrue(c.videoTakes(), "it was overwatch's: watch-only and its recorder's target go");
        assertFalse(c.isOverwatchs(), "the late camera off: 'left as it is'");
        assertFalse(c.videoTakes(), "the next start (a restart) has nothing of overwatch's to undo");
        assertFalse(c.isOverwatchs());
    }

    /** Lines 1142 and 1162: the video's dead-man leaves overwatch's camera alone, and a camera that is nobody's or a video's not. */
    @Test
    void afterAVideoTookItOnlyANewOverwatchMakesItOverwatchsAgain() {
        CameraOwner c = new CameraOwner();
        c.overwatchTakes();
        c.videoTakes();
        for (int i = 0; i < 1000; i++) {
            assertFalse(c.videoTakes());
            assertFalse(c.isOverwatchs(), "the old number never comes round");
        }
        c.overwatchTakes();
        assertTrue(c.isOverwatchs());
        c.overwatchTakes();
        assertTrue(c.isOverwatchs(), "overwatch begun again without an off between: still its");
        assertTrue(c.videoTakes());
        assertFalse(c.isOverwatchs());
    }

    /** Lines 1509-1513 then 1523: an off that found the camera taken sets nothing; the next overwatch takes it as any other. */
    @Test
    void overwatchAfterALateOffTakesTheCameraAgain() {
        CameraOwner c = new CameraOwner();
        c.overwatchTakes();
        assertTrue(c.videoTakes());
        // the late off: isOverwatchs() is false, so overwatchLetsGo() is not called (Udp returns)
        assertFalse(c.isOverwatchs());
        c.overwatchTakes();
        assertTrue(c.isOverwatchs());
        c.overwatchLetsGo();
        assertFalse(c.isOverwatchs());
        c.overwatchTakes();
        assertTrue(c.isOverwatchs(), "and after its own off too");
    }

    /** The two counters as they were (lines 343, 1030-1031, 1509, 1513, 1523), step by step against the class. */
    @Test
    void everySequenceAnswersAsTheOldCountersDid() {
        java.util.Random random = new java.util.Random(38);
        for (int run = 0; run < 200; run++) {
            CameraOwner c = new CameraOwner();
            int cameraOwner = 0, overwatchCameraOwner = -1;
            for (int i = 0; i < 60; i++) {
                switch (random.nextInt(4)) {
                    case 0: { // startVideoStreamNow
                        boolean wasOverwatch = overwatchCameraOwner == cameraOwner;
                        cameraOwner++;
                        assertEquals(wasOverwatch, c.videoTakes());
                        break;
                    }
                    case 1: // overwatchCamera(true)
                        overwatchCameraOwner = ++cameraOwner;
                        c.overwatchTakes();
                        break;
                    case 2: // overwatchCamera(false)
                        if (overwatchCameraOwner != cameraOwner) {
                            assertFalse(c.isOverwatchs());
                        } else {
                            assertTrue(c.isOverwatchs());
                            overwatchCameraOwner = -1;
                            c.overwatchLetsGo();
                        }
                        break;
                    default: // overwatchCameraAgain, overwatchEventVideoNow, pcSilentTick: only asked
                        break;
                }
                assertEquals(overwatchCameraOwner == cameraOwner, c.isOverwatchs(), "run " + run + ", step " + i);
            }
        }
    }
}
