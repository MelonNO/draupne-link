package de.droiddrone.bench.phone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PhoneCrashScenario's last part (the app's own limit on opening itself again): each error comes more than two minutes
 * after the one before it, counted from the last error. The loop counted from the first error of the run; on the real
 * phone (2026-11-22 evening) the parts before it took long, and its first two errors came 51 s apart - inside
 * Android's own two-minute limit.
 */
class PhoneRigCrashSpacingTest {
    private static final long FIRST = 1_800_000_000_000L;

    @Test
    void eachErrorIsSpacedFromTheLastErrorNotTheFirst() {
        assertTrue(PhoneCrashScenario.ERROR_SPACING_MS > 120_000, "more than Android's two minutes");
        // the evening's run: the loop's first error 250 s after the run's first - long enough after it, at once
        long first = FIRST + 250_000;
        assertEquals(0, PhoneCrashScenario.waitBeforeError(FIRST, first));
        // the next one: the whole spacing after THAT one, however long ago the run's first was
        assertEquals(PhoneCrashScenario.ERROR_SPACING_MS, PhoneCrashScenario.waitBeforeError(first, first));
        assertEquals(PhoneCrashScenario.ERROR_SPACING_MS - 51_000, PhoneCrashScenario.waitBeforeError(first, first + 51_000), "not at 51 s");
        assertEquals(0, PhoneCrashScenario.waitBeforeError(first, first + PhoneCrashScenario.ERROR_SPACING_MS));
        assertEquals(0, PhoneCrashScenario.waitBeforeError(first, first + 10 * 60_000), "never less than nothing");
    }
}
