package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** H3 (1.0.48 review): a second DRAUPNE Link on the pilot's own address can't take a drone that isn't known disarmed. */
class TakeoverTest {
    @Test
    void thePilotsAddressOnANewPortWaitsForTwoSecondsOfSilenceUnlessDisarmed() {
        assertFalse(Takeover.allowed(true, false, 100), "armed or unknown, the pilot just heard: PILOT_AT");
        assertFalse(Takeover.allowed(true, false, Takeover.SAME_IP_SILENT_MS - 1));
        assertTrue(Takeover.allowed(true, false, Takeover.SAME_IP_SILENT_MS), "the pilot silent 2 s: its PC started again");
        assertTrue(Takeover.allowed(true, true, 0), "disarmed: at once, as before");
    }

    @Test
    void anotherAddressNeedsDisarmedAndTenSecondsOfSilence() {
        assertFalse(Takeover.allowed(false, true, Takeover.PILOT_ALIVE_MS - 1));
        assertFalse(Takeover.allowed(false, false, 60_000), "never while not known disarmed");
        assertTrue(Takeover.allowed(false, true, Takeover.PILOT_ALIVE_MS));
    }
}
