package de.droiddrone.common;

import com.MAVLink.MAVLinkPacket;
import com.MAVLink.common.msg_attitude;
import com.MAVLink.minimal.msg_heartbeat;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD phase 1 on the phone's side (shared Common code): MavlinkSniff (AP-1, an ArduPilot board whose USB name is
 * its maker's), FcCommand (AP-6) and ArduLinkLossRule (AP-5, A3-A5, AC-41). The MAVLink frames are made by the same
 * library the phone uses.
 */
class ArduPilotPhoneRulesTest {
    private static byte[] heartbeat(int type, int autopilot, boolean v2) {
        MAVLinkPacket p = new msg_heartbeat(0, (short) type, (short) autopilot, (short) 0, (short) 4, (short) 3, 1, 1, v2).pack();
        return p.encodePacket();
    }

    private static byte[] attitude() {
        return new msg_attitude(1000, 0.1f, 0.2f, 0.3f, 0, 0, 0, 1, 1, true).pack().encodePacket();
    }

    // --- MavlinkSniff

    @Test
    void anArduPilotHeartbeatIsFoundInMavlinkOneAndTwo() {
        for (boolean v2 : new boolean[]{false, true}) {
            MavlinkSniff s = new MavlinkSniff();
            byte[] hb = heartbeat(2, MavlinkSniff.AUTOPILOT_ARDUPILOT, v2);
            assertTrue(s.feed(hb, hb.length), v2 ? "MAVLink 2" : "MAVLink 1");
            assertEquals(MavlinkSniff.AUTOPILOT_ARDUPILOT, s.autopilot());
        }
        MavlinkSniff px4 = new MavlinkSniff();
        byte[] hb = heartbeat(2, MavlinkSniff.AUTOPILOT_PX4, true);
        assertTrue(px4.feed(hb, hb.length));
    }

