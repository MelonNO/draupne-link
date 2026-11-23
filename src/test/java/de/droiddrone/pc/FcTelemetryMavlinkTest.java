package de.droiddrone.pc;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.DataWriter;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.TelemetryData;
import de.droiddrone.common.TelemetryRecords;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD AP-2: an ArduPilot drone's telemetry. The records are made as the phone's Mavlink.java makes them (big
 * endian, its own field order), go through the phone's TelemetryRecords as on the drone, and are read by the PC's
 * FcTelemetry. Before, the PC read only the attitude and stopped the packet at the first other MAVLink record.
 */
class FcTelemetryMavlinkTest {
    /** ArduPilot Copter 4.6.0 on a quad (MAV_TYPE_QUADROTOR 2), as the phone reports it. */
    static final FcInfo ARDUCOPTER = new FcInfo(FcInfo.FC_VARIANT_ARDUPILOT, 4, 6, 0, 0, 0, 3, 2);

    private static FcTelemetry readBack(FcInfo fc, TelemetryData... records) throws IOException {
        FcTelemetry t = new FcTelemetry();
        feed(t, fc, records);
        return t;
    }

    private static void feed(FcTelemetry t, FcInfo fc, TelemetryData... records) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (TelemetryData r : records) TelemetryRecords.write(r, fc, out);
        t.parse(new DataReader(bytes.toByteArray(), true), fc);
    }

    // --- the records as Mavlink.java writes them ---

    static TelemetryData mode(int customMode, boolean armed) {
        DataWriter w = new DataWriter(true);
        w.writeInt(customMode);
        w.writeBoolean(armed);
        return new TelemetryData(FcCommon.DD_MAVLINK_MODE, w.getData());
    }

    static TelemetryData attitude(double rollDeg, double pitchDeg, double yawDeg) {
        DataWriter w = new DataWriter(true);
        w.writeShort((short) (rollDeg * 10));
        w.writeShort((short) (pitchDeg * -10));
        w.writeShort((short) yawDeg);
        return new TelemetryData(FcCommon.DD_MAVLINK_ATTITUDE, w.getData());
    }

    static TelemetryData batteryStatus(int centiAmps, int mah) {
        DataWriter w = new DataWriter(true);
        w.writeShort((short) centiAmps);
        w.writeInt(mah);
        w.writeByte((byte) 80);
        w.writeInt(0);
        return new TelemetryData(FcCommon.DD_MAVLINK_BATTERY_STATUS, w.getData());
    }

    static TelemetryData sysStatus(int millivolts) {
        DataWriter w = new DataWriter(true);
        w.writeByte((byte) Math.round(millivolts / 4100f));
        w.writeShort((short) millivolts);
        w.writeByte((byte) 80);
        return new TelemetryData(FcCommon.DD_MAVLINK_SYS_STATUS, w.getData());
    }

    static TelemetryData statusText(int severity, String text) {
        DataWriter w = new DataWriter(true);
        w.writeByte((byte) severity);
        w.writeUTF(text);
        return new TelemetryData(FcCommon.DD_MAVLINK_STATUS_TEXT, w.getData());
    }

    static TelemetryData gpsRaw(int mavFix, int cmPerS, int sats) {
        DataWriter w = new DataWriter(true);
        w.writeByte((byte) mavFix);
        w.writeShort((short) cmPerS);
        w.writeByte((byte) sats);
        return new TelemetryData(FcCommon.DD_MAVLINK_GPS_RAW_INT, w.getData());
    }

    static TelemetryData globalPosition(double lat, double lon, int relAltMm, int vzCmPerS, int travelledM) {
        DataWriter w = new DataWriter(true);
        w.writeInt((int) Math.round(lat * 1e7));
        w.writeInt((int) Math.round(lon * 1e7));
        w.writeInt(relAltMm);
        w.writeShort((short) vzCmPerS);
        w.writeInt(travelledM);
        w.writeInt(0);           // the phone's distance home: 0 while disarmed
        w.writeShort((short) 0);
        return new TelemetryData(FcCommon.DD_MAVLINK_GLOBAL_POSITION_INT, w.getData());
    }

    static TelemetryData home(double lat, double lon) {
        DataWriter w = new DataWriter(true);
        w.writeInt((int) Math.round(lat * 1e7));
        w.writeInt((int) Math.round(lon * 1e7));
        return new TelemetryData(FcCommon.DD_MAVLINK_HOME_POSITION, w.getData());
    }

    static TelemetryData systemTime(int bootMs, int flightMs, int armedMs) {
        DataWriter w = new DataWriter(true);
        w.writeInt(bootMs);
        w.writeInt(flightMs);
        w.writeInt(armedMs);
        return new TelemetryData(FcCommon.DD_MAVLINK_SYSTEM_TIME, w.getData());
    }

    static TelemetryData oneByte(short code, int value) {
        DataWriter w = new DataWriter(true);
        w.writeByte((byte) value);
        return new TelemetryData(code, w.getData());
    }

    static TelemetryData oneShort(short code, int value) {
        DataWriter w = new DataWriter(true);
        w.writeShort((short) value);
        return new TelemetryData(code, w.getData());
    }

    static TelemetryData osdConfig(int items) {
        DataWriter w = new DataWriter(true);
        w.writeBoolean(true);
        w.writeByte((byte) 1);
        w.writeByte((byte) 0);
        w.writeByte((byte) 10);
        w.writeByte((byte) 30);
        w.writeByte((byte) 6);
        w.writeFloat(14.0f);
        w.writeFloat(3.5f);
        w.writeByte((byte) 4);
        w.writeByte((byte) items);
        for (int i = 0; i < items; i++) {
            w.writeBoolean(i % 2 == 0);
            w.writeByte((byte) i);
            w.writeByte((byte) (i + 1));
        }
        return new TelemetryData(FcCommon.DD_AP_OSD_CONFIG, w.getData());
    }

    // --- the tests ---

    @Test
    void everyMavlinkRecordIsReadAndNoneEndsThePacket() throws IOException {
        // the OSD config and VTX power first: before, the first record the PC didn't know ended the packet
        FcTelemetry t = readBack(ARDUCOPTER,
                osdConfig(5),
                oneShort(FcCommon.DD_AP_VTX_POWER, 25),
                oneShort(FcCommon.DD_MAVLINK_SCALED_PRESSURE, 2150),
                mode(ArduModes.LOITER, false),
                batteryStatus(1250, 330),
                sysStatus(16_620),
                gpsRaw(3, 500, 14),
                home(59.9, 10.7),
                globalPosition(59.9009, 10.7, 61_500, -120, 140),
                systemTime(95_000, 40_000, 12_000),
                oneByte(FcCommon.DD_MAVLINK_RC_CHANNELS, 254),
                oneByte(FcCommon.DD_MAVLINK_VFR_HUD, 37),
                statusText(4, "PreArm: Throttle below failsafe"),
                attitude(12.5, 4.0, 271));

        assertEquals(0, t.getUnknownCodes(), "every record known");
        assertEquals(12.5, t.getAttitude().rollDeg(), 1e-9, "the last record reached");
        assertEquals(271, t.getAttitude().headingDeg());

        FcTelemetry.Status s = t.getStatus();
        assertFalse(s.armed());
        assertTrue(s.armedKnown(), "ArduPilot's armed flag is its own word");
        assertEquals("LOITER", FcTelemetry.flightMode(s));
        assertEquals(List.of(), FcTelemetry.otherModes(s));
        assertEquals(ArduModes.LOITER, t.getArduMode());

        FcTelemetry.Battery b = t.getBattery();
        assertEquals(16.62, b.volts(), 1e-9);
        assertEquals(12.5, b.amps(), 1e-9);
        assertEquals(330, b.mahDrawn());

        FcTelemetry.Gps g = t.getGps();
        assertEquals(2, g.fix(), "MAVLink 3D fix = Betaflight's 2");
        assertEquals(14, g.sats());
        assertEquals(18.0, g.speedKmh(), 1e-9);
        assertEquals(59.9009, g.latDeg(), 1e-6);
        assertEquals(10.7, g.lonDeg(), 1e-6);
        assertEquals(62, g.altM());
        assertEquals(140, g.travelledM());
        assertEquals(100, g.distanceHomeM(), 1, "counted on the PC from ArduPilot's home");
        assertEquals(180, g.directionHomeDeg(), "home is due south");
        assertTrue(g.positionOk());

        assertEquals(61.5, t.getAltitude().meters(), 1e-9);
        assertEquals(1.2, t.getAltitude().varioMetersPerSecond(), 1e-9, "MAVLink's vz points down: -1.2 m/s down is a climb");
        assertEquals(new FcTelemetry.Timers(95, 40, 12), t.getTimers());
        assertEquals(100, t.getRssiPercent());
        assertEquals(37, t.getThrottlePercent());
        assertEquals(59.9, t.getHome().latDeg(), 1e-6);
        assertEquals("PreArm: Throttle below failsafe", t.recentStatusTexts().get(0).text());
        assertEquals(4, t.recentStatusTexts().get(0).severity());
        assertTrue(t.msSinceFcData(MonoClock.ms()) < 1000, "ArduPilot's records count as the FC talking");
    }

    @Test
    void armedNeedsTwoReportsAndDisarmedIsKnownAtOnce() throws IOException {
        FcTelemetry t = new FcTelemetry();
        feed(t, ARDUCOPTER, mode(ArduModes.STABILIZE, true));
        long now = MonoClock.ms();
        assertFalse(t.isArmedNow(now, 1000), "one armed report alone may be a late one");
        feed(t, ARDUCOPTER, mode(ArduModes.STABILIZE, true));
        assertTrue(t.isArmedNow(MonoClock.ms(), 1000));
        assertEquals(List.of("ARM", "STABILIZE"), t.getStatus().activeModes());

        feed(t, ARDUCOPTER, mode(ArduModes.RTL, false));
        assertFalse(t.isArmedNow(MonoClock.ms(), 1000));
        assertTrue(t.isDisarmedNow(MonoClock.ms(), 1000), "no mode names needed: ArduPilot says disarmed itself");
        assertEquals("RTL", FcTelemetry.flightMode(t.getStatus()));
    }

    @Test
    void beforeTheFirstHeartbeatTheDroneIsNotKnown() throws IOException {
        // the phone sends no mode record until it has read the FC's parameters: no status, the guards ask
        FcTelemetry t = readBack(ARDUCOPTER, attitude(0, 0, 10), sysStatus(16_000));
        assertNull(t.getStatus());
        assertFalse(t.isDisarmedNow(MonoClock.ms(), 1000));
        assertEquals(-1, t.getArduMode());
    }

    @Test
    void unmeasuredFiguresStayUnknown() throws IOException {
        FcTelemetry t = readBack(ARDUCOPTER,
                batteryStatus(-1, -1),
                sysStatus(0xffff),                          // no voltage: no battery
                gpsRaw(1, 0xffff, 255),                     // no fix, speed and sats not known
                globalPosition(0, 0, 0, 0, 0),
                oneByte(FcCommon.DD_MAVLINK_RC_CHANNELS, 255));
        assertNull(t.getBattery());
        FcTelemetry.Gps g = t.getGps();
        assertEquals(0, g.fix());
        assertEquals(0, g.sats());
        assertEquals(0, g.speedKmh(), 1e-9);
        assertFalse(g.positionOk(), "0/0 without a fix is no place");
        assertEquals(-1, g.distanceHomeM(), "no home yet");
        assertEquals(-1, t.getRssiPercent());
    }

    @Test
    void theBatteryTakesTheCurrentThatCameBeforeOrAfterTheVoltage() throws IOException {
        FcTelemetry t = readBack(ARDUCOPTER, sysStatus(25_200), batteryStatus(3000, 1200));
        assertEquals(25.2, t.getBattery().volts(), 1e-9);
        assertEquals(30.0, t.getBattery().amps(), 1e-9);
        assertEquals(1200, t.getBattery().mahDrawn());
    }

    @Test
    void statusTextsKeepTheNewestOnly() throws IOException {
        FcTelemetry t = new FcTelemetry();
        for (int i = 0; i < FcTelemetry.MAX_STATUS_TEXTS + 5; i++) feed(t, ARDUCOPTER, statusText(6, "text " + i));
        List<FcTelemetry.StatusText> texts = t.recentStatusTexts();
        assertEquals(FcTelemetry.MAX_STATUS_TEXTS, texts.size());
        assertEquals("text 5", texts.get(0).text());
        assertEquals("text " + (FcTelemetry.MAX_STATUS_TEXTS + 4), texts.get(texts.size() - 1).text());
    }

    @Test
    void modeNames() {
        assertEquals("ALT HOLD", ArduModes.name(ArduModes.ALT_HOLD, 2));
        assertEquals("SMART RTL", ArduModes.name(ArduModes.SMART_RTL, 13));
        assertEquals("FOLLOW", ArduModes.name(ArduModes.FOLLOW, 0), "MAV_TYPE not reported yet: a copter");
        assertEquals("MODE 8", ArduModes.name(8, 2), "a number Copter doesn't use");
        assertEquals("MODE 5", ArduModes.name(5, 1), "a fixed-wing (MAV_TYPE 1) numbers its modes otherwise");
        assertTrue(ArduModes.isReturnOrLand(ArduModes.LAND));
        assertFalse(ArduModes.isReturnOrLand(ArduModes.LOITER));
    }

    @Test
    void betaflightStatusesHaveNoArduPilotMode() {
        FcTelemetry.Status s = new FcTelemetry.Status(true, List.of("ARM", "ANGLE"), List.of(), 0, 0, 0);
        assertFalse(s.isArduPilot());
        assertEquals("ANGLE", FcTelemetry.flightMode(s));
    }
}
