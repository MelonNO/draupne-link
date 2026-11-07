package de.droiddrone.pc.overwatch;

import de.droiddrone.common.OverwatchProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.TreeMap;

/**
 * Brings the phone's full-quality clip of an overwatch event to this PC (1.0.47): this side asks, the phone answers
 * (CMD_CLIP_GET {@code [offset][count]}, {@link #WINDOW} chunks of {@link OverwatchProtocol#FRAGMENT} bytes at a
 * time), so the pace and every pause are this PC's: nothing is asked while a drone on this PC is armed (its uplink
 * is the sticks' and the picture's). The bytes go to a .part file in order - only the part that has no gap is
 * written, so the file's length is always where to go on from, after a lost window, a pause or a restart of the
 * PC. When the last byte is in, the whole file's SHA-256 must be the phone's; then it gets its name and the phone may
 * delete its copy. A mismatch starts over from nothing.
 */
public final class ClipTransfer implements AutoCloseable {
    public static final int WINDOW = 64;
    /** A window not complete this long after it was asked for is asked for again from its first gap. */
    public static final long RETRY_MS = 1500;
    static final int CHUNK = OverwatchProtocol.FRAGMENT;

    public enum Result { OK, MISMATCH }

    private final Path part, done;
    private final long size;
    private final byte[] sha256;
    private final TreeMap<Long, byte[]> pending = new TreeMap<>();
    private RandomAccessFile file;
    private long confirmed;
    private long askedEnd;
    private long askedAt = Long.MIN_VALUE / 2;
    private long mismatches;

    /** Goes on from a .part file already there (cut back to whole chunks). */
    public ClipTransfer(Path part, Path done, long size, byte[] sha256) throws IOException {
        this.part = part;
        this.done = done;
        this.size = size;
        this.sha256 = sha256.clone();
        Files.createDirectories(part.toAbsolutePath().getParent());
        file = new RandomAccessFile(part.toFile(), "rw");
        long have = file.length();
        if (have > size) have = 0; // another clip's leftovers
        confirmed = have - have % CHUNK;
        if (have == size) confirmed = size;
        file.setLength(confirmed);
    }

    /**
     * What to ask the phone for now: {offset, chunks}, or null - paused (a drone here is armed), complete, or the
     * window asked for is still coming.
     */
    public long[] next(long now, boolean paused) {
        if (paused || complete()) return null;
        if (confirmed < askedEnd && now - askedAt < RETRY_MS) return null;
        long end = Math.min(size, confirmed + (long) WINDOW * CHUNK);
        askedEnd = end;
        askedAt = now;
        int chunks = (int) ((end - confirmed + CHUNK - 1) / CHUNK);
        return new long[]{confirmed, chunks};
    }

    /** A CHUNK came. Out of the window, a repeat, or the wrong length: dropped. */
    public void onChunk(long offset, byte[] d, int from, int n) throws IOException {
        if (offset < confirmed || offset >= confirmed + (long) WINDOW * CHUNK || offset % CHUNK != 0) return;
        if (n != Math.min(CHUNK, size - offset) || pending.containsKey(offset)) return;
        pending.put(offset, Arrays.copyOfRange(d, from, from + n));
        byte[] next;
        while ((next = pending.remove(confirmed)) != null) {
            file.seek(confirmed);
            file.write(next);
            confirmed += next.length;
        }
        if (confirmed >= askedEnd) askedAt = Long.MIN_VALUE / 2; // the window is in: the next at once
    }

    public boolean complete() {
        return confirmed >= size;
    }

    /** Bytes in the .part file without a gap. */
    public long received() {
        return confirmed;
    }

    public long size() {
        return size;
    }

    /** Times the whole file came and didn't check (each started over). */
    public long mismatches() {
        return mismatches;
    }

    /** All in: the SHA-256 checked; OK renames the .part to its name, MISMATCH empties it to start over. */
    public Result finish() throws IOException {
        if (!complete()) throw new IllegalStateException("Not complete");
        file.close();
        file = null;
        if (Arrays.equals(sha256(part), sha256)) {
            Files.move(part, done, StandardCopyOption.REPLACE_EXISTING);
            return Result.OK;
        }
        mismatches++;
        Files.deleteIfExists(part);
        file = new RandomAccessFile(part.toFile(), "rw");
        confirmed = 0;
        askedEnd = 0;
        askedAt = Long.MIN_VALUE / 2;
        pending.clear();
        return Result.MISMATCH;
    }

    public static byte[] sha256(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            if (file != null) file.close();
        } catch (IOException ignored) {
        }
        file = null;
    }
}
