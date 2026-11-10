package de.droiddrone.pc.overwatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** An overwatch clip as one video (2026-11-10, the pilot): the pictures before the alert, then the parts, one clock. */
class ClipTimelineTest {
    @Test
    void thePicturesBeforeTheAlertAndThePartsAreOneTimeline() {
        ClipTimeline t = new ClipTimeline(6, new long[]{60_000, 22_500});
        assertEquals(6 * ClipTimeline.PRE_MS + 82_500, t.totalMs(), "the whole clip's time includes the pictures");
        assertEquals(3_000, t.videoStartMs());
        assertEquals(0, t.at(0).picture(), "it starts with the first picture");
        assertEquals(1, t.at(ClipTimeline.PRE_MS).picture());
        assertEquals(5, t.at(2_999).picture());
        ClipTimeline.At video = t.at(3_000);
        assertEquals(-1, video.picture());
        assertEquals(0, video.part());
        assertEquals(0, video.offsetMs(), "the video follows the last picture");
        ClipTimeline.At second = t.at(3_000 + 60_000 + 1_500);
        assertEquals(1, second.part(), "into the second part");
        assertEquals(1_500, second.offsetMs());
        assertTrue(t.at(t.totalMs()).end());
        assertFalse(t.at(t.totalMs() - 1).end());
        assertEquals(0, t.at(-50).picture(), "a seek before the start: the start");
    }

    @Test
    void onlyPicturesOrOnlyVideoStillPlay() {
        ClipTimeline pictures = new ClipTimeline(4, new long[0]);
        assertEquals(2_000, pictures.totalMs());
        assertEquals(3, pictures.at(1_999).picture());
        assertTrue(pictures.at(2_000).end());
        ClipTimeline video = new ClipTimeline(0, new long[]{10_000});
        assertEquals(0, video.at(0).part());
        assertEquals(10_000, video.totalMs());
        assertEquals(0, new ClipTimeline(0, new long[0]).totalMs(), "nothing: nothing to play");
    }
}
