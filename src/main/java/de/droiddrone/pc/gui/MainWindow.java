package de.droiddrone.pc.gui;

import de.droiddrone.common.FcInfo;
import de.droiddrone.common.SettingsCommon;
import de.droiddrone.pc.Connection;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.Joystick;
import de.droiddrone.pc.JoystickService;
import de.droiddrone.pc.LinkMode;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.RcMapper;
import de.droiddrone.pc.RcSession;
import de.droiddrone.pc.video.VideoPipeline;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Main window: link settings and status on the left, video area and live controller view on the right. */
public class MainWindow extends JFrame {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final PcConfig config;
    private final Path configFile;
    private final JoystickService service;
    private RcMapper mapper;
    private String mapperError;
    private boolean calibrated;
    private Connection connection;
    private RcSession session;
    private VideoPipeline video;
    private final VideoView videoView = new VideoView();
    /** Where videoView normally lives; it moves to fullScreen and back. */
    private JPanel videoHolder;
    private JFrame fullScreen;
    private de.droiddrone.pc.video.LatencyMeter latencyMeter;
    private JComponent linkColumn;
    private JComponent controllerCard;
    private ControlLink.State lastState;
    private boolean updatingCombo;

    private final JComboBox<LinkMode> modeCombo = new JComboBox<>(LinkMode.values());
    private final JLabel modeHint = Theme.label("", Theme.font(Font.PLAIN, 12f), Theme.MUTED);
    private final JLabel addressLabel = Theme.label("ADDRESS", Theme.headingFont(11f), Theme.MUTED);
    private final JTextField addressField = new JTextField();
    private final JTextField portField = new JTextField();
    private final JTextField keyField = new JTextField();
    private final JButton connectButton = Theme.primaryButton("Connect");
    private final JButton setupButton = Theme.secondaryButton("Setup");
    private final JButton betaflightButton = Theme.secondaryButton("Betaflight");
    private final JLabel statePill = new JLabel("OFFLINE", SwingConstants.CENTER);
    private final JLabel pingValue = statValue();
    private final JLabel fcValue = statValue();
    private final JLabel telemetryValue = statValue();
    private final JLabel rcSentValue = statValue();
    private final JTextArea log = new JTextArea();

    private final JComboBox<Joystick.Info> joystickCombo = new JComboBox<>();
    private final JButton calibrateButton = Theme.secondaryButton("Calibrate");
    private final JLabel calibrationLabel = Theme.label(" ", Theme.font(Font.BOLD, 12.5f), Theme.MUTED);
    private final JLabel rcStateLabel = Theme.label(" ", Theme.headingFont(13f), Theme.MUTED);
    private final JLabel fcStateLabel = Theme.label(" ", Theme.headingFont(13f), Theme.MUTED);
    private final GimbalView leftGimbal = new GimbalView("Throttle / Yaw", "YAW", "THR");
    private final GimbalView rightGimbal = new GimbalView("Pitch / Roll", "ROLL", "PIT");
    private final ChannelBars channelBars = new ChannelBars();

    public static void open(PcConfig config, Path configFile, JoystickService service) {
        Theme.install();
        boolean firstStart = !java.nio.file.Files.exists(configFile); // nothing has saved settings yet
        MainWindow window = new MainWindow(config, configFile, service);
        window.setVisible(true);
        if (firstStart) {
            // the very first start only: walk through the whole setup; after that it opens from SETUP
            window.save(Map.of(SetupWizard.OFFERED_KEY, "true"));
            SwingUtilities.invokeLater(() -> SetupWizard.open(window));
        }
    }

