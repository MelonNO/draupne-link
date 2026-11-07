package de.droiddrone.pc.fleet;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The virtual drone's 4G: loss, jitter, bunching, a 4G loss and a handover, judged packet by packet. */
class LinkImpairmentTest {
    @Test
    void aPerfectLinkPassesEverythingAtOnce() {
        LinkImpairment l = new LinkImpairment(new Random(1));
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, l.decide(true, i));
            assertEquals(0, l.decide(false, i));
        }
        assertFalse(l.active(0));
        assertEquals(1023, l.rssi(0));
    }

    @Test
    void theLossIsAboutTheShareSet() {
        LinkImpairment l = new LinkImpairment(new Random(7));
        l.set(20, 0, 0, 0);
        int lost = 0;
        for (int i = 0; i < 10_000; i++) if (l.decide(i % 2 == 0, i) < 0) lost++;
        assertEquals(2000, lost, 200, "about 20 %");
        assertEquals(lost, l.lostCount());
        assertTrue(l.rssi(0) < 900);
    }

    @Test
    void jitterAndDelayStayInTheirBoundsAndPacketsOvertake() {
        LinkImpairment l = new LinkImpairment(new Random(3));
        l.set(0, 100, 50, 0);
        boolean overtaken = false;
        long lastArrival = -1;
        for (int i = 0; i < 2000; i++) {
            long now = i * 5L;
            long d = l.decide(true, now);
            assertTrue(d >= 50 && d <= 150, "delay " + d);
            if (now + d < lastArrival) overtaken = true;
            lastArrival = Math.max(lastArrival, now + d);
        }
        assertTrue(overtaken, "with jitter a later packet can arrive first, as on 4G");
    }

    @Test
    void bunchingLetsPacketsGoTogether() {
        LinkImpairment l = new LinkImpairment(new Random(5));
        l.set(0, 0, 0, 200);
        for (long now = 1000; now < 3000; now += 7) {
            long d = l.decide(true, now);
            assertEquals(0, (now + d) % 200, "released on the 200 ms beat");
            assertTrue(d < 200);
        }
    }

    @Test
    void a4gLossSilencesBothWaysForItsTime() {
        LinkImpairment l = new LinkImpairment(new Random(1));
        l.silence(10_000, 3000);
        assertTrue(l.silent(10_000));
        assertEquals(-1, l.decide(true, 10_000));
        assertEquals(-1, l.decide(false, 12_999));
        assertEquals(1000, l.silentForMs(12_000));
        assertEquals(0, l.rssi(11_000));
        assertEquals(0, l.decide(true, 13_000), "over: a perfect link again");
        assertFalse(l.silent(13_000));
    }

    @Test
    void aHandoverHoldsTheUplinkAndLosesTheDownlinkThenIsSlower() {
        LinkImpairment l = new LinkImpairment(new Random(1));
        l.handover(5000, 1500);
        assertEquals(1500 + LinkImpairment.HANDOVER_EXTRA_MS, l.decide(true, 5000), "held, let go when the new cell takes over");
        assertEquals(500 + LinkImpairment.HANDOVER_EXTRA_MS, l.decide(true, 6000), "all of them at once at 6500");
        assertEquals(-1, l.decide(false, 6000), "what the PC sends meanwhile is lost");
        assertEquals(LinkImpairment.HANDOVER_EXTRA_MS, l.decide(false, 7000), "after: a slower path");
        assertEquals(0, l.decide(true, 6500 + LinkImpairment.HANDOVER_SLOW_MS), "and later the usual one");
        l.handover(20_000, 1000);
        l.clear();
        assertEquals(0, l.decide(false, 20_100), "CLEAR ends it");
    }
}
