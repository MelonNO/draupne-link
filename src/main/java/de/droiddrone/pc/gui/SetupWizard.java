package de.droiddrone.pc.gui;

import de.droiddrone.pc.gui.WizardDialog.Page;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.List;
import java.util.Map;

/**
 * The one setup wizard: PC, radio, flight controller, phone, link test. Whatever this PC can do itself it does
 * (installs Tailscale, firewall rules, power plan, Betaflight settings, serves the phone app, finds the phone);
 * the rest is a guided checklist. Opens by itself on the very first start only; after that SETUP in the main window
 * opens each part (PC, phone, drone) on its own.
 */
final class SetupWizard {
    static final String DONE_KEY = "setup.done";
    /** Set on the very first start, when the wizard opens by itself; it never opens by itself again. */
    static final String OFFERED_KEY = "setup.offered";

    private SetupWizard() {
    }

    static void open(MainWindow main) {
        FlightControllerPages.Found fc = new FlightControllerPages.Found();
        List<Page> pages = List.of(
                new WelcomePage(),
                new PcSetupWizard.ConnectionPage(main).in("THIS PC"),
                new PcSetupWizard.TailscalePage(main).in("THIS PC"),
                new PcSetupWizard.RelayPage(main).in("THIS PC"),
                new PcSetupWizard.FirewallPage(main).in("THIS PC"),
                new PcSetupWizard.LatencyPage(main).in("THIS PC"),
                new PcSetupWizard.RadioPage(main).in("RADIO"),
                new FlightControllerPages.UsbPage(fc).in("FLIGHT CONTROLLER"),
                new FlightControllerPages.ConfigPage(main, fc).in("FLIGHT CONTROLLER"),
                new PhoneGuideWizard.PhoneTailscalePage(main).in("PHONE"),
                new PhoneGuideWizard.FlightAppPage(main).in("PHONE"),
                new PhoneGuideWizard.PhoneSettingsPage(main).in("PHONE"),
                new PhoneGuideWizard.FlightAppSettingsPage(main).in("PHONE"),
                new PhoneGuideWizard.FlightControllerPage(main).in("PHONE"),
                new PhoneGuideWizard.LinkTestPage(main).in("TEST"),
                new PcSetupWizard.ShortcutsPage(main).in("FINISH"),
                new DonePage().in("FINISH"));
        new WizardDialog(main, "DroidDrone PC setup", pages, () -> {
            main.save(Map.of(DONE_KEY, "true"));
            main.logLine("Setup finished.");
        }).setVisible(true);
        main.reloadSettings();
    }

    private static final class WelcomePage extends Page {
        @Override
        String title() {
            return "Fly your drone over 4G";
        }

        @Override
        String hint() {
            return "This wizard sets up everything, one step at a time. It does what it can by itself and tells you exactly what to do for the rest.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            WizardDialog.addRow(col, WizardDialog.subheading("You need"));
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "&bull; This Windows PC, on the internet (cable or Wi-Fi)<br>"
                            + "&bull; Your radio (RadioMaster, EdgeTX) and its USB cable<br>"
                            + "&bull; The drone with a <b>Betaflight</b> flight controller (4.4 or newer is best), and a USB cable for it<br>"
                            + "&bull; An Android phone (8 or newer) with mobile data, and a <b>USB OTG adapter</b> to plug it into the flight controller<br>"
                            + "&bull; About 20 minutes"));
            WizardDialog.addRow(col, WizardDialog.subheading("What happens"));
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "<b>1 This PC</b> - connection method and Tailscale sign-in (firewall, power plan and Tailscale itself are done for you)<br>"
                            + "<b>2 Radio</b> - detect and calibrate your sticks and switches<br>"
                            + "<b>3 Flight controller</b> - Betaflight set up over USB<br>"
                            + "<b>4 Phone</b> - apps installed by QR code, settings, cable to the flight controller<br>"
                            + "<b>5 Test</b> - a real connection to the drone, with the latency measured"));
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
            WizardDialog.addRow(col, WizardDialog.subheading("Before the first flight"));
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "&bull; Bench test first, props off: arm, disarm, and check that pulling the radio's USB, turning off the PC's "
                            + "Wi-Fi or closing the app all make Betaflight fail safe<br>"
                            + "&bull; <b>RC CHECK</b> (next to CALIBRATE) shows exactly what Betaflight sees and why it might not arm<br>"
                            + "&bull; Set up and test <b>GPS Rescue</b> before flying out of sight<br>"
                            + "&bull; First flights close by, in line of sight, in Angle mode<br>"
                            + "&bull; Follow your local drone rules - flying beyond visual line of sight usually needs a permit"));
            WizardDialog.addRow(col, WizardDialog.paragraph("Run any part again any time with <b>SETUP</b> at the top of the main window: PC setup, Phone setup or Drone setup."));
            return col;
        }
    }
}
