package de.droiddrone.bench.ui;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.SmallDisk;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.fleet.VirtualDrone;
import de.droiddrone.pc.map.FlightTrack;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.JTextArea;
import java.awt.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avformat.av_read_frame;
import static org.bytedeco.ffmpeg.global.avformat.avformat_close_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_open_input;

/**
 * "Nothing lost - disk full", for real (the test week, 2026-11-24): the whole app, in flight with a made-up drone,
 * writing to a disk that really runs out of room - not a folder that refuses (the unit tests' stand-in) but a small
 * disk of WSL's memory ({@link SmallDisk}) that the recording and the track fill. What was saved stays whole; what can't
 * be saved is said in the window's log in the app's words; the link, the sticks and the picture go on; once there is
 * room again the saving goes on and says so. Then the app's own settings and logs on a full disk: it starts, keeps its
 * settings, and a change made while the disk was full is in the file once there is room.
 * <p>
 * Windows asks the small disk for its free room and gets WSL's own big disk: the app's LOW DISK warning cannot show
 * here (said in the film). Needs WSL with a distribution ({@code wsl -u root}, no Windows admin rights); without it the
 * scenario is skipped with the reason.
 */
@Tag("ui")
class DiskFullScenario {
    private static final String FILLER = "filler.bin";

    @Test
    void aDiskThatRunsFullInFlightLosesNothingSavedAndSavesAgainWhenThereIsRoom() throws Exception {
        String[] why = new String[1];
        SmallDisk disk = SmallDisk.mount(64, why);
        Assumptions.assumeTrue(disk != null, "No small disk to fill: " + why[0]);
        try {
            AppRig.scenario("diskfull-flight").drones(2).homeOn(disk.path()).run(rig -> flyIntoAFullDisk(rig, disk));
        } finally {
            disk.close();
        }
    }

