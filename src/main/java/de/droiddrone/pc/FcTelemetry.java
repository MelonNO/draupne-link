package de.droiddrone.pc;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.NetworkState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the flight controller reports through the Flight app's TelemetryData packets (port of the record layouts in
 * upstream Control Udp.processTelemetryData / Flight Udp.fillTelemetryPacketData). Records are [code i16][payload]
 * with no length field, so parsing stops at the first unknown code rather than guessing.
 * Written on the link's buffer thread, read by the GUI: fields are replaced as whole immutable values.
 */
public class FcTelemetry {
    /** One reason Betaflight refuses to arm: its OSD name and what to do about it. */
    public record Blocker(String osdName, String explanation) {}

    /** The FC's own view: armed or not, which modes its switches turned on, and why arming is blocked. */
    public record Status(boolean armed, List<String> activeModes, List<Blocker> blockers, int cpuLoadPercent, long receivedAt) {}

    public record Battery(double volts, int cells, int mahDrawn, double amps) {
        /** The FC's cell count; estimated from the pack voltage until the FC reports it. */
        public int cellCount() {
            return cells > 0 ? cells : Math.max(1, (int) Math.ceil(volts / 4.35));
        }

        public double cellVolts() {
            return volts / cellCount();
        }
    }

    /** roll/pitch in degrees (right / nose up positive as Betaflight reports), heading 0-359. */
    public record Attitude(double rollDeg, double pitchDeg, int headingDeg, long receivedAt) {}

    public record Altitude(double meters, double varioMetersPerSecond) {}

    /** fix: 0 none, 1 2D, 2 3D (as Betaflight); distanceHomeM -1 if unknown. */
    public record Gps(int fix, int sats, double speedKmh, int altM, int distanceHomeM, int directionHomeDeg) {}

    public record Timers(int onSeconds, int flySeconds, int lastArmSeconds) {}

    /** The phone's mobile network: "LTE", "5G", ... and signal 0-100 %. */
    public record Network(String name, int strengthPercent) {}

    /** Betaflight's cell voltage limits (Power & Battery tab). */
    public record BatteryLimits(double minCell, double maxCell, double warningCell) {}

    /**
     * Betaflight 4.4/4.5 armingDisableFlags_e, bit by bit (as upstream Control Osd.setStatusBetaflight), with the
     * name the Betaflight OSD shows.
     */
    static final Blocker[] BETAFLIGHT_BLOCKERS = {
            new Blocker("NOGYRO", "No gyro detected - hardware or firmware target problem."),
            new Blocker("FAILSAFE", "Failsafe is active."),
            new Blocker("RXLOSS", "The FC gets no stick data. Receiver mode must be 'MSP RX input' (Receiver tab) and RC OUTPUT must be LIVE here."),
            new Blocker("BADRX", "RC link came back after a loss - switch ARM off and on again."),
            new Blocker("BOXFAILSAFE", "The failsafe switch is on."),
            new Blocker("RUNAWAY", "Runaway takeoff was detected - disarm and re-arm."),
            new Blocker("CRASH", "Crash was detected - disarm and re-arm."),
            new Blocker("THROTTLE", "The FC sees throttle above minimum. If your throttle stick is down, the channels arrive in the wrong order (check the channel map and recalibrate)."),
            new Blocker("ANGLE", "The drone is tilted more than small_angle (25 deg). Put it on level ground; calibrate the accelerometer in Configurator's Setup tab; check board orientation - or 'set small_angle = 180' in the CLI."),
            new Blocker("BOOTGRACE", "Just powered up - wait a few seconds."),
            new Blocker("NOPREARM", "A PREARM switch is configured and not on."),
            new Blocker("LOAD", "CPU overloaded - lower the PID loop / gyro rate."),
            new Blocker("CALIB", "Sensors are calibrating - keep the drone still."),
            new Blocker("CLI", "The CLI is open in Betaflight Configurator."),
            new Blocker("CMS", "The OSD menu (CMS) is open."),
            new Blocker("BST", "BST interface is blocking arming."),
            new Blocker("MSP", "Arming was disabled over MSP - Betaflight Configurator does this while connected. Disconnect it properly, or power-cycle the FC (battery and USB out)."),
            new Blocker("PARALYZE", "PARALYZE mode was activated - power-cycle the FC."),
            new Blocker("GPS", "GPS Rescue needs a GPS fix with enough satellites before arming."),
            new Blocker("RESCUE SW", "The GPS Rescue switch is on."),
            new Blocker("RPMFILTER", "RPM filter has no motor telemetry - check bidirectional DShot / ESC firmware."),
            new Blocker("REBOOT_REQD", "Settings changed - reboot the FC."),
            new Blocker("DSHOT_BBANG", "DShot bitbang failed - check motor protocol settings."),
            new Blocker("NO_ACC_CAL", "Accelerometer not calibrated - Configurator's Setup tab, Calibrate Accelerometer."),
            new Blocker("MOTOR_PROTO", "No valid motor protocol selected (Motors tab)."),
            new Blocker("ARMSWITCH", "The arm switch was already on while arming was blocked - switch it off, fix the other reasons, then on."),
    };

