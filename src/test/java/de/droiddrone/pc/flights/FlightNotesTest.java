package de.droiddrone.pc.flights;

import de.droiddrone.pc.flights.FlightFiles.Pt;
import de.droiddrone.pc.flights.FlightFiles.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The pilot's bookmarks and note: a file beside the track that reads back, survives being cut, and goes where the flight goes. */
class FlightNotesTest {
    static final String T0 = "2026-11-08T14:05:00";

    @TempDir
    Path root;

    private Path dir(String name) throws IOException {
        return Files.createDirectories(root.resolve(name));
    }

    private FlightLibrary.Flight flight() throws IOException {
        List<FlightLibrary.Flight> flights = FlightLibrary.scan(dir("Tracks"), dir("Videos"), dir("logs"));
        assertEquals(1, flights.size());
        return flights.get(0);
    }

    private FlightLibrary.Flight wholeFlight() throws IOException {
        FlightFiles.gpx(dir("Tracks"), T0, 60, s -> new Pt(59.91 + s * 0.0001, 10.73, 100, s > 0));
        FlightFiles.recording(dir("Videos"), T0, 55, i -> new Row(24, 4.0, 59.91, 10.73, 0, 10, true, 40));
        FlightFiles.linkLog(dir("logs"), "2026-11-08T14:04:55", 70, "MobileAlpha");
        return flight();
    }

    @Test
    void bookmarksAndTheNoteRoundTrip() throws IOException {
        FlightLibrary.Flight f = wholeFlight();
        FlightNotes notes = FlightNotes.load(f);
        assertTrue(notes.isEmpty(), "a flight has none until the pilot writes some");
        assertEquals(dir("Tracks").resolve("track-20261108-140500.notes.txt"), notes.file(), "beside the track, named after it");
        assertFalse(Files.exists(notes.file()));

        notes.addBookmark(241_000, "Video froze");
        FlightNotes.Bookmark car = notes.addBookmark(83_500, "Car at\tthe gate\r\n");
        notes.addBookmark(83_500, "");
        notes.addBookmark(-40, "Before the start");
        notes.setNote("\r\nWind from the west.\r\n\r\n  Stronger than forecast.\n\n");
        assertEquals("Car at the gate", car.text(), "a bookmark is one line");
        assertEquals(List.of(0L, 83_500L, 83_500L, 241_000L), notes.bookmarks().stream().map(FlightNotes.Bookmark::offsetMs).toList(),
                "in time order; the same moment as added");
        assertFalse(Files.exists(notes.file()), "nothing is written until save");
        notes.save();

        assertEquals("0\tBefore the start\n83500\tCar at the gate\n83500\t\n241000\tVideo froze\n\nWind from the west.\n\n  Stronger than forecast.\n",
                Files.readString(notes.file(), StandardCharsets.UTF_8));
        FlightNotes again = FlightNotes.load(f);
        assertEquals(notes.bookmarks(), again.bookmarks());
        assertEquals("Wind from the west.\n\n  Stronger than forecast.", again.note());

        assertTrue(again.removeBookmark(car));
        assertFalse(again.removeBookmark(car), "it is gone");
        again.setNote(null);
        again.save();
        FlightNotes third = FlightNotes.load(f);
        assertEquals(List.of("Before the start", "", "Video froze"), third.bookmarks().stream().map(FlightNotes.Bookmark::text).toList());
        assertEquals("", third.note());

        // a note alone, and one whose first line looks like a bookmark
        for (FlightNotes.Bookmark b : third.bookmarks()) third.removeBookmark(b);
        third.setNote("120\tis not a bookmark\nsecond line");
        third.save();
        assertEquals("\n120\tis not a bookmark\nsecond line\n", Files.readString(third.file(), StandardCharsets.UTF_8));
        FlightNotes fourth = FlightNotes.load(f);
        assertTrue(fourth.bookmarks().isEmpty());
        assertEquals("120\tis not a bookmark\nsecond line", fourth.note());

        // nothing left: no file
        fourth.setNote("  \n");
        assertTrue(fourth.isEmpty());
        fourth.save();
        assertFalse(Files.exists(fourth.file()), "a flight without bookmarks or a note has no notes file");
    }

