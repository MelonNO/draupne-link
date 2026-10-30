package de.droiddrone.pc.gui;

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
 * Drone setup: the Betaflight settings DroidDrone needs - receiver mode MSP, serial_update_rate_hz and the battery's
 * cell count (6S unless the pilot changes it) - set over USB with nothing else to click. Everything else on the drone
 * (arm switch, accelerometer, failsafe) stays the pilot's own. The pilot also says whether the motors and props are
 * already checked, or checks them there and then with the motor test.
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

        private record Read(String port, String version, boolean msp, int rate, int target, int cells, int cellTarget,
                            boolean applied, String error) {}
        static final String CELLS_KEY = "drone.cellCount";
        static final String MOTORS_CHECKED_KEY = "setup.motorsChecked";

        private final MainWindow main;
        private final CheckRow usb = new CheckRow("Flight controller on USB");
        private final CheckRow receiver = new CheckRow("Receiver mode: MSP");
        private final CheckRow stickDelay = new CheckRow("Stick delay: serial_update_rate_hz");
        private final CheckRow battery = new CheckRow("Battery cell count: force_battery_cell_count");
        private final CheckRow motors = new CheckRow("Motors and props");
        /** The pilot has said: already checked, or checked them with the motor test. Needed to go on. */
        private boolean motorsChosen;
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
                    + "The settings DroidDrone needs are made by themselves. Then say whether the motors and props are checked.";
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
            WizardDialog.addRow(col, usb);
            WizardDialog.addRow(col, receiver);
            WizardDialog.addRow(col, stickDelay);
            WizardDialog.addRow(col, battery);
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
            motorsChosen = "true".equalsIgnoreCase(main.config().getString(MOTORS_CHECKED_KEY));
            motors.set(motorsChosen ? State.OK : State.PENDING, motorsChosen
                    ? "Checked before. <b>Check now</b> runs the motor test again."
                    : "Is each motor in the right place and turning the right way, and are the props on the right way round? "
                    + "<b>Already checked</b> if so; <b>Check now</b> opens the motor test (props off).");
            return col;
        }

        private void motorsAlreadyChecked() {
            motorsChosen = true;
            main.save(Map.of(MOTORS_CHECKED_KEY, "true"));
            motors.set(State.OK, "Marked as checked. The motor test is in SETTINGS any time.");
        }

        private void checkMotorsNow() {
            if (main.isLinkActive()) {
                motors.set(State.WARN, "Disconnect from the drone first: the motor test runs over this PC's USB, with the drone on the bench.");
                return;
            }
            motorTestOpen = true; // the page's own rounds would fight the motor test for the USB port
            try {
                MotorTestDialog.open(javax.swing.SwingUtilities.getWindowAncestor(motors), main);
            } finally {
                motorTestOpen = false;
                nextTry = 0;
            }
            motorsChosen = true;
            main.save(Map.of(MOTORS_CHECKED_KEY, "true"));
            motors.set(State.OK, "Checked with the motor test. <b>Check now</b> runs it again.");
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
                    + "<b>Receiver</b> tab &rarr; Receiver Mode <b>MSP RX input</b> &rarr; Save. "
                    + "<b>CLI</b> tab &rarr; <b>set serial_update_rate_hz = 2000</b> (1000 on F411 boards), "
                    + "<b>set force_battery_cell_count = " + cells + "</b> &rarr; <b>save</b>.");
        }

        @Override
        void refresh() {
            if (motorTestOpen || busy || done || System.currentTimeMillis() < nextTry) return;
            busy = true;
            boolean mayApply = applies < MAX_APPLIES;
            int cellTarget = cells;
            background(() -> work(mayApply, cellTarget), this::show);
        }

        /** One round: find the FC, read the settings, and change them in the same CLI session if needed. */
        private Read work(boolean mayApply, int cellTarget) {
            List<BetaflightUsb.Port> ports = BetaflightUsb.findFlightControllers();
            if (ports.isEmpty()) return null;
            BetaflightUsb.Port port = ports.get(0);
            try (BetaflightUsb fc = BetaflightUsb.open(port)) {
                fc.enterCli();
                String version = BetaflightPlan.version(fc.command("version"));
                String features = fc.command("feature");
                String rate = fc.command("get serial_update_rate_hz");
                String forced = fc.command("get force_battery_cell_count");
                List<BetaflightPlan.Change> plan = BetaflightPlan.plan(features, rate, version, forced, cellTarget);
                boolean apply = mayApply && !plan.isEmpty();
                if (apply) {
                    writing = true;
                    for (BetaflightPlan.Change c : plan) {
                        for (String cmd : c.commands()) fc.command(cmd);
                    }
                    fc.saveAndReboot();
                    main.logLine("Betaflight: " + plan.stream().map(BetaflightPlan.Change::description).collect(Collectors.joining("; ")));
                } else {
                    fc.exitCli(); // Betaflight restarts (nothing changed) - which also clears a stale "MSP" arming block
                }
                return new Read(port.systemName(), version, BetaflightPlan.isMspReceiver(features), BetaflightPlan.serialRate(rate),
                        BetaflightPlan.targetSerialRate(version), BetaflightPlan.forcedCells(forced), cellTarget, apply, null);
            } catch (IOException e) {
                return new Read(port.systemName(), "", false, -1, 0, -1, cellTarget, false, e.getMessage());
            }
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
            if (r.error() != null) {
                nextTry = System.currentTimeMillis() + 2000;
                usb.set(State.BAD, r.error() + "<br>Trying again by itself every few seconds.");
                return;
            }
            usb.set(State.OK, "<b>" + r.port() + "</b>" + (r.version().isEmpty() ? "" : " - " + BetaflightPlan.shortVersion(r.version())));
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
            if (done && !motorsChosen) return "Betaflight is set up. Now the motors and props: already checked, or check them now.";
            if (!motorsChosen && !found) return "Waiting for the flight controller. Then say whether the motors and props are checked.";
            if (done) return "Betaflight is set up.";
            if (!found) return "Waiting for the flight controller (or press Next and use the settings below).";
            if (failed) return "Waiting for the flight controller - close or Disconnect Betaflight Configurator.";
            return "Setting up Betaflight...";
        }

        @Override
        boolean canContinue() {
            return !writing && motorsChosen;
        }
    }
}
