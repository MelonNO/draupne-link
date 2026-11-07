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
 *  Modified for DRAUPNE Link: stick packets go through Serial.writeRcMsp (newest first), and only once the
 *  flight controller's channel map is known (asked for every tick until it answers). MSP replies are parsed as a
 *  byte stream (a reply cut by the end of a USB read waits for the rest).
 */

package de.droiddrone.flight;

import static de.droiddrone.common.Logcat.log;

import android.location.Location;
import android.location.LocationManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.DataWriter;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.TelemetryData;

public class Msp {
    /** pc.32: the flight controller's last 2D/3D GPS fix (1e-7 degrees) and when (elapsedRealtime), 0 = none yet. */
    volatile int gpsLatE7, gpsLonE7;
    volatile long gpsFixAt;
    /**
     * pc.37 (DRAUPNE Link's observation): the FC's heading (MSP_ATTITUDE yaw, degrees) and when it was read, and the
     * sensors it has (the status's bit mask, -1 unread; bit 2 a magnetometer: then the yaw is a compass heading).
     */
    volatile int attitudeYawDeg;
    volatile long attitudeAt;
    volatile int sensorStatus = -1;
    static final int SENSOR_MAG = 4;

    private static final byte MSP_HEADER_START = 0x24;//$
    private static final byte MSP_HEADER_V1 = 0x4D;//M
    private static final byte MSP_HEADER_V2 = 0x58;//X
    private static final byte MSP_HEADER_REQUEST = 0x3C;//<
    private static final byte MSP_HEADER_RESPONSE = 0x3E;//>
    private static final byte MSP_HEADER_ERROR = 0x21;//!
    private static final byte MSP_V1_HEADER_SIZE = 5;
    private static final byte MSP_V2_HEADER_SIZE = 8;
    private static final byte MSP_CRC_SIZE = 1;
    private static final byte MSP_V1_MIN_REQUEST_SIZE = MSP_V1_HEADER_SIZE + MSP_CRC_SIZE;
    private static final byte MSP_V2_MIN_REQUEST_SIZE = MSP_V2_HEADER_SIZE + MSP_CRC_SIZE;
    private final Serial serial;
    private final Config config;
    public final ArrayBlockingQueue<TelemetryData> telemetryOutputBuffer = new ArrayBlockingQueue<>(30);
    private final ArrayBlockingQueue<byte[]> serialRawData = new ArrayBlockingQueue<>(30);
    private int fcVariant;
    private int apiProtocolVersion;
    private int apiVersionMajor;
    private int apiVersionMinor;
    private int fcVersionMajor;
    private int fcVersionMinor;
    private int fcVersionPatchLevel;
    private int threadsId;
    private volatile boolean isInitialized; // pc.31: volatile - the guards read it on other threads (FcArmedState)
    private FcInfo fcInfo;
    private boolean runFcInit;
    private boolean runGetBoxNames;
    private boolean runGetBoxIds;
    private byte bfBoxNamesPage = 0;
    private byte bfBoxIdsPage = 0;
    private final byte[][] bfBoxNamesData = new byte[FcCommon.BF_BOXMODES_PAGE_COUNT][];
    private final byte[][] bfBoxIdsData = new byte[FcCommon.BF_BOXMODES_PAGE_COUNT][];
    private boolean runGetOsdConfig;
    private boolean runGetBatteryConfig;
    private long onTimestamp, flyTimestamp;
    private int onTime, flyTime, lastArmTime, flyTimeSave;
    /**
     * DRAUPNE Link: the flight controller's channel map (MSP_RX_MAP); sticks go out only once it is known - never in
     * the order the PC sends them, which could put roll on the throttle channel. Volatile: read on the receive thread.
     */
    private volatile int[] rxMap = null;
    /** DRAUPNE Link: a reply cut by the end of a USB read, waiting for the rest (parsePackets). */
    private byte[] carry = null;
    private static final int MAX_CARRY = 4096;
    private volatile int[] boxIds = null;
    private int[] modeFlagsInav = null;
    private byte[] modeFlagsBtfl = null;
    private byte[] osdConfig = null;
    private boolean oldCamSwitchState;
    private int rcMinPeriod;
    private long rcLastFrame;
    private int platformType;
    private FcCommon.GpsFixTypes fixType = FcCommon.GpsFixTypes.GPS_NO_FIX;
    volatile boolean isArmed = false;
    /** pc.31: when the last status reply (its mode flags) came; the armed state is only as fresh as that. */
    private volatile long statusAtMs;
    /**
     * pc.33: the statusAtMs of the reply isArmed was last taken from, written after isArmed - so a reader that sees
     * this stamp sees that reply's isArmed too (statusAtMs is set before checkModeFlags reads the flag).
     */
    private volatile long armedFlagStatusMs;
    /**
     * pc.31: a status reply older than this: armed UNKNOWN. The status is asked for every few telemetry ticks, once
     * a second in standby (slowPoll): 2.5 s lets one reply go missing there.
     */
    static final long STATUS_FRESH_MS = 2500;
    /**
     * DRAUPNE Link (pc.21): standby polls the flight controller once a second instead of telemetryRefreshRate times
     * - the FC's answers are of no use to a PC that shows no picture, and the USB traffic costs battery.
     */
    private volatile boolean slowPoll;
    private float groundSpeed; // km/h
    private float latDeg;
    private float lonDeg;
    private float traveledDistance; // m

