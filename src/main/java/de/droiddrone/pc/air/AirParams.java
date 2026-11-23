package de.droiddrone.pc.air;

/**
 * The fleet's airspace numbers (PRD "Key numbers", areas E and K), with the pilot's choices as defaults.
 * <p>
 * The hollow cube's rings, from the group's centre outwards ({@link #cubeHalfM} and on), are derived so that nothing
 * that moves round the cube ever comes within the protection radius of anything else in the same layer:
 * cells' centres at one cell from the centre, the cube's edge half a cell further; the outer lane one protection radius
 * outside the edge (AC-17); the transit shafts' centres a protection radius plus 2 m outside the lane (so a drone
 * climbing in a shaft is more than the radius from anyone on the lane, at any height); the hop ring through the
 * shafts (AC-28); the group's edge a radius outside the shafts. With the defaults: cells 20, cube edge 30, lane 40,
 * shafts 52, edge 62, keep-out boundary 82 m - inside the 100 m the group keeps from the lead (AC-35: the PRD's own
 * sum, 20 + 30 + 20 = 70, counts the transit area as starting at the lane; here it is placed clear of it).
 */
public record AirParams(
        double cellM,          // E11: spacing inside the group
        double layerM,         // K1: vertical spacing of layers
        double radiusM,        // K2: horizontal protection radius
        double keepOutM,       // K15: lead's minimum distance from the group's edge
        double leashM,         // E12: lead distance from the centre that starts the group moving
        double keepM,          // E10: distance the group keeps from the lead
        double floorM,         // E1: follower floor (the abort layer) above the ground
        double moveCapMs,      // K17: group speed while a move inside the group goes on
        double catchUpMs,      // FL-12: the drones' top cruise speed
        double accelMs2,       // the group's acceleration limit (AC-18): under the drones' own (3 m/s2), so they keep up
        long lostWaitMs,       // AC-37: one drone cut off: the group moves away after this
        double lostMoveM,      // K16: how far it moves away
        long holdMs,           // AC-37: a cut-off follower's hold before its exit plan
        long refreshMs,        // K20: exit plans refreshed at least this often
        long leadSilentMs,     // E7: the lead silent this long: a follower takes over
        long orderCheckMs,     // AC-30: the battery order is checked at least this often
        long transitWaitMs,    // AC-29: waited this long for the next area: the other way round
        double ceilingM,       // K21 / AC-13: highest layer above ground; NaN: no ceiling
        double brakeMs2        // the group's braking when it must stop or give way: the drones' own (AC-40, AC-19)
) {
    public static AirParams defaults() {
        return new AirParams(20, 10, 10, 20, 120, 100, 40, 8, 15, 2, 30_000, 20, 120_000, 10_000, 30_000, 300_000, 20_000, Double.NaN, 3);
    }

    public AirParams withFloor(double floor) {
        return new AirParams(cellM, layerM, radiusM, keepOutM, leashM, keepM, floor, moveCapMs, catchUpMs, accelMs2, lostWaitMs,
                lostMoveM, holdMs, refreshMs, leadSilentMs, orderCheckMs, transitWaitMs, ceilingM, brakeMs2);
    }

    public AirParams withCeiling(double ceiling) {
        return new AirParams(cellM, layerM, radiusM, keepOutM, leashM, keepM, floorM, moveCapMs, catchUpMs, accelMs2, lostWaitMs,
                lostMoveM, holdMs, refreshMs, leadSilentMs, orderCheckMs, transitWaitMs, ceiling, brakeMs2);
    }

    /** Half the cube's width: the outer cells' outer edge. */
    public double cubeHalfM() {
        return 1.5 * cellM;
    }

    /** The outer lane: a square this far from the centre (AC-17). */
    public double laneM() {
        return cubeHalfM() + radiusM;
    }

    /** The transit shafts' centres and the hop ring (AC-22, AC-28). */
    public double shaftM() {
        return laneM() + radiusM + 2;
    }

    /** The group's edge: cells, lane, transit areas and the hop ring inside it (AC-34). */
    public double edgeM() {
        return shaftM() + radiusM;
    }

    /** The keep-out zone's boundary from the centre (AC-34). */
    public double keepOutBoundaryM() {
        return edgeM() + keepOutM;
    }

    /** Where the top layer's exit lanes end, from the centre: past the keep-out zone by a protection radius (AC-36). */
    public double laneEndBaseM() {
        return keepOutBoundaryM() + radiusM;
    }

    /**
     * AC-35 / FL-9: the settings that don't fit together, in plain words, or null when they do. Says the smallest
     * values that work.
     */
    public String sizeProblem() {
        StringBuilder s = new StringBuilder();
        if (cellM < radiusM + 5) s.append("Cell size at least ").append(fmt(radiusM + 5)).append(" m (AC-6). ");
        if (keepM < keepOutBoundaryM()) s.append("Distance from the lead at least ").append(fmt(keepOutBoundaryM())).append(" m (AC-35). ");
        if (leashM < Math.max(keepM, keepOutBoundaryM()) + 10) s.append("Leash at least ").append(fmt(Math.max(keepM, keepOutBoundaryM()) + 10)).append(" m (E12). ");
        if (floorM < 10) s.append("Floor at least 10 m (FL-14). ");
        return s.length() == 0 ? null : s.toString().trim();
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString(Math.round(v)) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
