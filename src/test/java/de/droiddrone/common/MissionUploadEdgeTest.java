package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MissionUpload driven message by message on a made-up clock: the cases a real flight controller and a lossy USB make -
 * answers out of order, duplicates, a lost last MISSION_ACK, MISSION_ACK errors, NaN parameters, all three mission
 * types and the empty fence / rally list.
 */
class MissionUploadEdgeTest {
    /** What the upload sent, as words: "count 3", "item 2", "list", "request 1", "ack 0". */
    static final class Sent implements MissionUpload.Link {
        final List<String> out = new ArrayList<>();

        @Override
        public void sendCount(int count) {
            out.add("count " + count);
        }

        @Override
        public void sendItem(MissionItem item) {
            out.add("item " + item.seq);
        }

        @Override
        public void sendRequestList() {
            out.add("list");
        }

        @Override
        public void sendRequest(int seq) {
            out.add("request " + seq);
        }

        @Override
        public void sendAck(int type) {
            out.add("ack " + type);
        }

        String last() {
            return out.isEmpty() ? "" : out.get(out.size() - 1);
        }
    }

    static List<MissionItem> items(int n) {
        List<MissionItem> r = new ArrayList<>();
        for (int i = 0; i < n; i++) r.add(MissionItem.at(i, MissionItem.NAV_WAYPOINT, 59.9 + i * 1e-4, 10.7, 100 + i));
        return r;
    }

    /** Uploaded and read back cleanly from t. */
    static void readBack(MissionUpload u, List<MissionItem> items, long t) {
        u.onCount(items.size(), t);
        for (MissionItem m : items) u.onItem(m, t);
    }

    @Test
    void anEmptyFenceOrRallyListNeverDividesByZero() {
        for (int type : new int[]{MissionUpload.TYPE_FENCE, MissionUpload.TYPE_RALLY}) {
            Sent s = new Sent();
            MissionUpload u = new MissionUpload(new ArrayList<>(), s, type);
            assertEquals(0, u.percent(), "before the start");
            u.start(0);
            assertEquals(0, u.percent(), "uploading nothing: the phone says its percent at once");
            assertEquals("count 0", s.last());
            u.onAck(MissionUpload.ACK_ACCEPTED, 10);
            assertEquals(MissionUpload.READING_BACK, u.state());
            assertEquals(0, u.percent());
            u.onCount(0, 20);
            assertTrue(u.done());
            assertEquals(100, u.percent());
        }
    }

    @Test
    void anFcThatAsksOutOfOrderAndTwiceStillGetsTheRoute() {
        Sent s = new Sent();
        List<MissionItem> it = items(4);
        MissionUpload u = new MissionUpload(it, s);
        u.start(0);
        u.onRequest(0, 5);
        u.onRequest(2, 6); // out of order
        u.onRequest(1, 7);
        u.onRequest(1, 8); // asked twice
        u.onRequest(3, 9);
        assertEquals(List.of("count 4", "item 0", "item 2", "item 1", "item 1", "item 3"), s.out);
        u.onAck(MissionUpload.ACK_ACCEPTED, 10);
        u.onAck(MissionUpload.ACK_ACCEPTED, 11); // a duplicate ack changes nothing
        assertEquals(MissionUpload.READING_BACK, u.state());
        u.onCount(4, 12);
        u.onCount(4, 13); // duplicate count: one read-back
        u.onItem(it.get(0), 14);
        u.onItem(it.get(0), 15); // a duplicate item is not taken as the next
        u.onItem(it.get(2), 16); // out of order: ignored, asked again by the timeout
        assertEquals(MissionUpload.READING_BACK, u.state());
        u.onItem(it.get(1), 17);
        u.onItem(it.get(2), 18);
        u.onItem(it.get(3), 19);
        assertTrue(u.done(), u.reason());
        assertEquals("ack 0", s.last());
    }

    /**
     * ArduPilot answers an item it didn't ask for (our resend after a silence, or our answer to its own re-request)
     * with MISSION_ACK INVALID_SEQUENCE and goes on with the upload: it is no end of it.
     */
    @Test
    void anInvalidSequenceAckMidUploadIsNotTheEnd() {
        Sent s = new Sent();
        List<MissionItem> it = items(3);
        MissionUpload u = new MissionUpload(it, s);
        u.start(0);
        u.onRequest(0, 5);
        u.onRequest(1, 6);
        u.onRequest(1, 7); // the FC asked twice: item 1 goes twice, the second is out of turn for it
        u.onAck(13, 8); // MAV_MISSION_INVALID_SEQUENCE
        assertEquals(MissionUpload.UPLOADING, u.state(), u.reason());
        u.onRequest(2, 9);
        u.onAck(MissionUpload.ACK_ACCEPTED, 10);
        readBack(u, it, 11);
        assertTrue(u.done(), u.reason());
    }

