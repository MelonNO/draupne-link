package de.droiddrone.bench.soak;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.bench.Ui;
import de.droiddrone.bench.Watch;
import de.droiddrone.pc.fleet.VirtualDrone;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * The long run: the real app flying its made-up drones one flight after the other for hours ({@code BENCH_HOURS}) -
 * PILOT UAV, arm, fly on a network that is bad in a new way each time, every third flight a cut that takes the link
 * away in the air, land, STANDBY MODE - and what a long day must not do to it: no flight lost (every armed flight has
 * its record on disk), no freeze, no error nobody caught, memory and threads that do not keep growing. One row a
 * flight goes to soak/app.tsv in the run's folder: the report draws the curves.
 */
@Tag("soak")
class SoakScenario {
    private static final double HOURS = Double.parseDouble(System.getProperty("bench.hours", "0.1"));
    /** Flights after which memory in use must hold: the caches full, the map's 30 earlier flights there. */
    private static final int SETTLED_AFTER = 50;

    /** The least-squares slope of the values, in their unit a step. */
    static double slope(List<Long> values) {
        int n = values.size();
        double meanX = (n - 1) / 2.0, meanY = values.stream().mapToLong(Long::longValue).average().orElse(0), up = 0, down = 0;
        for (int i = 0; i < n; i++) {
            up += (i - meanX) * (values.get(i) - meanY);
            down += (i - meanX) * (i - meanX);
        }
        return down == 0 ? 0 : up / down;
    }

