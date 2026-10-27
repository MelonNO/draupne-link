package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.gui.CheckRow.State;
import de.droiddrone.pc.gui.WizardDialog.Page;
import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.pc.setup.BetaflightUsb;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Font;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Setup wizard steps for Betaflight. With the flight controller on this PC's USB the wizard reads and fixes the
 * settings itself (receiver mode, arm switch, accelerometer) and shows Betaflight's arming check live; without it,
 * the same steps become a manual checklist for Betaflight Configurator.
 */
final class FlightControllerPages {
    /** Shared between the two pages: the flight controller found on USB (or none). */
    static final class Found {
        volatile BetaflightUsb.Port port;
    }

    /** Arming blocks that are expected on the bench and mean nothing is wrong. */
    private static final Set<String> EXPECTED_ON_BENCH = Set.of("RXLOSS", "FAILSAFE", "BADRX", "BOOTGRACE");

    private FlightControllerPages() {
    }

    /** Plug the FC into this PC. */
    static final class UsbPage extends Page {
        private final Found found;
        private final CheckRow fc = new CheckRow("Flight controller on USB");
        private boolean scanning;
        private long nextScan;

        UsbPage(Found found) {
            this.found = found;
        }

        @Override
        String title() {
            return "Plug in the flight controller";
        }

