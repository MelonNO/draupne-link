package de.droiddrone.pc.launch;

import de.droiddrone.common.FleetStatus;

import java.util.Collection;

/**
 * Fleet PRD AL-11 (FA12) and AL-10 (FA10) on the PC, kept apart from the window so they can be tested alone.
 * <ul>
 *   <li>AL-11: autolaunch is not offered without GPS. SAFE TO AUTOLAUNCH is greyed out for a drone known to have no
 *       3D fix; CALL RESERVE is greyed out when every marked drone is known to have none. A fix not known (no report
 *       yet) is offered: the phone checks it again when marking (LaunchRunner) and at the call (PreArmCheck's NO GPS).</li>
 *   <li>AL-10: a reserve on its way whose call was cancelled, or whose group was lost, joins whoever is the lead now:
 *       the group's lead while a group flies; else the drone the pilot flies now (a new group round it); else it holds
 *       in its approach layer until there is one.</li>
 * </ul>
 */
public final class ReserveRules {
    public static final int FIX = 1, NO_FIX = 0, UNKNOWN = -1;

    private ReserveRules() {
    }

    /** The drone's fix: its fleet report's GNSS flag (3D) when there is one, else the check-in's word (1, 0, -1). */
    public static int fix(FleetStatus report, int checkIn) {
        if (report != null) return report.has(FleetStatus.GNSS) ? FIX : NO_FIX;
        return checkIn > 0 ? FIX : checkIn == 0 ? NO_FIX : UNKNOWN;
    }

    /** Why SAFE TO AUTOLAUNCH is greyed out for this fix, or null: offered. */
    public static String markRefusal(int fix) {
        return fix == NO_FIX ? "no GPS fix" : null;
    }

    /** Why CALL RESERVE is greyed out: none marked, or every marked one without a fix. null: offered. */
    public static String callRefusal(Collection<Integer> markedFixes) {
        if (markedFixes.isEmpty()) return "none marked";
        for (int f : markedFixes) if (f != NO_FIX) return null;
        return "no GPS fix";
    }

    /** Where a reserve on its way goes (AL-10). */
    public enum Join { GROUP, NEW_GROUP, HOLD }

    /** The decision and the lead it joins (null for HOLD). */
    public record Decision(Join join, String lead) {}

    /**
     * @param groupLead the lead of the group flying now, null: no group
     * @param flown the drone the pilot flies now, null: none
     * @param self the reserve
     */
    public static Decision joinWhom(String groupLead, String flown, String self) {
        if (groupLead != null) return new Decision(Join.GROUP, groupLead);
        if (flown != null && !flown.equalsIgnoreCase(self)) return new Decision(Join.NEW_GROUP, flown);
        return new Decision(Join.HOLD, null);
    }
}
