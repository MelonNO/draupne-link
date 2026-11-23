package de.droiddrone.bench.hw;

import de.droiddrone.common.MspFrames;
import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.EscFirmware;
import de.droiddrone.pc.setup.FakeEscBoard;
import de.droiddrone.pc.setup.MotorTest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A made-up Betaflight flight controller on USB, at byte level, behind {@link BetaflightUsb}'s door
 * ({@code BetaflightUsb.use(board.usb())}): MSP v1 and v2, the CLI, and the ESCs behind the passthrough (those are
 * {@link FakeEscBoard}'s). Every tool the app has can change it, and what it then holds can be read back and compared.
 * No real port is ever opened.
 * <p>
 * <b>Like the real thing:</b> settings live twice - as saved and as they are in memory. MSP writes and CLI commands
 * change the memory only; the CLI's {@code save} and MSP_EEPROM_WRITE keep them; every restart ({@code save},
 * {@code exit}, MSP_REBOOT, power lost) starts again from what was saved and ends the USB connection - the port is
 * away for a moment ({@link #restartLooks}) and must be opened anew. While the CLI is open no MSP is answered. An
 * armed board does not open its CLI, ignores MSP_SET_MOTOR and refuses MSP_EEPROM_WRITE. Outside the CLI a '#' opens
 * it and an 'R' sends the board into its bootloader ({@link #inBootloader}): CLI text must only go to an open CLI.
 * <p>
 * <b>The CLI</b> (Betaflight 4.4's words and formats): version, board_name, manufacturer_id, mcu_id, signature, get,
 * set, aux, feature, serial, timer, beeper, beacon, map, mixer, profile, rateprofile, batch, defaults [nosave] (and
 * its built-in config: {@link #builtInConfigNotTaken}), diff [all], dump [all], save, exit. Anything else: ###ERROR;
 * inside a batch an error keeps "batch end" and "save" from being taken. The settings are a made-up table of two
 * dozen with Betaflight's names ({@link #SETTINGS}), two PID profiles and two rate profiles (the real one has four
 * each: fewer here keeps a replay short).
 * <p>
 * <b>Dump and diff</b> have the real ones' shape where a replay meets it: "# version", the version, "# config: YES",
 * "batch start", ("defaults nosave" in "diff all" only,) board_name, manufacturer_id, mcu_id, signature, the parts
 * each under its "# heading", "save" last. A dump writes every value out - every feature switched off and then those
 * that are on switched on again - but of the timers only the pins that have one, each with a "# pin ..." line under
 * it that is no command. A diff has what differs from Betaflight's own defaults <i>at this start</i>, and those are
 * not the same at every start ({@link #flipDefaultsAtNextStart}).
 * <p>
 * <b>MSP:</b> API / variant / version / build / board info, UID, status (armed flag, sensors), feature mask get/set,
 * mixer get/set (props direction), rx map, mode ranges, box ids, advanced config (motor protocol), motor config,
 * MSP_MOTOR / MSP_SET_MOTOR, analog, battery state, beeper config get/set, MSP_SET_RAW_RC, passthrough, EEPROM
 * write, reboot; v2: motor output order get/set, DShot commands. Anything else gets Betaflight's error answer.
 * <p>
 * <b>The arming lock</b> (the flight controller keeper's, R3): MSP_ARMING_DISABLE (99) with a byte that is not 0 sets
 * the arming block "MSP" - no arming while it holds, an armed board is disarmed at once - and with 0 takes it off;
 * MSP_STATUS shows it among the arming-disable flags (bit 16), with "ARM switch" (bit 25) beside it for as long as the
 * ARM switch that was on under the block has not gone off once: taking the block off does not arm a board whose
 * switch was held on. It is in memory only: every restart clears it. A request framed as MSP v2 whose number is one
 * of v1's is answered as v1's, in a v2 frame - the phone app asks everything so.
 * <p>
 * <b>Faked loosely</b> (enough for the tools, not Betaflight's own rules): arming by sticks - armed while the ARM
 * range (and PREARM's, if set) holds in the last MSP_SET_RAW_RC, the receiver is MSP and the throttle was low when
 * it went on; no timing and <b>no RX loss: sticks that stop change nothing</b> - an armed made-up board stays armed
 * for good, where Betaflight's failsafe would disarm or land it after 1.5 s - unless a test tells it to notice
 * ({@link #modelsRxLoss}: RXLOSS, rxfail, failsafe_delay and failsafe_procedure, each loosely; what its GPS rescue does
 * to the motors is made up, {@link #rescueMotors}) - and of the other arming blocks only RPMFILTER (dshot_bidir on and
 * no ESC answering: the battery out, or {@link #escTelemetry} off); a throttle that was up when the switch went on does
 * not ask for the switch again. Armed motors read one fixed value. "rxfail" is a command here and in no dump or diff. A DShot "spin
 * direction + save" changes the direction byte of a BLHeli_S-kind ESC's settings and nothing else. The sensors, the
 * battery and the mixer's motor count (always 4) are what the test sets. Dumps are short: four timer pins stand for
 * all of resources, timers and dma; no LEDs, OSD, VTX.
 * <p>
 * <b>For a test:</b> {@link #setUp} and {@link #flown} (the board as someone left it), {@link #features}, {@link #arm},
 * {@link #battery}, {@link #fitEscs}, {@link #uid}, {@link #version}, {@link #cutWire} and the cut counters,
 * {@link #refuseCli}, {@link #cutShortListings}, {@link #flipDefaultsAtNextStart}, {@link #featuresNotBuilt},
 * {@link #unplug}, {@link #fly}, {@link #ignoreArmingDisable}, {@link #keepsMotorValuesWhileArmed},
 * {@link #armingLocked}; and what happened: {@link #cli()}, {@link #mspRequests()},
 * {@link #dshotCommands()}, {@link #saves}, {@link #restarts}, {@link #saved()}, {@link #inMemory()}, {@link #motors()}.
 * Thread-safe: one lock for the board.
 */
public final class FakeFlightBoard {
    public static final int MOTORS = 4, AUX_SLOTS = 20, PROFILES = 2, RATE_PROFILES = 2;
    /** The channels "rxfail" knows here: the four sticks and AUX1 .. 4 (Betaflight: eighteen). */
    public static final int RXFAIL_CHANNELS = 8;
    /** What an armed motor reads (idle). */
    public static final int RUNNING = 1060;
    /** For the tools' waits on a made-up board: it is back at once, so look often and give up soon. */
    public static final BoardPort.Timing NO_WAITING = new BoardPort.Timing(5, 5000);
    private static final int MAX_AUX_CHANNELS = 14;

    private static final String[] FEATURES = {"RX_PPM", "", "INFLIGHT_ACC_CAL", "RX_SERIAL", "MOTOR_STOP", "SERVO_TILT",
            "SOFTSERIAL", "GPS", "", "RANGEFINDER", "TELEMETRY", "", "3D", "RX_PARALLEL_PWM", "RX_MSP", "RSSI_ADC", "LED_STRIP",
            "DISPLAY", "OSD", "", "CHANNEL_FORWARDING", "TRANSPONDER", "AIRMODE", "", "", "RX_SPI", "", "ESC_SENSOR", "ANTI_GRAVITY"};
    /** A stock board: a serial receiver, telemetry, OSD, air mode, anti gravity. */
    public static final int DEFAULT_FEATURES = BetaflightPlan.FEATURE_RX_SERIAL | 1 << 10 | 1 << 18 | 1 << 22 | 1 << 28;
    /** Betaflight 4.4's beeper conditions in its table's order: a condition's off flag is bit (index). ALL is last. */
    private static final String[] BEEPERS = {"GYRO_CALIBRATED", "RX_LOST", "RX_LOST_LANDING", "DISARMING", "ARMING",
            "ARMING_GPS_FIX", "BAT_CRIT_LOW", "BAT_LOW", "GPS_STATUS", "RX_SET", "ACC_CALIBRATION", "ACC_CALIBRATION_FAIL",
            "READY_BEEP", "MULTI_BEEPS", "DISARM_REPEAT", "ARMED", "SYSTEM_INIT", "ON_USB", "BLACKBOX_ERASE", "CRASH_FLIP",
            "CAM_CONNECTION_OPEN", "CAM_CONNECTION_CLOSE", "RC_SMOOTHING_INIT_FAIL", "ARMING_GPS_NO_FIX", "ALL"};
    private static final int ALL_BEEPERS = (1 << BEEPERS.length - 1) - 1;
    /** The DShot beacon knows two conditions, both off as shipped. */
    private static final int BEACONS = 1 << 1 | 1 << 9;
    private static final String[] MIXERS = {"", "TRI", "QUADP", "QUADX", "BI", "GIMBAL", "Y6", "HEX6", "FLYING_WING", "Y4", "HEX6X"};
    private static final String[] PROTOCOLS = {"PWM", "ONESHOT125", "ONESHOT42", "MULTISHOT", "BRUSHED", "DSHOT150", "DSHOT300",
            "DSHOT600", "PROSHOT1000", "DISABLED"};
    private static final String DEFAULT_MAP = "AETR1234", MAP_LETTERS = "AERT1234";
    private static final int[] FREE_SLOT = {0, 0, 900, 900, 0, 0};

    private enum Kind { NUMBER, CHOICE, ARRAY, TEXT }

    private enum Section { MASTER, PROFILE, RATE }

    /**
     * One CLI setting: where it lives, what it takes, what it is on a fresh board - and, for the few Betaflight gives
     * other defaults when it takes the ESCs for brushed ones at a start, what it is on a fresh board then.
     */
    private record Setting(String name, Section section, Kind kind, String fresh, String brushedFresh, List<String> choices, int min, int max,
                           int count) {
        static Setting number(String name, Section section, int fresh, int min, int max) {
            return new Setting(name, section, Kind.NUMBER, Integer.toString(fresh), null, List.of(), min, max, 0);
        }

        static Setting choice(String name, String fresh, String... choices) {
            return new Setting(name, Section.MASTER, Kind.CHOICE, fresh, null, List.of(choices), 0, 0, 0);
        }

        static Setting array(String name, String fresh, int count, int min, int max) {
            return new Setting(name, Section.MASTER, Kind.ARRAY, fresh, null, List.of(), min, max, count);
        }

        /** The same setting, with this as its default at a start that took the ESCs for brushed ones. */
        Setting brushed(String otherFresh) {
            return new Setting(name, section, kind, fresh, otherFresh, choices, min, max, count);
        }

        String fresh(boolean brushed) {
            return brushed && brushedFresh != null ? brushedFresh : fresh;
        }

        /** The value as Betaflight keeps and prints it, or null when it does not take it. */
        String taken(String typed) {
            String v = typed.strip();
            try {
                switch (kind) {
                    case NUMBER -> {
                        int n = Integer.parseInt(v);
                        return n < min || n > max ? null : Integer.toString(n);
                    }
                    case CHOICE -> {
                        for (String c : choices) if (c.equalsIgnoreCase(v)) return c;
                        return null;
                    }
                    case ARRAY -> {
                        String[] parts = v.split("\\s*,\\s*");
                        if (parts.length != count) return null;
                        List<String> out = new ArrayList<>();
                        for (String p : parts) {
                            int n = Integer.parseInt(p);
                            if (n < min || n > max) return null;
                            out.add(Integer.toString(n));
                        }
                        return String.join(",", out);
                    }
                    default -> {
                        if (v.equals("-")) return "";
                        return v.isEmpty() || v.length() > max || v.contains(" ") ? null : v;
                    }
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }

        String allowed() {
            return switch (kind) {
                case NUMBER -> "Allowed range: " + min + " - " + max;
                case CHOICE -> "Allowed values: " + String.join(", ", choices);
                case ARRAY -> "Array length: " + count;
                default -> "Allowed length: 1 - " + max;
            };
        }
    }

    /** The made-up board's settings, in the order a dump prints them. The stats_ ones count by themselves ({@link #fly}). */
    private static final List<Setting> SETTINGS = List.of(
            Setting.array("mag_calibration", "0,0,0", 3, -32768, 32767),
            Setting.choice("mag_hardware", "AUTO", "AUTO", "NONE", "HMC5883", "AK8975", "AK8963", "QMC5883", "LIS3MDL", "IST8310"),
            Setting.number("min_check", Section.MASTER, 1050, 750, 1250),
            Setting.number("serial_update_rate_hz", Section.MASTER, 100, 100, 2000),
            Setting.number("min_throttle", Section.MASTER, 1070, 1000, 2000).brushed("1000"),
            Setting.choice("use_unsynced_pwm", "OFF", "OFF", "ON").brushed("ON"),
            Setting.choice("motor_pwm_protocol", "DSHOT600", PROTOCOLS),
            Setting.choice("dshot_bidir", "OFF", "OFF", "ON"),
            Setting.array("motor_output_reordering", "0,1,2,3,4,5,6,7", 8, 0, 7),
            Setting.number("failsafe_delay", Section.MASTER, 15, 1, 200),
            Setting.number("failsafe_off_delay", Section.MASTER, 10, 0, 200),
            Setting.number("failsafe_throttle", Section.MASTER, 1000, 885, 2000),
            Setting.choice("failsafe_switch_mode", "STAGE1", "STAGE1", "KILL", "STAGE2"),
            Setting.number("failsafe_throttle_low_delay", Section.MASTER, 100, 0, 300),
            Setting.choice("failsafe_procedure", "DROP", "AUTO-LANDING", "DROP", "GPS-RESCUE"),
            Setting.number("vbat_min_cell_voltage", Section.MASTER, 330, 100, 500),
            Setting.number("vbat_warning_cell_voltage", Section.MASTER, 350, 100, 500),
            Setting.number("force_battery_cell_count", Section.MASTER, 0, 0, 24),
            Setting.number("beeper_dshot_beacon_tone", Section.MASTER, 1, 1, 5),
            Setting.choice("yaw_motors_reversed", "OFF", "OFF", "ON"),
            Setting.number("small_angle", Section.MASTER, 25, 0, 180),
            Setting.choice("gps_auto_baud", "OFF", "OFF", "ON"),
            Setting.choice("gps_rescue_allow_arming_without_fix", "OFF", "OFF", "ON"),
            new Setting("craft_name", Section.MASTER, Kind.TEXT, "", null, List.of(), 0, 16, 0),
            Setting.number("stats_total_flights", Section.MASTER, 0, 0, Integer.MAX_VALUE),
            Setting.number("stats_total_time_s", Section.MASTER, 0, 0, Integer.MAX_VALUE),
            Setting.number("p_roll", Section.PROFILE, 45, 0, 250),
            Setting.number("anti_gravity_gain", Section.PROFILE, 80, 0, 250),
            Setting.number("motor_output_limit", Section.PROFILE, 100, 1, 100),
            Setting.number("thr_mid", Section.RATE, 50, 0, 100),
            Setting.number("roll_expo", Section.RATE, 0, 0, 100));

    /** Everything Betaflight keeps: once as saved, once as it is in memory. */
    private static final class Config {
        final Map<String, String> master = new LinkedHashMap<>();
        final List<Map<String, String>> profiles = new ArrayList<>(), rates = new ArrayList<>();
        int profile, rate, features = DEFAULT_FEATURES, mixer = MotorTest.MIXER_QUADX, beeperOff, beaconOff = BEACONS;
        String map = DEFAULT_MAP;
        final int[][] aux = new int[AUX_SLOTS][];
        /** Port id (Betaflight 4.4's numbers: UART1 = 0, USB VCP = 20) to "functions msp gps telemetry blackbox". */
        final Map<Integer, String> serial = new LinkedHashMap<>();
        /** rxfail: what each channel (roll, pitch, yaw, throttle, AUX1 ..) is given while the receiver is lost - "a", "h" or "s 1500". */
        final String[] rxfail = new String[RXFAIL_CHANNELS];
        /**
         * Pin to its timer ("B00" to "AF2"). A dump lists the pins that have one and says nothing of a pin that has
         * none - so a default taken off a pin is in a "diff" ("timer A02 NONE") and missing from a "dump".
         */
        final Map<String, String> timers = new LinkedHashMap<>();

        /** A fresh board's settings: Betaflight's defaults as they are at a start that took the ESCs for brushed ones, or not. */
        Config(boolean brushed) {
            for (int i = 0; i < PROFILES; i++) profiles.add(new LinkedHashMap<>());
            for (int i = 0; i < RATE_PROFILES; i++) rates.add(new LinkedHashMap<>());
            for (Setting s : SETTINGS) {
                switch (s.section()) {
                    case MASTER -> master.put(s.name(), s.fresh(brushed));
                    case PROFILE -> profiles.forEach(p -> p.put(s.name(), s.fresh(brushed)));
                    default -> rates.forEach(r -> r.put(s.name(), s.fresh(brushed)));
                }
            }
            for (int i = 0; i < AUX_SLOTS; i++) aux[i] = FREE_SLOT.clone();
            for (int i = 0; i < RXFAIL_CHANNELS; i++) rxfail[i] = i < 4 ? "a" : "h"; // Betaflight's own: the sticks auto, the switches held
            serial.put(20, "1 115200 57600 0 115200"); // USB: MSP
            serial.put(0, "64 115200 57600 0 115200"); // UART1: the serial receiver
            for (int id : new int[]{1, 2, 5}) serial.put(id, "0 115200 57600 0 115200");
            for (String pin : List.of("B00", "B01")) timers.put(pin, "AF2"); // the four motor pins
            for (String pin : List.of("A03", "A02")) timers.put(pin, "AF3");
        }

        Config copy() {
            Config c = new Config(false);
            c.master.putAll(master);
            for (int i = 0; i < PROFILES; i++) c.profiles.get(i).putAll(profiles.get(i));
            for (int i = 0; i < RATE_PROFILES; i++) c.rates.get(i).putAll(rates.get(i));
            c.profile = profile;
            c.rate = rate;
            c.features = features;
            c.mixer = mixer;
            c.beeperOff = beeperOff;
            c.beaconOff = beaconOff;
            c.map = map;
            for (int i = 0; i < AUX_SLOTS; i++) c.aux[i] = aux[i].clone();
            System.arraycopy(rxfail, 0, c.rxfail, 0, RXFAIL_CHANNELS);
            c.serial.clear();
            c.serial.putAll(serial);
            c.timers.clear();
            c.timers.putAll(timers);
            return c;
        }

        /** Where a setting's value is right now: the master, or the selected profile. */
        Map<String, String> home(Setting s) {
            return switch (s.section()) {
                case MASTER -> master;
                case PROFILE -> profiles.get(profile);
                default -> rates.get(rate);
            };
        }
    }

    // ------------------------------------------------------------------ what the board is (a test may change it)

    /** MSP_UID's twelve bytes; the CLI's mcu_id is the same three words. Made up. */
    public volatile byte[] uid = {0x4d, 0x41, 0x44, 0x45, 0x2d, 0x55, 0x50, 0, 0, 0, 0, 1};
    /** MSP_FC_VARIANT. */
    public volatile String variant = "BTFL";
    /** MSP_FC_VERSION and the version in the CLI's line: another firmware when a test changes it. */
    public volatile int[] version = {4, 4, 1};
    /** The build in the CLI's version line only (MSP's build info stays): another build of the same version. */
    public volatile String build = "Apr 11 2023 / 01:20:48 (madeup000)";
    public volatile String boardName = "MADEUPF405", manufacturerId = "MDUP";
    /** The permanent mode ids this build knows ("aux" takes only these), and those it offers (MSP_BOXIDS). */
    public final Set<Integer> knownModes = new TreeSet<>(Set.of(0, 1, 2, 4, 5, 6, 7, 12, 13, 19, 20, 26, 27, 28, 35, 36, 46));
    public final Set<Integer> offeredModes = new TreeSet<>(Set.of(0, 1, 2, 13, 26, 27, 28, 35, 36, 46));
    /** False: the CLI has no name for RX_MSP, as Betaflight 2025.12 - the bit is there over MSP only. */
    public volatile boolean cliNamesRxMsp = true;
    /**
     * Features this build was made without ("GPS", "RANGEFINDER"): the CLI knows their names, a dump lists them
     * switched off, and "feature X" or "feature -X" is answered "Unavailable" (as BetaflightPlan.refused knows it).
     */
    public final Set<String> featuresNotBuilt = new TreeSet<>();
    /**
     * Lines of the board's built-in config ("# config: YES": Betaflight's custom defaults for this board) that this
     * build does not have, such as "set camera_control_mode = HARDWARE_PWM" on a build without that part. Betaflight
     * 4.4.1 runs that config again at every reset to its defaults (cli.c): out loud in "defaults" - each such line's
     * ###ERROR after its "# resetting to defaults" line - and quietly in "diff" and "dump"; inside a batch each one
     * marks the batch as failed either way, so "batch end" and "save" in it answer ###ERROR. Empty (as R1 made this
     * board): a config this build takes whole.
     */
    public final List<String> builtInConfigNotTaken = new ArrayList<>();
    /** MSP_STATUS' sensors: a GPS that talks, a compass. */
    public volatile boolean gps, mag;
    /** A restart takes the port away: that many looks for it (the port list, an open) find none. */
    public volatile int restartLooks = 2;
    /**
     * The next start (a restart, or the power back) takes the ESCs for the other kind - brushed, then brushless
     * again - as Betaflight 4.4.1 was seen to do on a real board from one start to the next: its own defaults for
     * min_throttle (1070 / 1000) and use_unsynced_pwm (OFF / ON) are the other pair from then on. What is saved does
     * not change, so "dump" prints the same and "diff" does not.
     */
    public volatile boolean flipDefaultsAtNextStart;

    // ------------------------------------------------------------------ faults a test asks for

    /** That many more CLI commands are run; the next cuts the wire before it is run. -1: never. */
    public volatile int cutAfterCliCommands = -1;
    /** That many more MSP requests are answered; the next cuts the wire unanswered. -1: never. */
    public volatile int cutAfterMspRequests = -1;
    /**
     * A cut takes the board's power too (a board on USB power alone): it starts again from what was saved. False: a
     * battery keeps it running as it was - still in the CLI, if it was there, with what was changed but not saved.
     */
    public volatile boolean cutTakesPower = true;
    /** That many "diff" / "dump" listings come back cut short at a heading, as if its "# " had been the prompt. */
    public volatile int cutShortListings;
    /**
     * MSP_ARMING_DISABLE is answered and does nothing: a firmware on which the keeper's lock is not what it is taken
     * for. The keeper has to notice (the block never shows in MSP_STATUS) and open no window.
     */
    public volatile boolean ignoreArmingDisable;
    /**
     * MSP_SET_MOTOR on an armed board is kept and comes out the moment the board is disarmed - what Betaflight's own
     * code does with it (motor_disarmed), as far as it was read; false (as R1 made this board): an armed board drops
     * such a value and is at rest once disarmed. A tool that makes a board safe has to hold in both.
     */
    public volatile boolean keepsMotorValuesWhileArmed;
    // >>> R3, second round: sticks that stop
    /**
     * The board notices that its sticks have stopped, as Betaflight does with MSP as its receiver: 100 ms after the last
     * MSP_SET_RAW_RC (and before the first) its status says RXLOSS (arming-disable flag 2) and rxfail has its channels
     * (h: held as they were, s: set to a value, a: sticks centred and throttle down); an armed board goes on to stage 2
     * after failsafe_delay - FAILSAFE in its status (arming-disable flag 1 and the mode box FAILSAFE) - and does what
     * failsafe_procedure says: DROP disarms, AUTO-LANDING runs its motors at failsafe_throttle for failsafe_off_delay
     * and then disarms, GPS-RESCUE stays armed with its motors at {@link #rescueMotors}. False, as this board was:
     * sticks that stop change nothing.
     */
    public volatile boolean modelsRxLoss;
    /** With {@link #modelsRxLoss}: so long after the last stick frame the receiver counts as lost (Betaflight: 100 ms). */
    public volatile long rxLossMs = 100;
    /**
     * What an armed board's motors read in a GPS rescue. Made up - what Betaflight really does in a rescue with no GPS
     * fix is not known here (it may disarm at once): this is the worst of it, gps_rescue_throttle_max.
     */
    public volatile int rescueMotors = 1700;
    /** With bidirectional DShot on, the ESCs answer the flight controller; false: they do not (as with the battery out), and RPMFILTER blocks arming. */
    public volatile boolean escTelemetry = true;
    /** What MSP_FAILSAFE_CONFIG answers instead of the board's own settings: a firmware whose answer is laid out otherwise. Null: its own. */
    public volatile byte[] mspFailsafeConfig;
    private int stage2Count;
    // <<< R3, second round
    private final List<String> refused = new ArrayList<>();

    // ------------------------------------------------------------------ what happened

    public volatile int saves, restarts, opens;
    private final List<String> cliSeen = new ArrayList<>();
    private final List<Integer> mspSeen = new ArrayList<>();
    private final List<String> dshotSeen = new ArrayList<>();

    // ------------------------------------------------------------------ the board's state

    private final BetaflightUsb.Port port;
    private Config saved = new Config(false), ram = new Config(false);
    /** Betaflight's own defaults at this start: what "diff" compares with and "defaults" goes back to. */
    private Config fresh = new Config(false);
    private boolean brushed;
    private final int[] motors = new int[MOTORS];
    private int[] rc;
    private boolean cli, batch, batchError, forcedArmed, stickArmed, unplugged, bootloader;
    /** The arming block "MSP" (MSP_ARMING_DISABLE), and "ARM switch": the switch was on under a block and has not been off since. */
    private boolean armingDisabledMsp, armSwitchHeld;
    /** With {@link #keepsMotorValuesWhileArmed}: what MSP_SET_MOTOR said while armed. */
    private int[] heldMotors;
    /** With {@link #modelsRxLoss}: when the last sticks came (nanoTime; 0: none yet), and how far the failsafe has gone since. */
    private long lastSticksNs, stage2SinceNs;
    private boolean rxfailApplied, inStage2, rescue, landing;
    private final StringBuilder cliLine = new StringBuilder();
    private double volts = 0.3;
    private int cells, away;
    private FakeEscBoard escs = new FakeEscBoard();
    private final int[] escDirection = new int[MOTORS];
    private Link current;

    public FakeFlightBoard() {
        this("MADEUP1");
    }

    /** A board on a made-up port of this name, four BLHeli_S-kind (Bluejay) ESCs behind it, no battery in. */
    public FakeFlightBoard(String portName) {
        port = new BetaflightUsb.Port(portName, "Made-up Betaflight board");
        Arrays.fill(motors, MotorTest.STOP);
        for (int i = 0; i < MOTORS; i++) escs.silabs("Bluejay", 0, 19, "#S_H_50#", 4, 1);
    }

    /**
     * A board somebody set up and flew: a name, a GPS on UART2, ARM and ANGLE on switches, a quieter beeper, a
     * calibrated compass, settings of its own in the second PID and rate profile - so its "diff all" has a line of
     * every kind, and a restore has something to put back.
     */
    public static FakeFlightBoard flown() {
        return new FakeFlightBoard().setUp("set craft_name = Bench", "set small_angle = 40", "set serial_update_rate_hz = 1000",
                "set mag_calibration = 12,-34,56", "feature GPS", "serial 1 2 115200 57600 0 115200", "beeper -GYRO_CALIBRATED",
                "aux 0 0 2 1700 2100 0 0", "aux 1 1 1 900 1300 0 0", "profile 1", "set p_roll = 60", "profile 0",
                "rateprofile 1", "set thr_mid = 40", "rateprofile 0");
    }

    // ------------------------------------------------------------------ the door

    public BetaflightUsb.Port port() {
        return port;
    }

    /**
     * This PC's USB with this board on it and nothing else, for {@code BetaflightUsb.use}: the port is not in the
     * list while the board restarts or is unplugged, and opens once at a time, as a real COM port.
     */
    public BetaflightUsb.Usb usb() {
        return new BetaflightUsb.Usb() {
            @Override
            public List<BetaflightUsb.Port> ports() {
                return there() ? List.of(port) : List.of();
            }

            @Override
            public BetaflightUsb.Wire open(BetaflightUsb.Port p) throws IOException {
                synchronized (FakeFlightBoard.this) {
                    if (!p.systemName().equals(port.systemName()) || !there()) {
                        throw new IOException(p.systemName() + " is gone - plug the flight controller in again.");
                    }
                    if (current != null && current.isOpen()) throw new IOException(p.systemName() + " is in use - close Betaflight Configurator and retry.");
                    return connect();
                }
            }
        };
    }

    /**
     * One USB connection to the board, for a USB stand-in that takes suppliers (the bench's FakeUsb:
     * {@code usb.plug(port, description, board::connect)}). A board that is away gives a wire that is dead already.
     */
    public synchronized BetaflightUsb.Wire connect() {
        opens++;
        Link link = new Link();
        if (unplugged || bootloader || away > 0) {
            if (away > 0) away--;
            link.alive = false;
        } else {
            if (current != null) current.alive = false; // one connection: an older one is gone
            current = link;
        }
        return link;
    }

    private synchronized boolean there() {
        if (unplugged || bootloader) return false;
        if (away > 0) {
            away--;
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ for a test: the board as it is to be

    /**
     * The board as someone left it: these CLI lines, saved ("set small_angle = 40", "aux 0 0 0 1700 2100 0 0",
     * "feature GPS", "profile 1" ...). No wire, no restart counted.
     */
    public synchronized FakeFlightBoard setUp(String... lines) {
        for (String line : lines) {
            String out = configCommand(line.strip());
            if (BetaflightPlan.refused(out)) throw new IllegalArgumentException(line + " -> " + out);
        }
        batch = batchError = false;
        saved = ram.copy();
        return this;
    }

    /** The feature mask, saved - also a bit the CLI has no name for. */
    public synchronized FakeFlightBoard features(int mask) {
        ram.features = mask;
        saved.features = mask;
        return this;
    }

    /** Armed, whatever the sticks say - until {@link #disarm} or a restart. */
    public synchronized void arm() {
        forcedArmed = true;
    }

    public synchronized void disarm() {
        forcedArmed = stickArmed = false;
        rc = null;
        atDisarm();
    }

    /** Disarmed just now: the motors at rest - or at what MSP_SET_MOTOR said meanwhile ({@link #keepsMotorValuesWhileArmed}). */
    private void atDisarm() {
        rescue = landing = false;
        Arrays.fill(motors, MotorTest.STOP);
        if (heldMotors != null) System.arraycopy(heldMotors, 0, motors, 0, MOTORS);
        heldMotors = null;
    }

    /** How often the board's failsafe has gone into its stage 2 by now (with {@link #modelsRxLoss}). */
    public synchronized int stage2() {
        clock();
        return stage2Count;
    }

    /** The arming block "MSP" holds (the keeper's lock, MSP_ARMING_DISABLE). */
    public synchronized boolean armingLocked() {
        return armingDisabledMsp;
    }

    public synchronized boolean armed() {
        clock();
        return isArmed();
    }

    private boolean isArmed() {
        return forcedArmed || stickArmed;
    }

    /** A battery in (the ESCs answer), or out again: {@code battery(0.3, 0)}. */
    public synchronized void battery(double packVolts, int cellCount) {
        volts = packVolts;
        cells = cellCount;
    }

    public synchronized boolean batteryIn() {
        return cells > 0 || volts >= MotorTest.NO_BATTERY_VOLTS;
    }

    /**
     * Other ESCs than the four Bluejay ones it comes with: the list is emptied and fit adds its own, motor 1 first
     * (FakeEscBoard's silabs, bb51, am32, arm32).
     */
    public synchronized FakeFlightBoard fitEscs(java.util.function.Consumer<FakeEscBoard> fit) {
        escs.escs.clear();
        fit.accept(escs);
        return this;
    }

    /** CLI commands that start like this are answered ###ERROR from now on. */
    public synchronized void refuseCli(String startsWith) {
        refused.add(startsWith);
    }

    public synchronized void refuseNothing() {
        refused.clear();
    }

    /** The cable out now ({@link #cutTakesPower} says what the board does then); it is in again for the next open. */
    public synchronized void cutWire() {
        if (current != null) current.alive = false;
        current = null;
        if (cutTakesPower) powerCycle();
    }

    /** The cable out until {@link #plugIn}; the board loses its power. */
    public synchronized void unplug() {
        unplugged = true;
        if (current != null) current.alive = false;
        current = null;
        powerCycle();
    }

    /** The cable in again: the board has started from what was saved, its port is there at once. */
    public synchronized void plugIn() {
        unplugged = false;
        away = 0;
    }

    /** A flight: Betaflight's own statistics count up and are saved - lines that change by themselves. */
    public synchronized void fly() {
        for (Config c : List.of(saved, ram)) {
            c.master.merge("stats_total_flights", "1", (a, b) -> Integer.toString(Integer.parseInt(a) + 1));
            c.master.merge("stats_total_time_s", "180", (a, b) -> Integer.toString(Integer.parseInt(a) + 180));
        }
    }

    // ------------------------------------------------------------------ for a test: what happened, what it holds

    /** Every CLI command that came over a wire, in order (save and exit too). */
    public synchronized List<String> cli() {
        return List.copyOf(cliSeen);
    }

    /** Every MSP request that came, by number, in order. */
    public synchronized List<Integer> mspRequests() {
        return List.copyOf(mspSeen);
    }

    /** DShot commands sent to the ESCs: "motor 2: 8 12". */
    public synchronized List<String> dshotCommands() {
        return List.copyOf(dshotSeen);
    }

    /** What is saved, as "dump all" prints it after a restart: two boards (or one, before and after) compare by this. */
    public synchronized String saved() {
        return print(saved, false, true).replace("\r\n", "\n");
    }

    /** What is saved, as "diff all" prints it. */
    public synchronized String savedDiff() {
        return print(saved, true, true).replace("\r\n", "\n");
    }

    /** What is in memory now, saved or not, as "dump all" prints it. */
    public synchronized String inMemory() {
        return print(ram, false, true).replace("\r\n", "\n");
    }

    public synchronized int featureMask() {
        return ram.features;
    }

    /** Each motor's output now (1 first). */
    public synchronized int[] motors() {
        clock();
        int[] m = new int[MOTORS];
        // armed: at idle - or where the flight controller's own failsafe drives them
        int running = rescue ? rescueMotors : landing ? setting("failsafe_throttle") : RUNNING;
        for (int i = 0; i < MOTORS; i++) m[i] = isArmed() ? running : motors[i];
        return m;
    }

    public synchronized boolean inCli() {
        return cli;
    }

    public synchronized boolean inPassthrough() {
        return escs.inPassthrough();
    }

    /** An 'R' reached it outside the CLI: it sits in its bootloader, off USB's port list, until {@link #unplug} and {@link #plugIn}. */
    public synchronized boolean inBootloader() {
        return bootloader;
    }

    /**
     * The ESCs behind the board (motor 1 first): their memory, and what may go wrong with each. Whether one answers
     * is the battery's to say: it is set anew each time the port is handed to the ESCs.
     */
    public synchronized List<FakeEscBoard.Esc> escs() {
        return escs.escs;
    }

    // ------------------------------------------------------------------ restarts

    private void powerCycle() {
        if (flipDefaultsAtNextStart) { // what is saved stays: only what counts as a default is other
            flipDefaultsAtNextStart = false;
            brushed = !brushed;
            fresh = new Config(brushed);
        }
        ram = saved.copy();
        cli = batch = batchError = bootloader = false;
        cliLine.setLength(0);
        forcedArmed = stickArmed = false;
        armingDisabledMsp = armSwitchHeld = false; // the arming lock is in memory only
        lastSticksNs = stage2SinceNs = 0;
        rxfailApplied = inStage2 = rescue = landing = false;
        heldMotors = null;
        rc = null;
        Arrays.fill(motors, MotorTest.STOP);
        Arrays.fill(escDirection, 0);
        FakeEscBoard fresh = new FakeEscBoard(); // out of the passthrough, the same ESCs
        fresh.escs.addAll(escs.escs);
        escs = fresh;
        restarts++;
    }

    /** Betaflight restarts: memory from what was saved, the connection gone, the port away for a moment. */
    private void restart() {
        powerCycle();
        if (current != null) current.alive = false;
        current = null;
        away = restartLooks;
    }

    // ------------------------------------------------------------------ the wire

    /** One USB connection. A restart or a cut ends it: reads say "gone", writes fail. */
    private final class Link implements BetaflightUsb.Wire {
        private final ArrayDeque<Byte> out = new ArrayDeque<>();
        private byte[] in = new byte[0];
        private boolean alive = true, closed;

        @Override
        public int read(byte[] buf) {
            synchronized (FakeFlightBoard.this) {
                if (closed || !alive) return -1;
                int n = 0;
                while (n < buf.length && !out.isEmpty()) buf[n++] = out.poll();
                if (n > 0) return n;
            }
            try {
                Thread.sleep(2); // nothing yet: a port waits a little before it says so
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }

        @Override
        public int available() {
            synchronized (FakeFlightBoard.this) {
                return closed || !alive ? 0 : out.size();
            }
        }

        @Override
        public void write(byte[] data) throws IOException {
            synchronized (FakeFlightBoard.this) {
                if (closed || !alive) throw new IOException("Could not write to the flight controller");
                received(this, data);
            }
        }

        @Override
        public boolean isOpen() {
            synchronized (FakeFlightBoard.this) {
                return !closed && alive;
            }
        }

        @Override
        public void close() {
            synchronized (FakeFlightBoard.this) {
                closed = true; // the board stays as it is: in the CLI, if nobody said exit
                if (current == this) current = null;
            }
        }

        void send(byte[] b, int n) {
            for (int i = 0; i < n; i++) out.add(b[i]);
        }

        void send(String text) {
            byte[] b = text.getBytes(StandardCharsets.US_ASCII);
            send(b, b.length);
        }
    }

    private void received(Link link, byte[] data) {
        byte[] all = Arrays.copyOf(link.in, link.in.length + data.length);
        System.arraycopy(data, 0, all, link.in.length, data.length);
        int at = 0;
        while (at < all.length && link.alive) {
            if (escs.inPassthrough()) { // everything is the ESC interface's until it is left
                escs.write(Arrays.copyOfRange(all, at, all.length));
                at = all.length;
                fromEscs(link);
            } else if (cli) {
                cliByte(link, all[at++]);
            } else {
                int used = mspAt(link, all, at);
                if (used == 0) break; // a frame cut short: its rest comes with the next write
                at += used;
            }
        }
        link.in = link.alive ? Arrays.copyOfRange(all, at, all.length) : new byte[0];
    }

    private void fromEscs(Link link) {
        byte[] buf = new byte[512];
        for (int n = escs.read(buf); n > 0; n = escs.read(buf)) link.send(buf, n);
    }

    // ------------------------------------------------------------------ MSP

    /**
     * One step through the bytes while MSP has the port: the bytes used, or 0 when a frame's rest is still to come.
     * Betaflight looks at a byte that is no MSP only while disarmed: '#' opens the CLI, and 'R' (its reboot_character)
     * restarts it into its bootloader - text meant for the CLI must never reach a board that is not in it.
     */
    private int mspAt(Link link, byte[] b, int at) {
        if (b[at] == '#' && !armed()) {
            cli = true;
            cliLine.setLength(0);
            link.send("\r\nEntering CLI Mode, type 'exit' to return, or 'help'\r\n\r\n# ");
        } else if (b[at] == 'R' && !armed()) {
            restart();
            bootloader = true; // no Betaflight and no port until its power was off and on
        }
        if (b[at] != MspFrames.START) return 1;
        int left = b.length - at;
        if (left < 3) return 0;
        boolean v2 = b[at + 1] == MspFrames.V2;
        if (b[at + 1] != MspFrames.V1 && !v2 || b[at + 2] != MspFrames.REQUEST) return 1;
        int header = v2 ? MspFrames.V2_HEADER_SIZE : MspFrames.V1_HEADER_SIZE;
        if (left < header) return 0;
        int code = v2 ? Msp.u16(b, at + 4) : b[at + 4] & 0xff, size = v2 ? Msp.u16(b, at + 6) : b[at + 3] & 0xff;
        int length = header + size + MspFrames.CRC_SIZE;
        if (left < length) return 0;
        byte[] payload = Arrays.copyOfRange(b, at + header, at + header + size);
        byte[] whole = v2 ? MspFrames.v2Request(code, payload) : MspFrames.v1Request(code, payload);
        if (whole[length - 1] == b[at + length - 1]) request(link, v2, code, payload, whole); // a bad checksum: the frame is dropped whole
        return length;
    }

    private void request(Link link, boolean v2, int code, byte[] payload, byte[] frame) {
        mspSeen.add(code);
        clock();
        if (cutAfterMspRequests >= 0 && cutAfterMspRequests-- == 0) {
            cutWire();
            return;
        }
        if (!v2 && code == Msp.SET_PASSTHROUGH) { // FakeEscBoard answers it and is the 4-way interface from here
            for (FakeEscBoard.Esc e : escs.escs) e.answers = batteryIn(); // an ESC without power says nothing
            escs.write(frame);
            fromEscs(link);
            return;
        }
        byte[] reply;
        try {
            // v2 is a frame, not another set of commands: a number of v1's in it is v1's command (the phone app asks everything so)
            reply = v2 && code > 255 ? msp2(code, payload) : msp(code, payload == null ? DONE : payload);
        } catch (RuntimeException tooShort) {
            reply = null;
        }
        byte[] answer = v2 ? MspFrames.v2Request(code, reply) : MspFrames.v1Request(code, reply);
        answer[2] = reply == null ? MspFrames.ERROR : MspFrames.RESPONSE;
        link.send(answer, answer.length);
        if (reply != null && code == Msp.REBOOT) restart();
    }

    /** Little-endian, as MSP has its numbers. */
    private static final class Le {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();

        Le u8(int v) {
            o.write(v);
            return this;
        }

        Le u16(int v) {
            return u8(v).u8(v >> 8);
        }

        Le u32(int v) {
            return u16(v).u16(v >>> 16);
        }

        Le ascii(String s) {
            byte[] b = s.getBytes(StandardCharsets.US_ASCII);
            o.write(b, 0, b.length);
            return this;
        }

        byte[] bytes() {
            return o.toByteArray();
        }
    }

    private static final byte[] DONE = new byte[0];

    /** The answer's payload; null is Betaflight's error answer. */
    private byte[] msp(int code, byte[] p) {
        switch (code) {
            case Msp.API_VERSION:
                return new byte[]{0, 1, 45};
            case Msp.FC_VARIANT:
                return variant.getBytes(StandardCharsets.US_ASCII);
            case Msp.FC_VERSION:
                return new byte[]{(byte) version[0], (byte) version[1], (byte) version[2]};
            case Msp.BOARD_INFO:
                return new Le().ascii("S405").u16(0).u8(2).bytes();
            case Msp.BUILD_INFO:
                return new Le().ascii("Apr 11 2023").ascii("01:20:48").ascii("madeup0").bytes();
            case Msp.MODE_RANGES: {
                Le le = new Le();
                for (int[] s : ram.aux) le.u8(s[0]).u8(s[1]).u8((s[2] - 900) / 25).u8((s[3] - 900) / 25);
                return le.bytes();
            }
            case Msp.FEATURE_CONFIG:
                return new Le().u32(ram.features).bytes();
            case Msp.SET_FEATURE_CONFIG:
                ram.features = Msp.u32(p, 0);
                return DONE;
            case Msp.MIXER_CONFIG:
                return new byte[]{(byte) ram.mixer, (byte) (on(ram, "yaw_motors_reversed") ? 1 : 0)};
            case Msp.SET_MIXER_CONFIG: {
                int mixer = p[0] & 0xff;
                if (mixer < 1 || mixer >= MIXERS.length) return null;
                ram.mixer = mixer;
                if (p.length > 1) ram.master.put("yaw_motors_reversed", p[1] != 0 ? "ON" : "OFF");
                return DONE;
            }
            case Msp.RX_MAP: {
                byte[] map = new byte[MAP_LETTERS.length()];
                for (int i = 0; i < map.length; i++) map[i] = (byte) ram.map.indexOf(MAP_LETTERS.charAt(i));
                return map;
            }
            case Msp.REBOOT:
                return new byte[]{0};
            case Msp.ADVANCED_CONFIG:
                return new Le().u8(1).u8(2).u8(on(ram, "use_unsynced_pwm") ? 1 : 0).u8(Arrays.asList(PROTOCOLS).indexOf(ram.master.get("motor_pwm_protocol")))
                        .u16(480).u16(550).u8(0).u8(0).u8(0).u8(0).u8(48).u16(125).u16(0).u8(2).u8(0).u8(0).bytes();
            case Msp.STATUS:
                return new Le().u16(125).u16(0).u16(1 | (mag ? 1 << 2 : 0) | (gps ? 1 << 3 : 0))
                        .u32((isArmed() ? 1 : 0) | (inStage2 ? 1 << boxIndex(Msp.BOX_FAILSAFE) : 0))
                        .u8(ram.profile).u16(5).u16(125).u8(0).u8(26).u32(armingDisableFlags()).u8(0).bytes();
            case Msp.ARMING_DISABLE: // the keeper's lock: not 0 blocks arming and disarms, 0 takes the block off
                if (ignoreArmingDisable) return DONE;
                armingDisabledMsp = p[0] != 0;
                if (armingDisabledMsp) {
                    boolean was = isArmed();
                    forcedArmed = stickArmed = false;
                    if (was) atDisarm();
                    if (modeOn(0)) armSwitchHeld = true; // the switch is on under the block: off once before it counts again
                }
                return DONE;
            case Msp.FAILSAFE_CONFIG: { // delay, off delay (0.1 s), throttle, switch mode, throttle-low delay, procedure
                byte[] other = mspFailsafeConfig;
                if (other != null) return other.clone();
                return new Le().u8(setting("failsafe_delay")).u8(setting("failsafe_off_delay")).u16(setting("failsafe_throttle"))
                        .u8(List.of("STAGE1", "KILL", "STAGE2").indexOf(ram.master.get("failsafe_switch_mode"))).u16(setting("failsafe_throttle_low_delay"))
                        .u8(List.of("AUTO-LANDING", "DROP", "GPS-RESCUE").indexOf(ram.master.get("failsafe_procedure"))).bytes();
            }
            case Msp.MOTOR: {
                Le le = new Le();
                int[] now = motors();
                for (int i = 0; i < MotorTest.MAX_MOTORS; i++) le.u16(i < MOTORS ? now[i] : 0);
                return le.bytes();
            }
            case Msp.ANALOG:
                return new Le().u8((int) Math.min(255, Math.round(volts * 10))).u16(0).u16(0).u16(0).u16((int) Math.round(volts * 100)).bytes();
            case Msp.BOXIDS: {
                Le le = new Le();
                for (int id : offeredModes) le.u8(id);
                return le.bytes();
            }
            case Msp.BATTERY_STATE:
                return new Le().u8(cells).u16(0).u8((int) Math.min(255, Math.round(volts * 10))).u16(0).u16(0).u8(0)
                        .u16((int) Math.round(volts * 100)).bytes();
            case Msp.MOTOR_CONFIG:
                return new Le().u16(Integer.parseInt(ram.master.get("min_throttle"))).u16(2000).u16(1000).u8(MOTORS).u8(14).u8(0).u8(0).bytes();
            case Msp.UID:
                return uid.clone();
            case Msp.BEEPER_CONFIG:
                return new Le().u32(ram.beeperOff).u8(Integer.parseInt(ram.master.get("beeper_dshot_beacon_tone"))).u32(ram.beaconOff).bytes();
            case Msp.SET_BEEPER_CONFIG:
                ram.beeperOff = Msp.u32(p, 0) & ALL_BEEPERS;
                if (p.length > 4 && p[4] >= 1 && p[4] <= 5) ram.master.put("beeper_dshot_beacon_tone", Integer.toString(p[4]));
                if (p.length >= 9) ram.beaconOff = Msp.u32(p, 5) & BEACONS;
                return DONE;
            case Msp.SET_RAW_RC:
                rc = new int[p.length / 2];
                for (int i = 0; i < rc.length; i++) rc[i] = Msp.u16(p, 2 * i);
                lastSticksNs = System.nanoTime(); // the receiver is there again: stage 1 and stage 2 are over
                rxfailApplied = inStage2 = rescue = landing = false;
                sticks();
                return DONE;
            case Msp.SET_MOTOR: { // Betaflight keeps what it is sent, and uses it only while disarmed
                int[] into = motors;
                if (armed()) {
                    if (!keepsMotorValuesWhileArmed) return DONE;
                    if (heldMotors == null) heldMotors = motors.clone();
                    into = heldMotors; // kept, and out the moment it is disarmed
                }
                for (int i = 0; i < MOTORS && 2 * i + 1 < p.length; i++) into[i] = Math.max(1000, Math.min(2000, Msp.u16(p, 2 * i)));
                return DONE;
            }
            case Msp.EEPROM_WRITE:
                if (armed()) return null;
                saved = ram.copy();
                saves++;
                return DONE;
            default:
                return null;
        }
    }

    private byte[] msp2(int code, byte[] p) {
        switch (code) {
            case Msp.MOTOR_OUTPUT_REORDERING: {
                Le le = new Le().u8(MotorTest.MAX_MOTORS);
                for (String output : ram.master.get("motor_output_reordering").split(",")) le.u8(Integer.parseInt(output));
                return le.bytes();
            }
            case Msp.SET_MOTOR_OUTPUT_REORDERING: {
                List<String> order = new ArrayList<>(List.of(ram.master.get("motor_output_reordering").split(",")));
                for (int i = 0; i < (p[0] & 0xff) && i < order.size(); i++) {
                    if (p[1 + i] < 0 || p[1 + i] >= MotorTest.MAX_MOTORS) return null;
                    order.set(i, Integer.toString(p[1 + i]));
                }
                ram.master.put("motor_output_reordering", String.join(",", order));
                return DONE;
            }
            case Msp.SEND_DSHOT_COMMAND: { // type, motor (255: all), count, commands
                int motor = p[1] & 0xff, count = p[2] & 0xff;
                StringBuilder said = new StringBuilder("motor " + (motor == 255 ? "all" : Integer.toString(motor + 1)) + ":");
                for (int i = 0; i < count; i++) said.append(' ').append(p[3 + i] & 0xff);
                dshotSeen.add(said.toString());
                boolean reaches = !armed() && batteryIn() && ram.master.get("motor_pwm_protocol").startsWith("DSHOT");
                for (int m = 0; reaches && m < MOTORS; m++) {
                    if (motor != 255 && motor != m) continue;
                    for (int i = 0; i < count; i++) dshot(m, p[3 + i] & 0xff);
                }
                return DONE;
            }
            default:
                return null;
        }
    }

    /** DShot commands 7 / 8 (spin direction 1 / 2) and 12 (save): a saved direction is a byte in the ESC's own settings. */
    private void dshot(int motor, int command) {
        if (command == 7 || command == 8) escDirection[motor] = command - 6;
        if (command != 12 || escDirection[motor] == 0 || motor >= escs.escs.size()) return;
        FakeEscBoard.Esc esc = escs.escs.get(motor);
        EscFirmware.Chip chip = EscFirmware.chip(esc.signature, esc.mode);
        if (chip != null && chip.silabs()) esc.memory[chip.settingsAt() + EscFirmware.MOTOR_DIRECTION] = (byte) escDirection[motor];
    }

    /** Arming by the last sticks, loosely: see the class comment. */
    private void sticks() {
        boolean was = armed();
        if (!modeOn(0)) {
            stickArmed = false;
            armSwitchHeld = false; // off once: it counts again
        } else if (armingDisabledMsp || !stickArmed && rpmFilterBlocks()) {
            armSwitchHeld = true; // on under the arming lock (or another block): nothing arms, and the switch has to go off first
        } else if (!stickArmed && !armSwitchHeld) {
            int throttle = ram.map.indexOf('T');
            boolean prearmSet = false;
            for (int[] s : ram.aux) prearmSet |= s[0] == 36 && s[2] < s[3];
            stickArmed = !cli && !escs.inPassthrough() && BetaflightPlan.isMspReceiver(ram.features) && rc.length > throttle
                    && rc[throttle] <= Integer.parseInt(ram.master.get("min_check")) && (!prearmSet || modeOn(36));
        }
        if (was && !isArmed()) atDisarm();
    }

    /**
     * MSP_STATUS' arming-disable flags, those this board knows: "MSP" (the keeper's lock), "ARM switch", "RPMFILTER"
     * (bidirectional DShot and no ESC answering), and with {@link #modelsRxLoss} "RXLOSS" and "FAILSAFE".
     */
    private int armingDisableFlags() {
        return (armingDisabledMsp ? 1 << Msp.ARMING_DISABLED_MSP_BIT : 0) | (armSwitchHeld ? 1 << Msp.ARMING_DISABLED_ARM_SWITCH_BIT : 0)
                | (rpmFilterBlocks() ? 1 << RPMFILTER_BIT : 0) | (rxLost() ? 1 << Msp.ARMING_DISABLED_RXLOSS_BIT : 0)
                | (inStage2 ? 1 << Msp.ARMING_DISABLED_FAILSAFE_BIT : 0);
    }

    /** Betaflight's arming block when the RPM filter is on and no ESC sends it its speed: flag 20 in 4.4. */
    private static final int RPMFILTER_BIT = 20;

    /** Bidirectional DShot is on and no ESC answers the flight controller: the battery out, or {@link #escTelemetry} off. */
    private boolean rpmFilterBlocks() {
        return on(ram, "dshot_bidir") && (!batteryIn() || !escTelemetry);
    }

    private int setting(String name) {
        return Integer.parseInt(ram.master.get(name));
    }

    /** Where a mode box is among MSP_STATUS' mode flags: its place in MSP_BOXIDS' answer. */
    private int boxIndex(int permanentId) {
        int i = 0;
        for (int id : offeredModes) {
            if (id == permanentId) return i;
            i++;
        }
        return 31; // not offered: a flag nobody reads
    }

    // ------------------------------------------------------------------ sticks that stop (modelsRxLoss)

    /** The receiver - MSP - has sent no sticks for {@link #rxLossMs}, or none at all yet. */
    private boolean rxLost() {
        return modelsRxLoss && BetaflightPlan.isMspReceiver(ram.features) && (lastSticksNs == 0 || System.nanoTime() - lastSticksNs > rxLossMs * 1_000_000L);
    }

    /**
     * What Betaflight has done by now about sticks that stopped, loosely: stage 1 gives every channel what rxfail says
     * (so a switch that is "set" low disarms there and then, and one that is "held" stays as it was); an armed board
     * goes into stage 2 after failsafe_delay and does what failsafe_procedure says. Looked at whenever the board is.
     */
    private void clock() {
        if (!rxLost() || cli || escs.inPassthrough()) return;
        long now = System.nanoTime(), lostAt = lastSticksNs + rxLossMs * 1_000_000L;
        if (!rxfailApplied) {
            rxfailApplied = true;
            if (rc != null) {
                String letters = "AERT1234";
                for (int channel = 0; channel < RXFAIL_CHANNELS; channel++) {
                    int raw = ram.map.indexOf(letters.charAt(channel));
                    String mode = ram.rxfail[channel];
                    if (raw < 0 || raw >= rc.length || mode.equals("h")) continue;
                    rc[raw] = mode.startsWith("s ") ? Integer.parseInt(mode.substring(2)) : channel == 3 ? 885 : 1500;
                }
                boolean was = isArmed();
                if (!modeOn(0)) stickArmed = false; // the ARM switch taken down by rxfail
                if (was && !isArmed()) atDisarm();
            }
        }
        if (!isArmed() || lastSticksNs == 0) return;
        if (!inStage2 && now - lostAt >= setting("failsafe_delay") * 100_000_000L) {
            inStage2 = true;
            stage2SinceNs = now;
            stage2Count++;
            switch (ram.master.get("failsafe_procedure")) {
                case "GPS-RESCUE" -> rescue = true;
                case "AUTO-LANDING" -> landing = true;
                default -> dropped();
            }
        }
        if (landing && now - stage2SinceNs >= setting("failsafe_off_delay") * 100_000_000L) dropped();
    }

    /** The failsafe disarms: by DROP at once, by AUTO-LANDING when its time is over. The ARM switch, if it is held on, has to go off before it counts again. */
    private void dropped() {
        forcedArmed = stickArmed = false;
        if (modeOn(0)) armSwitchHeld = true;
        atDisarm();
    }

    /** A mode's range holds in the last sticks. */
    private boolean modeOn(int mode) {
        if (rc == null) return false;
        for (int[] s : ram.aux) {
            int channel = 4 + s[1];
            if (s[0] == mode && s[2] < s[3] && channel < rc.length && rc[channel] >= s[2] && rc[channel] < s[3]) return true;
        }
        return false;
    }

    private static boolean on(Config c, String setting) {
        return "ON".equals(c.master.get(setting));
    }

    // ------------------------------------------------------------------ the CLI

    /** One byte typed: echoed; at the line's end the command is run and the prompt printed. */
    private void cliByte(Link link, byte b) {
        if (b != '\n' && b != '\r') {
            if (b >= 32 && b <= 126 && !(cliLine.length() == 0 && b == ' ')) {
                cliLine.append((char) b);
                link.send(new byte[]{b}, 1);
            }
            return;
        }
        if (cliLine.length() == 0) return; // Enter on an empty line: Betaflight says nothing, not even its prompt
        String line = cliLine.toString();
        cliLine.setLength(0);
        link.send("\r\n");
        int comment = line.indexOf('#'); // from a # on, a line says nothing
        if (comment >= 0) line = line.substring(0, comment);
        line = line.strip();
        if (!line.isEmpty()) {
            if (cutAfterCliCommands >= 0 && cutAfterCliCommands-- == 0) {
                cutWire();
                return;
            }
            cliSeen.add(line);
            String out = wireCommand(link, line);
            if (out == null) return; // it restarted: no prompt
            if (!out.isEmpty()) link.send(out + "\r\n");
        }
        link.send("\r\n# ");
    }

    /** A command that came over the wire: the refusals a test asked for, save and exit, then the rest. */
    private String wireCommand(Link link, String line) {
        String word = line.split("\\s+")[0].toLowerCase(Locale.ROOT);
        for (String r : refused) if (line.startsWith(r)) return error(word, "REFUSED (the test asked for it)");
        if (word.equals("exit")) {
            link.send("Leaving CLI mode, unsaved changes lost.\r\n\r\nRebooting\r\n");
            restart();
            return null;
        }
        if (word.equals("save") || line.equalsIgnoreCase("defaults")) {
            if (batch && batchError) { // Betaflight's own guard: a batch with an error in it is not saved
                batch = batchError = false;
                return "###ERROR IN " + word + ": ERRORS WERE DETECTED - PLEASE FIX ERRORS THEN 'SAVE'###";
            }
            if (word.equals("defaults")) ram = new Config(brushed);
            saved = ram.copy();
            saves++;
            link.send("# saving\r\n\r\nRebooting\r\n");
            restart();
            return null;
        }
        return configCommand(line);
    }

    private String error(String command, String what) {
        if (batch) batchError = true;
        return "###ERROR IN " + command + ": " + what + "###";
    }

    /** A line of the built-in config this build does not have, as Betaflight answers it ("set x = 1": ERROR_INVALID_NAME). */
    private String notTaken(String line) {
        String[] p = line.strip().split("\\s+", 2);
        return error(p[0], "INVALID NAME: " + (p.length > 1 ? p[1] : ""));
    }

    /** A command that reads or changes the settings in memory, and what it prints (lines joined by CR LF). */
    private String configCommand(String line) {
        String[] parts = line.split("\\s+", 2);
        String word = parts[0].toLowerCase(Locale.ROOT), args = parts.length > 1 ? parts[1].strip() : "";
        switch (word) {
            case "version":
                return "# " + versionLine();
            case "board_name":
                return fixedName(word, args, boardName);
            case "manufacturer_id":
                return fixedName(word, args, manufacturerId);
            case "mcu_id":
                return "mcu_id " + mcuId();
            case "signature":
                return args.isEmpty() ? "signature " : error(word, "SIGNATURE CANNOT BE CHANGED. CURRENT VALUE: ''");
            case "get":
                return get(args);
            case "set":
                return args.contains("=") ? set(args) : get(args);
            case "aux":
                return aux(args);
            case "feature":
                return feature(args);
            case "serial":
                return serial(args);
            case "timer":
                return timer(args);
            case "beeper":
                return beeper(word, args, ALL_BEEPERS, true);
            case "beacon":
                return beeper(word, args, BEACONS, false);
            case "map":
                if (args.isEmpty()) return "map " + ram.map;
                if (args.length() != DEFAULT_MAP.length() || !sameLetters(args.toUpperCase(Locale.ROOT), DEFAULT_MAP)) {
                    return error(word, "INVALID ARGUMENT LENGTH");
                }
                ram.map = args.toUpperCase(Locale.ROOT);
                return "map " + ram.map;
            case "rxfail":
                return rxfail(args);
            case "mixer": {
                if (args.isEmpty()) return "Mixer: " + MIXERS[ram.mixer];
                int i = Arrays.asList(MIXERS).indexOf(args.toUpperCase(Locale.ROOT));
                if (i < 1) return error(word, "INVALID NAME: " + args);
                ram.mixer = i;
                return "Mixer: " + MIXERS[i];
            }
            case "profile":
            case "rateprofile": {
                boolean pid = word.equals("profile");
                int count = pid ? PROFILES : RATE_PROFILES;
                if (!args.isEmpty()) {
                    int i = number(args, -1);
                    if (i < 0 || i >= count) return error(word, "PROFILE OUTSIDE OF [0.." + (count - 1) + "]");
                    if (pid) ram.profile = i;
                    else ram.rate = i;
                }
                return word + " " + (pid ? ram.profile : ram.rate);
            }
            case "batch":
                if (args.equalsIgnoreCase("start")) {
                    batch = true;
                    batchError = false;
                    return "Command batch started";
                }
                if (args.equalsIgnoreCase("end")) {
                    boolean failed = batch && batchError;
                    batch = batchError = false;
                    return failed ? "###ERROR IN batch: ERRORS WERE DETECTED - PLEASE REVIEW BEFORE CONTINUING###" : "Command batch ended";
                }
                return error(word, "INVALID OPTION");
            case "defaults": {
                if (!args.equalsIgnoreCase("nosave")) return ""; // alone it saves and restarts: wireCommand
                ram = new Config(brushed);
                batchError = false; // as Betaflight: the batch goes on, its errors so far forgotten
                // cli.c: cliPrintHashLine (CR LF, "# ", the words, CR LF), then the built-in config again with its errors on
                StringBuilder out = new StringBuilder("\r\n# resetting to defaults");
                for (String config : builtInConfigNotTaken) out.append("\r\n").append(notTaken(config));
                return out.toString();
            }
            case "diff":
            case "dump":
                if (!args.isEmpty() && !args.equalsIgnoreCase("all")) return error(word, "INVALID ARGUMENT: " + args);
                if (!builtInConfigNotTaken.isEmpty() && batch) batchError = true; // its built-in config again, quietly: marks the batch all the same
                String listing = print(ram, word.equals("diff"), args.equalsIgnoreCase("all"));
                if (cutShortListings > 0) { // as a reader sees it that took a heading's "# " for the prompt
                    cutShortListings--;
                    int heading = listing.lastIndexOf("\r\n# ");
                    return listing.substring(0, heading > 0 ? heading : listing.length() / 2);
                }
                return listing;
            default:
                return error(word, "UNKNOWN COMMAND, TRY 'HELP'");
        }
    }

    public String versionLine() {
        return "Betaflight / STM32F405 (S405) " + version[0] + "." + version[1] + "." + version[2] + " " + build + " MSP API: 1.45";
    }

    /** The UID as the CLI prints it: three 32-bit words. */
    private String mcuId() {
        byte[] u = uid;
        return String.format("%08x%08x%08x", Msp.u32(u, 0), Msp.u32(u, 4), Msp.u32(u, 8));
    }

    /** board_name and manufacturer_id: said, or set to what they are already - never changed. */
    private String fixedName(String command, String wanted, String is) {
        if (!wanted.isEmpty() && !wanted.equals(is)) {
            return error(command, command.toUpperCase(Locale.ROOT) + " CANNOT BE CHANGED. CURRENT VALUE: '" + is + "'");
        }
        return command + " " + is;
    }

    private static boolean sameLetters(String a, String b) {
        char[] x = a.toCharArray(), y = b.toCharArray();
        Arrays.sort(x);
        Arrays.sort(y);
        return Arrays.equals(x, y);
    }

    private static int number(String text, int otherwise) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    /** rxfail: all channels listed, or one set - "rxfail 3 h", "rxfail 4 s 1000"; "a" (auto) is for the four sticks alone. */
    private String rxfail(String args) {
        if (args.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < RXFAIL_CHANNELS; i++) out.add("rxfail " + i + " " + ram.rxfail[i]);
            return String.join("\r\n", out);
        }
        String[] p = args.split("\\s+");
        int channel = number(p[0], -1);
        if (channel < 0 || channel >= RXFAIL_CHANNELS) return error("rxfail", "CHANNEL_RANGE: 0 - " + (RXFAIL_CHANNELS - 1));
        String mode = p.length > 1 ? p[1].toLowerCase(Locale.ROOT) : "";
        if (mode.equals("h") || mode.equals("a") && channel < 4) {
            ram.rxfail[channel] = mode;
        } else if (mode.equals("s") && p.length > 2 && number(p[2], -1) >= 885 && number(p[2], -1) <= 2115) {
            ram.rxfail[channel] = "s " + number(p[2], 0);
        } else {
            return error("rxfail", mode.equals("a") ? "CANNOT USE THIS MODE ON THIS CHANNEL" : "INVALID ARGUMENT");
        }
        return "rxfail " + channel + " " + ram.rxfail[channel];
    }

    /** "get part-of-a-name": every setting whose name has it. */
    private String get(String part) {
        List<String> out = new ArrayList<>();
        for (Setting s : SETTINGS) {
            if (part.isEmpty() || !s.name().contains(part.toLowerCase(Locale.ROOT))) continue;
            out.add(s.name() + " = " + shown(ram.home(s).get(s.name())));
            if (s.section() == Section.PROFILE) out.add("profile " + ram.profile);
            if (s.section() == Section.RATE) out.add("rateprofile " + ram.rate);
            out.add(s.allowed());
            out.add("");
        }
        return out.isEmpty() ? error("get", "INVALID NAME: " + part) : String.join("\r\n", out);
    }

    private String set(String args) {
        String name = args.substring(0, args.indexOf('=')).strip(), value = args.substring(args.indexOf('=') + 1);
        for (Setting s : SETTINGS) {
            if (!s.name().equalsIgnoreCase(name)) continue;
            String taken = s.taken(value);
            if (taken == null) return error("set", "INVALID VALUE") + "\r\n" + s.allowed();
            ram.home(s).put(s.name(), taken);
            return s.name() + " set to " + shown(taken);
        }
        return error("set", "INVALID NAME: " + name);
    }

    /** An empty text prints as "-". */
    private static String shown(String value) {
        return value.isEmpty() ? "-" : value;
    }

    /** Betaflight 4.4's cliAux: a mode id it doesn't know keeps the slot's old mode; too few good arguments clear the slot. */
    private String aux(String args) {
        if (args.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < AUX_SLOTS; i++) out.add(auxLine(ram, i));
            return String.join("\r\n", out);
        }
        String[] p = args.split("\\s+");
        int i = number(p[0], -1);
        if (i < 0 || i >= AUX_SLOTS) return error("aux", "INDEX NOT BETWEEN 0 AND " + (AUX_SLOTS - 1));
        int[] slot = ram.aux[i];
        int valid = 0;
        if (p.length > 1 && knownModes.contains(number(p[1], -1))) {
            slot[0] = number(p[1], 0);
            valid++;
        }
        if (p.length > 2 && number(p[2], -1) >= 0 && number(p[2], -1) < MAX_AUX_CHANNELS) {
            slot[1] = number(p[2], 0);
            valid++;
        }
        if (p.length > 4 && step(p[3]) >= 0 && step(p[4]) >= 0) {
            slot[2] = step(p[3]);
            slot[3] = step(p[4]);
            valid += 2;
        }
        if (p.length > 5 && (number(p[5], -1) == 0 || number(p[5], -1) == 1)) {
            slot[4] = number(p[5], 0);
            valid++;
        }
        if (p.length > 6 && knownModes.contains(number(p[6], -1))) {
            slot[5] = number(p[6], 0);
            valid++;
        }
        if (valid == 4 || valid == 5) {
            slot[4] = 0;
            slot[5] = 0;
        } else if (valid != 6) {
            ram.aux[i] = FREE_SLOT.clone();
        }
        return auxLine(ram, i);
    }

    /** A range's end in Betaflight's steps of 25 from 900 to 2100, or -1. */
    private static int step(String text) {
        int v = number(text, -1);
        return v < 900 || v > 2100 ? -1 : 900 + (v - 900) / 25 * 25;
    }

    private static String auxLine(Config c, int i) {
        int[] s = c.aux[i];
        return "aux " + i + " " + s[0] + " " + s[1] + " " + s[2] + " " + s[3] + " " + s[4] + " " + s[5];
    }

    /** The feature bit this CLI has that name for (Betaflight matches a name by its start), or -1. */
    private int featureBit(String name) {
        for (int i = 0; i < FEATURES.length; i++) {
            if (FEATURES[i].isEmpty() || !cliNamesRxMsp && FEATURES[i].equals("RX_MSP")) continue;
            if (FEATURES[i].regionMatches(true, 0, name, 0, name.length())) return i;
        }
        return -1;
    }

    private String feature(String args) {
        if (args.isEmpty() || args.equalsIgnoreCase("list")) {
            StringBuilder out = new StringBuilder(args.isEmpty() ? "Enabled:" : "Available:");
            for (int i = 0; i < FEATURES.length; i++) {
                if (featureBit(FEATURES[i].isEmpty() ? "?" : FEATURES[i]) != i) continue;
                if (!args.isEmpty() || (ram.features & 1 << i) != 0) out.append(' ').append(FEATURES[i]);
            }
            return out.toString();
        }
        boolean off = args.startsWith("-");
        String name = off ? args.substring(1) : args;
        int bit = name.isEmpty() ? -1 : featureBit(name);
        if (bit < 0) return error("feature", "INVALID NAME: " + name);
        if (featuresNotBuilt.contains(FEATURES[bit])) return "Unavailable"; // on or off alike; no error of the batch's
        if (off) ram.features &= ~(1 << bit);
        else ram.features |= 1 << bit;
        return (off ? "Disabled " : "Enabled ") + FEATURES[bit];
    }

    /** "timer": the pins with a timer; "timer B00 AF2" gives a pin one, "timer B00 NONE" takes it off. */
    private String timer(String args) {
        if (args.isEmpty()) {
            List<String> out = new ArrayList<>();
            ram.timers.forEach((pin, af) -> out.addAll(timerLines(pin, af)));
            return String.join("\r\n", out);
        }
        String[] p = args.toUpperCase(Locale.ROOT).split("\\s+");
        if (p.length != 2 || !p[0].matches("[A-H]\\d\\d") || !p[1].matches("AF\\d+|NONE")) return error("timer", "PARSING FAILED");
        if (p[1].equals("NONE")) ram.timers.remove(p[0]);
        else ram.timers.put(p[0], p[1]);
        return String.join("\r\n", timerLines(p[0], p[1]));
    }

    /** A pin's line as Betaflight prints it, with the line under it that says which timer that is: no command, a "# " line. */
    private static List<String> timerLines(String pin, String af) {
        return af.equals("NONE") ? List.of("timer " + pin + " NONE") : List.of("timer " + pin + " " + af, "# pin " + pin + ": TIM" + af.substring(2) + " CH1 (" + af + ")");
    }

    private String serial(String args) {
        if (args.isEmpty()) {
            List<String> out = new ArrayList<>();
            ram.serial.forEach((id, rest) -> out.add("serial " + id + " " + rest));
            return String.join("\r\n", out);
        }
        String[] p = args.split("\\s+");
        if (p.length < 6 || !ram.serial.containsKey(number(p[0], -1)) || number(p[1], -1) < 0) return error("serial", "PARSING FAILED");
        for (int i = 2; i < 6; i++) if (number(p[i], -1) < 0) return error("serial", "PARSING FAILED");
        ram.serial.put(number(p[0], -1), String.join(" ", Arrays.copyOfRange(p, 1, 6)));
        return ""; // Betaflight says nothing to a port line it takes
    }

    /** Betaflight's processBeeperCommand, for the beeper and the DShot beacon: a set flag is a sound that is off. */
    private String beeper(String command, String args, int allowed, boolean buzzer) {
        int off = buzzer ? ram.beeperOff : ram.beaconOff;
        if (args.isEmpty()) {
            StringBuilder out = new StringBuilder("Disabled:");
            for (int i = 0; i < BEEPERS.length - 1; i++) if ((off & 1 << i) != 0) out.append("  ").append(BEEPERS[i]);
            return off == 0 ? out + "  none" : out.toString();
        }
        if (args.equalsIgnoreCase("list")) {
            StringBuilder out = new StringBuilder("Available:");
            for (int i = 0; i < BEEPERS.length; i++) if (i == BEEPERS.length - 1 || (allowed & 1 << i) != 0) out.append(' ').append(BEEPERS[i]);
            return out.toString();
        }
        boolean remove = args.startsWith("-");
        String name = remove ? args.substring(1) : args;
        for (int i = 0; i < BEEPERS.length && !name.isEmpty(); i++) {
            boolean all = i == BEEPERS.length - 1;
            if (!BEEPERS[i].regionMatches(true, 0, name, 0, name.length()) || !all && (allowed & 1 << i) == 0) continue;
            if (remove) off = all ? allowed : off | 1 << i;
            else off = all ? 0 : off & ~(1 << i);
            if (buzzer) ram.beeperOff = off;
            else ram.beaconOff = off;
            return (remove ? "Disabled " : "Enabled ") + BEEPERS[i];
        }
        return error(command, "INVALID NAME: " + name);
    }

    // ------------------------------------------------------------------ diff and dump

    /** Betaflight's printConfig: every line (dump) or those that differ from a fresh board (diff), CR LF between. */
    private String print(Config c, boolean diff, boolean all) {
        List<String> o = new ArrayList<>();
        o.add("# version");
        o.add("# " + versionLine());
        o.add("# config: YES");
        o.add("");
        o.add("# start the command batch");
        o.add("batch start");
        if (diff && all) {
            o.add("");
            o.add("# reset configuration to default settings");
            o.add("defaults nosave");
        }
        o.add("");
        o.add("board_name " + boardName);
        o.add("manufacturer_id " + manufacturerId);
        o.add("mcu_id " + mcuId());
        o.add("signature ");
        List<String> lines = new ArrayList<>();
        c.timers.forEach((pin, af) -> {
            if (!diff || !af.equals(fresh.timers.get(pin))) lines.addAll(timerLines(pin, af));
        });
        if (diff) for (String pin : fresh.timers.keySet()) if (!c.timers.containsKey(pin)) lines.addAll(timerLines(pin, "NONE"));
        section(o, "timer", lines);
        if (!diff || c.mixer != fresh.mixer) lines.add("mixer " + MIXERS[c.mixer]);
        section(o, "mixer", lines);
        // a dump switches every feature off first, then those that are on on again; a diff has what differs
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < FEATURES.length; i++) {
                if (featureBit(FEATURES[i].isEmpty() ? "?" : FEATURES[i]) != i) continue;
                boolean is = (c.features & 1 << i) != 0, was = (fresh.features & 1 << i) != 0;
                if (diff) {
                    if (pass == 0 && is != was) lines.add("feature " + (is ? "" : "-") + FEATURES[i]);
                } else if (pass == 0) {
                    lines.add("feature -" + FEATURES[i]);
                } else if (is) {
                    lines.add("feature " + FEATURES[i]);
                }
            }
        }
        section(o, "feature", lines);
        for (int i = 0; i < BEEPERS.length - 1; i++) {
            boolean off = (c.beeperOff & 1 << i) != 0;
            if (!diff || off != ((fresh.beeperOff & 1 << i) != 0)) lines.add("beeper " + (off ? "-" : "") + BEEPERS[i]);
        }
        section(o, "beeper", lines);
        for (int i = 0; i < BEEPERS.length - 1; i++) {
            boolean off = (c.beaconOff & 1 << i) != 0;
            if ((BEACONS & 1 << i) != 0 && (!diff || off != ((fresh.beaconOff & 1 << i) != 0))) lines.add("beacon " + (off ? "-" : "") + BEEPERS[i]);
        }
        section(o, "beacon", lines);
        if (!diff || !c.map.equals(fresh.map)) lines.add("map " + c.map);
        section(o, "map", lines);
        c.serial.forEach((id, rest) -> {
            if (!diff || !rest.equals(fresh.serial.get(id))) lines.add("serial " + id + " " + rest);
        });
        section(o, "serial", lines);
        for (int i = 0; i < AUX_SLOTS; i++) if (!diff || !Arrays.equals(c.aux[i], FREE_SLOT)) lines.add(auxLine(c, i));
        section(o, "aux", lines);
        settings(lines, c.master, fresh.master, Section.MASTER, diff);
        section(o, "master", lines);
        for (int i = 0; i < PROFILES; i++) {
            if (!all && i != c.profile) continue;
            o.add("");
            o.add("profile " + i);
            settings(lines, c.profiles.get(i), fresh.profiles.get(i), Section.PROFILE, diff);
            section(o, "profile " + i, lines);
        }
        if (all) {
            o.add("");
            o.add("# restore original profile selection");
            o.add("profile " + c.profile);
        }
        for (int i = 0; i < RATE_PROFILES; i++) {
            if (!all && i != c.rate) continue;
            o.add("");
            o.add("rateprofile " + i);
            settings(lines, c.rates.get(i), fresh.rates.get(i), Section.RATE, diff);
            section(o, "rateprofile " + i, lines);
        }
        o.add("");
        if (all) {
            o.add("# restore original rateprofile selection");
            o.add("rateprofile " + c.rate);
            o.add("");
            o.add("# save configuration");
            o.add("save");
        } else {
            o.add("# end the command batch");
            o.add("batch end");
        }
        return String.join("\r\n", o);
    }

    private static void settings(List<String> lines, Map<String, String> values, Map<String, String> defaults, Section section, boolean diff) {
        for (Setting s : SETTINGS) {
            if (s.section() != section) continue;
            String value = values.get(s.name());
            if (!diff || !value.equals(defaults.get(s.name()))) lines.add("set " + s.name() + " = " + shown(value));
        }
    }

    /** A heading and its lines, when there are any (a diff has no heading for a part with nothing in it). */
    private static void section(List<String> o, String heading, List<String> lines) {
        if (!lines.isEmpty()) {
            o.add("");
            o.add("# " + heading);
            o.addAll(lines);
        }
        lines.clear();
    }
}
