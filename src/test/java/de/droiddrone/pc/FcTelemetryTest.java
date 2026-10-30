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
    void onlyRecordsFromTheFlightControllerCountAsFcData() throws Exception {
        FcTelemetry t = new FcTelemetry();
        assertEquals(Long.MAX_VALUE, t.msSinceFcData(System.currentTimeMillis()), "never heard from the FC");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bytes);
        o.writeShort(FcCommon.DD_PHONE_BATTERY_STATE); // the phone's own record keeps coming without an FC
        o.writeByte(80); o.writeBoolean(false);
        t.parse(new DataReader(bytes.toByteArray(), true), BETAFLIGHT);
        assertEquals(80, t.getPhoneBatteryPercent());
        assertEquals(Long.MAX_VALUE, t.msSinceFcData(System.currentTimeMillis()));
        t.parse(new DataReader(betaflightPacket(0, 0), true), BETAFLIGHT);
        assertTrue(t.msSinceFcData(System.currentTimeMillis()) < 1000);
    }

    @Test
    void cellVoltageUsesTheFlightControllersCellCount() {
        FcTelemetry.Battery fourS = new FcTelemetry.Battery(14.8, 4, 0, 0);
        assertTrue(fourS.cellCountKnown());
        assertEquals(3.7, fourS.cellVolts(), 1e-9);
        // not reported yet (or 0 on USB power): unknown, never guessed from a low or still-rising voltage
        FcTelemetry.Battery unknown = new FcTelemetry.Battery(8.1, 0, 0, 0);
        assertFalse(unknown.cellCountKnown());
        assertTrue(Double.isNaN(unknown.cellVolts()));
    }

    @Test
    void readsThePhoneAppsVersionAfterItsStats() throws Exception {
        // as the phone app sends it: stats, then once a second its version in the same packet
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream o = new java.io.DataOutputStream(bytes);
        o.writeShort(FcTelemetry.PHONE_LINK_STATS);
        o.writeShort(12); o.writeShort(14); o.writeShort(5); o.writeShort(0); o.writeShort(0);
        byte[] name = "2.3.0-pc.5".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        o.writeShort(FcTelemetry.PHONE_APP_VERSION);
        o.writeShort(name.length);
        o.write(name);
        FcTelemetry t = new FcTelemetry();
        assertFalse(t.phoneAppSilent(System.currentTimeMillis() + 60_000), "nothing from the phone yet: can't tell");
        t.parse(new DataReader(bytes.toByteArray(), true), BETAFLIGHT);
        assertEquals("2.3.0-pc.5", t.getPhoneAppVersion());
        assertEquals(0, t.getUnknownCodes());
        assertFalse(t.phoneAppSilent(System.currentTimeMillis() + 60_000));
    }

    @Test
    void aPhoneAppThatNeverSaysItsVersionIsOld() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream o = new java.io.DataOutputStream(bytes);
        o.writeShort(FcCommon.DD_PHONE_BATTERY_STATE);
        o.writeByte(55); o.writeBoolean(true);
        FcTelemetry t = new FcTelemetry();
        t.parse(new DataReader(bytes.toByteArray(), true), BETAFLIGHT);
        long now = System.currentTimeMillis();
        assertFalse(t.phoneAppSilent(now), "give it a moment");
        assertTrue(t.phoneAppSilent(now + FcTelemetry.PHONE_APP_SILENT_MS + 100));
        assertNull(t.getPhoneAppVersion());
    }

    @Test
    void phoneLinkStatsAreReadAndMerged() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream o = new java.io.DataOutputStream(bytes);
        o.writeShort(FcTelemetry.PHONE_LINK_STATS);
        o.writeShort(12); o.writeShort(140); o.writeShort(105); o.writeShort(1); o.writeShort(3);
        FcTelemetry t = new FcTelemetry();
        t.parse(new DataReader(bytes.toByteArray(), true), BETAFLIGHT);
        t.parse(new DataReader(bytes.toByteArray(), true), BETAFLIGHT);
        FcTelemetry.PhoneLink p = t.takePhoneLink();
        assertEquals(new FcTelemetry.PhoneLink(12, 140, 105, 2, 6), p, "worst gaps, summed counts");
        assertNull(t.takePhoneLink(), "taken");
        assertEquals(0, t.getUnknownCodes());
        assertEquals(Long.MAX_VALUE, t.msSinceFcData(System.currentTimeMillis()), "the phone's own record, not FC data");
    }

    @Test
    void flightModeIsNamedLikeBetaflightsOsd() {
        assertEquals("ACRO", FcTelemetry.flightMode(List.of("ARM")), "no self-level mode on: acro");
        assertEquals("AIR", FcTelemetry.flightMode(List.of("ARM", "AIR MODE")), "acro with the air mode switch");
        assertEquals("ANGLE", FcTelemetry.flightMode(List.of("ARM", "ANGLE", "AIR MODE")));
        assertEquals("HORIZON", FcTelemetry.flightMode(List.of("HORIZON", "AIR MODE")));
        assertEquals("ACRO TRAINER", FcTelemetry.flightMode(List.of("ARM", "ACRO TRAINER", "AIR MODE")));
        assertEquals("ANGLE", FcTelemetry.flightMode(List.of("ACRO TRAINER", "ANGLE")), "acro trainer only works in acro");
        assertEquals("HEADFREE", FcTelemetry.flightMode(List.of("ANGLE", "HEADFREE")));
        assertEquals("ALT HOLD", FcTelemetry.flightMode(List.of("ANGLE", "ALT HOLD")), "4.5 name");
        assertEquals("ALT HOLD", FcTelemetry.flightMode(List.of("ANGLE", "ALTHOLD")), "2025.12 name");
        assertEquals("POS HOLD", FcTelemetry.flightMode(List.of("ALTHOLD", "POSHOLD")));
        assertEquals("CHIRP", FcTelemetry.flightMode(List.of("CHIRP")));
        assertEquals("PASSTHRU", FcTelemetry.flightMode(List.of("PASSTHRU")));
        assertEquals("TURTLE", FcTelemetry.flightMode(List.of("ARM", "ANGLE", "FLIP OVER AFTER CRASH")));
        assertEquals("GPS RESCUE", FcTelemetry.flightMode(List.of("ANGLE", "GPS RESCUE")), "rescue over angle");
        assertEquals("FAILSAFE", FcTelemetry.flightMode(List.of("ANGLE", "GPS RESCUE", "FAILSAFE")));
        assertEquals(List.of("AIR MODE", "BEEPER"), FcTelemetry.otherModes(List.of("ARM", "ANGLE", "AIR MODE", "BEEPER")));
        assertEquals(List.of("BEEPER"), FcTelemetry.otherModes(List.of("ARM", "AIR MODE", "BEEPER")), "AIR is shown as the mode");
        assertEquals(List.of("ANGLE"), FcTelemetry.otherModes(List.of("ANGLE", "ALTHOLD")));
    }

    @Test
    void flyTimeCountsOnlyWhileArmed() {
        FcTelemetry t = new FcTelemetry();
        t.trackArmed(false, 1_000);
        t.trackArmed(true, 5_000);
        t.trackArmed(true, 6_000);
        assertEquals(3, t.flySeconds(8_500), "running while armed");
        t.trackArmed(false, 10_000);
        assertEquals(5, t.flySeconds(60_000), "stops when disarmed");
        t.trackArmed(true, 70_000);
        assertEquals(65, t.flySeconds(130_000), "the next arm adds to it, like Betaflight's total arm time");
    }

    @Test
    void flyTimeFollowsTheArmFlagInMspStatus() throws Exception {
        FcTelemetry t = new FcTelemetry();
        assertEquals(-1, t.flySeconds(System.currentTimeMillis()), "nothing known yet");
        t.parse(new DataReader(boxNamesPacket("ARM;ANGLE;"), true), BETAFLIGHT);
        t.parse(new DataReader(betaflightPacket(0, 0b10), true), BETAFLIGHT);
        assertEquals(0, t.flySeconds(System.currentTimeMillis() + 60_000), "disarmed: no time, though the phone's timer is unused");
        t.parse(new DataReader(betaflightPacket(0, 0b11), true), BETAFLIGHT);
        assertEquals(60, t.flySeconds(System.currentTimeMillis() + 60_200), "armed");
    }

    @Test
    void knowsWhenThePhoneHasReadTheModeIds() throws Exception {
        FcTelemetry t = new FcTelemetry();
        assertFalse(t.hasBoxIds());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bytes);
        o.writeShort(FcCommon.MSP_BOXIDS);
        o.writeShort(3); o.writeByte(0); o.writeByte(1); o.writeByte(2);
        o.write(betaflightPacket(0, 0)); // what follows is still read
        t.parse(new DataReader(bytes.toByteArray(), true), BETAFLIGHT);
        assertTrue(t.hasBoxIds());
        assertNotNull(t.getStatus());
        t.forgetBoxIds();
        assertFalse(t.hasBoxIds(), "asked for again after a link loss");
    }

    @Test
    void readsGpsCoordinatesAndTheDistanceFlown() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bytes);
        o.writeShort(FcCommon.MSP_RAW_GPS); // as the Flight app re-packs it, with its own travelled distance
        o.writeByte(2); o.writeByte(11); o.writeInt(599_123_456); o.writeInt(-107_512_345);
        o.writeShort(52); o.writeShort(1000); o.writeShort(0); o.writeShort(90); o.writeInt(1840);
        o.writeShort(FcCommon.MSP_COMP_GPS);
        o.writeShort(230); o.writeShort(140); o.writeByte(0);
        FcTelemetry t = new FcTelemetry();
        t.parse(new DataReader(bytes.toByteArray(), true), BETAFLIGHT);
        FcTelemetry.Gps gps = t.getGps();
        assertEquals(59.9123456, gps.latDeg(), 1e-7);
        assertEquals(-10.7512345, gps.lonDeg(), 1e-7);
        assertEquals(1840, gps.travelledM());
        assertEquals(36.0, gps.speedKmh(), 1e-9);
        assertEquals(230, gps.distanceHomeM(), "home from the next record, the rest kept");
        assertEquals(0, t.getUnknownCodes());
    }

    @Test
    void readsTheCoreTemperature() throws Exception {
        FcTelemetry t = new FcTelemetry();
        t.parse(new DataReader(betaflightPacket(0, 0), true), BETAFLIGHT);
        assertEquals(41, t.getStatus().coreTempC());
        assertTrue(t.getStatus().coreTempKnown());
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
