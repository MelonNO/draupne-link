package de.droiddrone.pc.gui;

import de.droiddrone.pc.ArmSwitch;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.gui.CheckRow.State;
import de.droiddrone.pc.gui.WizardDialog.Page;
import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.ModeSwitch;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Drone setup: the Betaflight settings DRAUPNE Link needs - receiver mode MSP, serial_update_rate_hz and the battery's
 * cell count (6S unless the pilot changes it) - set over USB with nothing else to click, plus the flight mode switch
 * the pilot picks (AUX2 by default: acro, angle, GPS position hold; or left as it is). Everything else on the drone
 * (arm switch, accelerometer, failsafe) stays the pilot's own. The motors and props are assumed checked; the pilot
 * can say so, or check them there and then with the motor test.
 */
final class DroneSetup {
    /**
     * Set when Drone setup wrote or changed a switch (ARM, flight modes, GPS rescue): an older radio calibration may
     * have any switch on those AUX channels, so the radio step is shown again until a calibration is saved.
     */
    static final String RADIO_REDO_KEY = "setup.radioNeedsRedo";

    private DroneSetup() {
    }

    /** Drone setup's steps. The first-time setup is made of these too (SetupWizard): a step added here is in both. */
    static List<Page> pages(MainWindow main) {
        return List.of(new BetaflightPage(main));
    }

    /** SETUP &rarr; Drone setup. */
    static void open(MainWindow main) {
        new WizardDialog(main, "Drone setup", pages(main), () -> {
            main.save(Map.of("setup.droneDone", "true"));
            main.logLine("Drone setup finished.");
        }).setVisible(true);
        main.reloadSettings();
    }

    /** The radio must be calibrated again: Drone setup changed the switches since the last calibration. */
    static boolean radioNeedsRedo(PcConfig config) {
        return "true".equalsIgnoreCase(config.getString(RADIO_REDO_KEY));
    }

    /** "Calibrate the radio again so AUX1 (ARM) and AUX2 (flight modes) are the right switches." */
    static String radioRedoText(PcConfig config) {
        ModeSwitch.Purpose[] purposes = ModeSwitch.purposes(config);
        List<String> named = new java.util.ArrayList<>();
        for (int i = 0; i < purposes.length; i++) {
            if (purposes[i] != null) named.add("AUX" + (i + 1) + " (" + purposes[i].title() + ")");
        }
        if (named.isEmpty()) return "Calibrate the radio again so each AUX is the right switch.";
        if (named.size() == 1) return "Calibrate the radio again so " + named.get(0) + " is the right switch.";
        return "Calibrate the radio again so " + String.join(", ", named.subList(0, named.size() - 1)) + " and "
                + named.get(named.size() - 1) + " are the right switches.";
    }

    /** Finds the flight controller on USB, reads the settings, fixes them, and checks they stuck. */
    static final class BetaflightPage extends Page {
        /** Automatic attempts at changing the settings before the page says it couldn't. */
        private static final int MAX_APPLIES = 3; // the GPS: turned on, then its modes once it answers
        /** How long to wait for the GPS to talk to Betaflight after a (re)start: it tries its baud rates first. */
        private static final long GPS_WAIT_MS = 12_000;
        /** After the CLI closes (exit or save) Betaflight restarts and its USB port is gone for a few seconds. */
        private static final long RESTART_MS = 6000;
        /** The same error this many times running: the USB link itself is stuck. */
        private static final int SAME_ERROR_LIMIT = 3;

        /**
         * @param arm        Betaflight's arm switch, or null if ARM isn't on one switch
         * @param armAuxes   every AUX ARM is on (more than one isn't covered by {@link ArmSwitch})
         * @param backup     where Betaflight's settings were saved before writing, or null
         * @param ports      every port that looked like a flight controller this round
         * @param refused    CLI commands Betaflight didn't take while writing, with its answers; null if none
         * @param plan       what needs changing (empty: all set); written when applied
         * @param replaced   what the plan takes off the switches
         * @param board      the board's name and the craft's, for the USB row ("" if none)
         * @param battery    the battery as Betaflight sees it, or null if it couldn't be asked
         * @param receiverNow the receiver(s) on before any change, for the log and the list of changes
         */
        private record Read(String port, String version, boolean msp, int rate, int target, int cells, int cellTarget,
                            boolean applied, String error, ArmSwitch arm, java.nio.file.Path backup, List<String> ports,
                            ModeSwitch modeTarget, ModeSwitch.Plan modes, Boolean gpsFound, String rescueNote,
                            int rescueTarget, String failsafe, ModeSwitch.Failsafe failsafeTarget, boolean rescueFitted,
                            String failsafeProblem, String failsafeNote, int armTarget, String refused,
                            List<BetaflightPlan.Change> plan, List<String> replaced, String board,
                            BetaflightUsb.BatteryState battery, Set<Integer> armAuxes, String receiverNow, boolean cellsConfirmed) {
            Read(String port, String error, int cellTarget, ModeSwitch modeTarget, int rescueTarget,
                 ModeSwitch.Failsafe failsafeTarget, int armTarget, List<String> ports) {
                this(port, "", false, -1, 0, -1, cellTarget, false, error, null, null, ports, modeTarget, null, null, null,
                        rescueTarget, null, failsafeTarget, false, null, null, armTarget, null, List.of(), List.of(), "", null,
                        Set.of(), "", false);
            }

            /** A switch (ARM, flight modes, GPS rescue) was written this round: the radio calibration may be off. */
            boolean switchesWritten() {
                return applied && modes != null && !modes.commands().isEmpty();
            }
        }
        /** What Betaflight refused on the last write, shown with the port until a write goes through cleanly. */
        private String lastRefused;
        static final String CELLS_KEY = "drone.cellCount";
        /** The pilot has confirmed (or changed) the cell count: only then is it forced in Betaflight. */
        static final String CELLS_CONFIRMED_KEY = "drone.cellCountConfirmed";
        static final String MOTORS_CHECKED_KEY = "setup.motorsChecked";

        private final MainWindow main;
        private final CheckRow usb = new CheckRow("Flight controller on USB");
        /** What the next WRITE changes; nothing is written before the first press. */
        private final CheckRow changes = new CheckRow("What will change");
        private javax.swing.JButton writeButton;
        private final Combined settings = new Combined("Betaflight settings for DRAUPNE Link");
        private final Part receiver = settings.part("Receiver");
        private final Part stickDelay = settings.part("Stick delay");
        private final Part battery = settings.part("Battery");
        private final Combined switches = new Combined("Switches");
        private final Part armRow = switches.part("Arm");
        private final Part modeRow = switches.part("Flight modes");
        private final Part rescueRow = switches.part("GPS rescue");
        private final CheckRow motors = new CheckRow("Motors and props");
        /** The pilot's choice; null = leave Betaflight's modes as they are. Read by the background round. */
        private volatile ModeSwitch modeSwitch;
        /** GPS rescue's own switch (AUX number, 0 = none), fitted when Betaflight's failsafe is GPS rescue. */
        private volatile int rescueAux;
        /** The ARM switch (AUX number, 0 = leave ARM as it is in Betaflight); AUX1 by default. */
        private volatile int armAux;
        private javax.swing.JComboBox<String> armChoice;
        private final CheckRow failsafeRow = new CheckRow("Failsafe (signal lost)");
        /** The pilot's failsafe choice: left as it is by default. */
        private volatile ModeSwitch.Failsafe failsafeChoice = ModeSwitch.Failsafe.KEEP;
        private javax.swing.JComboBox<ModeSwitch.Failsafe> failsafeCombo;
        private javax.swing.JComboBox<String> rescueChoice;
        private static final String MODES_OFF = "Leave as it is";
        private javax.swing.JComboBox<String> auxChoice;
        private final List<javax.swing.JComboBox<ModeSwitch.Mode>> positionChoices = new java.util.ArrayList<>();
        /** The flight controller's version line once read: modes it is too old for can't be chosen. */
        private volatile String fcVersion = "";
        private boolean revertingChoice;
        /** Which port to use; "Automatic" = the first that answers as Betaflight. */
        private final javax.swing.JComboBox<String> portChoice = new javax.swing.JComboBox<>(new String[]{AUTOMATIC});
        private static final String AUTOMATIC = "Automatic";
        private volatile String chosenPort; // null = automatic
        /** The motor test has the flight controller's USB port: this page leaves it alone meanwhile. */
        private boolean motorTestOpen;
        private JSpinner cellSpinner;
        /** The pilot's choice; read by the background round, so a change made meanwhile is noticed after it. */
        private volatile int cells;
        /** The count is the pilot's (confirmed or changed): before that Betaflight keeps what it has. */
        private volatile boolean cellsConfirmed;
        private javax.swing.JButton confirmCells;
        /** "Use measured: 4S": Betaflight's own count while a battery is in. */
        private javax.swing.JButton measuredCells;
        private int measured;
        /** The spinner follows a count Betaflight already forces: not the pilot's doing. */
        private boolean syncingSpinner;
        /** The same settings by hand, in Betaflight Configurator: behind the USB row's "By hand" button. */
        private String manualHtml = "";
        private javax.swing.JButton byHand;
        /** RETRY while nothing answers, READ AGAIN once the flight controller is there. */
        private javax.swing.JButton readAgain;
        /** DISCONNECT lets go of the USB port (for Betaflight Configurator); RECONNECT carries on. */
        private javax.swing.JButton disconnect;
        private boolean paused;
        private boolean busy;
        private boolean found;
        private boolean failed;
        private boolean done;
        /** Done, and everything is as it should be (the unplug line); otherwise something needs doing by hand. */
        private boolean cleanDone;
        /** WRITE was pressed: from then on changes are written (also when a choice changes). */
        private boolean writeAllowed;
        /** The page was left (Next, Back, Close, Disconnect) while a round ran: it reads, but writes nothing. */
        private volatile boolean abandoned;
        /** What the last write took off the switches, for the rows after the check that followed. */
        private List<String> lastReplaced = List.of();
        /** When the CLI last closed: Betaflight restarts, so the port is gone for a while (not "not found"). */
        private long restartedAt;
        private String lastError;
        private int sameErrorCount;
        private int applies;
        private long nextTry;