    private static void flyIntoAFullDisk(AppRig rig, SmallDisk disk) throws Exception {
        Ui ui = rig.ui;
        Pilot pilot = new Pilot(rig);
        Path mine = rig.sandbox.home().getParent(); // this user's folder on the small disk: home, and the filler beside it
        Path filler = mine.resolve(FILLER);
        Path tracks = rig.sandbox.tracks(), videos = rig.sandbox.videos(), documents = rig.sandbox.documents();
        rig.film.remark("The small disk: " + disk + " - " + disk.df() + ". Its free room as Windows (and the app) sees it: "
                + String.format("%.1f GB", mine.toFile().getUsableSpace() / 1e9) + " - WSL's own disk, not this one: LOW DISK ("
                + de.droiddrone.pc.DiskSpace.warning(mine) + ") cannot show here.");

        // ---------------------------------------------------------------- 1. in flight, room on the disk
        String name = String.valueOf(Ui.edt(() -> ui.all(javax.swing.JComboBox.class).get(0).getSelectedItem())).trim().split("\\s+")[0];
        VirtualDrone drone = rig.drone(name);
        pilot.connect();
        pilot.waitForVideo();
        ui.until("RC OUTPUT: LIVE", 10_000, () -> ui.sees("RC OUTPUT: LIVE"));
        pilot.arm(drone);
        pilot.throttle(0.15);
        pilot.until("the drone to climb to 12 m", 20_000, () -> drone.readout().altM() > 12);
        pilot.throttle(HOVER);
        pilot.until("a recording on the small disk", 20_000, () -> biggest(videos, ".mkv") > 50_000);
        pilot.until("the track on the small disk", 20_000, () -> gpxPoints(tracks) >= 5);

        MapToolsScenario.openTools(ui, "DRAW");
        Component map = MapToolsScenario.map(ui);
        int cx = Ui.edt(map::getWidth) / 2, cy = Ui.edt(map::getHeight) / 2;
        dropPin(rig, map, cx - 80, cy - 60);
        drawLine(ui, map, cx - 120, cy + 40, cx - 20, cy + 40);
        pilot.until("Alpha in pins.txt", 5000, () -> read(documents.resolve("pins.txt")).contains("Alpha"));
        pilot.until("one drawing in graphics.kml", 5000, () -> drawings(documents) == 1);
        ui.shot("flying: recording, track, a pin and a line saved");
        String pinsBefore = read(documents.resolve("pins.txt"));
        String drawingsBefore = read(documents.resolve("graphics.kml"));
        int pointsBefore = gpxPoints(tracks);
        Map<String, Long> videosBefore = sizes(videos);
        rig.film.remark("Saved before the disk ran full: " + pointsBefore + " track points, pins " + pinsBefore.strip().replace('\t', ' ')
                + ", 1 drawing, videos " + videosBefore + ". " + disk.df());
        long fullFrom = System.currentTimeMillis();

        // ---------------------------------------------------------------- 2. the disk runs full
        String refused = SmallDisk.fill(filler, 1_500_000); // 1.5 MB left: the recording and the track take it
        rig.film.remark("The filler took the disk to 1.5 MB free (a write past that: \"" + refused + "\"). " + disk.df());
        pilot.until("the app to meet the full disk (a write refused)", 90_000,
                () -> windowLog(ui).contains("TRACK NOT SAVED") || rig.logged("RECORDING INTERRUPTED") || rig.logged("RECORDING STOPPED"));
        SmallDisk.fill(filler, 0); // and what is left of it: the disk full to the last byte
        rig.film.remark("The disk full: " + disk.df());
        Ui.pause(3000);
        ui.until("TRACK NOT SAVED in the window's log", 20_000, () -> windowLog(ui).contains("TRACK NOT SAVED"));
        rig.check(windowLog(ui).contains("TRACK NOT SAVED"), "the window's log says TRACK NOT SAVED: " + lines(windowLog(ui), "TRACK"));

        dropPin(rig, map, cx + 60, cy - 60);
        ui.until("PINS NOT SAVED in the window's log", 10_000, () -> windowLog(ui).contains("PINS NOT SAVED"));
        drawLine(ui, map, cx - 120, cy + 90, cx - 20, cy + 90);
        ui.until("MAP DRAWINGS NOT SAVED in the window's log", 10_000, () -> windowLog(ui).contains("MAP DRAWINGS NOT SAVED"));
        ui.shot("the disk full: a pin and a line more, not saved");
        String log = windowLog(ui);
        rig.check(log.contains("PINS NOT SAVED"), "the window's log says PINS NOT SAVED: " + lines(log, "PINS"));
        rig.check(log.contains("MAP DRAWINGS NOT SAVED"), "the window's log says MAP DRAWINGS NOT SAVED: " + lines(log, "DRAWINGS"));
        rig.check(read(documents.resolve("pins.txt")).equals(pinsBefore), "pins.txt is as it was before the disk ran full (Alpha), not damaged: "
                + read(documents.resolve("pins.txt")).strip().replace('\t', ' '));
        rig.check(read(documents.resolve("graphics.kml")).equals(drawingsBefore), "graphics.kml is as it was (one drawing), not damaged: "
                + drawings(documents) + " drawing(s)");
        rig.check(gpxPoints(tracks) >= pointsBefore, "the track's file still reads, every point of before in it: " + gpxPoints(tracks) + " of " + pointsBefore);

        // the link, the sticks and the picture, the disk full
        rig.check(pilot.linkUp(), "the header says LINK READY with the disk full");
        rig.check(ui.sees("RC OUTPUT: LIVE"), "RC OUTPUT: LIVE with the disk full");
        double alt0 = drone.readout().altM();
        pilot.throttle(-0.35);
        Ui.pause(3000);
        double alt1 = drone.readout().altM();
        pilot.throttle(0.0);
        Ui.pause(3000);
        double alt2 = drone.readout().altM();
        pilot.throttle(HOVER);
        rig.check(alt1 < alt0 - 1 && alt2 > alt1 + 1, String.format("the drone follows the sticks with the disk full: down %.1f -> %.1f m, up again to %.1f m", alt0, alt1, alt2));
        Ui.pause(8000); // the recording's tries, and the picture going on meanwhile
        long fullUntil = System.currentTimeMillis();
        ui.shot("the disk full for " + (fullUntil - fullFrom) / 1000 + " s");
        List<String> appLog = rig.log();
        rig.film.remark("The app's log, the recording, while the disk was full: " + matching(appLog, "RECORDING", "OSD recording", "Recording"));
        rig.film.remark("The window's log, the disk full: " + lines(windowLog(ui), "SAVED", "Could not", "stopped", "Link log", "LOW DISK"));
        rig.film.remark("The OSD's REC mark with the disk full: " + (recordingNow(rig) ? "shown (a file is open)" : "gone (no file is being written)"));

        // ---------------------------------------------------------------- 3. room again
        Files.delete(filler);
        long roomFrom = System.currentTimeMillis();
        rig.film.remark("The filler deleted: " + disk.df());
        ui.until("\"Track saved again\" in the window's log", 20_000, () -> windowLog(ui).contains("Track saved again"));
        rig.check(windowLog(ui).contains("Track saved again"), "the window's log says the track is saved again: " + lines(windowLog(ui), "Track saved again"));
        dropPin(rig, map, cx + 60, cy + 60);
        ui.until("\"Pins saved again.\" in the window's log", 10_000, () -> windowLog(ui).contains("Pins saved again."));
        drawLine(ui, map, cx - 120, cy + 140, cx - 20, cy + 140);
        ui.until("\"Map drawings saved again.\" in the window's log", 10_000, () -> windowLog(ui).contains("Map drawings saved again."));
        rig.check(windowLog(ui).contains("Pins saved again."), "the window's log says: Pins saved again.");
        rig.check(windowLog(ui).contains("Map drawings saved again."), "the window's log says: Map drawings saved again.");
        String pinsAfter = read(documents.resolve("pins.txt"));
        rig.check(pinsAfter.contains("Alpha") && pinsAfter.contains("Bravo") && pinsAfter.contains("Charlie"),
                "pins.txt has the pin dropped while the disk was full, with the ones before and after: " + pinsAfter.strip().replace('\t', ' '));
        rig.check(drawings(documents) == 3, "graphics.kml has all three lines, the one drawn while the disk was full too: " + drawings(documents));
        ui.shot("room again: saved again");

        // the recording: does it come back by itself?
        long newVideoAt = waitForNewVideo(videos, sizes(videos), 20_000);
        boolean cameBack = newVideoAt > 0;
        rig.film.remark("The recording, room again: " + (cameBack ? "a new file " + (newVideoAt - roomFrom) / 1000 + " s after" : "NO new file within 20 s by itself")
                + ". REC on the OSD: " + (recordingNow(rig) ? "shown" : "not shown") + ".");
        if (!cameBack) {
            // what a pilot can do: VIDEO > RECORD VIDEO off and on (in the main window, the map window in front of it)
            Map<String, Long> beforeToggle = sizes(videos);
            ui.front(rig.main());
            MenusScenario.open(ui, "VIDEO");
            ui.click("RECORD VIDEO");
            Ui.pause(800);
            if (!MenusScenario.rowShowing(ui, "RECORD VIDEO")) MenusScenario.open(ui, "VIDEO");
            ui.click("RECORD VIDEO");
            Ui.pause(500);
            ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
            rig.film.remark("The window's log at RECORD VIDEO off and on: " + lines(windowLog(ui), "Recording", "recording", "LOW DISK"));
            long again = System.currentTimeMillis();
            long at = waitForNewVideo(videos, beforeToggle, 20_000);
            rig.film.remark("VIDEO > RECORD VIDEO off and on: " + (at > 0 ? "recording again, a new file " + (at - again) / 1000 + " s after" : "STILL no new file"));
            rig.check(at > 0, "with room again, RECORD VIDEO off and on records again");
        }
        ui.shot("room again: the recording");

        // ---------------------------------------------------------------- 4. landed: what is on the disk
        pilot.throttle(-1);
        pilot.until("the drone to come down", 60_000, () -> drone.readout().altM() < 0.5);
        pilot.disarm(drone);
        pilot.standby();
        pilot.until("the flight's record ended on disk", 15_000, () -> FlyScenario.names(tracks).stream().anyMatch(f -> f.endsWith(".record")
                && read(tracks.resolve(f)).contains("end=")));
        Ui.pause(3000);
        Path kept = rig.sandbox.root().resolve("small-disk");
        List<String> copied = SmallDisk.keep(mine, kept, FILLER);
        rig.film.remark("What the app left on the small disk (copied to " + kept + "): " + copied);

        Path gpx = FlightTrack.newest(kept.resolve("home").resolve("Documents").resolve("DRAUPNE Link").resolve("Tracks"));
        rig.check(gpx != null, "the flight's track file is there");
        if (gpx != null) {
            int points = FlightTrack.read(gpx).points().size();
            rig.check(points > pointsBefore, "the track has the points of before the disk ran full and after: " + points + " (" + pointsBefore + " before)");
            String strict = strictXml(gpx);
            rig.check(strict == null, "the track is a whole GPX file any map program reads (a strict XML reader): " + (strict == null ? "yes" : strict));
        }
        String record = "";
        for (String f : FlyScenario.names(tracks)) if (f.endsWith(".record")) record = read(tracks.resolve(f));
        rig.check(record.contains("DISCONNECT"), "the flight's record says it ended by DISCONNECT: " + record.replace("\n", " | "));
        Path keptVideos = kept.resolve("home").resolve("Videos").resolve("DRAUPNE Link");
        Map<String, Integer> frames = new LinkedHashMap<>();
        for (String f : FlyScenario.names(keptVideos)) if (f.endsWith(".mkv")) frames.put(f, frames(keptVideos.resolve(f)));
        rig.film.remark("The recordings, read back (frames; -1 does not open): " + frames + " - sizes when the disk ran full: " + videosBefore);
        for (String f : videosBefore.keySet()) {
            if (!f.endsWith(".mkv")) continue;
            Integer n = frames.get(f);
            rig.check(n != null && n > 0, "the recording begun before the disk ran full plays: " + f + " " + n + " frames");
        }
        List<String> empty = new ArrayList<>();
        for (String f : FlyScenario.names(keptVideos)) {
            try {
                if (Files.size(keptVideos.resolve(f)) == 0) empty.add(f);
            } catch (java.io.IOException ignored) {
            }
        }
        rig.film.remark("Files of no byte the full disk left in Videos: " + empty.size() + " " + empty);

        // what the pilot finds of this flight afterwards
        ui.front(ui.window("DRAUPNE Link map"));
        ui.click("DATA");
        Ui.pause(700);
        ui.click("PREVIOUS FLIGHTS");
        ui.until("the Previous flights window", 10_000, () -> ui.window("Previous flights") != null);
        Ui.pause(2000);
        rig.dump("previous-flights");
        ui.shot("PREVIOUS FLIGHTS after the full disk");
        java.awt.Window flights = ui.window("Previous flights");
        List<String> shown = new ArrayList<>();
        for (Component c : ui.components()) {
            if (Ui.edt(() -> javax.swing.SwingUtilities.getWindowAncestor(c)) != flights || c instanceof javax.swing.text.JTextComponent) continue;
            String t = Ui.edt(() -> Ui.textOf(c));
            if (t != null && !t.isBlank()) shown.add(t);
        }
        rig.film.remark("PREVIOUS FLIGHTS shows: " + shown);
        rig.check(shown.contains("DURATION") && !shown.contains("No armed flights yet."), "PREVIOUS FLIGHTS has the flight");
        ui.closeWindow(ui.window("Previous flights"));

        // the link log of the flight (on the normal disk): the picture and the sticks before, while full, after
        rig.film.remark("The link log, second by second: " + linkLogSummary(rig, fullFrom, fullUntil, roomFrom));
        String verdict = linkLogVerdict(rig, fullFrom, fullUntil);
        rig.check(verdict == null, "the picture and the sticks went on with the disk full (the link log): " + (verdict == null ? "yes" : verdict));
    }

