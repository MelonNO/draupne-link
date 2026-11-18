package de.droiddrone.pc.flights;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The field round of 2026-11-18. The pilot: "always save a log, I had a flight where the drone was lost due to loss
 * of link and we had no log saved" (its path was on the screen until the PC restarted for an update), "always log
 * the phone name to the flight", "don't log flights where the drone was not armed". Names and addresses are made up.
 */
class FlightRecordTest {
    private static final FlightFiles.Pt HERE = new FlightFiles.Pt(60.0, 10.0, 100, true);

    private static FlightFiles.Row row(boolean armed) {
        return new FlightFiles.Row(15.2, 3.8, 60.0, 10.0, 12, 20, armed, 50);
    }

    private static Path rename(Path f, String name) throws IOException {
        return Files.move(f, f.resolveSibling(name));
    }

    @Test
    void theRecordIsOnDiskFromTheStartAndReadsBack(@TempDir Path dir) throws IOException {
        long start = FlightFiles.at("2026-11-17T08:00:00");
        FlightRecord r = FlightRecord.start(dir, "MobileAlpha", "192.0.2.7", "1.0.71", start);
        FlightRecord.flush();
        assertTrue(Files.isRegularFile(r.file()), "written at connect, not at the end");
        assertEquals("flight-20261117-080000.record", r.file().getFileName().toString());
        FlightRecord early = FlightRecord.read(r.file());
        assertEquals("MobileAlpha", early.drone());
        assertEquals("192.0.2.7", early.address());
        assertFalse(early.wasArmed());
        assertFalse(early.finished());
        assertEquals(FlightRecord.APP_CLOSED, early.ended(), "a record without its end line: the app never closed it");

        r.track(dir.resolve("track-20261117-080001-MobileAlpha.gpx"));
        r.video(dir.resolve("flight-2026-11-17_08-00-02-MobileAlpha.mkv"));
        r.video(dir.resolve("flight-2026-11-17_08-03-10-MobileAlpha.mkv"));
        r.video(dir.resolve("flight-2026-11-17_08-00-02-MobileAlpha.mkv")); // told twice: once in the file
        r.linkLog(dir.resolve("link-20261117-080000.csv"));
        r.armed(start + 20_000);
        r.armed(start + 21_000); // within five seconds: not written again
        r.armed(start + 40_000);
        r.end(FlightRecord.LINK_LOST, start + 300_000);
        r.armed(start + 400_000); // after the end: nothing more

        FlightRecord back = FlightRecord.read(r.file());
        assertTrue(back.wasArmed());
        assertEquals(start + 20_000, back.armedFirstMs());
        assertEquals(start + 40_000, back.armedLastMs());
        assertTrue(back.finished());
        assertEquals(FlightRecord.LINK_LOST, back.ended());
        assertEquals(start + 300_000, back.endMs());
        assertEquals(2, back.videos().size());
        assertEquals("track-20261117-080001-MobileAlpha.gpx", back.track().getFileName().toString());
        assertEquals(1, Files.readAllLines(r.file()).stream().filter(l -> l.startsWith("armedlast=")).count());
    }

    @Test
    void aFlightWithoutANameIsCalledByItsAddress(@TempDir Path dir) {
        FlightRecord r = FlightRecord.start(dir, "", "192.0.2.7", "1.0.71", FlightFiles.at("2026-11-17T08:00:00"));
        assertEquals("192.0.2.7", r.label());
        assertEquals("ip192.0.2.7", FlightRecord.fileLabel(r.label()), "a file's name: never a digit after the stamp's dash");
        assertEquals("MobileAlpha", FlightRecord.fileLabel("MobileAlpha"));
        assertEquals("My_drone_2", FlightRecord.fileLabel(" My drone-2 "), "no dashes, no spaces");
        assertEquals("", FlightRecord.fileLabel(null));
        FlightRecord.flush();
    }

