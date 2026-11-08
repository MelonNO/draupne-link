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
import de.droiddrone.pc.NameService;
import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.RcMapper;
import de.droiddrone.pc.RcSession;
import de.droiddrone.pc.audio.AudioConfig;
import de.droiddrone.pc.audio.LiveAudio;
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
    /** SETTINGS > Hardware video decoding (on by default from 1.0.50, the pilot's choice; software if the GPU can't). */
    private boolean hardwareDecode;
    /**
     * TOOLS > Advanced (1.0.50, off by default): shows the settings a pilot rarely needs - PC, phone and drone setup,
     * motion detection, the camera's view on the map, hardware decoding, the OSD and STANAG 4609. Hidden, each keeps
     * what it was set to.
     */
    static final String ADVANCED_KEY = "advanced";
    private boolean advanced;
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
    /** Where the camera looks (1.0.39): drawn on the maps, and the STANAG 4609 stream's frame centre and corners. */
    private final de.droiddrone.pc.CameraView cameraView = new de.droiddrone.pc.CameraView(tileCache);
    private final de.droiddrone.pc.CameraView.Live cameraLive = new de.droiddrone.pc.CameraView.Live(cameraView);
    private boolean cameraOnMap;
    /** The STANAG 4609 stream while it is on and the video runs; null otherwise. */
    private de.droiddrone.pc.video.StanagStream stanag;
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
    /** IT'S DOWN - RESUME (1.0.50) and the reason this PC holds back, while a drone lost armed holds it. */
    private final JButton itsDownButton = Theme.secondaryButton("It's down - resume");
    private final JLabel itsDownLine = Theme.label(" ", Theme.font(Font.PLAIN, 11f), Theme.WARN);
    // ---- STANDBY / DEEP SLEEP: the phone saves power before take-off and this PC wakes it (phone app pc.21 on)
    private final JButton standbyButton = Theme.secondaryButton("Standby");
    private final JButton deepSleepButton = Theme.secondaryButton("Deep sleep");
    private final JButton wakeButton = Theme.primaryButton("Wake");
    // ---- OVERWATCH SLEEP (1.0.47, phone app pc.36 on): deep sleep with the camera and the motion detection on; a
    // detection calls this PC, which shows it in the map window (OverwatchHub, MapWindow's card)
    private final JButton overwatchButton = Theme.secondaryButton("Overwatch");
    private final de.droiddrone.pc.overwatch.OverwatchHub overwatch = new de.droiddrone.pc.overwatch.OverwatchHub(
            new de.droiddrone.pc.overwatch.ClipIndex(de.droiddrone.pc.overwatch.ClipIndex.defaultRoot()));
    /** The token of the OVERWATCH request being made (the phone's overwatch packets carry it). */
    private int overwatchToken;
    /** The phone said OVERWATCH and the link dropped: it watches, and this PC keeps quiet as in deep sleep. */
    private volatile boolean phoneOverwatching;
    /** The drones put to overwatch, for the hub after a restart: name|token|pose;... */
    static final String OVERWATCH_KEY = "overwatch.watched";
    /** POP OUT MAP: says OVERWATCH while an alert waits and the map window is closed. */
    private JButton popMapButton;
    private boolean overwatchBadge;
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
    private volatile int powerWanted = ControlLink.POWER_NORMAL; // read on the stick thread (sticksPaused)
    private long powerSentAt, powerAskedAt;
    /** The phone said DEEP SLEEP and the link dropped: it sleeps, and this PC keeps quiet (ControlLink.setDormant). */
    private volatile boolean phoneAsleep;
    /** When the phone went to sleep: its listening windows come every check interval from then on. */
    private long sleepStartAt;
    private int checkIntervalMin = 5;
    private static final String CHECK_INTERVAL_KEY = "phone.sleepCheckMin";
    /** The last "still here" from the sleeping phone that went to the log (one line per check). */
    private long lastHelloLogged;
    /**
     * A phone left in deep sleep when this PC disconnected, or heard checking in while no link was open (1.0.41; the
     * pilot: "if a pilot sets a phone to deep sleep then disconnects there is no way to remotely wake it up"). It stays
     * on the LINK card with WAKE; its check-ins are answered "sleep on" (NameService) until WAKE connects, and the link
     * then answers the next one "wake". Kept in the settings, so it outlives a restart. Wall-clock times.
     */
    private record Asleep(String name, String ip, long since, int intervalMin, boolean overwatch) {
        Asleep(String name, String ip, long since, int intervalMin) {
            this(name, ip, since, intervalMin, false);
        }

        /** What it is doing, for the lists: OVERWATCH (1.0.47) or DEEP SLEEP. */
        String mode() {
            return overwatch ? "OVERWATCH" : "DEEP SLEEP";
        }
    }
    /** Every phone known to be asleep, by address; and its last check-in heard (receivedAt: wall clock). */
    private final Map<String, Asleep> asleepPhones = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, ControlLink.SleepHello> asleepHellos = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Earlier flights kept on the map until removed (1.0.41): when this PC connects again, the flight before stays; a
     * spectator's flight ends when the pilot moves to another phone. Saved (name>file|...), read back at the start.
     */
    private record KeptFlight(String name, java.nio.file.Path file, java.util.List<de.droiddrone.pc.map.FlightTrack.Point> points) {}
    private final java.util.List<KeptFlight> keptFlights = new java.util.ArrayList<>();
    private static final String KEPT_FLIGHTS_KEY = "map.keptFlights";
    /** The live flight's name on the map ("MobileAlpha 22:41"). */
    private String liveTrackName;
    /** Watching: the phone the pilot flew at the last look (a new one: a new flight). */
    private String watchedPhone;
    /** The other drones on the network, for the map (1 s). */
    private final Timer networkTimer = new Timer(1000, e -> refreshNetwork());
    private static final String ASLEEP_KEY = "phone.asleep";
    /** WAKE pressed while disconnected: the connect that follows wakes the phone instead of keeping quiet. */
    private boolean wakeOnConnect;
    private long pilotViewSentAt;
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
    /** The minimap on the LINK card, under the figures (1.0.39: always there; it no longer takes the fields' place). */
    private final MapView miniMap = new MapView(tileCache);
    /** The pilot's drawings on the map (Documents\DRAUPNE Link\graphics.kml), shared by the map window and the minimap. */
    private de.droiddrone.pc.map.MapGraphics graphics;
    /** Where videoView normally lives; it moves to fullScreen and back. */
    private JPanel videoHolder;
    private JFrame fullScreen;
    /** The video in its own window (POP OUT VIDEO), or null while it sits in this one. */
    private JFrame popOut;
    /** Two small drawn buttons at the edge of the picture: the video keeps nearly all the room. */
    private final GlyphButton popOutButton = new GlyphButton(GlyphButton.Glyph.POP_OUT, "Pop out video");
    private final GlyphButton fullScreenButton = new GlyphButton(GlyphButton.Glyph.FULL_SCREEN, "Full screen");
    private final GlyphButton beeperButton = new GlyphButton(GlyphButton.Glyph.BEEPER, "Beeper");
    /** MUTE (1.0.45): the phone's live sound on this PC, on or off; per phone (AUDIO_MUTED_KEY). */
    private final GlyphButton muteButton = new GlyphButton(GlyphButton.Glyph.SOUND, "Mute");
    static final String AUDIO_MUTED_KEY = "audio.muted";
    private boolean audioMuted;
    /**
     * 1.0.50: MUTE went on by itself at the arming (the pilot's rule: armed = muted, unless the pilot unmutes in the
     * air); the disarm then gives the sound back. A press of MUTE meanwhile makes it the pilot's own choice again.
     */
    private boolean audioAutoMuted;
    /** The drone read armed at the last refresh (for the arming's auto-mute). */
    private boolean audioSawArmed;
    /** The phone's sound while connected, or null. */
    private LiveAudio liveAudio;
    /** What the live audio did at the last refresh, and the states already told in the log this connection. */
    private ControlLink.AudioState lastAudioState;
    private final java.util.EnumSet<ControlLink.AudioState> audioStatesTold = java.util.EnumSet.noneOf(ControlLink.AudioState.class);
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

    /** DRAUPNE names (1.0.35): the devices seen on the Tailscale network, and the service that keeps asking them. */
    private final Names names;
    private final NameService nameService;
    private final JLabel phoneLabel = Theme.label("PHONE", Theme.headingFont(11f), Theme.MUTED);
    /** The phone to fly (as a spectator: the pilot PC to watch), by name; the address comes from the name map. */
    private final JComboBox<String> phoneCombo = new JComboBox<>();
    private final JLabel phoneHint = Theme.label("", Theme.font(Font.PLAIN, 12f), Theme.MUTED);
    private java.util.List<Names.Entry> phoneList = java.util.List.of();
    private boolean fillingPhones;
    private final JButton connectButton = Theme.primaryButton("Connect");
    /** Connect as: pilot (fly) or spectator (watch only). */
    private final JComboBox<String> roleCombo = new JComboBox<>(new String[]{ROLE_PILOT, ROLE_SPECTATOR, ROLE_OBSERVER});
    private static final String ROLE_PILOT = "Pilot - fly the drone";
    private static final String ROLE_SPECTATOR = "Spectator - watch only";
    /** OBSERVATION (1.0.48): up to six idle drones at once in the video area; CONNECT on a tile flies that one. */
    private static final String ROLE_OBSERVER = "Observation - watch idle drones";
    private static final String CONNECT_AS_OBSERVATION = "observation";
    static final String OBSERVE_VOLUME_KEY = "observe.volume";
    /** The muted tile asks its phone to stop the sound after this long (as the pilot's MUTE does). */
    private static final long OBSERVE_MUTED_STOP_MS = 10_000;
    private final JButton chooseDronesButton = Theme.secondaryButton("Choose drones");
    private ObservationGrid observationGrid;
    private volatile de.droiddrone.pc.observe.ObservationSession observation;
    private de.droiddrone.pc.audio.ObservationAudio observationAudio;
    /** The grid's feeds, in its order (the session holds the same). */
    private final java.util.List<de.droiddrone.pc.observe.TileFeed> tileFeeds = new java.util.ArrayList<>();
    /** The observed phones, for the name service's thread (a sleeping one is woken at its check-in). */
    private volatile java.util.Set<String> observedNames = java.util.Set.of();
    /** When each observed phone's tile was muted (MonoClock); absent: not muted. */
    private final Map<String, Long> observeMutedAt = new java.util.HashMap<>();
    private int hoveredTile = -1;
    private long observedMapAt;
    /** CONNECT from the grid: the pilot connection's DISCONNECT offers the grid again. */
    private boolean backToGrid;
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
    /** Connected to a phone with an older app: UPDATE PHONE APP (phone app pc.32 on: over the link, 1.0.41). */
    private final JButton updatePhoneButton = Theme.secondaryButton("Update phone app");
    /** The remote update asked for: when, and the version; 0 = none. */
    private long remoteUpdateAskedAt, remoteUpdateSentAt;
    private String remoteUpdateVersion;
    private FcTelemetry.PhoneUpdate lastPhoneUpdate;
    /** Phone apps from this one on take a remote update. */
    static final String REMOTE_UPDATE_MIN_PHONE = "2.3.0-pc.32";
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
    // the controller's status lines, beside its name in the card's top row (1.0.39, the pilot): folded, the card is that one row
    private final JLabel calibrationLabel = Theme.label(" ", Theme.font(Font.BOLD, 11f), Theme.MUTED);
    private final JLabel rcStateLabel = Theme.label(" ", Theme.headingFont(11f), Theme.MUTED);
    private final JLabel fcStateLabel = Theme.label(" ", Theme.headingFont(11f), Theme.MUTED);
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
        // all in the glass pane's own coordinates, where the animation paints: FlatLaf's glass pane starts under the
        // title bar (30 px below the layered pane), and measured in the layered pane every piece landed that much too low
        BootReveal.onOpen(window, () -> window.videoView.getParent() == null ? null
                : SwingUtilities.convertRectangle(window.videoView.getParent(), window.videoView.getBounds(), window.getRootPane().getGlassPane()),
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
        } else {
            // the tour was had before an update brought new parts: a tour of just those, offered once, after the boot animation
            java.util.List<String> fresh = Tour.unseen(config);
            if (!fresh.isEmpty() && "true".equalsIgnoreCase(config.getString(SetupWizard.DONE_KEY))) {
                javax.swing.Timer later = new javax.swing.Timer(500, e -> {
                    if (BootReveal.isShowingOn(window)) return;
                    ((javax.swing.Timer) e.getSource()).stop();
                    if (window.isShowing()) Tour.offerNew(window, fresh);
                });
                later.setInitialDelay(1000);
                later.start();
            }
        }
    }

    private MainWindow(PcConfig config, Path configFile, JoystickService service) {
        super("DRAUPNE Link " + de.droiddrone.pc.AppInfo.version());
        setIconImages(AppIcon.images());
        this.config = config;
        this.configFile = configFile;
        this.service = service;
        cameraView.configure(config);
        cameraOnMap = !"false".equalsIgnoreCase(config.getString(de.droiddrone.pc.CameraView.SHOW_KEY));
        names = new Names(config, this::save);
        nameService = new NameService(config, names, Tailscale::status, config.getPort(), true, this::save, this::logLine);
        nameService.setSleepHandler(this::onSleepCheck);
        nameService.setOverwatchSink(overwatch::offer); // the link port while no link holds it (1.0.47)
        startOverwatch();
        for (Asleep a : parseAsleep(config.getString(ASLEEP_KEY))) asleepPhones.put(a.ip(), a);
        loadKeptFlights();
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!confirmEndWhileArmed("Close DRAUPNE Link")) return;
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

        reloadSettings();
        logLine("DRAUPNE Link " + de.droiddrone.pc.AppInfo.version() + ". Phone app: DRAUPNE Flight " + de.droiddrone.pc.setup.ApkShare.VERSION
                + " (protocol " + SettingsCommon.versionCompatibleCode + "). Settings: " + shortPath(configFile));

        videoView.setOsdSource(this::osdData);
        videoView.setOsdLayout(OsdLayout.load(config::getString));
        alertSound.setEnabled(!"false".equalsIgnoreCase(config.getString(AlertSound.KEY)));
        recordVideo = "true".equalsIgnoreCase(config.getString(RECORD_KEY));
        hardwareDecode = !"false".equalsIgnoreCase(config.getString(HW_DECODE_KEY)); // on unless turned off (1.0.50)
        advanced = "true".equalsIgnoreCase(config.getString(ADVANCED_KEY));
        videoView.setOsdEnabled(!"false".equalsIgnoreCase(config.getString("osd")));
        videoView.setUpsideDown(upsideDownFor(config.getString(Names.PHONE_NAME_KEY))); // the chosen phone's (1.0.41)
        setAudioMuted(mutedFor(config.getString(Names.PHONE_NAME_KEY)), false); // the chosen phone's (1.0.45)
        mapSource = de.droiddrone.pc.map.TileSource.parse(config.getString(MAP_SOURCE_KEY));
        coordinateFormat = de.droiddrone.pc.map.Coordinates.Format.parse(config.getString(COORDINATES_KEY));
        preferPhone = "phone".equalsIgnoreCase(config.getString(GPS_SOURCE_KEY));
        try {
            pins = new de.droiddrone.pc.map.Pins(de.droiddrone.pc.map.Pins.defaultFile());
        } catch (IOException e) {
            pins = new de.droiddrone.pc.map.Pins(null);
        }
        try {
            graphics = new de.droiddrone.pc.map.MapGraphics(de.droiddrone.pc.map.MapGraphics.defaultFile());
        } catch (IOException e) {
            graphics = new de.droiddrone.pc.map.MapGraphics(null);
        }
        if (pins.brokenMovedTo() != null) logLine("pins.txt had lines that aren't pins: kept as " + pins.brokenMovedTo().getFileName() + " beside it.");
        if (graphics.brokenMovedTo() != null) logLine("graphics.kml couldn't be read: kept as " + graphics.brokenMovedTo().getFileName() + " beside it; the map starts without graphics.");
        miniMap.setPins(pins);
        miniMap.setCoordinateFormat(() -> coordinateFormat);
        miniMap.setGraphics(graphics);
        miniMap.setSource(mapSource);
        miniMap.setOnSourceChosen(this::setMapSource); // the minimap's right-click choice is saved and shared too
        // bound on the video view, not the window, so the keys also work while it is in the full-screen window
        bindKey(java.awt.event.KeyEvent.VK_F3, this::toggleMapWindow);
        bindKey(java.awt.event.KeyEvent.VK_F2, () -> {
            videoView.setOsdEnabled(!videoView.isOsdEnabled());
            save(Map.of("osd", Boolean.toString(videoView.isOsdEnabled())));
        });
        bindKey(java.awt.event.KeyEvent.VK_F11, this::toggleFullScreen);
        // M: the live sound on / off (1.0.45) - a plain letter, so not while a text field takes the typing
        bindKey(java.awt.event.KeyEvent.VK_M, () -> {
            if (!(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof javax.swing.text.JTextComponent)) toggleMute();
        });
        bindKey(java.awt.event.KeyEvent.VK_ESCAPE, () -> {
            if (latencyMeter != null) setLatencyTest(false);
            else exitFullScreen();
        });
        new Timer(33, e -> refresh()).start();
        networkTimer.start();
        phoneWatch.scheduleWithFixedDelay(this::checkPhone, 1, 5, java.util.concurrent.TimeUnit.SECONDS);
        nameService.onChange(() -> SwingUtilities.invokeLater(this::updatePhoneList)); // the PHONE list follows the network
        nameService.start();
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
        if (advanced) { // TOOLS > Advanced
            addMenuItem(menu, "PC setup", "Connection method, Tailscale, firewall, radio, Windows settings", () -> PcSetupWizard.open(this));
            addMenuItem(menu, "Phone setup", "The phone on the drone: apps, settings, connection", () -> PhoneGuideWizard.open(this));
            addMenuItem(menu, "Drone setup", "Betaflight's receiver, stick delay and flight mode switch, set over USB", () -> DroneSetup.open(this));
        }
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
        // every picture AUTO can switch to is a button of its own too (1.0.39, the pilot), and it takes at once
        String sharp = config.getSharpWidth() + "x" + config.getSharpHeight() + ", up to " + config.getSharpBitrateLimitMbps() + " Mbit/s";
        String normal = config.getCameraWidth() + "x" + config.getCameraHeight() + ", up to " + config.getBitrateLimitMbps() + " Mbit/s";
        // a short line (the long one made the menu half the screen wide, the pilot); what each one is, in the tooltip
        MenuRows.ChoiceRow videoRow = new MenuRows.ChoiceRow("Video", "AUTO picks by the link",
                new String[]{"AUTO", "SHARP", "NORMAL", "ROBUST", "SURVIVAL"}, videoMode.ordinal(), i -> {
                    ControlLink.VideoMode m = ControlLink.VideoMode.values()[i];
                    save(Map.of(ControlLink.VIDEO_MODE_KEY, m.name().toLowerCase(java.util.Locale.ROOT)));
                    Connection c = connection;
                    if (c != null) c.getLink().setVideoMode(m);
                    String when = c == null ? " From the next connection." : "";
                    logLine(switch (m) {
                        case AUTO -> "Video: AUTO - the sharp picture after 20 s of calm at the top bitrate, bad coverage mode and the survival tier when the link is weak, by itself.";
                        case SHARP -> "Video: always the sharp picture - " + sharp + ", whatever the link.";
                        case NORMAL -> "Video: always the normal picture - " + normal + ", whatever the link.";
                        case ROBUST -> "Video: bad coverage mode always - 640x360, up to 1 Mbit/s, key frames sent twice.";
                        case SURVIVAL -> "Video: the survival tier always - 480x270 at 15 fps, up to 250 kbit/s.";
                    } + when);
                });
        videoRow.setToolTipText("<html>AUTO switches by itself, by the link.<br>SHARP " + sharp + "<br>NORMAL " + normal
                + "<br>ROBUST 640x360, up to 1 Mbit/s, key frames twice<br>SURVIVAL 480x270, 15 fps, up to 250 kbit/s<br>A choice takes at once.</html>");
        menu.add(videoRow);
        menu.add(new MenuRows.SwitchRow("Camera upside down", "Turns the picture 180&deg;",
                videoView.isUpsideDown(), this::setUpsideDown));
        MenuRows.ChoiceRow motionRow = new MenuRows.ChoiceRow("Motion detection", "Disarmed: what moves, boxed",
                DETECTION_LEVELS, detectionFor(config.getString(Names.PHONE_NAME_KEY)), this::setDetection);
        motionRow.setToolTipText("<html>While the drone is disarmed the phone watches the picture and boxes what travels through it"
                + "<br>(people, cars, animals, birds - not leaves and branches in the wind; lights at night).<br>"
                + "The direction goes on the map. Per drone; phone app 2.3.0-pc.35 or newer.</html>");
        if (advanced) menu.add(motionRow); // TOOLS > Advanced
        if (advanced) menu.add(new MenuRows.SwitchRow("Camera view on the map", "The ground the camera sees, on the map and the minimap",
                cameraOnMap, on -> {
                    cameraOnMap = on;
                    save(Map.of(de.droiddrone.pc.CameraView.SHOW_KEY, Boolean.toString(on)));
                    refreshMap();
                }));
        menu.add(new MenuRows.SwitchRow("Record video", "Every flight, to Videos\\DRAUPNE Link",
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
        if (!advanced) return menu; // the rest: TOOLS > Advanced (POP OUT VIDEO and FULL SCREEN are on the video card)
        menu.add(new MenuRows.SwitchRow("Hardware video decoding", "GPU, from the next connection",
                hardwareDecode, on -> {
                    hardwareDecode = on;
                    save(Map.of(HW_DECODE_KEY, Boolean.toString(on)));
                    logLine(on ? "Hardware video decoding: from the next connection (software if the GPU can't)."
                            : "Software video decoding from the next connection.");
                }));
        menu.addSeparator();
        menu.add(new MenuRows.SwitchRow("OSD", "The HUD over the video (F2)", videoView.isOsdEnabled(), on -> {
            videoView.setOsdEnabled(on);
            save(Map.of("osd", Boolean.toString(on)));
        }));
        menu.add(new MenuRows.WindowRow("OSD layout", "Choose and place its elements",
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
        menu.add(new MenuRows.WindowRow("Name map", "Every phone and PC seen on your Tailscale network: names and addresses, this PC's name, the port and key",
                this::openNameMap));
        if (!advanced) return menu; // STANAG 4609: TOOLS > Advanced
        menu.addSeparator();
        menu.add(new MenuRows.SwitchRow("STANAG 4609 stream", "Off by default. The video with MISB metadata over UDP, for ATAK, QGIS, VLC ... (the address: settings below)",
                stanagEnabled(), on -> {
                    save(Map.of(StanagDialog.ENABLED_KEY, Boolean.toString(on)));
                    if (!on) {
                        stopStanag();
                        logLine("STANAG 4609 stream off.");
                    } else if (video == null) {
                        logLine("STANAG 4609 stream: from the next connection, to " + StanagDialog.target(config) + ".");
                    } else {
                        startStanag();
                    }
                }));
        menu.add(new MenuRows.WindowRow("STANAG 4609 settings", "Where the stream goes, mission id, country, the camera's angles",
                this::openStanagSettings));
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
        if (advanced) menu.add(new MenuRows.WindowRow("Virtual fleet", "Virtual drones on this PC, to try the window, the map,"
                + " OVERWATCH and OBSERVATION without hardware (a program of its own)", this::openVirtualFleet));
        menu.addSeparator();
        menu.add(new MenuRows.SwitchRow("Advanced", "Off by default. Shows PC, phone and drone setup, motion detection, the camera view,"
                + " hardware decoding, the OSD, STANAG 4609 and the virtual fleet",
                advanced, on -> {
                    advanced = on;
                    save(Map.of(ADVANCED_KEY, Boolean.toString(on)));
                    logLine(on ? "Advanced settings shown: SETUP, VIDEO and LINK have them all."
                            : "Advanced settings hidden (TOOLS > Advanced brings them back); each keeps what it was set to.");
                }));
        menu.addSeparator();
        JLabel about = Theme.label("PC " + de.droiddrone.pc.AppInfo.version() + "   /   PHONE APP " + de.droiddrone.pc.setup.ApkShare.VERSION,
                Theme.headingFont(11f), Theme.MUTED);
        about.setBorder(BorderFactory.createEmptyBorder(6, 14, 8, 14));
        menu.add(about);
        return menu;
    }

    /**
     * TOOLS > Virtual fleet (Advanced, 1.0.50): starts the fleet program as a process of its own - the installed exe
     * with "fleet", or from source this same Java with this classpath. Closing its window ends it; this window finds
     * its drones at the next scan. Once only: a fleet already announcing itself is left as it is.
     */
    private void openVirtualFleet() {
        if (!de.droiddrone.pc.fleet.VirtualFleet.announced().isEmpty()) {
            logLine("Virtual fleet: it already runs - its window is open (its drones are in the PHONE list, marked VIRTUAL).");
            return;
        }
        List<String> command = new java.util.ArrayList<>();
        String app = System.getProperty("jpackage.app-path");
        if (app != null) {
            command.add(app);
        } else {
            String exe = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows") ? "java.exe" : "java";
            command.add(java.nio.file.Path.of(System.getProperty("java.home"), "bin", exe).toString());
            command.add("-Dfile.encoding=UTF-8");
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(de.droiddrone.pc.Main.class.getName());
        }
        command.add("fleet");
        try {
            new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            logLine("Virtual fleet starting: its window opens in a few seconds; its drones come into the PHONE list marked VIRTUAL.");
        } catch (java.io.IOException e) {
            logLine("Virtual fleet couldn't start: " + e.getMessage());
        }
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

    /**
     * The log keeps at least LOG_MIN of the link column and the minimap takes what is left, up to MINI_MAX. Short of
     * room the log gives way, down to LOG_FLOOR, before the minimap shrinks under MINI_MIN; it goes only when even
     * that does not fit (it went far too soon before, the pilot: "the minimap sometimes disappears").
     */
    private static final int LOG_MIN = 180, LOG_FLOOR = 110, MINI_MAX = 190, MINI_MIN = 90, MINI_FLOOR = 64;
    private int miniHeight = MINI_MAX;

    private JComponent buildLinkColumn() {
        JPanel column = new JPanel(new BorderLayout(0, 14)) {
            @Override
            public void doLayout() {
                fitMiniMap(getHeight());
                super.doLayout();
            }
        };
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
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        fields.add(phoneLabel, c);
        TacticalCombo.install(phoneCombo);
        // a fixed width: the list's items change as the name service hears answers (the first about a second after the
        // start, during the boot animation), and a width that followed them moved the LINK card - and with it the
        // animation's targets - up and down
        phoneCombo.setPrototypeDisplayValue("MobileWhiskey   seen 59 min ago");
        phoneCombo.setToolTipText("The phone to fly, by its DRAUPNE name (MobileAlpha ...): every phone with DRAUPNE Flight open on your Tailscale network is here");
        phoneCombo.addActionListener(e -> {
            if (!fillingPhones) choosePhone();
        });
        fields.add(phoneCombo, c);
        // OBSERVATION: the drones are picked in a list of their own (up to six), in place of the one phone
        chooseDronesButton.setToolTipText("The drones to observe: up to six idle ones from the name map, remembered");
        chooseDronesButton.addActionListener(e -> pickDrones());
        chooseDronesButton.setVisible(false);
        fields.add(chooseDronesButton, c);
        c.insets = new Insets(0, 0, 8, 0);
        phoneHint.setPreferredSize(new Dimension(300, 36));
        phoneHint.setVerticalAlignment(SwingConstants.TOP);
        fields.add(phoneHint, c);
        c.gridwidth = 2;
        c.gridy = GridBagConstraints.RELATIVE;
        c.insets = new Insets(0, 0, 6, 0);
        fields.add(Theme.label("CONNECT AS", Theme.headingFont(11f), Theme.MUTED), c);
        roleCombo.setSelectedItem(savedRole());
        roleCombo.setToolTipText("<html>Pilot flies the drone. Spectator only watches another PC's flight (needs Allow spectators on that PC).<br>"
                + "Observation watches up to six idle drones at once - pictures, sound, the map - and CONNECT on one flies it.</html>");
        TacticalCombo.install(roleCombo);
        roleCombo.addActionListener(e -> {
            boolean watch = ROLE_SPECTATOR.equals(roleCombo.getSelectedItem());
            boolean observe = observerRole();
            save(Map.of(Connection.CONNECT_AS_KEY, observe ? CONNECT_AS_OBSERVATION : watch ? "spectator" : "pilot"));
            if (connection == null && observation == null) connectButton.setText(observe ? "OBSERVE" : watch ? "WATCH" : "CONNECT");
            updatePhoneList(); // phones to fly, PCs to watch, or the drones to observe
        });
        fields.add(roleCombo, c);
        c.insets = new Insets(0, 0, 0, 0);
        link.add(fields, c);
        linkFields = fields;
        c.insets = new Insets(4, 0, 14, 0);
        connectButton.addActionListener(e -> {
            if (observation != null) stopObservation();
            else if (connection == null && observerRole()) startObservation();
            else if (connection == null) connect();
            else if (confirmEndWhileArmed("Disconnect")) disconnect();
        });
        connectButton.setFocusable(false); // SPACE on a focused DISCONNECT must never end a flight
        link.add(connectButton, c);
        link.add(powerPanel(), c);
        // the link dropped while the drone was armed: one click opens FIND DRONE and copies the MGRS to send on
        lostDroneButton.setBackground(Theme.BAD);
        lostDroneButton.setToolTipText("The link dropped while armed: the last known position in FIND DRONE, its MGRS copied to the clipboard");
        lostDroneButton.setVisible(false);
        lostDroneButton.addActionListener(e -> lostDrone());
        link.add(lostDroneButton, c);
        // 1.0.50: a drone lost armed holds this PC's downloads, previews and clips for 10 min - the pilot who knows it
        // is down ends that here (never while a link here says armed)
        itsDownButton.setToolTipText("A drone was lost armed: map tiles, overwatch previews and clips wait for 10 min in case it flies. Press once you know it is down");
        itsDownButton.setVisible(false);
        itsDownButton.addActionListener(e -> itsDownResume(this));
        link.add(itsDownButton, c);
        itsDownLine.setVisible(false);
        link.add(itsDownLine, c);

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
        c.insets = new Insets(0, 0, 0, 0);
        link.add(details, c);
        // the minimap and the way to the map window, always: connected or not, nothing folds away for it
        JPanel mini = new JPanel(new BorderLayout(0, 8));
        mini.setOpaque(false);
        miniMap.setPreferredSize(new Dimension(300, MINI_MAX));
        miniMap.setToolTipText("Where the drone is. Wheel zooms; right-click adds a pin; POP OUT MAP for the whole map");
        mini.add(miniMap, BorderLayout.CENTER);
        JButton popMap = Theme.primaryButton("Pop out map");
        popMapButton = popMap;
        popMap.setToolTipText("The map in a window of its own, for a second screen (F3)");
        popMap.addActionListener(e -> toggleMapWindow());
        mini.add(popMap, BorderLayout.SOUTH);
        c.insets = new Insets(10, 0, 0, 0);
        link.add(mini, c);
        phoneWarning.setVisible(false);
        c.insets = new Insets(8, 0, 0, 0);
        link.add(phoneWarning, c);
        phoneAppWarning.setVisible(false);
        link.add(phoneAppWarning, c);
        updatePhoneButton.setToolTipText("The phone downloads the new phone app, checks it and installs it, over its own internet - only while the drone is disarmed. The link drops for a moment and comes back");
        updatePhoneButton.setFocusable(false);
        updatePhoneButton.setVisible(false);
        updatePhoneButton.addActionListener(e -> askRemoteUpdate());
        c.insets = new Insets(6, 0, 0, 0);
        link.add(updatePhoneButton, c);
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

    static final String BEEPER_TIP = "BEEPER: hold to make the drone beep (buzzer, and the motors while disarmed) - to find it."
            + " Disarmed only, 10 s at most a press";

    /** The log line for a BEEPER press: it beeps, or why not. */
    static String beeperPressedText(RcSession s) {
        if (s.beeperOff() != null) return s.beeperOff() + ".";
        if (!s.isRcEnabled()) return "BEEPER: no sticks go to the drone now - it can't beep.";
        return "BEEPER held - the drone beeps (AUX" + de.droiddrone.pc.setup.ModeSwitch.BEEPER_AUX
                + " up) while Betaflight reads DISARMED, " + RcSession.BEEPER_MAX_MS / 1000 + " s at most.";
    }

    /** BEEPER let go (the window lost focus): the button's press and the session's both. */
    private void releaseBeeper() {
        RcSession s = session;
        boolean held = s != null && s.isBeeperHeld();
        javax.swing.ButtonModel m = beeperButton.getModel();
        if (m.isPressed() || m.isArmed()) {
            m.setArmed(false);
            m.setPressed(false);
        }
        if (s != null) s.setBeeper(false);
        if (held) logLine("BEEPER let go: the window lost focus.");
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
        videoBar.add(Box.createVerticalStrut(4));
        // the only way a drone on the phone beeps (Drone setup silences the rest): AUX14 up while held (1.0.49; was
        // AUX12), Betaflight's BEEPER mode there beeps the buzzer and, disarmed, the motors - to find the drone in grass
        beeperButton.setToolTipText(BEEPER_TIP);
        beeperButton.setAlignmentX(CENTER_ALIGNMENT);
        beeperButton.getModel().addChangeListener(e -> {
            boolean held = beeperButton.getModel().isPressed() && beeperButton.getModel().isArmed();
            RcSession s = session;
            if (s == null || s.isBeeperHeld() == held) return;
            s.setBeeper(held);
            if (held) logLine(beeperPressedText(s));
        });
        // a dialog opening (or the pilot clicking elsewhere) while BEEPER is held never sees the release: let go
        addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowLostFocus(java.awt.event.WindowEvent e) {
                releaseBeeper();
            }
        });
        videoBar.add(beeperButton);
        videoBar.add(Box.createVerticalStrut(4));
        // the phone's microphone, heard here while the drone is disarmed (1.0.45)
        muteButton.setAlignmentX(CENTER_ALIGNMENT);
        muteButton.addActionListener(e -> toggleMute());
        muteButton.setToolTipText(muteTip(null));
        videoBar.add(muteButton);
        videoBar.add(Box.createVerticalGlue());
        video.add(videoBar, BorderLayout.EAST);
        videoView.setToolTipText("Double-click: video only / back    F11: full screen    F2: OSD on / off    M: sound on / off");
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
        JPanel states = new JPanel(new GridLayout(3, 1, 0, 0));
        states.setOpaque(false);
        states.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));
        states.add(calibrationLabel);
        states.add(rcStateLabel);
        states.add(fcStateLabel);
        top.add(states, BorderLayout.CENTER);
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
        controller.add(channelBars, BorderLayout.CENTER); // folded away with the sticks: no gap is left for it
        column.add(controller, BorderLayout.SOUTH);
        setControllerMinimized("true".equalsIgnoreCase(config.getString(MINIMIZED_KEY)), false);
        return column;
    }

    /** Minimized: only the top row stays - the controller's name, its buttons and the status lines; the video grows into the space. */
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

    /**
     * The PHONE list (as a spectator: the PILOT PC list) from the name map, online first, the saved choice selected;
     * a first device seen is taken as the choice. Called as the name service hears answers.
     */
    private void updatePhoneList() {
        boolean observe = observerRole();
        phoneCombo.setVisible(!observe);
        chooseDronesButton.setVisible(observe);
        if (observe) {
            showObserveChoice();
            return;
        }
        boolean watch = ROLE_SPECTATOR.equals(roleCombo.getSelectedItem());
        Names.Kind kind = watch ? Names.Kind.PC : Names.Kind.PHONE;
        phoneLabel.setText(watch ? "PILOT PC" : "PHONE");
        String chosen = config.getString(Names.PHONE_NAME_KEY);
        java.util.List<Names.Entry> list = names.list(kind);
        if (kind == Names.Kind.PC) list.removeIf(e -> e.name().equalsIgnoreCase(nameService.ownName()));
        long now = System.currentTimeMillis();
        int at = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).name().equalsIgnoreCase(chosen)) at = i;
        fillingPhones = true;
        try {
            phoneList = list;
            phoneCombo.removeAllItems();
            for (Names.Entry e : list) {
                Asleep sleeping = e.ip() == null || kind != Names.Kind.PHONE ? null : asleepPhones.get(e.ip());
                phoneCombo.addItem(e.name() + (e.isVirtual() ? "  VIRTUAL" : "") + (sleeping != null ? "   " + sleeping.mode() + " " + clock(nextCheck(sleeping, now) - now)
                        : e.online() ? "   online" : "   seen " + Names.agoText(e.lastSeen(), now)));
            }
            if (list.isEmpty()) phoneCombo.addItem(kind == Names.Kind.PHONE ? "No phone seen yet" : "No pilot PC seen yet");
            phoneCombo.setSelectedIndex(Math.max(0, at));
        } finally {
            fillingPhones = false;
        }
        if (at < 0 && !list.isEmpty() && connection == null) {
            choosePhone(); // the first one seen: taken, so CONNECT just works
            return;
        }
        Names.Entry e = at < 0 ? null : list.get(at);
        Asleep chosenAsleep = e == null || e.ip() == null || kind != Names.Kind.PHONE ? null : asleepPhones.get(e.ip());
        String hint = chosenAsleep != null
                ? e.name() + (chosenAsleep.overwatch() ? " overwatches" : " is in deep sleep") + ": it checks for this PC in " + clock(nextCheck(chosenAsleep, now) - now) + ". WAKE below brings it back then."
                : e == null
                ? (kind == Names.Kind.PHONE ? "Open DRAUPNE Flight on the phone, on Tailscale: it shows up here by itself."
                        : "A PC running DRAUPNE Link on your Tailscale network shows up here by itself.")
                : e.isVirtual() ? "VIRTUAL DRONE - the virtual fleet on this PC, not a real drone. " + (e.online() ? e.detail() + "  -  " + e.ip() : "Not running now.")
                : e.online() ? e.detail() + "  -  " + e.ip()
                : e.name() + " was last seen " + Names.agoText(e.lastSeen(), now) + (kind == Names.Kind.PHONE ? ": open DRAUPNE Flight on it." : ".");
        phoneHint.setText("<html>" + hint + "</html>");
    }

    /** The list's choice: saved by name, its address with it. */
    private void choosePhone() {
        int i = phoneCombo.getSelectedIndex();
        if (i < 0 || i >= phoneList.size()) return;
        Names.Entry e = phoneList.get(i);
        if (!e.name().equalsIgnoreCase(config.getString(Names.PHONE_NAME_KEY)) || !String.valueOf(e.ip()).equals(config.getIp())) {
            save(Map.of(Names.PHONE_NAME_KEY, e.name(), "ip", e.ip() == null ? "" : e.ip()));
            if (connection == null && e.kind() == Names.Kind.PHONE) videoView.setUpsideDown(upsideDownFor(e.name()));
            if (connection == null) setAudioMuted(mutedFor(e.name()), false);
        }
        updatePhoneList();
    }

    private void openNameMap() {
        new NameMapDialog(this, names, nameService, config, this::save, this::updatePhoneList).setVisible(true);
    }

    Names names() {
        return names;
    }

    NameService nameService() {
        return nameService;
    }

    /** Shows the saved settings again (the setup wizards change them). */
    void reloadSettings() {
        channelBars.setPurposes(de.droiddrone.pc.setup.ModeSwitch.purposes(config)); // AUX rows named after Drone setup's switches
        if (connection == null) {
            roleCombo.setSelectedItem(savedRole());
            updatePhoneList();
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
        if (observation != null) return; // CONNECT on a tile stops the observation first
        if (mapper == null) {
            JOptionPane.showMessageDialog(this, "Controller mapping is not usable: " + mapperError + "\nRun Calibrate first.",
                    "Cannot connect", JOptionPane.WARNING_MESSAGE);
            return;
        }
        boolean watch = ROLE_SPECTATOR.equals(roleCombo.getSelectedItem());
        Names.Entry target = names.get(config.getString(Names.PHONE_NAME_KEY));
        if (target == null || target.kind() != (watch ? Names.Kind.PC : Names.Kind.PHONE) || target.ip() == null || target.ip().isEmpty()) {
            JOptionPane.showMessageDialog(this, watch ? "Pick the pilot PC to watch first: it shows up in the list once DRAUPNE Link runs on it."
                    : "Pick the phone first: it shows up in the list once DRAUPNE Flight is open on it, on Tailscale.", "Cannot connect", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (refuseDuplicate(target)) return;
        if (!save(Map.of("ip", target.ip()))) return;
        int port = config.getPort();
        nameService.pause(); // the link takes the port, and answers name queries itself meanwhile
        boolean wantVideo = !"false".equalsIgnoreCase(config.getString("video"));
        ControlLink[] linkRef = new ControlLink[1];
        video = wantVideo ? new VideoPipeline(videoView, () -> linkRef[0] == null ? -1 : linkRef[0].getPing()) : null;
        try {
            connection = Connection.open(config, video);
            linkRef[0] = connection.getLink();
            ControlLink flown = connection.getLink();
            // this PC's "possibly flying" flag (1.0.49): every background download waits while this drone may fly
            // kept per drone (1.0.50): a reconnect to it that hears DISARMED ends a lost-drone window at once
            de.droiddrone.pc.PossiblyFlying.pc().attach(flown, target.name(), () ->de.droiddrone.pc.PossiblyFlying.of(flown.getTelemetry().getStatus()));
            connection.getLink().setNameReply(nameService::replyPacket);
            connection.getLink().setEvents(this::logLine);
            startLiveAudio(connection.getLink(), video, target.name());
            if (video != null) video.setHardwareDecode(hardwareDecode);
            if (video != null) video.attach(connection.getLink());
            if (video != null && recordVideo) startRecording(video);
            if (video != null && stanagEnabled()) startStanag();
            videoView.setStatsSource(video == null ? null : video::stats);
        } catch (IOException | RuntimeException e) {
            if (linkRef[0] != null) de.droiddrone.pc.PossiblyFlying.pc().detach(linkRef[0]);
            connection = null;
            stopLiveAudio();
            nameService.resume();
            if (video != null) video.close();
            video = null;
            logLine("Connect failed: " + e.getMessage());
            JOptionPane.showMessageDialog(this, e.getMessage(), "Cannot connect", JOptionPane.WARNING_MESSAGE);
            return;
        }
        session = new RcSession(connection.getLink(), mapper, msg -> SwingUtilities.invokeLater(() -> logLine(msg)),
                ArmSwitch.parse(config.getString(ArmSwitch.KEY)));
        RcSession s = session;
        s.setFrozenStopMs(config.getRadioFrozenStopS() * 1000L);
        // BEEPER only where Drone setup put it, and never on a channel ARM or the radio uses (1.0.49)
        s.setBeeperSetUp("true".equalsIgnoreCase(config.getString(RcSession.BEEPER_KEY)));
        beeperButton.setToolTipText(s.beeperOff() == null ? BEEPER_TIP : s.beeperOff());
        if (!watch && s.beeperOff() != null && !s.beeperOff().equals(RcSession.BEEPER_NOT_SET_UP)) logLine(s.beeperOff() + ".");
        connection.getLink().setMaxStickLagMs(config.getMaxStickLagMs());
        if (!watch) connection.getLink().setDetection(detectionFor(target.name())); // the drone's own (1.0.46)
        // STANDBY / DEEP SLEEP: no sticks, and a WAKE starts with the throttle-low rule (not a radio blip's quick resume)
        service.setInputListener(state -> {
            if (sticksPaused()) s.onPaused(state); else s.onInput(state);
        });
        if (!watch && de.droiddrone.pc.WindowsTuning.keepAwake(true)) logLine("Windows and the screen stay awake while connected.");
        powerWanted = ControlLink.POWER_NORMAL;
        phoneAsleep = false;
        lastState = null;
        lastStateText = null;
        lastSpectators = 0;
        toldNotFlying = false;
        connection.getLink().setForeignSleepHandler(this::onSleepCheck); // another phone's check-in: sleep on
        connection.getLink().setOverwatchSink(overwatch::offer); // the link holds the link port now (1.0.47)
        phoneOverwatching = false;
        // the flight before stays on the map; this one is the live one
        keepFlight(track, liveTrackName);
        liveTrackName = flightName(watch ? null : target.name());
        watchedPhone = null;
        if (!watch) videoView.setUpsideDown(upsideDownFor(target.name())); // the phone's own way up (1.0.41)
        setVirtualShown(!watch && target.isVirtual());
        Asleep sleeping = target.ip() == null ? null : asleepPhones.get(target.ip());
        ControlLink.SleepHello lastCheck = sleeping == null ? null : asleepHellos.get(sleeping.ip());
        if (sleeping != null && target.online() && (lastCheck == null || System.currentTimeMillis() - lastCheck.receivedAt() > 60_000)
                && System.currentTimeMillis() - sleeping.since() > 60_000) {
            clearAsleep(sleeping.ip()); // it answers name queries and hasn't checked in for a minute: woken on the phone
            sleeping = null;
        }
        if (!watch && sleeping != null && sleeping.ip().equals(target.ip())) {
            // the phone sleeps: quiet until WAKE (a Connect in its listening window would wake it), or woken now
            phoneAsleep = true;
            phoneOverwatching = sleeping.overwatch();
            sleepStartAt = de.droiddrone.pc.MonoClock.ms() - (System.currentTimeMillis() - sleeping.since());
            if (wakeOnConnect) {
                logLine("WAKE: " + sleeping.name() + " takes it at its next check (up to " + sleeping.intervalMin() + " min), then its camera starts.");
            } else {
                connection.getLink().setDormant(true);
                logLine(sleeping.name() + (sleeping.overwatch() ? " overwatches" : " is in deep sleep") + ": this PC stays quiet until WAKE.");
            }
        }
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
        track = liveTrack; // the flight before is a kept one now (1.0.41)
        pushKept();
        positionFromPhone = null;
        lastArmedAt = 0;
        linkLogTimer.start();
        updateFlightLayout();
        logLine((watch ? "Watching " : "Connecting to ") + target.name() + (target.isVirtual() ? " (VIRTUAL)" : "") + " (" + target.ip() + ":" + de.droiddrone.pc.fleet.VirtualFleet.portOf(target.ip(), port) + ")" + (target.online() ? "" : " - last seen " + Names.agoText(target.lastSeen(), System.currentTimeMillis())));
        setLinkEditable(false);
    }

    /**
     * M3 (1.0.49): two devices answer with the target's name (two PCs that both acted as the names master can each
     * have named a phone MobileAlpha). CONNECT could reach the wrong drone, so it is refused, and the pilot is offered
     * to give the other one a free name (NAME_ASSIGN; a phone must be online to take it).
     * @return true when refused
     */
    private boolean refuseDuplicate(Names.Entry target) {
        java.util.List<String> ips = nameService.duplicateOf(target.name());
        if (ips == null) return false;
        java.util.List<String> others = new java.util.ArrayList<>(ips);
        others.remove(target.ip());
        String free = target.kind() == Names.Kind.PHONE ? names.firstFree(Names.Kind.PHONE, java.util.List.of()) : null;
        logLine("CONNECT refused: " + target.name() + " answers from " + String.join(" and ", ips) + ".");
        String text = "Two devices answer as " + target.name() + ": " + String.join(" and ", ips) + ".\n"
                + "CONNECT could reach the wrong drone, so it is refused until one of them has another name.";
        if (free == null || others.isEmpty()) {
            JOptionPane.showMessageDialog(this, text + "\nRename one in the name map (the PHONE list's NAME MAP).", "Cannot connect", JOptionPane.WARNING_MESSAGE);
            return true;
        }
        String other = others.get(0);
        String[] options = {"Rename " + other + " to " + free, "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, text + "\n\n" + target.name() + " in the name map is " + target.ip()
                        + (target.detail().isEmpty() ? "" : " (" + target.detail() + ")") + ".\nGive the phone at " + other + " the free name " + free + "?",
                "Cannot connect", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (choice == 0) {
            nameService.assign(other, free);
            logLine("The phone at " + other + " is asked to be " + free + "; CONNECT again once the list shows it.");
        }
        return true;
    }

    /** The OSD and the maps say VIRTUAL while the link is to a drone of the virtual fleet. */
    private void setVirtualShown(boolean on) {
        videoView.setVirtual(on);
        miniMap.setVirtualDrone(on);
        if (mapWindow != null) mapWindow.map().setVirtualDrone(on);
    }

    private void disconnect() {
        if (phoneAsleep && connection != null && !connection.getLink().isSpectator()) {
            String name = config.getString(Names.PHONE_NAME_KEY), ip = config.getString("ip");
            if (!ip.isEmpty()) {
                long since = System.currentTimeMillis() - (de.droiddrone.pc.MonoClock.ms() - sleepStartAt);
                rememberAsleep(new Asleep(name, ip, since, checkIntervalMin, phoneOverwatching));
                logLine(name + (phoneOverwatching ? " overwatches on: its alerts still come to this PC's map." : " sleeps on.")
                        + " This PC still answers its checks; WAKE on the LINK card brings it back.");
            }
        }
        service.setInputListener(null);
        remoteUpdateAskedAt = 0; // a remote update in flight: the phone goes on with it, this PC stops following it
        updatePhoneButton.setText("Update phone app");
        updatePhoneButton.setEnabled(true);
        de.droiddrone.pc.WindowsTuning.keepAwake(false);
        powerWanted = ControlLink.POWER_NORMAL;
        phoneAsleep = false;
        phoneOverwatching = false;
        if (powerRow != null) powerRow.setVisible(false);
        sampleLinkLog();
        linkLogTimer.stop();
        if (liveTrack != null) liveTrack.close(pins.list()); // stays for FIND DRONE and the map
        keepFlight(liveTrack, liveTrackName); // remembered; shown as the live one until the next connection
        lostDroneButton.setVisible(false);
        if (connection != null) {
            de.droiddrone.pc.PossiblyFlying.pc().detach(connection.getLink()); // armed at the end: the flag stays for the lost-drone window
            connection.close();
        }
        connection = null;
        setVirtualShown(false);
        stopLiveAudio();
        nameService.resume();
        updateFlightLayout();
        session = null;
        stopStanag();
        if (video != null) video.close();
        video = null;
        videoView.setStatsSource(null);
        videoView.clear();
        logLine("Disconnected. The Flight phone stops RC to the FC, so Betaflight fails safe.");
        if (backToGrid) {
            backToGrid = false;
            SwingUtilities.invokeLater(this::offerBackToGrid);
        }
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
        boolean mini = miniMap.isShowing();
        if (w == null && !mini) return;
        Connection c = connection;
        long now = de.droiddrone.pc.MonoClock.ms();
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
        de.droiddrone.pc.map.CameraFootprint.View view = null;
        VideoPipeline v = video;
        int[] size = v == null ? null : v.pictureSize();
        if (size != null) cameraView.setPictureSize(size[0], size[1]);
        // worked out five times a second off this thread (1.0.49: its terrain steps ran here every 33 ms)
        cameraLive.want(c != null && cameraOnMap && !fcLost ? c.getLink().getTelemetry() : null, track);
        view = cameraLive.latest();
        if (w != null) w.setCameraView(view);
        miniMap.setCameraView(view);
        List<de.droiddrone.pc.DetectionMarks.Mark> marks = c == null ? detectionMarks.marks(now) : detectionMarks(c.getLink().getTelemetry(), now);
        if (w != null) w.setDetections(marks);
        miniMap.setDetections(marks);
        if (w != null) w.update(track, gps, heading, fcLost, connected);
        if (mini) miniMap.update(track, gps, heading, fcLost, connected);
    }

    // ------------------------------------------------------------------ motion detection (1.0.46)

    /** The motion detection's marks on the map: a line along each detection's direction, its estimated ground point. */
    private final de.droiddrone.pc.DetectionMarks detectionMarks = new de.droiddrone.pc.DetectionMarks();
    private long motionIdsSeen, motionSaidAt;

    /**
     * The boxes of the phone's latest DETECTION record onto the map: each one's ray from the camera's pose now (the
     * picture turned as the screen shows it), its mark following it. A new thing seen goes to the log (once in 10 s).
     */
    private List<de.droiddrone.pc.DetectionMarks.Mark> detectionMarks(de.droiddrone.pc.FcTelemetry t, long now) {
        de.droiddrone.pc.MotionBoxes motion = t.getMotion();
        boolean flipX = videoView.isMirror() != videoView.isUpsideDown(), flipY = videoView.isUpsideDown();
        de.droiddrone.pc.map.CameraFootprint.Pose pose = null;
        for (de.droiddrone.pc.MotionBoxes.Shown s : motion.shown(now)) {
            if (now - s.seenAt() > de.droiddrone.pc.MotionBoxes.SOLID_MS) continue; // only what the latest record had
            if (pose == null) pose = cameraView.sittingPose(t, track);
            if (pose == null) break; // no position or heading: no line to draw
            double[] uv = de.droiddrone.pc.DetectionMarks.pictureUv(s.box(), flipX, flipY);
            detectionMarks.put(s.box().id, s.box().light(), pose.lat(), pose.lon(), cameraView.ray(t, track, uv[0], uv[1]), now);
        }
        long ids = motion.newIds();
        if (ids > motionIdsSeen) {
            if (now - motionSaidAt > 10_000) {
                logLine("MOTION: something travels in the picture (" + (ids - motionIdsSeen) + " new) - see the boxes on the video and the map.");
                motionSaidAt = now;
            }
            motionIdsSeen = ids;
        }
        return detectionMarks.marks(now);
    }

    static final String DETECTION_KEY = "detection";
    static final String[] DETECTION_LEVELS = {"OFF", "LOW", "NORMAL", "HIGH"};

    /** The phone's own motion detection setting (0 off .. 3 high); never set: NORMAL. */
    private int detectionFor(String phone) {
        String v = phone == null || phone.isEmpty() ? "" : config.getString(DETECTION_KEY + "." + phone);
        if (v.isEmpty()) v = config.getString(DETECTION_KEY);
        for (int i = 0; i < DETECTION_LEVELS.length; i++) if (DETECTION_LEVELS[i].equalsIgnoreCase(v.trim())) return i;
        return 2;
    }

    /** VIDEO > Motion detection: saved for the chosen phone (the drone), told to it within a second. */
    private void setDetection(int level) {
        String phone = config.getString(Names.PHONE_NAME_KEY);
        save(Map.of(phone.isEmpty() ? DETECTION_KEY : DETECTION_KEY + "." + phone, DETECTION_LEVELS[level].toLowerCase(Locale.ROOT)));
        Connection c = connection;
        if (c != null && !c.getLink().isSpectator()) {
            c.getLink().setDetection(level);
            if (!c.getLink().detectionSupported() && c.getLink().getTelemetry().getPhonePcBuild() >= 0) {
                logLine("Motion detection: the phone app is older than 2.3.0-pc.35 - update it to use this.");
                return;
            }
        }
        logLine(level == 0 ? "Motion detection off." : "Motion detection " + DETECTION_LEVELS[level] + ": while the drone is disarmed the phone marks what travels through the picture (not swaying trees).");
    }

    /**
     * Connected, with the map not popped out: the minimap follows the drone. (Up to 1.0.38 the connection fields and
     * the detail figures folded away for it; the minimap now has its own place on the LINK card.)
     */
    private void updateFlightLayout() {
        boolean flying = connection != null && !(mapWindow != null && mapWindow.isShown()); // minimized to the taskbar: not shown
        if (flying) {
            miniMap.setFollow(true);
            mapIdleUpdatedAt = 0;
        }
    }

    /**
     * The minimap's height for a link column this tall: the LINK card's other rows, the gap and the log's LOG_MIN come
     * first. Too little left and the minimap goes (POP OUT MAP stays) - the log was pushed out of the window otherwise.
     */
    private void fitMiniMap(int columnHeight) {
        if (linkCard == null || columnHeight <= 0) return;
        int others = linkCard.getPreferredSize().height - (miniMap.isVisible() ? miniHeight : 0);
        int room = columnHeight - 14 - LOG_MIN - others;
        int h = Math.min(MINI_MAX, room);
        if (h < MINI_MIN) h = Math.min(MINI_MIN, columnHeight - 14 - LOG_FLOOR - others); // the log gives way first
        if (h < MINI_FLOOR) h = 0;
        if (h == miniHeight && miniMap.isVisible() == (h > 0)) return;
        miniHeight = h;
        miniMap.setPreferredSize(new Dimension(300, Math.max(1, h)));
        miniMap.setVisible(h > 0);
        linkCard.invalidate();
    }

    MapWindow mapWindow() {
        if (mapWindow == null) {
            mapWindow = new MapWindow(this, tileCache, elevation, pins, graphics, mapSource, coordinateFormat, config.getString(MapWindow.BOUNDS_KEY), this::save,
                    this::setMapSource, this::setCoordinateFormat, this::openFindDrone);
            mapWindow.setPreferPhone(preferPhone);
            mapWindow.map().setVirtualDrone(videoView.isVirtual()); // a virtual drone flown now: its map says so too
            mapWindow.setOnShownChanged(this::updateFlightLayout);
            pushKept();
            refreshNetwork();
            mapWindow.bindKey(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F3, 0), this::toggleMapWindow); // F3 there too
            // popped out or back: the minimap follows the drone again
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

    private long itsDownCheckedAt;

    /**
     * Once a second: while only a drone lost armed holds this PC back (PossiblyFlying, no link here saying armed), the
     * LINK card says why and offers IT'S DOWN - RESUME.
     */
    private void refreshItsDown() {
        long now = de.droiddrone.pc.MonoClock.ms();
        if (now - itsDownCheckedAt < 1000) return;
        itsDownCheckedAt = now;
        de.droiddrone.pc.PossiblyFlying flag = de.droiddrone.pc.PossiblyFlying.pc();
        boolean show = flag.resumable();
        String why = show ? flag.reason() : null;
        if (why != null) itsDownLine.setText("<html><div style='width:220px'>Downloads, overwatch previews and clips wait: " + why + ".</div></html>");
        if (itsDownButton.isVisible() != show) {
            itsDownButton.setVisible(show);
            itsDownLine.setVisible(show);
            itsDownButton.getParent().revalidate();
        }
    }

    /**
     * IT'S DOWN - RESUME (the LINK card, an overwatch card): the pilot knows the drone lost armed is on the ground, so
     * this PC's lost-drone window ends - map tiles, overwatch previews and clips go on. Asks first; never touches a link
     * here that says armed, nor arming or the sticks.
     */
    void itsDownResume(java.awt.Component parent) {
        de.droiddrone.pc.PossiblyFlying flag = de.droiddrone.pc.PossiblyFlying.pc();
        String why = flag.reason();
        if (!flag.resumable()) {
            if (why != null) JOptionPane.showMessageDialog(parent, why + ".\nA link here says armed: that holds until it says DISARMED.", "It's down - resume", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String[] options = {"It's down - resume", "Cancel"};
        int choice = JOptionPane.showOptionDialog(parent, why + ".\n\nIs that drone on the ground (disarmed, or found)?\n"
                        + "Map tiles, overwatch previews and clips go on at once. Nothing is sent to any drone.",
                "It's down - resume", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        if (choice != 0) return;
        java.util.List<String> drones = flag.resume();
        logLine("IT'S DOWN - RESUME: the pilot says " + (drones.isEmpty() ? "the lost drone" : String.join(", ", drones))
                + " is down - downloads, overwatch previews and clips go on.");
        itsDownCheckedAt = 0;
        refreshItsDown();
    }

    /** The link dropped (or the FC went) within a few minutes of the drone being armed: LOST DRONE comes up. */
    private void refreshLostDrone(ControlLink link, ControlLink.State state) {
        long now = de.droiddrone.pc.MonoClock.ms();
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

    /**
     * The LINK card says LINK STALLED once the phone has been silent this long (ControlLink.isLinkStalled is true from
     * 0.3 s; the card waits a little longer so one late packet on 4G doesn't blink it) - when it was first seen silent.
     */
    static final long CARD_STALL_MS = 500;
    private long stallSeenAt;

    /** The LINK card's words for a link that is up: LINK STALLED, FC LOST (red) or LINK READY (green). */
    static String readyPill(long stalledMs, boolean fcLost, int spectators) {
        String watching = spectators > 0 ? " +" + spectators + " WATCHING" : "";
        if (stalledMs >= CARD_STALL_MS) return "LINK STALLED" + watching;
        if (fcLost) return "FC LOST" + watching;
        return "LINK READY" + watching;
    }

    /** How long after the last armed report a dropped link still counts as a lost drone. */
    private static final long LOST_DRONE_WINDOW_MS = 10 * 60_000;

    /** The phone list and CONNECT AS: only while not connected (1.0.39, the pilot) - the room goes to the minimap. */
    private JComponent linkFields;

    private void setLinkEditable(boolean editable) {
        if (linkFields != null && linkFields.isVisible() != editable) {
            linkFields.setVisible(editable);
            linkFields.revalidate();
        }
        phoneCombo.setEnabled(editable);
        roleCombo.setEnabled(editable);
        joystickCombo.setEnabled(editable);
        calibrateButton.setEnabled(editable);
        calibrateButton.setToolTipText(editable ? null : "Locked while connected - disconnect first: RC output must stop while calibrating");
        connectButton.setText(editable ? (observerRole() ? "OBSERVE" : ROLE_SPECTATOR.equals(roleCombo.getSelectedItem()) ? "WATCH" : "CONNECT")
                : observation != null ? "STOP OBSERVING" : "DISCONNECT");
        connectButton.setBackground(editable ? Theme.ACCENT : Theme.BAD);
    }

    private boolean stanagEnabled() {
        return "true".equalsIgnoreCase(config.getString(StanagDialog.ENABLED_KEY));
    }

    /**
     * The STANAG 4609 stream from the video in hand (1.0.39): its own thread, the frames handed over from the video's
     * receive thread; the KLV built there from the latest telemetry and the camera's view.
     */
    private void startStanag() {
        stopStanag();
        VideoPipeline v = video;
        Connection c = connection;
        if (v == null || c == null) return;
        ControlLink link = c.getLink();
        String mission = config.getString(StanagDialog.MISSION_KEY).trim(), country = StanagDialog.country(config);
        de.droiddrone.pc.video.StanagStream s = new de.droiddrone.pc.video.StanagStream(StanagDialog.target(config),
                ms -> cameraView.klv(ms, link.getTelemetry(), track, mission, country),
                msg -> SwingUtilities.invokeLater(() -> logLine(msg)));
        stanag = s;
        v.setFrameTap(s);
        logLine("STANAG 4609 stream on: " + s.url() + ".");
    }

    private void stopStanag() {
        de.droiddrone.pc.video.StanagStream s = stanag;
        if (s == null) return;
        stanag = null;
        VideoPipeline v = video;
        if (v != null) v.setFrameTap(null);
        s.close();
    }

    private void openStanagSettings() {
        new StanagDialog(this, config, m -> {
            if (!save(m)) return;
            cameraView.configure(config);
            logLine("STANAG 4609: " + StanagDialog.target(config) + ", camera " + m.get(de.droiddrone.pc.CameraView.HFOV_KEY)
                    + " deg across, tilted up " + m.get(de.droiddrone.pc.CameraView.TILT_KEY) + " deg.");
            if (stanag != null) startStanag(); // the new address / names from now
            refreshMap();
        }).setVisible(true);
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
        nameService.stop();
        overwatch.close();
        if (popOut != null) popOut.dispose();
        if (observation != null) stopObservation();
        backToGrid = false;
        if (connection != null) disconnect();
        if (osdRecorder != null) osdRecorder.finish(3000); // the file with the OSD closed whole, not cut off by the exit
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

    // ---------------------------------------------------------------- OBSERVATION (1.0.48)

    /** CONNECT AS from the settings. */
    private String savedRole() {
        if (CONNECT_AS_OBSERVATION.equalsIgnoreCase(config.getString(Connection.CONNECT_AS_KEY))) return ROLE_OBSERVER;
        return Connection.watchOnly(config) ? ROLE_SPECTATOR : ROLE_PILOT;
    }

    private boolean observerRole() {
        return ROLE_OBSERVER.equals(roleCombo.getSelectedItem());
    }

    /** The drones chosen for observation, under CHOOSE DRONES. */
    private void showObserveChoice() {
        phoneLabel.setText("DRONES");
        de.droiddrone.pc.observe.ObserveSet set = de.droiddrone.pc.observe.ObserveSet.parse(config.getString(de.droiddrone.pc.observe.ObserveSet.KEY));
        String hint = set.size() == 0 ? "No drone chosen yet: CHOOSE DRONES picks up to " + de.droiddrone.pc.observe.ObserveSet.MAX + " idle ones."
                : String.join(", ", set.phones()) + "  (" + set.size() + " of " + de.droiddrone.pc.observe.ObserveSet.MAX + ")";
        phoneHint.setText("<html>" + hint + "</html>");
    }

    /** Why each phone can't be observed now (idle phones only; app pc.37 on); absent: it can. */
    private Map<String, String> observeRefusals(java.util.List<Names.Entry> phones) {
        Map<String, Names.Position> where = new java.util.HashMap<>();
        long now = System.currentTimeMillis();
        for (NameService.DronePosition d : nameService.dronePositions()) {
            if (now - d.at() < 30_000) where.put(d.name().toLowerCase(Locale.ROOT), d.position()); // a fresh word only
        }
        Map<String, String> out = new java.util.HashMap<>();
        for (Names.Entry e : phones) {
            Names.Position p = where.get(e.name().toLowerCase(Locale.ROOT));
            String why = de.droiddrone.pc.observe.ObserveSet.refusal(p, e.version());
            if (why != null && p != null && p.flown() && !p.pilotIp().isEmpty()) {
                String pilot = names.nameAt(p.pilotIp());
                if (pilot != null) why = pilot + " flies it";
            }
            if (why != null) out.put(e.name(), why);
        }
        return out;
    }

    /** CHOOSE DRONES: the picker; the set is remembered. */
    private void pickDrones() {
        de.droiddrone.pc.observe.ObserveSet current = de.droiddrone.pc.observe.ObserveSet.parse(config.getString(de.droiddrone.pc.observe.ObserveSet.KEY));
        java.util.List<Names.Entry> phones = names.list(Names.Kind.PHONE);
        java.util.Set<String> asleep = new java.util.HashSet<>();
        for (Names.Entry e : phones) if (e.ip() != null && asleepPhones.containsKey(e.ip())) asleep.add(e.name());
        java.util.List<String> chosen = ObservePicker.pick(this, phones, current, observeRefusals(phones), asleep);
        if (chosen == null) return;
        de.droiddrone.pc.observe.ObserveSet next = new de.droiddrone.pc.observe.ObserveSet();
        chosen.forEach(next::add);
        save(Map.of(de.droiddrone.pc.observe.ObserveSet.KEY, next.encode()));
        updatePhoneList();
        logLine(next.size() == 0 ? "Observation: no drone chosen." : "Observation: " + String.join(", ", next.phones()) + " chosen.");
    }

    private int volumeFor(String phone) {
        String v = config.getString(OBSERVE_VOLUME_KEY + "." + phone);
        try {
            return v.isEmpty() ? 100 : Math.max(0, Math.min(100, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    private boolean compassCalibrated(String phone) {
        return "true".equalsIgnoreCase(config.getString(de.droiddrone.pc.observe.HeadingChoice.calibratedKey(phone)));
    }

    /**
     * OBSERVE: the grid takes the video area, one tile per chosen phone that may be observed now (idle, app pc.37 on,
     * in the name map); a sleeping one is woken at its check-in. Nothing flies: no RcSession, no sticks, no Connect.
     */
    private void startObservation() {
        if (observation != null || connection != null) return;
        if (fullScreen != null) exitFullScreen();
        if (popOut != null) { // the picture's window goes first (its listener puts the video back), then the grid
            popOut.dispose();
            SwingUtilities.invokeLater(this::startObservation);
            return;
        }
        de.droiddrone.pc.observe.ObserveSet saved = de.droiddrone.pc.observe.ObserveSet.parse(config.getString(de.droiddrone.pc.observe.ObserveSet.KEY));
        if (saved.size() == 0) {
            pickDrones();
            saved = de.droiddrone.pc.observe.ObserveSet.parse(config.getString(de.droiddrone.pc.observe.ObserveSet.KEY));
            if (saved.size() == 0) return;
        }
        Map<String, String> refusals = observeRefusals(names.list(Names.Kind.PHONE));
        de.droiddrone.pc.observe.ObserveSet set = new de.droiddrone.pc.observe.ObserveSet();
        for (String p : saved.phones()) {
            Names.Entry e = names.get(p);
            String why = refusals.get(e == null ? p : e.name());
            if (why != null) logLine("Observation: " + p + " left out - " + why + ".");
            else if (e == null || e.ip() == null || e.ip().isEmpty()) logLine("Observation: " + p + " isn't in the name map - left out.");
            else set.add(e.name());
        }
        if (set.size() == 0) {
            JOptionPane.showMessageDialog(this, "None of the chosen drones can be observed now (see the log). CHOOSE DRONES picks others.",
                    "Observation", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        de.droiddrone.pc.audio.ObservationAudio audio = new de.droiddrone.pc.audio.ObservationAudio(AlertSound::isPlaying,
                msg -> SwingUtilities.invokeLater(() -> logLine(msg)));
        ObservationGrid grid = new ObservationGrid(gridListener());
        grid.setPhones(set.phones());
        // the selected tile's picture: this PC's NORMAL (its camera size at 30 fps, its bitrate limit)
        de.droiddrone.pc.observe.ObserverLink.Quality sharp = new de.droiddrone.pc.observe.ObserverLink.Quality(config.getCameraWidth(),
                config.getCameraHeight(), 30, Math.max(1000, config.getBitrateLimitMbps() * 1000));
        java.util.List<de.droiddrone.pc.observe.TileFeed> feeds = new java.util.ArrayList<>();
        long now = de.droiddrone.pc.MonoClock.ms();
        observeMutedAt.clear();
        try {
            for (int i = 0; i < set.size(); i++) {
                String phone = set.phones().get(i);
                Names.Entry e = names.get(phone);
                de.droiddrone.pc.observe.TileFeed f = new de.droiddrone.pc.observe.TileFeed(phone, java.net.InetAddress.getByName(e.ip()),
                        de.droiddrone.pc.fleet.VirtualFleet.portOf(e.ip(), config.getPort()), config.getKey(), grid.sink(i), audio, sharp, msg -> SwingUtilities.invokeLater(() -> logLine(msg)));
                feeds.add(f);
                f.link().setDetection(detectionFor(phone)); // the drone's own setting (1.0.46): boxes on its tile
                boolean muted = mutedFor(phone);
                audio.setMuted(f.audioId(), muted);
                audio.setVolume(f.audioId(), volumeFor(phone) / 100f);
                if (muted) observeMutedAt.put(phone, now - OBSERVE_MUTED_STOP_MS); // asks no sound from the start
            }
        } catch (IOException | RuntimeException ex) {
            for (de.droiddrone.pc.observe.TileFeed f : feeds) {
                f.stopStream();
                f.release();
            }
            audio.close();
            logLine("Observation failed: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Cannot observe", JOptionPane.WARNING_MESSAGE);
            return;
        }
        tileFeeds.clear();
        tileFeeds.addAll(feeds);
        observationAudio = audio;
        observationGrid = grid;
        observation = new de.droiddrone.pc.observe.ObservationSession(set, new java.util.ArrayList<>(feeds), audio::close);
        observedNames = java.util.Set.copyOf(set.phones());
        hoveredTile = -1;
        java.awt.Container home = videoHome();
        home.remove(videoView);
        home.add(grid, BorderLayout.CENTER);
        home.revalidate();
        home.repaint();
        setLinkEditable(false);
        logLine("Observing " + String.join(", ", set.phones()) + ": a small picture and the sound of each (a click on a tile asks for "
                + "the sharp picture, a double-click enlarges it). This PC flies none of them - CONNECT on a tile does.");
    }

    private ObservationGrid.Listener gridListener() {
        return new ObservationGrid.Listener() {
            @Override
            public void select(int tile) {
                de.droiddrone.pc.observe.ObservationSession o = observation;
                if (o != null) o.select(tile);
            }

            @Override
            public void enlarge(int tile) {
                de.droiddrone.pc.observe.ObservationSession o = observation;
                if (o != null) o.toggleEnlarged(tile);
            }

            @Override
            public void connect(int tile) {
                connectFromGrid(tile);
            }

            @Override
            public void mute(int tile, boolean muted) {
                if (tile < 0 || tile >= tileFeeds.size() || observationAudio == null) return;
                de.droiddrone.pc.observe.TileFeed f = tileFeeds.get(tile);
                observationAudio.setMuted(f.audioId(), muted);
                if (muted) observeMutedAt.put(f.phone(), de.droiddrone.pc.MonoClock.ms());
                else observeMutedAt.remove(f.phone());
                save(Map.of(audioMutedKey(f.phone()), Boolean.toString(muted))); // the phone's own choice, as the pilot's MUTE
            }

            @Override
            public void volume(int tile, int volume, boolean done) {
                if (tile < 0 || tile >= tileFeeds.size() || observationAudio == null) return;
                de.droiddrone.pc.observe.TileFeed f = tileFeeds.get(tile);
                observationAudio.setVolume(f.audioId(), volume / 100f);
                if (done) save(Map.of(OBSERVE_VOLUME_KEY + "." + f.phone(), Integer.toString(volume)));
            }

            @Override
            public void hover(int tile) {
                hoveredTile = tile;
                observedMapAt = 0; // the cone stands out now, not at the next map refresh
            }
        };
    }

    /** STOP OBSERVING: every stream stopped, the picture area back to the single video. */
    private void stopObservation() {
        de.droiddrone.pc.observe.ObservationSession o = observation;
        if (o == null) return;
        o.stop();
        endObservationUi();
        logLine("Observation stopped.");
    }

    private void endObservationUi() {
        observation = null;
        observedNames = java.util.Set.of();
        tileFeeds.clear();
        observationAudio = null;
        ObservationGrid grid = observationGrid;
        observationGrid = null;
        hoveredTile = -1;
        if (grid != null && grid.getParent() != null) {
            java.awt.Container parent = grid.getParent();
            parent.remove(grid);
            parent.add(videoView, BorderLayout.CENTER);
            parent.revalidate();
            parent.repaint();
        }
        miniMap.setObserved(java.util.List.of(), null);
        miniMap.repaint();
        if (mapWindow != null) {
            mapWindow.map().setObserved(java.util.List.of(), null);
            mapWindow.map().repaint();
        }
        setLinkEditable(true);
    }

    /**
     * CONNECT on a tile: every stream stops at once (latency first), then the usual pilot connection to that phone -
     * its RcSession's gates (throttle low, the arm switch off) as always. DISCONNECT later offers the grid again.
     */
    private void connectFromGrid(int tile) {
        de.droiddrone.pc.observe.ObservationSession o = observation;
        if (o == null) return;
        if (mapper == null) { // the streams stay: nothing could fly yet
            JOptionPane.showMessageDialog(this, "Controller mapping is not usable: " + mapperError + "\nRun Calibrate first.",
                    "Cannot connect", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String phone = o.connect(tile); // every stream stops here
        endObservationUi();
        if (phone == null) return;
        backToGrid = true;
        roleCombo.setSelectedItem(ROLE_PILOT);
        Names.Entry e = names.get(phone);
        save(Map.of(Names.PHONE_NAME_KEY, phone, "ip", e == null || e.ip() == null ? "" : e.ip()));
        updatePhoneList();
        logLine("From the grid: connecting to " + phone + " as the pilot - the other streams stopped. Throttle low and the arm switch off first, as always.");
        connect();
        if (connection == null) backToGrid = false; // nothing opened: the grid is a press of OBSERVE away
    }

    /** After a flight that began on the grid: the same set again, if the pilot wants. */
    private void offerBackToGrid() {
        if (connection != null || observation != null) return;
        int answer = JOptionPane.showConfirmDialog(this, "Back to the grid? The same drones are observed again.", "Observation",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        roleCombo.setSelectedItem(ROLE_OBSERVER);
        startObservation();
    }

    /** Every 33 ms with the rest: the tiles' figures, a phone that got a pilot leaving the grid, the map. */
    private void refreshObservation() {
        de.droiddrone.pc.observe.ObservationSession o = observation;
        ObservationGrid grid = observationGrid;
        if (o == null || grid == null) return;
        long now = de.droiddrone.pc.MonoClock.ms();
        setPill("OBSERVING " + tileFeeds.size(), Theme.PANEL_2, Theme.TEXT);
        for (int i = tileFeeds.size() - 1; i >= 0; i--) {
            de.droiddrone.pc.observe.TileFeed f = tileFeeds.get(i);
            de.droiddrone.common.Observe.State st = f.link().state();
            if (st != null && st.state == de.droiddrone.common.Observe.ENDED_PILOT) {
                leaveGrid(i, "a pilot connected to it");
            }
        }
        for (int i = 0; i < tileFeeds.size(); i++) {
            de.droiddrone.pc.observe.TileFeed f = tileFeeds.get(i);
            de.droiddrone.pc.observe.ObserverLink l = f.link();
            String phone = f.phone();
            de.droiddrone.common.Observe.State st = l.state();
            long last = l.lastPacketAt();
            String status;
            if (st != null && st.state != de.droiddrone.common.Observe.STREAMING && now - l.stateAt() < 3000) {
                status = "NOT STREAMING: " + de.droiddrone.common.Observe.stateName(st.state);
            } else if (last == 0) {
                Names.Entry e = names.get(phone);
                status = e != null && e.ip() != null && asleepPhones.containsKey(e.ip()) ? "DEEP SLEEP - joins at its next check-in"
                        : "Asking the phone...";
            } else if (now - last > de.droiddrone.pc.observe.ObserverLink.SILENT_MS) {
                status = "NO SIGNAL for " + (now - last) / 1000 + " s";
            } else {
                VideoPipeline.Stats vs = f.video().stats();
                status = vs.width() > 0 ? vs.width() + "x" + vs.height() + "  " + vs.shownFps() + " FPS" : "Waiting for the picture...";
                if (l.quality() != de.droiddrone.pc.observe.ObserverLink.Quality.GRID) status += "  SHARP";
            }
            de.droiddrone.pc.FcTelemetry.Observed ob = l.telemetry().getObserved();
            de.droiddrone.pc.observe.HeadingChoice.Heading hd = de.droiddrone.pc.observe.HeadingChoice.choose(
                    ob == null ? null : ob.position(), compassCalibrated(phone));
            String note = ob == null ? null : hd.source() == de.droiddrone.pc.observe.HeadingChoice.Source.NONE ? "no heading"
                    : hd.approximate() ? "heading approximate" : null;
            Long mutedAt = observeMutedAt.get(phone);
            l.setAudioWanted(mutedAt == null || now - mutedAt < OBSERVE_MUTED_STOP_MS);
            de.droiddrone.pc.audio.ObservationAudio audio = observationAudio;
            grid.update(i, new ObservationGrid.TileState(status, audio != null && audio.isSpeaking(f.audioId()), mutedAt != null,
                    volumeFor(phone), note, l.telemetry().getMotion().shown(now)));
        }
        grid.setSelection(o.set().selected(), o.set().enlarged());
        refreshObservedMap(now);
    }

    /** A phone leaves the grid (a pilot connected to it): its tile goes, with a line why. It stays in the chosen set. */
    private void leaveGrid(int tile, String why) {
        de.droiddrone.pc.observe.ObservationSession o = observation;
        if (o == null || tile >= tileFeeds.size()) return;
        de.droiddrone.pc.observe.TileFeed f = tileFeeds.remove(tile);
        o.remove(f.phone());
        if (observationGrid != null) observationGrid.removeTile(tile);
        observedNames = java.util.Set.copyOf(o.set().phones());
        logLine(f.phone() + " left the grid: " + why + ". It stays chosen and joins again at the next OBSERVE while idle.");
    }

    /**
     * The observed phones on the minimap and the map window, five times a second: each one's marker and view cone in
     * its tile's colour and number, the hovered tile's cone stronger; a click on one selects its tile.
     */
    private void refreshObservedMap(long now) {
        if (now - observedMapAt < 200) return;
        observedMapAt = now;
        de.droiddrone.pc.observe.ObservationSession o = observation;
        if (o == null) return;
        java.util.List<MapView.Observed> marks = new java.util.ArrayList<>();
        for (int i = 0; i < tileFeeds.size(); i++) {
            de.droiddrone.pc.observe.TileFeed f = tileFeeds.get(i);
            de.droiddrone.pc.FcTelemetry.Observed ob = f.link().telemetry().getObserved();
            if (ob == null || !ob.position().hasPosition() || now - ob.receivedAt() > 30_000) continue;
            de.droiddrone.common.Observe.Position p = ob.position();
            de.droiddrone.pc.observe.HeadingChoice.Heading hd = de.droiddrone.pc.observe.HeadingChoice.choose(p, compassCalibrated(f.phone()));
            int[] size = f.video().pictureSize();
            double aspect = size == null || size[1] <= 0 ? 16 / 9.0 : (double) size[0] / size[1];
            double hfov = p.hfovDeg > 1 ? p.hfovDeg : cameraView.hfovDeg();
            java.util.List<double[]> cone = cameraOnMap
                    ? de.droiddrone.pc.observe.ObservedCone.edge(p.lat, p.lon, p.altM, hd.deg(), hfov, cameraView.tiltDeg(), aspect)
                    : java.util.List.of();
            boolean selected = i == o.set().selected() || i == o.set().enlarged();
            marks.add(new MapView.Observed(i + 1, ObservationGrid.colour(i), p.lat, p.lon, hd.deg(), hd.approximate(), cone,
                    i == hoveredTile, selected));
        }
        java.util.function.IntConsumer click = tile -> {
            de.droiddrone.pc.observe.ObservationSession s = observation;
            if (s != null && s.set().selected() != tile) s.select(tile);
        };
        miniMap.setObserved(marks, click);
        miniMap.repaint();
        if (mapWindow != null && mapWindow.isShown()) {
            mapWindow.map().setObserved(marks, click);
            mapWindow.map().repaint();
        }
    }


    // ---------------------------------------------------------------- periodic refresh

    private void refresh() {
        JoystickService.Snapshot snap = service.getSnapshot();
        refreshJoystickCombo(snap);
        refreshController(snap);
        refreshLink();
        alertSound.update(stickWarning(), System.currentTimeMillis());
        refreshAudio();
        refreshObservation();
        refreshMap();
        refreshOverwatch();
        refreshItsDown();
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
        } else if (fc == null || de.droiddrone.pc.MonoClock.ms() - fc.receivedAt() > 3000) {
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
        if (de.droiddrone.pc.PossiblyFlying.now()) return; // nothing on the network while a drone here may fly: in 15 min again
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
            boolean tailscale = !ip.isEmpty();
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
        boolean armed = st != null && st.armed() && de.droiddrone.pc.MonoClock.ms() - st.receivedAt() < 2000;
        boolean radioFrozen = armed && s != null && s.isRcEnabled() && s.msSinceInputChanged() > RADIO_FROZEN_MS;
        String banner = !spectator ? null : l.isWatchOnly() ? "SPECTATOR" : "NOT FLYING - SPECTATOR";
        // the phone's own word on the sticks (pc.19+): how long ago its last stick frame reached the FC
        FcTelemetry.PhoneState ps = l.getTelemetry().getPhoneState();
        long now = de.droiddrone.pc.MonoClock.ms();
        int sticksAge = ps == null || now - ps.receivedAt() > 2500 || ps.rcWriteAgeMs() < 0 ? -1
                : (int) Math.min(65_534, ps.rcWriteAgeMs() + (now - ps.receivedAt()));
        RcSession.Banner resume = s == null ? null : s.lastResumeBanner();
        String liveBanner = resume != null && now - resume.at() < RESUME_BANNER_MS ? resume.text() : null;
        if (liveBanner == null && !spectator && powerWanted == ControlLink.POWER_STANDBY) liveBanner = "STANDBY - PRESS WAKE";
        if (liveBanner == null && s != null && s.isFrozenStopped()) liveBanner = "RADIO FROZEN - STICKS STOPPED";
        int lagMs = spectator ? -1 : stickLagMs(l);
        if (liveBanner == null && lagMs > STICKS_LATE_MS) {
            liveBanner = String.format(java.util.Locale.ROOT, "STICKS %.1f S LATE", lagMs / 1000.0);
        }
        boolean linkReady = ready, rcLive = spectator || (s != null && s.isRcEnabled());
        boolean stalled = l.isLinkStalled(), relayed = l.isRelayed(), armUnknown = s != null && s.isArmSwitchUnknown();
        String rcWaiting = s == null ? null : s.waitingFor(), hint = osdLinkHint(s);
        short[] channels = s == null ? null : s.getLastChannels();
        // watching: the pilot PC's own warnings, as its OSD shows them (1.0.41: RC OFF - NO RADIO there, nothing here)
        ControlLink.PilotView pv = spectator ? l.getPilotView() : null;
        if (pv != null) {
            linkReady = ready && pv.linkReady();
            rcLive = pv.rcLive();
            rcWaiting = pv.rcWaiting();
            radioFrozen = pv.radioFrozen();
            armUnknown = pv.armSwitchUnknown();
            relayed = pv.relayed();
            stalled = pv.linkStalled();
            if (pv.banner() != null) liveBanner = pv.banner();
            if (!pv.linkReady()) hint = pv.linkHint();
            channels = pv.channels();
        }
        // watching: say what kind of loss - the pilot gone on purpose, or its own link to the drone lost (1.0.41)
        String lostText = null;
        String left = spectator ? l.getPilotLeft() : null;
        if (left != null) {
            linkReady = false;
            lostText = "PILOT DISCONNECTED";
            hint = "THE PILOT ENDED ITS LINK" + (left.isEmpty() ? "" : " TO " + left.toUpperCase(Locale.ROOT)) + " - NOT A LINK LOSS";
        } else if (pv != null && !pv.linkReady()) {
            lostText = "PILOT LINK LOST";
        }
        return new OsdRenderer.Data(true, linkReady, l.getPing(), rcLive,
                l.isFcLost(), l.getTelemetry(), v == null ? null : v.stats(), "true".equalsIgnoreCase(config.getString("osdInvertPitch")),
                hint, channels, false, stalled, videoFrozen, radioFrozen,
                rcWaiting, v != null && v.recordingFile() != null, banner, spectator && !l.isWatchOnly(),
                sticksAge, armUnknown, liveBanner, spectator && l.isSpectatorPaused(), relayed,
                ps != null && now - ps.receivedAt() <= 2500 && ps.rcWriteAgeMs() > OsdRenderer.STICKS_LOST_ALARM_MS, lostText);
    }

    /** A stream (re)start may take this long to show its first picture before VIDEO FROZEN says so. */
    private static final long VIDEO_RESTART_GRACE_MS = 2500;

    /** How long "LINK BACK - ARMED - STICKS LIVE" stays on the HUD. */
    private static final long RESUME_BANNER_MS = 3000;

    /** No new picture for this long while the stream runs: VIDEO FROZEN. */
    private static final long VIDEO_FROZEN_MS = 700;
    /** Armed and not a single bit of the radio's input changed for this long: RADIO FROZEN? */
    private static final long RADIO_FROZEN_MS = 5000;
    /** The sticks reach the phone this much later than the link's usual (phone app pc.31): STICKS LATE. */
    private static final int STICKS_LATE_MS = 300;

    /** The worst stick lateness the phone saw in the last second, in a fresh report; -1 = unknown. */
    private static int stickLagMs(ControlLink link) {
        FcTelemetry.PhoneRcLag lag = link.getTelemetry().getPhoneRcLag();
        if (lag == null || de.droiddrone.pc.MonoClock.ms() - lag.receivedAt() > 2500) return -1;
        return lag.maxLagMs();
    }

    /** The second file of a recording, with the OSD drawn in (1.0.42); one for the window, used by each connection. */
    private OsdRecorder osdRecorder;

    private void startRecording(VideoPipeline v) {
        try {
            java.nio.file.Path dir = de.droiddrone.pc.AppPaths.videosDir();
            boolean withOsd = !"false".equalsIgnoreCase(config.getString("recordOsd"));
            if (withOsd && osdRecorder == null) osdRecorder = new OsdRecorder(OsdRecorder.Source.of(videoView), this::logLine);
            v.setRecordingTap(withOsd ? osdRecorder : null);
            v.startRecording(dir);
            logLine("Recording the video to " + dir + " (from the next key frame)" + (withOsd ? ", and a copy with the OSD drawn in." : "."));
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
        if (st != null && de.droiddrone.pc.MonoClock.ms() - st.receivedAt() < 2000
                && st.blockers().stream().anyMatch(b -> b.osdName().equals("FAILSAFE"))) return "FAILSAFE";
        // a hung radio that still reports itself: the OSD alone is easy to miss with eyes on the picture
        RcSession s = session;
        boolean armed = st != null && st.armed() && de.droiddrone.pc.MonoClock.ms() - st.receivedAt() < 2000;
        if (armed && s != null && s.isRcEnabled() && s.msSinceInputChanged() > RADIO_FROZEN_MS) return "RADIO FROZEN";
        if (stickLagMs(l) > STICKS_LATE_MS) return "STICKS LATE";
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
        // 1.0.47: OVERWATCH under the two, as wide as both
        overwatchButton.setToolTipText("<html>Sleeps like DEEP SLEEP, but the camera and the motion detection stay on: when something moves "
                + "the phone calls this PC at once -<br>the map window shows where, a live picture, CONNECT and DISMISS - and records the event in "
                + "full quality. Disarmed only; about 15-25 h on a 5000 mAh phone</html>");
        overwatchButton.addActionListener(e -> askPower(ControlLink.POWER_OVERWATCH));
        JPanel sleepButtons = new JPanel(new GridLayout(2, 1, 0, 6));
        sleepButtons.setOpaque(false);
        sleepButtons.add(buttons);
        sleepButtons.add(overwatchButton);
        sleepFold = new Fold(sleepButtons, true); // folds away while the drone is armed, and back when it is disarmed
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

    /**
     * Ending the link stops the sticks, and an armed drone then fails safe: while the FC says armed (or the link is
     * down right after it did - the drone may still be in the air), ask first, with "keep flying" as the default.
     * @return true to go on
     */
    private boolean confirmEndWhileArmed(String action) {
        if (connection == null || connection.getLink().isSpectator()) return true;
        boolean armed = fcArmedNow();
        // no fresh word from the FC (link down), and it was armed within the lost-drone window: it may still fly
        FcTelemetry.Status st = connection.getLink().getTelemetry().getStatus();
        long now = de.droiddrone.pc.MonoClock.ms();
        boolean fresh = st != null && now - st.receivedAt() < 2000;
        boolean maybeFlying = !fresh && lastArmedAt != 0 && now - lastArmedAt < LOST_DRONE_WINDOW_MS;
        if (!armed && !maybeFlying) return true;
        String[] options = {"Keep flying", action};
        int choice = JOptionPane.showOptionDialog(this,
                (armed ? "The drone is ARMED." : "The drone was armed moments ago and may still be in the air.")
                        + "\n" + action + " stops the sticks: Betaflight's failsafe takes over (GPS rescue, landing or a drop, as set).",
                action + " while armed?", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (choice == 1) {
            logLine(action + " while " + (armed ? "armed" : "possibly flying") + ": confirmed.");
            return true;
        }
        return false;
    }

    /** The FC reported armed within the last two seconds. */
    private boolean fcArmedNow() {
        if (connection == null) return false;
        FcTelemetry.Status st = connection.getLink().getTelemetry().getStatus();
        return st != null && st.armed() && de.droiddrone.pc.MonoClock.ms() - st.receivedAt() < 2000;
    }

    private void askPower(int mode) {
        if (demoPower(mode)) return;
        if (connection == null) return;
        JButton pressed = mode == ControlLink.POWER_STANDBY ? standbyButton : mode == ControlLink.POWER_OVERWATCH ? overwatchButton : deepSleepButton;
        if (fcArmedNow()) {
            new LockedNotice(pressed, videoView, "ARMED", "No sleep while the drone is armed: disarm first.").pop();
            return;
        }
        // telemetry stale right after an arm: it may still fly, and every power mode stops the sticks - ask, as DISCONNECT does
        if (!confirmEndWhileArmed(mode == ControlLink.POWER_STANDBY ? "STANDBY"
                : mode == ControlLink.POWER_OVERWATCH ? "OVERWATCH" : "DEEP SLEEP")) return;
        if (mode == ControlLink.POWER_OVERWATCH && !prepareOverwatch(pressed)) return;
        powerWanted = mode;
        powerAskedAt = de.droiddrone.pc.MonoClock.ms();
        powerSentAt = 0;
        logLine(mode == ControlLink.POWER_STANDBY ? "STANDBY asked: the phone stops its camera and slows down; WAKE brings it back."
                : mode == ControlLink.POWER_OVERWATCH ? "OVERWATCH asked: the phone sleeps with its camera watching; if something moves it calls this "
                + "PC (the map window) and records it. It listens for this PC every " + checkIntervalMin + " min."
                : "DEEP SLEEP asked: the phone drops the link and sleeps, listening for this PC every " + checkIntervalMin + " min.");
    }

    // ------------------------------------------------------------------ OVERWATCH SLEEP (1.0.47)

    /**
     * Before the OVERWATCH request: the phone app must know it (pc.36), the camera must give the motion detection a
     * picture; then a new token, and where the drone stands and looks - remembered, so an alert after a restart of this
     * PC still draws the camera's view on the map.
     */
    private boolean prepareOverwatch(JButton pressed) {
        ControlLink link = connection.getLink();
        int build = link.getTelemetry().getPhonePcBuild();
        if (build >= 0 && build < 36) {
            new LockedNotice(pressed, videoView, "UPDATE THE PHONE APP", "OVERWATCH needs DRAUPNE Flight " + de.droiddrone.common.OverwatchProtocol.MIN_PHONE_VERSION
                    + " or newer: UPDATE PHONE APP on the LINK card.").pop();
            return false;
        }
        FcTelemetry.PhonePower pp = link.getTelemetry().getPhonePower();
        if (pp != null && build >= 36 && (pp.caps() & CAP_OVERWATCH) == 0) {
            new LockedNotice(pressed, videoView, "NO PICTURE TO WATCH", "This phone's camera gives the motion detection no picture "
                    + "(it refused the second small stream), so it can't overwatch. DEEP SLEEP saves more power anyway.").pop();
            return false;
        }
        String name = config.getString(Names.PHONE_NAME_KEY);
        int token;
        do {
            token = new java.security.SecureRandom().nextInt();
        } while (token == 0);
        overwatchToken = token;
        de.droiddrone.pc.map.CameraFootprint.Pose pose = cameraView.sittingPose(link.getTelemetry(), track);
        overwatch.watch(name, token, pose);
        saveWatched();
        if (pose == null) logLine("OVERWATCH: no position or heading of the drone now - an alert can't show its camera's view on the map.");
        return true;
    }

    /** PHONE_POWER caps bit 3 (pc.36): the camera gives the motion detection its picture - the phone can overwatch. */
    static final int CAP_OVERWATCH = 8;

    /** The hub's drones into the settings: name|token|lat|lon|alt|heading|pitch|roll|tilt|hfov|vfov;... */
    private void saveWatched() {
        StringBuilder b = new StringBuilder();
        for (de.droiddrone.pc.overwatch.OverwatchHub.Watched w : overwatch.watched()) {
            if (b.length() > 0) b.append(';');
            b.append(w.drone().replace("|", "").replace(";", "")).append('|').append(Integer.toHexString(w.token()));
            de.droiddrone.pc.map.CameraFootprint.Pose p = w.pose();
            if (p != null) {
                for (double v : new double[]{p.lat(), p.lon(), p.altM(), p.headingDeg(), p.pitchDeg(), p.rollDeg(), p.tiltDeg(), p.hfovDeg(), p.vfovDeg()}) {
                    b.append('|').append(v);
                }
            }
        }
        String v = b.toString();
        save(Map.of(OVERWATCH_KEY, v));
    }

    /** At the start: the drones put to overwatch before, the hub's listener, its thread. */
    private void startOverwatch() {
        for (String one : config.getString(OVERWATCH_KEY).split(";")) {
            String[] p = one.split("\\|");
            if (p.length < 2 || p[0].isBlank()) continue;
            try {
                int token = Integer.parseUnsignedInt(p[1], 16);
                de.droiddrone.pc.map.CameraFootprint.Pose pose = null;
                if (p.length >= 11) {
                    double[] v = new double[9];
                    for (int i = 0; i < 9; i++) v[i] = Double.parseDouble(p[2 + i]);
                    pose = new de.droiddrone.pc.map.CameraFootprint.Pose(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]);
                }
                overwatch.watch(p[0], token, pose);
            } catch (NumberFormatException ignored) {
                // a broken entry: left out
            }
        }
        overwatch.setListener(new de.droiddrone.pc.overwatch.OverwatchHub.Listener() {
            @Override
            public void onNews(String drone) {
                SwingUtilities.invokeLater(() -> overwatchNews(drone));
            }

            @Override
            public void onDropped(String drone, int reason, int batteryPercent, int tempDeci) {
                SwingUtilities.invokeLater(() -> overwatchDropped(drone, reason, batteryPercent));
            }

            @Override
            public void onClips(String drone) {
                SwingUtilities.invokeLater(() -> {
                    if (mapWindow != null) mapWindow.clipsChanged(drone);
                });
            }

            @Override
            public void log(String line) {
                SwingUtilities.invokeLater(() -> logLine(line));
            }
        });
        try {
            overwatch.start();
        } catch (IOException e) {
            logLine("Overwatch alerts can't be taken: " + e.getMessage());
        }
    }

    /**
     * A drone's overwatch alert (the pilot's rules): only the map window shows it - in front, with a sound - unless a
     * drone on this PC may be flying (PossiblyFlying: armed, or armed within the lost-drone window before its link went
     * quiet - 1.0.49): then quietly, no sound, nothing raised over the video. With the map window closed, POP OUT MAP
     * says OVERWATCH and nothing pops up.
     */
    private void overwatchNews(String drone) {
        boolean flying = overwatch.isFlying();
        logLine("OVERWATCH: " + drone + " sees something move." + (flying ? " Shown quietly in the map window, its live preview paused: " + java.util.Objects.requireNonNullElse(de.droiddrone.pc.PossiblyFlying.reasonNow(), "a drone here may be flying") + "."
                : mapWindow != null && mapWindow.isShown() ? " See the map window." : " Open the map (POP OUT MAP) to see it."));
        if (!flying) alertSound.ring();
        MapWindow w = mapWindow;
        if (w != null && w.isShown()) {
            w.overwatchNews(drone, !flying);
        } else {
            setOverwatchBadge(true);
        }
    }

    private void overwatchDropped(String drone, int reason, int batteryPercent) {
        logLine("OVERWATCH: " + drone + " left overwatch for deep sleep by itself - " + de.droiddrone.common.PowerFloor.describe(reason)
                + (batteryPercent >= 0 && batteryPercent <= 100 ? " (battery " + batteryPercent + " %)" : "") + ". WAKE brings it back.");
        String ip = names.ipOf(drone);
        Asleep a = ip == null ? null : asleepPhones.get(ip);
        if (a != null && a.overwatch()) rememberAsleep(new Asleep(a.name(), a.ip(), a.since(), a.intervalMin(), false));
        if (connection != null && phoneOverwatching && drone.equalsIgnoreCase(config.getString(Names.PHONE_NAME_KEY))) phoneOverwatching = false;
    }

    /** POP OUT MAP says OVERWATCH while an alert waits and the map window is closed (the pilot: nothing pops up). */
    private void setOverwatchBadge(boolean on) {
        if (popMapButton == null || on == overwatchBadge) return;
        overwatchBadge = on;
        popMapButton.setText(on ? "Pop out map - OVERWATCH" : "Pop out map");
        popMapButton.setBackground(on ? Theme.WARN : null);
        popMapButton.setToolTipText(on ? "A drone in overwatch saw something move: open the map to see it (F3)" : "The map in a window of its own, for a second screen (F3)");
    }

    /**
     * Every refresh: the map window gets the alerts, or POP OUT MAP its badge. (Whether this PC flies the hub reads
     * itself, from PossiblyFlying - since 1.0.49 also for the lost-drone window after a quiet link.)
     */
    private void refreshOverwatch() {
        java.util.List<de.droiddrone.pc.overwatch.OverwatchHub.View> views = overwatch.views();
        MapWindow w = mapWindow;
        if (w != null && w.isShown()) {
            w.setOverwatch(views);
            setOverwatchBadge(false);
        } else if (!overwatchBadge) {
            for (de.droiddrone.pc.overwatch.OverwatchHub.View v : views) {
                if (v.shown() == de.droiddrone.pc.overwatch.AlertCard.Shown.CARD && !v.ended()) {
                    setOverwatchBadge(true);
                    break;
                }
            }
        }
    }

    /** CONNECT on an overwatch card: the phone wakes (told over the overwatch link) and this PC connects to it as its pilot. */
    void overwatchConnect(String drone) {
        Connection c = connection;
        String flown = config.getString(Names.PHONE_NAME_KEY);
        boolean same = c != null && !c.getLink().isSpectator() && drone.equalsIgnoreCase(flown);
        if (!same && c != null && de.droiddrone.pc.PossiblyFlying.now()) {
            // armed now or within the lost-drone window: never a click away from cutting this PC's own sticks
            JOptionPane.showMessageDialog(this, "This PC flies another drone (or did moments ago, and it may still be in the air).\n"
                    + "Land it and DISCONNECT, then CONNECT to " + drone + ".", "Overwatch", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // 1.0.49 (H4): a click on an alert card ends what this PC does now, so it asks first
        String[] options = {"Connect to " + drone, "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, "Wake " + drone + " and connect to it as its pilot?"
                        + (same ? "" : c != null ? "\nThis PC's link now (" + (c.getLink().isSpectator() ? "watching " : "") + flown + ") is closed first." : ""),
                "Overwatch", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        if (choice != 0) return;
        overwatch.connect(drone);
        if (same) {
            wakePhone(); // this PC's link to it waits dormant: the Connects go out again now
            return;
        }
        if (c != null) disconnect();
        String ip = names.ipOf(drone);
        if (ip != null && asleepPhones.containsKey(ip)) clearAsleep(ip); // woken now, not at its next check
        connectFromMap(drone, false);
    }

    /** The pilot's DISMISS on a card. */
    void overwatchDismiss(String drone) {
        overwatch.dismiss(drone);
    }

    de.droiddrone.pc.overwatch.OverwatchHub overwatch() {
        return overwatch;
    }

    // ------------------------------------------------------------------ phones asleep while no link is open

    /** The saved "name|ip|since|interval;...". */
    private static java.util.List<Asleep> parseAsleep(String saved) {
        java.util.List<Asleep> out = new java.util.ArrayList<>();
        if (saved == null || saved.isBlank()) return out;
        for (String one : saved.split(";")) {
            String[] p = one.split("\\|");
            try {
                if ((p.length == 4 || p.length == 5) && !p[1].isBlank()) {
                    out.add(new Asleep(p[0], p[1], Long.parseLong(p[2]), Integer.parseInt(p[3]), p.length == 5 && "ow".equals(p[4])));
                }
            } catch (NumberFormatException ignored) {
                // a broken entry: left out
            }
        }
        return out;
    }

    private void saveAsleep() {
        StringBuilder b = new StringBuilder();
        for (Asleep a : asleepPhones.values()) {
            if (b.length() > 0) b.append(';');
            b.append(a.name()).append('|').append(a.ip()).append('|').append(a.since()).append('|').append(a.intervalMin());
            if (a.overwatch()) b.append("|ow");
        }
        String v = b.toString();
        SwingUtilities.invokeLater(() -> save(Map.of(ASLEEP_KEY, v)));
    }

    private void rememberAsleep(Asleep a) {
        asleepPhones.put(a.ip(), a);
        saveAsleep();
    }

    private void clearAsleep(String ip) {
        if (ip == null || asleepPhones.remove(ip) == null) return;
        asleepHellos.remove(ip);
        saveAsleep();
    }

    /** The phone chosen in the PHONE list, if it is asleep; null otherwise (or as a spectator). */
    private Asleep asleepChosen() {
        if (ROLE_SPECTATOR.equals(roleCombo.getSelectedItem())) return null;
        String ip = config.getString("ip");
        return ip.isEmpty() ? null : asleepPhones.get(ip);
    }

    /** When this sleeping phone checks for this PC next (wall clock). */
    private long nextCheck(Asleep a, long now) {
        ControlLink.SleepHello h = asleepHellos.get(a.ip());
        long interval = Math.max(1, a.intervalMin()) * 60_000L;
        long next = h != null && h.nextCheckMin() > 0 ? h.receivedAt() + h.nextCheckMin() * 60_000L : a.since() + interval;
        while (next < now) next += interval;
        return next;
    }

    private static String clock(long ms) {
        long left = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60);
    }

    /**
     * A sleeping phone's check-in that isn't this PC's link's own phone (the name service's thread with no link open,
     * or the link's for another phone): remembered for the LINK card and the PHONE list, and answered "sleep on" - WAKE
     * connects, and the link answers the next one "wake". A phone not known to be asleep is taken as asleep when it is
     * one of ours (it went to sleep under an older version, or before a restart).
     * @return false (sleep on), or null for a phone that isn't ours (no answer)
     */
    private Boolean onSleepCheck(String ip, byte[] d) {
        Asleep a = asleepPhones.get(ip);
        if (a == null) {
            String name = names.nameAt(ip);
            if (name == null || Names.kindOf(name) != Names.Kind.PHONE) return null;
            a = new Asleep(name, ip, System.currentTimeMillis(), Math.max(1, d[3] & 0xff),
                    d.length >= 7 && (d[6] & de.droiddrone.common.OverwatchProtocol.HELLO_OVERWATCH) != 0);
            rememberAsleep(a);
        }
        ControlLink.SleepHello h = ControlLink.SleepHello.read(d, d.length, System.currentTimeMillis());
        if (d.length >= 7 && h.overwatch() != a.overwatch()) { // into overwatch, or out of it by itself (the floor, 1.0.47)
            Asleep was = a;
            a = new Asleep(a.name(), a.ip(), a.since(), a.intervalMin(), h.overwatch());
            rememberAsleep(a);
            if (was.overwatch()) {
                String why = h.floorReason() != 0 ? de.droiddrone.common.PowerFloor.describe(h.floorReason()) : "it left overwatch";
                SwingUtilities.invokeLater(() -> logLine("OVERWATCH ended on " + was.name() + ": " + why + " - it is in deep sleep now."));
            }
        }
        ControlLink.SleepHello before = asleepHellos.put(ip, h);
        if (observedNames.contains(a.name())) { // OBSERVATION (1.0.48): it joins the grid at its check-in
            String name = a.name();
            SwingUtilities.invokeLater(() -> {
                clearAsleep(ip);
                logLine(name + " checked in: woken for the observation.");
            });
            return true;
        }
        if (before == null || h.receivedAt() - before.receivedAt() > 20_000) { // one line per check window
            String line = a.name() + (a.overwatch() ? " overwatching" : " asleep") + ", still here: battery " + (h.batteryPercent() >= 0 ? h.batteryPercent() + " %" : "unknown")
                    + (h.charging() ? ", charging" : "") + ", next check in " + h.nextCheckMin() + " min. WAKE on the LINK card brings it back.";
            SwingUtilities.invokeLater(() -> logLine(line));
        }
        return false;
    }

    /** Not connected, the chosen phone asleep: the power row stays, with when it checks next and WAKE. */
    private void refreshAsleep() {
        Asleep a = asleepChosen();
        if (powerRow == null || a == null) return;
        long now = System.currentTimeMillis();
        ControlLink.SleepHello h = asleepHellos.get(a.ip());
        // it answers the name service's queries, and hasn't checked in for a minute: it is awake (woken on the phone)
        Names.Entry e = names.get(a.name());
        if (e != null && e.online() && (h == null || now - h.receivedAt() > 60_000) && now - a.since() > 60_000) {
            logLine(a.name() + " is awake: it answers this PC again.");
            clearAsleep(a.ip());
            return;
        }
        if (!powerRow.isVisible()) powerRow.setVisible(true);
        standbyButton.setEnabled(false);
        deepSleepButton.setEnabled(false);
        checkIntervalCombo.setEnabled(false);
        wakeButton.setEnabled(true);
        String alive = "";
        if (h != null) {
            long ago = (now - h.receivedAt()) / 1000;
            alive = "Heard " + (ago < 60 ? ago + " s" : ago / 60 + " min") + " ago"
                    + (h.batteryPercent() >= 0 ? ", battery " + h.batteryPercent() + " %" : "") + (h.charging() ? " (charging)" : "") + ". ";
        }
        powerStatus.setText("<html>" + a.name() + " is in " + a.mode() + ". " + alive + "Next check in " + clock(nextCheck(a, now) - now)
                + ". WAKE connects and brings it back then.</html>");
    }

    /** WAKE with no link: connect to the sleeping phone as its pilot; the link answers its next check "wake". */
    private void wakeAsleep() {
        Asleep a = asleepChosen();
        if (a == null) return;
        if (ROLE_SPECTATOR.equals(roleCombo.getSelectedItem())) roleCombo.setSelectedItem(ROLE_PILOT);
        save(Map.of(Names.PHONE_NAME_KEY, a.name()));
        wakeOnConnect = true;
        try {
            connect();
        } finally {
            wakeOnConnect = false;
        }
    }

    // ------------------------------------------------------------------ the live sound (1.0.45)

    /**
     * The phone's microphone on this PC while connected: LiveAudio plays it (its own low-priority thread), the
     * recording takes it too. The link's threads only hand each frame on.
     */
    private void startLiveAudio(ControlLink link, VideoPipeline v, String phone) {
        stopLiveAudio();
        LiveAudio audio = new LiveAudio(AlertSound::isPlaying, msg -> SwingUtilities.invokeLater(() -> logLine(msg)));
        liveAudio = audio;
        setAudioMuted(mutedFor(phone), false);
        audioStatesTold.clear();
        lastAudioState = null;
        link.setAudioListener(new ControlLink.AudioListener() {
            @Override
            public void onAudioConfig(AudioConfig c) {
                audio.onConfig(c);
                if (v != null) v.recordAudioConfig(c);
            }

            @Override
            public void onAudioFrame(AudioConfig c, byte[] frame, long arrivedMs) {
                audio.onFrame(frame);
                if (v != null) v.recordAudio(c, frame, arrivedMs);
            }
        });
    }

    private void stopLiveAudio() {
        if (liveAudio != null) liveAudio.close();
        liveAudio = null;
    }

    private static String audioMutedKey(String phone) {
        return AUDIO_MUTED_KEY + "." + phone;
    }

    /** The phone's own choice; never set: not muted. */
    private boolean mutedFor(String phone) {
        return phone != null && !phone.isEmpty() && "true".equalsIgnoreCase(config.getString(audioMutedKey(phone)));
    }

    /** MUTE or M: silent here at once; after 10 s muted the phone stops sending; the choice kept for this phone. */
    private void toggleMute() {
        audioAutoMuted = false;
        setAudioMuted(!audioMuted, true);
        String phone = config.getString(Names.PHONE_NAME_KEY);
        if (phone != null && !phone.isEmpty()) save(Map.of(audioMutedKey(phone), Boolean.toString(audioMuted)));
        logLine(audioMuted ? "Sound muted (M). The phone stops sending it after 10 s." : "Sound on (M).");
    }

    private void setAudioMuted(boolean muted, boolean byHand) {
        audioMuted = muted;
        muteButton.setGlyph(muted ? GlyphButton.Glyph.MUTED : GlyphButton.Glyph.SOUND);
        LiveAudio a = liveAudio;
        if (a != null) a.setMuted(muted);
        Connection c = connection;
        if (c != null) c.getLink().setAudioMuted(muted);
        if (byHand) muteButton.setToolTipText(muteTip(lastAudioState));
    }

    /** What the live audio does, said once a connection in the log, and always in the button's tooltip. */
    private void refreshAudio() {
        autoMuteAtArming();
        Connection c = connection;
        ControlLink.AudioState s = c == null ? ControlLink.AudioState.NONE : c.getLink().audioState();
        if (s == lastAudioState) return;
        lastAudioState = s;
        muteButton.setToolTipText(muteTip(s));
        if (c == null || !audioStatesTold.add(s)) return;
        String line = switch (s) {
            case OLD_PHONE -> "Live sound needs DRAUPNE Flight 2.3.0-pc." + ControlLink.AUDIO_MIN_PC_BUILD + " or newer on the phone (it has "
                    + c.getLink().getTelemetry().getPhoneAppVersion() + "): no sound from it.";
            case ON -> c.getLink().isSpectator() ? "Live sound: the pilot PC passes the phone's microphone on" + (audioMuted ? " (muted here: M)." : ".")
                    : "Live sound: the phone's microphone" + (audioMuted ? " comes, muted here (M)." : " plays on this PC (M mutes it).");
            case HELD -> "Live sound held back by the phone: the drone is armed (or its state unknown)" + (audioMuted ? "." : " - its app sends it in the air from pc.39 on.");
            case FAILED -> "Live sound: the phone couldn't start its microphone (is the microphone allowed for DRAUPNE Flight?).";
            default -> null;
        };
        if (line != null) logLine(line);
    }

    /**
     * Armed = muted (1.0.50, the pilot's rule): at the arming MUTE goes on by itself; the pilot may unmute in the air
     * (M), and the phone (pc.39 on) then sends the sound armed too. At the disarm the sound comes back if it was muted
     * by the arming alone.
     */
    private void autoMuteAtArming() {
        Connection c = connection;
        long now = de.droiddrone.pc.MonoClock.ms();
        boolean armed = c != null && !c.getLink().isSpectator() && c.getLink().getTelemetry().isArmedNow(now, 2000);
        if (armed && !audioSawArmed) {
            if (!audioMuted) {
                audioAutoMuted = true;
                setAudioMuted(true, true);
                logLine("Armed: the sound is muted. M (or the loudspeaker) brings it back in the air.");
            }
        } else if (!armed && audioSawArmed) {
            if (c != null && !c.getLink().getTelemetry().isDisarmedNow(now, 2000)) return; // a 4G gap: wait for the word
            if (audioAutoMuted && audioMuted) {
                setAudioMuted(false, true);
                logLine("Disarmed: the sound is on again.");
            }
            audioAutoMuted = false;
        }
        audioSawArmed = armed;
    }

    private String muteTip(ControlLink.AudioState s) {
        String what = (audioMuted ? "SOUND MUTED: M or click to hear" : "SOUND: M or click to mute")
                + " the phone's microphone; arming mutes it by itself, unmuting in the air brings it back (phone app pc.39 on);"
                + " after 10 s muted the phone stops sending it";
        if (s == null) return what;
        return switch (s) {
            case OLD_PHONE -> what + ". The phone app is too old: live sound needs pc." + ControlLink.AUDIO_MIN_PC_BUILD + ".";
            case HELD -> what + ". Now: held back, the drone is armed.";
            case FAILED -> what + ". Now: the phone's microphone didn't start.";
            case STOPPED -> what + ". Now: the phone has stopped sending.";
            case ON -> what + ". Now: on.";
            default -> what + ".";
        };
    }

    // ------------------------------------------------------------------ the camera's way up, per phone (1.0.41)

    private static String upsideDownKey(String phone) {
        return UPSIDE_DOWN_KEY + "." + phone;
    }

    /** The phone's own setting; one never set takes the old PC-wide one (up to 1.0.40). */
    private boolean upsideDownFor(String phone) {
        String v = phone == null || phone.isEmpty() ? "" : config.getString(upsideDownKey(phone));
        if (v.isEmpty()) v = config.getString(UPSIDE_DOWN_KEY);
        return "true".equalsIgnoreCase(v);
    }

    // ------------------------------------------------------------------ kept flights (1.0.41)

    private void loadKeptFlights() {
        String saved = config.getString(KEPT_FLIGHTS_KEY);
        if (saved == null || saved.isBlank()) return;
        for (String one : saved.split("\\|")) {
            int at = one.indexOf('>');
            if (at <= 0) continue;
            java.nio.file.Path file = java.nio.file.Path.of(one.substring(at + 1));
            try {
                de.droiddrone.pc.map.FlightTrack t = de.droiddrone.pc.map.FlightTrack.read(file);
                if (!t.isEmpty()) keptFlights.add(new KeptFlight(one.substring(0, at), file, t.points()));
            } catch (IOException | RuntimeException e) {
                // the file is gone: the flight with it
            }
        }
    }

    private void saveKeptFlights() {
        StringBuilder b = new StringBuilder();
        for (KeptFlight k : keptFlights) {
            if (k.file() == null) continue;
            if (b.length() > 0) b.append('|');
            b.append(k.name().replace("|", " ").replace(">", " ")).append('>').append(k.file());
        }
        save(Map.of(KEPT_FLIGHTS_KEY, b.toString()));
    }

    /** A flight ended (a new connection, the pilot on another phone, DISCONNECT): it stays on the map. */
    private void keepFlight(de.droiddrone.pc.map.FlightTrack t, String name) {
        if (t == null || t.isEmpty()) return;
        java.nio.file.Path file = t.file();
        for (KeptFlight k : keptFlights) if (file != null && file.equals(k.file())) return; // kept already
        keptFlights.add(new KeptFlight(name == null ? "Flight " + (keptFlights.size() + 1) : name, file, t.points()));
        saveKeptFlights();
        pushKept();
    }

    /** The map views get the kept flights, but the one shown live (the flight just ended stays the live one until the next). */
    private void pushKept() {
        java.nio.file.Path live = track == null ? null : track.file();
        java.util.List<MapView.Kept> list = new java.util.ArrayList<>();
        java.util.List<Integer> index = new java.util.ArrayList<>();
        for (int i = 0; i < keptFlights.size(); i++) {
            KeptFlight k = keptFlights.get(i);
            if (live != null && live.equals(k.file())) continue;
            list.add(new MapView.Kept(k.name(), k.points()));
            index.add(i);
        }
        java.util.function.IntConsumer remove = shown -> {
            if (shown < 0 || shown >= index.size()) return;
            KeptFlight k = keptFlights.remove((int) index.get(shown));
            saveKeptFlights();
            logLine("Flight " + k.name() + " taken off the map (its file stays in the Tracks folder).");
            pushKept();
        };
        Runnable clear = () -> {
            int n = list.size();
            if (JOptionPane.showConfirmDialog(this, "Take all " + n + " earlier flight" + (n == 1 ? "" : "s") + " off the map?\n"
                    + "Their files stay in the Tracks folder.", "Earlier flights", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            keptFlights.removeIf(k -> live == null || !live.equals(k.file()));
            saveKeptFlights();
            logLine(n + " earlier flight" + (n == 1 ? "" : "s") + " taken off the map.");
            pushKept();
        };
        miniMap.setKept(list, remove, clear);
        if (mapWindow != null) mapWindow.map().setKept(list, remove, clear);
    }

    private static String flightName(String who) {
        return (who == null || who.isEmpty() ? "Flight" : who) + " " + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** Watching, and the pilot flies another phone now: the flight so far is kept, a new one starts. */
    private void followWatchedPhone(ControlLink link) {
        String phone = link.getPilotPhone();
        if (phone == null) return;
        if (watchedPhone != null && !phone.equals(watchedPhone)) {
            keepFlight(liveTrack, liveTrackName);
            if (liveTrack != null) liveTrack.close(pins.list());
            try {
                liveTrack = new de.droiddrone.pc.map.FlightTrack(de.droiddrone.pc.map.FlightTrack.defaultDir());
            } catch (IOException e) {
                liveTrack = new de.droiddrone.pc.map.FlightTrack(null);
            }
            track = liveTrack;
            logLine("The pilot flies " + phone + " now (it flew " + watchedPhone + "): that flight stays on the map, a new one starts.");
            pushKept();
        }
        if (!phone.equals(watchedPhone)) liveTrackName = flightName(phone);
        watchedPhone = phone;
    }

    // ------------------------------------------------------------------ every drone on the network (1.0.41)

    /**
     * Once a second: the other drones on the map (where their phones say they are, phone app pc.32), and the PHONE
     * list's sleep countdowns.
     */
    private void refreshNetwork() {
        long now = System.currentTimeMillis();
        String flying = connection == null ? null : connection.getLink().isSpectator() ? connection.getLink().getPilotPhone()
                : config.getString(Names.PHONE_NAME_KEY);
        java.util.List<MapView.NetDrone> drones = new java.util.ArrayList<>();
        for (NameService.DronePosition d : nameService.dronePositions()) {
            if (d.name().equalsIgnoreCase(flying)) continue; // shown live already
            if (observedNames.contains(d.name())) continue; // OBSERVATION draws it, numbered
            Names.Position p = d.position();
            long age = now - d.at() + p.ageS() * 1000L;
            String ip = names.ipOf(d.name());
            Asleep sleeping = ip == null ? null : asleepPhones.get(ip);
            String pilot = p.pilotIp().isEmpty() ? null : names.nameAt(p.pilotIp());
            String state = (de.droiddrone.pc.fleet.VirtualFleet.isVirtual(ip) ? "VIRTUAL - " : "") + (sleeping != null ? sleeping.mode() + " - check in " + clock(nextCheck(sleeping, now) - now)
                    : (Boolean.TRUE.equals(p.armed()) ? "ARMED - " : "") + (p.flown() ? "FLOWN" + (pilot == null ? "" : " BY " + pilot) : "IDLE")
                    + (age > 30_000 ? " - " + Names.agoText(now - age, now) : ""));
            drones.add(new MapView.NetDrone(d.name(), p.lat(), p.lon(), state, age > 30_000 || sleeping != null, p.flown(), pilot));
        }
        miniMap.setNetDrones(drones, this::connectFromMap);
        if (mapWindow != null) mapWindow.map().setNetDrones(drones, this::connectFromMap);
        if (!asleepPhones.isEmpty() && connection == null && !phoneCombo.isPopupVisible()) updatePhoneList(); // the countdowns
    }

    /** Right-click on a drone on the map: fly it (as pilot), or watch it through the PC that flies it. */
    private void connectFromMap(String phone, boolean watch) {
        if (observation != null) {
            JOptionPane.showMessageDialog(this, "This PC observes now. CONNECT on " + phone + "'s tile flies it, or STOP OBSERVING first.",
                    "Observing", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (connection != null) {
            JOptionPane.showMessageDialog(this, "This PC is connected now. Press DISCONNECT first, then connect to " + phone + " from the map.",
                    "Connected", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String target = phone;
        if (watch) {
            NameService.DronePosition d = nameService.dronePositions().stream().filter(x -> x.name().equals(phone)).findFirst().orElse(null);
            target = d == null || d.position().pilotIp().isEmpty() ? null : names.nameAt(d.position().pilotIp());
            if (target == null) {
                JOptionPane.showMessageDialog(this, "The PC that flies " + phone + " isn't in the name map yet.", "Watch", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        }
        roleCombo.setSelectedItem(watch ? ROLE_SPECTATOR : ROLE_PILOT);
        save(Map.of(Names.PHONE_NAME_KEY, target));
        updatePhoneList();
        logLine("From the map: " + (watch ? "watching " + phone + " through " + target : "connecting to " + phone) + ".");
        connect();
    }

    /** Flying with spectators: this PC's OSD warnings, for theirs (4 a second at most). */
    private void sharePilotView(ControlLink link) {
        long now = de.droiddrone.pc.MonoClock.ms();
        if (now - pilotViewSentAt < 200) return;
        pilotViewSentAt = now;
        OsdRenderer.Data d = osdData();
        if (d == null) return;
        link.setPilotView(new ControlLink.PilotView(d.linkReady(), d.rcLive(), d.radioFrozen(), d.armSwitchUnknown(), d.relayed(),
                d.linkStalled(), d.rcWaiting(), d.banner(), d.linkHint(), d.channels(), config.getString(Names.PHONE_NAME_KEY),
                videoView.isUpsideDown()));
    }

    private void wakePhone() {
        if (demoPower(ControlLink.POWER_NORMAL)) return;
        if (connection == null && asleepChosen() != null) {
            wakeAsleep();
            return;
        }
        powerWanted = ControlLink.POWER_NORMAL;
        powerAskedAt = de.droiddrone.pc.MonoClock.ms();
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
        long now = de.droiddrone.pc.MonoClock.ms();
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
        // the phone said DEEP SLEEP (or OVERWATCH, 1.0.47), then the link went: it sleeps now, and this PC must not wake
        // it until WAKE
        boolean sleepMode = powerWanted == ControlLink.POWER_DEEP_SLEEP || powerWanted == ControlLink.POWER_OVERWATCH;
        if (sleepMode && reported == powerWanted && !phoneAsleep && !link.isConnected()) {
            phoneAsleep = true;
            phoneOverwatching = powerWanted == ControlLink.POWER_OVERWATCH;
            sleepStartAt = pp.receivedAt() + pp.sleepInS() * 1000L;
            link.setDormant(true);
            logLine(phoneOverwatching ? "The phone overwatches: its camera watches, the rest sleeps. Something moving shows in the map window; "
                    + "it listens for this PC every " + checkIntervalMin + " min; press WAKE to bring it back."
                    : "The phone is in deep sleep. It listens for this PC for 20 s every " + checkIntervalMin + " min; press WAKE to bring it back.");
        }
        if (phoneAsleep) {
            if (link.isConnected()) {
                phoneAsleep = false;
                phoneOverwatching = false;
                powerWanted = ControlLink.POWER_NORMAL;
                powerSentAt = 0;
                link.setDormant(false);
                link.restartVideo();
                logLine("The phone is awake again.");
                clearAsleep(config.getString("ip"));
            } else {
                long interval = Math.max(1, checkIntervalMin) * 60_000L;
                long next = sleepStartAt + interval;
                // the phone's own word from its last check (pc.22): when the next one is, and its battery
                ControlLink.SleepHello hello = link.getSleepHello();
                String alive = "";
                if (hello != null && hello.receivedAt() >= sleepStartAt - 5000 && phoneOverwatching && !hello.overwatch()
                        && hello.receivedAt() != lastHelloLogged) {
                    phoneOverwatching = false; // the floor: it left overwatch for deep sleep by itself (1.0.47)
                    logLine("OVERWATCH ended on the phone: " + (hello.floorReason() != 0 ? de.droiddrone.common.PowerFloor.describe(hello.floorReason())
                            : "it left overwatch") + " - it is in deep sleep now.");
                }
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
                        ? "<html>" + (phoneOverwatching ? "OVERWATCH - its camera watches; an alert shows in the map window. " : "DEEP SLEEP - ") + alive
                        + "The phone checks for this PC every " + checkIntervalMin + " min; next check in " + in + ". WAKE brings it back.</html>"
                        : "<html>WAKE sent - " + alive + "The phone takes it at its next check, in " + in + ", then its camera starts.</html>");
                standbyButton.setEnabled(false);
                deepSleepButton.setEnabled(false);
                overwatchButton.setEnabled(false);
                wakeButton.setEnabled(link.isDormant());
                checkIntervalCombo.setEnabled(false);
            }
            return;
        }
        if (ready && powerWanted != reported) {
            if (now - powerSentAt >= 200) {
                if (powerWanted == ControlLink.POWER_OVERWATCH) {
                    // the drone's own motion detection level (OFF counts as NORMAL here: watching is the point), and its
                    // microphone in the clip unless this PC has the drone's sound muted
                    int level = detectionFor(config.getString(Names.PHONE_NAME_KEY));
                    if (level == 0) level = 2;
                    int flags = mutedFor(config.getString(Names.PHONE_NAME_KEY)) ? 0 : de.droiddrone.common.OverwatchProtocol.MODE_FLAG_SOUND;
                    link.sendOverwatch(checkIntervalMin, level, flags, overwatchToken);
                } else {
                    link.sendPowerMode(powerWanted, checkIntervalMin);
                }
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
        } else if (reported == ControlLink.POWER_OVERWATCH) {
            powerStatus.setText("Going to overwatch in " + pp.sleepInS() + " s...");
        } else {
            powerStatus.setText("<html>Before take-off: STANDBY saves power and is back in 2 s; DEEP SLEEP saves more and is back at the phone's next check; "
                    + "OVERWATCH sleeps with the camera watching.</html>");
        }
        boolean idle = ready && powerWanted == ControlLink.POWER_NORMAL && reported <= 0;
        standbyButton.setEnabled(idle && !fcArmedNow());
        deepSleepButton.setEnabled(idle && !fcArmedNow());
        overwatchButton.setEnabled(idle && !fcArmedNow());
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
            if (asleepChosen() != null && !tourPowerDemo) refreshAsleep();
            else if (powerRow != null && powerRow.isVisible() && !tourPowerDemo) powerRow.setVisible(false);
            if (observation == null) setPill("OFFLINE", Theme.PANEL_2, Theme.MUTED); // observing: refreshObservation's
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
        if (!link.isSpectator() && link.getSpectatorCount() > 0) sharePilotView(link);
        if (link.isSpectator()) {
            followWatchedPhone(link);
            ControlLink.PilotView pv = link.getPilotView();
            if (pv != null && pv.upsideDown() != videoView.isUpsideDown()) videoView.setUpsideDown(pv.upsideDown()); // the pilot's phone's way up
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
        } else if (phoneAsleep) {
            videoView.setIdleText(!link.isDormant() ? "Waking the phone at its next check..."
                    : phoneOverwatching ? "OVERWATCH - the phone's camera watches; alerts show in the map window. Press WAKE"
                    : "DEEP SLEEP - the phone sleeps; press WAKE");
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
            case READY -> {
                // M14 (1.0.49): green only while the phone is heard - not for the 2 s before the link counts as lost
                long now = de.droiddrone.pc.MonoClock.ms();
                if (!link.isLinkStalled()) stallSeenAt = 0;
                else if (stallSeenAt == 0) stallSeenAt = now;
                String text = readyPill(stallSeenAt == 0 ? 0 : now - stallSeenAt, link.isFcLost(), spectators);
                setPill(text, text.startsWith("LINK READY") ? Theme.OK : Theme.BAD, Theme.BG);
            }
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
                : t.phoneAppSilent(de.droiddrone.pc.MonoClock.ms()));
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
        boolean pilot = connection != null && !connection.getLink().isSpectator();
        boolean offer = (old && pilot) || remoteUpdateAskedAt != 0;
        if (updatePhoneButton.isVisible() != offer) {
            updatePhoneButton.setVisible(offer);
            updatePhoneButton.getParent().revalidate();
        }
        if (t != null && pilot) refreshRemoteUpdate(t, version);
    }

    // ------------------------------------------------------------------ remote update of the phone app (1.0.41)

    /** UPDATE PHONE APP: disarmed, a phone app that takes it, and a yes - then the request goes out until the phone answers. */
    private void askRemoteUpdate() {
        if (connection == null || connection.getLink().isSpectator()) return;
        FcTelemetry t = connection.getLink().getTelemetry();
        String version = t.getPhoneAppVersion();
        String newest = de.droiddrone.pc.setup.ApkShare.VERSION;
        if (de.droiddrone.pc.PossiblyFlying.now()) { // armed, or armed moments before the telemetry went quiet (1.0.49)
            String why = de.droiddrone.pc.PossiblyFlying.reasonNow();
            new LockedNotice(updatePhoneButton, videoView, "ARMED", "No update while the drone may be flying: it restarts the phone app, and the sticks with it."
                    + (why == null ? "" : " (" + why + ".)")).pop();
            return;
        }
        if (version == null || de.droiddrone.pc.setup.UpdateCheck.isNewer(REMOTE_UPDATE_MIN_PHONE, version)) {
            JOptionPane.showMessageDialog(this, "The phone runs " + (version == null ? "an old phone app" : version) + ", which can't be updated from here.\n"
                    + "Update it once on the phone (SETUP > Phone setup, or the update line in the phone app):\n"
                    + "from " + REMOTE_UPDATE_MIN_PHONE + " on, UPDATE PHONE APP does it from this PC.", "Update phone app", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!de.droiddrone.pc.setup.UpdateCheck.isNewer(newest, version)) {
            JOptionPane.showMessageDialog(this, "The phone runs " + version + " already - this PC brings that one.", "Update phone app", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this, "Update the phone to DRAUPNE Flight " + newest + "?\n\n"
                + "The phone downloads it over its own internet (about 23 MB), checks it, and installs it.\n"
                + "The phone app restarts: the link drops for a moment and comes back by itself.\n"
                + "Only while the drone is disarmed - keep it on the ground.", "Update phone app", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) return;
        remoteUpdateAskedAt = de.droiddrone.pc.MonoClock.ms();
        remoteUpdateSentAt = 0;
        remoteUpdateVersion = newest;
        lastPhoneUpdate = null;
        logLine("Phone app update to " + newest + " asked: the phone downloads and installs it.");
    }

    /** While a remote update runs: the request again until the phone answers, and its progress on the card. */
    private void refreshRemoteUpdate(FcTelemetry t, String phoneVersion) {
        if (remoteUpdateAskedAt == 0) return;
        long now = de.droiddrone.pc.MonoClock.ms();
        String want = remoteUpdateVersion;
        if (phoneVersion != null && want != null && !de.droiddrone.pc.setup.UpdateCheck.isNewer(want, phoneVersion)) {
            logLine("The phone app is " + phoneVersion + " now: updated.");
            remoteUpdateAskedAt = 0;
            updatePhoneButton.setText("Update phone app");
            updatePhoneButton.setEnabled(true);
            return;
        }
        FcTelemetry.PhoneUpdate u = t.getPhoneUpdate();
        boolean answered = u != null && u.receivedAt() >= remoteUpdateAskedAt;
        if (!answered && de.droiddrone.pc.PossiblyFlying.now()) {
            // armed since UPDATE PHONE APP was pressed: the request is not sent (1.0.49) - asked again from the button
            logLine("Phone app update not asked: the drone may be flying.");
            remoteUpdateAskedAt = 0;
            updatePhoneButton.setText("Update phone app");
            updatePhoneButton.setEnabled(true);
            return;
        }
        if (!answered && now - remoteUpdateSentAt >= 1000 && now - remoteUpdateAskedAt < 10_000) {
            connection.getLink().sendRemoteUpdate(want, de.droiddrone.pc.setup.ApkShare.APK_URL, de.droiddrone.pc.setup.ApkShare.APK_SHA256);
            remoteUpdateSentAt = now;
        }
        String text;
        boolean over = false;
        if (!answered) {
            text = now - remoteUpdateAskedAt < 10_000 ? "Asking the phone..." : "The phone doesn't answer the update.";
            over = now - remoteUpdateAskedAt >= 10_000;
        } else {
            text = switch (u.state()) {
                case 1 -> "Phone: downloading " + want + "... " + u.percent() + " %";
                case 2 -> "Phone: installing - the app restarts, the link comes back by itself.";
                case 3 -> "Phone: tap Update on the phone's screen (Android asks once; from then on it updates without a tap).";
                case 4 -> "Phone: the update failed - " + u.text();
                case 5 -> "Phone: not updated - " + u.text() + ". Disarm first.";
                case 6 -> "Phone: it runs " + u.text() + " already.";
                default -> "Phone: " + u.text();
            };
            over = u.state() >= 4;
        }
        if (lastPhoneUpdate == null || u != null && (u.state() != lastPhoneUpdate.state())) {
            if (u != null && answered) logLine(text);
            lastPhoneUpdate = answered ? u : null;
        }
        updatePhoneButton.setText(over ? "Update phone app" : text.length() > 34 ? "Updating the phone..." : text);
        updatePhoneButton.setToolTipText(text);
        updatePhoneButton.setEnabled(over);
        phoneAppWarning.setHtml(text);
        if (!phoneAppWarning.isVisible()) {
            phoneAppWarning.setVisible(true);
            phoneAppWarning.getParent().revalidate();
        }
        if (over) {
            if (!answered) logLine("The phone app doesn't answer the update request: it is older than " + REMOTE_UPDATE_MIN_PHONE + ".");
            remoteUpdateAskedAt = 0;
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

    JComponent tourOverwatchButton() {
        return overwatchButton;
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
            overwatchButton.setEnabled(true);
            wakeButton.setEnabled(false);
            powerStatus.setText("A demo while nothing is connected: press STANDBY, DEEP SLEEP, OVERWATCH or WAKE to read what the phone would do.");
        } else {
            standbyButton.setEnabled(true);
            deepSleepButton.setEnabled(true);
            overwatchButton.setEnabled(true);
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
        overwatchButton.setEnabled(mode == ControlLink.POWER_NORMAL);
        wakeButton.setEnabled(mode != ControlLink.POWER_NORMAL);
        powerStatus.setText(switch (mode) {
            case ControlLink.POWER_OVERWATCH -> "<html>OVERWATCH - the phone would sleep like DEEP SLEEP but keep its camera and the motion "
                    + "detection on: when something moves it calls this PC - the map window shows where, a live picture, CONNECT and DISMISS - "
                    + "and records it in full quality. Under 20 % battery or too hot it goes to deep sleep by itself.</html>";
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

    /** The controls' bounds in the glass pane (where the boot animation paints); null while any has no parent yet. */
    private java.awt.Rectangle[] boundsInPane(JComponent... controls) {
        java.awt.Rectangle[] r = new java.awt.Rectangle[controls.length];
        for (int i = 0; i < controls.length; i++) {
            JComponent c = controls[i];
            if (c == null || c.getParent() == null || !c.isShowing()) return null;
            r[i] = SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), getRootPane().getGlassPane());
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
        // the phone's own (1.0.41, the pilot: "make the upside down setting follow the phone not the computer");
        // watching, the pilot's phone decides (PILOT_VIEW), so nothing is saved
        boolean watching = connection != null ? connection.getLink().isSpectator() : ROLE_SPECTATOR.equals(roleCombo.getSelectedItem());
        String phone = config.getString(Names.PHONE_NAME_KEY);
        if (!watching && !phone.isEmpty()) save(Map.of(upsideDownKey(phone), Boolean.toString(on)));
        else if (!watching) save(Map.of(UPSIDE_DOWN_KEY, Boolean.toString(on)));
        logLine(on ? "Picture turned upside down." : "Picture the right way up.");
    }

    /**
     * F11: the video alone in exclusive full screen. Windows then puts the picture on the screen directly instead of
     * first composing it into the desktop, which can save up to a screen refresh. Leaving the window (alt-tab, another
     * window) drops back to normal.
     */
    private void toggleFullScreen() {
        if (observationGrid != null) {
            logLine("Not while observing: the grid stays in the main window.");
            return;
        }
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
        if (observationGrid != null) {
            logLine("Not while observing: the grid stays in the main window.");
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
        if (!text.equals(label.getText())) {
            label.setText(text);
            label.setToolTipText(text.isBlank() ? null : text); // the whole line, when a narrow window cuts it short
        }
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