    private MainWindow(PcConfig config, Path configFile, JoystickService service) {
        super("DroidDrone PC");
        setIconImages(AppIcon.images());
        this.config = config;
        this.configFile = configFile;
        this.service = service;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        JPanel root = new JPanel(new BorderLayout(14, 14));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        setContentPane(root);
        root.add(buildHeader(), BorderLayout.NORTH);
        linkColumn = buildLinkColumn();
        root.add(linkColumn, BorderLayout.WEST);
        root.add(buildRightColumn(), BorderLayout.CENTER);

        modeCombo.addActionListener(e -> updateModeUi());
        reloadSettings();
        logLine("Flight app protocol " + SettingsCommon.versionCompatibleCode + " (DroidDrone 2.3.0). Settings: " + shortPath(configFile));

        videoView.setOsdSource(this::osdData);
        videoView.setOsdEnabled(!"false".equalsIgnoreCase(config.getString("osd")));
        // bound on the video view, not the window, so the keys also work while it is in the full-screen window
        bindKey(java.awt.event.KeyEvent.VK_F2, () -> {
            videoView.setOsdEnabled(!videoView.isOsdEnabled());
            save(Map.of("osd", Boolean.toString(videoView.isOsdEnabled())));
        });
        bindKey(java.awt.event.KeyEvent.VK_F3, this::toggleLatencyTest);
        bindKey(java.awt.event.KeyEvent.VK_F11, this::toggleFullScreen);
        bindKey(java.awt.event.KeyEvent.VK_ESCAPE, () -> {
            if (latencyMeter != null) toggleLatencyTest();
            else exitFullScreen();
        });
        new Timer(33, e -> refresh()).start();
        setMinimumSize(new Dimension(1100, 760));
        setSize(1280, 820);
        setLocationRelativeTo(null);
    }

    // ---------------------------------------------------------------- layout

