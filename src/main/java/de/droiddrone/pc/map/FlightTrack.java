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
 * Where the drone has been this connection: every position reported (about 2 a second from the drone's GPS, 1 a
 * second from the phone's), written as it comes to a GPX file (Documents\DRAUPNE Link\Tracks\track-DATE.gpx: any map
 * program opens it, and a file cut short by a crash still holds every point) and, once a second, to
 * last-position.txt next to it: the drone's last known position with links to open it on a map.
 * <p>
 * Two sources: the drone's GPS through the flight controller, and the phone's own GPS (phone app 2.3.0-pc.16 on,
 * when the pilot allowed location). The pilot chooses one in the settings; the other fills in when the chosen one
 * has given nothing for {@value #FALLBACK_MS} ms, so a dead flight controller still leaves the phone's position, and
 * a phone without a fix the drone's. Home is where the drone first had a 3D fix while armed, as Betaflight sets it.
 * Thread-safe: samples come from the window's timer, readers from the painter.
 */
public final class FlightTrack {
    /** One position report. Speed in km/h, course and altitude as the GPS gave them; phone = from the phone's GPS. */
    public record Point(long timeMs, double lat, double lon, int altM, double speedKmh, int courseDeg, int sats, boolean armed, boolean phone) {}

    static final long FALLBACK_MS = 5000;
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final long LAST_POSITION_EVERY_MS = 1000;
    /** A phone position older than this (the phone's GPS lost) isn't a position. */
    private static final int PHONE_MAX_AGE_S = 30;

    private final Path dir;
    private final List<Point> points = new ArrayList<>();
    private Point home;
    private long lastDroneAt, lastPhoneAt;
    private long lastDronePointAt, lastPhonePointAt;
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
        boolean phoneFix = phoneNew && phone.ageS() <= PHONE_MAX_AGE_S && !(phone.latDeg() == 0 && phone.lonDeg() == 0);

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

    private static Point dronePoint(FcTelemetry.Gps gps, boolean armed) {
        return new Point(gps.receivedAt(), gps.latDeg(), gps.lonDeg(), gps.altM(), gps.speedKmh(), gps.courseDeg(), gps.sats(), armed, false);
    }

    private static Point phonePoint(FcTelemetry.PhonePosition phone, boolean armed) {
        return new Point(phone.receivedAt(), phone.latDeg(), phone.lonDeg(), phone.altM(), phone.speedKmh(), phone.courseDeg(), 0, armed, true);
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
        close(List.of());
    }

    /** Ends the GPX file, with the pilot's pins as its waypoints (GPX wants them before the track: it is rewritten). */
    public synchronized void close(List<Pins.Pin> pins) {
        closed = true;
        if (out == null) return;
        try {
            out.write("  </trkseg>\n </trk>\n</gpx>\n");
            out.close();
            if (!pins.isEmpty() && file != null) {
                StringBuilder wpts = new StringBuilder();
                for (Pins.Pin p : pins) {
                    wpts.append(String.format(Locale.ROOT, " <wpt lat=\"%.7f\" lon=\"%.7f\"><name>%s</name></wpt>%n", p.lat(), p.lon(),
                            p.name().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")));
                }
                String gpx = Files.readString(file, StandardCharsets.UTF_8);
                int at = gpx.indexOf(" <trk>");
                if (at > 0) Files.writeString(file, gpx.substring(0, at) + wpts + gpx.substring(at), StandardCharsets.UTF_8);
            }
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
                        + "<extensions><dl:speed_kmh>%.1f</dl:speed_kmh><dl:course>%d</dl:course><dl:armed>%d</dl:armed><dl:source>%s</dl:source></extensions></trkpt>\n",
                p.lat(), p.lon(), p.altM(), ISO.format(Instant.ofEpochMilli(p.timeMs())), p.sats(), p.speedKmh(), p.courseDeg(), p.armed() ? 1 : 0,
                p.phone() ? "phone" : "drone"));
        out.flush(); // every point: a crash loses nothing
    }

    /** last-position.txt: the drone's last known position, for finding it. Written whole, then moved into place. */
    private void writeLastPosition(Point p) throws IOException {
        Point h = home;
        StringBuilder sb = new StringBuilder();
        sb.append("DRAUPNE Link - the drone's last known position\n\n");
        sb.append("Time:      ").append(LocalDateTime.ofInstant(Instant.ofEpochMilli(p.timeMs()), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("  (").append(p.phone() ? "the phone's GPS" : "the drone's GPS").append(")\n");
        sb.append(String.format(Locale.ROOT, "Position:  %.6f, %.6f%n", p.lat(), p.lon()));
        sb.append("MGRS:      ").append(Coordinates.mgrs(p.lat(), p.lon(), 5)).append('\n');
        sb.append(String.format(Locale.ROOT, "Altitude:  %d m (GPS)   Speed: %.0f km/h   %s%s%n",
                p.altM(), p.speedKmh(), p.phone() ? "" : "Satellites: " + p.sats() + "   ", p.armed() ? "armed" : "not armed"));
        if (h != null) {
            sb.append(String.format(Locale.ROOT, "From launch: %.0f m, bearing %03d (launch %.6f, %.6f)%n",
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
