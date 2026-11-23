package de.droiddrone.pc.air;

import de.droiddrone.pc.air.virt.VirtualAirspace;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD FL-12 / AC-34: the group catching up with a lead that flew off and now hovers stops at the keeping
 * distance - it brakes on its way in, so the lead never comes near the keep-out zone and the pilot hears no TRAFFIC.
 */
class CatchUpTrafficTest {
    @ParameterizedTest
    @CsvSource({"2, 0, 300", "2, 150, 150", "8, 60, 320", "8, 150, 150", "8, -160, 200", "16, -200, 260"})
    void catchingUpWithAHoveringLeadIsNoTraffic(int n, double e, double north) {
        VirtualAirspace a = Groups.group(n, 7);
        AirParams p = a.params;
        a.pilot = Groups.goTo(e, north, Groups.GROUND + Groups.leadAgl(p, n)); // off, then it hovers
        a.run(150);
        long traffic = a.events.stream().filter(x -> x.endsWith(" TRAFFIC")).count();
        assertEquals(0, traffic, "no TRAFFIC while the group catches up with a lead that hovers: " + a.events);
        V2 lead = a.copters.get("LEAD").pos();
        double d = lead.dist(a.gc.centre());
        assertTrue(d > p.keepM() - 10 && d < p.leashM() + 1, "the group waits near the keeping distance: " + d);
        assertTrue(a.separated(), Groups.describe(a));
    }
}