    /**
     * The app's own settings and logs on a disk that is full from before it starts: it starts with the pilot's settings
     * (no first-start guide, the radio calibrated); a change made while full is said not saved and is in the file
     * once there is room - and still there at the next start.
     */
    @Test
    void theSettingsOnAFullDiskAreKeptAndAChangeReachesTheFileWhenThereIsRoom() throws Exception {
        String[] why = new String[1];
        SmallDisk disk = SmallDisk.mount(16, why);
        Assumptions.assumeTrue(disk != null, "No small disk to fill: " + why[0]);
        AtomicReference<de.droiddrone.bench.Sandbox> user = new AtomicReference<>();
        AtomicReference<String> fillWords = new AtomicReference<>();
        AtomicReference<Map<String, String>> firstSettings = new AtomicReference<>();
        try {
            AppRig.scenario("diskfull-settings").drones(1).appDataOn(disk.path())
                    .setting("settingsVersion", "5") // a pilot's settings of today (an older file is brought up to date at the start: that is a write)
                    .before(u -> fillWords.set(SmallDisk.fill(u.appData().getParent().resolve(FILLER), 0)))
                    .run(rig -> {
                        user.set(rig.sandbox);
                        Ui ui = rig.ui;
                        rig.film.remark("The settings folder on a full disk: " + disk + " - " + disk.df() + " (the filler: \"" + fillWords.get() + "\").");
                        Map<String, String> before = rig.settingsOnDisk();
                        firstSettings.set(before);
                        rig.check("BENCH".equals(before.get("pcName")) && "true".equals(before.get("setup.done")), "the settings file is the pilot's: " + before.size() + " keys");
                        Ui.pause(5000); // a first start opens the setup guide by itself within a few seconds
                        rig.check(ui.window("DRAUPNE Link setup") == null, "no first-start setup guide: the settings were read");
                        rig.check(ui.has("PILOT UAV"), "the window is up, ready to fly");
                        ui.shot("started with the settings folder full");

                        // a change while full: VIDEO > RECORD VIDEO off
                        MenusScenario.open(ui, "VIDEO");
                        ui.click("RECORD VIDEO");
                        Ui.pause(1500);
                        ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
                        ui.until("the window to say the settings could not be saved", 10_000, () -> windowLog(ui).contains("Could not save settings"));
                        rig.check(windowLog(ui).contains("Could not save settings"), "the window's log says the change is not saved: " + lines(windowLog(ui), "settings"));
                        rig.check(rig.settingsOnDisk().equals(before), "the settings file is as it was, not damaged (" + rig.settingsOnDisk().size() + " keys)");
                        ui.shot("a setting changed with the disk full");

                        Files.delete(u(rig).resolve(FILLER));
                        rig.film.remark("The filler deleted: " + disk.df());
                        // another change, with room: the one before goes with it (PcConfig.unwritten)
                        MenusScenario.open(ui, "VIDEO");
                        ui.click("CAMERA UPSIDE DOWN");
                        Ui.pause(1500);
                        ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
                        Map<String, String> after = rig.settingsOnDisk();
                        rig.check("false".equals(after.get("recordVideo")), "the change made while the disk was full is in the file now: recordVideo=" + after.get("recordVideo"));
                        rig.film.remark("The app's log on the full disk: " + (rig.log().isEmpty() ? "nothing" : rig.log().size() + " lines; first: " + rig.log().get(0)));
                        ui.shot("room again: both changes saved");
                    });
            de.droiddrone.bench.Sandbox was = user.get();
            AppRig.scenario("diskfull-settings-again").drones(1).sameUserAs(was).run(rig -> {
                Map<String, String> s = rig.settingsOnDisk();
                rig.check("false".equals(s.get("recordVideo")), "at the next start the change made on the full disk is there: recordVideo=" + s.get("recordVideo"));
                List<String> lost = new ArrayList<>();
                for (Map.Entry<String, String> e : firstSettings.get().entrySet()) {
                    // pcName: the app names this PC itself at its start (BENCH is no PC name: PCAlpha), full disk or not
                    if (!e.getKey().equals("pcName") && !e.getValue().equals(s.get(e.getKey()))) lost.add(e.getKey() + "=" + e.getValue() + " -> " + s.get(e.getKey()));
                }
                rig.check(lost.isEmpty(), "and every other setting of before the full disk, as it was (" + firstSettings.get().size() + " keys): " + lost);
                Ui.pause(5000);
                rig.check(rig.ui.window("DRAUPNE Link setup") == null, "no first-start setup guide");
            });
        } finally {
            de.droiddrone.bench.Sandbox was = user.get();
            if (was != null && !was.elsewhere().isEmpty()) SmallDisk.keep(was.appData().getParent(), was.root().resolve("small-disk"), FILLER);
            disk.close();
        }
    }

