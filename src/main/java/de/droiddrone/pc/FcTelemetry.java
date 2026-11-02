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

    /**
     * The FC's own view: armed or not, which modes its switches turned on, why arming is blocked, and the MCU's core
     * temperature (0 = not reported: Betaflight before 4.4 has no such field, and the Flight app sends 0 then).
     */
    public record Status(boolean armed, List<String> activeModes, List<Blocker> blockers, int cpuLoadPercent,
                         int coreTempC, long receivedAt) {
        public boolean coreTempKnown() {
            return coreTempC != 0;
        }
    }

    /**
     * fcCells is Betaflight's own count (it detects it when the battery is plugged in), 0 until it reports one - on USB
     * power, or before its first MSP_BATTERY_STATE. cells is the count to use: Betaflight's, unless that one is
     * impossible for the pack voltage (see {@link CellCount}); 0 while unknown. Never a guess from a voltage that is
     * still moving: that made a 6S look like 1S or 2S.
     */
    public record Battery(double volts, int cells, int mahDrawn, double amps, int fcCells) {
        public Battery(double volts, int cells, int mahDrawn, double amps) {
            this(volts, cells, mahDrawn, amps, cells);
        }

        public boolean cellCountKnown() {
            return cells > 0;
        }

        /** Betaflight counted wrong (and so warns at the wrong voltage); the count here is corrected. */
        public boolean fcCountWrong() {
            return fcCells > 0 && cells > 0 && cells != fcCells;
        }

        /** Per-cell voltage; NaN while the cell count isn't known. */
        public double cellVolts() {
            return cells > 0 ? volts / cells : Double.NaN;
        }
    }

    /**
     * What DRAUPNE Link's build of the Flight app measures on the phone (record {@link #PHONE_LINK_STATS}), the
     * worst of every report since last taken: longest gap between stick packets arriving from the PC and between
     * stick writes reaching the flight controller, slowest USB write, write timeouts and skipped requests.
     */
    public record PhoneLink(int rcInGapMs, int rcOutGapMs, int writeMaxMs, int writeTimeouts, int skipped) {
        PhoneLink merge(PhoneLink o) {
            return o == null ? this : new PhoneLink(Math.max(rcInGapMs, o.rcInGapMs), Math.max(rcOutGapMs, o.rcOutGapMs),
                    Math.max(writeMaxMs, o.writeMaxMs), writeTimeouts + o.writeTimeouts, skipped + o.skipped);
        }
    }

    /** Telemetry record of DRAUPNE Link's Flight app build (2.3.0-pc.3 on); the original app never sends it. */
    static final short PHONE_LINK_STATS = 0x40F0;
    /** The phone app's version, once a second (DRAUPNE Link's Flight app 2.3.0-pc.5 on). */
    static final short PHONE_APP_VERSION = 0x40F1;
    /** The phone's encoder: bitrate setting (bit/s) and intra refresh (DRAUPNE Flight 2.3.0-pc.13 on). */
    static final short PHONE_ENCODER = 0x40F2;
    /** Telemetry this long without a version: the phone app is older than 2.3.0-pc.5 (or upstream's own). */
    static final long PHONE_APP_SILENT_MS = 4000;

    /** roll/pitch in degrees (right / nose up positive as Betaflight reports), heading 0-359. */
    public record Attitude(double rollDeg, double pitchDeg, int headingDeg, long receivedAt) {}

    public record Altitude(double meters, double varioMetersPerSecond) {}

    /**
     * fix: 0 none, 1 2D, 2 3D (as Betaflight); distanceHomeM -1 if unknown. travelledM: what the Flight app adds up
     * while it sees the drone armed, -1 if unknown.
     */
    public record Gps(int fix, int sats, double speedKmh, int altM, int distanceHomeM, int directionHomeDeg,
                      double latDeg, double lonDeg, int travelledM, int courseDeg, double hdop, long receivedAt) {}

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
    /** The phone has read the mode IDs from the FC (and passed them on); see ControlLink. */
    private volatile boolean hasBoxIds;
    private volatile Status status;
    private volatile Battery battery;
    private final CellCount cellCount = new CellCount();
    private volatile int cameraFps = -1;
    /** The phone's encoder output, Mbit/s, as it measures it; -1 before the first report. */
    private volatile float phoneVideoMbps = -1;
    /** The phone's encoder bitrate setting, bit/s; -1 before the first report (older phone apps never send it). */
    private volatile int phoneTargetBitRate = -1;
    private volatile boolean phoneIntraRefresh;
    private volatile int phoneBatteryPercent = -1;
    private volatile boolean phoneCharging;
    private volatile Attitude attitude;
    private volatile Altitude altitude;
    private volatile Gps gps;
    private volatile Timers timers;
    /** Time armed, from Betaflight's own ARM flag: completed arms plus the one running (armedSince != 0). */
    private long flownMs;
    private long armedSince;
    private volatile Network network;
    private volatile BatteryLimits batteryLimits;
    private volatile int rssiPercent = -1;
    private volatile long unknownCodes;
    /** When a record from the flight controller itself last arrived (not the phone's own DD_ records); 0 = never. */
    private volatile long lastFcDataAt;
    private PhoneLink phoneLink;
    private volatile String phoneAppVersion;
    /** When the first record from the phone arrived; 0 = none yet. */
    private volatile long firstRecordAt;

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
                if (firstRecordAt == 0) firstRecordAt = System.currentTimeMillis();
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
                    battery = battery(volts / 100.0, old == null ? 0 : old.fcCells(), mah, amps / 100.0);
                }
            }
            case FcCommon.MSP_BOXIDS -> {
                skip(b, b.readShort());
                hasBoxIds = true;
            }
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
                battery = battery(volts / 100.0, cells, mah, amps / 100.0);
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
                double lat = b.readInt() / 1e7, lon = b.readInt() / 1e7;
                int altM = b.readShort();
                double kmh = b.readShort() * 0.036; // cm/s
                int course = Math.floorMod(b.readShort() / 10, 360); // decidegrees
                double hdop = b.readShort() / 100.0;
                int travelled = b.readInt(); // the Flight app's own count, in m
                Gps old = gps;
                gps = new Gps(fix, sats, kmh, altM, old == null ? -1 : old.distanceHomeM(), old == null ? 0 : old.directionHomeDeg(),
                        lat, lon, travelled, course, hdop, System.currentTimeMillis());
            }
            case FcCommon.MSP_COMP_GPS -> {
                int dist = b.readShort();
                int dir = b.readShort();
                b.readByte();
                Gps old = gps;
                gps = old == null ? new Gps(0, 0, 0, 0, dist, dir, 0, 0, -1, 0, 0, 0)
                        : new Gps(old.fix(), old.sats(), old.speedKmh(), old.altM(), dist, dir, old.latDeg(), old.lonDeg(), old.travelledM(),
                        old.courseDeg(), old.hdop(), old.receivedAt());
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
            case FcCommon.DD_VIDEO_BIT_RATE -> phoneVideoMbps = b.readFloat(); // what the phone's encoder puts out
            case FcCommon.DD_VIDEO_RECORDER_STATE -> skip(b, 5);
            case FcCommon.DD_VIDEO_RECORDER_START_STOP -> {
                // code only, no payload
            }
            case FcCommon.DD_NETWORK_STATE -> {
                NetworkState ns = new NetworkState(b.readUnsignedByteAsInt(), b.readUnsignedByteAsInt());
                network = new Network(ns.getNetworkName(), ns.getRssi());
            }
            case PHONE_LINK_STATS -> {
                PhoneLink p = new PhoneLink(b.readShort(), b.readShort(), b.readShort(), b.readShort(), b.readShort());
                synchronized (this) {
                    phoneLink = p.merge(phoneLink);
                }
            }
            case PHONE_APP_VERSION -> {
                byte[] name = new byte[b.readShort()];
                if (b.read(name, 0, name.length) != name.length) return false;
                phoneAppVersion = new String(name, StandardCharsets.UTF_8);
            }
            case PHONE_ENCODER -> {
                phoneTargetBitRate = b.readInt();
                phoneIntraRefresh = b.readByte() != 0;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** The phone app's version as it says, or null: not said yet, or an app too old to say (see phoneAppSilent). */
    public String getPhoneAppVersion() {
        return phoneAppVersion;
    }

    /** The phone has sent telemetry for a while but no version: its app is older than 2.3.0-pc.5. */
    public boolean phoneAppSilent(long nowMs) {
        long first = firstRecordAt;
        return phoneAppVersion == null && first != 0 && nowMs - first >= PHONE_APP_SILENT_MS;
    }

    private void parseBetaflightStatus(DataReader b) {
        b.readByte(); // pid profile
        int load = b.readShort();
        int n = b.readUnsignedByteAsInt();
        byte[] modeFlags = new byte[n];
        b.read(modeFlags, 0, n);
        int arming = b.readInt();
        b.readByte(); // configStateFlags
        int coreTemp = b.readShort();
        List<String> names = boxNames;
        List<String> modes = new ArrayList<>();
        for (int bit = 0; bit < n * 8; bit++) {
            if ((modeFlags[bit / 8] >> (bit % 8) & 1) == 1) modes.add(bit < names.size() ? names.get(bit) : "mode " + bit);
        }
        List<Blocker> blockers = blockersFor(arming);
        boolean armed = modes.contains("ARM");
        long now = System.currentTimeMillis();
        if (!names.isEmpty()) trackArmed(armed, now); // without the names, ARM can't be found among the bits
        status = new Status(armed, List.copyOf(modes), List.copyOf(blockers), load, coreTemp, now);
    }

    synchronized void trackArmed(boolean armed, long now) {
        if (armed && armedSince == 0) {
            armedSince = now;
        } else if (!armed && armedSince != 0) {
            flownMs += now - armedSince;
            armedSince = 0;
        }
    }

    /**
     * Seconds armed since connecting, like Betaflight's own OSD timer (total arm time), counted here from the ARM flag in
     * MSP_STATUS. The phone's DD_TIMERS can't be used for Betaflight: the Flight app finds ARM through MSP_BOXIDS,
     * which only the original Control app asks it for, so its fly time stays 0. Falls back to it for INAV.
     * -1 while nothing is known.
     */
    public synchronized int flySeconds(long now) {
        if (flownMs > 0 || armedSince != 0 || status != null && hasBoxNames()) {
            return (int) ((flownMs + (armedSince != 0 ? now - armedSince : 0)) / 1000);
        }
        Timers t = timers;
        return t == null ? -1 : t.flySeconds();
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

    /**
     * Betaflight's flight modes, highest priority first, as box names (MSP_BOXNAMES, spaces removed: 4.5 calls the hold
     * modes "POS HOLD" / "ALT HOLD", 2025.12 "POSHOLD" / "ALTHOLD") and the name shown. The order is Betaflight's OSD
     * flight mode element (osdElementFlymode): failsafe, rescue, the hold modes, headfree, the self-levelling modes,
     * acro trainer, chirp, then air mode. Turtle (crash flip) comes after the emergencies: its switch takes over the
     * motors. Fixed wing PASSTHRU last. ACRO is no switch of its own - it is what you fly when none of these is on.
     * Air mode switched on permanently (feature AIRMODE) has no box, so it shows as ACRO.
     */
    static final String[][] FLIGHT_MODES = {
            {"FAILSAFE", "FAILSAFE"},
            {"GPSRESCUE", "GPS RESCUE"},
            {"FLIPOVERAFTERCRASH", "TURTLE"},
            {"POSHOLD", "POS HOLD"},
            {"ALTHOLD", "ALT HOLD"},
            {"HEADFREE", "HEADFREE"},
            {"ANGLE", "ANGLE"},
            {"HORIZON", "HORIZON"},
            {"ACROTRAINER", "ACRO TRAINER"},
            {"CHIRP", "CHIRP"},
            {"PASSTHRU", "PASSTHRU"},
            {"AIRMODE", "AIR"},
    };

    private static String key(String boxName) {
        return boxName.toUpperCase(java.util.Locale.ROOT).replace(" ", "");
    }

    /** The FLIGHT_MODES row of the active mode that wins, or null: plain ACRO. */
    private static String[] activeFlightMode(List<String> activeModes) {
        java.util.Set<String> on = new java.util.HashSet<>();
        for (String m : activeModes) on.add(key(m));
        for (String[] m : FLIGHT_MODES) {
            if (on.contains(m[0])) return m;
        }
        return null;
    }

    /** The flight mode as Betaflight's own OSD shows it, from the active mode switches (box names); see FLIGHT_MODES. */
    public static String flightMode(List<String> activeModes) {
        String[] m = activeFlightMode(activeModes);
        return m == null ? "ACRO" : m[1];
    }

    /** The flight mode is an emergency: the pilot isn't flying (failsafe, GPS rescue). */
    public static boolean isEmergencyMode(String flightMode) {
        return flightMode.equals("FAILSAFE") || flightMode.equals("GPS RESCUE");
    }

    /** The active modes other than ARM and the flight mode itself (BEEPER, AIR MODE under ANGLE, ...), as Betaflight names them. */
    public static List<String> otherModes(List<String> activeModes) {
        String[] m = activeFlightMode(activeModes);
        String flight = m == null ? "" : m[0];
        return activeModes.stream().filter(n -> {
            String k = key(n);
            return !k.equals("ARM") && !k.equals(flight);
        }).toList();
    }

    /** The phone's measurements since the last call (worst of them), or null - always null with the original app. */
    public synchronized PhoneLink takePhoneLink() {
        PhoneLink p = phoneLink;
        phoneLink = null;
        return p;
    }

    public boolean hasBoxNames() {
        return !boxNames.isEmpty();
    }

    public boolean hasBoxIds() {
        return hasBoxIds;
    }

    void forgetBoxIds() {
        hasBoxIds = false;
    }

    /** null until the first MSP_STATUS arrives. */
    public Status getStatus() {
        return status;
    }

    private Battery battery(double volts, int fcCells, int mah, double amps) {
        BatteryLimits lim = batteryLimits;
        double maxCell = lim != null && lim.maxCell() > 0 ? lim.maxCell() : 4.3;
        return new Battery(volts, cellCount.resolve(volts, fcCells, maxCell, System.currentTimeMillis()), mah, amps, fcCells);
    }

    public Battery getBattery() {
        return battery;
    }

    public int getCameraFps() {
        return cameraFps;
    }

    /** The phone encoder's bitrate setting in bit/s, or -1 when not reported (phone app older than 2.3.0-pc.13). */
    public int getPhoneTargetBitRate() {
        return phoneTargetBitRate;
    }

    /** The phone's encoder refreshes a strip of the picture each frame instead of sending key frames often. */
    public boolean isPhoneIntraRefresh() {
        return phoneIntraRefresh;
    }

    /** The phone's encoder output in Mbit/s (its own measurement), or -1 when not reported yet. */
    public float getPhoneVideoMbps() {
        return phoneVideoMbps;
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
