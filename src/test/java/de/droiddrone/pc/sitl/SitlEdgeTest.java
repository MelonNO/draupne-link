package de.droiddrone.pc.sitl;

import com.MAVLink.MAVLinkPacket;
import com.MAVLink.common.msg_command_ack;
import com.MAVLink.common.msg_command_int;
import com.MAVLink.common.msg_log_entry;
import com.MAVLink.common.msg_log_request_list;
import de.droiddrone.common.ArduLinkLossRule;
import de.droiddrone.common.MavTunnel;
import de.droiddrone.common.MissionItem;
import de.droiddrone.common.MissionUpload;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.pc.route.FenceBuilder;
import de.droiddrone.pc.setup.ardu.ArduPlan;
import de.droiddrone.pc.setup.ardu.ArduSetupWriter;
import de.droiddrone.pc.setup.ardu.MavSession;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What only a real ArduPilot (Copter 4.7.3 SITL) can answer about DRAUPNE's messages: every mission item type DRAUPNE
 * sends read back as sent; a fence and rally points; the uploads' edges (a duplicate item, a lost last ack); GO_LOOK
 * exactly as the phone sends it; a mode command refused; the link-loss rule without a Smart RTL path; the log list.
 * Instances 6 - 9 (TCP 5760 + 10 n), apart from the gates'.
 */
@Tag("ardusitl")
class SitlEdgeTest {
    static final double LAT = 59.91, LON = 10.71, ALT = 120;
    private final List<String> texts = new java.util.concurrent.CopyOnWriteArrayList<>();

