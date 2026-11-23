package de.droiddrone.pc.setup.dfu;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Fleet PRD H1 / H4: writing a firmware image through the STM32's own USB bootloader (ST's DfuSe, as dfu-util and
 * Betaflight Configurator do it): out of any error state to dfuIDLE; every erasable sector of the internal flash
 * erased (Betaflight's settings go with it); the image written in wTransferSize pieces (address pointer, then the
 * piece as block 2); every piece read back and compared; then "leave": the address pointer at the flash's start and an
 * empty DNLOAD - the chip restarts into what was written. Each request waits out the device's poll time and checks
 * its status. Nothing here chooses the device: it is handed the one the pilot picked.
 */
public final class StmDfu {
    static final int STATE_IDLE = 2, STATE_DNBUSY = 4, STATE_DNLOAD_IDLE = 5, STATE_MANIFEST = 7, STATE_ERROR = 10;
    static final int CMD_SET_ADDRESS = 0x21, CMD_ERASE = 0x41;
    /** ArduPilot needs 1 MB of flash or more (PRD AS-1). */
    public static final long MIN_FLASH = 1024 * 1024;
    /** One request's longest wait (a 128 KB sector's erase takes about 1-2 s). */
    static final long OP_TIMEOUT_MS = 10_000;

    /** How the waits are slept (the tests' made-up chip answers at once). */
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private final DfuDevice dev;
    private final Sleeper sleeper;

    public StmDfu(DfuDevice dev) {
        this(dev, Thread::sleep);
    }

    public StmDfu(DfuDevice dev, Sleeper sleeper) {
        this.dev = dev;
        this.sleeper = sleeper;
    }

    /** GETSTATUS: bStatus, bwPollTimeout (ms), bState. */
    public record Status(int status, int pollMs, int state) {}

    Status status() throws IOException {
        byte[] s = dev.in(DfuDevice.GETSTATUS, 0, 6);
        if (s == null || s.length < 6) throw new IOException("the bootloader's status is cut short");
        return new Status(s[0] & 0xff, (s[1] & 0xff) | (s[2] & 0xff) << 8 | (s[3] & 0xff) << 16, s[4] & 0xff);
    }

    /** Out of an error or a half-done request, to dfuIDLE. */
    void toIdle() throws IOException {
        Status s = status();
        if (s.state() == STATE_ERROR || s.status() != 0) {
            dev.out(DfuDevice.CLRSTATUS, 0, new byte[0]);
            s = status();
        }
        if (s.state() != STATE_IDLE) {
            dev.out(DfuDevice.ABORT, 0, new byte[0]);
            s = status();
        }
        if (s.state() != STATE_IDLE) throw new IOException("the bootloader won't go idle (state " + s.state() + ")");
    }

    /** A DNLOAD and its wait: busy, then DNLOAD-IDLE with no error. */
    private void download(int block, byte[] data, String what) throws IOException, InterruptedException {
        dev.out(DfuDevice.DNLOAD, block, data);
        long end = System.currentTimeMillis() + OP_TIMEOUT_MS;
        while (true) {
            Status s = status();
            if (s.status() != 0) throw new IOException(what + ": the bootloader says error " + s.status());
            if (s.state() == STATE_DNLOAD_IDLE) return;
            if (s.state() == STATE_ERROR) throw new IOException(what + ": the bootloader says error");
            if (System.currentTimeMillis() > end) throw new IOException(what + ": no answer in time");
            sleeper.sleep(Math.max(1, Math.min(5000, s.pollMs())));
        }
    }

    private static byte[] command(int cmd, long address) {
        return new byte[]{(byte) cmd, (byte) address, (byte) (address >>> 8), (byte) (address >>> 16), (byte) (address >>> 24)};
    }

    void setAddress(long a) throws IOException, InterruptedException {
        download(0, command(CMD_SET_ADDRESS, a), "address 0x" + Long.toHexString(a));
    }

    void erase(long a) throws IOException, InterruptedException {
        download(0, command(CMD_ERASE, a), "erase 0x" + Long.toHexString(a));
    }

    /**
     * The whole write: checks the flash (1 MB or more, the image inside its writable sectors), erases it, writes,
     * reads back, and only when every byte matches lets the chip restart. Throws with the reason; a board stopped
     * half way stays in its bootloader and can be written again.
     */
    public void flash(HexImage img, Consumer<String> progress) throws IOException, InterruptedException {
        DfuLayout layout = DfuLayout.parse(dev.layout());
        if (layout.total() < MIN_FLASH) {
            throw new IOException("this board has " + layout.total() / 1024 + " KB of flash: ArduPilot needs 1 MB or more");
        }
        for (HexImage.Segment s : img.segments()) {
            if (!layout.writable(s.address(), s.end())) {
                throw new IOException("the image doesn't fit this board's flash (0x" + Long.toHexString(s.address()) + ")");
            }
        }
        int chunk = Math.max(64, dev.transferSize());
        toIdle();
        int erasable = 0;
        for (DfuLayout.Sector x : layout.sectors()) if (x.can(DfuLayout.ERASABLE)) erasable++;
        int done = 0, lastPct = -1;
        progress.accept("Erasing ...");
        for (DfuLayout.Sector x : layout.sectors()) {
            if (!x.can(DfuLayout.ERASABLE)) continue;
            erase(x.address());
            int pct = ++done * 100 / erasable;
            if (pct / 25 != lastPct / 25) progress.accept("Erasing " + pct + " %");
            lastPct = pct;
        }
        int total = img.bytes(), written = 0;
        lastPct = -1;
        for (HexImage.Segment s : img.segments()) {
            for (int at = 0; at < s.data().length; at += chunk) {
                byte[] piece = Arrays.copyOfRange(s.data(), at, Math.min(s.data().length, at + chunk));
                setAddress(s.address() + at);
                download(2, piece, "write 0x" + Long.toHexString(s.address() + at));
                written += piece.length;
                int pct = (int) (written * 100L / total);
                if (pct / 10 != lastPct / 10) progress.accept("Writing " + pct + " %");
                lastPct = pct;
            }
        }
        progress.accept("Checking ...");
        for (HexImage.Segment s : img.segments()) {
            for (int at = 0; at < s.data().length; at += chunk) {
                int n = Math.min(chunk, s.data().length - at);
                setAddress(s.address() + at);
                dev.out(DfuDevice.ABORT, 0, new byte[0]); // to dfuIDLE: an UPLOAD starts from there
                byte[] back = dev.in(DfuDevice.UPLOAD, 2, n);
                if (back == null || back.length != n || !Arrays.equals(back, 0, n, s.data(), at, at + n)) {
                    throw new IOException("read back differs at 0x" + Long.toHexString(s.address() + at) + ": write it again");
                }
            }
        }
        dev.out(DfuDevice.ABORT, 0, new byte[0]);
        leave(img.first());
        progress.accept("Done: the board restarts with the new firmware.");
    }

    /** The chip restarts from this address: the pointer set, then an empty DNLOAD and its status (manifestation). */
    void leave(long start) throws IOException, InterruptedException {
        toIdle();
        setAddress(start);
        dev.out(DfuDevice.DNLOAD, 0, new byte[0]);
        try {
            status(); // starts the restart; the device may be gone before it answers
        } catch (IOException ignored) {
            // gone: restarted
        }
    }
}
