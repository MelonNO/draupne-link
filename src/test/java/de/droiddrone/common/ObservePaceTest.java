package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the phone's observation does when (moved out of its Udp). Written from the code as it stood before the move -
 * Udp.java of phone app pc.52 (commit 2047470), whose fields observeApplied, observeStartTriedMs, observeStep(At),
 * observeDetectionAt, observeRecordAt, observeFcInfoAt, observeKeyFrameAt and pilotRecordAt this class now holds; each
 * test names the lines it pins. Times are the phone's monotonic clock: far from 0, as on a phone that has been up.
 */
class ObservePaceTest {
    private static final long T0 = 500_000;
    /** VIDEO_FIRST_FRAME_MS in the phone's Udp. */
    private static final long FIRST_FRAME_MS = 4000;
    private static final Observe.Request GRID = new Observe.Request("k", 640, 360, 15, 1000, Observe.FLAG_AUDIO, 0);

    private static Observe.Request picture(int w, int h, int fps, int kbps) {
        return new Observe.Request("k", w, h, fps, kbps, 0, 0);
    }

    /** Lines 2720-2725: observeApplied == null, or its width, height, fps or maxKbps differ - nothing else of the request. */
    @Test
    void thePictureIsAppliedWhenItsSizeRateOrBitrateChanges() {
        ObservePace p = new ObservePace();
        assertTrue(p.picture(GRID, T0), "the first");
        assertFalse(p.picture(GRID, T0 + 100));
        assertFalse(p.picture(picture(640, 360, 15, 1000), T0 + 200), "another request object, the same picture");
        assertFalse(p.picture(new Observe.Request("other", 640, 360, 15, 1000, Observe.FLAG_VIDEO_CONFIG, 3), T0 + 300),
                "the key, the flags and the detection are no part of the picture");
        assertTrue(p.picture(picture(1280, 360, 15, 1000), T0 + 400), "the width");
        assertTrue(p.picture(picture(1280, 720, 15, 1000), T0 + 500), "the height");
        assertTrue(p.picture(picture(1280, 720, 30, 1000), T0 + 600), "the frame rate");
        assertTrue(p.picture(picture(1280, 720, 30, 4000), T0 + 700), "the bitrate");
        assertFalse(p.picture(picture(1280, 720, 30, 4000), T0 + 800));
        assertTrue(p.picture(GRID, T0 + 900), "back to the grid's: applied again");
    }

    /** Lines 2691-2694: the last observer gone, observeApplied = null - the next observation applies its picture anew. */
    @Test
    void afterTheLastObserverTheSamePictureIsAppliedAgain() {
        ObservePace p = new ObservePace();
        assertTrue(p.picture(GRID, T0));
        assertFalse(p.picture(GRID, T0 + 100));
        p.end();
        assertTrue(p.picture(GRID, T0 + 200));
    }

    /**
     * Lines 2723 and 2726-2727: a camera that stands still is started again when now - observeStartTriedMs >
     * VIDEO_FIRST_FRAME_MS - counted from the picture's apply, then from each such start.
     */
    @Test
    void aStoppedCameraIsStartedAgainMoreThanFourSecondsAfterTheLastTry() {
        ObservePace p = new ObservePace();
        p.picture(GRID, T0);
        assertFalse(p.startAgain(T0 + 100, FIRST_FRAME_MS), "the apply's own start gets its time");
        assertFalse(p.startAgain(T0 + FIRST_FRAME_MS, FIRST_FRAME_MS), "4 s to the ms: not yet");
        assertTrue(p.startAgain(T0 + FIRST_FRAME_MS + 1, FIRST_FRAME_MS));
        assertFalse(p.startAgain(T0 + FIRST_FRAME_MS + 2, FIRST_FRAME_MS), "counted from that start");
        assertFalse(p.startAgain(T0 + 2 * FIRST_FRAME_MS + 1, FIRST_FRAME_MS));
        assertTrue(p.startAgain(T0 + 2 * FIRST_FRAME_MS + 2, FIRST_FRAME_MS));
        assertTrue(p.picture(picture(1280, 720, 30, 4000), T0 + 9000), "a new picture ...");
        assertFalse(p.startAgain(T0 + 9000 + FIRST_FRAME_MS, FIRST_FRAME_MS), "... counts anew too");
        assertTrue(p.startAgain(T0 + 9001 + FIRST_FRAME_MS, FIRST_FRAME_MS));
    }