    public Msp(Serial serial, Config config){
        this.serial = serial;
        this.config = config;
        fcVariant = FcInfo.FC_VARIANT_UNKNOWN;
        apiProtocolVersion = -1;
        apiVersionMajor = -1;
        apiVersionMinor = -1;
        fcVersionMajor = -1;
        fcVersionMinor = -1;
        fcVersionPatchLevel = -1;
        platformType = -1;
    }

    public boolean isInitialized(){
        isInitialized = (fcVariant != FcInfo.FC_VARIANT_UNKNOWN && apiProtocolVersion != -1 && apiVersionMajor != -1 && apiVersionMinor != -1
                && fcVersionMajor != -1 && fcVersionMinor != -1 && fcVersionPatchLevel != -1 && platformType != -1);
        if (isInitialized && fcInfo == null) setFcInfo();
        return isInitialized;
    }

    private void setFcInfo(){
        fcInfo = new FcInfo(fcVariant, fcVersionMajor, fcVersionMinor, fcVersionPatchLevel, apiProtocolVersion, apiVersionMajor, apiVersionMinor, platformType);
        onTimestamp = System.currentTimeMillis();
        flyTimestamp = 0;
        runFcInit = false;
        log(fcInfo.getFcName() + " Ver. " + fcInfo.getFcVersionStr() + " detected.");
        log("MSP API Ver.: " + fcInfo.getFcApiVersionStr());
        if (rxMap == null) getRxMap(); // DRAUPNE Link: its channel map now, not at the next tick
    }

    /**
     * Upstream drops a stick frame arriving within half a frame time of the one before. DRAUPNE Link's PC announces
     * 255 (the most the byte holds) and numbers its frames (Udp.handleRcFrame drops the stale ones), so for it the
     * window is 0: every frame that arrives in order goes out.
     */
    public void setRcMinPeriod(){
        int rate = config.getRcRefreshRate();
        rcMinPeriod = rate >= 255 ? 0 : 1000 / Math.max(1, rate) / 2;
    }

    /** DRAUPNE Link: whether the flight controller's channel map is known (sticks go out only then). */
    public boolean isRxMapKnown(){
        return rxMap != null;
    }

    /** DRAUPNE Link: whether the flight controller reports ARM on (its mode flags, read every half second). */
    public boolean isArmed(){
        return isInitialized && isArmed;
    }

    /**
     * pc.31: ARMED, DISARMED or UNKNOWN (FcArmedState): UNKNOWN while the FC isn't initialised, its mode ids aren't
     * known (the ARM flag can't be read without them), it is neither Betaflight nor INAV (no status polled), or its
     * last status reply is older than STATUS_FRESH_MS.
     */
    int armedState(long now){
        if (!isInitialized || boxIds == null) return FcArmedState.UNKNOWN;
        if (fcVariant != FcInfo.FC_VARIANT_BETAFLIGHT && fcVariant != FcInfo.FC_VARIANT_INAV) return FcArmedState.UNKNOWN;
        if (now - statusAtMs > STATUS_FRESH_MS) return FcArmedState.UNKNOWN;
        return isArmed ? FcArmedState.ARMED : FcArmedState.DISARMED;
    }

    /**
     * pc.33: Betaflight said DISARMED in a status reply that came at or after {@code since} and is still fresh - what
     * the idle sticks wait for (Udp.idleSticksRun).
     */
    boolean disarmedAfter(long since, long now){
        long at = armedFlagStatusMs; // first: the isArmed read below is at least as new
        return at >= since && now - at <= STATUS_FRESH_MS && armedState(now) == FcArmedState.DISARMED;
    }

    /**
     * DRAUPNE Link: once a second (standby) or at the usual rate. At 1 Hz the loop below counts five ticks per
     * second, so the status (the ARM flag, which ends standby) still comes every second, the rest every other.
     */
    public void setSlowPoll(boolean slow){
        slowPoll = slow;
    }

    public FcInfo getFcInfo(){
        return fcInfo;
    }

    public void initialize() {
        runFcInit = true;
        onTimestamp = 0;
        flyTimestamp = 0;
        flyTimeSave = 0;
        onTime = 0;
        flyTime = 0;
        lastArmTime = 0;
        setRcMinPeriod();
        telemetryOutputBuffer.clear();
        serialRawData.clear();
        carry = null;
        threadsId++;
        Thread mspThread = new Thread(mspRun);
        mspThread.setDaemon(false);
        mspThread.setName("mspThread");
        mspThread.start();
        Thread serialDataThread = new Thread(serialDataRun);
        serialDataThread.setDaemon(false);
        serialDataThread.setName("serialDataThread");
        serialDataThread.start();
    }

    public void runGetBoxIds(){
        runGetBoxIds = true;
    }

    public void runGetBoxNames(){
        runGetBoxNames = true;
    }

    public void runGetOsdConfig(){
        runGetOsdConfig = true;
    }

    public void runGetBatteryConfig(){
        runGetBatteryConfig = true;
    }