    private void until(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000;
        while (System.nanoTime() < end) {
            if (ok.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("waited " + ms / 1000 + " s for: " + what + " - ArduPilot said: " + texts.subList(Math.max(0, texts.size() - 12), texts.size()));
    }

    /** A MissionUpload of one list over the session, as the phone runs it; a wrapper may change what goes out. */
    static MissionUpload upload(MavSession fc, int type, List<MissionItem> items, java.util.function.UnaryOperator<MissionUpload.Link> wrap,
                                java.util.function.Predicate<MAVLinkPacket> drop) throws InterruptedException {
        MissionUpload[] up = new MissionUpload[1];
        MissionUpload.Link link = SitlMission.link(fc, type);
        up[0] = new MissionUpload(items, wrap == null ? link : wrap.apply(link), type);
        java.util.function.Consumer<MAVLinkPacket> feed = SitlMission.feed(fc, () -> up[0], type, drop);
        try {
            long t0 = System.currentTimeMillis();
            up[0].start(t0);
            while (!up[0].done() && !up[0].failed() && System.currentTimeMillis() - t0 < 30_000) {
                up[0].tick(System.currentTimeMillis());
                Thread.sleep(20);
            }
            return up[0];
        } finally {
            fc.removePacketListener(feed);
        }
    }

    /** Every mission item DRAUPNE's routes carry, read back from ArduPilot as sent (NaN yaw reads 0). */
    @Test
    void everyItemTypeDraupneSendsReadsBack() throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        try (ArduSitl sitl = ArduSitl.start(6, LAT, LON, ALT, 1)) {
            MavSession fc = sitl.fc;
            fc.onText(texts::add);
            Thread.sleep(3000);
            List<MissionItem> items = new ArrayList<>();
            items.add(MissionItem.at(0, MissionItem.NAV_WAYPOINT, LAT, LON, ALT)); // home
            items.add(MissionItem.at(1, MissionItem.NAV_TAKEOFF, LAT + 1e-4, LON, ALT + 40));
            items.add(MissionItem.at(2, MissionItem.NAV_WAYPOINT, LAT + 5e-4, LON + 5e-4, ALT + 60));
            items.add(new MissionItem(3, MissionItem.FRAME_MISSION, MissionItem.DO_CHANGE_SPEED, true, 1, 8, -1, 0, 0, 0, 0));
            items.add(MissionItem.at(4, MissionItem.NAV_LOITER_UNLIM, LAT + 9e-4, LON + 5e-4, ALT + 60));
            items.add(MissionItem.at(5, MissionItem.NAV_WAYPOINT, LAT + 1e-4, LON, ALT + 50));
            items.add(new MissionItem(6, MissionItem.FRAME_GLOBAL, MissionItem.NAV_LAND, true, 0, 0, 0, Float.NaN,
                    (int) Math.round(LAT * 1e7), (int) Math.round(LON * 1e7), (float) ALT));
            items.add(new MissionItem(7, MissionItem.FRAME_MISSION, MissionItem.NAV_RETURN_TO_LAUNCH, true, 0, 0, 0, 0, 0, 0, 0));
            MissionUpload u = upload(fc, MissionUpload.TYPE_MISSION, items, null, null);
            assertTrue(u.done(), "read back as sent: " + u.reason() + " - ArduPilot said: " + texts);
        }
    }

    /**
     * The uploads' edges against ArduPilot's own mission protocol: an item sent twice (the phone answering a repeated
     * request) and the last MISSION_ACK lost on the USB.
     */
    @Test
    void aDuplicateItemAndALostLastAckStillEndDone() throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        try (ArduSitl sitl = ArduSitl.start(6, LAT, LON, ALT, 1)) {
            MavSession fc = sitl.fc;
            fc.onText(texts::add);
            Thread.sleep(3000);
            List<MissionItem> items = new ArrayList<>();
            items.add(MissionItem.at(0, MissionItem.NAV_WAYPOINT, LAT, LON, ALT));
            for (int i = 1; i < 12; i++) items.add(MissionItem.at(i, MissionItem.NAV_WAYPOINT, LAT + i * 1e-4, LON, ALT + 50));
            List<Integer> acks = new java.util.concurrent.CopyOnWriteArrayList<>();
            java.util.function.Consumer<MAVLinkPacket> ackSeen = p -> {
                if (p.msgid == com.MAVLink.common.msg_mission_ack.MAVLINK_MSG_ID_MISSION_ACK) acks.add((int) new com.MAVLink.common.msg_mission_ack(p).type);
            };
            fc.onPacket(ackSeen);
            // item 4 goes twice: ArduPilot answers the second with MISSION_ACK INVALID_SEQUENCE and goes on
            MissionUpload u = upload(fc, MissionUpload.TYPE_MISSION, items, l -> new MissionUpload.Link() {
                public void sendCount(int count) {
                    l.sendCount(count);
                }

                public void sendItem(MissionItem item) {
                    l.sendItem(item);
                    if (item.seq == 4) l.sendItem(item);
                }

                public void sendRequestList() {
                    l.sendRequestList();
                }

                public void sendRequest(int seq) {
                    l.sendRequest(seq);
                }

                public void sendAck(int type) {
                    l.sendAck(type);
                }
            }, null);
            assertTrue(u.done(), "a duplicate item: " + u.reason() + " acks " + acks + " - ArduPilot said: " + texts);
            assertTrue(acks.contains(MissionUpload.ACK_INVALID_SEQUENCE), "ArduPilot answers the copy so: " + acks);
            // the last MISSION_ACK lost: the read-back says the route is there
            boolean[] dropped = {false};
            MissionUpload v = upload(fc, MissionUpload.TYPE_MISSION, items, null, p -> {
                if (p.msgid == com.MAVLink.common.msg_mission_ack.MAVLINK_MSG_ID_MISSION_ACK && !dropped[0]) {
                    dropped[0] = true;
                    return true;
                }
                return false;
            });
            assertTrue(dropped[0]);
            assertTrue(v.done(), "a lost last ack: " + v.reason() + " - ArduPilot said: " + texts);
            fc.removePacketListener(ackSeen);
        }
    }

