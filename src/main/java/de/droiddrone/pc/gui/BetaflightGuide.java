package de.droiddrone.pc.gui;

import de.droiddrone.pc.gui.WizardDialog.Page;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Map;

/**
 * Step-by-step guide through Betaflight Configurator, tab by tab, for everything DroidDrone needs on the flight
 * controller - including the latency setting the setup wizard does not make. Each step has a tick box saved in the
 * settings; the receiver, arm, accelerometer and disconnect boxes are the same ones as in the setup wizard's checklist.
 */
final class BetaflightGuide {
    static final String CONFIGURATOR_URL = "https://app.betaflight.com";
    static final String SERIAL_RATE_COMMANDS = "set serial_update_rate_hz = 2000\nsave";

    private BetaflightGuide() {
    }

    /** @param owner the window to open over (the main window, or the setup wizard) */
    static void open(MainWindow main, Window owner) {
        List<Page> pages = List.of(
                new Step(main, "Connect Betaflight Configurator", "Props off. This guide goes through the Betaflight settings DroidDrone needs, tab by tab.", null,
                        "Plug the flight controller's USB into this PC. No battery is needed.",
                        "Open <b>Betaflight Configurator</b>: the installed app, or <b>app.betaflight.com</b> in Chrome or Edge (button below).",
                        "Pick the flight controller's <b>COM port</b> at the top right and press <b>Connect</b>.",
                        "Check the version at the top left: DroidDrone needs <b>Betaflight 4.4 or newer</b>.")
                        .link("Open app.betaflight.com", CONFIGURATOR_URL)
                        .note("Prefer it done for you? SETUP &rarr; <i>Set up Betaflight</i> sets the receiver, the arm switch and the "
                                + "accelerometer over USB by itself (close Configurator first). The latency step in this guide is still yours to do.")
                        .in("START"),
                new Step(main, "Receiver: MSP", "The phone sends your sticks to Betaflight as MSP messages over USB.", "fc.receiver",
                        "Open the <b>Receiver</b> tab.",
                        "Set <b>Receiver Mode</b> to <b>MSP RX input</b>.",
                        "Leave <b>Channel Map</b> as it is (e.g. AETR1234): the phone app puts the channels in Betaflight's order itself.",
                        "Press <b>Save</b> (bottom right). Betaflight restarts; press Connect again.")
                        .note("The channel bars on this tab only move once the phone is connected and sending. Check them later with <b>RC CHECK</b>.")
                        .done("The receiver is set to MSP RX input")
                        .in("RECEIVER"),
                new Step(main, "Modes: arm switch", "Betaflight arms only from a switch, never from a stick.", "fc.arm",
                        "Open the <b>Modes</b> tab.",
                        "Under <b>ARM</b>, press <b>Add Range</b> and choose <b>AUX 1</b>: the switch you chose as aux1 when you calibrated the radio in DroidDrone PC.",
                        "Drag the range to the top, <b>1700-2100</b>, so the switch's up position arms.",
                        "Optional: put <b>ANGLE</b> (self-levelling) on AUX 2 the same way while you are learning.",
                        "Press <b>Save</b>.")
                        .note("No arm switch? Pick one on the radio, then CALIBRATE in DroidDrone PC so it is mapped to aux1.")
                        .done("ARM is on AUX 1")
                        .in("MODES"),
                new Step(main, "CLI: lower the stick delay", "One Betaflight setting decides whether your sticks queue up.", "fc.serialRate",
                        "Open the <b>CLI</b> tab.",
                        "Press <b>Copy</b> below, click into the CLI's command line, paste (Ctrl+V) and press <b>Enter</b>.",
                        "Betaflight saves and restarts. Press <b>Connect</b> again.")
                        .code(SERIAL_RATE_COMMANDS)
                        .note("<b>Why:</b> Betaflight reads only <b>one</b> MSP message per serial cycle, and that cycle runs just "
                                + "<b>100 times a second</b> by default. DroidDrone sends 250 stick updates a second, and the phone asks for OSD "
                                + "data too, so stick updates wait in a queue in the flight controller. At 2000 the queue is gone.<br>"
                                + "Needs Betaflight 4.3 or newer. Afterwards, type <b>status</b> in the CLI: if the CPU load jumped a lot "
                                + "on an older F411 board, use 1000 instead of 2000.")
                        .done("serial_update_rate_hz is set to 2000")
                        .in("LATENCY"),
                new Step(main, "Setup: level the accelerometer", "Betaflight refuses to arm (ANGLE) when it thinks the drone is tilted.", "fc.accel",
                        "Put the drone on a <b>flat, level</b> surface.",
                        "Open the <b>Setup</b> tab and press <b>Calibrate Accelerometer</b>.",
                        "Keep the drone still for a few seconds until it finishes. The 3D model should now lie flat.")
                        .done("The accelerometer is calibrated")
                        .in("SENSORS"),
                new Step(main, "Failsafe: when the 4G link drops", "If the phone stops sending sticks, Betaflight's failsafe takes over.", "fc.failsafe",
                        "Open the <b>Failsafe</b> tab.",
                        "For bench tests and flying in sight, <b>Stage 2 procedure: Drop</b> is fine (the motors stop).",
                        "Before flying beyond line of sight, set up <b>GPS Rescue</b> here (needs a GPS with a fix) and test it in the field "
                                + "by turning the link off on purpose.",
                        "Press <b>Save</b>.")
                        .note("DroidDrone PC stops sending sticks if the radio is unplugged, so Betaflight fails safe then too.")
                        .done("A failsafe procedure is chosen and saved")
                        .in("FAILSAFE"),
                new Step(main, "Disconnect and test", "One last click avoids an arming block that is confusing to track down.", "fc.disconnect",
                        "Press <b>Disconnect</b> (top right) <b>before</b> you unplug USB. Otherwise Betaflight keeps an \"MSP\" arming "
                                + "block until it restarts. If you forgot, just power the flight controller off and on.",
                        "Unplug USB and plug the phone in with the OTG adapter.",
                        "In DroidDrone PC: <b>CONNECT</b>, then <b>RC CHECK</b>. The sticks should move, the arm switch should show, "
                                + "and nothing should be listed as blocking arming.")
                        .done("Disconnected before unplugging")
                        .in("FINISH"));
        new WizardDialog(owner, "Betaflight guide", pages, () -> main.logLine("Betaflight guide finished.")).setVisible(true);
    }

