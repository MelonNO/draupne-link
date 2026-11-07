package de.droiddrone.pc.map;

import de.droiddrone.pc.FcTelemetry;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where the drone has been this connection: every position reported (about 2 a second from the drone's GPS, 1 a
 * second from the phone's), written as it comes to a GPX file (Documents\DRAUPNE Link\Tracks\track-DATE.gpx: any map
 * program opens it, and a file cut short by a crash still holds every point) and, once a second, to
 * last-position.txt next to it: the drone's last known position with links to open it on a map.
 * <p>
 * Two sources: the drone's GPS through the flight controller, and the phone's own GPS (phone app 2.3.0-pc.16 on,
 * when the pilot allowed location). The pilot chooses one in the settings; the other fills in when the chosen one
 * has given nothing for {@value #FALLBACK_MS} ms, so a dead flight controller still leaves the phone's position, and
 * a phone without a fix the drone's. Home is where the drone first had a 3D fix while armed, as Betaflight sets it.
 * Thread-safe: samples come from the window's timer, readers from the painter. The files are written on a thread of
 * the track's own, in order, so the painter never waits for the disk.
 */
public final class FlightTrack {
    /**
     * One position report. Speed in km/h, course and altitude as the GPS gave them; phone = from the phone's GPS,
     * accuracyM its accuracy (0 when unknown, and for the drone's GPS).
     */
    public record Point(long timeMs, double lat, double lon, int altM, double speedKmh, int courseDeg, int sats, boolean armed, boolean phone,
                        int accuracyM) {}

    static final long FALLBACK_MS = 5000;
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final long LAST_POSITION_EVERY_MS = 1000;
    /** A phone position older than this (the phone's GPS lost) isn't a position. */
    private static final int PHONE_MAX_AGE_S = 30;
    /** The phone sends an unknown accuracy as this. */
    private static final int ACCURACY_UNKNOWN = 65535;

    private final Path dir;
    private final List<Point> points = new ArrayList<>();
    private Point home;
    private long lastDroneAt, lastPhoneAt;
    private long lastDronePointAt, lastPhonePointAt;
    private Path file;
    private boolean closed;
    private final long startedAt = System.currentTimeMillis();
    /** The files, in order, off the caller's thread; null for a track kept in memory only. */
    private final ExecutorService writer;
    // ---- touched on the writer thread only
    private BufferedWriter out;
    private long lastPositionWrittenAt;

    /** @param dir where the files go; null keeps the track in memory only */
    public FlightTrack(Path dir) {
        this.dir = dir;
        writer = dir == null ? null : Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "trackWriter");
            t.setDaemon(true);
            return t;
        });
    }

    /** Documents\DRAUPNE Link\Tracks. */
    public static Path defaultDir() throws IOException {
        return Files.createDirectories(Path.of(System.getProperty("user.home"), "Documents", "DRAUPNE Link", "Tracks"));
    }

    /** The drone's GPS only. */
    public void sample(FcTelemetry.Gps gps, boolean armed) {
        sample(gps, null, armed, false);
    }

    /**
     * The newest reports (the same one twice is ignored) and whether the FC says armed.
     * @param preferPhone the pilot chose the phone's GPS; the drone's fills in when the phone gives nothing
     */
    public synchronized void sample(FcTelemetry.Gps gps, FcTelemetry.PhonePosition phone, boolean armed, boolean preferPhone) {
        if (closed) return;
        long now = System.currentTimeMillis();
        boolean droneNew = gps != null && gps.receivedAt() != 0 && gps.receivedAt() != lastDroneAt;
        if (droneNew) lastDroneAt = gps.receivedAt();
        boolean droneFix = droneNew && gps.fix() >= 1 && !(gps.latDeg() == 0 && gps.lonDeg() == 0);
        boolean phoneNew = phone != null && phone.receivedAt() != 0 && phone.receivedAt() != lastPhoneAt;
        if (phoneNew) lastPhoneAt = phone.receivedAt();
        boolean phoneFix = phoneNew && phone.ageS() <= PHONE_MAX_AGE_S && !(phone.latDeg() == 0 && phone.lonDeg() == 0) && !sameFix(phone);

        Point p = null;
        if (preferPhone) {
            if (phoneFix) p = phonePoint(phone, armed);
            else if (droneFix && now - lastPhonePointAt > FALLBACK_MS) p = dronePoint(gps, armed);
        } else {
            if (droneFix) p = dronePoint(gps, armed);
            else if (phoneFix && now - lastDronePointAt > FALLBACK_MS) p = phonePoint(phone, armed);
        }
        if (p == null) return;
        if (p.phone()) lastPhonePointAt = now; else lastDronePointAt = now;
        points.add(p);
        if (home == null && armed && (p.phone() || gps.fix() >= 2)) home = p;
        if (writer != null) {
            if (file == null) file = dir.resolve("track-" + LocalDateTime.now().format(FILE) + ".gpx");
            Point h = home, pt = p;
            Path f = file;
            writer.execute(() -> {
                try {
                    write(f, pt);
                    if (pt.timeMs() - lastPositionWrittenAt >= LAST_POSITION_EVERY_MS) {
                        lastPositionWrittenAt = pt.timeMs();
                        writeLastPosition(pt, h, f);
                    }
                } catch (IOException e) {
                    // the files are a convenience; the map in the window still has the track
                }
            });
        }
    }

    /**
     * The phone repeating a fix it has had for a while (its GPS lost: the age grows, the place and the fix time
     * don't): no new point, so the map shows LAST KNOWN with the fix's age and the drone's GPS may fill in.
     */
    private boolean sameFix(FcTelemetry.PhonePosition phone) {
        Point last = points.isEmpty() ? null : points.get(points.size() - 1);
        return last != null && last.phone() && last.lat() == phone.latDeg() && last.lon() == phone.lonDeg()
                && Math.abs(fixTime(phone) - last.timeMs()) < 1500;
    }

    /** When the phone's fix was taken: the record's age counted back from its arrival. */
    private static long fixTime(FcTelemetry.PhonePosition phone) {
        return phone.receivedAt() - Math.max(0, phone.ageS()) * 1000L;
    }

    private static Point dronePoint(FcTelemetry.Gps gps, boolean armed) {
        return new Point(gps.receivedAt(), gps.latDeg(), gps.lonDeg(), gps.altM(), gps.speedKmh(), gps.courseDeg(), gps.sats(), armed, false, 0);
    }

    private static Point phonePoint(FcTelemetry.PhonePosition phone, boolean armed) {
        int accuracy = phone.accuracyM() <= 0 || phone.accuracyM() >= ACCURACY_UNKNOWN ? 0 : phone.accuracyM();
        return new Point(fixTime(phone), phone.latDeg(), phone.lonDeg(), phone.altM(), phone.speedKmh(), phone.courseDeg(), 0, armed, true, accuracy);
    }

    public synchronized List<Point> points() {
        return new ArrayList<>(points);
    }

    /** The points from index from on (the map takes them in as they come, not the whole track each time). */
    public synchronized List<Point> pointsFrom(int from) {
        return from >= points.size() ? List.of() : new ArrayList<>(points.subList(Math.max(0, from), points.size()));
    }

    public synchronized int size() {
        return points.size();
    }

    public synchronized Point last() {
        return points.isEmpty() ? null : points.get(points.size() - 1);
    }

    public synchronized Point home() {
        return home;
    }

    public synchronized Path file() {
        return file;
    }

    public synchronized boolean isEmpty() {
        return points.isEmpty();
    }

    /** Waits for what is queued for the files (tests read them right after sampling). */
    void flush() {
        if (writer == null) return;
        try {
            writer.submit(() -> { }).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    /** Ends the GPX file properly; the track stays readable in memory. */
    public synchronized void close() {
        close(List.of());
    }

    /**
     * Ends the GPX file, with the pilot's pins as its waypoints (GPX wants them before the track: it is rewritten,
     * whole and then moved into place), and writes the last point to last-position.txt once more: the final position
     * is the one that matters for finding the drone. Waits for the writes, briefly.
     */
    public synchronized void close(List<Pins.Pin> pins) {
        if (closed) return;
        closed = true;
        if (writer == null) return;
        Point last = points.isEmpty() ? null : points.get(points.size() - 1);
        Point h = home;
        Path f = file;
        writer.execute(() -> {
            if (out == null) return;
            try {
                out.write("  </trkseg>\n </trk>\n</gpx>\n");
                out.close();
                if (last != null) writeLastPosition(last, h, f);
                if (!pins.isEmpty() && f != null) {
                    StringBuilder wpts = new StringBuilder();
                    for (Pins.Pin p : pins) {
                        wpts.append(String.format(Locale.ROOT, " <wpt lat=\"%.7f\" lon=\"%.7f\"><name>%s</name></wpt>%n", p.lat(), p.lon(), xml(p.name())));
                    }
                    String gpx = Files.readString(f, StandardCharsets.UTF_8);
                    int at = gpx.indexOf(" <trk>");
                    if (at > 0) {
                        Path tmp = f.resolveSibling(f.getFileName() + ".part");
                        Files.writeString(tmp, gpx.substring(0, at) + wpts + gpx.substring(at), StandardCharsets.UTF_8);
                        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    }
                }
            } catch (IOException ignored) {
            }
            out = null;
        });
        writer.shutdown();
        try {
            writer.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void write(Path f, Point p) throws IOException {
        if (out == null) {
            Files.createDirectories(dir);
            out = Files.newBufferedWriter(f, StandardCharsets.UTF_8);
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<gpx version=\"1.1\" creator=\"DRAUPNE Link " + de.droiddrone.pc.AppInfo.version() + "\""
                    + " xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:dl=\"https://github.com/MelonNO/draupne-link/gpx\">\n"
                    + " <metadata><time>" + ISO.format(Instant.ofEpochMilli(startedAt)) + "</time></metadata>\n"
                    + " <trk>\n  <name>Flight " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "</name>\n"
                    + "  <trkseg>\n");
        }
        out.write(String.format(Locale.ROOT,
                "   <trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%d</ele><time>%s</time><sat>%d</sat>"
                        + "<extensions><dl:speed_kmh>%.1f</dl:speed_kmh><dl:course>%d</dl:course><dl:armed>%d</dl:armed><dl:source>%s</dl:source>%s</extensions></trkpt>\n",
                p.lat(), p.lon(), p.altM(), ISO.format(Instant.ofEpochMilli(p.timeMs())), p.sats(), p.speedKmh(), p.courseDeg(), p.armed() ? 1 : 0,
                p.phone() ? "phone" : "drone", p.accuracyM() > 0 ? "<dl:accuracy_m>" + p.accuracyM() + "</dl:accuracy_m>" : ""));
        out.flush(); // every point: a crash loses nothing
    }

    /** "± 7 m (phone GPS)" for a phone point with a known accuracy; "" otherwise. */
    public static String accuracyText(Point p) {
        return p != null && p.phone() && p.accuracyM() > 0 ? "± " + p.accuracyM() + " m (phone GPS)" : "";
    }

    /** last-position.txt: the drone's last known position, for finding it. Written whole, then moved into place. */
    private void writeLastPosition(Point p, Point h, Path f) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("DRAUPNE Link - the drone's last known position\n\n");
        sb.append("Time:      ").append(LocalDateTime.ofInstant(Instant.ofEpochMilli(p.timeMs()), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("  (").append(p.phone() ? "the phone's GPS" : "the drone's GPS").append(")\n");
        sb.append(String.format(Locale.ROOT, "Position:  %.6f, %.6f%n", p.lat(), p.lon()));
        sb.append("MGRS:      ").append(Coordinates.mgrs(p.lat(), p.lon(), 5)).append('\n');
        if (p.phone() && p.accuracyM() > 0) sb.append("Accuracy:  ").append(accuracyText(p)).append('\n');
        sb.append(String.format(Locale.ROOT, "Altitude:  %d m (GPS)   Speed: %.0f km/h   %s%s%n",
                p.altM(), p.speedKmh(), p.phone() ? "" : "Satellites: " + p.sats() + "   ", p.armed() ? "armed" : "not armed"));
        if (h != null) {
            sb.append(String.format(Locale.ROOT, "From launch: %.0f m, bearing %03d (launch %.6f, %.6f)%n",
                    Mercator.distanceM(h.lat(), h.lon(), p.lat(), p.lon()), Mercator.bearingDeg(h.lat(), h.lon(), p.lat(), p.lon()), h.lat(), h.lon()));
        }
        sb.append('\n').append(googleMapsUrl(p.lat(), p.lon())).append('\n').append(openStreetMapUrl(p.lat(), p.lon())).append('\n');
        if (f != null) sb.append("\nThe whole flight: ").append(f.getFileName()).append(" (GPX) in this folder.\n");
        Files.createDirectories(dir);
        Path target = dir.resolve("last-position.txt");
        Path tmp = dir.resolve("last-position.txt.part");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static String googleMapsUrl(double lat, double lon) {
        return String.format(Locale.ROOT, "https://www.google.com/maps?q=%.6f,%.6f", lat, lon);
    }

    public static String openStreetMapUrl(double lat, double lon) {
        return String.format(Locale.ROOT, "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=17/%.6f/%.6f", lat, lon, lat, lon);
    }

    // ------------------------------------------------------------------ earlier flights

    private static final Pattern TRKPT = Pattern.compile("<trkpt lat=\"([-0-9.]+)\" lon=\"([-0-9.]+)\">(.*?)</trkpt>", Pattern.DOTALL);

    /** The track files in a folder, newest first (by name: track-DATE.gpx); empty when there are none. */
    public static List<Path> files(Path dir) {
        List<Path> files = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return files;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().startsWith("track-") && p.getFileName().toString().endsWith(".gpx")).forEach(files::add);
        } catch (IOException ignored) {
        }
        files.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
        return files;
    }

    /** The newest track file in the folder, or null. */
    public static Path newest(Path dir) {
        List<Path> files = files(dir);
        return files.isEmpty() ? null : files.get(0);
    }

    /**
     * A track read back from one of this program's GPX files (a file cut short by a crash reads too), closed: for
     * FIND DRONE after a restart, and earlier flights on the map. Home is the first point reported armed.
     */
    public static FlightTrack read(Path gpx) throws IOException {
        String text = Files.readString(gpx, StandardCharsets.UTF_8);
        FlightTrack t = new FlightTrack(null);
        t.file = gpx;
        t.closed = true;
        Matcher m = TRKPT.matcher(text);
        while (m.find()) {
            try {
                String body = m.group(3);
                long time = 0;
                String iso = tag(body, "time");
                if (iso != null) time = Instant.parse(iso).toEpochMilli();
                boolean phone = "phone".equals(tag(body, "dl:source"));
                boolean armed = "1".equals(tag(body, "dl:armed"));
                Point p = new Point(time, Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), number(tag(body, "ele")),
                        Double.parseDouble(tag(body, "dl:speed_kmh") == null ? "0" : tag(body, "dl:speed_kmh")), number(tag(body, "dl:course")),
                        number(tag(body, "sat")), armed, phone, number(tag(body, "dl:accuracy_m")));
                t.points.add(p);
                if (t.home == null && armed) t.home = p;
            } catch (RuntimeException ignored) {
                // a point cut short or hand-edited: left out
            }
        }
        return t;
    }

    private static String tag(String body, String name) {
        int a = body.indexOf("<" + name + ">");
        if (a < 0) return null;
        int b = body.indexOf("</" + name + ">", a);
        return b < 0 ? null : body.substring(a + name.length() + 2, b);
    }

    private static int number(String s) {
        return s == null ? 0 : (int) Math.round(Double.parseDouble(s));
    }
}
