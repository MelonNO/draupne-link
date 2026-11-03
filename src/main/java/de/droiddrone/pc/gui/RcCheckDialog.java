package de.droiddrone.pc.gui;

import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.RcSession;
import de.droiddrone.pc.gui.CheckRow.State;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;

/**
 * Side by side: the channels this PC sends, and what Betaflight reports back (MSP_STATUS via the Flight app) -
 * whether RC arrives at all, whether its throttle is low, whether it sees the arm switch, which modes are on and why
 * arming is blocked. Betaflight's raw channel values are not available (the Flight app never asks for them), so
 * these are the checks the FC's own status allows.
 */
final class RcCheckDialog extends JDialog {
    private static final int STALE_MS = 3000;

    private final MainWindow main;
    private final ChannelBars bars = new ChannelBars();
    private final CheckRow output = new CheckRow("RC output from this PC");
    private final CheckRow received = new CheckRow("Sticks reach Betaflight");
    private final CheckRow throttle = new CheckRow("Throttle on the right channel");
    /** Where ARM is: as Drone setup read it from Betaflight, else as chosen there (AUX1 by default). */
    private final de.droiddrone.pc.ArmSwitch armSwitch;
    /** The arm switch's channel in the frame this PC sends (4 = AUX1). */
    private final int armChannel;
    private final CheckRow arm;
    private final CheckRow modes = new CheckRow("Active flight modes");
    private final WrappingText blockers = new WrappingText("", Theme.font(Font.PLAIN, 13.5f), Theme.TEXT);
    private final Timer timer;

    static void open(MainWindow main) {
        new RcCheckDialog(main).setVisible(true);
    }

    private RcCheckDialog(MainWindow main) {
        super(main, "RC check", ModalityType.MODELESS);
        this.main = main;
        PcConfig config = main.config();
        bars.setPurposes(de.droiddrone.pc.setup.ModeSwitch.purposes(config));
        armSwitch = de.droiddrone.pc.ArmSwitch.parse(config.getString(de.droiddrone.pc.ArmSwitch.KEY));
        int chosenAux = de.droiddrone.pc.setup.ModeSwitch.parseArmAux(config.getString(de.droiddrone.pc.setup.ModeSwitch.ARM_KEY));
        armChannel = armSwitch != null ? armSwitch.channel() : 3 + (chosenAux > 0 ? chosenAux : de.droiddrone.pc.setup.ModeSwitch.DEFAULT_ARM_AUX);
        arm = new CheckRow("Arm switch (AUX" + (armChannel - 3) + ")");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(22, 30, 22, 30));
        setContentPane(root);

        JPanel header = WizardDialog.column();
        header.add(Theme.label("RC CHECK", Theme.headingFont(14f), Theme.ACCENT));
        header.add(Box.createVerticalStrut(6));
        header.add(Theme.label("WHAT BETAFLIGHT SEES", Theme.headingFont(28f), Theme.TEXT));
        header.add(Box.createVerticalStrut(4));
        header.add(Theme.label("<html>Props off. Move the sticks and flip the switches: left is what this PC sends, "
                + "right is what the flight controller reports back.</html>", Theme.font(Font.PLAIN, 14f), Theme.MUTED));
        root.add(header, BorderLayout.NORTH);

        JPanel left = Theme.card(new BorderLayout(0, 12));
        left.add(Theme.sectionTitle("Sent by this PC"), BorderLayout.NORTH);
        left.add(bars, BorderLayout.CENTER);
        JPanel leftBottom = WizardDialog.column();
        WizardDialog.addRow(leftBottom, output);
        WizardDialog.addRow(leftBottom, WizardDialog.paragraph("Channels go out as Roll, Pitch, Yaw, Throttle, then the switches: "
                + auxNames(config) + ". The Flight app reorders them to the channel map set in Betaflight's Receiver tab."));
        left.add(leftBottom, BorderLayout.SOUTH);

        JPanel rightColumn = WizardDialog.column();
        WizardDialog.addRow(rightColumn, Theme.sectionTitle("Reported by the flight controller"));
        WizardDialog.addRow(rightColumn, received);
        WizardDialog.addRow(rightColumn, throttle);
        WizardDialog.addRow(rightColumn, arm);
        WizardDialog.addRow(rightColumn, modes);
        WizardDialog.addRow(rightColumn, Theme.sectionTitle("Arming blocked by"));
        WizardDialog.addRow(rightColumn, blockers);
        JScrollPane scroll = new JScrollPane(new WidthTrackingPanel(rightColumn));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        JPanel right = Theme.card(new BorderLayout());
        right.add(scroll, BorderLayout.CENTER);

        JPanel center = new JPanel(new GridLayout(1, 2, 16, 0));
        center.setOpaque(false);
        center.add(left);
        center.add(right);
        root.add(center, BorderLayout.CENTER);

