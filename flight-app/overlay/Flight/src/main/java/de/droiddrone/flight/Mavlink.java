/*
 *  This file is part of DroidDrone.
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
 *
 *  Modified for DRAUPNE Link (F-070d): upstream's Mavlink with the pace of its stick frames (setRcMinPeriod,
 *  setRcChannelsOverride) kept by RcPace on the monotonic clock - on the wall clock, a clock set back N seconds
 *  dropped every stick frame for N seconds. Nothing else is changed.
 */

package de.droiddrone.flight;

import static de.droiddrone.common.Logcat.log;

import android.location.Location;
import android.location.LocationManager;

import com.MAVLink.MAVLinkPacket;
import com.MAVLink.Messages.MAVLinkPayload;
import com.MAVLink.common.msg_adsb_vehicle;
import com.MAVLink.common.msg_attitude;
import com.MAVLink.common.msg_autopilot_version;
import com.MAVLink.common.msg_battery_status;
import com.MAVLink.common.msg_command_long;
import com.MAVLink.common.msg_global_position_int;
import com.MAVLink.common.msg_gps_raw_int;
import com.MAVLink.common.msg_home_position;
import com.MAVLink.common.msg_param_request_read;
import com.MAVLink.common.msg_param_value;
import com.MAVLink.common.msg_rc_channels;
import com.MAVLink.common.msg_rc_channels_override;
import com.MAVLink.common.msg_scaled_pressure;
import com.MAVLink.common.msg_statustext;
import com.MAVLink.common.msg_sys_status;
import com.MAVLink.common.msg_system_time;
import com.MAVLink.common.msg_vfr_hud;
import com.MAVLink.enums.MAV_AUTOPILOT;
import com.MAVLink.enums.MAV_CMD;
import com.MAVLink.enums.MAV_MODE_FLAG;
import com.MAVLink.minimal.msg_heartbeat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.DataWriter;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.OsdCommon;
import de.droiddrone.common.RcPace;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.SettingsCommon;
import de.droiddrone.common.TelemetryData;
import de.droiddrone.common.Utils;

public class Mavlink {
    private final Serial serial;
    private final Config config;
    public final ArrayBlockingQueue<TelemetryData> telemetryOutputBuffer = new ArrayBlockingQueue<>(30);
    private final ArrayBlockingQueue<byte[]> serialRawData = new ArrayBlockingQueue<>(30);
    private final int componentId = 1;
    private final short targetComponent = 1;
    private int fcVariant;
    private final int apiProtocolVersion;
    private final int apiVersionMajor;
    private int apiVersionMinor;
    private int fcVersionMajor;
    private int fcVersionMinor;
    private int fcVersionPatchLevel;
    private FcInfo fcInfo;
    private final boolean isMavlink2;
    private int threadsId;
    private boolean isHeartBeatReceived;
    private int sequence = 0;
    private boolean runGetOsdConfig;
    private FcParams fcParams;
    private int telemetryIntervalUs;
    private long lastAttitudeTs;
    private long lastBatteryStatusTs;
    private long lastSysStatusTs;
    private long lastGpsRawIntTs;
    private long lastGlobalPositionIntTs;
    private long lastSystemTimeTs;
    private long lastRcChannelsTs;
    private long lastScaledPressureTs;
    private long lastVfrHudTs;
    private int  platformType;
    private int batteryCellCountDetected;
    private boolean isStatusTextReceived;
    private boolean isHomePositionReceived;
    private boolean isArmed;
    private long armedTs = 0;
    private long flightTs = 0;
    private long flightTime = 0;
    private int throttle = 0;
    private final RcPace rcPace = new RcPace(); // DRAUPNE Link (F-070d): was rcMinPeriod and rcLastFrame
    private byte cameraRecordVideoChannel = -1;
    private boolean cameraRecordLastState = false;
    private FcCommon.GpsFixTypesMavlink fixTypeArduPilot = FcCommon.GpsFixTypesMavlink.GPS_FIX_TYPE_NO_GPS;
    private float latDeg;
    private float lonDeg;
    private float homeLatDeg;
    private float homeLonDeg;
    private float groundSpeed; // km/h
    private int distanceToHome; // m
    private float traveledDistance; // m
    private float directionToHome; // grad
    private MavlinkUdpBridge mavlinkUdpBridge;
    private Udp udp;

    public Mavlink(Serial serial, Config config) {
        this.serial = serial;
        this.config = config;
        isMavlink2 = true;
        fcVariant = FcInfo.FC_VARIANT_UNKNOWN;
        apiProtocolVersion = 0;
        apiVersionMajor = 2;
        apiVersionMinor = -1;
        fcVersionMajor = -1;
        fcVersionMinor = -1;
        fcVersionPatchLevel = -1;
        platformType = -1;
        isHeartBeatReceived = false;
        isStatusTextReceived = false;
        isHomePositionReceived = false;
        isArmed = false;
        batteryCellCountDetected = 0;
    }

    public void setUdp(Udp udp){
        this.udp = udp;
    }

    public void setMavlinkUdpBridge(MavlinkUdpBridge mavlinkUdpBridge) {
        this.mavlinkUdpBridge = mavlinkUdpBridge;
    }

    public boolean isInitialized(){
        boolean isInitialized = (apiVersionMajor != -1 && apiVersionMinor != -1
                && fcVersionMajor != -1 && fcVersionMinor != -1 && fcVersionPatchLevel != -1
                && platformType != -1 && fcVariant != FcInfo.FC_VARIANT_UNKNOWN);
        if (isInitialized && fcInfo == null) setFcInfo();
        return isInitialized;
    }

    private void setFcInfo(){
        fcInfo = new FcInfo(fcVariant, fcVersionMajor, fcVersionMinor, fcVersionPatchLevel, apiProtocolVersion, apiVersionMajor, apiVersionMinor, platformType);
        log(fcInfo.getFcName() + " Ver. " + fcInfo.getFcVersionStr() + " detected.");
        log("Mavlink API Ver.: " + fcInfo.getFcApiVersionStr());
    }

    public void setRcMinPeriod(){
        rcPace.setMinPeriod(1000 / config.getRcRefreshRate() / 2);
    }

    public FcInfo getFcInfo(){
        return fcInfo;
    }

    public void runGetOsdConfig(){
        runGetOsdConfig = true;
    }

    public void initialize() {
        fcParams = new FcParams(this);
        telemetryOutputBuffer.clear();
        serialRawData.clear();
        telemetryIntervalUs = 1000000 / config.getTelemetryRefreshRate();
        setRcMinPeriod();
        threadsId++;
        Thread mavlinkThread = new Thread(mavlinkRun);
        mavlinkThread.setDaemon(false);
        mavlinkThread.setName("mavlinkThread");
        mavlinkThread.start();
        Thread serialDataThread = new Thread(serialDataRun);
        serialDataThread.setDaemon(false);
        serialDataThread.setName("serialDataThread");
        serialDataThread.start();
    }

    private final Runnable mavlinkRun = new Runnable() {
        public void run() {
            final int id = threadsId;
            final int timerDelayMs = 1000;
            log("Start Mavlink thread - OK");
            while (id == threadsId) {
                try {
                    // DRAUPNE Link (fleet AP-5): a ground station's heartbeat once a second while the phone's rules are in
                    // charge (the PC heard, or the phone's own link-loss plan running): ArduPilot's GCS failsafe fires only
                    // when this app itself has stopped
                    if (isHeartBeatReceived && gcsAlive.getAsBoolean()) sendGcsHeartbeat();
                    if (!isInitialized()) {
                        if (isHeartBeatReceived) getFcVersion();
                        Thread.sleep(timerDelayMs);
                        continue;
                    }
                    if (fcVariant == FcInfo.FC_VARIANT_ARDUPILOT) {
                        if (runGetOsdConfig) {
                            if (fcParams.isFcConfigInitialized()) {
                                runGetOsdConfig = false;
                                fcParams.sendOsdConfig();
                                cameraRecordVideoChannel = fcParams.getOptionChannelForValue(FcCommon.AP_RC_OPTION_CAMERA_RECORD_VIDEO);
                            } else {
                                fcParams.initializeFcConfig();
                            }
                        }
                    }else if (fcVariant == FcInfo.FC_VARIANT_PX4) {
                        if (!fcParams.isFcConfigInitialized()) {
                            fcParams.initializeFcConfig();
                        }
                    }
                    if (fcParams.isFcConfigInitialized()) {
                        getAttitude(telemetryIntervalUs);
                        getBatteryStatus(telemetryIntervalUs * 10);
                        getSystemStatus(telemetryIntervalUs * 10);
                        getStatusText(telemetryIntervalUs * 20);
                        getGpsRawInt(telemetryIntervalUs * 10);
                        getGlobalPositionInt(telemetryIntervalUs * 10);
                        getHomePosition(telemetryIntervalUs * 50);
                        getSystemTime(telemetryIntervalUs * 10);
                        getRcChannels(telemetryIntervalUs * 10);
                        getScaledPressure(telemetryIntervalUs * 10);
                        getVfrHud(telemetryIntervalUs * 10);
                        if (fcVariant == FcInfo.FC_VARIANT_ARDUPILOT) {
                            requestFcParameter(FcCommon.AP_PARAM_VTX_POWER);
                        }
                    }
                    Thread.sleep(timerDelayMs);
                } catch (Exception e) {
                    log("Mavlink thread error: " + e);
                }
            }
        }
    };

    private void requestFcParameter(String paramIdStr){
        if (paramIdStr == null || paramIdStr.isEmpty() || paramIdStr.length() > 16) return;
        byte[] paramId = new byte[16];
        System.arraycopy(paramIdStr.getBytes(StandardCharsets.US_ASCII), 0, paramId, 0, paramIdStr.length());
        MAVLinkPacket packet = new msg_param_request_read((short)-1, targetSys(), targetComponent,
                paramId, config.getMavlinkGcsSysId(), componentId, isMavlink2).pack();
        packet.seq = getSequence();
        serial.writeDataMavlink(packet.encodePacket());
    }

