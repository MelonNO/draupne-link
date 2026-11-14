package de.droiddrone.pc.gui;

import de.droiddrone.pc.flights.FlightMarks;
import de.droiddrone.pc.flights.FlightNotes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The marks on PREVIOUS FLIGHTS' bar - the pilot's bookmarks, the pins dropped during the flight, the overwatch clips
 * of its time: where they stand, what they say, that a click on a sign goes to its moment and a click beside it moves
 * the bar as ever; bookmarks made, renamed and removed are in the flight's notes file at once, and so is the note.
 */
class DebriefMarksTest {
    @TempDir
    Path dir;

    private static MouseEvent mouse(PreviousFlightsDialog.Timeline bar, int id, int x, int y, int button) {
        int mask = id == MouseEvent.MOUSE_RELEASED ? 0 : button == MouseEvent.BUTTON3 ? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;
        return new MouseEvent(bar, id, 0, mask, x, y, 1, false, id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : button);
    }

    private static void press(PreviousFlightsDialog.Timeline bar, int x, int y) {
        bar.dispatchEvent(mouse(bar, MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1));
        bar.dispatchEvent(mouse(bar, MouseEvent.MOUSE_RELEASED, x, y, MouseEvent.BUTTON1));
    }

    /** The flight's time under a place on the bar, as a click there seeks. */
    private static long timeAt(PreviousFlightsDialog d, int x) {
        double k = Math.max(0, Math.min(1, (x - 8) / (double) (d.timeline.getWidth() - 16)));
        return d.data.startMs() + Math.round(k * (d.data.endMs() - d.data.startMs()));
    }

    private static FlightMarks.Mark mark(PreviousFlightsDialog d, FlightMarks.Kind kind, String label) {
        return d.marks.stream().filter(m -> m.kind() == kind && m.label().equals(label)).findFirst().orElseThrow();
    }

