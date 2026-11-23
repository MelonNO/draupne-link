package de.droiddrone.pc.route;

import de.droiddrone.common.MissionBlock;
import de.droiddrone.common.MissionItem;
import de.droiddrone.common.MissionUpload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet AP-9 / AC-10: fences and rally points go up and are read back like a route, each in its own list. */
class MissionTypesTest {
    static List<MissionItem> fence() {
        List<MissionItem> l = new ArrayList<>();
        for (int i = 0; i < 4; i++) l.add(new MissionItem(i, 5, 5002, true, 4, 0, 0, 0, 600000000 + i * 1000, 100000000, 0));
        return l;
    }

    @Test
    void theBlockCarriesItsType() {
        byte[] p = MissionBlock.packet(12, MissionUpload.TYPE_FENCE, fence());
        MissionBlock b = MissionBlock.read(p, 1, p.length - 1);
        assertEquals(MissionUpload.TYPE_FENCE, b.missionType);
        assertEquals(4, b.items.size());
        byte[] r = MissionBlock.packet(13, List.of(MissionItem.at(0, MissionItem.NAV_WAYPOINT, 60, 10, 100)));
        assertEquals(MissionUpload.TYPE_MISSION, MissionBlock.read(r, 1, r.length - 1).missionType, "a route by default");
        p[3] = 9;
        assertNull(MissionBlock.read(p, 1, p.length - 1), "a type ArduPilot hasn't");
    }

    /** A made-up FC answering for one list. */
    static MissionUpload run(List<MissionItem> items, int type, boolean changeFirst) {
        List<MissionItem> onFc = new ArrayList<>();
        MissionUpload[] u = new MissionUpload[1];
        u[0] = new MissionUpload(items, new MissionUpload.Link() {
            int count;

            public void sendCount(int n) {
                count = n;
                if (n == 0) u[0].onAck(0, 1);
                else u[0].onRequest(0, 1);
            }

            public void sendItem(MissionItem m) {
                onFc.add(changeFirst && m.seq == 0 ? new MissionItem(0, m.frame, m.command, m.autocontinue, m.param1, m.param2, m.param3,
                        m.param4, m.x + 5000, m.y, m.z) : m);
                if (m.seq + 1 < count) u[0].onRequest(m.seq + 1, 1);
                else u[0].onAck(0, 1);
            }

            public void sendRequestList() {
                u[0].onCount(onFc.size(), 1);
            }

            public void sendRequest(int seq) {
                u[0].onItem(onFc.get(seq), 1);
            }

            public void sendAck(int t) {
            }
        }, type);
        u[0].start(0);
        return u[0];
    }

    @Test
    void aFencesFirstItemIsComparedARoutesIsNot() {
        assertTrue(run(fence(), MissionUpload.TYPE_FENCE, false).done());
        assertTrue(run(fence(), MissionUpload.TYPE_FENCE, true).failed(), "a fence has no home at item 0: every item counts");
        assertTrue(run(fence(), MissionUpload.TYPE_MISSION, true).done(), "a route's item 0 is the FC's own home");
        assertEquals(MissionUpload.TYPE_RALLY, run(fence(), MissionUpload.TYPE_RALLY, false).missionType());
        assertTrue(run(List.of(), MissionUpload.TYPE_RALLY, false).done(), "no rally points: an empty list clears them");
        assertThrows(IllegalArgumentException.class, () -> run(List.of(), MissionUpload.TYPE_MISSION, false));
    }
}