    @Test
    void theConnectionOpenNowIsNotAClosedApps(@TempDir Path dir) {
        FlightRecord r = FlightRecord.start(dir, "MobileAlpha", "192.0.2.7", "1.0.71", FlightFiles.at("2026-11-17T08:00:00"));
        try {
            FlightRecord.live(r);
            FlightRecord.flush();
            assertEquals(FlightRecord.FLYING, FlightRecord.read(r.file()).ended());
            assertTrue(FlightKeeper.recover(dir).isEmpty(), "the live flight is left alone");
            assertTrue(Files.isRegularFile(r.file()));
        } finally {
            FlightRecord.live(null);
        }
    }

    /** The lost flight: two connections ten seconds apart, the second one's video in parts. Each is its own flight. */
    @Test
    void connectionsCloseInTimeStayTwoFlightsWithAllTheirParts(@TempDir Path root) throws IOException {
        Path tracks = Files.createDirectories(root.resolve("Tracks")), videos = Files.createDirectories(root.resolve("Videos"));
        Path logs = Files.createDirectories(root.resolve("logs"));
        // 08:00:00: a short connection, armed for a moment
        FlightRecord first = FlightRecord.start(tracks, "MobileAlpha", "192.0.2.7", "1.0.71", FlightFiles.at("2026-11-17T08:00:00"));
        first.track(FlightFiles.gpx(tracks, "2026-11-17T08:00:00", 30, i -> HERE));
        first.video(FlightFiles.recording(videos, "2026-11-17T08:00:01", 28, i -> row(true)));
        first.armed(FlightFiles.at("2026-11-17T08:00:05"));
        first.end(FlightRecord.DISCONNECT, FlightFiles.at("2026-11-17T08:00:30"));
        // 08:00:40: the flight that was lost - within the 20 s that joined files before
        FlightRecord lost = FlightRecord.start(tracks, "MobileAlpha", "192.0.2.7", "1.0.71", FlightFiles.at("2026-11-17T08:00:40"));
        Path track = rename(FlightFiles.gpx(tracks, "2026-11-17T08:00:41", 600, i -> HERE), "track-20261117-080041-MobileAlpha.gpx");
        lost.track(track);
        Path part1 = rename(FlightFiles.recording(videos, "2026-11-17T08:00:42", 100, i -> row(true)), "flight-2026-11-17_08-00-42-MobileAlpha.mkv");
        Files.move(videos.resolve("flight-2026-11-17_08-00-42.csv"), videos.resolve("flight-2026-11-17_08-00-42-MobileAlpha.csv"));
        Path part2 = FlightFiles.recording(videos, "2026-11-17T08:02:23", 200, i -> row(true));
        Path part3 = FlightFiles.recording(videos, "2026-11-17T08:05:44", 290, i -> row(true));
        lost.video(part1);
        lost.video(part2);
        lost.video(part3);
        lost.linkLog(FlightFiles.linkLog(logs, "2026-11-17T08:00:40", 600, "MobileAlpha"));
        lost.armed(FlightFiles.at("2026-11-17T08:00:50"));
        lost.end(FlightRecord.LINK_LOST, FlightFiles.at("2026-11-17T08:10:41"));
        FlightRecord.flush();

        List<FlightLibrary.Flight> flights = FlightLibrary.scan(tracks, videos, logs);
        assertEquals(2, flights.size(), "one flight per connection, however close in time");
        FlightLibrary.Flight f = flights.get(0); // newest first
        assertEquals("MobileAlpha", f.drone());
        assertEquals("LINK LOST", f.endMark());
        assertEquals(track, f.track());
        assertEquals(List.of(part1, part2, part3), f.parts().stream().map(FlightLibrary.Part::video).toList(), "every part, in order");
        assertEquals(FlightFiles.at("2026-11-17T08:05:44"), f.parts().get(2).startMs());
        assertNotNull(f.parts().get(0).telemetry(), "the figures beside the renamed part");
        assertTrue(f.files().containsAll(List.of(track, part1, part2, part3, lost.file())), "EXPORT and DELETE take all of it");
        assertNull(flights.get(1).endMark(), "ended by DISCONNECT: nothing to say");

        // the figures of all the parts on one clock: the last part's rows are the flight's last minutes
        FlightData d = FlightData.load(f);
        assertEquals(f.parts().get(0).startMs(), d.telemetry.get(0).timeMs());
        assertTrue(d.telemetry.get(d.telemetry.size() - 1).timeMs() >= FlightFiles.at("2026-11-17T08:10:30"));

        // DELETE takes every file of the flight; the other flight is untouched
        assertTrue(FlightData.delete(f).isEmpty());
        assertFalse(Files.exists(part3));
        assertFalse(Files.exists(lost.file()));
        assertEquals(1, FlightLibrary.scan(tracks, videos, logs).size());
    }

