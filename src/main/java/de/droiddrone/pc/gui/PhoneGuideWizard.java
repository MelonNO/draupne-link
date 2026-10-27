package de.droiddrone.pc.gui;

import de.droiddrone.common.FcInfo;
import de.droiddrone.pc.AppPaths;
import de.droiddrone.pc.Connection;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.LinkMode;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.setup.ApkShare;
import de.droiddrone.pc.setup.Tailscale;
import de.droiddrone.pc.setup.WindowsSetup;
import de.droiddrone.pc.gui.CheckRow.State;
import de.droiddrone.pc.gui.WizardDialog.Page;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Interactive guide for the phone that flies on the drone. Where the PC can see the result it checks it live
 * (phone on Tailscale, app downloaded, link test); elsewhere the pilot ticks steps off.
 */
final class PhoneGuideWizard {
    private PhoneGuideWizard() {
    }

    static void open(MainWindow main) {
        List<Page> pages = List.of(
                new OverviewPage(main),
                new PhoneTailscalePage(main),
                new FlightAppPage(main),
                new PhoneSettingsPage(main),
                new FlightAppSettingsPage(main),
                new FlightControllerPage(main),
                new LinkTestPage(main));
        new WizardDialog(main, "Phone guide", pages, () -> {
            main.save(Map.of("setup.phoneDone", "true"));
            main.logLine("Phone guide finished.");
        }).setVisible(true);
        main.reloadSettings();
    }

    /** A checkbox whose ticked state is remembered in the settings file. */
    private static JCheckBox savedCheck(MainWindow main, String key, String html) {
        JCheckBox box = new JCheckBox("<html>" + html + "</html>");
        box.setFont(Theme.font(Font.PLAIN, 14f));
        box.setForeground(Theme.TEXT);
        box.setOpaque(false);
        box.setSelected("true".equals(main.config().getString(key)));
        box.addActionListener(e -> main.save(Map.of(key, Boolean.toString(box.isSelected()))));
        return box;
    }

    private static JComponent valueTable(String[][] rows) {
        JPanel table = new JPanel(new GridLayout(0, 2, 16, 8));
        table.setBackground(Theme.PANEL_2);
        table.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        for (String[] row : rows) {
            table.add(Theme.label(row[0].toUpperCase(), Theme.headingFont(12f), Theme.MUTED));
            JLabel value = Theme.label(row[1], Theme.font(Font.BOLD, 18f), Theme.ACCENT);
            table.add(value);
        }
        return table;
    }

    // ------------------------------------------------------------------------------------------------ pages

    static final class OverviewPage extends Page {
        private final MainWindow main;

        OverviewPage(MainWindow main) {
            this.main = main;
        }

        @Override
        String title() {
            return "Set up the flying phone";
        }

