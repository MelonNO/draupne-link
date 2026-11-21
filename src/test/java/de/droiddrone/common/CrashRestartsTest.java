package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How often the phone app opens itself again after an error ended it (pc.55; the bench's night of 2026-11-20: one
 * exception on the camera's thread ended the app, and nothing brought it back).
 */
class CrashRestartsTest {
    private static final long T = 1_800_000_000_000L;

    @Test
    void theFirstErrorsOpenTheAppAgainTheFourthInTenMinutesDoesNot() {
        String stored = CrashRestarts.next("", T);
        assertEquals(Long.toString(T), stored);
        stored = CrashRestarts.next(stored, T + 60_000);
        stored = CrashRestarts.next(stored, T + 120_000);
        assertNotNull(stored, "three times it comes back");
        assertNull(CrashRestarts.next(stored, T + 180_000), "an app that ends at every start is not opened for ever");
        assertNull(CrashRestarts.next(stored, T + 9 * 60_000));
    }

    @Test
    void afterTenQuietMinutesItCountsAnew() {
        String stored = CrashRestarts.next(CrashRestarts.next(CrashRestarts.next(null, T), T + 1000), T + 2000);
        assertNull(CrashRestarts.next(stored, T + 3000));
        String later = CrashRestarts.next(stored, T + CrashRestarts.WINDOW_MS + 2001);
        assertEquals(Long.toString(T + CrashRestarts.WINDOW_MS + 2001), later, "the old ones are forgotten, this one is the first again");
    }

    @Test
    void aClockSetBackAndRubbishInTheStoreStopNothing() {
        // three restarts "in the future": the phone's clock was corrected backwards since
        String ahead = (T + 3_600_000) + "," + (T + 3_601_000) + "," + (T + 3_602_000);
        assertEquals(Long.toString(T), CrashRestarts.next(ahead, T));
        assertEquals(T - 5000 + "," + T, CrashRestarts.next("x,, " + (T - 5000) + " ,-,9e9", T), "what is no time is left out, the rest kept");
    }
}
