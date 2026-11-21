package de.droiddrone.bench.night;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Ui;
import de.droiddrone.bench.Watch;
import de.droiddrone.pc.fleet.VirtualDrone;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One flight that lasts for hours ({@code BENCH_HOURS}). Every other scenario flies for seconds; a flight of two and a
 * half hours is what none had tried: the same link, the same recording, the same track, the same OSD for 9,000
 * seconds - past the hour on every clock, across midnight when started late. The drone flies slow circuits in ANGLE
 * mode on a pack big enough to last, the link is made poor for a minute now and then, and once a minute the app's
 * figures go into soak/long-flight.tsv: memory, threads, the window's slowest answer, the sizes of the track and of
 * the recording as they grow.
 * <p>
 * What must hold: the drone stays armed and under control to the end (nothing in the app ends or drops a long
 * flight); the track and the recording grow all the way; memory does not climb with the flight's length; and
 * afterwards the flight is one flight - one record, one track from take-off to landing - shown in PREVIOUS FLIGHTS
 * with its true length, and its REPORT can be written.
 */
@Tag("night")
class LongFlightScenario {
    private static final Pattern TIME = Pattern.compile("<time>([^<]+)</time>");

    @Test
    void oneFlightForHours() throws Exception {
        long flyMs = Math.max(4 * 60_000L, Night.runMs() - 4 * 60_000L); // the hours given, less the minutes round the flight
        // a pack for the whole flight (it draws about 20 A): what a low one does is the battery tests' matter
        int pack = (int) Math.max(80_000, flyMs / 3_600_000.0 * 40_000);
        AppRig.scenario("night-long-flight").drones(2).pack(pack).setting(de.droiddrone.pc.ArmSwitch.KEY, "4:1700-2100").run(rig -> {
            Ui ui = rig.ui;
            ui.autoShot(false);
            Pilot pilot = new Pilot(rig);
            String name = String.valueOf(Ui.edt(() -> ui.all(javax.swing.JComboBox.class).get(0).getSelectedItem())).trim().split("\\s+")[0];
            VirtualDrone drone = rig.drone(name);
            Night.Table table = new Night.Table("long-flight", "minutes", "heap MB", "threads", "slowest answer ms", "track KB", "recording MB",
                    "height m", "pack V", "app log KB");

            rig.radio.sticksAtRest();
            rig.radio.aux(1, -1);
            rig.radio.aux(2, 1); // ANGLE: it levels itself, as a drone flown for hours would be flown
            pilot.connect();
            pilot.waitForVideo();
            ui.until("RC OUTPUT: LIVE", 20_000, () -> ui.sees("RC OUTPUT: LIVE"));
            pilot.arm(drone);
            long armedAt = System.currentTimeMillis();
            ui.shot("armed: the long flight begins (" + flyMs / 60_000 + " min)");

            List<Long> heaps = new ArrayList<>();
            long end = armedAt + flyMs, nextRow = armedAt + 60_000, nextPoor = armedAt + 11 * 60_000, poorUntil = 0, nextShot = armedAt + 20 * 60_000;
            long lastTrack = -1, lastVideo = -1;
            int stuckTrack = 0, stuckVideo = 0, notReady = 0, rows = 0;
            String ended = null;
            while (System.currentTimeMillis() < end) {
                long now = System.currentTimeMillis();
                double t = (now - armedAt) / 1000.0;
                double alt = drone.readout().altM();
                // slow circuits between 20 and 40 m: a little roll and pitch, the throttle by the height
                double throttle = alt < 20 ? 0.2 : alt > 40 ? -0.35 : alt < 28 ? 0.08 : -0.12;
                rig.radio.sticks(0.22 * Math.sin(t / 11), 0.22 * Math.cos(t / 17), 0.1 * Math.sin(t / 29), throttle);
                if (now >= nextPoor) { // a minute of a poor link every eleven
                    drone.link().set(6, 40, 30, 0);
                    poorUntil = now + 60_000;
                    nextPoor = now + 11 * 60_000;
                } else if (poorUntil != 0 && now >= poorUntil) {
                    drone.link().clear();
                    poorUntil = 0;
                }
                if (now >= nextRow) {
                    nextRow += 60_000;
                    rows++;
                    long heap = Watch.usedMb();
                    heaps.add(heap);
                    long track = Night.bytes(rig.sandbox.tracks(), ".gpx"), video = Night.bytes(rig.sandbox.videos(), ".mkv");
                    table.row((now - armedAt) / 60_000.0, heap, rig.watch.threads(), rig.watch.maxLagMs(), track / 1024, video / (1024 * 1024),
                            alt, drone.readout().volts(), Night.bytes(rig.sandbox.logsDir(), ".log") / 1024);
                    stuckTrack = track > lastTrack ? 0 : stuckTrack + 1;
                    stuckVideo = video > lastVideo ? 0 : stuckVideo + 1;
                    lastTrack = track;
                    lastVideo = video;
                    notReady = ui.has("LINK READY") ? 0 : notReady + 1;
                    if (!drone.readout().armed()) ended = "the drone is no longer armed (" + drone.readout().flightMode() + ", " + drone.readout().failsafe() + ")";
                    else if (stuckTrack >= 3) ended = "the track file has not grown for three minutes";
                    else if (stuckVideo >= 3) ended = "the recording has not grown for three minutes";
                    else if (notReady >= 3) ended = "the header has not said LINK READY for three minutes";
                    else if (!rig.watch.problems().isEmpty()) ended = "the app had an error or a freeze: " + rig.watch.problems().get(0).toString().lines().findFirst().orElse("");
                    if (ended != null) break;
                }
                if (now >= nextShot) {
                    nextShot += 30 * 60_000;
                    ui.shot((now - armedAt) / 60_000 + " min into the flight");
                }
                Ui.pause(200);
            }
            long flownS = (System.currentTimeMillis() - armedAt) / 1000;
            drone.link().clear();
            ui.shot(flownS / 60 + " min flown" + (ended == null ? "" : " - ended early"));
            rig.check(ended == null, "the flight lasted its " + flyMs / 60_000 + " min" + (ended == null ? "" : ": after " + flownS / 60 + " min " + ended));

            // down, disarmed, the link ended
            rig.radio.sticks(0, 0, 0, -1);
            if (drone.readout().armed()) {
                pilot.until("the drone to come down", 180_000, () -> drone.readout().altM() < 0.5);
                pilot.disarm(drone);
            }
            long landedS = (System.currentTimeMillis() - armedAt) / 1000;
            if (ui.has("STANDBY MODE")) {
                ui.click("STANDBY MODE");
                Ui.pause(600);
                if (ui.has("Keep flying")) ui.click("Standby mode");
            }
            ui.waitForControl("PILOT UAV", 60_000);
            Ui.pause(4000);

            long records = Night.count(rig.sandbox.tracks(), ".record"), tracks = Night.count(rig.sandbox.tracks(), ".gpx");
            rig.check(records == 1 && tracks == 1, "a long flight is one flight on disk: " + records + " record(s), " + tracks + " track(s)");
            Path track = null;
            try (java.util.stream.Stream<Path> files = Files.list(rig.sandbox.tracks())) {
                track = files.filter(p -> p.getFileName().toString().endsWith(".gpx")).findFirst().orElse(null);
            }
            if (track != null) {
                String gpx = Files.readString(track, StandardCharsets.UTF_8);
                Matcher m = TIME.matcher(gpx);
                Instant first = null, last = null;
                int points = 0;
                long widest = 0;
                while (m.find()) {
                    Instant at = Instant.parse(m.group(1));
                    if (points == 1) first = at; // the first is the file's own date
                    if (last != null && points > 1) widest = Math.max(widest, at.toEpochMilli() - last.toEpochMilli());
                    last = at;
                    points++;
                }
                long span = first == null ? 0 : (last.toEpochMilli() - first.toEpochMilli()) / 1000;
                rig.film.remark("The track: " + points + " points over " + span / 60 + " min, " + gpx.length() / 1024 + " KB; the widest gap between two points " + widest / 1000 + " s.");
                rig.check(span >= landedS - 60, "the track runs from take-off to landing: " + span + " s of " + landedS + " s");
                rig.check(widest <= 30_000, "the track has no hole: the widest gap between two points is " + widest / 1000 + " s");
            }
            long video = Night.bytes(rig.sandbox.videos(), ".mkv");
            rig.film.remark("The recording: " + Night.count(rig.sandbox.videos(), ".mkv") + " file(s), " + video / (1024 * 1024) + " MB.");
            rig.check(video > flownS * 20_000, "the recording has the flight's length in it: " + video / (1024 * 1024) + " MB for " + flownS / 60 + " min");
            if (heaps.size() >= 30) {
                double perMinute = Night.slope(heaps.subList(10, heaps.size()));
                rig.film.remark(String.format("Memory in use from minute 10 on: %+.0f KB a minute (%d MB at minute 10, %d MB at the end).",
                        perMinute * 1024, heaps.get(10), heaps.get(heaps.size() - 1)));
                rig.check(perMinute * 1024 < 150, String.format("memory does not climb with the flight's length: %+.0f KB a minute", perMinute * 1024));
            }
            rig.check(rig.watch.threads() < rig.watch.threadsAtStart() + 80, "threads do not pile up: " + rig.watch.threadsAtStart() + " at the start, " + rig.watch.threads() + " now");

            // the flight as the pilot finds it afterwards
            Night.openPreviousFlights(ui);
            ui.shot("PREVIOUS FLIGHTS with the long flight");
            String duration = Night.after(ui, "DURATION"), armed = Night.after(ui, "ARMED");
            long shown = Night.seconds(armed);
            rig.check(shown >= 0 && Math.abs(shown - landedS) <= 45, "PREVIOUS FLIGHTS shows its true armed time: \"" + armed + "\" for " + landedS + " s (DURATION \"" + duration + "\")");
            ui.click("REPORT");
            ui.until("the Save report dialog", 15_000, () -> ui.window("Save report") != null);
            ui.click("Save");
            ui.until("the report of a long flight to be written", 180_000, () -> ui.sees("Report saved") || ui.sees("Report failed"));
            ui.shot("the long flight's report");
            rig.check(ui.sees("Report saved"), "the REPORT of a flight of " + landedS / 60 + " min is written");
            ui.click("OK");
        });
    }
}
