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

    /** UART1's TCP port: 5760 + 1, fixed in Betaflight's SITL (bench.sitl.port says otherwise only for a test). */
    public static int port() {
        return Integer.getInteger("bench.sitl.port", 5761);
    }

    /** The version bench.ps1 started ("4.4.1"), or "" when the run did not say. */
    public static String startedVersion() {
        return System.getProperty("bench.sitl.version", "").trim();
    }

    /** The folder of the SITL that runs (its eeprom.bin, sitl.log), or "" when the run did not say. */
    public static String folder() {
        return System.getProperty("bench.sitl.folder", "").trim();
    }

    /** Why the SITL scenarios cannot run now, or null: something answers MSP on 127.0.0.1:5761. */
    public static String missing() {
        return answersMsp(2000) ? null : "No Betaflight as a program answers MSP on " + HOST + ":" + port()
                + ": .\\bench.ps1 sitl starts one (by hand: tools\\sitl\\start.ps1 -Version 4.4.1 -Feed)";
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

    /** The board, open and answering MSP - waited for while it restarts. {@link #usb()} is put in place first. */
    public static BetaflightUsb open(Consumer<String> log) throws IOException {
        BetaflightUsb.use(usb());
        return BoardPort.open(BOARD, TIMING, log);
    }

    /** MSP_FC_VERSION as "4.4.1". */
    public static String version(BetaflightUsb fc) throws IOException {
        byte[] v = fc.msp(Msp.FC_VERSION, null);
        return v.length < 3 ? "?" : (v[0] & 0xff) + "." + (v[1] & 0xff) + "." + (v[2] & 0xff);
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
            fc.enterCli();
            for (String line : lines) {
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
     * and restarts. Waited for; returns its "serial" lines read afterwards (that visit ends with "exit").
     */
    public static String toDefaults(Consumer<String> log) throws IOException {
        try (BetaflightUsb fc = open(log)) {
            if (Msp.armed(fc.msp(Msp.STATUS, null))) throw new IOException("SITL is armed: not reset");
            fc.enterCli();
            try {
                fc.command("defaults", 3000); // "Rebooting": no prompt follows
            } catch (IOException restarting) {
                // the board restarts with its defaults
            }
        }
        pause(1000);
        try (BetaflightUsb fc = open(log)) {
            fc.enterCli();
            return fc.command("serial");
        } // "exit": Betaflight restarts
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
