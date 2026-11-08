package de.droiddrone.pc.gui;

import de.droiddrone.pc.gui.WizardDialog.Page;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.List;
import java.util.Map;

/**
 * The first-time setup: PC, drone, radio, phone, connection. Whatever this PC can do itself it does (Tailscale,
 * firewall, power plan, Betaflight settings, serving the phone app, finding the phone), and finished steps are
 * skipped, so the pilot reads and clicks as little as possible. It uses the connection method in the settings
 * (Tailscale unless changed in PC setup). Opens by itself on the very first start; SETUP &rarr; First-time setup
 * opens it again. FINISH plays the finale, and the first time round offers the tour of the window.
 */
final class SetupWizard {
    static final String DONE_KEY = "setup.done";
    /** Set on the very first start, when the wizard opens by itself; it never opens by itself again. */
    static final String OFFERED_KEY = "setup.offered";

    private SetupWizard() {
    }

    static void open(MainWindow main) {
        boolean firstTime = !"true".equalsIgnoreCase(main.config().getString(DONE_KEY));
        List<Page> pages = firstTimePages(PcSetupWizard.pages(main), DroneSetup.pages(main), PhoneGuideWizard.pages(main), new WelcomePage());
        boolean[] finished = {false};
        new WizardDialog(main, "DRAUPNE Link setup", pages, () -> {
            // it covered every part: PC, drone and phone setup count as done too
            main.save(Map.of(DONE_KEY, "true", "setup.pcDone", "true", "setup.droneDone", "true", "setup.phoneDone", "true"));
            main.logLine("Setup finished.");
            finished[0] = true;
        }).setVisible(true);
        main.reloadSettings();
        if (finished[0]) SetupFinale.play(main, firstTime ? () -> Tour.offer(main) : null);
    }

    /**
     * The first-time setup: every step of PC, drone and phone setup - their own lists, so none is left out - in the
     * order a new pilot needs them: this PC (its shortcuts with it), the drone, the radio, the phone, the first
     * connection. The radio after the drone: its calibration names each switch after what Drone setup put on it.
     */
    static List<Page> firstTimePages(List<Page> pc, List<Page> drone, List<Page> phone, Page welcome) {
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
        return pages;
    }

    private static final class WelcomePage extends Page {
        @Override
        String title() {
            return "Fly your drone over 5G and mobile";
        }

        @Override
        String hint() {
            return "The PC does what it can. You do the hands-on parts.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            WizardDialog.addRow(col, WizardDialog.subheading("Have ready"));
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "&bull; Radio + USB cable<br>"
                            + "&bull; Drone (Betaflight) + USB data cable<br>"
                            + "&bull; Android phone with mobile data + USB data cable to the drone"));
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "<b style='color:" + Theme.BAD_HEX + "'>Propellers OFF</b> during setup."));
            return col;
        }
    }
}
