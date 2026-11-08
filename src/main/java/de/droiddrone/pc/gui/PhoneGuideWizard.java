package de.droiddrone.pc.gui;

import de.droiddrone.common.FcInfo;
import de.droiddrone.pc.AppPaths;
import de.droiddrone.pc.Connection;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.Names;
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

    /** Phone setup's steps. The first-time setup is made of these too (SetupWizard): a step added here is in both. */
    static List<Page> pages(MainWindow main) {
        return List.of(
                new PhoneTailscalePage(main),
                new FlightAppPage(main),
                new PhoneSettingsPage(main),
                new ConnectPage(main));
    }

    static void open(MainWindow main) {
        new WizardDialog(main, "Phone setup", pages(main), () -> {
            main.save(Map.of("setup.phoneDone", "true"));
            main.logLine("Phone setup finished.");
        }).setVisible(true);
        main.reloadSettings();
    }

    /** A page of the first-time guide (SetupWizard gives its pages a section; Phone setup's own have none). */
    private static boolean inFirstTimeGuide(Page page) {
        return !page.section.isEmpty();
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
        String title() {
            return "Tailscale on the phone";
        }

        @Override
        String hint() {
            return "Scan with the phone's camera to get Tailscale.";
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
                    "Install <b>Tailscale</b>, sign in with <b>the same account</b> as the PC, allow the VPN. "
                            + "The phone appears below."));
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
                if (!updatingChoice) wizard.update();
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
                pc.set(State.BAD, "Not signed in - " + (inFirstTimeGuide() ? "go Back to the Tailscale step."
                        : "do SETUP > PC setup first."));
                return;
            }
            pc.set(State.OK, s.selfName() + " - " + s.selfIp());
            List<Tailscale.Peer> androids = new ArrayList<>(s.androidPeers());
            androids.sort((a, b) -> Boolean.compare(b.online(), a.online()));
            updatingChoice = true;
            Object selected = phoneChoice.getSelectedItem();
            phones.removeAllElements();
            androids.forEach(phones::addElement);
            Tailscale.Peer current = selected instanceof Tailscale.Peer p && androids.contains(p) ? p : null;
            if (current == null && !androids.isEmpty()) current = androids.get(0);
            phones.setSelectedItem(current);
            phoneChoice.setVisible(androids.size() > 1);
            updatingChoice = false;
            if (current == null) {
                phone.set(State.PENDING, "Waiting for the phone on Tailscale...");
            } else {
                phone.set(current.online() ? State.OK : State.WARN, "<b>" + current.hostName() + "</b> - " + current.ip()
                        + (current.online() ? " - online."
                        : " - offline: switch Tailscale on on the phone."));
            }
            wizard.update();
        }

        private boolean inFirstTimeGuide() {
            return PhoneGuideWizard.inFirstTimeGuide(this);
        }


        @Override
        String status() {
            return phone.getState() == State.OK ? "Phone found." : "Waiting for the phone on Tailscale.";
        }
    }

    /** Download + verify the Flight app on the PC, then serve it to the phone via a QR code. */
    static final class FlightAppPage extends Page {
        private final MainWindow main;
        private final QrView qr = new QrView(220);
        private final CheckRow file = new CheckRow("DRAUPNE Flight " + ApkShare.VERSION);
        private final CheckRow firewall = new CheckRow("Firewall");
        private final DefaultComboBoxModel<String> urls = new DefaultComboBoxModel<>();
        private final JComboBox<String> urlChoice = new JComboBox<>(urls);
        private ApkShare share;
        /** This step is on screen: a download that finishes after it was left must not start the server. */
        private boolean showing;
        private boolean preparing;
        /** A phone with DRAUPNE Flight open answered the name service: the app is on it (1.0.35). */
        private boolean installed;
        private long nextCheck;

        FlightAppPage(MainWindow main) {
            this.main = main;
        }

        @Override
        String title() {
            return "Install the mobile app";
        }

        @Override
        String hint() {
            return "Scan to download <b>DRAUPNE Flight</b>.";
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
                    "On the phone: tap <b>Download the app</b>, open the file, allow installing if asked, <b>Install</b>.<br>"
                            + "Phone must be on Tailscale or this PC's Wi-Fi. "
                            + "Link fails? Pick another address under the code."));
            WizardDialog.addRow(col, file);
            firewall.setVisible(false);
            WizardDialog.addRow(col, firewall);
            page.add(col, BorderLayout.CENTER);
            file.set(State.PENDING, "Preparing...");
            firewall.set(State.PENDING, "Checking...");
            main.nameService().scanSoon();
            return page;
        }

        @Override
        void onShow() {
            background(() -> WindowsSetup.firewallRuleExists(WindowsSetup.APK_RULE), ok -> {
                firewall.setVisible(!Boolean.TRUE.equals(ok)); // shown only when it's missing
                firewall.set(State.WARN, "No firewall rule - Windows may block the phone. "
                        + (inFirstTimeGuide(this) ? "Go Back to the firewall step." : "SETUP > PC setup adds it."));
            });
            showing = true;
            if (share != null || preparing) return;
            preparing = true;
            file.set(State.PENDING, "Downloading (about 7 MB)...");
            background(() -> {
                try {
                    Path apk = ApkShare.prepareApk(AppPaths.downloadsDir());
                    ApkShare s = new ApkShare();
                    s.start(apk);
                    return s;
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> file.set(State.BAD, "App not ready: " + e.getMessage()));
                    return null;
                }
            }, s -> {
                preparing = false;
                if (s == null) return;
                if (!showing) { // left (or the guide closed) while it downloaded: no server left behind
                    s.close();
                    return;
                }
                share = s;
                // neutral, not green: this PC can't see whether the phone has installed it (the Connect step can)
                file.set(State.INFO, "Ready to download (SHA-256 checked).");
                List<String> list = ApkShare.urls();
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
            showing = false;
            if (share != null) share.close(); // only serve while this step is open
            share = null;
        }

        /** Every 2.5 s: a phone that answers by name has the app open - the row turns green by itself. */
        @Override
        void refresh() {
            if (System.currentTimeMillis() < nextCheck) return;
            nextCheck = System.currentTimeMillis() + 2500;
            Names.Entry online = null;
            for (Names.Entry e : main.names().list(Names.Kind.PHONE)) if (e.online()) online = e;
            if (online == null) return;
            if (!installed) main.nameService().scanSoon();
            installed = true;
            file.set(State.OK, "<b>" + online.name() + "</b>: DRAUPNE Flight " + online.version() + " open.");
            wizard.update();
        }

        @Override
        String status() {
            return installed ? "DRAUPNE Flight is on the phone." : "Waiting for DRAUPNE Flight to open on the phone...";
        }
    }

    static final class PhoneSettingsPage extends Page {
        private final MainWindow main;

        PhoneSettingsPage(MainWindow main) {
            this.main = main;
        }

        @Override
        String title() {
            return "Settings on the phone";
        }

        @Override
        String hint() {
            return "DRAUPNE Flight shows a yellow line per thing to fix. Two it can't check are below.";
        }

        /** Three boxes the pilot ticks; the phone app watches everything else and says so in yellow. */
        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            WizardDialog.addRow(col, setting("yellow", "Yellow lines in DRAUPNE Flight: tap each, allow",
                    "Tick this when no yellow line is left."));
            WizardDialog.addRow(col, setting("vpn", "Tailscale: Always-on VPN (recommended)",
                    "Keeps Tailscale up after a restart: Settings > Network &amp; internet > VPN > gear by Tailscale > "
                            + "<b>Always-on VPN</b>."));
            WizardDialog.addRow(col, setting("otg", "OTG switched on (some phones)",
                    "Some phones have an <b>OTG</b> switch in Settings: turn it on just before plugging in "
                            + "(it times out). None: skip."));
            return col;
        }

        /** A box the pilot ticks when it's set on the phone; remembered for the next time this guide is open. */
        private CheckRow setting(String key, String title, String how) {
            String saveKey = "setup.phoneSetting." + key;
            return new CheckRow(title).tickable(how, "true".equalsIgnoreCase(main.config().getString(saveKey)),
                    done -> main.save(Map.of(saveKey, Boolean.toString(done))));
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

        /**
         * @param stateText what the link is doing, in words (Connection.describe)
         * @param spectator the phone named another session as the pilot, so this one became a spectator
         * @param stats     the video pipeline's figures (null before the stream)
         * @param hasStream the phone's video settings have arrived (frames can be decoded)
         * @param event     the link's last event line, or null
         */
        private record Snap(ControlLink.State state, FcInfo fc, int ping, String stateText, boolean spectator,
                            de.droiddrone.pc.video.VideoPipeline.Stats stats, boolean hasStream, String event) {
            Snap(ControlLink.State state, FcInfo fc, int ping) {
                this(state, fc, ping, "", false, null, false, null);
            }
        }

        private final MainWindow main;
        private final CheckRow fc = new CheckRow("Flight controller");
        private final CheckRow camera = new CheckRow("Camera");
        /** Round-trip time and Tailscale path in one row (1.0.35): the two halves kept apart, shown together. */
        private final CheckRow quality = new CheckRow("Link quality");
        private State phoneState = State.PENDING, linkState, latencyState = State.PENDING, pathState;
        private String phoneText = "", linkText = "", latencyText = "", pathText = "";
        /** The phone to fly by its DRAUPNE name (1.0.35): the name map's phones, kept fresh while the step shows. */
        private final CheckRow phoneRow = new CheckRow("Phone");
        private final DefaultComboBoxModel<Names.Entry> phones = new DefaultComboBoxModel<>();
        private final JComboBox<Names.Entry> phoneChoice = new JComboBox<>(phones);
        private boolean fillingPhones;
        private long nextPhones;
        /** The live picture, small, for the camera check: is it the right way up? */
        private final VideoView preview = new VideoView();
        private boolean flipped;
        private final List<Integer> rtts = new ArrayList<>();
        /** The running session's stop flag; a new one per session, so an old session can't touch the page. */
        private AtomicBoolean stop;
        /** A session told to stop is still closing its link: the next one waits for the port. */
        private volatile boolean closing;
        /** The link's last event line (setEvents), shown with the link row and logged. */
        private volatile String lastEvent;
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
            return "Connect DRAUPNE Flight";
        }

        @Override
        String hint() {
            return "Open DRAUPNE Flight on the phone. Plug it into the drone with a USB data cable (props off).";
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            // the phone, by name, with the link's state on the same row
            phoneChoice.setRenderer(new javax.swing.DefaultListCellRenderer() {
                @Override
                public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                                                                       int index, boolean selected, boolean focus) {
                    super.getListCellRendererComponent(list, value, index, selected, focus);
                    if (value instanceof Names.Entry p) setText(p.name() + "  " + p.detail() + (p.online() ? "" : "  (offline)"));
                    return this;
                }
            });
            phoneChoice.addActionListener(e -> {
                if (!fillingPhones && phoneChoice.getSelectedItem() instanceof Names.Entry p) choosePhone(p);
            });
            phoneChoice.setVisible(false);
            phoneRow.addComponent(phoneChoice);
            WizardDialog.addRow(col, phoneRow);
            WizardDialog.addRow(col, WizardDialog.paragraph("On the USB prompt tick <b>Always allow</b> (or <b>Use by default</b>), tap <b>OK</b>."));
            WizardDialog.addRow(col, fc);
            // the camera check next to its picture, FLIP on the row
            JPanel cam = new JPanel(new BorderLayout(18, 0));
            cam.setOpaque(false);
            camera.addAction("Flip", this::flip).setToolTipText("Turn the picture 180 degrees (VIDEO > Camera upside down)");
            cam.add(camera, BorderLayout.CENTER);
            preview.setPreferredSize(new java.awt.Dimension(320, 180));
            preview.setIdleText("THE PICTURE SHOWS HERE");
            cam.add(preview, BorderLayout.EAST);
            WizardDialog.addRow(col, cam);
            quality.addAction("Check again", this::checkPath);
            WizardDialog.addRow(col, quality);
            return col;
        }

        /** The phone row: the phone's line, and the link's under it; the link's light once the link has something to say. */
        private void setPhone(State state, String text) {
            phoneState = state;
            phoneText = text;
            renderPhone();
        }

        private void setLink(State state, String text) {
            linkState = state;
            linkText = text;
            renderPhone();
        }

        private void renderPhone() {
            phoneRow.set(linkState != null ? linkState : phoneState, phoneText + (linkText.isEmpty() ? "" : "<br>" + linkText));
        }

        /** The quality row: the round trip's verdict and the Tailscale path together; red if either is bad. */
        private void renderQuality() {
            State state = pathState == State.BAD ? State.BAD : latencyState == State.PENDING ? State.PENDING
                    : pathState == State.WARN && latencyState == State.OK ? State.WARN : latencyState;
            quality.set(state, latencyText + (pathText.isEmpty() ? "" : (latencyText.isEmpty() ? "" : "<br>") + pathText));
        }

        /** The camera check's FLIP: the setting the main window uses, so the phone stays as it is mounted. */
        private void flip() {
            if (main == null) return;
            main.setUpsideDown(!main.isUpsideDown());
            preview.setUpsideDown(main.isUpsideDown());
            flipped = true;
            camera.set(State.OK, "Turned " + (main.isUpsideDown() ? "upside down" : "the right way up") + ". Wrong? Flip again.");
        }

        /** The name map's phones into the list; the chosen one shown, the first one seen taken. */
        private void fillPhones() {
            List<Names.Entry> list = main.names().list(Names.Kind.PHONE);
            String chosenName = main.config().getString(Names.PHONE_NAME_KEY);
            Names.Entry chosen = null;
            fillingPhones = true;
            try {
                phones.removeAllElements();
                for (Names.Entry e : list) {
                    phones.addElement(e);
                    if (e.name().equalsIgnoreCase(chosenName)) chosen = e;
                }
                if (chosen == null && !list.isEmpty()) chosen = list.get(0);
                phones.setSelectedItem(chosen);
                phoneChoice.setVisible(list.size() > 1);
            } finally {
                fillingPhones = false;
            }
            if (chosen != null && !chosen.name().equalsIgnoreCase(chosenName)) choosePhone(chosen);
            if (chosen == null) {
                setPhone(State.PENDING, "Waiting for DRAUPNE Flight on Tailscale...");
            } else {
                setPhone(chosen.online() ? State.OK : State.WARN, "<b>" + chosen.name() + "</b> - " + chosen.detail()
                        + (chosen.online() ? " - online." : " - last seen " + Names.agoText(chosen.lastSeen(), System.currentTimeMillis()) + ": open DRAUPNE Flight."));
            }
        }

        /** The phone picked: saved as the link's; a session waiting on another phone starts again. */
        private void choosePhone(Names.Entry e) {
            main.save(Map.of(Names.PHONE_NAME_KEY, e.name(), "ip", e.ip() == null ? "" : e.ip()));
            main.logLine("Phone: " + e.name() + (e.ip() == null ? "" : " (" + e.ip() + ")") + ".");
            if (stop != null) {
                stop.set(true);
                closing = true;
                stop = null;
            }
            nextStart = System.currentTimeMillis() + 600;
        }

        @Override
        void onShow() {
            nextPhones = 0;
            main.nameService().scanSoon();
            readySince = 0;
            waitingSince = System.currentTimeMillis();
            rtts.clear();
            logged = false;
            pathChecked = false;
            nextStart = 0;
            flipped = false;
            lastEvent = null;
            preview.setUpsideDown(main.isUpsideDown());
            // the preview's picture size follows its on-screen size: told now, so the first frame isn't converted for 0 x 0
            SwingUtilities.invokeLater(() -> {
                preview.revalidate();
                preview.dispatchEvent(new java.awt.event.ComponentEvent(preview, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
            });
            setLink(State.PENDING, "Waiting for DRAUPNE Flight...");
            for (CheckRow r : new CheckRow[]{fc, camera}) r.set(State.PENDING, "");
            linkState = null;
            linkText = "";
            latencyState = State.PENDING;
            pathState = null;
            latencyText = pathText = "";
            renderQuality();
        }

        @Override
        void refresh() {
            if (System.currentTimeMillis() >= nextPhones) {
                nextPhones = System.currentTimeMillis() + 2500;
                fillPhones();
            }
            if (stop == null && System.currentTimeMillis() >= nextStart) start();
        }

        private void start() {
            if (closing) { // the last session still has the port: a moment
                nextStart = System.currentTimeMillis() + 500;
                return;
            }
            if (main.isLinkActive()) {
                setLink(State.WARN, "Main window is connected. Press DISCONNECT there.");
                nextStart = System.currentTimeMillis() + 1000;
                return;
            }
            PcConfig config = main.config();
            if (config.getIp().isEmpty()) {
                setLink(State.BAD, "No phone yet: open DRAUPNE Flight on it.");
                nextStart = System.currentTimeMillis() + 2000;
                return;
            }
            AtomicBoolean s = new AtomicBoolean();
            stop = s;
            main.logLine("Connect step: opening link.");
            background(() -> session(config, s), error -> {
                if (stop != s) return;
                stop = null;
                if (error != null) {
                    boolean reconnect = error.startsWith(RECONNECT);
                    setLink(reconnect ? State.WARN : State.BAD, error);
                    main.logLine("Connect step: " + error);
                    nextStart = System.currentTimeMillis() + 3000;
                }
            });
        }

        private static final String RECONNECT = "Phone has another pilot session";

        /** The link's event lines: to the app log, and the last one with the link row. */
        private void event(String line) {
            lastEvent = line;
            main.logLine("Connect step: " + line);
        }

        /**
         * Holds the link open until the page is left, reporting its state four times a second; the video to the preview.
         * @return null when stopped from the page; otherwise why it ended (shown, and a new session follows)
         */
        private String session(PcConfig config, AtomicBoolean s) {
            Connection connection = null;
            ControlLink[] linkRef = new ControlLink[1];
            de.droiddrone.pc.video.VideoPipeline video = new de.droiddrone.pc.video.VideoPipeline(preview,
                    () -> linkRef[0] == null ? -1 : linkRef[0].getPing());
            main.nameService().pause(); // the link takes the port
            try {
                connection = Connection.open(config, video);
                ControlLink l = connection.getLink();
                linkRef[0] = l;
                l.setNameReply(main.nameService()::replyPacket);
                l.setEvents(this::event);
                video.attach(l);
                long spectatorSince = 0;
                while (!s.get()) {
                    ControlLink.State state = l.getState();
                    boolean spectator = l.isSpectator() || state == ControlLink.State.SPECTATING || state == ControlLink.State.SPECTATOR_WAITING;
                    if (spectator) { // the phone still had an older session as the pilot: this one is told so, then tries afresh
                        if (spectatorSince == 0) spectatorSince = System.currentTimeMillis();
                        if (System.currentTimeMillis() - spectatorSince > 2000) return RECONNECT + " - reconnecting...";
                    }
                    Snap snap = new Snap(state, l.getFcInfo(), l.getPing(), connection.describe(state), spectator, video.stats(),
                            video.hasStream(), lastEvent);
                    SwingUtilities.invokeLater(() -> {
                        if (stop == s) show(snap);
                    });
                    Thread.sleep(250);
                }
                return null;
            } catch (Exception e) {
                return "Link failed: " + e.getMessage();
            } finally {
                if (connection != null) connection.close();
                main.nameService().resume();
                video.close();
                closing = false;
                main.logLine("Connect step: link closed.");
                SwingUtilities.invokeLater(preview::clear);
            }
        }

        private void show(Snap snap) {
            long now = System.currentTimeMillis();
            if (snap.state() == ControlLink.State.VERSION_MISMATCH) {
                setLink(State.BAD, "Wrong DRAUPNE Flight version - needs " + ApkShare.VERSION
                        + ". Tap <b>Update available</b> on the phone, or install it from the app step (Back).");
                return;
            }
            String event = snap.event() == null ? "" : "<br>" + snap.event();
            if (snap.spectator()) {
                setLink(State.WARN, RECONNECT + " - reconnecting..." + event);
                return;
            }
            if (snap.state() != ControlLink.State.READY) {
                if (readySince != 0) { // it was up and dropped: start over
                    readySince = 0;
                    waitingSince = now;
                    rtts.clear();
                    logged = false;
                    for (CheckRow r : new CheckRow[]{fc, camera}) r.set(State.PENDING, "");
                    latencyState = State.PENDING;
                    latencyText = "";
                    renderQuality();
                }
                boolean configuring = snap.state() == ControlLink.State.CONFIGURING;
                setLink(now - waitingSince < HINT_AFTER_MS || configuring ? State.PENDING : State.WARN, (configuring
                        ? "Phone answered, sending settings..."
                        : now - waitingSince < HINT_AFTER_MS ? "Waiting - is DRAUPNE Flight open?"
                        : "Still waiting. Check: DRAUPNE Flight open, same key (Advanced)"
                        + ", Tailscale on.")
                        + "<br><i>" + snap.stateText() + "</i>" + event);
                return;
            }
            if (readySince == 0) readySince = now;
            setLink(State.OK, "Connected." + event);
            if (snap.fc() != null) {
                fc.set(snap.fc().getFcVariant() == FcInfo.FC_VARIANT_BETAFLIGHT ? State.OK : State.WARN,
                        snap.fc().getFcName() + " " + snap.fc().getFcVersionStr());
            } else if (now - readySince > 3000) {
                fc.set(State.BAD, "No flight controller. Tap <b>Allow</b> on the phone's USB prompt. "
                        + "Try another data cable. "
                        + "Check OTG.");
            } else {
                fc.set(State.PENDING, "Asking...");
            }
            de.droiddrone.pc.video.VideoPipeline.Stats stats = snap.stats();
            if (flipped) {
                // the FLIP line stays
            } else if (stats != null && stats.error() != null) {
                camera.set(State.BAD, "Can't decode video: " + stats.error());
            } else if (preview.hasPicture()) {
                camera.set(State.INFO, "Sky at the top? If not, press <b>Flip</b>.");
            } else if (!snap.hasStream()) {
                camera.set(now - readySince > 4000 ? State.WARN : State.PENDING, "No video yet."
                        + (now - readySince > 4000 ? " Allow camera access on the phone." : ""));
            } else if (stats != null && stats.receivedFps() > 0) {
                camera.set(State.PENDING, "Frames arriving (" + stats.width() + "x" + stats.height() + ", " + stats.receivedFps() + " fps), decoding...");
            } else {
                camera.set(now - readySince > 6000 ? State.WARN : State.PENDING, "Video settings received (" + (stats == null ? "" : stats.width()
                        + "x" + stats.height() + ", ") + "waiting for frames...");
            }
            if (snap.ping() >= 0 && (rtts.isEmpty() || rtts.get(rtts.size() - 1) != snap.ping())) rtts.add(snap.ping());
            showLatency(now - readySince >= MEASURE_MS);
            if (!pathChecked) {
                pathChecked = true;
                checkPath();
            }
        }

        private void showLatency(boolean measured) {
            if (rtts.isEmpty()) {
                latencyState = State.PENDING;
                latencyText = "Measuring...";
                renderQuality();
                return;
            }
            List<Integer> sorted = new ArrayList<>(rtts);
            Collections.sort(sorted);
            int median = sorted.get(sorted.size() / 2);
            int worst = sorted.get(sorted.size() - 1);
            if (!measured) {
                latencyState = State.PENDING;
                latencyText = "Measuring... " + median + " ms so far.";
                renderQuality();
                return;
            }
            latencyState = median < 80 ? State.OK : median < 150 ? State.WARN : State.BAD;
            String verdict = median < 80 ? "Great." : median < 150 ? "Flyable - gentle and close." : "Too slow to fly safely.";
            latencyText = "Round trip <b>" + median + " ms</b>, worst " + worst + " ms. " + verdict;
            renderQuality();
            if (!logged) {
                logged = true;
                main.logLine("Link test: RTT median " + median + " ms, worst " + worst + " ms.");
            }
        }

        private void checkPath() {
            String ip = main.config().getIp();
            if (ip.isEmpty()) return;
            pathState = State.PENDING;
            pathText = "Pinging...";
            renderQuality();
            background(() -> Tailscale.ping(ip), p -> {
                if (p == null || !p.reached()) {
                    pathState = State.BAD;
                    pathText = "No answer. Is Tailscale on on the phone?";
                } else if (p.direct()) {
                    pathState = State.OK;
                    pathText = "Path <b>direct</b> (" + p.via() + ").";
                } else {
                    pathState = State.WARN;
                    pathText = "Path <b>relayed</b> via " + p.via() + ": more delay. Often turns direct in a minute - "
                            + "Check again.";
                }
                renderQuality();
            });
        }

        @Override
        void onHide() {
            if (stop != null) {
                stop.set(true); // the session closes the link; the next one waits for that
                closing = true;
            }
            stop = null;
        }

        @Override
        String status() {
            return readySince != 0 ? "Connected to the drone's phone." : "Waiting for DRAUPNE Flight.";
        }
    }
}
