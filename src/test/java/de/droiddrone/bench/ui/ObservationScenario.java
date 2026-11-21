package de.droiddrone.bench.ui;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.fleet.VirtualDrone;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import javax.swing.JLabel;

/**
 * OBSERVATION used (2026-11-19 afternoon): three idle drones chosen, watched side by side - a picture from each -
 * one of them taken over with its tile's CONNECT and given back, the watching stopped. On the way: the line under
 * each tile is whole (F-035: "heading approximate" was cut to "heading approxi...").
 */
@Tag("ui")
class ObservationScenario {
    @Test
    void threeIdleDronesAreWatchedOneIsFlownAndGivenBack() throws Exception {
        AppRig.scenario("observation").drones(3).run(rig -> {
            Ui ui = rig.ui;
            @SuppressWarnings("rawtypes")
            JComboBox connectAs = ui.all(JComboBox.class).get(1);
            String observation = String.valueOf(Ui.edt(() -> connectAs.getItemAt(1)));
            rig.check(observation.startsWith("Observation"), "CONNECT AS offers observation: " + observation);
            ui.choose(connectAs, observation);
            ui.click("CHOOSE DRONES");
            ui.until("the list of drones to choose from", 5000, () -> ui.window("choose the drones") != null);
            for (VirtualDrone d : rig.fleet.drones()) ui.click(d.name() + " VIRTUAL - online");
            ui.shot("three drones ticked");
            ui.click("OK");
            ui.click("OBSERVE");
            ui.until("a picture from each of the three", 40_000, () -> rig.log().stream().filter(l -> l.contains("Video: H.26")).count() >= 3);
            Ui.pause(4000);
            ui.shot("three drones watched");
            rig.check(ui.sees("OBSERVING 3"), "the header says OBSERVING 3");
            int lines = 0;
            for (JLabel l : ui.all(JLabel.class)) {
                String t = Ui.edt(l::getText);
                if (t == null || !t.contains("FPS")) continue;
                lines++;
                int[] room = Ui.edt(() -> new int[]{l.getWidth(), l.getPreferredSize().width});
                rig.check(room[0] >= room[1], "the line under a tile is whole: \"" + t + "\" has " + room[0] + " px, needs " + room[1]);
            }
            rig.check(lines == 3, "each of the three tiles has its line (picture size and rate): " + lines);

            // one of them flown: its tile's CONNECT, then given back
            ui.click("CONNECT");
            ui.until("the link to the drone taken over", 40_000, () -> ui.has("LINK READY") && ui.has("STANDBY MODE"));
            Ui.pause(2500);
            ui.shot("one of them flown from its tile's CONNECT");
            ui.click("STANDBY MODE");
            Ui.pause(4000);
            ui.shot("after STANDBY MODE");
            if (ui.has("STOP OBSERVING")) {
                ui.click("STOP OBSERVING");
                Ui.pause(2500);
            }
            ui.shot("the end of the watch");
            rig.check(ui.has("OBSERVE") || ui.has("PILOT UAV"), "after it the LINK card offers a start again");
        });
    }
}
