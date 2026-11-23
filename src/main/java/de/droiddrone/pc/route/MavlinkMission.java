package de.droiddrone.pc.route;

import com.MAVLink.common.msg_mission_item_int;
import de.droiddrone.common.MissionItem;

/**
 * Fleet PRD RT-8: a {@link MissionItem} as MAVLink's MISSION_ITEM_INT and back (upstream's MAVLink library, the one
 * the phone uses). The virtual drones' FC answers with these; the phone builds the same frames.
 */
public final class MavlinkMission {
    /** MAV_MISSION_TYPE_MISSION. */
    public static final short MISSION_TYPE = 0;

    private MavlinkMission() {
    }

    public static msg_mission_item_int toMavlink(MissionItem m, int targetSystem, int targetComponent) {
        msg_mission_item_int f = new msg_mission_item_int();
        f.param1 = m.param1;
        f.param2 = m.param2;
        f.param3 = m.param3;
        f.param4 = m.param4;
        f.x = m.x;
        f.y = m.y;
        f.z = m.z;
        f.seq = m.seq;
        f.command = m.command;
        f.target_system = (short) targetSystem;
        f.target_component = (short) targetComponent;
        f.frame = (short) m.frame;
        f.current = 0;
        f.autocontinue = (short) (m.autocontinue ? 1 : 0);
        f.mission_type = MISSION_TYPE;
        return f;
    }

    public static MissionItem fromMavlink(msg_mission_item_int f) {
        return new MissionItem(f.seq, f.frame, f.command, f.autocontinue != 0, f.param1, f.param2, f.param3, f.param4,
                f.x, f.y, f.z);
    }
}