        @Override
        String hint() {
            return "<b>Props off.</b> Connect the flight controller's USB to this PC - no battery needed. "
                    + "Close Betaflight Configurator if it is open: the wizard sets Betaflight up itself.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            WizardDialog.addRow(col, fc);
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "Not possible right now (no cable, or the flight controller is already built in)? Press <b>Next</b> anyway: "
                            + "the next step then shows the few settings to make in Betaflight Configurator yourself."));
            fc.set(State.PENDING, "Looking for it...");
            return col;
        }

        @Override
        void refresh() {
            if (scanning || System.currentTimeMillis() < nextScan) return;
            scanning = true;
            background(BetaflightUsb::findFlightControllers, ports -> {
                scanning = false;
                nextScan = System.currentTimeMillis() + 1500;
                found.port = ports == null || ports.isEmpty() ? null : ports.get(0);
                if (found.port == null) {
                    fc.set(State.WARN, "Not found yet. Use a data USB cable; if Windows doesn't see it, install the "
                            + "STM32 VCP driver (ImpulseRC Driver Fixer) once.");
                } else {
                    fc.set(State.OK, "<b>" + found.port + "</b>");
                }
            });
        }

        @Override
        String status() {
            return found.port != null ? "Flight controller found." : "Waiting for the flight controller (or press Next to set it up by hand).";
        }
    }

    /** Read Betaflight's settings, fix what DroidDrone needs, calibrate, and show the arming check. */
    static final class ConfigPage extends Page {
        private final MainWindow main;
        private final Found found;
        private final JPanel auto = WizardDialog.column();
        private final JPanel manual = WizardDialog.column();
        private final CheckRow version = new CheckRow("Betaflight");
        private final CheckRow receiver = new CheckRow("Receiver mode: MSP RX input");
        private final CheckRow arm = new CheckRow("Arm switch");
        private final CheckRow accel = new CheckRow("Accelerometer level");
        private final CheckRow check = new CheckRow("Arming check");
        private JButton applyButton;
        private JButton calibrateButton;
        private boolean busy;
        private List<BetaflightPlan.Change> plan = List.of();

        ConfigPage(MainWindow main, Found found) {
            this.main = main;
            this.found = found;
        }

        @Override
        String title() {
            return "Set up Betaflight";
        }

        @Override
        String hint() {
            return found.port != null
                    ? "The wizard reads Betaflight's settings and fixes what DroidDrone needs. Nothing else is changed."
                    : "No flight controller on USB - make these settings in Betaflight Configurator, then tick them off.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            WizardDialog.addRow(auto, version);
            applyButton = receiver.addPrimaryAction("Apply", this::apply);
            applyButton.setVisible(false);
            WizardDialog.addRow(auto, receiver);
            WizardDialog.addRow(auto, arm);
            calibrateButton = accel.addPrimaryAction("Calibrate", this::calibrate);
            WizardDialog.addRow(auto, accel);
            check.addAction("Check again", this::readStatus);
            WizardDialog.addRow(auto, check);
            for (CheckRow r : new CheckRow[]{version, receiver, arm, check}) r.set(State.PENDING, "Reading...");
            accel.set(State.INFO, "Put the drone on a <b>level</b> surface, then press Calibrate. This fixes the ANGLE arming block.");

            WizardDialog.addRow(manual, savedCheck("fc.receiver",
                    "<b>Receiver</b> tab &rarr; Receiver Mode <b>MSP RX input</b> &rarr; Save"));
            WizardDialog.addRow(manual, savedCheck("fc.arm",
                    "<b>Modes</b> tab &rarr; ARM on <b>AUX 1</b>, range over the top (1700-2100) &rarr; Save"));
            WizardDialog.addRow(manual, savedCheck("fc.accel",
                    "<b>Setup</b> tab &rarr; drone level &rarr; <b>Calibrate Accelerometer</b>"));
            WizardDialog.addRow(manual, savedCheck("fc.disconnect",
                    "Press <b>Disconnect</b> in Configurator before unplugging - otherwise Betaflight keeps an \"MSP\" arming block until it restarts"));

            WizardDialog.addRow(col, auto);
            WizardDialog.addRow(col, manual);
            WizardDialog.addRow(col, WizardDialog.subheading("Failsafe"));
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "If the 4G link drops, Betaflight's failsafe takes over. For bench tests, Stage 2 <b>Drop</b> is fine. "
                            + "Before flying beyond line of sight set up and test <b>GPS Rescue</b> (Failsafe tab) - the wizard leaves this to you on purpose."));
            return col;
        }

        private JComponent savedCheck(String key, String html) {
            javax.swing.JCheckBox box = new javax.swing.JCheckBox("<html>" + html + "</html>");
            box.setFont(Theme.font(Font.PLAIN, 14f));
            box.setForeground(Theme.TEXT);
            box.setOpaque(false);
            box.setSelected("true".equals(main.config().getString(key)));
            box.addActionListener(e -> main.save(Map.of(key, Boolean.toString(box.isSelected()))));
            return box;
        }

        @Override
        void onShow() {
            boolean usb = found.port != null;
            auto.setVisible(usb);
            manual.setVisible(!usb);
            if (usb && !busy) readSettings();
        }

        private void readSettings() {
            BetaflightUsb.Port port = found.port;
            busy = true;
            background(() -> {
                try (BetaflightUsb fc = BetaflightUsb.open(port)) {
                    fc.enterCli();
                    String ver = BetaflightPlan.version(fc.command("version"));
                    String features = fc.command("feature");
                    String aux = fc.command("aux");
                    fc.exitCli(); // Betaflight restarts (nothing changed) - which also clears a stale "MSP" arming block
                    return new String[]{ver, features, aux, null};
                } catch (IOException e) {
                    return new String[]{null, null, null, e.getMessage()};
                }
            }, r -> {
                busy = false;
                if (r[3] != null) {
                    version.set(State.BAD, r[3]);
                    return;
                }
                version.set(r[0].isEmpty() ? State.WARN : State.OK, r[0].isEmpty() ? "Version not recognised - carrying on." : r[0]);
                plan = BetaflightPlan.plan(r[1], r[2]);
                boolean msp = BetaflightPlan.isMspReceiver(r[1]);
                boolean armSet = BetaflightPlan.hasArmSwitch(r[2]);
                receiver.set(msp ? State.OK : State.WARN, msp ? "Set." : "Not set - press Apply.");
                arm.set(armSet ? State.OK : State.WARN, armSet ? "An ARM switch is configured (left as it is)."
                        : "None yet - Apply puts ARM on AUX1 (your arm switch from the radio calibration).");
                applyButton.setVisible(!plan.isEmpty());
                // after Betaflight's restart, read the arming check
                SwingUtilities.invokeLater(() -> background(() -> sleep(3000), x -> readStatus()));
            });
        }

        private void apply() {
            BetaflightUsb.Port port = found.port;
            List<BetaflightPlan.Change> changes = plan;
            busy = true;
            applyButton.setEnabled(false);
            receiver.set(State.PENDING, "Applying and saving - Betaflight restarts...");
            background(() -> {
                try (BetaflightUsb fc = BetaflightUsb.open(port)) {
                    fc.enterCli();
                    StringBuilder log = new StringBuilder();
                    for (BetaflightPlan.Change c : changes) {
                        for (String cmd : c.commands()) log.append(cmd).append(": ").append(fc.command(cmd)).append('\n');
                    }
                    fc.saveAndReboot();
                    main.logLine("Betaflight: " + changes.stream().map(BetaflightPlan.Change::description).collect(Collectors.joining("; ")));
                    return null;
                } catch (IOException e) {
                    return e.getMessage();
                }
            }, error -> {
                busy = false;
                applyButton.setEnabled(true);
                if (error != null) {
                    receiver.set(State.BAD, "Could not apply: " + error);
                } else {
                    receiver.set(State.PENDING, "Saved. Waiting for Betaflight to restart...");
                    background(() -> sleep(4000), x -> readSettings());
                }
            });
        }

        private void calibrate() {
            BetaflightUsb.Port port = found.port;
            calibrateButton.setEnabled(false);
            accel.set(State.PENDING, "Calibrating - keep the drone still and level...");
            background(() -> {
                try (BetaflightUsb fc = BetaflightUsb.open(port)) {
                    fc.msp(BetaflightUsb.MSP_ACC_CALIBRATION, null);
                    sleep(2500);
                    return null;
                } catch (IOException e) {
                    return e.getMessage();
                }
            }, error -> {
                calibrateButton.setEnabled(true);
                if (error != null) {
                    accel.set(State.BAD, "Could not calibrate: " + error);
                } else {
                    accel.set(State.OK, "Calibrated. If ANGLE still appears below, check that the drone really is level.");
                    main.save(Map.of("fc.accel", "true"));
                    readStatus();
                }
            });
        }

        private void readStatus() {
            BetaflightUsb.Port port = found.port;
            if (port == null) return;
            check.set(State.PENDING, "Asking Betaflight...");
            background(() -> {
                try (BetaflightUsb fc = BetaflightUsb.open(port)) {
                    return FcTelemetry.blockersFor(BetaflightUsb.armingFlags(fc.msp(BetaflightUsb.MSP_STATUS, null)));
                } catch (IOException e) {
                    return null;
                }
            }, blockers -> {
                if (blockers == null) {
                    check.set(State.WARN, "Could not read the arming check (Betaflight may still be restarting) - press Check again.");
                    return;
                }
                List<FcTelemetry.Blocker> real = blockers.stream().filter(b -> !EXPECTED_ON_BENCH.contains(b.osdName())).toList();
                if (real.isEmpty()) {
                    check.set(State.OK, "Nothing blocks arming except the missing RC link, which the phone provides later.");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (FcTelemetry.Blocker b : real) {
                        sb.append("<b style='color:").append(Theme.BAD_HEX).append("'>").append(b.osdName()).append("</b> ")
                                .append(b.explanation()).append("<br>");
                    }
                    check.set(State.BAD, sb.toString());
                }
            });
        }

        @Override
        String status() {
            if (found.port == null) return "Tick the steps off in Betaflight Configurator.";
            return busy ? "Working..." : null;
        }

        @Override
        boolean canContinue() {
            return !busy;
        }

        private static Object sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ignored) {
            }
            return null;
        }
    }

}
