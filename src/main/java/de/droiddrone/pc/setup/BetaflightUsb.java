package de.droiddrone.pc.setup;

import com.fazecast.jSerialComm.SerialPort;
import de.droiddrone.common.MspFrames;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A flight controller on this PC's USB, driven through Betaflight's CLI (text commands) or MSP (binary requests, as
 * Betaflight Configurator talks to it). Used by the setup wizard so nobody needs Betaflight Configurator - which, when
 * unplugged without disconnecting, leaves the "MSP" arming block behind - and by the motor test.
 */
public final class BetaflightUsb implements AutoCloseable {
    public record Port(String systemName, String description) {
        @Override
        public String toString() {
            return systemName + " - " + description;
        }
    }

    /** The bytes to and from the flight controller: the serial port, or a fake one in tests. */
    public interface Wire {
        /** What has arrived, waiting a little (the port's read timeout) for the first byte: 0 = nothing, -1 = gone. */
        int read(byte[] buf) throws IOException;

        /** Bytes waiting to be read, without waiting. */
        int available() throws IOException;

        void write(byte[] data) throws IOException;

        boolean isOpen();

        void close();
    }

    /**
     * USB vendors of flight controllers: the chip makers whose virtual COM port the firmware uses, the boards' own
     * IDs, and the USB-serial bridge chips on boards without native USB.
     */
    private static final Set<Integer> FC_VENDORS = Set.of(
            0x0483,  // STMicroelectronics: STM32 boards (Betaflight, INAV, ArduPilot, PX4)
            0x2E3C,  // Artery: AT32 boards
            0x314B,  // Geehy: APM32 boards
            0x28E9,  // GigaDevice: GD32 boards
            0x2E8A,  // Raspberry Pi: RP2040 / RP2350 boards
            0x1209,  // pid.codes: ArduPilot and others
            0x26AC,  // 3D Robotics: Pixhawk
            0x2DAE,  // CubePilot: Cube
            0x3162,  // Holybro
            0x1A86,  // QinHeng CH340 / CH341 / CH9102 bridge
            0x10C4,  // Silicon Labs CP210x bridge
            0x0403,  // FTDI bridge
            0x067B); // Prolific PL2303 bridge

    /**
     * A prompt at the end of what arrived only counts once nothing more follows for this long: "diff all" and
     * "version" print lines that begin with "# " (e.g. "# feature"), and a USB chunk can end right after that "# ".
     */
    static final long PROMPT_SETTLE_MS = 60;

    /** What the pilot is told when a write finds the board armed: nothing is sent to it then. */
    public static final String ARMED = "The drone is armed. Disarm it first.";

    private final Wire wire;
    /** The CLI is open: closing the port then sends "exit", so the board isn't left in CLI mode. */
    private boolean inCli;
    /** Betaflight answered the '#': its CLI is open, and leaving it ("exit" or "save") restarts Betaflight (cli.c). */
    private boolean cliAnswered;
    /** "exit" or "save" went to an open CLI: Betaflight restarts, which takes the arming block off. */
    private boolean restarting;
    /** The arming block "MSP" is set by this connection and was not on before: taken off at close if no restart comes. */
    private boolean blockOurs;
    /** The port is with the ESCs (the passthrough): closing it ends that first, so the board is not left there. */
    private EscLink escs;

    private BetaflightUsb(Wire wire) {
        this.wire = wire;
    }

    /** For tests: a flight controller on a fake wire. */
    static BetaflightUsb onWire(Wire wire) {
        return new BetaflightUsb(wire);
    }

    /**
     * The test bench's USB (1.0.71): the ports it says are there and what answers on them, instead of this PC's serial
     * ports - so Drone setup, the motor test and the compass pages can be run with no board, and never touch one
     * that is plugged in. The app itself never sets it.
     */
    public interface Usb {
        List<Port> ports();

        Wire open(Port port) throws IOException;
    }

    private static volatile Usb usb;

