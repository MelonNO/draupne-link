package de.droiddrone.pc.gui;

import de.droiddrone.pc.gui.CheckRow.State;
import de.droiddrone.pc.gui.WizardDialog.Page;
import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.pc.setup.BetaflightUsb;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Drone setup: the two Betaflight settings DroidDrone needs - receiver mode MSP and serial_update_rate_hz - set over
 * USB with nothing to click. Everything else on the drone (arm switch, accelerometer, failsafe) stays the pilot's own.
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

    /** Finds the flight controller on USB, reads the two settings, fixes them, and checks they stuck. */
    static final class BetaflightPage extends Page {
        /** Automatic attempts at changing the settings before the page says it couldn't. */
        private static final int MAX_APPLIES = 2;

        private record Read(String port, String version, boolean msp, int rate, int target, boolean applied, String error) {}

        private final MainWindow main;
        private final CheckRow usb = new CheckRow("Flight controller on USB");
        private final CheckRow receiver = new CheckRow("Receiver mode: MSP");
        private final CheckRow stickDelay = new CheckRow("Stick delay: serial_update_rate_hz");
        private JComponent manual;
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
            return "<b>Props off.</b> Plug the flight controller into this PC with USB. The two settings DroidDrone needs are made by themselves.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            WizardDialog.addRow(col, usb);
            WizardDialog.addRow(col, receiver);
            WizardDialog.addRow(col, stickDelay);
            manual = WizardDialog.paragraph("No USB cable? Make the same two settings in Betaflight Configurator: "
                    + "<b>Receiver</b> tab &rarr; Receiver Mode <b>MSP RX input</b> &rarr; Save. "
                    + "<b>CLI</b> tab &rarr; <b>set serial_update_rate_hz = 2000</b> (1000 on F411 boards) &rarr; <b>save</b>.");
            WizardDialog.addRow(col, manual);
            usb.set(State.PENDING, "Looking for it...");
            receiver.set(State.PENDING, "");
            stickDelay.set(State.PENDING, "");
            return col;
        }

        @Override
        void refresh() {
            if (busy || done || System.currentTimeMillis() < nextTry) return;
            busy = true;
            boolean mayApply = applies < MAX_APPLIES;
            background(() -> work(mayApply), this::show);
        }

        /** One round: find the FC, read both settings, and change them in the same CLI session if needed. */
        private Read work(boolean mayApply) {
            List<BetaflightUsb.Port> ports = BetaflightUsb.findFlightControllers();
            if (ports.isEmpty()) return null;
            BetaflightUsb.Port port = ports.get(0);
            try (BetaflightUsb fc = BetaflightUsb.open(port)) {
                fc.enterCli();
                String version = BetaflightPlan.version(fc.command("version"));
                String features = fc.command("feature");
                String rate = fc.command("get serial_update_rate_hz");
                List<BetaflightPlan.Change> plan = BetaflightPlan.plan(features, rate, version);
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
                        BetaflightPlan.targetSerialRate(version), apply, null);
            } catch (IOException e) {
                return new Read(port.systemName(), "", false, -1, 0, false, e.getMessage());
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
            manual.setVisible(!r.msp() || (r.rate() >= 0 && r.rate() < r.target()));
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
