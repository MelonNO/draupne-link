package de.droiddrone.bench.ui;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.fleet.VirtualDrone;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Window;
import java.util.List;

/**
 * The field round's flights (1.0.71) through the window: an armed flight is in PREVIOUS FLIGHTS with its drone's
 * name; a connection that never armed leaves nothing; a made-up drone, as the app treats it for a pilot, leaves
 * nothing either.
 */
@Tag("ui")
class FlightsScenario {
    /** A short armed flight on the chosen drone; its name. */
    static String fly(AppRig rig, Pilot pilot, boolean arm) {
        Ui ui = rig.ui;
        String name = String.valueOf(Ui.edt(() -> ui.all(javax.swing.JComboBox.class).get(0).getSelectedItem())).trim().split("\\s+")[0];
        VirtualDrone drone = rig.drone(name);
        pilot.connect();
        pilot.waitForVideo();
        ui.until("RC OUTPUT: LIVE", 10_000, () -> ui.sees("RC OUTPUT: LIVE"));
        if (arm) {
            pilot.arm(drone);
            pilot.throttle(0.15);
            pilot.until("the drone to climb", 15_000, () -> drone.readout().altM() > 2);
            Ui.pause(3000);
            pilot.throttle(-1);
            pilot.until("the drone to come down", 30_000, () -> drone.readout().altM() < 0.5);
            pilot.disarm(drone);
        } else {
            Ui.pause(3000);
        }
        pilot.standby();
        return name;
    }

    static Window openPreviousFlights(AppRig rig) {
        Ui ui = rig.ui;
        if (ui.has("POP OUT MAP")) ui.click("POP OUT MAP");
        ui.until("the map window", 10_000, () -> ui.window("DRAUPNE Link map") != null);
        if (!ui.has("PREVIOUS FLIGHTS")) {
            ui.click("MAP TOOLS");
            ui.click("DATA");
        }
        ui.click("PREVIOUS FLIGHTS");
        ui.until("the Previous flights window", 10_000, () -> ui.window("Previous flights") != null);
        return ui.window("Previous flights");
    }

    @Test
    void anArmedFlightIsKeptWithItsDronesName() throws Exception {
        AppRig.scenario("flights-armed").drones(2).run(rig -> {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            String name = fly(rig, pilot, true);
            pilot.until("the flight's record on disk", 15_000, () -> FlyScenario.names(rig.sandbox.tracks()).stream().anyMatch(f -> f.endsWith(".record")));

            Window flights = openPreviousFlights(rig);
            rig.dump("previous-flights");
            ui.shot("PREVIOUS FLIGHTS with one flight");
            rig.check(!ui.sees("No flights yet."), "the list is not empty");
            rig.check(texts(ui).stream().anyMatch(t -> t.contains(name)), "the flight shows its drone's name " + name + ": " + texts(ui));
            ui.closeWindow(flights);
            ui.until("the Previous flights window gone", 5000, () -> ui.window("Previous flights") == null);
        });
    }

    @Test
    void aConnectionThatNeverArmedLeavesNothing() throws Exception {
        AppRig.scenario("flights-never-armed").drones(2).run(rig -> {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            fly(rig, pilot, false);
            Ui.pause(3000); // the clean-up runs as the link ends
            List<String> tracks = FlyScenario.names(rig.sandbox.tracks());
            List<String> videos = FlyScenario.names(rig.sandbox.videos());
            rig.film.remark("Tracks: " + tracks + "  Videos: " + videos);
            rig.check(tracks.stream().noneMatch(f -> f.endsWith(".record") || f.endsWith(".gpx")), "no record and no track of a flight never armed: " + tracks);
            rig.check(videos.stream().noneMatch(f -> f.endsWith(".mkv")), "no recording of a flight never armed: " + videos);
            openPreviousFlights(rig);
            ui.shot("PREVIOUS FLIGHTS after a connection without arming");
            rig.check(ui.sees("No flights yet."), "PREVIOUS FLIGHTS says: No flights yet.");
        });
    }

