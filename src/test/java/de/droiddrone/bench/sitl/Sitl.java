package de.droiddrone.bench.sitl;

import de.droiddrone.bench.hw.BoardPort;
import de.droiddrone.bench.hw.FcKeeper;
import de.droiddrone.bench.hw.Msp;
import de.droiddrone.common.MspFrames;
import de.droiddrone.pc.setup.BetaflightUsb;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Betaflight as a program (SITL) for the bench's scenarios: the one that tools\sitl\start.ps1 started inside WSL
 * (bench.ps1 sitl starts it around the suite), reached on this PC's loopback at TCP 5761 - its UART1, MSP - as if it
 * were a flight controller on USB: {@link #usb()} is the bench's USB with that one board on it, so BoardSnapshot,
 * BoardRestore and the keeper take it as they take the real one.
 * <p>
 * What SITL is and is not (docs/TESTING.md, "Betaflight as a program"): Betaflight's own flight code, built for the
 * PC; no sensors but what a simulator sends it - the level-attitude feeder stands in (start.ps1 -Feed); no battery
 * (it reads 0 V); motors by PWM only; save, the CLI's exit and MSP's reboot end the program, and the start script
 * starts it again at once in the same folder, its eeprom.bin kept.
 */
public final class Sitl {
    public static final String HOST = "127.0.0.1";
    /** The bench's name for it, where a COM port's name would be. */
    public static final BetaflightUsb.Port BOARD = new BetaflightUsb.Port("SITL", "Betaflight as a program on tcp:" + HOST + ":" + port());
    /** It is back from a restart in about a second; a board on USB is given 25. */
    public static final BoardPort.Timing TIMING = new BoardPort.Timing(200, 20_000);
    /** MSP_ACC_CALIBRATION, and MSP_DEBUG (four int16: with debug_mode FAILSAFE the fourth is the failsafe's phase). */
    static final int ACC_CALIBRATION = 205, DEBUG = 254;
    /** Betaflight 4.4's arming blocks that the drone's set-up has to clear: CALIB (12) and NO_ACC_CAL (23). */
    static final int CALIB_BIT = 12, NO_ACC_CAL_BIT = 23, BOOTGRACE_BIT = 9;
    /**
     * How long after it answers again Betaflight's status is taken as its word: in the first tens of milliseconds
     * after a restart its arming checks have not run yet (seen 2026-11-23: BOOTGRACE alone, the full set 40 ms later).
     */
    static final long SETTLE_MS = 1500;
    /** Betaflight 4.4's failsafe phases (failsafe.h), by number. */
    static final List<String> PHASES = List.of("IDLE", "RX_LOSS_DETECTED", "LANDING", "LANDED", "RX_LOSS_MONITORING", "RX_LOSS_RECOVERED", "GPS_RESCUE");

    private Sitl() {
    }

    /**
     * UART1's TCP port: 5760 + 1, fixed in Betaflight's SITL - or, as bench.ps1 sets bench.sitl.port, the forwarder's in
     * front of it (tools\sitl\forwarder.py, 15761): it stays while Betaflight restarts, where WSL's forwarding of
     * 127.0.0.1 now and then lost SITL's own port after a restart (2026-11-23).
     */
    public static int port() {
        return Integer.getInteger("bench.sitl.port", 5761);
    }

    /** UART n's TCP port, on the same side as {@link #port}: SITL's own 5760 + n, or the forwarder's 15760 + n. */
    public static int uart(int n) {
        return port() - 1 + n;
    }

    /** The version bench.ps1 started ("4.4.1"), or "" when the run did not say. */
    public static String startedVersion() {
        return System.getProperty("bench.sitl.version", "").trim();
    }

    /** The folder of the SITL that runs (its eeprom.bin, sitl.log), or "" when the run did not say. */
    public static String folder() {
        return System.getProperty("bench.sitl.folder", "").trim();
    }

    /**
     * Why the SITL scenarios cannot run now, or null: something answers MSP on 127.0.0.1:5761. One that bench.ps1
     * started and that does not answer is revived first ({@link #revive}): a scenario before may have left it so.
     */
    public static String missing() {
        if (answersMsp(2000) || revive(System.out::println)) return null;
        return "No Betaflight as a program answers MSP on " + HOST + ":" + port()
                + ": .\\bench.ps1 sitl starts one (by hand: tools\\sitl\\start.ps1 -Version 4.4.1 -Feed)";
    }

    /**
     * The SITL bench.ps1 started (bench.sitl.version is set), answering no MSP: a CLI left open answers none - "exit"
     * leaves it (Betaflight restarts); else the program is ended (pkill in WSL) and its supervisor (tools\sitl\sitl.sh)
     * starts it again at once, eeprom.bin kept. Seen 2026-11-23: after the snapshot scenario's restore no connection
     * reached Betaflight for a minute, and the two scenarios after it were skipped. A SITL started by hand is left alone.
     * @return true once it answers MSP again
     */
    static boolean revive(Consumer<String> log) {
        if (startedVersion().isEmpty()) return false;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, port()), 2000);
            s.getOutputStream().write("exit\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); // MSP takes no notice of it
        } catch (IOException ignored) {
            // not even a connection
        }
        if (answersWithin(8000)) {
            log.accept("SITL answered no MSP: its CLI was left open - \"exit\" sent, Betaflight restarted.");
            return true;
        }
        try {
            Process p = new ProcessBuilder("wsl.exe", "-d", "Ubuntu", "-u", "root", "--", "pkill", "-x", "betaflight_SITL")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (IOException e) {
            log.accept("SITL answers no MSP, and its program could not be restarted: " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        boolean back = answersWithin(20_000);
        log.accept("SITL answered no MSP, not even after \"exit\": its program was ended and started again by its supervisor - "
                + (back ? "it answers again." : "it does NOT answer."));
        return back;
    }

    private static boolean answersWithin(long ms) {
        long end = System.currentTimeMillis() + ms;
        do {
            if (answersMsp(1000)) return true;
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.currentTimeMillis() < end);
        return false;
    }

    /**
     * Each scenario starts from a fresh board, whatever the one before left (2026-11-23: one left it armed - "SITL is
     * armed: not set up", "Betaflight opens no CLI then" - another its CLI open, another PREARM on AUX3): answering
     * MSP ({@link #revive} if not), disarmed, Betaflight's defaults (the CLI's "defaults", which restarts it - as
     * a run's fresh SITL starts, but in the same folder). What was found is said.
     */
    public static String fresh(Consumer<String> log) throws IOException {
        List<String> found = new ArrayList<>();
        if (!answersMsp(2000)) {
            if (!revive(log)) throw new IOException("SITL answers no MSP and could not be revived");
            found.add("it answered no MSP: revived");
        }
        String serial = toDefaults(log, found);
        found.add("Betaflight's defaults, restarted (" + (serial.contains("serial 0 1 ") ? "UART1 MSP" : "serial: " + serial.replaceAll("\\s+", " ")) + ")");
        return String.join("; ", found);
    }

    /** MSP_API_VERSION asked on a connection of its own (SITL takes one at a time: none may be open). */
    static boolean answersMsp(int ms) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, port()), ms);
            s.setSoTimeout(100);
            s.getOutputStream().write(MspFrames.v1Request(Msp.API_VERSION, null));
            InputStream in = s.getInputStream();
            MspFrames frames = new MspFrames();
            byte[] buf = new byte[256];
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                int n;
                try {
                    n = in.read(buf);
                } catch (java.net.SocketTimeoutException nothingYet) {
                    continue;
                }
                if (n < 0) return false;
                for (MspFrames.Frame f : frames.feed(buf, n)) if (f.code == Msp.API_VERSION && !f.isError()) return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** The bench's USB with one board on it: SITL. Always "plugged in"; opening it fails while Betaflight restarts. */
    public static BetaflightUsb.Usb usb() {
        FcKeeper.BoardSide side = FcKeeper.tcp(HOST, port());
        return new BetaflightUsb.Usb() {
            @Override
            public List<BetaflightUsb.Port> ports() {
                return List.of(BOARD);
            }

            @Override
            public BetaflightUsb.Wire open(BetaflightUsb.Port p) throws IOException {
                if (!p.systemName().equals(BOARD.systemName())) throw new IOException(p.systemName() + " is not there");
                BetaflightUsb.Wire wire = side.open();
                try {
                    clean(wire);
                } catch (IOException e) {
                    wire.close();
                    throw e;
                }
                return wire;
            }
        };
    }

    /**
     * SITL keeps what it had to say to the client before - the answers to requests still in its queue when that one
     * went (it takes 100 a second) - and sends it to the next client with that one's first answer, cut where its ring
     * buffer ran over: a "diff all" read with that in front is not whole (seen 2026-11-22 after the keeper's scenario).
     * So one question is asked on a new connection and everything that comes until nothing has for 150 ms is thrown
     * away. (A board on USB has its port's buffers emptied when it is opened.)
     */
    static void clean(BetaflightUsb.Wire wire) throws IOException {
        wire.write(MspFrames.v1Request(Msp.API_VERSION, null));
        byte[] buf = new byte[4096];
        long end = System.currentTimeMillis() + 3000, quietSince = System.currentTimeMillis();
        while (System.currentTimeMillis() < end && System.currentTimeMillis() - quietSince < 150) {
            int n = wire.read(buf); // waits up to 100 ms for a first byte
            if (n < 0) throw new IOException("SITL closed the connection");
            if (n > 0) quietSince = System.currentTimeMillis();
        }
    }

    /**
     * The board, open and answering MSP - waited for while it restarts. {@link #usb()} is put in place first. One that
     * bench.ps1 started and that does not come back within TIMING's 20 s is revived once ({@link #revive}): now and
     * then a restart left Betaflight taking no connection at all (2026-11-23, with the timer slack set too).
     */
    public static BetaflightUsb open(Consumer<String> log) throws IOException {
        BetaflightUsb.use(usb());
        try {
            return BoardPort.open(BOARD, TIMING, log);
        } catch (IOException notBack) {
            if (startedVersion().isEmpty() || !revive(log)) throw notBack;
            BetaflightUsb.use(usb());
            return BoardPort.open(BOARD, TIMING, log);
        }
    }

    /** MSP_FC_VERSION as "4.4.1" - or "2025.12.5" for the 25.12.5 that Betaflight 2025.12.5 says. */
    public static String version(BetaflightUsb fc) throws IOException {
        byte[] v = fc.msp(Msp.FC_VERSION, null);
        if (v.length < 3) return "?";
        int major = v[0] & 0xff;
        // Betaflight 2025.12 on says its year in two digits (25.12.5): named as its release is (2025.12.5), as bench.ps1 starts it
        return (major >= 25 ? 2000 + major : major) + "." + (v[1] & 0xff) + "." + (v[2] & 0xff);
    }

    /**
     * The real drone's settings as far as they matter to arming and failsafe (Drone setup's, and the hardware file's
     * board), on SITL: a receiver over MSP (SITL's own default), ARM on AUX1 1700-2100, the GPS on UART3 (nothing
     * answers there: no fix, as indoors), GPS rescue as the failsafe after failsafe_delay 200 (20 s), rxfail holding
     * the throttle and AUX1, arming without a GPS fix allowed (Drone setup always sets it), serial_update_rate_hz 2000
     * (Drone setup's own value). Beside that what SITL needs: PWM motors (its only motor driver; the drone has DShot),
     * debug_mode FAILSAFE (MSP_DEBUG then says the failsafe's phase), motor_output_limit in every PID profile as asked.
     * Saved; Betaflight restarts. Then the accelerometer calibrated, level on the feeder (it saves by itself), as the
     * drone's was.
     * <p>
     * serial_update_rate_hz matters more on SITL than on a board: its gyro loop takes nearly all its time, and at
     * Betaflight's own 100 its serial task - every MSP answer, every stick frame - ran 18 to 32 times a second
     * (2026-11-23, 4.4.1: its "tasks"), stick frames queued, MSP answers came 0.1 to 1 s late and the receiver was
     * lost for a tenth of a second about every two seconds (RXLOSS); at 2000 the serial task ran about 75 times a
     * second and the receiver was lost a quarter as often.
     * @param outputLimit motor_output_limit, per cent: 100 is Betaflight's own (the drone's), 15 the rig's cap
     * @return what was done, a line each
     */
    public static List<String> likeTheDrone(int outputLimit, Consumer<String> log) throws IOException {
        List<String> lines = new ArrayList<>(List.of(
                "set motor_pwm_protocol = PWM",
                "serial 2 2 115200 57600 0 115200",
                "feature GPS",
                "aux 0 0 0 1700 2100 0 0",
                "set failsafe_procedure = GPS-RESCUE",
                "set failsafe_delay = 200",
                "set failsafe_throttle_low_delay = 200",
                "rxfail 3 h",
                "rxfail 4 h",
                "set gps_rescue_allow_arming_without_fix = ON",
                "set serial_update_rate_hz = 2000",
                // the accelerometer calibrated, level: the feeder's level reads level, so no trim (the fourth value is
                // Betaflight's "calibration completed"). Asking MSP_ACC_CALIBRATION alone left NO_ACC_CAL now and then
                // (2026-11-23): see below
                "set acc_calibration = 0,0,0,1",
                "set debug_mode = FAILSAFE"));
        List<String> said = new ArrayList<>();
        try (BetaflightUsb fc = open(log)) {
            if (Msp.armed(fc.msp(Msp.STATUS, null))) throw new IOException("SITL is armed: not set up");
            if (version(fc).startsWith("4.3.")) {
                // 4.3's SITL ends with a segmentation fault a second after it starts with a GPS on a UART (tools\sitl\sitl.sh
                // patches that from 4.4 on only), and it has no GPS rescue (its SITL target leaves it out): no GPS, failsafe DROP
                lines.removeIf(l -> l.startsWith("serial 2 ") || l.equals("feature GPS") || l.startsWith("set failsafe_procedure"));
                lines.add(4, "set failsafe_procedure = DROP");
                said.add("(Betaflight 4.3 as a program: no GPS on UART3 - its SITL crashes with one - and failsafe DROP, it has no GPS rescue)");
            }
            fc.enterCli();
            for (String line : lines) {
                // a setting this Betaflight does not have is left out and said, as Drone setup does it (GpsSetup.armingWithoutFixPlan:
                // "get" first) - 4.3.2 has no gps_rescue_allow_arming_without_fix, its "set" was refused (2026-11-23)
                if (line.startsWith("set ")) {
                    String name = line.substring(4, line.indexOf('=')).trim();
                    String now = fc.command("get " + name);
                    if (!now.contains(name + " = ")) { // (not "refused": 4.5's list of debug modes has AC_ERROR in it)
                        said.add("(" + name + " left out: this Betaflight does not have it - \"get\" said " + now.strip().replaceAll("\\s+", " ") + ")");
                        continue;
                    }
                    // a value it does not offer (4.3.2 has no debug_mode FAILSAFE): left out too
                    String value = line.substring(line.indexOf('=') + 1).trim();
                    java.util.regex.Matcher allowed = java.util.regex.Pattern.compile("Allowed values:\\s*(.*)").matcher(now);
                    if (allowed.find() && !java.util.Arrays.asList(allowed.group(1).trim().split("\\s*,\\s*")).contains(value)) {
                        said.add("(" + line + " left out: this Betaflight offers " + name + " " + allowed.group(1).trim() + ")");
                        continue;
                    }
                }
                taken(fc, line);
                said.add(line);
            }
            String inUse = fc.command("profile").replaceAll("(?s).*?profile\\s+(\\d+).*", "$1"); // "profile 0": the one in use
            int profiles = 0;
            for (int p = 0; p < 8; p++) {
                String answer = fc.command("profile " + p);
                if (refused(answer)) break; // no such PID profile: that was all of them
                taken(fc, "set motor_output_limit = " + outputLimit);
                profiles++;
            }
            fc.command("profile " + (inUse.matches("\\d+") ? inUse : "0"));
            said.add("set motor_output_limit = " + outputLimit + " in each of the " + profiles + " PID profiles");
            fc.saveAndReboot();
        }
        // the accelerometer, checked: no NO_ACC_CAL (GPS rescue will not arm with it). It used to be calibrated only
        // when the status after the save said NO_ACC_CAL - and on the suite of 2026-11-23 it did not say so then (not
        // even 1.7 s on), but did after the next restart, so the calibration was skipped and the board stayed
        // NO_ACC_CAL for the whole scenario (three times). Hence acc_calibration above; and the status is read only
        // once Betaflight has answered for a moment (its first one after a restart can be BOOTGRACE alone). Should it
        // still say NO_ACC_CAL, it is calibrated level on the feeder (MSP_ACC_CALIBRATION, saved by Betaflight).
        long end = System.currentTimeMillis() + 30_000;
        boolean asked = false;
        while (true) {
            int flags;
            try (BetaflightUsb fc = open(log)) {
                pause(SETTLE_MS);
                flags = Msp.armingDisableFlags(fc.msp(Msp.STATUS, null));
                if ((flags >> NO_ACC_CAL_BIT & 1) == 0 && (flags >> CALIB_BIT & 1) == 0) break;
                if (!asked && (flags >> NO_ACC_CAL_BIT & 1) != 0 && (flags >> CALIB_BIT & 1) == 0) {
                    fc.msp(ACC_CALIBRATION, null);
                    asked = true;
                    said.add("accelerometer calibrated level (MSP_ACC_CALIBRATION, on the feeder's level attitude)");
                }
            }
            if (System.currentTimeMillis() > end) throw new IOException("SITL's accelerometer did not calibrate (" + Msp.armingBlocks(flags) + "): is the feeder on (start.ps1 -Feed)?");
            pause(500);
        }
        return said;
    }

    /**
     * The board back to Betaflight's own defaults - as a run's fresh SITL starts - for a scenario that leaves more
     * changed than the next one sets up for itself (the suite's scenarios share one SITL and its eeprom.bin): the CLI's
     * "defaults" resets everything (the accelerometer's calibration, the serial ports, what Drone setup wrote), saves
     * and restarts. An armed board is disarmed first (MSP's arming block: no CLI opens while armed). Waited for;
     * returns its serial ports afterwards as "serial &lt;port&gt; &lt;functions&gt;" lines, read over MSP.
     */
    public static String toDefaults(Consumer<String> log) throws IOException {
        return toDefaults(log, new ArrayList<>());
    }

    private static String toDefaults(Consumer<String> log, List<String> found) throws IOException {
        try (BetaflightUsb fc = open(log)) {
            if (Msp.armed(fc.msp(Msp.STATUS, null))) {
                fc.msp(Msp.ARMING_DISABLE, new byte[]{1}); // disarms it; the restart below takes the block off
                long end = System.currentTimeMillis() + 5000;
                while (Msp.armed(fc.msp(Msp.STATUS, null))) {
                    if (System.currentTimeMillis() > end) throw new IOException("SITL is armed and did not disarm on MSP's arming block");
                    pause(100);
                }
                found.add("it was ARMED: disarmed by MSP's arming block");
            }
            fc.enterCli();
            try {
                fc.command("defaults", 3000); // "Rebooting": no prompt follows
            } catch (IOException restarting) {
                // the board restarts with its defaults
            }
        }
        pause(1000);
        // the serial ports as Betaflight has them now, over MSP - no CLI visit, so no second restart (open revives a
        // Betaflight that takes no connection after the first: seen now and then on 2026-11-23)
        try (BetaflightUsb fc = open(log)) {
            return serialLines(fc.msp(MSP_CF_SERIAL_CONFIG, null));
        }
    }

    /** MSP_CF_SERIAL_CONFIG: per port its identifier, function mask (u16) and four baud rate indexes. */
    static final int MSP_CF_SERIAL_CONFIG = 54;

    /** MSP_CF_SERIAL_CONFIG as the CLI's "serial" lines say it: "serial 0 1 ...", one a port (function mask only). */
    static String serialLines(byte[] p) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i + 7 <= p.length; i += 7) s.append("serial ").append(p[i] & 0xff).append(' ').append(Msp.u16(p, i + 1)).append(" \n");
        return s.toString();
    }

    /** A CLI line Betaflight has to take: its "error", "invalid" or "unavailable" throws. */
    static void taken(BetaflightUsb fc, String line) throws IOException {
        String answer = fc.command(line);
        if (refused(answer)) throw new IOException("SITL refused \"" + line + "\": " + answer.strip());
    }

    private static boolean refused(String answer) {
        String low = answer.toLowerCase(Locale.ROOT);
        return low.contains("error") || low.contains("invalid") || low.contains("unavailable");
    }

    /** Waits until the board says no arming block but these (a mask of bits); returns its blocks then. */
    static String waitForBlocksOnly(BetaflightUsb fc, int allowedMask, long ms) throws IOException {
        long end = System.currentTimeMillis() + ms;
        int flags;
        do {
            flags = Msp.armingDisableFlags(fc.msp(Msp.STATUS, null));
            if (flags != -1 && (flags & ~allowedMask) == 0) return Msp.armingBlocks(flags);
            pause(200);
        } while (System.currentTimeMillis() < end);
        throw new IOException("SITL still has arming blocks after " + ms / 1000 + " s: " + Msp.armingBlocks(flags));
    }

    static void pause(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("interrupted");
        }
    }
}
