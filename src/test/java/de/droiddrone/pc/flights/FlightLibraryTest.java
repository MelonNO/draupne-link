package de.droiddrone.pc.flights;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assertions.*;

/** PREVIOUS FLIGHTS: the files of one connection found and put together by time, read back for playback. Made-up files only. */
public class FlightLibraryTest {
    static final ZoneId ZONE = ZoneId.systemDefault();

    public static long at(String local) {
        return LocalDateTime.parse(local).atZone(ZONE).toInstant().toEpochMilli();
    }

    /** A GPX like FlightTrack writes: one point a second going north, armed from the second point. */
    public static Path gpx(Path dir, String local, int seconds) throws IOException {
        long t0 = at(local);
        String stamp = LocalDateTime.parse(local).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>\n<gpx>\n <trk>\n  <name>Flight</name>\n  <trkseg>\n");
        for (int i = 0; i < seconds; i++) {
            sb.append(String.format(Locale.ROOT, "   <trkpt lat=\"%.7f\" lon=\"10.7300000\"><ele>%d</ele><time>%s</time><sat>14</sat>"
                            + "<extensions><dl:armed>%d</dl:armed><dl:speed_kmh>36.0</dl:speed_kmh><dl:course>0</dl:course></extensions></trkpt>\n",
                    59.91 + i * 0.0001, 10 + i, Instant.ofEpochMilli(t0 + i * 1000L), i >= 1 ? 1 : 0));
        }
        sb.append("  </trkseg>\n </trk>\n</gpx>\n");
        Path f = dir.resolve("track-" + stamp + ".gpx");
        Files.writeString(f, sb, StandardCharsets.UTF_8);
        return f;
    }

    /** A recording: an (empty) MKV, its -osd copy and the figures .csv, ten rows a second for this long. */
    public static Path video(Path dir, String local, int seconds) throws IOException {
        String stamp = LocalDateTime.parse(local).format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path mkv = dir.resolve("flight-" + stamp + ".mkv");
        Files.write(mkv, new byte[0]);
        Files.write(dir.resolve("flight-" + stamp + "-osd.mkv"), new byte[0]);
        StringBuilder sb = new StringBuilder("pts_ms,ping_ms,volts,cell_volts,amps,mah,sats,fix,lat,lon,speed_kmh,home_m,alt_m,vario_ms,mode,armed,roll,pitch,heading\n");
        for (int ms = 0; ms <= seconds * 1000; ms += 33) {
            double cell = 4.2 - ms / 1000.0 * 0.01;
            sb.append(String.format(Locale.ROOT, "%d,45,%.2f,%.2f,12.0,100,14,2,%.6f,10.730000,27.0,%d,%.1f,0.0,ANGLE,1,0.0,0.0,0%n",
                    ms, cell * 6, cell, 59.91 + ms / 1000.0 * 0.0001, ms / 100, 30.0 + ms / 1000.0));
        }
        Files.writeString(dir.resolve("flight-" + stamp + ".csv"), sb, StandardCharsets.UTF_8);
        return mkv;
    }

