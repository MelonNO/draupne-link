package de.droiddrone.pc.gui;

import de.droiddrone.pc.AppPaths;
import de.droiddrone.pc.JoystickService;
import de.droiddrone.pc.setup.ApkShare;
import de.droiddrone.pc.setup.NetInfo;
import de.droiddrone.pc.setup.PcPrep;
import de.droiddrone.pc.setup.Tailscale;
import de.droiddrone.pc.setup.WindowsSetup;
import de.droiddrone.pc.gui.CheckRow.State;
import de.droiddrone.pc.gui.WizardDialog.Page;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Font;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Everything the Windows side needs, one step at a time; steps that are already done are skipped:
 * Tailscale, firewall, radio + calibration, power plan, shortcuts.
 */
final class PcSetupWizard {
    private PcSetupWizard() {
    }

    /** PC setup's steps. The first-time setup is made of these too (SetupWizard): a step added here is in both. */
    static List<Page> pages(MainWindow main) {
        return List.of(
                new TailscalePage(main),
                new FirewallPage(main),
                new RadioPage(main),
                new LatencyPage(main),
                new ShortcutsPage(main));
    }

    static void open(MainWindow main) {
        boolean[] openPhoneGuide = {false};
        List<Page> pages = new java.util.ArrayList<>(pages(main));
        pages.add(new DonePage(main, openPhoneGuide));
        WizardDialog dialog = new WizardDialog(main, "PC setup", pages, () -> finished(main));
        dialog.setVisible(true); // modal
        main.reloadSettings();
        if (openPhoneGuide[0]) PhoneGuideWizard.open(main);
    }

    private static void finished(MainWindow main) {
        main.save(Map.of("setup.pcDone", "true"));
        main.logLine("PC setup finished.");
    }

