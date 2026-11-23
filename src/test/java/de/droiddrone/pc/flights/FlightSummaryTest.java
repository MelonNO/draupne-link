package de.droiddrone.pc.flights;

import de.droiddrone.pc.flights.FlightFiles.Link;
import de.droiddrone.pc.flights.FlightFiles.Pt;
import de.droiddrone.pc.flights.FlightFiles.Row;
import de.droiddrone.pc.map.Mercator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The debrief's figures from made-up files: each figure from the file that holds it, a loss and a failsafe counted
 * once where they begin, and "not known" - never 0 - for what a flight's files don't tell.
 */
class FlightSummaryTest {
    static final String T0 = "2026-11-08T14:05:00";
    /** One step of the made-up flight: 0.0001 degrees north. */
    static final double STEP_M = Mercator.distanceM(59.91, 10.73, 59.9101, 10.73);

    @TempDir
    Path root;

    private Path tracks() throws IOException {
        return Files.createDirectories(root.resolve("Tracks"));
    }

    private Path videos() throws IOException {
        return Files.createDirectories(root.resolve("Videos"));
    }

    private Path logs() throws IOException {
        return Files.createDirectories(root.resolve("logs"));
    }

    private FlightLibrary.Flight theFlight() throws IOException {
        List<FlightLibrary.Flight> flights = FlightLibrary.scan(tracks(), videos(), logs());
        assertEquals(1, flights.size(), "the made-up files are one flight");
        return flights.get(0);
    }

    /** Steps north of the start at second s: on the ground for 10 s, 60 steps out, 40 back, landed 20 steps out. */
    static int steps(double s) {
        return s <= 10 ? 0 : s <= 70 ? (int) Math.round(s - 10) : s <= 110 ? (int) Math.round(130 - s) : 20;
    }

    /** Two minutes: armed from second 10 to second 110; a twitch east and back on the ground at second 5. */
    static Pt point(int s) {
        return new Pt(59.91 + steps(s) * 0.0001, s == 5 ? 10.7302 : 10.73, 100 + steps(s), s >= 10 && s <= 110);
    }

    /** The recording from second 5 to 115, row i at i tenths: the cells sag to 3.47 V in the air, and read 0.80 V once the pack is pulled after landing. */
    static Row row(int i) {
        double s = 5 + i / 10.0;
        boolean armed = s >= 10 && s <= 110;
        double cell = !armed ? (s > 112 ? 0.80 : 4.20) : s >= 60 && s < 60.5 ? 3.47 : 4.20 - 0.006 * (s - 10);
        double alt = armed ? Math.min(42.4, Math.min(s - 10, 110 - s)) : 0;
        return new Row(cell * 6, cell, 59.91 + steps(s) * 0.0001, 10.73, steps(s) * 11, alt, armed, 40);
    }

    /**
     * The link from 8 s before the first position, row i at i tenths. Before the flight: connecting, a ping of 999 and
     * a loss. In it: down for 2 s from second 40 with FAILSAFE on when it is back (and nothing heard from Betaflight
     * for 0.3 s in the middle of that), a ping of 310 at second 60, down for 1 s from second 80 and FAILSAFE again.
     * After it: another loss and a ping of 800.
     */
    static Link link(int i) {
        int t = i - 80; // tenths of a second from the first position
        if (t < -50) return Link.down();
        if (t < -40) return Link.ready(999);
        if (t < -30) return Link.down();
        if (t >= 400 && t < 420) return Link.down();
        if (t >= 420 && t < 450) return t >= 430 && t < 433 ? new Link("READY", 48, "", "") : new Link("READY", 48, "1", "1");
        if (t >= 600 && t < 602) return Link.ready(310);
        if (t >= 800 && t < 810) return Link.down();
        if (t >= 810 && t < 830) return new Link("READY", 48, "1", "1");
        if (t >= 1210 && t < 1220) return Link.down();
        if (t >= 1230 && t < 1240) return Link.ready(800);
        return Link.ready(48);
    }

    private void wholeFlight() throws IOException {
        FlightFiles.gpx(tracks(), T0, 120, FlightSummaryTest::point);
        FlightFiles.recording(videos(), "2026-11-08T14:05:05", 110, FlightSummaryTest::row);
        FlightFiles.linkLog(logs(), "2026-11-08T14:04:52", 135, List.of("Connecting to MobileAlpha (192.0.2.7:8888)"), FlightSummaryTest::link);
    }

