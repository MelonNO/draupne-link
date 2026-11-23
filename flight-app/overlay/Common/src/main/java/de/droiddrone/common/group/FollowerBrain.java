/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone (GPL v3 or later, see the project's LICENSE).
 */

package de.droiddrone.common.group;

/**
 * DRAUPNE fleet: a group follower's own rules on its phone - what it flies while the PC's orders come, and what it does
 * when they stop or GNSS is lost, with no PC needed (PRD FL-2, FL-15, AC-25, AC-33, AC-37, AC-41, AC-42). The lead and
 * drones on their own have their own rule (ArduLinkLossRule); this class is for followers in a group only.
 * <p>
 * The phone calls {@link #tick} about ten times a second and commands the flight controller only when the intent
 * changes. While orders come: the {@link FollowerRule} target (GUIDED). The PC silent for the given threshold: LOITER
 * where it is (its cell); back within {@link #holdMs} (2 min): it stays and follows again. After the hold (plus the
 * plan's start delay): out along its lane in its own layer ({@link ExitPlan#laneEnd}), and at the lane's end plain RTL
 * at its return height to its own rally point. At 3 % during the hold: a bottom-row drone lands straight down (nothing
 * is under it), any other flies its lane and lands at its end. Once on its way out it keeps going if the PC comes back;
 * the PC calls it back with {@link #recall}. A bottom-row drone at 3 % while following lands straight down (AC-33).
 * <p>
 * GNSS lost (A5, AC-42): ALT_HOLD - height held, centred sticks; it can neither loiter nor fly its plan. So while GNSS
 * is gone, link or no link; at 3 % it lands. GNSS back: it follows the PC's orders again (the PC routes it in through a
 * transit area, AC-38).
 * <p>
 * Safe choices where the PRD is silent: an exit already started is not given up at 3 % - the drone lands where its lane
 * has brought it, never back towards the group; a follower that never had an exit plan holds and waits rather than
 * guessing one. Java 11, no Android; time in ms on any clock that only runs forward.
 */
public final class FollowerBrain {
    public static final int NORMAL = 0, HOLD = 1, EXIT_LANE = 2, EXIT_HOME = 3, LANDING = 4, GNSS_HOLD = 5;

    /** The PC silent this long: the link counts as lost (the same constant as the lead's rule, wired from one place). */
    private final long silentMs;
    /** PRD AC-37: the hold in the cell before the exit plan. */
    public long holdMs = 120_000;
    /** Battery at or under this: land (the PRD's one battery action, AP-14 / FD-5). */
    public double landPercent = 3;
    /** Within this of the lane's end: there. */
    public double arriveM = 2;

    private int phase = NORMAL;
    private GroupOrder order;
    private long orderAt;
    private ExitPlan plan;
    private long silentSince = -1;
    private double[] laneEnd;
    private boolean landAtLaneEnd;

    /** @param silentMs PC silence that counts as a lost link (1000 ms: orders come five times a second) */
    public FollowerBrain(long silentMs) {
        this.silentMs = silentMs;
    }

    public int phase() {
        return phase;
    }

    public ExitPlan plan() {
        return plan;
    }

    public GroupOrder order() {
        return order;
    }

    /** The lane's end it flies to while leaving, {lat, lon}; null otherwise. */
    public double[] laneEnd() {
        return laneEnd == null ? null : laneEnd.clone();
    }

    public void order(GroupOrder o, long now) {
        order = o;
        orderAt = now;
    }

    public void plan(ExitPlan p) {
        plan = p;
    }

    /** The pilot called it back on its way out (AC-37, AC-38): it follows orders again; the PC routes it in. */
    public void recall() {
        if (phase == EXIT_LANE || phase == EXIT_HOME || phase == HOLD) {
            phase = NORMAL;
            laneEnd = null;
            silentSince = -1;
        }
    }

    /**
     * @param lastPcAt   when the last packet from the PC came
     * @param batteryPct the pack's charge left, NaN when not known
     */
    public FleetIntent tick(long now, long lastPcAt, double lat, double lon, double batteryPct, boolean gnssOk) {
        boolean low = !Double.isNaN(batteryPct) && batteryPct <= landPercent;
        if (phase == LANDING) return FleetIntent.DOWN;
        if (order != null && order.land() && now - lastPcAt < silentMs) { // the PC lands it (AC-20, AC-24), GNSS or not
            phase = LANDING;
            return FleetIntent.DOWN;
        }
        if (!gnssOk) {
            if (phase != EXIT_HOME) phase = GNSS_HOLD; // RTL without GNSS is ArduPilot's own to sort out (it lands)
            if (phase == GNSS_HOLD) return low ? FleetIntent.DOWN : FleetIntent.HEIGHT_ONLY;
        }
        if (phase == GNSS_HOLD) {
            phase = NORMAL;
            silentSince = -1;
        }
        long silent = now - lastPcAt;
        switch (phase) {
            case NORMAL:
                if (silent < silentMs) {
                    if (order == null) return FleetIntent.HOLD;
                    if (low && order.bottomRow()) {
                        phase = LANDING;
                        return FleetIntent.DOWN;
                    }
                    return FollowerRule.target(order, now - orderAt);
                }
                phase = HOLD;
                silentSince = lastPcAt;
                return FleetIntent.HOLD;
            case HOLD:
                if (silent < silentMs) {
                    phase = NORMAL;
                    silentSince = -1;
                    return order != null ? FollowerRule.target(order, now - orderAt) : FleetIntent.HOLD;
                }
                if (low) {
                    if (plan == null || plan.bottomRow() || order != null && order.bottomRow()) {
                        phase = LANDING;
                        return FleetIntent.DOWN;
                    }
                    landAtLaneEnd = true;
                    return startExit(lat, lon);
                }
                if (plan != null && now - silentSince >= holdMs + Math.round(plan.startDelayS * 1000)) {
                    landAtLaneEnd = false;
                    return startExit(lat, lon);
                }
                return FleetIntent.HOLD;
            case EXIT_LANE:
                if (GeoLocal.distance(lat, lon, laneEnd[0], laneEnd[1]) <= arriveM) {
                    phase = landAtLaneEnd ? LANDING : EXIT_HOME;
                    return landAtLaneEnd ? FleetIntent.DOWN : FleetIntent.rtl(plan.returnAltRelM);
                }
                if (low) landAtLaneEnd = true;
                return FleetIntent.guided(laneEnd[0], laneEnd[1], plan.layerAltAmsl, Double.NaN, Double.NaN);
            case EXIT_HOME:
                return FleetIntent.rtl(plan.returnAltRelM);
            default:
                phase = NORMAL;
                return FleetIntent.HOLD;
        }
    }

    private FleetIntent startExit(double lat, double lon) {
        // out the way its place in the group points (no order ever: north), from wherever it is now
        laneEnd = order != null ? plan.laneEnd(lat, lon, order.offE, order.offN) : plan.laneEnd(lat, lon, 0, 0);
        phase = EXIT_LANE;
        return FleetIntent.guided(laneEnd[0], laneEnd[1], plan.layerAltAmsl, Double.NaN, Double.NaN);
    }
}
