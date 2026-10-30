package de.droiddrone.pc.setup;

import com.fazecast.jSerialComm.SerialPort;

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

    private final SerialPort port;

    private BetaflightUsb(SerialPort port) {
        this.port = port;
    }

    /** Serial ports that look like a flight controller's USB, by vendor or by name. */
    public static List<Port> findFlightControllers() {
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
        SerialPort port = SerialPort.getCommPort(p.systemName());
        port.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 1000);
        if (!port.openPort()) {
            throw new IOException(p.systemName() + " is in use - close Betaflight Configurator (or anything else using the flight controller) and try again.");
        }
        port.flushIOBuffers();
        return new BetaflightUsb(port);
    }

    // ------------------------------------------------------------------ MSP

    /**
     * One MSP v1 request and its answer's payload ("$M<" out, "$M>" back). Only while the CLI is not open. Answers to
     * other requests are skipped; Betaflight's error answer ("$M!") throws.
     */
    public byte[] msp(int command, byte[] payload) throws IOException {
        byte[] frame = mspFrame(command, payload);
        write(frame);
        MspParser parser = new MspParser();
        byte[] buf = new byte[256];
        long end = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < end) {
            int n = port.readBytes(buf, buf.length);
            if (n < 0) throw new IOException("The flight controller disconnected");
            for (int i = 0; i < n; i++) {
                MspParser.Frame f = parser.feed(buf[i]);
                if (f == null || f.command() != command) continue;
                if (f.error()) throw new IOException("The flight controller refused MSP command " + command);
                return f.payload();
            }
        }
        throw new IOException("No answer from the flight controller - is it running Betaflight?");
    }

    /** "$M<", size, command, payload, checksum (XOR of size, command and payload). */
    static byte[] mspFrame(int command, byte[] payload) {
        int size = payload == null ? 0 : payload.length;
        if (size > 255 || command < 0 || command > 255) throw new IllegalArgumentException("Not an MSP v1 request");
        byte[] f = new byte[6 + size];
        f[0] = '$';
        f[1] = 'M';
        f[2] = '<';
        f[3] = (byte) size;
        f[4] = (byte) command;
        int checksum = size ^ command;
        for (int i = 0; i < size; i++) {
            f[5 + i] = payload[i];
            checksum ^= payload[i] & 0xff;
        }
        f[5 + size] = (byte) checksum;
        return f;
    }

    /** Puts MSP v1 answers back together from bytes as they arrive; drops anything with a bad checksum. */
    static final class MspParser {
        record Frame(int command, byte[] payload, boolean error) {}

        private int state, size, command, checksum, index;
        private boolean error;
        private byte[] payload;

        Frame feed(byte b) {
            int v = b & 0xff;
            switch (state) {
                case 0 -> state = v == '$' ? 1 : 0;
                case 1 -> state = v == 'M' ? 2 : 0;
                case 2 -> {
                    if (v == '>' || v == '!') {
                        error = v == '!';
                        state = 3;
                    } else {
                        state = 0;
                    }
                }
                case 3 -> {
                    size = v;
                    checksum = v;
                    state = 4;
                }
                case 4 -> {
                    command = v;
                    checksum ^= v;
                    payload = new byte[size];
                    index = 0;
                    state = size == 0 ? 6 : 5;
                }
                case 5 -> {
                    payload[index++] = b;
                    checksum ^= v;
                    if (index == size) state = 6;
                }
                case 6 -> {
                    state = 0;
                    if (v == checksum) return new Frame(command, payload, error);
                }
                default -> state = 0;
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ CLI

    /** Enters the CLI (Betaflight answers '#' with a banner and the "# " prompt). */
    public String enterCli() throws IOException {
        write("#");
        return readUntilPrompt(3000);
    }

    /** Runs one CLI command and returns what it printed (without the echo and prompt). */
    public String command(String command) throws IOException {
        write(command + "\n");
        String out = readUntilPrompt(4000);
        int firstNewline = out.indexOf('\n');
        if (firstNewline >= 0 && out.substring(0, firstNewline).contains(command)) out = out.substring(firstNewline + 1);
        return out.replaceFirst("\\R?# ?$", "").trim();
    }

    /** Leaves the CLI without saving. Betaflight restarts (the port goes away), which also clears a stale MSP arming block. */
    public void exitCli() {
        try {
            write("exit\n");
            Thread.sleep(300);
        } catch (IOException | InterruptedException ignored) {
        }
        close();
    }

    /** "save": stores the settings; the FC reboots and this port goes away. */
    public void saveAndReboot() throws IOException {
        write("save\n");
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
            int n = port.readBytes(buf, buf.length);
            if (n < 0) throw new IOException("The flight controller disconnected");
            if (n > 0) {
                out.write(buf, 0, n);
                String s = out.toString(StandardCharsets.US_ASCII);
                if (s.endsWith("\n# ") || s.endsWith("\r\n# ") || s.equals("# ")) return s;
            }
        }
        String s = out.toString(StandardCharsets.US_ASCII);
        if (s.isEmpty()) throw new IOException("No answer from the flight controller - is it running Betaflight?");
        return s;
    }

    private void write(String text) throws IOException {
        write(text.getBytes(StandardCharsets.US_ASCII));
    }

    private void write(byte[] data) throws IOException {
        if (port.writeBytes(data, data.length) != data.length) throw new IOException("Could not write to the flight controller");
    }

    @Override
    public void close() {
        if (port.isOpen()) port.closePort();
    }
}
