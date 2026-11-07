package de.droiddrone.pc.overwatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The alert card's life on the PC (1.0.47): alert, resends, dismiss, the motion's end, the shrink to a badge. */
class AlertCardTest {

    @Test
    void anAlertOpensTheCardOnceAndItsResendsAreNoNews() {
        AlertCard c = new AlertCard();
        assertEquals(AlertCard.Shown.HIDDEN, c.shown(0));
        assertTrue(c.onAlert(1, 0, 1000));
        assertFalse(c.onAlert(1, 0, 2000), "the resent alert (the ACK was lost) makes no second sound");
        assertEquals(AlertCard.Shown.CARD, c.shown(2000));
        assertTrue(c.previewWanted(2000));
    }

    @Test
    void notAnsweredTheCardKeepsItsPictureNinetySecondsAfterTheEndThenShrinksToABadge() {
        AlertCard c = new AlertCard();
        c.onAlert(4, 0, 0);
        c.onEnded(60_000);
        assertTrue(c.ended(60_000));
        assertFalse(c.previewWanted(60_000), "no preview once the motion ended");
        assertEquals(AlertCard.Shown.CARD, c.shown(60_000 + AlertCard.SHRINK_AFTER_MS - 1));
        assertEquals(AlertCard.Shown.BADGE, c.shown(60_000 + AlertCard.SHRINK_AFTER_MS));
        c.closeBadge();
        assertEquals(AlertCard.Shown.HIDDEN, c.shown(60_000 + AlertCard.SHRINK_AFTER_MS));
        assertTrue(c.onAlert(5, 0, 200_000), "the next event is news again");
        assertEquals(AlertCard.Shown.CARD, c.shown(200_000));
    }

    @Test
    void aDismissClosesTheCardAndStopsThePreviewUntilTheDroneAlertsAgain() {
        AlertCard c = new AlertCard();
        c.onAlert(2, 0, 0);
        c.dismiss(5000);
        assertEquals(AlertCard.Shown.HIDDEN, c.shown(5000));
        assertFalse(c.previewWanted(5000));
        assertTrue(c.dismissed(5000 + AlertCard.DISMISS_CAP_MS - 1));
        assertFalse(c.dismissed(5000 + AlertCard.DISMISS_CAP_MS), "never silenced longer than 10 min");
        c.onHeard(5000 + AlertCard.DISMISS_CAP_MS); // still moving: the phone says so
        assertFalse(c.onAlert(2, 0, 5000 + AlertCard.DISMISS_CAP_MS), "a stray resend of the old alert stays quiet");
        assertTrue(c.onAlert(2, 1, 5000 + AlertCard.DISMISS_CAP_MS + 10), "the phone's alert after its 10 min is news");
        assertEquals(AlertCard.Shown.CARD, c.shown(5000 + AlertCard.DISMISS_CAP_MS + 10));
    }

    @Test
    void aDismissedEventThatEndsShowsNothingMore() {
        AlertCard c = new AlertCard();
        c.onAlert(3, 0, 0);
        c.dismiss(1000);
        c.onEnded(40_000);
        assertEquals(AlertCard.Shown.HIDDEN, c.shown(40_000));
        assertEquals(AlertCard.Shown.HIDDEN, c.shown(40_000 + AlertCard.SHRINK_AFTER_MS + 1), "no badge either: the pilot saw it");
        assertFalse(c.dismissed(40_000), "the event is over: nothing left to be silent about");
    }

    @Test
    void anEventNotHeardOfForTwoMinutesCountsAsEnded() {
        AlertCard c = new AlertCard();
        c.onAlert(9, 0, 0);
        c.onHeard(10_000);
        assertFalse(c.ended(10_000 + AlertCard.LOST_MS - 1));
        assertTrue(c.ended(10_000 + AlertCard.LOST_MS));
        assertEquals(10_000, c.endedAt(10_000 + AlertCard.LOST_MS), "ended when it was last heard");
        assertEquals(AlertCard.Shown.BADGE, c.shown(10_000 + AlertCard.SHRINK_AFTER_MS + AlertCard.LOST_MS));
    }
}
