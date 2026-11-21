package de.droiddrone.pc.gui;

import de.droiddrone.pc.flights.FlightFiles;
import de.droiddrone.pc.flights.FlightRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PREVIOUS FLIGHTS with two flights a review of 2026-11-20 found it wrong about: the one being flown now (DELETE took
 * its files away under it - and it is the newest, so the one chosen as the window opens), and an armed flight of
 * which only the link log is left (it was no flight at all). Names and addresses are made up.
 */
class DebriefLiveAndBareFlightTest {
    @TempDir
    Path dir;

    @Test
    void theFlightBeingFlownCannotBeDeletedTheOthersCan() throws Exception {
        try (DebriefFlight flights = new DebriefFlight(dir)) {
            FlightRecord now = FlightRecord.start(flights.tracks, "MobileAlpha", "192.0.2.7", "1.0.72", FlightFiles.at("2026-11-09T10:00:00"));
            now.track(FlightFiles.gpx(flights.tracks, "2026-11-09T10:00:01", 30, s -> new FlightFiles.Pt(59.91, 10.73 + s * 0.0001, 100, s > 2)));
            now.armed(FlightFiles.at("2026-11-09T10:00:04"));
            FlightRecord.flush();
            FlightRecord.live(now);
            try {
                PreviousFlightsDialog d = flights.open();
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals("NOW", d.data.flight.endMark(), "the newest is the one flown now, chosen as the window opens");
                    assertFalse(d.delete.isEnabled(), "DELETE is off for it");
                    assertEquals("Flying now", d.delete.getToolTipText());
                });
                flights.choose(1);
                SwingUtilities.invokeAndWait(() -> {
                    assertNull(d.data.flight.endMark());
                    assertTrue(d.delete.isEnabled(), "and on for a flight that is over");
                    assertNull(d.delete.getToolTipText());
                });
            } finally {
                FlightRecord.live(null);
                now.end(FlightRecord.DISCONNECT, FlightFiles.at("2026-11-09T10:00:31"));
            }
        }
    }

    @Test
    void anArmedFlightWithOnlyItsLinkLogIsShownPlayedAndPainted() throws Exception {
        List<Throwable> thrown = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> thrown.add(e));
        try (DebriefFlight flights = new DebriefFlight(dir)) {
            FlightRecord bare = FlightRecord.start(flights.tracks, "MobileCharlie", "192.0.2.9", "1.0.72", FlightFiles.at("2026-11-09T12:00:00"));
            bare.linkLog(FlightFiles.linkLog(flights.logs, "2026-11-09T12:00:00", 60, "MobileCharlie"));
            bare.armed(FlightFiles.at("2026-11-09T12:00:05"));
            bare.end(FlightRecord.LINK_LOST, FlightFiles.at("2026-11-09T12:01:00"));

            PreviousFlightsDialog d = flights.open();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(5, d.list.getModel().getSize(), "listed with the four others");
                assertNull(d.data.flight.track());
                assertNull(d.data.flight.video());
                assertEquals("LINK LOST", d.data.flight.endMark());
                assertEquals("MobileCharlie", d.data.flight.drone());
                assertEquals(600, d.data.link.size(), "its link's minute is there to look at");
                Offscreen.layout(d, 1280, 760);
                Offscreen.assertNothingClipped(d.getContentPane(), "a flight with only its link log");
                try {
                    Offscreen.shot(d, "bare-flight");
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                assertTrue(d.play.isEnabled());
                d.play.doClick(); // its time runs, with nothing to show but the link
            });
            Thread.sleep(700);
            SwingUtilities.invokeAndWait(() -> {
                d.play.doClick(); // and stops
                assertTrue(d.delete.isEnabled());
            });
            SwingUtilities.invokeAndWait(() -> { });
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
        }
        assertTrue(thrown.isEmpty(), "nothing thrown while it was shown and played: " + thrown);
    }
}
