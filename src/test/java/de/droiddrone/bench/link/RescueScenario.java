package de.droiddrone.bench.link;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.fleet.BenchFleetAccess;
import de.droiddrone.pc.fleet.FlightModel;
import de.droiddrone.pc.fleet.VirtualDrone;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The link lost in the air with Betaflight's failsafe set to GPS rescue (the pilot, 2026-11-19: "it is important
 * that the drone does not disarm if gps rescue is set"). The real window, a made-up drone 150 m from its home: the
 * link is cut for 8 s while armed. Neither app may disarm it - not during the cut (no "arm off" resting sticks to
 * an armed flight controller), not when the link returns (the pilot's sticks as they are, ARM still on) - the
 * rescue flies on until a stick is moved, and the pilot then flies and lands it.
 * The second scenario is what a pilot on GPS rescue must know: ARM put off during the cut disarms the drone in the
 * air the moment the link returns (Betaflight obeys the switch again).
 */
@Tag("badlink")
class RescueScenario {
    private static VirtualDrone flyAway(AppRig rig, Pilot pilot) {
        Ui ui = rig.ui;
        String name = String.valueOf(Ui.edt(() -> ui.all(javax.swing.JComboBox.class).get(0).getSelectedItem())).trim().split("\\s+")[0];
        VirtualDrone drone = rig.drone(name);
        BenchFleetAccess.launchedFromNorth(drone, 150); // as if flown out from there: home is 150 m away
        pilot.connect();
        pilot.waitForVideo();
        ui.until("RC OUTPUT: LIVE", 20_000, () -> ui.sees("RC OUTPUT: LIVE"));
        pilot.arm(drone);
        pilot.throttle(0.15);
        pilot.until("the drone to climb", 20_000, () -> drone.readout().altM() > 6);
        pilot.throttle(-0.2); // about a hover
        Ui.pause(1500);
        rig.check(BenchFleetAccess.homeDistanceM(drone) > 100, "the drone is " + Math.round(BenchFleetAccess.homeDistanceM(drone)) + " m from its home");
        ui.shot("armed, flying, 150 m from home; the failsafe is GPS rescue");
        return drone;
    }

    private static long idleFeeds(AppRig rig) {
        return rig.fleetLog.stream().filter(l -> l.contains("idle sticks to the FC")).count();
    }

    @Test
    void aLinkLostInTheAirLeavesARescuingDroneArmedAndThePilotTakesItBack() throws Exception {
        AppRig.scenario("rescue-link-lost").drones(1).failsafe(FlightModel.Procedure.GPS_RESCUE).run(rig -> {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            VirtualDrone drone = flyAway(rig, pilot);
            long idleBefore = idleFeeds(rig);

            drone.link().silence(System.currentTimeMillis(), 8000);
            Ui.pause(4000);
            ui.shot("4 s into the cut");
            rig.check(BenchFleetAccess.armed(drone), "4 s into the cut the drone is ARMED");
            rig.check("RESCUE".equals(BenchFleetAccess.failsafe(drone)), "and in GPS rescue: " + BenchFleetAccess.failsafe(drone));
            Ui.pause(4500);
            ui.until("the link back", 40_000, () -> ui.has("LINK READY"));
            Ui.pause(2500);
            ui.shot("the link back; the pilot's ARM switch still on, no stick moved");
            rig.check(BenchFleetAccess.armed(drone), "with the link back the drone is still ARMED");
            rig.check("RESCUE".equals(BenchFleetAccess.failsafe(drone)), "no stick moved: the rescue flies on (" + BenchFleetAccess.failsafe(drone) + ")");
            rig.check(idleFeeds(rig) == idleBefore, "the phone fed no resting (ARM off) sticks to the armed flight controller");
            rig.check(ui.sees("FC: ARMED"), "the controller card says FC: ARMED");
            rig.check(BenchFleetAccess.altitudeM(drone) > 10, "it has climbed for the way home: " + Math.round(BenchFleetAccess.altitudeM(drone)) + " m");

            // a stick moved: the drone is the pilot's again, armed
            rig.radio.sticks(0.6, 0, 0, -0.2);
            pilot.until("the rescue to end at the pilot's stick", 5000, () -> "NONE".equals(BenchFleetAccess.failsafe(drone)));
            rig.radio.sticks(0, 0, 0, -0.2);
            rig.check(BenchFleetAccess.armed(drone), "armed, and flown by the pilot again");
            ui.shot("a stick moved: the pilot flies again");

            // down gently, and disarmed by the pilot's own switch
            rig.radio.aux(2, 1); // ANGLE: level by itself
            pilot.throttle(-0.32);
            pilot.until("the drone to come down", 120_000, () -> drone.readout().altM() < 0.5);
            pilot.disarm(drone);
            rig.check("the arm switch".equals(BenchFleetAccess.lastDisarm(drone)), "disarmed by the pilot's switch, on the ground: " + BenchFleetAccess.lastDisarm(drone));
            pilot.standby();
        });
    }

    @Test
    void armPutOffDuringTheCutDisarmsTheRescueWhenTheLinkReturns() throws Exception {
        AppRig.scenario("rescue-arm-off").drones(1).failsafe(FlightModel.Procedure.GPS_RESCUE).run(rig -> {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            VirtualDrone drone = flyAway(rig, pilot);
            drone.link().silence(System.currentTimeMillis(), 8000);
            Ui.pause(4000);
            rig.check(BenchFleetAccess.armed(drone) && "RESCUE".equals(BenchFleetAccess.failsafe(drone)), "in the cut: armed, in GPS rescue");
            rig.radio.aux(1, -1); // the pilot gives up and puts ARM off
            rig.radio.sticksAtRest();
            Ui.pause(3000);
            rig.check(BenchFleetAccess.armed(drone), "ARM off during the cut changes nothing yet: the drone does not hear it");
            ui.until("the link back", 40_000, () -> ui.has("LINK READY") || ui.has("PILOT UAV"));
            pilot.until("the drone to hear the switch", 10_000, () -> !BenchFleetAccess.armed(drone));
            ui.shot("the link back with ARM off: disarmed in the air");
            rig.film.remark("With the link back and ARM off the drone disarmed at " + Math.round(BenchFleetAccess.altitudeM(drone))
                    + " m: \"" + BenchFleetAccess.lastDisarm(drone) + "\". Betaflight obeys the switch once it has the sticks again.");
            rig.check("the arm switch".equals(BenchFleetAccess.lastDisarm(drone)), "it was the pilot's switch that disarmed it, not the apps: " + BenchFleetAccess.lastDisarm(drone));
            Ui.pause(4000); // it falls
            if (ui.has("STANDBY MODE")) {
                ui.click("STANDBY MODE");
                Ui.pause(500);
                if (ui.has("Keep flying")) ui.click("Standby mode");
            }
            ui.waitForControl("PILOT UAV", 30_000);
        });
    }
}
