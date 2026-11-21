package de.droiddrone.pc.setup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * The ESCs, reached through the flight controller (1.0.71): Betaflight's ESC passthrough, the "4-way interface" that
 * BLHeliSuite and esc-configurator use. After MSP_SET_PASSTHROUGH the flight controller stops its motor outputs,
 * holds the ESC lines high - which sends each ESC into its bootloader - and the USB port speaks this protocol until
 * {@link #close} (InterfaceExit) gives it back to MSP. The battery must be on (an ESC without power doesn't answer)
 * and the props off.
 * <p>
 * A frame from the PC: {@code '/' command addressHi addressLo count(0 = 256) parameters crcHi crcLo}; the answer:
 * {@code '.' command addressHi addressLo count parameters ack crcHi crcLo}; CRC-16/XMODEM over everything before it
 * (Betaflight's serial_4way.c). Never an empty parameter list: a count of 0 means 256.
 * <p>
 * What DRAUPNE does here: read which firmware each ESC runs and its beacon delay ({@link #info}), and switch that
 * beacon off ({@link #silence}) - one byte of the ESC's settings, written the way esc-configurator writes settings
 * (erase the settings' page, write, read back), with everything that was on the page read first, kept in a file,
 * compared after, and put back if the write does not read back right.
 */
public final class EscLink implements AutoCloseable {
    static final int MSP_SET_PASSTHROUGH = 245;
    static final int FROM_PC = 0x2F, FROM_INTERFACE = 0x2E;
    static final int TEST_ALIVE = 0x30, EXIT = 0x34, RESET = 0x35, INIT_FLASH = 0x37, PAGE_ERASE = 0x39, READ = 0x3A, WRITE = 0x3B;
    static final int ACK_OK = 0, ACK_INVALID_CRC = 3, ACK_GENERAL_ERROR = 0x0F;
    /** The interface's modes: how it talks to the ESC it found (answer to INIT_FLASH, 4th byte). */
    public static final int MODE_SILABS_C2 = 0, MODE_SILABS = 1, MODE_ATMEL = 2, MODE_SIMONK = 3, MODE_ARM = 4;
    /** Betaflight erases by 512-byte steps whatever the chip's page (its address is page * 512). */
    static final int ERASE_UNIT = 512;
    /** The most read or written in one frame here (the protocol's limit is 256). */
    static final int CHUNK = 0x80;

    /** What an ESC's bootloader says it is. */
    public record Device(int signature, int mode) {
    }

    /** The interface (or the ESC behind it) said no. */
    public static final class Refused extends IOException {
        public final int ack;

        Refused(String what, int ack) {
            super(what + ": " + ackText(ack));
            this.ack = ack;
        }
    }

    private final BetaflightUsb.Wire wire;
    private final int count;
    private final Consumer<String> log;
    private final byte[] in = new byte[512];
    private int inAt, inLen;
    private boolean closed;
    /** Between tries of a command, ms. Tests: 0. */
    static long RETRY_PAUSE_MS = 250;
    long retryPauseMs = RETRY_PAUSE_MS;

    EscLink(BetaflightUsb.Wire wire, int count, Consumer<String> log) {
        this.wire = wire;
        this.count = count;
        this.log = log == null ? s -> {
        } : log;
    }

    /** The port is back with MSP. */
    public boolean closed() {
        return closed;
    }

    /** How many motor outputs the flight controller has (not: how many ESCs answer). */
    public int count() {
        return count;
    }

    // ------------------------------------------------------------------ what DRAUPNE does with it

    /** Every ESC's firmware and beacon; one that doesn't answer is in the list with its problem. */
    public List<EscFirmware.Info> infoAll() {
        List<EscFirmware.Info> all = new ArrayList<>();
        for (int i = 0; i < count; i++) all.add(null);
        // twice: an ESC needs a moment to fall into its bootloader once its line is held high (on the first real
        // drone tried, AM32, the first ESC asked was not there yet after 1.5 s; the next ones were)
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < count; i++) {
                if (all.get(i) != null && all.get(i).problem() == null) continue;
                try {
                    all.set(i, info(i));
                } catch (IOException e) {
                    all.set(i, EscFirmware.Info.failed(i, "no answer (battery on?)"));
                    if (pass == 1) log.accept("ESC " + (i + 1) + ": " + e.getMessage());
                }
            }
        }
        return all;
    }

    /** Which firmware ESC esc (0 = motor 1) runs, and its beacon delay. Reads only. */
    public EscFirmware.Info info(int esc) throws IOException {
        Device d = init(esc);
        EscFirmware.Chip chip = EscFirmware.chip(d.signature(), d.mode());
        if (chip == null) return EscFirmware.Info.failed(esc, String.format("ESC chip not known (%04X, mode %d)", d.signature(), d.mode()));
        if (!chip.silabs()) return EscFirmware.arm(esc, chip, readBytes(chip.settingsAt(), EscFirmware.AM32_SETTINGS));
        byte[] settings = readBytes(chip.settingsAt(), EscFirmware.SILABS_SETTINGS);
        return EscFirmware.silabs(esc, chip, settings, jesc(settings));
    }

    /** JESC says its name in the firmware itself, and only a nameless BLHeli_S is asked. */
    private byte[] jesc(byte[] settings) throws IOException {
        return EscFirmware.text(settings, 0x60, 16).isEmpty() ? readBytes(0x80, 0x80) : null;
    }

    /**
     * Switches ESC esc's own beacon off (Beacon Delay = Infinite), if it is a BLHeli_S kind with the beacon on;
     * anything else is left alone and returned as read. The page with the settings is read twice (and must read the
     * same), kept in backupDir (may be null), erased, written with that one byte changed and read back; up to three
     * tries, then what was there is put back. Throws when the ESC is left with settings that are not the old or the
     * new ones - the message says so.
     * @return the ESC as it is afterwards
     */
    public EscFirmware.Info silence(int esc, Path backupDir) throws IOException {
        Device d = init(esc);
        EscFirmware.Chip chip = EscFirmware.chip(d.signature(), d.mode());
        if (chip == null || !chip.silabs()) return info(esc);
        String name = "ESC " + (esc + 1);
        byte[] page = readBytes(chip.settingsAt(), chip.pageSize());
        byte[] jesc = jesc(page);
        byte[] wanted = EscFirmware.beaconOff(chip, page);
        if (wanted == null) return EscFirmware.silabs(esc, chip, page, jesc);
        if (!Arrays.equals(page, readBytes(chip.settingsAt(), chip.pageSize()))) {
            throw new IOException(name + ": its settings read differently twice - nothing changed.");
        }
        Path backup = backup(backupDir, esc, chip, page);
        log.accept(name + ": beacon " + EscFirmware.silabs(esc, chip, page, jesc).beaconText() + " -> off"
                + (backup == null ? "" : " (settings kept in " + backup.getFileName() + ")"));
        IOException last = null;
        for (int round = 0; round < 3; round++) {
            try {
                if (round > 0) init(esc);
                if (program(chip, wanted)) return EscFirmware.silabs(esc, chip, wanted, jesc);
                last = new IOException("the settings read back differently");
            } catch (IOException e) {
                last = e;
            }
            log.accept(name + ": try " + (round + 1) + " failed - " + last.getMessage());
        }
        // not written: the page back as it was
        String why = last.getMessage();
        int direction = page[EscFirmware.MOTOR_DIRECTION] & 0xff;
        boolean dirReversed = direction == 2 || direction == 4;
        boolean restored = false;
        for (int round = 0; round < 3 && !restored; round++) {
            try {
                init(esc);
                restored = program(chip, page);
            } catch (IOException e) {
                last = e;
            }
        }
        if (restored) throw new IOException(name + ": the beacon could not be changed (" + why + "). Its settings are as before.");
        throw new IOException(name + ": SETTINGS MAY BE LOST (" + last.getMessage() + "). The ESC then starts with its defaults: "
                + "check the motor's direction" + (dirReversed ? " (it was REVERSED)" : "") + " in the motor test before flying."
                + (backup == null ? "" : " The old settings are in " + backup + "."));
    }

    /** Erase the settings' page, write these bytes, read them back: true when the page reads exactly as wanted. */
    private boolean program(EscFirmware.Chip chip, byte[] wanted) throws IOException {
        command(PAGE_ERASE, 0, new byte[]{(byte) (chip.settingsAt() / ERASE_UNIT)}, 4000, 1);
        int end = wanted.length; // the erased rest of the page needs no write
        while (end > 0 && wanted[end - 1] == (byte) 0xFF) end--;
        for (int at = 0; at < end; at += CHUNK) {
            byte[] part = Arrays.copyOfRange(wanted, at, Math.min(end, at + CHUNK));
            command(WRITE, chip.settingsAt() + at, part, 4000, 1);
        }
        return Arrays.equals(wanted, readBytes(chip.settingsAt(), wanted.length));
    }

    private Path backup(Path dir, int esc, EscFirmware.Chip chip, byte[] page) {
        if (dir == null) return null;
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("esc" + (esc + 1) + "-" + chip.name() + "-" + String.format("%04X", chip.settingsAt()) + "-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".bin");
            Files.write(file, page);
            return file;
        } catch (IOException e) {
            log.accept("ESC " + (esc + 1) + ": its settings could not be kept in a file (" + e.getMessage() + ")");
            return null;
        }
    }

    // ------------------------------------------------------------------ the interface's commands

    /** Connects the interface to ESC esc's bootloader; an ESC just sent there may need a few tries. */
    public Device init(int esc) throws IOException {
        byte[] p = command(INIT_FLASH, 0, new byte[]{(byte) esc}, 3000, 6);
        if (p.length < 4) throw new IOException("ESC " + (esc + 1) + ": short answer from the interface");
        return new Device((p[1] & 0xff) << 8 | p[0] & 0xff, p[3] & 0xff);
    }

    /** len bytes of the connected ESC's memory from address. */
    public byte[] readBytes(int address, int len) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int at = 0; at < len; at += CHUNK) {
            int n = Math.min(CHUNK, len - at);
            byte[] p = command(READ, address + at, new byte[]{(byte) n}, 3000, 4);
            if (p.length != n) throw new IOException("ESC read: " + p.length + " bytes for " + n);
            out.write(p, 0, n);
        }
        return out.toByteArray();
    }

    /** Lets every ESC start its firmware again and gives the port back to MSP. Never throws. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (int i = 0; i < count; i++) {
            try {
                command(RESET, 0, new byte[]{(byte) i}, 1500, 1);
            } catch (IOException e) {
                // an ESC that never answered has nothing to restart
            }
        }
        try {
            command(EXIT, 0, new byte[]{0}, 1500, 2);
        } catch (IOException e) {
            log.accept("ESC passthrough: no answer to its end (" + e.getMessage() + ") - unplug the flight controller's USB and battery, then plug in again.");
        }
    }

    /**
     * One command and its answer's parameters, tried up to tries times (a CRC error on the line, an ESC not yet in
     * its bootloader). A write or an erase is never repeated blindly: tries 1.
     */
    byte[] command(int cmd, int address, byte[] params, long timeoutMs, int tries) throws IOException {
        IOException last = null;
        for (int t = 0; t < tries; t++) {
            if (t > 0) pause(retryPauseMs);
            try {
                drain();
                wire.write(frame(FROM_PC, cmd, address, params, -1));
                Reply r = reply(cmd, timeoutMs);
                if (r.ack == ACK_OK) return r.params;
                last = new Refused(name(cmd), r.ack);
            } catch (java.io.InterruptedIOException e) {
                last = e;
            }
        }
        throw last;
    }

    private record Reply(byte[] params, int ack) {
    }

    private Reply reply(int cmd, long timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) { // to the answer's first byte
            int b = next(end);
            if (b == FROM_INTERFACE) break;
        }
        int crc = crc(0, FROM_INTERFACE);
        int c = next(end), hi = next(end), lo = next(end), n = next(end);
        crc = crc(crc(crc(crc(crc, c), hi), lo), n);
        if (n == 0) n = 256;
        byte[] params = new byte[n];
        for (int i = 0; i < n; i++) {
            params[i] = (byte) next(end);
            crc = crc(crc, params[i] & 0xff);
        }
        int ack = next(end);
        crc = crc(crc, ack);
        int got = next(end) << 8 | next(end);
        if (got != crc) throw new java.io.InterruptedIOException(name(cmd) + ": the answer's checksum is wrong");
        if (c != cmd) throw new java.io.InterruptedIOException(name(cmd) + ": an answer to something else");
        return new Reply(params, ack);
    }

    private int next(long end) throws IOException {
        while (inAt >= inLen) {
            if (System.currentTimeMillis() > end) throw new java.io.InterruptedIOException("no answer from the ESC interface");
            int n = wire.read(in);
            if (n < 0) throw new IOException("The flight controller disconnected");
            inAt = 0;
            inLen = n;
        }
        return in[inAt++] & 0xff;
    }

    private void drain() throws IOException {
        inAt = inLen = 0;
        while (wire.available() > 0) if (wire.read(in) < 0) break;
    }

    /** A frame of the protocol; ack >= 0 makes it an answer (the interface's side, for the tests' made-up ESCs). */
    static byte[] frame(int from, int cmd, int address, byte[] params, int ack) {
        if (params == null || params.length == 0 || params.length > 256) throw new IllegalArgumentException("1 - 256 parameters");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(from);
        out.write(cmd);
        out.write(address >> 8 & 0xff);
        out.write(address & 0xff);
        out.write(params.length & 0xff);
        out.write(params, 0, params.length);
        if (ack >= 0) out.write(ack);
        int crc = 0;
        for (byte b : out.toByteArray()) crc = crc(crc, b & 0xff);
        out.write(crc >> 8);
        out.write(crc & 0xff);
        return out.toByteArray();
    }

    /** CRC-16/XMODEM: polynomial 0x1021, from 0, the high bit first. */
    static int crc(int crc, int data) {
        crc ^= data << 8;
        for (int i = 0; i < 8; i++) crc = (crc & 0x8000) != 0 ? crc << 1 ^ 0x1021 : crc << 1;
        return crc & 0xffff;
    }

    private static void pause(long ms) throws IOException {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("interrupted");
        }
    }

    private static String name(int cmd) {
        return switch (cmd) {
            case INIT_FLASH -> "ESC connect";
            case READ -> "ESC read";
            case WRITE -> "ESC write";
            case PAGE_ERASE -> "ESC erase";
            case RESET -> "ESC restart";
            case EXIT -> "ESC passthrough end";
            default -> "ESC command " + Integer.toHexString(cmd);
        };
    }

    static String ackText(int ack) {
        return switch (ack) {
            case 2 -> "the interface doesn't know the command";
            case ACK_INVALID_CRC -> "checksum error on the line";
            case 4 -> "the ESC's memory did not verify";
            case 8 -> "no such ESC";
            case 9 -> "wrong parameter";
            case ACK_GENERAL_ERROR -> "the ESC did not answer";
            default -> "error " + ack;
        };
    }
}
