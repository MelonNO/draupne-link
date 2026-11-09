package de.droiddrone.pc.setup;

import de.droiddrone.pc.ArmSwitch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one Drone setup round changes in Betaflight: the receiver, the serial rate and the cells (BetaflightPlan), the
 * GPS and the compass (GpsSetup), the beepers (Beepers), the failsafe and the switches (ModeSwitch) - from the CLI's
 * answers, asked here in the order Drone setup always asked them. Drone setup (gui.DroneSetup) does the I/O around it:
 * the port, MSP before the CLI, the backup and the write. Apart from it so it can be tested without a flight controller.
 */
public final class SetupPlanner {
    private SetupPlanner() {
    }

    /** One CLI command and Betaflight's reply ({@link BetaflightUsb#command}). */
    @FunctionalInterface
    public interface Cli {
        String command(String line) throws IOException;
    }

    /**
     * The GPS question's side of a round.
     * @param target     the pilot's answer (null = not asked yet: nothing GPS is written)
     * @param uart       the UART chosen (1 = UART1)
     * @param compass    the compass wanted
     * @param onFc       Betaflight's GPS feature is on
     * @param portNow    the port Betaflight has the GPS function on ("" = none)
     * @param uarts      the UARTs the board has, as Betaflight lists them (empty = not read)
     * @param magFound   Betaflight sees a compass
     * @param calibrated the compass has calibration offsets
     * @param gpsPending the plan changes the GPS (feature or port); portNote says what the port loses
     * @param compassPending the plan changes mag_hardware
     */
    public record Gps(Boolean target, int uart, boolean compass, boolean onFc, String portNow, List<Integer> uarts,
                      boolean magFound, boolean calibrated, boolean gpsPending, String portNote, boolean compassPending) {
        public static Gps none(Boolean target, int uart, boolean compass) {
            return new Gps(target, uart, compass, false, "", List.of(), false, false, false, "", false);
        }

        public boolean wanted() {
            return Boolean.TRUE.equals(target);
        }
    }

    /**
     * What the pilot chose on the page for this round.
     * @param cellTarget     the battery's cells
     * @param cellsOk        the pilot confirmed the cells: only then are they forced
     * @param modeTarget     the flight mode switch, or null to leave Betaflight's modes
     * @param rescueTarget   the GPS rescue switch (AUX, 0 = none)
     * @param failsafeTarget the failsafe chosen
     * @param armTarget      the ARM switch (AUX, 0 = leave)
     * @param prearmTarget   the PREARM switch (AUX, 0 = leave)
     * @param gps            the GPS answer (Gps.none)
     */
    public record Wanted(int cellTarget, boolean cellsOk, ModeSwitch modeTarget, int rescueTarget,
                         ModeSwitch.Failsafe failsafeTarget, int armTarget, int prearmTarget, Gps gps) {
    }

    /**
     * What MSP said before the CLI opened.
     * @param modesOffered the modes this board has (MSP_BOXIDS), or null if unknown: every chosen mode is written
     * @param featureMask  Betaflight's features, as read before the receiver was switched
     * @param magFound     MSP_STATUS shows a compass
     * @param gpsFound     a GPS answered (null = not asked: none wanted, Betaflight's GPS off, or no answer)
     */
    public record Fc(Set<Integer> modesOffered, int featureMask, boolean magFound, Boolean gpsFound) {
        public boolean gpsOnFc() {
            return BetaflightPlan.hasGps(featureMask);
        }

        public boolean mspRx() {
            return BetaflightPlan.isMspReceiver(featureMask);
        }

        /** The receiver(s) on before any change, for the log and the list of changes. */
        public String receiverNow() {
            return BetaflightPlan.receiverNames(featureMask);
        }
    }

    /**
     * One round's reading and its plan.
     * @param version         Betaflight's "version" line
     * @param board           the board's name and the craft's ("" if none)
     * @param aux             the CLI's "aux" output, as read
     * @param rate            "get serial_update_rate_hz", as read
     * @param forced          "get force_battery_cell_count", as read
     * @param plan            what needs changing, in the order it is written (empty: all set)
     * @param modes           the switches' plan, or null when no switch is set
     * @param failsafe        Betaflight's failsafe_procedure, or null when not read
     * @param rescueFailsafe  GPS rescue is (or becomes) the failsafe
     * @param failsafeProblem why the GPS rescue failsafe can't be written, or null
     * @param gps             the GPS as read
     * @param compassRead     the compass's calibration as read (null: not read): for the observation map
     */
    public record Result(String version, String board, String aux, String rate, String forced, List<BetaflightPlan.Change> plan,
                         ModeSwitch.Plan modes, String failsafe, boolean rescueFailsafe, String failsafeProblem, Gps gps,
                         Boolean compassRead) {
        public ArmSwitch arm() {
            return ArmSwitch.fromCli(aux);
        }

        /** What the plan takes off the switches. */
        public List<String> replaced() {
            return modes == null ? List.of() : modes.replaced();
        }
    }

