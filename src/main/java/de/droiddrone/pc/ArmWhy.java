package de.droiddrone.pc;

import java.util.List;

/**
 * Why the drone is not armed while the pilot's ARM switch is on (1.0.71; the pilot, 2026-11-18, of a drone that would
 * not arm on a low pack: "it showed nothing" - and wondered whether the radio's own low battery was behind it).
 * Nothing in the app or in Betaflight stops arming for the drone's battery; whatever does stop it is said here, once,
 * in the log (the OSD has the same reasons while they last: RC OFF - ..., ARMING BLOCKED: ...). With no reason at
 * all, that is said too - it points at the ARM mode's own setup, or at a switch the radio doesn't really send.
 * Pure: the window feeds it once a second.
 */
public final class ArmWhy {
    /** ARM on and not armed for this long before anything is said (arming itself takes a moment). */
    public static final long AFTER_MS = 1500;

    private long since = -1;
    private String said;

    /**
     * @param armOn      the radio's ARM switch is on, as the app reads it (false while unknown)
     * @param rcWaiting  what the sticks wait for while they are off (RcSession.waitingFor), null when they are live
     * @param fcFresh    the flight controller's status is a fresh one
     * @param fcArmed    it says armed
     * @param blockers   Betaflight's reasons against arming, as the OSD names them
     * @return a line for the log, the first time this reason has lasted {@link #AFTER_MS}; else null
     */
    public String check(boolean armOn, String rcWaiting, boolean fcFresh, boolean fcArmed, List<String> blockers, long now) {
        if (!armOn || (fcFresh && fcArmed)) {
            since = -1;
            said = null;
            return null;
        }
        if (since < 0) since = now;
        if (now - since < AFTER_MS) return null;
        String why = text(rcWaiting, fcFresh, blockers);
        if (why.equals(said)) return null;
        said = why;
        return why;
    }

    /** The reason, in the app's words. */
    public static String text(String rcWaiting, boolean fcFresh, List<String> blockers) {
        if (rcWaiting != null) return "ARM is on, not armed: the sticks are off - " + rcWaiting + ".";
        if (!fcFresh) return "ARM is on, not armed: no word from the flight controller.";
        // ARMSWITCH alone is Betaflight saying "the switch was on before I was ready": off and on again arms
        if (blockers != null && !blockers.isEmpty()) {
            String names = String.join(" ", blockers);
            return "ARM is on, not armed: Betaflight says " + names + "."
                    + (blockers.size() == 1 && blockers.get(0).equals("ARMSWITCH") ? " ARM off and on again." : "");
        }
        return "ARM is on, not armed, and Betaflight gives no reason: check the ARM mode in Drone setup and the radio's switch."
                + " (The drone's battery never stops arming.)";
    }
}
