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
        List<Page> pages = firstTimePages(PcSetupWizard.pages(main), DroneSetup.pages(main), PhoneGuideWizard.pages(main),
                new WelcomePage(), delay, new DonePage());
        new WizardDialog(main, "DRAUPNE Link setup", pages, () -> {
            // it covered every part: PC, drone and phone setup count as done too
            main.save(Map.of(DONE_KEY, "true", "setup.pcDone", "true", "setup.droneDone", "true", "setup.phoneDone", "true"));
            main.logLine("Setup finished.");
        }).setVisible(true);
        main.reloadSettings();
        if (delay.testAfterSetup) main.testLatencyNow();
    }

    /**
     * The first-time setup: every step of PC, drone and phone setup - their own lists, so none is left out - in the
     * order a new pilot needs them: this PC (its shortcuts with it), the drone, the radio, the phone, the first
     * connection. The radio after the drone: its calibration names each switch after what Drone setup put on it.
     * Only the separate setups' closing pages are left for this one's own.
     */
    static List<Page> firstTimePages(List<Page> pc, List<Page> drone, List<Page> phone, Page welcome, Page delay, Page done) {
        List<Page> pages = new java.util.ArrayList<>();
        List<Page> radio = new java.util.ArrayList<>();
        pages.add(welcome);
        for (Page p : pc) { // this PC's steps together; the radio after the drone
            if (p instanceof PcSetupWizard.RadioPage) radio.add(p.in("RADIO"));
            else pages.add(p.in("THIS PC"));
        }
        for (Page p : drone) pages.add(p.in("DRONE"));
        pages.addAll(radio);
        for (Page p : phone) pages.add(p.in(p instanceof PhoneGuideWizard.ConnectPage ? "CONNECT" : "PHONE"));
        pages.add(delay.in("CONNECT"));
        pages.add(done.in("FINISH"));
        return pages;
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
