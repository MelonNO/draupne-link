package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PinsTest {
    @Test
    void namesRunThroughTheAlphabetThenAlpha2() {
        Pins pins = new Pins(null);
        assertEquals("Alpha", pins.nextName());
        pins.add(null, 59.9, 10.7);
        assertEquals("Bravo", pins.nextName());
        for (int i = 1; i < 26; i++) pins.add(null, 59.9, 10.7 + i * 0.001);
        assertEquals(26, pins.list().size());
        assertEquals("Zulu", pins.list().get(25).name());
        assertEquals("Alpha2", pins.nextName(), "no Alpha1: Alpha2 follows Zulu");
        pins.add(null, 60, 10);
        assertEquals("Bravo2", pins.nextName());
        pins.remove(pins.list().get(1)); // Bravo gone: its name is free again
        assertEquals("Bravo", pins.nextName());
    }

    @Test
    void exportedAsGpxWaypoints(@TempDir Path dir) throws Exception {
        Pins pins = new Pins(null);
        pins.add("Car <van>", 59.9139, 10.7522);
        pins.add(null, 59.9339, 10.7522);
        String gpx = pins.toGpx();
        assertTrue(gpx.startsWith("<?xml"));
        assertTrue(gpx.contains("<wpt lat=\"59.9139000\" lon=\"10.7522000\"><name>Car &lt;van&gt;</name></wpt>"), gpx);
        assertTrue(gpx.contains("<name>Alpha</name>"));
        assertTrue(gpx.trim().endsWith("</gpx>"));
        Path file = dir.resolve("out").resolve("pins.gpx");
        pins.exportGpx(file);
        assertEquals(gpx, Files.readString(file));
        assertFalse(Files.exists(dir.resolve("out").resolve("pins.gpx.part")));
    }

    /** A disk that is full, a folder that refuses: the pins dropped all flight were gone at the next start, without a word. */
    @Test
    void pinsThatCannotBeSavedAreSaidOnceAndSoIsTheirBeingSavedAgain(@TempDir Path dir) throws Exception {
        Path inTheWay = Files.writeString(dir.resolve("DRAUPNE Link"), "a file where the folder should be");
        Path file = inTheWay.resolve("pins.txt");
        Pins pins = new Pins(file);
        java.util.List<String> said = new java.util.ArrayList<>();
        pins.onProblem(said::add);
        pins.add("Car", 59.9139, 10.7522);
        assertEquals(1, said.size(), said.toString());
        assertTrue(said.get(0).startsWith("PINS NOT SAVED: "), said.get(0));
        pins.add("Roof", 59.9140, 10.7523);
        assertEquals(1, said.size(), "said once, not at every pin: " + said);
        assertEquals(2, pins.list().size(), "they stay on the map for this session");

        Files.delete(inTheWay); // the disk has room again
        pins.add("Gate", 59.9141, 10.7524);
        assertEquals(java.util.List.of("Pins saved again."), said.subList(1, said.size()));
        assertEquals(3, new Pins(file).list().size(), "and all three are in the file");
    }

    /**
     * pins.txt held by another program for the moment the app starts. Until 2026-11-20 that was "a broken file": it
     * was renamed pins.txt.broken-... and the app started without the pilot's pins.
     */
    @Test
    void aPinsFileThatCannotBeReadJustNowIsLeftWhereItIsAndNeverWrittenOver(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"), "a lock that stops a read is Windows' way");
        Path file = dir.resolve("pins.txt");
        Pins before = new Pins(file);
        before.add("Car", 59.9139, 10.7522);
        before.add("Roof", 59.9140, 10.7523);
        String onDisk = Files.readString(file);
        Pins held;
        try (java.nio.channels.FileChannel other = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock lock = other.lock()) {
            held = new Pins(file);
            assertNotNull(held.notRead(), "the app knows it did not read them");
            assertNull(held.brokenMovedTo(), "not a broken file: not moved aside");
            assertTrue(held.list().isEmpty());
        }
        assertTrue(Files.exists(file), "pins.txt is where it was");
        held.add("New", 60, 10);
        assertEquals(onDisk, Files.readString(file), "and what it holds is not written over by a session that never read it");
        assertEquals(2, new Pins(file).list().size(), "at the next start they are all there");
        try (java.util.stream.Stream<Path> all = Files.list(dir)) {
            assertEquals(1, all.count(), "nothing beside it");
        }
    }

    @Test
    void renameRemoveNearestAndKeptOnDisk(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pins.txt");
        Pins pins = new Pins(file);
        Pins.Pin a = pins.add("", 59.9139, 10.7522);
        Pins.Pin b = pins.add("Landing field", 59.9339, 10.7522);
        assertEquals("Alpha", a.name());
        Pins.Pin renamed = pins.rename(a, "Car");
        assertEquals("Car", renamed.name());
        assertEquals(59.9139, renamed.lat(), 1e-9);
        assertEquals("Alpha", pins.nextName(), "Alpha is free again");
        assertEquals(b, pins.nearest(59.93, 10.75));
        assertEquals(renamed, pins.nearest(59.91, 10.75));

        assertTrue(Files.readString(file).contains("Landing field\t59.9339000\t10.7522000"));
        Pins again = new Pins(file);
        assertEquals(2, again.list().size());
        assertEquals("Car", again.list().get(0).name());
        again.remove(again.list().get(0));
        assertEquals(1, new Pins(file).list().size());
    }

    @Test
    void thePinsEndUpAsWaypointsInTheGpx(@TempDir Path dir) throws Exception {
        FlightTrack track = new FlightTrack(dir);
        track.sample(new de.droiddrone.pc.FcTelemetry.Gps(2, 12, 36.0, 120, -1, 0, 59.9139, 10.7522, -1, 90, 0.9, 1000), true);
        Pins pins = new Pins(null);
        pins.add("Car <&>", 59.9, 10.7);
        track.close(pins.list());
        String gpx = Files.readString(track.file());
        int wpt = gpx.indexOf("<wpt lat=\"59.9000000\" lon=\"10.7000000\"><name>Car &lt;&amp;&gt;</name></wpt>");
        assertTrue(wpt > 0, gpx);
        assertTrue(wpt < gpx.indexOf(" <trk>"), "waypoints before the track, as GPX wants");
        assertTrue(gpx.trim().endsWith("</gpx>"));
    }

    /**
     * A pin imported from someone's KML whose name runs over two lines (or a tab) is kept on one line of pins.txt: a
     * line break in it split the pin, and at the next start the half without numbers moved the whole file aside as broken.
     */
    @Test
    void aNameWithALineBreakIsSavedOnOneLine(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pins.txt");
        Pins pins = new Pins(file);
        pins.add("Road\r\nblock\tnorth", 59.9, 10.7);
        pins.rename(pins.add("Car", 59.8, 10.6), "Car\nwest");
        Pins again = new Pins(file);
        assertNull(again.brokenMovedTo(), "the file is read whole");
        assertEquals(2, again.list().size());
        assertEquals("Road block north", again.list().get(0).name());
        assertEquals("Car west", again.list().get(1).name());
    }
}