    @Test
    void aMadeUpDroneLeavesNothingForAPilot() throws Exception {
        AppRig.scenario("flights-made-up").drones(2).madeUpDronesSaveNothing().run(rig -> {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            fly(rig, pilot, true);
            Ui.pause(3000);
            List<String> tracks = FlyScenario.names(rig.sandbox.tracks());
            List<String> videos = FlyScenario.names(rig.sandbox.videos());
            rig.film.remark("Tracks: " + tracks + "  Videos: " + videos);
            rig.check(tracks.stream().noneMatch(f -> f.endsWith(".record") || f.endsWith(".gpx") || f.equals("last-position.txt")), "nothing in Tracks from a made-up drone: " + tracks);
            rig.check(videos.isEmpty(), "nothing in Videos from a made-up drone: " + videos);
            rig.check(!java.nio.file.Files.exists(rig.sandbox.dataDir().resolve("last-known.txt"))
                    || !java.nio.file.Files.readString(rig.sandbox.dataDir().resolve("last-known.txt")).contains("Mobile"), "no last known place of a made-up drone on disk");
            openPreviousFlights(rig);
            ui.shot("PREVIOUS FLIGHTS after a made-up drone's armed flight");
            rig.check(ui.sees("No flights yet."), "PREVIOUS FLIGHTS says: No flights yet.");
        });
    }

    /**
     * REPORT and EXPORT of a flight used, not only looked at (2026-11-19 afternoon). Both ask where with a save dialog
     * that opens in Documents\DRAUPNE Link\Exports - also the first time, when the folder is not there yet (F-024) -
     * and what they write is there afterwards: the report a page, the export a folder with the flight's track.
     */
    @Test
    void aFlightsReportAndItsExportLandInTheExportsFolder() throws Exception {
        AppRig.scenario("flights-report-export").drones(2).run(rig -> {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            fly(rig, pilot, true);
            pilot.until("the flight's record on disk", 15_000, () -> FlyScenario.names(rig.sandbox.tracks()).stream().anyMatch(f -> f.endsWith(".record")));
            java.nio.file.Path exports = rig.sandbox.documents().resolve("Exports");
            rig.check(!java.nio.file.Files.exists(exports), "no Exports folder before the first report (the case F-024 is about)");
            openPreviousFlights(rig);
            Ui.pause(1500);

            ui.click("REPORT");
            ui.until("the Save report dialog", 10_000, () -> ui.window("Save report") != null);
            ui.shot("REPORT asks where");
            ui.click("Save");
            ui.until("the report to be said saved", 60_000, () -> ui.sees("Report saved"));
            ui.shot("the report saved");
            List<String> inExports = FlyScenario.names(exports);
            rig.check(inExports.stream().anyMatch(f -> f.startsWith("flight-") && f.endsWith("-report.html")), "the report is in Exports: " + inExports);
            for (String f : inExports) {
                if (!f.endsWith(".html")) continue;
                String page = java.nio.file.Files.readString(exports.resolve(f));
                rig.check(page.contains("<html") && page.length() > 2000, "the report is a page (" + page.length() + " characters)");
            }
            ui.click("OK");

            ui.click("EXPORT");
            ui.until("the Export to folder dialog", 10_000, () -> ui.window("Export to folder") != null);
            ui.shot("EXPORT asks where");
            ui.click("Save");
            ui.until("the export to be said done", 90_000, () -> ui.sees("Exported to") || ui.sees("Export failed"));
            ui.shot("the flight exported");
            rig.check(ui.sees("Exported to"), "EXPORT says where it put the flight");
            boolean track;
            try (java.util.stream.Stream<java.nio.file.Path> all = java.nio.file.Files.walk(exports)) {
                track = all.anyMatch(p -> p.getFileName().toString().endsWith(".gpx"));
            }
            rig.check(track, "the exported flight's track is under Exports: " + FlyScenario.names(exports));
            ui.click("OK");
        });
    }

    /** Everything written on the windows, and the rows of every list (a list paints its rows; they are no labels). */
    static List<String> texts(Ui ui) {
        List<String> all = new java.util.ArrayList<>(ui.texts());
        for (javax.swing.JList<?> list : ui.all(javax.swing.JList.class)) {
            Ui.edt(() -> {
                for (int i = 0; i < list.getModel().getSize(); i++) all.add(String.valueOf(list.getModel().getElementAt(i)));
                return null;
            });
        }
        return all;
    }
}
