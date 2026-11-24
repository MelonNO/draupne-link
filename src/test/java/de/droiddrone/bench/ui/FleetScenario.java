package de.droiddrone.bench.ui;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.FleetRows;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.fleet.VirtualDrone;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.awt.Component;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/**
 * The FLEET panel of the map window and the field round's fleet items (1.0.71): OUT OF ACTION set by hand on any
 * drone, kept until cleared, asked about at PILOT UAV; modes changed from the panel; LAST KNOWN.
 */
@Tag("ui")
class FleetScenario {
    // the panel's helpers are FleetRows' (shared with the real phone's FLEET scenario)
    private static void openFleet(Ui ui) {
        FleetRows.open(ui);
    }

    private static List<Component> rows(Ui ui) {
        return FleetRows.rows(ui);
    }

    private static String menuRow(Ui ui, String start) {
        return FleetRows.menuRow(ui, start);
    }

    @Test
    void outOfActionIsSetByHandKeptAndAskedAboutAtPilotUav() throws Exception {
        Sandbox[] user = new Sandbox[1];
        String[] marked = new String[1];
        AppRig.scenario("fleet-out-of-action").drones(3).run(rig -> {
            Ui ui = rig.ui;
            user[0] = rig.sandbox;
            openFleet(ui);
            rig.check(rows(ui).size() == 3, "FLEET lists the three drones: " + rows(ui).size());
            ui.shot("FLEET with three idle drones");

            ui.rightClickAt(rows(ui).get(0), 100, 15);
            String row = menuRow(ui, "Mark OUT OF ACTION");
            String name = row.substring(row.indexOf(':') + 1).trim();
            marked[0] = name;
            ui.click(row);
            rig.waitForLog(name + ": OUT OF ACTION", 5000);
            rig.waitForLog("DEEP SLEEP queued, check every 30 min", 5000);
            ui.shot(name + " marked OUT OF ACTION");
            new Pilot(rig).until("the mark in the settings file", 5000, () -> {
                try {
                    return String.valueOf(rig.settingsOnDisk().get("drone.outOfAction")).contains(name);
                } catch (java.io.IOException e) {
                    return false;
                }
            });
            // the phone is asked to sleep deeply, its drone's beeper left on (the made-up phone says what it was asked)
            VirtualDrone drone = rig.drone(name);
            new Pilot(rig).until("the phone to take the deep sleep", 40_000, () -> String.valueOf(drone.readout().power()).toUpperCase().contains("SLEEP"));
            rig.film.remark("The phone's power mode after the mark: " + drone.readout().power() + "; asked: " + drone.readout().asked());
            ui.shot(name + " asleep, OUT OF ACTION");

            // a mark is not cleared by the phone being heard: it is still there half a minute later
            Ui.pause(8000);
            rig.check(String.valueOf(rig.settingsOnDisk().get("drone.outOfAction")).contains(name), "the mark stays while the phone checks in");

            // PILOT UAV on it asks first; Cancel keeps the mark
            ui.front(rig.main());
            @SuppressWarnings("rawtypes")
            JComboBox phones = ui.all(JComboBox.class).get(0);
            List<String> entries = Ui.edt(() -> {
                List<String> e = new ArrayList<>();
                for (int i = 0; i < phones.getItemCount(); i++) e.add(String.valueOf(phones.getItemAt(i)));
                return e;
            });
            rig.film.remark("PHONE list: " + entries + ", chosen: " + Ui.edt(phones::getSelectedItem));
            // 1.0.71 (was the bench's F-007: the marked drone left the list, which showed another, while PILOT UAV
            // still meant the marked one): the chosen drone stays in the list, marked, until the pilot picks another
            String chosenNow = String.valueOf(Ui.edt(phones::getSelectedItem));
            rig.check(chosenNow.startsWith(name) && chosenNow.contains("OUT OF ACTION"),
                    "the PHONE list shows the drone PILOT UAV means, as OUT OF ACTION: \"" + chosenNow.trim() + "\"");
            ui.click("PILOT UAV");
            ui.until("the question before connecting", 5000, () -> ui.window("Out of action") != null);
            ui.shot("PILOT UAV on an OUT OF ACTION drone asks first");
            rig.check(ui.sees(name + " is OUT OF ACTION"), "the question names the drone");
            ui.click("Cancel");
            Ui.pause(800);
            rig.check(String.valueOf(rig.settingsOnDisk().get("drone.outOfAction")).contains(name), "Cancel keeps the mark");
            rig.check(ui.has("PILOT UAV"), "Cancel does not connect");
        });
        // closed and started again: the mark is still there
        AppRig.scenario("fleet-out-of-action-restart").drones(3).sameUserAs(user[0]).run(rig -> {
            Ui ui = rig.ui;
            rig.check(String.valueOf(rig.settingsOnDisk().get("drone.outOfAction")).contains(marked[0]), "the mark is in the settings after a restart");
            openFleet(ui);
            Ui.pause(3000);
            ui.shot("FLEET after the restart: " + marked[0] + " still OUT OF ACTION");
            // cleared by hand only: the row's menu offers it
            Component row = null;
            for (Component r : rows(ui)) {
                ui.rightClickAt(r, 100, 15);
                if (ui.buttons().stream().anyMatch(b -> b.toLowerCase().contains("out of action") && b.contains(marked[0]) && !b.startsWith("Mark"))) {
                    row = r;
                    break;
                }
                ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
            }
            rig.check(row != null, "a row's menu offers to clear the mark of " + marked[0]);
            if (row != null) {
                String clear = ui.buttons().stream().filter(b -> b.toLowerCase().contains("out of action") && b.contains(marked[0])).findFirst().orElseThrow();
                ui.click(clear);
                Ui.pause(1500);
                rig.check(!String.valueOf(rig.settingsOnDisk().get("drone.outOfAction")).contains(marked[0]), "cleared by hand: the mark is gone from the settings");
                ui.shot("the mark cleared by hand");
            }
        });
    }

    @Test
    void lastKnownListsEveryPhoneAndTheFlownDronesGrid() throws Exception {
        AppRig.scenario("fleet-last-known").drones(2).run(rig -> {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            String flown = FlightsScenario.fly(rig, pilot, true);
            openFleet(ui);
            if (!ui.has("LAST KNOWN")) {
                ui.click("MAP TOOLS");
                ui.click("DATA");
            }
            ui.click("LAST KNOWN");
            ui.until("the Last known window", 5000, () -> ui.window("Last known") != null);
            Window w = ui.window("Last known");
            rig.dump("last-known");
            javax.swing.JTable table = ui.first(javax.swing.JTable.class);
            List<List<String>> cells = Ui.edt(() -> {
                List<List<String>> all = new ArrayList<>();
                for (int r = 0; r < table.getRowCount(); r++) {
                    List<String> line = new ArrayList<>();
                    for (int c = 0; c < table.getColumnCount(); c++) line.add(String.valueOf(table.getValueAt(r, c)));
                    all.add(line);
                }
                return all;
            });
            rig.film.remark("LAST KNOWN: " + cells);
            ui.shot("LAST KNOWN after a flight on " + flown);
            rig.check(cells.size() == 2, "both phones are listed: " + cells.size());
            List<String> row = cells.stream().filter(c -> c.get(0).startsWith(flown)).findFirst().orElse(List.of("", ""));
            rig.check(row.size() > 1 && row.get(1).matches(".*\\d{4,}.*"), "the flown drone has a grid: " + row);
            ui.click("CLOSE");
            ui.until("the Last known window gone", 5000, () -> !w.isShowing());
        });
    }
}
