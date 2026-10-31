package de.droiddrone.pc.gui;

import de.droiddrone.pc.AppPaths;
import de.droiddrone.pc.JoystickService;
import de.droiddrone.pc.LinkMode;
import de.droiddrone.pc.setup.ApkShare;
import de.droiddrone.pc.setup.NetInfo;
import de.droiddrone.pc.setup.PcPrep;
import de.droiddrone.pc.setup.Shell;
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
 * connection method, Tailscale, firewall, radio + calibration, power plan, shortcuts.
 */
final class PcSetupWizard {
    private PcSetupWizard() {
    }

    static void open(MainWindow main) {
        boolean[] openPhoneGuide = {false};
        List<Page> pages = List.of(
                new ConnectionPage(main),
                new TailscalePage(main),
                new RelayPage(main),
                new FirewallPage(main),
                new RadioPage(main),
                new LatencyPage(main),
                new ShortcutsPage(main),
                new DonePage(main, openPhoneGuide));
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
            main.logLine("Could not open the browser - open this link yourself: " + url);
        }
    }

    // ------------------------------------------------------------------------------------------------ pages

    /** Step 1: how the PC reaches the phone on 4G. */
    static final class ConnectionPage extends Page {
        private final MainWindow main;

        ConnectionPage(MainWindow main) {
            this.main = main;
        }

        @Override
        String title() {
            return "How will the PC reach the phone?";
        }

        @Override
        String hint() {
            return "Tailscale is right for almost everyone. Change this only if you know you need a relay.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            ButtonGroup group = new ButtonGroup();
            addOption(col, group, LinkMode.PHONE, "Tailscale VPN  (recommended)",
                    "Works on any 4G network, no router changes.");
            addOption(col, group, LinkMode.HOST_RELAY, "Host the relay on this PC",
                    "Needs a port forward on your router and a public IPv4 address.");
            addOption(col, group, LinkMode.RELAY, "Relay server elsewhere",
                    "A DroidDrone relay server on the internet. Adds latency.");
            return col;
        }

        private void addOption(JPanel col, ButtonGroup group, LinkMode mode, String title, String text) {
            JPanel card = new JPanel(new BorderLayout(12, 4));
            card.setBackground(Theme.PANEL_2);
            card.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
            JRadioButton radio = new JRadioButton(title);
            radio.setFont(Theme.font(Font.BOLD, 15f));
            radio.setForeground(Theme.TEXT);
            radio.setOpaque(false);
            radio.setSelected(main.config().getLinkMode() == mode);
            radio.addActionListener(e -> main.save(Map.of("linkMode", mode.key)));
            group.add(radio);
            card.add(radio, BorderLayout.NORTH);
            WrappingText desc = new WrappingText(text, Theme.font(Font.PLAIN, 13.5f), Theme.MUTED);
            desc.setBorder(BorderFactory.createEmptyBorder(0, 28, 0, 0));
            card.add(desc, BorderLayout.CENTER);
            WizardDialog.addRow(col, card);
        }
    }

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
        private String installError = "";

        TailscalePage(MainWindow main) {
            this.main = main;
        }

        @Override
        boolean isApplicable() {
            return main.config().getLinkMode() == LinkMode.PHONE && !readyAtStart;
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
            return "Sign in when your browser opens. Use the same account on the phone later.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            installButton = installed.addPrimaryAction("Install", this::install);
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
                    installed.set(State.PENDING, "Being installed by the DRAUPNE Link installer - this takes a minute or two...");
                    installButton.setVisible(false);
                    return;
                }
                if (!autoInstallTried) { // nothing to click: start the install straight away
                    autoInstallTried = true;
                    install();
                    return;
                }
                installed.set(State.BAD, (installError.isEmpty() ? "Not installed." : "Not installed: " + installError)
                        + " Press Install to try again (Windows asks you to approve it).");
                installButton.setVisible(true);
                return;
            }
            installButton.setVisible(false);
            installed.set(s.running() ? State.OK : State.WARN,
                    s.running() ? "Installed and running." : "Installed, but its service isn't answering yet - give it a few seconds.");
            if (s.signedIn()) {
                signedIn.set(State.OK, "Signed in as this device: " + s.selfName());
                signInButton.setVisible(false);
                address.set(State.OK, "<b>" + s.selfIp() + "</b>");
                openLinkWhenReady = false;
            } else {
                signInButton.setVisible(true);
                boolean stopped = "Stopped".equals(s.backendState());
                signedIn.set(State.WARN, stopped ? "Tailscale is switched off. Switching it on - sign in when your browser opens."
                        : "Not signed in. Your browser opens Tailscale's login page - sign in there (or press Sign in).");
                address.set(State.PENDING, "Appears after signing in.");
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
            installed.set(State.PENDING, "Installing Tailscale - approve the Windows prompt. Takes a minute or two.");
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
            if (status != null && "Stopped".equals(status.backendState())) {
                Path exe = Tailscale.exe();
                if (exe != null) background(() -> Shell.run(java.time.Duration.ofSeconds(20), exe.toString(), "up"), r -> nextPoll = 0);
                openLinkWhenReady = true;
                return;
            }
            if (status != null && !status.authUrl().isEmpty()) {
                openInBrowser(main, status.authUrl());
            } else {
                openLinkWhenReady = true;
                Tailscale.startLogin();
                signedIn.set(State.PENDING, "Asking Tailscale for a sign-in link...");
            }
            nextPoll = 0;
        }

        @Override
        String status() {
            if (status == null) return "Checking Tailscale...";
            if (!status.installed()) return "Install Tailscale to continue (or go Back and pick another method).";
            if (!status.signedIn()) return "Sign in to Tailscale.";
            return "Tailscale is ready.";
        }
    }

    /** Step 2 (relay modes): public IP / port forward, or the relay server's address. */
    static final class RelayPage extends Page {
        private final MainWindow main;
        private final CheckRow publicIp = new CheckRow("Your public IP address");
        private final CheckRow forward = new CheckRow("Router port forward");
        private final JPanel hostPanel = WizardDialog.column();
        private final JPanel relayPanel = WizardDialog.column();
        private final JTextField relayAddress = new JTextField();

        RelayPage(MainWindow main) {
            this.main = main;
        }

        @Override
        boolean isApplicable() {
            LinkMode mode = main.config().getLinkMode();
            return mode == LinkMode.HOST_RELAY || mode == LinkMode.RELAY;
        }

        @Override
        String title() {
            return main.config().getLinkMode() == LinkMode.HOST_RELAY ? "Open your router to the phone" : "Relay server";
        }

        @Override
        String hint() {
            return main.config().getLinkMode() == LinkMode.HOST_RELAY
                    ? "The phone on 4G must be able to reach this PC through your home's public IP address."
                    : "Enter the public address of the machine running DroidDrone's relay server.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            publicIp.addAction("Check", this::checkPublicIp);
            publicIp.set(State.PENDING, "Press Check (asks api.ipify.org which address your connection has).");
            WizardDialog.addRow(hostPanel, publicIp);
            WizardDialog.addRow(hostPanel, forward);
            WizardDialog.addRow(hostPanel, WizardDialog.paragraph(
                    "Compare the public IP with the <b>WAN / Internet IP</b> shown in your router's status page. "
                            + "If they differ, your provider shares one address between customers (CGNAT) and a port "
                            + "forward cannot work - go Back and choose Tailscale."));
            WizardDialog.addRow(relayPanel, WizardDialog.subheading("Relay server address"));
            relayAddress.setText(main.config().getIp());
            relayAddress.putClientProperty("JTextField.placeholderText", "e.g. 203.0.113.10 or relay.example.com");
            relayAddress.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    store();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    store();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    store();
                }
            });
            WizardDialog.addRow(relayPanel, relayAddress);
            WizardDialog.addRow(relayPanel, WizardDialog.paragraph(
                    "On that machine run upstream's DD_Server (Java) with UDP port " + main.config().getPort()
                            + " open, and the same key as here (" + main.config().getKey() + ")."));
            WizardDialog.addRow(col, hostPanel);
            WizardDialog.addRow(col, relayPanel);
            return col;
        }

        private void store() {
            main.save(Map.of("ip", relayAddress.getText().trim()));
        }

        @Override
        void onShow() {
            boolean host = main.config().getLinkMode() == LinkMode.HOST_RELAY;
            hostPanel.setVisible(host);
            relayPanel.setVisible(!host);
            List<String> lan = NetInfo.lanAddresses();
            forward.set(State.INFO, "In your router, forward <b>UDP " + main.config().getPort() + "</b> to this PC"
                    + (lan.isEmpty() ? "" : " (<b>" + String.join(" / ", lan) + "</b>)")
                    + ". Give this PC a fixed address in the router (DHCP reservation) so the forward keeps working.");
        }

        private void checkPublicIp() {
            publicIp.set(State.PENDING, "Asking...");
            background(() -> {
                try {
                    return NetInfo.publicIp();
                } catch (Exception e) {
                    return null;
                }
            }, ip -> {
                if (ip == null) {
                    publicIp.set(State.BAD, "Could not find out - is the PC online?");
                } else {
                    publicIp.set(State.OK, "<b>" + ip + "</b> - enter this in the Flight app (Connect over server).");
                    main.save(Map.of("publicIp", ip));
                    main.logLine("Public IP: " + ip);
                }
            });
        }

        @Override
        String status() {
            if (main.config().getLinkMode() == LinkMode.RELAY && main.config().getIp().isEmpty()) return "Enter the relay address.";
            return null;
        }

        @Override
        boolean canContinue() {
            return main.config().getLinkMode() != LinkMode.RELAY || !main.config().getIp().isEmpty();
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
            return "Approve the Windows prompt to add the rules.";
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
            boolean host = main.config().getLinkMode() == LinkMode.HOST_RELAY;
            int port = main.config().getPort();
            if (link) {
                linkRule.set(State.OK, "Inbound UDP " + port + " allowed.");
            } else if (host) {
                linkRule.set(State.BAD, "Required: the 4G phone connects in to the relay on UDP " + port + ".");
            } else {
                linkRule.set(State.INFO, "Not needed for your connection method (only for hosting the relay).");
            }
            apkRule.set(apk ? State.OK : State.WARN, apk ? "Allowed from your home network and Tailscale."
                    : "Recommended: lets the phone download the Flight app straight from this PC.");
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
            if (main.config().getLinkMode() == LinkMode.HOST_RELAY && linkRule.getState() == State.BAD) {
                return "Hosting the relay needs the UDP rule.";
            }
            return null;
        }
    }

    /** Step 4: radio connected and calibrated. */
    static final class RadioPage extends Page {
        private final MainWindow main;
        private final CheckRow radio = new CheckRow("Radio connected");
        private final CheckRow calibration = new CheckRow("Sticks and switches calibrated");
        private JButton calibrateButton;
        /** Calibrated, with the radio plugged in, when the wizard opened: nothing to show (CALIBRATE redoes it). */
        private boolean readyAtStart;

        RadioPage(MainWindow main) {
            this.main = main;
        }

        @Override
        boolean isApplicable() {
            return !readyAtStart;
        }

        @Override
        void prepare() {
            readyAtStart = main.isCalibrated() && main.service().getSnapshot().selected() != null;
        }

        @Override
        String title() {
            return "Your radio";
        }

        @Override
        String hint() {
            return "Turn the radio on, plug it into this PC and choose <b>USB Joystick (HID)</b> on the radio.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            WizardDialog.addRow(col, radio);
            calibrateButton = calibration.addPrimaryAction("Calibrate", () -> {
                main.calibrate();
                wizard.update();
            });
            WizardDialog.addRow(col, calibration);
            return col;
        }

        @Override
        void refresh() {
            JoystickService.Snapshot snap = main.service().getSnapshot();
            if (snap.selected() == null) {
                radio.set(State.BAD, "Not found yet. Select the model you fly, use the USB-C port on top of the radio, "
                        + "and choose USB Joystick (HID) on its pop-up.");
            } else {
                int axes = snap.state() == null ? 0 : snap.state().axes().length;
                radio.set(State.OK, "<b>" + snap.selected().name() + "</b> - " + axes + " axes.");
            }
            boolean ready = snap.selected() != null && !main.isLinkActive();
            calibrateButton.setEnabled(ready);
            calibration.set(main.isCalibrated() ? State.OK : State.WARN, main.isCalibrated()
                    ? "Done."
                    : "Press Calibrate and follow the steps (about a minute).");
        }

        @Override
        String status() {
            return main.isCalibrated() ? "Radio ready." : "Calibrate the radio before flying.";
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

        @Override
        void prepare() {
            record Prepared(boolean ready, boolean switched) {}
            background(() -> {
                WindowsSetup.PowerPlan plan = WindowsSetup.activePowerPlan();
                boolean switched = false;
                if (plan != null && !plan.isHighPerformance() && WindowsSetup.useHighPerformance().ok()) {
                    switched = true;
                    plan = WindowsSetup.activePowerPlan();
                }
                return new Prepared(plan == null || plan.isHighPerformance(), switched);
            }, p -> {
                if (p == null) return;
                readyAtStart = p.ready();
                if (p.switched()) main.logLine("Power plan set to High performance.");
            });
        }

        @Override
        String title() {
            return "Power plan";
        }

        @Override
        String hint() {
            return "Power saving delays packets while the CPU and Wi-Fi wake up.";
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
                if (plan == null) {
                    power.set(State.INFO, "Could not read the power plan.");
                } else if (plan.isHighPerformance()) {
                    power.set(State.OK, plan.name() + ".");
                    powerButton.setVisible(false);
                } else {
                    power.set(State.WARN, plan.name() + ". Press the button, or set Settings > System > Power > "
                            + "Power mode to <b>Best performance</b>.");
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
                desktop.set(State.INFO, "Start the app from <b>DRAUPNE Link.exe</b> (or draupne-link.bat) to create shortcuts.");
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
            return "Next: the phone that flies on the drone.";
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
