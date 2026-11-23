package de.droiddrone.pc.launch;

/**
 * Fleet PRD AL-1 / AL-2 (FA1): who may be marked SAFE TO AUTOLAUNCH, and the question the pilot answers first.
 * FA1 says any landed drone; landed here means the flight controller is known disarmed (an unread one counts as
 * armed, as everywhere since F-062), and the drone must fly ArduPilot: the launch is ArduPilot's GUIDED take-off,
 * which Betaflight has no counterpart for. A drone marked OUT OF ACTION is not.
 */
public final class MarkRules {
    private MarkRules() {
    }

    /** Why the drone can't be marked now, or null: it can. */
    public static String refusal(boolean knownDisarmed, boolean arduPilot, boolean outOfAction) {
        if (outOfAction) return "OUT OF ACTION: clear that first.";
        if (!arduPilot) return "Autolaunch needs ArduPilot.";
        if (!knownDisarmed) return "Not known landed: wait for its disarmed state.";
        return null;
    }

    /** AL-2: the pilot confirms the climb is clear, to the floor as it is set now. */
    public static String question(String drone, double floorM) {
        return "Can " + drone + " climb straight up to " + Math.round(floorM) + " m with nothing above it?";
    }
}
