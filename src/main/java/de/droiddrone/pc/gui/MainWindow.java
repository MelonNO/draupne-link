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
import java.util.Locale;
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
    /**
     * The track shown on the map and in FIND DRONE: the connection's once it has a position, until then the last
     * one's (a second CONNECT after losing the drone must not wipe its last known position).
     */
    private de.droiddrone.pc.map.FlightTrack track;
    /** The connection's own track, written to a GPX file as it comes; becomes {@link #track} with its first point. */
    private de.droiddrone.pc.map.FlightTrack liveTrack;
    /** Which GPS the shown position last came from, for one log line per switch; null before the first. */
    private Boolean positionFromPhone;
    /** The last time the FC reported armed; LOST DRONE shows when the link drops within a few minutes of it. */
    private long lastArmedAt;
    private final JButton lostDroneButton = Theme.primaryButton("Lost drone");
    // ---- STANDBY / DEEP SLEEP: the phone saves power before take-off and this PC wakes it (phone app pc.21 on)
    private final JButton standbyButton = Theme.secondaryButton("Standby");
    private final JButton deepSleepButton = Theme.secondaryButton("Deep sleep");
    private final JButton wakeButton = Theme.primaryButton("Wake");
    private final JComboBox<String> checkIntervalCombo = new JComboBox<>(new String[]{"1 min", "2 min", "5 min"});
    /** What the modes are doing; wraps to the card (a label would cut the line). */
    private final WrappingText powerStatusText = WrappingText.fitting(" ", Theme.font(Font.PLAIN, 12f), Theme.MUTED);
    private final PowerStatus powerStatus = new PowerStatus();
    private JPanel powerRow;
    /** STANDBY and DEEP SLEEP, folded away (animated) while the drone is armed: no sleep then. */
    private Fold sleepFold;

    /** setText on the wrapping text, with the html wrapper the old label calls used. */
    private final class PowerStatus {
        void setText(String html) {
            String h = html == null ? "" : html.replace("<html>", "").replace("</html>", "");
            powerStatusText.setHtml(h.isBlank() ? "&nbsp;" : h);
        }
    }
    /** The mode this PC asked for (ControlLink.POWER_*); the phone's PHONE_POWER record says what it is in. */
    private int powerWanted = ControlLink.POWER_NORMAL;
    private long powerSentAt, powerAskedAt;
    /** The phone said DEEP SLEEP and the link dropped: it sleeps, and this PC keeps quiet (ControlLink.setDormant). */
    private boolean phoneAsleep;
    /** When the phone went to sleep: its listening windows come every check interval from then on. */
    private long sleepStartAt;
    private int checkIntervalMin = 5;
    private static final String CHECK_INTERVAL_KEY = "phone.sleepCheckMin";
    /** The last "still here" from the sleeping phone that went to the log (one line per check). */
    private long lastHelloLogged;
    private long mapIdleUpdatedAt;
    static final String MAIN_BOUNDS_KEY = "mainWindow";
    private final MapWindow.Bounds mainBounds = new MapWindow.Bounds(this);
    private final Timer saveMainBounds = new Timer(800, e -> saveMainBounds());
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
    /** Two small drawn buttons at the edge of the picture: the video keeps nearly all the room. */
    private final GlyphButton popOutButton = new GlyphButton(GlyphButton.Glyph.POP_OUT, "Pop out video");
    private final GlyphButton fullScreenButton = new GlyphButton(GlyphButton.Glyph.FULL_SCREEN, "Full screen");
    private final JLabel poppedOutNote = Theme.label("VIDEO IS IN ITS OWN WINDOW", Theme.headingFont(16f), Theme.MUTED);
    private de.droiddrone.pc.video.LatencyMeter latencyMeter;
    private JComponent linkColumn;
    private JComponent controllerCard;
    /** The LINK card: the connection fields, in flight the minimap; the tour points at it. */
    private JComponent linkCard;
    /** The header with MENU and its slide-out: the tour keeps it clear while it walks the menu. */
    private JComponent headerPanel;
    /** The LOG card: the boot animation grows a piece of the mark into it. */
    private JComponent logCard;
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
    /** RC CHECK on the controller card; the boot animation flies a piece of the mark to it. */
    private JButton rcCheckButton;
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
                : SwingUtilities.convertRectangle(window.videoView.getParent(), window.videoView.getBounds(), window.getRootPane().getLayeredPane()),
                () -> window.boundsInPane(window.linkCard, window.logCard),       // the D's bars grow into the LINK and LOG cards
                () -> window.boundsInPane(window.controllerCard));                // its diagonals into the controller card
        window.setVisible(true);
        SwingUtilities.invokeLater(window::reportLastUpdate);
        if (firstStart) {
            // the very first start only: walk through the whole setup; after that it opens from SETUP
            window.save(Map.of(SetupWizard.OFFERED_KEY, "true"));
            SwingUtilities.invokeLater(() -> SetupWizard.open(window));
        } else if (!"true".equalsIgnoreCase(config.getString(Tour.OFFERED_KEY))) {
            // a PC set up before there was a tour (an update brought it): offered once, after the boot animation;
            // a PC still in its first-time setup gets the offer when that finishes
            if ("true".equalsIgnoreCase(config.getString(SetupWizard.DONE_KEY))) {
                javax.swing.Timer later = new javax.swing.Timer(500, e -> {
                    if (BootReveal.isShowingOn(window)) return; // not over the animation: again in half a second
                    ((javax.swing.Timer) e.getSource()).stop();
                    if (window.isShowing()) Tour.offer(window);
                });
                later.setInitialDelay(1000);
                later.start();
            } else {
                window.save(Map.of(Tour.OFFERED_KEY, "true"));
            }
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
        miniMap.setOnSourceChosen(this::setMapSource); // the minimap's right-click choice is saved and shared too
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
        // at once on opening, then every 15 minutes; its own thread, so a slow GitHub never holds up the phone check
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "updateCheck");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }).scheduleWithFixedDelay(this::checkForUpdate, 0, 15 * 60, java.util.concurrent.TimeUnit.SECONDS); // a release made while the app runs shows within a quarter of an hour
        setMinimumSize(new Dimension(1100, 760));
        // where it was last time (screen, size, maximized); the first time maximized, the size below is what un-maximizing gives
        if (!mainBounds.restore(config.getString(MAIN_BOUNDS_KEY))) {
            setSize(1280, 820);
            setLocationRelativeTo(null);
            setExtendedState(JFrame.MAXIMIZED_BOTH);
        }
        saveMainBounds.setRepeats(false);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentMoved(java.awt.event.ComponentEvent e) {
                saveMainBounds.restart();
            }

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                saveMainBounds.restart();
            }
        });
        addWindowStateListener(e -> saveMainBounds.restart());
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
        menu.addSeparator();
        addMenuItem(menu, "Tour", "The window's buttons, the map and the offline maps, one at a time", () -> Tour.start(this));
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
        menu.add(new MenuRows.ChoiceRow("Video", "SHARP: " + config.getSharpWidth() + "x" + config.getSharpHeight() + ", up to "
                + config.getSharpBitrateLimitMbps() + " Mbit/s. ROBUST: a small picture, up to 1 Mbit/s, sturdier. AUTO switches by itself",
                new String[]{"AUTO", "SHARP", "ROBUST"}, videoMode.ordinal(), i -> {
                    ControlLink.VideoMode m = ControlLink.VideoMode.values()[i];
                    save(Map.of(ControlLink.VIDEO_MODE_KEY, m.name().toLowerCase(java.util.Locale.ROOT)));
                    Connection c = connection;
                    if (c != null) c.getLink().setVideoMode(m);
                    logLine(switch (m) {
                        case AUTO -> "Video: the sharp picture after 20 s of calm at the top bitrate, bad coverage mode when the link is weak - by itself.";
                        case SHARP -> "Video: always the sharp picture - " + config.getSharpWidth() + "x" + config.getSharpHeight() + ", up to "
                                + config.getSharpBitrateLimitMbps() + " Mbit/s, whatever the link.";
                        case ROBUST -> "Video: bad coverage mode always - a small picture, up to 1 Mbit/s, key frames sent twice.";
                    });
                }));
        menu.add(new MenuRows.SwitchRow("Camera upside down", "Turns the picture 180&deg;; the OSD stays upright. No delay",
                videoView.isUpsideDown(), this::setUpsideDown));
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
        return menu; // POP OUT VIDEO and FULL SCREEN are buttons on the video card, always in sight
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
            TacticalCombo.show(menu, button, button.getWidth() - menu.getPreferredSize().width, button.getHeight() + 4);
        });
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        headerPanel = header;
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
        // the first added sits nearest MENU: TOOLS, then the rest, SETUP at the far left
        for (JButton b : new JButton[]{toolsButton, linkButton, videoButton, setupButton}) headerMenu.addItem(b);
        headerMenu.finishNow();
        right.add(headerMenu);
        // UPDATE next to MENU, outside the slide-out: an update must stay in sight with the menu folded
        right.add(updateButton);
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
        linkCard = link;
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
        link.add(powerPanel(), c);
        // the link dropped while the drone was armed: one click opens FIND DRONE and copies the MGRS to send on
        lostDroneButton.setBackground(Theme.BAD);
        lostDroneButton.setToolTipText("The link dropped while armed: the last known position in FIND DRONE, its MGRS copied to the clipboard");
        lostDroneButton.setVisible(false);
        lostDroneButton.addActionListener(e -> lostDrone());
        link.add(lostDroneButton, c);

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
        this.logCard = logCard; // the boot animation grows a piece of the mark into it
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
        // a narrow strip at the right edge of the picture with two small drawn buttons, one under the other: in
        // sight (the tour points at them, and they sit where the eye goes for window controls), the video keeps the room
        JPanel videoBar = new JPanel();
        videoBar.setLayout(new BoxLayout(videoBar, BoxLayout.Y_AXIS));
        videoBar.setBackground(Color.BLACK);
        videoBar.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        popOutButton.setToolTipText("Pop out video: the picture in a window of its own, e.g. on a second screen; again to put it back");
        fullScreenButton.setToolTipText("Full screen: the picture over the whole screen (F11); Esc or a double-click ends it");
        fullScreenButton.addActionListener(e -> toggleFullScreen());
        for (JButton b : new JButton[]{popOutButton, fullScreenButton}) b.setAlignmentX(CENTER_ALIGNMENT);
        videoBar.add(popOutButton);
        videoBar.add(Box.createVerticalStrut(4));
        videoBar.add(fullScreenButton);
        videoBar.add(Box.createVerticalGlue());
        video.add(videoBar, BorderLayout.EAST);
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
        rcCheckButton = Theme.secondaryButton("RC check");
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
        channelBars.setPurposes(de.droiddrone.pc.setup.ModeSwitch.purposes(config)); // AUX rows named after Drone setup's switches
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
            save(Map.of(DroneSetup.RADIO_REDO_KEY, "false")); // the switches Drone setup wrote are now the right ones
            rebuildMapper();
            channelBars.setPurposes(de.droiddrone.pc.setup.ModeSwitch.purposes(config));
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
        RcSession s = session;
        service.setInputListener(state -> s.onInput(sticksPaused() ? null : state)); // STANDBY: as if the radio were gone
        powerWanted = ControlLink.POWER_NORMAL;
        phoneAsleep = false;
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
        if (liveTrack != null) liveTrack.close();
        try {
            liveTrack = new de.droiddrone.pc.map.FlightTrack(de.droiddrone.pc.map.FlightTrack.defaultDir());
        } catch (IOException e) {
            liveTrack = new de.droiddrone.pc.map.FlightTrack(null);
            logLine("No track file: " + e.getMessage());
        }
        if (track == null || track.isEmpty()) track = liveTrack; // else the last flight stays shown until a fix comes
        positionFromPhone = null;
        lastArmedAt = 0;
        linkLogTimer.start();
        updateFlightLayout();
        logLine("Connecting: " + mode + (mode == LinkMode.HOST_RELAY ? "" : " " + addressField.getText().trim()) + ":" + port);
        setLinkEditable(false);
    }

    private void disconnect() {
        service.setInputListener(null);
        powerWanted = ControlLink.POWER_NORMAL;
        phoneAsleep = false;
        if (powerRow != null) powerRow.setVisible(false);
        sampleLinkLog();
        linkLogTimer.stop();
        if (liveTrack != null) liveTrack.close(pins.list()); // stays for FIND DRONE and the map
        lostDroneButton.setVisible(false);
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
        de.droiddrone.pc.map.FlightTrack tr = liveTrack;
        if (c != null && tr != null) {
            de.droiddrone.pc.FcTelemetry t = c.getLink().getTelemetry();
            de.droiddrone.pc.FcTelemetry.Status st = t.getStatus();
            tr.sample(t.getGps(), t.getPhonePosition(), st != null && st.armed(), preferPhone); // the file goes on its own thread
            de.droiddrone.pc.map.FlightTrack.Point last = tr.last();
            if (last != null) {
                if (track != tr) track = tr; // the new connection's first position: its track is the one shown now
                if (positionFromPhone == null || positionFromPhone != last.phone()) {
                    positionFromPhone = last.phone();
                    logLine(last.phone() ? "Position from the phone's GPS" + (preferPhone ? "." : " (the drone's gives none).")
                            : "Position from the drone's GPS" + (preferPhone ? " (the phone's gives none)." : "."));
                }
            }
        }
        if (l == null || c == null) return;
        RcSession s = session;
        VideoPipeline v = video;
        try {
            VideoPipeline.Stats vs = v == null ? null : v.stats();
            l.sample(c.getLink(), service.getSnapshot().selected() != null, s != null && s.isRcEnabled(),
                    vs == null ? 0 : vs.shownFps(), s == null ? null : s.getLastChannels(), phonePath, vs, service.getPollMaxMs());
        } catch (IOException e) {
            linkLog = null;
            logLine("Link log stopped: " + e.getMessage());
        }
    }

    /** The map window and the minimap get the latest position and heading; idle (no link) only once a second. */
    private void refreshMap() {
        MapWindow w = mapWindow != null && mapWindow.isShown() ? mapWindow : null;
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
        boolean mini = connection != null && !(mapWindow != null && mapWindow.isShown()); // minimized to the taskbar: not shown
        fieldsFold.setShown(!mini, true);
        detailsFold.setShown(!mini, true);
        miniFold.setShown(mini, true);
        if (mini) {
            miniMap.setFollow(true);
            mapIdleUpdatedAt = 0;
        }
    }

    MapWindow mapWindow() {
        if (mapWindow == null) {
            mapWindow = new MapWindow(this, tileCache, elevation, pins, mapSource, coordinateFormat, config.getString(MapWindow.BOUNDS_KEY), this::save,
                    this::setMapSource, this::setCoordinateFormat, this::openFindDrone);
            mapWindow.setPreferPhone(preferPhone);
            mapWindow.setOnShownChanged(this::updateFlightLayout);
            mapWindow.bindKey(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F3, 0), this::toggleMapWindow); // F3 there too
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
        if (w.isVisible() && !w.isShown()) { // minimized to the taskbar: back up, not hidden
            w.setExtendedState(w.getExtendedState() & ~JFrame.ICONIFIED);
            w.toFront();
            return;
        }
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

    private FindDroneDialog openFindDrone() {
        return FindDroneDialog.open(this, () -> track, this::isLinkUp, () -> {
            Connection c = connection;
            return c != null && c.getLink().isFcLost();
        }, tileCache, mapSource, pins);
    }

    /** LOST DRONE: FIND DRONE opens and the last known position's MGRS is on the clipboard, ready to send to a phone. */
    private void lostDrone() {
        FindDroneDialog d = openFindDrone();
        String mgrs = d.mgrs();
        if (mgrs != null) {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(mgrs), null);
            logLine("LOST DRONE: last known position " + mgrs + " copied to the clipboard.");
        } else {
            logLine("LOST DRONE: no position to show yet.");
        }
    }

    /** The link dropped (or the FC went) within a few minutes of the drone being armed: LOST DRONE comes up. */
    private void refreshLostDrone(ControlLink link, ControlLink.State state) {
        long now = System.currentTimeMillis();
        FcTelemetry.Status st = link.getTelemetry().getStatus();
        if (st != null && st.armed() && now - st.receivedAt() < 2000) lastArmedAt = now;
        boolean dropped = !link.isSpectator() && (state != ControlLink.State.READY || link.isFcLost() || link.isLinkStalled());
        boolean show = dropped && lastArmedAt != 0 && now - lastArmedAt < LOST_DRONE_WINDOW_MS && track != null && !track.isEmpty();
        if (show && !lostDroneButton.isVisible()) logLine("The link dropped while the drone was armed: LOST DRONE opens FIND DRONE with its last known position.");
        if (lostDroneButton.isVisible() != show) {
            lostDroneButton.setVisible(show);
            lostDroneButton.getParent().revalidate();
        }
    }

    /** How long after the last armed report a dropped link still counts as a lost drone. */
    private static final long LOST_DRONE_WINDOW_MS = 10 * 60_000;

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

    /** The window's place, a moment after it moved, resized, maximized or came back: off the Swing thread. */
    private void saveMainBounds() {
        String v = mainBounds.current();
        if (v != null) settingsWriter.execute(() -> save(Map.of(MAIN_BOUNDS_KEY, v)));
    }

    private void shutdown() {
        phoneWatch.shutdownNow();
        if (popOut != null) popOut.dispose();
        if (connection != null) disconnect();
        settingsWriter.shutdown(); // what is queued (the OSD layout, the window's place) reaches the file before the exit
        try {
            settingsWriter.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        de.droiddrone.pc.AppLog.drain(2000); // the last lines ("Installing ...") reach the file before the exit
        service.stop(); // the main thread leaves its polling loop and exits the app
        dispose();
    }

    /**
     * The update helper's log from the last UPDATE: once per start, says in the log (and in a pop-up when it failed)
     * what happened after the app closed - the installer that ran, or why nothing was installed.
     */
    private void reportLastUpdate() {
        String result = de.droiddrone.pc.setup.SelfUpdate.takeHelperResult();
        if (result == null) return;
        logLine("Last update: " + result);
        if (!result.startsWith("OK")) {
            JOptionPane.showMessageDialog(this, "<html>The last update did not install:<br>" + result
                    + "<br><br>Press UPDATE to try again, or download the installer from the release page.</html>",
                    "Update", JOptionPane.WARNING_MESSAGE);
        }
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
            setLabel(fcStateLabel, "FC: NOT CONNECTED TO THE PHONE - CHECK THE USB DATA CABLE", Theme.BAD);
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
        if (r == null) return;
        if (updating) {
            logLine("Update: already downloading.");
            return;
        }
        Path app = de.droiddrone.pc.setup.SelfUpdate.installedApp();
        if (!r.canInstall() || app == null) {
            String why = app == null
                    ? "This copy of DRAUPNE Link is not the installed one (it runs from " + de.droiddrone.pc.AppInfo.runningFrom()
                    + "), so it can't update itself."
                    : "This release has no installer that can be checked, so it can't be installed from here.";
            logLine("Update: " + why + " Opening the release page.");
            JOptionPane.showMessageDialog(this, "<html>" + why + "<br><br>The release page opens in your browser: download the "
                    + "installer there and run it (close DRAUPNE Link first).</html>", "Update", JOptionPane.INFORMATION_MESSAGE);
            PcSetupWizard.openInBrowser(this, r.pageUrl());
            return;
        }
        if (connection != null) {
            logLine("Update: refused while connected - disconnect first.");
            JOptionPane.showMessageDialog(this, "<html>You are connected to the drone.<br><br>Updating closes DRAUPNE Link to "
                    + "install the new version, and the sticks would stop with it.<br>Press DISCONNECT first, then UPDATE again.</html>",
                    "Update", JOptionPane.INFORMATION_MESSAGE);
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
                        logLine("Installing DRAUPNE Link " + r.version() + ": closing now, it starts again when done. The helper's log: "
                                + de.droiddrone.pc.setup.SelfUpdate.helperLog());
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
            Connection c = connection;
            if (c != null) c.getLink().setLinkPath(path); // a relay holds bad coverage mode, RELAY on the OSD
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
        long sinceStreamStart = videoView.msSinceStreamStart();
        // a stream (re)start gets a moment for its first picture (a size switch takes up to ~2 s) - but no longer
        boolean videoFrozen = v != null && v.hasStream() && sincePicture > VIDEO_FROZEN_MS
                && (sinceStreamStart < 0 || sinceStreamStart > VIDEO_RESTART_GRACE_MS || sincePicture < sinceStreamStart);
        FcTelemetry.Status st = l.getTelemetry().getStatus();
        boolean armed = st != null && st.armed() && System.currentTimeMillis() - st.receivedAt() < 2000;
        boolean radioFrozen = armed && s != null && s.isRcEnabled() && s.msSinceInputChanged() > RADIO_FROZEN_MS;
        String banner = !spectator ? null : l.isWatchOnly() ? "SPECTATOR" : "NOT FLYING - SPECTATOR";
        // the phone's own word on the sticks (pc.19+): how long ago its last stick frame reached the FC
        FcTelemetry.PhoneState ps = l.getTelemetry().getPhoneState();
        long now = System.currentTimeMillis();
        int sticksAge = ps == null || now - ps.receivedAt() > 2500 || ps.rcWriteAgeMs() < 0 ? -1
                : (int) Math.min(65_534, ps.rcWriteAgeMs() + (now - ps.receivedAt()));
        RcSession.Banner resume = s == null ? null : s.lastResumeBanner();
        String liveBanner = resume != null && now - resume.at() < RESUME_BANNER_MS ? resume.text() : null;
        if (liveBanner == null && !spectator && powerWanted == ControlLink.POWER_STANDBY) liveBanner = "STANDBY - PRESS WAKE";
        return new OsdRenderer.Data(true, ready, l.getPing(), spectator || (s != null && s.isRcEnabled()),
                l.isFcLost(), l.getTelemetry(), v == null ? null : v.stats(), "true".equalsIgnoreCase(config.getString("osdInvertPitch")),
                osdLinkHint(s), s == null ? null : s.getLastChannels(), false, l.isLinkStalled(), videoFrozen, radioFrozen,
                s == null ? null : s.waitingFor(), v != null && v.recordingFile() != null, banner, spectator && !l.isWatchOnly(),
                sticksAge, s != null && s.isArmSwitchUnknown(), liveBanner, spectator && l.isSpectatorPaused(), l.isRelayed(),
                ps != null && now - ps.receivedAt() <= 2500 && ps.rcWriteAgeMs() > OsdRenderer.STICKS_LOST_ALARM_MS);
    }

    /** A stream (re)start may take this long to show its first picture before VIDEO FROZEN says so. */
    private static final long VIDEO_RESTART_GRACE_MS = 2500;

    /** How long "LINK BACK - ARMED - STICKS LIVE" stays on the HUD. */
    private static final long RESUME_BANNER_MS = 3000;

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

    /** Under LINK LOST on the OSD: the reason, when Tailscale knows it; else that the sticks still go out. */
    private String osdLinkHint(RcSession session) {
        Tailscale.PhoneState s = shownPhoneState();
        if (s == null || !s.isProblem()) {
            return session != null && session.isRcEnabled() ? "STICKS STILL SENT - RECONNECTING" : null;
        }
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

    // ---------------------------------------------------------------- STANDBY / DEEP SLEEP

    /**
     * The power row of the LINK card, shown while connected: STANDBY (video, GPS and telemetry slowed on the phone,
     * the link stays, back in two seconds), DEEP SLEEP (the phone drops the link, locks its screen and sleeps,
     * listening for this PC for 20 s every CHECK EVERY minutes), WAKE. Neither sleep while the drone is armed.
     */
    private JPanel powerPanel() {
        powerRow = new JPanel(new GridBagLayout());
        powerRow.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 6, 0);
        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0)); // STANDBY and DEEP SLEEP side by side, WAKE under them
        buttons.setOpaque(false);
        standbyButton.setToolTipText("Before take-off: the phone stops its camera, slows its GPS and telemetry and dims its screen; the link stays. WAKE brings the picture back in about two seconds");
        standbyButton.addActionListener(e -> askPower(ControlLink.POWER_STANDBY));
        deepSleepButton.setToolTipText("The phone drops the link, locks its screen and sleeps; every CHECK EVERY minutes it listens 20 s for this PC. WAKE brings it back at its next check, with nobody touching the phone");
        deepSleepButton.addActionListener(e -> askPower(ControlLink.POWER_DEEP_SLEEP));
        wakeButton.setToolTipText("Back to normal: the camera, the GPS and the sticks");
        wakeButton.addActionListener(e -> wakePhone());
        buttons.add(standbyButton);
        buttons.add(deepSleepButton);
        sleepFold = new Fold(buttons, true); // folds away while the drone is armed, and back when it is disarmed
        powerRow.add(sleepFold, c);
        powerRow.add(wakeButton, c);
        JPanel interval = new JPanel(new BorderLayout(8, 0));
        interval.setOpaque(false);
        interval.add(Theme.label("CHECK EVERY", Theme.headingFont(11f), Theme.MUTED), BorderLayout.WEST);
        TacticalCombo.install(checkIntervalCombo);
        checkIntervalCombo.setToolTipText("How often the sleeping phone wakes to listen for this PC: more often costs a little more power");
        try {
            checkIntervalMin = Integer.parseInt(config.getString(CHECK_INTERVAL_KEY));
        } catch (NumberFormatException ignored) {
            checkIntervalMin = 5;
        }
        checkIntervalCombo.setSelectedIndex(checkIntervalMin == 1 ? 0 : checkIntervalMin == 2 ? 1 : 2);
        checkIntervalCombo.addActionListener(e -> {
            checkIntervalMin = new int[]{1, 2, 5}[Math.max(0, checkIntervalCombo.getSelectedIndex())];
            save(Map.of(CHECK_INTERVAL_KEY, Integer.toString(checkIntervalMin)));
        });
        interval.add(checkIntervalCombo, BorderLayout.CENTER);
        powerRow.add(interval, c);
        powerRow.add(powerStatusText, c);
        powerRow.setVisible(false);
        return powerRow;
    }

    /** The FC reported armed within the last two seconds. */
    private boolean fcArmedNow() {
        if (connection == null) return false;
        FcTelemetry.Status st = connection.getLink().getTelemetry().getStatus();
        return st != null && st.armed() && System.currentTimeMillis() - st.receivedAt() < 2000;
    }

    private void askPower(int mode) {
        if (demoPower(mode)) return;
        if (connection == null) return;
        if (fcArmedNow()) {
            new LockedNotice(mode == ControlLink.POWER_STANDBY ? standbyButton : deepSleepButton, videoView, "ARMED",
                    "No sleep while the drone is armed: disarm first.").pop();
            return;
        }
        powerWanted = mode;
        powerAskedAt = System.currentTimeMillis();
        powerSentAt = 0;
        logLine(mode == ControlLink.POWER_STANDBY ? "STANDBY asked: the phone stops its camera and slows down; WAKE brings it back."
                : "DEEP SLEEP asked: the phone drops the link and sleeps, listening for this PC every " + checkIntervalMin + " min.");
    }

    private void wakePhone() {
        if (demoPower(ControlLink.POWER_NORMAL)) return;
        powerWanted = ControlLink.POWER_NORMAL;
        powerAskedAt = System.currentTimeMillis();
        powerSentAt = 0;
        if (connection != null && phoneAsleep) {
            connection.getLink().setDormant(false);
            logLine("WAKE: the phone takes it at its next check (up to " + checkIntervalMin + " min), then its camera starts.");
        } else {
            logLine("WAKE asked.");
        }
    }

    /** Once a refresh while connected: keeps asking until the phone is in the mode wanted, and says where things are. */
    private void refreshPower(ControlLink link) {
        long now = System.currentTimeMillis();
        FcTelemetry.PhonePower pp = link.getTelemetry().getPhonePower();
        int reported = pp == null ? -1 : pp.mode();
        boolean ready = link.getState() == ControlLink.State.READY;
        if (!powerRow.isVisible()) powerRow.setVisible(true);
        sleepFold.setShown(!fcArmedNow(), true); // armed: the two sleep buttons slide away; disarmed: they slide back
        if (powerWanted != ControlLink.POWER_NORMAL && fcArmedNow()) {
            powerWanted = ControlLink.POWER_NORMAL;
            powerSentAt = 0;
            logLine("The drone is armed: sleep ended, the phone is woken.");
        }
        // the phone said DEEP SLEEP, then the link went: it sleeps now, and this PC must not wake it until WAKE
        if (powerWanted == ControlLink.POWER_DEEP_SLEEP && reported == ControlLink.POWER_DEEP_SLEEP && !phoneAsleep && !link.isConnected()) {
            phoneAsleep = true;
            sleepStartAt = pp.receivedAt() + pp.sleepInS() * 1000L;
            link.setDormant(true);
            logLine("The phone is in deep sleep. It listens for this PC for 20 s every " + checkIntervalMin + " min; press WAKE to bring it back.");
        }
        if (phoneAsleep) {
            if (link.isConnected()) {
                phoneAsleep = false;
                powerWanted = ControlLink.POWER_NORMAL;
                powerSentAt = 0;
                link.setDormant(false);
                link.restartVideo();
                logLine("The phone is awake again.");
            } else {
                long interval = Math.max(1, checkIntervalMin) * 60_000L;
                long next = sleepStartAt + interval;
                // the phone's own word from its last check (pc.22): when the next one is, and its battery
                ControlLink.SleepHello hello = link.getSleepHello();
                String alive = "";
                if (hello != null && hello.receivedAt() >= sleepStartAt - 5000) {
                    if (hello.nextCheckMin() > 0) next = hello.receivedAt() + hello.nextCheckMin() * 60_000L;
                    long ago = (now - hello.receivedAt()) / 1000;
                    alive = "Heard " + (ago < 60 ? ago + " s" : ago / 60 + " min") + " ago"
                            + (hello.batteryPercent() >= 0 ? ", battery " + hello.batteryPercent() + " %" : "")
                            + (hello.charging() ? " (charging)" : "") + ". ";
                    if (hello.receivedAt() != lastHelloLogged) {
                        lastHelloLogged = hello.receivedAt();
                        logLine("Phone asleep, still here: battery " + (hello.batteryPercent() >= 0 ? hello.batteryPercent() + " %" : "unknown")
                                + (hello.charging() ? ", charging" : "") + ", next check in " + hello.nextCheckMin() + " min.");
                    }
                }
                while (next < now) next += interval;
                long left = (next - now) / 1000;
                String in = String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60);
                powerStatus.setText(link.isDormant()
                        ? "<html>DEEP SLEEP - " + alive + "The phone checks for this PC every " + checkIntervalMin + " min; next check in " + in + ". WAKE brings it back.</html>"
                        : "<html>WAKE sent - " + alive + "The phone takes it at its next check, in " + in + ", then its camera starts.</html>");
                standbyButton.setEnabled(false);
                deepSleepButton.setEnabled(false);
                wakeButton.setEnabled(link.isDormant());
                checkIntervalCombo.setEnabled(false);
            }
            return;
        }
        if (ready && powerWanted != reported) {
            if (now - powerSentAt >= 200) {
                link.sendPowerMode(powerWanted, checkIntervalMin);
                powerSentAt = now;
            }
            if (pp == null && powerAskedAt != 0 && now - powerAskedAt > 10_000) {
                powerStatus.setText("<html>The phone app doesn't answer: STANDBY and DEEP SLEEP need DRAUPNE Flight 2.3.0-pc.21 or newer (tap its Update available line).</html>");
            } else if (powerWanted != ControlLink.POWER_NORMAL || reported > 0) {
                powerStatus.setText("Asking the phone...");
            } else {
                powerStatus.setText(" ");
            }
        } else if (!ready) {
            powerStatus.setText(" ");
        } else if (reported == ControlLink.POWER_STANDBY) {
            powerStatus.setText("<html>STANDBY - the phone's camera is off, its GPS and telemetry slowed, its screen dimmed. No sticks are sent. Press WAKE.</html>");
        } else if (reported == ControlLink.POWER_DEEP_SLEEP) {
            powerStatus.setText("Going to deep sleep in " + pp.sleepInS() + " s...");
        } else {
            powerStatus.setText("<html>Before take-off: STANDBY saves power and is back in 2 s; DEEP SLEEP saves more and is back at the phone's next check.</html>");
        }
        boolean idle = ready && powerWanted == ControlLink.POWER_NORMAL && reported <= 0;
        standbyButton.setEnabled(idle && !fcArmedNow());
        deepSleepButton.setEnabled(idle && !fcArmedNow());
        wakeButton.setEnabled(powerWanted != ControlLink.POWER_NORMAL || reported > 0);
        checkIntervalCombo.setEnabled(idle);
    }

    /** In STANDBY no sticks go out: the session sees "no radio" and, on waking, waits for a low throttle again. */
    private boolean sticksPaused() {
        return powerWanted != ControlLink.POWER_NORMAL || phoneAsleep;
    }

    private void refreshLink() {
        refreshPhone();
        if (connection == null) {
            videoView.setIdleText("Not connected");
            if (powerRow != null && powerRow.isVisible() && !tourPowerDemo) powerRow.setVisible(false);
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
        if (!link.isSpectator()) refreshPower(link);
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
        } else if (phoneAsleep) {
            videoView.setIdleText(link.isDormant() ? "DEEP SLEEP - the phone sleeps; press WAKE" : "Waking the phone at its next check...");
        } else if (state != ControlLink.State.READY && state != ControlLink.State.SPECTATING) {
            videoView.setIdleText("Waiting for the link...");
        } else if (powerWanted == ControlLink.POWER_STANDBY) {
            videoView.setIdleText("STANDBY - the phone's camera is off; press WAKE");
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
        refreshLostDrone(link, state);
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
            logLine("Latency test: point the drone's camera at the code on the screen. Press Esc to end it.");
        } else {
            logLine("Latency test, camera to screen" + (fullScreen != null ? " (full screen)" : "") + ": " + latencyMeter);
            videoView.setLatencyMeter(null);
            latencyMeter.close();
            latencyMeter = null;
        }
    }

    // ---------------------------------------------------------------- for the tour and the setup guide

    JComponent tourMenuButton() {
        return menuButton;
    }

    JComponent tourMinimizeButton() {
        return minimizeButton;
    }

    /** POP OUT VIDEO / VIDEO BACK on the video card: always in sight, connected or not. */
    JComponent tourPopOutButton() {
        return popOutButton;
    }

    /** FULL SCREEN next to it. */
    JComponent tourFullScreenButton() {
        return fullScreenButton;
    }

    JComponent tourLinkCard() {
        return linkCard;
    }

    /** The tour's SLEEP BUTTONS step: the power row is shown while nothing is connected, answering presses as a demo. */
    private boolean tourPowerDemo;

    JComponent tourPowerRow() {
        return powerRow;
    }

    void tourPowerDemo(boolean on) {
        if (connection != null) { // connected: the real row is there and answers for real
            tourPowerDemo = false;
            return;
        }
        tourPowerDemo = on;
        powerRow.setVisible(on);
        if (on) {
            sleepFold.setShown(true, false);
            standbyButton.setEnabled(true);
            deepSleepButton.setEnabled(true);
            wakeButton.setEnabled(false);
            powerStatus.setText("A demo while nothing is connected: press STANDBY, DEEP SLEEP or WAKE to read what the phone would do.");
        } else {
            standbyButton.setEnabled(true);
            deepSleepButton.setEnabled(true);
            wakeButton.setEnabled(true);
            powerStatus.setText(" ");
        }
        linkCard.revalidate();
        linkCard.repaint();
    }

    /** The demo's answer to a sleep button, instead of the phone's; false when the row is real. */
    private boolean demoPower(int mode) {
        if (!tourPowerDemo || connection != null) return false;
        standbyButton.setEnabled(mode == ControlLink.POWER_NORMAL);
        deepSleepButton.setEnabled(mode == ControlLink.POWER_NORMAL);
        wakeButton.setEnabled(mode != ControlLink.POWER_NORMAL);
        powerStatus.setText(switch (mode) {
            case ControlLink.POWER_STANDBY -> "<html>STANDBY - the phone would stop its camera, slow its GPS and telemetry and dim its "
                    + "screen; the link stays. WAKE brings the picture back in about two seconds.</html>";
            case ControlLink.POWER_DEEP_SLEEP -> "<html>DEEP SLEEP - the phone would drop the link, lock its screen and sleep, listening "
                    + "for this PC 20 s every " + checkIntervalMin + " min and reporting its battery. WAKE reaches it at its next check.</html>";
            default -> "<html>WAKE - the phone would be back to normal: the camera, the GPS and the sticks. Press STANDBY or DEEP SLEEP "
                    + "again, or NEXT.</html>";
        });
        return true;
    }

    JComponent tourHeader() {
        return headerPanel;
    }

    /** The controls' bounds in the layered pane, for the boot animation; null while any has no parent yet. */
    private java.awt.Rectangle[] boundsInPane(JComponent... controls) {
        java.awt.Rectangle[] r = new java.awt.Rectangle[controls.length];
        for (int i = 0; i < controls.length; i++) {
            JComponent c = controls[i];
            if (c == null || c.getParent() == null || !c.isShowing()) return null;
            r[i] = SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), getRootPane().getLayeredPane());
        }
        return r;
    }

    /** Slides the header menu out, so SETUP, VIDEO, LINK and TOOLS are in sight; nothing when it is out already. */
    void tourOpenMenu() {
        headerMenu.setOpen(true);
    }

    JComponent tourSetupButton() {
        return setupButton;
    }

    JComponent tourVideoButton() {
        return videoButton;
    }

    JComponent tourLinkButton() {
        return linkButton;
    }

    JComponent tourToolsButton() {
        return toolsButton;
    }

    /** UPDATE, next to MENU; invisible while no update is known. */
    JComponent tourUpdateButton() {
        return updateButton;
    }

    /** LOST DRONE under CONNECT; invisible until the link drops while armed. */
    JComponent tourLostDroneButton() {
        return lostDroneButton;
    }

    /** The map window in front (F3 does the same, and hides it again). */
    void showMapWindow() {
        MapWindow w = mapWindow();
        if (!w.isVisible()) toggleMapWindow();
        else w.toFront();
    }

    boolean isUpsideDown() {
        return videoView.isUpsideDown();
    }

    /** VIDEO > Camera upside down, also from the setup guide's camera check. */
    void setUpsideDown(boolean on) {
        videoView.setUpsideDown(on);
        save(Map.of(UPSIDE_DOWN_KEY, Boolean.toString(on)));
        logLine(on ? "Picture turned upside down." : "Picture the right way up.");
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
                popOutButton.setText("Pop out video");
                popOutButton.setGlyph(GlyphButton.Glyph.POP_OUT);
                logLine("Video back in the main window.");
            }
        });
        frame.pack();
        frame.setLocationByPlatform(true);
        WindowReveal.onOpen(frame); // fades in, like the program's other windows
        frame.setVisible(true);
        videoHolder.revalidate();
        videoHolder.repaint();
        popOutButton.setText("Video back");
        popOutButton.setGlyph(GlyphButton.Glyph.VIDEO_BACK);
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
