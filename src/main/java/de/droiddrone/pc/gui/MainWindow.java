package de.droiddrone.pc.gui;

import de.droiddrone.common.FcInfo;
import de.droiddrone.common.SettingsCommon;
import de.droiddrone.pc.Connection;
import de.droiddrone.pc.ArmSwitch;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.Joystick;
import de.droiddrone.pc.JoystickService;
import de.droiddrone.pc.LinkLog;
import de.droiddrone.pc.LinkMode;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.RcMapper;
import de.droiddrone.pc.RcSession;
import de.droiddrone.pc.setup.Tailscale;
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
    private final AlertSound alertSound = new AlertSound();
    static final String RECORD_KEY = "recordVideo";
    static final String HW_DECODE_KEY = "hardwareDecode";
    /** SETTINGS > Hardware video decoding (off by default; software is the tested path). */
    private boolean hardwareDecode;
    /** SETTINGS > Record video: every connection records its video (Videos\DRAUPNE Link). */
    private boolean recordVideo;
    /** The connection's timeline (10 rows a second) for finding the cause of failsafes; null when not connected. */
    private LinkLog linkLog;
    private final Timer linkLogTimer = new Timer(100, e -> sampleLinkLog());
    private VideoPipeline video;
    private final VideoView videoView = new VideoView();
    /** The map in its own window (MENU > MAP > Map window, F3), for a second screen: where the drone is and has been. */
    private final de.droiddrone.pc.map.TileCache tileCache = new de.droiddrone.pc.map.TileCache(de.droiddrone.pc.map.TileCache.defaultDir());
    private final de.droiddrone.pc.map.Elevation elevation = new de.droiddrone.pc.map.Elevation();
    private MapWindow mapWindow;
    static final String MAP_SOURCE_KEY = "mapSource";
    static final String COORDINATES_KEY = "coordinateFormat";
    static final String GPS_SOURCE_KEY = "gpsSource";
    private de.droiddrone.pc.map.TileSource mapSource;
    private de.droiddrone.pc.map.Coordinates.Format coordinateFormat = de.droiddrone.pc.map.Coordinates.Format.MGRS;
    /** MAP > Position from: the phone's own GPS rather than the drone's (the other fills in when it has nothing). */
    private boolean preferPhone;
    /** The connection's GPS positions, written to a GPX file as they come; kept after disconnecting for FIND DRONE. */
    private de.droiddrone.pc.map.FlightTrack track;
    private long mapIdleUpdatedAt;
    /** The header: MENU (the one filled button) slides out into these, each with a menu of its own. */
    private final JButton menuButton = Theme.primaryButton("Menu");
    private final SlideMenu headerMenu = new SlideMenu(menuButton);
    private final JButton videoButton = Theme.secondaryButton("Video");
    private final JButton linkButton = Theme.secondaryButton("Link");
    private final JButton toolsButton = Theme.secondaryButton("Tools");
    /** The pilot's pins on the map (Documents\DRAUPNE Link\pins.txt). */
    private de.droiddrone.pc.map.Pins pins;
    /** In flight the LINK card changes: the connection fields and detail figures fold away, the minimap unfolds. */
    private final MapView miniMap = new MapView(tileCache);
    private Fold fieldsFold, detailsFold, miniFold;
    /** Where videoView normally lives; it moves to fullScreen and back. */
    private JPanel videoHolder;
    private JFrame fullScreen;
    /** The video in its own window (POP OUT VIDEO), or null while it sits in this one. */
    private JFrame popOut;
    private final JButton popOutButton = Theme.secondaryButton("Pop out video");
    private final JLabel poppedOutNote = Theme.label("VIDEO IS IN ITS OWN WINDOW", Theme.headingFont(16f), Theme.MUTED);
    private de.droiddrone.pc.video.LatencyMeter latencyMeter;
    private JComponent linkColumn;
    private JComponent controllerCard;
    /** The controller card's height while it folds or unfolds (MINIMIZE / EXPAND); -1 = its own. */
    private int controllerAnimHeight = -1;
    /** What the pilot chose; the sticks stay visible while the card folds, so their visibility doesn't say. */
    private boolean controllerMinimized;
    private Timer controllerAnim;
    private static final long CONTROLLER_ANIM_MS = 240;
    private ControlLink.State lastState;
    private String lastStateText;
    private int lastSpectators;
    private boolean updatingCombo;
    /** The controller as a plain field when there is only one (or none); the dropdown appears with two or more. */
    private final JLabel joystickField = Theme.label("", Theme.font(Font.BOLD, 13.5f), Theme.TEXT);
    private final java.awt.CardLayout joystickCards = new java.awt.CardLayout();
    private final JPanel joystickSlot = new JPanel(joystickCards);
    private Color joystickFrame;

    private final JComboBox<LinkMode> modeCombo = new JComboBox<>(LinkMode.values());
    private final JLabel modeHint = Theme.label("", Theme.font(Font.PLAIN, 12f), Theme.MUTED);
    private final JLabel addressLabel = Theme.label("ADDRESS", Theme.headingFont(11f), Theme.MUTED);
    private final JTextField addressField = new JTextField();
    private final JTextField portField = new JTextField();
    private final JTextField keyField = new JTextField();
    private final JButton connectButton = Theme.primaryButton("Connect");
    /** Connect as: pilot (fly) or spectator (watch only). */
    private final JComboBox<String> roleCombo = new JComboBox<>(new String[]{ROLE_PILOT, ROLE_SPECTATOR});
    private static final String ROLE_PILOT = "Pilot - fly the drone";
    private static final String ROLE_SPECTATOR = "Spectator - watch only";
    /** Wanted to fly, but another PC flies: told once per connection. */
    private boolean toldNotFlying;
    private final JButton setupButton = Theme.secondaryButton("Setup");
    /** Shown when GitHub has a newer release; downloads and installs it (or opens its page, see startUpdate). */
    private final JButton updateButton = Theme.primaryButton("Update");
    private volatile de.droiddrone.pc.setup.UpdateCheck.Release update;
    private boolean updating;
    private final JLabel statePill = new JLabel("OFFLINE", SwingConstants.CENTER);
    private final JLabel pingValue = statValue();
    private final JLabel fcValue = statValue();
    private final JLabel telemetryValue = statValue();
    private final JLabel rcSentValue = statValue();
    private final JLabel phoneValue = statValue();
    private final JLabel phoneAppValue = statValue();
    private final JLabel phoneBatteryValue = statValue();
    /** The phone app is older than the one this PC brings: what to do about it. */
    private final WrappingText phoneAppWarning = new WrappingText("", Theme.font(Font.BOLD, 12.5f), Theme.WARN);
    private boolean phoneAppWarned;
    private final WrappingText phoneWarning = new WrappingText("", Theme.font(Font.BOLD, 12.5f), Theme.BAD);
    /** Tailscale's view of the phone, checked every few seconds; null when not using Tailscale or no address yet. */
    private volatile Tailscale.PhoneState phoneState;
    /** Tailscale's way to the phone ("direct" / "relay fra"), for the link log; "" when unknown. */
    private volatile String phonePath = "";
    private final java.util.concurrent.ScheduledExecutorService phoneWatch =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "phoneWatch");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private final JTextArea log = new JTextArea();

    private final JComboBox<Joystick.Info> joystickCombo = new JComboBox<>();
    private final JButton calibrateButton = Theme.secondaryButton("Calibrate");
    private final JLabel calibrationLabel = Theme.label(" ", Theme.font(Font.BOLD, 12.5f), Theme.MUTED);
    private final JLabel rcStateLabel = Theme.label(" ", Theme.headingFont(13f), Theme.MUTED);
    private final JLabel fcStateLabel = Theme.label(" ", Theme.headingFont(13f), Theme.MUTED);
    private final GimbalView leftGimbal = new GimbalView("Throttle / Yaw", "YAW", "THR");
    private final GimbalView rightGimbal = new GimbalView("Pitch / Roll", "ROLL", "PIT");
    private final ChannelBars channelBars = new ChannelBars();
    /** The sticks and channel bars; hidden when the controller panel is minimized, so the video gets the room. */
    private JComponent gimbalPanel;
    private final JButton minimizeButton = textButton("MINIMIZE", "Hide the sticks and channels: more room for the video");
    private static final String MINIMIZED_KEY = "controllerMinimized";
    static final String UPSIDE_DOWN_KEY = "videoUpsideDown";

    public static void open(PcConfig config, Path configFile, JoystickService service) {
        Theme.install();
        boolean firstStart = !java.nio.file.Files.exists(configFile); // nothing has saved settings yet
        MainWindow window = new MainWindow(config, configFile, service);
        BootReveal.onOpen(window, () -> window.videoView.getParent() == null ? null
                : SwingUtilities.convertRectangle(window.videoView.getParent(), window.videoView.getBounds(), window.getRootPane().getLayeredPane()));
        window.setVisible(true);
        if (firstStart) {
            // the very first start only: walk through the whole setup; after that it opens from SETUP
            window.save(Map.of(SetupWizard.OFFERED_KEY, "true"));
            SwingUtilities.invokeLater(() -> SetupWizard.open(window));
        }
    }

    private MainWindow(PcConfig config, Path configFile, JoystickService service) {
        super("DRAUPNE Link " + de.droiddrone.pc.AppInfo.version());
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
        TacticalCombo.install(modeCombo);
        reloadSettings();
        logLine("DRAUPNE Link " + de.droiddrone.pc.AppInfo.version() + ". Phone app: DRAUPNE Flight " + de.droiddrone.pc.setup.ApkShare.VERSION
                + " (protocol " + SettingsCommon.versionCompatibleCode + "). Settings: " + shortPath(configFile));

        videoView.setOsdSource(this::osdData);
        videoView.setOsdLayout(OsdLayout.load(config::getString));
        alertSound.setEnabled(!"false".equalsIgnoreCase(config.getString(AlertSound.KEY)));
        recordVideo = "true".equalsIgnoreCase(config.getString(RECORD_KEY));
        hardwareDecode = "true".equalsIgnoreCase(config.getString(HW_DECODE_KEY));
        videoView.setOsdEnabled(!"false".equalsIgnoreCase(config.getString("osd")));
        videoView.setUpsideDown("true".equalsIgnoreCase(config.getString(UPSIDE_DOWN_KEY)));
        mapSource = de.droiddrone.pc.map.TileSource.parse(config.getString(MAP_SOURCE_KEY));
        coordinateFormat = de.droiddrone.pc.map.Coordinates.Format.parse(config.getString(COORDINATES_KEY));
        preferPhone = "phone".equalsIgnoreCase(config.getString(GPS_SOURCE_KEY));
        try {
            pins = new de.droiddrone.pc.map.Pins(de.droiddrone.pc.map.Pins.defaultFile());
        } catch (IOException e) {
            pins = new de.droiddrone.pc.map.Pins(null);
        }
        miniMap.setPins(pins);
        miniMap.setSource(mapSource);
        // bound on the video view, not the window, so the keys also work while it is in the full-screen window
        bindKey(java.awt.event.KeyEvent.VK_F3, this::toggleMapWindow);
        bindKey(java.awt.event.KeyEvent.VK_F2, () -> {
            videoView.setOsdEnabled(!videoView.isOsdEnabled());
            save(Map.of("osd", Boolean.toString(videoView.isOsdEnabled())));
        });
        bindKey(java.awt.event.KeyEvent.VK_F11, this::toggleFullScreen);
        bindKey(java.awt.event.KeyEvent.VK_ESCAPE, () -> {
            if (latencyMeter != null) setLatencyTest(false);
            else exitFullScreen();
        });
        new Timer(33, e -> refresh()).start();
        phoneWatch.scheduleWithFixedDelay(this::checkPhone, 1, 5, java.util.concurrent.TimeUnit.SECONDS);
        // at once on opening, then every 6 hours; its own thread, so a slow GitHub never holds up the phone check
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "updateCheck");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }).scheduleWithFixedDelay(this::checkForUpdate, 0, 6 * 3600, java.util.concurrent.TimeUnit.SECONDS);
        setMinimumSize(new Dimension(1100, 760));
        setSize(1280, 820);
        setLocationRelativeTo(null);
    }

    // ---------------------------------------------------------------- layout

    /** SETUP's menu: the first-time setup, or each part of it on its own. */
    private JPopupMenu setupMenu() {
        JPopupMenu menu = TacticalCombo.menu();
        menu.setBackground(Theme.PANEL_2);
        menu.setBorder(BorderFactory.createLineBorder(Theme.ACCENT, 1));
        addMenuItem(menu, "First-time setup", "Everything, step by step: PC, radio, drone, phone, connection", () -> SetupWizard.open(this));
        addMenuItem(menu, "PC setup", "Connection method, Tailscale, firewall, radio, Windows settings", () -> PcSetupWizard.open(this));
        addMenuItem(menu, "Phone setup", "The phone on the drone: apps, settings, connection", () -> PhoneGuideWizard.open(this));
        addMenuItem(menu, "Drone setup", "Betaflight's receiver, stick delay and flight mode switch, set over USB", () -> DroneSetup.open(this));
        return menu;
    }

    /** A menu in the header's style. */
    private static JPopupMenu styledMenu() {
        JPopupMenu menu = TacticalCombo.menu();
        menu.setBackground(Theme.PANEL_2);
        menu.setBorder(BorderFactory.createLineBorder(Theme.ACCENT, 1));
        return menu;
    }

    /** VIDEO: the picture, its recording, where it is shown. */
    private JPopupMenu videoMenu() {
        JPopupMenu menu = styledMenu();
        ControlLink.VideoMode videoMode = ControlLink.VideoMode.parse(config.getString(ControlLink.VIDEO_MODE_KEY));
        menu.add(new MenuRows.ChoiceRow("Video", "Bad coverage mode: 640x360, up to 1 Mbit/s, sturdier. Auto switches by itself",
                new String[]{"AUTO", "SHARP", "ROBUST"}, videoMode.ordinal(), i -> {
                    ControlLink.VideoMode m = ControlLink.VideoMode.values()[i];
                    save(Map.of(ControlLink.VIDEO_MODE_KEY, m.name().toLowerCase(java.util.Locale.ROOT)));
                    Connection c = connection;
                    if (c != null) c.getLink().setVideoMode(m);
                    logLine(switch (m) {
                        case AUTO -> "Video: bad coverage mode comes on by itself when the link is weak.";
                        case SHARP -> "Video: always the full picture (no bad coverage mode).";
                        case ROBUST -> "Video: bad coverage mode always - 640x360, up to 1 Mbit/s, key frames sent twice.";
                    });
                }));
        menu.add(new MenuRows.SwitchRow("Camera upside down", "Turns the picture 180&deg;; the OSD stays upright. No delay",
                videoView.isUpsideDown(), on -> {
                    videoView.setUpsideDown(on);
                    save(Map.of(UPSIDE_DOWN_KEY, Boolean.toString(on)));
                    logLine(on ? "Picture turned upside down." : "Picture the right way up.");
                }));
        menu.add(new MenuRows.SwitchRow("Record video", "Every flight to Videos\\DRAUPNE Link, as it comes: no extra delay",
                recordVideo, on -> {
                    recordVideo = on;
                    save(Map.of(RECORD_KEY, Boolean.toString(on)));
                    VideoPipeline v = video;
                    if (v == null) {
                        logLine(on ? "Video will be recorded from the next connection." : "Video won't be recorded.");
                    } else if (on) {
                        startRecording(v);
                    } else {
                        v.stopRecording();
                        logLine("Recording stopped.");
                    }
                }));
        menu.add(new MenuRows.SwitchRow("Hardware video decoding", "GPU (D3D11VA), next connection. Only for big pictures: slower at 720p",
                hardwareDecode, on -> {
                    hardwareDecode = on;
                    save(Map.of(HW_DECODE_KEY, Boolean.toString(on)));
                    logLine(on ? "Hardware video decoding: from the next connection (software if the GPU can't)."
                            : "Software video decoding from the next connection.");
                }));
        menu.addSeparator();
        menu.add(new MenuRows.SwitchRow("OSD", "The HUD over the video. F2 switches", videoView.isOsdEnabled(), on -> {
            videoView.setOsdEnabled(on);
            save(Map.of("osd", Boolean.toString(on)));
        }));
        menu.add(new MenuRows.WindowRow("OSD layout", "Choose the OSD elements and place them, like Betaflight",
                () -> OsdSettingsDialog.open(this, this)));
        menu.addSeparator();
        menu.add(new MenuRows.WindowRow(popOut == null ? "Pop out video" : "Video back",
                popOut == null ? "The video in a window of its own, e.g. on a second screen" : "The video back into this window", this::togglePopOut));
        menu.add(new MenuRows.WindowRow("Full screen", "The video over the whole screen. F11; Esc ends it", this::toggleFullScreen));
        return menu;
    }

    /** LINK: how the connection behaves. */
    private JPopupMenu linkMenu() {
        JPopupMenu menu = styledMenu();
        menu.add(new MenuRows.SwitchRow("Warning sounds", "A beep when the sticks stop reaching the drone",
                alertSound.isEnabled(), on -> {
                    alertSound.setEnabled(on);
                    save(Map.of(AlertSound.KEY, Boolean.toString(on)));
                }));
        menu.add(new MenuRows.SwitchRow("Allow spectators", "Off by default. Other PCs watch your flight through this PC's upload",
                de.droiddrone.pc.Connection.spectatorsAllowed(config), on -> {
                    save(Map.of(de.droiddrone.pc.Connection.ALLOW_SPECTATORS_KEY, Boolean.toString(on)));
                    Connection c = connection;
                    if (c != null) c.getLink().setSpectatorsAllowed(on);
                    logLine(on ? "Spectators allowed: a second PC that connects watches through this one."
                            : "Spectators off: nobody can watch through this PC.");
                }));
        return menu;
    }

    /** TOOLS: the drone's whereabouts, tests, and what this is. */
    private JPopupMenu toolsMenu() {
        JPopupMenu menu = styledMenu();
        menu.add(new MenuRows.WindowRow("Find drone", "The last known position with the coordinates to copy and map links, to go and get it", this::openFindDrone));
        menu.add(new MenuRows.ChoiceRow("Position from", "The drone's GPS, or the phone's own (phone app pc.16, location allowed). The other fills in when the chosen one has nothing",
                new String[]{"DRONE", "PHONE"}, preferPhone ? 1 : 0, i -> {
                    preferPhone = i == 1;
                    save(Map.of(GPS_SOURCE_KEY, preferPhone ? "phone" : "drone"));
                    if (mapWindow != null) mapWindow.setPreferPhone(preferPhone);
                }));
        menu.add(new MenuRows.WindowRow("Tracks folder", "Every flight's path as a GPX file, and last-position.txt (Documents\\DRAUPNE Link\\Tracks)", () -> {
            try {
                java.awt.Desktop.getDesktop().open(de.droiddrone.pc.map.FlightTrack.defaultDir().toFile());
            } catch (Exception e) {
                logLine("Can't open the tracks folder: " + e.getMessage());
            }
        }));
        menu.addSeparator();
        menu.add(new MenuRows.SwitchRow("Latency test", "A time code for the drone's camera: the real delay",
                latencyMeter != null, this::setLatencyTest));
        JMenuItem motors = new MenuRows.WindowRow("Motor test", isLinkActive()
                ? "Disconnect from the drone first: it runs over USB"
                : "Spin each motor: right place, right direction?", () -> MotorTestDialog.open(this, this));
        motors.setEnabled(!isLinkActive());
        menu.add(motors);
        menu.addSeparator();
        JLabel about = Theme.label("PC " + de.droiddrone.pc.AppInfo.version() + "   /   PHONE APP " + de.droiddrone.pc.setup.ApkShare.VERSION,
                Theme.headingFont(11f), Theme.MUTED);
        about.setBorder(BorderFactory.createEmptyBorder(6, 14, 8, 14));
        menu.add(about);
        return menu;
    }

    private JMenuItem addMenuItem(JPopupMenu menu, String title, String detail, Runnable open) {
        JMenuItem item = new JMenuItem("<html><b>" + title.toUpperCase() + "</b><br><span style='color:" + Theme.MUTED_HEX
                + "'>" + detail + "</span></html>");
        item.setFont(Theme.font(Font.PLAIN, 13.5f));
        item.setForeground(Theme.TEXT);
        item.setBackground(Theme.PANEL_2);
        item.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        item.addActionListener(e -> open.run());
        menu.add(item);
        return item;
    }

    /**
     * The button opens its menu right-aligned under it, so it stays inside the window. A click on the button while
     * its menu is open closes the menu first (any click outside a menu does), and then reached the button and opened
     * it again: a second click must just close it.
     */
    private static void dropdown(JButton button, java.util.function.Supplier<JPopupMenu> menus) {
        long[] menuClosedAt = {0};
        button.addActionListener(e -> {
            if (System.currentTimeMillis() - menuClosedAt[0] < 250) return;
            JPopupMenu menu = menus.get();
            menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent ev) {
                }

                @Override
                public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent ev) {
                    menuClosedAt[0] = System.currentTimeMillis();
                }

                @Override
                public void popupMenuCanceled(javax.swing.event.PopupMenuEvent ev) {
                }
            });
            menu.show(button, button.getWidth() - menu.getPreferredSize().width, button.getHeight() + 4);
        });
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JPanel titles = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        titles.setOpaque(false);
        titles.add(Theme.label("DRAUPNE", Theme.headingFont(22f), Theme.TEXT));
        titles.add(Theme.label("LINK", Theme.headingFont(22f), Theme.ACCENT));
        header.add(titles, BorderLayout.WEST);
        statePill.setOpaque(true);
        statePill.setFont(Theme.headingFont(12f));
        statePill.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));
        setPill("OFFLINE", Theme.PANEL_2, Theme.MUTED);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        menuButton.setToolTipText("Setup, video, link and tools");
        setupButton.setToolTipText("First-time setup, or PC, phone or drone setup on its own");
        dropdown(setupButton, this::setupMenu);
        videoButton.setToolTipText("Bad coverage mode, camera upside down, recording, the OSD, pop out, full screen");
        dropdown(videoButton, this::videoMenu);
        linkButton.setToolTipText("Warning sounds, spectators");
        dropdown(linkButton, this::linkMenu);
        toolsButton.setToolTipText("Find the drone, the track files, which GPS, latency and motor tests, versions");
        dropdown(toolsButton, this::toolsMenu);
        updateButton.setVisible(false);
        updateButton.addActionListener(e -> startUpdate());
        popOutButton.addActionListener(e -> togglePopOut());
        // the first added sits nearest MENU: UPDATE (when there is one), then the rest, SETUP at the far left
        for (JButton b : new JButton[]{updateButton, toolsButton, linkButton, videoButton, setupButton}) headerMenu.addItem(b);
        headerMenu.finishNow();
        right.add(headerMenu);
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
        // in flight: the minimap (and the way to the map window) where the connection fields were
        JPanel mini = new JPanel(new BorderLayout(0, 8));
        mini.setOpaque(false);
        miniMap.setPreferredSize(new Dimension(300, 210));
        miniMap.setToolTipText("Where the drone is. Wheel zooms; right-click adds a pin; POP OUT MAP for the whole map");
        mini.add(miniMap, BorderLayout.CENTER);
        JButton popMap = Theme.primaryButton("Pop out map");
        popMap.setToolTipText("The map in a window of its own, for a second screen (F3)");
        popMap.addActionListener(e -> toggleMapWindow());
        mini.add(popMap, BorderLayout.SOUTH);
        mini.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        miniFold = new Fold(mini, false);
        link.add(miniFold, c);

        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        fields.add(Theme.label("CONNECTION", Theme.headingFont(11f), Theme.MUTED), c);
        fields.add(modeCombo, c);
        c.insets = new Insets(0, 0, 10, 0);
        modeHint.setPreferredSize(new Dimension(300, 52));
        modeHint.setVerticalAlignment(SwingConstants.TOP);
        fields.add(modeHint, c);
        c.insets = new Insets(0, 0, 6, 0);
        fields.add(addressLabel, c);
        addressField.putClientProperty("JTextField.placeholderText", "e.g. 100.64.0.20");
        fields.add(addressField, c);
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
        fields.add(portKey, c);
        c.insets = new Insets(0, 0, 6, 0);
        fields.add(Theme.label("CONNECT AS", Theme.headingFont(11f), Theme.MUTED), c);
        roleCombo.setSelectedItem(Connection.watchOnly(config) ? ROLE_SPECTATOR : ROLE_PILOT);
        roleCombo.setToolTipText("Pilot flies the drone. Spectator only watches another PC's flight (needs Allow spectators on that PC).");
        TacticalCombo.install(roleCombo);
        roleCombo.addActionListener(e -> {
            boolean watch = ROLE_SPECTATOR.equals(roleCombo.getSelectedItem());
            save(Map.of(Connection.CONNECT_AS_KEY, watch ? "spectator" : "pilot"));
            if (connection == null) connectButton.setText(watch ? "WATCH" : "CONNECT");
        });
        fields.add(roleCombo, c);
        fieldsFold = new Fold(fields, true);
        c.insets = new Insets(0, 0, 0, 0);
        link.add(fieldsFold, c);
        c.insets = new Insets(4, 0, 14, 0);
        connectButton.addActionListener(e -> {
            if (connection == null) connect(); else disconnect();
        });
        link.add(connectButton, c);

        // what matters in flight stays; the rest folds away with the fields
        JPanel stats = new JPanel(new GridLayout(3, 2, 8, 6));
        stats.setOpaque(false);
        addStat(stats, "PHONE BATTERY", phoneBatteryValue);
        addStat(stats, "PING", pingValue);
        addStat(stats, "FLIGHT CONTROLLER", fcValue);
        c.insets = new Insets(4, 0, 0, 0);
        link.add(stats, c);
        JPanel details = new JPanel(new GridLayout(4, 2, 8, 6));
        details.setOpaque(false);
        details.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        addStat(details, "PHONE ON TAILSCALE", phoneValue);
        addStat(details, "PHONE APP", phoneAppValue);
        addStat(details, "TELEMETRY PACKETS", telemetryValue);
        addStat(details, "RC FRAMES SENT", rcSentValue);
        detailsFold = new Fold(details, true);
        c.insets = new Insets(0, 0, 0, 0);
        link.add(detailsFold, c);
        phoneWarning.setVisible(false);
        c.insets = new Insets(8, 0, 0, 0);
        link.add(phoneWarning, c);
        phoneAppWarning.setVisible(false);
        link.add(phoneAppWarning, c);
        column.add(link, BorderLayout.NORTH);

        JPanel logCard = Theme.card(new BorderLayout(0, 8));
        JPanel logTitle = new JPanel(new BorderLayout());
        logTitle.setOpaque(false);
        logTitle.add(Theme.sectionTitle("Log"), BorderLayout.WEST);
        logTitle.add(copyLogButton(), BorderLayout.EAST);
        logCard.add(logTitle, BorderLayout.NORTH);
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

    /** A small "COPY" next to the log's title: the whole log to the clipboard, e.g. to send it to someone. */
    private JComponent copyLogButton() {
        JButton copy = textButton("COPY", "Copy the whole log to the clipboard");
        Timer reset = new Timer(1500, e -> copy.setText("COPY"));
        reset.setRepeats(false);
        copy.addActionListener(e -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(log.getText()), null);
            copy.setText("COPIED");
            reset.restart();
        });
        return copy;
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
        videoView.setToolTipText("Double-click: video only / back    F11: full screen    F2: OSD on / off");
        videoView.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 2) return;
                if (fullScreen != null) exitFullScreen();
                else if (popOut != null) popOut.setExtendedState(popOut.getExtendedState() ^ JFrame.MAXIMIZED_BOTH);
                else toggleVideoOnly();
            }
        });
        column.add(video, BorderLayout.CENTER);

        JPanel controller = new JPanel(new BorderLayout(18, 12)) {
            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                return controllerAnimHeight < 0 ? d : new Dimension(d.width, controllerAnimHeight);
            }
        };
        JPanel card = Theme.card(null); // the card look, on a panel that can fold
        controller.setBackground(card.getBackground());
        controller.setBorder(card.getBorder());
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
        TacticalCombo.install(joystickCombo);
        joystickCombo.addActionListener(e -> onJoystickChosen());
        joystickField.setOpaque(true);
        joystickField.setBackground(Theme.PANEL_2);
        joystickField.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.LINE, 1),
                BorderFactory.createEmptyBorder(0, 10, 0, 10)));
        joystickSlot.setOpaque(false);
        joystickSlot.add(joystickField, "field");
        joystickSlot.add(joystickCombo, "combo");
        joystickSlot.setPreferredSize(new Dimension(320, 32));
        joystickSlot.setMaximumSize(new Dimension(420, 32));
        topLeft.add(joystickSlot);
        topLeft.add(Box.createHorizontalStrut(10));
        // locked while connected: it shows (stripes, padlock), and a click on it says why, under it (never over the video).
        // No controller: the same kind of notice says so
        ((TacticalButton) calibrateButton).setShowLocked(true);
        LockedNotice calibrateLocked = new LockedNotice(calibrateButton, video, "CALIBRATE IS LOCKED",
                "You are connected to the drone. Press DISCONNECT first: the sticks must stop going to the drone while you calibrate.");
        LockedNotice noController = new LockedNotice(calibrateButton, video, "NO CONTROLLER",
                "Plug the radio into this PC with USB and choose 'USB Joystick (HID)' on it, then press CALIBRATE again.");
        calibrateButton.addActionListener(e -> {
            if (hasController()) {
                calibrate();
                return;
            }
            logLine("CALIBRATE needs the radio: plug it in with USB and choose 'USB Joystick (HID)' on it.");
            calibrateLocked.dismiss();
            if (!noController.pop()) calibrate(); // no room for the notice: calibrate() says it in a message box
        });
        calibrateButton.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (calibrateButton.isEnabled()) return;
                logLine("CALIBRATE is locked while connected: disconnect first (RC output must stop while calibrating).");
                noController.dismiss();
                calibrateLocked.pop();
            }
        });
        topLeft.add(calibrateButton);
        topLeft.add(Box.createHorizontalStrut(8));
        JButton rcCheckButton = Theme.secondaryButton("RC check");
        rcCheckButton.setToolTipText("Compare what this PC sends with what Betaflight reports");
        rcCheckButton.addActionListener(e -> RcCheckDialog.open(this));
        topLeft.add(rcCheckButton);
        top.add(topLeft, BorderLayout.WEST);
        minimizeButton.addActionListener(e -> {
            setControllerMinimized(!controllerMinimized, true); // also mid-fold: turns it round
            save(Map.of(MINIMIZED_KEY, Boolean.toString(controllerMinimized)));
        });
        top.add(minimizeButton, BorderLayout.EAST);
        controller.add(top, BorderLayout.NORTH);

        JPanel gimbals = new JPanel(new GridLayout(1, 2, 18, 0));
        gimbals.setOpaque(false);
        gimbals.add(leftGimbal);
        gimbals.add(rightGimbal);
        gimbals.setPreferredSize(new Dimension(440, 262));
        controller.add(gimbals, BorderLayout.WEST);
        gimbalPanel = gimbals;
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
        setControllerMinimized("true".equalsIgnoreCase(config.getString(MINIMIZED_KEY)), false);
        return column;
    }

    /** Minimized: only the controller's name and the status lines stay; the video grows into the space. */
    /**
     * @param animate fold or unfold over CONTROLLER_ANIM_MS (easing out), the video growing or shrinking with it;
     *                the stick boxes shrink or grow with the card, the channel bars are away meanwhile. The video
     *                is rescaled at each step, so only on the pilot's click, never by itself.
     */
    private void setControllerMinimized(boolean minimized, boolean animate) {
        if (controllerAnim != null) controllerAnim.stop();
        controllerMinimized = minimized;
        int from = controllerCard.getHeight();
        controllerAnimHeight = -1;
        gimbalPanel.setVisible(!minimized);
        channelBars.setVisible(!minimized);
        int to = controllerCard.getPreferredSize().height;
        minimizeButton.setText(minimized ? "EXPAND" : "MINIMIZE");
        minimizeButton.setToolTipText(minimized ? "Show the sticks and channels again" : "Hide the sticks and channels: more room for the video");
        if (!animate || !controllerCard.isShowing() || from <= 0 || from == to) {
            controllerCard.revalidate();
            controllerCard.repaint();
            return;
        }
        gimbalPanel.setVisible(true); // the stick boxes shrink with the card; hidden once folded
        channelBars.setVisible(false); // squeezed, the bars would run into the status lines under them
        controllerAnimHeight = from;
        long start = System.currentTimeMillis();
        controllerAnim = new Timer(15, e -> {
            float t = Math.min(1f, (System.currentTimeMillis() - start) / (float) CONTROLLER_ANIM_MS);
            float ease = 1 - (1 - t) * (1 - t) * (1 - t);
            controllerAnimHeight = Math.round(from + (to - from) * ease);
            if (t >= 1) {
                ((Timer) e.getSource()).stop();
                controllerAnimHeight = -1;
                gimbalPanel.setVisible(!minimized);
                channelBars.setVisible(!minimized);
            }
            controllerCard.revalidate();
            controllerCard.repaint();
        });
        controllerAnim.start();
    }

    /** A small, quiet text button (COPY, MINIMIZE): muted, red on hover. */
    private static JButton textButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setFont(Theme.headingFont(11f));
        button.setForeground(Theme.MUTED);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
        button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        button.setToolTipText(tooltip);
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                button.setForeground(Theme.ACCENT);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                button.setForeground(Theme.MUTED);
            }
        });
        return button;
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
            roleCombo.setSelectedItem(Connection.watchOnly(config) ? ROLE_SPECTATOR : ROLE_PILOT);
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
        chooseJoystick(info);
    }

    /** This controller from now on (the main window's list, or the setup's radio step); remembered by name. */
    void chooseJoystick(Joystick.Info info) {
        service.select(info.id());
        save(Map.of("joystick", info.name()));
    }

    /** @return true when a new calibration was saved */
    private boolean hasController() {
        JoystickService.Snapshot snap = service.getSnapshot();
        return snap.selected() != null && snap.state() != null;
    }

    boolean calibrate() {
        if (connection != null) return false;
        JoystickService.Snapshot snap = service.getSnapshot();
        java.awt.Window owner = javax.swing.FocusManager.getCurrentManager().getActiveWindow();
        if (snap.selected() == null || snap.state() == null) {
            JOptionPane.showMessageDialog(owner == null ? this : owner, "Plug in the radio and choose 'USB Joystick (HID)' on it first.",
                    "No controller", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        // the switch steps are named after what Drone setup put on them (ARM, flight modes, GPS rescue)
        Map<String, String> result = CalibrationDialog.run(owner == null ? this : owner, service, snap.selected(), snap.state(),
                de.droiddrone.pc.setup.ModeSwitch.purposes(config));
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
            connection.getLink().setEvents(this::logLine);
            if (video != null) video.setHardwareDecode(hardwareDecode);
            if (video != null) video.attach(connection.getLink());
            if (video != null && recordVideo) startRecording(video);
            videoView.setStatsSource(video == null ? null : video::stats);
        } catch (IOException | RuntimeException e) {
            connection = null;
            if (video != null) video.close();
            video = null;
            logLine("Connect failed: " + e.getMessage());
            JOptionPane.showMessageDialog(this, e.getMessage(), "Cannot connect", JOptionPane.WARNING_MESSAGE);
            return;
        }
        session = new RcSession(connection.getLink(), mapper, msg -> SwingUtilities.invokeLater(() -> logLine(msg)),
                ArmSwitch.parse(config.getString(ArmSwitch.KEY)));
        service.setInputListener(session::onInput);
        lastState = null;
        lastStateText = null;
        lastSpectators = 0;
        toldNotFlying = false;
        try {
            linkLog = LinkLog.open(de.droiddrone.pc.AppPaths.dataDir().resolve("logs"));
            logLine("Link log: " + shortPath(linkLog.file()));
        } catch (IOException e) {
            linkLog = null;
            logLine("No link log: " + e.getMessage());
        }
        if (track != null) track.close();
        try {
            track = new de.droiddrone.pc.map.FlightTrack(de.droiddrone.pc.map.FlightTrack.defaultDir());
        } catch (IOException e) {
            track = new de.droiddrone.pc.map.FlightTrack(null);
            logLine("No track file: " + e.getMessage());
        }
        linkLogTimer.start();
        updateFlightLayout();
        logLine("Connecting: " + mode + (mode == LinkMode.HOST_RELAY ? "" : " " + addressField.getText().trim()) + ":" + port);
        setLinkEditable(false);
    }

    private void disconnect() {
        service.setInputListener(null);
        sampleLinkLog();
        linkLogTimer.stop();
        if (track != null) track.close(pins.list()); // stays for FIND DRONE and the map
        if (connection != null) connection.close();
        connection = null;
        updateFlightLayout();
        session = null;
        if (video != null) video.close();
        video = null;
        videoView.setStatsSource(null);
        videoView.clear();
        logLine("Disconnected. The Flight phone stops RC to the FC, so Betaflight fails safe.");
        if (linkLog != null) {
            try {
                linkLog.close();
            } catch (IOException ignored) {
            }
            linkLog = null;
        }
        setLinkEditable(true);
    }

    private void sampleLinkLog() {
        LinkLog l = linkLog;
        Connection c = connection;
        de.droiddrone.pc.map.FlightTrack tr = track;
        if (c != null && tr != null) {
            de.droiddrone.pc.FcTelemetry t = c.getLink().getTelemetry();
            de.droiddrone.pc.FcTelemetry.Status st = t.getStatus();
            tr.sample(t.getGps(), t.getPhonePosition(), st != null && st.armed(), preferPhone);
        }
        if (l == null || c == null) return;
        RcSession s = session;
        VideoPipeline v = video;
        try {
            VideoPipeline.Stats vs = v == null ? null : v.stats();
            l.sample(c.getLink(), service.getSnapshot().selected() != null, s != null && s.isRcEnabled(),
                    vs == null ? 0 : vs.shownFps(), s == null ? null : s.getLastChannels(), phonePath, vs);
        } catch (IOException e) {
            linkLog = null;
            logLine("Link log stopped: " + e.getMessage());
        }
    }

    /** The map window and the minimap get the latest position and heading; idle (no link) only once a second. */
    private void refreshMap() {
        MapWindow w = mapWindow != null && mapWindow.isVisible() ? mapWindow : null;
        boolean mini = miniFold != null && miniFold.isShown();
        if (w == null && !mini) return;
        Connection c = connection;
        long now = System.currentTimeMillis();
        de.droiddrone.pc.FcTelemetry.Gps gps = null;
        int heading = -1;
        boolean fcLost = false, connected = false;
        if (c == null) {
            if (now - mapIdleUpdatedAt < 1000) return;
            mapIdleUpdatedAt = now;
        } else {
            ControlLink link = c.getLink();
            de.droiddrone.pc.FcTelemetry t = link.getTelemetry();
            de.droiddrone.pc.FcTelemetry.Attitude att = t.getAttitude();
            gps = t.getGps();
            heading = att != null && now - att.receivedAt() < 2000 ? att.headingDeg() : -1;
            fcLost = link.isFcLost();
            ControlLink.State state = link.getState();
            connected = state == ControlLink.State.READY || state == ControlLink.State.SPECTATING;
        }
        if (w != null) w.update(track, gps, heading, fcLost, connected);
        if (mini) miniMap.update(track, gps, heading, fcLost, connected);
    }

    /**
     * Connected, with the map not popped out: the connection fields and the detail figures fold away and the minimap
     * unfolds. With the map in its own window (or disconnected) the card has its fields and figures back.
     */
    private void updateFlightLayout() {
        boolean mini = connection != null && !(mapWindow != null && mapWindow.isVisible());
        fieldsFold.setShown(!mini, true);
        detailsFold.setShown(!mini, true);
        miniFold.setShown(mini, true);
        if (mini) {
            miniMap.setFollow(true);
            mapIdleUpdatedAt = 0;
        }
    }

    private MapWindow mapWindow() {
        if (mapWindow == null) {
            mapWindow = new MapWindow(this, tileCache, elevation, pins, mapSource, coordinateFormat, config.getString(MapWindow.BOUNDS_KEY), this::save,
                    this::setMapSource, this::setCoordinateFormat, this::openFindDrone);
            mapWindow.setPreferPhone(preferPhone);
            // popped out: the LINK card gets its fields and figures back; closed again in flight: the minimap returns
            mapWindow.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentShown(java.awt.event.ComponentEvent e) {
                    updateFlightLayout();
                }

                @Override
                public void componentHidden(java.awt.event.ComponentEvent e) {
                    updateFlightLayout();
                }
            });
        }
        return mapWindow;
    }

    /** MAP > Map window, F3: opens it (in front), or hides it. */
    private void toggleMapWindow() {
        MapWindow w = mapWindow();
        if (w.isVisible()) {
            w.setVisible(false);
            return;
        }
        mapIdleUpdatedAt = 0;
        w.setVisible(true);
        w.toFront();
        refreshMap();
    }

    private void setMapSource(de.droiddrone.pc.map.TileSource source) {
        mapSource = source;
        miniMap.setSource(source);
        save(Map.of(MAP_SOURCE_KEY, source == null ? de.droiddrone.pc.map.TileSource.AUTO : source.id));
        if (mapWindow != null && mapWindow.source() != source) mapWindow.setSource(source);
    }

    private void setCoordinateFormat(de.droiddrone.pc.map.Coordinates.Format format) {
        coordinateFormat = format;
        save(Map.of(COORDINATES_KEY, format.id));
        if (mapWindow != null) mapWindow.setFormat(format);
    }

    private void openFindDrone() {
        FindDroneDialog.open(this, track, tileCache, mapSource, pins);
    }

    private void setLinkEditable(boolean editable) {
        modeCombo.setEnabled(editable);
        portField.setEnabled(editable);
        keyField.setEnabled(editable);
        roleCombo.setEnabled(editable);
        joystickCombo.setEnabled(editable);
        calibrateButton.setEnabled(editable);
        calibrateButton.setToolTipText(editable ? null : "Locked while connected - disconnect first: RC output must stop while calibrating");
        connectButton.setText(editable ? (ROLE_SPECTATOR.equals(roleCombo.getSelectedItem()) ? "WATCH" : "CONNECT") : "DISCONNECT");
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

    OsdLayout osdLayout() {
        return videoView.getOsdLayout();
    }

    /** SETTINGS > OSD: shows on the live OSD at once (also in the popped-out video) and is saved. */
    void setOsdLayout(OsdLayout layout) {
        videoView.setOsdLayout(layout);
        Map<String, String> settings = layout.toSettings();
        settingsWriter.execute(() -> save(settings)); // the whole file, on every switch or drag: not on the Swing thread
    }

    /** Writes settings in the background, one after another (in order). */
    private final java.util.concurrent.ExecutorService settingsWriter = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "settingsWriter");
        t.setDaemon(true);
        return t;
    });

    private void shutdown() {
        phoneWatch.shutdownNow();
        if (popOut != null) popOut.dispose();
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
        alertSound.update(stickWarning(), System.currentTimeMillis());
        refreshMap();
        // keeps the HUD (and the latency test's time code) live even when no video frames arrive
        if (connection != null || latencyMeter != null) videoView.repaint();
    }

    private void refreshJoystickCombo(JoystickService.Snapshot snap) {
        List<Joystick.Info> list = snap.joysticks();
        // nothing to choose between: a plain field with the controller's name (or that there is none)
        boolean choice = list.size() > 1;
        joystickCards.show(joystickSlot, choice ? "combo" : "field");
        if (!choice) {
            String text = list.isEmpty() ? "No controller found - plug in the TX16S (USB Joystick)" : list.get(0).name();
            if (!text.equals(joystickField.getText())) joystickField.setText(text);
            joystickField.setForeground(list.isEmpty() ? Theme.WARN : Theme.TEXT);
            // no controller: the field's frame pulses red
            Color frame = list.isEmpty() ? Theme.alpha(Theme.ACCENT, Math.round(60 + 195 * pulse())) : Theme.LINE;
            if (!frame.equals(joystickFrame)) {
                joystickFrame = frame;
                joystickField.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(frame, 1),
                        BorderFactory.createEmptyBorder(0, 10, 0, 10)));
            }
            joystickField.setToolTipText(list.isEmpty() ? null : "The only controller found");
        }
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

    /** 0..1, about twice a second: for things that blink to get attention. */
    private static float pulse() {
        return 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 160.0);
    }

    private void refreshController(JoystickService.Snapshot snap) {
        leftGimbal.setDisconnected(snap.selected() == null);
        rightGimbal.setDisconnected(snap.selected() == null);
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
            setLabel(rcStateLabel, "NO CONTROLLER - PLUG IN THE RADIO (USB JOYSTICK)", Theme.alpha(Theme.BAD, Math.round(110 + 145 * pulse())));
        } else if (session == null) {
            setLabel(rcStateLabel, "RC OUTPUT: NOT CONNECTED", Theme.MUTED);
        } else if (session.isRcEnabled()) {
            setLabel(rcStateLabel, "RC OUTPUT: LIVE", Theme.OK);
        } else {
            setLabel(rcStateLabel, "RC OUTPUT: OFF - LOWER THROTTLE TO ENABLE", Theme.WARN);
        }

        FcTelemetry.Status fc = connection == null ? null : connection.getLink().getTelemetry().getStatus();
        if (connection != null && connection.getLink().isFcLost()) {
            setLabel(fcStateLabel, "FC: NOT CONNECTED TO THE PHONE - CHECK THE OTG CABLE", Theme.BAD);
        } else if (fc == null || System.currentTimeMillis() - fc.receivedAt() > 3000) {
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

    /** "%APPDATA%\DraupneLink\..." rather than a full path with the Windows user name in it. */
    private static String shortPath(Path file) {
        String full = file.toAbsolutePath().toString();
        String appData = System.getenv("APPDATA");
        return appData != null && full.startsWith(appData) ? "%APPDATA%" + full.substring(appData.length()) : full;
    }

    // ---------------------------------------------------------------- updates

    /** Background, at start and every 6 hours: is there a newer release on GitHub? Only tells; installs nothing. */
    private void checkForUpdate() {
        String current = de.droiddrone.pc.AppInfo.version();
        if ("dev".equals(current)) return;
        try {
            de.droiddrone.pc.setup.UpdateCheck.Release r = de.droiddrone.pc.setup.UpdateCheck.latest();
            if (!de.droiddrone.pc.setup.UpdateCheck.isNewer(r.version(), current)) return;
            SwingUtilities.invokeLater(() -> {
                if (updating) return;
                boolean first = update == null;
                update = r;
                updateButton.setText("UPDATE " + r.version());
                updateButton.setToolTipText(r.canInstall() && de.droiddrone.pc.setup.SelfUpdate.installedApp() != null
                        ? "DRAUPNE Link " + r.version() + " is out (you have " + current + "). Downloads and installs it, then starts it again."
                        : "DRAUPNE Link " + r.version() + " is out (you have " + current + "). Opens the release page.");
                updateButton.setVisible(true);
                if (first) logLine("DRAUPNE Link " + r.version() + " is available (you have " + current + "): press UPDATE at the top.");
            });
        } catch (Exception e) {
            de.droiddrone.common.Log.log("Update check: " + e.getMessage()); // offline or GitHub unreachable: try later
        }
    }

    /**
     * UPDATE: download the new installer, check it, close, install and start again. Not while connected (it closes the
     * app). A portable or development copy, or a release without a checked installer, gets the release page instead.
     */
    private void startUpdate() {
        de.droiddrone.pc.setup.UpdateCheck.Release r = update;
        if (r == null || updating) return;
        Path app = de.droiddrone.pc.setup.SelfUpdate.installedApp();
        if (!r.canInstall() || app == null) {
            PcSetupWizard.openInBrowser(this, r.pageUrl());
            return;
        }
        if (connection != null) {
            JOptionPane.showMessageDialog(this, "Disconnect first: updating closes DRAUPNE Link for a moment.",
                    "Update", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this, "<html>Update to DRAUPNE Link " + r.version() + " now?<br><br>"
                        + "It downloads the update, closes DRAUPNE Link, installs it (Windows asks for permission once)<br>"
                        + "and starts it again. Your settings stay.</html>",
                "Update", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        updating = true;
        updateButton.setText("DOWNLOADING");
        logLine("Downloading DRAUPNE Link " + r.version() + "...");
        Thread t = new Thread(() -> {
            try {
                Path installer = de.droiddrone.pc.setup.SelfUpdate.download(r, (done, total) -> SwingUtilities.invokeLater(
                        () -> updateButton.setText("DOWNLOADING " + done * 100 / total + "%")));
                SwingUtilities.invokeLater(() -> {
                    if (connection != null) {
                        updateFailed(r, "connected while it downloaded - disconnect and press UPDATE again");
                        return;
                    }
                    try {
                        de.droiddrone.pc.setup.SelfUpdate.installAndRestart(installer, app, r.installerSha256());
                        logLine("Installing DRAUPNE Link " + r.version() + ": closing now, it starts again when done.");
                        shutdown();
                    } catch (IOException ex) {
                        updateFailed(r, ex.getMessage());
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> updateFailed(r, ex.getMessage()));
            }
        }, "update");
        t.setDaemon(true);
        t.start();
    }

    private void updateFailed(de.droiddrone.pc.setup.UpdateCheck.Release r, String why) {
        updating = false;
        updateButton.setText("UPDATE " + r.version());
        logLine("Update failed: " + why);
        int answer = JOptionPane.showConfirmDialog(this, "<html>The update didn't work: " + why
                        + "<br><br>Open the release page to download it yourself?</html>",
                "Update", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.YES_OPTION) PcSetupWizard.openInBrowser(this, r.pageUrl());
    }

    // ---------------------------------------------------------------- the phone on Tailscale

    /** Background, every few seconds: is the phone (the address in the settings) on the Tailscale network? */
    private void checkPhone() {
        try {
            String ip = config.getIp();
            boolean tailscale = config.getLinkMode() == LinkMode.PHONE && !ip.isEmpty();
            Tailscale.Status status = tailscale ? Tailscale.status() : null;
            Tailscale.PhoneState state = status == null ? null : Tailscale.phoneState(status, ip);
            String path = status == null ? "" : Tailscale.phonePath(status, ip);
            if (!path.equals(phonePath) && !path.isEmpty() && !phonePath.isEmpty()) {
                String from = phonePath;
                SwingUtilities.invokeLater(() -> logLine("Tailscale path to the phone: " + from + " -> " + path
                        + (path.startsWith("relay") ? " (relayed: more delay and stalls)" : "")));
            }
            phonePath = path;
            SwingUtilities.invokeLater(() -> setPhoneState(state));
        } catch (RuntimeException e) {
            de.droiddrone.common.Log.log("Phone check: " + e);
        }
    }

    private void setPhoneState(Tailscale.PhoneState state) {
        Tailscale.PhoneState old = phoneState;
        phoneState = state;
        if (state == old) return;
        if (state != null && state.isProblem() && !isLinkUp()) {
            logLine("Warning: " + phoneProblem(state));
        } else if (state == Tailscale.PhoneState.ONLINE && old != null && old.isProblem()) {
            logLine("The phone is back on Tailscale.");
        }
    }

    private boolean isLinkUp() {
        Connection c = connection;
        return c != null && c.getLink().getState() == ControlLink.State.READY;
    }

    /** A live link proves the phone is reachable, whatever Tailscale's (slower) online flag says. */
    private Tailscale.PhoneState shownPhoneState() {
        Tailscale.PhoneState s = phoneState;
        return s != null && s.isProblem() && isLinkUp() ? Tailscale.PhoneState.ONLINE : s;
    }

    private void refreshPhone() {
        Tailscale.PhoneState s = shownPhoneState();
        if (s == null) {
            setLabel(phoneValue, "-", Theme.MUTED);
        } else {
            setLabel(phoneValue, switch (s) {
                case ONLINE -> "Online";
                case OFFLINE -> "OFFLINE";
                case NOT_FOUND -> "NOT FOUND";
                case PC_OFF -> "PC OFF TAILSCALE";
                case NOT_INSTALLED -> "NO TAILSCALE";
            }, s.isProblem() ? Theme.BAD : Theme.OK);
        }
        boolean warn = s != null && s.isProblem();
        if (warn) phoneWarning.setHtml(phoneProblem(s));
        if (phoneWarning.isVisible() != warn) {
            phoneWarning.setVisible(warn);
            phoneWarning.getParent().revalidate();
        }
    }

    private static String phoneProblem(Tailscale.PhoneState s) {
        return switch (s) {
            case OFFLINE -> "The phone is not connected to Tailscale. On the phone, open Tailscale and switch it on, and check it has mobile data.";
            case NOT_FOUND -> "The phone address is not a device on your Tailscale network. Run SETUP > Phone setup to find the phone again.";
            case PC_OFF -> "Tailscale on this PC is off or signed out. Open Tailscale on the PC and connect.";
            case NOT_INSTALLED -> "Tailscale is not installed on this PC. Run SETUP > PC setup.";
            case ONLINE -> "";
        };
    }

    /** What the HUD shows right now; null when not connected. Called on the Swing thread while painting. */
    private OsdRenderer.Data osdData() {
        Connection c = connection;
        if (c == null) return null;
        ControlLink l = c.getLink();
        VideoPipeline v = video;
        boolean spectator = l.isSpectator();
        RcSession s = spectator ? null : session;
        boolean ready = l.getState() == (spectator ? ControlLink.State.SPECTATING : ControlLink.State.READY);
        long sincePicture = videoView.msSincePicture();
        boolean videoFrozen = v != null && v.hasStream() && sincePicture > VIDEO_FROZEN_MS;
        FcTelemetry.Status st = l.getTelemetry().getStatus();
        boolean armed = st != null && st.armed() && System.currentTimeMillis() - st.receivedAt() < 2000;
        boolean radioFrozen = armed && s != null && s.isRcEnabled() && s.msSinceInputChanged() > RADIO_FROZEN_MS;
        String banner = !spectator ? null : l.isWatchOnly() ? "SPECTATOR" : "NOT FLYING - SPECTATOR";
        return new OsdRenderer.Data(true, ready, l.getPing(), spectator || (s != null && s.isRcEnabled()),
                l.isFcLost(), l.getTelemetry(), v == null ? null : v.stats(), "true".equalsIgnoreCase(config.getString("osdInvertPitch")),
                osdLinkHint(), s == null ? null : s.getLastChannels(), false, l.isLinkStalled(), videoFrozen, radioFrozen,
                s == null ? null : s.waitingFor(), v != null && v.recordingFile() != null, banner, spectator && !l.isWatchOnly());
    }

    /** No new picture for this long while the stream runs: VIDEO FROZEN. */
    private static final long VIDEO_FROZEN_MS = 700;
    /** Armed and not a single bit of the radio's input changed for this long: RADIO FROZEN? */
    private static final long RADIO_FROZEN_MS = 5000;

    private void startRecording(VideoPipeline v) {
        try {
            java.nio.file.Path dir = de.droiddrone.pc.AppPaths.videosDir();
            v.startRecording(dir);
            logLine("Recording the video to " + dir + " (from the next key frame).");
        } catch (IOException e) {
            logLine("Could not record the video: " + e.getMessage());
        }
    }

    /** The warning that stops the sticks, if one shows now: what the alert sound is about. */
    private String stickWarning() {
        Connection c = connection;
        if (c == null) return null;
        ControlLink l = c.getLink();
        if (l.isSpectator()) return null; // watching: no sticks to lose, no alarm
        if (l.getState() != ControlLink.State.READY) return "LINK LOST";
        if (l.isLinkStalled()) return "LINK STALL";
        if (l.isFcLost()) return "NO FLIGHT CONTROLLER";
        FcTelemetry.Status st = l.getTelemetry().getStatus();
        if (st != null && System.currentTimeMillis() - st.receivedAt() < 2000
                && st.blockers().stream().anyMatch(b -> b.osdName().equals("FAILSAFE"))) return "FAILSAFE";
        return null;
    }

    /** Under LINK LOST on the OSD: the reason, when Tailscale knows it. */
    private String osdLinkHint() {
        Tailscale.PhoneState s = shownPhoneState();
        if (s == null || !s.isProblem()) return null;
        return switch (s) {
            case OFFLINE -> "THE PHONE IS NOT CONNECTED TO TAILSCALE";
            case NOT_FOUND -> "THE PHONE ADDRESS IS NOT ON YOUR TAILSCALE NETWORK";
            case PC_OFF -> "TAILSCALE ON THIS PC IS OFF";
            case NOT_INSTALLED -> "TAILSCALE IS NOT INSTALLED ON THIS PC";
            case ONLINE -> null;
        };
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
        refreshPhone();
        if (connection == null) {
            videoView.setIdleText("Not connected");
            setPill("OFFLINE", Theme.PANEL_2, Theme.MUTED);
            pingValue.setText("-");
            fcValue.setText("-");
            telemetryValue.setText("-");
            rcSentValue.setText("-");
            refreshPhoneApp(null);
            return;
        }
        ControlLink link = connection.getLink();
        ControlLink.State state = link.getState();
        String stateText = connection.describe(state);
        if (state != lastState || !Objects.equals(stateText, lastStateText)) {
            logLine(stateText);
            lastState = state;
            lastStateText = stateText;
        }
        if (video != null && link.isSpectator()) video.setDrawEveryFrame(true); // watching: smooth, not least delay
        if (link.isSpectator() && !link.isWatchOnly() && !toldNotFlying) {
            toldNotFlying = true;
            tellNotFlying(link);
        }
        int spectators = link.getSpectatorCount();
        if (spectators != lastSpectators) {
            lastSpectators = spectators;
            if (spectators == 0) logLine("Nobody watches through this PC now.");
        }
        if (video == null) {
            videoView.setIdleText("Video is off (video=false in the settings)");
        } else if (state == ControlLink.State.SPECTATOR_WAITING) {
            videoView.setIdleText("Spectator: waiting for the pilot PC...");
        } else if (state == ControlLink.State.SPECTATING && !video.hasStream()) {
            videoView.setIdleText("Spectator: waiting for the pilot's video...");
        } else if (state != ControlLink.State.READY && state != ControlLink.State.SPECTATING) {
            videoView.setIdleText("Waiting for the link...");
        } else if (!video.hasStream()) {
            videoView.setIdleText("Starting the phone's camera...");
        } else {
            videoView.setIdleText("Waiting for the first key frame...");
        }
        switch (state) {
            case READY -> setPill(spectators > 0 ? "LINK READY +" + spectators + " WATCHING" : "LINK READY", Theme.OK, Theme.BG);
            case SPECTATING, SPECTATOR_WAITING -> {
                if (link.isWatchOnly()) {
                    setPill(state == ControlLink.State.SPECTATING ? "SPECTATING" : "SPECTATOR", state == ControlLink.State.SPECTATING
                            ? Theme.ACCENT : Theme.WARN, Theme.BG);
                } else {
                    setPill("NOT FLYING - SPECTATOR", Theme.BAD, Theme.BG); // wanted to fly: impossible to miss
                }
            }
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
        refreshPhoneApp(state == ControlLink.State.READY ? link.getTelemetry() : null);
    }

    /**
     * Connected as pilot, but another PC flies: this one can't, and is a spectator now. Said where it can't be missed:
     * a message to click away, the log, the red pill and a blinking line on the video. Never a takeover: it stays a
     * spectator until disconnected.
     */
    private void tellNotFlying(ControlLink link) {
        String status = link.spectatorStatus();
        String who = status != null && status.contains("(") ? status.substring(status.indexOf('(') + 1, status.indexOf(')')) : "another PC";
        logLine("YOU ARE NOT FLYING: " + who + " is the pilot of this drone. This PC was made a spectator - its sticks are not sent.");
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                "<html><div style='width:420px'><b style='font-size:16px;color:#ff5a4f'>You are not flying.</b><br><br>"
                        + "Another PC (" + who + ") is already the pilot of this drone, so this PC can't fly it.<br><br>"
                        + "It has been made a <b>spectator</b> instead: you see the video and OSD, a little behind, and your "
                        + "sticks are <b>not</b> sent.<br><br>To fly from this PC: the other PC disconnects first, then "
                        + "DISCONNECT and CONNECT here as Pilot.</div></html>",
                "Not flying - another PC is the pilot", JOptionPane.WARNING_MESSAGE));
    }

    /** The phone app's version and the phone's battery; a warning (and one log line per connection) when the app is old. */
    private void refreshPhoneApp(FcTelemetry t) {
        String newest = de.droiddrone.pc.setup.ApkShare.VERSION;
        String version = t == null ? null : t.getPhoneAppVersion();
        boolean old = t != null && (version != null ? de.droiddrone.pc.setup.UpdateCheck.isNewer(newest, version)
                : t.phoneAppSilent(System.currentTimeMillis()));
        if (t == null) {
            setLabel(phoneAppValue, "-", Theme.MUTED);
            phoneAppWarned = false;
        } else if (version == null) {
            setLabel(phoneAppValue, old ? "OLD - UPDATE IT" : "-", old ? Theme.WARN : Theme.MUTED);
        } else {
            setLabel(phoneAppValue, old ? version + " - OLD" : version, old ? Theme.WARN : Theme.OK);
        }
        int battery = t == null ? -1 : t.getPhoneBatteryPercent();
        if (battery < 0) {
            setLabel(phoneBatteryValue, "-", Theme.MUTED);
        } else {
            setLabel(phoneBatteryValue, battery + "%" + (t.isPhoneCharging() ? " charging" : ""),
                    battery < 20 ? Theme.BAD : battery < 40 ? Theme.WARN : Theme.OK);
        }
        if (old) {
            phoneAppWarning.setHtml("The phone runs an old phone app" + (version == null ? "" : " (" + version + ")")
                    + "; this PC brings " + newest + ". Update it: SETUP &gt; Phone setup, or tap the update line in the phone app.");
            if (!phoneAppWarned) {
                phoneAppWarned = true;
                logLine("Warning: the phone runs an old phone app" + (version == null ? " (older than 2.3.0-pc.5)" : " " + version)
                        + ", this PC brings " + newest + ". Update it with SETUP > Phone setup.");
            }
        }
        if (phoneAppWarning.isVisible() != old) {
            phoneAppWarning.setVisible(old);
            phoneAppWarning.getParent().revalidate();
        }
    }

    /**
     * SETTINGS > Latency test: glass-to-glass latency test. The video area shows a time code; filmed by the drone's camera and read back
     * from the video, it gives the delay from the scene to this screen. Stopping it logs the result.
     */
    private void setLatencyTest(boolean on) {
        if (on == (latencyMeter != null)) return;
        if (on) {
            latencyMeter = new de.droiddrone.pc.video.LatencyMeter();
            videoView.setLatencyMeter(latencyMeter);
            logLine("Latency test: point the drone's camera at the code on the screen. Esc or SETTINGS > Latency test to stop.");
        } else {
            logLine("Latency test, camera to screen" + (fullScreen != null ? " (full screen)" : "") + ": " + latencyMeter);
            videoView.setLatencyMeter(null);
            latencyMeter.close();
            latencyMeter = null;
        }
    }

    /** The setup guide's "Measure the delay", after Finish: connects (unless connected) and starts the latency test. */
    void testLatencyNow() {
        if (connection == null) connect();
        if (connection != null) setLatencyTest(true);
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
        java.awt.Container home = videoHome();
        home.add(videoView, BorderLayout.CENTER);
        frame.dispose();
        home.revalidate();
        home.repaint();
        java.awt.Window back = popOut != null ? popOut : this;
        back.toFront();
    }

    /** Where the video lives when not in full screen: its own window if popped out, else this one. */
    private java.awt.Container videoHome() {
        return popOut != null ? popOut.getContentPane() : videoHolder;
    }

    /**
     * POP OUT VIDEO: the picture (with its OSD) in a separate, resizable window - e.g. on a second screen - while this
     * window keeps the link, controller and log. Closing that window, or VIDEO BACK, puts it back. Same drawing path,
     * so no extra delay.
     */
    private void togglePopOut() {
        if (popOut != null) {
            popOut.dispose(); // the window listener puts the video back
            return;
        }
        if (fullScreen != null) exitFullScreen();
        JFrame frame = new JFrame(getTitle() + " - video");
        frame.setIconImages(getIconImages());
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.getContentPane().setBackground(Color.BLACK);
        frame.getContentPane().setLayout(new BorderLayout());
        java.awt.Dimension size = videoView.getSize();
        frame.getContentPane().setPreferredSize(new java.awt.Dimension(Math.max(640, size.width), Math.max(360, size.height)));
        popOut = frame;
        frame.add(videoView, BorderLayout.CENTER); // moves it out of this window
        poppedOutNote.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        videoHolder.add(poppedOutNote, BorderLayout.CENTER);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                if (popOut != frame) return;
                if (fullScreen != null) exitFullScreen();
                popOut = null;
                videoHolder.remove(poppedOutNote);
                videoHolder.add(videoView, BorderLayout.CENTER);
                videoHolder.revalidate();
                videoHolder.repaint();
                popOutButton.setText("POP OUT VIDEO");
                logLine("Video back in the main window.");
            }
        });
        frame.pack();
        frame.setLocationByPlatform(true);
        WindowReveal.onOpen(frame); // fades in, like the program's other windows
        frame.setVisible(true);
        videoHolder.revalidate();
        videoHolder.repaint();
        popOutButton.setText("VIDEO BACK");
        logLine("Video in its own window. Close it (or VIDEO BACK) to put it back.");
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
        if (!SwingUtilities.isEventDispatchThread()) { // called from link, motor and settings threads too
            SwingUtilities.invokeLater(() -> logLine(text));
            return;
        }
        log.append(LocalTime.now().format(TIME) + "  " + text + "\n");
        de.droiddrone.pc.AppLog.line(text);
        LinkLog l = linkLog;
        if (l != null) {
            try {
                l.event(text);
            } catch (IOException ignored) {
            }
        }
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
