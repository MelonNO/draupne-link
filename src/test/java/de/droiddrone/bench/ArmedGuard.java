package de.droiddrone.bench;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The pilot's rule of 2026-11-19: the bench may use this PC - mouse, screen, processor - as long as no real drone is
 * armed. The DRAUPNE Link the pilot flies with writes a row a second to its link log
 * (%APPDATA%\DraupneLink\logs\link-*.csv, column "armed") while a drone is connected; this reads the newest one's
 * tail. Reading only, and the file is never held: the bench does not write to, lock or delete anything of the real app.
 */
public final class ArmedGuard {
    public enum State {
        /** No DRAUPNE Link on this PC has a drone connected. */
        NO_LINK,
        /** A real drone is connected and its flight controller says disarmed (or has never said anything). */
        LINK_DISARMED,
        /** A real drone is armed - or was when its flight controller was last heard. Hands off. */
        ARMED
    }

    /** A link log not written for this long belongs to a link that is closed (the app writes a row a second). */
    static final long LIVE_MS = 5000;
    /** How much of the file's end is read: a row is ~400 bytes, so this is some minutes. */
    static final int TAIL_BYTES = 128 * 1024;

    private ArmedGuard() {
    }

    /** The real app's state now; NO_LINK when the bench was not told where the real logs are. */
    public static State now() {
        Path real = Sandbox.realAppData();
        if (real == null) return State.NO_LINK;
        return read(real.resolve("DraupneLink").resolve("logs"), System.currentTimeMillis());
    }

    public static boolean handsOff() {
        return now() == State.ARMED;
    }

    static State read(Path logsDir, long nowMs) {
        Path newest;
        try (Stream<Path> files = Files.list(logsDir)) {
            newest = files.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("link-") && n.endsWith(".csv");
            }).max(Comparator.comparingLong(ArmedGuard::modified)).orElse(null);
        } catch (IOException | RuntimeException e) {
            return State.NO_LINK;
        }
        if (newest == null || nowMs - modified(newest) > LIVE_MS) return State.NO_LINK;
        try {
            return armedInTail(header(newest), tail(newest)) ? State.ARMED : State.LINK_DISARMED;
        } catch (IOException | RuntimeException e) {
            return State.ARMED; // a live link whose log can't be read: the careful answer
        }
    }

    /**
     * The last row in which the flight controller said anything decides: armed there = armed now, whatever the rows
     * after it (empty = not heard) say. No row with a word from it at all = never armed on this link.
     */
    static boolean armedInTail(String header, List<String> rows) {
        int column = Arrays.asList(header.split(",", -1)).indexOf("armed");
        if (column < 0) return true; // a log this bench can't read: careful
        for (int i = rows.size() - 1; i >= 0; i--) {
            String[] cells = rows.get(i).split(",", -1);
            if (cells.length <= column) continue; // a row being written, or the cut first row of the tail
            String armed = cells[column].trim();
            if (armed.isEmpty()) continue;
            return !armed.equals("0") && !armed.equalsIgnoreCase("false");
        }
        return false;
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String header(Path file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            byte[] b = new byte[(int) Math.min(f.length(), 8192)];
            f.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8);
            int nl = s.indexOf('\n');
            return (nl < 0 ? s : s.substring(0, nl)).trim();
        }
    }

    private static List<String> tail(Path file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            long len = f.length();
            long from = Math.max(0, len - TAIL_BYTES);
            f.seek(from);
            byte[] b = new byte[(int) (len - from)];
            f.readFully(b);
            List<String> rows = new java.util.ArrayList<>(List.of(new String(b, StandardCharsets.UTF_8).split("\r?\n")));
            if (!rows.isEmpty()) rows.remove(0); // the header, or a row cut in half
            return rows;
        }
    }
}
