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
}
