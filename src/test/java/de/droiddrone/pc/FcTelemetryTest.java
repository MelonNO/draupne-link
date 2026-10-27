package de.droiddrone.pc;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.SettingsCommon;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FcTelemetryTest {
    static final FcInfo BETAFLIGHT = new FcInfo(FcInfo.FC_VARIANT_BETAFLIGHT, 4, 5, 1, 0, 1, 46, 0);

    /** Records written the way upstream Flight Udp.fillTelemetryPacketData writes them for Betaflight. */
    static byte[] betaflightPacket(int armingFlags, int modeBits) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bytes);
        o.writeShort(FcCommon.MSP_ATTITUDE);
        o.writeShort(12); o.writeShort(-30); o.writeShort(900);
        o.writeShort(FcCommon.MSP_ANALOG);
        o.writeShort(321); o.writeShort(1000); o.writeShort(1250); o.writeShort(1610);
        o.writeShort(FcCommon.MSP_OSD_CONFIG);            // variable length: must be skipped exactly
        o.writeShort(SettingsCommon.versionCompatibleCode);
        o.writeByte(1); o.writeByte(0); o.writeByte(20); o.writeShort(500); o.writeShort(100);
        o.writeShort(3); o.writeShort(0x0801); o.writeShort(0x0802); o.writeShort(0x0803);
        o.writeByte(2); o.writeByte(1); o.writeByte(2);   // stats
        o.writeByte(1); o.writeShort(7);                  // timers
        o.writeByte(10); o.writeInt(0xffff); o.writeByte(0); o.writeByte(30); o.writeByte(16);
        o.writeShort(FcCommon.MSP_VTX_CONFIG);
        o.writeShort(FcCommon.VTXDEV_UNKNOWN);            // no VTX: nothing follows
        o.writeShort(FcCommon.DD_VIDEO_RECORDER_START_STOP); // code only
        o.writeShort(FcCommon.MSP_BATTERY_STATE);
        o.writeByte(4); o.writeShort(1500); o.writeShort(321); o.writeShort(1250); o.writeByte(0); o.writeShort(1612);
        o.writeShort(FcCommon.DD_CAMERA_FPS);
        o.writeShort(58);
        o.writeShort(FcCommon.MSP_STATUS);
        o.writeShort(125); o.writeShort(0); o.writeShort(0x23);
        o.writeByte(0);                                   // pid profile
        o.writeShort(7);                                  // load
        o.writeByte(4);                                   // mode flag bytes
        o.writeByte(modeBits); o.writeByte(0); o.writeByte(0); o.writeByte(0);
        o.writeInt(armingFlags);
        o.writeByte(0);                                   // config state
        o.writeShort(41);                                 // core temp
        return bytes.toByteArray();
    }

    static byte[] boxNamesPacket(String names) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bytes);
        byte[] n = names.getBytes(StandardCharsets.US_ASCII);
        o.writeShort(FcCommon.MSP_BOXNAMES);
        o.writeShort(n.length);
        o.write(n);
        return bytes.toByteArray();
    }

    @Test
    void readsTheBlockersFromThisProjectsDrone() throws Exception {
        FcTelemetry t = new FcTelemetry();
        t.parse(new DataReader(boxNamesPacket("ARM;ANGLE;HORIZON;BEEPER;"), true), BETAFLIGHT);
        // what the OSD showed: ANGLE (bit 8) and MSP (bit 16); ANGLE mode switched on (box index 1)
        t.parse(new DataReader(betaflightPacket(1 << 8 | 1 << 16, 0b10), true), BETAFLIGHT);
        FcTelemetry.Status s = t.getStatus();
        assertNotNull(s, "status must be found behind attitude, analog, OSD config, VTX, recorder, battery and fps records");
        assertEquals(List.of("ANGLE", "MSP"), s.blockers().stream().map(FcTelemetry.Blocker::osdName).toList());
        assertEquals(List.of("ANGLE"), s.activeModes());
        assertFalse(s.armed());
        assertEquals(16.12, t.getBattery().volts(), 1e-9);
        assertEquals(4, t.getBattery().cells());
        assertEquals(58, t.getCameraFps());
        assertEquals(0, t.getUnknownCodes());
    }

    @Test
    void armedWhenArmModeIsActive() throws Exception {
        FcTelemetry t = new FcTelemetry();
        t.parse(new DataReader(boxNamesPacket("ARM;ANGLE;"), true), BETAFLIGHT);
        t.parse(new DataReader(betaflightPacket(0, 0b11), true), BETAFLIGHT);
        assertTrue(t.getStatus().armed());
        assertTrue(t.getStatus().blockers().isEmpty());
    }

    @Test
    void blockerTableMatchesBetaflightBitOrder() {
        String[] expected = {"NOGYRO", "FAILSAFE", "RXLOSS", "BADRX", "BOXFAILSAFE", "RUNAWAY", "CRASH", "THROTTLE",
                "ANGLE", "BOOTGRACE", "NOPREARM", "LOAD", "CALIB", "CLI", "CMS", "BST", "MSP", "PARALYZE", "GPS",
                "RESCUE SW", "RPMFILTER", "REBOOT_REQD", "DSHOT_BBANG", "NO_ACC_CAL", "MOTOR_PROTO", "ARMSWITCH"};
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], FcTelemetry.BETAFLIGHT_BLOCKERS[i].osdName(), "bit " + i);
    }

    @Test
    void unknownRecordStopsParsingInsteadOfGuessing() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bytes);
        o.writeShort(0x7abc); // unknown code: its length can't be known
        o.write(betaflightPacket(1 << 7, 0));
        FcTelemetry t = new FcTelemetry();
        t.parse(new DataReader(bytes.toByteArray(), true), BETAFLIGHT);
        assertNull(t.getStatus());
        assertEquals(1, t.getUnknownCodes());
    }

    @Test
    void truncatedPacketKeepsWhatWasParsed() throws Exception {
        byte[] full = betaflightPacket(0, 0);
        FcTelemetry t = new FcTelemetry();
        t.parse(new DataReader(java.util.Arrays.copyOf(full, full.length - 5), true), BETAFLIGHT);
        assertEquals(58, t.getCameraFps());
        assertNull(t.getStatus());
    }

    @Test
    void shortFramesArePaddedSoTheFlightAppAppliesTheChannelMap() {
        short[] six = {1500, 1500, 1500, 1000, 2000, 1000};
        short[] padded = ControlLink.padForRxMap(six);
        assertEquals(8, padded.length);
        assertEquals(2000, padded[4]);
        assertEquals(1000, padded[6]);
        assertEquals(1000, padded[7]);
        short[] ten = new short[10];
        assertSame(ten, ControlLink.padForRxMap(ten));
    }
}
