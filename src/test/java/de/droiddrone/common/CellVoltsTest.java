package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The field round of 2026-11-18, Li-ion packs: "empty at 2,8 and low at 3,2", the per cent on a Li-ion curve. */
class CellVoltsTest {
    @Test
    void emptyIsTwoPointEightAndFullFourPointTwo() {
        assertEquals(0, CellVolts.percent(2.8));
        assertEquals(0, CellVolts.percent(2.5));
        assertEquals(100, CellVolts.percent(4.2));
        assertEquals(100, CellVolts.percent(4.35));
        assertEquals(-1, CellVolts.percent(Double.NaN));
        assertTrue(Double.isNaN(CellVolts.fill(Double.NaN)));
    }

    @Test
    void theCurveIsALiIonCellsNotAStraightLine() {
        // a straight line from 2.8 to 4.2 V would say 50 % at 3.5 V
        assertTrue(CellVolts.percent(3.5) < 35, "a Li-ion cell at 3.5 V is well under half: " + CellVolts.percent(3.5));
        assertTrue(CellVolts.percent(3.7) > 40 && CellVolts.percent(3.7) < 60, "about half at 3.7 V: " + CellVolts.percent(3.7));
        assertTrue(CellVolts.percent(3.2) <= 10, "low is the last tenth: " + CellVolts.percent(3.2));
        double before = -1;
        for (double v = 2.7; v <= 4.3; v += 0.01) {
            double fill = CellVolts.fill(v);
            assertTrue(fill >= before, "never falls as the voltage rises, at " + v);
            assertTrue(fill >= 0 && fill <= 1);
            before = fill;
        }
    }

    @Test
    void lowAndEmptyAreThePilotsLimits() {
        assertFalse(CellVolts.low(3.21));
        assertTrue(CellVolts.low(3.2));
        assertTrue(CellVolts.low(3.0));
        assertFalse(CellVolts.empty(2.81));
        assertTrue(CellVolts.empty(2.8));
        assertFalse(CellVolts.low(Double.NaN), "an unknown voltage warns of nothing");
        assertFalse(CellVolts.empty(Double.NaN));
        assertEquals(320, CellVolts.LOW_CENTIVOLTS);
        assertEquals(280, CellVolts.EMPTY_CENTIVOLTS);
    }
}