    @Test
    void realRefusalsEndTheUpload() {
        for (int err : new int[]{1, 2, 3, 4, 5, 10, 14, 15}) {
            MissionUpload u = new MissionUpload(items(2), new Sent());
            u.start(0);
            u.onRequest(0, 1);
            u.onAck(err, 2);
            assertTrue(u.failed(), "ack " + err);
            assertTrue(u.reason().contains(MissionUpload.ackName(err)), u.reason());
        }
    }

    /** The FC took every item and its MISSION_ACK was lost: the read-back says whether the route is there. */
    @Test
    void aLostLastAckGoesToTheReadBack() {
        Sent s = new Sent();
        List<MissionItem> it = items(3);
        MissionUpload u = new MissionUpload(it, s);
        u.start(0);
        for (int i = 0; i < 3; i++) u.onRequest(i, 10 + i);
        // no ack
        u.tick(10 + 2 + MissionUpload.TIMEOUT_MS);
        assertEquals(MissionUpload.READING_BACK, u.state());
        assertEquals("list", s.last());
        readBack(u, it, 2000);
        assertTrue(u.done(), u.reason());
    }

    @Test
    void aSilentFcFailsAfterItsRetriesAndNoLater() {
        Sent s = new Sent();
        MissionUpload u = new MissionUpload(items(2), s);
        u.start(0);
        long t = 0;
        int ticks = 0;
        while (!u.failed() && ticks++ < 1000) u.tick(t += 100);
        assertTrue(u.failed());
        assertEquals("count 2", s.last(), "the count was asked again");
        assertEquals(1 + MissionUpload.MAX_RETRIES, s.out.size());
        assertTrue(t <= (MissionUpload.MAX_RETRIES + 1) * MissionUpload.TIMEOUT_MS + 100, "failed by " + t);
        u.onRequest(0, t + 1); // too late: nothing more is sent
        assertEquals(1 + MissionUpload.MAX_RETRIES, s.out.size());
    }

    @Test
    void aRequestPastTheEndFails() {
        MissionUpload u = new MissionUpload(items(2), new Sent());
        u.start(0);
        u.onRequest(2, 1);
        assertTrue(u.failed());
        MissionUpload v = new MissionUpload(items(2), new Sent());
        v.start(0);
        v.onRequest(-1, 1);
        assertTrue(v.failed());
    }

    @Test
    void anAcceptedAckBeforeEveryItemWasAskedFails() {
        MissionUpload u = new MissionUpload(items(3), new Sent());
        u.start(0);
        u.onRequest(0, 1);
        u.onAck(MissionUpload.ACK_ACCEPTED, 2);
        assertTrue(u.failed());
    }

    @Test
    void nanParametersReadBackAsZeroAndAWrongPlaceFails() {
        List<MissionItem> it = items(3);
        // the yaw NaN ("as it is"): ArduPilot stores it and reads 0 back
        assertTrue(Float.isNaN(it.get(1).param4));
        Sent s = new Sent();
        MissionUpload u = new MissionUpload(it, s);
        u.start(0);
        for (int i = 0; i < 3; i++) u.onRequest(i, 1);
        u.onAck(0, 2);
        u.onCount(3, 3);
        u.onItem(it.get(0), 4);
        MissionItem b = it.get(1);
        u.onItem(new MissionItem(1, b.frame, b.command, b.autocontinue, b.param1, b.param2, b.param3, 0f, b.x, b.y, b.z), 5);
        MissionItem c = it.get(2);
        u.onItem(new MissionItem(2, c.frame, c.command, c.autocontinue, c.param1, c.param2, c.param3, Float.NaN, c.x + 1, c.y, c.z), 6);
        assertTrue(u.failed());
        assertTrue(u.reason().contains("item 2"), u.reason());
        // a number sent as a number never matches NaN read back
        MissionItem p = new MissionItem(0, 0, 16, true, 1, 0, 0, 0, 1, 1, 1);
        assertFalse(p.sameAs(new MissionItem(0, 0, 16, true, Float.NaN, 0, 0, 0, 1, 1, 1)));
    }