    /** One tab of Configurator: numbered instructions, optional copy-able commands, a note, and a tick box. */
    private static final class Step extends Page {
        private final MainWindow main;
        private final String title;
        private final String hint;
        private final String key;
        private final String[] instructions;
        private String linkText;
        private String linkUrl;
        private String code;
        private String note;
        private String doneText;
        private JCheckBox doneBox;

        Step(MainWindow main, String title, String hint, String key, String... instructions) {
            this.main = main;
            this.title = title;
            this.hint = hint;
            this.key = key;
            this.instructions = instructions;
        }

        Step link(String text, String url) {
            linkText = text;
            linkUrl = url;
            return this;
        }

        Step code(String commands) {
            code = commands;
            return this;
        }

        Step note(String html) {
            note = html;
            return this;
        }

        Step done(String text) {
            doneText = text;
            return this;
        }

        @Override
        String title() {
            return title;
        }

        @Override
        String hint() {
            return hint;
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            StringBuilder steps = new StringBuilder();
            for (int i = 0; i < instructions.length; i++) {
                steps.append("<b style='color:").append(Theme.ACCENT_HEX).append("'>").append(i + 1).append(".</b>&nbsp; ")
                        .append(instructions[i]).append(i < instructions.length - 1 ? "<br><br>" : "");
            }
            WizardDialog.addRow(col, WizardDialog.paragraph(steps.toString()));
            if (code != null) WizardDialog.addRow(col, codeBlock(code));
            if (linkUrl != null) {
                JButton open = Theme.secondaryButton(linkText);
                open.addActionListener(e -> PcSetupWizard.openInBrowser(main, linkUrl));
                JPanel row = new JPanel(new BorderLayout());
                row.setOpaque(false);
                row.add(open, BorderLayout.WEST);
                WizardDialog.addRow(col, row);
            }
            if (note != null) {
                WrappingText text = new WrappingText(note, Theme.font(Font.PLAIN, 13.5f), Theme.MUTED);
                WizardDialog.addRow(col, text);
            }
            if (key != null && doneText != null) {
                doneBox = new JCheckBox(doneText);
                doneBox.setFont(Theme.font(Font.BOLD, 14.5f));
                doneBox.setForeground(Theme.TEXT);
                doneBox.setOpaque(false);
                doneBox.setSelected("true".equals(main.config().getString(key)));
                doneBox.addActionListener(e -> main.save(Map.of(key, Boolean.toString(doneBox.isSelected()))));
                WizardDialog.addRow(col, doneBox);
            }
            return col;
        }

        /** The commands in a monospace box, with a button that copies them for pasting into the CLI. */
        private static JComponent codeBlock(String commands) {
            JTextArea area = new JTextArea(commands);
            area.setEditable(false);
            area.setFont(Theme.monoFont(Font.BOLD, 15f));
            area.setForeground(Theme.OK);
            area.setBackground(Theme.PANEL_2);
            area.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
            JButton copy = Theme.primaryButton("Copy");
            copy.addActionListener(e -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(commands), null);
                copy.setText("COPIED");
            });
            JPanel box = new JPanel(new BorderLayout(12, 0));
            box.setOpaque(false);
            box.add(area, BorderLayout.CENTER);
            JPanel right = new JPanel(new BorderLayout());
            right.setOpaque(false);
            right.add(copy, BorderLayout.NORTH);
            box.add(right, BorderLayout.EAST);
            return box;
        }

        @Override
        String status() {
            if (doneBox == null) return null;
            return doneBox.isSelected() ? "Done." : "Tick the box when it is done.";
        }
    }
}
