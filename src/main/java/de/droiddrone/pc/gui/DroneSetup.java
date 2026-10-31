package de.droiddrone.pc.gui;

import de.droiddrone.pc.ArmSwitch;
import de.droiddrone.pc.gui.CheckRow.State;
import de.droiddrone.pc.gui.WizardDialog.Page;
import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.pc.setup.BetaflightUsb;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.FlowLayout;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Drone setup: the Betaflight settings DRAUPNE Link needs - receiver mode MSP, serial_update_rate_hz and the battery's
 * cell count (6S unless the pilot changes it) - set over USB with nothing else to click. Everything else on the drone
 * (arm switch, accelerometer, failsafe) stays the pilot's own. The motors and props are assumed checked; the pilot
 * can say so, or check them there and then with the motor test.
 */
final class DroneSetup {
    private DroneSetup() {
    }

    /** SETUP &rarr; Drone setup. */
    static void open(MainWindow main) {
        new WizardDialog(main, "Drone setup", List.of(new BetaflightPage(main)), () -> {
            main.save(Map.of("setup.droneDone", "true"));
            main.logLine("Drone setup finished.");
        }).setVisible(true);
        main.reloadSettings();
    }

    /** Finds the flight controller on USB, reads the settings, fixes them, and checks they stuck. */
    static final class BetaflightPage extends Page {
        /** Automatic attempts at changing the settings before the page says it couldn't. */
        private static final int MAX_APPLIES = 2;

        /**
         * @param arm    Betaflight's arm switch, or null if ARM isn't on a switch
         * @param backup where Betaflight's settings were saved before writing, or null
         * @param ports  every port that looked like a flight controller this round
         */
        private record Read(String port, String version, boolean msp, int rate, int target, int cells, int cellTarget,
                            boolean applied, String error, ArmSwitch arm, java.nio.file.Path backup, List<String> ports) {}
        static final String CELLS_KEY = "drone.cellCount";
        static final String MOTORS_CHECKED_KEY = "setup.motorsChecked";

        private final MainWindow main;
        private final CheckRow usb = new CheckRow("Flight controller on USB");
        private final CheckRow receiver = new CheckRow("Receiver mode: MSP");
        private final CheckRow stickDelay = new CheckRow("Stick delay: serial_update_rate_hz");
        private final CheckRow battery = new CheckRow("Battery cell count: force_battery_cell_count");
        private final CheckRow motors = new CheckRow("Motors and props");
        private final CheckRow armRow = new CheckRow("Arm switch");
        /** Which port to use; "Automatic" = the first that answers as Betaflight. */
        private final javax.swing.JComboBox<String> portChoice = new javax.swing.JComboBox<>(new String[]{AUTOMATIC});
        private static final String AUTOMATIC = "Automatic";
        private volatile String chosenPort; // null = automatic
        /** The motor test has the flight controller's USB port: this page leaves it alone meanwhile. */
        private boolean motorTestOpen;
        private JSpinner cellSpinner;
        /** The pilot's choice; read by the background round, so a change made meanwhile is noticed after it. */
        private volatile int cells;
        private WrappingText manual;
        private boolean busy;
        private volatile boolean writing;
        private boolean found;
        private boolean failed;
        private boolean done;
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
            return "<b>Props off.</b> Check the battery's cell count, then plug the flight controller into this PC with USB. "
                    + "The settings DRAUPNE Link needs are made by themselves.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            cells = savedCells();
            cellSpinner = new JSpinner(new SpinnerNumberModel(cells, BetaflightPlan.MIN_CELLS, BetaflightPlan.MAX_CELLS, 1));
            cellSpinner.setFont(Theme.headingFont(16f));
            ((JSpinner.DefaultEditor) cellSpinner.getEditor()).getTextField().setColumns(2);
            cellSpinner.addChangeListener(e -> cellsChanged((Integer) cellSpinner.getValue()));
            JPanel cellRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            cellRow.setOpaque(false);
            cellRow.add(Theme.label("BATTERY CELLS", Theme.headingFont(13f), Theme.TEXT));
            cellRow.add(cellSpinner);
            cellRow.add(Theme.label("S", Theme.headingFont(16f), Theme.TEXT));
            cellRow.add(Theme.label("the packs this drone flies on - Betaflight then always uses this count",
                    Theme.font(java.awt.Font.PLAIN, 12.5f), Theme.MUTED));
            WizardDialog.addRow(col, cellRow);
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
            WizardDialog.addRow(col, usb);
            WizardDialog.addRow(col, receiver);
            WizardDialog.addRow(col, stickDelay);
            WizardDialog.addRow(col, battery);
            WizardDialog.addRow(col, armRow);
            motors.addAction("Already checked", this::motorsAlreadyChecked);
            motors.addPrimaryAction("Check now", this::checkMotorsNow);
            WizardDialog.addRow(col, motors);
            manual = WizardDialog.paragraph("");
            showManual();
            WizardDialog.addRow(col, manual);
            usb.set(State.PENDING, "Looking for it...");
            receiver.set(State.PENDING, "");
            stickDelay.set(State.PENDING, "");
            battery.set(State.PENDING, "");
            ArmSwitch known = ArmSwitch.parse(main.config().getString(ArmSwitch.KEY));
            armRow.set(State.PENDING, known == null ? "Read from Betaflight when the flight controller is found." : describe(known));
            boolean checkedBefore = "true".equalsIgnoreCase(main.config().getString(MOTORS_CHECKED_KEY));
            motors.set(checkedBefore ? State.OK : State.INFO, checkedBefore
                    ? "Checked before. <b>Check now</b> runs the motor test again."
                    : "Assumed checked: each motor in the right place and turning the right way, the props on the right way "
                    + "round. Not sure? <b>Check now</b> opens the motor test (props off).");
            return col;
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
            main.save(Map.of(CELLS_KEY, Integer.toString(n)));
            showManual();
            if (!found || failed) return; // written as soon as the flight controller is there
            done = false;
            applies = 0;
            nextTry = 0;
            battery.set(State.PENDING, "Setting " + n + "S...");
        }