    /** A fence's and the rally points' item 0 are theirs, not ArduPilot's home: compared too. */
    @Test
    void fenceAndRallyItemZeroIsCompared() {
        for (int type : new int[]{MissionUpload.TYPE_FENCE, MissionUpload.TYPE_RALLY}) {
            List<MissionItem> it = items(2);
            MissionUpload u = new MissionUpload(it, new Sent(), type);
            u.start(0);
            u.onRequest(0, 1);
            u.onRequest(1, 1);
            u.onAck(0, 2);
            u.onCount(2, 3);
            MissionItem a = it.get(0);
            u.onItem(new MissionItem(0, a.frame, a.command, true, 0, 0, 0, Float.NaN, a.x + 100, a.y, a.z), 4);
            assertTrue(u.failed(), "type " + type);
        }
        // a route's item 0 is the FC's home: not compared
        List<MissionItem> it = items(2);
        MissionUpload u = new MissionUpload(it, new Sent());
        u.start(0);
        u.onRequest(0, 1);
        u.onRequest(1, 1);
        u.onAck(0, 2);
        u.onCount(2, 3);
        MissionItem a = it.get(0);
        u.onItem(new MissionItem(0, a.frame, a.command, true, 0, 0, 0, Float.NaN, a.x + 100, a.y, a.z + 50), 4);
        u.onItem(it.get(1), 5);
        assertTrue(u.done(), u.reason());
    }

    /** Seen in ArduPilot SITL 4.7.3: frames come back as their plain twins (6 as 3), a placeless item's MISSION as GLOBAL. */
    @Test
    void framesReadBackAsArduPilotStoresThem() {
        List<MissionItem> rally = List.of(new MissionItem(0, 6, 5100, true, 0, 0, 0, 0, 599000000, 107000000, 40));
        MissionUpload u = new MissionUpload(rally, new Sent(), MissionUpload.TYPE_RALLY);
        u.start(0);
        u.onRequest(0, 1);
        u.onAck(0, 2);
        u.onCount(1, 3);
        u.onItem(new MissionItem(0, 3, 5100, true, 0, 0, 0, 0, 599000000, 107000000, 40), 4);
        assertTrue(u.done(), u.reason());
        MissionItem speed = new MissionItem(3, MissionItem.FRAME_MISSION, MissionItem.DO_CHANGE_SPEED, true, 1, 8, -1, 0, 0, 0, 0);
        assertTrue(speed.sameAs(new MissionItem(3, 0, MissionItem.DO_CHANGE_SPEED, true, 1, 8, -1, 0, 0, 0, 0)));
        assertTrue(new MissionItem(1, 5, 16, true, 0, 0, 0, 0, 1, 1, 1).sameAs(new MissionItem(1, 0, 16, true, 0, 0, 0, 0, 1, 1, 1)));
        assertFalse(new MissionItem(1, 6, 16, true, 0, 0, 0, 0, 1, 1, 1).sameAs(new MissionItem(1, 0, 16, true, 0, 0, 0, 0, 1, 1, 1)),
                "above home is not above the sea");
        MissionItem land = new MissionItem(6, 0, MissionItem.NAV_LAND, true, 0, 0, 0, Float.NaN, 1, 1, 1);
        assertTrue(land.sameAs(new MissionItem(6, 0, MissionItem.NAV_LAND, true, 0, 0, 0, 1f, 1, 1, 1)), "NAV_LAND's NaN reads back 1");
        assertFalse(new MissionItem(1, 10, 16, true, 0, 0, 0, 0, 1, 1, 1).sameAs(new MissionItem(1, 3, 16, true, 0, 0, 0, 0, 1, 1, 1)));
    }

    @Test
    void aReadBackCountOtherThanSentFails() {
        MissionUpload u = new MissionUpload(items(3), new Sent());
        u.start(0);
        for (int i = 0; i < 3; i++) u.onRequest(i, 1);
        u.onAck(0, 2);
        u.onCount(2, 3);
        assertTrue(u.failed());
    }

    @Test
    void anEmptyRouteIsRefusedButAnEmptyFenceIsNot() {
        assertThrows(IllegalArgumentException.class, () -> new MissionUpload(new ArrayList<>(), new Sent()));
        assertThrows(IllegalArgumentException.class, () -> new MissionUpload(null, new Sent(), MissionUpload.TYPE_FENCE));
        assertDoesNotThrow(() -> new MissionUpload(new ArrayList<>(), new Sent(), MissionUpload.TYPE_RALLY));
    }

    @Test
    void messagesBeforeTheStartOrAfterTheEndChangeNothing() {
        Sent s = new Sent();
        List<MissionItem> it = items(2);
        MissionUpload u = new MissionUpload(it, s);
        u.onRequest(0, 0);
        u.onAck(0, 0);
        u.onCount(2, 0);
        u.tick(10_000);
        assertTrue(s.out.isEmpty());
        assertEquals(0, u.state());
        u.start(20_000);
        u.onRequest(0, 20_001);
        u.onRequest(1, 20_002);
        u.onAck(0, 20_003);
        readBack(u, it, 20_004);
        assertTrue(u.done());
        int n = s.out.size();
        u.onRequest(0, 20_005);
        u.onAck(4, 20_006);
        u.tick(90_000);
        assertTrue(u.done());
        assertEquals(n, s.out.size());
        assertEquals(4, u.elapsedMs());
    }
}