    @Test
    void everyFigureComesFromTheFlightsFiles() throws IOException {
        wholeFlight();
        FlightLibrary.Flight f = theFlight();
        FlightSummary s = FlightSummary.of(f);

        assertEquals(119_000, s.durationMs, "from the first position to the last");
        assertEquals("1:59", s.durationText());
        assertEquals(100_000, s.armedMs, "armed from second 10 to second 110");
        assertEquals("1:40", s.armedText());

        assertEquals(100 * STEP_M, s.distanceM, 1, "60 steps out and 40 back, flown armed");
        assertEquals("1.11 KM", s.distanceText());
        assertTrue(f.distanceM() > s.distanceM + 15, "the twitch on the ground is in the list's track length (" + f.distanceM() + "), not in the way flown");
        assertEquals(60 * STEP_M, s.furthestM, 1, "60 steps from where it armed");
        assertEquals("667 M", s.furthestText());

        assertEquals(42.4, s.maxAltM, 0.05, "the flight controller's altitude, from the recording");
        assertEquals(FlightSummary.AltitudeKind.ABOVE_TAKE_OFF, s.maxAltKind);
        assertEquals("42 M", s.maxAltText());
        assertEquals("MAX HEIGHT", s.maxAltLabel(), "counted from take-off: a height, not the GPS's altitude (160 m in the track)");

        assertEquals(3.47, s.minCellVolts, 0.005, "the sag in the air - not the 0.80 V of the pack pulled after landing");
        assertEquals(3.47 * 6, s.minPackVolts, 0.05);
        assertEquals("3.47 V", s.batteryText());
        assertEquals("MIN CELL", s.batteryLabel());

        assertEquals(2, s.linkLosses, "each loss once, however many rows it lasts; not the connecting, not the losses before and after the flight");
        assertEquals(2, s.failsafes, "each failsafe once - rows with nothing heard from Betaflight in the middle of one don't start another");
        assertEquals(310, s.worstPingMs, "the worst in the flight: not the 999 before it or the 800 after it");
        assertEquals("310 MS", s.worstPingText());
        assertEquals("2", s.linkLossesText());
        assertEquals("2", s.failsafesText());

        List<FlightSummary.Figure> figures = s.figures();
        assertEquals(List.of("DURATION", "ARMED", "DISTANCE", "FURTHEST", "MAX HEIGHT", "MIN CELL", "LINK LOSSES", "FAILSAFES", "WORST PING", "CLOSEST"),
                figures.stream().map(FlightSummary.Figure::label).toList());
        assertEquals(List.of("1:59", "1:40", "1.11 KM", "667 M", "42 M", "3.47 V", "2", "2", "310 MS", "-"),
                figures.stream().map(FlightSummary.Figure::value).toList());
        assertTrue(figures.stream().limit(9).allMatch(FlightSummary.Figure::known));
        assertFalse(figures.get(9).known(), "a flight without a record: its closest approach not known");
    }

    @Test
    void aTrackAloneKnowsNoBatteryAndNoLink() throws IOException {
        FlightFiles.gpx(tracks(), T0, 120, FlightSummaryTest::point);
        FlightSummary s = FlightSummary.of(theFlight());
        assertEquals("1:59", s.durationText());
        assertEquals("1:40", s.armedText());
        assertEquals("1.11 KM", s.distanceText());
        assertEquals("667 M", s.furthestText());
        assertEquals(160, s.maxAltM, 1e-9, "the track's altitude is the GPS's");
        assertEquals(FlightSummary.AltitudeKind.GPS, s.maxAltKind);
        assertEquals("MAX GPS ALT", s.maxAltLabel());
        assertEquals("160 M", s.maxAltText());

        assertTrue(Double.isNaN(s.minCellVolts));
        assertTrue(Double.isNaN(s.minPackVolts));
        assertEquals(FlightSummary.UNKNOWN, s.batteryText(), "no recording: no battery");
        assertEquals(-1, s.linkLosses);
        assertEquals(-1, s.failsafes);
        assertEquals(-1, s.worstPingMs);
        assertEquals(FlightSummary.UNKNOWN, s.linkLossesText(), "no link log: not known - not 0 losses");
        assertEquals(FlightSummary.UNKNOWN, s.failsafesText());
        assertEquals(FlightSummary.UNKNOWN, s.worstPingText());
        assertEquals(List.of(true, true, true, true, true, false, false, false, false, false), s.figures().stream().map(FlightSummary.Figure::known).toList());
    }

