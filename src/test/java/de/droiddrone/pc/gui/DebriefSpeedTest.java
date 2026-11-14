package de.droiddrone.pc.gui;

import de.droiddrone.pc.flights.FlightFiles;
import de.droiddrone.pc.gui.PreviousFlightsDialog.Pace;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;
import de.droiddrone.pc.overwatch.ClipPlayer;
import de.droiddrone.pc.video.StreamRecorder;
import de.droiddrone.pc.video.TestStreams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PREVIOUS FLIGHTS' playing speed: the clock runs 1X, 2X, 4X or 0.5X against this PC's time (here the test's own
 * clock), pause and seek keep the speed, and what is shown is read off that one clock. The player's pace as a rule
 * (which picture is shown, which left out), the recording's reader leaving pictures out, and a real recording played
 * at 4X.
 */
class DebriefSpeedTest {
    @TempDir
    Path dir;

    private static final long START = DebriefFlight.START;

    @Test
    void theClockRunsAtTheSpeedChosenAndTheButtonGoesRound() throws Exception {
        try (DebriefFlight flights = new DebriefFlight(dir)) {
            PreviousFlightsDialog d = flights.open();
            SwingUtilities.invokeAndWait(() -> {
                long[] now = {7_000_000};
                d.clock = () -> now[0];
                assertEquals("1X", d.speedButton.getText());
                assertEquals(1, d.speed());
                d.togglePlay();
                now[0] += 1_000;
                assertEquals(START + 1_000, d.position(), "1X: a second a second");

                d.speedButton.doClick(0);
                assertEquals("2X", d.speedButton.getText());
                assertEquals(START + 1_000, d.position(), "the clock goes on from where it is");
                now[0] += 1_000;
                assertEquals(START + 3_000, d.position(), "2X");

                d.speedButton.doClick(0);
                assertEquals("4X", d.speedButton.getText());
                now[0] += 500;
                assertEquals(START + 5_000, d.position(), "4X");

                d.speedButton.doClick(0);
                assertEquals("0.5X", d.speedButton.getText());
                now[0] += 1_000;
                assertEquals(START + 5_500, d.position(), "0.5X");

                d.speedButton.doClick(0);
                assertEquals("1X", d.speedButton.getText(), "and round again");
                assertEquals(1, d.speed());
                now[0] += 250;
                assertEquals(START + 5_750, d.position());
                d.togglePlay();
            });
        }
    }

