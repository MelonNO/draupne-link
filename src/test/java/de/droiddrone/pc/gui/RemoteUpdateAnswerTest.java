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

    /**
     * F-082 (the bench's update test on Android 11, 2026-11-22): Android ended the phone app when "Install unknown
     * apps" was allowed, the link was lost while the update was asked, the phone said nothing - and the PC's log blamed
     * a phone app older than 2.3.0-pc.32, though it knew the phone ran 2.3.0-pc.57-beta.
     */
    @Test
    void noAnswerFromAPhoneAppThatTakesTheUpdateIsALostLinkNotAnOldApp() {
        String lost = "No answer from the phone (link lost) - press UPDATE PHONE APP again.";
        assertEquals(lost, MainWindow.noUpdateAnswer("2.3.0-pc.57-beta"));
        assertEquals(lost, MainWindow.noUpdateAnswer(MainWindow.REMOTE_UPDATE_MIN_PHONE), "pc.32 itself takes the update");
        assertEquals(lost, MainWindow.noUpdateAnswer("2.3.0-pc.55"));
        assertEquals(lost, MainWindow.noUpdateAnswer("2.4.0"));

        // the version not known, or older: the old words, as before
        String old = "No answer to the update: phone app older than 2.3.0-pc.32.";
        assertEquals(old, MainWindow.noUpdateAnswer(null));
        assertEquals(old, MainWindow.noUpdateAnswer(""));
        assertEquals(old, MainWindow.noUpdateAnswer("unknown"));
        assertEquals(old, MainWindow.noUpdateAnswer("2.3.0-pc.31"));
        assertEquals(old, MainWindow.noUpdateAnswer("2.3.0"));
    }
}
