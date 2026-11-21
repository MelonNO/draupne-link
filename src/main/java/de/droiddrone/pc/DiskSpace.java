package de.droiddrone.pc;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * A disk that fills up, said before and when it happens (a review of 2026-11-20): every armed flight is recorded and
 * nothing is ever cleared away, so one day the disk is full - and then the recording stopped with one line in the
 * log, and the track, the pins and the map drawings stopped being saved without a word.
 */
public final class DiskSpace {
    /** Under this much free room the pilot is told at the start of a recording: a few minutes of video at most. */
    public static final long LOW_BYTES = 2_000_000_000L;

    private DiskSpace() {
    }

    /** What to tell the pilot about this much free room; null when there is enough (or it is not known: under 0). */
    public static String warning(long freeBytes) {
        if (freeBytes < 0 || freeBytes >= LOW_BYTES) return null;
        return String.format(Locale.ROOT, "LOW DISK: %.1f GB free. Recording and track may stop.", freeBytes / 1e9);
    }

    /** The warning for the disk this folder is on; null when there is room, or the disk does not say. */
    public static String warning(Path dir) {
        try {
            return warning(Files.getFileStore(dir).getUsableSpace());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Why a file could not be written, in a few words: Windows' own reason when it gives one. */
    public static String why(Exception e) {
        if (e instanceof FileSystemException f && f.getReason() != null && !f.getReason().isBlank()) return f.getReason().trim();
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m.trim());
    }
}