    static void openInBrowser(MainWindow main, String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            main.logLine("Browser did not open. Open: " + url);
        }
    }

    // ------------------------------------------------------------------------------------------------ pages

    /** Step 1: how the PC reaches the phone on 4G. */
    /** Step 2 (Tailscale): installed, running, signed in. */
    static final class TailscalePage extends Page {
        private final MainWindow main;
        private final CheckRow installed = new CheckRow("Tailscale installed on this PC");
        private final CheckRow signedIn = new CheckRow("Signed in");
        private final CheckRow address = new CheckRow("This PC's Tailscale address");
        private JButton installButton;
        private JButton signInButton;
        private Tailscale.Status status;
        private boolean polling;
        private boolean installing;
        private boolean openLinkWhenReady;
        private long nextPoll;
        /** Set when the wizard opened with this PC already signed in: then there is nothing to show. */
        private volatile boolean readyAtStart;
        private boolean autoInstallTried;
        private boolean autoSignInTried;
        private volatile boolean installerBusy;
        /** Since when this page has waited for the installer's Tailscale task; after INSTALLER_WAIT_MS Install shows anyway. */
        private long installerWaitSince;
        private static final long INSTALLER_WAIT_MS = 3 * 60_000;
        /** Since when Tailscale's service hasn't answered; the first seconds after an install are normal. */
        private long serviceDownSince;
        private static final long SERVICE_GRACE_MS = 15_000;
        private JButton openButton;
        private String installError = "";

        TailscalePage(MainWindow main) {
            this.main = main;
        }

        @Override
        boolean isApplicable() {
            return !readyAtStart;
        }

        @Override
        void prepare() {
            background(Tailscale::status, s -> readyAtStart = s != null && s.signedIn() && !s.selfIp().isEmpty());
        }

        @Override
        String title() {
            return "Tailscale on this PC";
        }

        @Override
        String hint() {
            return "Sign in when the browser opens. Use the same account on the phone.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            installButton = installed.addPrimaryAction("Install", this::install);
            openButton = installed.addAction("Open Tailscale", () -> {
                if (!Tailscale.openApp()) main.logLine("Could not open Tailscale's window.");
            });
            openButton.setToolTipText("Opens Tailscale and starts its service");
            openButton.setVisible(false);
            signInButton = signedIn.addPrimaryAction("Sign in", this::signIn);
            WizardDialog.addRow(col, installed);
            WizardDialog.addRow(col, signedIn);
            WizardDialog.addRow(col, address);
            installed.set(State.PENDING, "Checking...");
            signedIn.set(State.PENDING, "");
            address.set(State.PENDING, "");
            return col;
        }

        @Override
        void onShow() {
            nextPoll = 0;
        }

        @Override
        void refresh() {
            if (polling || installing || System.currentTimeMillis() < nextPoll) return;
            polling = true;
            background(() -> {
                Tailscale.Status s = Tailscale.status();
                installerBusy = !s.installed() && PcPrep.tailscaleInstallPending();
                return s;
            }, s -> {
                polling = false;
                nextPoll = System.currentTimeMillis() + 2500;
                if (s != null) apply(s);
            });
        }

        private void apply(Tailscale.Status s) {
            status = s;
            if (!s.installed()) {
                signedIn.set(State.PENDING, "Install Tailscale first.");
                signInButton.setVisible(false);
                address.set(State.PENDING, "");
                if (installing) return;
                if (installerBusy) {
                    if (installerWaitSince == 0) installerWaitSince = System.currentTimeMillis();
                    boolean tooLong = System.currentTimeMillis() - installerWaitSince > INSTALLER_WAIT_MS;
                    installed.set(State.PENDING, "Installer is installing it (1-2 min)..."
                            + (tooLong ? " Slow: press Install to do it here." : ""));
                    installButton.setVisible(tooLong); // the installer's task may be stuck: not waited for forever
                    return;
                }
                if (!autoInstallTried) { // nothing to click: start the install straight away
                    autoInstallTried = true;
                    install();
                    return;
                }
                installed.set(State.BAD, (installError.isEmpty() ? "Not installed." : "Not installed: " + installError)
                        + " Press Install to retry (approve the Windows prompt).");
                installButton.setVisible(true);
                return;
            }
            installButton.setVisible(false);
            if (!s.running()) { // `tailscale status` printed no JSON: the service isn't there to ask
                if (serviceDownSince == 0) serviceDownSince = System.currentTimeMillis();
                boolean justInstalled = System.currentTimeMillis() - serviceDownSince < SERVICE_GRACE_MS;
                installed.set(State.WARN, justInstalled ? "Installed, service starting..."
                        : "Installed, service not running. Press <b>Open Tailscale</b> or restart the PC.");
                openButton.setVisible(!justInstalled);
                signedIn.set(State.PENDING, "Once Tailscale runs.");
                signInButton.setVisible(false);
                address.set(State.PENDING, "");
                return;
            }
            serviceDownSince = 0;
            openButton.setVisible(false);
            installed.set(State.OK, "Installed and running.");
            if (s.signedIn()) {
                signedIn.set(State.OK, "Signed in as " + s.selfName());
                signInButton.setVisible(false);
                address.set(State.OK, "<b>" + s.selfIp() + "</b>");
                openLinkWhenReady = false;
            } else {
                signInButton.setVisible(true);
                boolean stopped = "Stopped".equals(s.backendState());
                signedIn.set(State.WARN, stopped ? "Tailscale is off. Turning it on - sign in when the browser opens."
                        : "Not signed in. Sign in in the browser (or press Sign in).");
                address.set(State.PENDING, "After sign-in.");
                if (!autoSignInTried && s.running()) { // nothing to click: ask for the sign-in page straight away
                    autoSignInTried = true;
                    signIn();
                }
                if (openLinkWhenReady && !s.authUrl().isEmpty()) {
                    openLinkWhenReady = false;
                    openInBrowser(main, s.authUrl());
                }
            }
        }

        private void install() {
            installing = true;
            installButton.setVisible(false);
            installed.set(State.PENDING, "Installing - approve the Windows prompt (1-2 min).");
            background(Tailscale::install, r -> {
                installing = false;
                installButton.setEnabled(true);
                nextPoll = 0;
                if (r == null || !r.ok()) {
                    installError = r == null ? "the install failed." : lastLine(r.output());
                    main.logLine("Tailscale install: " + installError);
                } else {
                    installError = "";
                    main.logLine("Tailscale installed.");
                }
            });
        }

        private void signIn() {
            // Stopped (switched off, or never signed in): `tailscale login` brings it up and asks for a sign-in link in
            // one go - `up` alone would wait for a login that never comes
            if (status != null && !status.authUrl().isEmpty()) {
                openInBrowser(main, status.authUrl());
            } else {
                openLinkWhenReady = true;
                Tailscale.startLogin();
                signedIn.set(State.PENDING, "Getting sign-in link...");
            }
            nextPoll = 0;
        }

        @Override
        String status() {
            if (status == null) return "Checking Tailscale...";
            if (!status.installed()) return "Install Tailscale to continue.";
            if (!status.signedIn()) return "Sign in to Tailscale.";
            return "Tailscale is ready.";
        }
    }

    /** Step 3: inbound firewall rules. */
    static final class FirewallPage extends Page {
        private final MainWindow main;
        private final CheckRow linkRule;
        private final CheckRow apkRule = new CheckRow("Phone app download (TCP " + ApkShare.PORT + ")");
        private JButton addButton;
        private boolean checking;
        private long nextCheck;
        /** Both rules were there when the wizard opened (the installer adds them): nothing to show. */
        private volatile boolean readyAtStart;
        private boolean autoAddTried;

        FirewallPage(MainWindow main) {
            this.main = main;
            linkRule = new CheckRow("Relay port (UDP " + main.config().getPort() + ")");
        }

        @Override
        boolean isApplicable() {
            return !readyAtStart;
        }

        @Override
        void prepare() {
            background(() -> WindowsSetup.firewallRuleExists(WindowsSetup.LINK_RULE)
                    && WindowsSetup.firewallRuleExists(WindowsSetup.APK_RULE), both -> readyAtStart = Boolean.TRUE.equals(both));
        }

        @Override
        String title() {
            return "Windows Firewall";
        }

        @Override
        String hint() {
            return "Approve the Windows prompt.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            WizardDialog.addRow(col, linkRule);
            WizardDialog.addRow(col, apkRule);
            JPanel buttons = new JPanel(new BorderLayout());
            buttons.setOpaque(false);
            addButton = Theme.primaryButton("Add firewall rules");
            addButton.addActionListener(e -> addRules());
            buttons.add(addButton, BorderLayout.WEST);
            WizardDialog.addRow(col, buttons);
            linkRule.set(State.PENDING, "Checking...");
            apkRule.set(State.PENDING, "Checking...");
            return col;
        }

        @Override
        void onShow() {
            nextCheck = 0;
        }

        @Override
        void refresh() {
            if (checking || System.currentTimeMillis() < nextCheck) return;
            checking = true;
            background(() -> new boolean[]{WindowsSetup.firewallRuleExists(WindowsSetup.LINK_RULE),
                    WindowsSetup.firewallRuleExists(WindowsSetup.APK_RULE)}, r -> {
                checking = false;
                nextCheck = System.currentTimeMillis() + 5000;
                if (r != null) show(r[0], r[1]);
            });
        }

        private void show(boolean link, boolean apk) {
            int port = main.config().getPort();
            linkRule.set(link ? State.OK : State.WARN, link ? "UDP " + port + " open: name lookup and spectators work."
                    : "Recommended: UDP " + port + " for name lookup and spectators.");
            apkRule.set(apk ? State.OK : State.WARN, apk ? "Open to home network and Tailscale."
                    : "Recommended: phone downloads the Flight app from this PC.");
            addButton.setVisible(!(link && apk));
            if (!(link && apk) && !autoAddTried) { // nothing to click: add them straight away
                autoAddTried = true;
                addRules();
            }
        }

        private void addRules() {
            addButton.setEnabled(false);
            linkRule.set(State.PENDING, "Approve the Windows prompt...");
            background(() -> WindowsSetup.addFirewallRules(main.config().getPort(), ApkShare.PORT), r -> {
                addButton.setEnabled(true);
                nextCheck = 0;
                if (r == null || !r.ok()) main.logLine("Firewall rules not added (prompt declined?)");
            });
        }

        @Override
        String status() {
            return null;
        }
    }

    /** Step 4: radio connected and calibrated. */
    static final class RadioPage extends Page {
        private final MainWindow main;
        private final CheckRow radio = new CheckRow("Radio connected");
        private final CheckRow calibration = new CheckRow("Sticks and switches calibrated");
        private JButton calibrateButton;
        /** Which controller, when more than one is plugged in (the same choice as the main window's list). */
        private final javax.swing.JComboBox<de.droiddrone.pc.Joystick.Info> radioChoice = new javax.swing.JComboBox<>();
        private boolean updatingChoice;
        /** Another controller was chosen here: its sticks need a calibration of their own. */
        private boolean changedController;
        /** Calibrated, one radio plugged in, when the wizard opened: nothing to show (CALIBRATE redoes it). */
        private boolean readyAtStart;

        RadioPage(MainWindow main) {
            this.main = main;
        }

        /** Shown unless it was ready when the wizard opened - and always when Drone setup changed the switches since. */
        @Override
        boolean isApplicable() {
            return shown(readyAtStart, main.config());
        }

        /** The step's rule, on its own for the tests: an old calibration may have any switch on the AUX Drone setup wrote. */
        static boolean shown(boolean readyAtStart, de.droiddrone.pc.PcConfig config) {
            return !readyAtStart || DroneSetup.radioNeedsRedo(config);
        }

        @Override
        void prepare() {
            JoystickService.Snapshot snap = main.service().getSnapshot();
            // with several controllers plugged in, the step stays: the pilot picks the right one here
            readyAtStart = main.isCalibrated() && snap.selected() != null && snap.joysticks().size() <= 1;
        }

        @Override
        String title() {
            return "Your radio";
        }

        @Override
        String hint() {
            return "Radio on, plug in USB, choose <b>USB Joystick (HID)</b> on the radio.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            radioChoice.setRenderer(new javax.swing.DefaultListCellRenderer() {
                @Override
                public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                                       boolean selected, boolean focus) {
                    super.getListCellRendererComponent(list, value, index, selected, focus);
                    if (value instanceof de.droiddrone.pc.Joystick.Info info) setText(info.name());
                    return this;
                }
            });
            radioChoice.setToolTipText("Which plugged-in controller is the radio");
            TacticalCombo.install(radioChoice);
            radioChoice.addActionListener(e -> {
                if (updatingChoice) return;
                Object chosen = radioChoice.getSelectedItem();
                JoystickService.Snapshot snap = main.service().getSnapshot();
                if (!(chosen instanceof de.droiddrone.pc.Joystick.Info info) || info.equals(snap.selected())) return;
                main.chooseJoystick(info);
                changedController = true;
                wizard.update();
            });
            radio.addComponent(radioChoice); // always there, so another controller can be picked at any time
            WizardDialog.addRow(col, radio);
            calibrateButton = calibration.addPrimaryAction("Calibrate", () -> {
                if (main.calibrate()) {
                    changedController = false;
                    main.save(Map.of(DroneSetup.RADIO_REDO_KEY, "false")); // the switches are named afresh
                }
                wizard.update();
            });
            WizardDialog.addRow(col, calibration);
            return col;
        }

        @Override
        void refresh() {
            JoystickService.Snapshot snap = main.service().getSnapshot();
            showChoice(snap);
            if (snap.selected() == null) {
                radio.set(State.BAD, "Not found. Select your model, use the top USB-C port, "
                        + "choose USB Joystick (HID).");
            } else {
                int axes = snap.state() == null ? 0 : snap.state().axes().length;
                radio.set(State.OK, "<b>" + snap.selected().name() + "</b> - " + axes + " axes."
                        + (snap.joysticks().size() > 1 ? " " + snap.joysticks().size() + " controllers: pick yours on the right." : ""));
            }
            boolean ready = snap.selected() != null && !main.isLinkActive();
            calibrateButton.setEnabled(ready);
            radioChoice.setEnabled(!main.isLinkActive());
            if (changedController) {
                calibration.set(State.WARN, "New controller: press Calibrate.");
            } else {
                calibration.set(main.isCalibrated() ? State.OK : State.WARN, main.isCalibrated()
                        ? "Done."
                        : "Press Calibrate (about a minute).");
            }
        }

        /** The list of controllers, kept up to date as they are plugged in and out. */
        private void showChoice(JoystickService.Snapshot snap) {
            List<de.droiddrone.pc.Joystick.Info> list = snap.joysticks();
            javax.swing.DefaultComboBoxModel<de.droiddrone.pc.Joystick.Info> model =
                    (javax.swing.DefaultComboBoxModel<de.droiddrone.pc.Joystick.Info>) radioChoice.getModel();
            boolean same = model.getSize() == list.size();
            for (int i = 0; same && i < list.size(); i++) same = model.getElementAt(i).equals(list.get(i));
            if (same && java.util.Objects.equals(model.getSelectedItem(), snap.selected())) return;
            updatingChoice = true;
            if (!same) {
                model.removeAllElements();
                list.forEach(model::addElement);
            }
            model.setSelectedItem(snap.selected());
            updatingChoice = false;
        }

        @Override
        String status() {
            if (changedController) return "Calibrate the controller you picked.";
            return main.isCalibrated() ? "Radio ready." : "Calibrate before flying.";
        }
    }

    /** Step 5: the High performance power plan (the installer sets it; this page shows only if that didn't work). */
    static final class LatencyPage extends Page {
        private final MainWindow main;
        private final CheckRow power = new CheckRow("Power plan");
        private JButton powerButton;
        /** High performance already, or switched to it just now: nothing to show. */
        private volatile boolean readyAtStart;

        LatencyPage(MainWindow main) {
            this.main = main;
        }

        @Override
        boolean isApplicable() {
            return !readyAtStart;
        }

        /** Modern Standby: the plans do nothing on this PC, Settings' Power mode slider does - the step stays, to say so. */
        private volatile boolean modernStandby;

        @Override
        void prepare() {
            record Prepared(boolean ready, boolean switched, boolean modernStandby) {}
            background(() -> {
                WindowsSetup.PowerPlan plan = WindowsSetup.activePowerPlan();
                boolean switched = false;
                if (plan != null && !plan.isHighPerformance() && WindowsSetup.useHighPerformance().ok()) {
                    switched = true;
                    plan = WindowsSetup.activePowerPlan();
                }
                boolean standby = WindowsSetup.modernStandby();
                return new Prepared((plan == null || plan.isHighPerformance()) && !standby, switched, standby);
            }, p -> {
                if (p == null) return;
                readyAtStart = p.ready();
                modernStandby = p.modernStandby();
                if (p.switched()) main.logLine("Power plan set to High performance.");
            });
        }

        @Override
        String title() {
            return "Power plan";
        }

        @Override
        String hint() {
            return "Power saving delays packets.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            powerButton = power.addPrimaryAction("Use High performance", this::useHighPerformance);
            powerButton.setVisible(false);
            WizardDialog.addRow(col, power);
            power.set(State.PENDING, "Checking...");
            return col;
        }

        @Override
        void onShow() {
            checkPower();
        }

        private void checkPower() {
            background(WindowsSetup::activePowerPlan, plan -> {
                if (modernStandby) {
                    power.set(State.INFO, "Modern Standby: power plans do nothing here"
                            + (plan == null || !plan.isHighPerformance() ? "" : " (" + plan.name() + " set)")
                            + ". Set Settings > System > Power > Power mode to <b>Best performance</b>, battery and plugged in.");
                    powerButton.setVisible(plan != null && !plan.isHighPerformance());
                } else if (plan == null) {
                    power.set(State.INFO, "Could not read the power plan.");
                } else if (plan.isHighPerformance()) {
                    power.set(State.OK, plan.name() + ".");
                    powerButton.setVisible(false);
                } else {
                    power.set(State.WARN, plan.name() + ". Press the button, or set Power mode to <b>Best performance</b>.");
                    powerButton.setVisible(true);
                }
            });
        }

        private void useHighPerformance() {
            powerButton.setEnabled(false);
            background(WindowsSetup::useHighPerformance, r -> {
                powerButton.setEnabled(true);
                if (r == null || !r.ok()) {
                    main.logLine("Could not switch power plan: " + (r == null ? "" : lastLine(r.output())));
                } else {
                    main.logLine("Power plan set to High performance.");
                }
                checkPower();
            });
        }
    }

    /** Step 6: shortcuts. */
    static final class ShortcutsPage extends Page {
        private final MainWindow main;
        private final CheckRow desktop = new CheckRow("Desktop shortcut");
        private final CheckRow startMenu = new CheckRow("Start menu entry");
        private final Path launcher = AppPaths.launcher();
        private Path desktopLnk;
        private final Path startMenuLnk = WindowsSetup.startMenuShortcut();
        /** Both shortcuts exist (from the installer, or made just now): nothing to show. */
        private volatile boolean readyAtStart;

        ShortcutsPage(MainWindow main) {
            this.main = main;
        }

        @Override
        boolean isApplicable() {
            return !readyAtStart;
        }

        @Override
        void prepare() {
            if (launcher == null) return;
            background(() -> {
                boolean ok = true;
                for (Path lnk : List.of(WindowsSetup.desktopShortcut(), startMenuLnk)) {
                    if (!WindowsSetup.exists(lnk)) WindowsSetup.createShortcut(lnk, launcher, AppPaths.dataDir());
                    ok &= WindowsSetup.exists(lnk);
                }
                return ok;
            }, ok -> readyAtStart = Boolean.TRUE.equals(ok));
        }

        @Override
        String title() {
            return "Shortcuts";
        }

        @Override
        String hint() {
            return "Start DRAUPNE Link with one click.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            if (launcher != null) {
                desktop.addPrimaryAction("Create", () -> create(desktopLnk, desktop));
                startMenu.addPrimaryAction("Create", () -> create(startMenuLnk, startMenu));
            }
            WizardDialog.addRow(col, desktop);
            WizardDialog.addRow(col, startMenu);
            desktop.set(State.PENDING, "Checking...");
            startMenu.set(State.PENDING, "Checking...");
            return col;
        }

        @Override
        void onShow() {
            if (launcher == null) {
                desktop.set(State.INFO, "Start from <b>DRAUPNE Link.exe</b> to create shortcuts.");
                startMenu.set(State.INFO, "");
                return;
            }
            background(WindowsSetup::desktopShortcut, lnk -> {
                desktopLnk = lnk;
                show(desktop, lnk);
            });
            show(startMenu, startMenuLnk);
        }

        private void show(CheckRow row, Path lnk) {
            boolean exists = WindowsSetup.exists(lnk);
            row.set(exists ? State.OK : State.WARN, exists ? "Created: " + lnk : "Starts " + launcher.getFileName());
        }

        private void create(Path lnk, CheckRow row) {
            if (lnk == null) return;
            background(() -> WindowsSetup.createShortcut(lnk, launcher, AppPaths.dataDir()), r -> {
                if (r == null || !r.ok()) main.logLine("Shortcut not created: " + (r == null ? "" : lastLine(r.output())));
                show(row, lnk);
            });
        }
    }

    /** Last step. */
    static final class DonePage extends Page {
        private final MainWindow main;
        private final boolean[] openPhoneGuide;

        DonePage(MainWindow main, boolean[] openPhoneGuide) {
            this.main = main;
            this.openPhoneGuide = openPhoneGuide;
        }

        @Override
        String title() {
            return "The PC is ready";
        }

        @Override
        String hint() {
            return "Next: the drone's phone.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            JPanel buttons = new JPanel(new BorderLayout());
            buttons.setOpaque(false);
            JButton guide = Theme.primaryButton("Continue with phone setup");
            guide.addActionListener(e -> {
                openPhoneGuide[0] = true;
                finished(main);
                wizard.dispose();
            });
            buttons.add(guide, BorderLayout.WEST);
            WizardDialog.addRow(col, buttons);
            return col;
        }
    }

    static String lastLine(String text) {
        String[] lines = text.strip().split("\\R");
        return lines.length == 0 ? "" : lines[lines.length - 1];
    }
}
