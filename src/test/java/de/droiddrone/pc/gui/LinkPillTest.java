package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** M14 (1.0.49): the LINK card is green only while the phone is heard and passes the flight controller on. */
class LinkPillTest {
    @Test
    void stalledAndFcLostAreNotLinkReady() {
        assertEquals("LINK READY", MainWindow.readyPill(0, false, 0));
        assertEquals("LINK READY", MainWindow.readyPill(MainWindow.CARD_STALL_MS - 1, false, 0), "one late packet doesn't blink it");
        assertEquals("LINK STALLED", MainWindow.readyPill(MainWindow.CARD_STALL_MS, false, 0));
        assertEquals("LINK STALLED", MainWindow.readyPill(1500, true, 0), "a stalled link explains the silence itself");
        assertEquals("FC LOST", MainWindow.readyPill(0, true, 0));
        assertEquals("LINK READY +2 WATCHING", MainWindow.readyPill(0, false, 2));
        assertEquals("FC LOST +1 WATCHING", MainWindow.readyPill(0, true, 1));
    }
}
