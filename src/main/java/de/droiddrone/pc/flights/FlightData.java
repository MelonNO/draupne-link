package de.droiddrone.pc.flights;

import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Mercator;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One previous flight read for playback, on one clock (epoch ms): the track's points, the recording's figures (the
 * .csv beside the MKV: pts_ms from the file's start, which is the stamp in its name) and the link log's rows. What
 * the flight has no file for stays empty; {@link #at} then makes do with the track (no battery, no mode).
 */
public final class FlightData {
    /** The figures at one moment; NaN / -1 / "" when unknown. homeM: distance from the launch point. */
    public record Sample(long timeMs, double altM, double speedKmh, double cellVolts, double volts, int homeM, String mode,
                         boolean armed, double lat, double lon, double headingDeg) {}

    /** One link log row: ping (ms, -1 unknown), the link not up, RXLOSS, FAILSAFE as Betaflight reported them. */
    public record LinkSample(long timeMs, int pingMs, boolean down, boolean rxloss, boolean failsafe) {}

    public final FlightLibrary.Flight flight;
    public final List<FlightTrack.Point> track;
    public final List<Sample> telemetry;
    public final List<LinkSample> link;
    /** Where the recording's pts 0 is on the clock; 0 without a recording. */
    public final long videoStartMs;

    private FlightData(FlightLibrary.Flight flight, List<FlightTrack.Point> track, List<Sample> telemetry, List<LinkSample> link, long videoStartMs) {
        this.flight = flight;
        this.track = track;
        this.telemetry = telemetry;
        this.link = link;
        this.videoStartMs = videoStartMs;
    }

    /** Reads the flight's files (not on the Swing thread). A file that can't be read is left out. */
    public static FlightData load(FlightLibrary.Flight f) {
        List<FlightTrack.Point> track = List.of();
        if (f.track() != null) {
            try {
                track = FlightTrack.read(f.track()).points();
            } catch (IOException ignored) {
            }
        }
        long videoStart = 0;
        if (f.video() != null) {
            FlightLibrary.Item v = FlightLibrary.videoItem(f.video());
            if (v != null) videoStart = v.startMs();
        }
        List<Sample> tel = f.telemetry() == null ? List.of() : readTelemetry(f.telemetry(), videoStart);
        List<LinkSample> link = f.linkLog() == null ? List.of() : readLink(f.linkLog());
        return new FlightData(f, track, tel, link, videoStart);
    }

    /** The recording's .csv: pts_ms,ping_ms,volts,cell_volts,amps,mah,sats,fix,lat,lon,speed_kmh,home_m,alt_m,vario_ms,mode,armed,roll,pitch,heading */
    static List<Sample> readTelemetry(Path csv, long startMs) {
        List<Sample> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String line = r.readLine();
            if (line == null || !line.startsWith("pts_ms")) return out;
            long lastT = Long.MIN_VALUE / 2; // (not MIN_VALUE: t - lastT would overflow)
            while ((line = r.readLine()) != null) {
                String[] c = line.split(",", -1);
                if (c.length < 19) continue;
                try {
                    long t = startMs + Long.parseLong(c[0]);
                    if (t - lastT < 100) continue; // every frame is more than playback needs: 10 a second
                    lastT = t;
                    out.add(new Sample(t, num(c[12]), num(c[10]), num(c[3]), num(c[2]), (int) num(c[11], -1), c[14], "1".equals(c[15]),
                            num(c[8]), num(c[9]), num(c[18])));
                } catch (RuntimeException ignored) {
                    // a row cut short: left out
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    static List<LinkSample> readLink(Path csv) {
        List<LinkSample> out = new ArrayList<>();
        FlightLibrary.Item item = FlightLibrary.linkItem(csv);
        if (item == null) return out;
        try (BufferedReader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String line = r.readLine(); // the header
            if (line == null) return out;
            while ((line = r.readLine()) != null) {
                if (line.length() < 14) continue;
                String[] c = line.split(",", 12);
                if (c.length < 11 || c[1].isEmpty()) continue; // an event row: every figure blank
                long t = FlightLibrary.timeOnDay(item.startMs(), c[0]);
                if (t == 0) continue;
                int ping = c[2].isEmpty() ? -1 : (int) num(c[2], -1);
                boolean down = !c[1].equals("READY") && !c[1].equals("SPECTATING");
                out.add(new LinkSample(t, ping, down, "1".equals(c[8]), "1".equals(c[9])));
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return out;
    }

    private static double num(String s) {
        return num(s, Double.NaN);
    }

    private static double num(String s, double unknown) {
        if (s == null || s.isEmpty()) return unknown;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return unknown;
        }
    }

    public long startMs() {
        return flight.startMs();
    }

    public long endMs() {
        return flight.endMs();
    }

    /** The figures at time t: the recording's row at or before it; without a recording, the track's point. Null: none yet. */
    public Sample at(long t) {
        if (!telemetry.isEmpty()) {
            int i = indexAtOrBefore(telemetry, t, Sample::timeMs);
            return i < 0 ? telemetry.get(0) : telemetry.get(i);
        }
        if (track.isEmpty()) return null;
        int i = indexAtOrBefore(track, t, FlightTrack.Point::timeMs);
        FlightTrack.Point p = track.get(Math.max(0, i));
        FlightTrack.Point home = null;
        for (FlightTrack.Point q : track) {
            if (q.armed()) {
                home = q;
                break;
            }
        }
        int homeM = home == null ? -1 : (int) Math.round(Mercator.distanceM(home.lat(), home.lon(), p.lat(), p.lon()));
        return new Sample(p.timeMs(), p.altM(), p.speedKmh(), Double.NaN, Double.NaN, homeM, "", p.armed(), p.lat(), p.lon(), p.courseDeg());
    }

    /** Where the drone was at t, {lat, lon, heading} (heading NaN unknown), from the track, else the recording; null: unknown. */
    public double[] positionAt(long t) {
        if (!track.isEmpty()) {
            int i = indexAtOrBefore(track, t, FlightTrack.Point::timeMs);
            if (i < 0) i = 0;
            FlightTrack.Point a = track.get(i);
            if (i + 1 < track.size()) { // between two points: on the way
                FlightTrack.Point b = track.get(i + 1);
                long span = b.timeMs() - a.timeMs();
                double k = span <= 0 ? 0 : Math.max(0, Math.min(1, (t - a.timeMs()) / (double) span));
                double heading = a.lat() == b.lat() && a.lon() == b.lon() ? a.courseDeg() : Mercator.bearingDeg(a.lat(), a.lon(), b.lat(), b.lon());
                return new double[]{a.lat() + (b.lat() - a.lat()) * k, a.lon() + (b.lon() - a.lon()) * k, heading};
            }
            return new double[]{a.lat(), a.lon(), a.courseDeg()};
        }
        Sample s = at(t);
        if (s == null || Double.isNaN(s.lat()) || (s.lat() == 0 && s.lon() == 0)) return null;
        return new double[]{s.lat(), s.lon(), s.headingDeg()};
    }

    /** The track to draw: the GPX's, else the recording's positions. */
    public List<FlightTrack.Point> path() {
        if (!track.isEmpty()) return track;
        List<FlightTrack.Point> out = new ArrayList<>();
        for (Sample s : telemetry) {
            if (Double.isNaN(s.lat()) || (s.lat() == 0 && s.lon() == 0)) continue;
            out.add(new FlightTrack.Point(s.timeMs(), s.lat(), s.lon(), Double.isNaN(s.altM()) ? 0 : (int) Math.round(s.altM()),
                    Double.isNaN(s.speedKmh()) ? 0 : s.speedKmh(), Double.isNaN(s.headingDeg()) ? 0 : (int) s.headingDeg(), 0, s.armed(), false, 0));
        }
        return out;
    }

    private static <T> int indexAtOrBefore(List<T> list, long t, java.util.function.ToLongFunction<T> time) {
        int lo = 0, hi = list.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (time.applyAsLong(list.get(mid)) <= t) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** The files to a folder of its own (EXPORT): track, recordings and figures; the link log too. */
    public static Path export(FlightLibrary.Flight f, Path parent) throws IOException {
        String stamp = f.title().replace(":", "").replace(" ", "_");
        Path dir = parent.resolve("flight-" + stamp);
        for (int n = 2; Files.exists(dir); n++) dir = parent.resolve("flight-" + stamp + "-" + n);
        Files.createDirectories(dir);
        for (Path p : f.files()) Files.copy(p, dir.resolve(p.getFileName()));
        return dir;
    }

    /** DELETE: every file of the flight; those that couldn't go are returned. */
    public static List<Path> delete(FlightLibrary.Flight f) {
        List<Path> left = new ArrayList<>();
        for (Path p : f.files()) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                left.add(p);
            }
        }
        return Collections.unmodifiableList(left);
    }
}