    /** A link log: the "Connecting to" line, then ten rows a second, the link down for one second in the middle. */
    public static Path linkLog(Path dir, String local, int seconds, String drone) throws IOException {
        LocalDateTime start = LocalDateTime.parse(local);
        Path f = dir.resolve("link-" + start.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv");
        DateTimeFormatter hms = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
        StringBuilder sb = new StringBuilder("time,link,ping_ms,rc_frames,rc_max_gap_ms,telemetry_packets,telemetry_max_gap_ms,fc_data_age_ms,rxloss,failsafe,armed,blockers,event\n");
        sb.append(start.format(hms)).append(",,,,,,,,,,,,Connecting to ").append(drone).append(" (VIRTUAL) (100.64.0.1:8888)\n");
        for (int i = 1; i <= seconds * 10; i++) {
            LocalDateTime t = start.plusNanos(i * 100_000_000L);
            boolean down = i >= seconds * 5 && i < seconds * 5 + 10;
            sb.append(t.format(hms)).append(',').append(down ? "CONNECTING" : "READY").append(',').append(down ? 900 : 48)
                    .append(",25,4,10,100,20,").append(i % 50 == 0 ? 1 : 0).append(",0,1,,\n");
        }
        Files.writeString(f, sb, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void oneConnectionsTrackRecordingAndLinkLogAreOneFlight(@TempDir Path root) throws IOException {
        Path tracks = Files.createDirectories(root.resolve("Tracks")), videos = Files.createDirectories(root.resolve("Videos")),
                logs = Files.createDirectories(root.resolve("logs"));
        linkLog(logs, "2026-11-08T14:04:50", 80, "MobileAlpha");
        gpx(tracks, "2026-11-08T14:05:00", 60);
        video(videos, "2026-11-08T14:05:02", 55);
        // a second flight an hour later: only a track
        gpx(tracks, "2026-11-08T15:10:00", 30);
        // a connection that kept nothing but its link log: not a flight
        linkLog(logs, "2026-11-08T16:00:00", 20, "MobileBravo");

        List<FlightLibrary.Flight> flights = FlightLibrary.scan(tracks, videos, logs);
        assertEquals(2, flights.size(), "the link log alone is not a flight");
        FlightLibrary.Flight late = flights.get(0), early = flights.get(1);
        assertTrue(late.startMs() > early.startMs(), "newest first");
        assertNull(late.video());
        assertNull(late.drone(), "no link log: the drone isn't known");

        assertEquals("MobileAlpha", early.drone());
        assertNotNull(early.track());
        assertNotNull(early.video());
        assertNotNull(early.osdVideo());
        assertNotNull(early.telemetry());
        assertNotNull(early.linkLog());
        assertEquals(early.osdVideo(), early.playable(), "the copy with the OSD plays");
        assertEquals(at("2026-11-08T14:05:00"), early.startMs(), "the flight starts at the track, not at CONNECT");
        assertEquals(59_000, early.durationMs(), 1_000, "to the track's end (the recording ends sooner)");
        assertEquals(59 * 11.1, early.distanceM(), 15, "the track's length");
        assertEquals(5, early.files().size());
        assertEquals("1:00", FlightLibrary.durationText(60_000));
        assertEquals("1:02:03", FlightLibrary.durationText(3_723_000));
    }

    @Test
    void aFlightReadsBackOnOneClock(@TempDir Path root) throws IOException {
        Path tracks = Files.createDirectories(root.resolve("Tracks")), videos = Files.createDirectories(root.resolve("Videos")),
                logs = Files.createDirectories(root.resolve("logs"));
        linkLog(logs, "2026-11-08T14:04:58", 40, "MobileZulu");
        gpx(tracks, "2026-11-08T14:05:00", 30);
        video(videos, "2026-11-08T14:05:00", 30);
        FlightLibrary.Flight f = FlightLibrary.scan(tracks, videos, logs).get(0);
        FlightData d = FlightData.load(f);
        assertEquals(30, d.track.size());
        assertFalse(d.telemetry.isEmpty(), "telemetry from " + f.telemetry());
        assertTrue(d.telemetry.size() <= 31 * 10 + 1, "ten rows a second are enough");
        assertFalse(d.link.isEmpty(), "link from " + f.linkLog());

        long t10 = at("2026-11-08T14:05:10");
        FlightData.Sample s = d.at(t10);
        assertEquals(40.0, s.altM(), 0.2, "the recording's altitude 10 s in");
        assertEquals(4.1, s.cellVolts(), 0.01);
        assertEquals("ANGLE", s.mode());
        assertEquals(100, s.homeM(), 2);

        double[] p = d.positionAt(t10 + 500);
        assertEquals(59.91 + 10.5 * 0.0001, p[0], 1e-6, "halfway between two track points");
        assertEquals(0, p[2], 1, "heading north");

        long down = d.link.stream().filter(FlightData.LinkSample::down).count();
        assertEquals(10, down, "the second the link was down");
        assertTrue(d.link.stream().anyMatch(FlightData.LinkSample::rxloss));
        assertTrue(d.link.stream().filter(FlightData.LinkSample::down).allMatch(l -> l.pingMs() == 900));
    }

    /**
     * A recording without the FC's attitude writes its heading as -1 (VideoPipeline.osdRow): unknown, not -1 degrees -
     * before 1.0.53 a flight with a recording and no track drew its marker pointing at -1 degrees (1.0.53 review).
     */
    @Test
    void aRecordingsUnknownHeadingStaysUnknown(@TempDir Path root) throws IOException {
        Path csv = root.resolve("flight-2026-11-08_14-05-00.csv");
        Files.writeString(csv, "pts_ms,ping_ms,volts,cell_volts,amps,mah,sats,fix,lat,lon,speed_kmh,home_m,alt_m,vario_ms,mode,armed,roll,pitch,heading\n"
                + "0,40,16.4,4.10,1.0,10,9,2,59.910000,10.750000,0.0,0,1.0,0.0,ANGLE,1,,,-1\n"
                + "200,40,16.4,4.10,1.0,10,9,2,59.910000,10.750000,0.0,0,1.0,0.0,ANGLE,1,0.0,0.0,90\n", StandardCharsets.UTF_8);
        java.util.List<FlightData.Sample> rows = FlightData.readTelemetry(csv, 1_000);
        assertEquals(2, rows.size());
        assertTrue(Double.isNaN(rows.get(0).headingDeg()), "-1 is unknown: " + rows.get(0).headingDeg());
        assertEquals(90, rows.get(1).headingDeg(), 1e-9);
    }

    @Test
    void withoutARecordingTheTrackGivesTheFigures(@TempDir Path root) throws IOException {
        Path tracks = Files.createDirectories(root.resolve("Tracks"));
        gpx(tracks, "2026-11-08T09:00:00", 20);
        FlightData d = FlightData.load(FlightLibrary.scan(tracks, null, null).get(0));
        FlightData.Sample s = d.at(at("2026-11-08T09:00:10"));
        assertEquals(20, s.altM(), 0.01);
        assertEquals(36, s.speedKmh(), 0.01);
        assertTrue(Double.isNaN(s.cellVolts()), "no battery without a recording");
        assertEquals(9 * 11.1, s.homeM(), 3, "from the first armed point");
        assertEquals(20, d.path().size());
    }

    @Test
    void exportCopiesAndDeleteRemovesEveryFile(@TempDir Path root) throws IOException {
        Path tracks = Files.createDirectories(root.resolve("Tracks")), videos = Files.createDirectories(root.resolve("Videos")),
                logs = Files.createDirectories(root.resolve("logs")), out = Files.createDirectories(root.resolve("Exports"));
        linkLog(logs, "2026-11-08T14:04:58", 40, "MobileZulu");
        gpx(tracks, "2026-11-08T14:05:00", 30);
        video(videos, "2026-11-08T14:05:00", 30);
        FlightLibrary.Flight f = FlightLibrary.scan(tracks, videos, logs).get(0);
        Path dir = FlightData.export(f, out);
        try (var s = Files.list(dir)) {
            assertEquals(5, s.count());
        }
        Path again = FlightData.export(f, out);
        assertNotEquals(dir, again, "a second export doesn't overwrite the first");
        assertTrue(FlightData.delete(f).isEmpty());
        for (Path p : f.files()) assertFalse(Files.exists(p));
        assertTrue(FlightLibrary.scan(tracks, videos, logs).isEmpty());
    }

    @Test
    void aLinkLogPastMidnightCountsOnTheNextDay() {
        long start = at("2026-11-08T23:59:50");
        assertEquals(at("2026-11-09T00:00:05"), FlightLibrary.timeOnDay(start, "00:00:05.000"));
        assertEquals(at("2026-11-08T23:59:55"), FlightLibrary.timeOnDay(start, "23:59:55.000"));
    }
}