        BetaflightPage(MainWindow main) {
            this.main = main;
        }

        @Override
        String title() {
            return "Betaflight";
        }

        @Override
        String hint() {
            return "Plug a USB data cable into the drone and this computer. <b>Props off.</b>";
        }

        /** Rank for a combined row's light: the worst part shows; INFO (nothing to do) only when all are. */
        private static int rank(State s) {
            return switch (s) {
                case BAD -> 5;
                case WARN -> 4;
                case PENDING -> 3;
                case TODO -> 2;
                case OK -> 1;
                case INFO -> 0;
            };
        }

        /** Several settings on one row: one light (the worst of them) and a line each. */
        private static final class Combined {
            final CheckRow row;
            final List<Part> parts = new java.util.ArrayList<>();
            /** A warning under the parts (the radio calibration to redo); "" for none. */
            String note = "";

            Combined(String title) {
                row = new CheckRow(title);
            }

            Part part(String label) {
                Part p = new Part(this, label);
                parts.add(p);
                return p;
            }

            void note(String html) {
                note = html;
                refresh();
            }

            void refresh() {
                State worst = note.isEmpty() ? State.INFO : State.WARN;
                StringBuilder html = new StringBuilder();
                for (Part p : parts) {
                    if (rank(p.state) > rank(worst)) worst = p.state;
                    if (html.length() > 0) html.append("<br>");
                    html.append("<b>").append(p.label).append(":</b> ").append(p.text.isEmpty() ? "..." : p.text);
                }
                if (!note.isEmpty()) html.append("<br><b>").append(note).append("</b>");
                row.set(worst, html.toString());
            }
        }

        /** One setting's line in a combined row; set like a row of its own. */
        private static final class Part {
            final Combined owner;
            final String label;
            State state = State.PENDING;
            String text = "";

            Part(Combined owner, String label) {
                this.owner = owner;
                this.label = label;
            }

            Part set(State state, String text) {
                this.state = state;
                this.text = text;
                owner.refresh();
                return this;
            }
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            cells = savedCells();
            cellsConfirmed = "true".equalsIgnoreCase(main.config().getString(CELLS_CONFIRMED_KEY));
            cellSpinner = new JSpinner(new SpinnerNumberModel(cells, BetaflightPlan.MIN_CELLS, BetaflightPlan.MAX_CELLS, 1));
            cellSpinner.setFont(Theme.headingFont(16f));
            ((JSpinner.DefaultEditor) cellSpinner.getEditor()).getTextField().setColumns(2);
            cellSpinner.addChangeListener(e -> cellsChanged((Integer) cellSpinner.getValue()));
            cellSpinner.setToolTipText("The packs this drone flies on: Betaflight then always uses this count");
            settings.row.addComponent(Theme.label("BATTERY", Theme.headingFont(12f), Theme.MUTED));
            settings.row.addComponent(cellSpinner);
            settings.row.addComponent(Theme.label("S", Theme.headingFont(16f), Theme.TEXT));
            measuredCells = settings.row.addAction("Use measured", () -> cellSpinner.setValue(measured));
            measuredCells.setToolTipText("The cell count Betaflight sees on the battery plugged in now");
            measuredCells.setVisible(false);
            confirmCells = settings.row.addPrimaryAction("Confirm", this::confirmCells);
            confirmCells.setToolTipText("The count is right: Betaflight is set to always use it");
            confirmCells.setVisible(!cellsConfirmed);
            JPanel switchChoices = new JPanel();
            switchChoices.setLayout(new javax.swing.BoxLayout(switchChoices, javax.swing.BoxLayout.Y_AXIS));
            switchChoices.setOpaque(false);
            switchChoices.add(modeChoices());
            switchChoices.add(javax.swing.Box.createVerticalStrut(6));
            switchChoices.add(rescueChoices());
            switchChoices.add(javax.swing.Box.createVerticalStrut(8));
            switchChoices.add(WrappingText.fitting("AUX1, AUX2 ... are the radio's switch channels. The defaults suit most radios. "
                    + "Which switch is which AUX is chosen in the radio step.", Theme.font(Font.PLAIN, 13f), Theme.MUTED));
            for (java.awt.Component c : switchChoices.getComponents()) { // all at the left edge, like the lines above them
                if (c instanceof JComponent j) j.setAlignmentX(javax.swing.JComponent.LEFT_ALIGNMENT);
            }
            switches.row.addBelow(switchChoices);
            failsafeRow.addComponent(failsafeChoices());
            portChoice.setToolTipText("Which USB port the flight controller is on, if more than one is found");
            portChoice.addActionListener(e -> {
                Object c = portChoice.getSelectedItem();
                String chosen = c == null || AUTOMATIC.equals(c) ? null : c.toString();
                if (java.util.Objects.equals(chosen, chosenPort)) return;
                chosenPort = chosen;
                done = false;
                nextTry = 0;
            });
            TacticalCombo.install(portChoice);
            usb.addComponent(portChoice);
            portChoice.setVisible(false); // shown once more than one port is found
            byHand = usb.addAction("By hand", this::showByHand);
            byHand.setToolTipText("The same settings in Betaflight Configurator, for a drone without USB to this PC");
            readAgain = usb.addAction("Retry", this::checkModesAgain);
            readAgain.setToolTipText("Look for the flight controller again, or read it again after plugging the battery in or changing something in Betaflight");
            disconnect = usb.addAction("Disconnect", this::togglePaused);
            disconnect.setToolTipText("Let go of the USB port, e.g. for Betaflight Configurator; RECONNECT carries on");
            writeButton = changes.addPrimaryAction("Write", this::writeNow);
            writeButton.setToolTipText("Make these changes in Betaflight (its settings are backed up first)");
            changes.setVisible(false);
            WizardDialog.addRow(col, usb);
            WizardDialog.addRow(col, changes);
            WizardDialog.addRow(col, settings.row);
            WizardDialog.addRow(col, switches.row);
            WizardDialog.addRow(col, failsafeRow);
            motors.addAction("Already checked", this::motorsAlreadyChecked);
            motors.addPrimaryAction("Check now", this::checkMotorsNow);
            WizardDialog.addRow(col, motors);
            showManual();
            usb.set(State.PENDING, "Looking for it...");
            receiver.set(State.PENDING, "");
            stickDelay.set(State.PENDING, "");
            battery.set(cellsConfirmed ? State.PENDING : State.INFO, cellsConfirmed ? "" : cells + "S - correct? Change it, or press <b>Confirm</b>.");
            ArmSwitch known = ArmSwitch.parse(main.config().getString(ArmSwitch.KEY));
            armRow.set(State.PENDING, armAux > 0 ? "<b>AUX" + armAux + "</b> - written when the flight controller is found."
                    : known == null ? "Read from Betaflight when the flight controller is found." : describe(known));
            showModeChoice();
            showRescueChoice();
            showFailsafeChoice();
            if (radioNeedsRedo(main.config())) switches.note(radioRedoText(main.config()));
            boolean checkedBefore = "true".equalsIgnoreCase(main.config().getString(MOTORS_CHECKED_KEY));
            motors.set(checkedBefore ? State.OK : State.INFO, checkedBefore
                    ? "Checked before. <b>Check now</b> runs the motor test again."
                    : "Assumed checked: each motor in the right place and turning the right way, the props on the right way "
                    + "round. Not sure? <b>Check now</b> opens the motor test (props off).");
            return col;
        }

