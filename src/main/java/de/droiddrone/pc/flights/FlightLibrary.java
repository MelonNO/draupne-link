package de.droiddrone.pc.flights;

import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Mercator;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * PREVIOUS FLIGHTS (the pilot, 2026-11-08: "a proper screen with the possibility to play back"): every connection
 * this PC has kept something of, found by time - its GPX track (Documents\DRAUPNE Link\Tracks\track-DATE.gpx), its
 * recording (Videos\DRAUPNE Link\flight-DATE.mkv, the OSD copy -osd.mkv and the figures .csv beside it, whose pts_ms is
 * the MKV's clock) and its link log (%APPDATA%\DraupneLink\logs\link-DATE.csv, the newest 20 only). The files of one
 * connection overlap in time; that's how they are put together. A connection with neither a track nor a recording
 * isn't a flight to show. The drone's name comes from the link log's "Connecting to ..." / "Watching ..." line.
 */
public final class FlightLibrary {
    /** Files this close in time (or overlapping) are the same connection's. */
    static final long JOIN_MS = 20_000;
    private static final Pattern VIDEO = Pattern.compile("flight-(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})(-\\d+)?\\.mkv");
    private static final Pattern LINK = Pattern.compile("link-(\\d{8}-\\d{6})\\.csv");
    private static final Pattern TRACK = Pattern.compile("track-(\\d{8}-\\d{6})\\.gpx");
    private static final Pattern CONNECTING = Pattern.compile("(?:Connecting to|Watching|The pilot now flies) (\\S+)");
    private static final DateTimeFormatter VIDEO_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private FlightLibrary() {
    }

    /**
     * One connection's flight. Times are epoch ms; any file may be null (only a track, only a recording, no link log
     * any more); distanceM is the track's length (NaN: not measured).
     */
    public record Flight(long startMs, long endMs, String drone, Path track, Path video, Path osdVideo, Path telemetry,
                         Path linkLog, double distanceM) {
        public long durationMs() {
            return Math.max(0, endMs - startMs);
        }

        /** "2026-11-08 14:05". */
        public String title() {
            return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startMs), ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }

        /** The video to show: the copy with the OSD drawn in when there is one, else the recording. */
        public Path playable() {
            return osdVideo != null ? osdVideo : video;
        }

        /** Every file of it, for EXPORT and DELETE. */
        public List<Path> files() {
            List<Path> out = new ArrayList<>();
            for (Path p : new Path[]{track, video, osdVideo, telemetry, linkLog}) if (p != null) out.add(p);
            return out;
        }
    }

    /** One file found, with its time span. */
    record Item(Kind kind, Path file, long startMs, long endMs, String drone, double distanceM) {}

    enum Kind { TRACK, VIDEO, LINK }

    /** Every flight in these folders (any may be null or missing), newest first. Reads files: not on the Swing thread. */
    public static List<Flight> scan(Path tracksDir, Path videosDir, Path logsDir) {
        List<Item> items = new ArrayList<>();
        for (Path f : list(tracksDir)) {
            Item i = trackItem(f);
            if (i != null) items.add(i);
        }
        for (Path f : list(videosDir)) {
            Item i = videoItem(f);
            if (i != null) items.add(i);
        }
        for (Path f : list(logsDir)) {
            Item i = linkItem(f);
            if (i != null) items.add(i);
        }
        return group(items);
    }

    static List<Flight> group(List<Item> items) {
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingLong(Item::startMs));
        List<Flight> flights = new ArrayList<>();
        List<Item> group = new ArrayList<>();
        long groupEnd = Long.MIN_VALUE;
        for (Item i : sorted) {
            if (!group.isEmpty() && i.startMs() > groupEnd + JOIN_MS) {
                Flight f = flight(group);
                if (f != null) flights.add(f);
                group.clear();
            }
            group.add(i);
            groupEnd = group.size() == 1 ? i.endMs() : Math.max(groupEnd, i.endMs());
        }
        if (!group.isEmpty()) {
            Flight f = flight(group);
            if (f != null) flights.add(f);
        }
        flights.sort(Comparator.comparingLong(Flight::startMs).reversed());
        return flights;
    }

    private static Flight flight(List<Item> group) {
        Path track = null, video = null, linkLog = null;
        String drone = null;
        double distance = Double.NaN;
        long start = Long.MAX_VALUE, end = Long.MIN_VALUE;
        for (Item i : group) {
            switch (i.kind()) {
                case TRACK -> {
                    if (track == null) {
                        track = i.file();
                        distance = i.distanceM();
                    }
                }
                case VIDEO -> {
                    if (video == null) video = i.file();
                }
                case LINK -> {
                    if (linkLog == null) linkLog = i.file();
                }
            }
            if (drone == null && i.drone() != null) drone = i.drone();
            if (i.kind() != Kind.LINK) { // the link log runs from CONNECT, before the flight: the flight is the track and the video
                start = Math.min(start, i.startMs());
                end = Math.max(end, i.endMs());
            }
        }
        if (track == null && video == null) return null;
        Path osd = null, csv = null;
        if (video != null) {
            String base = video.getFileName().toString().replaceFirst("\\.mkv$", "");
            Path o = video.resolveSibling(base + "-osd.mkv");
            Path c = video.resolveSibling(base + ".csv");
            if (Files.isRegularFile(o)) osd = o;
            if (Files.isRegularFile(c)) csv = c;
        }
        return new Flight(start, Math.max(start, end), drone, track, video, osd, csv, linkLog, distance);
    }

    private static List<Path> list(Path dir) {
        List<Path> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    static Item trackItem(Path f) {
        if (!TRACK.matcher(f.getFileName().toString()).matches()) return null;
        try {
            FlightTrack t = FlightTrack.read(f);
            List<FlightTrack.Point> pts = t.points();
            if (pts.isEmpty()) return null;
            long start = Long.MAX_VALUE, end = Long.MIN_VALUE;
            for (FlightTrack.Point p : pts) {
                if (p.timeMs() <= 0) continue;
                start = Math.min(start, p.timeMs());
                end = Math.max(end, p.timeMs());
            }
            if (start == Long.MAX_VALUE) start = end = fileTime(f.getFileName().toString(), TRACK);
            return new Item(Kind.TRACK, f, start, end, null, length(pts));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static Item videoItem(Path f) {
        String name = f.getFileName().toString();
        if (name.endsWith("-osd.mkv")) return null;
        Matcher m = VIDEO.matcher(name);
        if (!m.matches()) return null;
        long start;
        try {
            start = LocalDateTime.parse(m.group(1), VIDEO_TIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return null;
        }
        long length = 0;
        Path csv = f.resolveSibling(name.replaceFirst("\\.mkv$", "") + ".csv");
        String last = lastLine(csv);
        if (last != null) {
            try {
                length = Long.parseLong(last.substring(0, last.indexOf(',')));
            } catch (RuntimeException ignored) {
            }
        }
        return new Item(Kind.VIDEO, f, start, start + length, null, Double.NaN);
    }

    static Item linkItem(Path f) {
        String name = f.getFileName().toString();
        if (!LINK.matcher(name).matches()) return null;
        long start = fileTime(name, LINK);
        if (start == 0) return null;
        long end = start;
        String last = lastLine(f);
        if (last != null && last.length() >= 12 && !last.startsWith("time")) {
            long t = timeOnDay(start, last.substring(0, 12));
            if (t > 0) end = t;
        }
        return new Item(Kind.LINK, f, start, end, droneIn(f), Double.NaN);
    }

    /** The drone the connection was made to: the first "Connecting to X" (or "Watching X") line near the top. */
    static String droneIn(Path linkLog) {
        try (java.io.BufferedReader r = Files.newBufferedReader(linkLog, StandardCharsets.UTF_8)) {
            String line;
            for (int n = 0; n < 600 && (line = r.readLine()) != null; n++) {
                int at = line.lastIndexOf(',');
                if (at < 0) continue;
                Matcher m = CONNECTING.matcher(line.substring(at + 1).replace("\"", ""));
                if (m.find()) return m.group(1);
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return null;
    }

    /** A link log's HH:mm:ss.SSS on the day it started (the next day when it ran past midnight). */
    static long timeOnDay(long startMs, String hms) {
        try {
            LocalDateTime start = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startMs), ZoneId.systemDefault());
            LocalTime t = LocalTime.parse(hms, LOG_TIME);
            LocalDate day = start.toLocalDate();
            if (t.isBefore(start.toLocalTime().minusMinutes(1))) day = day.plusDays(1);
            return LocalDateTime.of(day, t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long fileTime(String name, Pattern p) {
        Matcher m = p.matcher(name);
        if (!m.matches()) return 0;
        try {
            return LocalDateTime.parse(m.group(1), FILE_TIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The last full line of a text file (read from its end), or null. */
    static String lastLine(Path f) {
        if (f == null || !Files.isRegularFile(f)) return null;
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            long len = raf.length();
            int n = (int) Math.min(len, 8192);
            byte[] b = new byte[n];
            raf.seek(len - n);
            raf.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8).stripTrailing();
            int at = Math.max(s.lastIndexOf('\n'), -1);
            String line = s.substring(at + 1).trim();
            return line.isEmpty() ? null : line;
        } catch (IOException e) {
            return null;
        }
    }

    static double length(List<FlightTrack.Point> pts) {
        double d = 0;
        for (int i = 1; i < pts.size(); i++) {
            FlightTrack.Point a = pts.get(i - 1), b = pts.get(i);
            d += Mercator.distanceM(a.lat(), a.lon(), b.lat(), b.lon());
        }
        return d;
    }

    /** "12:34" or "1:02:03". */
    public static String durationText(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