    @Test
    void foundAmongOtherFramesAndNoiseAndSplitAcrossReads() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        Random r = new Random(7);
        byte[] noise = new byte[300];
        r.nextBytes(noise);
        b.writeBytes(noise);
        b.writeBytes("$M<\0\1\1".getBytes()); // MSP requests the phone wrote echo nowhere, but noise may look like them
        b.writeBytes(attitude());
        b.writeBytes(heartbeat(2, MavlinkSniff.AUTOPILOT_ARDUPILOT, true));
        b.writeBytes(attitude());
        b.writeBytes(heartbeat(2, MavlinkSniff.AUTOPILOT_ARDUPILOT, true)); // the next second's, should the first be lost
        byte[] all = b.toByteArray();
        MavlinkSniff s = new MavlinkSniff();
        boolean found = false;
        for (int i = 0; i < all.length && !found; i += 7) {
            byte[] part = java.util.Arrays.copyOfRange(all, i, Math.min(all.length, i + 7));
            found = s.feed(part, part.length);
        }
        assertTrue(found, "the heartbeat across 7-byte reads after noise");
    }

    @Test
    void groundStationsBadChecksumsAndMspAreNotAnAutopilot() {
        MavlinkSniff s = new MavlinkSniff();
        byte[] gcs = heartbeat(6, 8, true); // MAV_TYPE_GCS, MAV_AUTOPILOT_INVALID
        assertFalse(s.feed(gcs, gcs.length), "a ground station's heartbeat");
        byte[] bad = heartbeat(2, MavlinkSniff.AUTOPILOT_ARDUPILOT, true);
        bad[bad.length - 1] ^= 0x55;
        assertFalse(s.feed(bad, bad.length), "a broken checksum");
        // a Betaflight reply stream: MSP frames, never MAVLink
        byte[] msp = new byte[4000];
        for (int i = 0; i < msp.length; i++) msp[i] = (byte) "$M>\u0006l\u0001\u0002\u0003\u0004\u0005\u0006k".charAt(i % 12);
        assertFalse(s.feed(msp, msp.length));
        Random r = new Random(3);
        for (int round = 0; round < 200; round++) {
            byte[] junk = new byte[512];
            r.nextBytes(junk);
            MavlinkSniff fresh = new MavlinkSniff();
            assertFalse(fresh.feed(junk, junk.length), "random bytes, round " + round);
        }
        s.reset();
        assertEquals(-1, s.autopilot());
    }

    // --- FcCommand

    @Test
    void commandsRoundTripAndGarbageIsRefused() {
        byte[] m = FcCommand.mode(5);
        FcCommand c = FcCommand.read(m, m.length);
        assertEquals(FcCommand.MODE, c.kind);
        assertEquals(5, c.mode);
        for (int k : new int[]{FcCommand.MISSION_START, FcCommand.PAUSE, FcCommand.CONTINUE}) {
            byte[] s = FcCommand.simple(k);
            assertEquals(k, FcCommand.read(s, s.length).kind);
        }
        assertNull(FcCommand.read(m, 3), "cut short");
        assertNull(FcCommand.read(new byte[]{FcCommand.PACKET, 99}, 2), "an unknown kind");
        assertNull(FcCommand.read(new byte[]{FcCommand.PACKET, 1, 0x7f, 0, 0, 0}, 6), "a mode number no Copter has");
        assertNull(FcCommand.read(new byte[]{0x31, 1}, 2), "another packet");
        Random r = new Random(5);
        for (int i = 0; i < 2000; i++) {
            byte[] junk = new byte[1 + r.nextInt(10)];
            r.nextBytes(junk);
            junk[0] = FcCommand.PACKET;
            FcCommand.read(junk, junk.length); // never throws
        }
    }

    // --- MavTunnel

    private static boolean passes(MAVLinkPacket p) {
        byte[] f = p.encodePacket();
        return MavTunnel.toFc(f, 0, f.length);
    }

    private static MAVLinkPacket command(int cmd) {
        return new com.MAVLink.common.msg_command_long(1, 0, 0, 0, 0, 0, 0, cmd, (short) 1, (short) 1, (short) 0, 255, 190, true).pack();
    }

    @Test
    void theTunnelCarriesRoutesParametersAndTargetsButNeverSticksOrArming() {
        assertTrue(passes(new com.MAVLink.common.msg_param_set(3000, (short) 1, (short) 1, new byte[16], (short) 9, 255, 190, true).pack()));
        assertTrue(passes(new com.MAVLink.common.msg_mission_count(12, (short) 1, (short) 1, (short) 0, 0L, 255, 190, true).pack()));
        assertTrue(passes(new com.MAVLink.common.msg_mission_item_int(0, 0, 0, 0, 599000000, 107000000, 120, 0, 16, (short) 1, (short) 1,
                (short) 0, (short) 0, (short) 1, (short) 0, 255, 190, true).pack()));
        assertTrue(passes(command(176)), "DO_SET_MODE");
        assertTrue(passes(command(300)), "MISSION_START");
        assertFalse(passes(command(400)), "COMPONENT_ARM_DISARM: never through the tunnel");
        assertFalse(passes(command(209)), "motor test: the bench's, over USB");
        assertFalse(passes(command(241)), "calibration");
        assertFalse(passes(command(246)), "reboot");
        assertFalse(passes(new com.MAVLink.common.msg_rc_channels_override(1500, 1500, 1000, 1500, 2000, 0, 0, 0, (short) 1, (short) 1,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 255, 190, true).pack()), "stick overrides go their own way");
        assertFalse(passes(new com.MAVLink.minimal.msg_heartbeat(0, (short) 6, (short) 8, (short) 0, (short) 4, (short) 3, 255, 190, true).pack()));
        assertTrue(MavTunnel.toPc(22), "PARAM_VALUE back");
        assertFalse(MavTunnel.toPc(30), "ATTITUDE: the telemetry's own way");
    }

    @Test
    void framesAreSplitAndABrokenTailIsDropped() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(heartbeat(2, MavlinkSniff.AUTOPILOT_ARDUPILOT, false));
        b.writeBytes(command(176).encodePacket());
        byte[] last = attitude();
        b.writeBytes(java.util.Arrays.copyOf(last, last.length - 3)); // cut
        byte[] all = b.toByteArray();
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        int n = MavTunnel.split(all, 0, all.length, (d, off, len) -> ids.add(MavTunnel.msgId(d, off, len)));
        assertEquals(2, n);
        assertEquals(java.util.List.of(0, 76), ids);
        Random r = new Random(11);
        for (int i = 0; i < 500; i++) {
            byte[] junk = new byte[r.nextInt(80)];
            r.nextBytes(junk);
            MavTunnel.split(junk, 0, junk.length, (d, off, len) -> MavTunnel.toFc(d, off, len)); // never throws
        }
    }

    // --- ArduLinkLossRule

    private static final long T = 1_000_000;

    @Test
    void aSoloDroneBrakesAfterASecondAndGoesHomeAfterTwentyByItsOwnPath() {
        ArduLinkLossRule r = new ArduLinkLossRule();
        assertEquals(ArduLinkLossRule.NONE, r.tick(T + 900, T, true, 0, true, false), "a 4G gap under a second: nothing");
        assertFalse(r.running());
        assertEquals(ArduLinkLossRule.BRAKE, r.tick(T + 1000, T, true, 0, true, false));
        assertTrue(r.running());
        assertEquals(5, ArduLinkLossRule.modeFor(ArduLinkLossRule.BRAKE), "LOITER");
        assertEquals(ArduLinkLossRule.NONE, r.tick(T + 1100, T, true, 5, true, false), "once per step");
        assertEquals(ArduLinkLossRule.SMART_RTL, r.tick(T + 20_000, T, true, 5, true, false));
        assertEquals(ArduLinkLossRule.NONE, r.tick(T + 25_000, T, true, 21, false, false), "a return under way is left to ArduPilot");
        // the PC back: the rule lets go; the mode stays (the pilot flies on with the switch)
        assertEquals(ArduLinkLossRule.NONE, r.tick(T + 26_000, T + 25_900, true, 21, true, false));
        assertFalse(r.running());
    }

    @Test
    void aLeadGoesHomeByPlainRtl() {
        ArduLinkLossRule r = new ArduLinkLossRule();
        r.tick(T + 1000, T, true, 0, true, true);
        assertEquals(ArduLinkLossRule.RTL, r.tick(T + 20_000, T, true, 5, true, true), "AC-41: Smart RTL would fly back through the group");
    }

    @Test
    void withoutGnssItHoldsHeightAndWaits() {
        ArduLinkLossRule r = new ArduLinkLossRule();
        assertEquals(ArduLinkLossRule.HOLD_HEIGHT, r.tick(T + 1000, T, true, 0, false, false));
        assertEquals(2, ArduLinkLossRule.modeFor(ArduLinkLossRule.HOLD_HEIGHT), "ALT_HOLD");
        assertEquals(ArduLinkLossRule.NONE, r.tick(T + 60_000, T, true, 2, false, false), "never home without GNSS (A5)");
        // GNSS lost after the brake: hold the height instead of a place it can't hold
        ArduLinkLossRule s = new ArduLinkLossRule();
        assertEquals(ArduLinkLossRule.BRAKE, s.tick(T + 1000, T, true, 0, true, false));
        assertEquals(ArduLinkLossRule.HOLD_HEIGHT, s.tick(T + 5000, T, true, 5, false, false));
    }

    @Test
    void nothingDisarmedNeverHeardOrAlreadyComingHome() {
        ArduLinkLossRule r = new ArduLinkLossRule();
        assertEquals(ArduLinkLossRule.NONE, r.tick(T + 30_000, T, false, 0, true, false), "disarmed");
        assertEquals(ArduLinkLossRule.NONE, r.tick(T + 30_000, 0, true, 0, true, false), "no PC ever: flown with its own radio");
        for (int mode : new int[]{6, 21, 27, 9}) {
            ArduLinkLossRule k = new ArduLinkLossRule();
            assertEquals(ArduLinkLossRule.NONE, k.tick(T + 30_000, T, true, mode, true, false), "mode " + mode + ": ArduPilot's own");
            assertFalse(k.running());
        }
    }
}