        /** FLIGHT MODES [AUX2] LOW [Acro] MIDDLE [Angle] HIGH [GPS position hold]: saved, and written like the rest. */
        private JComponent modeChoices() {
            modeSwitch = ModeSwitch.parse(main.config().getString(ModeSwitch.KEY));
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            row.setOpaque(false);
            row.add(Theme.label("FLIGHT MODES", Theme.headingFont(12f), Theme.MUTED));
            String[] switches = new String[ModeSwitch.MAX_AUX + 1];
            switches[0] = MODES_OFF;
            for (int i = 1; i <= ModeSwitch.MAX_AUX; i++) switches[i] = "AUX" + i;
            auxChoice = new javax.swing.JComboBox<>(switches);
            auxChoice.setToolTipText("The 3-position switch on your radio for the flight modes (AUX2 = the radio's channel 6 by default)");
            auxChoice.setSelectedIndex(modeSwitch == null ? 0 : modeSwitch.aux());
            TacticalCombo.install(auxChoice);
            row.add(auxChoice);
            ModeSwitch shown = modeSwitch == null ? ModeSwitch.DEFAULT : modeSwitch;
            String[] names = {"LOW", "MIDDLE", "HIGH"};
            for (int i = 0; i < 3; i++) {
                row.add(Theme.label(names[i], Theme.headingFont(12f), Theme.MUTED));
                javax.swing.JComboBox<ModeSwitch.Mode> c = new javax.swing.JComboBox<>(ModeSwitch.Mode.values());
                c.setSelectedItem(shown.positions()[i]);
                c.setToolTipText("What the switch in this position does");
                TacticalCombo.install(c);
                c.addActionListener(e -> modeChoiceChanged());
                positionChoices.add(c);
                row.add(c);
            }
            auxChoice.addActionListener(e -> modeChoiceChanged());
            enablePositions();
            return row;
        }

        /** FAILSAFE [Leave as it is]: in the failsafe row. */
        private JComponent failsafeChoices() {
            failsafeChoice = ModeSwitch.Failsafe.parse(main.config().getString(ModeSwitch.Failsafe.KEY));
            failsafeCombo = new javax.swing.JComboBox<>(ModeSwitch.Failsafe.values());
            failsafeCombo.setToolTipText("What Betaflight does when the signal is lost for good (failsafe stage 2)");
            failsafeCombo.setSelectedItem(failsafeChoice);
            TacticalCombo.install(failsafeCombo);
            failsafeCombo.addActionListener(e -> failsafeChoiceChanged());
            return failsafeCombo;
        }

        /** GPS RESCUE [AUX4]: up = GPS rescue, as the failsafe does - only when Betaflight's failsafe is GPS rescue. */
        private JComponent rescueChoices() {
            rescueAux = ModeSwitch.parseRescueAux(main.config().getString(ModeSwitch.RESCUE_KEY));
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            row.setOpaque(false);
            armAux = ModeSwitch.parseArmAux(main.config().getString(ModeSwitch.ARM_KEY));
            row.add(Theme.label("ARM", Theme.headingFont(12f), Theme.MUTED));
            String[] armSwitches = new String[ModeSwitch.MAX_AUX + 1];
            armSwitches[0] = MODES_OFF;
            for (int i = 1; i <= ModeSwitch.MAX_AUX; i++) armSwitches[i] = "AUX" + i;
            armChoice = new javax.swing.JComboBox<>(armSwitches);
            armChoice.setToolTipText("The switch that arms the drone (up = armed; an ARM already on that switch keeps its own range). "
                    + "ARM on any other switch is taken off.");
            armChoice.setSelectedIndex(armAux);
            TacticalCombo.install(armChoice);
            armChoice.addActionListener(e -> armChoiceChanged());
            row.add(armChoice);
            row.add(javax.swing.Box.createHorizontalStrut(10));
            row.add(Theme.label("GPS RESCUE", Theme.headingFont(12f), Theme.MUTED));
            String[] switches = new String[ModeSwitch.MAX_AUX + 1];
            switches[0] = "None";
            for (int i = 1; i <= ModeSwitch.MAX_AUX; i++) switches[i] = "AUX" + i;
            rescueChoice = new javax.swing.JComboBox<>(switches);
            rescueChoice.setToolTipText("A switch to call GPS rescue yourself; fitted when Betaflight's failsafe is GPS rescue");
            rescueChoice.setSelectedIndex(rescueAux);
            TacticalCombo.install(rescueChoice);
            rescueChoice.addActionListener(e -> rescueChoiceChanged());
            row.add(rescueChoice);
            row.add(Theme.label("switch up = rescue; fitted when the failsafe is GPS rescue",
                    Theme.font(java.awt.Font.PLAIN, 12.5f), Theme.MUTED));
            return row;
        }

        /** A switch choice changed: an older radio calibration may have any switch on that AUX (see RADIO_REDO_KEY). */
        private void switchChanged() {
            main.save(Map.of(RADIO_REDO_KEY, "true"));
            switches.note(radioRedoText(main.config()));
        }

        private void armChoiceChanged() {
            int aux = armChoice.getSelectedIndex();
            if (aux == armAux) return;
            armAux = aux;
            main.save(Map.of(ModeSwitch.ARM_KEY, aux == 0 ? ModeSwitch.OFF : Integer.toString(aux)));
            armRow.set(State.PENDING, aux == 0 ? "Left as set in Betaflight." : "<b>AUX" + aux + "</b> - written when the flight controller is found.");
            switchChanged();
            showManual();
            if (!found || failed) return;
            done = false;
            applies = 0;
            nextTry = 0;
        }

        private void rescueChoiceChanged() {
            if (revertingChoice) return;
            int aux = rescueChoice.getSelectedIndex();
            if (aux == rescueAux) return;
            String tooOld = ModeSwitch.Mode.GPS_RESCUE.tooOld(fcVersion);
            if (aux > 0 && tooOld != null) { // this flight controller's Betaflight is too old: not allowed
                revertingChoice = true;
                rescueChoice.setSelectedIndex(rescueAux);
                revertingChoice = false;
                rescueRow.set(State.WARN, tooOld);
                return;
            }
            rescueAux = aux;
            main.save(Map.of(ModeSwitch.RESCUE_KEY, aux == 0 ? ModeSwitch.OFF : Integer.toString(aux)));
            showRescueChoice();
            switchChanged();
            showManual();
            if (!found || failed) return;
            done = false;
            applies = 0;
            nextTry = 0;
        }

        private void failsafeChoiceChanged() {
            if (revertingChoice) return;
            ModeSwitch.Failsafe f = (ModeSwitch.Failsafe) failsafeCombo.getSelectedItem();
            if (f == null || f == failsafeChoice) return;
            String tooOld = ModeSwitch.Mode.GPS_RESCUE.tooOld(fcVersion);
            if (f == ModeSwitch.Failsafe.GPS_RESCUE && tooOld != null) { // too old a Betaflight: not allowed
                revertingChoice = true;
                failsafeCombo.setSelectedItem(failsafeChoice);
                revertingChoice = false;
                failsafeRow.set(State.WARN, tooOld);
                return;
            }
            failsafeChoice = f;
            main.save(Map.of(ModeSwitch.Failsafe.KEY, f.name()));
            showFailsafeChoice();
            if (!found || failed) return;
            done = false;
            applies = 0;
            nextTry = 0;
        }

        private void showFailsafeChoice() {
            ModeSwitch.Failsafe f = failsafeChoice;
            failsafeRow.set(f == ModeSwitch.Failsafe.KEEP ? State.INFO : State.PENDING, f == ModeSwitch.Failsafe.KEEP
                    ? "Left as set in Betaflight (read when the flight controller is found)."
                    : "<b>" + f.title + "</b> - written when the flight controller is found"
                    + (f == ModeSwitch.Failsafe.GPS_RESCUE ? ", once the GPS answers." : "."));
        }