        private void showManual() {
            manual.setHtml("No USB cable? Make the same settings in Betaflight Configurator: "
                    + "<b>Receiver</b> tab > Receiver Mode <b>MSP RX input</b> > Save. "
                    + "<b>CLI</b> tab > <b>set serial_update_rate_hz = 2000</b> (1000 on F411 boards), "
                    + "<b>set force_battery_cell_count = " + cells + "</b> > <b>save</b>.");
        }

        @Override
        void refresh() {
            // the motor test may have the port, however it was opened (Check now or SETTINGS)
            if (motorTestOpen || MotorTestDialog.isOpen() || busy || done || System.currentTimeMillis() < nextTry) return;
            busy = true;
            boolean mayApply = applies < MAX_APPLIES;
            int cellTarget = cells;
            background(() -> work(mayApply, cellTarget), this::show);
        }

        /** One round: find the FC, read the settings, and change them in the same CLI session if needed. */
        private Read work(boolean mayApply, int cellTarget) {
            List<BetaflightUsb.Port> all = BetaflightUsb.findFlightControllers();
            if (all.isEmpty()) return null;
            List<String> names = all.stream().map(BetaflightUsb.Port::systemName).toList();
            String chosen = chosenPort;
            List<BetaflightUsb.Port> ports = chosen == null ? all : all.stream().filter(p -> p.systemName().equals(chosen)).toList();
            if (ports.isEmpty()) {
                return new Read(chosen, "", false, -1, 0, -1, cellTarget, false, chosen + " isn't plugged in - pick another port, or Automatic.",
                        null, null, names);
            }
            // each port that looks like one, not just the first: a GPS or ESC adapter may come first. Asked over MSP
            // first (no restart, nothing written); only Betaflight gets the CLI
            String why = null;
            for (BetaflightUsb.Port port : ports) {
                try (BetaflightUsb fc = BetaflightUsb.open(port)) {
                    String firmware = fc.firmware();
                    if (!firmware.startsWith("BTFL")) {
                        why = port.systemName() + " runs " + (firmware.isEmpty() ? "something else" : firmware) + ", not Betaflight - nothing was changed.";
                        continue;
                    }
                    return work(fc, port, mayApply, cellTarget, names);
                } catch (IOException e) {
                    why = port.systemName() + ": " + e.getMessage();
                }
            }
            return new Read(ports.get(0).systemName(), "", false, -1, 0, -1, cellTarget, false, why, null, null, names);
        }