    /** AP-9 / AC-10: a fence as FenceBuilder makes it and rally points as the phone makes them, read back; cleared again. */
    @Test
    void aFenceAndRallyPointsReadBack() throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        try (ArduSitl sitl = ArduSitl.start(7, LAT, LON, ALT, 1)) {
            MavSession fc = sitl.fc;
            fc.onText(texts::add);
            Thread.sleep(3000);
            List<MissionItem> fence = new ArrayList<>();
            double[][] corners = {{LAT - 0.01, LON - 0.01}, {LAT - 0.01, LON + 0.01}, {LAT + 0.01, LON + 0.01}, {LAT + 0.01, LON - 0.01}};
            for (double[] c : corners) fence.add(new MissionItem(fence.size(), 0, FenceBuilder.POLYGON_INCLUSION, true, corners.length, 0, 0, 0,
                    (int) Math.round(c[0] * 1e7), (int) Math.round(c[1] * 1e7), 0));
            double[][] hole = {{LAT + 0.002, LON + 0.002}, {LAT + 0.002, LON + 0.004}, {LAT + 0.004, LON + 0.003}};
            for (double[] c : hole) fence.add(new MissionItem(fence.size(), 0, FenceBuilder.POLYGON_EXCLUSION, true, hole.length, 0, 0, 0,
                    (int) Math.round(c[0] * 1e7), (int) Math.round(c[1] * 1e7), 0));
            MissionUpload f = upload(fc, MissionUpload.TYPE_FENCE, fence, null, null);
            assertTrue(f.done(), "fence: " + f.reason() + " - ArduPilot said: " + texts);
            List<MissionItem> rally = List.of(
                    new MissionItem(0, 6, 5100, true, 0, 0, 0, 0, (int) Math.round((LAT + 0.001) * 1e7), (int) Math.round(LON * 1e7), 40),
                    new MissionItem(1, 6, 5100, true, 0, 0, 0, 0, (int) Math.round((LAT - 0.001) * 1e7), (int) Math.round(LON * 1e7), 55));
            MissionUpload r = upload(fc, MissionUpload.TYPE_RALLY, rally, null, null);
            assertTrue(r.done(), "rally points: " + r.reason() + " - ArduPilot said: " + texts);
            // the route is untouched by them, and an empty list clears each
            MissionUpload clearFence = upload(fc, MissionUpload.TYPE_FENCE, new ArrayList<>(), null, null);
            assertTrue(clearFence.done(), "fence cleared: " + clearFence.reason());
            assertEquals(100, clearFence.percent());
            MissionUpload clearRally = upload(fc, MissionUpload.TYPE_RALLY, new ArrayList<>(), null, null);
            assertTrue(clearRally.done(), "rally cleared: " + clearRally.reason());
        }
    }

    /** COMMAND_INT as the phone sends it (Mavlink.command), the FC's COMMAND_ACK result; -1 when none came. */
    static int commandInt(MavSession fc, int command, float p1, float p2, int lat, int lon, float z, int frame) throws Exception {
        int[] result = {-1};
        Object got = new Object();
        java.util.function.Consumer<MAVLinkPacket> l = p -> {
            if (p.msgid != msg_command_ack.MAVLINK_MSG_ID_COMMAND_ACK) return;
            msg_command_ack a = new msg_command_ack(p);
            if (a.command != command) return;
            synchronized (got) {
                result[0] = a.result;
                got.notifyAll();
            }
        };
        fc.onPacket(l);
        try {
            MAVLinkPacket p = new msg_command_int(p1, p2, 0, Float.NaN, lat, lon, z, command, (short) Math.max(1, fc.fcSysId()), (short) 1,
                    (short) frame, (short) 0, (short) 0, MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack();
            byte[] raw = p.encodePacket();
            assertTrue(MavTunnel.toFc(raw, 0, raw.length), "the tunnel carries it");
            fc.send(p);
            synchronized (got) {
                long end = System.nanoTime() + 3_000_000_000L;
                while (result[0] < 0 && System.nanoTime() < end) got.wait(100);
            }
            return result[0];
        } finally {
            fc.removePacketListener(l);
        }
    }

    /**
     * GO_LOOK (AP-12) exactly as the phone sends it - DO_REPOSITION with no height (NaN: "the height it has") and the
     * mode change flag, then DO_SET_ROI_LOCATION on the ground; a mode command refused; the log list on the ground.
     */
    @Test
    void goLookAModeRefusedAndTheLogList() throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        try (ArduSitl sitl = ArduSitl.start(8, LAT, LON, ALT, 1)) {
            MavSession fc = sitl.fc;
            fc.onText(texts::add);
            sitl.awaitReady(120_000);
            // the log list on the ground (AP-10): ArduPilot answers LOG_ENTRY, also when it has none
            List<msg_log_entry> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
            java.util.function.Consumer<MAVLinkPacket> logL = p -> {
                if (p.msgid == msg_log_entry.MAVLINK_MSG_ID_LOG_ENTRY) logs.add(new msg_log_entry(p));
            };
            fc.onPacket(logL);
            MAVLinkPacket list = new msg_log_request_list(0, 0xffff, (short) fc.fcSysId(), (short) 1, MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack();
            byte[] raw = list.encodePacket();
            assertTrue(MavTunnel.toFc(raw, 0, raw.length, true), "on the ground the tunnel carries it");
            assertFalse(MavTunnel.toFc(raw, 0, raw.length, false), "in the air (or not known) never");
            fc.send(list);
            until(() -> !logs.isEmpty(), 5000, "LOG_ENTRY");
            fc.removePacketListener(logL);
            // a mode ArduPilot hasn't: refused, the mode unchanged
            int before = fc.customMode();
            int r = fc.command(176, 1, 99, 0, 0, 0, 0, 0, 3000);
            assertTrue(r > 0, "mode 99 refused: MAV_RESULT " + r);
            Thread.sleep(1500);
            assertEquals(before, fc.customMode());
            // AUTO with no mission, on the ground: what the GO button's MISSION_START meets without a route
            int auto = fc.command(176, 1, 3, 0, 0, 0, 0, 0, 3000);
            texts.add("(AUTO without a mission: MAV_RESULT " + auto + ")");
            // off the ground, then GO LOOK 120 m north-east (a fresh SITL: the level calibration first, as the gates)
            assertEquals(0, new de.droiddrone.pc.setup.ardu.ArduTools(fc).simpleAccelCal(), "accelerometer");
            assertEquals(0, fc.command(176, 1, 4, 0, 0, 0, 0, 0, 3000), "GUIDED (AUTO with no mission isn't armable)");
            Thread.sleep(3000);
            try {
                sitl.awaitPreArm(120_000);
            } catch (java.io.IOException e) {
                fail(e.getMessage() + " - ArduPilot said: " + texts);
            }
            long end = System.nanoTime() + 60_000_000_000L;
            while (!fc.armedFlag() && System.nanoTime() < end) {
                fc.command(400, 1, 0, 0, 0, 0, 0, 0, 2000);
                Thread.sleep(1500);
            }
            until(fc::armedFlag, 1000, "armed");
            assertEquals(0, fc.command(22, 0, 0, 0, 0, 0, 0, 25, 3000), "take-off");
            until(() -> fc.position() != null && fc.position()[3] > 23, 30_000, "25 m up");
            assertEquals(0, fc.command(176, 1, 5, 0, 0, 0, 0, 0, 3000), "LOITER: GO LOOK changes the mode itself");
            Thread.sleep(1500);
            double[] to = GeoLocal.offset(LAT, LON, 85, 85);
            int lat = (int) Math.round(to[0] * 1e7), lon = (int) Math.round(to[1] * 1e7);
            // "the height it has" as NaN is DENIED by ArduPilot 4.7.3 - GO LOOK never moved a drone before
            assertEquals(2, commandInt(fc, 192, -1, 1, lat, lon, Float.NaN, 6), "a NaN height: MAV_RESULT_DENIED");
            assertEquals(5, fc.customMode(), "refused: still LOITER");
            float h = de.droiddrone.common.FcCommand.goLookHeight(fc.position()[3]);
            int rep = commandInt(fc, 192, -1, 1, lat, lon, h, 6);
            int roi = commandInt(fc, 195, 0, 0, lat, lon, 0f, 6);
            assertEquals(0, rep, "DO_REPOSITION as the phone sends it (its height now) - ArduPilot said: " + texts);
            assertEquals(0, roi, "DO_SET_ROI_LOCATION");
            until(() -> fc.customMode() == 4, 3000, "GUIDED by the reposition's own flag");
            until(() -> GeoLocal.distance(to[0], to[1], fc.position()[0], fc.position()[1]) < 8, 60_000, "there");
            double now = fc.position()[3];
            assertTrue(Math.abs(now - h) < 3, "at the height it had: " + now + " m, was " + h);
        }
    }

    /**
     * The phone's link-loss rule on a real ArduPilot with no Smart RTL path (SRTL_POINTS 0): LOITER at the cut, Smart RTL
     * at 20 s refused - asked again, then RTL - and home. Before, the refused Smart RTL left it in LOITER for good.
     */
    @Test
    void theLinkLossRuleWithoutASmartRtlPathComesHomeByRtl(@TempDir Path backups) throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        int n = 9;
        try (ArduSitl first = ArduSitl.start(n, LAT, LON, ALT, 1)) {
            MavSession fc = first.fc;
            fc.onText(texts::add);
            first.awaitReady(120_000);
            ArduPlan.Choices c = new ArduPlan.Choices(n + 1, 3, 3300, 1, 2, ArduPlan.DEFAULT_MODES.clone(), 30, 10, 2.5, 1.5, true);
            ArduSetupWriter.Result r = new ArduSetupWriter(fc, null).write(ArduPlan.plan(c, fc.readAllParams(60_000)), backups,
                    java.time.LocalDateTime.now());
            assertTrue(r.ok(), r.failure());
            assertEquals(0f, fc.setParam("SRTL_POINTS", 0, 2000), "no Smart RTL path (takes effect at the next start)");
            assertEquals(0, new de.droiddrone.pc.setup.ardu.ArduTools(fc).simpleAccelCal(), "accelerometer");
            Thread.sleep(2000);
        }
        Thread.sleep(2000);
        try (ArduSitl sitl = ArduSitl.start(n, LAT, LON, ALT, 1)) { // the same eeprom: the plan and SRTL_POINTS 0 kept
            MavSession fc = sitl.fc;
            fc.onText(texts::add);
            sitl.awaitReady(120_000);
            assertEquals(0f, fc.readParam("SRTL_POINTS", 3000));
            sitl.awaitPreArm(120_000);
            try (SitlPilot pilot = new SitlPilot(fc)) {
                pilot.frame[5] = SitlPilot.modePwm(3); // LOITER
                Thread.sleep(1500);
                long armEnd = System.nanoTime() + 60_000_000_000L;
                while (!fc.armedFlag() && System.nanoTime() < armEnd) {
                    pilot.frame[4] = 2000;
                    for (int i = 0; i < 40 && !fc.armedFlag(); i++) Thread.sleep(100);
                    if (!fc.armedFlag()) {
                        pilot.frame[4] = 1000;
                        Thread.sleep(1000);
                    }
                }
                until(fc::armedFlag, 1000, "armed on the overrides");
                pilot.frame[3] = 1900;
                until(() -> fc.position() != null && fc.position()[3] > 20, 30_000, "20 m up");
                pilot.frame[3] = 1500;
                pilot.frame[1] = 1200;
                Thread.sleep(6000);
                pilot.frame[1] = 1500;
                pilot.setPc(false);
                until(() -> fc.customMode() == ArduLinkLossRule.MODE_LOITER, 3000, "LOITER at the cut");
                Thread.sleep(17_000);
                assertEquals(ArduLinkLossRule.MODE_LOITER, fc.customMode(), "still LOITER before 20 s");
                until(() -> fc.customMode() == ArduLinkLossRule.MODE_RTL, 15_000,
                        "RTL after Smart RTL was refused (" + pilot.lastAction + ")");
                until(() -> !fc.armedFlag(), 150_000, "landed and disarmed at home");
                double off = GeoLocal.distance(LAT, LON, fc.position()[0], fc.position()[1]);
                assertTrue(off < 5, "landed " + off + " m from home");
            }
        }
    }
}