        private void showRescueChoice() {
            int aux = rescueAux;
            rescueRow.set(aux == 0 ? State.INFO : State.PENDING, aux == 0 ? "Not on a switch."
                    : "<b>AUX" + aux + "</b> up: GPS rescue - fitted if Betaflight's failsafe is GPS rescue. Read when the "
                    + "flight controller is found.");
        }

        private void enablePositions() {
            for (var c : positionChoices) c.setEnabled(auxChoice.getSelectedIndex() > 0);
        }

        private void modeChoiceChanged() {
            if (revertingChoice) return;
            enablePositions();
            int aux = auxChoice.getSelectedIndex();
            ModeSwitch m = aux == 0 ? null : new ModeSwitch(aux, (ModeSwitch.Mode) positionChoices.get(0).getSelectedItem(),
                    (ModeSwitch.Mode) positionChoices.get(1).getSelectedItem(), (ModeSwitch.Mode) positionChoices.get(2).getSelectedItem());
            if (java.util.Objects.equals(m, modeSwitch)) return;
            // a mode this flight controller's Betaflight is too old for can't be picked
            for (int i = 0; m != null && i < 3; i++) {
                ModeSwitch.Mode chosen = m.positions()[i];
                ModeSwitch old = modeSwitch == null ? ModeSwitch.DEFAULT : modeSwitch;
                String tooOld = chosen.tooOld(fcVersion);
                if (tooOld != null && chosen != old.positions()[i]) {
                    revertingChoice = true;
                    positionChoices.get(i).setSelectedItem(old.positions()[i]);
                    revertingChoice = false;
                    modeRow.set(State.WARN, tooOld);
                    return;
                }
            }
            modeSwitch = m;
            main.save(Map.of(ModeSwitch.KEY, m == null ? ModeSwitch.OFF : m.format()));
            showModeChoice();
            switchChanged();
            showManual();
            if (!found || failed) return; // written as soon as the flight controller is there
            done = false;
            applies = 0;
            nextTry = 0;
        }

        /** After plugging the battery in (some boards only power the GPS from it), or fixing its wiring. */
        private void checkModesAgain() {
            if (!found || busy) return;
            done = false;
            applies = 0;
            nextTry = 0;
            modeRow.set(State.PENDING, "Reading Betaflight again" + (modeSwitch != null && !modeSwitch.gpsModes().isEmpty()
                    ? " and looking for the GPS (up to " + GPS_WAIT_MS / 1000 + " s)..." : "..."));
        }

        /** WRITE: the listed changes go to Betaflight now; from then on changed choices are written as they come. */
        private void writeNow() {
            writeAllowed = true;
            writeButton.setVisible(false);
            changes.set(State.PENDING, "Writing...");
            done = false;
            applies = 0;
            nextTry = 0;
            wizard.update();
        }

        private void showModeChoice() {
            ModeSwitch m = modeSwitch;
            modeRow.set(m == null ? State.INFO : State.PENDING, m == null
                    ? "Left as set in Betaflight's Modes tab."
                    : "<b>AUX" + m.aux() + "</b>: " + m.describe() + ". Written when the flight controller is found.");
        }

        private void motorsAlreadyChecked() {
            main.save(Map.of(MOTORS_CHECKED_KEY, "true"));
            motors.set(State.OK, "Marked as checked. The motor test is in SETTINGS any time.");
        }

        private void checkMotorsNow() {
            if (main.isLinkActive()) {
                motors.set(State.WARN, "Disconnect from the drone first: the motor test runs over this PC's USB, with the drone on the bench.");
                return;
            }
            motorTestOpen = true; // the page's own rounds would fight the motor test for the USB port
            motors.set(State.PENDING, "The motor test is open. Close it when you're done.");
            MotorTestDialog.open(javax.swing.SwingUtilities.getWindowAncestor(motors), main, () -> {
                motorTestOpen = false;
                nextTry = System.currentTimeMillis() + 1500; // the motor test lets go of the port first
                main.save(Map.of(MOTORS_CHECKED_KEY, "true"));
                motors.set(State.OK, "Checked with the motor test. <b>Check now</b> runs it again.");
            });
        }

        private int savedCells() {
            try {
                int n = Integer.parseInt(main.config().getString(CELLS_KEY));
                if (n >= BetaflightPlan.MIN_CELLS && n <= BetaflightPlan.MAX_CELLS) return n;
            } catch (NumberFormatException ignored) {
                // not chosen yet
            }
            return BetaflightPlan.DEFAULT_CELLS;
        }

        /** A new count: remembered, and written to the flight controller again (it may already have the old one). */
        private void cellsChanged(int n) {
            if (n == cells) return;
            cells = n;
            if (syncingSpinner) return; // Betaflight's own forced count shown, not the pilot's choice
            cellsConfirmed = true; // touched: the pilot's count
            confirmCells.setVisible(false);
            measuredCells.setVisible(measured > 0 && measured != cells);
            main.save(Map.of(CELLS_KEY, Integer.toString(n), CELLS_CONFIRMED_KEY, "true"));
            showManual();
            if (!found || failed) return; // written as soon as the flight controller is there
            done = false;
            applies = 0;
            nextTry = 0;
            battery.set(State.PENDING, writeAllowed ? "Setting " + n + "S..." : n + "S - written with <b>Write</b>.");
        }

        /** CONFIRM: the count shown is right; from now on Betaflight is set to always use it. */
        private void confirmCells() {
            cellsConfirmed = true;
            confirmCells.setVisible(false);
            main.save(Map.of(CELLS_KEY, Integer.toString(cells), CELLS_CONFIRMED_KEY, "true"));
            if (!found || failed) {
                battery.set(State.PENDING, cells + "S - written when the flight controller is found.");
                return;
            }
            done = false;
            applies = 0;
            nextTry = 0;
            battery.set(State.PENDING, writeAllowed ? "Setting " + cells + "S..." : cells + "S - written with <b>Write</b>.");
        }

        /** The manual way, for a drone without USB to this PC (and for whatever couldn't be set by itself). */
        private void showByHand() {
            javax.swing.JLabel text = new javax.swing.JLabel("<html><div style='width:520px'>" + manualHtml + "</div></html>");
            text.setFont(Theme.font(java.awt.Font.PLAIN, 14f));
            javax.swing.JOptionPane.showMessageDialog(javax.swing.SwingUtilities.getWindowAncestor(usb), text,
                    "Betaflight settings by hand", javax.swing.JOptionPane.INFORMATION_MESSAGE);
        }

        /** The "By hand" button stands out while something needs doing that way. */
        private void markByHand(boolean needed) {
            if (byHand != null) byHand.setText(needed ? "By hand (needed)" : "By hand");
        }

        private void showManual() {
            ModeSwitch m = modeSwitch;
            int rescue = rescueAux;
            int arm = armAux;
            manualHtml = ("Can't plug the drone into this PC? Make the same settings in Betaflight Configurator: "
                    + "<b>Receiver</b> tab > Receiver Mode <b>MSP RX input</b> > Save. "
                    + "<b>CLI</b> tab > <b>set serial_update_rate_hz = 2000</b> (1000 on F411 boards), "
                    + "<b>set force_battery_cell_count = " + cells + "</b> > <b>save</b>."
                    + (arm == 0 ? "" : " <b>Modes</b> tab: <b>ARM</b> on AUX" + arm + " 1700-2100.")
                    + (m == null ? "" : " <b>Modes</b> tab: on AUX" + m.aux() + ", " + modeRanges(m) + " > Save.")
                    + (failsafeChoice == ModeSwitch.Failsafe.KEEP ? "" : " <b>Failsafe</b> tab: Stage 2 procedure <b>"
                    + failsafeChoice.title + "</b>.")
                    + (rescue == 0 ? "" : " With the failsafe on GPS rescue: <b>GPS RESCUE</b> on AUX" + rescue + " 1700-2100."));
        }

        /** "ANGLE 1300-1700, POS HOLD 1700-2100" for the manual way, named as Betaflight Configurator's Modes tab does. */
        private static String modeRanges(ModeSwitch m) {
            String[] ranges = {"900-1300", "1300-1700", "1700-2100"};
            List<String> parts = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                if (m.positions()[i] != ModeSwitch.Mode.ACRO) parts.add("<b>" + m.positions()[i].configuratorName + "</b> " + ranges[i]);
            }
            return parts.isEmpty() ? "nothing (acro throughout)" : String.join(", ", parts);
        }

