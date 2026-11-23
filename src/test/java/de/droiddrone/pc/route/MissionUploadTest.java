package de.droiddrone.pc.route;

import com.MAVLink.common.msg_mission_item_int;
import com.MAVLink.enums.MAV_CMD;
import com.MAVLink.enums.MAV_FRAME;
import de.droiddrone.common.MissionItem;
import de.droiddrone.common.MissionUpload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD RT-8: the mission into ArduPilot and read back, against a virtual flight controller that answers as
 * ArduPilot's mission protocol does, on a made-up clock. Items cross as real MISSION_ITEM_INT frames (packed and read
 * back by upstream's MAVLink library). The phase 2 gate: 100 items in under 5 s with a 50 ms round trip to the drone.
 */
class MissionUploadTest {
    /** Events on a made-up clock. */
    static final class Clock {
        record Event(long at, long order, Runnable run) {}

        long now;
        long order;
        final PriorityQueue<Event> events = new PriorityQueue<>((a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : Long.compare(a.order, b.order));

        void at(long t, Runnable r) {
            events.add(new Event(t, order++, r));
        }

        /** Runs events (and the upload's 100 ms ticks) until done or the time limit. */
        void run(MissionUpload up, long limitMs) {
            for (long t = 100; t <= limitMs; t += 100) {
                long tick = t;
                at(tick, () -> up.tick(tick));
            }
            while (!events.isEmpty() && !up.done() && !up.failed()) {
                Event e = events.poll();
                if (e.at > limitMs) break;
                now = e.at;
                e.run.run();
            }
        }
    }

    /** ArduPilot's side of the mission protocol, delayMs each way from the uploader. */
    static final class VirtualFc implements MissionUpload.Link {
        final Clock clock;
        final long delayMs;
        MissionUpload up;
        final List<MissionItem> stored = new ArrayList<>();
        int expected = -1;
        int count;
        int capacity = 700;
        /** Messages to drop once, by name: "count", "item 5", "request 7", "ack". */
        final Set<String> dropOnce = new HashSet<>();
        /** Read-back changes this item's altitude (a store that went wrong). */
        int corruptOnRead = -1;
        boolean dead;
        int sent;

        VirtualFc(Clock clock, long delayMs) {
            this.clock = clock;
            this.delayMs = delayMs;
        }

        private boolean dropped(String what) {
            return dead || dropOnce.remove(what);
        }

        private void toUploader(String what, Runnable r) {
            sent++;
            if (dropped(what)) return;
            clock.at(clock.now + delayMs, r);
        }

        @Override
        public void sendCount(int n) {
            if (dropped("count")) return;
            clock.at(clock.now + delayMs, () -> {
                if (n > capacity) {
                    toUploader("ack", () -> up.onAck(4, clock.now)); // NO SPACE
                    return;
                }
                count = n;
                stored.clear();
                expected = 0;
                toUploader("request 0", () -> up.onRequest(0, clock.now));
            });
        }

        @Override
        public void sendItem(MissionItem item) {
            if (dropped("item " + item.seq)) return;
            // as the frame crosses: MISSION_ITEM_INT packed and read by the MAVLink library
            msg_mission_item_int frame = MavlinkMission.toMavlink(item, 1, 1);
            MissionItem crossed = MavlinkMission.fromMavlink(new msg_mission_item_int(frame.pack()));
            clock.at(clock.now + delayMs, () -> {
                if (crossed.seq == expected) {
                    stored.add(crossed);
                    expected++;
                }
                if (expected < count) {
                    int next = expected;
                    toUploader("request " + next, () -> up.onRequest(next, clock.now));
                } else {
                    toUploader("ack", () -> up.onAck(0, clock.now));
                }
            });
        }

        @Override
        public void sendRequestList() {
            if (dropped("list")) return;
            clock.at(clock.now + delayMs, () -> toUploader("count back", () -> up.onCount(stored.size(), clock.now)));
        }

        @Override
        public void sendRequest(int seq) {
            if (dropped("read " + seq)) return;
            clock.at(clock.now + delayMs, () -> {
                MissionItem m = stored.get(seq);
                if (seq == 0) m = MissionItem.at(0, MissionItem.NAV_WAYPOINT, 60.5, 10.5, 123); // ArduPilot answers its own home
                if (seq == corruptOnRead) m = new MissionItem(m.seq, m.frame, m.command, m.autocontinue, m.param1, m.param2, m.param3, m.param4, m.x, m.y, m.z + 1);
                MissionItem answer = m;
                toUploader("item back " + seq, () -> up.onItem(answer, clock.now));
            });
        }

        @Override
        public void sendAck(int type) {
            // the read-back's end: nothing comes back
        }
    }

    static List<MissionItem> items(int n) {
        List<MissionItem> out = new ArrayList<>();
        out.add(MissionItem.at(0, MissionItem.NAV_WAYPOINT, 60, 10, 100));
        for (int i = 1; i < n; i++) out.add(MissionItem.at(i, MissionItem.NAV_WAYPOINT, 60 + i * 1e-4, 10, 160 + i * 0.5));
        return out;
    }

    private static MissionUpload upload(VirtualFc fc, List<MissionItem> items) {
        MissionUpload up = new MissionUpload(items, fc);
        fc.up = up;
        up.start(fc.clock.now);
        return up;
    }

