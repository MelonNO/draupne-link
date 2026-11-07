package de.droiddrone.pc.fleet;

import de.droiddrone.common.FcCommon;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.common.UdpPacketData;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The telemetry records a virtual phone sends, byte for byte as DRAUPNE Flight lays them out after re-packing the
 * flight controller's MSP replies (overlay Flight Udp.fillTelemetryPacketData, PcLinkStats, PowerMode) - the PC reads
 * them with its real parser (FcTelemetry), and FleetTelemetryTest reads every one back through it, so the two can't
 * drift apart unnoticed. Each record is [code i16][payload], no length.
 */
final class TelemetryRecords {
    /** DRAUPNE Flight's own records (FcTelemetry's codes). */
    static final short PHONE_LINK_STATS = 0x40F0, PHONE_APP_VERSION = 0x40F1, PHONE_ENCODER = 0x40F2, PHONE_POSITION = 0x40F3,
            PHONE_STATE = 0x40F4, PHONE_POWER = 0x40F5, PHONE_RC_LAG = 0x40F6;
    /** PHONE_STATE flags (overlay PcLinkStats). */
    static final int STATE_RX_MAP_KNOWN = 1, STATE_CAMERA_OPEN = 8, STATE_AUDIO_ON = 32, STATE_AUDIO_HELD = 64;
    /** MSP_STATUS sensor bits: accelerometer, compass, GPS, gyro. */
    static final int SENSOR_ACC = 1, SENSOR_MAG = 4, SENSOR_GPS = 8, SENSOR_GYRO = 32;

    private TelemetryRecords() {
    }

    /** The FcInfo answer: Betaflight 4.4.1, MSP API 1.45, as the bench drone reports. */
    static byte[] fcInfoPacket() throws IOException {
        UdpPacketData p = new UdpPacketData(UdpCommon.FcInfo);
        p.daos.writeByte(FcInfo.FC_VARIANT_BETAFLIGHT);
        p.daos.writeByte(4);
        p.daos.writeByte(4);
        p.daos.writeByte(1);
        p.daos.writeByte(0); // MSP protocol version
        p.daos.writeByte(1); // API 1.45
        p.daos.writeByte(45);
        p.daos.writeByte(0); // platform: multirotor
        return p.getData();
    }

    static void attitude(DataOutputStream out, double rollDeg, double pitchDeg, double headingDeg) throws IOException {
        out.writeShort(FcCommon.MSP_ATTITUDE);
        out.writeShort((int) Math.round(rollDeg * 10));
        out.writeShort((int) Math.round(pitchDeg * 10));
        out.writeShort((int) Math.round(headingDeg) % 360);
    }

    static void altitude(DataOutputStream out, double altM, double varioMs) throws IOException {
        out.writeShort(FcCommon.MSP_ALTITUDE);
        out.writeInt((int) Math.round(altM * 100));
        out.writeShort((int) Math.round(varioMs * 100));
    }

    /** MSP_ANALOG as the phone passes it on for Betaflight: mAh, RSSI 0-1023, amps x 100, volts x 100. */
    static void analog(DataOutputStream out, int mah, int rssi, double amps, double volts) throws IOException {
        out.writeShort(FcCommon.MSP_ANALOG);
        out.writeShort(mah);
        out.writeShort(rssi);
        out.writeShort((int) Math.round(amps * 100));
        out.writeShort((int) Math.round(volts * 100));
    }

    static void batteryState(DataOutputStream out, int cells, int capacityMah, int mah, double amps, double volts) throws IOException {
        out.writeShort(FcCommon.MSP_BATTERY_STATE);
        out.writeByte(cells);
        out.writeShort(capacityMah);
        out.writeShort(mah);
        out.writeShort((int) Math.round(amps * 100));
        out.writeByte(0); // battery state: OK
        out.writeShort((int) Math.round(volts * 100));
    }

    /** Cell limits in volts (min, max, warning), as the phone keeps them from MSP_BATTERY_CONFIG. */
    static void batteryConfig(DataOutputStream out, double min, double max, double warning) throws IOException {
        out.writeShort(FcCommon.MSP_BATTERY_CONFIG);
        out.writeShort((int) Math.round(min * 100));
        out.writeShort((int) Math.round(max * 100));
        out.writeShort((int) Math.round(warning * 100));
    }

    /** MSP_STATUS for Betaflight as re-packed by the phone: the mode flags as bytes (one bit per box), arming flags. */
    static void status(DataOutputStream out, int sensors, int modeFlags, int armingFlags, int loadPercent, int coreTempC) throws IOException {
        out.writeShort(FcCommon.MSP_STATUS);
        out.writeShort(125); // cycle time, us
        out.writeShort(0); // i2c errors
        out.writeShort(sensors);
        out.writeByte(0); // pid profile
        out.writeShort(loadPercent);
        out.writeByte(4); // the mode flags' size: four bytes (no extra ones)
        out.writeByte(modeFlags & 0xff);
        out.writeByte(modeFlags >> 8 & 0xff);
        out.writeByte(modeFlags >> 16 & 0xff);
        out.writeByte(modeFlags >> 24 & 0xff);
        out.writeInt(armingFlags);
        out.writeByte(0); // config state flags
        out.writeShort(coreTempC);
    }

