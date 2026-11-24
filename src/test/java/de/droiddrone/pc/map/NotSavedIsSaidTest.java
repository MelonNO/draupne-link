package de.droiddrone.pc.map;

import de.droiddrone.pc.DiskSpace;
import de.droiddrone.pc.FcTelemetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A disk that is full or a folder that refuses, in the middle of a flight (a review of 2026-11-20): the track's file
 * ended there without a word - "always save a log" - and so did the map drawings. Now the pilot is told once when
 * the saving stops, and when it works again. The folder that refuses is played by a file standing where it should be.
 */
class NotSavedIsSaidTest {
    private static FcTelemetry.Gps fix(double lat, double lon, long at) {
        return new FcTelemetry.Gps(3, 12, 40, 100, 0, 0, lat, lon, 0, 0, 1.0, at);
    }

    @Test
    void aTrackThatCannotBeWrittenSaysSoOnceAndCarriesOnWhenItCan(@TempDir Path root) throws Exception {
        Path tracks = root.resolve("Tracks");
        Files.writeString(tracks, "a file where the folder should be");
        FlightTrack track = new FlightTrack(tracks);
        List<String> said = new CopyOnWriteArrayList<>();
        track.setProblemListener(said::add);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) track.sample(fix(59.9139 + i * 0.001, 10.7522, now - 5000 + i), true);
        track.flush();
        assertEquals(1, said.size(), "said once for three points: " + said);
        assertTrue(said.get(0).startsWith("TRACK NOT SAVED: "), said.get(0));
        assertEquals(3, track.points().size(), "the map in the window still has them");