    private void getFcVersion(){
        MAVLinkPacket packet = new msg_command_long(msg_autopilot_version.MAVLINK_MSG_ID_AUTOPILOT_VERSION, 0, 0, 0, 0, 0, 0,
                MAV_CMD.MAV_CMD_REQUEST_MESSAGE, targetSys(), targetComponent, (short)0, config.getMavlinkGcsSysId(), componentId, isMavlink2).pack();
        packet.seq = getSequence();
        serial.writeDataMavlink(packet.encodePacket());
    }

    private void getAttitude(int interval){
        if (interval > 0 && System.currentTimeMillis() - lastAttitudeTs < interval / 200) return;
        setMessageInterval(msg_attitude.MAVLINK_MSG_ID_ATTITUDE, interval);
    }

    private void getBatteryStatus(int interval){
        if (interval > 0 && System.currentTimeMillis() - lastBatteryStatusTs < interval / 200) return;
        setMessageInterval(msg_battery_status.MAVLINK_MSG_ID_BATTERY_STATUS, interval);
    }

    private void getSystemStatus(int interval){
        if (interval > 0 && System.currentTimeMillis() - lastSysStatusTs < interval / 200) return;
        setMessageInterval(msg_sys_status.MAVLINK_MSG_ID_SYS_STATUS, interval);
    }

    private void getStatusText(int interval){
        if (interval > 0 && isStatusTextReceived) return;
        setMessageInterval(msg_statustext.MAVLINK_MSG_ID_STATUSTEXT, interval);
    }

    private void getGpsRawInt(int interval){
        if (interval > 0 && System.currentTimeMillis() - lastGpsRawIntTs < interval / 200) return;
        setMessageInterval(msg_gps_raw_int.MAVLINK_MSG_ID_GPS_RAW_INT, interval);
    }

    private void getGlobalPositionInt(int interval){
        if (interval > 0 && System.currentTimeMillis() - lastGlobalPositionIntTs < interval / 200) return;
        setMessageInterval(msg_global_position_int.MAVLINK_MSG_ID_GLOBAL_POSITION_INT, interval);
    }

    private void getHomePosition(int interval){
        if (interval > 0 && isHomePositionReceived) return;
        setMessageInterval(msg_home_position.MAVLINK_MSG_ID_HOME_POSITION, interval);
    }

    private void getSystemTime(int interval){
        if (interval > 0 && System.currentTimeMillis() - lastSystemTimeTs < interval / 200) return;
        setMessageInterval(msg_system_time.MAVLINK_MSG_ID_SYSTEM_TIME, interval);
    }

    private void getRcChannels(int interval){
        if (interval > 0 && System.currentTimeMillis() - lastRcChannelsTs < interval / 200) return;
        setMessageInterval(msg_rc_channels.MAVLINK_MSG_ID_RC_CHANNELS, interval);
    }

    private void getScaledPressure(int interval){
        if (interval > 0 && System.currentTimeMillis() - lastScaledPressureTs < interval / 200) return;
        setMessageInterval(msg_scaled_pressure.MAVLINK_MSG_ID_SCALED_PRESSURE, interval);
    }

    private void getVfrHud(int interval){
        if (interval > 0 && System.currentTimeMillis() - lastVfrHudTs < interval / 200) return;
        setMessageInterval(msg_vfr_hud.MAVLINK_MSG_ID_VFR_HUD, interval);
    }

    private void setMessageInterval(int messageId, int intervalUs){
        MAVLinkPacket packet = new msg_command_long(messageId, intervalUs, 0, 0, 0, 0, 0,
                MAV_CMD.MAV_CMD_SET_MESSAGE_INTERVAL, targetSys(), targetComponent,
                (short)0, config.getMavlinkGcsSysId(), componentId, isMavlink2).pack();
        packet.seq = getSequence();
        serial.writeDataMavlink(packet.encodePacket());
    }

    public void setRcChannelsOverride(short[] channels){
        if (channels == null || fcParams == null) return;
        int rcCount = channels.length;
        if (!rcPace.take(android.os.SystemClock.elapsedRealtime())) return; // DRAUPNE Link (F-070d): was the wall clock
        checkRcOptionsChannels(channels);
        short roll = channels[0];
        short pitch = channels[1];
        short yaw = channels[2];
        short throttle = channels[3];
        int rcMapPitch = fcParams.rcMapPitch - 1;
        int rcMapRoll = fcParams.rcMapRoll - 1;
        int rcMapThrottle = fcParams.rcMapThrottle - 1;
        int rcMapYaw = fcParams.rcMapYaw - 1;
        if (rcMapPitch < 0 || rcMapPitch >= rcCount || rcMapRoll < 0 || rcMapRoll >= rcCount
                || rcMapThrottle < 0 || rcMapThrottle >= rcCount || rcMapYaw < 0 || rcMapYaw >= rcCount) return;
        channels[rcMapRoll] = roll;
        channels[rcMapPitch] = pitch;
        channels[rcMapYaw] = yaw;
        channels[rcMapThrottle] = throttle;
        MAVLinkPacket packet = new msg_rc_channels_override(channels[0], channels[1], channels[2], channels[3],
                rcCount > 4 ? channels[4] : Utils.UINT16_MAX, rcCount > 5 ? channels[5] : Utils.UINT16_MAX,
                rcCount > 6 ? channels[6] : Utils.UINT16_MAX, rcCount > 7 ? channels[7] : Utils.UINT16_MAX,
                targetSys(), targetComponent,
                rcCount > 8 ? channels[8] : 0, rcCount > 9 ? channels[9] : 0,
                rcCount > 10 ? channels[10] : 0, rcCount > 11 ? channels[11] : 0,
                rcCount > 12 ? channels[12] : 0, rcCount > 13 ? channels[13] : 0,
                rcCount > 14 ? channels[14] : 0, rcCount > 15 ? channels[15] : 0,
                rcCount > 16 ? channels[16] : 0, rcCount > 17 ? channels[17] : 0,
                config.getMavlinkGcsSysId(), componentId, isMavlink2).pack();
        packet.seq = getSequence();
        serial.writeDataMavlink(packet.encodePacket());
    }

    // ------------------------------------------------------------------ DRAUPNE Link (fleet)