        @Override
        String hint() {
            return "This phone rides on the drone, talks to the flight controller over USB and to this PC over 4G.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            WizardDialog.addRow(col, WizardDialog.subheading("You need"));
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "&bull; An Android phone, Android 8 or newer (11+ recommended), with a 4G/5G data plan<br>"
                            + "&bull; A USB OTG cable or adapter from the phone to the flight controller<br>"
                            + "&bull; The phone and this PC within reach for the setup (about 10 minutes)"));
            WizardDialog.addRow(col, WizardDialog.subheading("Steps"));
            boolean vpn = main.config().getLinkMode() == LinkMode.PHONE;
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    (vpn ? "1. Tailscale on the phone - the PC spots it automatically<br>" : "")
                            + "2. Install DroidDrone Flight by scanning a QR code<br>"
                            + "3. Phone settings that keep the link alive<br>"
                            + "4. Flight app settings<br>"
                            + "5. Connect the flight controller<br>"
                            + "6. Test the link and measure the latency"));
            return col;
        }
    }

    /** Tailscale on the phone; the PC detects the phone joining and stores its address. */
    static final class PhoneTailscalePage extends Page {
        private final MainWindow main;
        private final QrView qr = new QrView(200);
        private final CheckRow pc = new CheckRow("This PC on Tailscale");
        private final CheckRow phone = new CheckRow("Phone on Tailscale");
        private final DefaultComboBoxModel<Tailscale.Peer> phones = new DefaultComboBoxModel<>();
        private final JComboBox<Tailscale.Peer> phoneChoice = new JComboBox<>(phones);
        private boolean polling;
        private long nextPoll;
        private boolean updatingChoice;

        PhoneTailscalePage(MainWindow main) {
            this.main = main;
        }

        @Override
        boolean isApplicable() {
            return main.config().getLinkMode() == LinkMode.PHONE;
        }

        @Override
        String title() {
            return "Tailscale on the phone";
        }

        @Override
        String hint() {
            return "Scan the code with the phone's camera to open Tailscale in the Play Store.";
        }

        @Override
        JComponent build() {
            JPanel page = new JPanel(new BorderLayout(24, 0));
            page.setOpaque(false);
            qr.setContent(Tailscale.PLAY_STORE_URL, "Tailscale - Google Play");
            JPanel left = new JPanel(new BorderLayout());
            left.setOpaque(false);
            left.add(qr, BorderLayout.NORTH);
            page.add(left, BorderLayout.WEST);
            JPanel col = WizardDialog.column();
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "1. Install <b>Tailscale</b> and open it.<br>"
                            + "2. Sign in with <b>the same account</b> you used on this PC.<br>"
                            + "3. Allow the VPN connection when Android asks.<br>"
                            + "The phone appears below within a few seconds."));
            WizardDialog.addRow(col, pc);
            phoneChoice.setRenderer(new javax.swing.DefaultListCellRenderer() {
                @Override
                public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                                                                       int index, boolean selected, boolean focus) {
                    super.getListCellRendererComponent(list, value, index, selected, focus);
                    if (value instanceof Tailscale.Peer p) setText(p.hostName() + "  " + p.ip() + (p.online() ? "" : "  (offline)"));
                    return this;
                }
            });
            phoneChoice.addActionListener(e -> {
                if (!updatingChoice && phoneChoice.getSelectedItem() instanceof Tailscale.Peer p) choose(p);
            });
            phoneChoice.setVisible(false);
            phone.addComponent(phoneChoice);
            WizardDialog.addRow(col, phone);
            page.add(col, BorderLayout.CENTER);
            pc.set(State.PENDING, "Checking...");
            phone.set(State.PENDING, "Waiting for an Android device on your Tailscale network...");
            return page;
        }

        @Override
        void onShow() {
            nextPoll = 0;
        }

        @Override
        void refresh() {
            if (polling || System.currentTimeMillis() < nextPoll) return;
            polling = true;
            background(Tailscale::status, s -> {
                polling = false;
                nextPoll = System.currentTimeMillis() + 2500;
                if (s != null) apply(s);
            });
        }

        private void apply(Tailscale.Status s) {
            if (!s.signedIn()) {
                pc.set(State.BAD, "Not signed in - finish the Tailscale step in PC SETUP first.");
                return;
            }
            pc.set(State.OK, s.selfName() + " - " + s.selfIp());
            List<Tailscale.Peer> androids = new ArrayList<>(s.androidPeers());
            androids.sort((a, b) -> Boolean.compare(b.online(), a.online()));
            updatingChoice = true;
            Object selected = phoneChoice.getSelectedItem();
            phones.removeAllElements();
            androids.forEach(phones::addElement);
            String savedIp = main.config().getIp();
            Tailscale.Peer current = androids.stream().filter(p -> p.ip().equals(savedIp)).findFirst()
                    .orElse(selected instanceof Tailscale.Peer p && androids.contains(p) ? p : null);
            if (current == null && !androids.isEmpty()) current = androids.get(0);
            phones.setSelectedItem(current);
            phoneChoice.setVisible(androids.size() > 1);
            updatingChoice = false;
            if (current == null) {
                phone.set(State.PENDING, "Waiting for an Android device on your Tailscale network...");
            } else {
                if (!current.ip().equals(savedIp)) choose(current);
                phone.set(current.online() ? State.OK : State.WARN, "<b>" + current.hostName() + "</b> - " + current.ip()
                        + (current.online() ? " - online. Address saved for the link." : " - offline: open Tailscale on the phone and switch it on."));
            }
            wizard.update();
        }

        private void choose(Tailscale.Peer p) {
            main.save(Map.of("ip", p.ip(), "phoneName", p.hostName()));
            main.logLine("Phone address set to " + p.ip() + " (" + p.hostName() + ").");
        }

        @Override
        String status() {
            return phone.getState() == State.OK ? "Phone found." : "Waiting for the phone to join Tailscale.";
        }
    }

    /** Download + verify the Flight app on the PC, then serve it to the phone via a QR code. */
    static final class FlightAppPage extends Page {
        private final MainWindow main;
        private final QrView qr = new QrView(220);
        private final CheckRow file = new CheckRow("DroidDrone Flight " + ApkShare.VERSION);
        private final CheckRow downloaded = new CheckRow("Downloaded by the phone");
        private final CheckRow firewall = new CheckRow("Firewall");
        private final DefaultComboBoxModel<String> urls = new DefaultComboBoxModel<>();
        private final JComboBox<String> urlChoice = new JComboBox<>(urls);
        private ApkShare share;
        private boolean preparing;

        FlightAppPage(MainWindow main) {
            this.main = main;
        }

        @Override
        String title() {
            return "Install DroidDrone Flight";
        }

        @Override
        String hint() {
            return "The PC fetches the official release from GitHub, checks it, and hands it to the phone over your network.";
        }

        @Override
        JComponent build() {
            JPanel page = new JPanel(new BorderLayout(24, 0));
            page.setOpaque(false);
            JPanel qrAndChoice = new JPanel(new BorderLayout(0, 8));
            qrAndChoice.setOpaque(false);
            qrAndChoice.add(qr, BorderLayout.NORTH);
            urlChoice.addActionListener(e -> showQr());
            qrAndChoice.add(urlChoice, BorderLayout.SOUTH);
            JPanel left = new JPanel(new BorderLayout());
            left.setOpaque(false);
            left.add(qrAndChoice, BorderLayout.NORTH);
            page.add(left, BorderLayout.WEST);
            JPanel col = WizardDialog.column();
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "1. Scan the code with the phone's camera and open the link.<br>"
                            + "2. Tap <b>Download the app</b>, then open the downloaded file.<br>"
                            + "3. Android asks to allow installing from your browser: <b>Settings &rarr; Allow from this source</b>, go back, <b>Install</b>.<br>"
                            + "If the link doesn't open, pick another address under the code (the phone must be on your Wi-Fi or on Tailscale)."));
            WizardDialog.addRow(col, file);
            WizardDialog.addRow(col, downloaded);
            WizardDialog.addRow(col, firewall);
            page.add(col, BorderLayout.CENTER);
            file.set(State.PENDING, "Preparing...");
            downloaded.set(State.PENDING, "Waiting for the phone...");
            firewall.set(State.PENDING, "Checking...");
            return page;
        }

        @Override
        void onShow() {
            background(() -> WindowsSetup.firewallRuleExists(WindowsSetup.APK_RULE), ok -> firewall.set(
                    Boolean.TRUE.equals(ok) ? State.OK : State.WARN, Boolean.TRUE.equals(ok)
                            ? "The phone may download from this PC."
                            : "No firewall rule yet - Windows may block the phone. Add it in PC SETUP (Windows Firewall step)."));
            if (share != null || preparing) return;
            preparing = true;
            file.set(State.PENDING, "Downloading from GitHub and checking it (about 7 MB)...");
            background(() -> {
                try {
                    Path apk = ApkShare.prepareApk(AppPaths.downloadsDir());
                    ApkShare s = new ApkShare();
                    s.start(apk);
                    return s;
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> file.set(State.BAD, "Could not prepare the app: " + e.getMessage()));
                    return null;
                }
            }, s -> {
                preparing = false;
                if (s == null) return;
                share = s;
                file.set(State.OK, "Verified (SHA-256) and ready to download.");
                List<String> list = ApkShare.urls();
                if (main.config().getLinkMode() != LinkMode.PHONE) {
                    List<String> lanFirst = new ArrayList<>(list);
                    lanFirst.sort((a, b) -> Boolean.compare(a.startsWith("http://100."), b.startsWith("http://100.")));
                    list = lanFirst;
                }
                urls.removeAllElements();
                list.forEach(urls::addElement);
                if (!list.isEmpty()) urls.setSelectedItem(list.get(0));
                showQr();
            });
        }

        private void showQr() {
            String url = (String) urlChoice.getSelectedItem();
            qr.setContent(url, url == null ? "No network address found" : "Scan with the phone");
        }

        @Override
        void refresh() {
            if (share == null) return;
            int n = share.getCompletedDownloads();
            if (n > 0) downloaded.set(State.OK, "The phone downloaded the app. Install it, then press Next.");
        }

        @Override
        void onHide() {
            if (share != null) share.close(); // only serve while this step is open
            share = null;
        }
    }

    static final class PhoneSettingsPage extends Page {
        private final MainWindow main;

        PhoneSettingsPage(MainWindow main) {
            this.main = main;
        }

        @Override
        String title() {
            return "Keep the phone connected";
        }

        @Override
        String hint() {
            return "Android saves battery by pausing apps in the background - in the air that means a failsafe. Tick each one off.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            WizardDialog.addRow(col, savedCheck(main, "guide.battery",
                    "<b>Battery: Unrestricted</b> for DroidDrone Flight <i>and</i> Tailscale<br>"
                            + "<span style='color:" + Theme.MUTED_HEX + "'>Settings &rarr; Apps &rarr; DroidDrone Flight &rarr; Battery &rarr; Unrestricted</span>"));
            WizardDialog.addRow(col, savedCheck(main, "guide.data",
                    "<b>Unrestricted mobile data</b> for both apps (or Data Saver off)<br>"
                            + "<span style='color:" + Theme.MUTED_HEX + "'>Settings &rarr; Apps &rarr; app &rarr; Mobile data &rarr; Unrestricted data usage</span>"));
            if (main.config().getLinkMode() == LinkMode.PHONE) {
                WizardDialog.addRow(col, savedCheck(main, "guide.alwaysOn",
                        "<b>Tailscale as Always-on VPN</b>, so it reconnects by itself after a network change<br>"
                                + "<span style='color:" + Theme.MUTED_HEX + "'>Settings &rarr; Network &amp; internet &rarr; VPN &rarr; Tailscale &#9881; &rarr; Always-on VPN</span>"));
            }
            WizardDialog.addRow(col, savedCheck(main, "guide.wifiOff",
                    "<b>Before flying: Wi-Fi off</b> on the phone, so it stays on 4G<br>"
                            + "<span style='color:" + Theme.MUTED_HEX + "'>Otherwise it may take off on your home Wi-Fi and drop out as the drone flies away.</span>"));
            return col;
        }
    }

    static final class FlightAppSettingsPage extends Page {
        private final MainWindow main;
        private final JPanel tableHolder = new JPanel(new BorderLayout());

        FlightAppSettingsPage(MainWindow main) {
            this.main = main;
        }

        @Override
        String title() {
            return "Flight app settings";
        }

        @Override
        String hint() {
            return "Open DroidDrone Flight on the phone and enter exactly this:";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            tableHolder.setOpaque(false);
            WizardDialog.addRow(col, tableHolder);
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "Then tap <b>CONNECT</b>. The app keeps running in the background; leave it open while flying. "
                            + "Its network status shows <i>Awaiting connection</i> (direct) or <i>Connecting</i> (server) until the PC connects."));
            WizardDialog.addRow(col, savedCheck(main, "guide.flightConnected", "The Flight app is set up and I tapped <b>Connect</b>"));
            return col;
        }

        @Override
        void onShow() {
            PcConfig c = main.config();
            String[][] rows = switch (c.getLinkMode()) {
                case PHONE -> new String[][]{
                        {"Connection", "Direct connection"},
                        {"Port", Integer.toString(c.getPort())},
                        {"Key", c.getKey()}};
                case HOST_RELAY -> new String[][]{
                        {"Connection", "Connect over server"},
                        {"IP", c.getString("publicIp").isEmpty() ? "your public IP (PC SETUP)" : c.getString("publicIp")},
                        {"Port", Integer.toString(c.getPort())},
                        {"Server key", c.getKey()}};
                case RELAY -> new String[][]{
                        {"Connection", "Connect over server"},
                        {"IP", c.getIp().isEmpty() ? "your relay server (PC SETUP)" : c.getIp()},
                        {"Port", Integer.toString(c.getPort())},
                        {"Server key", c.getKey()}};
            };
            tableHolder.removeAll();
            tableHolder.add(valueTable(rows), BorderLayout.CENTER);
            tableHolder.revalidate();
        }
    }

    static final class FlightControllerPage extends Page {
        private final MainWindow main;

        FlightControllerPage(MainWindow main) {
            this.main = main;
        }

        @Override
        String title() {
            return "Connect the flight controller";
        }

        @Override
        String hint() {
            return "Props off. Plug the phone into the flight controller's USB port with the OTG cable.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            WizardDialog.addRow(col, savedCheck(main, "guide.msp",
                    "Betaflight Configurator &rarr; <b>Receiver</b> tab &rarr; Receiver Mode <b>MSP RX input</b> (saved)"));
            WizardDialog.addRow(col, savedCheck(main, "guide.fc",
                    "The Flight app's <b>FC</b> status turns green and shows <b>Betaflight</b>"));
            WizardDialog.addRow(col, WizardDialog.subheading("What the FC status means"));
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "<b>Disconnected</b> before you tap Connect is normal. After Connect it goes <i>Device found</i> "
                            + "&rarr; <i>Permission requested</i> (tap <b>Allow</b> on the Android pop-up) &rarr; <i>Betaflight x.y.z</i>."));
            WizardDialog.addRow(col, WizardDialog.subheading("Still \"Disconnected\" after tapping Connect?"));
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "&bull; Do the FC's LEDs light up from the phone alone (no battery)? If not, the phone isn't powering it: cable problem.<br>"
                            + "&bull; Many flight controllers ignore a plain USB-C to USB-C cable. Use a <b>USB-A cable + a USB-C OTG adapter</b> on the phone.<br>"
                            + "&bull; Make sure it's a data cable - test it on the PC with Betaflight Configurator.<br>"
                            + "&bull; Some phones (OnePlus, Oppo, Realme, Xiaomi) have an <b>OTG</b> switch in Settings that turns itself off after a while.<br>"
                            + "&bull; Android normally asks \"Open DroidDrone Flight when this device is connected?\" - if it never does, Android doesn't see the FC.<br>"
                            + "&bull; Status <i>Permission denied</i>: unplug, replug, and tap Allow."));
            return col;
        }
    }

    /** Opens a real link to the phone without sending RC, and measures it. */
    static final class LinkTestPage extends Page {
        private final MainWindow main;
        private final CheckRow path = new CheckRow("Network path");
        private final CheckRow link = new CheckRow("DroidDrone link");
        private final CheckRow fc = new CheckRow("Flight controller");
        private final CheckRow latency = new CheckRow("Round-trip time (PC ↔ phone)");
        private JButton runButton;
        private boolean running;

        private record Result(Tailscale.PingResult ping, ControlLink.State state, FcInfo fc, List<Integer> rtts, String error) {}

        LinkTestPage(MainWindow main) {
            this.main = main;
        }

        @Override
        String title() {
            return "Test the link";
        }

        @Override
        String hint() {
            return "Put the phone on 4G (Wi-Fi off) with the Flight app connected, then run the test. No stick commands are sent.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            JPanel buttons = new JPanel(new BorderLayout());
            buttons.setOpaque(false);
            runButton = Theme.primaryButton("Run test");
            runButton.addActionListener(e -> run());
            buttons.add(runButton, BorderLayout.WEST);
            WizardDialog.addRow(col, buttons);
            WizardDialog.addRow(col, path);
            WizardDialog.addRow(col, link);
            WizardDialog.addRow(col, fc);
            WizardDialog.addRow(col, latency);
            for (CheckRow r : new CheckRow[]{path, link, fc, latency}) r.set(State.PENDING, "Not run yet.");
            return col;
        }

        @Override
        void onShow() {
            if (main.config().getLinkMode() != LinkMode.PHONE) path.set(State.INFO, "Only measured for Tailscale.");
        }

        private void run() {
            if (main.isLinkActive()) {
                link.set(State.WARN, "Disconnect in the main window first.");
                return;
            }
            running = true;
            runButton.setEnabled(false);
            boolean tailscale = main.config().getLinkMode() == LinkMode.PHONE;
            if (tailscale) path.set(State.PENDING, "Pinging the phone over Tailscale...");
            link.set(State.PENDING, tailscale ? "Waiting..." : "Connecting...");
            fc.set(State.PENDING, "Waiting...");
            latency.set(State.PENDING, "Waiting...");
            PcConfig config = main.config();
            background(() -> test(config, tailscale), this::show);
        }

        private Result test(PcConfig config, boolean tailscale) {
            Tailscale.PingResult ping = null;
            if (tailscale) {
                if (config.getIp().isEmpty()) return new Result(null, null, null, List.of(), "No phone address yet - see the Tailscale step.");
                ping = Tailscale.ping(config.getIp());
                SwingUtilities.invokeLater(() -> link.set(State.PENDING, "Connecting to the Flight app..."));
            }
            Connection connection = null;
            try {
                connection = Connection.open(config);
                ControlLink l = connection.getLink();
                long end = System.currentTimeMillis() + 20_000;
                while (System.currentTimeMillis() < end && l.getState() != ControlLink.State.READY
                        && l.getState() != ControlLink.State.VERSION_MISMATCH) {
                    Thread.sleep(100);
                }
                if (l.getState() != ControlLink.State.READY) return new Result(ping, l.getState(), null, List.of(), null);
                SwingUtilities.invokeLater(() -> link.set(State.OK, "Connected - measuring..."));
                List<Integer> rtts = new ArrayList<>();
                long until = System.currentTimeMillis() + 6000;
                while (System.currentTimeMillis() < until) {
                    int p = l.getPing();
                    if (p >= 0 && (rtts.isEmpty() || rtts.get(rtts.size() - 1) != p)) rtts.add(p);
                    Thread.sleep(250);
                }
                return new Result(ping, l.getState(), l.getFcInfo(), rtts, null);
            } catch (Exception e) {
                return new Result(ping, null, null, List.of(), e.getMessage());
            } finally {
                if (connection != null) connection.close();
            }
        }

        private void show(Result r) {
            running = false;
            runButton.setEnabled(true);
            runButton.setText("Run again");
            if (r == null) return;
            if (r.ping() != null) {
                Tailscale.PingResult p = r.ping();
                if (!p.reached()) {
                    path.set(State.BAD, "The phone didn't answer over Tailscale. Is Tailscale on and connected on the phone?");
                } else if (p.direct()) {
                    path.set(State.OK, "<b>Direct</b> (" + p.via() + "): typical " + p.medianMs() + " ms, best "
                            + p.bestMs() + " ms - the lowest-latency path.");
                } else {
                    path.set(State.WARN, "<b>Relayed</b> through Tailscale's " + p.via() + " server: typical " + p.medianMs()
                            + " ms. This adds latency; it often turns direct within a minute - run the test again. "
                            + "If it stays relayed, your 4G carrier blocks direct paths: consider hosting the relay on this PC.");
                }
            }
            if (r.error() != null) {
                link.set(State.BAD, r.error());
                fc.set(State.PENDING, "");
                latency.set(State.PENDING, "");
                return;
            }
            if (r.state() == ControlLink.State.VERSION_MISMATCH) {
                link.set(State.BAD, "Version mismatch - the phone needs DroidDrone Flight " + ApkShare.VERSION + ".");
            } else if (r.state() != ControlLink.State.READY) {
                link.set(State.BAD, "No answer from the Flight app within 20 s. Check it shows Connect tapped, the key ("
                        + main.config().getKey() + ") and the connection settings.");
            } else {
                link.set(State.OK, "The Flight app accepted the connection.");
            }
            if (r.fc() != null) {
                fc.set(r.fc().getFcVariant() == FcInfo.FC_VARIANT_BETAFLIGHT ? State.OK : State.WARN,
                        r.fc().getFcName() + " " + r.fc().getFcVersionStr());
            } else {
                fc.set(r.state() == ControlLink.State.READY ? State.BAD : State.PENDING, r.state() == ControlLink.State.READY
                        ? "The phone isn't talking to the flight controller - see the previous step." : "");
            }
            if (r.rtts().isEmpty()) {
                latency.set(State.PENDING, "");
            } else {
                List<Integer> sorted = new ArrayList<>(r.rtts());
                Collections.sort(sorted);
                int median = sorted.get(sorted.size() / 2);
                int worst = sorted.get(sorted.size() - 1);
                State s = median < 80 ? State.OK : median < 150 ? State.WARN : State.BAD;
                String verdict = median < 80 ? "Great." : median < 150 ? "Flyable - stay gentle and close." : "Too slow to fly safely.";
                latency.set(s, "Typical <b>" + median + " ms</b>, worst " + worst + " ms. " + verdict
                        + " Stick commands take about half the round trip.");
                main.logLine("Link test: RTT median " + median + " ms, worst " + worst + " ms.");
            }
        }

        @Override
        boolean canContinue() {
            return !running;
        }

        @Override
        String status() {
            return running ? "Testing..." : null;
        }
    }
}
