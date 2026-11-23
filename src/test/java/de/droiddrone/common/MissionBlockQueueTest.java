package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One upload at a time on the phone: a route and a fence sent together, a block repeated after its upload ended and the
 * phone's own rally upload followed, a late copy of an older block, a mission upload while the follower's own rally
 * points wait. The FC answers at once (a made-up one that takes every list whole).
 */
class MissionBlockQueueTest {
    /** A flight controller that takes every list whole and reads it back; counts the lists it was given. */
    static final class Fc {
        final List<String> started = new ArrayList<>();
        final List<List<MissionItem>> onFc = new ArrayList<>(List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
        boolean answer = true;

        MissionBlockQueue.Maker maker() {
            return (type, items) -> {
                MissionUpload[] self = new MissionUpload[1];
                MissionUpload u = new MissionUpload(items, new MissionUpload.Link() {
                    @Override
                    public void sendCount(int count) {
                        started.add(type + ":" + count);
                    }

                    @Override
                    public void sendItem(MissionItem item) {
                    }

                    @Override
                    public void sendRequestList() {
                    }

                    @Override
                    public void sendRequest(int seq) {
                    }

                    @Override
                    public void sendAck(int t) {
                    }
                }, type);
                self[0] = u;
                return u;
            };
        }

        /** The FC runs the upload under way to its end (the protocol as MissionUploadEdgeTest drives it). */
        void finish(MissionBlockQueue q, long t) {
            MissionUpload u = q.current();
            if (u == null || u.done() || u.failed()) return;
            List<MissionItem> it = u.items();
            for (int i = 0; i < it.size(); i++) u.onRequest(i, t);
            u.onAck(MissionUpload.ACK_ACCEPTED, t);
            u.onCount(it.size(), t);
            for (MissionItem m : it) u.onItem(m, t);
            assertTrue(u.done(), u.reason());
            onFc.set(u.missionType(), it);
        }
    }

    static MissionBlock block(int id, int type, int n) {
        List<MissionItem> r = new ArrayList<>();
        for (int i = 0; i < n; i++) r.add(MissionItem.at(i, MissionItem.NAV_WAYPOINT, 59.9 + id * 1e-3 + i * 1e-4, 10.7, 100));
        byte[] p = MissionBlock.packet(id, type, r);
        return MissionBlock.read(p, 1, p.length - 1);
    }

    /** A route and a fence sent together restarted each other (one upload, replaced by the other each second). */
    @Test
    void aRouteAndAFenceTogetherBothArrive() {
        Fc fc = new Fc();
        MissionBlockQueue q = new MissionBlockQueue(fc.maker());
        assertTrue(q.offer(block(100, MissionUpload.TYPE_MISSION, 5), 0));
        assertTrue(q.offer(block(101, MissionUpload.TYPE_FENCE, 4), 10), "answered: waits its turn");
        assertArrayEquals(new int[]{MissionUpload.UPLOADING, 0}, q.status(101));
        // the PC repeats both each second meanwhile: nothing starts over
        for (int i = 0; i < 3; i++) {
            q.offer(block(100, MissionUpload.TYPE_MISSION, 5), 1000 + i);
            q.offer(block(101, MissionUpload.TYPE_FENCE, 4), 1000 + i);
        }
        assertEquals(List.of("0:5"), fc.started);
        fc.finish(q, 2000);
        assertArrayEquals(new int[]{MissionUpload.DONE, 100}, q.status(100));
        assertTrue(q.tick(2100), "the fence starts");
        assertEquals(List.of("0:5", "1:4"), fc.started);
        fc.finish(q, 2200);
        q.tick(2300);
        assertArrayEquals(new int[]{MissionUpload.DONE, 100}, q.status(101));
        assertEquals(5, fc.onFc.get(0).size());
        assertEquals(4, fc.onFc.get(1).size());
    }

    @Test
    void aNewerBlockOfTheSameListReplacesTheRunningOne() {
        Fc fc = new Fc();
        MissionBlockQueue q = new MissionBlockQueue(fc.maker());
        q.offer(block(1, MissionUpload.TYPE_MISSION, 5), 0);
        q.offer(block(2, MissionUpload.TYPE_MISSION, 3), 10);
        assertEquals(2, q.currentBlock());
        assertEquals(List.of("0:5", "0:3"), fc.started);
        // and of a waiting list: the newer waits in its place
        q.offer(block(3, MissionUpload.TYPE_FENCE, 2), 20);
        q.offer(block(4, MissionUpload.TYPE_FENCE, 6), 30);
        assertEquals(MissionUpload.FAILED, q.status(3)[0], "replaced while it waited");
        assertEquals(MissionUpload.FAILED, q.status(1)[0], "replaced while it ran");
        assertTrue(q.reason(1).contains("replaced"));
        fc.finish(q, 40);
        q.tick(50);
        assertEquals(4, q.currentBlock());
    }