    /**
     * A settings file of an older version (no settingsVersion: the rig's own, as a pilot's from before 1.0.27 or so)
     * on a full disk. The app brings such a file up to date at its start - a write - and the start is the same in the
     * rig as in Main ({@code config.upgrade}).
     */
    @Test
    void anOlderSettingsFileOnAFullDiskStillStartsTheApp() throws Exception {
        String[] why = new String[1];
        SmallDisk disk = SmallDisk.mount(16, why);
        Assumptions.assumeTrue(disk != null, "No small disk to fill: " + why[0]);
        AtomicReference<de.droiddrone.bench.Sandbox> user = new AtomicReference<>();
        try {
            AppRig.scenario("diskfull-old-settings").drones(1).appDataOn(disk.path())
                    .before(u -> {
                        user.set(u);
                        SmallDisk.fill(u.appData().getParent().resolve(FILLER), 0);
                    })
                    .run(rig -> {
                        Ui ui = rig.ui;
                        rig.film.remark("An older settings file on a full disk: " + disk.df());
                        Ui.pause(3000);
                        rig.check(ui.has("PILOT UAV"), "the window is up, ready to fly");
                        rig.check(ui.window("DRAUPNE Link setup") == null, "no first-start setup guide: the settings were read");
                        rig.check("5".equals(rig.config.getString("settingsVersion")), "the settings are up to date in the app: settingsVersion="
                                + rig.config.getString("settingsVersion"));
                        Files.delete(u(rig).resolve(FILLER));
                        MenusScenario.open(ui, "VIDEO");
                        ui.click("CAMERA UPSIDE DOWN");
                        Ui.pause(1500);
                        ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
                        rig.check("5".equals(rig.settingsOnDisk().get("settingsVersion")), "with room again the update is in the file too: settingsVersion="
                                + rig.settingsOnDisk().get("settingsVersion"));
                        ui.shot("an older settings file on a full disk: started");
                    });
        } finally {
            de.droiddrone.bench.Sandbox was = user.get();
            if (was != null && !was.elsewhere().isEmpty()) SmallDisk.keep(was.appData().getParent(), was.root().resolve("small-disk"), FILLER);
            disk.close();
        }
    }