    public void close(){
        threadsId++;
        isInitialized = false;
        osdConfig = null;
        telemetryOutputBuffer.clear();
        serialRawData.clear();
        fcVariant = FcInfo.FC_VARIANT_UNKNOWN;
        apiProtocolVersion = -1;
        apiVersionMajor = -1;
        apiVersionMinor = -1;
        fcVersionMajor = -1;
        fcVersionMinor = -1;
        fcVersionPatchLevel = -1;
        platformType = -1;
        fcInfo = null;
        rxMap = null; // DRAUPNE Link: a reconnected (maybe another) flight controller: read its channel map again
        carry = null;
        // pc.31: and its mode ids and status too (another FC has its modes on other bits; armed is UNKNOWN until then)
        boxIds = null;
        bfBoxIdsPage = 0;
        modeFlagsBtfl = null;
        modeFlagsInav = null;
        statusAtMs = 0;
        armedFlagStatusMs = 0;
        isArmed = false;
    }

    private final Runnable mspRun = new Runnable() {
        public void run() {
            final int id = threadsId;
            int timerDiv = 0;
            log("Start MSP thread - OK");
            while (id == threadsId) {
                try {
                    timerDiv += slowPoll ? 5 : 1;

                    if (!isInitialized && isInitialized()) setFcInfo();
                    // DRAUPNE Link: the channel map every tick (10 a second) from the port opening until it is
                    // known - no sticks go out before. Not through the writer's slow-link backoff, which skips
                    // ordinary requests
                    if (rxMap == null) getRxMap();
                    if (runFcInit) {
                        if (fcVariant == 0) getFcVariant();
                        if (apiVersionMajor == -1) getMspApiVersion();
                        if (fcVersionMajor == -1) getFcVersion();
                        if (platformType == -1 && fcVariant != 0) getMixerConfig();
                        Thread.sleep(1000 / config.getTelemetryRefreshRate());
                        continue;
                    }

                    if (timerDiv % 5 == 0) {
                        switch (fcVariant){
                            case FcInfo.FC_VARIANT_INAV:
                                getInavStatus();
                                break;
                            case FcInfo.FC_VARIANT_BETAFLIGHT:
                                getStatus();
                                break;
                        }
                        // pc.31: checkModeFlags runs on the status reply itself (one caller: the camera switch's edge)
                        processOnTimeFlyTime();
                        getAltitude();
                    }

                    if (timerDiv % 10 == 0) {
                        timerDiv = 0;
                        // pc.31: the mode ids by themselves, not only when the PC asks - without them the ARM flag
                        // can't be read, and every armed guard counts the FC as armed (FcArmedState)
                        if (boxIds == null && (fcVariant == FcInfo.FC_VARIANT_BETAFLIGHT || fcVariant == FcInfo.FC_VARIANT_INAV)) runGetBoxIds = true;
                        if (runGetBoxNames || runGetBoxIds) {
                            switch (fcVariant){
                                case FcInfo.FC_VARIANT_INAV:
                                    if (runGetBoxIds) getBoxIds();
                                    if (runGetBoxNames) getBoxNames();
                                    break;
                                case FcInfo.FC_VARIANT_BETAFLIGHT:
                                    if (runGetBoxIds) getBoxIds(bfBoxIdsPage);
                                    if (runGetBoxNames) getBoxNames(bfBoxNamesPage);
                                    break;
                            }
                        }
                        if (runGetOsdConfig) getOsdConfig();
                        if (runGetBatteryConfig) {
                            if (fcVariant == FcInfo.FC_VARIANT_INAV){
                                runGetBatteryConfig = false;
                            }else {
                                getBatteryConfig();
                            }
                        }
                        getBatteryState();
                        getRawGps();
                        getCompGps();
                        getVtxConfig();
                        switch (fcVariant){
                            case FcInfo.FC_VARIANT_INAV:
                                getInavAnalog();
                                break;
                            case FcInfo.FC_VARIANT_BETAFLIGHT:
                                getAnalog();
                                break;
                        }
                    }

                    getAttitude();
                    Thread.sleep(slowPoll ? 1000 : 1000 / config.getTelemetryRefreshRate());
                } catch (Exception e) {
                    log("MSP thread error: " + e);
                }
            }
        }
    };

    public static class MspPacket{
        public final byte version;
        public final byte type;
        public final byte flag;
        public final int code;
        public final int payloadSize;
        public final byte[] payload;

        public MspPacket(byte type, byte flag, int code, int payloadSize, byte[] payload) {
            this.version = MSP_HEADER_V2;
            this.type = type;
            this.flag = flag;
            this.code = code;
            this.payloadSize = payloadSize;
            this.payload = payload;
        }

        public MspPacket(byte type, int code, int payloadSize, byte[] payload) {
            this.version = MSP_HEADER_V1;
            this.type = type;
            this.flag = 0;
            this.code = code;
            this.payloadSize = payloadSize;
            this.payload = payload;
        }
    }

