package de.droiddrone.pc.flights;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * One connection's flight, on disk from the moment of connecting (1.0.71; the pilot, 2026-11-18: "always save a log,
 * I had a flight where the drone was lost due to loss of link and we had no log saved" - its path was on the screen
 * and gone when the PC restarted for an update; "always log the phone name to the flight"; "don't log flights where
 * the drone was not armed").
 * A small text file beside the tracks, {@code flight-DATE.record}: lines of {@code key=value}, each written (and
 * flushed) when it is known - the drone's name and address, the files that are this flight's (its track, every part
 * of its recording, its link log), when it was first and last seen armed, and at the end how it ended. A record
 * without an end line is a flight the app never got to close: the PC was restarted, the app killed.
 * PREVIOUS FLIGHTS puts a flight together from its record ({@link FlightLibrary}); files from before 1.0.71 have none
 * and are found by their times, as before.
 */
public final class FlightRecord {
    public static final String PREFIX = "flight-", SUFFIX = ".record";
    /** How a flight ended. No end line at all: {@link #APP_CLOSED}. */
    public static final String DISCONNECT = "DISCONNECT", LOST_DRONE = "LOST DRONE", LINK_LOST = "LINK LOST", APP_CLOSED = "APP CLOSED";
    /** Not an end: the connection is open now. */
    public static final String FLYING = "NOW";
    private static volatile Path live;
    /** While armed, "last seen armed" is written this often. */
    static final long ARMED_EVERY_MS = 5_000;
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "recordWriter"); // (not "flight...": the tests wait for the window's flight threads to end)
        t.setDaemon(true);
        return t;
    });

    private final Path file;
    private String drone = "", address = "", app = "";
    private long startMs, armedFirstMs, armedLastMs, endMs;
    private String ended;
    private Path track, linkLog;
    private final List<Path> videos = new ArrayList<>();
    /** Held open and locked from the start to the end by the app that has this connection open (see {@link #heldElsewhere}). */
    private java.nio.channels.FileChannel hold;

    private FlightRecord(Path file) {
        this.file = file;
    }

    /** The mark beside a record that its app holds while the connection is open: flight-DATE.record.open. */
    static Path openMark(Path record) {
        return record.resolveSibling(record.getFileName() + ".open");
    }

    private synchronized void hold() {
        try {
            java.nio.channels.FileChannel c = java.nio.channels.FileChannel.open(openMark(file), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            if (c.tryLock() == null) {
                c.close();
                return;
            }
            hold = c;
        } catch (IOException | RuntimeException e) {
            // no mark: this flight is then told from a closed app's by the one app's own memory alone, as before
        }
    }

    private synchronized void letGo(boolean markToo) {
        java.nio.channels.FileChannel c = hold;
        hold = null;
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
        if (!markToo) return;
        try {
            Files.deleteIfExists(openMark(file));
        } catch (IOException ignored) {
        }
    }

    /** As if the app had been killed here: the record stays without its end line, nobody holds it any more (the tests). */
    void abandon() {
        letGo(false);
    }

    /**
     * Another copy of the app, running now, has this connection open. Two copies on one PC were not thought of
     * until a review of 2026-11-20: the second one, starting, took the first one's flight in progress for a closed
     * app's - deleted its track and recording when the drone was not armed yet, wrote "APP CLOSED" into it when it
     * was. Windows lets go of the lock when its app ends, however it ends: a mark nobody holds is a closed app's.
     */
    public boolean heldElsewhere() {
        synchronized (this) {
            if (hold != null) return false; // this app's own
        }
        Path mark = openMark(file);
        if (!Files.exists(mark)) return false;
        try (java.nio.channels.FileChannel c = java.nio.channels.FileChannel.open(mark, StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock lock = c.tryLock();
            if (lock == null) return true;
            lock.release();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return true; // held in this very program, by the record that was started (not this copy read from disk)
        } catch (IOException | RuntimeException e) {
            return false;
        }
        try {
            Files.deleteIfExists(mark); // its app is gone: the mark with it
        } catch (IOException ignored) {
        }
        return false;
    }

    /**
     * A new connection's record, written at once.
     * @param drone   the phone's name, "" when it has none
     * @param address its address as connected to
     */
    public static FlightRecord start(Path dir, String drone, String address, String app, long nowMs) {
        String stamp = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), ZoneId.systemDefault()).format(FILE);
        Path file = dir.resolve(PREFIX + stamp + SUFFIX);
        for (int n = 2; Files.exists(file); n++) file = dir.resolve(PREFIX + stamp + "-" + n + SUFFIX);
        FlightRecord r = new FlightRecord(file);
        r.drone = clean(drone);
        r.address = clean(address);
        r.app = clean(app);
        r.startMs = nowMs;
        r.append("drone=" + r.drone, "address=" + r.address, "app=" + r.app, "start=" + nowMs);
        r.hold();
        return r;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\r', ' ').replace('\n', ' ').trim();
    }

    /** The drone's name learned later (a spectator hears which phone the pilot flies). */
    public synchronized void drone(String name) {
        String n = clean(name);
        if (n.isEmpty() || n.equals(drone)) return;
        drone = n;
        append("drone=" + n);
    }

    public synchronized void track(Path f) {
        if (f == null || f.equals(track)) return;
        track = f;
        append("track=" + f.toAbsolutePath());
    }

    public synchronized void linkLog(Path f) {
        if (f == null || f.equals(linkLog)) return;
        linkLog = f;
        append("linklog=" + f.toAbsolutePath());
    }

    /** One more part of the recording (a new file starts whenever the picture's size changes). */
    public synchronized void video(Path f) {
        if (f == null || videos.contains(f)) return;
        videos.add(f);
        append("video=" + f.toAbsolutePath());
    }

    /**
     * A part of the recording that got nothing on the disk (a full disk) and was deleted: no part of this flight any
     * more. Written as a line of its own - the record is only ever added to, so a full disk never cuts it short.
     */
    public synchronized void videoDropped(Path f) {
        if (f == null || !videos.remove(f)) return;
        append("novideo=" + f.toAbsolutePath());
    }

    /** The flight controller says armed now. The first time is written at once, later ones every {@link #ARMED_EVERY_MS}. */
    public synchronized void armed(long nowMs) {
        if (ended != null) return;
        if (armedFirstMs == 0) {
            armedFirstMs = nowMs;
            armedLastMs = nowMs;
            append("armed=" + nowMs);
        } else if (nowMs - armedLastMs >= ARMED_EVERY_MS) {
            armedLastMs = nowMs;
            append("armedlast=" + nowMs);
        }
    }

    /** The flight is over; written and on disk when this returns (it waits a moment for the writer). */
    public void end(String reason, long nowMs) {
        Future<?> written;
        synchronized (this) {
            if (ended != null) return;
            ended = reason;
            endMs = nowMs;
            written = append("end=" + nowMs + " " + reason);
        }
        try {
            written.get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // the record is a convenience; a flight without its end line reads as APP CLOSED
        }
        letGo(true);
    }

    private Future<?> append(String... lines) {
        StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(l).append('\n');
        byte[] bytes = b.toString().getBytes(StandardCharsets.UTF_8);
        return WRITER.submit(() -> {
            try {
                Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
            } catch (IOException ignored) {
                // a full or missing disk: the flight's other files say what they can
            }
        });
    }

    /** Reads a record file; null when it isn't one. */
    public static FlightRecord read(Path file) {
        String name = file.getFileName().toString();
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return null;
        FlightRecord r = new FlightRecord(file);
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int at = line.indexOf('=');
                if (at <= 0) continue;
                String key = line.substring(0, at), value = line.substring(at + 1).trim();
                switch (key) {
                    case "drone" -> r.drone = value;
                    case "address" -> r.address = value;
                    case "app" -> r.app = value;
                    case "start" -> r.startMs = number(value);
                    case "armed" -> {
                        r.armedFirstMs = number(value);
                        r.armedLastMs = Math.max(r.armedLastMs, r.armedFirstMs);
                    }
                    case "armedlast" -> {
                        r.armedLastMs = Math.max(r.armedLastMs, number(value));
                        // "last seen armed" says armed too: the one "armed=" line may never have reached the disk
                        // (a full disk, the file held for a moment), and a flight read as never armed is deleted
                        // with its track and recording at the next start
                        if (r.armedFirstMs == 0) r.armedFirstMs = number(value);
                    }
                    case "track" -> r.track = Path.of(value);
                    case "linklog" -> r.linkLog = Path.of(value);
                    case "video" -> {
                        Path v = Path.of(value);
                        if (!r.videos.contains(v)) r.videos.add(v);
                    }
                    case "novideo" -> r.videos.remove(Path.of(value)); // a part deleted for having nothing in it
                    case "end" -> {
                        int space = value.indexOf(' ');
                        r.endMs = number(space < 0 ? value : value.substring(0, space));
                        r.ended = space < 0 ? DISCONNECT : value.substring(space + 1).trim();
                    }
                    default -> {
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return r.startMs > 0 ? r : null;
    }

    private static long number(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Every record in a folder (null or missing: none), oldest first. Reads files: not on the Swing thread. */
    public static List<FlightRecord> readAll(Path dir) {
        List<FlightRecord> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).sorted().forEach(f -> {
                FlightRecord r = read(f);
                if (r != null) out.add(r);
            });
        } catch (IOException ignored) {
        }
        return out;
    }

    /** Waits until everything asked so far is written (the tests; a clean shutdown). */
    public static void flush() {
        try {
            WRITER.submit(() -> {
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    public Path file() {
        return file;
    }

    /** The phone's name; "" when it had none. */
    public synchronized String drone() {
        return drone;
    }

    public synchronized String address() {
        return address;
    }

    /** What the flight is called: the phone's name, or without one its address; "" when neither is known. */
    public synchronized String label() {
        return !drone.isEmpty() ? drone : address;
    }

    public synchronized String app() {
        return app;
    }

    public synchronized long startMs() {
        return startMs;
    }

    /** The flight controller was seen armed in this connection. */
    public synchronized boolean wasArmed() {
        return armedFirstMs > 0;
    }

    public synchronized long armedFirstMs() {
        return armedFirstMs;
    }

    public synchronized long armedLastMs() {
        return armedLastMs;
    }

    /** It has its end line. Without: the app never closed it. */
    public synchronized boolean finished() {
        return ended != null;
    }

    /** How it ended; without an end line: {@link #FLYING} for the connection open now, else {@link #APP_CLOSED}. */
    public synchronized String ended() {
        if (ended != null) return ended;
        Path now = live;
        return now != null && now.equals(file) ? FLYING : APP_CLOSED;
    }

    /** The record of the connection open now (null: none): it has no end line yet and is not a closed app's. */
    public static void live(FlightRecord r) {
        live = r == null ? null : r.file;
    }

    /** This is the connection open now. */
    public boolean isLive() {
        Path now = live;
        return now != null && now.equals(file);
    }

    /** When it ended; 0 for a record without an end line. */
    public synchronized long endMs() {
        return endMs;
    }

    public synchronized Path track() {
        return track;
    }

    public synchronized Path linkLog() {
        return linkLog;
    }

    /** The recording's parts, in the order they were made. */
    public synchronized List<Path> videos() {
        return List.copyOf(videos);
    }

    /**
     * A name for a file's name from a phone's name or address: letters, digits, dots and underscores (no dash: the files' names are cut at dashes), never
     * starting with a digit (a recording's second file in the same second is "-2"; an address gets "ip" before it).
     * "" when there is nothing to say.
     */
    public static String fileLabel(String label) {
        if (label == null) return "";
        String s = label.trim().replaceAll("[^A-Za-z0-9._]", "_");
        while (s.startsWith(".")) s = s.substring(1);
        if (s.isEmpty()) return "";
        if (!Character.isLetter(s.charAt(0))) s = "ip" + s;
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