    /** Lines 2726-2727: a start that is not due leaves the count alone (the time is set only inside the branch). */
    @Test
    void askingForAStartThatIsNotDueChangesNothing() {
        ObservePace p = new ObservePace();
        p.picture(GRID, T0);
        for (long t = T0; t <= T0 + FIRST_FRAME_MS; t += 100) assertFalse(p.startAgain(t, FIRST_FRAME_MS));
        assertTrue(p.startAgain(T0 + FIRST_FRAME_MS + 1, FIRST_FRAME_MS));
    }

    /** Lines 2734-2739: step != observeStep || now - observeStepAt >= 10_000 sets the encoder's step. */
    @Test
    void theEncodersStepIsSetWhenItChangesAndAgainEveryTenSeconds() {
        ObservePace p = new ObservePace();
        assertTrue(p.step(3, T0), "the first (observeStep was -1)");
        assertFalse(p.step(3, T0 + 100));
        assertFalse(p.step(3, T0 + ObservePace.STEP_AGAIN_MS - 1));
        assertTrue(p.step(3, T0 + ObservePace.STEP_AGAIN_MS), "10 s: again, back up after the phone's own brake");
        assertFalse(p.step(3, T0 + 2 * ObservePace.STEP_AGAIN_MS - 1));
        assertTrue(p.step(3, T0 + 2 * ObservePace.STEP_AGAIN_MS));
        assertTrue(p.step(5, T0 + 20_100), "another step: at once");
        assertTrue(p.step(3, T0 + 20_200), "and back: at once");
        assertFalse(p.step(3, T0 + 30_199), "counted from when it was last set");
        assertTrue(p.step(3, T0 + 30_200));
        assertEquals(10_000, ObservePace.STEP_AGAIN_MS);
    }

    /** Lines 2707-2709: a new observation (observing false to true) sets observeStep = -1 - its step is set at that tick. */
    @Test
    void aNewObservationSetsTheStepAtOnce() {
        ObservePace p = new ObservePace();
        assertTrue(p.step(3, T0));
        assertFalse(p.step(3, T0 + 100));
        p.begin();
        assertTrue(p.step(3, T0 + 200), "the same step, set anew for the new observers");
        assertFalse(p.step(3, T0 + 300));
        p.end();
        assertFalse(p.step(3, T0 + 400), "the last observer's going resets the picture only (line 2694)");
    }

    /** Lines 2740-2743: want.detection > 0 && now - observeDetectionAt >= 1000. */
    @Test
    void theDetectionIsAskedForOnceASecondWhileAnObserverWantsIt() {
        ObservePace p = new ObservePace();
        assertFalse(p.detection(0, T0), "nobody wants it");
        assertTrue(p.detection(2, T0));
        assertFalse(p.detection(2, T0 + 999));
        assertTrue(p.detection(2, T0 + 1000));
        assertFalse(p.detection(0, T0 + 5000), "not wanted: not asked for ...");
        assertTrue(p.detection(1, T0 + 5000), "... and not counted either: due at once when wanted again");
        assertFalse(p.detection(3, T0 + 5100), "a higher sensitivity waits for its second too");
        assertEquals(1000, ObservePace.DETECTION_EVERY_MS);
    }

    /** Lines 2744-2747: now - observeRecordAt >= 200; and 2793-2794: the pilot's, on a count of its own (pilotRecordAt). */
    @Test
    void theObserverRecordGoesFiveTimesASecondToObserversAndToAPilotEachOnItsOwnCount() {
        ObservePace p = new ObservePace();
        assertTrue(p.record(T0));
        assertFalse(p.record(T0 + 199));
        assertTrue(p.record(T0 + 200));
        assertTrue(p.pilotRecord(T0 + 250), "the pilot's count is its own");
        assertFalse(p.record(T0 + 250));
        assertTrue(p.record(T0 + 400));
        assertFalse(p.pilotRecord(T0 + 449));
        assertTrue(p.pilotRecord(T0 + 450));
        assertFalse(p.record(T0 + 450));
        assertEquals(200, ObservePace.RECORD_EVERY_MS);
    }