    /** SETUP's menu: each part of the setup on its own. */
    private JPopupMenu setupMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(Theme.PANEL_2);
        menu.setBorder(BorderFactory.createLineBorder(Theme.ACCENT, 1));
        addSetupItem(menu, "PC setup", "Connection method, Tailscale, firewall, radio, Windows settings", () -> PcSetupWizard.open(this));
        addSetupItem(menu, "Phone setup", "The phone on the drone: apps, settings, link test", () -> PhoneGuideWizard.open(this));
        addSetupItem(menu, "Drone setup", "The flight controller: Betaflight over USB", () -> FlightControllerPages.open(this));
        return menu;
    }

    private void addSetupItem(JPopupMenu menu, String title, String detail, Runnable open) {
        JMenuItem item = new JMenuItem("<html><b>" + title.toUpperCase() + "</b><br><span style='color:" + Theme.MUTED_HEX
                + "'>" + detail + "</span></html>");
        item.setFont(Theme.font(Font.PLAIN, 13.5f));
        item.setForeground(Theme.TEXT);
        item.setBackground(Theme.PANEL_2);
        item.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        item.addActionListener(e -> open.run());
        menu.add(item);
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JPanel titles = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        titles.setOpaque(false);
        titles.add(Theme.label("DROIDDRONE", Theme.headingFont(22f), Theme.TEXT));
        titles.add(Theme.label("PC GROUND STATION", Theme.headingFont(22f), Theme.ACCENT));
        header.add(titles, BorderLayout.WEST);
        statePill.setOpaque(true);
        statePill.setFont(Theme.headingFont(12f));
        statePill.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));
        setPill("OFFLINE", Theme.PANEL_2, Theme.MUTED);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        setupButton.setToolTipText("PC setup, phone setup or drone setup");
        setupButton.addActionListener(e -> {
            JPopupMenu menu = setupMenu(); // right-aligned under the button, so it stays inside the window
            menu.show(setupButton, setupButton.getWidth() - menu.getPreferredSize().width, setupButton.getHeight() + 4);
        });
        right.add(setupButton);
        betaflightButton.setToolTipText("Step-by-step guide for the Betaflight Configurator settings");
        betaflightButton.addActionListener(e -> BetaflightGuide.open(this, this));
        right.add(betaflightButton);
        right.add(Box.createHorizontalStrut(8));
        right.add(statePill);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JComponent buildLinkColumn() {
        JPanel column = new JPanel(new BorderLayout(0, 14));
        column.setOpaque(false);
        column.setPreferredSize(new Dimension(340, 100));

        JPanel link = Theme.card(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 6, 0);
        link.add(Theme.sectionTitle("Link"), c);
        link.add(Theme.label("CONNECTION", Theme.headingFont(11f), Theme.MUTED), c);
        link.add(modeCombo, c);
        c.insets = new Insets(0, 0, 10, 0);
        modeHint.setPreferredSize(new Dimension(300, 52));
        modeHint.setVerticalAlignment(SwingConstants.TOP);
        link.add(modeHint, c);
        c.insets = new Insets(0, 0, 6, 0);
        link.add(addressLabel, c);
        addressField.putClientProperty("JTextField.placeholderText", "e.g. 100.64.0.20");
        link.add(addressField, c);
        c.gridwidth = 1;
        c.gridy = GridBagConstraints.RELATIVE;
        JPanel portKey = new JPanel(new GridLayout(2, 2, 10, 6));
        portKey.setOpaque(false);
        portKey.add(Theme.label("PORT", Theme.headingFont(11f), Theme.MUTED));
        portKey.add(Theme.label("KEY", Theme.headingFont(11f), Theme.MUTED));
        portKey.add(portField);
        portKey.add(keyField);
        c.gridwidth = 2;
        c.insets = new Insets(4, 0, 14, 0);
        link.add(portKey, c);
        connectButton.addActionListener(e -> {
            if (connection == null) connect(); else disconnect();
        });
        link.add(connectButton, c);

        JPanel stats = new JPanel(new GridLayout(4, 2, 8, 6));
        stats.setOpaque(false);
        addStat(stats, "PING", pingValue);
        addStat(stats, "FLIGHT CONTROLLER", fcValue);
        addStat(stats, "TELEMETRY PACKETS", telemetryValue);
        addStat(stats, "RC FRAMES SENT", rcSentValue);
        c.insets = new Insets(4, 0, 0, 0);
        link.add(stats, c);
        column.add(link, BorderLayout.NORTH);

        JPanel logCard = Theme.card(new BorderLayout(0, 8));
        logCard.add(Theme.sectionTitle("Log"), BorderLayout.NORTH);
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setBackground(Theme.PANEL);
        log.setForeground(Theme.TEXT);
        JScrollPane scroll = new JScrollPane(log);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        logCard.add(scroll, BorderLayout.CENTER);
        column.add(logCard, BorderLayout.CENTER);
        return column;
    }

    private static JLabel statValue() {
        JLabel label = Theme.label("-", Theme.font(Font.BOLD, 13f), Theme.TEXT);
        label.setHorizontalAlignment(SwingConstants.RIGHT);
        return label;
    }

    private void bindKey(int key, Runnable action) {
        videoView.registerKeyboardAction(e -> action.run(), javax.swing.KeyStroke.getKeyStroke(key, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private static void addStat(JPanel panel, String name, JLabel value) {
        panel.add(Theme.label(name, Theme.headingFont(11f), Theme.MUTED));
        panel.add(value);
    }

    private JComponent buildRightColumn() {
        JPanel column = new JPanel(new BorderLayout(0, 14));
        column.setOpaque(false);

        JPanel video = new JPanel(new BorderLayout());
        video.setBackground(Color.BLACK);
        video.add(videoView, BorderLayout.CENTER);
        videoHolder = video;
        videoView.setToolTipText("Double-click: video only / back    F11: full screen    F2: OSD on / off    F3: latency test");
        videoView.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 2) return;
                if (fullScreen != null) exitFullScreen();
                else toggleVideoOnly();
            }
        });
        column.add(video, BorderLayout.CENTER);

        JPanel controller = Theme.card(new BorderLayout(18, 12));
        controllerCard = controller;
        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setOpaque(false);
        JPanel topLeft = new JPanel();
        topLeft.setOpaque(false);
        topLeft.setLayout(new BoxLayout(topLeft, BoxLayout.X_AXIS));
        topLeft.add(Theme.sectionTitle("Controller"));
        topLeft.add(Box.createHorizontalStrut(14));
        joystickCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setText(value instanceof Joystick.Info info ? info.name() : "No controller found - plug in the TX16S (USB Joystick)");
                return this;
            }
        });
        joystickCombo.setPreferredSize(new Dimension(320, 32));
        joystickCombo.setMaximumSize(new Dimension(420, 32));
        joystickCombo.addActionListener(e -> onJoystickChosen());
        topLeft.add(joystickCombo);
        topLeft.add(Box.createHorizontalStrut(10));
        calibrateButton.addActionListener(e -> calibrate());
        topLeft.add(calibrateButton);
        topLeft.add(Box.createHorizontalStrut(8));
        JButton rcCheckButton = Theme.secondaryButton("RC check");
        rcCheckButton.setToolTipText("Compare what this PC sends with what Betaflight reports");
        rcCheckButton.addActionListener(e -> RcCheckDialog.open(this));
        topLeft.add(rcCheckButton);
        top.add(topLeft, BorderLayout.WEST);
        controller.add(top, BorderLayout.NORTH);

        JPanel gimbals = new JPanel(new GridLayout(1, 2, 18, 0));
        gimbals.setOpaque(false);
        gimbals.add(leftGimbal);
        gimbals.add(rightGimbal);
        gimbals.setPreferredSize(new Dimension(440, 262));
        controller.add(gimbals, BorderLayout.WEST);
        JPanel bars = new JPanel(new BorderLayout(0, 10));
        bars.setOpaque(false);
        bars.add(channelBars, BorderLayout.NORTH);
        JPanel states = new JPanel(new GridLayout(3, 1, 0, 4));
        states.setOpaque(false);
        states.add(calibrationLabel);
        states.add(rcStateLabel);
        states.add(fcStateLabel);
        bars.add(states, BorderLayout.SOUTH);
        controller.add(bars, BorderLayout.CENTER);
        column.add(controller, BorderLayout.SOUTH);
        return column;
    }

    // ---------------------------------------------------------------- behaviour

    private void updateModeUi() {
        LinkMode mode = (LinkMode) modeCombo.getSelectedItem();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            port = SettingsCommon.port;
        }
        modeHint.setText("<html>" + Objects.requireNonNull(mode).hint(port) + "</html>");
        boolean needsAddress = mode != LinkMode.HOST_RELAY;
        addressField.setEnabled(needsAddress && connection == null);
        addressLabel.setText(mode == LinkMode.RELAY ? "RELAY SERVER ADDRESS" : mode == LinkMode.PHONE ? "PHONE ADDRESS" : "ADDRESS (NOT USED)");
    }

    /** Shows the saved settings again (the setup wizards change them). */
    void reloadSettings() {
        if (connection == null) {
            modeCombo.setSelectedItem(config.getLinkMode());
            addressField.setText(config.getIp());
            portField.setText(Integer.toString(config.getPort()));
            keyField.setText(config.getKey());
            updateModeUi();
        }
        rebuildMapper();
    }

    PcConfig config() {
        return config;
    }

    JoystickService service() {
        return service;
    }

    boolean isLinkActive() {
        return connection != null;
    }

    boolean isCalibrated() {
        return calibrated;
    }

    private void rebuildMapper() {
        try {
            mapper = config.createMapper();
            mapperError = null;
            calibrated = !config.getAxisCalibrations().isEmpty();
        } catch (IllegalArgumentException e) {
            mapper = null;
            mapperError = e.getMessage();
            calibrated = false;
        }
    }

    private void onJoystickChosen() {
        if (updatingCombo) return;
        Joystick.Info info = (Joystick.Info) joystickCombo.getSelectedItem();
        if (info == null) return;
        service.select(info.id());
        save(Map.of("joystick", info.name()));
    }

    /** @return true when a new calibration was saved */
    boolean calibrate() {
        if (connection != null) return false;
        JoystickService.Snapshot snap = service.getSnapshot();
        java.awt.Window owner = javax.swing.FocusManager.getCurrentManager().getActiveWindow();
        if (snap.selected() == null || snap.state() == null) {
            JOptionPane.showMessageDialog(owner == null ? this : owner, "Plug in the radio and choose 'USB Joystick (HID)' on it first.",
                    "No controller", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        Map<String, String> result = CalibrationDialog.run(owner == null ? this : owner, service, snap.selected(), snap.state());
        if (result == null) {
            logLine("Calibration cancelled - nothing changed.");
            return false;
        }
        if (save(result)) {
            rebuildMapper();
            logLine("Calibration saved: " + mappingSummary());
            return true;
        }
        return false;
    }

    private String mappingSummary() {
        StringBuilder sb = new StringBuilder();
        String[] specs = config.getChannelSpecs();
        for (int i = 0; i < specs.length; i++) {
            if (specs[i] != null) sb.append(PcConfig.CHANNEL_NAMES[i]).append('=').append(specs[i]).append(' ');
        }
        return sb.toString().trim();
    }

    private void connect() {
        if (mapper == null) {
            JOptionPane.showMessageDialog(this, "Controller mapping is not usable: " + mapperError + "\nRun Calibrate first.",
                    "Cannot connect", JOptionPane.WARNING_MESSAGE);
            return;
        }
        LinkMode mode = (LinkMode) modeCombo.getSelectedItem();
        String port = portField.getText().trim();
        try {
            int p = Integer.parseInt(port);
            if (p < 1024 || p > 65534) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Port must be a number between 1024 and 65534.", "Cannot connect", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!save(Map.of("linkMode", Objects.requireNonNull(mode).key, "ip", addressField.getText().trim(),
                "port", port, "key", keyField.getText().trim()))) {
            return;
        }
        boolean wantVideo = !"false".equalsIgnoreCase(config.getString("video"));
        ControlLink[] linkRef = new ControlLink[1];
        video = wantVideo ? new VideoPipeline(videoView, () -> linkRef[0] == null ? -1 : linkRef[0].getPing()) : null;
        try {
            connection = Connection.open(config, video);
            linkRef[0] = connection.getLink();
            if (video != null) video.attach(connection.getLink());
            videoView.setStatsSource(video == null ? null : video::stats);
        } catch (IOException | RuntimeException e) {
            connection = null;
            if (video != null) video.close();
            video = null;
            logLine("Connect failed: " + e.getMessage());
            JOptionPane.showMessageDialog(this, e.getMessage(), "Cannot connect", JOptionPane.WARNING_MESSAGE);
            return;
        }
        session = new RcSession(connection.getLink(), mapper, msg -> SwingUtilities.invokeLater(() -> logLine(msg)));
        service.setInputListener(session::onInput);
        lastState = null;
        logLine("Connecting: " + mode + (mode == LinkMode.HOST_RELAY ? "" : " " + addressField.getText().trim()) + ":" + port);
        setLinkEditable(false);
    }

    private void disconnect() {
        service.setInputListener(null);
        if (connection != null) connection.close();
        connection = null;
        session = null;
        if (video != null) video.close();
        video = null;
        videoView.setStatsSource(null);
        videoView.clear();
        logLine("Disconnected. The Flight phone stops RC to the FC, so Betaflight fails safe.");
        setLinkEditable(true);
    }

    private void setLinkEditable(boolean editable) {
        modeCombo.setEnabled(editable);
        portField.setEnabled(editable);
        keyField.setEnabled(editable);
        joystickCombo.setEnabled(editable);
        calibrateButton.setEnabled(editable);
        calibrateButton.setToolTipText(editable ? null : "Disconnect first - RC output must be stopped while calibrating");
        connectButton.setText(editable ? "CONNECT" : "DISCONNECT");
        connectButton.setBackground(editable ? Theme.ACCENT : Theme.BAD);
        updateModeUi();
    }

    boolean save(Map<String, String> updates) {
        try {
            config.save(configFile, updates);
            return true;
        } catch (IOException e) {
            logLine("Could not save settings: " + e.getMessage());
            return false;
        }
    }

    private void shutdown() {
        if (connection != null) disconnect();
        service.stop(); // the main thread leaves its polling loop and exits the app
        dispose();
    }

    // ---------------------------------------------------------------- periodic refresh

    private void refresh() {
        JoystickService.Snapshot snap = service.getSnapshot();
        refreshJoystickCombo(snap);
        refreshController(snap);
        refreshLink();
        // keeps the HUD (and the latency test's time code) live even when no video frames arrive
        if (connection != null || latencyMeter != null) videoView.repaint();
    }

    private void refreshJoystickCombo(JoystickService.Snapshot snap) {
        List<Joystick.Info> list = snap.joysticks();
        DefaultComboBoxModel<Joystick.Info> model = (DefaultComboBoxModel<Joystick.Info>) joystickCombo.getModel();
        boolean same = model.getSize() == list.size();
        for (int i = 0; same && i < list.size(); i++) same = model.getElementAt(i).equals(list.get(i));
        if (same && Objects.equals(model.getSelectedItem(), snap.selected())) return;
        updatingCombo = true;
        if (!same) {
            model.removeAllElements();
            list.forEach(model::addElement);
        }
        model.setSelectedItem(snap.selected());
        updatingCombo = false;
    }

    private void refreshController(JoystickService.Snapshot snap) {
        short[] channels = null;
        if (session != null) {
            channels = session.getLastChannels();
        } else if (mapper != null && snap.state() != null) {
            channels = mapper.map(snap.state().axes(), snap.state().buttons());
        }
        if (channels != null) {
            leftGimbal.setValues((int) channels[2], (int) channels[PcConfig.THROTTLE_INDEX]);
            rightGimbal.setValues((int) channels[0], (int) channels[1]);
        } else {
            leftGimbal.setValues(null, null);
            rightGimbal.setValues(null, null);
        }
        channelBars.setValues(channels, mapper == null ? 8 : Math.max(4, mapper.getChannelCount()));

        if (mapper == null) {
            setLabel(calibrationLabel, "MAPPING INVALID: " + mapperError, Theme.BAD);
        } else if (!calibrated) {
            setLabel(calibrationLabel, "NOT CALIBRATED - PRESS CALIBRATE", Theme.WARN);
        } else {
            setLabel(calibrationLabel, "CALIBRATED", Theme.OK);
        }

        if (snap.selected() == null) {
            setLabel(rcStateLabel, "NO CONTROLLER", Theme.BAD);
        } else if (session == null) {
            setLabel(rcStateLabel, "RC OUTPUT: NOT CONNECTED", Theme.MUTED);
        } else if (session.isRcEnabled()) {
            setLabel(rcStateLabel, "RC OUTPUT: LIVE", Theme.OK);
        } else {
            setLabel(rcStateLabel, "RC OUTPUT: OFF - LOWER THROTTLE TO ENABLE", Theme.WARN);
        }

        FcTelemetry.Status fc = connection == null ? null : connection.getLink().getTelemetry().getStatus();
        if (fc == null || System.currentTimeMillis() - fc.receivedAt() > 3000) {
            setLabel(fcStateLabel, connection == null ? " " : "FC: NO STATUS YET", Theme.MUTED);
        } else if (fc.armed()) {
            setLabel(fcStateLabel, "FC: ARMED" + (fc.activeModes().size() > 1 ? "  ·  " + String.join(" ", fc.activeModes()) : ""), Theme.BAD);
        } else if (fc.blockers().isEmpty()) {
            setLabel(fcStateLabel, "FC: DISARMED - READY TO ARM", Theme.OK);
        } else {
            setLabel(fcStateLabel, "FC: ARMING BLOCKED BY " + fc.blockers().stream().map(FcTelemetry.Blocker::osdName)
                    .reduce((a, b) -> a + ", " + b).orElse("") + "  (RC CHECK explains)", Theme.WARN);
        }
    }

    /** "%APPDATA%\DroidDronePC\..." rather than a full path with the Windows user name in it. */
    private static String shortPath(Path file) {
        String full = file.toAbsolutePath().toString();
        String appData = System.getenv("APPDATA");
        return appData != null && full.startsWith(appData) ? "%APPDATA%" + full.substring(appData.length()) : full;
    }

    /** What the HUD shows right now; null when not connected. Called on the Swing thread while painting. */
    private OsdRenderer.Data osdData() {
        Connection c = connection;
        if (c == null) return null;
        ControlLink l = c.getLink();
        RcSession s = session;
        VideoPipeline v = video;
        return new OsdRenderer.Data(true, l.getState() == ControlLink.State.READY, l.getPing(), s != null && s.isRcEnabled(),
                l.getTelemetry(), v == null ? null : v.stats(), "true".equalsIgnoreCase(config.getString("osdInvertPitch")));
    }

    RcSession rcSession() {
        return session;
    }

    /** The link while connected, else null. */
    ControlLink activeLink() {
        return connection == null ? null : connection.getLink();
    }

    /** What would be sent right now (when not connected). */
    short[] previewChannels() {
        JoystickService.Snapshot snap = service.getSnapshot();
        return mapper == null || snap.state() == null ? null : mapper.map(snap.state().axes(), snap.state().buttons());
    }

    private void refreshLink() {
        if (connection == null) {
            videoView.setIdleText("Not connected");
            setPill("OFFLINE", Theme.PANEL_2, Theme.MUTED);
            pingValue.setText("-");
            fcValue.setText("-");
            telemetryValue.setText("-");
            rcSentValue.setText("-");
            return;
        }
        ControlLink link = connection.getLink();
        ControlLink.State state = link.getState();
        if (state != lastState) {
            logLine(connection.describe(state));
            lastState = state;
        }
        if (video == null) {
            videoView.setIdleText("Video is off (video=false in the settings)");
        } else if (state != ControlLink.State.READY) {
            videoView.setIdleText("Waiting for the link...");
        } else if (!video.hasStream()) {
            videoView.setIdleText("Starting the phone's camera...");
        } else {
            videoView.setIdleText("Waiting for the first key frame...");
        }
        switch (state) {
            case READY -> setPill("LINK READY", Theme.OK, Theme.BG);
            case VERSION_MISMATCH -> setPill("VERSION MISMATCH", Theme.BAD, Theme.BG);
            case CLOSED -> setPill("OFFLINE", Theme.PANEL_2, Theme.MUTED);
            default -> setPill(state == ControlLink.State.CONNECTING ? "CONNECTING" : "WAITING FOR PHONE", Theme.WARN, Theme.BG);
        }
        int ping = link.getPing();
        pingValue.setText(ping < 0 ? "-" : ping + " ms");
        pingValue.setForeground(ping < 0 ? Theme.MUTED : ping > 150 ? Theme.BAD : ping > 80 ? Theme.WARN : Theme.OK);
        FcInfo fc = link.getFcInfo();
        fcValue.setText(fc == null ? "-" : fc.getFcName() + " " + fc.getFcVersionStr());
        telemetryValue.setText(Long.toString(link.getTelemetryPacketsReceived()));
        rcSentValue.setText(Long.toString(link.getRcFramesSent()));
    }

    /**
     * F3: glass-to-glass latency test. The video area shows a time code; filmed by the drone's camera and read back
     * from the video, it gives the delay from the scene to this screen. Stopping it logs the result.
     */
    private void toggleLatencyTest() {
        if (latencyMeter == null) {
            latencyMeter = new de.droiddrone.pc.video.LatencyMeter();
            videoView.setLatencyMeter(latencyMeter);
            logLine("Latency test: point the drone's camera at the code on the screen. F3 or Esc to stop.");
        } else {
            logLine("Latency test, camera to screen" + (fullScreen != null ? " (full screen)" : "") + ": " + latencyMeter);
            videoView.setLatencyMeter(null);
            latencyMeter.close();
            latencyMeter = null;
        }
    }

    /**
     * F11: the video alone in exclusive full screen. Windows then puts the picture on the screen directly instead of
     * first composing it into the desktop, which can save up to a screen refresh. Leaving the window (alt-tab, another
     * window) drops back to normal.
     */
    private void toggleFullScreen() {
        if (fullScreen != null) {
            exitFullScreen();
            return;
        }
        java.awt.GraphicsDevice device = getGraphicsConfiguration().getDevice();
        JFrame frame = new JFrame(getTitle());
        frame.setUndecorated(true);
        frame.setIconImages(getIconImages());
        frame.getContentPane().setBackground(Color.BLACK);
        frame.add(videoView, BorderLayout.CENTER); // moves it out of this window
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            private boolean active;

            @Override
            public void windowActivated(java.awt.event.WindowEvent e) {
                active = true;
            }

            @Override
            public void windowDeactivated(java.awt.event.WindowEvent e) {
                if (active) javax.swing.SwingUtilities.invokeLater(MainWindow.this::exitFullScreen);
            }
        });
        fullScreen = frame;
        boolean exclusive = device.isFullScreenSupported();
        if (exclusive) {
            device.setFullScreenWindow(frame);
        } else {
            frame.setBounds(device.getDefaultConfiguration().getBounds());
            frame.setVisible(true);
        }
        videoHolder.revalidate();
        videoHolder.repaint();
        logLine("Full screen (" + (exclusive ? "exclusive" : "window") + "). F11, Esc or double-click to leave.");
    }

    private void exitFullScreen() {
        JFrame frame = fullScreen;
        if (frame == null) return;
        fullScreen = null;
        java.awt.GraphicsDevice device = frame.getGraphicsConfiguration().getDevice();
        if (device.getFullScreenWindow() == frame) device.setFullScreenWindow(null);
        videoHolder.add(videoView, BorderLayout.CENTER);
        frame.dispose();
        videoHolder.revalidate();
        videoHolder.repaint();
        toFront();
    }

    /** Double-click on the video: hide everything else for a bigger picture, and back. */
    private void toggleVideoOnly() {
        boolean show = !linkColumn.isVisible();
        linkColumn.setVisible(show);
        controllerCard.setVisible(show);
        revalidate();
    }

    private void setPill(String text, Color background, Color foreground) {
        if (text.equals(statePill.getText()) && background.equals(statePill.getBackground())) return;
        statePill.setText(text);
        statePill.setBackground(background);
        statePill.setForeground(foreground);
    }

    private static void setLabel(JLabel label, String text, Color color) {
        if (!text.equals(label.getText())) label.setText(text);
        label.setForeground(color);
    }

    void logLine(String text) {
        log.append(LocalTime.now().format(TIME) + "  " + text + "\n");
        int excess = log.getLineCount() - 300;
        if (excess > 0) {
            try {
                log.replaceRange("", 0, log.getLineEndOffset(excess - 1));
            } catch (Exception ignored) {
            }
        }
        log.setCaretPosition(log.getDocument().getLength());
    }
}
