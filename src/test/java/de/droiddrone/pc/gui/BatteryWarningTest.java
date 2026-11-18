package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The field round of 2026-11-18: "lower the low battery warning to be empty at 2,8 and low at 3,2". */
class BatteryWarningTest {
    @Test
    void lowAtThreePointTwoEmptyAtTwoPointEight() {
        assertNull(OsdRenderer.batteryWarning(3.5), "3.5 V a cell warned before; a Li-ion pack is fine there");
        assertNull(OsdRenderer.batteryWarning(3.21));
        assertEquals("LOW BATTERY", OsdRenderer.batteryWarning(3.2));
        assertEquals("LOW BATTERY", OsdRenderer.batteryWarning(2.81));
        assertEquals("BATTERY EMPTY", OsdRenderer.batteryWarning(2.8));
        assertEquals("BATTERY EMPTY", OsdRenderer.batteryWarning(2.4));
        assertNull(OsdRenderer.batteryWarning(Double.NaN), "an unknown voltage warns of nothing");
    }

    @Test
    void theFleetGaugeAndFiguresUseTheSameLimits() {
        assertFalse(FleetCards.cellLow(3.3));
        assertTrue(FleetCards.cellLow(3.2));
        assertEquals(0, FleetCards.cellFill(2.8), 1e-9);
        assertEquals(1, FleetCards.cellFill(4.2), 1e-9);
        assertEquals("0 MIN", FleetCards.flightLeft(2.8));
    }
}