        JButton close = Theme.secondaryButton("Close");
        close.addActionListener(e -> dispose());
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        footer.setOpaque(false);
        footer.add(close);
        root.add(footer, BorderLayout.SOUTH);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        timer = new Timer(150, e -> refresh());
        timer.start();
        refresh();
        setMinimumSize(new Dimension(980, 640));
        setSize(1120, 720);
        setLocationRelativeTo(main);
        WindowReveal.onOpen(this);
    }

    @Override
    public void dispose() {
        timer.stop();
        super.dispose();
    }

    /** "AUX1 = ARM, AUX2 = flight modes, AUX3 = spare, AUX4 = GPS rescue": each AUX after what Drone setup put on it. */
    private static String auxNames(PcConfig config) {
        de.droiddrone.pc.setup.ModeSwitch.Purpose[] purposes = de.droiddrone.pc.setup.ModeSwitch.purposes(config);
        java.util.List<String> names = new java.util.ArrayList<>();
        int last = 3;
        for (int i = 0; i < purposes.length; i++) if (purposes[i] != null) last = Math.max(last, i);
        for (int i = 0; i <= last; i++) {
            names.add("AUX" + (i + 1) + " = " + (purposes[i] == null ? "spare" : purposes[i].title()));
        }
        return String.join(", ", names);
    }

    private void refresh() {
        RcSession session = main.rcSession();
        short[] ch = session == null ? main.previewChannels() : session.getLastChannels();
        bars.setValues(ch, 8);
        ControlLink link = main.activeLink();
        if (link == null) {
            output.set(State.PENDING, "Not connected - press CONNECT in the main window (the phone must be running).");
            for (CheckRow r : new CheckRow[]{received, throttle, arm, modes}) r.set(State.PENDING, "Needs a connection.");
            blockers.setHtml("");
            return;
        }
        boolean live = session != null && session.isRcEnabled();
        output.set(live ? State.OK : State.WARN, live ? "LIVE - " + link.getRcFramesSent() + " frames sent."
                : "OFF - pull the throttle fully down to start sending.");

        FcTelemetry.Status s = link.getTelemetry().getStatus();
        if (s == null || System.currentTimeMillis() - s.receivedAt() > STALE_MS) {
            String why = link.getState() != ControlLink.State.READY ? "Waiting for the link..."
                    : link.getFcInfo() == null ? "The phone isn't talking to the flight controller yet."
                    : "Waiting for status from the flight controller...";
            for (CheckRow r : new CheckRow[]{received, throttle, arm, modes}) r.set(State.PENDING, why);
            blockers.setHtml("");
            return;
        }
        boolean rxLoss = has(s, "RXLOSS");
        received.set(rxLoss ? State.BAD : State.OK, rxLoss
                ? "<b>No.</b> " + explain(s, "RXLOSS")
                : "Yes - Betaflight gets the RC frames.");

        int thr = ch == null ? -1 : ch[PcConfig.THROTTLE_INDEX];
        boolean fcThrottleHigh = has(s, "THROTTLE");
        if (rxLoss) {
            throttle.set(State.PENDING, "Needs RC first.");
        } else if (thr >= 0 && thr <= RcSession.THROTTLE_SAFE_MAX) {
            throttle.set(fcThrottleHigh ? State.BAD : State.OK, fcThrottleHigh
                    ? "<b>Wrong:</b> you send throttle " + thr + " but Betaflight thinks it's up. The channels arrive in the "
                      + "wrong order - check the channel map in Betaflight's Receiver tab (AETR1234 is standard) and run CALIBRATE."
                    : "Throttle down here and down on the FC.");
        } else {
            throttle.set(fcThrottleHigh ? State.OK : State.INFO, fcThrottleHigh
                    ? "Throttle up here and up on the FC. Pull it down to check the low end."
                    : "Pull the throttle stick fully down to check.");
        }

        String auxName = "AUX" + (armChannel - 3);
        int aux = ch != null && ch.length > armChannel ? ch[armChannel] : -1;
        // in Betaflight's ARM range as Drone setup read it; without that, the usual "up" (1700 and over)
        boolean on = aux >= 0 && (armSwitch != null ? armSwitch.on(ch) : aux >= 1700);
        if (s.armed()) {
            arm.set(State.WARN, "<b>ARMED</b> - Betaflight follows your arm switch. Motors can spin!");
        } else if (rxLoss) {
            arm.set(State.PENDING, "Needs RC first.");
        } else if (on) {
            boolean seen = has(s, "ARMSWITCH");
            arm.set(seen ? State.OK : State.WARN, seen
                    ? "Betaflight sees the arm switch ON, but won't arm yet - see the reasons below."
                    : auxName + " is " + aux + " but Betaflight doesn't react. In the Modes tab, ARM must be on " + auxName
                    + " with a range that includes " + aux + ".");
        } else if (aux >= 0) {
            arm.set(State.INFO, auxName + " is " + aux + " (off). Flip your arm switch to test - props off!");
        } else {
            arm.set(State.WARN, auxName + " isn't mapped - run CALIBRATE and flip your arm switch at the " + auxName + " step.");
        }

        modes.set(State.INFO, s.activeModes().isEmpty()
                ? "None. Flip a mode switch - the mode set for it in Betaflight's Modes tab should appear here."
                : "<b>" + String.join(", ", s.activeModes()) + "</b> - flip your switches and watch these change.");

        if (s.blockers().isEmpty()) {
            blockers.setHtml(s.armed() ? "Armed." : "Nothing - the drone will arm when you flip the arm switch.");
        } else {
            StringBuilder html = new StringBuilder();
            for (FcTelemetry.Blocker b : s.blockers()) {
                html.append("<p style='margin-top:6px'><b style='color:" + Theme.BAD_HEX + "'>").append(b.osdName()).append("</b> &nbsp;")
                        .append(b.explanation()).append("</p>");
            }
            blockers.setHtml(html.toString());
        }
    }

    private static boolean has(FcTelemetry.Status s, String osdName) {
        return s.blockers().stream().anyMatch(b -> b.osdName().equals(osdName));
    }

    private static String explain(FcTelemetry.Status s, String osdName) {
        return s.blockers().stream().filter(b -> b.osdName().equals(osdName)).map(FcTelemetry.Blocker::explanation).findFirst().orElse("");
    }
}