    /** The test bench only; null puts this PC's serial ports back. */
    public static void use(Usb standIn) {
        usb = standIn;
    }

    /** Serial ports that look like a flight controller's USB, by vendor or by name. */
    public static List<Port> findFlightControllers() {
        Usb standIn = usb;
        if (standIn != null) return standIn.ports();
        List<Port> found = new ArrayList<>();
        for (SerialPort p : SerialPort.getCommPorts()) {
            if (isFlightController(p.getVendorID(), p.getPortDescription() + " " + p.getDescriptivePortName())) {
                found.add(new Port(p.getSystemPortName(), p.getDescriptivePortName()));
            }
        }
        return found;
    }

    static boolean isFlightController(int vendorId, String description) {
        String d = description.toLowerCase(Locale.ROOT);
        return FC_VENDORS.contains(vendorId)
                || d.contains("stm") || d.contains("betaflight") || d.contains("at32") || d.contains("inav") || d.contains("ardupilot");
    }

    public static BetaflightUsb open(Port p) throws IOException {
        Usb standIn = usb;
        if (standIn != null) return new BetaflightUsb(standIn.open(p));
        SerialPort port = SerialPort.getCommPort(p.systemName());
        port.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 1000);
        if (!port.openPort()) {
            throw new IOException(p.systemName() + " is in use - close Betaflight Configurator and retry.");
        }
        port.flushIOBuffers();
        return new BetaflightUsb(new PortWire(port));
    }

    /** The real thing: a jSerialComm port. */
    private static final class PortWire implements Wire {
        private final SerialPort port;

        PortWire(SerialPort port) {
            this.port = port;
        }

        @Override
        public int read(byte[] buf) {
            return port.readBytes(buf, buf.length);
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
    }

    // ------------------------------------------------------------------ MSP

    /**
     * One MSP v1 request and its answer's payload ("$M<" out, "$M>" back). Only while the CLI is not open. Answers to
     * other requests are skipped; Betaflight's error answer ("$M!") throws.
     */
    public byte[] msp(int command, byte[] payload) throws IOException {
        write(mspFrame(command, payload));
        byte[] answer = answer(MspFrames.V1, command, 1000, "MSP command " + command);
        if (answer == null) throw new IOException("No answer from the flight controller - is it running Betaflight?");
        return answer;
    }

    /** "$M<", size, command, payload, checksum (XOR of size, command and payload): MspFrames, shared with the phone. */
    static byte[] mspFrame(int command, byte[] payload) {
        return MspFrames.v1Request(command, payload);
    }

    /**
     * One MSP v2 request and its answer's payload ("$X<" out, "$X>" back): for the commands that only exist in v2, such
     * as Betaflight's motor output order and DShot commands to the ESCs. Only while the CLI is not open.
     */
    public byte[] msp2(int command, byte[] payload) throws IOException {
        write(msp2Frame(command, payload));
        byte[] answer = answer(MspFrames.V2, command, 1500, "MSP2 command 0x" + Integer.toHexString(command));
        if (answer == null) throw new IOException("No MSP2 answer - Betaflight may be too old");
        return answer;
    }

    /** "$X<", flag 0, command u16, size u16, payload, CRC-8/DVB-S2 over flag to payload: MspFrames. */
    static byte[] msp2Frame(int command, byte[] payload) {
        return MspFrames.v2Request(command, payload);
    }

    /** MSP v2's checksum: CRC-8 with polynomial 0xD5 (DVB-S2), from 0, no reflection. */
    static int crc8DvbS2(int crc, byte b) {
        return MspFrames.crc8DvbS2(crc, b);
    }

    /**
     * The payload of the answer to command in the given MSP version (empty, never null), read for up to timeoutMs, or
     * null when none came. Answers to other requests are skipped; Betaflight's error answer ("!") throws.
     */
    private byte[] answer(byte version, int command, long timeoutMs, String what) throws IOException {
        MspFrames frames = new MspFrames(version);
        byte[] buf = new byte[256];
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            int n = wire.read(buf);
            if (n < 0) throw new IOException("The flight controller disconnected");
            for (MspFrames.Frame f : frames.feed(buf, n)) {
                if (f.version != version || f.type == MspFrames.REQUEST || f.code != command) continue;
                if (f.isError()) throw new IOException("The flight controller refused " + what);
                return f.payload == null ? new byte[0] : f.payload;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ CLI

    /** Betaflight's MSP_SET_ARMING_DISABLED: 1 sets the arming block "MSP" (and disarms an armed board), 0 takes it off. */
    static final int MSP_SET_ARMING_DISABLED = 99;
    /** The arming block "MSP" among MSP_STATUS' arming-disable flags (Betaflight 4.4's runtime_config.h). */
    static final int ARMING_DISABLED_MSP = 1 << 16;

    /** An MSP write to make before the CLI opens (the CLI hears no MSP). */
    public interface BeforeCli {
        void run() throws IOException;
    }

    /** Enters the CLI (Betaflight answers '#' with a banner and the "# " prompt): {@link #enterCli(BeforeCli)}, nothing first. */
    public String enterCli() throws IOException {
        return enterCli(null);
    }

    /**
     * Enters the CLI, for every CLI write of the app (F-078). Betaflight 4.4.1 ignores '#' while armed (tasks.c,
     * msp_serial.c), and opens its CLI about 100 ms after one without looking again - arming is blocked only once it
     * is open (cli.c). So MSP_STATUS is read first: armed, nothing is sent and the pilot is told ({@link #ARMED}).
     * Not armed, arming is blocked (MSP_SET_ARMING_DISABLED, 1 - it would disarm an armed board, so never before that
     * reading), then {@code first} runs - an MSP write that must reach no armed board either, such as CONFIRM's
     * receiver switch - and the '#' goes. Every way out of Betaflight's CLI restarts it ("exit" always; "save" too,
     * as the app uses it), which takes the block off; when the CLI never opened, close() takes it off.
     * @param first null, or the MSP write to make just before the '#'
     */
    public String enterCli(BeforeCli first) throws IOException {
        if (inCli) throw new IOException("The CLI is open already");
        byte[] status = msp(de.droiddrone.common.FcCommon.MSP_STATUS, null);
        if (MotorTest.armed(status)) throw new IOException(ARMED);
        int flags = armingDisableFlags(status);
        boolean blockedBefore = flags != -1 && (flags & ARMING_DISABLED_MSP) != 0; // somebody else's: left as it is
        msp(MSP_SET_ARMING_DISABLED, new byte[]{1});
        blockOurs |= !blockedBefore;
        if (first != null) first.run();
        inCli = true; // from the first byte: a cut-off banner still leaves the board in the CLI
        write("#");
        try {
            String banner = readUntilPrompt(3000);
            cliAnswered = true;
            return banner;
        } catch (NoAnswer e) {
            throw e; // nothing came: no CLI (close still says "exit", in case), and the block is taken off at close
        } catch (IOException e) {
            cliAnswered = true; // a part of the banner: the CLI is open
            throw e;
        }
    }

    /** MSP_STATUS' arming-disable flags (after the extra flight-mode bytes and the flag count); -1 when not there. */
    static int armingDisableFlags(byte[] status) {
        if (status.length < 16) return -1;
        int at = 17 + (status[15] & 0x0f);
        if (status.length < at + 4) return -1;
        return (status[at] & 0xff) | (status[at + 1] & 0xff) << 8 | (status[at + 2] & 0xff) << 16 | (status[at + 3] & 0xff) << 24;
    }

    /** Nothing at all came back. */
    private static final class NoAnswer extends IOException {
        NoAnswer() {
            super("No answer from the flight controller - is it running Betaflight?");
        }
    }

    /** The CLI is open (and would be left with "exit" on close). */
    public boolean inCli() {
        return inCli;
    }

    /** Runs one CLI command and returns what it printed (without the echo and prompt). */
    public String command(String command) throws IOException {
        return command(command, 4000);
    }

    /** With its own time limit, for long answers ("diff all"). */
    public String command(String command, long timeoutMs) throws IOException {
        write(command + "\n");
        String out = readUntilPrompt(timeoutMs);
        int firstNewline = out.indexOf('\n');
        if (firstNewline >= 0 && out.substring(0, firstNewline).contains(command)) out = out.substring(firstNewline + 1);
        return out.replaceFirst("\\R?# ?$", "").trim();
    }

    /** Leaves the CLI without saving. Betaflight restarts (the port goes away), which also clears a stale MSP arming block. */
    public void exitCli() {
        close();
    }

    /**
     * "save": stores the settings; the FC reboots and this port goes away. (Betaflight 4.4.1 stays in its CLI only
     * after a failed "batch", which the app never starts - and arming stays blocked there until a restart.)
     */
    public void saveAndReboot() throws IOException {
        write("save\n");
        inCli = false; // the board restarts: no "exit" to send
        restarting = cliAnswered;
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }
        close();
    }

    private String readUntilPrompt(long timeoutMs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            int n = wire.read(buf);
            if (n < 0) throw new IOException("The flight controller disconnected");
            if (n == 0) continue;
            out.write(buf, 0, n);
            String s = out.toString(StandardCharsets.US_ASCII);
            if (endsWithPrompt(s) && quietFor(PROMPT_SETTLE_MS)) return s;
        }
        String s = out.toString(StandardCharsets.US_ASCII);
        if (s.isEmpty()) throw new NoAnswer();
        // part of an answer: never read settings from it (or decide what to write)
        throw new IOException("The flight controller's answer was cut off - try again");
    }

    static boolean endsWithPrompt(String s) {
        return s.endsWith("\n# ") || s.endsWith("\r\n# ") || s.equals("# ");
    }

    /** True when nothing more arrives for ms: only then is a "# " at the end the prompt and not a "# ..." line's start. */
    private boolean quietFor(long ms) throws IOException {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            if (wire.available() > 0) return false;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return wire.available() <= 0;
    }

    /** What firmware answers on this port over MSP ("BTFL", "INAV", ...), without entering the CLI (no restart). */
    public String firmware() throws IOException {
        return new String(msp(2 /* MSP_FC_VARIANT */, null), StandardCharsets.US_ASCII).trim();
    }

    /**
     * The flight modes this Betaflight offers, as permanent mode ids (MSP_BOXIDS): only those its sensors and features
     * allow (ANGLE needs the accelerometer, GPS RESCUE the GPS feature, POS HOLD both and Betaflight 2025.12).
     */
    public Set<Integer> boxIds() throws IOException {
        Set<Integer> ids = new java.util.HashSet<>();
        for (byte b : msp(119 /* MSP_BOXIDS */, null)) ids.add(b & 0xff);
        return ids;
    }

    /** Betaflight's feature mask (MSP_FEATURE_CONFIG; bits in {@link BetaflightPlan}). */
    public int featureMask() throws IOException {
        byte[] p = msp(36 /* MSP_FEATURE_CONFIG */, null);
        if (p.length < 4) throw new IOException("Short feature answer");
        return (p[0] & 0xff) | (p[1] & 0xff) << 8 | (p[2] & 0xff) << 16 | (p[3] & 0xff) << 24;
    }

    /**
     * Replaces the feature mask (MSP_SET_FEATURE_CONFIG), as Betaflight Configurator's Receiver tab does. Held in
     * RAM until a save: the CLI's "save" that follows stores it with the rest.
     */
    public void setFeatureMask(int mask) throws IOException {
        msp(37 /* MSP_SET_FEATURE_CONFIG */, new byte[]{(byte) mask, (byte) (mask >> 8), (byte) (mask >> 16), (byte) (mask >> 24)});
    }

    /** The battery as Betaflight sees it: its cell count (0 = no battery detected) and the pack voltage. */
    public record BatteryState(int cells, double volts) {
        /** A battery is plugged in (USB power alone reads well under 3 V, a 1S pack over it). */
        public boolean present() {
            return cells > 0 || volts >= 3;
        }
    }

    /** MSP_BATTERY_STATE: Betaflight's own cell count and the voltage, for the cell count the pilot enters. */
    public BatteryState batteryState() throws IOException {
        return parseBatteryState(msp(130 /* MSP_BATTERY_STATE */, null));
    }

    /** cells u8, capacity u16, legacy voltage u8 (0.1 V), mAh u16, amps u16, state u8, voltage u16 (0.01 V, from 4.0). */
    static BatteryState parseBatteryState(byte[] p) throws IOException {
        if (p.length < 4) throw new IOException("Short battery answer");
        double volts = p.length >= 11 ? ((p[9] & 0xff) | (p[10] & 0xff) << 8) / 100.0 : (p[3] & 0xff) / 10.0;
        return new BatteryState(p[0] & 0xff, volts);
    }

    /**
     * Waits up to timeoutMs for the GPS to talk to Betaflight: MSP_STATUS' sensor bit 3 (SENSOR_GPS). Betaflight sets
     * it on every message from the GPS, fix or not, and clears it after 2.5 s of silence - so this works indoors.
     * Never set means a GPS hardware failure: none fitted, not powered, or not on the port set for it.
     */
    public boolean waitForGps(long timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        do {
            byte[] s = msp(101 /* MSP_STATUS */, null);
            if (s.length >= 6 && ((((s[4] & 0xff) | (s[5] & 0xff) << 8)) & (1 << 3)) != 0) return true;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.currentTimeMillis() < end);
        return false;
    }

    private void write(String text) throws IOException {
        write(text.getBytes(StandardCharsets.US_ASCII));
    }

    private void write(byte[] data) throws IOException {
        wire.write(data);
    }

    /**
     * Hands the port to the ESCs (1.0.71, {@link EscLink}): Betaflight stops its motor outputs and passes what the PC
     * says on to each ESC's bootloader, until the returned link is closed - only then MSP answers again. Refused on
     * an armed drone and with the CLI open. Battery on, props off.
     */
    public EscLink escs(java.util.function.Consumer<String> log) throws IOException {
        if (inCli) throw new IOException("The CLI is open");
        if (escs != null && !escs.closed()) throw new IOException("The ESC passthrough is open already");
        if (MotorTest.armed(msp(de.droiddrone.common.FcCommon.MSP_STATUS, null))) throw new IOException(ARMED);
        byte[] answer = msp(EscLink.MSP_SET_PASSTHROUGH, null);
        int count = answer.length > 0 ? answer[0] & 0xff : 0;
        if (count == 0) throw new IOException("Betaflight has no motor outputs for the ESCs.");
        escs = new EscLink(wire, count, log);
        return escs;
    }

    /**
     * Lets go of the port; with the CLI open, "exit" goes first so the board isn't left in CLI mode (it restarts).
     * The arming block this connection set is taken off when no restart comes to do it (F-078: a CLI that never
     * opened, or an error between the block and the '#'). The port is let go of whatever happens.
     */
    @Override
    public void close() {
        try {
            if (escs != null && !escs.closed() && wire.isOpen()) escs.close();
            if (inCli && wire.isOpen()) {
                inCli = false;
                restarting |= cliAnswered;
                try {
                    write("exit\n");
                    Thread.sleep(300);
                } catch (IOException | InterruptedException ignored) {
                    // the port may be gone already
                }
            }
            if (blockOurs && !restarting && wire.isOpen()) {
                blockOurs = false;
                try {
                    msp(MSP_SET_ARMING_DISABLED, new byte[]{0});
                } catch (IOException | RuntimeException ignored) {
                    // the port is gone (Betaflight lost its power: the block with it), or the CLI took it after all
                }
            }
        } finally {
            wire.close();
        }
    }
}
