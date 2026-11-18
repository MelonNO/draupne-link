package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The field round of 2026-11-18, the pilot: "allow low battery arming" - a drone on a low pack did not arm and "it
 * showed nothing". Nothing stops arming for the battery; what does stop it is said.
 */
class ArmWhyTest {
    @Test
    void nothingIsSaidWhileArmIsOffOrTheDroneArms() {
        ArmWhy w = new ArmWhy();
        assertNull(w.check(false, null, true, false, List.of(), 0));
        assertNull(w.check(false, "THROTTLE DOWN", true, false, List.of("THROTTLE"), 10_000));
        assertNull(w.check(true, null, true, false, List.of(), 20_000), "ARM just went on: arming takes a moment");
        assertNull(w.check(true, null, true, true, List.of(), 20_500), "armed: nothing to say");
        assertNull(w.check(true, null, true, true, List.of(), 60_000));
    }

    @Test
    void betaflightsReasonIsSaidOnceAndANewReasonAgain() {
        ArmWhy w = new ArmWhy();
        assertNull(w.check(true, null, true, false, List.of("RPMFILTER"), 0));
        assertEquals("ARM is on, not armed: Betaflight says RPMFILTER.", w.check(true, null, true, false, List.of("RPMFILTER"), ArmWhy.AFTER_MS));
        assertNull(w.check(true, null, true, false, List.of("RPMFILTER"), 5000), "once");
        assertEquals("ARM is on, not armed: Betaflight says RPMFILTER ANGLE.", w.check(true, null, true, false, List.of("RPMFILTER", "ANGLE"), 6000));
        // the switch off and on: said again if it still doesn't arm
        assertNull(w.check(false, null, true, false, List.of(), 7000));
        assertNull(w.check(true, null, true, false, List.of("RPMFILTER"), 8000));
        assertNotNull(w.check(true, null, true, false, List.of("RPMFILTER"), 8000 + ArmWhy.AFTER_MS));
    }

    @Test
    void everyKindOfReasonHasItsWords() {
        assertEquals("ARM is on, not armed: the sticks are off - THROTTLE DOWN.", ArmWhy.text("THROTTLE DOWN", true, List.of()));
        assertEquals("ARM is on, not armed: the sticks are off - NO RADIO.", ArmWhy.text("NO RADIO", false, null));
        assertEquals("ARM is on, not armed: no word from the flight controller.", ArmWhy.text(null, false, List.of("ANGLE")));
        assertTrue(ArmWhy.text(null, true, List.of("ARMSWITCH")).endsWith("ARM off and on again."));
        String none = ArmWhy.text(null, true, List.of());
        assertTrue(none.contains("gives no reason"), none);
        assertTrue(none.contains("battery never stops arming"), "the pilot's question, answered where it comes up: " + none);
    }

    /** "Allow low battery arming": the app's battery warnings are warnings - no arming blocker is named after the battery. */
    @Test
    void noBlockerOfBetaflightsIsABatteryOne() {
        for (FcTelemetry.Blocker b : FcTelemetry.blockersFor(0xFFFFFFFF)) {
            String name = b.osdName().toUpperCase();
            assertFalse(name.contains("BAT") || name.contains("VOLT"), name);
        }
    }
}