        private static final String PAUSED_TEXT = "Disconnected: the USB port is free, e.g. for Betaflight Configurator. Press <b>Reconnect</b> to carry on.";

        /** DISCONNECT / RECONNECT: the rounds stop, so the USB port is free; a round in progress finishes its read first. */
        private void togglePaused() {
            paused = !paused;
            disconnect.setText(paused ? "Reconnect" : "Disconnect");
            if (paused) {
                abandoned = true; // a round still running reads, but writes nothing
                found = false;
                failed = false;
                done = false;
                markByHand(false);
                usb.set(busy ? State.PENDING : State.INFO, busy ? "Finishing the current read, then the port is free..." : PAUSED_TEXT);
                for (Part p : new Part[]{receiver, stickDelay, battery}) p.set(State.PENDING, "");
            } else {
                abandoned = false;
                applies = 0;
                nextTry = 0;
                usb.set(State.PENDING, "Looking for it...");
            }
            wizard.update();
        }

        @Override
        void onShow() {
            abandoned = paused;
        }

        @Override
        void onHide() {
            abandoned = true; // a round still running may read, but must not write
        }

        @Override
        void refresh() {
            // the motor test may have the port, however it was opened (Check now or SETTINGS)
            if (paused || motorTestOpen || MotorTestDialog.isOpen() || busy || done || System.currentTimeMillis() < nextTry) return;
            busy = true;
            boolean mayApply = writeAllowed && applies < MAX_APPLIES;
            int cellTarget = cells;
            boolean cellsOk = cellsConfirmed;
            ModeSwitch modeTarget = modeSwitch;
            int rescueTarget = rescueAux;
            ModeSwitch.Failsafe failsafeTarget = failsafeChoice;
            int armTarget = armAux;
            background(() -> work(mayApply, cellTarget, cellsOk, modeTarget, rescueTarget, failsafeTarget, armTarget), this::show);
        }

        /** One round: find the FC, read the settings, and change them in the same CLI session if needed. */
        private Read work(boolean mayApply, int cellTarget, boolean cellsOk, ModeSwitch modeTarget, int rescueTarget,
                          ModeSwitch.Failsafe failsafeTarget, int armTarget) {
            List<BetaflightUsb.Port> all = BetaflightUsb.findFlightControllers();
            if (all.isEmpty()) return null;
            List<String> names = all.stream().map(BetaflightUsb.Port::systemName).toList();
            String chosen = chosenPort;
            List<BetaflightUsb.Port> ports = chosen == null ? all : all.stream().filter(p -> p.systemName().equals(chosen)).toList();
            if (ports.isEmpty()) {
                return new Read(chosen, chosen + " isn't plugged in - pick another port, or Automatic.", cellTarget, modeTarget,
                        rescueTarget, failsafeTarget, armTarget, names);
            }
            // each port that looks like one, not just the first: a GPS or ESC adapter may come first. Asked over MSP
            // first (no restart, nothing written); only Betaflight gets the CLI
            String why = null;
            for (BetaflightUsb.Port port : ports) {
                try (BetaflightUsb fc = BetaflightUsb.open(port)) {
                    String firmware = fc.firmware();
                    if (!firmware.startsWith("BTFL")) {
                        why = port.systemName() + " runs <b>" + firmwareName(firmware) + "</b>, not Betaflight. DRAUPNE Link needs "
                                + "Betaflight: flash it with Betaflight Configurator. Nothing was changed.";
                        continue;
                    }
                    return work(fc, port, mayApply, cellTarget, cellsOk, modeTarget, rescueTarget, failsafeTarget, armTarget, names);
                } catch (IOException e) {
                    why = port.systemName() + ": " + e.getMessage();
                }
            }
            return new Read(ports.get(0).systemName(), why, cellTarget, modeTarget, rescueTarget, failsafeTarget, armTarget, names);
        }

