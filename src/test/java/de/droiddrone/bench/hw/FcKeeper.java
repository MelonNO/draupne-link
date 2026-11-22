package de.droiddrone.bench.hw;

import com.fazecast.jSerialComm.SerialPort;
import de.droiddrone.bench.Hardware;
import de.droiddrone.bench.PhoneAdb;
import de.droiddrone.bench.PhoneRig;
import de.droiddrone.common.ArmRanges;
import de.droiddrone.common.BeeperBack;
import de.droiddrone.common.BeeperQuiet;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.MspFrames;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.MotorTest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The flight controller keeper (the hardware rig, R3): the one program that has the board's port while it runs, and
 * the place where the rig's safety is kept - in a process of its own, so that it holds when the test JVM dies.
 * <pre>
 * gradlew fcKeeper [-Pfolder=...]   the keeper for this bench's board (the hardware file), until it is told to end
 * gradlew fcKeeperEnd               "make safe and end" to the running keeper; exit code 0 only when it left the board safe
 * </pre>
 * <b>The chain it stands in:</b> PC app (made-up radio) - real network - phone app (bench build) - TCP down the USB
 * cable ({@code adb reverse}, which the keeper sets and sets again every few seconds) - <i>the keeper</i> - the board's
 * COM port - ESCs and motors. The board may be armed by made-up sticks, with nobody in the room.
 * <p>
 * <b>Who may talk to the board through it</b>, all on this PC's loopback and never two at once:
 * <ul>
 * <li><b>The phone's lease</b> ({@link Config#phonePort}): MSP frames only, whole ones. In strict mode - always, on a
 * real board - only the requests the phone app really sends ({@link #PHONE_CODES}), framed as it frames them (MSP v2);
 * everything else is dropped and written down as a finding. No byte that is not part of such a frame ever goes on: a
 * '#' would open Betaflight's CLI and stop the sticks, an 'R' restart it into its bootloader.</li>
 * <li><b>Drone setup's lease</b> ({@link Config#setupPort}, {@link KeeperWire}): the PC app's own door to a flight
 * controller, bytes as they come (it types into the CLI). Given only while the board is disarmed and no arm window is
 * open; while it is held the phone's side is cut, exactly as one USB socket would be, and the keeper itself says
 * nothing to the board. When it ends the keeper takes the board anew: locked, stopped, its channel map read again.</li>
 * <li><b>The rig's control channel</b> ({@link Config#controlPort}, {@link KeeperClient}): lines of text - BEAT, STATE,
 * OPEN n, CLOSE, SAFE, FAULT ..., TRIP, CLEAR, SCENARIO, MARK, FIGURES, EVENTS, END.</li>
 * </ul>
 * The keeper's own requests go out as MSP v1 and the phone app's as v2, and Betaflight answers each in its own kind:
 * that is how the keeper knows which answers are its own and which the phone's.
 * <p>
 * <b>The interlocks</b>, each proven on the made-up board ({@code FcKeeper*Test}) before the real one sees it:
 * <ul>
 * <li><b>Arm lock.</b> Betaflight's arming block "MSP" (MSP_ARMING_DISABLE, 99) is set whenever the board is taken and
 * stays set, except inside a window the rig opens ({@code OPEN seconds}: 60 at most, never made longer without being
 * closed first). Where the block shows among MSP_STATUS' arming-disable flags is measured on each board (off, on, which
 * bit moved); a board on which it does not show gets no window.</li>
 * <li><b>Throttle cap.</b> A stick frame with more throttle than the ceiling (1100) is not passed on; the window is
 * closed and the lock set.</li>
 * <li><b>Voltage.</b> Volts per cell from the answers that pass (and the keeper's own question while nobody asks): no
 * window opens under the floor; under it for 2 s in a window closes it. A line a minute, always.</li>
 * <li><b>Armed time.</b> The window's own length; and so many armed seconds in any hour (600), kept across starts.</li>
 * <li><b>Heartbeat.</b> No BEAT for 2 s while a window is open: closed, locked, motors stopped.</li>
 * <li><b>Armed outside a window</b>, however it came about: locked, stopped, said loudly.</li>
 * <li><b>The end</b> - asked for (END), the process told to go, or an error of the keeper's own: the lock, STOP to
 * every motor, the motors read back at rest, the port let go. The exit code is 0 only when the board was left locked,
 * disarmed and at rest.</li>
 * </ul>
 * Every one of them closes the window and sets the lock; after one the keeper opens no window until it is told CLEAR.
 * <p>
 * <b>The board's own settings</b> (second round, 2026-11-22). A real board need not drop when its sticks stop: its
 * rxfail may hold the throttle and the ARM switch for all of failsafe_delay (20 s at 200), and its failsafe procedure
 * may be a landing or a GPS rescue, with a throttle that is in no stick frame. So:
 * <ul>
 * <li><b>They are read before anybody gets a lease</b> ({@link Config#readSettings}; a real board's always): one visit
 * to its CLI - the failsafe's settings, rxfail, motor_output_limit in every PID profile - left with "exit". Betaflight
 * restarts, and the board is taken once more, locked first. Nothing is set. MSP_FAILSAFE_CONFIG is held against it and
 * a difference said as a finding; read again after Drone setup had the board, and for another board on the port. The
 * first lines say all of it as it is.</li>
 * <li><b>The board's failsafe is not let run</b> ({@link Config#failsafeEndsWindow}; a real board's unless the
 * hardware file says board.letFailsafeRun AND board.failsafeSeenOnSitl): in an open window the keeper ends the window
 * itself - lock, STOP, the motors read back - as soon as the board's status says RXLOSS or FAILSAFE, and at the
 * latest when no stick frame has reached the board for the shorter of 2 s and half its failsafe_delay. This end asks
 * for no CLEAR: a cut is what the rig tests. No window opens while no sticks reach the board.</li>
 * <li><b>The third throttle layer</b>: no window opens unless the board's own motor_output_limit is at or under
 * {@link Config#outputLimitMax} (board.outputLimitMax, 15) in every PID profile - {@code gradlew boardCap} writes it -
 * or when it could not be read.</li>
 * <li><b>"cannot arm: ..."</b>: the arming blocks that hold beside the keeper's lock (the battery out and bidirectional
 * DShot on: RPMFILTER) are the board's own word, said in the first lines, the line a minute, the state and OPEN's
 * answer, and never a failure of the keeper's.</li>
 * </ul>
 * <p>
 * <b>What it cannot do.</b> A keeper that is killed outright (Task Manager, a power cut) runs none of this: then the
 * board's own failsafe is what happens - on a board that holds its throttle and ARM switch, an armed board keeps its
 * last throttle for all of failsafe_delay and then runs its procedure. The board's output limit, {@code gradlew
 * boardSafe} (which sets the same lock) and the battery out are what bounds that - {@code bench.ps1 hw} ends with
 * fcKeeperEnd and boardSafe. And the lock lives in the board's memory: a restart of the board clears it, and the keeper
 * sets it again the moment the port is back.
 * <p>
 * <b>Faults</b> the rig can ask for: cut (the phone's connection closed and refused - a pulled cable), starve (the
 * phone's frames taken and not passed on), stall (the phone's side not read), delay (every frame held so long), garbage
 * to the phone; garbage to the board only when the board is the made-up one or Betaflight as a program.
 */
public final class FcKeeper implements AutoCloseable {
    /** What the keeper is told when it starts. A real board's keeper takes all of it from the hardware file ({@link #forHardware}). */
    public static final class Config {
        /** This PC's loopback ports: the rig's control channel, the phone's lease, Drone setup's lease (0: none of that kind). */
        public int controlPort, phonePort, setupPort;
        /** Where the tap's files and the keeper's own log go; null: nowhere. */
        public Path folder;
        /** The file that says a keeper is running: a second one is refused ("in use"). Null: none. */
        public Path lockFile;
        /** The armed seconds of the last hour, kept across starts; null: not kept. */
        public Path budgetFile;
        /** The pack: its cells, and the volts per cell under which nothing is armed. 0: not known - no window ever opens. */
        public int cells;
        public double cellFloor;
        /**
         * Betaflight as a program (SITL): it has no battery to read (its voltage reads 0), so the battery's rules - no
         * window under the floor, a window ended under it - are left out. Refused for a real board.
         */
        public boolean noBattery;
        /** No stick frame with more throttle than this is passed on. */
        public int ceiling = Hardware.THROTTLE_CEILING;
        /** The switch the rig believes arms the board (AUX 1 ..); the board's own word is read and a difference said. */
        public int armAux = 1;
        /** Only what the phone app really sends passes from the phone's side. Not to be switched off for a real board. */
        public boolean strict = true;
        /** An arm window's greatest length, and the armed seconds allowed in any hour. */
        public int windowMaxS = 60, budgetS = 600;
        /** No beat for this long while a window is open ends it; so does a battery under its floor for this long. */
        public long heartbeatMs = 2000, voltageLowMs = 2000;
        /** The keeper's own pace: its tick, its own questions to the board, how long an answer may take, how often an absent board is looked for. */
        public long tickMs = 50, statusEveryMs = 250, voltsEveryMs = 1000, askMs = 400, reopenMs = 500;
        /** The board's word on armed and on volts counts for this long. */
        public long staleMs = 1500, voltsStaleMs = 3500;
        /** How long a board that restarts is waited for before the keeper says it could not make it safe. */
        public long backMs = 12_000;
        /** The phone's port led down the cable again this often. */
        public long reverseEveryMs = 5000;
        /** The voltage line's pace. */
        public long voltageLineMs = 60_000;
        // >>> R3, second round: the board's own failsafe and its output limit
        /**
         * An open arm window is ended by the keeper - the lock, then STOP - as soon as the board says its receiver is
         * lost (RXLOSS, FAILSAFE), and at the latest {@link #sticksStoppedMaxMs} (or half the board's failsafe_delay,
         * whichever is shorter) after the last stick frame reached the board: the board's own failsafe never gets to
         * run. Always so on a real board, unless the hardware file says board.letFailsafeRun=true and
         * board.failsafeSeenOnSitl=true; the made-up board and Betaflight as a program let it run unless told.
         */
        public boolean failsafeEndsWindow;
        /** The word that what this board's failsafe procedure does was seen on Betaflight as a program: only with it may a real board's failsafe be let run. */
        public boolean failsafeSeenOnSitl;
        /** The "2 s" of the rule above. */
        public long sticksStoppedMaxMs = 2000;
        /**
         * The board's failsafe settings, rxfail and motor_output_limit are read in its CLI when it is first taken (and
         * after every Drone setup lease), before anybody gets a lease; the visit ends with "exit", so Betaflight
         * restarts once. Always on a real board.
         */
        public boolean readSettings;
        /** No arm window opens unless the board's motor_output_limit is at or under this in every PID profile. 0: not asked (never so on a real board). */
        public int outputLimitMax;
        /** How long an answer in the board's CLI may take. */
        public long cliMs = 3000;
        // <<< R3, second round
        /** Where the keeper's lines are said beside its log file (its console). */
        public Consumer<String> console = System.out::println;

        public Config ports(int control, int phone, int setup) {
            controlPort = control;
            phonePort = phone;
            setupPort = setup;
            return this;
        }

        public Config battery(int cellCount, double floorVoltsPerCell) {
            cells = cellCount;
            cellFloor = floorVoltsPerCell;
            return this;
        }
    }

    // ------------------------------------------------------------------ the board's side

    /** Where the board is. Opened anew each time it was away: Betaflight restarts after every CLI visit and every save. */
    public interface BoardSide {
        /** A connection to the board; an IOException while it is not there, or somebody else has it. */
        BetaflightUsb.Wire open() throws IOException;

        String name();

        /** A real flight controller: strict mode stays on, and no garbage is ever sent to it. */
        boolean real();
    }

    /**
     * The real thing: this PC's serial port of that name, opened with the parameters BetaflightUsb opens it with
     * (115200 8N1, reads that wait 100 ms for a first byte). Windows gives a COM port to one program at a time: while
     * the keeper has it, Betaflight Configurator, the rig's board tools and DRAUPNE Link all get "in use".
     */
    public static BoardSide serial(String portName) {
        return new BoardSide() {
            @Override
            public BetaflightUsb.Wire open() throws IOException {
                SerialPort port;
                try {
                    port = SerialPort.getCommPort(portName);
                } catch (RuntimeException e) {
                    throw new IOException(portName + " is not there");
                }
                port.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
                port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 1000);
                if (!port.openPort()) throw new IOException(portName + " is not there, or in use");
                port.flushIOBuffers();
                return new BetaflightUsb.Wire() {
                    @Override
                    public int read(byte[] buf) {
                        int n = port.readBytes(buf, buf.length);
                        return n == 0 && !port.isOpen() ? -1 : n; // -1: the port is gone (the board restarts, the cable is out)
                    }

                    @Override
                    public int available() {
                        return port.bytesAvailable();
                    }

                    @Override
                    public void write(byte[] data) throws IOException {
                        if (port.writeBytes(data, data.length) != data.length) throw new IOException("Could not write to the flight controller");
                    }

                    @Override
                    public boolean isOpen() {
                        return port.isOpen();
                    }

                    @Override
                    public void close() {
                        if (port.isOpen()) port.closePort();
                    }
                };
            }

            @Override
            public String name() {
                return portName;
            }

            @Override
            public boolean real() {
                return true;
            }
        };
    }

    /** Betaflight running as a program (SITL), or a test's stand-in, on a TCP port of this PC's own loopback - nowhere else. */
    public static BoardSide tcp(String host, int port) {
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (IOException e) {
            throw new IllegalArgumentException("No such address: " + host);
        }
        if (!address.isLoopbackAddress()) throw new IllegalArgumentException("The keeper reaches a board over TCP on this PC's loopback only, not " + host);
        return new BoardSide() {
            @Override
            public BetaflightUsb.Wire open() throws IOException {
                Socket socket = new Socket();
                try {
                    socket.setTcpNoDelay(true);
                    socket.connect(new InetSocketAddress(address, port), 1000);
                    socket.setSoTimeout(100);
                } catch (IOException e) {
                    quietly(socket);
                    throw new IOException(name() + " is not there (" + e.getMessage() + ")");
                }
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                return new BetaflightUsb.Wire() {
                    @Override
                    public int read(byte[] buf) throws IOException {
                        try {
                            return in.read(buf);
                        } catch (SocketTimeoutException nothingYet) {
                            return 0;
                        } catch (IOException e) {
                            if (socket.isClosed()) return -1;
                            throw e;
                        }
                    }

                    @Override
                    public int available() throws IOException {
                        return in.available();
                    }

                    @Override
                    public void write(byte[] data) throws IOException {
                        out.write(data);
                        out.flush();
                    }

                    @Override
                    public boolean isOpen() {
                        return !socket.isClosed();
                    }

                    @Override
                    public void close() {
                        quietly(socket);
                    }
                };
            }

            @Override
            public String name() {
                return "tcp:" + host + ":" + port;
            }

            @Override
            public boolean real() {
                return false;
            }
        };
    }

    /** The made-up board, for the tests: its port as a COM port is - one opener at a time, "in use" for the next. */
    public static BoardSide madeUp(FakeFlightBoard board) {
        return new BoardSide() {
            @Override
            public BetaflightUsb.Wire open() throws IOException {
                return board.usb().open(board.port());
            }

            @Override
            public String name() {
                return board.port().systemName();
            }

            @Override
            public boolean real() {
                return false;
            }
        };
    }

    // ------------------------------------------------------------------ what the phone's side may send

    /**
     * Strict mode's list: every MSP request DRAUPNE Flight sends a Betaflight board (flight-app/overlay, Flight's
     * Msp.java: each number below is the argument of a getMspRequest there; {@code FcKeeperStrictTest} reads that file
     * and holds this list to it), with the fewest and the most payload bytes it sends with it. The three it sends an
     * INAV board alone (MSP2_INAV_STATUS, _ANALOG, _MIXER) are not on it.
     */
    static final Map<Integer, int[]> PHONE_CODES = phoneCodes();

    private static Map<Integer, int[]> phoneCodes() {
        Map<Integer, int[]> m = new TreeMap<>();
        int[] none = {0, 0};
        for (int code : new int[]{FcCommon.MSP_API_VERSION, FcCommon.MSP_FC_VARIANT, FcCommon.MSP_FC_VERSION, FcCommon.MSP_MIXER_CONFIG, // getMspApiVersion, getFcVariant, getFcVersion, getMixerConfig: until it knows the board
                FcCommon.MSP_RX_MAP,                                   // getRxMap: every tick until the channel map is known - no sticks before
                FcCommon.MSP_STATUS, FcCommon.MSP_ALTITUDE,            // getStatus, getAltitude: twice a second, once a second in standby
                FcCommon.MSP_ATTITUDE,                                 // getAttitude: every tick
                ArmRanges.MSP_MODE_RANGES, BeeperQuiet.MSP_BEEPER_CONFIG, // getModeRanges, getBeeperConfig: once a second
                FcCommon.MSP_BATTERY_STATE, FcCommon.MSP_RAW_GPS, FcCommon.MSP_COMP_GPS, FcCommon.MSP_VTX_CONFIG, FcCommon.MSP_ANALOG, // once a second
                FcCommon.MSP_OSD_CONFIG, FcCommon.MSP_OSD_CANVAS, FcCommon.MSP_BATTERY_CONFIG, // getOsdConfig, getOsdCanvas, getBatteryConfig: when the PC asks
                BeeperBack.MSP_UID}) {                                 // getBoardId (F-069): with the slow requests until the board has said which board it is
            m.put(code, none);
        }
        m.put((int) FcCommon.MSP_BOXIDS, new int[]{0, 1});             // getBoxIds(page)
        m.put((int) FcCommon.MSP_BOXNAMES, new int[]{0, 1});           // getBoxNames(page): when the PC asks
        m.put(BeeperQuiet.MSP_SET_BEEPER_CONFIG, new int[]{9, 9});     // quietBeeper, writeBeeperConfig: around a sleep, in memory only
        m.put((int) FcCommon.MSP_SET_RAW_RC, new int[]{8, 2 * FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT}); // setRawRc: the sticks, 4 to 18 channels
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Why a frame from the phone's side is not passed on; null: it is. */
    String refusal(MspTap.Frame f) {
        if (!f.request()) return "it is no request";
        if (!f.v2()) return "it is framed as MSP v1 (the phone app asks in v2; v1 is the keeper's own)";
        if (!config.strict) return null;
        if (f.flag() != 0) return "its v2 flag is " + f.flag();
        int[] payload = PHONE_CODES.get(f.code());
        if (payload == null) return "MSP " + f.code() + " is not among what the phone app sends";
        int n = f.payload().length;
        if (n < payload[0] || n > payload[1] || f.code() == FcCommon.MSP_SET_RAW_RC && n % 2 != 0) {
            return "MSP " + f.code() + " with " + n + " payload bytes is not as the phone app sends it (" + payload[0] + " to " + payload[1] + ")";
        }
        return null;
    }

    // ------------------------------------------------------------------ what it says

    public enum Kind {
        INFO, FINDING, TRIP
    }

    /** Something the keeper has to say: what it did, what it found, what made it close the window. */
    public record Event(long atMs, Kind kind, String text) {
        String line() {
            return kind + " " + atMs + " " + text;
        }
    }

    /**
     * The board as the keeper left it.
     * @param safe   locked (the block shows in the board's status), disarmed, and every motor read back at rest
     * @param motors the motor outputs read back; null when the board did not answer
     */
    public record Safe(boolean safe, boolean locked, boolean armed, int[] motors, boolean atRest, String text) {
        /** As the control channel says it. */
        String line() {
            return "safe=" + flag(safe) + " locked=" + flag(locked) + " armed=" + flag(armed) + " atRest=" + flag(atRest) + " motors="
                    + (motors == null ? "?" : Arrays.toString(motors).replace(" ", "")) + " " + text;
        }
    }

    /**
     * Which raw channel of a stick frame the board reads its throttle from, and where ARM sits: {raw channel, from, to
     * (exclusive), AUX number}; and the AUX number of the PREARM switch, if the board has one (0: none) - with one,
     * Betaflight arms only while it is on.
     */
    private record Sticks(int throttleAt, int[][] arm, int prearmAux, String said) {
        boolean armOn(int[] channels) {
            for (int[] slot : arm) {
                if (slot[0] >= channels.length) continue;
                int v = Math.max(900, Math.min(2099, channels[slot[0]])); // as Betaflight's isRangeActive
                if (v >= slot[1] && v < slot[2]) return true;
            }
            return false;
        }

        int armValue(int[] channels) {
            return arm.length > 0 && arm[0][0] < channels.length ? channels[arm[0][0]] : -1;
        }
    }

    /**
     * What the board itself is set to do when its sticks stop, and how much it may give its motors (second round): read
     * in its CLI where the keeper is told to ({@link Config#readSettings}; a real board's always), else what
     * MSP_FAILSAFE_CONFIG says of the failsafe alone.
     *
     * @param procedure    failsafe_procedure: "AUTO-LANDING", "DROP", "GPS-RESCUE"; null: not known
     * @param delay        failsafe_delay in tenths of a second - how long stage 1 lasts; -1: not known
     * @param rxfail       "0 a", "3 h", "4 s 1000" ... as the CLI lists them; empty: not read
     * @param profile      the PID profile in use; -1: not known
     * @param limits       motor_output_limit in each PID profile (0 first); empty: not read
     * @param limitProblem why the limits were not read
     * @param byCli        read in the CLI (the failsafe's settings at least)
     */
    private record Settings(String procedure, int delay, int offDelay, int throttle, String switchMode, int lowDelay, List<String> rxfail, int profile,
                            List<Integer> limits, String limitProblem, boolean byCli) {
        /** The largest motor_output_limit of all PID profiles (Betaflight changes profiles by itself); -1: not read. */
        int maxLimit() {
            int max = -1;
            for (int v : limits) max = Math.max(max, v);
            return max;
        }

        int maxProfile() {
            return limits.indexOf(maxLimit());
        }

        /** What rxfail gives this channel (roll 0, pitch 1, yaw 2, throttle 3, AUX1 4 ..) while the receiver is lost: "h", "a", "s 1000"; null: not read. */
        String rxfailOf(int channel) {
            for (String line : rxfail) if (line.startsWith(channel + " ")) return line.substring(line.indexOf(' ') + 1);
            return null;
        }

        static String seconds(int tenths) {
            return tenths / 10 + "." + tenths % 10 + " s";
        }
    }

    private static final String[] PROCEDURES = {"AUTO-LANDING", "DROP", "GPS-RESCUE"}, SWITCH_MODES = {"STAGE1", "KILL", "STAGE2"};
    private static final java.util.regex.Pattern RXFAIL_LINE = java.util.regex.Pattern.compile("(?m)^rxfail (\\d+ [ahs](?: \\d+)?)\\s*$"),
            PROFILE_LINE = java.util.regex.Pattern.compile("(?m)^profile (\\d+)\\s*$");

    /** The answer the keeper waits for: one question at a time. */
    private static final class Waiting {
        final int code;
        final CompletableFuture<byte[]> answer = new CompletableFuture<>();

        Waiting(int code) {
            this.code = code;
        }
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"), CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final byte[] LOCK = {1}, UNLOCK = {0};
    /** Exit codes of the process: the board left safe; it could not start; the board NOT known safe; ended by an error of its own (board safe); no keeper answered. */
    public static final int EXIT_SAFE = 0, EXIT_NO_START = 2, EXIT_NOT_SAFE = 3, EXIT_ERROR = 5, EXIT_NO_KEEPER = 4;

    private final Config config;
    private final BoardSide side;
    private final PhoneAdb adb;
    private MspTap tap;
    private final List<Event> events = new ArrayList<>();
    private final Map<String, Integer> findingCounts = new LinkedHashMap<>();
    private BufferedWriter log;
    private FileChannel lockChannel;
    private FileLock fileLock;
    private ServerSocket controlListener, phoneListener, setupListener;
    /** The rig's connections to the control channel; and, on a control thread, its own. */
    private final java.util.Set<Socket> controlSockets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Socket> controlSocket = new ThreadLocal<>();

    /** Lock order: endLock, windowLock, openLock, askLock, boardOut; boardState and the small ones innermost. */
    private final Object endLock = new Object(), windowLock = new Object(), openLock = new Object(), askLock = new Object(), boardOut = new Object(),
            boardState = new Object();
    private volatile BetaflightUsb.Wire wire;
    private volatile boolean ready, ending, started, failed;
    private volatile Sticks sticks;
    private volatile int lockBit = -1, stopValue = MotorTest.STOP;
    private volatile String boardSays = "", awayBecause = "", scenario = "";
    /** The firmware the lock's place was measured on; and what was last said of a board let go, and when. */
    private String lockMeasuredOn = "", lastGone = "";
    private long lastGoneNs;
    // ---- second round: the board's own settings, and its word on its receiver
    /** What the board is set to do when its sticks stop and how much it gives its motors; null: not read yet. */
    private volatile Settings settings;
    /** The board's settings are to be read in its CLI when it is next taken: at the start, after Drone setup had it, for another board. */
    private volatile boolean needSettings;
    private int settingsTries;
    /** The board's own id as it last said it: compared, to know another board when one is plugged in - never written down. */
    private byte[] boardUid;
    /** While the keeper is in the board's CLI, what the board says there (the reader hands it over); null: it is not. */
    private volatile StringBuilder cliSink;
    private volatile long cliAtNs;
    /** Where the mode box FAILSAFE is among MSP_STATUS' mode flags (-1: not known), and whether it is on. */
    private volatile int failsafeBox = -1;
    private volatile boolean failsafeBoxOn;
    /** When the last stick frame was written to the board. */
    private volatile long lastStickNs;
    /** Why the keeper itself ended the last arm window; null: it did not. */
    private volatile String lastEnd;
    private volatile Waiting waiting;
    private long nextOpenNs;
    /** When "exit" was last sent to a board that answered no MSP; long ago: never. */
    private long lastExitNs = Long.MIN_VALUE / 2;
    private int pollFailures;

    // the board's own word
    private volatile boolean statusKnown, armed, voltsKnown;
    private volatile int flags = -1;
    private volatile long statusNs, voltsNs;
    private volatile double volts = Double.NaN;
    private volatile int lastThrottle = -1, lastArm = -1;
    private volatile boolean armSwitchOn;

    // the window and what ends it
    private volatile boolean windowOpen, lostInWindow;
    private volatile long windowEndNs, beatNs;
    private volatile boolean beaten;
    private volatile String tripped;
    private String lastTripKey = "";
    private long lastTripNs, lowSinceNs, lastVoltageLineNs, lastRowNs;
    private boolean low, wasArmed;
    private int tripCount, findingCount;
    private final ArrayDeque<long[]> armedStretches = new ArrayDeque<>();
    private long armedSinceMs;

    // the leases
    private volatile PhoneConn phone;
    private volatile SetupConn setup;
    private volatile int phoneRefusals;
    private volatile String lastPhoneRefusal = "";

    // faults
    private volatile boolean cut, starve, stall;
    private volatile long delayMs, cutEndNs, starveEndNs, stallEndNs, delayEndNs;
    private final Hold toBoardHold = new Hold("keeperHoldToBoard"), toPhoneHold = new Hold("keeperHoldToPhone");
    private final Random noise = new Random(11);

    // the end
    private volatile Safe result;
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile String reverseSaid = "";

    /**
     * @param adb the phone on adb, for the reverse port; null: no phone on a cable (a test's plain TCP client stands in)
     */
    public FcKeeper(Config config, BoardSide side, PhoneAdb adb) {
        this.config = config;
        this.side = side;
        this.adb = adb;
        if (side.real() && !config.strict) throw new IllegalArgumentException("Strict mode is not switched off for a real board");
        if (config.windowMaxS < 1 || config.windowMaxS > 60) throw new IllegalArgumentException("An arm window is 60 s at most: " + config.windowMaxS);
        if (side.real() && config.noBattery) throw new IllegalArgumentException("A real board's battery is always read: noBattery is for Betaflight as a program only");
        // >>> second round: what a real board's keeper is never without
        if (side.real() && !config.failsafeEndsWindow && !config.failsafeSeenOnSitl) {
            throw new IllegalArgumentException("On a real board the keeper ends an arm window itself when the sticks stop, before the board's own failsafe can run; "
                    + "that is switched off only with board.letFailsafeRun=true AND board.failsafeSeenOnSitl=true in the hardware file");
        }
        if (side.real() && !config.readSettings) {
            throw new IllegalArgumentException("A real board's failsafe settings and output limit are always read in its CLI before anybody gets a lease (readSettings)");
        }
        if (config.outputLimitMax < 0 || config.outputLimitMax > 100 || side.real() && config.outputLimitMax == 0) {
            throw new IllegalArgumentException("board.outputLimitMax is per cent, 1 to 100 (a real board gets no arm window without one): " + config.outputLimitMax);
        }
        needSettings = config.readSettings;
        // <<< second round
    }

    // ------------------------------------------------------------------ start

    /**
     * The lock file taken, the three ports bound, the keeper's threads started; the board is looked for from now on.
     * @throws IOException "in use" when another keeper runs, or a port that is taken: nothing was started then
     */
    public synchronized FcKeeper start() throws IOException {
        if (started) throw new IllegalStateException("started already");
        try {
            takeLockFile();
            InetAddress loopback = InetAddress.getLoopbackAddress();
            controlListener = new ServerSocket(config.controlPort, 16, loopback);
            phoneListener = new ServerSocket(config.phonePort, 4, loopback);
            setupListener = new ServerSocket(config.setupPort, 4, loopback);
        } catch (IOException | RuntimeException e) {
            quietly(controlListener);
            quietly(phoneListener);
            quietly(setupListener);
            releaseLockFile();
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
        started = true;
        tap = new MspTap(config.folder);
        if (config.folder != null) {
            try {
                Files.createDirectories(config.folder);
                log = Files.newBufferedWriter(config.folder.resolve("keeper-" + LocalDateTime.now().format(STAMP) + "-" + ProcessHandle.current().pid() + "-"
                        + controlPort() + ".log"), StandardCharsets.UTF_8);
            } catch (IOException e) {
                config.console.accept("The keeper's log could not be made in " + config.folder + ": " + e.getMessage());
            }
        }
        loadBudget();
        writeLockFile();
        beatNs = lastRowNs = lastVoltageLineNs = clock();
        event(Kind.INFO, "THE KEEPER IS UP: board " + side.name() + (side.real() ? "" : " (not a real board)") + ", control 127.0.0.1:" + controlPort()
                + ", the phone's lease on 127.0.0.1:" + phonePort() + ", Drone setup's on 127.0.0.1:" + setupPort() + ", " + (config.strict ? "strict" : "NOT strict")
                + ", throttle ceiling " + config.ceiling + ", " + (config.noBattery ? "no battery (Betaflight as a program): the battery's rules are left out"
                : config.cells > 0 && config.cellFloor > 0 ? config.cells + " cells, floor " + config.cellFloor + " V a cell"
                : "no battery floor known: no arm window will open") + ", windows of " + config.windowMaxS + " s at most, " + config.budgetS
                + " armed seconds an hour (" + budgetUsedMs(System.currentTimeMillis()) / 1000 + " used)." + secondRoundWords());
        thread("keeperWatch", this::watch);
        thread("keeperControl", this::acceptControl);
        thread("keeperPhone", this::acceptPhone);
        thread("keeperSetup", this::acceptSetup);
        if (adb != null) thread("keeperAdb", this::reverseLoop);
        return this;
    }

    public int controlPort() {
        return controlListener.getLocalPort();
    }

    public int phonePort() {
        return phoneListener.getLocalPort();
    }

    public int setupPort() {
        return setupListener.getLocalPort();
    }

    private void thread(String name, Runnable body) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable error) {
                fatal(name, error);
            }
        }, name);
        t.setDaemon(true);
        if (name.equals("keeperWatch")) t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    /** The tap's clock: nanoseconds, never 0. */
    private long clock() {
        return tap.now();
    }

    private static boolean since(long thenNs, long nowNs, long ms) {
        return nowNs - thenNs >= ms * 1_000_000L;
    }

    // ------------------------------------------------------------------ the lock file

    private void takeLockFile() throws IOException {
        if (config.lockFile == null) return;
        if (config.lockFile.getParent() != null) Files.createDirectories(config.lockFile.getParent());
        lockChannel = FileChannel.open(config.lockFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            // one byte far behind the text: the text itself stays readable to whoever wants to know who has the board
            fileLock = lockChannel.tryLock(Long.MAX_VALUE - 1, 1, false);
        } catch (OverlappingFileLockException sameProgram) {
            fileLock = null;
        }
        if (fileLock == null) {
            String who;
            try {
                who = Files.readString(config.lockFile, StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                who = "";
            }
            quietly(lockChannel);
            lockChannel = null;
            throw new IOException("in use: a keeper has the board already (" + (who.isEmpty() ? config.lockFile : who) + "). End it first: gradlew fcKeeperEnd");
        }
    }

    private void writeLockFile() {
        if (lockChannel == null) return;
        try {
            lockChannel.truncate(0);
            lockChannel.write(ByteBuffer.wrap(("keeper pid " + ProcessHandle.current().pid() + ", board " + side.name() + ", control 127.0.0.1:" + controlPort()
                    + ", since " + LocalDateTime.now().withNano(0)).getBytes(StandardCharsets.UTF_8)), 0);
            lockChannel.force(false);
        } catch (IOException ignored) {
            // who has it is a courtesy: the lock itself is what refuses a second keeper
        }
    }

    private void releaseLockFile() {
        try {
            if (lockChannel != null) lockChannel.truncate(0);
            if (fileLock != null) fileLock.release();
        } catch (IOException ignored) {
        }
        quietly(lockChannel);
        fileLock = null;
        lockChannel = null;
    }

    // ------------------------------------------------------------------ events

    private void event(Kind kind, String text) {
        Event e = new Event(System.currentTimeMillis(), kind, text);
        String line = LocalDateTime.now().format(CLOCK) + " " + (kind == Kind.INFO ? "" : kind + ": ") + text;
        synchronized (events) {
            events.add(e);
            if (kind == Kind.TRIP) tripCount++;
            if (kind == Kind.FINDING) findingCount++;
            if (log != null) {
                try {
                    log.write(line);
                    log.newLine();
                    log.flush();
                } catch (IOException ignored) {
                }
            }
        }
        MspTap t = tap;
        if (t != null) t.event((kind == Kind.INFO ? "" : kind + ": ") + text);
        config.console.accept("keeper " + line);
    }

    /** Something that should not be: said the first three times it happens, then every 500th, with the count. */
    private void finding(String key, String text) {
        int n;
        synchronized (findingCounts) {
            n = findingCounts.merge(key, 1, Integer::sum);
        }
        if (n <= 3 || n % 500 == 0) event(Kind.FINDING, text + (n > 1 ? " (" + n + " times now)" : ""));
    }

    public List<Event> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /** How often each kind of finding was met. */
    public Map<String, Integer> findings() {
        synchronized (findingCounts) {
            return new LinkedHashMap<>(findingCounts);
        }
    }

    // ------------------------------------------------------------------ the keeper's own questions to the board

    /** One MSP v1 request and its answer's payload. One at a time; never while Drone setup has the board. */
    private byte[] ask(int code, byte[] payload) throws IOException {
        synchronized (askLock) {
            BetaflightUsb.Wire w = wire;
            if (w == null) throw new IOException("the board is away");
            if (setup != null) throw new IOException("Drone setup has the board");
            Waiting mine = new Waiting(code);
            waiting = mine;
            try {
                byte[] raw = MspFrames.v1Request(code, payload);
                MspTap.Frame f = new MspTap.Frame(MspFrames.V1, MspFrames.REQUEST, 0, code, payload == null ? new byte[0] : payload, raw);
                long in = clock();
                writeBoard(w, raw);
                tap.seen(new MspTap.Seen(in, clock(), MspTap.Way.KEEPER_TO_BOARD, f, -1, -1, null));
                return mine.answer.get(config.askMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new IOException("no answer to MSP " + code + " within " + config.askMs + " ms");
            } catch (ExecutionException e) {
                throw new IOException(String.valueOf(e.getCause().getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            } finally {
                waiting = null;
            }
        }
    }

    private void writeBoard(BetaflightUsb.Wire w, byte[] bytes) throws IOException {
        try {
            synchronized (boardOut) {
                w.write(bytes);
            }
        } catch (IOException | RuntimeException e) {
            boardGone(w, "a write to it failed (" + e.getMessage() + ")");
            throw new IOException("the board is away");
        }
    }

    /** The arming block set; tried three times. */
    private void lockOn() throws IOException {
        IOException last = null;
        for (int i = 0; i < 3; i++) {
            try {
                ask(Msp.ARMING_DISABLE, LOCK);
                return;
            } catch (IOException e) {
                last = e;
                if (wire == null) break;
            }
        }
        throw last;
    }

    private void readStatus() throws IOException {
        byte[] status = ask(Msp.STATUS, null); // the reader has taken it in already: armed, the flags
        if (status.length <= 6) throw new IOException("a status answer of " + status.length + " bytes");
    }

    private boolean lockedNow() {
        int bit = lockBit, f = flags;
        return bit >= 0 && f != -1 && (f >> bit & 1) != 0;
    }

    private void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ the board taken, and lost

    /**
     * The board opened and taken: the lock first of all, then who it is, whether it is armed, where the lock shows,
     * how it reads its sticks, STOP to its motors. Only then does anybody get a lease.
     */
    private void tryOpen() {
        synchronized (openLock) {
            if (wire != null) return;
            BetaflightUsb.Wire w;
            try {
                w = side.open();
            } catch (IOException | RuntimeException e) {
                away(String.valueOf(e.getMessage()));
                return;
            }
            synchronized (boardState) {
                wire = w;
                ready = false;
            }
            thread("keeperBoardReader", () -> readBoard(w));
            try {
                try {
                    lockOn();
                } catch (IOException silent) {
                    // The port is there and nothing answers MSP: a tool may have died in the CLI (the rig's own board tools
                    // do the same for it, BoardPort). Enter, then exit: outside the CLI neither does anything - Betaflight
                    // looks only for '#' and 'R' there, and there is none in these.
                    if (wire == w && since(lastExitNs, clock(), 10_000)) {
                        lastExitNs = clock();
                        event(Kind.INFO, side.name() + " answers no MSP: Enter and \"exit\" sent in case a tool left its CLI open (Betaflight then restarts).");
                        writeBoard(w, "\nexit\n".getBytes(StandardCharsets.US_ASCII));
                    }
                    throw silent;
                }
                String variant = new String(ask(Msp.FC_VARIANT, null), StandardCharsets.US_ASCII).trim();
                if (!variant.startsWith("BTFL")) throw new IOException("it runs " + variant + ", not Betaflight");
                byte[] v = ask(Msp.FC_VERSION, null);
                String version = v.length >= 3 ? (v[0] & 0xff) + "." + (v[1] & 0xff) + "." + (v[2] & 0xff) : "?";
                readStatus();
                if (armed) {
                    event(Kind.TRIP, "THE BOARD ON " + side.name() + " WAS FOUND ARMED when the keeper took it: the arming lock is set.");
                    long end = clock() + 1_000_000_000L;
                    while (armed && clock() - end < 0) {
                        pause(50);
                        lockOn();
                        readStatus();
                    }
                    if (armed) throw new IOException("it is ARMED and the arming lock does not disarm it - take the battery out");
                }
                // >>> second round: the board's own settings, before anybody gets a lease
                // another board than the one before (by its own id: compared, never said) is read anew
                byte[] uid;
                try {
                    uid = ask(Msp.UID, null);
                } catch (IOException notSaid) {
                    uid = boardUid; // a firmware that does not say: taken for the board it was (one that is gone fails at the next question)
                }
                if (boardUid != null && uid != null && !Arrays.equals(uid, boardUid)) {
                    event(Kind.INFO, "Another board than the one before is on " + side.name() + ": where its lock shows and its settings are read anew.");
                    lockMeasuredOn = "";
                    settings = null;
                    needSettings = config.readSettings;
                    settingsTries = 0;
                }
                boardUid = uid;
                int[] byMsp = failsafeByMsp();
                if (needSettings) {
                    // one visit to its CLI: nothing is set there, and it is left with "exit" - Betaflight restarts, and the
                    // board is taken once more (locked first, as ever) with this known
                    Settings read;
                    try {
                        read = readSettings(w, byMsp);
                    } catch (IOException cut) {
                        if (++settingsTries < 3) {
                            if (wire == w) writeBoard(w, "\nexit\n".getBytes(StandardCharsets.US_ASCII)); // out of its CLI, should it be in there
                            throw new IOException("its CLI could not be read (" + cut.getMessage() + "): once more");
                        }
                        read = new Settings(byMsp == null ? null : PROCEDURES[byMsp[5]], byMsp == null ? -1 : byMsp[0], -1, -1, null, -1, List.of(), -1, List.of(),
                                "its CLI could not be read: " + cut.getMessage(), false);
                        event(Kind.FINDING, side.name() + ": the board's CLI could not be read in three visits (" + cut.getMessage() + "): its motor_output_limit and "
                                + "rxfail are not known.");
                    }
                    settings = read;
                    needSettings = false;
                    settingsTries = 0;
                    if (wire == w) writeBoard(w, "\nexit\n".getBytes(StandardCharsets.US_ASCII));
                    pause(300); // (as the app does it: a moment for the word to be out before the port is let go)
                    if (wire == w) boardGone(w, "its settings were read in its CLI: Betaflight restarts");
                    nextOpenNs = clock();
                    return;
                }
                if (!config.readSettings) { // not asked into its CLI (the made-up board, Betaflight as a program): what MSP says of the failsafe
                    settings = new Settings(byMsp == null ? null : PROCEDURES[byMsp[5]], byMsp == null ? -1 : byMsp[0], byMsp == null ? -1 : byMsp[1],
                            byMsp == null ? -1 : byMsp[2], byMsp == null ? null : SWITCH_MODES[byMsp[3]], byMsp == null ? -1 : byMsp[4], List.of(), -1, List.of(),
                            "its CLI was not asked", false);
                }
                // <<< second round
                // where the lock shows is measured once for a firmware (it takes the lock off for a moment); after that the
                // board, taken again, only has to show it there
                String firmware = variant + " " + version;
                if (lockBit < 0 || !firmware.equals(lockMeasuredOn) || !lockedNow()) {
                    measureLock();
                    lockMeasuredOn = firmware;
                }
                int features = Msp.u32(ask(Msp.FEATURE_CONFIG, null), 0);
                stopValue = (features & Msp.FEATURE_3D) != 0 ? Msp.STOP_3D : MotorTest.STOP;
                Sticks s = readSticks();
                failsafeBox = failsafeBoxIndex();
                String failsafe = failsafeWords(s) + (limitWords().isEmpty() ? "" : "; " + limitWords());
                stopMotors();
                int[] motors = motors();
                boolean rest = BoardSafe.atRest(motors, stopValue);
                try {
                    ask(Msp.ANALOG, null);
                } catch (IOException noVolts) {
                    // the watch says so every minute
                }
                boardSays = variant + "_" + version;
                sticks = s;
                pollFailures = 0;
                String lock = lockBit < 0 ? "THE ARMING LOCK DOES NOT SHOW IN ITS STATUS: no arm window opens on this board"
                        : "the arming lock is set and shows as arming-disable flag " + lockBit + (lockBit == Msp.ARMING_DISABLED_MSP_BIT ? ""
                        : " (Betaflight 4.4 has it at " + Msp.ARMING_DISABLED_MSP_BIT + ": another firmware?)");
                synchronized (boardState) {
                    if (wire != w) throw new IOException("it went away again");
                    ready = true;
                    awayBecause = "";
                }
                event(lockBit < 0 || !rest ? Kind.FINDING : Kind.INFO, "The board is taken: " + side.name() + ", " + variant + " " + version + ", disarmed; " + lock
                        + "; " + s.said() + "; " + failsafe + "; motors read " + Arrays.toString(motors) + (rest ? " (at rest)" : " (NOT AT REST)") + "; "
                        + voltageWords() + "; " + cannotArmWords() + ".");
            } catch (IOException | RuntimeException e) {
                if (wire == w) boardGone(w, "it could not be taken (" + e.getMessage() + ")");
            }
        }
    }

    /** (Second round.) What the keeper does about the board's own failsafe and output limit: the end of its first line. */
    private String secondRoundWords() {
        return " When the sticks stop in an arm window " + (config.failsafeEndsWindow ? "the keeper ends it itself - the lock, STOP - before the board's own failsafe can run."
                : side.real() ? "THE BOARD'S OWN FAILSAFE IS LET RUN (board.letFailsafeRun, board.failsafeSeenOnSitl)." : "the board's own failsafe runs (not a real board).")
                + (config.outputLimitMax > 0 ? " No arm window on a board whose motor_output_limit is over " + config.outputLimitMax
                + " (board.outputLimitMax; gradlew boardCap writes it)." : "")
                + (config.readSettings ? " The board's settings are read in its CLI first: Betaflight restarts once before the board is taken." : "");
    }

    /** An absent board is said once for each reason, not twice a second. */
    private void away(String why) {
        if (!why.equals(awayBecause)) {
            awayBecause = why;
            event(Kind.INFO, "No board: " + why + " - looked for every " + config.reopenMs + " ms.");
        }
    }

    /**
     * Where Betaflight shows the arming block "MSP" among its arming-disable flags: the block taken off, the flags
     * read, the block set, the flags read - the one bit that came is it. Nobody has a lease at this moment, so nothing
     * can arm the board while the block is off. It always ends with the block set.
     */
    private void measureLock() throws IOException {
        int candidates = -1;
        lockBit = -1;
        try {
            for (int round = 0; round < 3; round++) {
                ask(Msp.ARMING_DISABLE, UNLOCK);
                readStatus();
                int off = flags;
                ask(Msp.ARMING_DISABLE, LOCK);
                readStatus();
                int on = flags;
                if (off == -1 || on == -1) return; // a status without the flags: not known
                candidates &= on & ~off;
                if (Integer.bitCount(candidates) <= 1) break; // more than one: another flag moved in the same moment - once more
            }
        } finally {
            lockOn();
        }
        if (Integer.bitCount(candidates) == 1) lockBit = Integer.numberOfTrailingZeros(candidates);
        // three times another flag moved with it (the ARM switch's own block does, when the switch is on): then the place Betaflight 4.4 has it, if that moved too
        else if (candidates != -1 && (candidates >> Msp.ARMING_DISABLED_MSP_BIT & 1) != 0) lockBit = Msp.ARMING_DISABLED_MSP_BIT;
        readStatus();
        if (lockBit >= 0 && !lockedNow()) lockBit = -1;
    }

    private Sticks readSticks() throws IOException {
        byte[] map = ask(Msp.RX_MAP, null);
        if (map.length < 4) throw new IOException("a channel map of " + map.length + " bytes");
        // Betaflight's own order is roll, pitch, yaw, throttle, AUX1 ..; the map says which raw channel each is read from
        int throttleAt = map[3] & 0xff;
        byte[] ranges = ask(Msp.MODE_RANGES, null); // a slot: mode id, AUX index, start and end in steps of 25 from 900
        int[][] slots = ArmRanges.parse(ranges);
        if (slots == null) throw new IOException("its mode ranges are not understood");
        int prearmAux = 0;
        String prearm = "";
        for (int i = 0; i + 3 < ranges.length && prearmAux == 0; i += 4) {
            if ((ranges[i] & 0xff) != 36 || (ranges[i + 2] & 0xff) >= (ranges[i + 3] & 0xff)) continue; // 36: Betaflight's PREARM
            prearmAux = (ranges[i + 1] & 0xff) + 1;
            prearm = ", PREARM on AUX" + prearmAux + " " + (900 + 25 * (ranges[i + 2] & 0xff)) + "-" + (900 + 25 * (ranges[i + 3] & 0xff));
        }
        int[][] arm = new int[slots.length][];
        StringBuilder where = new StringBuilder();
        boolean asExpected = false;
        for (int i = 0; i < slots.length; i++) {
            int channel = 4 + slots[i][0];
            arm[i] = new int[]{channel < map.length ? map[channel] & 0xff : channel, slots[i][1], slots[i][2], slots[i][0] + 1};
            where.append(i > 0 ? " and " : "").append("AUX").append(arm[i][3]).append(" ").append(arm[i][1]).append("-").append(arm[i][2]);
            asExpected |= arm[i][3] == config.armAux;
        }
        String letters = "AERT1234";
        char[] order = new char[Math.min(8, map.length)];
        Arrays.fill(order, '?');
        for (int i = 0; i < order.length; i++) if ((map[i] & 0xff) < order.length) order[map[i] & 0xff] = letters.charAt(i);
        String said = "channel map " + new String(order) + " (throttle is raw channel " + (throttleAt + 1) + "), ARM on "
                + (arm.length == 0 ? "NO SWITCH" : where) + (asExpected ? "" : " - NOT on AUX" + config.armAux + " as the rig was told") + prearm;
        return new Sticks(throttleAt, arm, prearmAux, said);
    }

    // ------------------------------------------------------------------ second round: the board's own settings

    /**
     * MSP_FAILSAFE_CONFIG as the keeper reads it - failsafe_delay, failsafe_off_delay, failsafe_throttle,
     * failsafe_switch_mode, failsafe_throttle_low_delay, failsafe_procedure, in that order (1, 1, 2, 1, 2 and 1 bytes) -
     * or null when the answer is not of that shape. Nothing else in this repo reads that request, so on a real board
     * its word is held to the CLI's ({@link #readSettings}), and the CLI's is the one taken.
     */
    private int[] failsafeByMsp() {
        try {
            byte[] f = ask(Msp.FAILSAFE_CONFIG, null);
            if (f.length != 8) return null;
            int[] v = {f[0] & 0xff, f[1] & 0xff, Msp.u16(f, 2), f[4] & 0xff, Msp.u16(f, 5), f[7] & 0xff};
            return v[0] < 1 || v[0] > 200 || v[3] >= SWITCH_MODES.length || v[5] >= PROCEDURES.length ? null : v;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The one visit to the board's CLI: its failsafe settings, rxfail, and motor_output_limit in every PID profile (it
     * is a profile's own, and Betaflight changes profiles by itself). Nothing is set; the PID profile in use is
     * selected again at the end, and the caller says "exit". From the '#' on, what the board says on this connection is
     * its CLI's text ({@link #cliSink}) - no MSP is asked or answered until Betaflight has restarted.
     *
     * @throws IOException the CLI did not open, or an answer did not come whole: nothing of it is taken then
     */
    private Settings readSettings(BetaflightUsb.Wire w, int[] byMsp) throws IOException {
        StringBuilder sink = new StringBuilder();
        cliSink = sink;
        cli(w, sink, "#");
        java.util.regex.Matcher inUse = PROFILE_LINE.matcher(cli(w, sink, "profile"));
        int profile = inUse.find() ? Integer.parseInt(inUse.group(1)) : -1;
        String procedure = setting(cli(w, sink, "get failsafe_procedure"), "failsafe_procedure");
        int delay = number(setting(cli(w, sink, "get failsafe_delay"), "failsafe_delay")), offDelay = number(setting(cli(w, sink, "get failsafe_off_delay"), "failsafe_off_delay")),
                throttle = number(setting(cli(w, sink, "get failsafe_throttle"), "failsafe_throttle"));
        String switchMode = setting(cli(w, sink, "get failsafe_switch_mode"), "failsafe_switch_mode");
        int lowDelay = number(setting(cli(w, sink, "get failsafe_throttle_low_delay"), "failsafe_throttle_low_delay"));
        List<String> rxfail = new ArrayList<>();
        java.util.regex.Matcher line = RXFAIL_LINE.matcher(cli(w, sink, "rxfail"));
        while (line.find()) rxfail.add(line.group(1));
        List<Integer> limits = new ArrayList<>();
        String limitProblem = "";
        for (int i = 0; i < 8 && limitProblem.isEmpty(); i++) { // (no Betaflight has more PID profiles)
            if (cli(w, sink, "profile " + i).contains("###ERROR")) break; // one more than the board has
            String said = cli(w, sink, "get motor_output_limit");
            int limit = number(setting(said, "motor_output_limit"));
            if (limit < 0) limitProblem = "its CLI: " + said.lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("no answer");
            else limits.add(limit);
        }
        if (!limitProblem.isEmpty()) limits.clear();
        else if (limits.isEmpty()) limitProblem = "its CLI named no PID profile";
        if (profile >= 0) cli(w, sink, "profile " + profile);

        // MSP's word on the failsafe held to the CLI's: where they differ the CLI's is taken, and said
        boolean mspKnown = byMsp != null, same = mspKnown && procedure != null && procedure.equals(PROCEDURES[byMsp[5]]) && delay == byMsp[0]
                && (offDelay < 0 || offDelay == byMsp[1]) && (throttle < 0 || throttle == byMsp[2]) && (switchMode == null || switchMode.equals(SWITCH_MODES[byMsp[3]]))
                && (lowDelay < 0 || lowDelay == byMsp[4]);
        boolean cliSaid = procedure != null && delay >= 0;
        if (mspKnown && cliSaid && !same) {
            event(Kind.FINDING, "MSP_FAILSAFE_CONFIG (75) reads failsafe_procedure " + PROCEDURES[byMsp[5]] + ", failsafe_delay " + byMsp[0] + " - the board's CLI says "
                    + procedure + ", " + delay + ": the CLI's word is taken (the keeper's reading of MSP 75 is not this Betaflight's).");
        }
        String byMspWords = !mspKnown ? "MSP_FAILSAFE_CONFIG was not understood" : !cliSaid ? "taken from MSP_FAILSAFE_CONFIG where the CLI did not say"
                : same ? "MSP_FAILSAFE_CONFIG says the same" : "MSP_FAILSAFE_CONFIG READS OTHERWISE: the CLI's word is taken";
        if (procedure == null && mspKnown) procedure = PROCEDURES[byMsp[5]];
        if (delay < 0 && mspKnown) delay = byMsp[0];
        StringBuilder said = new StringBuilder("The board's own settings, read in its CLI (Betaflight restarts now, and is locked again when it is back): ");
        said.append("failsafe_procedure = ").append(procedure == null ? "?" : procedure).append(", failsafe_delay = ").append(delay < 0 ? "?" : delay + " (" + Settings.seconds(delay) + ")")
                .append(", failsafe_off_delay = ").append(known(offDelay)).append(", failsafe_throttle = ").append(known(throttle)).append(", failsafe_switch_mode = ")
                .append(switchMode == null ? "?" : switchMode).append(", failsafe_throttle_low_delay = ").append(known(lowDelay)).append(" - ").append(byMspWords).append("; ");
        said.append(rxfail.isEmpty() ? "rxfail not read" : "rxfail " + String.join(", ", rxfail)).append("; ");
        if (limits.isEmpty()) {
            said.append("motor_output_limit not read (").append(limitProblem).append(")");
        } else {
            for (int i = 0; i < limits.size(); i++) {
                said.append(i == 0 ? "motor_output_limit = " : ", ").append(limits.get(i)).append(i == 0 ? " in PID profile " : " in profile ").append(i)
                        .append(i == profile ? " (the one in use)" : "");
            }
        }
        event(Kind.INFO, said.append('.').toString());
        return new Settings(procedure, delay, offDelay, throttle, switchMode, lowDelay, List.copyOf(rxfail), profile, List.copyOf(limits), limitProblem, true);
    }

    private static String known(int value) {
        return value < 0 ? "?" : Integer.toString(value);
    }

    /** "name = value" in a CLI answer: the value, or null. */
    private static String setting(String said, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^" + name + " = (\\S+)\\s*$").matcher(said);
        return m.find() ? m.group(1) : null;
    }

    private static int number(String text) {
        try {
            return text == null ? -1 : Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * One line typed into the board's CLI ('#' alone opens it) and what the board printed for it, without its echo and
     * its prompt. The prompt counts once nothing more has come for 60 ms, as the app's own CLI reader has it.
     */
    private String cli(BetaflightUsb.Wire w, StringBuilder sink, String command) throws IOException {
        synchronized (sink) {
            sink.setLength(0);
        }
        cliAtNs = clock();
        writeBoard(w, (command.equals("#") ? "#" : command + "\n").getBytes(StandardCharsets.US_ASCII));
        long end = clock() + config.cliMs * 1_000_000L;
        while (clock() - end < 0) {
            if (wire != w) throw new IOException("the board went away while its CLI was read");
            String text;
            synchronized (sink) {
                text = sink.toString();
            }
            if ((text.endsWith("\n# ") || text.equals("# ")) && since(cliAtNs, clock(), 60)) {
                text = text.replace("\r", "");
                int firstLine = text.indexOf('\n');
                if (firstLine >= 0 && text.substring(0, firstLine).contains(command)) text = text.substring(firstLine + 1);
                return text.endsWith("# ") ? text.substring(0, text.length() - 2).strip() : text.strip();
            }
            pause(5);
        }
        throw new IOException("its CLI did not answer \"" + command + "\" within " + config.cliMs + " ms");
    }

    /** What the board does when its sticks stop, and what the keeper does about it: for the first lines. */
    private String failsafeWords(Sticks s) {
        Settings st = settings;
        StringBuilder said = new StringBuilder("when its sticks stop: ");
        if (st == null || st.procedure() == null || st.delay() < 0) {
            said.append("NOT KNOWN (neither MSP_FAILSAFE_CONFIG nor its CLI said)");
        } else if (!st.byCli()) {
            said.append("after failsafe_delay ").append(st.delay()).append(" (").append(Settings.seconds(st.delay())).append(") ").append(st.procedure())
                    .append(config.readSettings ? " (by MSP_FAILSAFE_CONFIG alone: ITS CLI COULD NOT BE READ, so what rxfail does with the throttle is not known)"
                            : " (by MSP_FAILSAFE_CONFIG; its CLI was not asked)");
        } else {
            String throttle = st.rxfailOf(3);
            said.append("rxfail ").append(throttle == null ? "(not read for the throttle)" : throttle.equals("h") ? "holds the throttle at its last value (3 h)"
                    : throttle.equals("a") ? "puts the throttle down (3 a)" : "sets the throttle to " + throttle.substring(2) + " (3 " + throttle + ")");
            for (int[] arm : s.arm()) {
                int channel = 3 + arm[3];
                String mode = st.rxfailOf(channel);
                said.append(" and ").append(mode == null ? "(not read for the ARM switch, AUX" + arm[3] + ")" : mode.equals("h") ? "holds the ARM switch (AUX" + arm[3] + ": " + channel + " h)"
                        : "sets the ARM switch to " + mode.substring(2) + " (AUX" + arm[3] + ": " + channel + " " + mode + ")");
            }
            said.append(" for failsafe_delay ").append(st.delay()).append(" (").append(Settings.seconds(st.delay())).append("), then ").append(st.procedure());
        }
        long limit = stickLimitMs();
        if (!config.failsafeEndsWindow) said.append(side.real() ? " - board.letFailsafeRun: THE KEEPER LETS IT RUN" : " - the keeper lets it run (not a real board)");
        else if (limit < 0) said.append(" - the keeper would end an arm window first, and has no failsafe_delay to go by: NO ARM WINDOW on this board");
        else said.append(" - in an arm window the keeper ends it first: the lock and STOP when the board says RXLOSS or FAILSAFE, at the latest ").append(limit)
                    .append(" ms after the last stick frame");
        return said.toString();
    }

    /** The board's motor_output_limit against what the rig allows: for the first lines; "" when there is nothing to say. */
    private String limitWords() {
        Settings st = settings;
        int max = st == null ? -1 : st.maxLimit(), cap = config.outputLimitMax;
        if (cap <= 0) return max < 0 ? "" : "motor_output_limit " + max;
        if (max < 0) return "motor_output_limit NOT READ (" + (st == null ? "its settings were not read" : st.limitProblem()) + "): NO ARM WINDOW on this board";
        if (max <= cap) return "motor_output_limit " + max + " (at or under the " + cap + " this rig allows, in every PID profile)";
        return "motor_output_limit " + max + " - OVER the " + cap + " this rig allows (board.outputLimitMax): NO ARM WINDOW until gradlew boardCap -Pport=" + side.name()
                + " -Plimit=" + cap + " (with the keeper ended: one program has the port)";
    }

    /** The arming blocks that hold beside the keeper's own lock, by Betaflight's names ("RXLOSS+RPMFILTER"); "": none, or not known. */
    private String otherBlocks() {
        int f = flags;
        if (f == -1) return "";
        int others = f & ~(1 << (lockBit < 0 ? Msp.ARMING_DISABLED_MSP_BIT : lockBit));
        return others == 0 ? "" : Msp.armingBlocks(others);
    }

    /** Whether the board could arm were the keeper's lock off - its own word, and no failure of the keeper's. */
    private String cannotArmWords() {
        String others = otherBlocks();
        return others.isEmpty() ? "nothing but the keeper's own lock keeps it from arming" : "cannot arm now: " + others + " (beside the keeper's own lock)";
    }

    /** Where the mode box FAILSAFE is among MSP_STATUS' mode flags: its place in MSP_BOXIDS' answer (asked page by page, 32 a page); -1: not offered, or not said. */
    private int failsafeBoxIndex() {
        try {
            int index = 0;
            for (int page = 0; page < 4; page++) {
                byte[] ids = ask(Msp.BOXIDS, new byte[]{(byte) page});
                for (byte id : ids) {
                    if ((id & 0xff) == Msp.BOX_FAILSAFE) return index;
                    index++;
                }
                if (ids.length < 32) break;
            }
        } catch (IOException e) {
            // the arming-disable flags say RXLOSS and FAILSAFE too
        }
        return -1;
    }

    /** What the board's own status says of a receiver that is gone - "RXLOSS", "FAILSAFE", both - or null. */
    private String receiverLost() {
        int f = flags;
        if (!statusKnown) return null;
        boolean rxLoss = f != -1 && (f >> Msp.ARMING_DISABLED_RXLOSS_BIT & 1) != 0, failsafe = f != -1 && (f >> Msp.ARMING_DISABLED_FAILSAFE_BIT & 1) != 0 || failsafeBoxOn;
        return rxLoss && failsafe ? "RXLOSS and FAILSAFE" : rxLoss ? "RXLOSS" : failsafe ? "FAILSAFE" : null;
    }

    /**
     * How long after the last stick frame the keeper ends an open arm window: the shorter of
     * {@link Config#sticksStoppedMaxMs} and half the board's failsafe_delay. 0: it does not (the failsafe is let run);
     * -1: it would, and the board's failsafe_delay is not known.
     */
    private long stickLimitMs() {
        if (!config.failsafeEndsWindow) return 0;
        Settings st = settings;
        return st == null || st.delay() < 0 ? -1 : Math.min(config.sticksStoppedMaxMs, st.delay() * 100L / 2);
    }

    private void stopMotors() throws IOException {
        IOException last = null;
        int taken = 0;
        for (int i = 0; i < 3; i++) { // three times, as the app's motor test does
            try {
                ask(Msp.SET_MOTOR, Msp.allMotors(stopValue));
                taken++;
            } catch (IOException e) {
                last = e;
            }
        }
        if (taken == 0) throw last;
    }

    /** The motor outputs the mixer has, as read back. */
    private int[] motors() throws IOException {
        return Arrays.stream(Msp.motors(ask(Msp.MOTOR, null))).filter(v -> v > 0).toArray();
    }

    private void boardGone(BetaflightUsb.Wire w, String why) {
        synchronized (boardState) {
            if (wire != w) return;
            wire = null;
            ready = false;
            statusKnown = voltsKnown = false;
            flags = -1;
            sticks = null;
            cliSink = null; // the next connection begins with MSP again
            failsafeBoxOn = false;
            if (windowOpen) lostInWindow = true; // even if it is back within the same tick: a board that restarted is no longer the one the window was opened on
        }
        try {
            w.close();
        } catch (RuntimeException ignored) {
        }
        Waiting wt = waiting;
        if (wt != null) wt.answer.completeExceptionally(new IOException("the board went away"));
        PhoneConn p = phone;
        if (p != null) p.close("the board went away"); // as a USB port's: the connection ends with the board
        SetupConn s = setup;
        if (s != null) s.close("the board went away");
        long now = clock();
        nextOpenNs = now + config.reopenMs * 1_000_000L;
        boolean again; // a board that cannot be taken is tried twice a second: said once, and again every half minute
        synchronized (boardState) {
            again = why.equals(lastGone) && !since(lastGoneNs, now, 30_000);
            if (!again) {
                lastGone = why;
                lastGoneNs = now;
            }
        }
        if (!ending && !again) event(Kind.INFO, "The board is let go: " + why + ".");
    }

    /** The board's bytes: Drone setup's as they come while it has the lease, else frame by frame. */
    private void readBoard(BetaflightUsb.Wire w) {
        byte[] buf = new byte[4096];
        MspTap.Splitter frames = new MspTap.Splitter(MspFrames.MAX_CARRY);
        String why = "its port closed";
        try {
            while (wire == w) {
                int n = w.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                long in = clock();
                SetupConn s = setup;
                if (s != null) {
                    s.fromBoard(buf, n, in);
                    continue;
                }
                StringBuilder cli = cliSink; // the keeper itself is in the board's CLI: text, until this connection ends
                if (cli != null) {
                    synchronized (cli) {
                        cli.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                    }
                    cliAtNs = in;
                    continue;
                }
                long before = frames.skipped();
                for (MspTap.Frame f : frames.feed(buf, n)) fromBoard(f, in);
                long junk = frames.skipped() - before;
                if (junk > 0) finding("board-bytes", junk + " byte(s) from the board that are part of no MSP frame: dropped (is something else of it talking - its CLI?)");
            }
        } catch (IOException e) {
            why = "its port failed (" + e.getMessage() + ")";
        }
        boardGone(w, why);
    }

    private void fromBoard(MspTap.Frame f, long in) {
        if (f.request()) {
            tap.seen(new MspTap.Seen(in, 0, MspTap.Way.BOARD_TO_KEEPER, f, -1, -1, "a request from the board: dropped"));
            return;
        }
        if (f.answer()) learn(f.code(), f.payload(), in);
        if (!f.v2()) { // the keeper's own: it asks in v1, the phone app in v2
            Waiting w = waiting;
            if (w != null && w.code == f.code()) {
                if (f.answer()) w.answer.complete(f.payload());
                else w.answer.completeExceptionally(new IOException("the board refused MSP " + f.code()));
            }
            tap.seen(new MspTap.Seen(in, clock(), MspTap.Way.BOARD_TO_KEEPER, f, -1, -1, null));
            return;
        }
        PhoneConn p = phone;
        if (p == null) {
            tap.seen(new MspTap.Seen(in, 0, MspTap.Way.BOARD_TO_PHONE, f, -1, -1, "no phone to take it"));
            return;
        }
        long hold = delayMs;
        if (hold > 0 || !toPhoneHold.idle()) toPhoneHold.add(() -> p.send(f, f.raw(), in), in + hold * 1_000_000L);
        else p.send(f, f.raw(), in);
    }

    /** What an answer says of the board, whoever asked: armed and the arming blocks, the volts. */
    private void learn(int code, byte[] payload, long at) {
        if (code == Msp.STATUS && payload.length > 6) {
            flags = Msp.armingDisableFlags(payload);
            failsafeBoxOn = Msp.modeFlag(payload, failsafeBox);
            armed = Msp.armed(payload);
            statusNs = at;
            statusKnown = true;
        } else if (code == Msp.ANALOG || code == Msp.BATTERY_STATE) {
            double v = Msp.volts(code, payload);
            if (!Double.isNaN(v)) {
                volts = v;
                voltsNs = at;
                voltsKnown = true;
            }
        }
    }

    // ------------------------------------------------------------------ made safe

    /**
     * The arming lock set, an armed board waited down, STOP to every motor, the motors read back, the status read.
     * Drone setup's lease, if it has the board, is ended first; a board that answers nothing is let go and taken anew
     * (it may sit in its CLI), for as long as {@link Config#backMs}. The caller holds windowLock.
     */
    private Safe lockAndStop() {
        SetupConn s = setup;
        if (s != null) s.close("the keeper makes the board safe");
        Safe first = lockAndStopOnce();
        if (first.motors() != null || config.backMs <= 0) return first;
        // nothing answered, or the board is away: let go of it and take it anew - the taking itself locks and stops
        BetaflightUsb.Wire w = wire;
        if (w != null) boardGone(w, "it did not answer while it was made safe");
        long end = clock() + config.backMs * 1_000_000L;
        while (clock() - end < 0) {
            tryOpen();
            if (ready) return lockAndStopOnce();
            pause(Math.min(config.reopenMs, 100));
        }
        return new Safe(false, false, false, null, false, "NOT SAFE: " + side.name() + " did not answer within " + config.backMs + " ms ("
                + (awayBecause.isEmpty() ? first.text() : awayBecause) + ") - nothing could be locked, stopped or looked at.");
    }

    private Safe lockAndStopOnce() {
        if (wire == null) return new Safe(false, false, false, null, false, "NOT SAFE: the board is away.");
        try {
            lockOn();
            readStatus();
            long end = clock() + 1_000_000_000L;
            while (armed && clock() - end < 0) { // Betaflight disarms on the lock; a moment for it to say so
                pause(40);
                lockOn();
                readStatus();
            }
            stopMotors();
            int[] m = null;
            boolean rest = false;
            for (int look = 0; look < 5 && !rest; look++) {
                if (look > 0) pause(60);
                m = motors();
                rest = BoardSafe.atRest(m, stopValue);
            }
            readStatus();
            boolean locked = lockedNow(), isArmed = armed;
            boolean safe = locked && !isArmed && rest;
            String text = (safe ? "SAFE: " : "NOT SAFE: ") + (locked ? "arming locked" : lockBit < 0 ? "the arming lock was sent but does not show in this board's status"
                    : "THE ARMING LOCK IS NOT SET") + ", " + (isArmed ? "ARMED" : "disarmed") + ", motors read " + Arrays.toString(m)
                    + (rest ? " (at rest)" : " (NOT AT REST)") + ".";
            return new Safe(safe, locked, isArmed, m, rest, text);
        } catch (IOException e) {
            return new Safe(false, false, statusKnown && armed, null, false, "NOT SAFE: " + e.getMessage() + ".");
        }
    }

    /**
     * An interlock: the window closed, the lock set, the motors stopped, said loudly; no window opens after it until
     * CLEAR. The same cause again within two seconds is acted on and not said again.
     */
    private void trip(String key, String why) {
        synchronized (windowLock) {
            long now = clock();
            boolean again = key.equals(lastTripKey) && !since(lastTripNs, now, 2000);
            if (again && !since(lastTripNs, now, 400)) return;
            boolean wasOpen = windowOpen;
            windowOpen = false;
            if (tripped == null) tripped = why;
            if (wasOpen) lastEnd = why;
            if (!again) {
                lastTripKey = key;
                event(Kind.TRIP, why + (wasOpen ? " - the arm window is closed." : ""));
            }
            lastTripNs = now;
            Safe s = lockAndStop();
            if (!again || !s.safe()) event(s.safe() ? Kind.INFO : Kind.TRIP, "After that: " + s.text());
        }
    }

    /**
     * (Second round.) The keeper ends an open arm window because the board's receiver is gone: the lock, STOP, the motors
     * read back, said loudly - as for any trip - but with no CLEAR to ask for afterwards. A cut is what the hardware week
     * tests: the next window opens when sticks reach the board again and the board says so.
     */
    private void endWindow(String why) {
        synchronized (windowLock) {
            if (!windowOpen) return;
            windowOpen = false;
            lastEnd = why;
            event(Kind.TRIP, why + " - the arm window is closed.");
            Safe s = lockAndStop();
            event(s.safe() ? Kind.INFO : Kind.TRIP, "After that: " + s.text() + " No CLEAR is asked for this end: a window opens again once sticks reach the board.");
        }
    }

    // ------------------------------------------------------------------ the window

    private String openWindow(int seconds) {
        synchronized (windowLock) {
            String no = mayOpen(seconds);
            if (no == null) {
                try {
                    readStatus(); // a fresh word, both
                    ask(Msp.ANALOG, null);
                    no = mayOpen(seconds);
                } catch (IOException e) {
                    no = "the board does not answer (" + e.getMessage() + ")";
                }
            }
            if (no != null) {
                event(Kind.INFO, "No arm window: " + no + ".");
                return "NO " + no;
            }
            windowEndNs = clock() + seconds * 1_000_000_000L;
            lowSinceNs = 0;
            low = false;
            lastEnd = null;
            windowOpen = true; // before the lock comes off: from here on the watch takes "not locked" for right
            try {
                ask(Msp.ARMING_DISABLE, UNLOCK);
                readStatus();
                if (lockedNow()) throw new IOException("the board keeps its arming block though the keeper took its own off - another MSP client holds one");
            } catch (IOException e) {
                windowOpen = false;
                Safe s = lockAndStop();
                event(Kind.FINDING, "No arm window: " + e.getMessage() + ". " + s.text());
                return "NO " + e.getMessage();
            }
            // (second round) the lock is off - whether the board can arm now is the board's own to say, and no failure of the keeper's
            String cannot = otherBlocks();
            event(Kind.INFO, "ARM WINDOW OPEN for " + seconds + " s: the arming lock is off" + (cannot.isEmpty() ? ". " : " - and the board cannot arm yet: " + cannot + ". ")
                    + voltageWords() + "; " + (config.budgetS * 1000L - budgetUsedMs(System.currentTimeMillis())) / 1000 + " armed seconds left in this hour.");
            return "OK window=" + seconds * 1000L + (cannot.isEmpty() ? "" : " cannotArm=" + cannot);
        }
    }

    /** Why no window opens now; null: one may. */
    private String mayOpen(int seconds) {
        long now = clock();
        if (ending) return "the keeper is ending";
        if (tripped != null) return "the keeper has tripped (" + tripped + "): CLEAR first";
        if (windowOpen) return "a window is open: close it first";
        if (seconds < 1 || seconds > config.windowMaxS) return "a window is 1 to " + config.windowMaxS + " s long, not " + seconds;
        if (setup != null) return "Drone setup has the board";
        if (!ready) return "no board";
        if (lockBit < 0) return "the arming lock does not show in this board's status, so it cannot be seen to hold";
        if (!lockedNow()) return "the arming lock is not set";
        if (statusKnown && armed) return "the board is armed";
        if (stopValue == Msp.STOP_3D) return "the board has 3D mode on: there the throttle's minimum is full reverse, and the rig's ceiling means nothing";
        if (!beaten || since(beatNs, now, config.heartbeatMs)) return "no heartbeat: BEAT first, and go on beating";
        if (!config.noBattery) {
            if (config.cells <= 0 || config.cellFloor <= 0) return "no battery floor is known (cells, floor)";
            if (!voltsKnown || since(voltsNs, now, config.voltsStaleMs)) return "the battery's voltage is not known";
            double cell = volts / config.cells;
            if (cell < config.cellFloor) {
                return String.format(Locale.ROOT, "the battery is at %.2f V a cell (%.2f V, %d cells), under the floor of %.2f", cell, volts, config.cells, config.cellFloor);
            }
        }
        // >>> second round
        // the throttle layer that holds whatever makes the throttle: the board's own output limit, in every PID profile
        if (config.outputLimitMax > 0) {
            Settings st = settings;
            int max = st == null ? -1 : st.maxLimit();
            if (max < 0) return "the board's motor_output_limit is not known (" + (st == null ? "its settings were not read" : st.limitProblem()) + ")";
            if (max > config.outputLimitMax) {
                return "the board's motor_output_limit is " + max + " (in PID profile " + st.maxProfile() + "), over the " + config.outputLimitMax
                        + " this rig allows (board.outputLimitMax): gradlew boardCap -Pport=" + side.name() + " -Plimit=" + config.outputLimitMax + " writes it (with the keeper ended: one program has the port)";
            }
        }
        // a window the keeper would have to end at once is not opened: the board's receiver has to be there, by its own word and by the stick frames
        if (config.failsafeEndsWindow) {
            long limit = stickLimitMs();
            if (limit < 0) return "the board's failsafe_delay is not known, so the keeper has no clock for sticks that stop";
            String lost = receiverLost();
            if (lost != null) return "the board says its receiver is lost (" + lost + "): no sticks reach it";
            if (lastStickNs == 0 || since(lastStickNs, now, limit)) return "no stick frame has reached the board in the last " + limit + " ms";
        }
        // <<< second round
        if (budgetUsedMs(System.currentTimeMillis()) >= config.budgetS * 1000L) return "this hour's " + config.budgetS + " armed seconds are used up";
        return null;
    }

    private Safe closeWindow(String why) {
        synchronized (windowLock) {
            boolean was = windowOpen;
            Safe s = lockAndStop();
            windowOpen = false; // after the lock: the watch never sees a closed window on a board that is still unlocked
            if (was) event(s.locked() && !s.armed() ? Kind.INFO : Kind.TRIP, "ARM WINDOW CLOSED (" + why + "). " + s.text());
            return s;
        }
    }

    // ------------------------------------------------------------------ the watch: twenty times a second

    private void watch() {
        while (!ending) {
            tick();
            pause(config.tickMs);
        }
    }

    private void tick() {
        long now = clock();
        if (wire == null && now - nextOpenNs >= 0 && !ending) {
            nextOpenNs = now + config.reopenMs * 1_000_000L;
            tryOpen();
        }
        expireFaults(now);
        poll(now);
        interlocks();
        armedTime();
        String problem = tap.takeProblem();
        if (problem != null) event(Kind.FINDING, problem);
        tap.drain();
        now = clock();
        if (since(lastRowNs, now, 1000)) {
            lastRowNs = now;
            tap.row(row());
        }
        if (since(lastVoltageLineNs, now, config.voltageLineMs)) {
            lastVoltageLineNs = now;
            event(Kind.INFO, "VOLTS: " + voltageWords() + (statusKnown ? armed ? ", ARMED" : ", disarmed" : "")
                    + (ready && !otherBlocks().isEmpty() ? "; " + cannotArmWords() : ""));
        }
    }

    /** The keeper's own questions, while nobody else's answers say it: armed four times a second, the volts once. */
    private void poll(long now) {
        if (!ready || setup != null || ending) return;
        try {
            // (second round) in a window whose end hangs on the board's word about its receiver, that word is asked for ten times a second
            long every = windowOpen && config.failsafeEndsWindow ? Math.min(config.statusEveryMs, 100) : config.statusEveryMs;
            if (!statusKnown || since(statusNs, now, every)) readStatus();
            if (!voltsKnown || since(voltsNs, now, config.voltsEveryMs)) ask(Msp.ANALOG, null);
            pollFailures = 0;
        } catch (IOException e) {
            BetaflightUsb.Wire w = wire;
            if (w != null && setup == null && ++pollFailures >= 3) boardGone(w, "it stopped answering (" + e.getMessage() + ")");
        }
    }

    private void interlocks() {
        synchronized (windowLock) {
            if (ending) return;
            long now = clock();
            if (windowOpen && (wire == null || lostInWindow)) {
                lostInWindow = false;
                trip("board", "THE BOARD WENT AWAY while an arm window was open");
                return;
            }
            lostInWindow = false;
            if (wire == null) return;
            boolean isArmed = statusKnown && armed;
            if (isArmed && !windowOpen) {
                trip("armed", "ARMED OUTSIDE A WINDOW: the board says it is armed and no arm window is open");
                return;
            }
            if (!ready || setup != null) return;
            if (!windowOpen) {
                if (lockBit >= 0 && statusKnown && flags != -1 && !lockedNow()) {
                    finding("lock-off", "The arming lock was found off outside a window (the board restarted, or another MSP client took it off): set again");
                    try {
                        lockOn();
                        readStatus();
                    } catch (IOException e) {
                        // the poll lets a board go that stops answering
                    }
                }
                return;
            }
            // a window is open
            // >>> second round: the board's receiver gone - by its own word, or by the stick frames that no longer pass - ends the
            // window before the board's own failsafe can run (a throttle the flight controller makes itself is in no stick frame)
            if (config.failsafeEndsWindow) {
                String lost = receiverLost();
                Settings st = settings;
                long limit = stickLimitMs(), quiet = (now - lastStickNs) / 1_000_000;
                if (lost != null) {
                    endWindow("RECEIVER LOST: the board says " + lost + " in an open arm window - ended by the keeper before the board's own failsafe ("
                            + (st == null || st.procedure() == null ? "not known" : st.procedure() + " after " + Settings.seconds(st.delay())) + ") can run");
                    return;
                }
                if (limit >= 0 && quiet >= limit) {
                    endWindow("STICKS STOPPED: no stick frame has reached the board for " + quiet + " ms in an open arm window (the keeper's limit is " + limit
                            + " ms: the shorter of " + config.sticksStoppedMaxMs + " ms and half the board's failsafe_delay of " + Settings.seconds(st.delay()) + ")");
                    return;
                }
            }
            // <<< second round
            boolean lowNow = !config.noBattery && (!voltsKnown || since(voltsNs, now, config.voltsStaleMs) || volts / config.cells < config.cellFloor);
            if (!lowNow) {
                low = false;
            } else if (!low) {
                low = true;
                lowSinceNs = now;
            }
            if (now - windowEndNs >= 0) {
                if (isArmed) {
                    trip("time", "ARMED TIME: the arm window's time was over and the board was still armed");
                } else {
                    closeWindow("its time was over");
                    lastEnd = "THE WINDOW'S TIME WAS OVER (the board was disarmed)"; // the keeper's own doing, not the rig's: the rig's guard takes the radio's hands off
                }
            } else if (since(beatNs, now, config.heartbeatMs)) {
                trip("heartbeat", "HEARTBEAT: no beat from the rig for " + (now - beatNs) / 1_000_000 + " ms while an arm window was open");
            } else if (low && since(lowSinceNs, now, config.voltageLowMs)) {
                trip("voltage", "VOLTAGE: " + voltageWords() + " for " + config.voltageLowMs + " ms while an arm window was open");
            } else if (budgetUsedMs(System.currentTimeMillis()) >= config.budgetS * 1000L) {
                trip("budget", "ARMED TIME: this hour's " + config.budgetS + " armed seconds are used up");
            } else if (!statusKnown || since(statusNs, now, config.staleMs)) {
                trip("stale", "THE BOARD HAS NOT SAID WHETHER IT IS ARMED for " + config.staleMs + " ms while an arm window was open");
            }
        }
    }

    private String voltageWords() {
        if (config.noBattery) return "no battery (Betaflight as a program)";
        if (!voltsKnown) return "the battery's voltage is not known";
        if (config.cells <= 0) return String.format(Locale.ROOT, "%.2f V", volts);
        double cell = volts / config.cells;
        return String.format(Locale.ROOT, "%.2f V, %.2f V a cell (%d cells)%s", volts, cell, config.cells, config.cellFloor <= 0 ? ""
                : cell < config.cellFloor ? String.format(Locale.ROOT, " - UNDER the floor of %.2f", config.cellFloor) : String.format(Locale.ROOT, ", floor %.2f", config.cellFloor));
    }

    // ------------------------------------------------------------------ armed seconds

    /** The armed stretches, on the wall clock: begun when the board says armed, ended when it says disarmed. */
    private void armedTime() {
        if (!statusKnown) return; // away or silent: an armed stretch goes on until the board says otherwise
        boolean isArmed = armed;
        long wall = System.currentTimeMillis();
        if (isArmed && !wasArmed) {
            armedSinceMs = wall;
            event(Kind.INFO, "THE BOARD IS ARMED" + (windowOpen ? " (in the arm window, " + Math.max(0, windowEndNs - clock()) / 1_000_000 + " ms left)." : "."));
        } else if (!isArmed && wasArmed) {
            synchronized (armedStretches) {
                armedStretches.add(new long[]{armedSinceMs, wall});
            }
            event(Kind.INFO, "The board is disarmed, after " + (wall - armedSinceMs) / 100 / 10.0 + " s armed; "
                    + (config.budgetS * 1000L - budgetUsedMs(wall)) / 1000 + " armed seconds left in this hour.");
            armedSinceMs = 0;
            saveBudget();
        }
        wasArmed = isArmed;
    }

    private long budgetUsedMs(long wall) {
        long from = wall - 3_600_000L, used = 0;
        synchronized (armedStretches) {
            while (!armedStretches.isEmpty() && armedStretches.peekFirst()[1] < from) armedStretches.removeFirst();
            for (long[] s : armedStretches) used += Math.max(0, Math.min(wall, s[1]) - Math.max(from, s[0]));
        }
        long since = armedSinceMs;
        if (since != 0) used += Math.max(0, wall - Math.max(from, since));
        return used;
    }

    private void loadBudget() {
        if (config.budgetFile == null || !Files.isRegularFile(config.budgetFile)) return;
        try {
            long now = System.currentTimeMillis();
            for (String line : Files.readAllLines(config.budgetFile, StandardCharsets.UTF_8)) {
                String[] w = line.trim().split("\\s+");
                if (w.length < 2) continue;
                long from = Long.parseLong(w[0]), to = Long.parseLong(w[1]);
                if (to > from && to <= now && to > now - 3_600_000L) armedStretches.add(new long[]{from, to});
            }
        } catch (IOException | NumberFormatException e) {
            config.console.accept("The armed seconds of before could not be read (" + e.getMessage() + "): counted from none.");
        }
    }

    private void saveBudget() {
        if (config.budgetFile == null) return;
        StringBuilder text = new StringBuilder();
        synchronized (armedStretches) {
            for (long[] s : armedStretches) text.append(s[0]).append(' ').append(s[1]).append('\n');
        }
        try {
            if (config.budgetFile.getParent() != null) Files.createDirectories(config.budgetFile.getParent());
            Files.writeString(config.budgetFile, text.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            finding("budget-file", "The armed seconds could not be kept (" + e.getMessage() + ")");
        }
    }

    // ------------------------------------------------------------------ the phone's lease

    private String phoneRefused() {
        if (ending) return "the keeper is ending";
        if (cut) return "cut (a fault that was asked for)";
        if (setup != null) return "Drone setup has the board";
        if (!ready) return "no board";
        return null;
    }

    private void acceptPhone() {
        while (!ending) {
            Socket s;
            try {
                s = phoneListener.accept();
            } catch (IOException e) {
                if (ending || phoneListener.isClosed()) return;
                continue;
            }
            String no = phoneRefused();
            if (no != null) { // taken and closed at once: to the phone app "no flight controller plugged in"
                quietly(s);
                phoneRefusals++;
                if (!no.equals(lastPhoneRefusal)) {
                    lastPhoneRefusal = no;
                    event(Kind.INFO, "The phone's connection is not taken: " + no + ".");
                }
                continue;
            }
            lastPhoneRefusal = "";
            PhoneConn old = phone;
            if (old != null) old.close("the phone connected anew");
            PhoneConn p;
            try {
                p = new PhoneConn(s);
            } catch (IOException e) {
                quietly(s);
                continue;
            }
            phone = p;
            event(Kind.INFO, "The phone has its lease (a connection from " + s.getRemoteSocketAddress() + ").");
            p.start();
            no = phoneRefused(); // the board went, or Drone setup came, in the same moment
            if (no != null) p.close(no);
        }
    }

    /** One connection from the phone app: read on one thread, written from a queue on another - the board's reader never waits for the phone. */
    private final class PhoneConn {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final ArrayBlockingQueue<Object[]> toPhone = new ArrayBlockingQueue<>(2048);
        private final MspTap.Splitter frames = new MspTap.Splitter(64);
        private volatile boolean closed;

        PhoneConn(Socket socket) throws IOException {
            this.socket = socket;
            socket.setTcpNoDelay(true); // each answer out as it is written, as on USB
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        void start() {
            thread("keeperPhoneReader", this::read);
            thread("keeperPhoneWriter", this::write);
        }

        private void read() {
            byte[] buf = new byte[4096];
            String why = "the phone closed it";
            try {
                while (!closed) {
                    if (stall) { // not read: what the phone writes piles up on its side
                        pause(5);
                        continue;
                    }
                    int n = in.read(buf);
                    if (n < 0) break;
                    long at = clock(), before = frames.skipped();
                    for (MspTap.Frame f : frames.feed(buf, n)) fromPhone(f, at);
                    long junk = frames.skipped() - before;
                    if (junk > 0) {
                        finding("phone-bytes", junk + " byte(s) from the phone's side that are part of no MSP frame: not passed on (the read began "
                                + Msp.hex(Arrays.copyOf(buf, Math.min(n, 16))) + ")");
                    }
                }
            } catch (IOException e) {
                if (!closed) why = "its connection failed (" + e.getMessage() + ")";
            }
            close(why);
        }

        private void write() {
            try {
                while (!closed) {
                    Object[] next = toPhone.poll(200, TimeUnit.MILLISECONDS);
                    if (next == null) continue;
                    out.write((byte[]) next[1]);
                    out.flush();
                    if (next[0] != null) tap.seen(new MspTap.Seen((Long) next[2], clock(), MspTap.Way.BOARD_TO_PHONE, (MspTap.Frame) next[0], -1, -1, null));
                }
            } catch (IOException e) {
                close("a write to the phone failed (" + e.getMessage() + ")");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** An answer for the phone (or, with no frame, a fault's garbage). */
        void send(MspTap.Frame f, byte[] bytes, long in) {
            if (closed) {
                if (f != null) tap.seen(new MspTap.Seen(in, 0, MspTap.Way.BOARD_TO_PHONE, f, -1, -1, "the phone's connection is closed"));
                return;
            }
            if (!toPhone.offer(new Object[]{f, bytes, in})) close("the phone does not take what the board says: " + toPhone.size() + " answers wait");
        }

        void close(String why) {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            quietly(socket);
            if (phone == this) phone = null;
            if (!ending) event(Kind.INFO, "The phone's lease has ended: " + why + ".");
        }
    }

    /** A frame from the phone: strict mode, the throttle cap, the faults - and then to the board. */
    private void fromPhone(MspTap.Frame f, long in) {
        String no = refusal(f);
        if (no != null) {
            tap.seen(new MspTap.Seen(in, 0, MspTap.Way.PHONE_TO_BOARD, f, -1, -1, "dropped: " + no));
            finding("strict-" + (f.v2() ? "" : "v1-") + f.code(), "From the phone's side, not passed on: " + f + " - " + no);
            return;
        }
        int throttle = -1, arm = -1;
        if (f.code() == Msp.SET_RAW_RC) {
            int[] channels = Msp.channels(f.payload());
            Sticks s = sticks;
            if (s == null || s.throttleAt() >= channels.length) {
                tap.seen(new MspTap.Seen(in, 0, MspTap.Way.PHONE_TO_BOARD, f, -1, -1, "dropped: its throttle cannot be read"));
                finding("sticks-short", "A stick frame of " + channels.length + " channels whose throttle cannot be read: not passed on");
                return;
            }
            throttle = channels[s.throttleAt()];
            arm = s.armValue(channels);
            lastThrottle = throttle;
            lastArm = arm;
            if (throttle > config.ceiling) {
                tap.seen(new MspTap.Seen(in, 0, MspTap.Way.PHONE_TO_BOARD, f, throttle, arm, "dropped: throttle above the ceiling of " + config.ceiling));
                trip("throttle", "THROTTLE CAP: a stick frame with throttle " + throttle + ", above the ceiling of " + config.ceiling + ", was not passed on");
                return;
            }
            boolean on = s.armOn(channels);
            if (on && !armSwitchOn && !windowOpen) {
                event(Kind.INFO, "The sticks ask for ARM (the switch at " + arm + ") and no arm window is open: the arming lock holds it.");
            }
            armSwitchOn = on;
        }
        if (starve) {
            tap.seen(new MspTap.Seen(in, 0, MspTap.Way.PHONE_TO_BOARD, f, throttle, arm, "starved (a fault that was asked for)"));
            return;
        }
        long hold = delayMs;
        if (hold > 0 || !toBoardHold.idle()) {
            int t = throttle, a = arm;
            toBoardHold.add(() -> toBoard(f, in, t, a), in + hold * 1_000_000L);
        } else {
            toBoard(f, in, throttle, arm);
        }
    }

    private void toBoard(MspTap.Frame f, long in, int throttle, int arm) {
        BetaflightUsb.Wire w = wire;
        String no = w == null || !ready ? "no board" : null;
        if (no == null) {
            try {
                synchronized (boardOut) {
                    if (setup != null) no = "Drone setup has the board";
                    else w.write(f.raw());
                }
            } catch (IOException | RuntimeException e) {
                no = "the write failed";
                boardGone(w, "a write to it failed (" + e.getMessage() + ")");
            }
        }
        long out = no == null ? clock() : 0;
        if (out != 0 && f.code() == Msp.SET_RAW_RC) lastStickNs = out; // the keeper's own clock on sticks that stop goes by this
        tap.seen(new MspTap.Seen(in, out, MspTap.Way.PHONE_TO_BOARD, f, throttle, arm, no == null ? null : "dropped: " + no));
    }

    /** Frames held back for a fault's delay, let go in the order they came. */
    private final class Hold {
        private final ArrayDeque<Object[]> held = new ArrayDeque<>();
        private final String name;
        private boolean running, busy;

        Hold(String name) {
            this.name = name;
        }

        synchronized boolean idle() {
            return held.isEmpty() && !busy;
        }

        synchronized void add(Runnable what, long dueNs) {
            held.add(new Object[]{what, dueNs});
            if (!running) {
                running = true;
                thread(name, this::run);
            }
            notifyAll();
        }

        private void run() {
            while (!ending) {
                Runnable what;
                synchronized (this) {
                    busy = false;
                    try {
                        if (held.isEmpty()) {
                            wait(200);
                            continue;
                        }
                        long wait = (Long) held.peekFirst()[1] - clock();
                        if (wait > 0) {
                            wait(wait / 1_000_000, (int) (wait % 1_000_000));
                            continue;
                        }
                    } catch (InterruptedException e) {
                        return;
                    }
                    what = (Runnable) held.removeFirst()[0];
                    busy = true;
                }
                what.run();
            }
        }
    }

    // ------------------------------------------------------------------ Drone setup's lease

    private void acceptSetup() {
        while (!ending) {
            Socket s;
            try {
                s = setupListener.accept();
            } catch (IOException e) {
                if (ending || setupListener.isClosed()) return;
                continue;
            }
            String no;
            SetupConn conn = null;
            try {
                s.setTcpNoDelay(true);
                synchronized (windowLock) { // no window opens while the lease is being given
                    no = setupRefused();
                    if (no == null) {
                        try {
                            readStatus(); // the board's word of this moment
                            if (armed) no = "the board is armed";
                        } catch (IOException e) {
                            no = "the board does not answer (" + e.getMessage() + ")";
                        }
                    }
                    if (no == null) {
                        conn = new SetupConn(s);
                        synchronized (askLock) { // none of the keeper's own questions is under way, and none begins
                            synchronized (boardOut) { // and no frame of the phone's goes out after this
                                setup = conn;
                            }
                        }
                        PhoneConn p = phone;
                        if (p != null) p.close("Drone setup has the board"); // as one USB socket: the two never talk to it at once
                    }
                }
                s.getOutputStream().write(((no == null ? "OK" : "NO " + no) + "\n").getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
            } catch (IOException e) {
                if (conn != null) conn.close("its connection failed");
                quietly(s);
                continue;
            }
            if (conn == null) {
                quietly(s);
                event(Kind.INFO, "Drone setup is not given the board: " + no + ".");
            } else {
                event(Kind.INFO, "Drone setup has the board: the phone's side is cut, and the keeper says nothing to the board until it is done.");
                conn.start();
            }
        }
    }

    private String setupRefused() {
        if (ending) return "the keeper is ending";
        if (windowOpen) return "an arm window is open";
        if (setup != null) return "Drone setup has it already";
        if (!ready) return "no board";
        return null;
    }

    /** Drone setup's connection: bytes as they come, both ways - it types into Betaflight's CLI. The tap listens for frames only. */
    private final class SetupConn {
        private final Socket socket;
        private final OutputStream out;
        private final ArrayBlockingQueue<byte[]> toSetup = new ArrayBlockingQueue<>(4096);
        private final MspTap.Splitter up = new MspTap.Splitter(MspFrames.MAX_CARRY), down = new MspTap.Splitter(MspFrames.MAX_CARRY);
        private volatile boolean closed;

        SetupConn(Socket socket) throws IOException {
            this.socket = socket;
            out = socket.getOutputStream();
        }

        void start() {
            thread("keeperSetupReader", this::read);
            thread("keeperSetupWriter", this::write);
        }

        private void read() {
            byte[] buf = new byte[4096];
            String why = "it let go";
            try {
                InputStream in = socket.getInputStream();
                while (!closed) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    long at = clock();
                    BetaflightUsb.Wire w = wire;
                    if (w == null) break;
                    writeBoard(w, Arrays.copyOf(buf, n));
                    long out = clock();
                    for (MspTap.Frame f : up.feed(buf, n)) tap.seen(new MspTap.Seen(at, out, MspTap.Way.SETUP_TO_BOARD, f, -1, -1, null));
                }
            } catch (IOException e) {
                if (!closed) why = "its connection ended (" + e.getMessage() + ")";
            }
            close(why);
        }

        private void write() {
            try {
                while (!closed) {
                    byte[] next = toSetup.poll(200, TimeUnit.MILLISECONDS);
                    if (next == null) continue;
                    out.write(next);
                    out.flush();
                }
            } catch (IOException e) {
                close("a write to it failed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void fromBoard(byte[] buf, int n, long in) {
            if (!toSetup.offer(Arrays.copyOf(buf, n))) {
                close("it does not take what the board says");
                return;
            }
            for (MspTap.Frame f : down.feed(buf, n)) {
                if (f.answer()) learn(f.code(), f.payload(), in); // should the board say "armed" here, the watch acts on it
                tap.seen(new MspTap.Seen(in, clock(), MspTap.Way.BOARD_TO_SETUP, f, -1, -1, null));
            }
        }

        /**
         * The lease ends, however: the board is let go and taken anew. That sets the lock again, stops the motors, takes
         * the board out of a CLI nobody left, and reads its channel map and ARM switch - Drone setup may have changed them.
         */
        void close(String why) {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            quietly(socket);
            BetaflightUsb.Wire w = wire;
            if (setup == this) setup = null;
            needSettings = config.readSettings; // (second round) Drone setup may have changed the failsafe: the board's settings are read anew
            settingsTries = 0;
            if (!ending) event(Kind.INFO, "Drone setup's lease has ended: " + why + ".");
            if (w != null) boardGone(w, "after Drone setup it is taken anew");
            nextOpenNs = clock();
        }
    }

    // ------------------------------------------------------------------ faults

    private String fault(String[] w) {
        if (w.length < 2) return "NO which fault? cut, starve, stall, delay <ms>, garbage phone|board <bytes>, clear";
        long ms = 0;
        String kind = w[1].toLowerCase(Locale.ROOT);
        try {
            int at = kind.equals("delay") ? 3 : kind.equals("garbage") ? 4 : 2;
            if (w.length > at) ms = Long.parseLong(w[at]);
            long end = ms > 0 ? clock() + ms * 1_000_000L : 0;
            switch (kind) {
                case "clear":
                    clearFaults();
                    event(Kind.INFO, "FAULTS: none any more.");
                    return "OK";
                case "cut": {
                    cutEndNs = end;
                    cut = true;
                    PhoneConn p = phone;
                    if (p != null) p.close("cut (a fault that was asked for)");
                    break;
                }
                case "starve":
                    starveEndNs = end;
                    starve = true;
                    break;
                case "stall":
                    stallEndNs = end;
                    stall = true;
                    break;
                case "delay": {
                    long hold = Long.parseLong(w[2]);
                    if (hold < 0 || hold > 5000) return "NO a delay is 0 to 5000 ms";
                    delayEndNs = end;
                    delayMs = hold;
                    break;
                }
                case "garbage": {
                    int count = Integer.parseInt(w[3]);
                    if (count < 1 || count > 65536) return "NO 1 to 65536 bytes of garbage";
                    byte[] junk = new byte[count];
                    synchronized (noise) {
                        for (int i = 0; i < count; i++) {
                            do junk[i] = (byte) noise.nextInt(256);
                            while (junk[i] == '#' || junk[i] == 'R' || junk[i] == 2 || junk[i] == MspFrames.START); // noise, not orders (CLI, bootloader)
                        }
                    }
                    if (w[2].equalsIgnoreCase("phone")) {
                        PhoneConn p = phone;
                        if (p == null) return "NO no phone is connected";
                        p.send(null, junk, clock());
                    } else if (w[2].equalsIgnoreCase("board")) {
                        if (side.real()) return "NO garbage is never sent to a real board";
                        BetaflightUsb.Wire b = wire;
                        if (b == null || !ready || setup != null) return "NO no board";
                        writeBoard(b, junk);
                    } else {
                        return "NO garbage to the phone or to the board?";
                    }
                    event(Kind.INFO, "FAULT: " + count + " bytes of garbage to the " + w[2].toLowerCase(Locale.ROOT) + ".");
                    return "OK";
                }
                default:
                    return "NO not a fault the keeper knows: " + w[1];
            }
        } catch (RuntimeException | IOException e) {
            return "NO " + (e instanceof IOException ? e.getMessage() : "FAULT " + kind + " takes numbers: milliseconds, bytes");
        }
        event(Kind.INFO, "FAULT: " + faults() + (ms > 0 ? " for " + ms + " ms." : " until FAULT clear."));
        return "OK";
    }

    private void clearFaults() {
        cut = starve = stall = false;
        delayMs = 0;
    }

    private void expireFaults(long now) {
        boolean ended = false;
        if (cut && cutEndNs != 0 && now - cutEndNs >= 0) {
            cut = false;
            ended = true;
        }
        if (starve && starveEndNs != 0 && now - starveEndNs >= 0) {
            starve = false;
            ended = true;
        }
        if (stall && stallEndNs != 0 && now - stallEndNs >= 0) {
            stall = false;
            ended = true;
        }
        if (delayMs > 0 && delayEndNs != 0 && now - delayEndNs >= 0) {
            delayMs = 0;
            ended = true;
        }
        if (ended) event(Kind.INFO, "FAULT over: " + (faults().isEmpty() ? "none any more." : "still " + faults() + "."));
    }

    private String faults() {
        StringBuilder s = new StringBuilder();
        if (cut) s.append("cut ");
        if (starve) s.append("starve ");
        if (stall) s.append("stall ");
        if (delayMs > 0) s.append("delay ").append(delayMs).append(" ms ");
        return s.toString().trim();
    }

    // ------------------------------------------------------------------ the phone's port down the cable

    /** adb reverse, now and every few seconds: a cable that was out for a moment loses it. The one command the keeper ever sends a phone. */
    private void reverseLoop() {
        List<String> command = List.of("reverse", "tcp:" + phonePort(), "tcp:" + phonePort());
        while (!ending) {
            String said;
            try {
                said = new String(adb.run(15, command), StandardCharsets.UTF_8).replace("\r", "").trim();
                said = said.toLowerCase(Locale.ROOT).contains("error") ? "FAILED: " + said : "ok";
            } catch (RuntimeException e) {
                said = "FAILED: " + e.getMessage();
            }
            if (!said.equals(reverseSaid) && !ending) {
                reverseSaid = said;
                event(said.equals("ok") ? Kind.INFO : Kind.FINDING, "adb " + String.join(" ", command) + ": " + (said.equals("ok")
                        ? "the phone's 127.0.0.1:" + phonePort() + " comes down the cable to the keeper, and is led again every " + config.reverseEveryMs + " ms." : said));
            }
            pause(config.reverseEveryMs);
        }
    }

    // ------------------------------------------------------------------ the control channel

    private void acceptControl() {
        while (!ending || result == null) {
            Socket s;
            try {
                s = controlListener.accept();
            } catch (IOException e) {
                if (controlListener.isClosed()) return;
                continue;
            }
            thread("keeperControlClient", () -> control(s));
        }
    }

    private void control(Socket s) {
        controlSockets.add(s);
        controlSocket.set(s);
        try (s) {
            s.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (line.isBlank()) continue;
                boolean end = line.trim().toUpperCase(Locale.ROOT).startsWith("END");
                String answer;
                try {
                    answer = command(line.trim());
                } catch (RuntimeException e) {
                    // an order the keeper stumbled over: the rig is told no, and - whatever state that left - the window is shut and the lock set
                    trip("order", "THE KEEPER STUMBLED OVER AN ORDER (" + line.trim() + "): " + e);
                    answer = "NO the keeper stumbled over that (" + e + "): its window is closed and the lock set";
                }
                out.write((answer + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (end) {
                    released.countDown(); // only now: whoever asked has the keeper's word before its process goes
                    return;
                }
            }
        } catch (IOException ignored) {
            // the rig went: its beats stop, and the watch acts on that
        } finally {
            controlSockets.remove(s);
        }
    }

    /** One line in, one line out ("OK ..." or "NO ..."); EVENTS alone answers with more lines. */
    String command(String line) {
        String[] w = line.split("\\s+");
        String rest = line.length() > w[0].length() ? line.substring(w[0].length()).trim() : "";
        try {
            switch (w[0].toUpperCase(Locale.ROOT)) {
                case "BEAT":
                    beatNs = clock();
                    beaten = true;
                    return "OK " + stateLine();
                case "STATE":
                    return "OK " + stateLine();
                case "OPEN":
                    if (w.length != 2) return "NO OPEN takes the window's seconds";
                    return openWindow(Integer.parseInt(w[1]));
                case "CLOSE": {
                    Safe s = closeWindow("the rig closed it");
                    return (s.locked() && !s.armed() ? "OK " : "NO ") + s.line();
                }
                case "SAFE": {
                    Safe s = closeWindow("the rig asked for a safe board");
                    return (s.safe() ? "OK " : "NO ") + s.line();
                }
                case "TRIP":
                    trip("rig", "THE RIG STOPPED IT: " + (rest.isEmpty() ? "no reason given" : rest));
                    return "OK";
                case "CLEAR":
                    synchronized (windowLock) {
                        if (windowOpen) return "NO a window is open";
                        if (tripped == null) return "OK nothing was tripped";
                        event(Kind.INFO, "CLEARED by the rig: \"" + tripped + "\" no longer keeps the window shut.");
                        tripped = null;
                        lastTripKey = "";
                        return "OK";
                    }
                case "FAULT":
                    return fault(w);
                case "SCENARIO":
                    scenario = rest.equals("-") ? "" : rest;
                    if (scenario.isEmpty()) tap.closeLog();
                    else tap.openLog(scenario);
                    event(Kind.INFO, scenario.isEmpty() ? "No scenario." : "SCENARIO: " + scenario);
                    return "OK";
                case "MARK":
                    tap.mark();
                    return "OK";
                case "FIGURES":
                    return "OK " + tap.figures().line();
                case "EVENTS": {
                    int from = w.length > 1 ? Integer.parseInt(w[1]) : 0;
                    List<Event> all = events();
                    from = Math.max(0, Math.min(from, all.size()));
                    StringBuilder b = new StringBuilder("OK " + all.size() + " " + (all.size() - from));
                    for (Event e : all.subList(from, all.size())) b.append('\n').append(e.line().replace('\n', ' ').replace('\r', ' '));
                    return b.toString();
                }
                case "END": {
                    Safe s = end("END was asked for on the control channel" + (rest.isEmpty() ? "" : " (" + rest + ")"));
                    return (s.safe() ? "OK " : "NO ") + s.line();
                }
                default:
                    return "NO not one of the keeper's commands: " + w[0];
            }
        } catch (NumberFormatException e) {
            return "NO " + w[0] + " takes a number";
        }
    }

    /** name=value pairs, no spaces in a value: what the rig's guard reads twenty times a second. */
    String stateLine() {
        long now = clock();
        boolean fresh = statusKnown && !since(statusNs, now, config.staleMs);
        StringBuilder s = new StringBuilder();
        s.append("board=").append(wire == null ? "away" : ready ? "up" : "taking");
        s.append(" lease=").append(setup != null ? "setup" : phone != null ? "phone" : "none");
        s.append(" armed=").append(statusKnown ? flag(armed) : "?");
        s.append(" fresh=").append(flag(fresh));
        s.append(" locked=").append(lockBit < 0 || flags == -1 ? "?" : flag(lockedNow()));
        s.append(" blocks=").append(Msp.armingBlocks(flags)); // why Betaflight would not arm just now, by its 4.4 names
        s.append(" window=").append(windowOpen ? Math.max(1, (windowEndNs - now) / 1_000_000) : 0);
        s.append(" tripped=").append(tripped == null ? "-" : tripped.replace(' ', '_'));
        // (second round) why the keeper itself ended the last window; the board's failsafe and the keeper's clock on the stick frames
        // (0: the failsafe is let run, -1: not known); the largest motor_output_limit of its PID profiles (-1: not read) against the rig's
        String ended = lastEnd;
        Settings st = settings;
        s.append(" cannotArm=").append(otherBlocks().isEmpty() ? "-" : otherBlocks()); // "blocks" without the keeper's own lock: the board's word, no failure
        s.append(" lastEnd=").append(ended == null ? "-" : ended.replace(' ', '_'));
        s.append(" failsafe=").append(st == null || st.procedure() == null ? "?" : st.procedure()).append(" failsafeDelay=").append(st == null ? -1 : st.delay());
        s.append(" stickLimitMs=").append(stickLimitMs());
        s.append(" outputLimit=").append(st == null ? -1 : st.maxLimit()).append(" outputLimitMax=").append(config.outputLimitMax);
        s.append(" volts=").append(voltsKnown ? String.format(Locale.ROOT, "%.2f", volts) : "?");
        s.append(" cell=").append(voltsKnown && config.cells > 0 ? String.format(Locale.ROOT, "%.3f", volts / config.cells) : "?");
        s.append(" floor=").append(config.cellFloor);
        s.append(" throttle=").append(lastThrottle).append(" arm=").append(lastArm).append(" armOn=").append(flag(armSwitchOn));
        Sticks how = sticks; // which switches the board itself arms on: 0 - none, or not known
        s.append(" armAux=").append(how == null || how.arm().length == 0 ? 0 : how.arm()[0][3]).append(" prearmAux=").append(how == null ? 0 : how.prearmAux());
        s.append(" faults=").append(faults().isEmpty() ? "-" : faults().replace(' ', '_'));
        s.append(" budgetLeft=").append(Math.max(0, config.budgetS * 1000L - budgetUsedMs(System.currentTimeMillis())) / 1000);
        s.append(" lockBit=").append(lockBit);
        s.append(" says=").append(boardSays.isEmpty() ? "-" : boardSays);
        s.append(" phonePort=").append(phonePort()).append(" setupPort=").append(setupPort());
        s.append(" reverse=").append(adb == null ? "-" : reverseSaid.isEmpty() ? "?" : reverseSaid.startsWith("ok") ? "ok" : "failed");
        s.append(" refused=").append(phoneRefusals);
        synchronized (events) {
            s.append(" events=").append(events.size()).append(" trips=").append(tripCount).append(" findings=").append(findingCount);
        }
        s.append(" scenario=").append(scenario.isEmpty() ? "-" : scenario.replace(' ', '_'));
        s.append(" ending=").append(flag(ending));
        return s.toString();
    }

    private MspTap.Row row() {
        long now = clock();
        boolean fresh = statusKnown && !since(statusNs, now, config.staleMs);
        return new MspTap.Row(wire == null ? "away" : ready ? "up" : "taking", setup != null ? "setup" : phone != null ? "phone" : "none", fresh ? flag(armed) : "",
                fresh && lockBit >= 0 && flags != -1 ? flag(lockedNow()) : "", windowOpen ? Math.max(1, (windowEndNs - now) / 1_000_000) : 0,
                voltsKnown ? volts : Double.NaN, voltsKnown && config.cells > 0 ? volts / config.cells : Double.NaN, faults(), scenario);
    }

    private static String flag(boolean b) {
        return b ? "1" : "0";
    }

    // ------------------------------------------------------------------ the end

    /** An error of the keeper's own, on any of its threads: the board made safe, the keeper ended. */
    private void fatal(String thread, Throwable error) {
        if (ending && result != null) return;
        failed = true;
        StringWriter stack = new StringWriter();
        error.printStackTrace(new PrintWriter(stack));
        event(Kind.TRIP, "AN ERROR OF THE KEEPER'S OWN (" + thread + "): " + stack.toString().replace("\r", "").replace("\n", " | "));
        end("an error of the keeper's own: " + error);
        released.countDown();
    }

    /**
     * Make safe and end: the window closed, the leases ended, the lock set, STOP to every motor, the motors read back,
     * the port let go, the lock file given back. Once; later calls get the first one's word.
     */
    public Safe end(String why) {
        synchronized (endLock) {
            if (result != null) return result;
            if (!started) return result = new Safe(false, false, false, null, false, "NOT SAFE: the keeper never started.");
            ending = true;
            event(Kind.INFO, "ENDING: " + why + ".");
            Safe s;
            synchronized (windowLock) {
                clearFaults();
                PhoneConn p = phone;
                if (p != null) p.close("the keeper is ending");
                boolean was = windowOpen;
                s = lockAndStop();
                windowOpen = false;
                if (was) event(Kind.INFO, "The arm window that was open is closed.");
            }
            if (armedSinceMs != 0) {
                synchronized (armedStretches) {
                    armedStretches.add(new long[]{armedSinceMs, System.currentTimeMillis()});
                }
                armedSinceMs = 0;
            }
            saveBudget();
            BetaflightUsb.Wire w = wire; // the port let go: whoever comes next finds it free
            synchronized (boardState) {
                wire = null;
                ready = false;
            }
            if (w != null) {
                try {
                    w.close();
                } catch (RuntimeException ignored) {
                }
            }
            SetupConn lease = setup;
            if (lease != null) lease.close("the keeper is ending");
            quietly(phoneListener);
            quietly(setupListener);
            event(s.safe() ? Kind.INFO : Kind.TRIP, "THE KEEPER HAS ENDED, the port " + side.name() + " is let go. " + s.text());
            tap.close();
            synchronized (events) {
                if (log != null) {
                    try {
                        log.close();
                    } catch (IOException ignored) {
                    }
                    log = null;
                }
            }
            result = s;
            quietly(controlListener); // last: END's own answer goes out on a connection that is open already -
            for (Socket rig : controlSockets) if (rig != controlSocket.get()) quietly(rig); // and every other one ends with the keeper
            releaseLockFile();
            return s;
        }
    }

    /** The word the keeper ended with; null while it runs. */
    public Safe result() {
        return result;
    }

    /** Whether an error of the keeper's own ended it. */
    public boolean failed() {
        return failed;
    }

    /** Waits until the keeper has ended and whoever asked has its answer. */
    public Safe awaitEnd() throws InterruptedException {
        released.await();
        return result;
    }

    @Override
    public void close() {
        end("closed");
        released.countDown();
    }

    private static void quietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ the program

    /**
     * No arguments: this bench's keeper, everything from the hardware file. "end" / "state": said to the keeper that
     * runs. Else name=value, for Betaflight as a program and for the tests: board=tcp:127.0.0.1:5761 (never a serial
     * port: a real board's keeper is the hardware file's), control= phone= setup= (ports), folder= lock= cells= floor=
     * battery=none (Betaflight as a program: no battery to read) budget= window= heartbeat= ceiling= strict=false.
     */
    public static void main(String[] args) {
        int code;
        try {
            code = run(args, System.out);
        } catch (Throwable t) {
            t.printStackTrace();
            code = EXIT_NO_START;
        }
        System.exit(code);
    }

    static int run(String[] args, PrintStream out) throws Exception {
        Map<String, String> said = new LinkedHashMap<>();
        String word = "";
        for (String a : args) {
            if (a == null || a.isBlank()) continue;
            int eq = a.indexOf('=');
            if (eq > 0) said.put(a.substring(0, eq).trim(), a.substring(eq + 1).trim());
            else word = a.trim().toLowerCase(Locale.ROOT);
        }
        if (word.equals("end") || word.equals("state")) return tell(word, said, out);
        if (!word.isEmpty()) {
            out.println("Not understood: " + word + ". fcKeeper, fcKeeper end, fcKeeper state.");
            return EXIT_NO_START;
        }
        Config config = new Config();
        BoardSide side;
        PhoneAdb adb = null;
        String board = said.get("board");
        if (board == null) {
            Hardware bench;
            try {
                bench = Hardware.load(); // says what is missing, and where
                forHardware(config, bench, Hardware.file().getParent());
            } catch (IllegalStateException said2) {
                out.println("THE KEEPER DID NOT START: " + said2.getMessage());
                return EXIT_NO_START;
            }
            side = serial(bench.boardPort());
            try {
                PhoneRig.Target phone = PhoneRig.Target.device();
                if (phone.setUp()) adb = new PhoneAdb.Sdk(phone.sdk(), null, phone.serial());
                else out.println("No adb in the Android SDK at " + phone.sdk() + ": the phone's port is not led down the cable by the keeper (adb reverse by hand).");
            } catch (IllegalStateException noPhone) {
                out.println("No phone on adb for the keeper (" + noPhone.getMessage() + "): the phone's port is not led down the cable.");
            }
        } else {
            if (!board.startsWith("tcp:")) {
                out.println("board=" + board + ": by hand the keeper takes Betaflight as a program only (board=tcp:127.0.0.1:<port>); a real board's port is the hardware file's.");
                return EXIT_NO_START;
            }
            String[] at = board.substring(4).split(":");
            side = tcp(at[0], Integer.parseInt(at[1]));
            config.ports(number(said, "control", 0), number(said, "phone", 0), number(said, "setup", 0));
        }
        if (said.containsKey("folder")) config.folder = Path.of(said.get("folder"));
        if (said.containsKey("lock")) config.lockFile = Path.of(said.get("lock"));
        if (said.containsKey("armedFile")) config.budgetFile = Path.of(said.get("armedFile"));
        if (said.containsKey("battery") && board != null) config.noBattery = said.get("battery").equalsIgnoreCase("none"); // Betaflight as a program: battery=none
        if (said.containsKey("cells")) config.cells = number(said, "cells", 0);
        if (said.containsKey("floor")) config.cellFloor = Double.parseDouble(said.get("floor"));
        if (said.containsKey("budget")) config.budgetS = number(said, "budget", 600);
        if (said.containsKey("window")) config.windowMaxS = number(said, "window", 60);
        if (said.containsKey("ceiling")) config.ceiling = Math.min(Hardware.THROTTLE_CEILING, number(said, "ceiling", Hardware.THROTTLE_CEILING));
        if (said.containsKey("heartbeat")) config.heartbeatMs = number(said, "heartbeat", 2000);
        if (said.containsKey("back")) config.backMs = number(said, "back", 12_000);
        if (said.containsKey("strict")) config.strict = !said.get("strict").equalsIgnoreCase("false");
        // (second round) for Betaflight as a program, what a real board's keeper has from the hardware file: failsafe=end - the keeper
        // ends an arm window when the sticks stop - or failsafe=run; settings=true - its settings read in its CLI; outputLimit=15
        if (said.containsKey("failsafe")) {
            String how = said.get("failsafe").toLowerCase(Locale.ROOT);
            if (!how.equals("end") && !how.equals("run")) {
                out.println("failsafe=" + said.get("failsafe") + ": failsafe=end (the keeper ends an arm window when the sticks stop) or failsafe=run (the board's own failsafe runs).");
                return EXIT_NO_START;
            }
            if (board != null) config.failsafeEndsWindow = how.equals("end"); // (a real board's is the hardware file's word, never an argument's)
        }
        if (said.containsKey("settings") && board != null) config.readSettings = said.get("settings").equalsIgnoreCase("true");
        if (said.containsKey("outputLimit") && board != null) config.outputLimitMax = number(said, "outputLimit", 0);

        FcKeeper keeper;
        try {
            keeper = new FcKeeper(config, side, adb);
            keeper.start();
        } catch (IOException | IllegalArgumentException e) {
            out.println("THE KEEPER DID NOT START: " + e.getMessage());
            return EXIT_NO_START;
        }
        // told to go (Ctrl+C, a window closed): the board is made safe first. A process that is killed outright runs none of this.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> keeper.end("the keeper's process was told to go"), "keeperShutdown"));
        Safe s = keeper.awaitEnd();
        out.println(s.text());
        return !s.safe() ? EXIT_NOT_SAFE : keeper.failed() ? EXIT_ERROR : EXIT_SAFE;
    }

    /** A real board's keeper: everything from this bench's hardware file, its own files beside it. */
    static void forHardware(Config config, Hardware bench, Path home) {
        config.ports(bench.keeperPort(), bench.keeperPort() + 1, bench.keeperPort() + 2);
        config.battery(bench.cells(), bench.cellFloor());
        config.armAux = bench.armAux();
        config.budgetS = bench.motorSecondsPerHour();
        config.lockFile = home.resolve("keeper.lock");
        config.budgetFile = home.resolve("keeper-armed.txt");
        config.folder = home.resolve("keeper");
        // >>> second round: a real board's own failsafe is never let run on one word alone, its settings are always read, and
        // no arm window opens over its own output limit
        if (bench.letFailsafeRun() && !bench.failsafeSeenOnSitl()) {
            throw new IllegalStateException("The hardware file " + bench.source() + " says board.letFailsafeRun=true without board.failsafeSeenOnSitl=true. A real "
                    + "board's own failsafe is let run only when somebody has seen, on Betaflight as a program, what its failsafe procedure does; until then the "
                    + "keeper ends an arm window itself when the sticks stop. Take the line out, or add the second word once it is true.");
        }
        config.failsafeEndsWindow = !bench.letFailsafeRun();
        config.failsafeSeenOnSitl = bench.failsafeSeenOnSitl();
        config.readSettings = true;
        config.outputLimitMax = bench.outputLimitMax();
        // <<< second round
    }

    private static int number(Map<String, String> said, String key, int unset) {
        String v = said.get(key);
        return v == null || v.isBlank() ? unset : Integer.parseInt(v.trim());
    }

    /** "end" and "state", said to the keeper that runs (control=port, else the hardware file's). */
    private static int tell(String word, Map<String, String> said, PrintStream out) {
        int control = said.containsKey("control") ? Integer.parseInt(said.get("control")) : Hardware.load().keeperPort();
        try (KeeperClient keeper = new KeeperClient(control, 60_000)) {
            String answer = keeper.ask(word.equals("end") ? "END by hand" : "STATE");
            out.println(answer);
            return word.equals("state") || answer.startsWith("OK") ? EXIT_SAFE : EXIT_NOT_SAFE;
        } catch (IOException e) {
            out.println("NO KEEPER answers on 127.0.0.1:" + control + " (" + e.getMessage() + ")" + (word.equals("end")
                    ? ": nothing was made safe by it - gradlew boardSafe does that with the port free." : "."));
            return EXIT_NO_KEEPER;
        }
    }
}
