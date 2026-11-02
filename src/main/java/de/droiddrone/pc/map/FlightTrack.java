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

/**
 * Where the drone has been this connection: every GPS position the flight controller reported (about 2 a second),
 * written as it comes to a GPX file (Documents\DRAUPNE Link\Tracks\track-DATE.gpx: any map program opens it, and a
 * file cut short by a crash still holds every point) and, once a second, to last-position.txt next to it: the drone's
 * last known position with links to open it on a map. Home is where the drone first had a 3D fix while armed, as
 * Betaflight sets it. Thread-safe: samples come from the window's timer, readers from the painter.
 */
public final class FlightTrack {
    /** One GPS report. Speed in km/h, course and altitude as the GPS gave them. */
    public record Point(long timeMs, double lat, double lon, int altM, double speedKmh, int courseDeg, int sats, boolean armed) {}

    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final long LAST_POSITION_EVERY_MS = 1000;

    private final Path dir;
    private final List<Point> points = new ArrayList<>();
    private Point home;
    private long lastReceivedAt;
    private Path file;
    private BufferedWriter out;
    private long lastPositionWrittenAt;
    private boolean closed;
    private final long startedAt = System.currentTimeMillis();

    /** @param dir where the files go; null keeps the track in memory only */
    public FlightTrack(Path dir) {
        this.dir = dir;
    }

    /** Documents\DRAUPNE Link\Tracks. */
    public static Path defaultDir() throws IOException {
        return Files.createDirectories(Path.of(System.getProperty("user.home"), "Documents", "DRAUPNE Link", "Tracks"));
    }

    /** The newest GPS report (the same one twice is ignored) and whether the FC says armed. */
    public synchronized void sample(FcTelemetry.Gps gps, boolean armed) {
        if (closed || gps == null || gps.receivedAt() == 0 || gps.receivedAt() == lastReceivedAt) return;
        lastReceivedAt = gps.receivedAt();
        if (gps.fix() < 1 || (gps.latDeg() == 0 && gps.lonDeg() == 0)) return;
        Point p = new Point(gps.receivedAt(), gps.latDeg(), gps.lonDeg(), gps.altM(), gps.speedKmh(), gps.courseDeg(), gps.sats(), armed);
        points.add(p);
        if (home == null && armed && gps.fix() >= 2) home = p;
        if (dir != null) {
            try {
                write(p);
                if (p.timeMs() - lastPositionWrittenAt >= LAST_POSITION_EVERY_MS) {
                    lastPositionWrittenAt = p.timeMs();
                    writeLastPosition(p);
                }
            } catch (IOException e) {
                // the files are a convenience; the map in the window still has the track
            }
        }
    }

    public synchronized List<Point> points() {
        return new ArrayList<>(points);
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

    /** Ends the GPX file properly; the track stays readable in memory. */
    public synchronized void close() {
        closed = true;
        if (out == null) return;
        try {
            out.write("  </trkseg>\n </trk>\n</gpx>\n");
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }

    private void write(Point p) throws IOException {
        if (out == null) {
            Files.createDirectories(dir);
            file = dir.resolve("track-" + LocalDateTime.now().format(FILE) + ".gpx");
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<gpx version=\"1.1\" creator=\"DRAUPNE Link " + de.droiddrone.pc.AppInfo.version() + "\""
                    + " xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:dl=\"https://github.com/MelonNO/draupne-link/gpx\">\n"
                    + " <metadata><time>" + ISO.format(Instant.ofEpochMilli(startedAt)) + "</time></metadata>\n"
                    + " <trk>\n  <name>Flight " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "</name>\n"
                    + "  <trkseg>\n");
        }
        out.write(String.format(Locale.ROOT,
                "   <trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%d</ele><time>%s</time><sat>%d</sat>"
                        + "<extensions><dl:speed_kmh>%.1f</dl:speed_kmh><dl:course>%d</dl:course><dl:armed>%d</dl:armed></extensions></trkpt>\n",
                p.lat(), p.lon(), p.altM(), ISO.format(Instant.ofEpochMilli(p.timeMs())), p.sats(), p.speedKmh(), p.courseDeg(), p.armed() ? 1 : 0));
        out.flush(); // every point: a crash loses nothing
    }

    /** last-position.txt: the drone's last known position, for finding it. Written whole, then moved into place. */
    private void writeLastPosition(Point p) throws IOException {
        Point h = home;
        StringBuilder sb = new StringBuilder();
        sb.append("DRAUPNE Link - the drone's last known position\n\n");
        sb.append("Time:      ").append(LocalDateTime.ofInstant(Instant.ofEpochMilli(p.timeMs()), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append(String.format(Locale.ROOT, "Position:  %.6f, %.6f%n", p.lat(), p.lon()));
        sb.append(String.format(Locale.ROOT, "Altitude:  %d m (GPS)   Speed: %.0f km/h   Satellites: %d   %s%n",
                p.altM(), p.speedKmh(), p.sats(), p.armed() ? "armed" : "not armed"));
        if (h != null) {
            sb.append(String.format(Locale.ROOT, "From home: %.0f m, bearing %03d (home %.6f, %.6f)%n",
                    Mercator.distanceM(h.lat(), h.lon(), p.lat(), p.lon()), Mercator.bearingDeg(h.lat(), h.lon(), p.lat(), p.lon()), h.lat(), h.lon()));
        }
        sb.append('\n').append(googleMapsUrl(p.lat(), p.lon())).append('\n').append(openStreetMapUrl(p.lat(), p.lon())).append('\n');
        if (file != null) sb.append("\nThe whole flight: ").append(file.getFileName()).append(" (GPX) in this folder.\n");
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
}
