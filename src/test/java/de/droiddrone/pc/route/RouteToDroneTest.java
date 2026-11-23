package de.droiddrone.pc.route;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.MissionBlock;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD RT-8 end to end without a link: the PC's RouteSender, the block as the phone reads it (MissionBlock),
 * the phone's MissionUpload against a made-up ArduPilot's mission protocol, the status record through the phone's
 * TelemetryRecords and the PC's FcTelemetry. A lost first block is sent again; a refused one says why.
 */
class RouteToDroneTest {
    static final FcInfo ARDU = new FcInfo(FcInfo.FC_VARIANT_ARDUPILOT, 4, 6, 0, 0, 0, 3, 2);

    /** The phone and its FC: takes blocks, uploads them, answers with status records into the PC's telemetry. */
    static final class Phone {
        final FcTelemetry pcSide = new FcTelemetry();
        final List<MissionItem> onFc = new ArrayList<>();
        MissionUpload upload;
        int block = -1;
        final MissionBlock.Assembler parts = new MissionBlock.Assembler();
        int dropFirst;
        boolean refuse;

        boolean take(byte[] packet) {
            if (dropFirst-- > 0) return true; // sent, lost on the way
            MissionBlock.Part part = MissionBlock.readPart(packet, 1, packet.length - 1);
            assertNotNull(part, "every part fits and reads");
            assertTrue(packet.length <= de.droiddrone.common.UdpCommon.packetLength, packet.length + " bytes");
            MissionBlock b = parts.offer(part);
            if (b == null) return true; // more parts to come (the phone says RECEIVING; not needed here)
            if (b.blockId != block) {
                block = b.blockId;
                onFc.clear();
                upload = new MissionUpload(b.items, fc());
                upload.start(0);
            }
            report();
            return true;
        }

        MissionUpload.Link fc() {
            return new MissionUpload.Link() {
                int count;

                public void sendCount(int n) {
                    count = n;
                    if (refuse) {
                        upload.onAck(4, 1); // MAV_MISSION_NO_SPACE
                        return;
                    }
                    upload.onRequest(0, 1);
                }

                public void sendItem(MissionItem m) {
                    onFc.add(m);
                    if (m.seq + 1 < count) upload.onRequest(m.seq + 1, 1);
                    else upload.onAck(0, 1);
                }

                public void sendRequestList() {
                    upload.onCount(onFc.size(), 1);
                }

                public void sendRequest(int seq) {
                    upload.onItem(onFc.get(seq), 1);
                }

                public void sendAck(int type) {
                }
            };
        }

        void report() {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                TelemetryRecords.write(new TelemetryData(MissionBlock.STATUS_RECORD,
                        MissionBlock.status(block, upload.state(), upload.percent(), upload.failed() ? upload.reason() : null)), ARDU, new DataOutputStream(bytes));
                pcSide.parse(new DataReader(bytes.toByteArray(), true), ARDU);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    static List<MissionItem> route(int n) {
        List<MissionItem> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(MissionItem.at(i, MissionItem.NAV_WAYPOINT, 60 + i * 1e-4, 10.7, 180 + i));
        return l;
    }

    @Test
    void aRouteReachesTheDroneAndIsReadBackAlsoWhenTheFirstBlockIsLost() throws Exception {
        Phone phone = new Phone();
        phone.dropFirst = 1;
        List<MissionItem> items = route(100);
        List<byte[]> packet = MissionBlock.packets(7, MissionUpload.TYPE_MISSION, items);
        AtomicInteger sends = new AtomicInteger();
        RouteSender.Result r = RouteSender.run(7, packet, p -> {
            sends.incrementAndGet();
            return phone.take(p);
        }, phone.pcSide::getRouteUpload, null);
        assertTrue(r.done(), r.text());
        assertEquals(8, sends.get(), "100 items in 4 parts; the first lost: all 4 again, then no more");
        assertEquals(100, phone.onFc.size());
        for (int i = 0; i < 100; i++) assertTrue(items.get(i).sameAs(phone.onFc.get(i)), "item " + i);
        assertTrue(r.text().contains("100 points"), r.text());
    }

    @Test
    void aRefusalSaysWhy() throws Exception {
        Phone phone = new Phone();
        phone.refuse = true;
        RouteSender.Result r = RouteSender.run(9, List.of(MissionBlock.packet(9, route(5))), phone::take, phone.pcSide::getRouteUpload, null);
        assertFalse(r.done());
        assertTrue(r.text().startsWith("Route upload failed"), r.text());
    }

    @Test
    void blocksAreCheckedWhenRead() {
        byte[] p = MissionBlock.packet(65535, route(3));
        MissionBlock b = MissionBlock.read(p, 1, p.length - 1);
        assertEquals(65535, b.blockId);
        assertEquals(3, b.items.size());
        assertNull(MissionBlock.read(p, 1, 10), "cut short");
        assertNull(MissionBlock.read(new byte[]{0, 1, 0, 0}, 0, 4), "no items");
        java.util.Random rnd = new java.util.Random(1);
        for (int i = 0; i < 500; i++) {
            byte[] junk = new byte[rnd.nextInt(200)];
            rnd.nextBytes(junk);
            MissionBlock.read(junk, 0, junk.length); // never throws
        }
    }
}