    /**
     * OVERWATCH with the disk full: a phone asleep in OVERWATCH sees someone and calls; its pictures and its clip have
     * nowhere to go. On a real disk the app's floor holds (under 2 GB free nothing of overwatch is written, LOW DISK is
     * said, the clip waits on the phone); this disk's free room is misreported (WSL's big disk), so the floor does not
     * hold and every write is tried and fails - the case of a disk that says it has room and has none.
     */
    @Test
    void anOverwatchAlertWithTheDiskFull() throws Exception {
        String[] why = new String[1];
        SmallDisk disk = SmallDisk.mount(64, why);
        Assumptions.assumeTrue(disk != null, "No small disk to fill: " + why[0]);
        try {
            AppRig.scenario("diskfull-overwatch").drones(1).homeOn(disk.path()).run(rig -> {
                Ui ui = rig.ui;
                Pilot pilot = new Pilot(rig);
                String name = String.valueOf(Ui.edt(() -> ui.all(javax.swing.JComboBox.class).get(0).getSelectedItem())).trim().split("\\s+")[0];
                VirtualDrone drone = rig.drone(name);
                pilot.connect();
                Ui.pause(3000);
                ui.click("OVERWATCH");
                pilot.until(name + " in OVERWATCH and its link ended", 90_000, () -> drone.mode() == VirtualDrone.OVERWATCH && ui.has("PILOT UAV"));
                Path filler = rig.sandbox.home().getParent().resolve(FILLER);
                SmallDisk.fill(filler, 0);
                rig.film.remark(name + " in OVERWATCH; the disk full: " + disk.df());
                int before = windowLog(ui).split("\\R").length;
                drone.someoneNow();
                pilot.until("the overwatch alert", 60_000, () -> rig.logged("overwatch alert"));
                long alertAt = System.currentTimeMillis();
                pilot.until("the motion to end and the clip to be tried", 90_000, () -> rig.logged("motion ended") || System.currentTimeMillis() - alertAt > 80_000);
                Ui.pause(10_000);
                ui.shot("an overwatch alert with the disk full");
                List<String> said = new ArrayList<>();
                String[] all = windowLog(ui).split("\\R");
                for (int i = Math.min(before, all.length); i < all.length; i++) said.add(all[i].strip());
                rig.film.remark("The window's log after the alert, the disk full (" + said.size() + " lines): "
                        + (said.size() > 40 ? said.subList(0, 40) + " ..." : said));
                rig.check(said.size() < 60, "the window's log is not flooded by what could not be stored: " + said.size() + " lines");
                rig.check(said.stream().anyMatch(l -> l.contains("MOTION")), "the alert is said in the window's log");
                Files.delete(filler);
                rig.film.remark("The filler deleted: " + disk.df());
                long roomAt = System.currentTimeMillis();
                boolean clip = false;
                while (System.currentTimeMillis() - roomAt < 90_000 && !(clip = rig.logged("full-quality clip"))) Ui.pause(1000);
                List<String> after = new ArrayList<>();
                all = windowLog(ui).split("\\R");
                for (String l : all) if (l.contains(name)) after.add(l.strip());
                rig.film.remark("Room again: " + (clip ? "the clip came" : "NO clip within 90 s") + ". The window's log of " + name + ": "
                        + (after.size() > 30 ? after.subList(after.size() - 30, after.size()) : after));
                List<String> stored = new ArrayList<>();
                Path overwatch = rig.sandbox.documents().resolve("Overwatch");
                if (Files.isDirectory(overwatch)) {
                    try (Stream<Path> files = Files.walk(overwatch)) {
                        for (Path p : files.filter(Files::isRegularFile).toList()) stored.add(overwatch.relativize(p) + " " + Files.size(p));
                    }
                }
                rig.film.remark("Documents\\DRAUPNE Link\\Overwatch: " + stored);
                ui.shot("room again: the overwatch clip");
            });
        } finally {
            disk.close();
        }
    }

