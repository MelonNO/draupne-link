package de.droiddrone.pc.route;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.MissionBlock;
import de.droiddrone.common.MissionBlockQueue;
import de.droiddrone.common.MissionItem;
import de.droiddrone.common.MissionUpload;
import de.droiddrone.common.TelemetryData;
import de.droiddrone.common.TelemetryRecords;
import de.droiddrone.pc.FcTelemetry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The PC's sender against the phone's real upload queue (MissionBlockQueue): a route and a fence sent at once (the
 * pilot presses UPLOAD, then FENCE), and the phone's one DONE status lost on the way.
 */
class RouteSenderEdgeTest {
    /** The phone: its upload queue, ticked 10 times a second, its FC answering at once; statuses into the PC's telemetry. */
    static final class Phone implements AutoCloseable {
        final FcTelemetry pcSide = new FcTelemetry();
        final List<List<MissionItem>> onFc = new ArrayList<>(List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
        final MissionBlockQueue queue = new MissionBlockQueue(this::make);
        final MissionBlock.Assembler parts = new MissionBlock.Assembler();
        /** DONE statuses to lose on the way, by count. */
        volatile int loseDone;
        /** The FC answers only after this many ticks of each upload (so two uploads overlap). */
        final int slowTicks;
        private final Thread ticker;
        private volatile boolean open = true;
        private int saidBlock = -1, saidState = -1;

        Phone(int slowTicks) {
            this.slowTicks = slowTicks;
            ticker = new Thread(() -> {
                long t = 0;
                while (open) {
                    synchronized (this) {
                        queue.tick(t += 100);
                        if (queue.lastEnded() != MissionBlockQueue.OWN) say(queue.lastEnded());
                        int cur = queue.currentBlock();
                        if (cur != MissionBlockQueue.OWN) {
                            int[] s = queue.status(cur);
                            if (s != null && (cur != saidBlock || s[0] != saidState)) say(cur);
                        }
                    }
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "phoneTicker");
            ticker.setDaemon(true);
            ticker.start();
        }

        MissionUpload make(int type, List<MissionItem> items) {
            MissionUpload[] self = new MissionUpload[1];
            int[] waited = {0};
            self[0] = new MissionUpload(items, new MissionUpload.Link() {
                int count;
                final List<MissionItem> got = new ArrayList<>();

                public void sendCount(int n) {
                    count = n;
                    got.clear();
                    // answered on a later tick: the next timeout resends it, and that one is answered
                }

                public void sendItem(MissionItem m) {
                    got.add(m);
                    if (m.seq + 1 < count) self[0].onRequest(m.seq + 1, 1);
                    else {
                        onFc.set(type, new ArrayList<>(got));
                        self[0].onAck(0, 1);
                    }
                }

                public void sendRequestList() {
                    self[0].onCount(onFc.get(type).size(), 1);
                }

                public void sendRequest(int seq) {
                    self[0].onItem(onFc.get(type).get(seq), 1);
                }

                public void sendAck(int t) {
                }
            }, type);
            // the FC starts asking after slowTicks of the phone's 100 ms ticks: drives it from the ticker
            return new Delayed(self[0], slowTicks, items.size()).upload;
        }

        /** Starts the FC's answers once the upload has run its ticks. */
        final class Delayed {
            final MissionUpload upload;

            Delayed(MissionUpload u, int ticks, int n) {
                upload = u;
                Thread t = new Thread(() -> {
                    try {
                        while (u.state() == 0) Thread.sleep(5);
                        Thread.sleep(ticks * 10L);
                        synchronized (Phone.this) {
                            if (n == 0) u.onAck(0, 1);
                            else u.onRequest(0, 1);
                        }
                    } catch (InterruptedException ignored) {
                    }
                });
                t.setDaemon(true);
                t.start();
            }
        }

        synchronized boolean take(byte[] packet) {
            MissionBlock.Part part = MissionBlock.readPart(packet, 1, packet.length - 1);
            assertNotNull(part);
            MissionBlock b = parts.offer(part);
            if (b == null) { // as the phone's missionPart: every part is answered (a block it has: its status)
                say(part.blockId);
                return true;
            }
            if (queue.offer(b, 0)) say(b.blockId);
            return true;
        }

        void say(int block) {
            int[] s = queue.status(block);
            if (s == null) return;
            saidBlock = block;
            saidState = s[0];
            if (s[0] == MissionUpload.DONE && loseDone > 0) {
                loseDone--;
                return; // lost on the way
            }
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                TelemetryRecords.write(new TelemetryData(MissionBlock.STATUS_RECORD, MissionBlock.status(block, s[0], s[1], queue.reason(block))),
                        RouteToDroneTest.ARDU, new DataOutputStream(bytes));
                pcSide.parse(new DataReader(bytes.toByteArray(), true), RouteToDroneTest.ARDU);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
            open = false;
            ticker.interrupt();
        }
    }

    /** UPLOAD then FENCE at once: the fence's status hid the route's DONE, and on the phone each replaced the other. */
    @Test
    void aRouteAndAFenceSentTogetherBothArrive() throws Exception {
        try (Phone phone = new Phone(30)) {
            List<MissionItem> route = RouteToDroneTest.route(20), fence = RouteToDroneTest.route(6);
            CompletableFuture<RouteSender.Result> r = new CompletableFuture<>(), f = new CompletableFuture<>();
            RouteSender.send(MissionUpload.TYPE_MISSION, route, phone::take, phone.pcSide::getRouteUpload, null, r::complete);
            RouteSender.send(MissionUpload.TYPE_FENCE, fence, phone::take, phone.pcSide::getRouteUpload, null, f::complete);
            RouteSender.Result rr = r.get(40, TimeUnit.SECONDS), fr = f.get(40, TimeUnit.SECONDS);
            assertTrue(rr.done(), rr.text());
            assertTrue(fr.done(), fr.text());
            assertEquals(20, phone.onFc.get(MissionUpload.TYPE_MISSION).size());
            assertEquals(6, phone.onFc.get(MissionUpload.TYPE_FENCE).size());
        }
    }

    /** The phone says an upload's end once: that record lost, the sender waited out its 30 s. */
    @Test
    void aLostDoneIsAskedForAgain() throws Exception {
        try (Phone phone = new Phone(0)) {
            phone.loseDone = 1;
            long t0 = System.nanoTime();
            RouteSender.Result r = RouteSender.run(4242, java.util.List.of(MissionBlock.packet(4242, RouteToDroneTest.route(5))), phone::take,
                    phone.pcSide::getRouteUpload, null);
            assertTrue(r.done(), r.text());
            assertTrue(System.nanoTime() - t0 < 10_000_000_000L, "within seconds, not the 30 s limit");
        }
    }

    @Test
    void theNewestStatusOfEachBlockIsKept() throws Exception {
        try (Phone phone = new Phone(0)) {
            phone.take(MissionBlock.packet(500, MissionUpload.TYPE_FENCE, RouteToDroneTest.route(2)));
            phone.take(MissionBlock.packet(501, MissionUpload.TYPE_RALLY, RouteToDroneTest.route(2)));
            Thread.sleep(500);
            assertNotNull(phone.pcSide.getRouteUpload(500));
            assertNotNull(phone.pcSide.getRouteUpload(501));
            assertNull(phone.pcSide.getRouteUpload(999));
        }
    }
}