    @Test
    void aTornFileReadsAsFarAsItMakesSense() throws IOException {
        Path file = dir("Tracks").resolve("track-20261108-140500.notes.txt");

        Files.writeString(file, "83500\tCar at the gate\n2410", StandardCharsets.UTF_8); // cut inside the second bookmark's time
        FlightNotes n = FlightNotes.load(file);
        assertEquals(List.of(new FlightNotes.Bookmark(83_500, "Car at the gate")), n.bookmarks());
        assertEquals("", n.note(), "half a bookmark is no note");

        Files.writeString(file, "83500\tCar at the gate\n241000\tVideo fro", StandardCharsets.UTF_8); // cut inside its text
        n = FlightNotes.load(file);
        assertEquals(List.of(new FlightNotes.Bookmark(83_500, "Car at the gate"), new FlightNotes.Bookmark(241_000, "Video fro")), n.bookmarks());

        Files.writeString(file, "83500\tCar at the gate\n\nWind from the we", StandardCharsets.UTF_8); // cut inside the note
        n = FlightNotes.load(file);
        assertEquals(1, n.bookmarks().size());
        assertEquals("Wind from the we", n.note());

        Files.writeString(file, "﻿Just a note, written in an editor\r\nwith a second line\r\n", StandardCharsets.UTF_8); // by hand: no empty line first
        n = FlightNotes.load(file);
        assertTrue(n.bookmarks().isEmpty());
        assertEquals("Just a note, written in an editor\nwith a second line", n.note(), "kept, not dropped at the next save");

        Files.write(file, new byte[]{(byte) 0xff, (byte) 0xfe, 0, 0, '1', '2', '\t', (byte) 0xc3}); // not text at all
        n = assertDoesNotThrow(() -> FlightNotes.load(file));
        assertNotNull(n.note());

        Files.write(file, new byte[0]);
        assertTrue(FlightNotes.load(file).isEmpty());
        assertTrue(FlightNotes.load(root.resolve("nowhere").resolve("track-x.notes.txt")).isEmpty(), "no file: no notes");
        assertTrue(FlightNotes.load((Path) null).isEmpty());
    }

    @Test
    void aSaveIsWholeOrNotAtAll() throws IOException {
        Path file = dir("Tracks").resolve("track-20261108-140500.notes.txt");
        FlightNotes notes = FlightNotes.load(file);
        notes.addBookmark(1000, "First");
        notes.save();
        String first = Files.readString(file, StandardCharsets.UTF_8);
        try (var s = Files.list(file.getParent())) {
            assertEquals(List.of(file), s.toList(), "the file written beside it is gone: moved into place");
        }

        // the next save can't write its file (a folder stands where it goes): the notes on disk stay as they were, whole
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Files.createDirectory(part);
        notes.addBookmark(2000, "Second");
        assertThrows(IOException.class, notes::save);
        assertEquals(first, Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(1, FlightNotes.load(file).bookmarks().size());

        Files.delete(part);
        notes.save();
        assertEquals(2, FlightNotes.load(file).bookmarks().size(), "what was added is still there to save");
    }

    @Test
    void theNotesGoWithTheFlightOnExportAndDelete() throws IOException {
        FlightLibrary.Flight f = wholeFlight();
        assertEquals(4, f.files().size(), "track, recording, figures, link log: no notes yet");
        FlightNotes notes = FlightNotes.load(f);
        notes.addBookmark(12_000, "Tower");
        notes.setNote("Landed long.");
        notes.save();

        assertEquals(1, FlightLibrary.scan(dir("Tracks"), dir("Videos"), dir("logs")).size(), "the notes file is no flight of its own");
        assertEquals(5, f.files().size());
        assertTrue(f.files().contains(notes.file()));

        Path out = FlightData.export(f, dir("Exports"));
        Path copy = out.resolve("track-20261108-140500.notes.txt");
        assertEquals(Files.readString(notes.file(), StandardCharsets.UTF_8), Files.readString(copy, StandardCharsets.UTF_8));
        try (var s = Files.list(out)) {
            assertEquals(5, s.count());
        }

        assertTrue(FlightData.delete(f).isEmpty());
        assertFalse(Files.exists(notes.file()), "deleted with the flight");
        assertTrue(Files.exists(copy), "the export stays");
        assertTrue(FlightLibrary.scan(dir("Tracks"), dir("Videos"), dir("logs")).isEmpty());
    }

    @Test
    void aFlightWithOnlyARecordingKeepsItsNotesBesideThat() throws IOException {
        FlightFiles.recording(dir("Videos"), T0, 20, i -> new Row(24, 4.0, 59.91, 10.73, 0, 10, true, 40));
        FlightLibrary.Flight f = flight();
        assertNull(f.track());
        assertEquals(dir("Videos").resolve("flight-2026-11-08_14-05-00.notes.txt"), f.notesFile());
        FlightNotes notes = FlightNotes.load(f);
        notes.setNote("No GPS.");
        notes.save();
        FlightLibrary.Flight again = flight();
        assertEquals("No GPS.", FlightNotes.load(again).note());
        assertTrue(again.files().contains(f.notesFile()));
        assertNull(new FlightLibrary.Flight(0, 1, null, null, null, null, null, null, Double.NaN).notesFile(), "nothing to keep them beside");
    }
}