    /** A flight without GPS: a recording whose figures have no position (none sent, then 0 / 0 for "no fix") and no cell count. */
    @Test
    void aFlightWithoutPositionsHasNoDistance() throws IOException {
        FlightFiles.recording(videos(), T0, 30, i -> {
            boolean armed = i >= 50 && i <= 250;
            return new Row(armed ? 22.8 - (i - 50) * 0.007 : 22.9, Double.NaN, i < 150 ? Double.NaN : 0, i < 150 ? Double.NaN : 0, i < 150 ? -1 : 0,
                    armed ? 12.0 : 0, armed, i == 100 ? 95 : 30);
        });
        FlightSummary s = FlightSummary.of(theFlight());
        assertEquals("0:30", s.durationText());
        assertEquals("0:20", s.armedText());
        assertTrue(Double.isNaN(s.distanceM));
        assertEquals(FlightSummary.UNKNOWN, s.distanceText(), "not known - never 0 M");
        assertTrue(Double.isNaN(s.furthestM), "Betaflight's 0 m to a home it never had is no distance");
        assertEquals(FlightSummary.UNKNOWN, s.furthestText());
        assertEquals("12 M", s.maxAltText());
        assertEquals("MAX HEIGHT", s.maxAltLabel());

        assertTrue(Double.isNaN(s.minCellVolts), "the cell count wasn't known");
        assertEquals(21.4, s.minPackVolts, 0.01);
        assertEquals("MIN PACK", s.batteryLabel());
        assertEquals("21.4 V", s.batteryText());

        assertEquals(95, s.worstPingMs, "no link log: the recording's ping");
        assertEquals(FlightSummary.UNKNOWN, s.linkLossesText());
        assertEquals(FlightSummary.UNKNOWN, s.failsafesText());
        for (FlightSummary.Figure fig : s.figures()) {
            if (!fig.known()) assertEquals(FlightSummary.UNKNOWN, fig.value(), fig.label());
            assertFalse(fig.value().matches("0( .*)?") && !fig.known(), fig.label() + " not known is never 0");
        }
    }

    /** A connection made in the air: the track is armed from its first point, so where it took off isn't in it. */
    @Test
    void aTrackThatStartsArmedDoesNotKnowItsTakeOffPoint() throws IOException {
        FlightFiles.gpx(tracks(), T0, 60, s -> new Pt(59.92 + s * 0.0001, 10.73, 150, true));
        FlightSummary s = FlightSummary.of(theFlight());
        assertEquals(59 * STEP_M, s.distanceM, 1, "the way flown is known");
        assertTrue(Double.isNaN(s.furthestM), "but not how far that was from the take-off point");
        assertEquals(FlightSummary.UNKNOWN, s.furthestText());

        // with a recording, the flight controller's own distance to its home tells it
        FlightFiles.recording(videos(), T0, 55, i -> new Row(24, 4.0, 59.92 + i * 0.00001, 10.73, 300 + i / 5, 40, true, 40));
        s = FlightSummary.of(theFlight());
        assertEquals(410, s.furthestM, 1e-9);
        assertEquals("410 M", s.furthestText());
    }

    @Test
    void aTrackThatNeverArmedCountsItsWholeLength() throws IOException {
        FlightFiles.gpx(tracks(), T0, 31, s -> new Pt(59.91 + s * 0.0001, 10.73, 90, false, true));
        FlightLibrary.Flight f = theFlight();
        FlightSummary s = FlightSummary.of(f);
        assertEquals(30 * STEP_M, s.distanceM, 1);
        assertEquals(f.distanceM(), s.distanceM, 1e-6, "as the flights list counts it");
        assertEquals("0:00", s.armedText(), "the track never says armed");
        assertTrue(Double.isNaN(s.furthestM), "no take-off seen");
        assertEquals(FlightSummary.AltitudeKind.PHONE_GPS, s.maxAltKind, "the phone's GPS made this track");
        assertEquals("MAX PHONE GPS ALT", s.maxAltLabel());
        assertEquals("90 M", s.maxAltText());
    }

