package de.droiddrone.pc.route;

import java.util.ArrayList;
import java.util.List;

/**
 * Fleet PRD RT-12: one route to several drones, each on a height layer of its own (layerSpacingM apart, the PRD's
 * K1 is 10 m; the order is kept over terrain since every drone's heights move up by the same amount, AC-3), or each
 * in a time slot of its own on the same heights.
 */
public final class RouteShare {
    /** PRD K1: height layers 10 m apart. */
    public static final double LAYER_SPACING_M = 10;

    private RouteShare() {
    }

    /** Drone k (0 = the first) flies the mission raised by k layers. */
    public static List<MissionBuilder.Mission> layered(MissionBuilder.Mission base, int drones, double layerSpacingM) {
        List<MissionBuilder.Mission> out = new ArrayList<>(drones);
        for (int k = 0; k < drones; k++) out.add(k == 0 ? base : base.raisedBy(k * layerSpacingM));
        return out;
    }

    /**
     * Drone k starts k slots after the first, on the same heights. Start delays in seconds; a slot should be at least
     * {@link #minSlotS} so that one drone is past the protection radius before the next leaves.
     */
    public static double[] slots(int drones, double slotS) {
        double[] d = new double[drones];
        for (int k = 0; k < drones; k++) d[k] = k * slotS;
        return d;
    }

    /** The shortest slot that keeps drones apart along the route: protectionM at cruise, rounded up to a second. */
    public static double minSlotS(RoutePlanner.Limits limits, double protectionM) {
        return Math.ceil(2 * protectionM / limits.cruiseMs());
    }
}