    /** Blockers for a raw Betaflight arming-disable bit field. */
    public static List<Blocker> blockersFor(int armingFlags) {
        List<Blocker> blockers = new ArrayList<>();
        for (int bit = 0; bit < 32; bit++) {
            if ((armingFlags >> bit & 1) == 1) {
                blockers.add(bit < BETAFLIGHT_BLOCKERS.length ? BETAFLIGHT_BLOCKERS[bit] : new Blocker("FLAG " + bit, "Unknown reason."));
            }
        }
        return blockers;
    }

    private volatile List<String> boxNames = List.of();
    private volatile Status status;
    private volatile Battery battery;
    private volatile int cameraFps = -1;
    private volatile int phoneBatteryPercent = -1;
    private volatile boolean phoneCharging;
    private volatile Attitude attitude;
    private volatile Altitude altitude;
    private volatile Gps gps;
    private volatile Timers timers;
    private volatile Network network;
    private volatile BatteryLimits batteryLimits;
    private volatile int rssiPercent = -1;
    private volatile long unknownCodes;
    /** When a record from the flight controller itself last arrived (not the phone's own DD_ records); 0 = never. */
    private volatile long lastFcDataAt;

    public void parse(DataReader b, FcInfo fc) {
        try {
            while (b.getRemaining() >= 2) {
                short code = b.readShort();
                if (fc == null && code < FcCommon.DD_TIMERS) return;
                if (!parseRecord(code, b, fc)) {
                    unknownCodes++;
                    return;
                }
                if (code < FcCommon.DD_TIMERS || code == FcCommon.DD_MAVLINK_ATTITUDE) lastFcDataAt = System.currentTimeMillis();
            }
        } catch (RuntimeException e) {
            // truncated packet: keep what was parsed
        }
    }