        private Read work(BetaflightUsb fc, BetaflightUsb.Port port, boolean mayApply, int cellTarget, List<String> ports) {
            try {
                fc.enterCli();
                String version = BetaflightPlan.version(fc.command("version"));
                // where the arm switch is: the sticks then also wait for it to be off (RcSession)
                ArmSwitch arm = ArmSwitch.fromCli(fc.command("aux"));
                if (arm != null) main.save(Map.of(ArmSwitch.KEY, arm.format()));
                String features = fc.command("feature");
                String rate = fc.command("get serial_update_rate_hz");
                String forced = fc.command("get force_battery_cell_count");
                List<BetaflightPlan.Change> plan = BetaflightPlan.plan(features, rate, version, forced, cellTarget);
                boolean apply = mayApply && !plan.isEmpty() && version.startsWith("Betaflight"); // never INAV & co.
                java.nio.file.Path backup = null;
                if (apply) {
                    writing = true;
                    backup = backup(fc.command("diff all", 20_000), version); // what it was, before anything changes
                    for (BetaflightPlan.Change c : plan) {
                        for (String cmd : c.commands()) fc.command(cmd);
                    }
                    fc.saveAndReboot();
                    main.logLine("Betaflight: " + plan.stream().map(BetaflightPlan.Change::description).collect(Collectors.joining("; "))
                            + (backup == null ? "" : ". Its settings before: " + backup));
                } else {
                    fc.exitCli(); // Betaflight restarts (nothing changed) - which also clears a stale "MSP" arming block
                }
                return new Read(port.systemName(), version, BetaflightPlan.isMspReceiver(features), BetaflightPlan.serialRate(rate),
                        BetaflightPlan.targetSerialRate(version), BetaflightPlan.forcedCells(forced), cellTarget, apply, null, arm, backup, ports);
            } catch (IOException e) {
                return new Read(port.systemName(), "", false, -1, 0, -1, cellTarget, false, e.getMessage(), null, null, ports);
            }
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
            return "<b>AUX" + (a.channel() - 3) + "</b>, armed at " + ranges + ". The sticks only start once it is off (and the throttle down).";
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
            writing = false;
            found = r != null;
            failed = r != null && r.error() != null;
            manual.setVisible(r == null);
            if (r == null) {
                nextTry = System.currentTimeMillis() + 1500;
                usb.set(State.WARN, "Not found yet. Use a data USB cable; if Windows doesn't see it, install the "
                        + "STM32 VCP driver (ImpulseRC Driver Fixer) once.");
                receiver.set(State.PENDING, "");
                stickDelay.set(State.PENDING, "");
                battery.set(State.PENDING, "");
                return;
            }
            showPorts(r.ports());
            portChoice.setVisible(r.ports() != null && r.ports().size() > 1 || chosenPort != null);
            if (r.error() != null) {
                nextTry = System.currentTimeMillis() + 2000;
                usb.set(State.BAD, r.error() + "<br>Trying again by itself every few seconds.");
                return;
            }
            usb.set(State.OK, "<b>" + r.port() + "</b>" + (r.version().isEmpty() ? "" : " - " + BetaflightPlan.shortVersion(r.version()))
                    + (r.backup() == null ? "" : "<br>Its settings before the change: " + r.backup()));
            armRow.set(r.arm() == null ? State.WARN : State.OK, r.arm() == null
                    ? "ARM isn't on a switch in Betaflight's Modes tab: only the throttle-down rule applies before the sticks start."
                    : describe(r.arm()));
            if (r.applied()) {
                applies++;
                nextTry = System.currentTimeMillis() + 4000; // then read again, to check the settings stuck
                receiver.set(State.PENDING, "Saved - Betaflight restarts...");
                stickDelay.set(State.PENDING, "");
                battery.set(State.PENDING, "");
                return;
            }
            if (r.cellTarget() != cells) { // changed while this round ran: write the new count
                applies = 0;
                nextTry = 0;
                return;
            }
            done = true;
            receiver.set(r.msp() ? State.OK : State.BAD, r.msp() ? "Set."
                    : "Could not set it - set it in Betaflight Configurator as described below.");
            if (r.rate() < 0) {
                stickDelay.set(State.WARN, "Not in this Betaflight (it came in 4.2) - update Betaflight for the least stick delay.");
            } else if (r.rate() >= r.target()) {
                stickDelay.set(State.OK, "Set (" + r.rate() + ").");
            } else {
                stickDelay.set(State.BAD, "Could not set it (" + r.rate() + ") - set it in the CLI as described below.");
            }
            if (r.cells() < 0) {
                battery.set(State.WARN, "Not in this Betaflight - it counts the cells itself; the OSD corrects a wrong count.");
            } else if (r.cells() == r.cellTarget()) {
                battery.set(State.OK, "Set (" + r.cells() + "S).");
            } else {
                battery.set(State.BAD, "Could not set it (" + r.cells() + ") - set it in the CLI as described below.");
            }
            manual.setVisible(!r.msp() || (r.rate() >= 0 && r.rate() < r.target()) || (r.cells() >= 0 && r.cells() != r.cellTarget()));
        }

        @Override
        String status() {
            if (done) return "Betaflight is set up.";
            if (!found) return "Waiting for the flight controller (or press Next and use the settings below).";
            if (failed) return "Waiting for the flight controller - close or Disconnect Betaflight Configurator.";
            return "Setting up Betaflight...";
        }

        @Override
        boolean canContinue() {
            return !writing;
        }
    }
}
