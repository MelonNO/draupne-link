package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SetupConfirmTest {
    private static final String A = SetupConfirm.boardId(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}, null);
    private static final String B = SetupConfirm.boardId(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13}, null);

    @Test
    void aConfirmIsNotReusedOnABoardPluggedInAfterwards() {
        // 1.0.48: one CONFIRM armed the page; a second drone plugged in later on the same page was written to as well
        SetupConfirm c = new SetupConfirm();
        c.press(A);
        assertTrue(c.mayWrite(A));
        assertFalse(c.mayWrite(B), "another board: nothing written");
        assertTrue(c.seen(B), "reading the other board takes the CONFIRM away");
        assertFalse(c.armed());
        assertFalse(c.mayWrite(A), "and it doesn't come back for the first one: CONFIRM again");
    }

    @Test
    void theConfirmedStateBelongsToOneBoard() {
        SetupConfirm c = new SetupConfirm();
        c.press(A);
        c.done(A);
        assertEquals(A, c.saved());
        assertFalse(c.seen(A), "the same board: still confirmed");
        assertTrue(c.confirmed());
        SetupConfirm restarted = new SetupConfirm();
        restarted.restore(c.saved());
        assertTrue(restarted.confirmed());
        assertFalse(restarted.armed(), "a new session writes nothing before CONFIRM");
        assertTrue(restarted.seen(B));
        assertFalse(restarted.confirmed(), "another board isn't confirmed by it");
        assertEquals(SetupConfirm.NONE, restarted.saved());
    }

    @Test
    void oldSavedValuesNameNoBoard() {
        SetupConfirm c = new SetupConfirm();
        c.restore("true"); // 1.0.48's
        assertFalse(c.confirmed());
        c.restore("false");
        assertFalse(c.confirmed());
        c.restore(null);
        assertFalse(c.confirmed());
    }

    @Test
    void theBoardIdComesFromItsUniqueId() {
        assertEquals("uid:0102030405060708090a0b0c", A);
        assertNotEquals(A, B);
        assertEquals("info:53343035", SetupConfirm.boardId(null, new byte[]{'S', '4', '0', '5'}), "no UID: the board info");
        assertEquals("info:53343035", SetupConfirm.boardId(new byte[12], new byte[]{'S', '4', '0', '5'}), "an all-zero UID isn't one");
        assertNull(SetupConfirm.boardId(null, null));
        SetupConfirm c = new SetupConfirm();
        c.press(null);
        assertFalse(c.mayWrite(null), "an unknown board is never written to");
    }
}
