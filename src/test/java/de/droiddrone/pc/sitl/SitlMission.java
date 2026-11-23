package de.droiddrone.pc.sitl;

import com.MAVLink.MAVLinkPacket;
import de.droiddrone.common.MissionItem;
import de.droiddrone.common.MissionUpload;
import de.droiddrone.pc.setup.ardu.MavSession;

import java.util.function.Supplier;

/** The phone's route upload (MissionUpload) wired to a MavSession, as DRAUPNE Flight's Mavlink wires it to USB. */
final class SitlMission {
    private SitlMission() {
    }

    static MissionUpload.Link link(MavSession fc) {
        short gcs = (short) MavSession.GCS_SYSID;
        return new MissionUpload.Link() {
            short t() {
                return (short) Math.max(1, fc.fcSysId());
            }

            void send(MAVLinkPacket p) {
                try {
                    fc.send(p);
                } catch (java.io.IOException ignored) {
                    // the upload asks again
                }
            }

            public void sendCount(int count) {
                send(new com.MAVLink.common.msg_mission_count(count, t(), (short) 1, (short) 0, 0L, gcs, MavSession.GCS_COMPID, true).pack());
            }

            public void sendItem(MissionItem m) {
                send(new com.MAVLink.common.msg_mission_item_int(m.param1, m.param2, m.param3, m.param4, m.x, m.y, m.z, m.seq, m.command,
                        t(), (short) 1, (short) m.frame, (short) 0, (short) (m.autocontinue ? 1 : 0), (short) 0, gcs, MavSession.GCS_COMPID, true).pack());
            }

            public void sendRequestList() {
                send(new com.MAVLink.common.msg_mission_request_list(t(), (short) 1, (short) 0, gcs, MavSession.GCS_COMPID, true).pack());
            }

            public void sendRequest(int seq) {
                send(new com.MAVLink.common.msg_mission_request_int(seq, t(), (short) 1, (short) 0, gcs, MavSession.GCS_COMPID, true).pack());
            }

            public void sendAck(int type) {
                send(new com.MAVLink.common.msg_mission_ack(t(), (short) 1, (short) type, (short) 0, 0L, gcs, MavSession.GCS_COMPID, true).pack());
            }
        };
    }

    /** The FC's mission answers to the upload under way (the phone's Mavlink.missionFromFc). */
    static void feed(MavSession fc, Supplier<MissionUpload> upload) {
        fc.onPacket(p -> {
            MissionUpload u = upload.get();
            if (u == null) return;
            long now = System.currentTimeMillis();
            switch (p.msgid) {
                case com.MAVLink.common.msg_mission_request_int.MAVLINK_MSG_ID_MISSION_REQUEST_INT ->
                        u.onRequest(new com.MAVLink.common.msg_mission_request_int(p).seq, now);
                case com.MAVLink.common.msg_mission_request.MAVLINK_MSG_ID_MISSION_REQUEST ->
                        u.onRequest(new com.MAVLink.common.msg_mission_request(p).seq, now);
                case com.MAVLink.common.msg_mission_ack.MAVLINK_MSG_ID_MISSION_ACK -> u.onAck(new com.MAVLink.common.msg_mission_ack(p).type, now);
                case com.MAVLink.common.msg_mission_count.MAVLINK_MSG_ID_MISSION_COUNT -> u.onCount(new com.MAVLink.common.msg_mission_count(p).count, now);
                case com.MAVLink.common.msg_mission_item_int.MAVLINK_MSG_ID_MISSION_ITEM_INT -> {
                    com.MAVLink.common.msg_mission_item_int m = new com.MAVLink.common.msg_mission_item_int(p);
                    u.onItem(new MissionItem(m.seq, m.frame, m.command, m.autocontinue != 0, m.param1, m.param2, m.param3, m.param4,
                            m.x, m.y, m.z), now);
                }
                default -> {
                }
            }
        });
    }
}
