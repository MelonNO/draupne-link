package de.droiddrone.pc;

/**
 * F-062: what this PC's guards go by before they end a pilot's link or stop its sticks - STANDBY MODE, closing the
 * window, DEEP SLEEP, OVERWATCH, OUT OF ACTION, another drone's CONNECT. The rule is the phone's own (its
 * FcArmedState, pc.31): only a DISARMED the flight controller said itself lets a guard through; not known counts as
 * armed.
 * <p>
 * The armed flag is read from MSP_STATUS by Betaflight's mode names, and those are read anew on every new connection
 * and after a loss of {@link ControlLink#FC_STATE_KEEP_MS}: for the first seconds of a link there is no status, then
 * statuses that say "not armed" whatever the drone does ({@link FcTelemetry.Status#armedKnown}). Up to 1.0.71 the
 * guards took both for DISARMED: with the app started again while its drone was in the air, STANDBY MODE and closing
 * the window ended the link without a question, and DEEP SLEEP and OVERWATCH stopped the sticks at once.
 * <p>
 * A phone with no flight controller (the LINK card's FC LOST) is a case of its own - no sticks reach a drone through
 * it, there is nothing to protect - unless its drone was armed lately. Nothing here blocks: every guard asks, and the
 * pilot's answer goes. Pure: the window hands in what its link says.
 */
public enum ArmedCheck {
    /** Nothing to protect: DISARMED (said now, or its last word), no flight controller on the phone, or no link was up yet. */
    CLEAR,
    /** A fresh status says ARMED. */
    ARMED,
    /** Armed within the lost-drone window and nothing since says DISARMED: it may still be flying. */
    MAY_FLY,
    /** The phone is there, but whether its drone is armed has not been read. Counts as armed. */
    NOT_KNOWN;

    /** A status older than this says nothing about now (as the window's own armed check). */
    static final long FRESH_MS = 2000;

    /** The guard asks the pilot before it goes on. */
    public boolean asks() {
        return this != CLEAR;
    }

    /**
     * @param ready       the link is READY now
     * @param fcLost      the link is up and the phone passes nothing on from a flight controller ({@link ControlLink#isFcLost})
     * @param fcSilentMs  since the flight controller's last record on this link; Long.MAX_VALUE: none ever
     * @param st          its last status, or null
     * @param lastArmedAt when this connection last saw it armed in a fresh status (0: never)
     */
    static ArmedCheck of(boolean ready, boolean fcLost, long fcSilentMs, FcTelemetry.Status st, long lastArmedAt, long now) {
        boolean fresh = st != null && now - st.receivedAt() < FRESH_MS;
        if (fresh && st.armed()) return ARMED;
        if (fresh && st.armedKnown()) return CLEAR; // it says DISARMED now
        long armedAt = st != null && st.armed() ? Math.max(lastArmedAt, st.receivedAt()) : lastArmedAt;
        if (armedAt != 0 && now - armedAt < PossiblyFlying.LOST_DRONE_WINDOW_MS) return MAY_FLY;
        if (fcLost) return CLEAR; // the phone has no flight controller: no sticks reach a drone through it
        if (st != null && st.armedKnown()) return CLEAR; // its last word, nothing heard since: DISARMED (or armed longer ago than the window)
        // a flight controller is there (or was, within the window) and was never read: a new connection's first
        // seconds, or the mode names forgotten in a long loss
        if (fcSilentMs < PossiblyFlying.LOST_DRONE_WINDOW_MS) return NOT_KNOWN;
        // nothing from a flight controller yet: too early to say on a link just up (FC LOST comes after its grace);
        // with no link up, nothing is known and no sticks went
        return ready ? NOT_KNOWN : CLEAR;
    }

    /** As above, read from a pilot's link; CLEAR without one, and while only watching (no sticks to lose). */
    public static ArmedCheck of(ControlLink link, long lastArmedAt, long now) {
        if (link == null || link.isSpectator()) return CLEAR;
        FcTelemetry t = link.getTelemetry();
        return of(link.getState() == ControlLink.State.READY, link.isFcLost(), t.msSinceFcData(now), t.getStatus(), lastArmedAt, now);
    }

    /** The question's text for an action that stops the sticks ("Standby mode", "DEEP SLEEP", ...); null when nothing is asked. */
    public String question(String action) {
        return switch (this) {
            case CLEAR -> null;
            case ARMED -> "The drone is ARMED.\n" + action + " stops the sticks: Betaflight failsafe takes over (GPS rescue, land or drop, as set).";
            case MAY_FLY -> "The drone was armed moments ago and may still be flying.\n" + action
                    + " stops the sticks: Betaflight failsafe takes over (GPS rescue, land or drop, as set).";
            case NOT_KNOWN -> "Not known yet if the drone is armed.\n" + action
                    + " stops the sticks: in the air, Betaflight failsafe takes over (GPS rescue, land or drop, as set).";
        };
    }

    /** The question's title; null when nothing is asked. */
    public String title(String action) {
        return this == CLEAR ? null : this == NOT_KNOWN ? action + " - armed?" : action + " while armed?";
    }

    /** For the log, after the action's name: what the pilot confirmed it in. */
    public String confirmedIn() {
        return switch (this) {
            case CLEAR -> "";
            case ARMED -> "while armed";
            case MAY_FLY -> "while possibly flying";
            case NOT_KNOWN -> "with the armed state not known";
        };
    }
}