        private Read work(BetaflightUsb fc, BetaflightUsb.Port port, boolean mayApply, int cellTarget, boolean cellsOk, ModeSwitch modeTarget,
                          int rescueTarget, ModeSwitch.Failsafe failsafeTarget, int armTarget, List<String> ports) {
            try {
                java.util.Set<Integer> modesOffered = null; // over MSP, before the CLI: which modes this board has
                try {
                    modesOffered = fc.boxIds();
                } catch (IOException e) {
                    // unknown: every chosen mode is written
                }
                BetaflightUsb.BatteryState batteryNow = null;
                try {
                    batteryNow = fc.batteryState();
                } catch (IOException e) {
                    // an old Betaflight without it
                }
                int featureMask = fc.featureMask();
                // a GPS mode chosen: is a GPS there? Only asked while Betaflight's GPS is on (else it is turned on first)
                Boolean gpsFound = null;
                if (modeTarget != null && !modeTarget.gpsModes().isEmpty() || rescueTarget > 0
                        || failsafeTarget == ModeSwitch.Failsafe.GPS_RESCUE) {
                    try {
                        if (BetaflightPlan.hasGps(featureMask)) gpsFound = fc.waitForGps(GPS_WAIT_MS);
                    } catch (IOException e) {
                        // not known: GPS modes wait
                    }
                }
                // the receiver, over MSP and before the CLI (see BetaflightPlan): the CLI's save below stores it
                boolean mspRx = BetaflightPlan.isMspReceiver(featureMask);
                String receiverNow = BetaflightPlan.receiverNames(featureMask);
                boolean switchRx = !mspRx && mayApply && !abandoned;
                if (switchRx) fc.setFeatureMask(BetaflightPlan.mspReceiverMask(featureMask));
                fc.enterCli();
                String version = BetaflightPlan.version(fc.command("version"));
                String board = boardName(fc);
                // where the arm switch is: the sticks then also wait for it to be off (RcSession)
                String aux = fc.command("aux");
                ArmSwitch arm = ArmSwitch.fromCli(aux);
                if (arm != null) main.save(Map.of(ArmSwitch.KEY, arm.format()));
                String rate = fc.command("get serial_update_rate_hz");
                String forced = fc.command("get force_battery_cell_count");
                List<BetaflightPlan.Change> plan = new java.util.ArrayList<>(BetaflightPlan.plan(rate, version, forced, cellTarget, cellsOk));
                if (!mspRx) {
                    plan.add(0, new BetaflightPlan.Change("Receiver: MSP RX input (the phone sends the sticks over MSP), now "
                            + receiverNow, List.of()));
                }
                java.util.Map<ModeSwitch.Mode, String> blocked = new java.util.EnumMap<>(ModeSwitch.Mode.class);
                String rescueNote = null;
                String failsafeNote = null;
                String failsafeProblem = null;
                // the failsafe, as chosen; the rescue switch only goes on while the failsafe is (or becomes) GPS rescue
                boolean readFailsafe = rescueTarget > 0 || failsafeTarget != ModeSwitch.Failsafe.KEEP;
                String failsafe = readFailsafe ? ModeSwitch.Failsafe.current(fc.command("get failsafe_procedure")) : null;
                boolean rescueNow = "GPS-RESCUE".equals(failsafe);
                boolean rescueWanted = failsafeTarget == ModeSwitch.Failsafe.GPS_RESCUE
                        || (rescueNow && failsafeTarget == ModeSwitch.Failsafe.KEEP);
                List<ModeSwitch.Mode> gpsModes = new java.util.ArrayList<>(modeTarget == null ? List.of() : modeTarget.gpsModes());
                if ((rescueWanted && rescueTarget > 0 || failsafeTarget == ModeSwitch.Failsafe.GPS_RESCUE)
                        && !gpsModes.contains(ModeSwitch.Mode.GPS_RESCUE)) {
                    gpsModes.add(ModeSwitch.Mode.GPS_RESCUE);
                }
                if (!gpsModes.isEmpty()) {
                    boolean gpsOn = BetaflightPlan.hasGps(featureMask);
                    boolean gpsPort = BetaflightPlan.hasGpsPort(fc.command("serial"));
                    boolean turnOn = false;
                    for (ModeSwitch.Mode m : gpsModes) {
                        String tooOld = m.tooOld(version);
                        if (tooOld != null) {
                            blocked.put(m, tooOld);
                        } else if (!gpsOn) {
                            turnOn = true;
                            blocked.put(m, "Betaflight's GPS is being turned on; the GPS is looked for once it has restarted.");
                        } else if (!Boolean.TRUE.equals(gpsFound)) {
                            blocked.put(m, noGps(gpsPort));
                        }
                    }
                    if (turnOn) plan.add(new BetaflightPlan.Change("GPS turned on (feature GPS)", List.of("feature GPS")));
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
                // GPS rescue anywhere in Betaflight (failsafe or switch): it then arms only with a GPS fix
                boolean rescueUsed = rescueFailsafe || (modeTarget != null && modeTarget.gpsModes().contains(ModeSwitch.Mode.GPS_RESCUE)
                        && !blocked.containsKey(ModeSwitch.Mode.GPS_RESCUE));
                if (rescueUsed && !fc.command("get gps_rescue_allow_arming_without_fix").replace(" ", "").contains("=ON")) {
                    String rule = "Betaflight then won't arm until the GPS has a fix (arming blocked: RESC) - indoors too. "
                            + "Betaflight's gps_rescue_allow_arming_without_fix changes that.";
                    if (rescueFailsafe) failsafeNote = rule;
                    else rescueNote = "With GPS rescue on a switch, " + Character.toLowerCase(rule.charAt(0)) + rule.substring(1);
                }
                ModeSwitch.Plan modes = modeTarget == null && rescueOn == 0 && armTarget == 0 ? null
                        : ModeSwitch.plan(aux, modesOffered, blocked, modeTarget, rescueOn, armTarget);
                List<String> replaced = modes == null ? List.of() : modes.replaced();
                if (modes != null && !modes.commands().isEmpty()) {
                    List<String> what = new java.util.ArrayList<>();
                    if (modeTarget != null && modes.problem() == null) what.add("flight modes on AUX" + modeTarget.aux() + ": " + modeTarget.describe());
                    if (rescueOn > 0 && modes.rescueProblem() == null) what.add("GPS rescue up on AUX" + rescueOn);
                    if (armTarget > 0 && modes.armProblem() == null) what.add("ARM on AUX" + armTarget);
                    plan.add(new BetaflightPlan.Change("Modes: " + String.join("; ", what) + "." + takenOff(replaced), modes.commands()));
                }
                boolean apply = mayApply && !plan.isEmpty() && version.startsWith("Betaflight"); // never INAV & co.
                if (apply && abandoned) { // the page was left meanwhile: nothing is written behind the pilot's back
                    fc.exitCli();
                    main.logLine("Betaflight: the page was left while reading - nothing written.");
                } else {
                    java.nio.file.Path backup = null;
                    String refused = null;
                    if (apply) {
                        String diff = fc.command("diff all", 20_000); // what it was, before anything changes
                        if (switchRx) diff = BetaflightPlan.restoreReceiver(diff, featureMask); // but for the receiver, already switched
                        backup = backup(diff, version);
                        List<String> refusals = new java.util.ArrayList<>();
                        for (BetaflightPlan.Change c : plan) {
                            for (String cmd : c.commands()) {
                                String reply = fc.command(cmd);
                                main.logLine("Betaflight CLI: " + cmd + " -> " + brief(reply));
                                if (BetaflightPlan.refused(reply)) refusals.add(cmd + " -> " + reply.lines().findFirst().orElse("").trim());
                            }
                        }
                        fc.saveAndReboot();
                        main.logLine("Betaflight: " + plan.stream().map(BetaflightPlan.Change::description).collect(Collectors.joining("; "))
                                + (backup == null ? "" : ". Its settings before: " + backup));
                        if (!refusals.isEmpty()) {
                            refused = String.join("; ", refusals);
                            main.logLine("Betaflight refused: " + refused);
                        }
                    } else {
                        fc.exitCli(); // Betaflight restarts (nothing changed, or the receiver only in RAM) - which also clears a stale "MSP" arming block
                    }
                    return new Read(port.systemName(), version, mspRx, BetaflightPlan.serialRate(rate),
                            BetaflightPlan.targetSerialRate(version), BetaflightPlan.forcedCells(forced), cellTarget, apply, null, arm, backup, ports,
                            modeTarget, modes, gpsFound, rescueNote, rescueTarget, failsafe, failsafeTarget, rescueFailsafe,
                            failsafeProblem, failsafeNote, armTarget, refused, List.copyOf(plan), replaced, board, batteryNow,
                            ModeSwitch.armAuxes(aux), receiverNow, cellsOk);
                }
                return new Read(port.systemName(), version, mspRx, BetaflightPlan.serialRate(rate),
                        BetaflightPlan.targetSerialRate(version), BetaflightPlan.forcedCells(forced), cellTarget, false, null, arm, null, ports,
                        modeTarget, modes, gpsFound, rescueNote, rescueTarget, failsafe, failsafeTarget, rescueFailsafe,
                        failsafeProblem, failsafeNote, armTarget, null, List.copyOf(plan), replaced, board, batteryNow,
                        ModeSwitch.armAuxes(aux), receiverNow, cellsOk);
            } catch (IOException e) {
                return new Read(port.systemName(), e.getMessage(), cellTarget, modeTarget, rescueTarget, failsafeTarget, armTarget, ports);
            }
        }

        /** The board's name and the craft's ("IFLIGHT_BLITZ_F722 - Vanguard"), from the CLI; "" when neither is set. */
        private static String boardName(BetaflightUsb fc) throws IOException {
            String board = BetaflightPlan.boardName(fc.command("board_name"));
            String craft = BetaflightPlan.craftName(fc.command("get craft_name")); // Betaflight 4.4 on
            if (craft.isEmpty()) craft = BetaflightPlan.craftName(fc.command("name")); // older
            if (board.isEmpty()) return craft;
            return craft.isEmpty() ? board : board + " - " + craft;
        }

        /** A CLI reply's first line, short, for the log; "(ok)" when Betaflight printed nothing. */
        static String brief(String reply) {
            String line = reply.lines().map(String::trim).filter(l -> !l.isEmpty()).findFirst().orElse("");
            if (line.isEmpty()) return "(ok)";
            return line.length() > 80 ? line.substring(0, 77) + "..." : line;
        }

        /** " Taken off: ARM on AUX3, BEEPER on AUX2." or "". */
        private static String takenOff(List<String> replaced) {
            return replaced.isEmpty() ? "" : " Taken off: " + String.join(", ", replaced) + ".";
        }

        /** MSP's four-letter firmware id as a name: INAV, ArduPilot, Cleanflight ... */
        static String firmwareName(String variant) {
            return switch (variant) {
                case "INAV" -> "INAV";
                case "ARDU" -> "ArduPilot";
                case "CLFL" -> "Cleanflight";
                case "BAFL" -> "Baseflight";
                case "EMUF" -> "EmuFlight";
                case "" -> "something that doesn't answer MSP";
                default -> variant;
            };
        }

        /** The GPS didn't answer within GPS_WAIT_MS: Betaflight's "GPS hardware failure". */
        private static String noGps(boolean gpsPort) {
            return "No GPS found: Betaflight gets nothing from it (GPS hardware failure). " + (gpsPort
                    ? "Is one fitted and wired to the port set for it? Some boards only power the GPS with the battery in: "
                    + "plug it in (props off) and press <b>Read again</b>. No fix is needed - indoors is fine."
                    : "No port is set for it: in Betaflight's Ports tab, set <b>Sensor input: GPS</b> on the GPS's UART, "
                    + "then press <b>Read again</b>.") + " That position flies acro meanwhile.";
        }

        /** Betaflight's "diff all" into Documents\DRAUPNE Link\Betaflight backups; null (and logged) if it can't be saved. */
        private java.nio.file.Path backup(String diff, String version) {
            try {
                String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
                java.nio.file.Path file = de.droiddrone.pc.AppPaths.betaflightBackupsDir().resolve("betaflight-" + stamp + ".txt");
                java.nio.file.Files.writeString(file, "# " + version + "\n# saved by DRAUPNE Link before Drone setup changed it. "
                        + "To go back: paste this into Betaflight Configurator's CLI tab, then type save.\n\n" + diff + "\n");
                return file;
            } catch (IOException e) {
                main.logLine("Could not save a backup of Betaflight's settings: " + e.getMessage());
                return null;
            }
        }

        private static String describe(ArmSwitch a) {
            String ranges = a.ranges().stream().map(r -> r[0] + "-" + r[1]).collect(Collectors.joining(", "));
            return "<b>AUX" + (a.channel() - 3) + "</b> (" + ranges + "). The sticks only start with it off and the throttle down.";
        }

        /** The port list: Automatic plus every port found, keeping the choice. */
        private void showPorts(List<String> ports) {
            if (ports == null) return;
            java.util.List<String> items = new java.util.ArrayList<>();
            items.add(AUTOMATIC);
            items.addAll(ports);
            if (chosenPort != null && !items.contains(chosenPort)) items.add(chosenPort);
            javax.swing.DefaultComboBoxModel<String> model = (javax.swing.DefaultComboBoxModel<String>) portChoice.getModel();
            boolean same = model.getSize() == items.size();
            for (int i = 0; same && i < items.size(); i++) same = items.get(i).equals(model.getElementAt(i));
            if (same) return;
            model.removeAllElements();
            items.forEach(model::addElement);
            model.setSelectedItem(chosenPort == null ? AUTOMATIC : chosenPort);
        }

        private void show(Read r) {
            busy = false;
            long now = System.currentTimeMillis();
            if (paused) { // DISCONNECT came while this round ran: it is over, the port is free
                usb.set(State.INFO, PAUSED_TEXT);
                wizard.update();
                return;
            }
            found = r != null;
            failed = r != null && r.error() != null;
            markByHand(r == null);
            readAgain.setText(r == null || r.error() != null ? "Retry" : "Read again");
            if (r == null) {
                nextTry = now + 1500;
                if (now - restartedAt < RESTART_MS) {
                    usb.set(State.PENDING, "Betaflight restarts...");
                } else {
                    usb.set(State.WARN, "Not found yet. Use a USB <b>data</b> cable (some only charge). If Windows doesn't see the drone, "
                            + "install the STM32 VCP driver (ImpulseRC Driver Fixer) once.");
                }
                receiver.set(State.PENDING, "");
                stickDelay.set(State.PENDING, "");
                return;
            }
            showPorts(r.ports());
            portChoice.setVisible(r.ports() != null && r.ports().size() > 1 || chosenPort != null);
            if (r.error() != null) {
                nextTry = now + 2000;
                sameErrorCount = r.error().equals(lastError) ? sameErrorCount + 1 : 1;
                lastError = r.error();
                usb.set(State.BAD, r.error() + (sameErrorCount >= SAME_ERROR_LIMIT
                        ? "<br><b>Unplug and plug the drone's USB in again.</b>"
                        : "<br>Trying again by itself every few seconds, or press <b>Retry</b>."));
                return;
            }
            lastError = null;
            sameErrorCount = 0;
            restartedAt = now; // the CLI was open and is closed now: Betaflight restarts
            if (!r.version().isEmpty()) fcVersion = r.version();
            if (r.applied()) lastRefused = r.refused();
            if (r.applied() && r.modes() != null) lastReplaced = r.modes().replaced();
            if (r.switchesWritten()) switchChanged();
            String usbText = "<b>" + r.port() + "</b>" + (r.version().isEmpty() ? "" : " - " + BetaflightPlan.shortVersion(r.version()))
                    + (r.board().isEmpty() ? "" : " - " + r.board())
                    + (r.backup() == null ? "" : "<br>Its settings before the change: " + r.backup())
                    + (lastRefused == null ? "" : "<br>Betaflight refused: " + lastRefused);
            usb.set(lastRefused == null ? State.OK : State.WARN, usbText);
            showMeasured(r.battery());
            if (r.applied()) {
                applies++;
                nextTry = now + 4000; // then read again, to check the settings stuck
                changes.set(State.PENDING, "Saved - Betaflight restarts, then the settings are checked...");
                receiver.set(State.PENDING, "Saved - Betaflight restarts...");
                if (r.modes() != null && !r.modes().blocked().isEmpty() && r.modeTarget() != null) {
                    modeRow.set(State.PENDING, "Saved - Betaflight restarts, then the GPS is looked for (up to "
                            + GPS_WAIT_MS / 1000 + " s; no fix needed)...");
                }
                stickDelay.set(State.PENDING, "");
                battery.set(State.PENDING, "");
                return;
            }
            if (r.cellTarget() != cells || r.cellsConfirmed() != cellsConfirmed || !java.util.Objects.equals(r.modeTarget(), modeSwitch)
                    || r.rescueTarget() != rescueAux || r.failsafeTarget() != failsafeChoice || r.armTarget() != armAux) {
                // changed meanwhile: write it
                applies = 0;
                nextTry = 0;
                return;
            }
            done = true;
            // nothing is written before WRITE: the rows say what it would change
            boolean pendingWrite = !writeAllowed && !r.plan().isEmpty();
            showChanges(r, pendingWrite);
            String armText = r.armTarget() > 0 && r.arm() != null && r.arm().channel() - 3 == r.armTarget() ? describe(r.arm()) + takenOff(lastReplaced)
                    : r.arm() == null ? null : describe(r.arm());
            ModeSwitch.Plan armPlan = r.modes();
            if (r.armTarget() > 0 && armPlan != null && armPlan.armProblem() != null) {
                armRow.set(State.WARN, armPlan.armProblem());
            } else if (r.armTarget() > 0 && (r.arm() == null || r.arm().channel() - 3 != r.armTarget())) {
                armRow.set(pendingWrite ? State.TODO : State.BAD, pendingWrite
                        ? "<b>AUX" + r.armTarget() + "</b> - written with <b>Write</b>." + takenOff(r.replaced())
                        : "Could not put ARM on AUX" + r.armTarget() + " - set it in the Modes tab (see <b>By hand</b>).");
            } else if (r.arm() == null) {
                armRow.set(State.WARN, r.armAuxes().size() > 1
                        ? "ARM is on more than one switch in Betaflight - set it on one."
                        : "ARM is on no switch in Betaflight - the drone can't be armed. Choose a switch under ARM above (AUX1 is usual).");
            } else {
                armRow.set(State.OK, armText);
            }
            receiver.set(r.msp() ? State.OK : pendingWrite ? State.TODO : State.BAD, r.msp() ? "MSP RX input."
                    : pendingWrite ? "MSP RX input - now " + r.receiverNow() + ". Written with <b>Write</b>."
                    : "Could not set it - set it in Betaflight Configurator (see <b>By hand</b>).");
            if (r.rate() < 0) {
                stickDelay.set(State.WARN, "Not in this Betaflight (it came in 4.2) - update Betaflight for the least stick delay.");
            } else if (r.rate() >= r.target()) {
                stickDelay.set(State.OK, "serial_update_rate_hz " + r.rate() + ".");
            } else if (pendingWrite) {
                stickDelay.set(State.TODO, "serial_update_rate_hz " + r.rate() + " -> " + r.target() + ". Written with <b>Write</b>.");
            } else {
                stickDelay.set(State.BAD, "Could not set it (" + r.rate() + ") - set it in the CLI (see <b>By hand</b>).");
            }
            boolean cellsWrong = showCells(r, pendingWrite);
            boolean modesWrong = showModes(r, pendingWrite);
            modesWrong |= showRescue(r, pendingWrite);
            modesWrong |= showFailsafe(r, pendingWrite);
            boolean byHandNeeded = !pendingWrite && (!r.msp() || (r.rate() >= 0 && r.rate() < r.target()) || cellsWrong || modesWrong);
            markByHand(byHandNeeded);
            cleanDone = !byHandNeeded && !pendingWrite && lastRefused == null;
            if (cleanDone) usb.set(State.OK, usbText + "<br><b>Done - you can unplug the drone from the PC.</b>");
        }

        /** The "What will change" row: the plan with WRITE before the first write; what is left after the last try. */
        private void showChanges(Read r, boolean pendingWrite) {
            if (r.plan().isEmpty()) {
                changes.setVisible(false);
                return;
            }
            String list = r.plan().stream().map(c -> "&bull; " + c.description()).collect(Collectors.joining("<br>"));
            writeButton.setVisible(pendingWrite);
            changes.set(pendingWrite ? State.TODO : State.WARN, pendingWrite
                    ? list + "<br>Nothing is written until you press <b>Write</b>. Betaflight's settings are backed up first."
                    : "Still not set:<br>" + list + "<br>See the rows below and <b>By hand</b>.");
            changes.setVisible(true);
        }

        /** The battery line. @return true if the count is not as the pilot's (confirmed) choice */
        private boolean showCells(Read r, boolean pendingWrite) {
            if (r.cells() < 0) {
                battery.set(State.WARN, "Not in this Betaflight - it counts the cells itself; the OSD corrects a wrong count.");
                return false;
            }
            if (!r.cellsConfirmed()) {
                if (r.cells() > 0 && main.config().getString(CELLS_KEY).isEmpty() && r.cells() != cells) {
                    syncingSpinner = true; // show the count Betaflight already forces, as the one to confirm
                    cellSpinner.setValue(r.cells());
                    syncingSpinner = false;
                }
                battery.set(State.INFO, (r.cells() > 0 ? "Betaflight already forces <b>" + r.cells() + "S</b> - kept until you confirm a count. "
                        : "Betaflight counts the cells itself. ") + "<b>" + cells + "S</b> - correct? Change it, or press <b>Confirm</b>.");
                return false;
            }
            if (r.cells() == r.cellTarget()) {
                battery.set(State.OK, r.cells() + "S.");
                return false;
            }
            if (pendingWrite) {
                battery.set(State.TODO, r.cellTarget() + "S (Betaflight " + (r.cells() == 0 ? "counts itself" : "forces " + r.cells() + "S")
                        + " now). Written with <b>Write</b>.");
                return false;
            }
            battery.set(State.BAD, "Could not set it (" + r.cells() + ") - set it in the CLI (see <b>By hand</b>).");
            return true;
        }

        /** "Use measured: 4S" while a battery is in and Betaflight's own count differs from the spinner. */
        private void showMeasured(BetaflightUsb.BatteryState b) {
            measured = b != null && b.present() ? b.cells() : 0;
            measuredCells.setText("Use measured: " + measured + "S");
            measuredCells.setVisible(measured > 0 && measured != cells);
        }

        /** The failsafe row after a round that changed nothing. @return true if it is not as chosen */
        private boolean showFailsafe(Read r, boolean pendingWrite) {
            ModeSwitch.Failsafe f = r.failsafeTarget();
            String now = r.failsafe() == null || r.failsafe().isEmpty() ? null : r.failsafe();
            if (f == ModeSwitch.Failsafe.KEEP) {
                failsafeRow.set(State.INFO, now == null ? "Left as set in Betaflight."
                        : "Left as set in Betaflight: <b>" + now + "</b>." + (r.failsafeNote() == null ? "" : "<br>" + r.failsafeNote()));
                return false;
            }
            if (r.failsafeProblem() != null) {
                failsafeRow.set(State.WARN, "Not changed (" + (now == null ? "?" : now) + "): " + r.failsafeProblem());
                return true;
            }
            if (now == null || !now.equals(f.value)) {
                failsafeRow.set(pendingWrite ? State.TODO : State.BAD, pendingWrite
                        ? "<b>" + f.title + "</b> (now " + (now == null ? "?" : now) + ") - written with <b>Write</b>."
                        : "Could not set it - set it in Betaflight Configurator's Failsafe tab.");
                return !pendingWrite;
            }
            String text = "Set: <b>" + f.title + "</b>" + (f == ModeSwitch.Failsafe.GPS_RESCUE
                    ? " - on losing the signal for good, the drone flies home." : " - on losing the signal for good, the motors stop.")
                    + (Boolean.TRUE.equals(r.gpsFound()) && f == ModeSwitch.Failsafe.GPS_RESCUE ? " GPS found." : "");
            failsafeRow.set(r.failsafeNote() == null ? State.OK : State.WARN, r.failsafeNote() == null ? text : text + "<br>" + r.failsafeNote());
            return false;
        }

        /** The rescue switch row after a round that changed nothing. @return true if it is not as chosen */
        private boolean showRescue(Read r, boolean pendingWrite) {
            // remembered for the radio calibration: it names AUX4 (or whichever) GPS rescue only when it is fitted
            main.save(Map.of(ModeSwitch.RESCUE_FITTED_KEY, Boolean.toString(r.rescueTarget() > 0 && r.rescueFitted())));
            int aux = r.rescueTarget();
            if (aux == 0) {
                showRescueChoice();
                return false;
            }
            if (!r.rescueFitted()) {
                String now = r.failsafe() == null ? "" : r.failsafe().replaceAll("(?s).*=\\s*", "").trim();
                rescueRow.set(State.INFO, "Not fitted: Betaflight's failsafe is " + (now.isEmpty() ? "not GPS rescue" : now)
                        + ". It goes on <b>AUX" + aux + "</b> once the failsafe is GPS rescue" + (r.failsafeTarget() == ModeSwitch.Failsafe.GPS_RESCUE
                        ? " (see the failsafe row)." : ": choose <b>GPS rescue</b> under FAILSAFE above."));
                return false;
            }
            ModeSwitch.Plan p = r.modes();
            if (p == null) return false;
            if (p.rescueProblem() != null) {
                rescueRow.set(State.WARN, p.rescueProblem());
                return true;
            }
            if (!p.commands().isEmpty()) {
                rescueRow.set(pendingWrite ? State.TODO : State.BAD, pendingWrite
                        ? "<b>AUX" + aux + "</b> up: GPS rescue - written with <b>Write</b>."
                        : "Could not set it - set GPS RESCUE on AUX" + aux + " 1700-2100 in the Modes tab.");
                return !pendingWrite;
            }
            StringBuilder text = new StringBuilder("Set: <b>AUX" + aux + "</b> up.");
            if (Boolean.TRUE.equals(r.gpsFound())) text.append(" GPS found.");
            List<String> replaced = lastReplaced.stream().filter(x -> x.endsWith("AUX" + aux)).toList();
            if (!replaced.isEmpty()) text.append(" Taken off that switch: ").append(String.join(", ", replaced)).append('.');
            rescueRow.set(State.OK, text.toString());
            return false;
        }

        /** The flight mode row after a round that changed nothing. @return true if they are not as chosen */
        private boolean showModes(Read r, boolean pendingWrite) {
            ModeSwitch m = r.modeTarget();
            ModeSwitch.Plan p = r.modes();
            if (m == null || p == null) {
                showModeChoice();
                return false;
            }
            String where = "<b>AUX" + m.aux() + "</b>: ";
            if (p.problem() != null) {
                modeRow.set(State.WARN, p.problem());
                return true;
            }
            if (!p.isSet()) {
                modeRow.set(pendingWrite ? State.TODO : State.BAD, pendingWrite
                        ? where + m.describe() + " - written with <b>Write</b>." + takenOff(r.replaced())
                        : "Could not set " + where + m.describe() + " - set it in the Modes tab (see <b>By hand</b>).");
                return !pendingWrite;
            }
            boolean partly = !p.blocked().isEmpty() || !p.missing().isEmpty();
            StringBuilder text = new StringBuilder(partly ? "Partly set - " + where + m.describe() + "." : "Set: " + where.trim());
            if (Boolean.TRUE.equals(r.gpsFound())) text.append(" GPS found.");
            text.append(takenOff(lastReplaced));
            for (var b : p.blocked().entrySet()) text.append("<br><b>").append(b.getKey().title).append(":</b> ").append(b.getValue());
            if (r.rescueNote() != null) text.append("<br>").append(r.rescueNote());
            for (ModeSwitch.Mode missing : p.missing()) {
                text.append("<br><b>").append(missing.title).append("</b> isn't offered by this Betaflight (")
                        .append(missing.detail).append("): that position flies acro.");
            }
            if (!p.elsewhere().isEmpty()) {
                text.append("<br>Also in Betaflight's Modes, left as they are: ").append(String.join(", ", p.elsewhere())).append('.');
            }
            boolean warn = !p.missing().isEmpty() || !p.elsewhere().isEmpty() || !p.blocked().isEmpty() || r.rescueNote() != null;
            modeRow.set(warn ? State.WARN : State.OK, text.toString());
            return false;
        }

        @Override
        String status() {
            if (paused) return busy ? "Finishing the current read, then the port is free..." : "Disconnected from the USB port - press Reconnect to carry on.";
            if (busy && found) return "Reading Betaflight...";
            if (done) {
                if (cleanDone) return "Betaflight is set up - you can unplug the drone from the PC.";
                if (!writeAllowed && changes.isVisible()) return "Press Write to make the changes listed, or Next to leave Betaflight as it is.";
                wizard.setStatusColor(Theme.WARN);
                return "Some settings could not be set - see By hand";
            }
            if (!found) return "Waiting for the flight controller (or press Next and use the settings by hand).";
            if (failed) return "Waiting for the flight controller - close or Disconnect Betaflight Configurator, then Retry.";
            return "Setting up Betaflight...";
        }

        @Override
        boolean canContinue() {
            return !(busy && found); // a round reads (and may write) for up to half a minute: not left halfway
        }
    }
}
