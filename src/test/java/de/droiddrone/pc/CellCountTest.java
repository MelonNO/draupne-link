package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CellCountTest {
    @Test
    void keepsBetaflightsCountWhenItIsPossible() {
        CellCount c = new CellCount();
        assertEquals(6, c.resolve(25.1, 6, 4.3, 0));
        assertEquals(6, c.resolve(19.9, 6, 4.3, 100), "an empty 6S is still 6S");
        assertEquals(4, new CellCount().resolve(16.8, 4, 4.3, 0));
        assertEquals(0, new CellCount().resolve(24.0, 0, 4.3, 0), "no count from Betaflight yet: none here either");
    }

    @Test
    void replacesAnImpossibleOneAfterTheVoltageSettles() {
        CellCount c = new CellCount();
        // Betaflight says 1S while the 6S reads 25 V: 25 V per cell can't be
        assertEquals(0, c.resolve(25.0, 1, 4.3, 0), "wait: the voltage may still be climbing");
        assertEquals(0, c.resolve(25.02, 1, 4.3, 1000));
        assertEquals(6, c.resolve(25.05, 1, 4.3, CellCount.STEADY_MS + 1));
        assertEquals(6, c.resolve(22.0, 1, 4.3, 60_000), "kept while the pack drains");
        assertEquals(6, c.resolve(20.0, 2, 4.3, 61_000), "Betaflight's 2S is just as wrong");
    }

    @Test
    void neverCountsWhileTheVoltageClimbs() {
        CellCount c = new CellCount();
        long t = 0;
        for (double v = 5; v < 25; v += 0.5, t += 100) assertEquals(0, c.resolve(v, 1, 4.3, t), "at " + v + " V");
        assertEquals(0, c.resolve(25.0, 1, 4.3, t), "just arrived at 25 V");
        assertEquals(6, c.resolve(25.0, 1, 4.3, t + CellCount.STEADY_MS));
    }

    @Test
    void countsAgainForANewPack() {
        CellCount c = new CellCount();
        c.resolve(16.6, 1, 4.3, 0);
        assertEquals(4, c.resolve(16.6, 1, 4.3, 2000));
        assertEquals(0, c.resolve(0.1, 0, 4.3, 3000), "unplugged (Betaflight drops its count too)");
        c.resolve(25.0, 1, 4.3, 4000);
        assertEquals(6, c.resolve(25.0, 1, 4.3, 6000));
    }

    @Test
    void theBatteryRecordSaysWhenBetaflightIsWrong() {
        assertTrue(new FcTelemetry.Battery(25.0, 6, 0, 0, 1).fcCountWrong());
        assertFalse(new FcTelemetry.Battery(25.0, 6, 0, 0, 6).fcCountWrong());
        assertFalse(new FcTelemetry.Battery(25.0, 0, 0, 0, 1).fcCountWrong(), "still settling: nothing to say yet");
    }
}