    /** MSP_RAW_GPS plus the phone's own travelled metres. */
    static void rawGps(DataOutputStream out, int fix, int sats, double lat, double lon, int altM, double speedMs, double courseDeg,
                       double hdop, int travelledM) throws IOException {
        out.writeShort(FcCommon.MSP_RAW_GPS);
        out.writeByte(fix);
        out.writeByte(sats);
        out.writeInt((int) Math.round(lat * 1e7));
        out.writeInt((int) Math.round(lon * 1e7));
        out.writeShort(altM);
        out.writeShort((int) Math.round(speedMs * 100));
        out.writeShort((int) Math.round(courseDeg * 10) % 3600);
        out.writeShort((int) Math.round(hdop * 100));
        out.writeInt(travelledM);
    }

    static void compGps(DataOutputStream out, int distanceM, int directionDeg) throws IOException {
        out.writeShort(FcCommon.MSP_COMP_GPS);
        out.writeShort(Math.min(32767, distanceM));
        out.writeShort(directionDeg);
        out.writeByte(1); // GPS heartbeat
    }

    static void boxNames(DataOutputStream out, String[] names) throws IOException {
        byte[] b = (String.join(";", names) + ";").getBytes(StandardCharsets.US_ASCII);
        out.writeShort(FcCommon.MSP_BOXNAMES);
        out.writeShort(b.length);
        out.write(b);
    }

    static void boxIds(DataOutputStream out, int[] ids) throws IOException {
        out.writeShort(FcCommon.MSP_BOXIDS);
        out.writeShort(ids.length);
        for (int id : ids) out.writeByte(id);
    }

    static void timers(DataOutputStream out, int onS, int flyS, int lastArmS) throws IOException {
        out.writeShort(FcCommon.DD_TIMERS);
        out.writeInt(onS);
        out.writeInt(flyS);
        out.writeInt(lastArmS);
    }

    static void phoneBattery(DataOutputStream out, int percent, boolean charging) throws IOException {
        out.writeShort(FcCommon.DD_PHONE_BATTERY_STATE);
        out.writeByte(percent);
        out.writeBoolean(charging);
    }

    static void cameraFps(DataOutputStream out, int fps) throws IOException {
        out.writeShort(FcCommon.DD_CAMERA_FPS);
        out.writeShort(fps);
    }

    static void videoBitRate(DataOutputStream out, float mbps) throws IOException {
        out.writeShort(FcCommon.DD_VIDEO_BIT_RATE);
        out.writeFloat(mbps);
    }

    /** The phone's mobile network: type (NetworkState: 4 = LTE) and signal 0-100. */
    static void network(DataOutputStream out, int type, int signalPercent) throws IOException {
        out.writeShort(FcCommon.DD_NETWORK_STATE);
        out.writeByte(type);
        out.writeByte(signalPercent);
    }

    static void linkStats(DataOutputStream out, int rcInGapMs, int rcOutGapMs, int writeMaxMs) throws IOException {
        out.writeShort(PHONE_LINK_STATS);
        out.writeShort(Math.min(32767, rcInGapMs));
        out.writeShort(Math.min(32767, rcOutGapMs));
        out.writeShort(writeMaxMs);
        out.writeShort(0); // write timeouts
        out.writeShort(0); // skipped
    }

    static void version(DataOutputStream out, String version) throws IOException {
        byte[] b = version.getBytes(StandardCharsets.UTF_8);
        out.writeShort(PHONE_APP_VERSION);
        out.writeShort(b.length);
        out.write(b);
    }

    static void encoder(DataOutputStream out, int bitRate) throws IOException {
        out.writeShort(PHONE_ENCODER);
        out.writeInt(bitRate);
        out.writeByte(0); // no intra refresh
    }

    static void phonePosition(DataOutputStream out, double lat, double lon, int altM, int accuracyM, double speedKmh, int courseDeg, int ageS) throws IOException {
        out.writeShort(PHONE_POSITION);
        out.writeInt((int) Math.round(lat * 1e7));
        out.writeInt((int) Math.round(lon * 1e7));
        out.writeShort(altM);
        out.writeShort(accuracyM);
        out.writeShort((int) Math.round(speedKmh * 10));
        out.writeShort(courseDeg);
        out.writeShort(ageS);
    }

    /** [flags u8][lastRcSeq u8, 255 none][rcWriteAgeMs u16, 65535 unknown][rcInToOutMs u16]. */
    static void phoneState(DataOutputStream out, int flags, int lastRcSeq, long rcWriteAgeMs, int rcInToOutMs) throws IOException {
        out.writeShort(PHONE_STATE);
        out.writeByte(flags);
        out.writeByte(lastRcSeq < 0 ? 255 : lastRcSeq & 0xff);
        out.writeShort(rcWriteAgeMs < 0 ? 65535 : (int) Math.min(65534, rcWriteAgeMs));
        out.writeShort(rcInToOutMs < 0 ? 65535 : rcInToOutMs);
    }

    /** Last in its packet: [mode u8][checkIntervalMin u8][sleepInS u16][caps u8]. */
    static void phonePower(DataOutputStream out, int mode, int intervalMin, int sleepInS, int caps) throws IOException {
        out.writeShort(PHONE_POWER);
        out.writeByte(mode);
        out.writeByte(intervalMin);
        out.writeShort(sleepInS);
        out.writeByte(caps);
    }

    /** Last in its packet: [lagMs u16][maxLagMs u16][dropped u16] (65535 unknown). */
    static void rcLag(DataOutputStream out, int lagMs, int maxLagMs, int dropped) throws IOException {
        out.writeShort(PHONE_RC_LAG);
        out.writeShort(lagMs < 0 ? 65535 : Math.min(65534, lagMs));
        out.writeShort(maxLagMs < 0 ? 65535 : Math.min(65534, maxLagMs));
        out.writeShort(Math.min(65535, dropped));
    }
}
