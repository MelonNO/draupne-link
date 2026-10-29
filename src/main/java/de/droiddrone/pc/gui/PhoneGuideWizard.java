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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phone setup, for the phone that flies on the drone. Where the PC can see the result it checks it live
 * (phone on Tailscale, the link to the Flight app); the rest is a short list of what to do on the phone.
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
                new ConnectPage(main));
        new WizardDialog(main, "Phone setup", pages, () -> {
            main.save(Map.of("setup.phoneDone", "true"));
            main.logLine("Phone setup finished.");
        }).setVisible(true);
        main.reloadSettings();
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
            return "This phone rides on the drone: USB to the flight controller, 4G to this PC.";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            WizardDialog.addRow(col, WizardDialog.subheading("Have ready"));
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "&bull; An Android phone (8 or newer) with mobile data<br>"
                            + "&bull; A USB OTG adapter from the phone to the flight controller"));
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
                    "Install <b>Tailscale</b>, sign in with <b>the same account</b> as on this PC, and allow the VPN. "
                            + "The phone appears below by itself."));
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
                pc.set(State.BAD, "Not signed in - finish the Tailscale step in SETUP &rarr; PC setup first.");
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
            return "Scan the code with the phone's camera.";
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
                    "Tap <b>Download the app</b> and open the file. If Android asks, allow installing from your browser, then <b>Install</b>. "
                            + "Then press Next.<br>"
                            + "Link doesn't open? Pick another address under the code."));
            WizardDialog.addRow(col, file);
            firewall.setVisible(false);
            WizardDialog.addRow(col, firewall);
            page.add(col, BorderLayout.CENTER);
            file.set(State.PENDING, "Preparing...");
            firewall.set(State.PENDING, "Checking...");
            return page;
        }

        @Override
        void onShow() {
            background(() -> WindowsSetup.firewallRuleExists(WindowsSetup.APK_RULE), ok -> {
                firewall.setVisible(!Boolean.TRUE.equals(ok)); // shown only when it's missing
                firewall.set(State.WARN, "No firewall rule yet - Windows may block the phone. SETUP &rarr; PC setup adds it.");
            });
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
            return "Android pauses apps in the background to save battery. In the air that means a failsafe. On the phone:";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            String muted = "<span style='color:" + Theme.MUTED_HEX + "'>";
            WizardDialog.addRow(col, WizardDialog.paragraph(
                    "&bull; <b>Battery: Unrestricted</b> for DroidDrone Flight and Tailscale " + muted + "(Settings &rarr; Apps &rarr; the app &rarr; Battery)</span><br>"
                            + "&bull; <b>Unrestricted mobile data</b> for both apps " + muted + "(Settings &rarr; Apps &rarr; the app &rarr; Mobile data)</span><br>"
                            + (main.config().getLinkMode() == LinkMode.PHONE
                            ? "&bull; <b>Tailscale: Always-on VPN</b> " + muted + "(Settings &rarr; Network &amp; internet &rarr; VPN &rarr; Tailscale &#9881;)</span><br>" : "")
                            + "&bull; <b>Wi-Fi off</b> before flying, so the phone stays on 4G"));
            return col;
        }
    }

    /**
     * The Flight app's settings, with the live link to it right below: the connection is held open while this step is
     * shown, so the pilot sees it turn green the moment CONNECT is tapped on the phone. No stick commands are sent.
     */
    static final class ConnectPage extends Page {
        /** Round trips collected after the link comes up, before the verdict. */
        private static final long MEASURE_MS = 6000;
        /** Waiting this long without a link adds the usual reasons. */
        private static final long HINT_AFTER_MS = 20_000;

        private record Snap(ControlLink.State state, FcInfo fc, int ping) {}

        private final MainWindow main;
        private final JPanel tableHolder = new JPanel(new BorderLayout());
        private final CheckRow link = new CheckRow("DroidDrone link");
        private final CheckRow fc = new CheckRow("Flight controller");
        private final CheckRow latency = new CheckRow("Round-trip time");
        private final CheckRow path = new CheckRow("Network path");
        private final List<Integer> rtts = new ArrayList<>();
        /** The running session's stop flag; a new one per session, so an old session can't touch the page. */
        private AtomicBoolean stop;
        private long nextStart;
        private long waitingSince;
        private long readySince;
        private boolean logged;
        private boolean pathChecked;

        ConnectPage(MainWindow main) {
            this.main = main;
        }

        @Override
        String title() {
            return "Connect the Flight app";
        }

        @Override
        String hint() {
            return "On the phone, open DroidDrone Flight, enter this and tap <b>CONNECT</b>. "
                    + "Plug the phone into the flight controller with the OTG adapter (props off).";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            tableHolder.setOpaque(false);
            WizardDialog.addRow(col, tableHolder);
            WizardDialog.addRow(col, link);
            WizardDialog.addRow(col, fc);
            WizardDialog.addRow(col, latency);
            path.addAction("Check again", this::checkPath);
            WizardDialog.addRow(col, path);
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
                        {"IP", c.getString("publicIp").isEmpty() ? "your public IP (SETUP > PC setup)" : c.getString("publicIp")},
                        {"Port", Integer.toString(c.getPort())},
                        {"Server key", c.getKey()}};
                case RELAY -> new String[][]{
                        {"Connection", "Connect over server"},
                        {"IP", c.getIp().isEmpty() ? "your relay server (SETUP > PC setup)" : c.getIp()},
                        {"Port", Integer.toString(c.getPort())},
                        {"Server key", c.getKey()}};
            };
            tableHolder.removeAll();
            tableHolder.add(valueTable(rows), BorderLayout.CENTER);
            tableHolder.revalidate();
            path.setVisible(c.getLinkMode() == LinkMode.PHONE); // only Tailscale can tell direct from relayed
            readySince = 0;
            waitingSince = System.currentTimeMillis();
            rtts.clear();
            logged = false;
            pathChecked = false;
            nextStart = 0;
            link.set(State.PENDING, "Waiting for the Flight app...");
            for (CheckRow r : new CheckRow[]{fc, latency, path}) r.set(State.PENDING, "");
        }

        @Override
        void refresh() {
            if (stop == null && System.currentTimeMillis() >= nextStart) start();
        }

        private void start() {
            if (main.isLinkActive()) {
                link.set(State.WARN, "The main window is connected. Press DISCONNECT there and the link opens here.");
                nextStart = System.currentTimeMillis() + 1000;
                return;
            }
            PcConfig config = main.config();
            if (config.getLinkMode() == LinkMode.PHONE && config.getIp().isEmpty()) {
                link.set(State.BAD, "No phone address yet - finish the Tailscale on the phone step.");
                nextStart = System.currentTimeMillis() + 2000;
                return;
            }
            AtomicBoolean s = new AtomicBoolean();
            stop = s;
            background(() -> session(config, s), error -> {
                if (stop != s) return;
                stop = null;
                if (error != null) {
                    link.set(State.BAD, error);
                    nextStart = System.currentTimeMillis() + 3000;
                }
            });
        }

        /** Holds the link open until the page is left, reporting its state four times a second. */
        private String session(PcConfig config, AtomicBoolean s) {
            Connection connection = null;
            try {
                connection = Connection.open(config);
                ControlLink l = connection.getLink();
                while (!s.get()) {
                    Snap snap = new Snap(l.getState(), l.getFcInfo(), l.getPing());
                    SwingUtilities.invokeLater(() -> {
                        if (stop == s) show(snap);
                    });
                    Thread.sleep(250);
                }
                return null;
            } catch (Exception e) {
                return "Could not open the link: " + e.getMessage();
            } finally {
                if (connection != null) connection.close();
            }
        }

        private void show(Snap snap) {
            long now = System.currentTimeMillis();
            if (snap.state() == ControlLink.State.VERSION_MISMATCH) {
                link.set(State.BAD, "Version mismatch - the phone needs DroidDrone Flight " + ApkShare.VERSION + ".");
                return;
            }
            if (snap.state() != ControlLink.State.READY) {
                if (readySince != 0) { // it was up and dropped: start over
                    readySince = 0;
                    waitingSince = now;
                    rtts.clear();
                    logged = false;
                    for (CheckRow r : new CheckRow[]{fc, latency}) r.set(State.PENDING, "");
                }
                link.set(now - waitingSince < HINT_AFTER_MS ? State.PENDING : State.WARN, now - waitingSince < HINT_AFTER_MS
                        ? "Waiting for the Flight app - tap <b>CONNECT</b> on the phone."
                        : "Still waiting. Check the phone shows exactly the values above, that CONNECT is tapped"
                        + (main.config().getLinkMode() == LinkMode.PHONE ? ", and that Tailscale is on." : "."));
                return;
            }
            if (readySince == 0) readySince = now;
            link.set(State.OK, "Connected.");
            if (snap.fc() != null) {
                fc.set(snap.fc().getFcVariant() == FcInfo.FC_VARIANT_BETAFLIGHT ? State.OK : State.WARN,
                        snap.fc().getFcName() + " " + snap.fc().getFcVersionStr());
            } else if (now - readySince > 3000) {
                fc.set(State.BAD, "The phone isn't talking to the flight controller. Tap Allow on the phone's USB pop-up. Many flight "
                        + "controllers ignore USB-C to USB-C: use a USB-A cable with an OTG adapter, and check the phone's OTG setting.");
            } else {
                fc.set(State.PENDING, "Asking the flight controller...");
            }
            if (snap.ping() >= 0 && (rtts.isEmpty() || rtts.get(rtts.size() - 1) != snap.ping())) rtts.add(snap.ping());
            showLatency(now - readySince >= MEASURE_MS);
            if (!pathChecked && path.isVisible()) {
                pathChecked = true;
                checkPath();
            }
        }

        private void showLatency(boolean measured) {
            if (rtts.isEmpty()) {
                latency.set(State.PENDING, "Measuring...");
                return;
            }
            List<Integer> sorted = new ArrayList<>(rtts);
            Collections.sort(sorted);
            int median = sorted.get(sorted.size() / 2);
            int worst = sorted.get(sorted.size() - 1);
            if (!measured) {
                latency.set(State.PENDING, "Measuring... typical " + median + " ms so far.");
                return;
            }
            State s = median < 80 ? State.OK : median < 150 ? State.WARN : State.BAD;
            String verdict = median < 80 ? "Great." : median < 150 ? "Flyable - stay gentle and close." : "Too slow to fly safely.";
            latency.set(s, "Typical <b>" + median + " ms</b>, worst " + worst + " ms. " + verdict
                    + " Stick commands take about half the round trip.");
            if (!logged) {
                logged = true;
                main.logLine("Link test: RTT median " + median + " ms, worst " + worst + " ms.");
            }
        }

        private void checkPath() {
            String ip = main.config().getIp();
            if (ip.isEmpty()) return;
            path.set(State.PENDING, "Pinging the phone over Tailscale...");
            background(() -> Tailscale.ping(ip), p -> {
                if (p == null || !p.reached()) {
                    path.set(State.BAD, "The phone didn't answer over Tailscale. Is Tailscale on and connected on the phone?");
                } else if (p.direct()) {
                    path.set(State.OK, "<b>Direct</b> (" + p.via() + "): typical " + p.medianMs() + " ms, best "
                            + p.bestMs() + " ms - the lowest-latency path.");
                } else {
                    path.set(State.WARN, "<b>Relayed</b> through Tailscale's " + p.via() + " server: typical " + p.medianMs()
                            + " ms. This adds latency; it often turns direct within a minute - press Check again. "
                            + "If it stays relayed, your 4G carrier blocks direct paths: consider hosting the relay on this PC.");
                }
            });
        }

        @Override
        void onHide() {
            if (stop != null) stop.set(true); // the session closes the link
            stop = null;
        }

        @Override
        String status() {
            return readySince != 0 ? "Connected to the drone's phone." : "Waiting for the Flight app.";
        }
    }
}
