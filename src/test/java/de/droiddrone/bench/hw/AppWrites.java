package de.droiddrone.bench.hw;

import de.droiddrone.common.BeeperQuiet;
import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.CliWrite;
import de.droiddrone.pc.setup.GpsSetup;
import de.droiddrone.pc.setup.ModeSwitch;
import de.droiddrone.pc.setup.MotorTest;
import de.droiddrone.pc.setup.SetupPlanner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The writes DRAUPNE Link makes to a flight controller, each made here by the app's own code in the order its pages
 * use it - for tests that need a board changed the way the app changes it. Each waits for the board first (the write
 * before it may have restarted Betaflight) and leaves it saved.
 */
public final class AppWrites {
    private AppWrites() {
    }

    /**
     * A whole Drone setup round as gui.DroneSetup makes it: the receiver switched to MSP over MSP, then in one CLI
     * visit the plan read (SetupPlanner: serial rate, cells, battery limits, GPS on UART3 without a compass, beepers,
     * ARM on AUX1, flight modes on AUX2, PREARM on AUX3, BEEPER on AUX14), written all or nothing (CliWrite), saved.
     * @return the CLI commands written
     */
    public static List<String> droneSetup(BetaflightUsb.Port port, BoardPort.Timing timing, Consumer<String> log) throws IOException {
        try (BetaflightUsb fc = BoardPort.open(port, timing, log)) {
            Set<Integer> modes = fc.boxIds();
            int mask = fc.featureMask();
            if (!BetaflightPlan.isMspReceiver(mask)) fc.setFeatureMask(BetaflightPlan.mspReceiverMask(mask));
            fc.enterCli();
            SetupPlanner.Result round = SetupPlanner.plan(fc::command, new SetupPlanner.Fc(modes, mask, false, null),
                    new SetupPlanner.Wanted(6, true, ModeSwitch.DEFAULT, 0, ModeSwitch.Failsafe.KEEP, ModeSwitch.DEFAULT_ARM_AUX,
                            ModeSwitch.DEFAULT_PREARM_AUX, SetupPlanner.Gps.none(true, GpsSetup.DEFAULT_UART, false)));
            List<String> commands = new ArrayList<>();
            for (BetaflightPlan.Change c : round.plan()) commands.addAll(c.commands());
            CliWrite.Result written = CliWrite.run(fc::command, commands, log);
            if (!written.complete()) throw new IOException(written.stopped()); // closing says exit: nothing saved
            fc.saveAndReboot();
            return commands;
        }
    }

    /** CLI lines written all or nothing and saved, as any of the app's CLI writes. */
    public static void cli(BetaflightUsb.Port port, BoardPort.Timing timing, Consumer<String> log, String... lines) throws IOException {
        try (BetaflightUsb fc = BoardPort.open(port, timing, log)) {
            fc.enterCli();
            CliWrite.Result written = CliWrite.run(fc::command, List.of(lines), log);
            if (!written.complete()) throw new IOException(written.stopped());
            fc.saveAndReboot();
        }
    }

    /** The motor test, opened once the board answers. Close it. */
    public static MotorTest motorTest(BetaflightUsb.Port port, BoardPort.Timing timing) throws IOException {
        BoardPort.open(port, timing, s -> {
        }).close();
        return MotorTest.open(port);
    }

    /** The motor test's reorder wizard: a new output order, saved; Betaflight restarts. */
    public static void motorOrder(BetaflightUsb.Port port, BoardPort.Timing timing, int... order) throws IOException {
        try (MotorTest test = motorTest(port, timing)) {
            test.setOutputOrderAndRestart(order);
        }
    }

    /** The motor test's props in / out, saved; Betaflight restarts. */
    public static void propsOut(BetaflightUsb.Port port, BoardPort.Timing timing, boolean out) throws IOException {
        try (MotorTest test = motorTest(port, timing)) {
            test.setPropsOutAndRestart(out);
        }
    }

    /** The motor test's REVERSE for one motor (1 first): a DShot command the ESC saves in its own memory. Battery in. */
    public static void reverse(BetaflightUsb.Port port, BoardPort.Timing timing, int motor, boolean reversed) throws IOException {
        try (MotorTest test = motorTest(port, timing)) {
            test.setDirection(motor, reversed);
        }
    }

    /**
     * The phone app's beeper silence (BeeperQuiet, MSP 185) - which the phone leaves in memory only - saved here, as
     * a tool that then stores the settings over MSP would leave it.
     */
    public static void beeperQuietSaved(BetaflightUsb.Port port, BoardPort.Timing timing) throws IOException {
        try (BetaflightUsb fc = BoardPort.open(port, timing, s -> {
        })) {
            byte[] quiet = BeeperQuiet.quiet(fc.msp(Msp.BEEPER_CONFIG, null));
            if (quiet == null) throw new IOException("Beeper config not understood");
            fc.msp(Msp.SET_BEEPER_CONFIG, quiet);
            fc.msp(Msp.EEPROM_WRITE, null);
        }
    }
}