    @Test
    void pauseSeekAndAnotherFlightKeepTheSpeed() throws Exception {
        try (DebriefFlight flights = new DebriefFlight(dir)) {
            PreviousFlightsDialog d = flights.open();
            long[] now = {7_000_000};
            SwingUtilities.invokeAndWait(() -> {
                d.clock = () -> now[0];
                d.setSpeed(4);
                assertEquals("4X", d.speedButton.getText());
                assertEquals(START, d.position(), "set while paused: nothing moves");
                now[0] += 1_000;
                assertEquals(START, d.position());

                d.togglePlay();
                now[0] += 1_000;
                assertEquals(START + 4_000, d.position());
                d.togglePlay(); // pause
                assertEquals("Play", d.play.getText());
                now[0] += 5_000;
                assertEquals(START + 4_000, d.position(), "paused: the clock stands");
                assertEquals(4, d.speed(), "and the speed is kept");
                assertEquals("4X", d.speedButton.getText());
                d.togglePlay();
                now[0] += 500;
                assertEquals(START + 6_000, d.position(), "on at 4X");

                d.seek(START + 60_000); // while playing
                assertEquals(START + 60_000, d.position());
                now[0] += 250;
                assertEquals(START + 61_000, d.position(), "from the new place, still 4X");
                d.togglePlay();
                d.seek(START + 30_000); // while paused
                assertEquals(START + 30_000, d.position());
                d.togglePlay();
                now[0] += 1_000;
                assertEquals(START + 34_000, d.position());
                assertEquals(4, d.speed());

                // the speed changed while paused counts from the next PLAY
                d.togglePlay();
                d.setSpeed(0.5);
                d.togglePlay();
                now[0] += 2_000;
                assertEquals(START + 35_000, d.position());
                d.togglePlay();
            });
            flights.choose(1);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(0.5, d.speed(), "another flight plays at the speed set");
                assertEquals("0.5X", d.speedButton.getText());
                long start = d.data.startMs();
                d.togglePlay();
                now[0] += 4_000;
                assertEquals(start + 2_000, d.position());
                d.togglePlay();
            });
        }
    }

    @Test
    void whatIsShownGoesByTheOneClockAndTheEndStopsIt() throws Exception {
        try (DebriefFlight flights = new DebriefFlight(dir)) {
            PreviousFlightsDialog d = flights.open();
            SwingUtilities.invokeAndWait(() -> {
                long[] now = {7_000_000};
                d.clock = () -> now[0];
                Offscreen.layout(d, 1280, 760);
                d.setSpeed(2);
                d.togglePlay();
                now[0] += 30_000; // half a minute here is a minute of the flight
                d.tick();
                assertEquals(START + 60_000, d.position());
                assertEquals("1:00 / 3:00", d.time.getText(), "the time");
                assertEquals("60 m", d.figures[0].getText(), "the height at that moment (it climbs a metre a second)");
                assertEquals(String.format(java.util.Locale.ROOT, "%.2f V", 4.10 - 600 * 0.0003), d.figures[2].getText(), "the battery then");
                double[] drone = d.data.positionAt(START + 60_000);
                assertArrayEquals(new double[]{drone[0], drone[1]}, d.map.replayMarker(), 1e-9, "the drone on the map, where the track has it then");
                // the bar's playhead: a white line a third of the way along
                java.awt.image.BufferedImage bar = new java.awt.image.BufferedImage(d.timeline.getWidth(), d.timeline.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = bar.createGraphics();
                d.timeline.paint(g);
                g.dispose();
                int x = (int) (8 + (d.timeline.getWidth() - 16) / 3.0);
                assertEquals(0xffffff, bar.getRGB(x, d.timeline.getHeight() / 2) & 0xffffff, "the playhead at " + x);

                d.setSpeed(4);
                now[0] += 10_000;
                d.tick();
                assertEquals("1:40 / 3:00", d.time.getText(), "40 s of the flight on, in step");
                assertEquals("80 m", d.figures[0].getText(), "ten seconds past the turn: coming down");
                now[0] += 60_000; // far past the end
                d.tick();
                assertEquals("Play", d.play.getText(), "the end stops it");
                assertEquals(d.data.endMs(), d.position());
                assertEquals("3:00 / 3:00", d.time.getText());
                assertEquals(4, d.speed());
                d.togglePlay(); // PLAY at the end: from the start again
                now[0] += 1_000;
                assertEquals(START + 4_000, d.position());
                d.togglePlay();
            });
        }
    }

    @Test
    void aLatePictureIsLeftOutNotShownLate() {
        assertEquals(Pace.WAIT, PreviousFlightsDialog.pace(40, 1, false), "ahead of the clock: it waits");
        assertEquals(Pace.SHOW, PreviousFlightsDialog.pace(0, 1, false));
        assertEquals(Pace.SHOW, PreviousFlightsDialog.pace(-100, 1, false), "a little late: shown");
        assertEquals(Pace.LEAVE_OUT, PreviousFlightsDialog.pace(-200, 1, false), "over " + PreviousFlightsDialog.LATE_MS + " ms late: left out");
        assertEquals(Pace.SHOW, PreviousFlightsDialog.pace(-200, 4, false), "at 4X 200 ms of the flight are 50 ms here");
        assertEquals(Pace.LEAVE_OUT, PreviousFlightsDialog.pace(-600, 4, false));
        assertEquals(Pace.LEAVE_OUT, PreviousFlightsDialog.pace(-100, 0.5, false), "at 0.5X 100 ms of the flight are 200 ms here");
        assertEquals(Pace.LEAVE_OUT, PreviousFlightsDialog.pace(-600_000, 2, false), "the clock far on: to its place, not every picture on the way");
        assertEquals(Pace.SHOW, PreviousFlightsDialog.pace(-600, 4, true), "the first after pictures were left out is shown however late:");
        assertEquals(Pace.SHOW, PreviousFlightsDialog.pace(-60_000, 4, true), "a PC too slow for the speed shows fewer pictures, not none");
        assertEquals(Pace.FIND_AGAIN, PreviousFlightsDialog.pace(2_000, 1, false), "the clock went back");
        assertEquals(Pace.FIND_AGAIN, PreviousFlightsDialog.pace(2_000, 4, true));
    }

    /** A made-up recording as the app writes it: 25 pictures a second, a key frame every second. */
    private static Path recording(Path videos, String local, int frames) throws Exception {
        Path mkv = FlightFiles.recording(videos, local, frames / 25, i -> new FlightFiles.Row(24, 4.0, 59.91, 10.73, 0, 5, true, 40));
        TestStreams.Stream st = TestStreams.h264(320, 180, frames, 25);
        Files.delete(mkv);
        try (StreamRecorder r = new StreamRecorder(mkv, false, 320, 180, st.codecConfig())) {
            long t = 0;
            for (TestStreams.Packet p : st.packets()) r.write(p.data(), p.keyFrame(), t += 40);
        }
        return mkv;
    }

    @Test
    void theReaderLeavesPicturesOutAndGoesOnFromALaterKeyFrame() throws Exception {
        Path mkv = recording(Files.createDirectories(dir.resolve("Videos")), "2026-11-08T14:05:00", 150); // 6 s
        int[] px = new int[160 * 90];
        try (ClipPlayer p = new ClipPlayer(mkv)) {
            p.seek(0);
            long first = p.next(px, 160, 90), second = p.next(px, 160, 90);
            assertTrue(first >= 0 && first <= 40, "from the start: " + first);
            assertEquals(40, second - first, "every picture, 25 a second");

            p.skipTo(second + 160); // 4X: the next to show is four on
            long shown = p.next(px, 160, 90);
            assertEquals(second + 160, shown, "the three between were decoded, not given");
            assertEquals(shown + 40, p.next(px, 160, 90), "and on from there, every picture again");

            assertFalse(p.catchUp(shown + 400), "no key frame between here and there: it reads on, a seek would decode the same again");
            long caught = p.next(px, 160, 90);
            assertTrue(caught >= shown + 400 && caught < shown + 440, "the first picture at the place: " + caught);

            assertTrue(p.catchUp(4_500), "key frames at 1, 2, 3 and 4 s lie between: on from the one at 4 s");
            long far = p.next(px, 160, 90);
            assertTrue(far >= 4_500 && far < 4_540, "the first picture at the place: " + far);
            assertFalse(p.catchUp(4_800), "the same second: reads on");
            long on = p.next(px, 160, 90);
            assertTrue(on >= 4_800 && on < 4_840, "" + on);

            p.catchUp(60_000); // past the end: no picture, no harm
            assertEquals(-1, p.next(px, 160, 90));
            p.seek(1_000); // and a seek finds its place as ever
            long back = p.next(px, 160, 90);
            assertTrue(back >= 960 && back <= 1_040, "" + back);
        }
    }

    @Test
    void aRecordingPlayedAtFourTimesShowsFewerPicturesAndEndsWithTheClock() throws Exception {
        Theme.install();
        Path tracks = Files.createDirectories(dir.resolve("Tracks")), videos = Files.createDirectories(dir.resolve("Videos"));
        FlightFiles.gpx(tracks, "2026-11-08T14:05:00", 7, s -> new FlightFiles.Pt(59.91 + s * 0.0001, 10.73, 100, s > 0));
        recording(videos, "2026-11-08T14:05:00", 150); // 6 s, 150 pictures
        PreviousFlightsDialog[] holder = new PreviousFlightsDialog[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new PreviousFlightsDialog(null, new TileCache(dir.resolve("tiles"), "test", false),
                TileSource.KARTVERKET_TOPO, f -> { }, tracks, videos, null));
        PreviousFlightsDialog d = holder[0];
        try {
            d.awaitLoaded();
            assertTrue(d.video.awaitShown(1, 30_000), "the first picture, on loading");
            int[] before = new int[1];
            SwingUtilities.invokeAndWait(() -> {
                before[0] = d.video.shown();
                d.setSpeed(4);
                d.togglePlay();
            });
            Thread.sleep(2_500); // 6 s of flight at 4X are 1.5 s here
            SwingUtilities.invokeAndWait(() -> {
                d.tick();
                assertEquals("Play", d.play.getText(), "played to the end at 4X in under 2.5 s");
                assertEquals(d.data.endMs(), d.position());
                int shown = d.video.shown() - before[0];
                assertTrue(shown >= 1, "pictures were shown on the way: " + shown);
                assertTrue(shown <= 60, "about 30 a second of this PC's time, not all 150: " + shown);
            });
        } finally {
            SwingUtilities.invokeAndWait(d::dispose);
            SwingUtilities.invokeAndWait(() -> { }); // windowClosed (queued by dispose) stops playback: files shut before @TempDir goes
        }
    }
}
