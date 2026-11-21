package de.droiddrone.bench.edge;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.CrashRig;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.bench.Ui;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The pilot's lost flight (field round, 2026-11-18): "the flight path was on screen after the flight ... the flight
 * path disappeared when the program restarted due to a Windows update". The app is killed dead in mid-flight - no
 * shutdown code runs - and started again as the same user: an armed flight is there (APP CLOSED), with its recording
 * and its path; a connection that never armed leaves nothing.
 */
@Tag("edge")
class KillScenario {
    private static List<String> names(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    private static Window openPreviousFlights(Ui ui) {
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
    void killedWhileArmedTheFlightIsThereAfterTheRestart() throws Exception {
        Sandbox user = CrashRig.killAt("kill-armed", "armed");
        List<String> tracksAtKill = names(user.tracks()), videosAtKill = names(user.videos());
        AppRig.scenario("kill-armed-after-the-restart").drones(2).sameUserAs(user).run(rig -> {
            Ui ui = rig.ui;
            rig.film.remark("On disk at the kill - Tracks: " + tracksAtKill + "  Videos: " + videosAtKill);
            rig.check(tracksAtKill.stream().anyMatch(f -> f.endsWith(".record")), "the flight's record was on disk at the kill");
            rig.check(tracksAtKill.stream().anyMatch(f -> f.endsWith(".gpx")), "the flight's track was on disk at the kill");
            rig.check(videosAtKill.stream().anyMatch(f -> f.endsWith(".mkv")), "the flight's recording was on disk at the kill");
            ui.shot("started again after the kill");
            List<String> tracks = names(rig.sandbox.tracks());
            String recordFile = tracks.stream().filter(f -> f.endsWith(".record")).findFirst().orElse(null);
            rig.check(recordFile != null, "the record is kept by the start after the kill: " + tracks);
            if (recordFile != null) {
                String record = Files.readString(rig.sandbox.tracks().resolve(recordFile));
                rig.film.remark("The record after the restart: " + record.replace("\r", "").replace("\n", " | "));
                rig.check(record.contains("APP CLOSED"), "the record says how it ended: APP CLOSED");
            }
            for (String v : names(rig.sandbox.videos())) {
                if (v.endsWith(".mkv")) rig.check(Files.size(rig.sandbox.videos().resolve(v)) > 50_000, "the recording " + v + " holds video: " + Files.size(rig.sandbox.videos().resolve(v)) + " bytes");
            }
            // the path is back on the map: the settings name the kept flight
            String kept = String.valueOf(rig.settingsOnDisk().get("map.keptFlights"));
            rig.film.remark("map.keptFlights = " + kept);
            rig.check(kept.contains(".gpx"), "the flight's path is among the map's kept flights");
            Window flights = openPreviousFlights(ui);
            ui.shot("PREVIOUS FLIGHTS after the kill");
            rig.dump("previous-flights-after-kill");
            rig.check(!ui.sees("No flights yet."), "PREVIOUS FLIGHTS lists the flight");
            List<String> rows = new ArrayList<>();
            for (javax.swing.JList<?> list : ui.all(javax.swing.JList.class)) {
                Ui.edt(() -> {
                    for (int i = 0; i < list.getModel().getSize(); i++) rows.add(String.valueOf(list.getModel().getElementAt(i)));
                    return null;
                });
            }
            rig.film.remark("The list's rows: " + rows);
            // it plays: Play is pressable and the time moves on
            if (ui.has("Play")) {
                ui.click("Play");
                Ui.pause(3500);
                ui.shot("the killed flight's recording playing");
            }
            ui.closeWindow(flights);
        });
    }

    @Test
    void killedWhileConnectedButNeverArmedNothingIsKept() throws Exception {
        Sandbox user = CrashRig.killAt("kill-never-armed", "connected");
        List<String> tracksAtKill = names(user.tracks()), videosAtKill = names(user.videos());
        AppRig.scenario("kill-never-armed-after-the-restart").drones(2).sameUserAs(user).run(rig -> {
            Ui ui = rig.ui;
            rig.film.remark("On disk at the kill - Tracks: " + tracksAtKill + "  Videos: " + videosAtKill);
            Ui.pause(2000);
            List<String> tracks = names(rig.sandbox.tracks()), videos = names(rig.sandbox.videos());
            rig.film.remark("After the restart - Tracks: " + tracks + "  Videos: " + videos);
            rig.check(tracks.stream().noneMatch(f -> f.endsWith(".record") || f.endsWith(".gpx")), "no record and no track left of a connection never armed: " + tracks);
            rig.check(videos.stream().noneMatch(f -> f.endsWith(".mkv")), "no recording left of a connection never armed: " + videos);
            openPreviousFlights(ui);
            ui.shot("PREVIOUS FLIGHTS after the kill of a connection never armed");
            rig.check(ui.sees("No flights yet."), "PREVIOUS FLIGHTS says: No flights yet.");
        });
    }
}