    private static long count(Path dir, String ending) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(ending)).count();
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    @Test
    void flightAfterFlightForHours() throws Exception {
        long endAt = System.currentTimeMillis() + (long) (HOURS * 3_600_000);
        AppRig.scenario("soak-app").drones(3).run(rig -> {
            Ui ui = rig.ui;
            ui.autoShot(false); // hours of pictures of the same two buttons help nobody: one per flight, and every failure
            Pilot pilot = new Pilot(rig);
            Random random = new Random(Long.getLong("bench.seed", 20261119L));
            Path table = Files.createDirectories(Sandbox.runRoot().resolve("soak")).resolve("app.tsv");
            Files.writeString(table, "minutes\theap MB\tthreads\tslowest answer ms\tflights on disk\tflights flown\tflight s\n", StandardCharsets.UTF_8);
            long t0 = System.currentTimeMillis();
            int flown = 0, cuts = 0, heapStart = -1;
            List<Long> heaps = new ArrayList<>();
            @SuppressWarnings("rawtypes")
            JComboBox phones = ui.all(JComboBox.class).get(0);
            int failures = 0, tried = 0;
            while ((System.currentTimeMillis() < endAt || flown < 2) && failures < 5) {
                long f0 = System.currentTimeMillis();
                tried++;
                try {
                int n = Ui.edt(phones::getItemCount);
                String entry = String.valueOf(Ui.edt(() -> phones.getItemAt(random.nextInt(Math.max(1, phones.getItemCount())))));
                if (n > 1 && !entry.equals(String.valueOf(Ui.edt(phones::getSelectedItem)))) ui.choose(phones, entry);
                String name = String.valueOf(Ui.edt(phones::getSelectedItem)).trim().split("\\s+")[0];
                VirtualDrone drone = rig.drone(name);
                // this flight's network: from fine to poor
                double loss = random.nextInt(4) == 0 ? 0 : random.nextInt(15);
                int jitter = random.nextInt(4) == 0 ? 0 : random.nextInt(80);
                drone.link().set(loss, jitter, random.nextInt(60), random.nextInt(3) == 0 ? 40 : 0);
                pilot.connect();
                pilot.waitForVideo();
                ui.until("RC OUTPUT: LIVE", 20_000, () -> ui.sees("RC OUTPUT: LIVE"));
                pilot.arm(drone);
                pilot.throttle(0.15);
                pilot.until("the drone to climb", 20_000, () -> drone.readout().altM() > 2);
                pilot.throttle(-0.2);
                Ui.pause(4000 + random.nextInt(8000));
                boolean cut = flown % 3 == 2;
                if (cut) {
                    cuts++;
                    long ms = 3000 + random.nextInt(5000);
                    drone.link().silence(System.currentTimeMillis(), ms);
                    Ui.pause(ms + 500);
                    // failsafe took the motors; the link comes back by itself; the switch goes off as a pilot's would
                    rig.radio.handsOff();
                    rig.radio.aux(1, -1);
                    // back means the header says LINK READY again, and a moment for the flight controller's fresh word.
                    // (The first long run pressed STANDBY MODE in the very moment the link returned, 5 times in 183:
                    // the app rightly asked "may still be flying - keep flying?", and nobody answered.)
                    ui.until("the link back after the cut", 40_000, () -> ui.has("LINK READY") || ui.has("PILOT UAV"));
                    Ui.pause(1500);
                    rig.check(!drone.readout().armed(), "flight " + (flown + 1) + ": after a " + ms + " ms cut the drone is disarmed (failsafe), not re-armed");
                } else {
                    pilot.throttle(-1);
                    pilot.until("the drone to come down", 60_000, () -> drone.readout().altM() < 0.5);
                    pilot.disarm(drone);
                }
                drone.link().clear();
                if (ui.has("STANDBY MODE")) {
                    ui.click("STANDBY MODE");
                    Ui.pause(500);
                    if (ui.has("Keep flying")) { // the app's question while it has no fresh word of a drone that was armed
                        rig.film.remark("Flight " + (flown + 1) + ": STANDBY MODE asked \"may still be flying\" first; answered Standby mode.");
                        ui.click("Standby mode");
                    }
                }
                ui.waitForControl("PILOT UAV", 30_000);
                flown++;
                Ui.pause(1500); // the flight's files are closed as the link ends
                long heap = Watch.usedMb();
                heaps.add(heap);
                if (heapStart < 0) heapStart = (int) heap;
                long records = count(rig.sandbox.tracks(), ".record");
                Files.writeString(table, String.format(java.util.Locale.ROOT, "%.1f\t%d\t%d\t%d\t%d\t%d\t%d\n", (System.currentTimeMillis() - t0) / 60_000.0, heap,
                        rig.watch.threads(), rig.watch.maxLagMs(), records, flown, (System.currentTimeMillis() - f0) / 1000), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                if (flown % 10 == 1) ui.shot("flight " + flown + " done (" + name + (cut ? ", cut in the air" : "") + ")");
                if (records != flown) {
                    rig.check(false, "after flight " + flown + " there are " + records + " flight records on disk: a flight was lost (or made twice)");
                    break;
                }
                } catch (Throwable t) {
                    // one flight that went wrong is a finding, not the end of a night's run: said, the app brought
                    // back to "nothing connected" as a pilot would, and on with the next
                    failures++;
                    rig.check(false, "flight try " + tried + " (after " + (System.currentTimeMillis() - t0) / 60_000 + " min) went wrong: " + t.getMessage());
                    rig.radio.handsOff();
                    rig.radio.aux(1, -1);
                    for (VirtualDrone d : rig.fleet.drones()) d.link().clear();
                    Ui.pause(3000);
                    try {
                        if (ui.has("STANDBY MODE")) ui.click("STANDBY MODE");
                        ui.waitForControl("PILOT UAV", 90_000);
                    } catch (Throwable stuck) {
                        rig.check(false, "the app could not be brought back to PILOT UAV after that: " + stuck.getMessage());
                        break;
                    }
                    // a flight that armed before it went wrong left its record: the count goes on from what is on disk
                    flown = (int) count(rig.sandbox.tracks(), ".record");
                }
            }
            long records = count(rig.sandbox.tracks(), ".record"), tracks = count(rig.sandbox.tracks(), ".gpx"), videos = count(rig.sandbox.videos(), ".mkv");
            rig.film.remark("Flights flown " + flown + " (" + cuts + " with a cut in the air) in " + (System.currentTimeMillis() - t0) / 60_000 + " min. On disk: "
                    + records + " records, " + tracks + " tracks, " + videos + " video files.");
            rig.check(records == flown && tracks == flown, "every armed flight has its record and its track: " + records + " / " + tracks + " of " + flown);
            rig.check(videos >= flown, "every armed flight has its recording: " + videos + " files for " + flown + " flights");
            // memory: the last third of the flights against the first third (a few flights fill caches; after that it must hold)
            if (heaps.size() >= 9) {
                int third = heaps.size() / 3;
                double first = heaps.subList(0, third).stream().mapToLong(Long::longValue).average().orElse(0);
                double last = heaps.subList(heaps.size() - third, heaps.size()).stream().mapToLong(Long::longValue).average().orElse(0);
                rig.film.remark(String.format("Heap in use: %.0f MB over the first third of the flights, %.0f MB over the last third.", first, last));
                rig.check(last < first * 1.6 + 80, String.format("memory does not keep growing: %.0f MB at first, %.0f MB at the end", first, last));
            }
            // and its slope once the caches are full and the map has its 30 earlier flights (F-015: 76 KB a flight,
            // evenly, for hours - which the check above let pass twice)
            if (heaps.size() >= SETTLED_AFTER + 100) {
                double perFlight = slope(heaps.subList(SETTLED_AFTER, heaps.size()));
                rig.film.remark(String.format("Heap after flight %d: %+.0f KB a flight.", SETTLED_AFTER, perFlight * 1024));
                rig.check(perFlight * 1024 < 25, String.format("memory is level once the app has settled: %+.0f KB a flight over %d flights", perFlight * 1024, heaps.size() - SETTLED_AFTER));
            }
            rig.check(rig.watch.threads() < rig.watch.threadsAtStart() + 120, "threads do not pile up: " + rig.watch.threadsAtStart() + " at the start, " + rig.watch.threads() + " now");
        });
    }
}
