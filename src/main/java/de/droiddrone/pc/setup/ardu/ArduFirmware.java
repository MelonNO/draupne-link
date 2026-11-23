package de.droiddrone.pc.setup.ardu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Fleet PRD AS-1 / H1 (in part): ArduPilot firmware onto a board that already runs ArduPilot - by its own bootloader
 * over USB, as ArduPilot's uploader does: sync, the board's id and flash size (an .apj for another board is refused),
 * erase, program in 252-byte blocks, the board's CRC32 over the image (padded with 0xFF to the flash size) against
 * ours, reboot. The firmware is an .apj (JSON: board_id, image = base64 of the zlib-compressed binary). Getting there:
 * MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN with param1 3 makes ArduPilot restart into its bootloader (a new serial port).
 * Not built: Betaflight to ArduPilot - that board's bootloader is the STM32's DFU, which needs a USB driver this app
 * doesn't install; flash it once with ArduPilot's own tools.
 */
public final class ArduFirmware {
    static final int INSYNC = 0x12, EOC = 0x20, OK = 0x10, FAILED = 0x11, INVALID = 0x13;
    static final int GET_SYNC = 0x21, GET_DEVICE = 0x22, CHIP_ERASE = 0x23, PROG_MULTI = 0x27, GET_CRC = 0x29, REBOOT = 0x30;
    static final int INFO_BL_REV = 1, INFO_BOARD_ID = 2, INFO_BOARD_REV = 3, INFO_FLASH_SIZE = 4;
    static final int PROG_MAX = 252;
    static final long ERASE_TIMEOUT_MS = 30_000, REPLY_TIMEOUT_MS = 2000;

    /** The bytes to the bootloader and back (a serial port, or a test's made-up bootloader). */
    public interface Wire {
        void write(byte[] data) throws IOException;

        /** One byte, or -1 when none came within timeoutMs. */
        int read(int timeoutMs) throws IOException;
    }

    /** An .apj: its board id and the binary image (a multiple of 4 bytes, 0xFF padded). */
    public record Apj(int boardId, byte[] image, String summary) {}

    private ArduFirmware() {
    }

    public static Apj parseApj(byte[] json) throws IOException {
        try {
            JsonObject o = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            int boardId = o.get("board_id").getAsInt();
            byte[] z = Base64.getDecoder().decode(o.get("image").getAsString().replaceAll("\\s", ""));
            Inflater inf = new Inflater();
            inf.setInput(z);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(buf, 0, n);
            }
            inf.end();
            byte[] img = out.toByteArray();
            int pad = (4 - img.length % 4) % 4;
            byte[] image = java.util.Arrays.copyOf(img, img.length + pad);
            for (int i = img.length; i < image.length; i++) image[i] = (byte) 0xff;
            if (o.has("image_size") && o.get("image_size").getAsInt() != img.length) throw new IOException("image size doesn't match");
            String summary = o.has("summary") ? o.get("summary").getAsString() : "board " + boardId;
            return new Apj(boardId, image, summary);
        } catch (DataFormatException | RuntimeException e) {
            throw new IOException("not an ArduPilot firmware file (.apj): " + e.getMessage());
        }
    }

    /** The bootloader's CRC: px4's CRC32 (the zlib table, state 0, no final xor). */
    static int crc32(byte[] b, int len, int state) {
        for (int i = 0; i < len; i++) {
            int index = (state ^ b[i]) & 0xff;
            state = TABLE[index] ^ (state >>> 8);
        }
        return state;
    }

    /** The CRC the bootloader works out: the image, then 0xFF up to the flash size. */
    static int expectedCrc(byte[] image, int flashSize) {
        int s = crc32(image, image.length, 0);
        byte[] ff = {(byte) 0xff};
        for (int i = image.length; i < flashSize; i++) s = crc32(ff, 1, s);
        return s;
    }

    /**
     * The whole upload. progress gets lines ("Erasing ...", "Programming 40 %" ...). Throws with the reason; the board
     * stays in its bootloader then and can be written again.
     */
    public static void upload(Wire w, Apj fw, Consumer<String> progress) throws IOException {
        sync(w);
        int board = device(w, INFO_BOARD_ID);
        int flash = device(w, INFO_FLASH_SIZE);
        if (board != fw.boardId()) throw new IOException("this firmware is for board " + fw.boardId() + ", the board is " + board);
        if (fw.image().length > flash) throw new IOException("the firmware (" + fw.image().length + " bytes) doesn't fit (" + flash + ")");
        progress.accept("Erasing ...");
        w.write(new byte[]{(byte) CHIP_ERASE, (byte) EOC});
        reply(w, ERASE_TIMEOUT_MS);
        byte[] img = fw.image();
        int lastPct = -1;
        for (int at = 0; at < img.length; at += PROG_MAX) {
            int n = Math.min(PROG_MAX, img.length - at);
            byte[] p = new byte[n + 3];
            p[0] = (byte) PROG_MULTI;
            p[1] = (byte) n;
            System.arraycopy(img, at, p, 2, n);
            p[n + 2] = (byte) EOC;
            w.write(p);
            reply(w, REPLY_TIMEOUT_MS);
            int pct = (int) ((at + n) * 100L / img.length);
            if (pct / 10 != lastPct / 10) progress.accept("Programming " + pct + " %");
            lastPct = pct;
        }
        progress.accept("Checking ...");
        w.write(new byte[]{(byte) GET_CRC, (byte) EOC});
        int crc = int32(w, REPLY_TIMEOUT_MS);
        reply(w, REPLY_TIMEOUT_MS);
        int want = expectedCrc(img, flash);
        if (crc != want) throw new IOException(String.format("the board's check doesn't match (%08x, wanted %08x): write it again", crc, want));
        w.write(new byte[]{(byte) REBOOT, (byte) EOC});
        progress.accept("Done: the board restarts with " + fw.summary() + ".");
    }

    static void sync(Wire w) throws IOException {
        for (int i = 0; i < 3; i++) {
            while (w.read(50) >= 0) {
                // what was waiting
            }
            w.write(new byte[]{(byte) GET_SYNC, (byte) EOC});
            try {
                reply(w, 500);
                return;
            } catch (IOException e) {
                // again
            }
        }
        throw new IOException("no ArduPilot bootloader answers");
    }

    static int device(Wire w, int what) throws IOException {
        w.write(new byte[]{(byte) GET_DEVICE, (byte) what, (byte) EOC});
        int v = int32(w, REPLY_TIMEOUT_MS);
        reply(w, REPLY_TIMEOUT_MS);
        return v;
    }

    private static int int32(Wire w, long timeoutMs) throws IOException {
        int v = 0;
        for (int i = 0; i < 4; i++) {
            int b = w.read((int) timeoutMs);
            if (b < 0) throw new IOException("the bootloader stopped answering");
            v |= (b & 0xff) << (8 * i);
        }
        return v;
    }

    private static void reply(Wire w, long timeoutMs) throws IOException {
        int a = w.read((int) timeoutMs);
        if (a != INSYNC) throw new IOException(a < 0 ? "the bootloader didn't answer" : "the bootloader is out of step");
        int b = w.read((int) REPLY_TIMEOUT_MS);
        if (b == INVALID) throw new IOException("the bootloader refused the command");
        if (b == FAILED) throw new IOException("the bootloader failed the command");
        if (b != OK) throw new IOException("the bootloader's answer: " + b);
    }

    private static final int[] TABLE = new int[256];

    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
            TABLE[n] = c;
        }
    }
}
