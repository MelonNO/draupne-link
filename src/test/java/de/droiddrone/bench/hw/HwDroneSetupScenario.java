package de.droiddrone.bench.hw;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Hardware;
import de.droiddrone.bench.Sandbox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Every Drone setup row on the real flight controller (the test week's day 3): the sealed Windows app's own Drone
 * setup, as a pilot opens it - SETUP &gt; Set up a new drone, the drone's two steps - on the board on this PC's USB,
 * through the keeper's lease for Drone setup ({@link KeeperWire}). <b>Battery out, propellers off.</b>
 * {@code .\bench.ps1 hw -Tests *HwDroneSetup*}; about five minutes (each CLI visit restarts the board, and the keeper
 * reads its settings again before the next lease).
 * <ol>
 * <li><b>Before anything</b>: the keeper has the board - taken, disarmed, locked, no window, not tripped - and the
 * board's settings as they are (the R1 snapshot: the MSP facts, "diff all", "dump all", each read twice) go into the
 * run's folder, {@code board\<id>\<time>-before-drone-setup}, with the copy for a paste by hand beside it. The
 * questions a pilot answers for this drone - GPS and its UART, the compass, the cells - are answered as that snapshot
 * has them ({@link HwDroneSetup#choices}); a board that counts its cells itself gets the hardware file's
 * {@code battery.cells}, and without one CONFIRM is not pressed.</li>
 * <li><b>The first step, Betaflight</b>: read through the lease; every row's words into the film and
 * {@code drone-setup-rows.txt} - the USB row (port, Betaflight's version, board and craft name), RECEIVER, STICK DELAY,
 * BATTERY, GPS with its PORT, COMPASS, FLIGHT MODES, ARM, PREARM, GPS RESCUE, SIGNAL LOST (the failsafe), ESCS, what
 * CONFIRM would write ("Set on Confirm.", "Also sets ...") - and BY HAND's text.</li>
 * <li><b>The second step, Motors and orientation</b>: the live view of the board's attitude; CALIBRATE only on a drone
 * that reads still and flat within the app's own 3 degrees (Betaflight levels its accelerometer where it sits and
 * saves that itself); the motor test window opened to read its rows - the board, its layout and props direction, the
 * battery, the motor protocol (spin directions are offered for DShot only) - and closed, nothing in it pressed; the
 * orientation's words.</li>
 * <li><b>CONFIRM</b>, back on the first step. Drone setup must say done ("Betaflight set up - unplug the drone.") with
 * every row set, or - battery out - name as not set ("Not set: GPS, ...") only rows that wait for a GPS that did not
 * answer (said in the film, no failure; F-087). Then
 * the guide closed, nothing of the app's on the board, and the board read again the same way: each difference from
 * the snapshot into the film and {@code drone-setup-compare.txt}, held to what Drone setup said it changed - its
 * "Betaflight: ..." line and the commands its log says it wrote, CALIBRATE's accelerometer, the receiver it switched
 * over MSP ({@link HwDroneSetup#judge}); anything else is WRONG, and so is a CLI line sent that its log does not have.
 * Every request the app sent is in {@code drone-setup-tap.tsv}.</li>
 * <li><b>The end</b>: the keeper has the board back - taken, locked, disarmed, no window - and says SAFE (locked,
 * disarmed, every motor at rest); the rig asks it once more at its end, and {@code bench.ps1 hw} ends with
 * fcKeeperEnd and boardSafe as always.</li>
 * </ol>
 * <b>Never pressed</b> ({@link DroneSetupRun#SKIPPED}, said in the film): anything that turns a motor or needs the
 * battery - the motor test's box, sliders and motors, REORDER MOTORS, PROPS IN / OUT, the spin directions - READ ESCS
 * and BEACON OFF (F-080 has its own probes and rules), FIND GPS, CALIBRATE COMPASS and the orientation's CHECK IT
 * (the drone turned by hand). Checked from the requests themselves: no passthrough, no DShot command, no motor order
 * or props write, no EEPROM write or MSP reboot, every motor value STOP.
 * <p>
 * With the battery out the board reads near 0 V: what Drone setup says of the battery, the GPS and the ESCs is in the
 * film, and none of it is a failure by itself. The snapshot before is the way back: {@code gradlew boardRestore
 * -Psnapshot=<its folder>} once the keeper has ended. Without a hardware file, or with one that names no keeper, it
 * skips itself. Its made-up twin is {@link HwDroneSetupSelfTest}.
 */
@Tag("hw")
class HwDroneSetupScenario {
    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void everyDroneSetupRowOnTheRealBoardThenConfirmAndTheBoardChangedOnlyAsDroneSetupSaid() throws Exception {
        Assumptions.assumeTrue(Hardware.present(), "This bench has no hardware set up: no " + Hardware.FILE
                + " in %LOCALAPPDATA%\\DraupneBench (start from " + Hardware.EXAMPLE + ")");
        Hardware bench = Hardware.load();
        Assumptions.assumeTrue(bench.hasKeeper(), "This bench's hardware file names no keeper (keeper.port): no flight controller on this PC's USB to test");
        int cells;
        try {
            cells = bench.cells();
        } catch (IllegalStateException none) {
            cells = 0; // only wanted for a board that counts its cells itself
        }
        DroneSetupRun run = new DroneSetupRun(bench.keeperPort(), cells, Sandbox.runRoot().resolve("board"), DroneSetupRun.THROUGH_KEEPER, true);
        try {
            run.before(s -> System.out.println("  " + s)); // before anything: the keeper's board as it is, into the run's folder
        } catch (IOException e) {
            throw new AssertionError("The board's settings were not read through the keeper (127.0.0.1:" + bench.keeperPort() + "): " + e.getMessage()
                    + ". Is the keeper up? .\\bench.ps1 hw starts it, or gradlew fcKeeper in a window of its own.");
        }
        AppRig.Plan plan = AppRig.scenario("hw-drone-setup").hardware(bench);
        run.settings().forEach(plan::setting);
        plan.run(run::run);
    }
}
