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
 * delete its copy. A mismatch starts over from nothing, twice at most.
 * <p>
 * 1.0.64: a window is asked for again only after {@link #RETRY_MS} without a chunk, and then only its gaps. It was
 * asked again 1.5 s after the ask whatever had come since, whole from the first gap: on a slow uplink (a window takes
 * longer than that) the phone sent every window twice or more.
 */
public final class ClipTransfer implements AutoCloseable {
    public static final int WINDOW = 64;
    /**
     * A window not complete, and nothing of it come for this long, is asked for again: from its first gap to its last
     * (1.0.64; the phone's serving takes a start and a count, so what lies between two gaps comes again with them).
     */
    public static final long RETRY_MS = 1500;
    static final int CHUNK = OverwatchProtocol.FRAGMENT;

    /** A clip that came whole but didn't check is fetched again at most this often (1.0.49: before, without end). */
    public static final int MAX_RETRIES = 2;

    public enum Result { OK, MISMATCH, FAILED }

    private final Path part, done;
    private final long size;
    private final byte[] sha256;
    private final TreeMap<Long, byte[]> pending = new TreeMap<>();
    private RandomAccessFile file;
    private long confirmed;
    private long askedEnd;
    private long askedAt = Long.MIN_VALUE / 2;
    /** 1.0.64: when the last chunk of use came, and the latest time this transfer was told. */
    private long progressAt = Long.MIN_VALUE / 2, clock = Long.MIN_VALUE / 2;
    private long mismatches;
    private boolean checked, failed;

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
        clock = Math.max(clock, now);
        if (paused || failed || complete()) return null;
        if (confirmed < askedEnd) {
            // the window asked for is still coming while its chunks come: a slow uplink is no loss
            if (now - Math.max(askedAt, progressAt) < RETRY_MS) return null;
            // nothing for a while: what is missing of it, from the first gap to the last
            long last = askedEnd - 1 - (askedEnd - 1) % CHUNK;
            while (last > confirmed && pending.containsKey(last)) last -= CHUNK;
            askedAt = now;
            return new long[]{confirmed, (int) ((last - confirmed) / CHUNK) + 1};
        }
        long end = Math.min(size, confirmed + (long) WINDOW * CHUNK);
        askedEnd = end;
        askedAt = now;
        int chunks = (int) ((end - confirmed + CHUNK - 1) / CHUNK);
        return new long[]{confirmed, chunks};
    }

    /** A CHUNK came. Out of the window, a repeat, or the wrong length: dropped. */
    public void onChunk(long offset, byte[] d, int from, int n) throws IOException {
        onChunk(offset, d, from, n, clock);
    }

    /** As above; now (1.0.64): when it came, for "nothing came for RETRY_MS". */
    public void onChunk(long offset, byte[] d, int from, int n, long now) throws IOException {
        clock = Math.max(clock, now);
        if (offset < confirmed || offset >= confirmed + (long) WINDOW * CHUNK || offset % CHUNK != 0) return;
        if (n != Math.min(CHUNK, size - offset) || pending.containsKey(offset)) return;
        progressAt = clock;
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

    /**
     * All in: the SHA-256 checked; OK renames the .part to its name, MISMATCH empties it to start over - at most
     * {@link #MAX_RETRIES} times (1.0.49), then FAILED: the .part is deleted and nothing more is asked. A rename that
     * fails (the file held open by a virus scanner, Explorer's preview) throws; the next call tries the rename again
     * without reading the whole clip again ({@link #checked}).
     */
    public Result finish() throws IOException {
        if (failed) return Result.FAILED;
        if (!complete()) throw new IllegalStateException("Not complete");
        if (!checked) {
            if (file != null) file.close();
            file = null;
            if (!Arrays.equals(sha256(part), sha256)) return mismatch();
            checked = true;
        }
        Files.move(part, done, StandardCopyOption.REPLACE_EXISTING);
        return Result.OK;
    }

    /** The whole file came and checked; only its rename may still be due (see {@link #finish}). */
    public boolean checked() {
        return checked;
    }

    /** Given up after {@link #MAX_RETRIES} mismatches: nothing more is asked. */
    public boolean failed() {
        return failed;
    }

    private Result mismatch() throws IOException {
        mismatches++;
        Files.deleteIfExists(part);
        if (mismatches > MAX_RETRIES) {
            failed = true;
            return Result.FAILED;
        }
        file = new RandomAccessFile(part.toFile(), "rw");
        confirmed = 0;
        askedEnd = 0;
        askedAt = Long.MIN_VALUE / 2;
        progressAt = Long.MIN_VALUE / 2;
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
