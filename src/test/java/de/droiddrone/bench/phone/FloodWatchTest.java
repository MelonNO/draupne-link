package de.droiddrone.bench.phone;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BS6's verdicts on its figures ({@link FloodWatch}): what counts as the sticks unchanged, memory flat, threads capped. */
class FloodWatchTest {
    private static List<Long> of(long... v) {
        List<Long> out = new ArrayList<>();
        for (long x : v) out.add(x);
        return out;
    }

    private static List<Long> repeat(long value, int n) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(value);
        return out;
    }

    @Test
    void percentilesByNearestRank() {
        List<Long> v = of(5, 1, 4, 2, 3);
        assertEquals(3, FloodWatch.percentile(v, 50));
        assertEquals(5, FloodWatch.percentile(v, 95));
        assertEquals(5, FloodWatch.percentile(v, 100));
        assertEquals(1, FloodWatch.percentile(v, 0));
        assertEquals(-1, FloodWatch.percentile(of(), 50));
        List<Long> hundred = new ArrayList<>();
        for (long i = 1; i <= 100; i++) hundred.add(i);
        assertEquals(95, FloodWatch.percentile(hundred, 95));
    }

    @Test
    void theSticksUnchangedLetTheNetworksOwnUpsAndDownsPassNotAHoldUp() {
        List<Long> before = repeat(8, 90);
        before.addAll(repeat(20, 10));
        assertTrue(FloodWatch.unchanged(before, before, 30), "the same");
        List<Long> jittery = repeat(9, 90);
        jittery.addAll(repeat(55, 10));
        assertTrue(FloodWatch.unchanged(before, jittery, 30), "within half again of 20, and 30 ms");
        List<Long> heldUp = repeat(9, 80);
        heldUp.addAll(repeat(400, 20));
        assertFalse(FloodWatch.unchanged(before, heldUp, 30), "a fifth of the readings 400 ms: held up");
        assertFalse(FloodWatch.unchanged(of(), heldUp, 30), "nothing to compare with is no pass");
        assertFalse(FloodWatch.unchanged(before, of(), 30));
    }

    @Test
    void memoryThatSawsIsFlatMemoryThatClimbsIsNot() {
        assertEquals(0, FloodWatch.growth(of(210, 260, 205, 255, 212, 258, 210, 262, 207)), "a collection's saw: the thirds' medians alike");
        assertEquals(80, FloodWatch.growth(of(200, 210, 220, 240, 250, 260, 280, 290, 300)), "a climb: from a median of 210 to 290");
        assertEquals(40, FloodWatch.growth(of(100, 140)), "two readings: one a third");
        assertEquals(0, FloodWatch.growth(of(100)), "one reading says nothing");
        assertTrue(FloodWatch.capped(of(80, 82, 81), of(82, 84, 83), 4));
        assertFalse(FloodWatch.capped(of(80, 82, 81), of(82, 120, 160), 4), "a thread a query and more");
        assertFalse(FloodWatch.capped(of(), of(1), 4));
    }

    @Test
    void aSeriesKeepsWhatIsKnownByPhase() {
        FloodWatch.Series s = new FloodWatch.Series("ages");
        s.add(FloodWatch.Phase.BEFORE, 4);
        s.add(FloodWatch.Phase.BEFORE, -1);
        s.add(FloodWatch.Phase.DURING, 6);
        assertEquals(of(4), s.of(FloodWatch.Phase.BEFORE), "not known is not kept");
        assertEquals(of(6), s.of(FloodWatch.Phase.DURING));
        assertEquals("ages: before p50 4, p95 4, max 4 ms (1); during p50 6, p95 6, max 6 ms (1); after -", s.said("ms"));
    }

    @Test
    void theFloodsMinutesAreTheRunsWordElseATry() {
        String was = System.getProperty("bench.flood.min");
        try {
            System.clearProperty("bench.flood.min");
            assertEquals(3, PhoneHardeningScenario.floodMinutes());
            for (String word : List.of("", " ", "x", "0", "-2")) {
                System.setProperty("bench.flood.min", word);
                assertEquals(3, PhoneHardeningScenario.floodMinutes(), "\"" + word + "\"");
            }
            System.setProperty("bench.flood.min", "30");
            assertEquals(30, PhoneHardeningScenario.floodMinutes());
            System.setProperty("bench.flood.min", "0.5");
            assertEquals(0.5, PhoneHardeningScenario.floodMinutes());
        } finally {
            if (was == null) System.clearProperty("bench.flood.min");
            else System.setProperty("bench.flood.min", was);
        }
    }

    @Test
    void thePhonesMemoryIsReadAsAndroidPrintsIt() {
        String android15 = """
                Applications Memory Usage (in Kilobytes):
                Uptime: 123456 Realtime: 123456

                ** MEMINFO in pid 4711 [de.droiddrone.flight.pc.bench] **
                                   Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap
                                 Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free
                                ------   ------   ------   ------   ------   ------   ------   ------
                  Native Heap    30210    30120        0        0    31884    41004    35112     5891
                  Dalvik Heap    12056    11980        0        0    15448    24012    12006    12006
                        TOTAL   142873   101220    20016        0   220144    65016    47118    17897

                 App Summary
                                       Pss(KB)                        Rss(KB)
                                        ------                         ------
                           Java Heap:    14000                          28832
                         Native Heap:    30120                          31884
                           TOTAL PSS:   142873            TOTAL RSS:   220144       TOTAL SWAP PSS:        0
                """;
        assertEquals(142873, FloodWatch.pssKb(android15));
        assertEquals(14000, FloodWatch.javaHeapKb(android15));
        assertEquals(98_765, FloodWatch.pssKb("        TOTAL:    98,765\n"), "Android 8's way, with its thousands' comma");
        assertEquals(-1, FloodWatch.pssKb("No process found for: de.droiddrone.flight.pc"));
        assertEquals("33 kB", FloodWatch.bytes(32_100));
        assertEquals("12.3 MB", FloodWatch.bytes(12_345_678));
    }
}
