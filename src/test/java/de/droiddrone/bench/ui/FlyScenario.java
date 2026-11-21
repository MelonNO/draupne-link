package de.droiddrone.bench.ui;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.fleet.VirtualDrone;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The flight every other scenario builds on, in the real window with the real mouse: PILOT UAV, the picture, ARM on
 * the radio, throttle, down again, STANDBY MODE - and what the app keeps of it.
 */
@Tag("ui")
class FlyScenario {
    @Test
    void connectArmFlyLandStandBy() throws Exception {
        AppRig.scenario("fly").drones(2).run(rig -> {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            ui.shot("started: two made-up drones, nothing connected");
            rig.check(ui.has("OFFLINE"), "the header says OFFLINE before connecting");
            rig.check(ui.sees("RC OUTPUT: NOT CONNECTED"), "the controller card says RC OUTPUT: NOT CONNECTED");
            String phone = String.valueOf(Ui.edt(() -> ui.all(javax.swing.JComboBox.class).get(0).getSelectedItem()));
            String name = phone.trim().split("\\s+")[0];
            VirtualDrone drone = rig.drone(name);

            pilot.connect();
            rig.check(ui.sees(name), "the card names the drone flown: " + name);
            ui.until("the flight controller's firmware on the card", 8000, () -> ui.sees("Betaflight")); // it is asked for a second after the link is up
            rig.check(ui.sees("Betaflight"), "the flight controller's firmware is shown");
            pilot.waitForVideo();
            ui.until("RC OUTPUT: LIVE", 10_000, () -> ui.sees("RC OUTPUT: LIVE"));
            ui.shot("connected: picture and telemetry");
            rig.check(!drone.readout().armed(), "connecting did not arm the drone");

            pilot.arm(drone);
            ui.until("FC: ARMED on the controller card", 5000, () -> ui.sees("FC: ARMED"));
            ui.shot("armed");
            pilot.throttle(0.15);
            pilot.until("the drone to climb", 15_000, () -> drone.readout().altM() > 2);
            ui.shot("flying");
            rig.check(!Ui.edt(() -> ui.find("CALIBRATE").isEnabled()), "CALIBRATE is locked while connected");

            pilot.throttle(-1);
            pilot.until("the drone to come down", 30_000, () -> drone.readout().altM() < 0.5);
            pilot.disarm(drone);
            ui.until("FC: DISARMED on the controller card", 5000, () -> ui.sees("FC: DISARMED"));
            ui.shot("landed and disarmed");

            pilot.standby();
            rig.waitForLog("in standby", 10_000);
            ui.until("OFFLINE again", 10_000, () -> ui.has("OFFLINE"));
            rig.check(!drone.readout().armed(), "the drone is disarmed after STANDBY MODE");

            // what is kept of an armed flight (1.0.71): its record, its track with the drone's name, its recording
            pilot.until("the flight's files", 15_000, () -> has(rig.sandbox.tracks(), ".record") && has(rig.sandbox.tracks(), ".gpx"));
            List<String> tracks = names(rig.sandbox.tracks());
            List<String> videos = names(rig.sandbox.videos());
            rig.film.remark("Tracks: " + tracks + "  Videos: " + videos);
            rig.check(tracks.stream().anyMatch(f -> f.endsWith(".gpx") && f.contains(name)), "a track file carries the drone's name: " + tracks);
            rig.check(videos.stream().anyMatch(f -> f.endsWith(".mkv") && f.contains(name)), "a recording carries the drone's name: " + videos);
            String record = Files.readString(rig.sandbox.tracks().resolve(tracks.stream().filter(f -> f.endsWith(".record")).findFirst().orElseThrow()));
            rig.check(record.contains(name), "the flight's record names the drone");
            rig.check(record.contains("DISCONNECT"), "the flight's record says it ended by DISCONNECT: " + record.replace("\n", " | ").replace("\r", ""));
        });
    }

    /**
     * A pilot who has been through Drone setup (2026-11-19 afternoon): the PC knows which switch arms. Every other
     * scenario flies as a pilot who has not - the OSD says ARM SWITCH NOT READ throughout - so this rule had no one
     * pressing it: with the ARM switch on at PILOT UAV the sticks are not sent until it has been off, and the drone
     * does not arm from a switch that was left on.
     */
    @Test
    void withTheArmSwitchKnownAndLeftOnTheSticksWaitForItToBeOff() throws Exception {
        AppRig.scenario("fly-arm-switch-known").drones(2).setting(de.droiddrone.pc.ArmSwitch.KEY, "4:1700-2100").run(rig -> {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            String name = String.valueOf(Ui.edt(() -> ui.all(javax.swing.JComboBox.class).get(0).getSelectedItem())).trim().split("\\s+")[0];
            VirtualDrone drone = rig.drone(name);
            rig.radio.handsOff();
            rig.radio.aux(1, 1); // left on after the last flight
            pilot.connect();
            pilot.waitForVideo();
            Ui.pause(4000);
            ui.shot("connected with the ARM switch on");
            List<String> said = ui.texts().stream().filter(t -> t.contains("RC OUTPUT")).toList();
            rig.check(!drone.readout().armed(), "a switch left on at PILOT UAV does not arm the drone");
            rig.check(!ui.sees("RC OUTPUT: LIVE"), "the sticks are not sent while the ARM switch is on from before: " + said);
            rig.check(said.stream().anyMatch(t -> t.contains("ARM")), "the controller card says it waits for the ARM switch: " + said);

            rig.radio.aux(1, -1);
            ui.until("RC OUTPUT: LIVE once the switch is off", 10_000, () -> ui.sees("RC OUTPUT: LIVE"));
            ui.shot("the switch off: sticks live");
            rig.check(!drone.readout().armed(), "still disarmed");
            pilot.arm(drone);
            ui.until("FC: ARMED on the controller card", 5000, () -> ui.sees("FC: ARMED"));
            pilot.throttle(0.15);
            pilot.until("the drone to climb", 15_000, () -> drone.readout().altM() > 2);
            ui.shot("armed and flying, the ARM switch known");
            pilot.throttle(-1);
            pilot.until("the drone to come down", 30_000, () -> drone.readout().altM() < 0.5);
            pilot.disarm(drone);
            pilot.standby();
            rig.check(!drone.readout().armed(), "the drone is disarmed after STANDBY MODE");
        });
    }

    private static boolean has(Path dir, String ending) {
        return names(dir).stream().anyMatch(f -> f.endsWith(ending));
    }

    static List<String> names(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }
}
