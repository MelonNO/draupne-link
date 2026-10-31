package de.droiddrone.pc.gui;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.NetworkState;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.video.VideoPipeline;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Sample flight data for the OSD editor's preview: an armed 6S quad in ANGLE with air mode, GPS fix, LTE. Fed through
 * the real telemetry parser, written as the Flight app writes its records, so the preview shows what the OSD would.
 */
final class OsdPreview {
    private static final FcInfo BETAFLIGHT = new FcInfo(FcInfo.FC_VARIANT_BETAFLIGHT, 4, 5, 1, 0, 1, 46, 0);
    private static final VideoPipeline.Stats VIDEO = new VideoPipeline.Stats(true, 1280, 720, 60, 60, 4.2, 0.9, 0.6, 0, 0, null, false, 4, 0, 0);
    /** roll, pitch, yaw, throttle: a little right roll and forward pitch, 35 % throttle. */
    private static final short[] CHANNELS = {1580, 1430, 1500, 1350};

    private final FcTelemetry telemetry = new FcTelemetry();
    private final byte[] packet = packet();

    /** The sample, refreshed so it never goes stale (and the fly time runs while the editor is open). */
    OsdRenderer.Data data() {
        telemetry.parse(new DataReader(packet, true), BETAFLIGHT);
        return new OsdRenderer.Data(true, true, 42, true, false, telemetry, VIDEO, false, null, CHANNELS, true, false, false, false, null, false, null, false);
    }

    private static byte[] packet() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bytes);
            byte[] names = "ARM;ANGLE;HORIZON;BEEPER;AIR MODE;".getBytes(StandardCharsets.US_ASCII);
            o.writeShort(FcCommon.MSP_BOXNAMES);
            o.writeShort(names.length);
            o.write(names);
            o.writeShort(FcCommon.MSP_BATTERY_CONFIG);
            o.writeShort(330); o.writeShort(430); o.writeShort(350);           // min, max, warning per cell
            o.writeShort(FcCommon.MSP_ATTITUDE);
            o.writeShort(80); o.writeShort(-40); o.writeShort(75);             // roll 8.0, pitch -4.0, heading 75
            o.writeShort(FcCommon.MSP_ALTITUDE);
            o.writeInt(4520); o.writeShort(120);                               // 45.2 m, climbing 1.2 m/s
            o.writeShort(FcCommon.MSP_ANALOG);
            o.writeShort(640); o.writeShort(1004); o.writeShort(1450); o.writeShort(2340); // mAh, rssi, 14.5 A, 23.40 V
            o.writeShort(FcCommon.MSP_BATTERY_STATE);
            o.writeByte(6); o.writeShort(1500); o.writeShort(640); o.writeShort(1450); o.writeByte(0); o.writeShort(2340);
            o.writeShort(FcCommon.MSP_STATUS);
            o.writeShort(125); o.writeShort(0); o.writeShort(0x23);
            o.writeByte(0); o.writeShort(14);                                  // pid profile, cpu load
            o.writeByte(4); o.writeByte(0b10011); o.writeByte(0); o.writeByte(0); o.writeByte(0); // ARM, ANGLE, AIR MODE
            o.writeInt(0); o.writeByte(0); o.writeShort(48);                   // no blockers, config, core 48 C
            o.writeShort(FcCommon.MSP_RAW_GPS);
            o.writeByte(2); o.writeByte(14); o.writeInt(599_123_456); o.writeInt(107_512_345);
            o.writeShort(52); o.writeShort(1250); o.writeShort(0); o.writeShort(90); o.writeInt(1840);
            o.writeShort(FcCommon.MSP_COMP_GPS);
            o.writeShort(230); o.writeShort(140); o.writeByte(0);
            o.writeShort(FcCommon.DD_TIMERS);
            o.writeInt(754); o.writeInt(183); o.writeInt(60);
            o.writeShort(FcCommon.DD_PHONE_BATTERY_STATE);
            o.writeByte(76); o.writeBoolean(false);
            o.writeShort(FcCommon.DD_NETWORK_STATE);
            o.writeByte(NetworkState.NETWORK_TYPE_LTE); o.writeByte(82);
            o.writeShort(FcCommon.DD_CAMERA_FPS);
            o.writeShort(60);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