    @Test
    void theMarksStandAtTheirTimesEachWithItsOwnSign() throws Exception {
        try (DebriefFlight flights = new DebriefFlight(dir)) {
            PreviousFlightsDialog d = flights.open();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(DebriefFlight.START, d.data.startMs());
                assertEquals(DebriefFlight.LENGTH, d.data.endMs() - d.data.startMs());
                assertEquals(List.of("0:40  PIN Car", "1:40  CLIP MobileBravo", "2:00  PIN Roof"), d.marks.stream().map(PreviousFlightsDialog::markText).toList(),
                        "the pins dropped and the clip started while it flew; the pins of other times are nobody's");
                d.addBookmark(0, "");
                d.addBookmark(90_000, "Turned back");
                d.addBookmark(DebriefFlight.LENGTH, "Landed");
                assertEquals(List.of("0:00  BOOKMARK", "0:40  PIN Car", "1:30  Turned back", "1:40  CLIP MobileBravo", "2:00  PIN Roof", "3:00  Landed"),
                        d.marks.stream().map(PreviousFlightsDialog::markText).toList());

                Offscreen.layout(d, 1280, 760);
                PreviousFlightsDialog.Timeline bar = d.timeline;
                int w = bar.getWidth(), lane = PreviousFlightsDialog.Timeline.MARKS_TOP + PreviousFlightsDialog.Timeline.MARKS_H / 2;
                assertTrue(w > 600, "the bar has its width: " + w);
                assertEquals(new Point(8, lane), bar.markCentre(mark(d, FlightMarks.Kind.BOOKMARK, "BOOKMARK")), "the start: the bar's left end");
                assertEquals(new Point((int) Math.round(8 + (w - 16) / 2.0), lane), bar.markCentre(mark(d, FlightMarks.Kind.BOOKMARK, "Turned back")), "half way");
                assertEquals(new Point(w - 8, lane), bar.markCentre(mark(d, FlightMarks.Kind.BOOKMARK, "Landed")), "the end: its right end");
                for (FlightMarks.Mark m : d.marks) {
                    assertEquals((int) Math.round(8 + (w - 16) * (m.offsetMs() / (double) DebriefFlight.LENGTH)), bar.markCentre(m).x, PreviousFlightsDialog.markText(m));
                }

                // three signs, three colours: what is painted at each mark's middle is its kind's
                assertEquals(3, List.of(PreviousFlightsDialog.Timeline.markColor(FlightMarks.Kind.BOOKMARK),
                        PreviousFlightsDialog.Timeline.markColor(FlightMarks.Kind.PIN), PreviousFlightsDialog.Timeline.markColor(FlightMarks.Kind.CLIP)).stream().distinct().count());
                d.seek(DebriefFlight.START + 10_000); // the playhead away from every mark
                BufferedImage img = new BufferedImage(w, bar.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                bar.paint(g);
                g.dispose();
                for (FlightMarks.Mark m : d.marks) {
                    Point c = bar.markCentre(m);
                    int x = Math.max(9, Math.min(w - 10, c.x)); // (the signs at the two ends are half off the lines' box: inside it)
                    assertEquals(PreviousFlightsDialog.Timeline.markColor(m.kind()).getRGB() & 0xffffff, img.getRGB(x, c.y - 1) & 0xffffff,
                            PreviousFlightsDialog.markText(m) + " in its colour at " + c);
                }

                // pointing at a sign says when and what; beside it, nothing
                FlightMarks.Mark car = mark(d, FlightMarks.Kind.PIN, "Car");
                Point at = bar.markCentre(car);
                assertSame(car, bar.markAt(at.x, at.y));
                assertSame(car, bar.markAt(at.x + PreviousFlightsDialog.Timeline.MARK_REACH, at.y));
                assertEquals("0:40  PIN Car", bar.getToolTipText(mouse(bar, MouseEvent.MOUSE_MOVED, at.x, at.y, MouseEvent.NOBUTTON)));
                assertNull(bar.markAt(at.x + PreviousFlightsDialog.Timeline.MARK_REACH + 1, at.y), "just beside the sign");
                assertNull(bar.markAt(at.x, at.y + PreviousFlightsDialog.Timeline.MARKS_H), "under it, on the lines");
                assertNull(bar.markAt(at.x, 3), "over it, on the legend");
                assertNull(bar.getToolTipText(mouse(bar, MouseEvent.MOUSE_MOVED, at.x + 20, at.y, MouseEvent.NOBUTTON)));
            });
        }
    }

    @Test
    void aClickOnASignGoesToItsMomentAndOneBesideItMovesTheBarAsEver() throws Exception {
        try (DebriefFlight flights = new DebriefFlight(dir)) {
            PreviousFlightsDialog d = flights.open();
            SwingUtilities.invokeAndWait(() -> {
                Offscreen.layout(d, 1280, 760);
                PreviousFlightsDialog.Timeline bar = d.timeline;
                FlightMarks.Mark car = mark(d, FlightMarks.Kind.PIN, "Car"), clip = mark(d, FlightMarks.Kind.CLIP, "MobileBravo");
                Point at = bar.markCentre(car);
                long carTime = DebriefFlight.START + DebriefFlight.CAR_AT;
                assertNotEquals(carTime, timeAt(d, at.x), "the pin's moment is not what a click on the bar at its place gives");

                press(bar, at.x, at.y);
                assertEquals(carTime, d.position(), "to the mark's moment, to the millisecond");
                press(bar, at.x + 3, at.y + 4); // anywhere on the sign
                assertEquals(carTime, d.position());

                // beside the sign, in its lane: the bar's own seek, to where the mouse is
                int beside = at.x + PreviousFlightsDialog.Timeline.MARK_REACH + 4;
                press(bar, beside, at.y);
                assertEquals(timeAt(d, beside), d.position());
                assertNotEquals(carTime, d.position());
                // under the sign, on the lines; and on the link's row at the bottom
                press(bar, at.x, at.y + 30);
                assertEquals(timeAt(d, at.x), d.position());
                press(bar, 400, bar.getHeight() - 5);
                assertEquals(timeAt(d, 400), d.position());

                // a drag over the signs moves the bar all the way: no sign holds it
                Point c = bar.markCentre(clip);
                bar.dispatchEvent(mouse(bar, MouseEvent.MOUSE_PRESSED, c.x - 60, c.y, MouseEvent.BUTTON1));
                for (int x = c.x - 50; x <= c.x + 50; x += 10) {
                    bar.dispatchEvent(mouse(bar, MouseEvent.MOUSE_DRAGGED, x, c.y, MouseEvent.BUTTON1));
                    assertEquals(timeAt(d, x), d.position(), "dragged to " + x);
                }
                bar.dispatchEvent(mouse(bar, MouseEvent.MOUSE_RELEASED, c.x + 50, c.y, MouseEvent.BUTTON1));
                press(bar, c.x, c.y);
                assertEquals(DebriefFlight.START + DebriefFlight.CLIP_AT, d.position(), "the clip's sign: to when it started");

                // a right-click on a pin's or a clip's sign changes nothing: they aren't edited here
                long before = d.position();
                int marks = d.marks.size();
                bar.dispatchEvent(mouse(bar, MouseEvent.MOUSE_PRESSED, at.x, at.y, MouseEvent.BUTTON3));
                assertEquals(before, d.position());
                assertEquals(marks, d.marks.size());
                assertEquals(4, flights.pins.list().size());

                // playing goes on from a mark clicked
                long[] now = {5_000_000};
                d.clock = () -> now[0];
                d.togglePlay();
                press(bar, at.x, at.y);
                now[0] += 2_000;
                assertEquals(carTime + 2_000, d.position());
                d.togglePlay();
            });
        }
    }

    @Test
    void aBookmarkIsInTheNotesFileAtOnceRenamedAndRemovedToo() throws Exception {
        try (DebriefFlight flights = new DebriefFlight(dir)) {
            PreviousFlightsDialog d = flights.open();
            Path file = flights.tracks.resolve("track-20261108-140500.notes.txt");
            FlightNotes.Bookmark[] made = new FlightNotes.Bookmark[2];
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(file, d.data.flight.notesFile());
                assertFalse(Files.exists(file), "no notes yet, no file");
                assertTrue(d.bookmark.isEnabled());
                JComponent root = (JComponent) d.getContentPane();
                assertEquals("bookmark", root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("B")), "the B key");
                assertEquals("play", root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("SPACE")), "Space plays, as before");
                assertTrue(root.getActionMap().get("bookmark").isEnabled(), "no text is being typed: the key is the window's");

                d.seek(DebriefFlight.START + 75_000);
                made[0] = d.addBookmark(d.position() - d.data.startMs(), "Car at\tthe gate"); // what BOOKMARK does once the text is in
                made[1] = d.addBookmark(20_000, "  ");
                assertEquals(new FlightNotes.Bookmark(75_000, "Car at the gate"), made[0], "on one line");
                assertEquals("0:20  BOOKMARK", PreviousFlightsDialog.markText(mark(d, FlightMarks.Kind.BOOKMARK, "BOOKMARK")), "no text: it says what it is");
                assertNotNull(d.timeline.markCentre(mark(d, FlightMarks.Kind.BOOKMARK, "Car at the gate")));
            });
            PreviousFlightsDialog.awaitSaved();
            assertEquals(List.of(new FlightNotes.Bookmark(20_000, ""), new FlightNotes.Bookmark(75_000, "Car at the gate")),
                    FlightNotes.load(file).bookmarks(), "in the flight's notes file, in time order");

            SwingUtilities.invokeAndWait(() -> {
                FlightNotes.Bookmark renamed = d.renameBookmark(made[0], "Van at the gate");
                assertEquals(new FlightNotes.Bookmark(75_000, "Van at the gate"), renamed, "the same moment, other words");
                assertEquals(List.of("0:20  BOOKMARK", "0:40  PIN Car", "1:15  Van at the gate", "1:40  CLIP MobileBravo", "2:00  PIN Roof"),
                        d.marks.stream().map(PreviousFlightsDialog::markText).toList());
                made[0] = renamed;
            });
            PreviousFlightsDialog.awaitSaved();
            assertEquals(List.of(new FlightNotes.Bookmark(20_000, ""), new FlightNotes.Bookmark(75_000, "Van at the gate")), FlightNotes.load(file).bookmarks());

            SwingUtilities.invokeAndWait(() -> {
                d.removeBookmark(made[1]);
                assertEquals(2, d.marks.stream().filter(m -> m.kind() == FlightMarks.Kind.PIN).count(), "the pins stay: only bookmarks are edited here");
            });
            PreviousFlightsDialog.awaitSaved();
            assertEquals(List.of(made[0]), FlightNotes.load(file).bookmarks());
            assertTrue(d.data.flight.files().contains(file), "the notes are one of the flight's files: EXPORT and DELETE take them along");

            SwingUtilities.invokeAndWait(() -> d.removeBookmark(made[0]));
            PreviousFlightsDialog.awaitSaved();
            assertFalse(Files.exists(file), "nothing left to keep: no file");
            assertEquals(4, flights.pins.list().size());
        }
    }

    @Test
    void theNoteIsKeptWhenTheFocusLeavesTheFlightChangesAndTheWindowCloses() throws Exception {
        Path first, second;
        try (DebriefFlight flights = new DebriefFlight(dir)) {
            PreviousFlightsDialog d = flights.open();
            first = d.data.flight.notesFile();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(d.note.isEnabled());
                assertEquals("", d.note.getText());
                d.note.setText("Wind from the west.");
                // a click elsewhere: the focus leaves the note (told to its listeners - no window is on the screen to lose it)
                java.awt.event.FocusEvent left = new java.awt.event.FocusEvent(d.note, java.awt.event.FocusEvent.FOCUS_LOST);
                for (java.awt.event.FocusListener l : d.note.getFocusListeners()) l.focusLost(left);
            });
            PreviousFlightsDialog.awaitSaved();
            assertEquals("Wind from the west.", FlightNotes.load(first).note(), "kept when the focus leaves it");

            SwingUtilities.invokeAndWait(() -> {
                d.note.setText("Wind from the west.\nStronger than forecast.");
                d.addBookmark(30_000, "Gust");
            });
            flights.choose(1); // another flight: the note on the one before is kept as it stood
            PreviousFlightsDialog.awaitSaved();
            FlightNotes kept = FlightNotes.load(first);
            assertEquals("Wind from the west.\nStronger than forecast.", kept.note());
            assertEquals(List.of(new FlightNotes.Bookmark(30_000, "Gust")), kept.bookmarks(), "beside its bookmark");

            second = d.data.flight.notesFile();
            assertNotEquals(first, second);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("", d.note.getText(), "the other flight's note: none yet");
                assertTrue(d.marks.isEmpty(), "and its own marks: none");
                assertFalse(Files.exists(second));
                d.note.setText("Battery check only.");
            });
        } // the window closes
        assertEquals("Battery check only.", FlightNotes.load(second).note(), "kept when the window closes");
        assertEquals("Wind from the west.\nStronger than forecast.", FlightNotes.load(first).note(), "each flight its own");

        // opened again: the note and the bookmark are the flight's
        try (DebriefFlight again = new DebriefFlight(dir.resolve("second-look"))) {
            Files.copy(first, again.tracks.resolve(first.getFileName()));
            PreviousFlightsDialog d = again.open();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Wind from the west.\nStronger than forecast.", d.note.getText());
                assertEquals("0:30  Gust", PreviousFlightsDialog.markText(d.marks.get(0)));
                d.note.setText("   ");
                d.keepNote();
                d.removeBookmark(d.marks.get(0).bookmark());
            });
            PreviousFlightsDialog.awaitSaved();
            assertFalse(Files.exists(again.tracks.resolve(first.getFileName())), "the note emptied and the bookmark removed: the file goes");
        }
    }
}
