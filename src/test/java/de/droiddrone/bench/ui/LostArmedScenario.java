package de.droiddrone.bench.ui;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.fleet.VirtualDrone;
import de.droiddrone.pc.gui.BenchAccess;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Window;

/**
 * A drone lost while armed, and what this PC then holds back (2026-11-19 evening). The other way a map loads nothing
 * "though the computer has internet" - on purpose: for ten minutes after a link that last said ARMED went quiet, a
 * drone here may be flying, and nothing is fetched so the sticks and the picture have the network. The scenario
 * loses a drone in the air for good, ends its link as a pilot would, and checks that the PC says so (the LINK card's
 * "Downloads wait: ...", IT'S DOWN - RESUME), that the map fetches nothing meanwhile, and that it fetches again the
 * moment the pilot says the drone is down.
 */
@Tag("ui")
class LostArmedScenario {
    @Test
    void aDroneLostArmedHoldsTheMapBackUntilThePilotSaysItIsDown() throws Exception {
        AppRig.scenario("lost-armed-map-held").drones(2).run(rig -> {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            String name = String.valueOf(Ui.edt(() -> ui.all(javax.swing.JComboBox.class).get(0).getSelectedItem())).trim().split("\\s+")[0];
            VirtualDrone drone = rig.drone(name);
            pilot.connect();
            pilot.waitForVideo();
            ui.until("RC OUTPUT: LIVE", 10_000, () -> ui.sees("RC OUTPUT: LIVE"));
            pilot.arm(drone);
            pilot.throttle(0.15);
            pilot.until("the drone to climb", 15_000, () -> drone.readout().altM() > 2);
            pilot.throttle(-0.2);
            Ui.pause(2000);

            drone.link().silence(System.currentTimeMillis(), 30 * 60_000L); // not heard again in this scenario
            ui.until("the PC to count the link lost", 20_000, () -> !ui.has("LINK READY"));
            ui.until("LOST DRONE to be offered", 30_000, () -> ui.has("LOST DRONE"));
            ui.shot("the link lost in the air: LOST DRONE is offered");

            // the pilot ends the link without marking the drone lost
            ui.click("STANDBY MODE");
            Ui.pause(800);
            if (ui.has("Keep flying")) {
                ui.shot("STANDBY MODE asks first: the drone was armed");
                ui.click("Standby mode");
            }
            ui.waitForControl("PILOT UAV", 30_000);
            Ui.pause(2500);
            ui.shot("the link ended; the drone was lost armed");
            rig.check(ui.has("IT'S DOWN - RESUME"), "the LINK card offers IT'S DOWN - RESUME");
            rig.check(ui.sees("Downloads wait"), "and says why downloads wait: "
                    + ui.texts().stream().filter(t -> t.contains("Downloads wait")).findFirst().orElse("-"));

            MapToolsScenario.openTools(ui, "TOOLS");
            Component map = MapToolsScenario.map(ui);
            long asked = rig.web.requestsTo("kartverket") + rig.web.requestsTo("opentopomap");
            MapToolsScenario.goTo(ui, "61.5000, 10.5000", "Go");
            Ui.pause(7000);
            boolean missing = Ui.edt(() -> BenchAccess.mapTilesMissing(map));
            long askedAfter = rig.web.requestsTo("kartverket") + rig.web.requestsTo("opentopomap");
            ui.shot("the map at a new place while the PC holds back");
            rig.check(missing, "the map at a new place stays empty while a drone lost armed may be flying");
            rig.check(askedAfter == asked, "and no tile is asked of the internet meanwhile: " + (askedAfter - asked) + " requests");

            ui.front(rig.main());
            ui.click("IT'S DOWN - RESUME");
            ui.until("IT'S DOWN - RESUME asks first", 5000, () -> ui.window("It's down - resume") != null);
            ui.shot("IT'S DOWN - RESUME asks");
            Window question = ui.window("It's down - resume");
            Component yes = null;
            for (Component c : ui.all("It's down - resume")) if (c instanceof javax.swing.JButton && Ui.edt(() -> SwingUtilities.getWindowAncestor(c)) == question) yes = c;
            rig.check(yes != null, "the question has its own It's down - resume button");
            if (yes == null) return;
            ui.click(yes);
            Ui.pause(1500);
            rig.check(rig.logged("IT'S DOWN - RESUME"), "the log says the pilot called the drone down");
            ui.front(ui.window("DRAUPNE Link map"));
            boolean came = MapNetworkScenario.whole(map, 40_000);
            ui.shot("the map after IT'S DOWN - RESUME");
            rig.check(came, "the map has its tiles by itself once the pilot said the drone is down");
            ui.front(rig.main());
            Ui.pause(1500);
            boolean offered = false; // folded away it is still a part of the card: gone means it can no longer be pressed
            for (Component c : ui.all("IT'S DOWN - RESUME")) if (c instanceof javax.swing.JButton && ui.whyNotPressable(c, null) == null) offered = true;
            ui.shot("the LINK card after IT'S DOWN - RESUME");
            rig.check(!offered, "and IT'S DOWN - RESUME is gone from the LINK card");
        });
    }
}
