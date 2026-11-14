package de.droiddrone.pc.flights;

import de.droiddrone.pc.flights.FlightFiles.Pt;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.overwatch.ClipIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** A flight's marks: its bookmarks, the pins dropped while it flew and the overwatch clips that started meanwhile, in order. */
class FlightMarksTest {
    static final String T0 = "2026-11-08T14:05:00";
    static final long START = FlightFiles.at(T0);

    @TempDir
    Path root;

    /** A minute's flight. */
    private FlightData flight() throws IOException {
        Path tracks = Files.createDirectories(root.resolve("Tracks"));
        FlightFiles.gpx(tracks, T0, 61, s -> new Pt(59.91 + s * 0.0001, 10.73, 100, s > 0));
        FlightLibrary.Flight f = FlightLibrary.scan(tracks, null, null).get(0);
        assertEquals(START, f.startMs());
        assertEquals(START + 60_000, f.endMs());
        return FlightData.load(f);
    }

    private static String pin(String name, double lat, long createdMs, int rough) {
        return name + "\t" + lat + "\t10.7400000\t" + createdMs + "\t" + rough + "\n";
    }

    private void clip(ClipIndex index, String drone, long startedAt, Double lat) throws IOException {
        Properties m = new Properties();
        m.setProperty("drone", drone);
        m.setProperty("startedAt", Long.toString(startedAt));
        if (lat != null) {
            m.setProperty("lat", Double.toString(lat));
            m.setProperty("lon", "10.75");
        }
        index.save(drone, index.newId(drone, startedAt), m);
    }

    @Test
    void bookmarksPinsAndClipsOfTheFlightInTheOrderTheyCame() throws IOException {
        FlightData d = flight();
        FlightNotes notes = FlightNotes.load(d.flight);
        notes.addBookmark(30_000, "Turned back");
        notes.addBookmark(5_000, "");

        Path pinsFile = root.resolve("pins.txt");
        Files.writeString(pinsFile, pin("Before", 59.90, START - 3_600_000, 0) + pin("Car", 59.92, START + 10_000, 0)
                + "Old\t59.93\t10.74\n" // from before pins had a time
                + pin("Roof", 59.94, START + 30_000, 1) + pin("AtTheEnd", 59.95, START + 60_000, 0) + pin("After", 59.96, START + 60_001, 0), StandardCharsets.UTF_8);
        Pins pins = new Pins(pinsFile);
        assertEquals(6, pins.list().size());

        ClipIndex clips = new ClipIndex(root.resolve("Overwatch"));
        clip(clips, "MobileBravo", START + 20_000, 59.97);
        clip(clips, "MobileBravo", START - 600_000, null); // ten minutes before the flight
        clip(clips, "MobileBravo", START + 40_000, null);
        clips.hide("MobileBravo", ClipIndex.baseId(START + 40_000)); // REMOVE was pressed: off the list, so no mark
        clip(clips, "Mobile/Charlie", START + 50_000, null); // another phone's, in a folder named Mobile_Charlie
        clip(clips, "MobileDelta", START + 120_000, null); // a minute after it

        List<FlightMarks.Mark> marks = d.marks(notes, pins, clips);
        assertEquals(List.of("0:05 BOOKMARK BOOKMARK", "0:10 PIN Car", "0:20 CLIP MobileBravo", "0:30 BOOKMARK Turned back", "0:30 PIN Roof",
                        "0:50 CLIP Mobile/Charlie", "1:00 PIN AtTheEnd"),
                marks.stream().map(m -> m.timeText() + " " + m.kind().title + " " + m.label()).toList());
        assertEquals(List.of(5_000L, 10_000L, 20_000L, 30_000L, 30_000L, 50_000L, 60_000L), marks.stream().map(FlightMarks.Mark::offsetMs).toList());

        FlightMarks.Mark bookmark = marks.get(3), car = marks.get(1), roof = marks.get(4), bravo = marks.get(2), charlie = marks.get(5);
        assertEquals(new FlightNotes.Bookmark(30_000, "Turned back"), bookmark.bookmark());
        assertNull(bookmark.pin());
        assertNull(bookmark.clip());
        assertTrue(Double.isNaN(bookmark.lat()), "a bookmark is a moment, not a place");
        assertEquals("Car", car.pin().name());
        assertEquals(59.92, car.lat(), 1e-9);
        assertEquals(10.74, car.lon(), 1e-9);
        assertFalse(car.pin().rough());
        assertTrue(roof.pin().rough(), "a rough pin is a mark too, and says so");
        assertEquals(ClipIndex.baseId(START + 20_000), bravo.clip().id());
        assertEquals(59.97, bravo.lat(), 1e-9, "where the phone stood");
        assertEquals(10.75, bravo.lon(), 1e-9);
        assertTrue(Double.isNaN(charlie.lat()), "a clip without a place");
    }

    @Test
    void aFlightWithNothingMarkedHasNoMarks() throws IOException {
        FlightData d = flight();
        assertTrue(d.marks(null, null, null).isEmpty());
        assertTrue(d.marks(FlightNotes.load(d.flight), new Pins(null), new ClipIndex(root.resolve("no-such-folder"))).isEmpty());
        assertTrue(FlightMarks.of(d.flight, null, null, null).isEmpty());
        assertTrue(FlightMarks.clipsIn(null, 0, Long.MAX_VALUE).isEmpty());
    }

    @Test
    void aPinDroppedNowIsAMarkOfTheFlightGoingOnNow() {
        long now = System.currentTimeMillis();
        FlightLibrary.Flight f = new FlightLibrary.Flight(now - 60_000, now + 60_000, "MobileAlpha", null, null, null, null, null, Double.NaN);
        Pins pins = new Pins(null);
        pins.add("Gate", 59.9, 10.7);
        List<FlightMarks.Mark> marks = FlightMarks.of(f, null, pins.list(), List.of());
        assertEquals(1, marks.size());
        assertEquals("Gate", marks.get(0).label());
        assertEquals(60_000, marks.get(0).offsetMs(), 5_000);
    }
}
