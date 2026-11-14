package de.droiddrone.pc.gui;

import de.droiddrone.pc.flights.FlightReport;
import de.droiddrone.pc.flights.FlightSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PREVIOUS FLIGHTS' REPORT: the chosen flight written as FlightReport's one HTML file off the Swing thread - its
 * figures, its marks and its note as the window has them, the track on a map painted off the screen - with REPORT
 * locked meanwhile and no window opened (the file chooser and the "saved" box are the button's, not the writing's).
 */
class DebriefReportTest {
    @TempDir
    Path dir;

    /** Writes the chosen flight's report as REPORT does once the file is named; what failed, or nothing. */
    private static Optional<Exception> write(PreviousFlightsDialog d, Path target) throws Exception {
        CompletableFuture<Optional<Exception>> done = new CompletableFuture<>();
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(d.report.isEnabled());
            d.writeReport(target, failed -> {
                assertTrue(SwingUtilities.isEventDispatchThread(), "told on the Swing thread");
                done.complete(Optional.ofNullable(failed));
            });
            assertFalse(d.report.isEnabled(), "locked while it is written: not twice at once");
        });
        Optional<Exception> failed = done.get(60, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> assertTrue(d.report.isEnabled(), "free again"));
        return failed;
    }

    private static void assertNoWindowShown() {
        for (Window w : Window.getWindows()) assertFalse(w.isShowing(), "a window on the screen: " + w);
    }

    @Test
    void reportWritesTheFlightWithItsFiguresMarksNoteAndTrack() throws Exception {
        try (DebriefFlight flights = new DebriefFlight(dir)) {
            PreviousFlightsDialog d = flights.open();
            SwingUtilities.invokeAndWait(() -> {
                d.addBookmark(90_000, "Turned back <early>");
                d.note.setText("Wind from the west."); // typed, the focus still in it: the report has it as it stands
            });
            Path target = dir.resolve("Exports").resolve(FlightReport.fileName(d.data.flight));
            assertEquals("flight-2026-11-08_1405-report.html", target.getFileName().toString(), "the name REPORT proposes");
            assertEquals(Optional.empty(), write(d, target));
            assertNoWindowShown();

            String html = Files.readString(target, StandardCharsets.UTF_8);
            assertTrue(html.startsWith("<!DOCTYPE html>") && html.contains("FLIGHT 2026-11-08 14:05") && html.contains("MobileAlpha"), "when and which drone");
            for (FlightSummary.Figure f : FlightSummary.of(d.data).figures()) {
                assertTrue(html.contains("<dt>" + f.label() + "</dt><dd>" + f.value() + "</dd>"), f.label() + " " + f.value() + ": the summary's figure");
            }
            assertTrue(html.contains("<dt>DURATION</dt><dd>3:00</dd>") && html.contains("<dt>LINK LOSSES</dt><dd>1</dd>")
                    && html.contains("<dt>WORST PING</dt><dd>412 MS</dd>") && html.contains("<dt>MIN CELL</dt><dd>3.56 V</dd>"));
            assertTrue(html.contains("Turned back &lt;early&gt;"), "the bookmark, as text");
            assertTrue(html.contains(">PIN</td><td>Car</td>") && html.contains(">CLIP</td><td>MobileBravo</td>") && html.contains(">PIN</td><td>Roof</td>"),
                    "the pins and the clip of its time");
            assertTrue(html.contains("<p class=\"note\">Wind from the west.</p>"), "the note");

            // the track on a map painted off the screen, inside the file
            int at = html.indexOf("src=\"data:image/png;base64,");
            assertTrue(at > 0, "the track's picture");
            String png = html.substring(at + 27, html.indexOf('"', at + 27));
            BufferedImage picture = javax.imageio.ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(png)));
            assertEquals(840, picture.getWidth());
            assertEquals(472, picture.getHeight());
            // the replayed track is drawn in colours of its own: more than the empty map's greys
            java.util.Set<Integer> colours = new java.util.HashSet<>();
            for (int y = 0; y < picture.getHeight(); y += 2) for (int x = 0; x < picture.getWidth(); x += 2) colours.add(picture.getRGB(x, y) & 0xffffff);
            assertTrue(colours.size() > 4, "something is drawn on it: " + colours.size() + " colours");
            try (var files = Files.list(target.getParent())) {
                assertEquals(1, files.count(), "written whole: no half file beside it");
            }

            // the note went to the flight's notes with it
            PreviousFlightsDialog.awaitSaved();
            assertEquals("Wind from the west.", de.droiddrone.pc.flights.FlightNotes.load(d.data.flight).note());

            String shots = System.getenv("DEBRIEF_SHOTS");
            if (shots != null && !shots.isBlank()) {
                Files.createDirectories(Path.of(shots));
                Files.copy(target, Path.of(shots, "flight-report.html"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                javax.imageio.ImageIO.write(picture, "png", Path.of(shots, "flight-report-track.png").toFile());
            }
        }
    }

    @Test
    void aFlightWithoutPositionsGetsItsReportWithoutAPicture() throws Exception {
        try (DebriefFlight flights = new DebriefFlight(dir)) {
            PreviousFlightsDialog d = flights.open();
            flights.choose(3); // a recording's figures, no track
            Path target = dir.resolve("no-track.html");
            assertEquals(Optional.empty(), write(d, target));
            String html = Files.readString(target, StandardCharsets.UTF_8);
            assertTrue(html.contains("FLIGHT 2026-11-05 09:00"));
            assertFalse(html.contains("<img"), "no positions, no map");
            assertTrue(html.contains("<dt>DISTANCE</dt><dd>-</dd>") && html.contains("NO MARKS"));
            assertNoWindowShown();
        }
    }

    @Test
    void aReportThatCannotBeWrittenSaysWhy() throws Exception {
        try (DebriefFlight flights = new DebriefFlight(dir)) {
            PreviousFlightsDialog d = flights.open();
            Path inTheWay = Files.writeString(dir.resolve("in-the-way"), "a file where the folder should be");
            Optional<Exception> failed = write(d, inTheWay.resolve("report.html"));
            assertTrue(failed.isPresent(), "told, not swallowed");
            assertInstanceOf(java.io.IOException.class, failed.get());
            assertNoWindowShown();
        }
    }
}
