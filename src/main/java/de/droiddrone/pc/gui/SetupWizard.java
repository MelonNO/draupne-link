package de.droiddrone.pc.gui;

import de.droiddrone.pc.gui.CheckRow.State;
import de.droiddrone.pc.gui.WizardDialog.Page;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.List;
import java.util.Map;

/**
 * The first-time setup: PC, radio, drone, phone, connection. Whatever this PC can do itself it does (Tailscale,
 * firewall, power plan, Betaflight settings, serving the phone app, finding the phone), and finished steps are
 * skipped, so the pilot reads and clicks as little as possible. It uses the connection method in the settings
 * (Tailscale unless changed in PC setup). Opens by itself on the very first start; SETUP &rarr; First-time setup
 * opens it again.
 */
final class SetupWizard {
    static final String DONE_KEY = "setup.done";
    /** Set on the very first start, when the wizard opens by itself; it never opens by itself again. */
    static final String OFFERED_KEY = "setup.offered";

    private SetupWizard() {
    }

    static void open(MainWindow main) {
        DelayPage delay = new DelayPage();
        List<Page> pages = List.of(
                new WelcomePage(),
                new PcSetupWizard.TailscalePage(main).in("THIS PC"),
                new PcSetupWizard.RelayPage(main).in("THIS PC"),
                new PcSetupWizard.FirewallPage(main).in("THIS PC"),
                new PcSetupWizard.LatencyPage(main).in("THIS PC"),
                new PcSetupWizard.RadioPage(main).in("RADIO"),
                new DroneSetup.BetaflightPage(main).in("DRONE"),
                new PhoneGuideWizard.PhoneTailscalePage(main).in("PHONE"),
                new PhoneGuideWizard.FlightAppPage(main).in("PHONE"),
                new PhoneGuideWizard.PhoneSettingsPage(main).in("PHONE"),
                new PhoneGuideWizard.ConnectPage(main).in("CONNECT"),
                delay.in("CONNECT"),
                new PcSetupWizard.ShortcutsPage(main).in("FINISH"),
                new DonePage().in("FINISH"));
        new WizardDialog(main, "DRAUPNE Link setup", pages, () -> {
            main.save(Map.of(DONE_KEY, "true"));
            main.logLine("Setup finished.");
        }).setVisible(true);
        main.reloadSettings();
        if (delay.testAfterSetup) main.testLatencyNow();
    }

    /**
     * The delay from the drone's camera to this screen: the number to fly by. The test needs DRAUPNE Link's own
     * connection with video, which the guide doesn't run, so it is offered for right after Finish.
     */
    private static final class DelayPage extends Page {
        private final CheckRow test = new CheckRow("Latency test");
        boolean testAfterSetup;

        @Override
        String title() {
            return "Measure the delay";
        }

        @Override
        String hint() {
            return "How long from the drone's camera to this screen. Worth knowing before the first flight.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            test.addPrimaryAction("Test after setup", () -> choose(true));
            test.addAction("Not now", () -> choose(false));
            WizardDialog.addRow(col, test);
            WizardDialog.addRow(col, WizardDialog.paragraph("The screen shows a time code. Point the drone's camera at it, close "
                    + "enough that it fills most of the picture, and hold still for a few seconds: the delay shows on the "
                    + "screen and in the log. Try it in a window and in <b>F11</b> full screen. Stop with <b>Esc</b>. "
                    + "Any time later: <b>SETTINGS > Latency test</b>."));
            test.set(State.PENDING, "<b>Test after setup</b>: when you press Finish, DRAUPNE Link connects to the drone and "
                    + "starts the test (camera on, props off).");
            return col;
        }

        private void choose(boolean now) {
            testAfterSetup = now;
            test.set(now ? State.OK : State.INFO, now ? "It starts when you press Finish." : "Later: SETTINGS > Latency test.");
        }
    }

    private static final class WelcomePage extends Page {
        @Override
        String title() {
            return "Fly your drone over 4G";
        }

        @Override
        String hint() {
            return "Everything is done for you where possible. You only do what needs your hands.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            WizardDialog.addRow(col, WizardDialog.subheading("Have ready"));
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "&bull; Your radio and its USB cable<br>"
                            + "&bull; The drone (Betaflight) and a USB cable for it<br>"
                            + "&bull; An Android phone with mobile data, and a <b>USB OTG adapter</b>"));
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "<b style='color:" + Theme.BAD_HEX + "'>Keep the propellers OFF</b> for the whole setup."));
            return col;
        }
    }

    private static final class DonePage extends Page {
        @Override
        String title() {
            return "Ready";
        }

        @Override
        String hint() {
            return "Press FINISH, then CONNECT in the main window.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "&bull; Bench test first, props off: arm, disarm, and check that losing the link makes Betaflight fail safe<br>"
                            + "&bull; Won't arm? <b>RC CHECK</b> shows why<br>"
                            + "&bull; Set up <b>GPS Rescue</b> in Betaflight before flying out of sight"));
            return col;
        }
    }
}
