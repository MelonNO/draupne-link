package de.droiddrone.pc.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AudioPtsTest {
    private static final double FRAME = AudioConfig.DEFAULT.frameMs(); // 21.33 ms

    @Test
    void framesAreCountedNotStampedWithTheirArrival() {
        AudioPts p = new AudioPts(FRAME);
        assertEquals(1000, p.next(1000));
        // a bunch: three frames arrive in the same ms after a short delay - they keep their place in the sound
        assertEquals(1021, p.next(1060));
        assertEquals(1043, p.next(1060));
        assertEquals(1064, p.next(1061));
    }

    @Test
    void aPauseStartsTheCountAtTheArrivalAgain() {
        AudioPts p = new AudioPts(FRAME);
        p.next(0);
        p.next(21);
        // the drone armed for a minute: the sound comes back a minute later, where the video is
        assertEquals(60_000, p.next(60_000));
        assertEquals(60_021, p.next(60_030));
    }

    @Test
    void framesLostOnTheWayDontLeaveTheSoundBehindForGood() {
        AudioPts p = new AudioPts(FRAME);
        p.next(0);
        // 20 frames lost (427 ms): more than RESYNC_MS behind the arrivals - back on the video's clock
        assertEquals(448, p.next(448));
        // a few lost (a little behind, under RESYNC_MS): counted on, no jump
        assertEquals(469, p.next(560));
    }

    @Test
    void alwaysRising() {
        AudioPts p = new AudioPts(0.4); // frames shorter than the ms the muxer counts in
        long last = -1;
        for (int i = 0; i < 20; i++) {
            long pts = p.next(100);
            assertTrue(pts > last, "rising at " + i);
            last = pts;
        }
    }
}
