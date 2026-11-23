/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone (GPL v3 or later, see the project's LICENSE).
 */

package de.droiddrone.common.group;

/**
 * DRAUPNE fleet (PRD FL-2, FL-3, FL-13, AC-18): a follower's GUIDED target from the PC's latest {@link GroupOrder}.
 * The group's centre is carried forward by its velocity for the order's age (at most {@link #MAX_CARRY_MS}), the
 * drone's offset added; the height is the order's, never under its floor; the nose points at the lead.
 * <p>
 * Where the leash is decided: on the PC, once for the whole group ({@code GroupController}), and every follower is told
 * the same centre and - while nothing is in its way - the same velocity, so the cube keeps its shape (AC-18); one that
 * must give way is told its own safe velocity (AC-8) and the place it is at. Were each phone to run the leash on its
 * own relayed copy of the lead's place, the copies would arrive at different times and the followers would start and
 * stop at different moments. The phone's part is this: it flies the PC's centre on between orders (a 4G gap of a
 * second costs no shape) and it never goes under the floor, whatever it is told. Java 11.
 */
public final class FollowerRule {
    private FollowerRule() {
    }

    /** An order older than this is not carried further (the drone stops where the group would be then). */
    public static final long MAX_CARRY_MS = 1500;

    /** {lat, lon, altAmsl} where the drone is to be now. */
    public static double[] place(GroupOrder o, long ageMs) {
        double t = o.stopped() ? 0 : Math.max(0, Math.min(ageMs, MAX_CARRY_MS)) / 1000.0;
        double e = o.velE * t + o.offE, n = o.velN * t + o.offN;
        double[] p = GeoLocal.offset(o.centreLat, o.centreLon, e, n);
        return new double[]{p[0], p[1], Math.max(o.altAmsl, o.floorAmsl)};
    }

    /** The GUIDED target now; yaw at the lead when its place is known (FL-13), else kept. */
    public static FleetIntent target(GroupOrder o, long ageMs) {
        double[] p = place(o, ageMs);
        double yaw = Double.NaN;
        if (o.leadKnown()) {
            double t = Math.max(0, Math.min(ageMs, MAX_CARRY_MS)) / 1000.0;
            double[] lead = GeoLocal.offset(o.leadLat, o.leadLon, o.leadVelE * t, o.leadVelN * t);
            if (GeoLocal.distance(p[0], p[1], lead[0], lead[1]) > 1) yaw = GeoLocal.bearing(p[0], p[1], lead[0], lead[1]);
        }
        boolean carried = !o.stopped() && ageMs <= MAX_CARRY_MS;
        double ve = carried ? o.velE : 0, vn = carried ? o.velN : 0;
        return FleetIntent.guided(p[0], p[1], p[2], yaw, o.speedCap, ve, vn);
    }
}