        Files.delete(tracks); // room again
        track.sample(fix(59.9179, 10.7522, now - 4000), true);
        track.sample(fix(59.9189, 10.7522, now - 3000), true);
        track.flush();
        assertEquals(List.of("Track saved again (3 points missing)."), said.subList(1, said.size()));
        track.close();
        FlightTrack read = FlightTrack.read(track.file());
        assertEquals(2, read.points().size(), "the file has what came after, whole and readable");
    }

    /** A write that fails in the middle of the file: the next point is added to it, the head is not written twice. */
    @Test
    void aTrackWhoseFileRefusesForAWhileIsAddedToNotBegunAgain(@TempDir Path root) throws Exception {
        Path tracks = Files.createDirectories(root.resolve("Tracks"));
        FlightTrack track = new FlightTrack(tracks);
        List<String> said = new CopyOnWriteArrayList<>();
        track.setProblemListener(said::add);
        long now = System.currentTimeMillis();
        track.sample(fix(59.9139, 10.7522, now - 5000), true);
        track.flush();
        Path file = track.file();
        assertTrue(Files.exists(file));
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"), "a lock that stops a write is Windows' way");
        try (java.nio.channels.FileChannel other = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock lock = other.lock(0, Long.MAX_VALUE, false)) {
            track.sample(fix(59.9149, 10.7522, now - 4000), true);
            track.flush();
            assertEquals(1, said.size(), said.toString());
        }
        track.sample(fix(59.9159, 10.7522, now - 3000), true);
        track.flush();
        assertEquals(2, said.size(), said.toString());
        assertEquals("Track saved again (1 point missing).", said.get(1));
        track.close();
        String gpx = Files.readString(file);
        assertEquals(1, gpx.split("<gpx ", -1).length - 1, "one head");
        assertEquals(2, FlightTrack.read(file).points().size(), "the point before and the point after");
    }

    /**
     * A write that a full disk took part of (the test bench's full disk, 2026-11-24): "<trkpt lat="60.86" stayed in
     * the file and the next point came after it - this app read the track, a map program did not. Now what the failed
     * write left is taken off before the next point. The part of a line is played by this test writing it while the
     * file is locked (the lock makes the track's own write fail).
     */
    @Test
    void whatAFailedWriteLeftOfAPointIsTakenOffBeforeTheNext(@TempDir Path root) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"), "a lock that stops a write is Windows' way");
        Path tracks = Files.createDirectories(root.resolve("Tracks"));
        FlightTrack track = new FlightTrack(tracks);
        List<String> said = new CopyOnWriteArrayList<>();
        track.setProblemListener(said::add);
        long now = System.currentTimeMillis();
        track.sample(fix(59.9139, 10.7522, now - 5000), true);
        track.flush();
        Path file = track.file();
        try (java.nio.channels.FileChannel other = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock lock = other.lock(0, Long.MAX_VALUE, false)) {
            other.write(java.nio.ByteBuffer.wrap("   <trkpt lat=\"59.91".getBytes(java.nio.charset.StandardCharsets.UTF_8)), other.size());
            track.sample(fix(59.9149, 10.7522, now - 4000), true);
            track.flush();
            assertEquals(1, said.size(), said.toString());
        }
        track.sample(fix(59.9159, 10.7522, now - 3000), true);
        track.close();
        assertEquals("Track saved again (1 point missing).", said.get(1));
        assertNull(strictXml(file), "a whole GPX file, as a map program reads it");
        assertEquals(2, FlightTrack.read(file).points().size(), "the point before and the point after");
    }

    /** The disk full at the last point and room at the end of the flight: the file's end is written all the same. */
    @Test
    void aTrackWhoseLastWriteFailedIsEndedWholeAtTheClose(@TempDir Path root) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"), "a lock that stops a write is Windows' way");
        Path tracks = Files.createDirectories(root.resolve("Tracks"));
        FlightTrack track = new FlightTrack(tracks);
        long now = System.currentTimeMillis();
        track.sample(fix(59.9139, 10.7522, now - 5000), true);
        track.flush();
        Path file = track.file();
        try (java.nio.channels.FileChannel other = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock lock = other.lock(0, Long.MAX_VALUE, false)) {
            other.write(java.nio.ByteBuffer.wrap("   <trkpt lat=\"59.91".getBytes(java.nio.charset.StandardCharsets.UTF_8)), other.size());
            track.sample(fix(59.9149, 10.7522, now - 4000), true);
            track.flush();
        }
        track.close();
        assertNull(strictXml(file), "a whole GPX file, as a map program reads it");
        assertEquals(1, FlightTrack.read(file).points().size(), "the point that was written");
    }

    /** Null when a strict XML reader reads the file (as map programs do); else what it says. */
    private static String strictXml(Path file) {
        try {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.newDocumentBuilder().parse(file.toFile());
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    @Test
    void mapDrawingsThatCannotBeSavedAreSaidOnce(@TempDir Path root) throws Exception {
        Path inTheWay = Files.writeString(root.resolve("DRAUPNE Link"), "a file where the folder should be");
        MapGraphics graphics = new MapGraphics(inTheWay.resolve("graphics.kml"));
        List<String> said = new CopyOnWriteArrayList<>();
        graphics.onProblem(said::add);
        MapGraphics.LatLon a = new MapGraphics.LatLon(59.91, 10.73), b = new MapGraphics.LatLon(59.92, 10.74);
        graphics.add(MapGraphics.Item.line("Route 1", List.of(a, b), 0x3b82f6, 4));
        graphics.add(MapGraphics.Item.line("Route 2", List.of(b, a), 0x3b82f6, 4));
        assertEquals(1, said.size(), said.toString());
        assertTrue(said.get(0).startsWith("MAP DRAWINGS NOT SAVED: "), said.get(0));
        Files.delete(inTheWay);
        graphics.add(MapGraphics.Item.line("Route 3", List.of(a, b), 0x3b82f6, 4));
        assertEquals("Map drawings saved again.", said.get(1));
        assertEquals(3, new MapGraphics(inTheWay.resolve("graphics.kml")).list().size());
    }

    @Test
    void aNearlyFullDiskIsSaidWhenARecordingStarts(@TempDir Path dir) {
        assertNull(DiskSpace.warning(3_000_000_000L));
        assertNull(DiskSpace.warning(DiskSpace.LOW_BYTES));
        assertEquals("LOW DISK: 1.4 GB free. Recording and track may stop.", DiskSpace.warning(1_400_000_000L));
        assertEquals("LOW DISK: 0.0 GB free. Recording and track may stop.", DiskSpace.warning(0));
        assertNull(DiskSpace.warning(-1), "a disk that does not say is not warned about");
        assertNull(DiskSpace.warning(dir.resolve("not there")), "nor a folder that is not there");
    }
}
