package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static de.droiddrone.common.CameraWatchdog.Action.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * pc.49: 2026-11-11, the pilot: "if I walk in front of it 5 m away while connected I get the movement box, however if
 * I do the same in overwatch I get no warning" - its check-in said it saw nothing. Overwatch's camera is opened again.
 */
class CameraWatchdogTest {
    private final CameraWatchdog w = new CameraWatchdog();

    @Test
    void fiveSecondsWithoutAPictureOpensTheCameraAgainAndSaysSoOnce() {
        assertEquals(NONE, w.tick(0, false, false));
        assertEquals(NONE, w.tick(4999, false, false));
        assertEquals(REOPEN_AND_SAY_BLIND, w.tick(5000, false, false));
        assertEquals(1, w.reopens());
        assertEquals(NONE, w.tick(5000 + 14_999, false, false), "a camera takes its time to open");
        assertEquals(REOPEN, w.tick(5000 + 15_000, false, false), "again, not said again");
        assertEquals(NONE, w.tick(20_000 + 29_999, false, false));
        assertEquals(REOPEN, w.tick(20_000 + 30_000, false, false), "then 30 s");
        assertEquals(REOPEN, w.tick(50_000 + 60_000, false, false), "60 s");
        assertEquals(REOPEN, w.tick(110_000 + 120_000, false, false), "120 s");
        assertEquals(REOPEN, w.tick(230_000 + 120_000, false, false), "and no longer than that");
        assertEquals(350_000, w.blindFor(350_000));
    }

    @Test
    void picturesAgainAreSaidOnceAndStartItOver() {
        w.tick(0, false, false);
        w.tick(5000, false, false);
        assertEquals(SAY_BACK, w.tick(7000, true, false));
        assertEquals(0, w.reopens());
        assertEquals(NONE, w.tick(7100, true, false));
        assertEquals(0, w.blindFor(7100));
        assertEquals(NONE, w.tick(8000, false, false));
        assertEquals(REOPEN_AND_SAY_BLIND, w.tick(13_000, false, false), "blind again: said again");
    }

    /**
     * pc.50: an event's session wasn't counted at all - one that recorded without the picture (pc.47), or never started,
     * left the phone blind for the whole event and nobody was told. Now the PC is told; the camera is not opened again
     * under a recording (the caller decides what to do).
     */
    @Test
    void blindInAnEventIsSaidButTheCameraIsNotOpenedAgainUnderIt() {
        assertEquals(NONE, w.tick(0, true, false));
        assertEquals(NONE, w.tick(1000, false, true), "the event's session starts");
        assertEquals(NONE, w.tick(5999, false, true));
        assertEquals(SAY_BLIND, w.tick(6000, false, true), "5 s of it without a picture: the PC is told");
        assertEquals(0, w.reopens(), "not opened again under the recording");
        assertEquals(NONE, w.tick(30_000, false, true), "said once");
        assertEquals(NONE, w.tick(31_000, false, false), "the event over: the watch's session takes its moment - counted from here");
        assertEquals(NONE, w.tick(35_999, false, false));
        assertEquals(REOPEN, w.tick(36_000, false, false), "still blind: opened again now, not said again");
        assertEquals(SAY_BACK, w.tick(37_000, true, false));
    }

    @Test
    void picturesAgainInAnEventAreSaidToo() {
        w.tick(0, false, true);
        assertEquals(SAY_BLIND, w.tick(5000, false, true));
        assertEquals(SAY_BACK, w.tick(6000, true, true));
        assertEquals(NONE, w.tick(7000, true, true));
        assertEquals(NONE, w.tick(8000, false, true));
        assertEquals(SAY_BLIND, w.tick(13_000, false, true), "blind again: said again");
    }

    @Test
    void anEventsStartAndEndStartTheCountOver() {
        w.tick(0, false, false);
        assertEquals(NONE, w.tick(4000, false, true), "4 s blind, then an event's session: it has its own 5 s");
        assertEquals(NONE, w.tick(8999, false, true));
        assertEquals(SAY_BLIND, w.tick(9000, false, true));
        w.reset();
        w.tick(0, false, true);
        assertEquals(NONE, w.tick(4000, false, false), "and so has the watch's session after an event");
        assertEquals(NONE, w.tick(8999, false, false));
        assertEquals(REOPEN_AND_SAY_BLIND, w.tick(9000, false, false));
    }

    @Test
    void aNewWatchForgetsTheLastOne() {
        w.tick(0, false, false);
        w.tick(5000, false, false);
        w.reset();
        assertEquals(NONE, w.tick(6000, true, false), "nothing was said in this watch");
        assertEquals(NONE, w.tick(7000, false, false));
        assertEquals(REOPEN_AND_SAY_BLIND, w.tick(12_000, false, false));
    }

    @Test
    void theReasonsReadShort() {
        assertEquals("camera taken away", OverwatchProtocol.describeBlind(OverwatchProtocol.BLIND_LOST));
        assertEquals("no pictures", OverwatchProtocol.describeBlind(99));
        byte[] s = OverwatchProtocol.status(7, OverwatchProtocol.STATE_BLIND, OverwatchProtocol.BLIND_SESSION, 80, 300);
        assertArrayEquals(new int[]{OverwatchProtocol.STATE_BLIND, OverwatchProtocol.BLIND_SESSION, 80, 300}, OverwatchProtocol.readStatus(s, s.length));
    }
}
