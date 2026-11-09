package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Betaflight's paged BOXNAMES / BOXIDS, gathered as the phone's Msp did (one copy for both now). */
class MspPagesTest {
    @Test
    void thePagesAreJoinedInOrderAtTheLast() {
        MspPages p = new MspPages();
        assertEquals(0, p.page());
        assertFalse(p.add(new byte[]{1, 2}));
        assertEquals(1, p.page(), "the next page to ask for");
        assertTrue(p.add(new byte[]{3}));
        assertArrayEquals(new byte[]{1, 2, 3}, p.joined());
        assertEquals(0, p.page(), "the next round starts over");
    }

    @Test
    void aRepeatedPageIsNoNewPage() {
        MspPages p = new MspPages();
        p.add(new byte[]{1, 2});
        assertFalse(p.add(new byte[]{1, 2}), "the first page's reply again (a request asked twice)");
        assertEquals(1, p.page());
        assertTrue(p.add(new byte[]{9}));
        assertArrayEquals(new byte[]{1, 2, 9}, p.joined());
    }

    @Test
    void emptyPagesJoinToNothingButEndTheRound() {
        MspPages p = new MspPages();
        assertFalse(p.add(null));
        assertTrue(p.add(new byte[0]));
        assertNull(p.joined());
    }

    @Test
    void aRestartAsksForTheFirstPageAgain() {
        MspPages p = new MspPages();
        p.add(new byte[]{1});
        p.restart();
        assertEquals(0, p.page());
        assertFalse(p.add(new byte[]{5}));
        assertTrue(p.add(new byte[]{6}));
        assertArrayEquals(new byte[]{5, 6}, p.joined());
    }
}
