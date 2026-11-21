package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-058 (the bench's first remote update in the emulator, 2026-11-21): the phone reports its update state every half
 * second for 30 s. A FAILED from the try before ("Install unknown apps" not allowed) was taken as the answer to the
 * next UPDATE PHONE APP - pressed once the pilot had allowed it - before the request had even gone out: the PC gave
 * up and sent nothing.
 */
class RemoteUpdateAnswerTest {
    @Test
    void aReportBeforeTheRequestCouldReachThePhoneIsNoAnswer() {
        assertFalse(MainWindow.updateAnswered(10_000, 0, 50), "nothing sent yet: the last try's report is no answer");
        assertFalse(MainWindow.updateAnswered(10_100, 10_000, 50), "100 ms after the request: still the old report");
        assertTrue(MainWindow.updateAnswered(10_000 + MainWindow.UPDATE_ANSWER_MIN_MS, 10_000, 50), "after the request had time to arrive");
        assertFalse(MainWindow.updateAnswered(10_500, 10_000, 600), "a slow way (600 ms ping): the answer takes longer");
        assertTrue(MainWindow.updateAnswered(10_900, 10_000, 600));
        assertTrue(MainWindow.updateAnswered(10_400, 10_000, -1), "an unknown ping counts as none");
    }
}