    /** The block repeated after its upload ended - and after the follower's own rally upload followed. */
    @Test
    void theSameBlockLaterIsAnsweredNeverUploadedAgain() {
        Fc fc = new Fc();
        MissionBlockQueue q = new MissionBlockQueue(fc.maker());
        q.offer(block(7, MissionUpload.TYPE_MISSION, 5), 0);
        fc.finish(q, 10);
        q.tick(20);
        List<MissionItem> rally = List.of(new MissionItem(0, 6, 5100, true, 0, 0, 0, 0, 599000000, 107000000, 40));
        q.ownRally(rally, 30);
        assertEquals(MissionBlockQueue.OWN, q.currentBlock());
        assertArrayEquals(new int[]{7, MissionUpload.DONE, 100}, q.pcStatus(), "the fleet report names the PC's route, done");
        assertTrue(q.offer(block(7, MissionUpload.TYPE_MISSION, 5), 40), "answered with its status");
        assertEquals(MissionBlockQueue.OWN, q.currentBlock(), "the own upload goes on");
        assertEquals(List.of("0:5", "2:1"), fc.started, "the route was not uploaded again");
        fc.finish(q, 50);
        q.tick(60);
        q.ownRally(rally, 70);
        assertEquals(List.of("0:5", "2:1"), fc.started, "the same rally points: not again");
    }

    /** A late or replayed copy of an older block after a newer one: the FC keeps the newer route. */
    @Test
    void aReplayedOlderBlockIsNotUploaded() {
        Fc fc = new Fc();
        MissionBlockQueue q = new MissionBlockQueue(fc.maker());
        q.offer(block(10, MissionUpload.TYPE_MISSION, 5), 0);
        fc.finish(q, 10);
        q.offer(block(11, MissionUpload.TYPE_MISSION, 3), 20);
        fc.finish(q, 30);
        assertTrue(q.offer(block(10, MissionUpload.TYPE_MISSION, 5), 40), "answered with its old outcome");
        assertEquals(MissionUpload.DONE, q.status(10)[0]);
        assertEquals(11, q.currentBlock());
        assertEquals(3, fc.onFc.get(0).size());
        assertEquals(List.of("0:5", "0:3"), fc.started);
    }

    @Test
    void theOwnRallyWaitsForThePcsUploadAndThePcsBlockWaitsForTheOwn() {
        Fc fc = new Fc();
        MissionBlockQueue q = new MissionBlockQueue(fc.maker());
        List<MissionItem> rally = List.of(new MissionItem(0, 6, 5100, true, 0, 0, 0, 0, 599000000, 107000000, 40));
        q.ownRally(rally, 0);
        assertEquals(MissionBlockQueue.OWN, q.currentBlock());
        // a PC route while the own rally runs: it waits, not replaces (the own list is another)
        q.offer(block(20, MissionUpload.TYPE_MISSION, 4), 10);
        assertEquals(MissionBlockQueue.OWN, q.currentBlock());
        assertArrayEquals(new int[]{MissionUpload.UPLOADING, 0}, q.status(20));
        // a PC rally block while the own rally runs: waits too (the PC's after the phone's)
        q.offer(block(21, MissionUpload.TYPE_RALLY, 2), 20);
        fc.finish(q, 30);
        q.tick(40);
        assertEquals(20, q.currentBlock());
        q.ownRally(List.of(new MissionItem(0, 6, 5100, true, 0, 0, 0, 0, 599100000, 107000000, 40)), 50);
        fc.finish(q, 60);
        q.tick(70);
        assertEquals(21, q.currentBlock(), "the PC's waiting blocks first");
        fc.finish(q, 80);
        q.tick(90);
        assertEquals(MissionBlockQueue.OWN, q.currentBlock(), "then the phone's own");
    }

    @Test
    void aFailedUploadIsReportedAndTheNextStarts() {
        Fc fc = new Fc();
        MissionBlockQueue q = new MissionBlockQueue(fc.maker());
        q.offer(block(30, MissionUpload.TYPE_MISSION, 2), 0);
        q.offer(block(31, MissionUpload.TYPE_FENCE, 2), 0);
        long t = 0;
        int ended = MissionBlockQueue.OWN;
        while (q.currentBlock() == 30 && t < 60_000) {
            q.tick(t += 100); // the FC never answers
            if (q.lastEnded() != MissionBlockQueue.OWN) ended = q.lastEnded();
        }
        assertEquals(30, ended, "its end is said");
        assertEquals(MissionUpload.FAILED, q.status(30)[0]);
        assertTrue(q.reason(30).contains("no answer"), q.reason(30));
        assertTrue(q.offer(block(30, MissionUpload.TYPE_MISSION, 2), t), "a copy: its outcome, not a new upload");
        assertEquals(31, q.currentBlock());
        assertEquals(31, q.currentBlock());
        assertArrayEquals(new int[]{31, MissionUpload.UPLOADING, 0}, q.pcStatus());
        assertNull(q.status(99));
        assertFalse(q.offer(null, 0));
    }
}
