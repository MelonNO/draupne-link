package de.droiddrone.pc.launch;

import de.droiddrone.common.MissionItem;
import de.droiddrone.common.group.GeoLocal;

import java.util.Collection;
import java.util.List;

/**
 * Fleet PRD AC-10: before a drone leaves the ground it gets its own landing (rally) point, at least twice the protection
 * radius from every other drone's. Here for the drones this PC sends out on their own - DISPATCH, CALL RESERVE, a route's
 * GO: the point is where the drone stands (its RTL lands it where it took off), unless another drone's point is too near -
 * then the nearest free one around it, on rings {@link #STEP_M} apart. A follower's exit plan brings a rally point of the
 * group's own (its phone uploads that one).
 */
public final class RallyPlan {
    private RallyPlan() {
    }

    /** Twice the protection radius (AirParams: 10 m). */
    public static final double MIN_APART_M = 2 * de.droiddrone.pc.air.AirParams.defaults().radiusM();
    /** The rings round a drone's spot when its own spot is taken: 2.5 radii, as RallyPoints spaces a site. */
    public static final double STEP_M = 2.5 * de.droiddrone.pc.air.AirParams.defaults().radiusM();
    static final int RINGS = 12;

    /** {lat, lon}: at (lat, lon) when nothing taken is nearer than {@link #MIN_APART_M}, else the nearest free point round it. */
    public static double[] place(double lat, double lon, Collection<double[]> taken) {
        if (free(lat, lon, taken)) return new double[]{lat, lon};
        for (int k = 1; k <= RINGS; k++) {
            int n = 6 * k;
            for (int i = 0; i < n; i++) {
                double b = 2 * Math.PI * i / n;
                double[] p = GeoLocal.offset(lat, lon, k * STEP_M * Math.sin(b), k * STEP_M * Math.cos(b));
                if (free(p[0], p[1], taken)) return p;
            }
        }
        return new double[]{lat, lon}; // a site this crowded: its own spot, as without a rally point
    }

    /** No point taken is nearer than {@link #MIN_APART_M}. */
    public static boolean free(double lat, double lon, Collection<double[]> taken) {
        for (double[] t : taken) if (GeoLocal.distance(lat, lon, t[0], t[1]) < MIN_APART_M) return false;
        return true;
    }

    /** The FC's rally list: this one point, its height above home (ArduPilot's NAV_RALLY_POINT, relative frame). */
    public static List<MissionItem> items(double lat, double lon, double relAltM) {
        // MAV_FRAME_GLOBAL_RELATIVE_ALT (3): ArduPilot answers a rally point in it (an _INT frame, 6, reads back as 3 and failed the
        // read-back); its height whole metres, as ArduPilot keeps it
        return List.of(new MissionItem(0, 3, 5100, true, 0, 0, 0, 0, (int) Math.round(lat * 1e7), (int) Math.round(lon * 1e7), (float) Math.round(relAltM)));
    }
}
