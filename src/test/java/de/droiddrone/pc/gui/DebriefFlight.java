package de.droiddrone.pc.gui;

import de.droiddrone.pc.flights.FlightFiles;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;
import de.droiddrone.pc.overwatch.ClipIndex;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Made-up flights in a folder of the test's own, and PREVIOUS FLIGHTS opened on them without a window on the screen -
 * for the debrief's tests. Newest first, as the list has them:
 * <ol start="0">
 * <li>8 November, three minutes out and back: a track, a recording's figures and a link log with one loss, a pin and
 * a rough pin dropped meanwhile, an overwatch clip of its time</li>
 * <li>7 November, a minute and a half: a track and nothing else</li>
 * <li>6 November, a minute: a track from the phone's GPS</li>
 * <li>5 November, twenty seconds: a recording's figures without a position</li>
 * </ol>
 * Names and addresses are made up; nothing is read or written outside the folder given.
 */
final class DebriefFlight implements AutoCloseable {
    static final String T0 = "2026-11-08T14:05:00";
    static final long START = FlightFiles.at(T0);
    static final long LENGTH = 180_000;
    /** The pin Car and the rough pin Roof were dropped, and MobileBravo's clip started, this long into flight 0. */
    static final long CAR_AT = 40_123, CLIP_AT = 100_000, ROOF_AT = 120_000;

    final Path dir, tracks, videos, logs;
    final Pins pins;
    final ClipIndex clips;
    PreviousFlightsDialog dialog;

    DebriefFlight(Path dir) throws IOException {
        this.dir = dir;
        tracks = Files.createDirectories(dir.resolve("Tracks"));
        videos = Files.createDirectories(dir.resolve("Videos"));
        logs = Files.createDirectories(dir.resolve("logs"));

        FlightFiles.gpx(tracks, T0, 181, s -> new FlightFiles.Pt(59.91 + 0.00005 * out(s * 10), 10.73 + 0.00002 * out(s * 10), 100 + out(s * 10) / 10, s > 0));
        FlightFiles.recording(videos, T0, 180, i -> new FlightFiles.Row(24.6 - i * 0.0018, 4.10 - i * 0.0003, 59.91 + 0.000005 * out(i),
                10.73 + 0.000002 * out(i), out(i), out(i) / 10.0, i > 0, i == 700 ? 230 : 48));
        // the link: up from 5 s before the first position, down from 1:00 to 1:05, one slow answer at 1:25
        FlightFiles.linkLog(logs, "2026-11-08T14:04:55", 190, List.of("Connecting to MobileAlpha (192.0.2.7)"),
                i -> i >= 650 && i < 700 ? FlightFiles.Link.down() : FlightFiles.Link.ready(i == 900 ? 412 : 48));
        FlightFiles.gpx(tracks, "2026-11-07T10:00:00", 91, s -> new FlightFiles.Pt(59.90 + s * 0.0001, 10.70, 120 + s, s > 0));
        FlightFiles.gpx(tracks, "2026-11-06T16:00:00", 61, s -> new FlightFiles.Pt(59.92, 10.71 + s * 0.0001, 80, s > 0, true));
        FlightFiles.recording(videos, "2026-11-05T09:00:00", 20, i -> new FlightFiles.Row(24, 4.0, Double.NaN, Double.NaN, -1, 5, true, 40));

        Path pinsFile = dir.resolve("pins.txt");
        Files.writeString(pinsFile, "Before\t59.9000000\t10.7200000\t" + (START - 3_600_000) + "\t0\n"
                + "Car\t59.9120000\t10.7310000\t" + (START + CAR_AT) + "\t0\n"
                + "Old\t59.9300000\t10.7400000\n" // from before pins had a time
                + "Roof\t59.9140000\t10.7320000\t" + (START + ROOF_AT) + "\t1\n", StandardCharsets.UTF_8);
        pins = new Pins(pinsFile);
        clips = new ClipIndex(dir.resolve("Overwatch"));
        Properties facts = new Properties();
        facts.setProperty("drone", "MobileBravo");
        facts.setProperty("startedAt", Long.toString(START + CLIP_AT));
        clips.save("MobileBravo", clips.newId("MobileBravo", START + CLIP_AT), facts);
    }

    /** Out and back over the three minutes, in tenths of a second: 0 .. 900 .. 0 (metres from the launch, and ten times the height). */
    private static int out(int tenths) {
        return tenths <= 900 ? tenths : 1800 - tenths;
    }

    /** PREVIOUS FLIGHTS on these folders, the list read and its newest flight loaded; never shown. */
    PreviousFlightsDialog open() throws Exception {
        Theme.install();
        TileCache cache = new TileCache(dir.resolve("tiles"), "test", false); // an empty offline map
        SwingUtilities.invokeAndWait(() -> dialog = new PreviousFlightsDialog(null, cache, TileSource.KARTVERKET_TOPO, f -> { }, tracks, videos, logs, pins, clips));
        dialog.awaitLoaded();
        return dialog;
    }

    /** Another flight chosen in the list (see the class comment for which is which), read and shown. */
    void choose(int index) throws Exception {
        SwingUtilities.invokeAndWait(() -> dialog.list.setSelectedIndex(index));
        dialog.awaitLoaded();
    }

    /** The window as a small screen has it: the least size ScreenFit leaves it on a 1093x584 screen. */
    static void asOnASmallScreen(PreviousFlightsDialog d) {
        d.setMinimumSize(ScreenFit.least(new java.awt.Dimension(900, 600), new java.awt.Rectangle(0, 0, 1093, 584)));
    }

    /** The window gone, its playback stopped and the notes it kept in their files. */
    @Override
    public void close() throws Exception {
        if (dialog == null) return;
        SwingUtilities.invokeAndWait(dialog::dispose);
        SwingUtilities.invokeAndWait(() -> { }); // windowClosed, queued by dispose: playback stops, the note is kept
        PreviousFlightsDialog.awaitSaved();
    }
}