    /** Lines 2744-2745: observeRecordAt = now - the next is counted from when this one went, not from when it was due. */
    @Test
    void aLateTickShiftsTheNextOnes() {
        ObservePace p = new ObservePace();
        assertTrue(p.record(T0));
        assertTrue(p.record(T0 + 350), "late");
        assertFalse(p.record(T0 + 400), "no catching up");
        assertFalse(p.record(T0 + 549));
        assertTrue(p.record(T0 + 550));
    }

    /**
     * Lines 2751-2758 (pc.43): due when now - observeFcInfoAt >= 1000, but observeFcInfoAt = now only with an FC known -
     * without one it is looked for again at every tick.
     */
    @Test
    void theFcInfoGoesOnceASecondAndStaysDueWhileNoFcIsKnown() {
        ObservePace p = new ObservePace();
        assertTrue(p.fcInfoDue(T0));
        assertTrue(p.fcInfoDue(T0 + 100), "no FC yet: still due");
        assertTrue(p.fcInfoDue(T0 + 200));
        p.fcInfoKnown(T0 + 200);
        assertFalse(p.fcInfoDue(T0 + 300));
        assertFalse(p.fcInfoDue(T0 + 1199));
        assertTrue(p.fcInfoDue(T0 + 1200));
        assertTrue(p.fcInfoDue(T0 + 1300), "asking changes nothing");
        assertEquals(1000, ObservePace.FC_INFO_EVERY_MS);
    }

    /** Lines 2594-2597: video && now - observeKeyFrameAt >= 1000 - a newcomer's key frame, one a second at most. */
    @Test
    void aNewcomersKeyFrameIsAskedForOnceASecondAtMost() {
        ObservePace p = new ObservePace();
        assertTrue(p.keyFrame(T0));
        assertFalse(p.keyFrame(T0 + 10), "a second observer's config request right after: the same key frame serves it");
        assertFalse(p.keyFrame(T0 + 999));
        assertTrue(p.keyFrame(T0 + 1000));
        assertEquals(1000, ObservePace.KEY_FRAME_EVERY_MS);
    }

    /** The counts are apart: none moves another's (each was a field of its own). */
    @Test
    void theCountsDoNotTouchEachOther() {
        ObservePace p = new ObservePace();
        assertTrue(p.record(T0));
        assertTrue(p.detection(1, T0));
        assertTrue(p.step(3, T0));
        assertTrue(p.keyFrame(T0));
        assertTrue(p.pilotRecord(T0));
        assertTrue(p.fcInfoDue(T0));
        p.fcInfoKnown(T0);
        assertTrue(p.picture(GRID, T0));
        p.begin();
        p.end();
        assertFalse(p.record(T0 + 100));
        assertFalse(p.detection(1, T0 + 100));
        assertFalse(p.keyFrame(T0 + 100));
        assertFalse(p.pilotRecord(T0 + 100));
        assertFalse(p.fcInfoDue(T0 + 100));
        assertFalse(p.startAgain(T0 + 100, FIRST_FRAME_MS), "end() leaves the start's count alone");
    }

    /**
     * Udp.observeTick lines 2706-2758 run for a minute as the telemetry thread runs it, every 0.1 s, the grid's picture
     * wanted all along with the detection on and the FC known: how often each thing is done.
     */
    @Test
    void aMinuteOfTheTickDoesEachThingAtItsRate() {
        ObservePace p = new ObservePace();
        Observe.Request want = new Observe.Request("k", 640, 360, 15, 1000, 0, 2);
        int pictures = 0, steps = 0, detections = 0, records = 0, infos = 0;
        p.begin();
        for (long t = T0; t < T0 + 60_000; t += 100) {
            if (p.picture(want, t)) pictures++;
            if (p.step(Observe.stepFor(want.maxKbps), t)) steps++;
            if (p.detection(want.detection, t)) detections++;
            if (p.record(t)) records++;
            if (p.fcInfoDue(t)) {
                p.fcInfoKnown(t);
                infos++;
            }
        }
        assertEquals(1, pictures, "applied once");
        assertEquals(6, steps, "at the start and every 10 s");
        assertEquals(60, detections, "once a second");
        assertEquals(300, records, "five a second");
        assertEquals(60, infos, "once a second");
    }
}
