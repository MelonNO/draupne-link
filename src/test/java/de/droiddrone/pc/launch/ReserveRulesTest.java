package de.droiddrone.pc.launch;

import de.droiddrone.common.FleetStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet PRD AL-11 (not offered without GPS) and AL-10 (join whoever is the lead now). */
class ReserveRulesTest {
    private static FleetStatus report(boolean gnss) {
        FleetStatus s = new FleetStatus();
        if (gnss) s.flags |= FleetStatus.GNSS;
        return s;
    }

    @Test
    void theReportWinsOverTheCheckIn() {
        assertEquals(ReserveRules.NO_FIX, ReserveRules.fix(report(false), 1), "the fleet report is newer");
        assertEquals(ReserveRules.FIX, ReserveRules.fix(report(true), 0));
        assertEquals(ReserveRules.FIX, ReserveRules.fix(null, 1));
        assertEquals(ReserveRules.NO_FIX, ReserveRules.fix(null, 0));
        assertEquals(ReserveRules.UNKNOWN, ReserveRules.fix(null, -1));
    }

    @Test
    void markIsGreyedOutOnlyWithoutAFix() {
        assertEquals("no GPS fix", ReserveRules.markRefusal(ReserveRules.NO_FIX));
        assertNull(ReserveRules.markRefusal(ReserveRules.FIX));
        assertNull(ReserveRules.markRefusal(ReserveRules.UNKNOWN), "not known: offered, the phone checks again");
    }

    @Test
    void callReserveNeedsOneMarkedDroneThatMayHaveAFix() {
        assertEquals("none marked", ReserveRules.callRefusal(List.of()));
        assertEquals("no GPS fix", ReserveRules.callRefusal(List.of(0, 0)));
        assertNull(ReserveRules.callRefusal(List.of(0, 1)));
        assertNull(ReserveRules.callRefusal(List.of(-1)));
    }

    @Test
    void aReserveJoinsWhoeverIsTheLeadNow() {
        assertEquals(new ReserveRules.Decision(ReserveRules.Join.GROUP, "Lead2"), ReserveRules.joinWhom("Lead2", "Lead2", "R1"));
        assertEquals(new ReserveRules.Decision(ReserveRules.Join.NEW_GROUP, "F1"), ReserveRules.joinWhom(null, "F1", "R1"),
                "group lost: the drone the pilot flies now");
        assertEquals(ReserveRules.Join.HOLD, ReserveRules.joinWhom(null, null, "R1").join(), "no lead: it holds");
        assertEquals(ReserveRules.Join.HOLD, ReserveRules.joinWhom(null, "r1", "R1").join(), "never its own lead");
    }
}