    /** Fleet AP-5: whether this app's rules are in charge now (Udp: the PC heard, or a link-loss plan running). */
    private volatile java.util.function.BooleanSupplier gcsAlive = () -> false;
    /** The commands sent for the pilot (FcCommand) whose COMMAND_ACK goes back to the PC as a record. */
    private final java.util.Set<Integer> awaitingAck = java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());
    static final int MAV_TYPE_GCS = 6, MAV_AUTOPILOT_INVALID = 8, MAV_STATE_ACTIVE = 4;
    /** MAV_MODE_FLAG_CUSTOM_MODE_ENABLED: DO_SET_MODE's first parameter for an autopilot's own mode numbers. */
    static final int CUSTOM_MODE_ENABLED = 1;
    /** ArduPilot Copter's mode numbers this class sets itself (the rest come from the PC as numbers). */
    static final int COPTER_AUTO = 3, COPTER_LOITER = 5;

    private volatile int customModeNow = -1;
    private volatile long heartbeatAtMs;
    /** The FC's own system id, from its heartbeat; -1 before the first. */
    private volatile int fcSysId = -1;

    /**
     * Fleet AP-7: the system id every message to the FC is addressed to - its own, from its heartbeat. Each drone of a
     * fleet has a SYSID_THISMAV of its own (follow, ADS-B); upstream always sent to 1, which a drone with another id
     * ignores. Before the first heartbeat: the Config's (1).
     */
    private short targetSys() {
        int id = fcSysId;
        return (short) (id > 0 ? id : config.getMavlinkTargetSysId());
    }

    /** The FC's armed flag from a heartbeat no older than 3 s (elapsedRealtime); false otherwise. */
    public boolean armedNow(long nowMs) {
        return isArmed && heartbeatAtMs != 0 && nowMs - heartbeatAtMs <= FcArmedState.MAVLINK_FRESH_MS;
    }

    /** The FC's flight mode (its heartbeat's custom mode), -1 before the first. */
    public int customModeNow() {
        return customModeNow;
    }

    /** A 3D fix or better (GPS_RAW_INT), said within 3 s: the last fix of an FC that stopped talking is none. */
    public boolean gnssOk() {
        long at = gpsRawAtMs;
        return checkGpsFix() && at != 0 && android.os.SystemClock.elapsedRealtime() - at <= FcArmedState.MAVLINK_FRESH_MS;
    }

    /** Fleet: when the FC last sent its attitude, SYS_STATUS and GPS_RAW_INT (elapsedRealtime, 0 never). */
    private volatile long attitudeAtMs, sysStatusAtMs, gpsRawAtMs;

    /** The FC's armed word is fresh: a heartbeat within 3 s. */
    boolean armedKnown(long nowMs) {
        return heartbeatAtMs != 0 && nowMs - heartbeatAtMs <= FcArmedState.MAVLINK_FRESH_MS;
    }

    /** Known DISARMED: a fresh heartbeat that says so (not known counts as armed). */
    boolean disarmedKnown(long nowMs) {
        return armedKnown(nowMs) && !isArmed;
    }

    /** How old the oldest of the attitude, SYS_STATUS and GPS_RAW_INT is, ms; Long.MAX_VALUE if one never came. */
    long fcDataAgeMs(long nowMs) {
        long a = attitudeAtMs, s = sysStatusAtMs, g = gpsRawAtMs;
        if (a == 0 || s == 0 || g == 0) return Long.MAX_VALUE;
        return Math.max(0, nowMs - Math.min(a, Math.min(s, g)));
    }

    /** An ArduPilot flight controller is on the port and known (its version read). */
    public boolean isArduPilot() {
        return isInitialized() && fcVariant == FcInfo.FC_VARIANT_ARDUPILOT;
    }

    public void setGcsAlive(java.util.function.BooleanSupplier alive) {
        gcsAlive = alive == null ? () -> false : alive;
    }

    private void sendGcsHeartbeat() {
        MAVLinkPacket packet = new msg_heartbeat(0, (short) MAV_TYPE_GCS, (short) MAV_AUTOPILOT_INVALID, (short) 0,
                (short) MAV_STATE_ACTIVE, (short) 3, config.getMavlinkGcsSysId(), componentId, isMavlink2).pack();
        packet.seq = getSequence();
        serial.writeDataMavlink(packet.encodePacket());
    }

    /** ADSB_VEHICLE's flags and kinds (MAVLink common): coords, altitude, heading, velocity, callsign valid; GNSS height; a UAV. */
    static final int ADSB_VALID_COORDS = 1, ADSB_VALID_ALTITUDE = 2, ADSB_VALID_HEADING = 4, ADSB_VALID_VELOCITY = 8,
            ADSB_VALID_CALLSIGN = 16, ADSB_ALTITUDE_GEOMETRIC = 1, ADSB_EMITTER_UAV = 14;

    /**
     * DRAUPNE Link (fleet) FD-8 / AC-11: the other DRAUPNE drones to the FC as ADS-B targets, one ADSB_VEHICLE each
     * (its address, place, height above sea level, course and speeds; callsign "DL" and the address in hex). ArduPilot
     * takes them with ADSB_TYPE set and avoids them with AVD_ENABLE; without those it ignores them. False: no ArduPilot.
     */
    public boolean adsbTraffic(de.droiddrone.common.group.Traffic t) {
        if (t == null || !isArduPilot()) return false;
        for (de.droiddrone.common.group.Traffic.Entry e : t.entries) {
            byte[] callsign = new byte[9];
            byte[] name = String.format("DL%06X", e.address & 0xffffff).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(name, 0, callsign, 0, Math.min(8, name.length));
            int flags = ADSB_VALID_COORDS | ADSB_VALID_ALTITUDE | ADSB_VALID_VELOCITY | ADSB_VALID_CALLSIGN
                    | (e.horSpeedMs >= 0.3 ? ADSB_VALID_HEADING : 0);
            MAVLinkPacket packet = new msg_adsb_vehicle(e.address, (int) Math.round(e.lat * 1e7), (int) Math.round(e.lon * 1e7),
                    (int) Math.round(e.altAmsl * 1000), (int) Math.round(e.headingDeg * 100) % 36000,
                    (int) Math.max(0, Math.min(65535, Math.round(e.horSpeedMs * 100))),
                    (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(e.verSpeedMs * 100))),
                    flags, 0, (short) ADSB_ALTITUDE_GEOMETRIC, callsign, (short) ADSB_EMITTER_UAV, (short) Math.min(255, e.ageS),
                    config.getMavlinkGcsSysId(), componentId, isMavlink2).pack();
            packet.seq = getSequence();
            serial.writeDataMavlink(packet.encodePacket());
        }
        return true;
    }

    /** Fleet AP-6: the pilot's command from the PC (FcCommand), on the receive thread. False: no MAVLink FC to give it to. */
    public boolean command(de.droiddrone.common.FcCommand c) {
        if (c == null || !isInitialized() || fcVariant != FcInfo.FC_VARIANT_ARDUPILOT) return false;
        switch (c.kind) {
            case de.droiddrone.common.FcCommand.MODE:
                setMode(c.mode);
                break;
            case de.droiddrone.common.FcCommand.MISSION_START:
                setMode(COPTER_AUTO);
                commandLong(MAV_CMD.MAV_CMD_MISSION_START, 0, 0);
                break;
            case de.droiddrone.common.FcCommand.PAUSE:
                setMode(COPTER_LOITER);
                break;
            case de.droiddrone.common.FcCommand.CONTINUE:
                setMode(COPTER_AUTO);
                break;
            case de.droiddrone.common.FcCommand.GO_LOOK: { // fleet AP-12: there at its height, the camera on the spot
                // the height it has, as a number: ArduPilot DENIES a reposition whose height is NaN (SITL 4.7.3)
                float h = de.droiddrone.common.FcCommand.goLookHeight(relAltM);
                if (Float.isNaN(h)) {
                    telemetryOutputBuffer.offer(new TelemetryData(de.droiddrone.common.FcCommand.ACK_RECORD,
                            de.droiddrone.common.FcCommand.ack(192, 4))); // MAV_RESULT_FAILED: its height not known
                    return true;
                }
                commandInt(192, -1, 1, (int) Math.round(c.lat * 1e7), (int) Math.round(c.lon * 1e7), h, (short) 6); // DO_REPOSITION, change mode
                commandInt(195, 0, 0, (int) Math.round(c.lat * 1e7), (int) Math.round(c.lon * 1e7), 0f, (short) 6); // DO_SET_ROI_LOCATION, on the ground
                break;
            }
            default:
                return false;
        }
        log("Command from the PC to the FC: " + c.kind + (c.kind == de.droiddrone.common.FcCommand.MODE ? " mode " + c.mode : ""));
        return true;
    }

    /** ArduPilot's mode by its number (MAV_CMD_DO_SET_MODE); the phone's own link-loss rules use it too. */
    public void setMode(int customMode) {
        commandLong(MAV_CMD.MAV_CMD_DO_SET_MODE, CUSTOM_MODE_ENABLED, customMode);
    }

    /** COMMAND_INT: a place in whole 1e7 degrees (DO_REPOSITION, DO_SET_ROI_LOCATION); frame 6 = heights above home. */
    private void commandInt(int command, float p1, float p2, int lat, int lon, float z, short frame) {
        awaitingAck.add(command);
        send(new com.MAVLink.common.msg_command_int(p1, p2, 0, Float.NaN, lat, lon, z, command, targetSys(), targetComponent, frame,
                (short) 0, (short) 0, config.getMavlinkGcsSysId(), componentId, isMavlink2).pack());
    }

    private void commandLong(int command, float p1, float p2) {
        awaitingAck.add(command);
        MAVLinkPacket packet = new msg_command_long(p1, p2, 0, 0, 0, 0, 0, command, targetSys(), targetComponent,
                (short) 0, config.getMavlinkGcsSysId(), componentId, isMavlink2).pack();
        packet.seq = getSequence();
        serial.writeDataMavlink(packet.encodePacket());
    }

    /** Fleet: when the PC last used the tunnel (elapsedRealtime), 0: never - the FC's answers go to it only then. */
    private volatile long tunnelUsedAtMs;
    private boolean tunnelRefusedSaid;

    /**
     * Fleet (MavTunnel): frames from the PC (the pilot's session, the buffer thread) - each one MavTunnel lists is
     * written to the FC as it came; anything else is dropped and said once.
     */
    public void tunnelFromPc(byte[] data) {
        if (!isArduPilot()) return;
        tunnelUsedAtMs = android.os.SystemClock.elapsedRealtime();
        de.droiddrone.common.MavTunnel.split(data, 0, data.length, (d, off, len) -> {
            // AP-10: logs on the ground only - known DISARMED (a stale heartbeat read as "not armed" let a log read start in the air)
            boolean disarmed = disarmedKnown(android.os.SystemClock.elapsedRealtime());
            if (de.droiddrone.common.MavTunnel.isLogRequest(de.droiddrone.common.MavTunnel.msgId(d, off, len)) && !disarmed) return;
            if (de.droiddrone.common.MavTunnel.toFc(d, off, len, disarmed)) {
                serial.writeDataMavlink(java.util.Arrays.copyOfRange(d, off, off + len));
            } else if (!tunnelRefusedSaid) {
                tunnelRefusedSaid = true;
                log("A MAVLink message from the PC not written to the FC (not one the tunnel carries): id "
                        + de.droiddrone.common.MavTunnel.msgId(d, off, len));
            }
        });
    }

    /** An FC message the PC's tunnel wants, while it is open. */
    private void tunnelToPc(MAVLinkPacket packet) {
        long used = tunnelUsedAtMs;
        if (used == 0 || android.os.SystemClock.elapsedRealtime() - used > de.droiddrone.common.MavTunnel.OPEN_MS) return;
        if (!de.droiddrone.common.MavTunnel.toPc(packet.msgid)) return;
        Udp u = udp;
        if (u != null) u.sendTunnel(packet.encodePacket());
    }

    // ---- fleet FD-1 / FL-2: what the PC's fleet link needs of the FC, and the follower's targets

    private volatile double altAmslM = Double.NaN, homeAmslM = Double.NaN, velN, velE, velU, latExact, lonExact;
    private volatile int batteryPercent = 255;
    static final int COPTER_GUIDED = 4;
    /** MAV_FRAME_GLOBAL_INT: heights above sea level, as the PC plans them. */
    static final short FRAME_GLOBAL_INT = 5;
    /** SET_POSITION_TARGET's type mask: no acceleration, no yaw rate (and no yaw: +0x400). */
    static final int MASK_POS_VEL = 0x1C0 | 0x800, MASK_NO_YAW = 0x400;

    /** The drone as its FC says, for the PC's fleet report (FleetStatus). */
    void fillStatus(de.droiddrone.common.FleetStatus s, long now) {
        s.lat = latExact;
        s.lon = lonExact;
        s.altAmsl = Double.isNaN(altAmslM) ? 0 : altAmslM;
        s.velE = velE;
        s.velN = velN;
        s.velU = velU;
        s.battery = batteryPercent;
        s.mode = customModeNow < 0 || customModeNow > 254 ? 255 : customModeNow;
        s.homeAmsl = Double.isNaN(homeAmslM) ? 0 : homeAmslM;
        if (armedNow(now)) s.flags |= de.droiddrone.common.FleetStatus.ARMED;
        if (gnssOk()) s.flags |= de.droiddrone.common.FleetStatus.GNSS;
        if (disarmedKnown(now)) s.flags |= de.droiddrone.common.FleetStatus.LANDED; // a stale heartbeat is neither
        int[] r = uploads.pcStatus(); // the PC's newest block - also while the phone's own rally upload follows it
        if (r[0] >= 0) {
            s.routeBlock = r[0];
            s.routeState = r[1];
            s.routePercent = r[2];
        }
    }

    double latNow() {
        return latExact;
    }

    double lonNow() {
        return lonExact;
    }

    double batteryNow() {
        return batteryPercent == 255 ? Double.NaN : batteryPercent;
    }

    /**
     * Fleet FL-2: a GUIDED target above sea level with a velocity fed forward (the group's own motion), the nose to yawDeg
     * (NaN: as it is). ArduPilot holds the last target; the follower sends it five times a second.
     */
    void guidedTarget(double lat, double lon, double altAmsl, double yawDeg, double velEast, double velNorth) {
        if (customModeNow != COPTER_GUIDED) setMode(COPTER_GUIDED);
        int mask = MASK_POS_VEL | (Double.isNaN(yawDeg) ? MASK_NO_YAW : 0);
        send(new com.MAVLink.common.msg_set_position_target_global_int(0, (int) Math.round(lat * 1e7), (int) Math.round(lon * 1e7),
                (float) altAmsl, (float) velNorth, (float) velEast, 0f, 0f, 0f, 0f, Double.isNaN(yawDeg) ? 0f : (float) Math.toRadians(yawDeg),
                0f, mask, targetSys(), targetComponent, FRAME_GLOBAL_INT, config.getMavlinkGcsSysId(), componentId, isMavlink2).pack());
    }

    /** RTL_ALT (cm above home) for the follower's or lead's way home (AC-41). */
    void setRtlAltM(double relM) {
        byte[] id = new byte[16];
        byte[] n = "RTL_ALT".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(n, 0, id, 0, n.length);
        send(new com.MAVLink.common.msg_param_set((float) Math.round(relM * 100), targetSys(), targetComponent, id, (short) 9,
                config.getMavlinkGcsSysId(), componentId, isMavlink2).pack());
    }

    // ---- fleet area F: what the launch reads and does

    private volatile double rollNow = Double.NaN, pitchNow = Double.NaN, yawNow = Double.NaN, relAltM = Double.NaN;
    private volatile long sensorsPresent, sensorsHealth;
    /** MAV_SYS_STATUS_SENSOR bits: gyro, accelerometer, magnetometer. */
    static final long SENSOR_GYRO = 1, SENSOR_ACCEL = 2, SENSOR_MAG = 4;
    static final int MAV_CMD_ARM_DISARM = 400, MAV_CMD_NAV_TAKEOFF = 22;
    /** COMPONENT_ARM_DISARM's param2 that disarms whatever the FC thinks (FA16: motors off at once, always). */
    static final float FORCE_DISARM = 21196f;

    double rollDeg() {
        return rollNow;
    }

    double pitchDeg() {
        return pitchNow;
    }

    double headingDeg() {
        return yawNow;
    }

    double heightAboveTakeoffM() {
        return relAltM;
    }

    double velUp() {
        return velU;
    }

    int throttlePercent() {
        return throttle;
    }

    /** The IMU (gyro and accelerometer) present and healthy (SYS_STATUS). */
    boolean imuHealthy() {
        long need = SENSOR_GYRO | SENSOR_ACCEL;
        return (sensorsPresent & need) == need && (sensorsHealth & need) == need;
    }

    /** MAV_SYS_STATUS_PREARM_CHECK: ArduPilot's own pre-arm checks. */
    static final long SENSOR_PREARM = 0x10000000L;

    /**
     * ArduPilot says its own pre-arm checks pass (SYS_STATUS' PREARM_CHECK bit healthy; a firmware that doesn't report the
     * bit counts as passing). Fleet AL-3 / AL-13: a reserve that would be refused is not armed - skipped as FC UNHEALTHY.
     */
    boolean preArmOk() {
        return (sensorsPresent & SENSOR_PREARM) == 0 || (sensorsHealth & SENSOR_PREARM) != 0;
    }

    /** A compass: present and healthy, or none at all (counted healthy: the GPS puck's is the drone's, D3). */
    boolean compassHealthy() {
        return (sensorsPresent & SENSOR_MAG) == 0 || (sensorsHealth & SENSOR_MAG) != 0;
    }

    /** Fleet AL-12: arm - only the launch sequence asks it, after its checks. */
    void arm() {
        commandLong(MAV_CMD_ARM_DISARM, 1, 0);
    }

    /** Fleet FA16: motors off at once, whatever the FC thinks. */
    void forceDisarm() {
        commandLong(MAV_CMD_ARM_DISARM, 0, FORCE_DISARM);
    }

    /** Fleet AL-8: straight up to heightM above the take-off point (GUIDED). */
    void takeoff(double heightM) {
        awaitingAck.add(MAV_CMD_NAV_TAKEOFF);
        send(new msg_command_long(0, 0, 0, 0, 0, 0, (float) heightM, MAV_CMD_NAV_TAKEOFF, targetSys(), targetComponent,
                (short) 0, config.getMavlinkGcsSysId(), componentId, isMavlink2).pack());
    }

    // ---- fleet RT-8: a route from the PC (MissionBlock), uploaded and read back over USB here

    /** One upload at a time: the PC's blocks and the phone's own rally points (MissionBlockQueue: waits, copies, replays). */
    private final de.droiddrone.common.MissionBlockQueue uploads = new de.droiddrone.common.MissionBlockQueue(this::makeUpload);
    /** The PC block whose status was said last, its state then, and when (the status goes again each second while it runs). */
    private int saidBlock = -1, saidState = -1;
    private long saidAt;

    /** A route, fence or rally block from the PC (the buffer thread): started, queued, or answered with its status. */
    public void missionBlock(de.droiddrone.common.MissionBlock b) {
        if (b == null || !isArduPilot()) return;
        if (uploads.offer(b, android.os.SystemClock.elapsedRealtime())) say(b.blockId);
        // AC-10: the PC's rally point for a drone it sends out - its RTL goes there, not home
        if (b.missionType == de.droiddrone.common.MissionUpload.TYPE_RALLY && !b.items.isEmpty()) setParam("RALLY_INCL_HOME", 0);
    }

    /**
     * Fleet AC-10: this follower's own rally point, so its RTL goes there and not to its home - uploaded only when it
     * changed, after any upload under way.
     */
    void rallyPoint(double lat, double lon, double relAltM) {
        if (!isArduPilot() || Double.isNaN(lat) || Double.isNaN(lon)) return;
        java.util.List<de.droiddrone.common.MissionItem> want = java.util.Collections.singletonList(new de.droiddrone.common.MissionItem(0,
                // NAV_RALLY_POINT in MAV_FRAME_GLOBAL_RELATIVE_ALT (3), whole metres: as ArduPilot keeps it and reads it back
                // (frame 6 came back as 3; MissionItem.sameFrame takes both, the height it rounds to metres)
                3, 5100, true, 0, 0, 0, 0, (int) Math.round(lat * 1e7), (int) Math.round(lon * 1e7), (float) Math.round(relAltM)));
        de.droiddrone.common.MissionUpload before = uploads.current();
        uploads.ownRally(want, android.os.SystemClock.elapsedRealtime());
        if (uploads.current() != before && uploads.currentBlock() == de.droiddrone.common.MissionBlockQueue.OWN) {
            setParam("RALLY_INCL_HOME", 0); // RTL goes to the rally point, not home
        }
    }

    /** A PARAM_SET of the phone's own (RALLY_INCL_HOME, FENCE_ENABLE). */
    void setParam(String name, float value) {
        byte[] id = new byte[16];
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(n, 0, id, 0, Math.min(16, n.length));
        send(new com.MAVLink.common.msg_param_set(value, targetSys(), targetComponent, id, (short) 9,
                config.getMavlinkGcsSysId(), componentId, isMavlink2).pack());
    }

    /** An upload (not started) that talks to the FC for one of its lists: every message carries the list's type. */
    private de.droiddrone.common.MissionUpload makeUpload(final int type, java.util.List<de.droiddrone.common.MissionItem> items) {
        final short mt = (short) type;
        log((type == 1 ? "Fence" : type == 2 ? "Rally points" : "Route") + ": " + items.size() + " items to the FC");
        return new de.droiddrone.common.MissionUpload(items, new de.droiddrone.common.MissionUpload.Link() {
            @Override
            public void sendCount(int count) {
                send(new com.MAVLink.common.msg_mission_count(count, targetSys(), targetComponent, mt, 0L,
                        config.getMavlinkGcsSysId(), componentId, isMavlink2).pack());
            }

            @Override
            public void sendItem(de.droiddrone.common.MissionItem m) {
                send(new com.MAVLink.common.msg_mission_item_int(m.param1, m.param2, m.param3, m.param4, m.x, m.y, m.z, m.seq, m.command,
                        targetSys(), targetComponent, (short) m.frame, (short) 0, (short) (m.autocontinue ? 1 : 0), mt,
                        config.getMavlinkGcsSysId(), componentId, isMavlink2).pack());
            }

            @Override
            public void sendRequestList() {
                send(new com.MAVLink.common.msg_mission_request_list(targetSys(), targetComponent, mt,
                        config.getMavlinkGcsSysId(), componentId, isMavlink2).pack());
            }

            @Override
            public void sendRequest(int seq) {
                send(new com.MAVLink.common.msg_mission_request_int(seq, targetSys(), targetComponent, mt,
                        config.getMavlinkGcsSysId(), componentId, isMavlink2).pack());
            }

            @Override
            public void sendAck(int ackType) {
                send(new com.MAVLink.common.msg_mission_ack(targetSys(), targetComponent, (short) ackType, mt, 0L,
                        config.getMavlinkGcsSysId(), componentId, isMavlink2).pack());
            }
        }, type);
    }

    private void send(MAVLinkPacket packet) {
        packet.seq = getSequence();
        serial.writeDataMavlink(packet.encodePacket());
    }

    /** Ten times a second (the phone's ArduPilot thread): the uploads' timeouts and their status to the PC. */
    void tick100(long now) {
        de.droiddrone.common.MissionUpload before = uploads.current();
        uploads.tick(now);
        int ended = uploads.lastEnded();
        if (ended != de.droiddrone.common.MissionBlockQueue.OWN) say(ended);
        if (uploads.current() != before && uploads.currentBlock() == de.droiddrone.common.MissionBlockQueue.OWN) {
            setParam("RALLY_INCL_HOME", 0); // the own rally points waited for the upload before them
        }
        int cur = uploads.currentBlock();
        if (cur == de.droiddrone.common.MissionBlockQueue.OWN) return; // the phone's own: its log only
        int[] s = uploads.status(cur);
        if (s != null && (cur != saidBlock || s[0] != saidState || now - saidAt >= 1000)) say(cur);
    }

    /** A PC block's status to the PC (MissionBlock.STATUS_RECORD); its end in the log. */
    private void say(int block) {
        int[] s = uploads.status(block);
        if (s == null) return;
        String reason = uploads.reason(block);
        if ((block != saidBlock || s[0] != saidState) && (s[0] == de.droiddrone.common.MissionUpload.DONE || s[0] == de.droiddrone.common.MissionUpload.FAILED)) {
            log("Upload " + block + ": " + (s[0] == de.droiddrone.common.MissionUpload.DONE ? "done, read back" : "FAILED - " + reason));
        }
        saidBlock = block;
        saidState = s[0];
        saidAt = android.os.SystemClock.elapsedRealtime();
        telemetryOutputBuffer.offer(new TelemetryData(de.droiddrone.common.MissionBlock.STATUS_RECORD,
                de.droiddrone.common.MissionBlock.status(block, s[0], s[1], s[0] == de.droiddrone.common.MissionUpload.FAILED ? reason : null)));
    }
    /** The FC's mission talk, to the upload under way. */
    private void missionFromFc(MAVLinkPacket packet) {
        de.droiddrone.common.MissionUpload u = uploads.current();
        if (u == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        int t = u.missionType(); // fleet: only the answers of the list being uploaded
        switch (packet.msgid) {
            case com.MAVLink.common.msg_mission_request_int.MAVLINK_MSG_ID_MISSION_REQUEST_INT: {
                com.MAVLink.common.msg_mission_request_int q = new com.MAVLink.common.msg_mission_request_int(packet);
                if (q.mission_type == t) u.onRequest(q.seq, now);
                break;
            }
            case com.MAVLink.common.msg_mission_request.MAVLINK_MSG_ID_MISSION_REQUEST: {
                com.MAVLink.common.msg_mission_request q = new com.MAVLink.common.msg_mission_request(packet);
                if (q.mission_type == t) u.onRequest(q.seq, now);
                break;
            }
            case com.MAVLink.common.msg_mission_ack.MAVLINK_MSG_ID_MISSION_ACK: {
                com.MAVLink.common.msg_mission_ack a = new com.MAVLink.common.msg_mission_ack(packet);
                if (a.mission_type == t) u.onAck(a.type, now);
                break;
            }
            case com.MAVLink.common.msg_mission_count.MAVLINK_MSG_ID_MISSION_COUNT: {
                com.MAVLink.common.msg_mission_count c = new com.MAVLink.common.msg_mission_count(packet);
                if (c.mission_type == t) u.onCount(c.count, now);
                break;
            }
            case com.MAVLink.common.msg_mission_item_int.MAVLINK_MSG_ID_MISSION_ITEM_INT: {
                com.MAVLink.common.msg_mission_item_int m = new com.MAVLink.common.msg_mission_item_int(packet);
                if (m.mission_type == t) u.onItem(new de.droiddrone.common.MissionItem(m.seq, m.frame, m.command, m.autocontinue != 0,
                        m.param1, m.param2, m.param3, m.param4, m.x, m.y, m.z), now);
                break;
            }
            default:
                break;
        }
    }

    /** The FC's answer to a command of the pilot's: to the PC as FcCommand.ACK_RECORD. */
    private void commandAck(int command, int result) {
        if (!awaitingAck.remove(command)) return; // the telemetry's own SET_MESSAGE_INTERVAL and the like
        telemetryOutputBuffer.offer(new TelemetryData(de.droiddrone.common.FcCommand.ACK_RECORD, de.droiddrone.common.FcCommand.ack(command, result)));
    }

    private void checkRcOptionsChannels(short[] channels){
        final short optionActivePwm = 1600;
        if (cameraRecordVideoChannel != -1 && cameraRecordVideoChannel < channels.length){
            boolean camRecordActive = channels[cameraRecordVideoChannel] >= optionActivePwm;
            if (camRecordActive && !cameraRecordLastState){
                telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_VIDEO_RECORDER_START_STOP, null));
            }
            cameraRecordLastState = camRecordActive;
        }
    }

    public void addData(byte[] buf, int dataLength){
        if (dataLength <= 0) return; // DRAUPNE Link (fleet): a short read is a frame's tail or head (MavFrameStream), never dropped
        byte[] data = new byte[dataLength];
        System.arraycopy(buf, 0, data, 0, dataLength);
        serialRawData.offer(data);
    }

    private final Runnable serialDataRun = new Runnable() {
        public void run() {
            final int id = threadsId;
            byte[] data;
            while (id == threadsId) {
                try {
                    data = serialRawData.take();
                    processData(data);
                } catch (Exception e) {
                    log("Serial data thread error: " + e);
                }
            }
        }
    };

    public void processReceivedUdpData(byte[] data){
        List<MAVLinkPacket> packets = null;
        try {
            packets = parsePackets(data);
        }catch (Exception e){
            log("Mavlink processUdpData - parsePackets error: " + e);
        }
        if (packets == null || packets.isEmpty()) return;
        for (MAVLinkPacket packet : packets) {
            serial.writeDataMavlink(packet.encodePacket());
        }
    }

    private void sendUdpPackets(List<MAVLinkPacket> packets) {
        try {
            if (config.getMavlinkUdpBridge() == SettingsCommon.MavlinkUdpBridge.redirectFromControlDevice) {
                if (udp != null) {
                    for (MAVLinkPacket packet : packets) {
                        udp.sendMavlinkPacket(packet.encodePacket());
                    }
                }
                return;
            }
            if (mavlinkUdpBridge == null || !mavlinkUdpBridge.isInitialized()) return;
            for (MAVLinkPacket packet : packets) {
                mavlinkUdpBridge.sendPacket(packet.encodePacket());
            }
        } catch (Exception e) {
            log("Mavlink - sendUdpPackets error: " + e);
        }
    }

    /**
     * DRAUPNE Link (fleet): the FC's bytes as a stream (MavFrameStream) - a frame split across two USB reads is put
     * together, a read that begins inside a frame resyncs on the next start byte; upstream parsed each read alone and
     * lost both (and every frame after a bad first byte). Each candidate is checked by parsePackets (its CRC).
     */
    private final de.droiddrone.common.MavFrameStream frames = new de.droiddrone.common.MavFrameStream();

    private void processData(byte[] data){
        final List<MAVLinkPacket> packets = new ArrayList<>();
        try {
            frames.feed(data, data.length, (d, off, len) -> {
                List<MAVLinkPacket> one = parsePackets(java.util.Arrays.copyOfRange(d, off, off + len));
                if (one.size() != 1) return false;
                packets.add(one.get(0));
                return true;
            });
        }catch (Exception e){
            frames.reset();
            log("Mavlink - parsePackets error: " + e);
        }
        if (packets.isEmpty()) return;
        sendUdpPackets(packets);
        for (MAVLinkPacket packet : packets) {
            tunnelToPc(packet); // DRAUPNE Link (fleet): the PC's tunnel, while open
            try {
                missionFromFc(packet); // DRAUPNE Link (fleet RT-8): a route's upload under way
            } catch (Exception e) {
                log("Route upload: an FC message not read: " + e);
            }
            try {
                switch (packet.msgid) {
                    case msg_heartbeat.MAVLINK_MSG_ID_HEARTBEAT: {
                        msg_heartbeat message = new msg_heartbeat(packet);
                        // DRAUPNE Link (fleet): another ground station's, or a gimbal's, an ADS-B receiver's ... (MAV_AUTOPILOT_INVALID) that
                        // ArduPilot routes to USB - not the FC's: its base mode 0 read as DISARMED, its sysid became the target
                        if (!de.droiddrone.common.MavlinkSniff.isFlightController(message.type, message.autopilot)) break;
                        isArmed = (message.base_mode & MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED) != 0;
                        // DRAUPNE Link (fleet): every heartbeat is the FC's word on armed - before, only the mode record
                        // (sent once the FC's parameters were read) told the guards, so a new board was UNKNOWN for longer
                        FcArmedState.mavlinkHeartbeat(isArmed);
                        customModeNow = (int) message.custom_mode;
                        fcSysId = packet.sysid;
                        heartbeatAtMs = android.os.SystemClock.elapsedRealtime();
                        if (isArmed) {
                            if (armedTs == 0) armedTs = System.currentTimeMillis();
                        } else {
                            armedTs = 0;
                            flightTs = 0;
                        }
                        if (fcParams != null && fcParams.isFcConfigInitialized()) {
                            DataWriter buffer = new DataWriter(true);
                            buffer.writeInt((int) message.custom_mode);
                            buffer.writeBoolean(isArmed);
                            telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_MAVLINK_MODE, buffer.getData()));
                        }
                        if (isHeartBeatReceived) break;
                        apiVersionMinor = message.mavlink_version;
                        platformType = message.type;
                        switch (message.autopilot) {
                            case MAV_AUTOPILOT.MAV_AUTOPILOT_PX4:
                                fcVariant = FcInfo.FC_VARIANT_PX4;
                                break;
                            case MAV_AUTOPILOT.MAV_AUTOPILOT_ARDUPILOTMEGA:
                                fcVariant = FcInfo.FC_VARIANT_ARDUPILOT;
                                break;
                        }
                        isHeartBeatReceived = true;
                        break;
                    }
                    case com.MAVLink.common.msg_command_ack.MAVLINK_MSG_ID_COMMAND_ACK: { // DRAUPNE Link (fleet)
                        com.MAVLink.common.msg_command_ack message = new com.MAVLink.common.msg_command_ack(packet);
                        commandAck(message.command, message.result);
                        break;
                    }
                    case msg_autopilot_version.MAVLINK_MSG_ID_AUTOPILOT_VERSION: {
                        msg_autopilot_version message = new msg_autopilot_version(packet);
                        fcVersionMajor = (int) (message.flight_sw_version >> 24 & 0xFF);
                        fcVersionMinor = (int) (message.flight_sw_version >> 16 & 0xFF);
                        fcVersionPatchLevel = (int) (message.flight_sw_version >> 8 & 0xFF);
                        byte fw_type = (byte) (message.flight_sw_version & 0xFF);
                        break;
                    }
                    case msg_param_value.MAVLINK_MSG_ID_PARAM_VALUE: {
                        msg_param_value message = new msg_param_value(packet);
                        if (fcParams == null) break;
                        fcParams.setParam(message.getParam_Id(), message.param_value);
                        break;
                    }
                    case msg_attitude.MAVLINK_MSG_ID_ATTITUDE: {
                        msg_attitude message = new msg_attitude(packet);
                        lastAttitudeTs = System.currentTimeMillis();
                        attitudeAtMs = android.os.SystemClock.elapsedRealtime(); // DRAUPNE Link (fleet): the launch's check reads its age
                        rollNow = Math.toDegrees(message.roll); // DRAUPNE Link (fleet): the launch's checks (AL-13, AL-14)
                        pitchNow = Math.toDegrees(message.pitch);
                        yawNow = (Math.toDegrees(message.yaw) + 360) % 360;
                        DataWriter buffer = new DataWriter(true);
                        buffer.writeShort((short) (Math.toDegrees(message.roll) * 10));
                        buffer.writeShort((short) (Math.toDegrees(message.pitch) * -10));
                        buffer.writeShort((short) Math.toDegrees(message.yaw));
                        telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_MAVLINK_ATTITUDE, buffer.getData()));
                        break;
                    }
                    case msg_battery_status.MAVLINK_MSG_ID_BATTERY_STATUS: {
                        msg_battery_status message = new msg_battery_status(packet);
                        lastBatteryStatusTs = System.currentTimeMillis();
                        DataWriter buffer = new DataWriter(true);
                        buffer.writeShort(message.current_battery);
                        buffer.writeInt(message.current_consumed);
                        buffer.writeByte(message.battery_remaining);
                        buffer.writeInt((int) message.fault_bitmask);
                        telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_MAVLINK_BATTERY_STATUS, buffer.getData()));
                        break;
                    }
                    case msg_sys_status.MAVLINK_MSG_ID_SYS_STATUS: {
                        msg_sys_status message = new msg_sys_status(packet);
                        lastSysStatusTs = System.currentTimeMillis();
                        sysStatusAtMs = android.os.SystemClock.elapsedRealtime(); // DRAUPNE Link (fleet)
                        batteryPercent = message.battery_remaining < 0 ? 255 : message.battery_remaining; // DRAUPNE Link (fleet)
                        sensorsPresent = message.onboard_control_sensors_present;
                        sensorsHealth = message.onboard_control_sensors_health;
                        DataWriter buffer = new DataWriter(true);
                        if (message.voltage_battery != Utils.UINT16_MAX) {
                            int cellCountDetected = Math.round(message.voltage_battery / 4100f);
                            if (batteryCellCountDetected < cellCountDetected) {
                                batteryCellCountDetected = cellCountDetected;
                            }
                        }
                        buffer.writeByte((byte) batteryCellCountDetected);
                        buffer.writeShort((short) message.voltage_battery);
                        buffer.writeByte(message.battery_remaining);
                        telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_MAVLINK_SYS_STATUS, buffer.getData()));
                        break;
                    }
                    case msg_statustext.MAVLINK_MSG_ID_STATUSTEXT: {
                        msg_statustext message = new msg_statustext(packet);
                        isStatusTextReceived = true;
                        String msg = message.getText();
                        if (msg == null || msg.contains("No ap_message for mavlink"))
                            break;
                        DataWriter buffer = new DataWriter(true);
                        buffer.writeByte((byte) message.severity);
                        buffer.writeUTF(msg.trim());
                        telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_MAVLINK_STATUS_TEXT, buffer.getData()));
                        break;
                    }
                    case msg_gps_raw_int.MAVLINK_MSG_ID_GPS_RAW_INT: {
                        msg_gps_raw_int message = new msg_gps_raw_int(packet);
                        lastGpsRawIntTs = System.currentTimeMillis();
                        gpsRawAtMs = android.os.SystemClock.elapsedRealtime(); // DRAUPNE Link (fleet): a fix this old is none
                        if (message.fix_type >= 0 && message.fix_type < FcCommon.GpsFixTypesMavlink.values().length) {
                            this.fixTypeArduPilot = FcCommon.GpsFixTypesMavlink.values()[message.fix_type];
                        }
                        if (message.vel != Utils.UINT16_MAX) groundSpeed = message.vel * 0.036f;
                        DataWriter buffer = new DataWriter(true);
                        buffer.writeByte((byte) message.fix_type);
                        buffer.writeShort((short) message.vel);
                        buffer.writeByte((byte) message.satellites_visible);
                        telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_MAVLINK_GPS_RAW_INT, buffer.getData()));
                        break;
                    }
                    case msg_global_position_int.MAVLINK_MSG_ID_GLOBAL_POSITION_INT: {
                        msg_global_position_int message = new msg_global_position_int(packet);
                        lastGlobalPositionIntTs = System.currentTimeMillis();
                        latExact = message.lat / 1e7; // DRAUPNE Link (fleet): doubles - a float is half a metre off here
                        lonExact = message.lon / 1e7;
                        relAltM = message.relative_alt / 1000.0;
                        altAmslM = message.alt / 1000.0; // DRAUPNE Link (fleet): for the PC's fleet report
                        velN = message.vx / 100.0;
                        velE = message.vy / 100.0;
                        velU = -message.vz / 100.0;
                        float latDeg = message.lat / 10000000f;
                        float lonDeg = message.lon / 10000000f;
                        calculateTraveledDist(latDeg, lonDeg);
                        calculateHomeDistDir();
                        DataWriter buffer = new DataWriter(true);
                        buffer.writeInt(message.lat);
                        buffer.writeInt(message.lon);
                        buffer.writeInt(message.relative_alt);
                        buffer.writeShort(message.vz);
                        buffer.writeInt(Math.round(traveledDistance));
                        buffer.writeInt(distanceToHome);
                        buffer.writeShort((short)Math.round(directionToHome));
                        telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_MAVLINK_GLOBAL_POSITION_INT, buffer.getData()));
                        break;
                    }
                    case msg_home_position.MAVLINK_MSG_ID_HOME_POSITION: {
                        msg_home_position message = new msg_home_position(packet);
                        isHomePositionReceived = true;
                        homeAmslM = message.altitude / 1000.0; // DRAUPNE Link (fleet)
                        homeLatDeg = message.latitude / 10000000f;
                        homeLonDeg = message.longitude / 10000000f;
                        calculateHomeDistDir();
                        DataWriter buffer = new DataWriter(true);
                        buffer.writeInt(message.latitude);
                        buffer.writeInt(message.longitude);
                        telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_MAVLINK_HOME_POSITION, buffer.getData()));
                        break;
                    }
                    case msg_system_time.MAVLINK_MSG_ID_SYSTEM_TIME: {
                        msg_system_time message = new msg_system_time(packet);
                        lastSystemTimeTs = System.currentTimeMillis();
                        long armingTime = 0;
                        if (armedTs != 0) armingTime = lastSystemTimeTs - armedTs;
                        if (isArmed) {
                            if (flightTs == 0) {
                                if (throttle >= 2) flightTs = lastSystemTimeTs;
                            } else {
                                flightTime += (lastSystemTimeTs - flightTs);
                                flightTs = lastSystemTimeTs;
                            }
                        }
                        DataWriter buffer = new DataWriter(true);
                        buffer.writeInt((int) message.time_boot_ms);
                        buffer.writeInt((int) flightTime);
                        buffer.writeInt((int) armingTime);
                        telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_MAVLINK_SYSTEM_TIME, buffer.getData()));
                        break;
                    }
                    case msg_rc_channels.MAVLINK_MSG_ID_RC_CHANNELS: {
                        msg_rc_channels message = new msg_rc_channels(packet);
                        lastRcChannelsTs = System.currentTimeMillis();
                        DataWriter buffer = new DataWriter(true);
                        buffer.writeByte((byte) message.rssi);
                        telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_MAVLINK_RC_CHANNELS, buffer.getData()));
                        break;
                    }
                    case msg_scaled_pressure.MAVLINK_MSG_ID_SCALED_PRESSURE: {
                        msg_scaled_pressure message = new msg_scaled_pressure(packet);
                        lastScaledPressureTs = System.currentTimeMillis();
                        DataWriter buffer = new DataWriter(true);
                        buffer.writeShort(message.temperature);
                        telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_MAVLINK_SCALED_PRESSURE, buffer.getData()));
                        break;
                    }
                    case msg_vfr_hud.MAVLINK_MSG_ID_VFR_HUD: {
                        msg_vfr_hud message = new msg_vfr_hud(packet);
                        lastVfrHudTs = System.currentTimeMillis();
                        throttle = message.throttle;
                        DataWriter buffer = new DataWriter(true);
                        buffer.writeByte((byte) message.throttle);
                        telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_MAVLINK_VFR_HUD, buffer.getData()));
                        break;
                    }
                }
            }catch (Exception e){
                log("Mavlink process packet data error: " + e + ", msgId: " + packet.msgid);
            }
        }
    }

    private void calculateTraveledDist(float newLatDeg, float newLonDeg){
        if (!checkGpsFix()) return;
        if (groundSpeed > 0.2f && this.latDeg != 0 && isArmed) {
            Location oldLocation = new Location(LocationManager.GPS_PROVIDER);
            Location newLocation = new Location(LocationManager.GPS_PROVIDER);
            oldLocation.setLatitude(this.latDeg);
            oldLocation.setLongitude(this.lonDeg);
            newLocation.setLatitude(newLatDeg);
            newLocation.setLongitude(newLonDeg);
            traveledDistance += oldLocation.distanceTo(newLocation);
        }
        this.latDeg = newLatDeg;
        this.lonDeg = newLonDeg;
    }

    private void calculateHomeDistDir(){
        if (checkGpsFix() && latDeg != 0 && homeLatDeg != 0 && isArmed) {
            Location droneLocation = new Location(LocationManager.GPS_PROVIDER);
            Location homeLocation = new Location(LocationManager.GPS_PROVIDER);
            droneLocation.setLatitude(latDeg);
            droneLocation.setLongitude(lonDeg);
            homeLocation.setLatitude(homeLatDeg);
            homeLocation.setLongitude(homeLonDeg);
            distanceToHome = Math.round(droneLocation.distanceTo(homeLocation));
            directionToHome = droneLocation.bearingTo(homeLocation);
        }
    }

    private boolean checkGpsFix() {
        return fixTypeArduPilot != FcCommon.GpsFixTypesMavlink.GPS_FIX_TYPE_NO_GPS
                && fixTypeArduPilot != FcCommon.GpsFixTypesMavlink.GPS_FIX_TYPE_NO_FIX
                && fixTypeArduPilot != FcCommon.GpsFixTypesMavlink.GPS_FIX_TYPE_2D_FIX;
    }

    private static class OsdItemParam{
        private final String osdItemName;
        private boolean isEnabled;
        private boolean isEnabledReceived;
        private byte x;
        private boolean isXReceived;
        private byte y;
        private boolean isYReceived;

        public OsdItemParam(String osdItemName){
            this.osdItemName = osdItemName;
            isEnabled = false;
            isEnabledReceived = false;
            x = 0;
            isXReceived = false;
            y = 0;
            isYReceived = false;
        }

        public String getOsdItemName(){
            return osdItemName;
        }

        public void setEnabled(boolean isEnabled){
            this.isEnabled = isEnabled;
            isEnabledReceived = true;
        }

        public boolean isEnabled() {
            return isEnabled;
        }

        public void setX(byte x) {
            this.x = x;
            isXReceived = true;
        }

        public byte getX() {
            return x;
        }

        public void setY(byte y) {
            this.y = y;
            isYReceived = true;
        }

        public byte getY() {
            return y;
        }

        public boolean isInitialized(){
            return isEnabledReceived && isXReceived && isYReceived;
        }
    }

    private static class RcOptionParam{
        private final String rcOptionName;
        private byte channel;
        private short value;
        private boolean isValueReceived;

        public RcOptionParam(String rcOptionName){
            this.rcOptionName = rcOptionName;
            String num = rcOptionName.replace("RC", "").replace("_OPTION", "");
            try {
                channel = (byte)(Byte.parseByte(num) - 1);
            } catch (NumberFormatException ignored) {
                channel = -1;
            }
            if (channel < 0 || channel >= FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT) channel = -1;
            value = 0;
            isValueReceived = false;
        }

        public boolean isInitialized(){
            return isValueReceived;
        }

        public String getRcOptionName(){
            return rcOptionName;
        }

        public byte getChannel() {
            return channel;
        }

        public short getValue() {
            return value;
        }

        public void setValue(short value) {
            this.value = value;
            isValueReceived = true;
        }
    }

    private static class FcParams{
        private final Mavlink mavlink;
        private final OsdItemParam[] osdItems = new OsdItemParam[OsdCommon.AP_OSD_ITEMS.length];
        private final RcOptionParam[] rcOptionParams = new RcOptionParam[FcCommon.AP_PARAM_RC_OPTIONS.length];
        boolean osd1Enabled;
        boolean osd1EnabledReceived;
        byte osd1TxtRes;
        boolean osd1TxtResReceived;
        byte osdUnits;
        boolean osdUnitsReceived;
        byte osdMsgTime;
        boolean osdMsgTimeReceived;
        byte osdWarnRssi;
        boolean osdWarnRssiReceived;
        byte osdWarnNumSat;
        boolean osdWarnNumSatReceived;
        float osdWarnBatVolt;
        boolean osdWarnBatVoltReceived;
        float osdWarnAvgCellVolt;
        boolean osdWarnAvgCellVoltReceived;
        byte osdCellCount;
        boolean osdCellCountReceived;
        byte rcMapPitch;
        boolean rcMapPitchReceived;
        byte rcMapRoll;
        boolean rcMapRollReceived;
        byte rcMapThrottle;
        boolean rcMapThrottleReceived;
        byte rcMapYaw;
        boolean rcMapYawReceived;

        private FcParams(Mavlink mavlink) {
            this.mavlink = mavlink;
            int count = OsdCommon.AP_OSD_ITEMS.length;
            for (int i = 0; i < count; i++) {
                osdItems[i] = new OsdItemParam(OsdCommon.AP_OSD_ITEMS[i]);
            }
            count = FcCommon.AP_PARAM_RC_OPTIONS.length;
            for (int i = 0; i < count; i++) {
                rcOptionParams[i] = new RcOptionParam(FcCommon.AP_PARAM_RC_OPTIONS[i]);
            }
        }

        private String getOsdItemNameFromEn(String osdEnParam){
            return osdEnParam.substring(5, osdEnParam.length() - 3);
        }

        private String getOsdItemNameFromXY(String osdXYParam){
            return osdXYParam.substring(5, osdXYParam.length() - 2);
        }

        private void setOsdItemParam(String paramId, float paramValue){
            if (paramId == null) return;
            int l = paramId.length();
            if (l < 8) return;
            String osdItemName;
            OsdItemParam osdItemParam;
            if (paramId.substring(l - 3).equals("_EN")){
                osdItemName = getOsdItemNameFromEn(paramId);
                osdItemParam = getOsdItemParamFromName(osdItemName);
                if (osdItemParam == null) return;
                osdItemParam.setEnabled((int)paramValue != 0);
            } else if (paramId.substring(l - 2).equals("_X")){
                osdItemName = getOsdItemNameFromXY(paramId);
                osdItemParam = getOsdItemParamFromName(osdItemName);
                if (osdItemParam == null) return;
                osdItemParam.setX((byte)paramValue);
            } else if (paramId.substring(l - 2).equals("_Y")){
                osdItemName = getOsdItemNameFromXY(paramId);
                osdItemParam = getOsdItemParamFromName(osdItemName);
                if (osdItemParam == null) return;
                osdItemParam.setY((byte)paramValue);
            }
        }

        private void setRcOptionParam(String paramId, float paramValue) {
            if (paramId == null) return;
            for (RcOptionParam item : rcOptionParams){
                if (item.getRcOptionName().equals(paramId)){
                    item.setValue((short)paramValue);
                    break;
                }
            }
        }

        private OsdItemParam getOsdItemParamFromName(String osdItemName){
            for (OsdItemParam item : osdItems){
                if (item.getOsdItemName().equals(osdItemName)) return item;
            }
            return null;
        }

        public byte getOptionChannelForValue(short rcOptionValue){
            byte channel = -1;
            for (RcOptionParam item : rcOptionParams){
                if (item.getValue() == rcOptionValue) return item.getChannel();
            }
            return channel;
        }

        public void initializeFcConfig(){
            if (mavlink.fcVariant == FcInfo.FC_VARIANT_ARDUPILOT) initializeFcConfigAp();
            if (mavlink.fcVariant == FcInfo.FC_VARIANT_PX4) initializeFcConfigPx4();
        }

        private void initializeFcConfigAp(){
            new Thread(() -> {
                if (!osd1EnabledReceived) mavlink.requestFcParameter(FcCommon.AP_PARAM_OSD1_ENABLE);
                if (!osd1TxtResReceived) mavlink.requestFcParameter(FcCommon.AP_PARAM_OSD1_TXT_RES);
                if (!osdUnitsReceived) mavlink.requestFcParameter(FcCommon.AP_PARAM_OSD_UNITS);
                if (!osdMsgTimeReceived) mavlink.requestFcParameter(FcCommon.AP_PARAM_OSD_MSG_TIME);
                if (!osdWarnRssiReceived) mavlink.requestFcParameter(FcCommon.AP_PARAM_OSD_W_RSSI);
                if (!osdWarnNumSatReceived) mavlink.requestFcParameter(FcCommon.AP_PARAM_OSD_W_NSAT);
                if (!osdWarnBatVoltReceived) mavlink.requestFcParameter(FcCommon.AP_PARAM_OSD_W_BATVOLT);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    //
                }
                if (!osdWarnAvgCellVoltReceived) mavlink.requestFcParameter(FcCommon.AP_PARAM_OSD_W_AVGCELLV);
                if (!osdCellCountReceived) mavlink.requestFcParameter(FcCommon.AP_PARAM_OSD_CELL_COUNT);
                if (!rcMapPitchReceived) mavlink.requestFcParameter(FcCommon.AP_PARAM_RCMAP_PITCH);
                if (!rcMapRollReceived) mavlink.requestFcParameter(FcCommon.AP_PARAM_RCMAP_ROLL);
                if (!rcMapThrottleReceived) mavlink.requestFcParameter(FcCommon.AP_PARAM_RCMAP_THROTTLE);
                if (!rcMapYawReceived) mavlink.requestFcParameter(FcCommon.AP_PARAM_RCMAP_YAW);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    //
                }
                for (RcOptionParam item : rcOptionParams){
                    if (item.isInitialized()) continue;
                    mavlink.requestFcParameter(item.rcOptionName);
                }
                for (OsdItemParam item : osdItems){
                    if (item.isInitialized()) continue;
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        //
                    }
                    String osdItemName = item.getOsdItemName();
                    if (!item.isEnabledReceived) mavlink.requestFcParameter("OSD1_" + osdItemName + "_EN");
                    if (!item.isXReceived) mavlink.requestFcParameter("OSD1_" + osdItemName + "_X");
                    if (!item.isYReceived) mavlink.requestFcParameter("OSD1_" + osdItemName + "_Y");
                }
            }).start();
        }

        private void initializeFcConfigPx4(){
            new Thread(() -> {
                if (!rcMapPitchReceived) mavlink.requestFcParameter(FcCommon.PX4_PARAM_RC_MAP_PITCH);
                if (!rcMapRollReceived) mavlink.requestFcParameter(FcCommon.PX4_PARAM_RC_MAP_ROLL);
                if (!rcMapThrottleReceived) mavlink.requestFcParameter(FcCommon.PX4_PARAM_RC_MAP_THROTTLE);
                if (!rcMapYawReceived) mavlink.requestFcParameter(FcCommon.PX4_PARAM_RC_MAP_YAW);
            }).start();
        }

        private boolean isFcConfigInitializedAp(){
            if (!osd1EnabledReceived || !osd1TxtResReceived || !osdUnitsReceived
                    || !osdMsgTimeReceived || !osdWarnRssiReceived || !osdWarnNumSatReceived
                    || !osdWarnBatVoltReceived || !osdWarnAvgCellVoltReceived || !osdCellCountReceived
                    || !rcMapPitchReceived || !rcMapRollReceived
                    || !rcMapThrottleReceived || !rcMapYawReceived) return false;
            for (RcOptionParam item : rcOptionParams){
                if (!item.isInitialized()) return false;
            }
            for (OsdItemParam item : osdItems){
                if (!item.isInitialized()) {
                    return false;
                }
            }
            return true;
        }

        private boolean isFcConfigInitializedPx4(){
            if (!rcMapPitchReceived || !rcMapRollReceived
                    || !rcMapThrottleReceived || !rcMapYawReceived) return false;
            return true;
        }

        public boolean isFcConfigInitialized(){
            if (mavlink.fcVariant == FcInfo.FC_VARIANT_ARDUPILOT) return isFcConfigInitializedAp();
            if (mavlink.fcVariant == FcInfo.FC_VARIANT_PX4) return isFcConfigInitializedPx4();
            return false;
        }

        public void sendOsdConfig(){
            DataWriter buffer = new DataWriter(true);
            buffer.writeBoolean(osd1Enabled);
            buffer.writeByte(osd1TxtRes);
            buffer.writeByte(osdUnits);
            buffer.writeByte(osdMsgTime);
            buffer.writeByte(osdWarnRssi);
            buffer.writeByte(osdWarnNumSat);
            buffer.writeFloat(osdWarnBatVolt);
            buffer.writeFloat(osdWarnAvgCellVolt);
            buffer.writeByte(osdCellCount);
            int osdItemsCount = osdItems.length;
            buffer.writeByte((byte) osdItemsCount);
            for (OsdItemParam osdItem : osdItems) {
                buffer.writeBoolean(osdItem.isEnabled());
                buffer.writeByte(osdItem.getX());
                buffer.writeByte(osdItem.getY());
            }
            mavlink.telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_AP_OSD_CONFIG, buffer.getData()));
        }

        public void setParam(String paramId, float paramValue){
            switch (paramId){
                case FcCommon.AP_PARAM_OSD1_ENABLE:
                    osd1Enabled = ((int)paramValue != 0);
                    osd1EnabledReceived = true;
                    break;
                case FcCommon.AP_PARAM_OSD1_TXT_RES:
                    osd1TxtRes = (byte)paramValue;
                    osd1TxtResReceived = true;
                    break;
                case FcCommon.AP_PARAM_OSD_UNITS:
                    osdUnits = (byte)paramValue;
                    osdUnitsReceived = true;
                    break;
                case FcCommon.AP_PARAM_OSD_MSG_TIME:
                    osdMsgTime = (byte)paramValue;
                    osdMsgTimeReceived = true;
                    break;
                case FcCommon.AP_PARAM_OSD_W_RSSI:
                    osdWarnRssi = (byte)paramValue;
                    osdWarnRssiReceived = true;
                    break;
                case FcCommon.AP_PARAM_OSD_W_NSAT:
                    osdWarnNumSat = (byte)paramValue;
                    osdWarnNumSatReceived = true;
                    break;
                case FcCommon.AP_PARAM_OSD_W_BATVOLT:
                    osdWarnBatVolt = paramValue;
                    osdWarnBatVoltReceived = true;
                    break;
                case FcCommon.AP_PARAM_OSD_W_AVGCELLV:
                    osdWarnAvgCellVolt = paramValue;
                    osdWarnAvgCellVoltReceived = true;
                    break;
                case FcCommon.AP_PARAM_OSD_CELL_COUNT:
                    osdCellCount = (byte)paramValue;
                    osdCellCountReceived = true;
                    break;
                case FcCommon.AP_PARAM_VTX_POWER:
                    DataWriter buffer = new DataWriter(true);
                    buffer.writeShort((short) Math.round(paramValue));
                    mavlink.telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_AP_VTX_POWER, buffer.getData()));
                    break;
                case FcCommon.AP_PARAM_RCMAP_PITCH:
                    rcMapPitch = (byte)paramValue;
                    rcMapPitchReceived = true;
                    break;
                case FcCommon.AP_PARAM_RCMAP_ROLL:
                    rcMapRoll = (byte)paramValue;
                    rcMapRollReceived = true;
                    break;
                case FcCommon.AP_PARAM_RCMAP_THROTTLE:
                    rcMapThrottle = (byte)paramValue;
                    rcMapThrottleReceived = true;
                    break;
                case FcCommon.AP_PARAM_RCMAP_YAW:
                    rcMapYaw = (byte)paramValue;
                    rcMapYawReceived = true;
                    break;
                case FcCommon.PX4_PARAM_RC_MAP_PITCH:
                    rcMapPitch = (byte)convertParamValueToInt(paramValue);
                    rcMapPitchReceived = true;
                    break;
                case FcCommon.PX4_PARAM_RC_MAP_ROLL:
                    rcMapRoll = (byte)convertParamValueToInt(paramValue);
                    rcMapRollReceived = true;
                    break;
                case FcCommon.PX4_PARAM_RC_MAP_THROTTLE:
                    rcMapThrottle = (byte)convertParamValueToInt(paramValue);
                    rcMapThrottleReceived = true;
                    break;
                case FcCommon.PX4_PARAM_RC_MAP_YAW:
                    rcMapYaw = (byte)convertParamValueToInt(paramValue);
                    rcMapYawReceived = true;
                    break;
                default:
                    if (mavlink.fcVariant == FcInfo.FC_VARIANT_ARDUPILOT) {
                        if (paramId.contains("OSD1_") &&
                                (paramId.contains("_EN") || paramId.contains("_X") || paramId.contains("_Y"))) {
                            setOsdItemParam(paramId, paramValue);
                        } else if (Arrays.asList(FcCommon.AP_PARAM_RC_OPTIONS).contains(paramId)) {
                            setRcOptionParam(paramId, paramValue);
                        }
                    }
                    break;
            }
        }

        private int convertParamValueToInt(float paramValue){
            DataWriter dw = new DataWriter(true);
            dw.writeFloat(paramValue);
            DataReader dr = new DataReader(dw.getData(), true);
            return dr.readInt();
        }
    }

    private List<MAVLinkPacket> parsePackets(byte[] data){
        DataReader reader = new DataReader(data, false);
        List<MAVLinkPacket> packets = new ArrayList<>();
        while (reader.getRemaining() > 0) {
            int magic = reader.readUnsignedByteAsInt();
            int payloadLength = reader.readUnsignedByteAsInt();
            boolean isMavlink2 = false;
            if (magic == MAVLinkPacket.MAVLINK_STX_MAVLINK2) {
                isMavlink2 = true;
            } else if (magic != MAVLinkPacket.MAVLINK_STX_MAVLINK1) {
                return packets;
            }
            MAVLinkPacket packet = new MAVLinkPacket(payloadLength, isMavlink2);
            if (isMavlink2) {
                if (reader.getRemaining() < MAVLinkPacket.MAVLINK2_NONPAYLOAD_LEN + payloadLength - 2) return packets;
                packet.incompatFlags = reader.readUnsignedByteAsInt();
                if (packet.incompatFlags != 0) return packets;
                packet.compatFlags = reader.readUnsignedByteAsInt();
                packet.seq = reader.readUnsignedByteAsInt();
                packet.sysid = reader.readUnsignedByteAsInt();
                packet.compid = reader.readUnsignedByteAsInt();
                packet.msgid = reader.readInt24AsInt();
                byte[] payloadData = new byte[payloadLength];
                reader.read(payloadData, 0, payloadLength);
                packet.payload = new MAVLinkPayload();
                packet.payload.putArray(payloadData);
                int crc1 = reader.readUnsignedByteAsInt();
                int crc2 = reader.readUnsignedByteAsInt();
                if (!packet.generateCRC(payloadLength)) return packets;
                if (packet.crc.getLSB() != crc1 || packet.crc.getMSB() != crc2) return packets;
            } else {
                if (reader.getRemaining() < MAVLinkPacket.MAVLINK1_NONPAYLOAD_LEN + payloadLength - 2) return packets;
                packet.seq = reader.readUnsignedByteAsInt();
                packet.sysid = reader.readUnsignedByteAsInt();
                packet.compid = reader.readUnsignedByteAsInt();
                packet.msgid = reader.readUnsignedByteAsInt();
                byte[] payloadData = new byte[payloadLength];
                reader.read(payloadData, 0, payloadLength);
                packet.payload = new MAVLinkPayload();
                packet.payload.putArray(payloadData);
                int crc1 = reader.readUnsignedByteAsInt();
                int crc2 = reader.readUnsignedByteAsInt();
                if (!packet.generateCRC(payloadLength)) return packets;
                if (packet.crc.getLSB() != crc1 || packet.crc.getMSB() != crc2) return packets;
            }
            packets.add(packet);
        }
        return packets;
    }

    private synchronized int getSequence(){
        int seq = sequence;
        sequence++;
        if (sequence >= 256) sequence = 0;
        return seq;
    }

    private void disableIntervalMessages(){
        getAttitude(-1);
        getBatteryStatus(-1);
        getSystemStatus(-1);
        getStatusText(-1);
        getGpsRawInt(-1);
        getGlobalPositionInt(-1);
        getHomePosition(-1);
        getSystemTime(-1);
        getRcChannels(-1);
        getScaledPressure(-1);
        getVfrHud(-1);
    }

    public void close(){
        threadsId++;
        if (isInitialized()) disableIntervalMessages();
        apiVersionMinor = -1;
        fcVariant = FcInfo.FC_VARIANT_UNKNOWN;
        fcVersionMajor = -1;
        fcVersionMinor = -1;
        fcVersionPatchLevel = -1;
        platformType = -1;
        isHeartBeatReceived = false;
        fcParams = null;
        telemetryOutputBuffer.clear();
        serialRawData.clear();
        batteryCellCountDetected = 0;
        fcInfo = null;
    }
}