    private boolean parseRecord(short code, DataReader b, FcInfo fc) {
        boolean inav = fc != null && fc.getFcVariant() == FcInfo.FC_VARIANT_INAV;
        switch (code) {
            case FcCommon.MSP_ATTITUDE, FcCommon.DD_MAVLINK_ATTITUDE -> {
                double roll = b.readShort() / 10.0;  // decidegrees
                double pitch = b.readShort() / 10.0;
                int yaw = b.readShort();            // degrees
                attitude = new Attitude(roll, pitch, Math.floorMod(yaw, 360), System.currentTimeMillis());
            }
            case FcCommon.MSP_ALTITUDE -> {
                int cm = b.readInt();
                int vario = b.readShort(); // cm/s
                if (inav) b.readInt();
                altitude = new Altitude(cm / 100.0, vario / 100.0);
            }
            case FcCommon.MSP_ANALOG -> {
                int mah = b.readShort();
                rssiPercent = Math.round(b.readShort() * 100 / 1023f); // 0..1023
                int amps = b.readShort();
                if (!inav) {
                    int volts = b.readShort();
                    Battery old = battery;
                    battery = new Battery(volts / 100.0, old == null ? 0 : old.cells(), mah, amps / 100.0);
                }
            }
            case FcCommon.MSP_BOXIDS -> skip(b, b.readShort());
            case FcCommon.MSP_BOXNAMES -> {
                byte[] names = new byte[b.readShort()];
                b.read(names, 0, names.length);
                boxNames = Arrays.stream(new String(names, StandardCharsets.US_ASCII).split(";"))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
            }
            case FcCommon.MSP_BATTERY_CONFIG -> batteryLimits = new BatteryLimits(b.readShort() / 100.0, b.readShort() / 100.0, b.readShort() / 100.0);
            case FcCommon.MSP_OSD_CONFIG -> skipOsdConfig(b, inav);
            case FcCommon.MSP_VTX_CONFIG -> {
                int device = b.readShort();
                if (device != FcCommon.VTXDEV_UNKNOWN) skip(b, inav ? 6 : 8);
            }
            case FcCommon.MSP_BATTERY_STATE -> {
                int cells = b.readUnsignedByteAsInt();
                b.readShort(); // capacity
                int mah = b.readShort();
                int amps = b.readShort();
                b.readByte(); // batteryState
                int volts = b.readShort();
                battery = new Battery(volts / 100.0, cells, mah, amps / 100.0);
            }
            case FcCommon.MSP_STATUS -> {
                skip(b, 6); // cycleTime, i2cErrors, sensors
                if (inav) {
                    b.readByte();
                } else {
                    parseBetaflightStatus(b);
                }
            }
            case FcCommon.MSP_RAW_GPS -> {
                int fix = b.readUnsignedByteAsInt();
                int sats = b.readUnsignedByteAsInt();
                skip(b, 8); // lat, lon
                int altM = b.readShort();
                double kmh = b.readShort() * 0.036; // cm/s
                skip(b, 8); // course, hdop, traveled distance
                Gps old = gps;
                gps = new Gps(fix, sats, kmh, altM, old == null ? -1 : old.distanceHomeM(), old == null ? 0 : old.directionHomeDeg());
            }
            case FcCommon.MSP_COMP_GPS -> {
                int dist = b.readShort();
                int dir = b.readShort();
                b.readByte();
                Gps old = gps;
                gps = old == null ? new Gps(0, 0, 0, 0, dist, dir) : new Gps(old.fix(), old.sats(), old.speedKmh(), old.altM(), dist, dir);
            }
            case FcCommon.MSP_OSD_CANVAS -> skip(b, 2);
            case FcCommon.MSP2_INAV_STATUS -> {
                skip(b, 13);
                skip(b, b.readUnsignedByteAsInt() * 4);
            }
            case FcCommon.MSP2_INAV_ANALOG -> skip(b, 24);
            case FcCommon.DD_TIMERS -> timers = new Timers(b.readInt(), b.readInt(), b.readInt());
            case FcCommon.DD_PHONE_BATTERY_STATE -> {
                phoneBatteryPercent = b.readUnsignedByteAsInt();
                phoneCharging = b.readBoolean();
            }
            case FcCommon.DD_CAMERA_FPS -> cameraFps = b.readShort();
            case FcCommon.DD_VIDEO_BIT_RATE -> skip(b, 4);
            case FcCommon.DD_VIDEO_RECORDER_STATE -> skip(b, 5);
            case FcCommon.DD_VIDEO_RECORDER_START_STOP -> {
                // code only, no payload
            }
            case FcCommon.DD_NETWORK_STATE -> {
                NetworkState ns = new NetworkState(b.readUnsignedByteAsInt(), b.readUnsignedByteAsInt());
                network = new Network(ns.getNetworkName(), ns.getRssi());
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void parseBetaflightStatus(DataReader b) {
        b.readByte(); // pid profile
        int load = b.readShort();
        int n = b.readUnsignedByteAsInt();
        byte[] modeFlags = new byte[n];
        b.read(modeFlags, 0, n);
        int arming = b.readInt();
        b.readByte(); // configStateFlags
        b.readShort(); // core temperature
        List<String> names = boxNames;
        List<String> modes = new ArrayList<>();
        for (int bit = 0; bit < n * 8; bit++) {
            if ((modeFlags[bit / 8] >> (bit % 8) & 1) == 1) modes.add(bit < names.size() ? names.get(bit) : "mode " + bit);
        }
        List<Blocker> blockers = blockersFor(arming);
        status = new Status(modes.contains("ARM"), List.copyOf(modes), List.copyOf(blockers), load, System.currentTimeMillis());
    }

    /** Betaflight / INAV OSD config as the Flight app re-packs it (variable length). */
    private static void skipOsdConfig(DataReader b, boolean inav) {
        b.readShort(); // DroidDrone version code
        if (inav) {
            if (b.readByte() == 0) return; // no OSD driver
            skip(b, 13);
            skip(b, b.readShort() * 2);
            return;
        }
        skip(b, 5); // videoSystem, units, rssiAlarm, capacityWarning
        b.readShort(); // altAlarm
        int items = b.readShort();
        if (items <= 0) return;
        skip(b, items * 2);
        skip(b, b.readUnsignedByteAsInt());     // stats
        skip(b, b.readUnsignedByteAsInt() * 2); // timers
        skip(b, 1 + 4 + 1 + 1 + 1);             // warningsCount, enabledWarnings, profile, camera frame w/h
    }

    private static void skip(DataReader b, int bytes) {
        for (int i = 0; i < bytes; i++) b.readByte();
    }

    public boolean hasBoxNames() {
        return !boxNames.isEmpty();
    }

    /** null until the first MSP_STATUS arrives. */
    public Status getStatus() {
        return status;
    }

    public Battery getBattery() {
        return battery;
    }

    public int getCameraFps() {
        return cameraFps;
    }

    public Attitude getAttitude() {
        return attitude;
    }

    public Altitude getAltitude() {
        return altitude;
    }

    public Gps getGps() {
        return gps;
    }

    public Timers getTimers() {
        return timers;
    }

    public Network getNetwork() {
        return network;
    }

    public BatteryLimits getBatteryLimits() {
        return batteryLimits;
    }

    /** RC link quality as Betaflight reports it (0-100 %), -1 if unknown. With MSP RX this reflects the 4G link. */
    public int getRssiPercent() {
        return rssiPercent;
    }

    public boolean isPhoneCharging() {
        return phoneCharging;
    }

    public int getPhoneBatteryPercent() {
        return phoneBatteryPercent;
    }

    public long getUnknownCodes() {
        return unknownCodes;
    }

    /**
     * How long since the phone last forwarded anything from the flight controller. The Flight app reads attitude
     * 20 times a second while it talks to the FC, so a long silence means the phone has lost its USB link to it.
     */
    public long msSinceFcData(long now) {
        long last = lastFcDataAt;
        return last == 0 ? Long.MAX_VALUE : now - last;
    }
}
