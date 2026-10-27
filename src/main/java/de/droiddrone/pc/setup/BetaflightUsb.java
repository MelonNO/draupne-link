package de.droiddrone.pc.setup;

import com.fazecast.jSerialComm.SerialPort;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A flight controller on this PC's USB: Betaflight's CLI (text commands) and MSP v1 (binary requests).
 * Used by the setup wizard so nobody needs Betaflight Configurator - which, when unplugged without disconnecting,
 * leaves the "MSP" arming block behind.
 */
public final class BetaflightUsb implements AutoCloseable {
    public record Port(String systemName, String description) {
        @Override
        public String toString() {
            return systemName + " - " + description;
        }
    }

    public static final int MSP_STATUS = 101;
    public static final int MSP_ACC_CALIBRATION = 205;
    private static final int STM32_VID = 0x0483;
    private static final int ARTERY_VID = 0x2E3C;   // AT32 flight controllers
    private static final int PIDCODES_VID = 0x1209;

    private final SerialPort port;

    private BetaflightUsb(SerialPort port) {
        this.port = port;
    }

    /** Serial ports that look like a flight controller's USB (virtual COM port of STM32 / AT32 chips). */
    public static List<Port> findFlightControllers() {
        List<Port> found = new ArrayList<>();
        for (SerialPort p : SerialPort.getCommPorts()) {
            int vid = p.getVendorID();
            String desc = (p.getPortDescription() + " " + p.getDescriptivePortName()).toLowerCase(Locale.ROOT);
            if (vid == STM32_VID || vid == ARTERY_VID || vid == PIDCODES_VID
                    || desc.contains("stm") || desc.contains("betaflight") || desc.contains("at32")) {
                found.add(new Port(p.getSystemPortName(), p.getDescriptivePortName()));
            }
        }
        return found;
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

    // ------------------------------------------------------------------ MSP v1

    /** Sends an MSP v1 request and returns the reply payload. */
    public byte[] msp(int command, byte[] payload) throws IOException {
        port.flushIOBuffers();
        write(Msp.request(command, payload));
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        byte[] buf = new byte[256];
        long end = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < end) {
            int n = port.readBytes(buf, buf.length);
            if (n < 0) throw new IOException("The flight controller disconnected");
            if (n > 0) {
                in.write(buf, 0, n);
                byte[] reply = Msp.parseReply(in.toByteArray(), command);
                if (reply != null) return reply;
            }
        }
        throw new IOException("No MSP reply to command " + command);
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

    /** MSP v1 framing: "$M<" size cmd payload checksum (XOR of size, cmd and payload). */
    static final class Msp {
        private Msp() {
        }

        static byte[] request(int command, byte[] payload) {
            byte[] p = payload == null ? new byte[0] : payload;
            byte[] frame = new byte[6 + p.length];
            frame[0] = '$';
            frame[1] = 'M';
            frame[2] = '<';
            frame[3] = (byte) p.length;
            frame[4] = (byte) command;
            int checksum = p.length ^ command;
            for (int i = 0; i < p.length; i++) {
                frame[5 + i] = p[i];
                checksum ^= p[i];
            }
            frame[5 + p.length] = (byte) checksum;
            return frame;
        }

        /** @return the payload of the first complete, valid "$M>" reply to command, or null if not there yet */
        static byte[] parseReply(byte[] data, int command) throws IOException {
            for (int i = 0; i + 5 < data.length; i++) {
                if (data[i] != '$' || data[i + 1] != 'M') continue;
                byte dir = data[i + 2];
                int size = data[i + 3] & 0xff;
                int cmd = data[i + 4] & 0xff;
                if (i + 6 + size > data.length) return null;
                if (cmd != (command & 0xff)) continue;
                if (dir == '!') throw new IOException("The flight controller rejected MSP command " + command);
                if (dir != '>') continue;
                int checksum = size ^ cmd;
                byte[] payload = new byte[size];
                for (int k = 0; k < size; k++) {
                    payload[k] = data[i + 5 + k];
                    checksum ^= payload[k];
                }
                if ((byte) checksum != data[i + 5 + size]) throw new IOException("Corrupted MSP reply");
                return payload;
            }
            return null;
        }
    }

    /**
     * Arming-disable flags from a raw (little-endian) Betaflight MSP_STATUS reply:
     * cycleTime u16, i2cErrors u16, sensors u16, modeFlags u32, pidProfile u8, load u16, unused u16,
     * extraModeFlagBytes u8 [+ bytes], armingFlagCount u8, armingFlags u32, ...
     */
    public static int armingFlags(byte[] status) {
        int i = 2 + 2 + 2 + 4 + 1 + 2 + 2;
        if (status.length <= i) return 0;
        int extra = status[i] & 0xff;
        i += 1 + extra + 1;
        if (status.length < i + 4) return 0;
        return (status[i] & 0xff) | (status[i + 1] & 0xff) << 8 | (status[i + 2] & 0xff) << 16 | (status[i + 3] & 0xff) << 24;
    }
}
