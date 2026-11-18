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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * PREVIOUS FLIGHTS (the pilot, 2026-11-08: "a proper screen with the possibility to play back"): every connection
 * this PC has kept something of, found by time - its GPX track (Documents\DRAUPNE Link\Tracks\track-DATE.gpx), its
 * recording (Videos\DRAUPNE Link\flight-DATE.mkv, the OSD copy -osd.mkv and the figures .csv beside it, whose pts_ms is
 * the MKV's clock) and its link log (%APPDATA%\DraupneLink\logs\link-DATE.csv: a kept flight's stays, LinkLog deletes
 * the others beyond the newest 20). The files of one connection overlap in time; that's how they are put together. A
 * connection with neither a track nor a recording isn't a flight to show. The drone's name comes from the link log's
 * "Connecting to ..." / "Watching ..." line. The pilot's bookmarks and note are a file beside the track
 * ({@link FlightNotes}).
 */
public final class FlightLibrary {
    /** Files this close in time (or overlapping) are the same connection's. */
    static final long JOIN_MS = 20_000;
    /** 1.0.71: after the stamp the drone's name ({@link FlightRecord#fileLabel}) and / or a counter, each after a dash. */
    private static final Pattern VIDEO = Pattern.compile("flight-(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})(-[A-Za-z0-9._]+)*\\.mkv");
    private static final Pattern LINK = Pattern.compile("link-(\\d{8}-\\d{6})\\.csv");
    private static final Pattern TRACK = Pattern.compile("track-(\\d{8}-\\d{6})(-[A-Za-z0-9._]+)*\\.gpx");
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
                         Path linkLog, double distanceM, List<Part> parts, String ended, Path record) {
        /** A flight found by its files' times (before 1.0.71): one recording, nothing known of its end. */
        public Flight(long startMs, long endMs, String drone, Path track, Path video, Path osdVideo, Path telemetry,
                      Path linkLog, double distanceM) {
            this(startMs, endMs, drone, track, video, osdVideo, telemetry, linkLog, distanceM,
                    video == null ? List.of() : List.of(new Part(video, osdVideo, telemetry, videoStart(video))), null, null);
        }

        /** How it ended, for the list: "LINK LOST", "LOST DRONE", "APP CLOSED", "NOW" - null for a flight ended as usual. */
        public String endMark() {
            return ended == null || FlightRecord.DISCONNECT.equals(ended) ? null : ended;
        }

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

        /** Every file of it, for EXPORT and DELETE: track, recordings, figures, link log - and its notes, once written. */
        public List<Path> files() {
            List<Path> out = new ArrayList<>();
            for (Path p : new Path[]{track, video, osdVideo, telemetry, linkLog}) if (p != null) out.add(p);
            for (Part part : parts) { // 1.0.71: every part of the recording, not the first alone
                for (Path p : new Path[]{part.video(), part.osdVideo(), part.telemetry()}) if (p != null && !out.contains(p)) out.add(p);
            }
            if (record != null && Files.isRegularFile(record)) out.add(record);
            Path notes = notesFile();
            if (notes != null && Files.isRegularFile(notes)) out.add(notes);
            return out;
        }

        /**
         * Where its bookmarks and note are kept ({@link FlightNotes}), there or not: beside the track as
         * track-DATE.notes.txt; a flight with only a recording has them beside that, as flight-DATE.notes.txt.
         */
        public Path notesFile() {
            Path beside = track != null ? track : video;
            if (beside == null) return null;
            String name = beside.getFileName().toString();
            int dot = name.lastIndexOf('.');
            return beside.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + FlightNotes.SUFFIX);
        }
    }

    /**
     * One part of a flight's recording (a new file starts whenever the picture's size changes): the camera's own
     * recording, its copy with the OSD and its figures (either may be null), and where its clock's 0 is.
     */
    public record Part(Path video, Path osdVideo, Path telemetry, long startMs) {
        /** The file to show: the copy with the OSD drawn in when there is one. */
        public Path playable() {
            return osdVideo != null ? osdVideo : video;
        }
    }

    /** When a recording began, from the stamp in its name; 0 when it has none. */
    static long videoStart(Path video) {
        Item i = video == null ? null : videoItem(video);
        return i == null ? 0 : i.startMs();
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
        // 1.0.71: a connection with a record is one flight with all its files, whatever their times; the files of
        // older connections are put together by their times, as before
        List<Flight> recorded = new ArrayList<>();
        Set<Path> claimed = new HashSet<>();
        for (FlightRecord r : FlightRecord.readAll(tracksDir)) {
            Flight f = flight(r, items, claimed);
            if (f != null) recorded.add(f);
        }
        items.removeIf(i -> claimed.contains(normal(i.file())));
        List<Flight> all = new ArrayList<>(group(items));
        all.addAll(recorded);
        all.sort(Comparator.comparingLong(Flight::startMs).reversed());
        return all;
    }

    private static Path normal(Path p) {
        return p.toAbsolutePath().normalize();
    }

    /**
     * A record's flight: its own files among those found (they are claimed, armed or not, so the grouping by time
     * leaves them alone). Null when the drone was never armed in it (the pilot: no flight to log), or when it has
     * neither a track nor a recording to show.
     */
    static Flight flight(FlightRecord r, List<Item> items, Set<Path> claimed) {
        Item track = null, link = null;
        List<Item> videos = new ArrayList<>();
        Set<Path> mine = new HashSet<>();
        if (r.track() != null) mine.add(normal(r.track()));
        if (r.linkLog() != null) mine.add(normal(r.linkLog()));
        for (Path v : r.videos()) mine.add(normal(v));
        for (Item i : items) {
            if (!mine.contains(normal(i.file()))) continue;
            claimed.add(normal(i.file()));
            switch (i.kind()) {
                case TRACK -> track = i;
                case LINK -> link = i;
                case VIDEO -> videos.add(i);
            }
        }
        if (!r.wasArmed()) return null;
        if (track == null && videos.isEmpty()) return null;
        videos.sort(Comparator.comparingLong(Item::startMs));
        List<Part> parts = new ArrayList<>();
        long start = Long.MAX_VALUE, end = Long.MIN_VALUE;
        for (Item v : videos) {
            String base = v.file().getFileName().toString().replaceFirst("\\.mkv$", "");
            Path o = v.file().resolveSibling(base + "-osd.mkv"), c = v.file().resolveSibling(base + ".csv");
            parts.add(new Part(v.file(), Files.isRegularFile(o) ? o : null, Files.isRegularFile(c) ? c : null, v.startMs()));
            start = Math.min(start, v.startMs());
            end = Math.max(end, v.endMs());
        }
        if (track != null) {
            start = Math.min(start, track.startMs());
            end = Math.max(end, track.endMs());
        }
        Part first = parts.isEmpty() ? null : parts.get(0);
        String label = r.label().isEmpty() ? null : r.label();
        return new Flight(start, Math.max(start, end), label, track == null ? null : track.file(),
                first == null ? null : first.video(), first == null ? null : first.osdVideo(), first == null ? null : first.telemetry(),
                link == null ? null : link.file(), track == null ? Double.NaN : track.distanceM(), List.copyOf(parts), r.ended(), r.file());
    }

    /** The link logs that are a kept flight's: what {@link #scan} gives each flight. Reads files: not on the Swing thread. */
    public static Set<Path> keptLinkLogs(Path tracksDir, Path videosDir, Path logsDir) {
        Set<Path> kept = new HashSet<>();
        for (Flight f : scan(tracksDir, videosDir, logsDir)) if (f.linkLog() != null) kept.add(f.linkLog().toAbsolutePath().normalize());
        return kept;
    }

    /**
     * For {@link de.droiddrone.pc.LinkLog#open(Path, Predicate)}: whether a link log is a kept flight's, with the
     * tracks and recordings in the app's own folders. The folders are read once, when the first log is asked about
     * (every track: LinkLog asks on a thread of its own); making it costs nothing.
     */
    public static Predicate<Path> keptLinkLog() {
        return keptLinkLog(() -> {
            try {
                return FlightTrack.defaultDir();
            } catch (IOException e) {
                return null;
            }
        }, () -> {
            try {
                return de.droiddrone.pc.AppPaths.videosDir();
            } catch (IOException e) {
                return null;
            }
        });
    }

    /**
     * {@link #keptLinkLog()} with the folders given; the logs' folder is the asked log's. A folder that isn't there
     * (Documents on a share that is away) hides its flights: every log then counts as kept, so none is deleted until
     * the flights can be seen again.
     */
    public static Predicate<Path> keptLinkLog(Supplier<Path> tracksDir, Supplier<Path> videosDir) {
        return new Predicate<>() {
            private Set<Path> kept;
            private boolean unseen;

            @Override
            public synchronized boolean test(Path log) {
                Path file = log.toAbsolutePath().normalize();
                if (kept == null) {
                    Path tracks = tracksDir.get(), videos = videosDir.get();
                    unseen = tracks == null || videos == null || !Files.isDirectory(tracks) || !Files.isDirectory(videos);
                    kept = unseen ? Set.of() : keptLinkLogs(tracks, videos, file.getParent());
                }
                return unseen || kept.contains(file);
            }
        };
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
        for (String last : lastLines(f)) { // from the end: a last row cut short has no time, the row before it has
            if (last.length() < 12 || last.startsWith("time")) continue;
            long t = timeOnDay(start, last.substring(0, 12));
            if (t > 0) {
                end = t;
                break;
            }
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

    /** The lines in a text file's last 8 KB, the last one first (blank ones left out); empty when it can't be read. */
    static List<String> lastLines(Path f) {
        List<String> out = new ArrayList<>();
        if (f == null || !Files.isRegularFile(f)) return out;
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            long len = raf.length();
            byte[] b = new byte[(int) Math.min(len, 8192)];
            raf.seek(len - b.length);
            raf.readFully(b);
            String[] lines = new String(b, StandardCharsets.UTF_8).split("\n");
            for (int i = lines.length - 1; i >= 0; i--) if (!lines[i].isBlank()) out.add(lines[i].trim());
        } catch (IOException ignored) {
        }
        return out;
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
