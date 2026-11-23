package de.droiddrone.pc.route;

import de.droiddrone.common.MissionItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fleet PRD RT-8, B4 - B6: a planned route as the ArduPilot mission uploaded to the drone.
 * <ol start="0">
 *   <li>home (ArduPilot keeps its own there; sent for its place in the list)</li>
 *   <li>NAV_TAKEOFF to the line's first height, when the drone starts on the ground</li>
 *   <li>the way out, every waypoint, altitudes above sea level (B4)</li>
 *   <li>NAV_LOITER_UNLIM at the last point: the drone holds there and the pilot is called (B5) - "YOUR TURN"</li>
 *   <li>the way home: the route reversed (B6), entered only when the pilot or the PC sends the drone on to
 *   {@link Mission#returnSeq} (MISSION_SET_CURRENT)</li>
 *   <li>NAV_LAND where it took off; a drone sent from the air ("fly to here") holds there instead</li>
 * </ol>
 * Nothing in it uses terrain frames (RT-10).
 */
public final class MissionBuilder {
    private MissionBuilder() {
    }

    /**
     * The mission and where its parts begin: holdSeq the hold at the route's end, returnSeq the first item of the way
     * home, endSeq the last item.
     */
    public record Mission(List<MissionItem> items, int holdSeq, int returnSeq, int endSeq) {
        public Mission {
            items = Collections.unmodifiableList(new ArrayList<>(items));
        }

        public int size() {
            return items.size();
        }

        /** The same mission with every positioned altitude after home raised by dz metres (RT-12, a layer of its own). */
        public Mission raisedBy(double dz) {
            List<MissionItem> out = new ArrayList<>(items.size());
            for (MissionItem m : items) {
                boolean positioned = m.frame == MissionItem.FRAME_GLOBAL && m.seq > 0 && m.command != MissionItem.NAV_LAND;
                out.add(positioned ? new MissionItem(m.seq, m.frame, m.command, m.autocontinue, m.param1, m.param2, m.param3,
                        m.param4, m.x, m.y, (float) (m.z + dz)) : m);
            }
            return new Mission(out, holdSeq, returnSeq, endSeq);
        }
    }

    /** @param homeAmslM home's altitude above sea level (the ground there) */
    public static Mission build(RoutePlanner.Plan plan, double homeLat, double homeLon, double homeAmslM) {
        List<MissionItem> items = new ArrayList<>();
        items.add(MissionItem.at(0, MissionItem.NAV_WAYPOINT, homeLat, homeLon, homeAmslM));
        List<RoutePlanner.Waypoint> out = plan.out().waypoints();
        if (plan.takeoff()) {
            RoutePlanner.Waypoint first = out.get(0);
            items.add(MissionItem.at(items.size(), MissionItem.NAV_TAKEOFF, first.lat(), first.lon(), first.altAmslM()));
        }
        // the start is where the drone is (or took off to): the way out begins with the waypoint after it
        for (int i = 1; i < out.size() - 1; i++) {
            RoutePlanner.Waypoint w = out.get(i);
            items.add(MissionItem.at(items.size(), MissionItem.NAV_WAYPOINT, w.lat(), w.lon(), w.altAmslM()));
        }
        RoutePlanner.Waypoint last = out.get(out.size() - 1);
        int hold = items.size();
        items.add(MissionItem.at(hold, MissionItem.NAV_LOITER_UNLIM, last.lat(), last.lon(), last.altAmslM()));

        List<RoutePlanner.Waypoint> back = plan.back().waypoints();
        int ret = items.size();
        for (int i = 1; i < back.size() - 1; i++) {
            RoutePlanner.Waypoint w = back.get(i);
            items.add(MissionItem.at(items.size(), MissionItem.NAV_WAYPOINT, w.lat(), w.lon(), w.altAmslM()));
        }
        RoutePlanner.Waypoint home = back.get(back.size() - 1);
        if (plan.takeoff()) {
            // over the start at its height, then down (the land's own altitude is not used: it lands where it is)
            items.add(MissionItem.at(items.size(), MissionItem.NAV_WAYPOINT, home.lat(), home.lon(), home.altAmslM()));
            items.add(new MissionItem(items.size(), MissionItem.FRAME_GLOBAL, MissionItem.NAV_LAND, true, 0, 0, 0, Float.NaN,
                    (int) Math.round(home.lat() * 1e7), (int) Math.round(home.lon() * 1e7), 0f));
        } else {
            items.add(MissionItem.at(items.size(), MissionItem.NAV_LOITER_UNLIM, home.lat(), home.lon(), home.altAmslM()));
        }
        return new Mission(items, hold, ret, items.size() - 1);
    }
}