    @Test
    void aConnectionWithoutArmingIsNoFlightAndItsFilesGo(@TempDir Path root) throws IOException {
        Path tracks = Files.createDirectories(root.resolve("Tracks")), videos = Files.createDirectories(root.resolve("Videos"));
        Path logs = Files.createDirectories(root.resolve("logs"));
        FlightRecord r = FlightRecord.start(tracks, "MobileAlpha", "192.0.2.7", "1.0.71", FlightFiles.at("2026-11-17T09:00:00"));
        Path track = FlightFiles.gpx(tracks, "2026-11-17T09:00:01", 60, i -> new FlightFiles.Pt(60.0, 10.0, 100, false));
        Path video = FlightFiles.recording(videos, "2026-11-17T09:00:02", 55, i -> row(false));
        Path osd = Files.write(videos.resolve("flight-2026-11-17_09-00-02-osd.mkv"), new byte[0]);
        Path link = FlightFiles.linkLog(logs, "2026-11-17T09:00:00", 60, "MobileAlpha");
        r.track(track);
        r.video(video);
        r.linkLog(link);
        r.end(FlightRecord.DISCONNECT, FlightFiles.at("2026-11-17T09:01:00"));
        FlightRecord.flush();
        assertTrue(FlightLibrary.scan(tracks, videos, logs).isEmpty(), "never armed: not a flight to list, and not put together by time either");

        assertEquals(5, FlightKeeper.deleteUnarmed(FlightRecord.read(r.file()), track, List.of(video)));
        for (Path gone : new Path[]{track, video, osd, videos.resolve("flight-2026-11-17_09-00-02.csv"), r.file()}) {
            assertFalse(Files.exists(gone), gone.getFileName().toString());
        }
        assertTrue(Files.exists(link), "the link log is the app's own diagnosis: it stays, sorted out with the others");
    }

    @Test
    void anArmedFlightIsNeverDeletedAsUnarmed(@TempDir Path dir) throws IOException {
        FlightRecord r = FlightRecord.start(dir, "MobileAlpha", "192.0.2.7", "1.0.71", FlightFiles.at("2026-11-17T09:00:00"));
        Path track = FlightFiles.gpx(dir, "2026-11-17T09:00:01", 10, i -> HERE);
        r.track(track);
        r.armed(FlightFiles.at("2026-11-17T09:00:03"));
        r.end(FlightRecord.DISCONNECT, FlightFiles.at("2026-11-17T09:00:11"));
        assertEquals(0, FlightKeeper.deleteUnarmed(r, track, List.of()));
        assertTrue(Files.exists(track));
    }

