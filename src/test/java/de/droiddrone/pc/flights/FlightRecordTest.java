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

    /**
     * F-106 (the test bench's full disk, 2026-11-24): each try of the recording on the full disk was named in the
     * record - PREVIOUS FLIGHTS had five parts of no byte. A part deleted for having nothing in it is left out, also
     * when the record is read back from the disk.
     */
    @Test
    void aPartDeletedForHavingNothingInItIsNoPartOfTheFlight(@TempDir Path dir) {
        long start = FlightFiles.at("2026-11-17T08:00:00");
        FlightRecord r = FlightRecord.start(dir, "MobileAlpha", "192.0.2.7", "1.0.71", start);
        Path kept = dir.resolve("flight-2026-11-17_08-00-02-MobileAlpha.mkv"), empty = dir.resolve("flight-2026-11-17_08-01-38-MobileAlpha.mkv");
        r.video(kept);
        r.video(empty);
        r.videoDropped(empty);
        r.videoDropped(dir.resolve("flight-2026-11-17_08-09-00-MobileAlpha.mkv")); // never told: nothing written
        assertEquals(List.of(kept), r.videos());
        r.armed(start + 20_000);
        r.end(FlightRecord.DISCONNECT, start + 300_000);
        assertEquals(List.of(kept), FlightRecord.read(r.file()).videos(), "read back from the disk");
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

    /**
     * A second copy of the app started while the first has a drone connected (a second double-click on the shortcut).
     * Until 2026-11-20 its start-up took the first one's open connection for a closed app's: not armed yet - track
     * and recording deleted under the flight about to be flown; armed - "APP CLOSED" written into it.
     */
    @Test
    void aFlightAnotherCopyOfTheAppHasOpenIsLeftAlone(@TempDir Path root) throws IOException {
        Path tracks = Files.createDirectories(root.resolve("Tracks"));
        FlightRecord waiting = FlightRecord.start(tracks, "MobileAlpha", "192.0.2.7", "1.0.72", FlightFiles.at("2026-11-20T09:00:00"));
        Path waitingTrack = FlightFiles.gpx(tracks, "2026-11-20T09:00:01", 20, i -> new FlightFiles.Pt(60.0, 10.0, 100, false));
        waiting.track(waitingTrack);
        FlightRecord flying = FlightRecord.start(tracks, "MobileBravo", "192.0.2.8", "1.0.72", FlightFiles.at("2026-11-20T09:10:00"));
        Path flyingTrack = FlightFiles.gpx(tracks, "2026-11-20T09:10:01", 20, i -> HERE);
        flying.track(flyingTrack);
        flying.armed(FlightFiles.at("2026-11-20T09:10:05"));
        FlightRecord.flush();
        FlightRecord.live(null); // the copy starting up knows of no connection of its own

        assertTrue(FlightRecord.read(waiting.file()).heldElsewhere(), "the first copy holds its record");
        assertTrue(FlightKeeper.recover(tracks).isEmpty(), "nothing of a running copy's is recovered");
        assertTrue(Files.exists(waitingTrack), "the track of the connection waiting to be armed is still there");
        assertTrue(Files.exists(waiting.file()));
        assertFalse(FlightRecord.read(flying.file()).finished(), "and no end is written into the flight being flown");

        // the first copy disconnects, as ever: its own files are its own to end
        flying.end(FlightRecord.DISCONNECT, FlightFiles.at("2026-11-20T09:10:30"));
        assertFalse(Files.exists(FlightRecord.openMark(flying.file())), "the mark goes with the connection");
        assertFalse(FlightRecord.read(flying.file()).heldElsewhere());
        // ... and is then killed with the other connection open: now that one is a closed app's
        waiting.abandon();
        assertFalse(FlightRecord.read(waiting.file()).heldElsewhere(), "a mark nobody holds is a closed app's");
        assertTrue(FlightKeeper.recover(tracks).isEmpty());
        assertFalse(Files.exists(waitingTrack), "never armed, its app gone: its files go as before");
        assertFalse(Files.exists(waiting.file()));
        assertFalse(Files.exists(FlightRecord.openMark(waiting.file())), "and the mark with them");
        assertTrue(Files.exists(flyingTrack));
    }

    /** The one "armed=" line never reached the disk; the "last seen armed" lines after it did; then the app was killed. */
    @Test
    void lastSeenArmedAloneSaysTheFlightWasArmed(@TempDir Path root) throws IOException {
        Path tracks = Files.createDirectories(root.resolve("Tracks"));
        Path track = FlightFiles.gpx(tracks, "2026-11-20T10:00:01", 60, i -> new FlightFiles.Pt(60.0, 10.0, 100, false));
        Path file = tracks.resolve("flight-20261120-100000.record");
        Files.writeString(file, "drone=MobileAlpha\naddress=192.0.2.7\napp=1.0.72\nstart=" + FlightFiles.at("2026-11-20T10:00:00") + "\ntrack=" + track.toAbsolutePath()
                + "\narmedlast=" + FlightFiles.at("2026-11-20T10:00:20") + "\narmedlast=" + FlightFiles.at("2026-11-20T10:00:55") + "\n");
        FlightRecord read = FlightRecord.read(file);
        assertTrue(read.wasArmed(), "last seen armed is armed");
        assertEquals(FlightFiles.at("2026-11-20T10:00:20"), read.armedFirstMs());

        List<FlightRecord> kept = FlightKeeper.recover(tracks);
        assertEquals(1, kept.size(), "kept at the next start, not deleted as never armed");
        assertTrue(Files.exists(track));
        assertEquals(FlightFiles.at("2026-11-20T10:00:55"), FlightRecord.read(file).endMs());
    }

    /**
     * The window froze before the arming (it writes the "armed" line) while the sticks and the logs ran on: nothing
     * in the record says armed, the app was killed. Its link log and its track say it: the flight is kept.
     */
    @Test
    void aKilledFlightWhoseOwnFilesSayArmedIsKept(@TempDir Path root) throws IOException {
        Path tracks = Files.createDirectories(root.resolve("Tracks")), logs = Files.createDirectories(root.resolve("logs"));
        // by its link log (FlightFiles' rows are armed)
        FlightRecord byLog = FlightRecord.start(tracks, "MobileAlpha", "192.0.2.7", "1.0.72", FlightFiles.at("2026-11-20T11:00:00"));
        Path logTrack = FlightFiles.gpx(tracks, "2026-11-20T11:00:01", 30, i -> new FlightFiles.Pt(60.0, 10.0, 100, false));
        byLog.track(logTrack);
        byLog.linkLog(FlightFiles.linkLog(logs, "2026-11-20T11:00:00", 30, "MobileAlpha"));
        // by its track
        FlightRecord byTrack = FlightRecord.start(tracks, "MobileBravo", "192.0.2.8", "1.0.72", FlightFiles.at("2026-11-20T11:10:00"));
        Path armedTrack = FlightFiles.gpx(tracks, "2026-11-20T11:10:01", 30, i -> new FlightFiles.Pt(60.0, 10.0, 100, i > 5));
        byTrack.track(armedTrack);
        // and one whose files say disarmed all the way
        FlightRecord idle = FlightRecord.start(tracks, "MobileCharlie", "192.0.2.9", "1.0.72", FlightFiles.at("2026-11-20T11:20:00"));
        Path idleTrack = FlightFiles.gpx(tracks, "2026-11-20T11:20:01", 30, i -> new FlightFiles.Pt(60.0, 10.0, 100, false));
        idle.track(idleTrack);
        Path idleLog = Files.writeString(logs.resolve("link-20261120-112000.csv"), FlightFiles.LINK_HEADER + "\n11:20:01.000,LINK READY,48,25,4,10,100,20,0,0,0,,\n");
        idle.linkLog(idleLog);
        FlightRecord.flush();
        for (FlightRecord r : new FlightRecord[]{byLog, byTrack, idle}) r.abandon();

        List<FlightRecord> kept = FlightKeeper.recover(tracks);
        assertEquals(List.of("MobileAlpha", "MobileBravo"), kept.stream().map(FlightRecord::drone).toList());
        assertTrue(Files.exists(logTrack) && Files.exists(armedTrack), "both kept with their tracks");
        assertTrue(FlightRecord.read(byLog.file()).wasArmed() && FlightRecord.read(byLog.file()).finished(), "and the record says so from now on");
        assertFalse(Files.exists(idleTrack), "disarmed all the way: gone as before");
        assertTrue(Files.exists(idleLog), "(its link log stays, as ever)");
    }

    /** No GPS and the recording off or failed: an armed flight of which only the link log is left. */
    @Test
    void anArmedFlightWithOnlyItsLinkLogIsListedAndItsLogIsKept(@TempDir Path root) throws IOException {
        Path tracks = Files.createDirectories(root.resolve("Tracks")), videos = Files.createDirectories(root.resolve("Videos"));
        Path logs = Files.createDirectories(root.resolve("logs"));
        FlightRecord r = FlightRecord.start(tracks, "MobileAlpha", "192.0.2.7", "1.0.72", FlightFiles.at("2026-11-20T12:00:00"));
        Path link = FlightFiles.linkLog(logs, "2026-11-20T12:00:00", 90, "MobileAlpha");
        r.linkLog(link);
        r.armed(FlightFiles.at("2026-11-20T12:00:10"));
        r.end(FlightRecord.LINK_LOST, FlightFiles.at("2026-11-20T12:01:30"));

        List<FlightLibrary.Flight> flights = FlightLibrary.scan(tracks, videos, logs);
        assertEquals(1, flights.size(), "an armed flight is a flight, whatever is left of it");
        FlightLibrary.Flight f = flights.get(0);
        assertNull(f.track());
        assertNull(f.video());
        assertEquals(link, f.linkLog());
        assertEquals("LINK LOST", f.endMark());
        assertEquals("MobileAlpha", f.drone());
        assertEquals(FlightFiles.at("2026-11-20T12:00:00"), f.startMs());
        assertEquals(90_000, f.durationMs(), 1000);
        assertTrue(FlightLibrary.keptLinkLogs(tracks, videos, logs).contains(link.toAbsolutePath().normalize()), "its link log is a kept flight's");
        assertEquals(900, FlightData.load(f).link.size(), "and reads as any flight's");
        assertEquals(List.of(link, r.file()), f.files());
    }

    /** DELETE on the flight being flown took its track, record and link log away under it. */
    @Test
    void theFlightBeingFlownNowIsNotDeleted(@TempDir Path root) throws IOException {
        Path tracks = Files.createDirectories(root.resolve("Tracks")), videos = Files.createDirectories(root.resolve("Videos"));
        FlightRecord r = FlightRecord.start(tracks, "MobileAlpha", "192.0.2.7", "1.0.72", FlightFiles.at("2026-11-20T13:00:00"));
        Path track = FlightFiles.gpx(tracks, "2026-11-20T13:00:01", 30, i -> HERE);
        r.track(track);
        r.armed(FlightFiles.at("2026-11-20T13:00:05"));
        FlightRecord.flush();
        FlightRecord.live(r);
        try {
            FlightLibrary.Flight now = FlightLibrary.scan(tracks, videos, null).get(0);
            assertEquals(FlightRecord.FLYING, now.ended());
            assertFalse(FlightData.deletable(now));
            assertEquals(now.files(), FlightData.delete(now), "nothing of it goes");
            assertTrue(Files.exists(track) && Files.exists(r.file()));
        } finally {
            FlightRecord.live(null);
        }
        r.end(FlightRecord.DISCONNECT, FlightFiles.at("2026-11-20T13:00:31"));
        FlightLibrary.Flight landed = FlightLibrary.scan(tracks, videos, null).get(0);
        assertTrue(FlightData.deletable(landed));
        assertTrue(FlightData.delete(landed).isEmpty(), "once it is over it is deleted as any other");
        assertFalse(Files.exists(track));
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
        armed.abandon(); // the PC went down: nobody holds them any more
        idle.abandon();

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