    /**
     * DRAUPNE Link: the whole MSP frames in data, as a byte stream. Upstream took data as whole frames and dropped
     * the rest of a read at the first frame that was cut short - and a USB read ends wherever it ends: long replies
     * (BOXNAMES, OSD_CONFIG) split across two reads were lost, asked for again, and lost again. Here a frame that
     * isn't complete yet stays in {@link #carry} for the next read; a byte that is no frame start, or a frame with
     * a bad checksum, is stepped over one byte at a time until the next frame start.
     */
    private List<MspPacket> parsePackets(byte[] data){
        List<MspPacket> packets = new ArrayList<>();
        byte[] s = data;
        if (carry != null) {
            s = new byte[carry.length + data.length];
            System.arraycopy(carry, 0, s, 0, carry.length);
            System.arraycopy(data, 0, s, carry.length, data.length);
            carry = null;
        }
        int len = s.length;
        int i = 0;
        while (i < len) {
            if (s[i] != MSP_HEADER_START) {
                i++;
                continue;
            }
            if (len - i < 3) break; // the rest of the header comes with the next read
            byte magic = s[i + 1];
            byte type = s[i + 2];
            boolean isMsp2 = magic == MSP_HEADER_V2;
            if (magic != MSP_HEADER_V1 && !isMsp2 || type != MSP_HEADER_REQUEST && type != MSP_HEADER_RESPONSE && type != MSP_HEADER_ERROR) {
                i++;
                continue;
            }
            int frameSize;
            if (isMsp2) {
                if (len - i < MSP_V2_HEADER_SIZE) break;
                byte flag = s[i + 3];
                int code = (s[i + 4] & 0xFF) | (s[i + 5] & 0xFF) << 8;
                int payloadSize = (s[i + 6] & 0xFF) | (s[i + 7] & 0xFF) << 8;
                if (payloadSize > MAX_CARRY) { // no MSP reply is that long: not a frame start
                    i++;
                    continue;
                }
                frameSize = MSP_V2_HEADER_SIZE + payloadSize + MSP_CRC_SIZE;
                if (len - i < frameSize) break;
                byte[] payload = null;
                if (payloadSize > 0) {
                    payload = new byte[payloadSize];
                    System.arraycopy(s, i + MSP_V2_HEADER_SIZE, payload, 0, payloadSize);
                }
                int checksum = s[i + frameSize - 1] & 0xFF;
                if (checksum == calculateCrcV2(flag, code, payloadSize, payload)) {
                    packets.add(new MspPacket(type, flag, code, payloadSize, payload));
                    i += frameSize;
                } else {
                    i++;
                }
            } else {
                if (len - i < MSP_V1_HEADER_SIZE) break;
                int payloadSize = s[i + 3] & 0xFF;
                int code = s[i + 4] & 0xFF;
                frameSize = MSP_V1_HEADER_SIZE + payloadSize + MSP_CRC_SIZE;
                if (len - i < frameSize) break;
                byte[] payload = null;
                if (payloadSize > 0) {
                    payload = new byte[payloadSize];
                    System.arraycopy(s, i + MSP_V1_HEADER_SIZE, payload, 0, payloadSize);
                }
                int checksum = s[i + frameSize - 1] & 0xFF;
                if (checksum == calculateCrcV1(code, payloadSize, payload)) {
                    packets.add(new MspPacket(type, code, payloadSize, payload));
                    i += frameSize;
                } else {
                    i++;
                }
            }
        }
        if (i < len) {
            int rest = len - i;
            if (rest <= MAX_CARRY) carry = Arrays.copyOfRange(s, i, len);
        }
        return packets;
    }