    private static Path u(AppRig rig) {
        return rig.sandbox.appData().getParent();
    }

    // ------------------------------------------------------------------ the pilot's moves

    /** Throttle where the made-up drone neither climbs nor sinks (thrust to weight 2.5: 40 % throttle). */
    private static final double HOVER = -0.2;

    /** Right-click on the map, "Add pin here (...)", OK. */
    private static void dropPin(AppRig rig, Component map, int x, int y) {
        Ui ui = rig.ui;
        ui.rightClickAt(map, x, y);
        ui.until("the map's menu", 5000, () -> addPinItem(ui) != null);
        JMenuItem add = addPinItem(ui);
        String text = Ui.edt(add::getText);
        ui.click(add);
        ui.until("the Add pin dialog", 5000, () -> ui.window("Add pin") != null);
        ui.click("OK");
        ui.until("the Add pin dialog closed", 5000, () -> ui.window("Add pin") == null);
        rig.film.remark("Dropped: " + text);
    }

    private static JMenuItem addPinItem(Ui ui) {
        for (JMenuItem m : ui.all(JMenuItem.class)) {
            String t = Ui.edt(m::getText);
            if (t != null && t.startsWith("Add pin here")) return m;
        }
        return null;
    }

    /** MAP TOOLS > DRAW > LINE, two points, Esc. */
    private static void drawLine(Ui ui, Component map, int x1, int y1, int x2, int y2) {
        if (!ui.has("LINE")) { // DRAW opens its list and closes it again: pressed only when it is shut
            ui.click("DRAW");
            Ui.pause(500);
        }
        ui.click("LINE");
        ui.clickAt(map, x1, y1);
        ui.doubleClickAt(map, x2, y2);
        ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
        Ui.pause(500);
    }

