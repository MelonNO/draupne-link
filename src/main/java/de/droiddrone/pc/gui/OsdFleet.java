package de.droiddrone.pc.gui;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Fleet PRD GU-5: what the OSD shows of the fleet, made once a refresh by FleetOps (never on the stick or video path) and
 * handed to the renderer with the rest of the OSD's data. Every field may be null / NaN: then its element draws nothing.
 *
 * @param reserves   AL-5: "+2 RESERVES: R1 1:20  R2 2:05" (ReserveQueue.osdLine), null none
 * @param group      the follower group: "GROUP 6", null none
 * @param edgeM      the lead's distance to the group's edge (negative: inside the keep-out zone), NaN none
 * @param floorM     the group's floor above the ground, NaN none
 * @param gnss       the flown drone's GNSS word: GNSS, NO FIX, GNSS LOST; null not ArduPilot / not known
 * @param traffic    AC-8: "TRAFFIC 14 M NE  -3 M", null nobody within twice the radius and twice the layer spacing
 * @param homeNow    FD-5: "LEFT 6:20" or "HOME NOW", null not known
 * @param homeAlarm  HOME NOW: the trip home needs the rest of the battery
 * @param leadDown   FL-4: the lead landed or disarmed with the group up
 * @param minis      AL-20: the launch mini-views, oldest first (the recording leaves them out)
 */
record OsdFleet(String reserves, String group, double edgeM, double floorM, String gnss, String traffic, String homeNow,
                boolean homeAlarm, boolean leadDown, List<Mini> minis) {
    /**
     * One launching reserve's corner view: its picture (null: none yet), name, status word, height above take-off and
     * the floor it climbs to, and whether the launch failed (its last picture held, red).
     */
    record Mini(String name, BufferedImage picture, String status, double heightM, double floorM, boolean failed) {
    }

    static final OsdFleet NONE = new OsdFleet(null, null, Double.NaN, Double.NaN, null, null, null, false, false, List.of());

    /** The same without the mini-views: what the lead's OSD recording keeps (AL-21). */
    OsdFleet withoutMinis() {
        return minis.isEmpty() ? this : new OsdFleet(reserves, group, edgeM, floorM, gnss, traffic, homeNow, homeAlarm, leadDown, List.of());
    }

    /** The OSD editor's sample: every element with something to draw. */
    static OsdFleet sample() {
        return new OsdFleet("+2 RESERVES: R1 1:20  R2 2:05", "GROUP 6", 42, 40, "GNSS", "TRAFFIC 14 M NE  -3 M", "LEFT 6:20", false,
                true, List.of(new Mini("R1", null, "CLIMBING", 12, 40, false)));
    }
}