    public void addData(byte[] buf, int dataLength){
        if (dataLength <= 0) return; // DRAUPNE Link: a short read can be the end of a frame begun in the last one
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

    private void processData(byte[] data){
        List<MspPacket> packets = null;
        try {
            packets = parsePackets(data);
        }catch (Exception e){
            log("Msp - parsePackets error: " + e);
        }
        if (packets == null || packets.isEmpty()) return;
        for (MspPacket packet : packets) {
            try {
                if (packet.type == MSP_HEADER_ERROR) {
                    StringBuilder s = new StringBuilder();
                    for (byte b : data) s.append(String.valueOf(b)).append(" ");
                    log("MSP error received: " + s);
                    continue;
                }
                if (packet.type != MSP_HEADER_RESPONSE) continue;
                DataReader buffer = new DataReader(packet.payload, false);
                switch (packet.code) {
                    case FcCommon.MSP_API_VERSION:
                        apiProtocolVersion = buffer.readUnsignedByteAsInt();
                        apiVersionMajor = buffer.readUnsignedByteAsInt();
                        apiVersionMinor = buffer.readUnsignedByteAsInt();
                        break;
                    case FcCommon.MSP_FC_VARIANT: {
                        String fcStr = buffer.readBufferAsString();
                        if (FcInfo.INAV_ID.equals(fcStr)) fcVariant = FcInfo.FC_VARIANT_INAV;
                        if (FcInfo.BETAFLIGHT_ID.equals(fcStr))
                            fcVariant = FcInfo.FC_VARIANT_BETAFLIGHT;
                        break;
                    }
                    case FcCommon.MSP_FC_VERSION:
                        fcVersionMajor = buffer.readUnsignedByteAsInt();
                        fcVersionMinor = buffer.readUnsignedByteAsInt();
                        fcVersionPatchLevel = buffer.readUnsignedByteAsInt();
                        break;
                    case FcCommon.MSP_MIXER_CONFIG:
                        platformType = buffer.readUnsignedByteAsInt();
                        break;
                    case FcCommon.MSP2_INAV_MIXER:
                        buffer.readUnsignedByteAsInt();// motorDirectionInverted
                        buffer.readUnsignedByteAsInt();// 0
                        buffer.readUnsignedByteAsInt();// motorstopOnLow
                        platformType = buffer.readUnsignedByteAsInt();
                        break;
                    case FcCommon.MSP_BOXNAMES: {
                        switch (fcVariant) {
                            case FcInfo.FC_VARIANT_INAV: {
                                runGetBoxNames = false;
                                telemetryOutputBuffer.offer(new TelemetryData(packet.code, buffer.getData()));
                                break;
                            }
                            case FcInfo.FC_VARIANT_BETAFLIGHT: {
                                byte[] pageData = buffer.getData();
                                if (bfBoxNamesPage > 0) {
                                    boolean found = false;
                                    for (int i = 0; i < bfBoxNamesPage; i++) {
                                        if (Arrays.equals(bfBoxNamesData[i], pageData)) {
                                            found = true;
                                            break;
                                        }
                                    }
                                    if (found) break;
                                }
                                bfBoxNamesData[bfBoxNamesPage] = pageData;
                                bfBoxNamesPage++;
                                if (bfBoxNamesPage == FcCommon.BF_BOXMODES_PAGE_COUNT || pageData == null && bfBoxNamesPage > 1) {
                                    runGetBoxNames = false;
                                    bfBoxNamesPage = 0;
                                    int total = 0;
                                    int offset = 0;
                                    for (int i = 0; i < FcCommon.BF_BOXMODES_PAGE_COUNT; i++) {
                                        if (bfBoxNamesData[i] == null) continue;
                                        total += bfBoxNamesData[i].length;
                                    }
                                    if (total > 0) {
                                        byte[] allPages = new byte[total];
                                        for (int i = 0; i < FcCommon.BF_BOXMODES_PAGE_COUNT; i++) {
                                            if (bfBoxNamesData[i] == null) continue;
                                            int length = bfBoxNamesData[i].length;
                                            System.arraycopy(bfBoxNamesData[i], 0, allPages, offset, length);
                                            offset += length;
                                        }
                                        telemetryOutputBuffer.offer(new TelemetryData(packet.code, allPages));
                                    }
                                }
                                break;
                            }
                        }
                        break;
                    }
                    case FcCommon.MSP_BOXIDS: {
                        switch (fcVariant) {
                            case FcInfo.FC_VARIANT_INAV: {
                                runGetBoxIds = false;
                                boxIds = FcCommon.getBoxIds(buffer.getData());
                                telemetryOutputBuffer.offer(new TelemetryData(packet.code, buffer.getData()));
                                break;
                            }
                            case FcInfo.FC_VARIANT_BETAFLIGHT: {
                                byte[] pageData = buffer.getData();
                                if (bfBoxIdsPage > 0) {
                                    boolean found = false;
                                    for (int i = 0; i < bfBoxIdsPage; i++) {
                                        if (Arrays.equals(bfBoxIdsData[i], pageData)) {
                                            found = true;
                                            break;
                                        }
                                    }
                                    if (found) break;
                                }
                                bfBoxIdsData[bfBoxIdsPage] = pageData;
                                bfBoxIdsPage++;
                                if (bfBoxIdsPage == FcCommon.BF_BOXMODES_PAGE_COUNT || pageData == null && bfBoxIdsPage > 1) {
                                    runGetBoxIds = false;
                                    bfBoxIdsPage = 0;
                                    int total = 0;
                                    int offset = 0;
                                    for (int i = 0; i < FcCommon.BF_BOXMODES_PAGE_COUNT; i++) {
                                        if (bfBoxIdsData[i] == null) continue;
                                        total += bfBoxIdsData[i].length;
                                    }
                                    if (total > 0) {
                                        byte[] allPages = new byte[total];
                                        for (int i = 0; i < FcCommon.BF_BOXMODES_PAGE_COUNT; i++) {
                                            if (bfBoxIdsData[i] == null) continue;
                                            int length = bfBoxIdsData[i].length;
                                            System.arraycopy(bfBoxIdsData[i], 0, allPages, offset, length);
                                            offset += length;
                                        }
                                        boxIds = FcCommon.getBoxIds(allPages);
                                        telemetryOutputBuffer.offer(new TelemetryData(packet.code, allPages));
                                    }
                                }
                                break;
                            }
                        }
                        break;
                    }
                    case FcCommon.MSP_BATTERY_CONFIG: {
                        runGetBatteryConfig = false;
                        telemetryOutputBuffer.offer(new TelemetryData(packet.code, buffer.getData()));
                        break;
                    }
                    case FcCommon.MSP_RX_MAP: {
                        if (buffer.getSize() == 0) break;
                        int[] map = new int[buffer.getSize()];
                        for (int i = 0; i < map.length; i++) {
                            map[i] = buffer.readUnsignedByteAsInt();
                        }
                        if (rxMap == null) log("Channel map known: " + Arrays.toString(map) + " - sticks go out now");
                        rxMap = map;
                        break;
                    }
                    case FcCommon.MSP_OSD_CONFIG: {
                        runGetOsdConfig = false;
                        osdConfig = buffer.getData();
                        if (fcVariant == FcInfo.FC_VARIANT_BETAFLIGHT) {
                            getOsdCanvas();
                        } else {
                            telemetryOutputBuffer.offer(new TelemetryData(packet.code, buffer.getData()));
                        }
                        break;
                    }
                    case FcCommon.MSP_OSD_CANVAS: {
                        telemetryOutputBuffer.offer(new TelemetryData(packet.code, buffer.getData()));
                        if (osdConfig != null)
                            telemetryOutputBuffer.offer(new TelemetryData(FcCommon.MSP_OSD_CONFIG, osdConfig));
                        break;
                    }
                    case FcCommon.MSP2_INAV_STATUS: {
                        buffer.readShort();//cycleTime
                        buffer.readShort();//i2cErrorCount
                        sensorStatus = buffer.readShort() & 0xffff; // pc.37: the compass bit, for the observers' heading
                        buffer.readShort();//averageSystemLoad
                        buffer.readByte();//profiles
                        buffer.readInt();//armingFlags
                        int[] modeFlags = null;
                        int modeFlagsSize = (int) Math.ceil((buffer.getRemaining() - 1) / 4.0);
                        if (modeFlagsSize > 0) {
                            modeFlags = new int[modeFlagsSize];
                            for (int i = 0; i < modeFlagsSize; i++) {
                                modeFlags[i] = buffer.readInt();
                            }
                        }
                        this.modeFlagsInav = modeFlags;
                        statusAtMs = System.currentTimeMillis();
                        checkModeFlags(); // pc.31: the ARM flag from this reply, now - not at the next poll
                        telemetryOutputBuffer.offer(new TelemetryData(packet.code, buffer.getData()));
                        break;
                    }
                    case FcCommon.MSP_STATUS: {
                        switch (fcVariant) {
                            case FcInfo.FC_VARIANT_INAV:
                                telemetryOutputBuffer.offer(new TelemetryData(packet.code, buffer.getData()));
                                break;
                            case FcInfo.FC_VARIANT_BETAFLIGHT: {
                                buffer.readShort();//cycleTime
                                buffer.readShort();//i2cErrorCount
                                sensorStatus = buffer.readShort() & 0xffff; // pc.37: the compass bit, for the observers' heading
                                int firstModeFlag = buffer.readInt();
                                buffer.readByte();//currentPidProfileIndex
                                buffer.readShort();//averageSystemLoad
                                buffer.readShort();//unused
                                byte[] modeFlags;
                                int modeFlagsSize = buffer.readByte() + 4;
                                if (modeFlagsSize > 0) {
                                    modeFlags = new byte[modeFlagsSize];
                                    modeFlags[0] = (byte) (firstModeFlag & 0xFF);
                                    modeFlags[1] = (byte) (firstModeFlag >> 8 & 0xFF);
                                    modeFlags[2] = (byte) (firstModeFlag >> 16 & 0xFF);
                                    modeFlags[3] = (byte) (firstModeFlag >> 24 & 0xFF);
                                    for (int i = 4; i < modeFlagsSize; i++) {
                                        modeFlags[i] = buffer.readByte();
                                    }
                                    this.modeFlagsBtfl = modeFlags;
                                    statusAtMs = System.currentTimeMillis();
                                    checkModeFlags(); // pc.31: the ARM flag from this reply, now - not at the next poll
                                }
                                telemetryOutputBuffer.offer(new TelemetryData(packet.code, buffer.getData()));
                                break;
                            }
                        }
                        break;
                    }
                    case FcCommon.MSP_RAW_GPS: {
                        byte fixType = buffer.readByte();
                        byte numSat = buffer.readByte();
                        int lat = buffer.readInt();
                        int lon = buffer.readInt();
                        short altGps = buffer.readShort();
                        // pc.32: kept for the PCs' map (the name reply says where this drone is)
                        if (fixType >= 2 && (lat != 0 || lon != 0)) {
                            gpsLatE7 = lat;
                            gpsLonE7 = lon;
                            gpsFixAt = android.os.SystemClock.elapsedRealtime();
                        }
                        short groundSpeed = buffer.readShort();
                        short groundCourse = buffer.readShort();
                        short hdop = buffer.readShort();

                        if (fixType >= 0 && fixType < FcCommon.GpsFixTypes.values().length) this.fixType = FcCommon.GpsFixTypes.values()[fixType];
                        float latDeg = lat / 10000000f;
                        float lonDeg = lon / 10000000f;
                        this.groundSpeed = groundSpeed * 0.036f;
                        calculateTraveledDist(latDeg, lonDeg);

                        DataWriter wBuffer = new DataWriter(true);
                        wBuffer.writeByte(fixType);
                        wBuffer.writeByte(numSat);
                        wBuffer.writeInt(lat);
                        wBuffer.writeInt(lon);
                        wBuffer.writeShort(altGps);
                        wBuffer.writeShort(groundSpeed);
                        wBuffer.writeShort(groundCourse);
                        wBuffer.writeShort(hdop);
                        wBuffer.writeInt(Math.round(traveledDistance));
                        telemetryOutputBuffer.offer(new TelemetryData(packet.code, wBuffer.getData()));
                        break;
                    }
                    case FcCommon.MSP_ATTITUDE: {
                        // pc.37: the heading kept for the observers (roll, pitch in 0.1 deg, then yaw in deg)
                        byte[] a = buffer.getData();
                        if (a != null && a.length >= 6) {
                            attitudeYawDeg = (short) ((a[4] & 0xff) | (a[5] << 8));
                            attitudeAt = android.os.SystemClock.elapsedRealtime();
                        }
                        telemetryOutputBuffer.offer(new TelemetryData(packet.code, a));
                        break;
                    }
                    case FcCommon.MSP_ALTITUDE:
                    case FcCommon.MSP_ANALOG:
                    case FcCommon.MSP_VTX_CONFIG:
                    case FcCommon.MSP_BATTERY_STATE:
                    case FcCommon.MSP_COMP_GPS:
                    case FcCommon.MSP2_INAV_ANALOG: {
                        telemetryOutputBuffer.offer(new TelemetryData(packet.code, buffer.getData()));
                        break;
                    }
                }
            } catch (Exception e) {
                log("MSP - processData error: " + e + ", MSP code: " + packet.code);
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

    private boolean checkGpsFix(){
        return fixType == FcCommon.GpsFixTypes.GPS_FIX_3D;
    }

    private void checkModeFlags(){
        boolean isCamSwitch = false;
        boolean isArmed = false;
        FcCommon.BoxMode[] activeBoxModes = null;
        switch (fcVariant){
            case FcInfo.FC_VARIANT_INAV:
                activeBoxModes = FcCommon.getActiveBoxesInav(modeFlagsInav, boxIds);
                break;
            case FcInfo.FC_VARIANT_BETAFLIGHT:
                activeBoxModes = FcCommon.getActiveBoxesBtfl(modeFlagsBtfl, boxIds);
                break;
        }
        if (activeBoxModes != null) {
            for (FcCommon.BoxMode box : activeBoxModes) {
                if (box.boxId == FcCommon.BoxModeIds.BOXARM) isArmed = true;
                if (box.boxId == FcCommon.BoxModeIds.BOXCAMERA2) isCamSwitch = true;
            }
        }
        this.isArmed = isArmed;
        armedFlagStatusMs = statusAtMs;
        if (isArmed){
            if (flyTimestamp == 0) flyTimestamp = System.currentTimeMillis();
        }else{
            if (flyTimestamp != 0) flyTimeSave = flyTimeSave + (int) (System.currentTimeMillis() - flyTimestamp) / 1000;
            flyTimestamp = 0;
        }

        if (isCamSwitch && !oldCamSwitchState){
            telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_VIDEO_RECORDER_START_STOP, null));
        }
        oldCamSwitchState = isCamSwitch;
    }

    private void processOnTimeFlyTime(){ // we don't have these values via MSP
        long current = System.currentTimeMillis();
        if (onTimestamp != 0) onTime = (int) (current - onTimestamp) / 1000;
        if (flyTimestamp != 0) {
            lastArmTime = (int) (current - flyTimestamp) / 1000;
            flyTime = flyTimeSave + lastArmTime;
        }
        DataWriter writer = new DataWriter(false);
        writer.writeInt(onTime);
        writer.writeInt(flyTime);
        writer.writeInt(lastArmTime);
        telemetryOutputBuffer.offer(new TelemetryData(FcCommon.DD_TIMERS, writer.getData()));
    }

    /**
     * A stick frame (the first count channels of rcChannels, AETR then AUX), on the receive thread. Only once the
     * flight controller's channel map is known: never unmapped (the PC sees why on the PHONE_STATE record), as sticks
     * in the wrong order would put, say, roll on the throttle channel. The MSP_SET_RAW_RC packet is built here and
     * handed to the serial writer; nothing waits.
     */
    public void setRawRc(short[] rcChannels, int count){
        if (rcChannels == null || count < 4 || count > rcChannels.length || count > FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT) return;
        int[] map = rxMap;
        if (map == null) return;
        long current = System.currentTimeMillis();
        if (rcMinPeriod > 0 && current - rcLastFrame < rcMinPeriod) return;
        rcLastFrame = current;
        byte[] payload = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            int at = i; // where the PC's channel i goes in Betaflight's order
            if (i < map.length) {
                if (map[i] < 0 || map[i] >= count) return; // a map that doesn't fit the frame: no sticks
                at = map[i];
            }
            payload[at * 2] = (byte) (rcChannels[i] & 0xFF); // MSP is little-endian
            payload[at * 2 + 1] = (byte) (rcChannels[i] >> 8 & 0xFF);
        }
        serial.writeRcMsp(getMspRequestWithPayload(FcCommon.MSP_SET_RAW_RC, payload));
    }

    public void getBatteryState(){
        serial.writeDataMsp(getMspRequest(FcCommon.MSP_BATTERY_STATE), true);
    }

    /** DRAUPNE Link: ahead of the writer's slow-link backoff, and before the version check (any MSP board answers). */
    public void getRxMap(){
        serial.writeDataMspUrgent(getMspRequest(FcCommon.MSP_RX_MAP));
    }

    /** pc.31: the status through its own slot, never skipped by the writer's slow-link backoff - it says armed. */
    public void getInavStatus(){
        serial.writeDataMspStatus(getMspRequest(FcCommon.MSP2_INAV_STATUS));
    }

    public void getInavAnalog(){
        serial.writeDataMsp(getMspRequest(FcCommon.MSP2_INAV_ANALOG), true);
    }

    public void getAttitude(){
        serial.writeDataMsp(getMspRequest(FcCommon.MSP_ATTITUDE), true);
    }

    public void getAltitude(){
        serial.writeDataMsp(getMspRequest(FcCommon.MSP_ALTITUDE), true);
    }

    public void getAnalog(){
        serial.writeDataMsp(getMspRequest(FcCommon.MSP_ANALOG), true);
    }

    public void getOsdConfig(){
        serial.writeDataMsp(getMspRequest(FcCommon.MSP_OSD_CONFIG), true);
    }

    public void getBatteryConfig(){
        serial.writeDataMsp(getMspRequest(FcCommon.MSP_BATTERY_CONFIG), true);
    }

    public void getVtxConfig(){
        serial.writeDataMsp(getMspRequest(FcCommon.MSP_VTX_CONFIG), true);
    }

    public void getBoxNames(){
        serial.writeDataMsp(getMspRequest(FcCommon.MSP_BOXNAMES), true);
    }

    public void getBoxNames(byte page){
        byte[] data = getMspRequestWithPayload(FcCommon.MSP_BOXNAMES, new byte[] { page });
        serial.writeDataMsp(data, true);
    }

    public void getBoxIds(){
        serial.writeDataMsp(getMspRequest(FcCommon.MSP_BOXIDS), true);
    }

    public void getBoxIds(byte page){
        byte[] data = getMspRequestWithPayload(FcCommon.MSP_BOXIDS, new byte[] { page });
        serial.writeDataMsp(data, true);
    }

    public void getStatus(){
        serial.writeDataMspStatus(getMspRequest(FcCommon.MSP_STATUS));
    }

    public void getRawGps(){
        serial.writeDataMsp(getMspRequest(FcCommon.MSP_RAW_GPS), true);
    }

    public void getCompGps(){
        serial.writeDataMsp(getMspRequest(FcCommon.MSP_COMP_GPS), true);
    }

    public void getOsdCanvas(){
        serial.writeDataMsp(getMspRequest(FcCommon.MSP_OSD_CANVAS), true);
    }

    public void getFcVersion(){
        serial.writeDataMsp(getMspRequest(FcCommon.MSP_FC_VERSION), false);
    }

    public void getFcVariant(){
        serial.writeDataMsp(getMspRequest(FcCommon.MSP_FC_VARIANT), false);
    }

    public void getMspApiVersion(){
        serial.writeDataMsp(getMspRequest(FcCommon.MSP_API_VERSION), false);
    }

    public void getMixerConfig(){
        switch (fcVariant){
            case FcInfo.FC_VARIANT_INAV:
                serial.writeDataMsp(getMspRequest(FcCommon.MSP2_INAV_MIXER), false);
                break;
            case FcInfo.FC_VARIANT_BETAFLIGHT:
                serial.writeDataMsp(getMspRequest(FcCommon.MSP_MIXER_CONFIG), false);
                break;
        }
    }

    private byte[] getMspRequest(short cmd){
        byte[] data = {MSP_HEADER_START, MSP_HEADER_V2, MSP_HEADER_REQUEST, 0, (byte) (cmd & 0xFF), (byte) (cmd >> 8 & 0xFF), 0, 0, 0};
        setCrcV2(data);
        return data;
    }

    private byte[] getMspRequestWithPayload(short cmd, byte[] payload){
        int payloadSize = payload.length;
        int totalSize = payloadSize + MSP_V2_MIN_REQUEST_SIZE;
        byte[] data = new byte[totalSize];
        byte[] header = {MSP_HEADER_START, MSP_HEADER_V2, MSP_HEADER_REQUEST, 0, (byte) (cmd & 0xFF), (byte) (cmd >> 8 & 0xFF), (byte) (payloadSize & 0xFF), (byte) (payloadSize >> 8 & 0xFF)};
        System.arraycopy(header, 0, data, 0, MSP_V2_HEADER_SIZE);
        System.arraycopy(payload, 0, data, MSP_V2_HEADER_SIZE, payloadSize);
        setCrcV2(data);
        return data;
    }

    private void setCrcV2(byte[] data){
        if (data == null || data.length < MSP_V2_MIN_REQUEST_SIZE) return;
        data[data.length-1] = (byte)calculateCrcV2(data, data.length);
    }

    private int calculateCrcV2(byte[] data, int dataLength){
        int crc = 0;
        for (int i = 3; i < dataLength-1; i++) {
            crc = crc8_dvb_s2(crc, data[i]);
        }
        return crc;
    }

    private int calculateCrcV2(byte flag, int code, int payloadSize, byte[] payload){
        int crc = 0;
        crc = crc8_dvb_s2(crc, flag);
        crc = crc8_dvb_s2(crc, code & 0xFF);
        crc = crc8_dvb_s2(crc, code >> 8 & 0xFF);
        crc = crc8_dvb_s2(crc, payloadSize & 0xFF);
        crc = crc8_dvb_s2(crc, payloadSize >> 8 & 0xFF);
        if (payloadSize > 0){
            for (int i = 0; i < payloadSize; i++) {
                crc = crc8_dvb_s2(crc, payload[i]);
            }
        }
        return crc;
    }

    private int calculateCrcV1(int code, int payloadSize, byte[] payload){
        int crc = 0;
        crc ^= code;
        crc ^= payloadSize;
        if (payloadSize > 0){
            for (int i = 0; i < payloadSize; i++) {
                crc ^= payload[i];
            }
        }
        return crc;
    }

    private int crc8_dvb_s2(int crc, int a) {
        crc ^= a;
        for (int i = 0; i < 8; i++) {
            if ((crc & 0x80) != 0) {
                crc = (crc << 1) ^ 0xD5;
            } else {
                crc = crc << 1;
            }
        }
        crc &= 0xFF;
        return crc;
    }
}