    /** Asks the CLI (opened already) and plans the round; nothing is written. */
    public static Result plan(Cli cli, Fc fc, Wanted w) throws IOException {
        boolean gpsOnFc = fc.gpsOnFc();
        boolean magFound = fc.magFound();
        Gps gpsWanted = w.gps();
        String version = BetaflightPlan.version(cli.command("version"));
        String board = boardName(cli);
        // where the arm switch is: the sticks then also wait for it to be off (RcSession)
        String aux = cli.command("aux");
        String rate = cli.command("get serial_update_rate_hz");
        String forced = cli.command("get force_battery_cell_count");
        List<BetaflightPlan.Change> plan = new ArrayList<>(BetaflightPlan.plan(rate, version, forced, w.cellTarget(), w.cellsOk()));
        if (!fc.mspRx()) {
            plan.add(0, new BetaflightPlan.Change("Receiver: MSP RX input, now "
                    + fc.receiverNow(), List.of()));
        }
        // the GPS as answered: its feature, its port, the compass
        String serial = gpsWanted.wanted() ? cli.command("serial") : "";
        Gps gpsRead;
        Boolean compassRead = null;
        if (gpsWanted.wanted()) {
            List<BetaflightPlan.Change> gpsPlan = GpsSetup.gpsPlan(fc.featureMask(), serial, cli.command("get gps_auto_baud"), gpsWanted.uart());
            plan.addAll(gpsPlan);
            String portNote = "";
            for (BetaflightPlan.Change c : gpsPlan) {
                int at = c.description().indexOf(" (");
                if (c.description().startsWith("GPS on UART") && at > 0) portNote = c.description().substring(at + 2, c.description().length() - 1);
            }
            String magHardware = GpsSetup.magHardware(cli.command("get mag_hardware"));
            BetaflightPlan.Change compass = GpsSetup.compassPlan(gpsWanted.compass(), magHardware);
            if (compass != null) plan.add(compass);
            boolean calibrated = gpsWanted.compass() && magFound && GpsSetup.calibrated(cli.command("get mag_calibration"));
            if (gpsWanted.compass() && magFound) compassRead = calibrated;
            gpsRead = new Gps(gpsWanted.target(), gpsWanted.uart(), gpsWanted.compass(), gpsOnFc, GpsSetup.gpsPortName(serial),
                    GpsSetup.uarts(serial), magFound, calibrated, !gpsPlan.isEmpty(), portNote, compass != null);
        } else {
            gpsRead = new Gps(gpsWanted.target(), gpsWanted.uart(), gpsWanted.compass(), gpsOnFc, "", List.of(), magFound, false, false, "", false);
        }
        // silent unless BEEPER is pressed or the phone is unplugged (Beepers); the button's mode on its AUX (BEEPER_AUX) below
        BetaflightPlan.Change beepers = Beepers.plan(cli.command("beeper list"), cli.command("beeper"), cli.command("beacon"));
        if (beepers != null) plan.add(beepers);
        Set<Integer> modesOffered = fc.modesOffered();
        int beeperTarget = modesOffered == null || modesOffered.contains(ModeSwitch.BEEPER_MODE) ? ModeSwitch.BEEPER_AUX : 0;
        Map<ModeSwitch.Mode, String> blocked = new EnumMap<>(ModeSwitch.Mode.class);
        String failsafeProblem = null;
        ModeSwitch modeTarget = w.modeTarget();
        ModeSwitch.Failsafe failsafeTarget = w.failsafeTarget();
        int rescueTarget = w.rescueTarget();
        // the failsafe, as chosen; the rescue switch only goes on while the failsafe is (or becomes) GPS rescue
        boolean readFailsafe = rescueTarget > 0 || failsafeTarget != ModeSwitch.Failsafe.KEEP;
        String failsafe = readFailsafe ? ModeSwitch.Failsafe.current(cli.command("get failsafe_procedure")) : null;
        boolean rescueNow = "GPS-RESCUE".equals(failsafe);
        boolean rescueWanted = failsafeTarget == ModeSwitch.Failsafe.GPS_RESCUE
                || (rescueNow && failsafeTarget == ModeSwitch.Failsafe.KEEP);
        List<ModeSwitch.Mode> gpsModes = new ArrayList<>(modeTarget == null ? List.of() : modeTarget.gpsModes());
        if ((rescueWanted && rescueTarget > 0 || failsafeTarget == ModeSwitch.Failsafe.GPS_RESCUE)
                && !gpsModes.contains(ModeSwitch.Mode.GPS_RESCUE)) {
            gpsModes.add(ModeSwitch.Mode.GPS_RESCUE);
        }
        for (ModeSwitch.Mode m : gpsModes) {
            String tooOld = m.tooOld(version);
            if (tooOld != null) {
                blocked.put(m, tooOld);
            } else if (!gpsWanted.wanted()) {
                blocked.put(m, "Needs a GPS: answer <b>Yes</b> under GPS above.");
            } else if (!gpsOnFc) {
                blocked.put(m, "Turning GPS on; checked after restart.");
            } else if (!Boolean.TRUE.equals(fc.gpsFound())) {
                blocked.put(m, "No GPS found - see the GPS row.");
            }
        }
        boolean rescueFailsafe = rescueNow && failsafeTarget != ModeSwitch.Failsafe.DROP;
        if (failsafeTarget == ModeSwitch.Failsafe.GPS_RESCUE && !rescueNow) {
            if (blocked.containsKey(ModeSwitch.Mode.GPS_RESCUE)) {
                failsafeProblem = blocked.get(ModeSwitch.Mode.GPS_RESCUE);
            } else {
                plan.add(new BetaflightPlan.Change("Failsafe: GPS rescue", List.of("set failsafe_procedure = GPS-RESCUE")));
                rescueFailsafe = true;
            }
        } else if (failsafeTarget == ModeSwitch.Failsafe.DROP && failsafe != null && !"DROP".equals(failsafe)) {
            plan.add(new BetaflightPlan.Change("Failsafe: drop", List.of("set failsafe_procedure = DROP")));
        }
        int rescueOn = rescueFailsafe ? rescueTarget : 0;
        // with a GPS, GPS rescue (failsafe or switch) would block arming until the GPS has a fix - indoors too:
        // always allowed (the pilot's rule), written with the rest
        if (gpsWanted.wanted()) {
            BetaflightPlan.Change withoutFix = GpsSetup.armingWithoutFixPlan(cli.command("get gps_rescue_allow_arming_without_fix"));
            if (withoutFix != null) plan.add(withoutFix);
        }
        ModeSwitch.Switches switches = new ModeSwitch.Switches(modeTarget, rescueOn, w.armTarget(), w.prearmTarget(), beeperTarget);
        ModeSwitch.Plan modes = switches.none() ? null : ModeSwitch.plan(aux, modesOffered, blocked, switches);
        if (modes != null && !modes.commands().isEmpty()) {
            List<String> what = new ArrayList<>();
            if (modeTarget != null && modes.problem() == null) what.add("flight modes on AUX" + modeTarget.aux() + ": " + modeTarget.describe());
            if (rescueOn > 0 && modes.rescueProblem() == null) what.add("GPS rescue up on AUX" + rescueOn);
            if (w.armTarget() > 0 && modes.armProblem() == null) what.add("ARM on AUX" + w.armTarget());
            if (w.prearmTarget() > 0 && modes.prearmProblem() == null) what.add("PREARM on AUX" + w.prearmTarget());
            if (beeperTarget > 0 && modes.commands().stream().anyMatch(c -> c.matches("aux \\d+ " + ModeSwitch.BEEPER_MODE + " .*"))) {
                what.add("BEEPER on AUX" + beeperTarget + " (the app's BEEPER button)");
            }
            plan.add(new BetaflightPlan.Change("Modes: " + String.join("; ", what) + "." + takenOff(modes.replaced()), modes.commands()));
        }
        return new Result(version, board, aux, rate, forced, List.copyOf(plan), modes, failsafe, rescueFailsafe, failsafeProblem,
                gpsRead, compassRead);
    }

    /** The board's name and the craft's ("IFLIGHT_BLITZ_F722 - Vanguard"), from the CLI; "" when neither is set. */
    static String boardName(Cli cli) throws IOException {
        String board = BetaflightPlan.boardName(cli.command("board_name"));
        String craft = BetaflightPlan.craftName(cli.command("get craft_name")); // Betaflight 4.4 on
        if (craft.isEmpty()) craft = BetaflightPlan.craftName(cli.command("name")); // older
        if (board.isEmpty()) return craft;
        return craft.isEmpty() ? board : board + " - " + craft;
    }

    /** " Taken off: ARM on AUX3, BEEPER on AUX2." or "". */
    public static String takenOff(List<String> replaced) {
        return replaced.isEmpty() ? "" : " Taken off: " + String.join(", ", replaced) + ".";
    }
}