    /** The PC restarted for an update while the lost drone's path was on the screen. */
    @Test
    void aFlightTheAppNeverClosedIsKeptAtTheNextStart(@TempDir Path root) throws IOException {
        Path tracks = Files.createDirectories(root.resolve("Tracks")), videos = Files.createDirectories(root.resolve("Videos"));
        FlightRecord armed = FlightRecord.start(tracks, "MobileAlpha", "192.0.2.7", "1.0.71", FlightFiles.at("2026-11-17T10:00:00"));
        Path track = FlightFiles.gpx(tracks, "2026-11-17T10:00:01", 120, i -> HERE);
        armed.track(track);
        armed.video(FlightFiles.recording(videos, "2026-11-17T10:00:02", 118, i -> row(true)));
        armed.armed(FlightFiles.at("2026-11-17T10:00:10"));
        armed.armed(FlightFiles.at("2026-11-17T10:01:50"));
        // ... and another connection that was open, never armed, when the PC went down
        FlightRecord idle = FlightRecord.start(tracks, "MobileBravo", "192.0.2.8", "1.0.71", FlightFiles.at("2026-11-17T11:00:00"));
        Path idleTrack = FlightFiles.gpx(tracks, "2026-11-17T11:00:01", 20, i -> new FlightFiles.Pt(60.0, 10.0, 100, false));
        idle.track(idleTrack);
        FlightRecord.flush();

        List<FlightRecord> kept = FlightKeeper.recover(tracks);
        assertEquals(1, kept.size());
        assertEquals("MobileAlpha", kept.get(0).drone());
        assertEquals(track, kept.get(0).track());
        FlightRecord after = FlightRecord.read(armed.file());
        assertTrue(after.finished(), "closed now: found once, not at every start");
        assertEquals(FlightRecord.APP_CLOSED, after.ended());
        assertEquals(FlightFiles.at("2026-11-17T10:01:50"), after.endMs(), "ended when it was last seen armed");
        assertFalse(Files.exists(idleTrack), "never armed: gone, as at its DISCONNECT");
        assertFalse(Files.exists(idle.file()));
        assertTrue(FlightKeeper.recover(tracks).isEmpty(), "nothing left to recover");

        List<FlightLibrary.Flight> flights = FlightLibrary.scan(tracks, videos, null);
        assertEquals(1, flights.size());
        assertEquals("APP CLOSED", flights.get(0).endMark());
        assertEquals("MobileAlpha", flights.get(0).drone());
    }

    @Test
    void olderFilesWithoutARecordArePutTogetherByTimeAsBefore(@TempDir Path root) throws IOException {
        Path tracks = Files.createDirectories(root.resolve("Tracks")), videos = Files.createDirectories(root.resolve("Videos"));
        Path logs = Files.createDirectories(root.resolve("logs"));
        Path track = FlightFiles.gpx(tracks, "2026-11-08T14:05:00", 60, i -> HERE);
        Path video = FlightFiles.recording(videos, "2026-11-08T14:05:02", 55, i -> row(true));
        FlightFiles.linkLog(logs, "2026-11-08T14:04:50", 70, "MobileAlpha");
        List<FlightLibrary.Flight> flights = FlightLibrary.scan(tracks, videos, logs);
        assertEquals(1, flights.size());
        FlightLibrary.Flight f = flights.get(0);
        assertEquals(track, f.track());
        assertEquals(video, f.video());
        assertEquals("MobileAlpha", f.drone(), "from the link log's line, as before");
        assertNull(f.endMark());
        assertNull(f.record());
        assertEquals(1, f.parts().size());
    }

    @Test
    void fileNamesWithTheDronesNameAreStillFound(@TempDir Path root) throws IOException {
        Path tracks = Files.createDirectories(root.resolve("Tracks")), videos = Files.createDirectories(root.resolve("Videos"));
        Path track = rename(FlightFiles.gpx(tracks, "2026-11-17T12:00:00", 30, i -> HERE), "track-20261117-120000-MobileAlpha.gpx");
        Path video = rename(FlightFiles.recording(videos, "2026-11-17T12:00:01", 28, i -> row(true)), "flight-2026-11-17_12-00-01-ip192.0.2.7-2.mkv");
        Files.write(videos.resolve("flight-2026-11-17_12-00-01-ip192.0.2.7-2-osd.mkv"), new byte[0]);
        FlightLibrary.Item t = FlightLibrary.trackItem(track), v = FlightLibrary.videoItem(video);
        assertNotNull(t);
        assertNotNull(v);
        assertEquals(FlightFiles.at("2026-11-17T12:00:01"), v.startMs());
        assertNull(FlightLibrary.videoItem(videos.resolve("flight-2026-11-17_12-00-01-ip192.0.2.7-2-osd.mkv")), "the copy with the OSD is not a recording of its own");
    }
}
