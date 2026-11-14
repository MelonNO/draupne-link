package de.droiddrone.pc.flights;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * Made-up flight files for the debrief's tests, written as the app writes them: a GPX track (one point a second), a
 * recording's figures (ten rows a second beside an empty MKV) and a link log (ten rows a second after its
 * "Connecting to" line). Every figure is the test's own; names and addresses are made up.
 */
public final class FlightFiles {
    private FlightFiles() {
    }

    /** A local time ("2026-11-08T14:05:00") on the clock the files use. */
    public static long at(String local) {
        return LocalDateTime.parse(local).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** One track point. */
    public record Pt(double lat, double lon, int eleM, boolean armed, boolean phone) {
        public Pt(double lat, double lon, int eleM, boolean armed) {
            this(lat, lon, eleM, armed, false);
        }
    }

    /** track-DATE.gpx: point(i) at second i, for i = 0 .. seconds - 1. */
    public static Path gpx(Path dir, String local, int seconds, IntFunction<Pt> point) throws IOException {
        long t0 = at(local);
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"DRAUPNE Link test\">\n"
                + " <trk>\n  <name>Flight</name>\n  <trkseg>\n");
        for (int i = 0; i < seconds; i++) {
            Pt p = point.apply(i);
            sb.append(String.format(Locale.ROOT, "   <trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%d</ele><time>%s</time><sat>14</sat>"
                            + "<extensions><dl:speed_kmh>40.0</dl:speed_kmh><dl:course>0</dl:course><dl:armed>%d</dl:armed><dl:source>%s</dl:source></extensions></trkpt>\n",
                    p.lat(), p.lon(), p.eleM(), Instant.ofEpochMilli(t0 + i * 1000L), p.armed() ? 1 : 0, p.phone() ? "phone" : "drone"));
        }
        sb.append("  </trkseg>\n </trk>\n</gpx>\n");
        Path f = dir.resolve("track-" + LocalDateTime.parse(local).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".gpx");
        Files.writeString(f, sb, StandardCharsets.UTF_8);
        return f;
    }

    /** One row of a recording's figures; NaN (or -1 for homeM and ping) writes the figure as not known. */
    public record Row(double volts, double cellVolts, double lat, double lon, int homeM, double altM, boolean armed, int pingMs) {}

    /** flight-DATE.mkv (empty) and its flight-DATE.csv: row(i) at i tenths of a second, for i = 0 .. seconds * 10. Returns the MKV. */
    public static Path recording(Path dir, String local, int seconds, IntFunction<Row> row) throws IOException {
        String stamp = LocalDateTime.parse(local).format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path mkv = dir.resolve("flight-" + stamp + ".mkv");
        Files.write(mkv, new byte[0]);
        StringBuilder sb = new StringBuilder("pts_ms,ping_ms,volts,cell_volts,amps,mah,sats,fix,lat,lon,speed_kmh,home_m,alt_m,vario_ms,mode,armed,roll,pitch,heading\n");
        for (int i = 0; i <= seconds * 10; i++) {
            Row r = row.apply(i);
            sb.append(i * 100).append(',').append(r.pingMs()).append(',').append(num(r.volts(), 2)).append(',').append(num(r.cellVolts(), 2))
                    .append(",12.0,100,14,2,").append(num(r.lat(), 6)).append(',').append(num(r.lon(), 6)).append(",40.0,").append(r.homeM()).append(',')
                    .append(num(r.altM(), 1)).append(",0.0,ANGLE,").append(r.armed() ? 1 : 0).append(",0.0,0.0,0\n");
        }
        Files.writeString(dir.resolve("flight-" + stamp + ".csv"), sb, StandardCharsets.UTF_8);
        return mkv;
    }

    private static String num(double v, int decimals) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    /** One link log row: the link's state (READY, CONNECTING ...), the ping, and Betaflight's RXLOSS / FAILSAFE as "1", "0" or "" (nothing heard). */
    public record Link(String state, int pingMs, String rxloss, String failsafe) {
        /** The link up, nothing wrong. */
        public static Link ready(int pingMs) {
            return new Link("READY", pingMs, "0", "0");
        }

        /** The link down: nothing heard from Betaflight. */
        public static Link down() {
            return new Link("CONNECTING", -1, "", "");
        }
    }

    public static final String LINK_HEADER = "time,link,ping_ms,rc_frames,rc_max_gap_ms,telemetry_packets,telemetry_max_gap_ms,fc_data_age_ms,"
            + "rxloss,failsafe,armed,blockers,event";

    /**
     * link-DATE.csv: the header, the event rows given (the app's log lines: "Connecting to NAME (ADDRESS)" ...), then
     * row(i) at i tenths of a second, for i = 1 .. seconds * 10.
     */
    public static Path linkLog(Path dir, String local, int seconds, List<String> events, IntFunction<Link> row) throws IOException {
        LocalDateTime start = LocalDateTime.parse(local);
        DateTimeFormatter hms = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
        StringBuilder sb = new StringBuilder(LINK_HEADER + "\n");
        for (String e : events) sb.append(start.format(hms)).append(",,,,,,,,,,,,").append(e.contains(",") ? '"' + e + '"' : e).append('\n');
        for (int i = 1; i <= seconds * 10; i++) {
            Link l = row.apply(i);
            sb.append(start.plusNanos(i * 100_000_000L).format(hms)).append(',').append(l.state()).append(',').append(l.pingMs() < 0 ? "" : l.pingMs())
                    .append(",25,4,10,100,20,").append(l.rxloss()).append(',').append(l.failsafe()).append(",1,,\n");
        }
        Path f = dir.resolve("link-" + start.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv");
        Files.writeString(f, sb, StandardCharsets.UTF_8);
        return f;
    }

    /** A link log with its "Connecting to" line (a made-up address) and the link up all the way. */
    public static Path linkLog(Path dir, String local, int seconds, String drone) throws IOException {
        return linkLog(dir, local, seconds, List.of("Connecting to " + drone + " (192.0.2.7:8888)"), i -> Link.ready(48));
    }
}