    /** Files cut short by a crash or a full disk: the track mid-point, the figures and the link log mid-row. */
    @Test
    void tornFilesGiveWhatTheyHold() throws IOException {
        wholeFlight();
        FlightLibrary.Flight whole = theFlight();
        cut(whole.track(), 170); // inside its last point: the closing tags are gone too
        String csv = Files.readString(whole.telemetry(), StandardCharsets.UTF_8);
        Files.writeString(whole.telemetry(), csv + "110100,40,18.06,3.01,12.0,100,14", StandardCharsets.UTF_8); // a row the disk took half of: 3.01 V never counted
        String log = Files.readString(whole.linkLog(), StandardCharsets.UTF_8);
        int middle = log.indexOf('\n', log.length() / 2) + 1;
        Files.writeString(whole.linkLog(), log.substring(0, middle) + "\u0000\u0000\u0000garbage, not a row\n,,,\n" + log.substring(middle) + "14:07:07.1,CONNEC",
                StandardCharsets.UTF_8);

        FlightSummary s = assertDoesNotThrow(() -> FlightSummary.of(theFlight()));
        assertEquals(118_000, s.durationMs, "the track ends at its last whole point");
        assertEquals("1.11 KM", s.distanceText());
        assertEquals("667 M", s.furthestText());
        assertEquals("3.47 V", s.batteryText());
        assertEquals(2, s.linkLosses);
        assertEquals(2, s.failsafes);
        assertEquals(310, s.worstPingMs);
    }

    /**
     * A link log whose last row was cut is still its flight's: its end is the last whole row's time. (It was the
     * connection's first second - and a log that began more than 20 s before the first position was nobody's.)
     */
    @Test
    void aLinkLogCutInItsLastRowIsStillItsFlights() throws IOException {
        FlightFiles.gpx(tracks(), T0, 60, FlightSummaryTest::point);
        Path log = FlightFiles.linkLog(logs(), "2026-11-08T14:04:00", 100, List.of("Connecting to MobileAlpha (192.0.2.7:8888)"),
                i -> i >= 900 && i < 910 ? Link.down() : Link.ready(48));
        Files.writeString(log, Files.readString(log, StandardCharsets.UTF_8) + "14:05:40.1,REA", StandardCharsets.UTF_8);
        FlightLibrary.Flight f = theFlight();
        assertEquals(log, f.linkLog());
        assertEquals("MobileAlpha", f.drone());
        assertEquals(1, FlightSummary.of(f).linkLosses, "the loss at second 30 of the flight");
    }

    private static void cut(Path file, int bytesOffTheEnd) throws IOException {
        byte[] b = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(b, b.length - bytesOffTheEnd));
    }

    @Test
    void missingAndEmptyFilesLeaveTheirFiguresUnknown() throws IOException {
        long start = FlightFiles.at(T0);
        // the files a flight names are gone (deleted in Explorer while the list was open)
        FlightLibrary.Flight gone = new FlightLibrary.Flight(start, start + 60_000, "MobileAlpha", tracks().resolve("track-20261108-140500.gpx"), null, null,
                videos().resolve("flight-2026-11-08_14-05-00.csv"), logs().resolve("link-20261108-140452.csv"), Double.NaN);
        FlightSummary s = assertDoesNotThrow(() -> FlightSummary.of(gone));
        assertEquals("1:00", s.durationText());
        assertEquals(List.of(true, false, false, false, false, false, false, false, false, false), s.figures().stream().map(FlightSummary.Figure::known).toList());
        assertEquals(-1, s.armedMs);
        assertNull(s.maxAltKind);
        assertEquals("MAX ALT", s.maxAltLabel());

        // and there but empty
        for (Path p : gone.files()) assertFalse(Files.exists(p), "not there: " + p);
        Files.write(gone.track(), new byte[0]);
        Files.write(gone.telemetry(), new byte[0]);
        Files.write(gone.linkLog(), new byte[0]);
        s = assertDoesNotThrow(() -> FlightSummary.of(gone));
        assertEquals(List.of(true, false, false, false, false, false, false, false, false, false), s.figures().stream().map(FlightSummary.Figure::known).toList());
        for (FlightSummary.Figure fig : s.figures()) if (!fig.known()) assertEquals(FlightSummary.UNKNOWN, fig.value(), fig.label());
    }

    /** A link log that only has the connection's first seconds says nothing about the flight after it. */
    @Test
    void aLinkLogThatEndsBeforeTheFlightKnowsNoLosses() throws IOException {
        FlightFiles.gpx(tracks(), T0, 60, FlightSummaryTest::point);
        FlightFiles.linkLog(logs(), "2026-11-08T14:04:45", 10, List.of("Connecting to MobileAlpha (192.0.2.7:8888)"), i -> i < 30 ? Link.down() : Link.ready(48));
        FlightLibrary.Flight f = theFlight();
        assertNotNull(f.linkLog(), "the log is the flight's");
        FlightSummary s = FlightSummary.of(f);
        assertEquals(-1, s.linkLosses);
        assertEquals(-1, s.failsafes);
        assertEquals(-1, s.worstPingMs);
    }
}
