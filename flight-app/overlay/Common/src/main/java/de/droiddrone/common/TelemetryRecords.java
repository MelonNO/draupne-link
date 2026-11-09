/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone.
 *
 *  DroidDrone is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DroidDrone is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidDrone.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.common;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * DRAUPNE Flight: the telemetry record the phone sends for an MSP reply or one of its own DD_ records - the code, then
 * the fields the PC reads (DRAUPNE Link's FcTelemetry.parse) in the protocol's byte order: the MSP payload is
 * little-endian, and partly skipped or rearranged here. Moved out of the phone's Udp unchanged; in Common, so the PC
 * tests the two halves together.
 */
public final class TelemetryRecords {
    private TelemetryRecords() {
    }

    /**
     * buf's record into out. Nothing before the flight controller's info is known (fcInfo null), but the phone's own
     * records (DD_TIMERS and on). A reply cut short throws, with what was written so far left in out.
     */
    public static void write(TelemetryData buf, FcInfo fcInfo, DataOutputStream out) throws IOException {
        if (fcInfo == null && buf.code < FcCommon.DD_TIMERS) return;
        out.writeShort(buf.code);
        DataReader buffer = new DataReader(buf.data, false);
        switch (buf.code){
            case FcCommon.MSP_ATTITUDE: {
                out.writeShort(buffer.readShort());//roll
                out.writeShort(buffer.readShort());//pitch
                out.writeShort(buffer.readShort());//yaw
                break;
            }
            case FcCommon.MSP_ALTITUDE: {
                out.writeInt(buffer.readInt());//altitude
                out.writeShort(buffer.readShort());//altVelocity
                if (fcInfo.getFcVariant() == FcInfo.FC_VARIANT_INAV){
                    out.writeInt(buffer.readInt());//altBaro
                }
                break;
            }
            case FcCommon.MSP_ANALOG: {
                buffer.readByte();//legacyVoltage
                out.writeShort(buffer.readShort());//mahDrawn
                out.writeShort(buffer.readShort());//rssi
                out.writeShort(buffer.readShort());//amperage
                if (fcInfo.getFcVariant() == FcInfo.FC_VARIANT_BETAFLIGHT) {
                    out.writeShort(buffer.readShort());//voltage
                }
                break;
            }
            case FcCommon.MSP_BOXIDS:
            case FcCommon.MSP_BOXNAMES: {
                byte[] data = buffer.getData();
                out.writeShort(data.length);
                out.write(data, 0, data.length);
                break;
            }
            case FcCommon.MSP_BATTERY_CONFIG: {
                switch (fcInfo.getFcVariant()){
                    case FcInfo.FC_VARIANT_INAV:{
                        buffer.readShort();//voltageScale
                        buffer.readByte();//voltageSource
                        buffer.readByte();//configCells
                        buffer.readShort();//cellDetect
                        out.writeShort(buffer.readShort());//minCellVoltage
                        out.writeShort(buffer.readShort());//maxCellVoltage
                        out.writeShort(buffer.readShort());//warningCellVoltage
                        buffer.readShort();//currentOffset
                        buffer.readShort();//currentScale
                        buffer.readInt();//batteryCapacity
                        buffer.readInt();//capacityWarning
                        buffer.readInt();//capacityCritical
                        buffer.readByte();//capacityUnit
                        break;
                    }
                    case FcInfo.FC_VARIANT_BETAFLIGHT:{
                        buffer.readByte();//vbatMinCellVoltage - legacy
                        buffer.readByte();//vbatMaxCellVoltage - legacy
                        buffer.readByte();//vbatWarningCellVoltage - legacy
                        buffer.readShort();//batteryCapacity
                        buffer.readByte();//voltageMeterSource
                        buffer.readByte();//currentMeterSource
                        out.writeShort(buffer.readShort());//minCellVoltage
                        out.writeShort(buffer.readShort());//maxCellVoltage
                        out.writeShort(buffer.readShort());//warningCellVoltage
                        break;
                    }
                }
                break;
            }
            case FcCommon.MSP_OSD_CONFIG: {
                out.writeShort(SettingsCommon.versionCompatibleCode);
                switch (fcInfo.getFcVariant()){
                    case FcInfo.FC_VARIANT_INAV:{
                        byte driver = buffer.readByte();
                        out.writeByte(driver);
                        if (driver == 0) break;
                        out.writeByte(buffer.readByte());//videoSystem
                        out.writeByte(buffer.readByte());//units
                        out.writeByte(buffer.readByte());//rssiAlarm
                        out.writeShort(buffer.readShort());//capacityWarning
                        out.writeShort(buffer.readShort());//timeAlarm
                        out.writeShort(buffer.readShort());//altAlarm
                        out.writeShort(buffer.readShort());//distAlarm
                        out.writeShort(buffer.readShort());//negAltAlarm
                        int osdItemCount = buffer.getRemaining() / 2;
                        out.writeShort(osdItemCount);
                        if (osdItemCount == 0) break;
                        for (int i = 0; i < osdItemCount; i++) {
                            out.writeShort(buffer.readShort());//osdItem
                        }
                        break;
                    }
                    case FcInfo.FC_VARIANT_BETAFLIGHT:{
                        buffer.readByte();//osdFlags
                        out.writeByte(buffer.readByte());//videoSystem
                        out.writeByte(buffer.readByte());//units
                        out.writeByte(buffer.readByte());//rssiAlarm
                        out.writeShort(buffer.readShort());//capacityWarning
                        buffer.readByte();//unused
                        int osdItemCount = buffer.readUnsignedByteAsInt();
                        out.writeShort(buffer.readShort());//altAlarm
                        out.writeShort(osdItemCount);
                        if (osdItemCount <= 0) break;
                        for (int i = 0; i < osdItemCount; i++) {
                            out.writeShort(buffer.readShort());//osdItem
                        }
                        int osdStatCount = buffer.readUnsignedByteAsInt();
                        out.writeByte(osdStatCount);
                        if (osdStatCount > 0){
                            for (int i = 0; i < osdStatCount; i++) {
                                out.writeByte(buffer.readByte());//osdStat
                            }
                        }
                        int osdTimerCount = buffer.readUnsignedByteAsInt();
                        out.writeByte(osdTimerCount);
                        if (osdTimerCount > 0){
                            for (int i = 0; i < osdTimerCount; i++) {
                                out.writeShort(buffer.readShort());//osdTimer
                            }
                        }
                        buffer.readShort();//legacyWarnings
                        out.writeByte(buffer.readByte());//osdWarningsCount
                        out.writeInt(buffer.readInt());//enabledWarnings
                        buffer.readByte();//osdProfileCount
                        out.writeByte(buffer.readByte());//osdSelectedProfile
                        buffer.readByte();//overlayRadioMode
                        out.writeByte(buffer.readByte());//cameraFrameWidth
                        out.writeByte(buffer.readByte());//cameraFrameHeight
                        if (buffer.getRemaining() >= 2) { // 1.46
                            buffer.readShort();//linkQualityAlarm
                        }
                        if (buffer.getRemaining() >= 2) { // 1.47
                            buffer.readShort();//rssiDbmAlarm
                        }
                        break;
                    }
                }
                break;
            }
            case FcCommon.MSP_VTX_CONFIG: {
                int vtxDevice = buffer.readUnsignedByteAsInt();
                out.writeShort(vtxDevice);
                if (vtxDevice == FcCommon.VTXDEV_UNKNOWN) break;
                out.writeByte(buffer.readByte());//band
                out.writeByte(buffer.readByte());//channel
                out.writeByte(buffer.readByte());//power
                out.writeByte(buffer.readByte());//pitMode
                if (fcInfo.getFcVariant() == FcInfo.FC_VARIANT_BETAFLIGHT) out.writeShort(buffer.readShort());//frequency
                out.writeByte(buffer.readByte());//deviceIsReady
                out.writeByte(buffer.readByte());//lowPowerDisarm
                break;
            }
            case FcCommon.MSP_BATTERY_STATE: {
                out.writeByte(buffer.readByte());//cellCount
                out.writeShort(buffer.readShort());//capacity
                buffer.readByte();//legacyVoltage
                out.writeShort(buffer.readShort());//mahDrawn
                out.writeShort(buffer.readShort());//amperage
                out.writeByte(buffer.readByte());//batteryState
                out.writeShort(buffer.readShort());//voltage
                break;
            }
            case FcCommon.MSP_STATUS: {
                out.writeShort(buffer.readShort());//cycleTime
                out.writeShort(buffer.readShort());//i2cErrorCount
                out.writeShort(buffer.readShort());//sensorStatus
                switch (fcInfo.getFcVariant()){
                    case FcInfo.FC_VARIANT_INAV:{
                        buffer.readInt();//legacyModeFlags
                        out.writeByte(buffer.readByte());//configProfile
                        break;
                    }
                    case FcInfo.FC_VARIANT_BETAFLIGHT:{
                        int firstModeFlag = buffer.readInt();
                        out.writeByte(buffer.readByte());//currentPidProfileIndex
                        out.writeShort(buffer.readShort());//averageSystemLoad
                        buffer.readShort();//unused
                        byte[] modeFlags;
                        int modeFlagsSize = buffer.readByte()+4;
                        out.writeByte(modeFlagsSize);
                        if (modeFlagsSize > 0) {
                            modeFlags = new byte[modeFlagsSize];
                            modeFlags[0] = (byte) (firstModeFlag & 0xFF);
                            modeFlags[1] = (byte) (firstModeFlag >> 8 & 0xFF);
                            modeFlags[2] = (byte) (firstModeFlag >> 16 & 0xFF);
                            modeFlags[3] = (byte) (firstModeFlag >> 24 & 0xFF);
                            for (int i = 4; i < modeFlagsSize; i++) {
                                modeFlags[i] = buffer.readByte();
                            }
                            for (int i = 0; i < modeFlagsSize; i++) {
                                out.writeByte(modeFlags[i]);
                            }
                        }
                        buffer.readByte();//armingFlagsCount
                        out.writeInt(buffer.readInt());//armingFlags
                        out.writeByte(buffer.readByte());//configStateFlags
                        short coreTemperatureCelsius = 0;
                        if (buffer.getRemaining() >= 2) { // 1.46
                            coreTemperatureCelsius = buffer.readShort();
                        }
                        out.writeShort(coreTemperatureCelsius);
                        break;
                    }
                }
                break;
            }
            case FcCommon.MSP_COMP_GPS: {
                out.writeShort(buffer.readShort());//distanceToHome
                out.writeShort(buffer.readShort());//directionToHome
                out.writeByte(buffer.readByte());//gpsHeartbeat
                break;
            }
            case FcCommon.MSP_OSD_CANVAS: {
                out.writeByte(buffer.readByte());//cols
                out.writeByte(buffer.readByte());//rows
                break;
            }
            case FcCommon.MSP2_INAV_STATUS: {
                out.writeShort(buffer.readShort());//cycleTime
                out.writeShort(buffer.readShort());//i2cErrorCount
                out.writeShort(buffer.readShort());//sensorStatus
                out.writeShort(buffer.readShort());//averageSystemLoad
                out.writeByte(buffer.readByte());//profiles
                out.writeInt(buffer.readInt());//armingFlags
                int modeFlagsSize = (int)Math.ceil((buffer.getRemaining() - 1) / 4.0);
                out.writeByte(modeFlagsSize);
                if (modeFlagsSize > 0) {
                    for (int i = 0; i < modeFlagsSize; i++) {
                        out.writeInt(buffer.readInt());//modeFlags
                    }
                }
                break;
            }
            case FcCommon.MSP2_INAV_ANALOG: {
                out.writeByte(buffer.readByte());//batteryInfo
                out.writeShort(buffer.readShort());//voltage
                out.writeShort(buffer.readShort());//amperage
                out.writeInt(buffer.readInt());//power
                out.writeInt(buffer.readInt());//mahDrawn
                out.writeInt(buffer.readInt());//mwhDrawn
                out.writeInt(buffer.readInt());//batteryRemainingCapacity
                out.writeByte(buffer.readByte());//batteryPercentage
                out.writeShort(buffer.readShort());//rssi
                break;
            }
            case FcCommon.DD_TIMERS: {
                out.writeInt(buffer.readInt());//onTime
                out.writeInt(buffer.readInt());//flyTime
                out.writeInt(buffer.readInt());//lastArmTime
                break;
            }
            case FcCommon.DD_PHONE_BATTERY_STATE: {
                out.writeByte(buffer.readByte());//batteryLevel
                out.writeBoolean(buffer.readBoolean());//isCharging
                break;
            }
            case FcCommon.DD_CAMERA_FPS: {
                out.writeShort(buffer.readShort());//cameraFps
                break;
            }
            case FcCommon.DD_VIDEO_BIT_RATE: {
                out.writeFloat(buffer.readFloat());//videoBitRate
                break;
            }
            case FcCommon.DD_VIDEO_RECORDER_STATE: {
                out.writeBoolean(buffer.readBoolean());//isRecording
                out.writeInt(buffer.readInt());//recordingTimeSec
                break;
            }
            case FcCommon.DD_VIDEO_RECORDER_START_STOP: {
                break; // the code alone (the phone starts or stops its recording)
            }
            case FcCommon.DD_NETWORK_STATE: {
                out.writeByte(buffer.readByte());//networkType
                out.writeByte(buffer.readByte());//rssi
                break;
            }
            // already Big Endian encoded, can be written as a whole array
            case FcCommon.MSP_RAW_GPS:
            case FcCommon.DD_AP_OSD_CONFIG:
            case FcCommon.DD_MAVLINK_MODE:
            case FcCommon.DD_MAVLINK_BATTERY_STATUS:
            case FcCommon.DD_MAVLINK_SYS_STATUS:
            case FcCommon.DD_MAVLINK_STATUS_TEXT:
            case FcCommon.DD_MAVLINK_GPS_RAW_INT:
            case FcCommon.DD_MAVLINK_GLOBAL_POSITION_INT:
            case FcCommon.DD_MAVLINK_HOME_POSITION:
            case FcCommon.DD_MAVLINK_SYSTEM_TIME:
            case FcCommon.DD_MAVLINK_RC_CHANNELS:
            case FcCommon.DD_MAVLINK_SCALED_PRESSURE:
            case FcCommon.DD_AP_VTX_POWER:
            case FcCommon.DD_MAVLINK_VFR_HUD:
            case FcCommon.DD_MAVLINK_ATTITUDE: {
                out.write(buffer.getData());
                break;
            }
        }
    }
}