    // ------------------------------------------------------------------ what is seen and what is on the disk

    /** The main window's log (the LOG card). */
    static String windowLog(Ui ui) {
        StringBuilder all = new StringBuilder();
        for (JTextArea a : ui.all(JTextArea.class)) {
            String t = Ui.edt(a::getText);
            if (t != null && t.contains("  ")) all.append(t).append('\n');
        }
        return all.toString();
    }

    private static String lines(String text, String... parts) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\\R")) for (String p : parts) if (l.contains(p)) {
            out.add(l.strip());
            break;
        }
        return out.toString();
    }

    private static List<String> matching(List<String> log, String... parts) {
        List<String> out = new ArrayList<>();
        for (String l : log) for (String p : parts) if (l.contains(p)) {
            out.add(l.length() > 200 ? l.substring(0, 200) + " ..." : l);
            break;
        }
        return out.size() > 30 ? out.subList(out.size() - 30, out.size()) : out;
    }

    /** A recording file grows (the OSD's REC shows while the pipeline has a file open). */
    private static boolean recordingNow(AppRig rig) {
        return recentFileGrows(rig.sandbox.videos());
    }

    private static boolean recentFileGrows(Path videos) {
        Map<String, Long> a = sizes(videos);
        Ui.pause(1500);
        Map<String, Long> b = sizes(videos);
        for (Map.Entry<String, Long> e : b.entrySet()) {
            if (e.getKey().endsWith(".mkv") && !e.getKey().contains("osd") && e.getValue() > a.getOrDefault(e.getKey(), -1L)) return true;
        }
        return false;
    }

    /** When a recording file that was not there before has some picture in it (20 kB); -1 when none came within ms. */
    private static long waitForNewVideo(Path videos, Map<String, Long> before, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            for (Map.Entry<String, Long> e : sizes(videos).entrySet()) {
                if (e.getKey().endsWith(".mkv") && !before.containsKey(e.getKey()) && e.getValue() > 20_000) return System.currentTimeMillis();
            }
            Ui.pause(500);
        }
        return -1;
    }

    private static Map<String, Long> sizes(Path dir) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String f : FlyScenario.names(dir)) {
            try {
                out.put(f, Files.size(dir.resolve(f)));
            } catch (java.io.IOException e) {
                out.put(f, -1L);
            }
        }
        return out;
    }

    private static long biggest(Path dir, String ending) {
        long most = 0;
        for (Map.Entry<String, Long> e : sizes(dir).entrySet()) if (e.getKey().endsWith(ending)) most = Math.max(most, e.getValue());
        return most;
    }

    private static int gpxPoints(Path tracks) {
        Path newest = FlightTrack.newest(tracks);
        if (newest == null) return 0;
        try {
            return FlightTrack.read(newest).points().size();
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    private static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (java.io.IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }

    private static int drawings(Path documents) {
        String kml = read(documents.resolve("graphics.kml"));
        int n = 0;
        for (int at = kml.indexOf("<Placemark"); at >= 0; at = kml.indexOf("<Placemark", at + 1)) n++;
        return n;
    }

    /** Null when a strict XML reader reads the file; else what it says. */
    private static String strictXml(Path file) {
        try {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.newDocumentBuilder().parse(file.toFile());
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** The frames FFmpeg reads from a recording; -1 when it does not open. */
    private static int frames(Path file) {
        AVFormatContext in = new AVFormatContext(null);
        if (avformat_open_input(in, file.toString(), null, (AVDictionary) null) != 0) return -1;
        AVPacket pkt = av_packet_alloc();
        int n = 0;
        while (av_read_frame(in, pkt) >= 0) {
            n++;
            av_packet_unref(pkt);
        }
        av_packet_free(pkt);
        avformat_close_input(in);
        return n;
    }

    // ------------------------------------------------------------------ the link log (on the run's disk)

    /** One row of the link log: the pictures shown and received a second, the longest gap between stick frames. */
    private record Row(long atMs, double fps, double rxFps, int rcMaxGap, String link) {}

    private static List<Row> linkLog(AppRig rig) {
        List<Row> rows = new ArrayList<>();
        try (Stream<Path> files = Files.list(rig.sandbox.logsDir())) {
            for (Path f : files.filter(p -> p.getFileName().toString().startsWith("link-")).sorted().toList()) {
                List<String> all = Files.readAllLines(f, StandardCharsets.UTF_8);
                if (all.isEmpty()) continue;
                List<String> head = List.of(all.get(0).split(","));
                int time = head.indexOf("time"), fps = head.indexOf("video_fps"), rx = head.indexOf("video_rx_fps"), gap = head.indexOf("rc_max_gap_ms"),
                        link = head.indexOf("link");
                java.time.LocalDate day = java.time.LocalDate.now();
                for (String line : all.subList(1, all.size())) {
                    String[] c = line.split(",", -1);
                    if (c.length <= Math.max(Math.max(fps, rx), gap) || c[fps].isEmpty()) continue;
                    try {
                        long at = java.time.LocalDateTime.of(day, java.time.LocalTime.parse(c[time])).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                        rows.add(new Row(at, Double.parseDouble(c[fps]), c[rx].isEmpty() ? -1 : Double.parseDouble(c[rx]),
                                c[gap].isEmpty() ? -1 : Integer.parseInt(c[gap]), c[link]));
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        } catch (java.io.IOException ignored) {
        }
        return rows;
    }

    private static String linkLogSummary(AppRig rig, long fullFrom, long fullUntil, long roomFrom) {
        List<Row> rows = linkLog(rig);
        return "before: " + stats(rows, fullFrom - 30_000, fullFrom) + "; disk full: " + stats(rows, fullFrom, fullUntil)
                + "; room again: " + stats(rows, roomFrom, roomFrom + 30_000);
    }

    private static String stats(List<Row> rows, long from, long until) {
        List<Row> in = rows.stream().filter(r -> r.atMs() >= from && r.atMs() < until).toList();
        if (in.isEmpty()) return "no rows";
        double minFps = in.stream().mapToDouble(Row::fps).min().orElse(0), avgFps = in.stream().mapToDouble(Row::fps).average().orElse(0);
        double avgRx = in.stream().mapToDouble(Row::rxFps).average().orElse(0);
        int maxGap = in.stream().mapToInt(Row::rcMaxGap).max().orElse(-1);
        return String.format("%d rows, picture shown %.1f fps on average of %.1f received (lowest shown %.0f), sticks' longest gap %d ms",
                in.size(), avgFps, avgRx, minFps, maxGap);
    }

    /**
     * Null when the app showed the picture it got and sent the sticks while the disk was full; else what was wrong.
     * Shown against received, not against before: the made-up drone's own encoder - on this PC, beside the app - makes
     * fewer pictures a second when its picture steps up to 1080p, disk or not (seen 2026-11-24: 29 -> 16 fps received
     * from that step on, and as many shown).
     */
    private static String linkLogVerdict(AppRig rig, long fullFrom, long fullUntil) {
        List<Row> rows = linkLog(rig);
        List<Row> full = rows.stream().filter(r -> r.atMs() >= fullFrom + 2000 && r.atMs() < fullUntil && r.rxFps() >= 0).toList();
        if (full.size() < 20) return "the link log has too few rows of the full disk to say (" + full.size() + ")";
        double shown = full.stream().mapToDouble(Row::fps).average().orElse(0);
        double received = full.stream().mapToDouble(Row::rxFps).average().orElse(0);
        long noPicture = full.stream().filter(r -> r.fps() < 1).count();
        int gap = full.stream().mapToInt(Row::rcMaxGap).max().orElse(-1);
        List<String> wrong = new ArrayList<>();
        if (shown < received * 0.9 - 1) wrong.add(String.format("%.1f pictures a second shown of %.1f received", shown, received));
        if (noPicture > 0) wrong.add(noPicture + " rows without a picture shown");
        if (gap > 250) wrong.add("a gap of " + gap + " ms between stick frames");
        return wrong.isEmpty() ? null : String.join(", ", wrong);
    }
}