    @Test
    void phase2GateAHundredItemsInUnderFiveSecondsOverA50MsRoundTrip() {
        // the PC sends the whole mission to the phone in one block: one 4G trip (25 ms each way)...
        long fourG = 25;
        byte[] block = MissionItem.pack(items(100));
        List<MissionItem> onPhone = MissionItem.unpack(block, 0, block.length);
        assertEquals(100, onPhone.size());
        // ...and the phone talks to the FC over USB (a few ms each way: 2.5 ms here)
        Clock c = new Clock();
        c.now = fourG;
        VirtualFc fc = new VirtualFc(c, 3);
        MissionUpload up = upload(fc, onPhone);
        c.run(up, 20_000);
        assertTrue(up.done(), up.reason());
        long total = fourG + up.elapsedMs() + fourG; // the block there, the phone's "done" back
        assertTrue(total < 5000, "100 items up and read back in " + total + " ms");
        assertEquals(100, fc.stored.size());
        for (int i = 1; i < 100; i++) assertTrue(onPhone.get(i).sameAs(fc.stored.get(i)));
    }

    @Test
    void theSameItemByItemOverFourGWouldMissTheGate() {
        // why the phone runs the protocol: every item is a round trip
        Clock c = new Clock();
        VirtualFc fc = new VirtualFc(c, 25);
        MissionUpload up = upload(fc, items(100));
        c.run(up, 60_000);
        assertTrue(up.done(), up.reason());
        assertTrue(up.elapsedMs() > 5000, "over 4G: " + up.elapsedMs() + " ms");
    }

    @Test
    void lostMessagesAreSentAgain() {
        Clock c = new Clock();
        VirtualFc fc = new VirtualFc(c, 3);
        fc.dropOnce.addAll(List.of("count", "item 5", "request 9", "list", "read 3", "item back 7"));
        MissionUpload up = upload(fc, items(20));
        c.run(up, 30_000);
        assertTrue(up.done(), up.reason());
        assertEquals(20, fc.stored.size());
        assertTrue(up.elapsedMs() >= 6 * MissionUpload.TIMEOUT_MS, "six timeouts: " + up.elapsedMs());
    }

    @Test
    void theFcRefusingTheMissionFailsItWithTheReason() {
        Clock c = new Clock();
        VirtualFc fc = new VirtualFc(c, 3);
        fc.capacity = 10;
        MissionUpload up = upload(fc, items(20));
        c.run(up, 10_000);
        assertTrue(up.failed());
        assertEquals("the flight controller refused the mission (NO SPACE)", up.reason());
    }

    @Test
    void anItemThatReadsBackOtherwiseFailsTheUpload() {
        Clock c = new Clock();
        VirtualFc fc = new VirtualFc(c, 3);
        fc.corruptOnRead = 5;
        MissionUpload up = upload(fc, items(20));
        c.run(up, 10_000);
        assertTrue(up.failed());
        assertTrue(up.reason().startsWith("item 5 reads back other than sent"), up.reason());
    }

    @Test
    void homeIsNotComparedArduPilotKeepsItsOwn() {
        Clock c = new Clock();
        VirtualFc fc = new VirtualFc(c, 3);
        MissionUpload up = upload(fc, items(5));
        c.run(up, 10_000);
        assertTrue(up.done(), up.reason());
        assertEquals(100, up.percent());
    }

    @Test
    void aSilentFcFailsAfterTheRetries() {
        Clock c = new Clock();
        VirtualFc fc = new VirtualFc(c, 3);
        fc.dead = true;
        MissionUpload up = upload(fc, items(5));
        c.run(up, 30_000);
        assertTrue(up.failed());
        assertEquals("no answer from the flight controller during the upload", up.reason());
        assertEquals((MissionUpload.MAX_RETRIES + 1) * MissionUpload.TIMEOUT_MS, up.elapsedMs(), 200);
    }

    @Test
    void anAckBeforeTheLastItemIsAFailure() {
        MissionUpload up = new MissionUpload(items(5), new VirtualFc(new Clock(), 1));
        up.start(0);
        up.onRequest(0, 10);
        up.onAck(0, 20);
        assertTrue(up.failed());
        assertTrue(up.reason().contains("ended the upload at item 0 of 5"), up.reason());
    }

    @Test
    void theConstantsAreMavlinksOwn() {
        assertEquals(MAV_CMD.MAV_CMD_NAV_WAYPOINT, MissionItem.NAV_WAYPOINT);
        assertEquals(MAV_CMD.MAV_CMD_NAV_LOITER_UNLIM, MissionItem.NAV_LOITER_UNLIM);
        assertEquals(MAV_CMD.MAV_CMD_NAV_RETURN_TO_LAUNCH, MissionItem.NAV_RETURN_TO_LAUNCH);
        assertEquals(MAV_CMD.MAV_CMD_NAV_LAND, MissionItem.NAV_LAND);
        assertEquals(MAV_CMD.MAV_CMD_NAV_TAKEOFF, MissionItem.NAV_TAKEOFF);
        assertEquals(MAV_CMD.MAV_CMD_DO_CHANGE_SPEED, MissionItem.DO_CHANGE_SPEED);
        assertEquals(MAV_FRAME.MAV_FRAME_GLOBAL, MissionItem.FRAME_GLOBAL);
        assertEquals(MAV_FRAME.MAV_FRAME_MISSION, MissionItem.FRAME_MISSION);
    }

    @Test
    void theBlockRoundTripsAndRefusesACutOne() {
        List<MissionItem> in = items(7);
        byte[] b = MissionItem.pack(in);
        assertEquals(2 + 7 * MissionItem.PACKED_SIZE, b.length);
        List<MissionItem> out = MissionItem.unpack(b, 0, b.length);
        for (int i = 0; i < 7; i++) assertTrue(in.get(i).sameAs(out.get(i)));
        assertNull(MissionItem.unpack(b, 0, b.length - 1));
        assertNull(MissionItem.unpack(new byte[]{(byte) 0xff, (byte) 0xff}, 0, 2), "65535 items: over the most");
    }
}
