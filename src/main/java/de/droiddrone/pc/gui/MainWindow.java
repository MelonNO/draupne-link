package de.droiddrone.pc.gui;

import de.droiddrone.common.FcInfo;
import de.droiddrone.common.FleetProtocol;
import de.droiddrone.common.Log;
import de.droiddrone.common.Observe;
import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.common.PowerFloor;
import de.droiddrone.common.SettingsCommon;
import de.droiddrone.common.SleepCheck;
import de.droiddrone.pc.AppInfo;
import de.droiddrone.pc.AppLog;
import de.droiddrone.pc.AppPaths;
import de.droiddrone.pc.ArmSwitch;
import de.droiddrone.pc.CameraView;
import de.droiddrone.pc.Connection;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.DetectionMarks;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.FleetManager;
import de.droiddrone.pc.Joystick;
import de.droiddrone.pc.JoystickService;
import de.droiddrone.pc.LinkLog;
import de.droiddrone.pc.Main;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.NameService;
import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.PossiblyFlying;
import de.droiddrone.pc.RcMapper;
import de.droiddrone.pc.RcSession;
import de.droiddrone.pc.WindowsTuning;
import de.droiddrone.pc.audio.AudioConfig;
import de.droiddrone.pc.audio.LiveAudio;
import de.droiddrone.pc.audio.ObservationAudio;
import de.droiddrone.pc.fleet.VirtualFleet;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.Elevation;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;
import de.droiddrone.pc.observe.HeadingChoice;
import de.droiddrone.pc.observe.ObservationSession;
import de.droiddrone.pc.observe.ObserveSet;
import de.droiddrone.pc.observe.ObservedCone;
import de.droiddrone.pc.observe.ObserverLink;
import de.droiddrone.pc.observe.TileFeed;
import de.droiddrone.pc.overwatch.AlertCard;
import de.droiddrone.pc.overwatch.ClipIndex;
import de.droiddrone.pc.overwatch.OverwatchHub;
import de.droiddrone.pc.setup.ApkShare;
import de.droiddrone.pc.setup.ModeSwitch;
import de.droiddrone.pc.setup.SelfUpdate;
import de.droiddrone.pc.setup.Tailscale;
import de.droiddrone.pc.setup.UpdateCheck;
import de.droiddrone.pc.video.LatencyMeter;
import de.droiddrone.pc.video.StanagStream;
import de.droiddrone.pc.gui.PowerController.Asleep;
import de.droiddrone.pc.video.VideoPipeline;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.FocusManager;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Main window: link settings and status on the left, video area and live controller view on the right. */
public class MainWindow extends JFrame {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final PcConfig config;
    /** Each phone's way up, for every view of its picture (1.0.55). */
    private final CameraTurn cameraTurn;
    private final Path configFile;
    private final JoystickService service;
    private RcMapper mapper;
    private String mapperError;
    private boolean calibrated;
    private Connection connection;
    private RcSession session;
    private final AlertSound alertSound = new AlertSound();
    /** Settings keys the template names (PcConfig): the phone's address, the controller, OSD and video on/off. */
    static final String IP_KEY = "ip";
    static final String JOYSTICK_KEY = "joystick";
    static final String OSD_KEY = "osd";
    static final String OSD_INVERT_PITCH_KEY = "osdInvertPitch";
    static final String VIDEO_KEY = "video";
    static final String RECORD_OSD_KEY = "recordOsd";
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
    private final TileCache tileCache = new TileCache(TileCache.defaultDir());
    private final Elevation elevation = new Elevation();
    /** Where the camera looks (1.0.39): drawn on the maps, and the STANAG 4609 stream's frame centre and corners. */
    private final CameraView cameraView = new CameraView(tileCache);
    private final CameraView.Live cameraLive = new CameraView.Live(cameraView);
    private boolean cameraOnMap;
    /** The STANAG 4609 stream while it is on and the video runs; null otherwise. */
    private StanagStream stanag;
    private MapWindow mapWindow;
    static final String MAP_SOURCE_KEY = "mapSource";
    static final String COORDINATES_KEY = "coordinateFormat";
    static final String GPS_SOURCE_KEY = "gpsSource";
    private TileSource mapSource;
    private Coordinates.Format coordinateFormat = Coordinates.Format.MGRS;
    /** MAP > Position from: the phone's own GPS rather than the drone's (the other fills in when it has nothing). */
    private boolean preferPhone;
    /**
     * The track shown on the map and in FIND DRONE: the connection's once it has a position, until then the last
     * one's (a second CONNECT after losing the drone must not wipe its last known position).
     */
    private FlightTrack track;
    /** The connection's own track, written to a GPX file as it comes; becomes {@link #track} with its first point. */
    private FlightTrack liveTrack;
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
    // ---- OVERWATCH SLEEP (1.0.47, phone app pc.36 on): deep sleep with the camera and the motion detection on; a
    // detection calls this PC, which shows it in the map window (OverwatchHub, MapWindow's card)
    private final JButton overwatchButton = Theme.secondaryButton("Overwatch");
    private final OverwatchHub overwatch = new OverwatchHub(
            new ClipIndex(ClipIndex.defaultRoot()));
    /** The token of the OVERWATCH request being made (the phone's overwatch packets carry it). */
    private int overwatchToken;
    /** The drones put to overwatch, for the hub after a restart: name|token|pose;... */
    static final String OVERWATCH_KEY = "overwatch.watched";
    /** POP OUT MAP: says OVERWATCH while an alert waits and the map window is closed. */
    private JButton popMapButton;
    private boolean overwatchBadge;
    private final JComboBox<String> checkIntervalCombo = new JComboBox<>(java.util.Arrays.stream(CHECK_INTERVALS).mapToObj(SleepCheck::label).toArray(String[]::new));
    /** CHECK EVERY's choices in order; "Constant" (was "0 min") is SleepCheck.ALWAYS, always listening (1.0.50). */
    static final int[] CHECK_INTERVALS = {SleepCheck.ALWAYS, 1, 2, 5};
    /** What the modes are doing; wraps to the card (a label would cut the line). */
    private final WrappingText powerStatusText = WrappingText.fitting(" ", Theme.font(Font.PLAIN, 12f), Theme.MUTED);
    private final PowerStatus powerStatus = new PowerStatus();
    private JPanel powerRow;
    /** CHECK EVERY and its list, under the sleep buttons (the tour lights it with them). */
    private JPanel checkEveryRow;
    /** DEEP SLEEP / OVERWATCH, and STANDBY before them while the chosen phone sleeps (1.0.56). */
    private JPanel sleepButtonsRow;

    /** STANDBY in the sleep buttons' row: only for a phone in DEEP SLEEP or OVERWATCH (the pilot's three buttons otherwise). */
    private void showStandbyButton(boolean on) {
        JPanel row = sleepButtonsRow;
        if (row == null || (standbyButton.getParent() == row) == on) return;
        if (on) row.add(standbyButton, 0);
        else row.remove(standbyButton);
        row.revalidate();
        row.repaint();
    }
    /** DEEP SLEEP and OVERWATCH, folded away (animated) while the drone is armed: no sleep then. */
    private Fold sleepFold;

    /** setText on the wrapping text, with the html wrapper the old label calls used. */
    private final class PowerStatus {
        void setText(String html) {
            String h = html == null ? "" : html.replace("<html>", "").replace("</html>", "");
            powerStatusText.setHtml(h.isBlank() ? "&nbsp;" : h);
        }
    }
    private static final String CHECK_INTERVAL_KEY = "phone.sleepCheckMin";
    /** CHECK EVERY for OVERWATCH (1.0.56): its own, Constant until chosen. */
    private static final String OVERWATCH_INTERVAL_KEY = "phone.overwatchCheckMin";
    /** CHECK EVERY shows (and sets) OVERWATCH's interval: the chosen phone overwatches, or OVERWATCH was pressed last. */
    private boolean intervalForOverwatch;
    private boolean fillingInterval;

    /** A saved CHECK EVERY, one of the list's; the mode's default when none (or another) is saved. */
    private int savedInterval(String key, int mode) {
        int v;
        try {
            v = Integer.parseInt(config.getString(key));
        } catch (NumberFormatException ignored) {
            v = PowerController.defaultInterval(mode);
        }
        for (int i : CHECK_INTERVALS) if (i == v) return v;
        return PowerController.defaultInterval(mode);
    }

    /** CHECK EVERY shows the interval of OVERWATCH (true) or DEEP SLEEP; the list's choice changes nothing. */
    private void showIntervalFor(boolean overwatchMode) {
        intervalForOverwatch = overwatchMode;
        int v = overwatchMode ? power.overwatchIntervalMin : power.checkIntervalMin;
        int chosen = CHECK_INTERVALS.length - 1;
        for (int i = 0; i < CHECK_INTERVALS.length; i++) if (CHECK_INTERVALS[i] == v) chosen = i;
        if (checkIntervalCombo.getSelectedIndex() == chosen) return;
        fillingInterval = true;
        try {
            checkIntervalCombo.setSelectedIndex(chosen);
        } finally {
            fillingInterval = false;
        }
    }
    /** Crashed drones (CRASHED_KEY): the name as the name map has it -> when LOST DRONE was pressed (wall clock). */
    private final Map<String, Long> crashed = new ConcurrentHashMap<>();

    /**
     * Earlier flights kept on the map until removed (1.0.41): when this PC connects again, the flight before stays; a
     * spectator's flight ends when the pilot moves to another phone. Saved (name>file|...), read back at the start.
     */
    private record KeptFlight(String name, Path file, List<FlightTrack.Point> points) {}
    private final List<KeptFlight> keptFlights = new ArrayList<>();
    private static final String KEPT_FLIGHTS_KEY = "map.keptFlights";
    /** The live flight's name on the map ("MobileAlpha 22:41"). */
    private String liveTrackName;
    /** Watching: the phone the pilot flew at the last look (a new one: a new flight). */
    private String watchedPhone;
    /** The other drones on the network, for the map (1 s). */
    private final Timer networkTimer = new Timer(1000, e -> {
        refreshNetwork();
        refreshFleet(); // FLEET MANAGEMENT's cards (1.0.52)
    });
    private static final String ASLEEP_KEY = "phone.asleep";
    /** "name@wallMs;..." - the drones LOST DRONE was pressed for (1.0.52): CRASHED until heard again or cleared. */
    static final String CRASHED_KEY = "drone.crashed";
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
    private Pins pins;
    /** The minimap on the LINK card, under the figures (1.0.39: always there; it no longer takes the fields' place). */
    private final MapView miniMap = new MapView(tileCache);
    /** The pilot's drawings on the map (Documents\DRAUPNE Link\graphics.kml), shared by the map window and the minimap. */
    private MapGraphics graphics;
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
    /** The power modes and the phones asleep (started now: a sleeping phone gets one check-in interval from here). */
    private final PowerController power = new PowerController(new PowerHost(), System::currentTimeMillis);
    /** The drone read armed at the last refresh (for the arming's auto-mute). */
    private boolean audioSawArmed;
    /** A virtual drone's arming mute comes this long after it (1.0.52): its motors spinning up are heard. */
    private static final long SPIN_UP_HEARD_MS = 1500;
    /** When this arming was seen (MonoClock ms), and whether its mute has come. */
    private long armedSoundAt;
    private boolean armMuteDone;
    /** The phone's sound while connected, or null. */
    private LiveAudio liveAudio;
    /** What the live audio did at the last refresh, and the states already told in the log this connection. */
    private ControlLink.AudioState lastAudioState;
    private final EnumSet<ControlLink.AudioState> audioStatesTold = EnumSet.noneOf(ControlLink.AudioState.class);
    private final JLabel poppedOutNote = Theme.label("VIDEO IS IN ITS OWN WINDOW", Theme.headingFont(16f), Theme.MUTED);
    private LatencyMeter latencyMeter;
    private JPanel linkColumn;
    private JComponent controllerCard;
    /** The LINK card: the connection fields, in flight the minimap; the tour points at it. */
    private JComponent linkCard;
    /** The minimap and POP OUT MAP in a card of their own, gone while the map window is open. */
    private JComponent mapCard;
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
    private final CardLayout joystickCards = new CardLayout();
    private final JPanel joystickSlot = new JPanel(joystickCards);
    private Color joystickFrame;

    /** DRAUPNE names (1.0.35): the devices seen on the Tailscale network, and the service that keeps asking them. */
    private final Names names;
    private final NameService nameService;
    private final JLabel phoneLabel = Theme.label("PHONE", Theme.headingFont(11f), Theme.MUTED);
    /** The phone to fly (as a spectator: the pilot PC to watch), by name; the address comes from the name map. */
    private final JComboBox<String> phoneCombo = new JComboBox<>();
    private final JLabel phoneHint = Theme.label("", Theme.font(Font.PLAIN, 12f), Theme.MUTED);
    private List<Names.Entry> phoneList = List.of();
    private boolean fillingPhones;
    private final JButton connectButton = Theme.primaryButton("Connect");
    /** Connect as: pilot (fly) or spectator (watch only). */
    private final JComboBox<String> roleCombo = new JComboBox<>(new String[]{ROLE_PILOT, ROLE_OBSERVER, ROLE_SPECTATOR}); // 1.0.56: the pilot's order
    private static final String ROLE_PILOT = "Pilot - fly the drone";
    private static final String ROLE_SPECTATOR = "Spectator - watch only";
    /** OBSERVATION (1.0.48): up to six idle drones at once in the video area; CONNECT on a tile flies that one. */
    private static final String ROLE_OBSERVER = "Observation - watch idle drones";
    /** The LINK card's title: the mode CONNECT AS is in (1.0.56, the pilot: not "LINK"). */
    private final JLabel linkTitle = Theme.sectionTitle("Pilot mode");

    /** "PILOT MODE", "OBSERVER MODE" or "SPECTATOR MODE" for a CONNECT AS choice. */
    static String linkTitleFor(Object role) {
        return ROLE_SPECTATOR.equals(role) ? "SPECTATOR MODE" : ROLE_OBSERVER.equals(role) ? "OBSERVER MODE" : "PILOT MODE";
    }

    private void refreshLinkTitle() {
        String t = linkTitleFor(roleCombo.getSelectedItem());
        if (!t.equals(linkTitle.getText())) linkTitle.setText(t);
    }
    private static final String CONNECT_AS_OBSERVATION = "observation";
    static final String OBSERVE_VOLUME_KEY = "observe.volume";
    private final JButton chooseDronesButton = Theme.secondaryButton("Choose drones");
    private ObservationGrid observationGrid;
    private volatile ObservationSession observation;
    private ObservationAudio observationAudio;
    /** The grid's feeds, in its order (the session holds the same). */
    private final List<TileFeed> tileFeeds = new ArrayList<>();
    /** The observed phones, for the name service's thread (a sleeping one is woken at its check-in). */
    private volatile Set<String> observedNames = Set.of();
    private int hoveredTile = -1;
    private long observedMapAt;
    /** CONNECT from the grid: the pilot connection's DISCONNECT offers the grid again. */
    private boolean backToGrid;
    /** Wanted to fly, but another PC flies: told once per connection. */
    private boolean toldNotFlying;
    private final JButton setupButton = Theme.secondaryButton("Setup");
    /** Shown when GitHub has a newer release; downloads and installs it (or opens its page, see startUpdate). */
    private final JButton updateButton = Theme.primaryButton("Update");
    private volatile UpdateCheck.Release update;
    private boolean updating;
    private final JLabel statePill = new JLabel("OFFLINE", SwingConstants.CENTER);
    private final JLabel fcValue = statValue();
    /** The connected phone's name (or the watched PC's), big on the LINK card while connected (the pilot, 1.0.51). */
    private final JLabel connectedNameLabel = Theme.label("", Theme.headingFont(20f), Theme.TEXT);
    private String connectedName;
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
    private final ScheduledExecutorService phoneWatch =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "phoneWatch");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private final JTextArea log = new JTextArea();

    private final JComboBox<Joystick.Info> joystickCombo = new JComboBox<>();
    private final JButton calibrateButton = Theme.secondaryButton("Calibrate");
    // the controller's status lines, beside its name in the card's top row (1.0.39, the pilot): folded, the card is that one row
    // the controller card's status lines: selectable and copyable (1.0.51, the pilot)
    private final CopyableText calibrationLabel = new CopyableText(" ", Theme.font(Font.BOLD, 11f), Theme.MUTED, Theme.PANEL);
    private final CopyableText rcStateLabel = new CopyableText(" ", Theme.headingFont(11f), Theme.MUTED, Theme.PANEL);
    private final CopyableText fcStateLabel = new CopyableText(" ", Theme.headingFont(11f), Theme.MUTED, Theme.PANEL);
    private final GimbalView leftGimbal = new GimbalView("Throttle / Yaw", "YAW", "THR");
    private final GimbalView rightGimbal = new GimbalView("Pitch / Roll", "ROLL", "PIT");
    private final ChannelBars channelBars = new ChannelBars();
    /** The sticks and channel bars; hidden when the controller panel is minimized, so the video gets the room. */
    private JComponent gimbalPanel;
    private final JButton minimizeButton = textButton("MINIMIZE", "Hide sticks and channels");
    private static final String MINIMIZED_KEY = "controllerMinimized";
    static final String UPSIDE_DOWN_KEY = CameraTurn.KEY;

    public static void open(PcConfig config, Path configFile, JoystickService service) {
        Theme.install();
        boolean firstStart = !Files.exists(configFile); // nothing has saved settings yet
        MainWindow window = new MainWindow(config, configFile, service);
        // all in the glass pane's own coordinates, where the animation paints: FlatLaf's glass pane starts under the
        // title bar (30 px below the layered pane), and measured in the layered pane every piece landed that much too low
        BootReveal.onOpen(window, () -> window.videoView.getParent() == null ? null
                : SwingUtilities.convertRectangle(window.videoView.getParent(), window.videoView.getBounds(), window.getRootPane().getGlassPane()),
                () -> window.boundsInPane(window.linkCard, window.logCard),       // the D's bars grow into the LINK and LOG cards
                // its diagonals: one into the controller card, the other into the MAP card (1.0.52), or both into the
                // controller card while the map is popped out
                () -> window.mapCard != null && window.mapCard.isVisible() && window.boundsInPane(window.mapCard) != null
                        ? window.boundsInPane(window.controllerCard, window.mapCard) : window.boundsInPane(window.controllerCard));
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
                Timer later = new Timer(500, e -> {
                    if (BootReveal.isShowingOn(window)) return; // not over the animation: again in half a second
                    ((Timer) e.getSource()).stop();
                    if (window.isShowing()) Tour.offer(window);
                });
                later.setInitialDelay(1000);
                later.start();
            } else {
                window.save(Map.of(Tour.OFFERED_KEY, "true"));
            }
        } else {
            // the tour was had before an update brought new parts: a tour of just those, offered once, after the boot animation
            List<String> fresh = Tour.unseen(config);
            if (!fresh.isEmpty() && "true".equalsIgnoreCase(config.getString(SetupWizard.DONE_KEY))) {
                Timer later = new Timer(500, e -> {
                    if (BootReveal.isShowingOn(window)) return;
                    ((Timer) e.getSource()).stop();
                    if (window.isShowing()) Tour.offerNew(window, fresh);
                });
                later.setInitialDelay(1000);
                later.start();
            }
        }
    }

    private void startNetwork() {
        networkTimer.start();
        phoneWatch.scheduleWithFixedDelay(this::checkPhone, 1, 5, TimeUnit.SECONDS);
        nameService.onChange(() -> SwingUtilities.invokeLater(this::updatePhoneList)); // the PHONE list follows the network
        nameService.start();
        // at once on opening, then every 15 minutes; its own thread, so a slow GitHub never holds up the phone check
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "updateCheck");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }).scheduleWithFixedDelay(this::checkForUpdate, 0, 15 * 60, TimeUnit.SECONDS); // a release made while the app runs shows within a quarter of an hour
    }

    private MainWindow(PcConfig config, Path configFile, JoystickService service) {
        super("DRAUPNE Link " + AppInfo.version());
        setIconImages(AppIcon.images());
        this.config = config;
        cameraTurn = new CameraTurn(config, this::save);
        cameraTurn.addListener((phone, on) -> {
            // turned elsewhere (a tile, an overwatch card): the main picture too when it is that phone's
            boolean watching = connection != null ? connection.getLink().isSpectator() : ROLE_SPECTATOR.equals(roleCombo.getSelectedItem());
            if (!watching && phone.equalsIgnoreCase(config.getString(Names.PHONE_NAME_KEY)) && videoView.isUpsideDown() != on) {
                videoView.setUpsideDown(on);
            }
        });
        tourHost = new TourHost(config, new TourHooks());
        detection = new DetectionController(config, cameraView, this::save, this::logLine);
        this.configFile = configFile;
        this.service = service;
        cameraView.configure(config);
        cameraOnMap = !"false".equalsIgnoreCase(config.getString(CameraView.SHOW_KEY));
        names = new Names(config, this::save);
        cameraTurn.setVirtualTest(n -> {
            Names.Entry e = names.get(n);
            return e != null && e.isVirtual();
        });
        nameService =new NameService(config, names, Tailscale::status, config.getPort(), true, this::save, this::logLine);
        nameService.setSleepHandler(this::onSleepCheck);
        FleetManager fleet = new FleetManager(config.getKey(), config.getPort(), config.getString(FLEET_QUEUE_KEY), this::save, this::logLine);
        fleets = new FleetController(fleet, config, names, nameService, overwatch, power, standbyPhones, crashed, new FleetHost());
        fleet.setOnTaken(fleets::taken);
        nameService.setFleet(fleet, fleets::hello);
        nameService.setOverwatchSink(overwatch::offer); // the link port while no link holds it (1.0.47)
        startOverwatch();
        power.load(config.getString(ASLEEP_KEY));
        crashed.putAll(parseCrashed(config.getString(CRASHED_KEY)));
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
        logLine("DRAUPNE Link " + AppInfo.version() + ". Phone app: DRAUPNE Flight " + ApkShare.VERSION
                + " (protocol " + SettingsCommon.versionCompatibleCode + "). Settings: " + shortPath(configFile));

        videoView.setOsdSource(this::osdData);
        videoView.setOsdLayout(OsdLayout.load(config::getString));
        alertSound.setEnabled(!"false".equalsIgnoreCase(config.getString(AlertSound.KEY)));
        recordVideo = "true".equalsIgnoreCase(config.getString(RECORD_KEY));
        hardwareDecode = !"false".equalsIgnoreCase(config.getString(HW_DECODE_KEY)); // on unless turned off (1.0.50)
        advanced = "true".equalsIgnoreCase(config.getString(ADVANCED_KEY));
        videoView.setOsdEnabled(!"false".equalsIgnoreCase(config.getString(OSD_KEY)));
        videoView.setUpsideDown(upsideDownFor(config.getString(Names.PHONE_NAME_KEY))); // the chosen phone's (1.0.41)
        setAudioMuted(mutedFor(config.getString(Names.PHONE_NAME_KEY)), false); // the chosen phone's (1.0.45)
        mapSource = TileSource.parse(config.getString(MAP_SOURCE_KEY));
        coordinateFormat = Coordinates.Format.parse(config.getString(COORDINATES_KEY));
        preferPhone = "phone".equalsIgnoreCase(config.getString(GPS_SOURCE_KEY));
        try {
            pins = new Pins(Pins.defaultFile());
        } catch (IOException e) {
            pins = new Pins(null);
        }
        try {
            graphics = new MapGraphics(MapGraphics.defaultFile());
        } catch (IOException e) {
            graphics = new MapGraphics(null);
        }
        if (pins.brokenMovedTo() != null) logLine("pins.txt had bad lines: moved to " + pins.brokenMovedTo().getFileName() + ".");
        if (graphics.brokenMovedTo() != null) logLine("graphics.kml unreadable: moved to " + graphics.brokenMovedTo().getFileName() + "; map starts without graphics.");
        miniMap.setPins(pins);
        miniMap.setCoordinateFormat(() -> coordinateFormat);
        miniMap.setGraphics(graphics);
        miniMap.setSource(mapSource);
        miniMap.setOnSourceChosen(this::setMapSource); // the minimap's right-click choice is saved and shared too
        // bound on the video view, not the window, so the keys also work while it is in the full-screen window
        bindKey(KeyEvent.VK_F3, this::toggleMapWindow);
        bindKey(KeyEvent.VK_F2, () -> {
            videoView.setOsdEnabled(!videoView.isOsdEnabled());
            save(Map.of(OSD_KEY, Boolean.toString(videoView.isOsdEnabled())));
        });
        bindKey(KeyEvent.VK_F11, this::toggleFullScreen);
        // M: the live sound on / off (1.0.45) - a plain letter, so not while a text field takes the typing
        bindKey(KeyEvent.VK_M, () -> {
            if (!(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent)) toggleMute();
        });
        bindKey(KeyEvent.VK_ESCAPE, () -> {
            if (latencyMeter != null) setLatencyTest(false);
            else exitFullScreen();
        });
        new Timer(33, e -> refresh()).start();
        startNetwork();
        // up to 1.0.51 at least 1100x760: more than a 1080p screen at 150 % has (1.0.52, the pilot's cut-off window)
        ScreenFit.least(this, 960, 560);
        // where it was last time (screen, size, maximized); the first time maximized, the size below is what un-maximizing gives
        if (!mainBounds.restore(config.getString(MAIN_BOUNDS_KEY))) {
            ScreenFit.apply(this, 960, 560, 1280, 820);
            setLocationRelativeTo(null);
            setExtendedState(JFrame.MAXIMIZED_BOTH);
        } else if ((getExtendedState() & JFrame.MAXIMIZED_BOTH) == 0) {
            ScreenFit.clampToScreen(this); // kept from a bigger screen or a higher scaling
        }
        saveMainBounds.setRepeats(false);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                saveMainBounds.restart();
            }

            @Override
            public void componentResized(ComponentEvent e) {
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
        addMenuItem(menu, "First-time setup", "Everything, step by step", () -> SetupWizard.open(this));
        addMenuItem(menu, "Set up a new drone", "Another drone and phone", () -> DroneSetup.openNewDrone(this));
        if (advanced) { // TOOLS > Advanced; phone and drone setup are in "Set up a new drone" (1.0.52)
            addMenuItem(menu, "PC setup", "Tailscale, firewall, radio, Windows", () -> PcSetupWizard.open(this));
        }
        menu.addSeparator();
        addMenuItem(menu, "Tour", "Buttons and map, one by one", () -> Tour.start(this));
        if (advanced) { // hidden under TOOLS > Advanced (1.0.52, the pilot)
            addMenuItem(menu, "Overwatch demo", "The tour's fleet part: alert, connect, hit the BTR", () -> Tour.demo(this));
        }
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
                    save(Map.of(ControlLink.VIDEO_MODE_KEY, m.name().toLowerCase(Locale.ROOT)));
                    Connection c = connection;
                    if (c != null) c.getLink().setVideoMode(m);
                    String when = c == null ? " Next connection." : "";
                    logLine(switch (m) {
                        case AUTO -> "Video: AUTO - SHARP after 20 s calm at top bitrate; ROBUST, then SURVIVAL, when the link is weak.";
                        case SHARP -> "Video: always SHARP - " + sharp + ".";
                        case NORMAL -> "Video: always NORMAL - " + normal + ".";
                        case ROBUST -> "Video: always ROBUST - 640x360, up to 1 Mbit/s, key frames twice.";
                        case SURVIVAL -> "Video: always SURVIVAL - 480x270, 15 fps, up to 250 kbit/s.";
                    } + when);
                });
        videoRow.setToolTipText("<html>AUTO picks by the link.<br>SHARP " + sharp + "<br>NORMAL " + normal
                + "<br>ROBUST 640x360, up to 1 Mbit/s, key frames twice<br>SURVIVAL 480x270, 15 fps, up to 250 kbit/s<br>Takes effect at once.</html>");
        menu.add(videoRow);
        menu.add(new MenuRows.SwitchRow("Camera upside down", "Turns the picture 180&deg;",
                videoView.isUpsideDown(), this::setUpsideDown));
        MenuRows.ChoiceRow motionRow = new MenuRows.ChoiceRow("Motion detection", "Disarmed: what moves, boxed",
                DetectionController.DETECTION_LEVELS, detection.levelFor(config.getString(Names.PHONE_NAME_KEY)), level -> detection.set(level, connection));
        motionRow.setToolTipText("<html>Disarmed: the phone boxes what moves in the picture"
                + "<br>(people, cars, animals, birds - not swaying trees; lights at night).<br>"
                + "Direction on the map. Per drone; needs phone app 2.3.0-pc.35.</html>");
        if (advanced) menu.add(motionRow); // TOOLS > Advanced
        if (advanced) menu.add(new MenuRows.SwitchRow("Camera view on the map", "What the camera sees, on the maps",
                cameraOnMap, on -> {
                    cameraOnMap = on;
                    save(Map.of(CameraView.SHOW_KEY, Boolean.toString(on)));
                    refreshMap();
                }));
        menu.add(new MenuRows.SwitchRow("Record video", "Every flight, to Videos",
                recordVideo, on -> {
                    recordVideo = on;
                    save(Map.of(RECORD_KEY, Boolean.toString(on)));
                    VideoPipeline v = video;
                    if (v == null) {
                        logLine(on ? "Recording from the next connection." : "Recording off.");
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
                    logLine(on ? "Hardware decoding from the next connection (software if the GPU can't)."
                            : "Software decoding from the next connection.");
                }));
        menu.addSeparator();
        menu.add(new MenuRows.SwitchRow("OSD", "The HUD over the video (F2)", videoView.isOsdEnabled(), on -> {
            videoView.setOsdEnabled(on);
            save(Map.of(OSD_KEY, Boolean.toString(on)));
        }));
        menu.add(new MenuRows.WindowRow("OSD layout", "Choose and place its elements",
                () -> OsdSettingsDialog.open(this, this)));
        return menu; // POP OUT VIDEO and FULL SCREEN are buttons on the video card, always in sight
    }

    /** LINK: how the connection behaves. */
    private JPopupMenu linkMenu() {
        JPopupMenu menu = styledMenu();
        menu.add(new MenuRows.SwitchRow("Warning sounds", "A beep when the sticks stop reaching it",
                alertSound.isEnabled(), on -> {
                    alertSound.setEnabled(on);
                    save(Map.of(AlertSound.KEY, Boolean.toString(on)));
                }));
        menu.add(new MenuRows.SwitchRow("Allow spectators", "Other PCs watch through this PC",
                Connection.spectatorsAllowed(config), on -> {
                    save(Map.of(Connection.ALLOW_SPECTATORS_KEY, Boolean.toString(on)));
                    Connection c = connection;
                    if (c != null) c.getLink().setSpectatorsAllowed(on);
                    logLine(on ? "Spectators allowed: other PCs can watch through this one."
                            : "Spectators off.");
                }));
        menu.add(new MenuRows.WindowRow("Name map", "Every phone and PC, the port and key",
                this::openNameMap));
        if (!advanced) return menu; // STANAG 4609: TOOLS > Advanced
        menu.addSeparator();
        menu.add(new MenuRows.SwitchRow("STANAG 4609 stream", "Video with metadata for ATAK, QGIS, VLC",
                stanagEnabled(), on -> {
                    save(Map.of(StanagDialog.ENABLED_KEY, Boolean.toString(on)));
                    if (!on) {
                        stopStanag();
                        logLine("STANAG 4609 stream off.");
                    } else if (video == null) {
                        logLine("STANAG 4609 stream from the next connection, to " + StanagDialog.target(config) + ".");
                    } else {
                        startStanag();
                    }
                }));
        menu.add(new MenuRows.WindowRow("STANAG 4609 settings", "Address, mission, camera angles",
                this::openStanagSettings));
        return menu;
    }

    /** TOOLS: where the position comes from, tests, and what this is (FIND DRONE is on the map, the motor test in Drone setup). */
    private JPopupMenu toolsMenu() {
        JPopupMenu menu = styledMenu();
        menu.add(new MenuRows.ChoiceRow("Position from", "The drone's GPS or the phone's",
                new String[]{"DRONE", "PHONE"}, preferPhone ? 1 : 0, i -> {
                    preferPhone = i == 1;
                    save(Map.of(GPS_SOURCE_KEY, preferPhone ? "phone" : "drone"));
                    if (mapWindow != null) mapWindow.setPreferPhone(preferPhone);
                }));
        menu.addSeparator();
        menu.add(new MenuRows.WindowRow("RC check", "What Betaflight reads, why it won't arm", () -> RcCheckDialog.open(this)));
        menu.add(new MenuRows.SwitchRow("Latency test", "Measures the real video delay",
                latencyMeter != null, this::setLatencyTest));
        if (advanced) menu.add(new MenuRows.WindowRow("Virtual fleet", "Virtual drones, no hardware needed", this::openVirtualFleet));
        if (advanced) menu.add(new MenuRows.WindowRow("Reset setup", "Settings back to a fresh install", () -> ResetSetup.ask(this)));
        menu.addSeparator();
        menu.add(new MenuRows.SwitchRow("Advanced", "Shows the settings few pilots need",
                advanced, on -> {
                    advanced = on;
                    save(Map.of(ADVANCED_KEY, Boolean.toString(on)));
                    logLine(on ? "Advanced settings shown."
                            : "Advanced settings hidden; their values stay.");
                }));
        menu.addSeparator();
        JLabel about = Theme.label("PC " + AppInfo.version() + "   /   PHONE APP " + ApkShare.VERSION,
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
        if (!VirtualFleet.announced().isEmpty()) {
            logLine("Virtual fleet already runs: its drones are in the PHONE list as VIRTUAL.");
            return;
        }
        List<String> command = appCommand();
        command.add("fleet");
        try {
            new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            logLine("Virtual fleet starting: its drones appear in the PHONE list as VIRTUAL.");
        } catch (IOException e) {
            logLine("Virtual fleet couldn't start: " + e.getMessage());
        }
    }

    /** This app again: the installed exe, or from source this same Java with this classpath; its arguments go after. */
    private static List<String> appCommand() {
        List<String> command = new ArrayList<>();
        String app = System.getProperty("jpackage.app-path");
        if (app != null) {
            command.add(app);
        } else {
            String exe = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "java.exe" : "java";
            command.add(Path.of(System.getProperty("java.home"), "bin", exe).toString());
            command.add("-Dfile.encoding=UTF-8");
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(Main.class.getName());
        }
        return command;
    }

    /**
     * TOOLS > Advanced > Reset setup (1.0.52): this PC's settings file goes (the name map, the calibration, the tour's
     * and the setup's state, queued fleet modes - all in it); flights, videos, clips, pins, drawings and saved maps
     * stay. The app closes and starts again as a fresh install would: the setup guide, then the tour. The new one
     * waits for this one to have gone (its ports, its settings writes).
     */
    void resetSetupAndRestart() {
        shutdown(() -> {
            try {
                Files.deleteIfExists(configFile);
                List<String> command = appCommand();
                command.addAll(List.of("gui", "--after", Long.toString(ProcessHandle.current().pid())));
                new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                AppLog.drain(500);
            } catch (IOException e) {
                System.err.println("Reset setup: " + e.getMessage());
            }
        });
    }

    private JMenuItem addMenuItem(JPopupMenu menu, String title, String detail, Runnable open) {
        JMenuItem item = new JMenuItem(MenuRows.html(title, detail));
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
    private static void dropdown(JButton button, Supplier<JPopupMenu> menus) {
        long[] menuClosedAt = {0};
        button.addActionListener(e -> {
            if (System.currentTimeMillis() - menuClosedAt[0] < 250) return;
            JPopupMenu menu = menus.get();
            menu.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent ev) {
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent ev) {
                    menuClosedAt[0] = System.currentTimeMillis();
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent ev) {
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
        setupButton.setToolTipText("Setup: first-time, PC, phone, drone");
        dropdown(setupButton, this::setupMenu);
        videoButton.setToolTipText("Video mode, upside down, recording, OSD");
        dropdown(videoButton, this::videoMenu);
        linkButton.setToolTipText("Warning sounds, spectators, name map");
        dropdown(linkButton, this::linkMenu);
        toolsButton.setToolTipText("GPS source, tests, versions");
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
     * The link column (1.0.50, the pilot: "it does not show the minimap, but instead has a bunch of empty space"): the
     * LINK card, the MAP card (minimap and POP OUT MAP, a card of its own since 1.0.52) every pixel left over, the log
     * under them (LinkCardLayout.Column); popped out, the MAP card goes and the log takes the room. (Up to 1.0.49 the minimap's height
     * was worked out from the card's preferred size, which a wrapped warning overstated: the minimap went, and the card
     * stood centred in the space it had kept.)
     */
    private static final int MINI_PREFERRED = 90;

    private JPanel buildLinkColumn() {
        JPanel column = new JPanel();
        column.setOpaque(false);

        JPanel link = Theme.card(null);
        linkCard = link;
        // the card's rows scroll when the column is too short for them (a small screen, a warning, the sleep row); the
        // minimap takes what is left over and POP OUT MAP always sits at the bottom (1.0.52, the pilot: "the pop out map
        // button is gone when not connected", the card's top cut off)
        JPanel rows = new LinkCardLayout.Rows(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 6, 0);
        rows.add(linkTitle, c); // PILOT MODE, OBSERVER MODE or SPECTATOR MODE (1.0.56, the pilot)
        connectedNameLabel.setToolTipText("The phone this PC is connected to");
        rows.add(folded(connectedNameLabel, 0), c);
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        fields.add(phoneLabel, c);
        TacticalCombo.install(phoneCombo);
        // a fixed width: the list's items change as the name service hears answers (the first about a second after the
        // start, during the boot animation), and a width that followed them moved the LINK card - and with it the
        // animation's targets - up and down
        phoneCombo.setPrototypeDisplayValue("MobileWhiskey   seen 59 min ago");
        phoneCombo.setToolTipText("The phone to fly: every phone with DRAUPNE Flight open on Tailscale");
        phoneCombo.addActionListener(e -> {
            if (!fillingPhones) choosePhone();
        });
        fields.add(phoneCombo, c);
        // OBSERVATION: the drones are picked in a list of their own (up to six), in place of the one phone
        chooseDronesButton.setToolTipText("Up to six idle drones to observe");
        chooseDronesButton.addActionListener(e -> pickDrones());
        chooseDronesButton.setVisible(false);
        fields.add(chooseDronesButton, c);
        // 1.0.50 (the pilot): no hint line under the PHONE list - it said what the list says (phoneHint is kept, unshown)
        c.gridwidth = 2;
        c.gridy = GridBagConstraints.RELATIVE;
        c.insets = new Insets(10, 0, 6, 0); // the gap the hint line left
        fields.add(Theme.label("CONNECT AS", Theme.headingFont(11f), Theme.MUTED), c);
        c.insets = new Insets(0, 0, 6, 0);
        roleCombo.setSelectedItem(savedRole());
        roleCombo.setToolTipText("<html>Pilot flies. Spectator watches another PC's flight (Allow spectators there).<br>"
                + "Observation watches up to six idle drones; CONNECT on one flies it.</html>");
        TacticalCombo.install(roleCombo);
        roleCombo.addActionListener(e -> {
            boolean watch = ROLE_SPECTATOR.equals(roleCombo.getSelectedItem());
            boolean observe = observerRole();
            save(Map.of(Connection.CONNECT_AS_KEY, observe ? CONNECT_AS_OBSERVATION : watch ? "spectator" : "pilot"));
            refreshConnectText();
            refreshLinkTitle();
            updatePhoneList(); // phones to fly, PCs to watch, or the drones to observe
        });
        refreshLinkTitle();
        fields.add(roleCombo, c);
        c.insets = new Insets(0, 0, 0, 0);
        rows.add(fields, c);
        linkFields = fields;
        c.insets = new Insets(4, 0, 14, 0);
        connectButton.addActionListener(e -> {
            if (observation != null) stopObservation();
            else if (connection == null && observerRole()) startObservation();
            else if (connection == null && alertingChosen() != null) overwatchConnect(alertingChosen()); // as the alert's CONNECT (1.0.56)
            else if (connection == null && asleepChosen() != null) wakeAsleep(); // back at its next check
            else if (connection == null) connect();
            else if (confirmEndWhileArmed("Standby mode")) disconnectToStandby();
            refreshConnectText();
        });
        connectButton.setFocusable(false); // SPACE on a focused DISCONNECT must never end a flight
        rows.add(connectButton, c);
        c.insets = new Insets(0, 0, 0, 0); // the gaps go in the folds: a folded one leaves none (up to 1.0.51 each kept 14 px)
        rows.add(folded(powerPanel(), 0), c);
        // the link dropped while the drone was armed: one click opens FIND DRONE and copies the MGRS to send on
        lostDroneButton.setBackground(Theme.BAD);
        lostDroneButton.setToolTipText("Link lost while armed: last position in FIND DRONE, MGRS copied");
        lostDroneButton.addActionListener(e -> lostDrone());
        rows.add(folded(lostDroneButton, 14), c);
        // 1.0.50: a drone lost armed holds this PC's downloads, previews and clips for 10 min - the pilot who knows it
        // is down ends that here (never while a link here says armed)
        itsDownButton.setToolTipText("Drone lost armed: downloads wait 10 min. Press once it is down");
        itsDownButton.addActionListener(e -> itsDownResume(this));
        rows.add(folded(itsDownButton, 14), c);
        rows.add(folded(itsDownLine, 6), c);

        // what matters in flight stays; the rest folds away with the fields
        // 1.0.51 (the pilot): no ping, telemetry packets or RC frames here - the link log keeps them
        JPanel stats = new JPanel(new GridLayout(2, 2, 8, 6));
        stats.setOpaque(false);
        addStat(stats, "PHONE BATTERY", phoneBatteryValue);
        addStat(stats, "FLIGHT CONTROLLER", fcValue);
        c.insets = new Insets(14, 0, 0, 0);
        rows.add(stats, c);
        JPanel details = new JPanel(new GridLayout(2, 2, 8, 6));
        details.setOpaque(false);
        details.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        addStat(details, "PHONE ON TAILSCALE", phoneValue);
        addStat(details, "PHONE APP", phoneAppValue);
        c.insets = new Insets(0, 0, 0, 0);
        rows.add(details, c);
        // the minimap and the way to the map window, always: connected or not, nothing folds away for it
        miniMap.setPreferredSize(new Dimension(300, MINI_PREFERRED)); // and every pixel the card has over
        miniMap.setMinimumSize(new Dimension(100, 40));
        miniMap.setToolTipText("Wheel zooms; right-click adds a pin");
        JButton popMap = Theme.primaryButton("Pop out map");
        popMapButton = popMap;
        popMap.setToolTipText("The map in its own window (F3)");
        popMap.addActionListener(e -> toggleMapWindow());
        c.insets = new Insets(0, 0, 0, 0); // the gaps go in the folds, so a folded one leaves none
        rows.add(folded(phoneWarning, 8), c);
        rows.add(folded(phoneAppWarning, 8), c);
        updatePhoneButton.setToolTipText("The phone installs the new app itself. Disarmed only; the link drops briefly");
        updatePhoneButton.setFocusable(false);
        updatePhoneButton.addActionListener(e -> askRemoteUpdate());
        rows.add(folded(updatePhoneButton, 6), c);
        JScrollPane rowsScroll = new JScrollPane(rows, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
            @Override
            public boolean isValidateRoot() {
                return false; // a fold opening in the rows lays the whole card out again, not just the scroll pane
            }
        };
        rowsScroll.setBorder(BorderFactory.createEmptyBorder());
        rowsScroll.setOpaque(false);
        rowsScroll.getViewport().setOpaque(false);
        rowsScroll.getVerticalScrollBar().setUnitIncrement(16);
        link.setLayout(new BorderLayout());
        link.add(rowsScroll, BorderLayout.CENTER);
        column.add(link);
        // the minimap in a card of its own (1.0.52, the pilot): POP OUT MAP takes the whole card away
        JPanel map = Theme.card(new LinkCardLayout(miniMap, popMap));
        map.add(miniMap);
        map.add(popMap);
        mapCard = map;
        column.add(map);

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
        column.add(logCard);
        column.setLayout(new LinkCardLayout.Column(link, map, logCard)); // its width and the log's height follow the window
        this.logCard = logCard; // the boot animation grows a piece of the mark into it
        return column;
    }

    /**
     * 1.0.50 (the pilot: "animation between all button and box transitions"): the LINK card's parts that come and go
     * slide open and shut (Fold) instead of jumping. folded() wraps one, its gap above inside the fold; show() and
     * shown() stand in for setVisible() and isVisible().
     */
    private final Map<JComponent, Fold> folds = new HashMap<>();

    private Fold folded(JComponent c, int gapAbove) {
        JComponent content = c;
        if (gapAbove > 0) {
            JPanel gap = new JPanel(new BorderLayout());
            gap.setOpaque(false);
            gap.setBorder(BorderFactory.createEmptyBorder(gapAbove, 0, 0, 0));
            gap.add(c, BorderLayout.CENTER);
            content = gap;
        }
        Fold f = new Fold(content, false);
        folds.put(c, f);
        return f;
    }

    private void show(JComponent c, boolean on) {
        Fold f = folds.get(c);
        if (f == null) c.setVisible(on);
        else f.setShown(on, true);
    }

    private boolean shown(JComponent c) {
        Fold f = folds.get(c);
        return f == null ? c.isVisible() : f.isShown();
    }

    /** A small "COPY" next to the log's title: the whole log to the clipboard, e.g. to send it to someone. */
    private JComponent copyLogButton() {
        JButton copy = textButton("COPY", "Copy the log");
        Timer reset = new Timer(1500, e -> copy.setText("COPY"));
        reset.setRepeats(false);
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(log.getText()), null);
            copy.setText("COPIED");
            reset.restart();
        });
        return copy;
    }

    static final String BEEPER_TIP = "BEEPER: hold to make the drone beep, to find it. Disarmed only, 10 s max";

    /** The log line for a BEEPER press: it beeps, or why not. */
    static String beeperPressedText(RcSession s) {
        if (s.beeperOff() != null) return s.beeperOff() + ".";
        if (!s.isRcEnabled()) return "BEEPER: no sticks sent now - can't beep.";
        return "BEEPER held (AUX" + ModeSwitch.BEEPER_AUX
                + "): beeps while DISARMED, " + RcSession.BEEPER_MAX_MS / 1000 + " s max.";
    }

    /** BEEPER let go (the window lost focus): the button's press and the session's both. */
    private void releaseBeeper() {
        RcSession s = session;
        boolean held = s != null && s.isBeeperHeld();
        ButtonModel m = beeperButton.getModel();
        if (m.isPressed() || m.isArmed()) {
            m.setArmed(false);
            m.setPressed(false);
        }
        if (s != null) s.setBeeper(false);
        if (held) logLine("BEEPER released: window lost focus.");
    }

    private static JLabel statValue() {
        JLabel label = Theme.label("-", Theme.font(Font.BOLD, 13f), Theme.TEXT);
        label.setHorizontalAlignment(SwingConstants.RIGHT);
        return label;
    }

    private void bindKey(int key, Runnable action) {
        videoView.registerKeyboardAction(e -> action.run(), KeyStroke.getKeyStroke(key, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
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
        popOutButton.setToolTipText("Pop out video into its own window; again to put it back");
        fullScreenButton.setToolTipText("Full screen (F11); Esc or double-click ends it");
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
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
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
        videoView.setToolTipText("Double-click: video only    F11: full screen    F2: OSD    M: sound");
        videoView.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
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
        JPanel top = new JPanel();
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
                setText(value instanceof Joystick.Info info ? info.name() : "No controller");
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
        joystickSlot.setMinimumSize(new Dimension(140, 32)); // a narrow window: the name shortens before a button goes
        topLeft.add(joystickSlot);
        topLeft.add(Box.createHorizontalStrut(10));
        // locked while connected: it shows (stripes, padlock), and a click on it says why, under it (never over the video).
        // No controller: the same kind of notice says so
        ((TacticalButton) calibrateButton).setShowLocked(true);
        LockedNotice calibrateLocked = new LockedNotice(calibrateButton, video, "CALIBRATE IS LOCKED",
                "Connected: STANDBY MODE first. No sticks may reach the drone while calibrating.");
        LockedNotice noController = new LockedNotice(calibrateButton, video, "NO CONTROLLER",
                "Plug the radio in by USB, choose 'USB Joystick (HID)' on it, then CALIBRATE again.");
        calibrateButton.addActionListener(e -> {
            if (hasController()) {
                calibrate();
                return;
            }
            logLine("CALIBRATE: plug the radio in by USB, choose 'USB Joystick (HID)'.");
            calibrateLocked.dismiss();
            if (!noController.pop()) calibrate(); // no room for the notice: calibrate() says it in a message box
        });
        calibrateButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (calibrateButton.isEnabled()) return;
                logLine("CALIBRATE locked while connected: disconnect first.");
                noController.dismiss();
                calibrateLocked.pop();
            }
        });
        topLeft.add(calibrateButton); // 1.0.56: RC check moved to TOOLS (the pilot)
        top.add(topLeft);
        JPanel states = new JPanel(new GridLayout(3, 1, 0, 0));
        states.setOpaque(false);
        states.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
        states.add(calibrationLabel);
        states.add(rcStateLabel);
        states.add(fcStateLabel);
        top.add(states);
        minimizeButton.addActionListener(e -> {
            setControllerMinimized(!controllerMinimized, true); // also mid-fold: turns it round
            save(Map.of(MINIMIZED_KEY, Boolean.toString(controllerMinimized)));
        });
        top.add(minimizeButton);
        top.setLayout(new WrapRowLayout(topLeft, states, minimizeButton, 10, 8)); // narrow: the status lines under the buttons
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
        minimizeButton.setToolTipText(minimized ? "Show sticks and channels" : "Hide sticks and channels");
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
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText(tooltip);
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setForeground(Theme.ACCENT);
            }

            @Override
            public void mouseExited(MouseEvent e) {
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
        List<Names.Entry> list = names.list(kind);
        if (kind == Names.Kind.PC) list.removeIf(e -> e.name().equalsIgnoreCase(nameService.ownName()));
        // a CRASHED drone isn't offered (1.0.52, the pilot); the one this PC is still linked to stays while it waits
        if (kind == Names.Kind.PHONE) list = offeredPhones(list, crashed.keySet(), connection != null ? config.getString(Names.PHONE_NAME_KEY) : null);
        long now = System.currentTimeMillis();
        int at = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).name().equalsIgnoreCase(chosen)) at = i;
        fillingPhones = true;
        try {
            phoneList = list;
            phoneCombo.removeAllItems();
            for (Names.Entry e : list) {
                Asleep sleeping = e.ip() == null || kind != Names.Kind.PHONE ? null : power.asleep(e.ip());
                phoneCombo.addItem(e.name() + (e.isVirtual() ? "  VIRTUAL" : "") + (kind == Names.Kind.PHONE && crashed.containsKey(e.name()) ? "   CRASHED"
                        : sleeping != null ? "   " + sleeping.mode() + " " + power.checkText(sleeping, now)
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
        Asleep chosenAsleep = e == null || e.ip() == null || kind != Names.Kind.PHONE ? null : power.asleep(e.ip());
        String hint = chosenAsleep != null
                ? e.name() + (chosenAsleep.overwatch() ? " overwatches" : " is in deep sleep") + (chosenAsleep.listening()
                        ? ", listening: PILOT UAV brings it back at once."
                        : ": it checks for this PC in " + PowerController.clock(power.nextCheck(chosenAsleep, now) - now) + ". PILOT UAV brings it back then.")
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
            save(Map.of(Names.PHONE_NAME_KEY, e.name(), IP_KEY, e.ip() == null ? "" : e.ip()));
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
        channelBars.setPurposes(ModeSwitch.purposes(config)); // AUX rows named after Drone setup's switches
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
        save(Map.of(JOYSTICK_KEY, info.name()));
    }

    /** @return true when a new calibration was saved */
    private boolean hasController() {
        JoystickService.Snapshot snap = service.getSnapshot();
        return snap.selected() != null && snap.state() != null;
    }

    boolean calibrate() {
        if (connection != null) return false;
        JoystickService.Snapshot snap = service.getSnapshot();
        Window owner = FocusManager.getCurrentManager().getActiveWindow();
        if (snap.selected() == null || snap.state() == null) {
            Dialogs.message(owner == null ? this : owner, "Plug in the radio and choose 'USB Joystick (HID)' on it.",
                    "No controller", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        // the switch steps are named after what Drone setup put on them (ARM, flight modes, GPS rescue)
        Map<String, String> result = CalibrationDialog.run(owner == null ? this : owner, service, snap.selected(), snap.state(),
                ModeSwitch.purposes(config));
        if (result == null) {
            logLine("Calibration cancelled.");
            return false;
        }
        if (save(result)) {
            save(Map.of(DroneSetup.RADIO_REDO_KEY, "false")); // the switches Drone setup wrote are now the right ones
            rebuildMapper();
            channelBars.setPurposes(ModeSwitch.purposes(config));
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
            Dialogs.message(this, "Controller mapping is not usable: " + mapperError + "\nRun Calibrate first.",
                    "Cannot connect", JOptionPane.WARNING_MESSAGE);
            return;
        }
        boolean watch = ROLE_SPECTATOR.equals(roleCombo.getSelectedItem());
        Names.Entry target = names.get(config.getString(Names.PHONE_NAME_KEY));
        if (target == null || target.kind() != (watch ? Names.Kind.PC : Names.Kind.PHONE) || target.ip() == null || target.ip().isEmpty()) {
            Dialogs.message(this, watch ? "Pick the pilot PC first (it needs DRAUPNE Link running)."
                    : "Pick the phone first (it needs DRAUPNE Flight open, on Tailscale).", "Cannot connect", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (refuseDuplicate(target)) return;
        if (!save(Map.of(IP_KEY, target.ip()))) return;
        int port = config.getPort();
        nameService.pause(); // the link takes the port, and answers name queries itself meanwhile
        boolean wantVideo = !"false".equalsIgnoreCase(config.getString(VIDEO_KEY));
        ControlLink[] linkRef = new ControlLink[1];
        video = wantVideo ? new VideoPipeline(videoView, () -> linkRef[0] == null ? -1 : linkRef[0].getPing()) : null;
        try {
            connection = Connection.open(config, video);
            linkRef[0] = connection.getLink();
            ControlLink flown = connection.getLink();
            // this PC's "possibly flying" flag (1.0.49): every background download waits while this drone may fly
            // kept per drone (1.0.50): a reconnect to it that hears DISARMED ends a lost-drone window at once
            PossiblyFlying.pc().attach(flown, target.name(), () ->PossiblyFlying.of(flown.getTelemetry().getStatus()));
            connection.getLink().setNameReply(nameService::replyPacket);
            connection.getLink().setEvents(this::logLine);
            connectedName = target.name() + (target.isVirtual() ? " (VIRTUAL)" : "");
            startLiveAudio(connection.getLink(), video, target.name());
            if (video != null) video.setHardwareDecode(hardwareDecode);
            if (video != null) video.attach(connection.getLink());
            if (video != null && recordVideo) startRecording(video);
            if (video != null && stanagEnabled()) startStanag();
            videoView.setStatsSource(video == null ? null : video::stats);
        } catch (IOException | RuntimeException e) {
            if (linkRef[0] != null) PossiblyFlying.pc().detach(linkRef[0]);
            connection = null;
            stopLiveAudio();
            nameService.resume();
            if (video != null) video.close();
            video = null;
            logLine("Connect failed: " + e.getMessage());
            Dialogs.message(this, e.getMessage(), "Cannot connect", JOptionPane.WARNING_MESSAGE);
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
        if (!watch) connection.getLink().setDetection(detection.levelFor(target.name())); // the drone's own (1.0.46)
        // STANDBY / DEEP SLEEP: no sticks, and a WAKE starts with the throttle-low rule (not a radio blip's quick resume)
        service.setInputListener(state -> {
            if (sticksPaused()) s.onPaused(state); else s.onInput(state);
        });
        if (!watch && WindowsTuning.keepAwake(true)) logLine("PC and screen stay awake while connected.");
        power.wanted = ControlLink.POWER_NORMAL;
        power.phoneAsleep = false;
        power.awaitingWake = false;
        lastState = null;
        lastStateText = null;
        lastSpectators = 0;
        toldNotFlying = false;
        connection.getLink().setForeignSleepHandler(this::onSleepCheck); // another phone's check-in: sleep on
        connection.getLink().setFleetHello(fleets::hello); // and a fleet mode queued for it goes ahead (1.0.52)
        connection.getLink().setOverwatchSink(overwatch::offer); // the link holds the link port now (1.0.47)
        power.phoneOverwatching = false;
        // the flight before stays on the map; this one is the live one
        keepFlight(track, liveTrackName);
        liveTrackName = flightName(watch ? null : target.name());
        watchedPhone = null;
        if (!watch) videoView.setUpsideDown(upsideDownFor(target.name())); // the phone's own way up (1.0.41)
        setVirtualShown(!watch && target.isVirtual());
        Asleep sleeping = target.ip() == null ? null : power.asleep(target.ip());
        ControlLink.SleepHello lastCheck = sleeping == null ? null : power.hello(sleeping.ip());
        if (sleeping != null && target.online() && (lastCheck == null || System.currentTimeMillis() - lastCheck.receivedAt() > PowerController.awakeAfterMs(sleeping))
                && System.currentTimeMillis() - sleeping.since() > PowerController.awakeAfterMs(sleeping)) {
            power.clear(sleeping.ip()); // it answers name queries and hasn't checked in for a minute: woken on the phone
            sleeping = null;
        }
        if (!watch && sleeping != null && sleeping.ip().equals(target.ip())) {
            // the phone sleeps: PILOT UAV wakes it - now when it listens all along, else at its next check (1.0.52: no
            // WAKE button, and no connection that keeps quiet)
            power.phoneAsleep = true;
            power.awaitingWake = true;
            power.phoneOverwatching = sleeping.overwatch();
            power.sleepStartAt = MonoClock.ms() - (System.currentTimeMillis() - sleeping.since());
            power.sentIntervalMin = sleeping.intervalMin();
            if (sleeping.listening()) {
                logLine(sleeping.name() + " is listening: wakes now.");
            } else {
                logLine(sleeping.name() + " wakes at its next check (within " + sleeping.intervalMin() + " min).");
            }
        }
        try {
            linkLog = LinkLog.open(AppPaths.dataDir().resolve("logs"));
            logLine("Link log: " + shortPath(linkLog.file()));
        } catch (IOException e) {
            linkLog = null;
            logLine("No link log: " + e.getMessage());
        }
        if (liveTrack != null) liveTrack.close();
        try {
            liveTrack = new FlightTrack(FlightTrack.defaultDir());
        } catch (IOException e) {
            liveTrack = new FlightTrack(null);
            logLine("No track file: " + e.getMessage());
        }
        track = liveTrack; // the flight before is a kept one now (1.0.41)
        pushKept();
        positionFromPhone = null;
        lastArmedAt = 0;
        linkLogTimer.start();
        updateFlightLayout();
        standbyPhones.remove(target.ip()); // a Connect wakes a standby phone
        tourHost.noteConnect(target.name());
        logLine((watch ? "Watching " : "Connecting to ") + target.name() + (target.isVirtual() ? " (VIRTUAL)" : "") + " (" + target.ip() + ":" + VirtualFleet.portOf(target.ip(), port) + ")" + (target.online() ? "" : " - last seen " + Names.agoText(target.lastSeen(), System.currentTimeMillis())));
        setLinkEditable(false);
    }

    /**
     * M3 (1.0.49): two devices answer with the target's name (two PCs that both acted as the names master can each
     * have named a phone MobileAlpha). CONNECT could reach the wrong drone, so it is refused, and the pilot is offered
     * to give the other one a free name (NAME_ASSIGN; a phone must be online to take it).
     * @return true when refused
     */
    private boolean refuseDuplicate(Names.Entry target) {
        List<String> ips = nameService.duplicateOf(target.name());
        if (ips == null) return false;
        List<String> others = new ArrayList<>(ips);
        others.remove(target.ip());
        String free = target.kind() == Names.Kind.PHONE ? names.firstFree(Names.Kind.PHONE, List.of()) : null;
        logLine("CONNECT refused: " + target.name() + " answers from " + String.join(" and ", ips) + ".");
        String text = "Two devices answer as " + target.name() + ": " + String.join(" and ", ips) + ".\n"
                + "CONNECT could reach the wrong drone: refused until one is renamed.";
        if (free == null || others.isEmpty()) {
            Dialogs.message(this, text + "\nRename one in the NAME MAP.", "Cannot connect", JOptionPane.WARNING_MESSAGE);
            return true;
        }
        String other = others.get(0);
        String[] options = {"Rename " + other + " to " + free, "Cancel"};
        int choice = Dialogs.option(this, text + "\n\n" + target.name() + " in the name map is " + target.ip()
                        + (target.detail().isEmpty() ? "" : " (" + target.detail() + ")") + ".\nRename the phone at " + other + " to " + free + "?",
                "Cannot connect", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (choice == 0) {
            nameService.assign(other, free);
            logLine("Phone at " + other + " asked to become " + free + "; CONNECT again once the list shows it.");
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
        if (power.phoneAsleep && connection != null && !connection.getLink().isSpectator()) {
            String name = config.getString(Names.PHONE_NAME_KEY), ip = config.getString(IP_KEY);
            if (!ip.isEmpty()) {
                long since = System.currentTimeMillis() - (MonoClock.ms() - power.sleepStartAt);
                power.remember(new Asleep(name, ip, since, power.sentIntervalMin, power.phoneOverwatching));
                logLine(name + (power.phoneOverwatching ? " overwatches on: alerts still come to the map." : " sleeps on.")
                        + " PILOT UAV brings it back.");
            }
        }
        service.setInputListener(null);
        remoteUpdateAskedAt = 0; // a remote update in flight: the phone goes on with it, this PC stops following it
        updatePhoneButton.setText("Update phone app");
        updatePhoneButton.setEnabled(true);
        WindowsTuning.keepAwake(false);
        power.wanted = ControlLink.POWER_NORMAL;
        power.phoneAsleep = false;
        power.awaitingWake = false;
        power.phoneOverwatching = false;
        if (powerRow != null) show(powerRow, false);
        sampleLinkLog();
        linkLogTimer.stop();
        if (liveTrack != null) liveTrack.close(pins.list()); // stays for FIND DRONE and the map
        tourHost.noteDisconnect();
        keepFlight(liveTrack, liveTrackName); // remembered; shown as the live one until the next connection
        show(lostDroneButton, false);
        if (connection != null) {
            PossiblyFlying.pc().detach(connection.getLink()); // armed at the end: the flag stays for the lost-drone window
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
        logLine("Disconnected. No more sticks: Betaflight fails safe.");
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
        FlightTrack tr = liveTrack;
        if (c != null && tr != null) {
            FcTelemetry t = c.getLink().getTelemetry();
            FcTelemetry.Status st = t.getStatus();
            tr.sample(t.getGps(), t.getPhonePosition(), st != null && st.armed(), preferPhone); // the file goes on its own thread
            FlightTrack.Point last = tr.last();
            if (last != null) {
                if (track != tr) track = tr; // the new connection's first position: its track is the one shown now
                if (positionFromPhone == null || positionFromPhone != last.phone()) {
                    positionFromPhone = last.phone();
                    logLine(last.phone() ? "Position: phone GPS" + (preferPhone ? "." : " (drone GPS has none).")
                            : "Position: drone GPS" + (preferPhone ? " (phone GPS has none)." : "."));
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
        long now = MonoClock.ms();
        FcTelemetry.Gps gps = null;
        int heading = -1;
        boolean fcLost = false, connected = false;
        if (c == null) {
            if (now - mapIdleUpdatedAt < 1000) return;
            mapIdleUpdatedAt = now;
        } else {
            ControlLink link = c.getLink();
            FcTelemetry t = link.getTelemetry();
            FcTelemetry.Attitude att = t.getAttitude();
            gps = t.getGps();
            heading = att != null && now - att.receivedAt() < 2000 ? att.headingDeg() : -1;
            fcLost = link.isFcLost();
            ControlLink.State state = link.getState();
            connected = state == ControlLink.State.READY || state == ControlLink.State.SPECTATING;
        }
        CameraFootprint.View view = null;
        VideoPipeline v = video;
        int[] size = v == null ? null : v.pictureSize();
        if (size != null) cameraView.setPictureSize(size[0], size[1]);
        // worked out five times a second off this thread (1.0.49: its terrain steps ran here every 33 ms)
        cameraLive.want(c != null && cameraOnMap && !fcLost ? c.getLink().getTelemetry() : null, track);
        view = cameraLive.latest();
        if (w != null) w.setCameraView(view);
        miniMap.setCameraView(view);
        List<DetectionMarks.Mark> marks = c == null ? detection.marks(now) : detection.marks(c.getLink().getTelemetry(), track,
                videoView.isMirror() != videoView.isUpsideDown(), videoView.isUpsideDown(), now);
        if (w != null) w.setDetections(marks);
        miniMap.setDetections(marks);
        // the chosen drone put to sleep and on time with its check-ins: its sleep sign, not LAST KNOWN (1.0.50) - and
        // the same when it was heard of after the track's last point (an overwatch alert, a sleep check, its phone's
        // position): LAST KNOWN is only for a drone nothing newer is known of (the pilot)
        String livePhone = config.getString(Names.PHONE_NAME_KEY);
        String liveIp = names.ipOf(livePhone);
        Asleep sleeping = liveIp == null ? null : power.asleep(liveIp);
        boolean liveAsleep = sleeping != null && power.checkInOverdue(sleeping, System.currentTimeMillis()) <= 0;
        if (isCrashed(livePhone)) liveAsleep = true; // CRASHED (1.0.52): its crash sign stands at the last point, never LAST KNOWN
        FlightTrack.Point lastFix = track == null ? null : track.last();
        if (!liveAsleep && lastFix != null && livePhone != null && !livePhone.isEmpty()) liveAsleep = heardSince(livePhone, liveIp, lastFix.timeMs());
        // the pilot put it to sleep a moment ago: it goes quiet on purpose before the connection ends (the pilot: "they
        // know where it is as they put it in overwatch") - no LAST KNOWN in between either
        if (!liveAsleep && connection != null && !connection.getLink().isSpectator() && (power.phoneAsleep
                || power.wanted == ControlLink.POWER_DEEP_SLEEP || power.wanted == ControlLink.POWER_OVERWATCH
                || power.wanted == ControlLink.POWER_STANDBY)) liveAsleep = true;
        if (w != null) w.map().setLiveAsleep(liveAsleep);
        miniMap.setLiveAsleep(liveAsleep);
        if (w != null) w.update(track, gps, heading, fcLost, connected);
        if (mini) miniMap.update(track, gps, heading, fcLost, connected);
    }

    // ------------------------------------------------------------------ motion detection (1.0.46)

    /** Each drone's motion detection level, and the detections' marks on the maps. */
    private final DetectionController detection;

    /**
     * Connected, with the map not popped out: the minimap follows the drone. (Up to 1.0.38 the connection fields and
     * the detail figures folded away for it; the minimap now has its own place on the LINK card.)
     */
    private void updateFlightLayout() {
        placeMiniMap();
        boolean flying = connection != null && !(mapWindow != null && mapWindow.isShown()); // minimized to the taskbar: not shown
        if (flying) {
            miniMap.setFollow(true);
            mapIdleUpdatedAt = 0;
        }
    }

    /**
     * The map in its own window: no MAP card, the LINK card as tall as its rows and the log takes the rest of the column.
     * Back (the map window closed): the MAP card again, taking the column's spare room, the log its usual height.
     */
    private void placeMiniMap() {
        if (linkColumn == null || mapCard == null || logCard == null) return;
        boolean popped = mapWindow != null && mapWindow.isShown();
        if (mapCard.isVisible() == !popped) return;
        mapCard.setVisible(!popped); // LinkCardLayout.Column: the LINK card as tall as its rows, the log the rest
        linkColumn.revalidate();
        linkColumn.repaint();
    }

    /** The map window if it was ever made, without making it. */
    MapWindow mapWindowIfOpen() {
        return mapWindow;
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
            mapWindow.bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), this::toggleMapWindow); // F3 there too
            // popped out or back: the minimap follows the drone again
            mapWindow.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentShown(ComponentEvent e) {
                    updateFlightLayout();
                }

                @Override
                public void componentHidden(ComponentEvent e) {
                    updateFlightLayout();
                }
            });
        }
        return mapWindow;
    }

    /**
     * 1.0.52 (the pilot): CONNECT from the map window (an overwatch alert, the fleet strip, a right-click) brings this,
     * the pilot's window, to the front - out of the taskbar too.
     */
    void toPilotWindow() {
        if ((getExtendedState() & JFrame.ICONIFIED) != 0) setExtendedState(getExtendedState() & ~JFrame.ICONIFIED);
        toFront();
        requestFocus();
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

    private void setMapSource(TileSource source) {
        mapSource = source;
        miniMap.setSource(source);
        save(Map.of(MAP_SOURCE_KEY, source == null ? TileSource.AUTO : source.id));
        if (mapWindow != null && mapWindow.source() != source) mapWindow.setSource(source);
    }

    private void setCoordinateFormat(Coordinates.Format format) {
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
        // 1.0.52 (the pilot): the drone is down and gone - CRASHED on the map, the fleet and the PHONE list until its
        // phone is heard again or the pilot clears it; FIND DRONE still has its last position
        // and the link to it ends at once (1.0.52, the pilot: standby): no more waiting for it, no reconnecting, no
        // link-loss alarms; the LINK card is idle. Its track stays for FIND DRONE and the map; if its phone ever answers
        // again it is heard as any phone is.
        String name = config.getString(Names.PHONE_NAME_KEY);
        if (connection != null) {
            logLine("LOST DRONE: link to " + name + " ended.");
            disconnect();
        }
        markCrashed(name, "lost");
        FindDroneDialog d = openFindDrone();
        String mgrs = d.mgrs();
        if (mgrs != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(mgrs), null);
            logLine("LOST DRONE: last position " + mgrs + " copied.");
        } else {
            logLine("LOST DRONE: no position yet.");
        }
    }

    /** "name@wallMs;..." to the map (a broken entry is left out). */
    static Map<String, Long> parseCrashed(String saved) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (saved == null || saved.isBlank()) return out;
        for (String one : saved.split(";")) {
            int at = one.lastIndexOf('@');
            if (at <= 0) continue;
            try {
                out.put(one.substring(0, at), Long.parseLong(one.substring(at + 1)));
            } catch (NumberFormatException ignored) {
                // left out
            }
        }
        return out;
    }

    private void saveCrashed() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : crashed.entrySet()) sb.append(sb.length() == 0 ? "" : ";").append(e.getKey()).append('@').append(e.getValue());
        save(Map.of(CRASHED_KEY, sb.toString()));
    }

    /**
     * The drone is down and gone (1.0.52, the pilot): LOST DRONE after a link loss, or right-click "Mark CRASHED" on a
     * drone not heard from for a while. CRASHED on the map and the fleet, out of the PHONE list, no LAST KNOWN, and it
     * no longer counts as possibly flying (PossiblyFlying.markDown) - until it is heard again or cleared.
     * @return whether it was newly marked
     */
    boolean markCrashed(String name, String why) {
        if (name == null || name.isBlank() || crashed.containsKey(name)) return false;
        crashed.put(name, System.currentTimeMillis());
        saveCrashed();
        boolean held = PossiblyFlying.pc().markDown(name);
        logLine(name + ": CRASHED - " + ("lost".equals(why) ? "LOST" : "marked by the pilot") + "." + (held ? " Not flying any more: downloads resume." : ""));
        updatePhoneList();
        return true;
    }

    /** The PHONE list without CRASHED drones (1.0.52), but the one still linked (its link waits after the crash). */
    static List<Names.Entry> offeredPhones(List<Names.Entry> all, Set<String> crashedNames, String linked) {
        List<Names.Entry> out = new ArrayList<>(all);
        out.removeIf(e -> crashedNames.contains(e.name()) && !e.name().equalsIgnoreCase(linked));
        return out;
    }

    /** The pilot's Clear CRASHED (map or fleet right-click). */
    void clearCrashedByPilot(String name) {
        clearCrashed(name, "cleared");
    }

    /** Heard again (a reply after the crash, or a link to it), or the pilot's Clear: no longer CRASHED. */
    private void clearCrashed(String name, String why) {
        if (name == null || crashed.remove(name) == null) return;
        saveCrashed();
        logLine(name + ": CRASHED cleared - " + why + ".");
        SwingUtilities.invokeLater(this::updatePhoneList); // offered in the PHONE list again
    }

    boolean isCrashed(String name) {
        return name != null && crashed.containsKey(name);
    }

    private long itsDownCheckedAt;

    /**
     * Once a second: while only a drone lost armed holds this PC back (PossiblyFlying, no link here saying armed), the
     * LINK card says why and offers IT'S DOWN - RESUME.
     */
    private void refreshItsDown() {
        long now = MonoClock.ms();
        if (now - itsDownCheckedAt < 1000) return;
        itsDownCheckedAt = now;
        PossiblyFlying flag = PossiblyFlying.pc();
        boolean show = flag.resumable();
        String why = show ? flag.reason() : null;
        if (why != null) itsDownLine.setText("<html><div style='width:220px'>Downloads wait: " + why + ".</div></html>");
        if (shown(itsDownButton) != show) {
            show(itsDownButton, show);
            show(itsDownLine, show);
            itsDownButton.getParent().revalidate();
        }
    }

    /**
     * IT'S DOWN - RESUME (the LINK card, an overwatch card): the pilot knows the drone lost armed is on the ground, so
     * this PC's lost-drone window ends - map tiles, overwatch previews and clips go on. Asks first; never touches a link
     * here that says armed, nor arming or the sticks.
     */
    void itsDownResume(Component parent) {
        PossiblyFlying flag = PossiblyFlying.pc();
        String why = flag.reason();
        if (!flag.resumable()) {
            if (why != null) Dialogs.message(parent, why + ".\nA link here says armed: waits until DISARMED.", "It's down - resume", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String[] options = {"It's down - resume", "Cancel"};
        int choice = Dialogs.option(parent, why + ".\n\nIs that drone on the ground (disarmed or found)?\n"
                        + "Downloads resume. Nothing is sent to any drone.",
                "It's down - resume", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        if (choice != 0) return;
        List<String> drones = flag.resume();
        logLine("IT'S DOWN - RESUME: " + (drones.isEmpty() ? "the lost drone" : String.join(", ", drones))
                + " down; downloads resume.");
        itsDownCheckedAt = 0;
        refreshItsDown();
    }

    /** The link dropped (or the FC went) within a few minutes of the drone being armed: LOST DRONE comes up. */
    private void refreshLostDrone(ControlLink link, ControlLink.State state) {
        long now = MonoClock.ms();
        FcTelemetry.Status st = link.getTelemetry().getStatus();
        if (st != null && st.armed() && now - st.receivedAt() < 2000) lastArmedAt = now;
        boolean dropped = !link.isSpectator() && (state != ControlLink.State.READY || link.isFcLost() || link.isLinkStalled());
        boolean show = dropped && lastArmedAt != 0 && now - lastArmedAt < LOST_DRONE_WINDOW_MS && track != null && !track.isEmpty();
        if (show && !shown(lostDroneButton)) logLine("Link lost while armed: LOST DRONE shows the last position.");
        if (shown(lostDroneButton) != show) {
            show(lostDroneButton, show);
            if (!show) lostDroneButton.setText("Lost drone");
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
        calibrateButton.setToolTipText(editable ? null : "Locked while connected: disconnect first");
        refreshConnectText();
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
        StanagStream s = new StanagStream(StanagDialog.target(config),
                ms -> cameraView.klv(ms, link.getTelemetry(), track, mission, country),
                msg -> SwingUtilities.invokeLater(() -> logLine(msg)));
        stanag = s;
        v.setFrameTap(s);
        logLine("STANAG 4609 stream on: " + s.url() + ".");
    }

    private void stopStanag() {
        StanagStream s = stanag;
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
            logLine("STANAG 4609: " + StanagDialog.target(config) + ", camera " + m.get(CameraView.HFOV_KEY)
                    + " deg wide, tilt " + m.get(CameraView.TILT_KEY) + " deg.");
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
    private final ExecutorService settingsWriter = Executors.newSingleThreadExecutor(r -> {
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
        shutdown(null);
    }

    /** @param beforeExit run once the settings are written and before the app exits (Reset setup), or null */
    private void shutdown(Runnable beforeExit) {
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
            settingsWriter.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        AppLog.drain(2000); // the last lines ("Installing ...") reach the file before the exit
        if (beforeExit != null) beforeExit.run();
        // the window goes first, then the main thread exits: exiting while this thread was still taking the window
        // down (its drop targets) deadlocked in Windows' toolkit shutdown - the process stayed, with no window
        dispose();
        service.stop(); // the main thread leaves its polling loop and exits the app
    }

    /**
     * The update helper's log from the last UPDATE: once per start, says in the log (and in a pop-up when it failed)
     * what happened after the app closed - the installer that ran, or why nothing was installed.
     */
    private void reportLastUpdate() {
        String result = SelfUpdate.takeHelperResult();
        if (result == null) return;
        logLine("Last update: " + result);
        if (!result.startsWith("OK")) {
            Dialogs.message(this, "<html>The last update did not install:<br>" + result
                    + "<br><br>Press UPDATE again, or get the installer from the release page.</html>",
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
        ObserveSet set = ObserveSet.parse(config.getString(ObserveSet.KEY));
        String hint = set.size() == 0 ? "No drone chosen yet: CHOOSE DRONES picks up to " + ObserveSet.MAX + " idle ones."
                : String.join(", ", set.phones()) + "  (" + set.size() + " of " + ObserveSet.MAX + ")";
        phoneHint.setText("<html>" + hint + "</html>");
    }

    /** Why each phone can't be observed now (idle phones only; app pc.37 on); absent: it can. */
    private Map<String, String> observeRefusals(List<Names.Entry> phones) {
        Map<String, Names.Position> where = new HashMap<>();
        long now = System.currentTimeMillis();
        for (NameService.DronePosition d : nameService.dronePositions()) {
            if (now - d.at() < 30_000) where.put(d.name().toLowerCase(Locale.ROOT), d.position()); // a fresh word only
        }
        Map<String, String> out = new HashMap<>();
        for (Names.Entry e : phones) {
            Names.Position p = where.get(e.name().toLowerCase(Locale.ROOT));
            String why = ObserveSet.refusal(p, e.version());
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
        ObserveSet current = ObserveSet.parse(config.getString(ObserveSet.KEY));
        List<Names.Entry> phones = names.list(Names.Kind.PHONE);
        Set<String> asleep = new HashSet<>();
        for (Names.Entry e : phones) if (e.ip() != null && power.isAsleep(e.ip())) asleep.add(e.name());
        List<String> chosen = ObservePicker.pick(this, phones, current, observeRefusals(phones), asleep);
        if (chosen == null) return;
        ObserveSet next = new ObserveSet();
        chosen.forEach(next::add);
        save(Map.of(ObserveSet.KEY, next.encode()));
        updatePhoneList();
        logLine(next.size() == 0 ? "Observation: no drone chosen." : "Observation: " + String.join(", ", next.phones()) + " chosen.");
    }

    private int volumeFor(String phone) {
        return ObservationSession.volume(config.getString(OBSERVE_VOLUME_KEY + "." + phone));
    }

    private boolean compassCalibrated(String phone) {
        return "true".equalsIgnoreCase(config.getString(HeadingChoice.calibratedKey(phone)));
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
        ObserveSet saved = ObserveSet.parse(config.getString(ObserveSet.KEY));
        if (saved.size() == 0) {
            pickDrones();
            saved = ObserveSet.parse(config.getString(ObserveSet.KEY));
            if (saved.size() == 0) return;
        }
        Map<String, String> refusals = observeRefusals(names.list(Names.Kind.PHONE));
        ObserveSet set = new ObserveSet();
        for (String p : saved.phones()) {
            Names.Entry e = names.get(p);
            String why = refusals.get(e == null ? p : e.name());
            if (why != null) logLine("Observation: " + p + " left out - " + why + ".");
            else if (e == null || e.ip() == null || e.ip().isEmpty()) logLine("Observation: " + p + " left out - not in the name map.");
            else set.add(e.name());
        }
        if (set.size() == 0) {
            Dialogs.message(this, "None of the chosen drones can be observed now (see the log).",
                    "Observation", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ObservationAudio audio = new ObservationAudio(AlertSound::isPlaying,
                msg -> SwingUtilities.invokeLater(() -> logLine(msg)));
        ObservationGrid grid = new ObservationGrid(gridListener(), cameraTurn, videoView::getOsdLayout, videoView::isOsdEnabled);
        grid.setPhones(set.phones());
        // the selected tile's picture: this PC's NORMAL (its camera size at 30 fps, its bitrate limit)
        ObserverLink.Quality sharp = new ObserverLink.Quality(config.getCameraWidth(),
                config.getCameraHeight(), 30, Math.max(1000, config.getBitrateLimitMbps() * 1000));
        List<TileFeed> feeds = new ArrayList<>();
        long now = MonoClock.ms();
        List<String> mutedPhones = new ArrayList<>();
        try {
            for (int i = 0; i < set.size(); i++) {
                String phone = set.phones().get(i);
                Names.Entry e = names.get(phone);
                TileFeed f = new TileFeed(phone, InetAddress.getByName(e.ip()),
                        VirtualFleet.portOf(e.ip(), config.getPort()), config.getKey(), grid.sink(i), audio, sharp, msg -> SwingUtilities.invokeLater(() -> logLine(msg)));
                feeds.add(f);
                f.link().setDetection(detection.levelFor(phone)); // the drone's own setting (1.0.46): boxes on its tile
                boolean muted = mutedFor(phone);
                audio.setMuted(f.audioId(), muted);
                audio.setVolume(f.audioId(), volumeFor(phone) / 100f);
                if (muted) mutedPhones.add(phone); // asks no sound from the start
            }
        } catch (IOException | RuntimeException ex) {
            for (TileFeed f : feeds) {
                f.stopStream();
                f.release();
            }
            audio.close();
            logLine("Observation failed: " + ex.getMessage());
            Dialogs.message(this, ex.getMessage(), "Cannot observe", JOptionPane.WARNING_MESSAGE);
            return;
        }
        tileFeeds.clear();
        tileFeeds.addAll(feeds);
        observationAudio = audio;
        observationGrid = grid;
        observation = new ObservationSession(set, new ArrayList<>(feeds), audio::close);
        for (String phone : mutedPhones) observation.setMuted(phone, true, now - ObservationSession.MUTED_STOP_MS);
        observedNames = Set.copyOf(set.phones());
        hoveredTile = -1;
        Container home = videoHome();
        home.remove(videoView);
        home.add(grid, BorderLayout.CENTER);
        home.revalidate();
        home.repaint();
        setLinkEditable(false);
        logLine("Observing " + String.join(", ", set.phones()) + ". Click a tile: sharp; double-click: enlarge; CONNECT on a tile flies it.");
    }

    private ObservationGrid.Listener gridListener() {
        return new ObservationGrid.Listener() {
            @Override
            public void select(int tile) {
                ObservationSession o = observation;
                if (o != null) o.select(tile);
            }

            @Override
            public void enlarge(int tile) {
                ObservationSession o = observation;
                if (o != null) o.toggleEnlarged(tile);
            }

            @Override
            public void connect(int tile) {
                connectFromGrid(tile);
            }

            @Override
            public void mute(int tile, boolean muted) {
                if (tile < 0 || tile >= tileFeeds.size() || observationAudio == null) return;
                TileFeed f = tileFeeds.get(tile);
                observationAudio.setMuted(f.audioId(), muted);
                ObservationSession o = observation;
                if (o != null) o.setMuted(f.phone(), muted, MonoClock.ms());
                save(Map.of(audioMutedKey(f.phone()), Boolean.toString(muted))); // the phone's own choice, as the pilot's MUTE
            }

            @Override
            public void volume(int tile, int volume, boolean done) {
                if (tile < 0 || tile >= tileFeeds.size() || observationAudio == null) return;
                TileFeed f = tileFeeds.get(tile);
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
        ObservationSession o = observation;
        if (o == null) return;
        o.stop();
        endObservationUi();
        logLine("Observation stopped.");
    }

    private void endObservationUi() {
        observation = null;
        observedNames = Set.of();
        tileFeeds.clear();
        observationAudio = null;
        ObservationGrid grid = observationGrid;
        observationGrid = null;
        hoveredTile = -1;
        if (grid != null) grid.dispose();
        if (grid != null && grid.getParent() != null) {
            Container parent = grid.getParent();
            parent.remove(grid);
            parent.add(videoView, BorderLayout.CENTER);
            parent.revalidate();
            parent.repaint();
        }
        miniMap.setObserved(List.of(), null);
        miniMap.repaint();
        if (mapWindow != null) {
            mapWindow.map().setObserved(List.of(), null);
            mapWindow.map().repaint();
        }
        setLinkEditable(true);
    }

    /**
     * CONNECT on a tile: every stream stops at once (latency first), then the usual pilot connection to that phone -
     * its RcSession's gates (throttle low, the arm switch off) as always. DISCONNECT later offers the grid again.
     */
    private void connectFromGrid(int tile) {
        ObservationSession o = observation;
        if (o == null) return;
        if (mapper == null) { // the streams stay: nothing could fly yet
            Dialogs.message(this, "Controller mapping is not usable: " + mapperError + "\nRun Calibrate first.",
                    "Cannot connect", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String phone = o.connect(tile); // every stream stops here
        endObservationUi();
        if (phone == null) return;
        backToGrid = true;
        roleCombo.setSelectedItem(ROLE_PILOT);
        Names.Entry e = names.get(phone);
        save(Map.of(Names.PHONE_NAME_KEY, phone, IP_KEY, e == null || e.ip() == null ? "" : e.ip()));
        updatePhoneList();
        logLine("From the grid: connecting to " + phone + " as pilot. Throttle low, arm switch off first.");
        connect();
        if (connection == null) backToGrid = false; // nothing opened: the grid is a press of OBSERVE away
    }

    /** After a flight that began on the grid: the same set again, if the pilot wants. */
    private void offerBackToGrid() {
        if (connection != null || observation != null) return;
        int answer = Dialogs.confirm(this, "Back to the grid, same drones?", "Observation",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        roleCombo.setSelectedItem(ROLE_OBSERVER);
        startObservation();
    }

    /** Every 33 ms with the rest: the tiles' figures, a phone that got a pilot leaving the grid, the map. */
    private void refreshObservation() {
        ObservationSession o = observation;
        ObservationGrid grid = observationGrid;
        if (o == null || grid == null) return;
        long now = MonoClock.ms();
        boolean invertPitch = "true".equalsIgnoreCase(config.getString(OSD_INVERT_PITCH_KEY));
        setPill("OBSERVING " + tileFeeds.size(), Theme.PANEL_2, Theme.TEXT);
        for (int i = tileFeeds.size() - 1; i >= 0; i--) {
            TileFeed f = tileFeeds.get(i);
            Observe.State st = f.link().state();
            if (st != null && st.state == Observe.ENDED_PILOT) {
                leaveGrid(i, "a pilot connected to it");
            }
        }
        for (int i = 0; i < tileFeeds.size(); i++) {
            TileFeed f = tileFeeds.get(i);
            ObserverLink l = f.link();
            String phone = f.phone();
            long last = l.lastPacketAt();
            boolean asleep = false;
            if (last == 0) {
                Names.Entry e = names.get(phone);
                asleep = e != null && e.ip() != null && power.isAsleep(e.ip());
            }
            String status = ObservationSession.tileStatus(l.state(), l.stateAt(), last, asleep, () -> {
                VideoPipeline.Stats vs = f.video().stats();
                return new int[]{vs.width(), vs.height(), vs.shownFps()};
            }, l.quality() != ObserverLink.Quality.GRID, now);
            FcTelemetry.Observed ob = l.telemetry().getObserved();
            HeadingChoice.Heading hd = HeadingChoice.choose(
                    ob == null ? null : ob.position(), compassCalibrated(phone));
            String note = ob == null ? null : hd.source() == HeadingChoice.Source.NONE ? "no heading"
                    : hd.approximate() ? "heading approximate" : null;
            l.setAudioWanted(o.audioWanted(phone, now));
            ObservationAudio audio = observationAudio;
            OsdRenderer.Data osd = ObservationOsd.data(l.telemetry(), f.video().stats(), last, invertPitch, now);
            grid.update(i, new ObservationGrid.TileState(status, audio != null && audio.isSpeaking(f.audioId()), o.isMuted(phone),
                    volumeFor(phone), note, l.telemetry().getMotion().shown(now), osd));
        }
        grid.setSelection(o.set().selected(), o.set().enlarged());
        refreshObservedMap(now);
    }

    /** A phone leaves the grid (a pilot connected to it): its tile goes, with a line why. It stays in the chosen set. */
    private void leaveGrid(int tile, String why) {
        ObservationSession o = observation;
        if (o == null || tile >= tileFeeds.size()) return;
        TileFeed f = tileFeeds.remove(tile);
        o.remove(f.phone());
        if (observationGrid != null) observationGrid.removeTile(tile);
        observedNames = Set.copyOf(o.set().phones());
        logLine(f.phone() + " left the grid: " + why + ". Stays chosen.");
    }

    /**
     * The observed phones on the minimap and the map window, five times a second: each one's marker and view cone in
     * its tile's colour and number, the hovered tile's cone stronger; a click on one selects its tile.
     */
    private void refreshObservedMap(long now) {
        if (now - observedMapAt < 200) return;
        observedMapAt = now;
        ObservationSession o = observation;
        if (o == null) return;
        List<MapView.Observed> marks = new ArrayList<>();
        for (int i = 0; i < tileFeeds.size(); i++) {
            TileFeed f = tileFeeds.get(i);
            FcTelemetry.Observed ob = f.link().telemetry().getObserved();
            if (ob == null || !ob.position().hasPosition() || now - ob.receivedAt() > 30_000) continue;
            Observe.Position p = ob.position();
            HeadingChoice.Heading hd = HeadingChoice.choose(p, compassCalibrated(f.phone()));
            int[] size = f.video().pictureSize();
            double aspect = size == null || size[1] <= 0 ? 16 / 9.0 : (double) size[0] / size[1];
            double hfov = p.hfovDeg > 1 ? p.hfovDeg : cameraView.hfovDeg();
            List<double[]> cone = cameraOnMap
                    ? ObservedCone.edge(p.lat, p.lon, p.altM, hd.deg(), hfov, cameraView.tiltDeg(), aspect)
                    : List.of();
            boolean selected = i == o.set().selected() || i == o.set().enlarged();
            boolean motion = !f.link().telemetry().getMotion().shown(now).isEmpty(); // its phone sees something move now
            marks.add(new MapView.Observed(i + 1, ObservationGrid.colour(i), p.lat, p.lon, hd.deg(), hd.approximate(), cone,
                    i == hoveredTile, selected, motion));
        }
        IntConsumer click = tile -> {
            ObservationSession s = observation;
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
        refreshPowerMarks();
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
            String text = list.isEmpty() ? "No controller" : list.get(0).name();
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
            setLabel(rcStateLabel, "NO CONTROLLER - PLUG IN THE RADIO (USB)", Theme.alpha(Theme.BAD, Math.round(110 + 145 * pulse())));
        } else if (session == null) {
            setLabel(rcStateLabel, "RC OUTPUT: NOT CONNECTED", Theme.MUTED);
        } else if (session.isRcEnabled()) {
            setLabel(rcStateLabel, "RC OUTPUT: LIVE", Theme.OK);
        } else {
            setLabel(rcStateLabel, "RC OUTPUT: OFF - LOWER THROTTLE TO ENABLE", Theme.WARN);
        }

        FcTelemetry.Status fc = connection == null ? null : connection.getLink().getTelemetry().getStatus();
        if (connection != null && connection.getLink().isFcLost()) {
            setLabel(fcStateLabel, "FC: NOT ON THE PHONE - CHECK THE USB DATA CABLE", Theme.BAD);
        } else if (fc == null || MonoClock.ms() - fc.receivedAt() > 3000) {
            setLabel(fcStateLabel, connection == null ? " " : "FC: NO STATUS YET", Theme.MUTED);
        } else if (fc.armed()) {
            setLabel(fcStateLabel, "FC: ARMED" + (fc.activeModes().size() > 1 ? "  ·  " + String.join(" ", fc.activeModes()) : ""), Theme.BAD);
        } else if (fc.blockers().isEmpty()) {
            setLabel(fcStateLabel, "FC: DISARMED - READY TO ARM", Theme.OK);
        } else {
            setLabel(fcStateLabel, "FC: ARMING BLOCKED BY " + fc.blockers().stream().map(FcTelemetry.Blocker::osdName)
                    .reduce((a, b) -> a + ", " + b).orElse("") + "  (TOOLS > RC check)", Theme.WARN);
        }
    }

    /** "%APPDATA%\DraupneLink\..." rather than a full path with the Windows user name in it. */
    static String shortPath(Path file) {
        String full = file.toAbsolutePath().toString();
        String appData = System.getenv("APPDATA");
        return appData != null && full.startsWith(appData) ? "%APPDATA%" + full.substring(appData.length()) : full;
    }

    // ---------------------------------------------------------------- updates

    /** Background, at start and every 6 hours: is there a newer release on GitHub? Only tells; installs nothing. */
    private void checkForUpdate() {
        String current = AppInfo.version();
        if ("dev".equals(current)) return;
        if (PossiblyFlying.now()) return; // nothing on the network while a drone here may fly: in 15 min again
        try {
            UpdateCheck.Release r = UpdateCheck.latest();
            if (!UpdateCheck.isNewer(r.version(), current)) return;
            SwingUtilities.invokeLater(() -> {
                if (updating) return;
                boolean first = update == null;
                update = r;
                updateButton.setText("UPDATE " + r.version());
                updateButton.setToolTipText(r.canInstall() && SelfUpdate.installedApp() != null
                        ? "DRAUPNE Link " + r.version() + " (you have " + current + "): installs and restarts"
                        : "DRAUPNE Link " + r.version() + " (you have " + current + "): opens the release page");
                updateButton.setVisible(true);
                if (first) logLine("DRAUPNE Link " + r.version() + " is out (you have " + current + "): press UPDATE.");
            });
        } catch (Exception e) {
            Log.log("Update check: " + e.getMessage()); // offline or GitHub unreachable: try later
        }
    }

    /**
     * UPDATE: download the new installer, check it, close, install and start again. Not while connected (it closes the
     * app). A portable or development copy, or a release without a checked installer, gets the release page instead.
     */
    private void startUpdate() {
        UpdateCheck.Release r = update;
        if (r == null) return;
        if (updating) {
            logLine("Update: already downloading.");
            return;
        }
        Path app = SelfUpdate.installedApp();
        if (!r.canInstall() || app == null) {
            String why = app == null
                    ? "This copy is not the installed one (runs from " + AppInfo.runningFrom()
                    + "): it can't update itself."
                    : "This release has no checkable installer.";
            logLine("Update: " + why + " Opening the release page.");
            Dialogs.message(this, "<html>" + why + "<br><br>The release page opens: download the "
                    + "installer and run it (close DRAUPNE Link first).</html>", "Update", JOptionPane.INFORMATION_MESSAGE);
            PcSetupWizard.openInBrowser(this, r.pageUrl());
            return;
        }
        if (connection != null) {
            logLine("Update: refused while connected - disconnect first.");
            Dialogs.message(this, "<html>Connected to the drone.<br><br>Updating closes DRAUPNE Link: "
                    + "the sticks would stop.<br>STANDBY MODE first, then UPDATE.</html>",
                    "Update", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int answer = Dialogs.confirm(this, updateQuestion(r.version()), "Update", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        updating = true;
        updateButton.setText("DOWNLOADING");
        logLine("Downloading DRAUPNE Link " + r.version() + "...");
        Thread t = new Thread(() -> {
            try {
                Path installer = SelfUpdate.download(r, (done, total) -> SwingUtilities.invokeLater(
                        () -> updateButton.setText("DOWNLOADING " + done * 100 / total + "%")));
                SwingUtilities.invokeLater(() -> {
                    if (connection != null) {
                        updateFailed(r, "connected during the download - disconnect, then UPDATE");
                        return;
                    }
                    try {
                        SelfUpdate.installAndRestart(installer, app, r.installerSha256());
                        logLine("Installing DRAUPNE Link " + r.version() + ": closing, restarts when done. Log: "
                                + SelfUpdate.helperLog());
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

    static String updateQuestion(String version) {
        return "<html>Update to DRAUPNE Link " + version + " now?<br><br>"
                + "It downloads, closes, installs (Windows asks once) and restarts.<br>"
                + "Settings stay.</html>";
    }

    private void updateFailed(UpdateCheck.Release r, String why) {
        updating = false;
        updateButton.setText("UPDATE " + r.version());
        logLine("Update failed: " + why);
        int answer = Dialogs.confirm(this, "<html>The update didn't work: " + why
                        + "<br><br>Open the release page?</html>",
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
                        + (path.startsWith("relay") ? " (relayed: more delay)" : "")));
            }
            phonePath = path;
            Connection c = connection;
            if (c != null) c.getLink().setLinkPath(path); // a relay holds bad coverage mode, RELAY on the OSD
            SwingUtilities.invokeLater(() -> setPhoneState(state));
        } catch (RuntimeException e) {
            Log.log("Phone check: " + e);
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
        if (shown(phoneWarning) != warn) {
            show(phoneWarning, warn);
            phoneWarning.getParent().revalidate();
        }
    }

    private static String phoneProblem(Tailscale.PhoneState s) {
        return switch (s) {
            case OFFLINE -> "Phone not on Tailscale. Switch Tailscale on there; check mobile data.";
            case NOT_FOUND -> "Phone address not on your Tailscale network. Run SETUP > Set up a new drone.";
            case PC_OFF -> "Tailscale on this PC is off or signed out: connect it.";
            case NOT_INSTALLED -> "Tailscale not installed. Run SETUP > PC setup.";
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
        return OsdDataBuilder.build(new OsdDataBuilder.In(spectator, l.isWatchOnly(),
                l.getState() == (spectator ? ControlLink.State.SPECTATING : ControlLink.State.READY),
                v != null && v.hasStream(), videoView.msSincePicture(), videoView.msSinceStreamStart(),
                l.getTelemetry().getStatus(), l.getTelemetry().getPhoneState(), s != null, s != null && s.isRcEnabled(),
                s == null ? 0 : s.msSinceInputChanged(), s == null ? null : s.lastResumeBanner(), s != null && s.isFrozenStopped(),
                s != null && s.isArmSwitchUnknown(), s == null ? null : s.waitingFor(), s == null ? null : s.getLastChannels(),
                power.wanted == ControlLink.POWER_STANDBY, spectator ? -1 : stickLagMs(l), osdLinkHint(s),
                spectator ? l.getPilotView() : null, spectator ? l.getPilotLeft() : null, l.getPing(), l.isFcLost(),
                l.getTelemetry(), v == null ? null : v.stats(), "true".equalsIgnoreCase(config.getString(OSD_INVERT_PITCH_KEY)),
                v != null && v.recordingFile() != null, spectator && l.isSpectatorPaused(), l.isLinkStalled(), l.isRelayed(),
                MonoClock.ms()));
    }

    /** The worst stick lateness the phone saw in the last second, in a fresh report; -1 = unknown. */
    private static int stickLagMs(ControlLink link) {
        FcTelemetry.PhoneRcLag lag = link.getTelemetry().getPhoneRcLag();
        if (lag == null || MonoClock.ms() - lag.receivedAt() > 2500) return -1;
        return lag.maxLagMs();
    }

    /** The second file of a recording, with the OSD drawn in (1.0.42); one for the window, used by each connection. */
    private OsdRecorder osdRecorder;

    private void startRecording(VideoPipeline v) {
        try {
            Path dir = AppPaths.videosDir();
            boolean withOsd = !"false".equalsIgnoreCase(config.getString(RECORD_OSD_KEY));
            if (withOsd && osdRecorder == null) osdRecorder = new OsdRecorder(OsdRecorder.Source.of(videoView), this::logLine);
            v.setRecordingTap(withOsd ? osdRecorder : null);
            // a recording made while the tour flies one of its drones is noted by its exact path (and its OSD copy's),
            // so the tour's end takes that and nothing else of the pilot's videos
            v.setRecordingFileListener(file -> {
                TourTraces t = tourHost.traces();
                if (t != null && t.inConnection()) {
                    t.note(file);
                    t.note(OsdRecorder.osdFile(file));
                }
            });
            v.startRecording(dir);
            logLine("Recording to " + dir + (withOsd ? ", plus a copy with the OSD." : "."));
        } catch (IOException e) {
            logLine("Could not record: " + e.getMessage());
        }
    }

    /** The warning that stops the sticks, if one shows now: what the alert sound is about. */
    private String stickWarning() {
        Connection c = connection;
        if (c == null) return null;
        ControlLink l = c.getLink();
        if (l.isSpectator()) return null; // watching: no sticks to lose, no alarm
        if (PowerController.quietWhileWaking(power.awaitingWake, l.getState())) return null;
        if (l.getState() != ControlLink.State.READY) return "LINK LOST";
        if (l.isLinkStalled()) return "LINK STALL";
        if (l.isFcLost()) return "NO FLIGHT CONTROLLER";
        FcTelemetry.Status st = l.getTelemetry().getStatus();
        if (st != null && MonoClock.ms() - st.receivedAt() < 2000
                && st.blockers().stream().anyMatch(b -> b.osdName().equals("FAILSAFE"))) return "FAILSAFE";
        // a hung radio that still reports itself: the OSD alone is easy to miss with eyes on the picture
        RcSession s = session;
        boolean armed = st != null && st.armed() && MonoClock.ms() - st.receivedAt() < 2000;
        if (armed && s != null && s.isRcEnabled() && s.msSinceInputChanged() > OsdDataBuilder.RADIO_FROZEN_MS) return "RADIO FROZEN";
        if (stickLagMs(l) > OsdDataBuilder.STICKS_LATE_MS) return "STICKS LATE";
        return null;
    }

    /** Under LINK LOST on the OSD: the reason, when Tailscale knows it; else that the sticks still go out. */
    private String osdLinkHint(RcSession session) {
        Tailscale.PhoneState s = shownPhoneState();
        if (s == null || !s.isProblem()) {
            return session != null && session.isRcEnabled() ? "STICKS STILL SENT - RECONNECTING" : null;
        }
        return switch (s) {
            case OFFLINE -> "PHONE NOT ON TAILSCALE";
            case NOT_FOUND -> "PHONE ADDRESS NOT ON YOUR TAILSCALE";
            case PC_OFF -> "TAILSCALE ON THIS PC IS OFF";
            case NOT_INSTALLED -> "TAILSCALE NOT INSTALLED ON THIS PC";
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
        // 1.0.52 (the pilot): "There should only ever be three buttons" - PILOT UAV / STANDBY MODE above, these two; no WAKE
        // (PILOT UAV on a sleeping drone wakes it at its next check)
        JPanel buttons = new JPanel(new GridLayout(1, 0, 6, 0));
        buttons.setOpaque(false);
        sleepButtonsRow = buttons;
        // 1.0.56 (the pilot): STANDBY joins them for a phone in DEEP SLEEP or OVERWATCH - queued for its next check
        standbyButton.setToolTipText("Standby at its next check");
        standbyButton.addActionListener(e -> askPower(ControlLink.POWER_STANDBY));
        deepSleepButton.setToolTipText("Phone sleeps, checking in every CHECK EVERY. Not connected: queued for its next check");
        deepSleepButton.addActionListener(e -> askPower(ControlLink.POWER_DEEP_SLEEP));
        buttons.add(deepSleepButton);
        buttons.add(overwatchButton);
        overwatchButton.setToolTipText("<html>DEEP SLEEP with the camera watching: motion calls this PC (map window) and is recorded.<br>"
                + "Not connected: queued for its next check. Disarmed only; ~15-25 h on a 5000 mAh phone</html>");
        overwatchButton.addActionListener(e -> askPower(ControlLink.POWER_OVERWATCH));
        JPanel sleepButtons = new JPanel(new GridLayout(1, 1, 0, 6));
        sleepButtons.setOpaque(false);
        sleepButtons.add(buttons);
        sleepFold = new Fold(sleepButtons, true); // folds away while the drone is armed, and back when it is disarmed
        powerRow.add(sleepFold, c);
        queuedLine.setToolTipText("Sent when the phone next listens; FLEET in the map window cancels it");
        powerRow.add(folded(queuedLine, 0), c); // a mode queued for the chosen phone (not connected)
        JPanel interval = new JPanel(new BorderLayout(8, 0));
        interval.setOpaque(false);
        interval.add(Theme.label("CHECK EVERY", Theme.headingFont(11f), Theme.MUTED), BorderLayout.WEST);
        TacticalCombo.install(checkIntervalCombo);
        checkIntervalCombo.setToolTipText("<html>How often a sleeping phone listens for this PC. More often: a little more power.<br>"
                + "Constant: always listening, PILOT UAV reaches it at once. Own setting for OVERWATCH (default Constant)</html>");
        // 1.0.56 (the pilot): DEEP SLEEP and OVERWATCH each keep their own; OVERWATCH starts at Constant, DEEP SLEEP at 5 min
        power.checkIntervalMin = savedInterval(CHECK_INTERVAL_KEY, ControlLink.POWER_DEEP_SLEEP);
        power.overwatchIntervalMin = savedInterval(OVERWATCH_INTERVAL_KEY, ControlLink.POWER_OVERWATCH);
        showIntervalFor(false);
        checkIntervalCombo.addActionListener(e -> {
            if (fillingInterval) return;
            int v = CHECK_INTERVALS[Math.max(0, checkIntervalCombo.getSelectedIndex())];
            if (intervalForOverwatch) {
                power.overwatchIntervalMin = v;
                save(Map.of(OVERWATCH_INTERVAL_KEY, Integer.toString(v)));
            } else {
                power.checkIntervalMin = v;
                save(Map.of(CHECK_INTERVAL_KEY, Integer.toString(v)));
            }
        });
        interval.add(checkIntervalCombo, BorderLayout.CENTER);
        powerRow.add(interval, c);
        checkEveryRow = interval;
        // 1.0.50 (the pilot): no status text under CHECK EVERY (powerStatus still feeds the log and tooltips where used)
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
        long now = MonoClock.ms();
        boolean fresh = st != null && now - st.receivedAt() < 2000;
        boolean maybeFlying = !fresh && lastArmedAt != 0 && now - lastArmedAt < LOST_DRONE_WINDOW_MS;
        if (!armed && !maybeFlying) return true;
        String[] options = {"Keep flying", action};
        int choice = Dialogs.option(this,
                (armed ? "The drone is ARMED." : "The drone was armed moments ago and may still be flying.")
                        + "\n" + action + " stops the sticks: Betaflight failsafe takes over (GPS rescue, land or drop, as set).",
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
        return st != null && st.armed() && MonoClock.ms() - st.receivedAt() < 2000;
    }

    private void askPower(int mode) {
        if (demoPower(mode)) return;
        JButton pressed = mode == ControlLink.POWER_STANDBY ? standbyButton : mode == ControlLink.POWER_OVERWATCH ? overwatchButton : deepSleepButton;
        String phone = config.getString(Names.PHONE_NAME_KEY);
        int current = chosenPowerMode();
        if (current == mode && mode != ControlLink.POWER_STANDBY) {
            new LockedNotice(pressed, videoView, "ALREADY " + PowerController.modeWord(mode), phone + " is already in " + PowerController.modeWord(mode) + ".").pop();
            return;
        }
        if (connection != null && power.phoneAsleep && !connection.getLink().isSpectator()) {
            // waiting for a sleeping phone to wake: another sleep mode instead - let it sleep on, queue the new one
            disconnect();
            if (!phone.isEmpty()) fleetRequest(phone, mode, power.intervalFor(mode), pressed);
            showIntervalFor(mode == ControlLink.POWER_OVERWATCH);
            return;
        }
        if (connection == null) {
            // 1.0.52 (the pilot): the mode changes without connecting - queued, sent when the phone next listens
            if (!phone.isEmpty() && !ROLE_SPECTATOR.equals(roleCombo.getSelectedItem())) fleetRequest(phone, mode, power.intervalFor(mode), pressed);
            if (mode != ControlLink.POWER_STANDBY) showIntervalFor(mode == ControlLink.POWER_OVERWATCH);
            return;
        }
        if (fcArmedNow()) {
            new LockedNotice(pressed, videoView, "ARMED", "No sleep while the drone is armed: disarm first.").pop();
            return;
        }
        // telemetry stale right after an arm: it may still fly, and every power mode stops the sticks - ask, as DISCONNECT does
        if (!confirmEndWhileArmed(mode == ControlLink.POWER_STANDBY ? "STANDBY"
                : mode == ControlLink.POWER_OVERWATCH ? "OVERWATCH" : "DEEP SLEEP")) return;
        if (mode == ControlLink.POWER_OVERWATCH && !prepareOverwatch(pressed)) return;
        power.wanted = mode;
        power.askedAt = MonoClock.ms();
        power.sentAt = 0;
        int build = connection.getLink().getTelemetry().getPhonePcBuild();
        int wantedInterval = power.intervalFor(mode);
        if (mode != ControlLink.POWER_STANDBY) showIntervalFor(mode == ControlLink.POWER_OVERWATCH);
        power.sentIntervalMin = SleepCheck.forPhone(wantedInterval, build);
        if (mode != ControlLink.POWER_STANDBY && power.sentIntervalMin != wantedInterval) {
            logLine("CHECK EVERY Constant needs phone app 2.3.0-pc." + SleepCheck.ALWAYS_MIN_PC_BUILD
                    + " (it has " + (build < 0 ? "an older one" : "pc." + build) + "): " + power.sentIntervalMin + " min instead.");
        }
        String listens = PowerController.listensText(power.sentIntervalMin);
        logLine(mode == ControlLink.POWER_STANDBY ? "STANDBY asked: camera off; PILOT UAV brings it back."
                : mode == ControlLink.POWER_OVERWATCH ? "OVERWATCH asked: sleeps with the camera watching; motion calls the map window. "
                + listens + "."
                : "DEEP SLEEP asked: drops the link and sleeps. " + listens + ".");
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
            new LockedNotice(pressed, videoView, "UPDATE THE PHONE APP", "OVERWATCH needs DRAUPNE Flight " + OverwatchProtocol.MIN_PHONE_VERSION
                    + ": UPDATE PHONE APP.").pop();
            return false;
        }
        FcTelemetry.PhonePower pp = link.getTelemetry().getPhonePower();
        if (pp != null && build >= 36 && (pp.caps() & CAP_OVERWATCH) == 0) {
            new LockedNotice(pressed, videoView, "NO PICTURE TO WATCH", "This phone's camera gives motion detection no picture: "
                    + "it can't overwatch. Use DEEP SLEEP.").pop();
            return false;
        }
        String name = config.getString(Names.PHONE_NAME_KEY);
        int token;
        do {
            token = new SecureRandom().nextInt();
        } while (token == 0);
        overwatchToken = token;
        CameraFootprint.Pose pose = cameraView.sittingPose(link.getTelemetry(), track);
        overwatch.watch(name, token, pose);
        saveWatched();
        if (pose == null) logLine("OVERWATCH: no position or heading - alerts won't show the camera view on the map.");
        return true;
    }

    /** PHONE_POWER caps bit 3 (pc.36): the camera gives the motion detection its picture - the phone can overwatch. */
    static final int CAP_OVERWATCH = 8;

    /** The hub's drones into the settings: name|token|lat|lon|alt|heading|pitch|roll|tilt|hfov|vfov;... */
    private void saveWatched() {
        StringBuilder b = new StringBuilder();
        for (OverwatchHub.Watched w : overwatch.watched()) {
            if (b.length() > 0) b.append(';');
            b.append(w.drone().replace("|", "").replace(";", "")).append('|').append(Integer.toHexString(w.token()));
            CameraFootprint.Pose p = w.pose();
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
                CameraFootprint.Pose pose = null;
                if (p.length >= 11) {
                    double[] v = new double[9];
                    for (int i = 0; i < 9; i++) v[i] = Double.parseDouble(p[2 + i]);
                    pose = new CameraFootprint.Pose(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]);
                }
                overwatch.watch(p[0], token, pose);
            } catch (NumberFormatException ignored) {
                // a broken entry: left out
            }
        }
        overwatch.setListener(new OverwatchHub.Listener() {
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
            logLine("Overwatch alerts unavailable: " + e.getMessage());
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
        logLine("OVERWATCH: " + drone + " sees motion." + (flying ? " Shown quietly, preview paused: " + Objects.requireNonNullElse(PossiblyFlying.reasonNow(), "a drone here may be flying") + "."
                : mapWindow != null && mapWindow.isShown() ? " See the map window." : " POP OUT MAP to see it."));
        if (!flying) alertSound.ring();
        MapWindow w = mapWindow;
        if (w != null && w.isShown()) {
            w.overwatchNews(drone, !flying);
        } else {
            setOverwatchBadge(true);
        }
    }

    private void overwatchDropped(String drone, int reason, int batteryPercent) {
        logLine("OVERWATCH: " + drone + " went to deep sleep - " + PowerFloor.describe(reason)
                + (batteryPercent >= 0 && batteryPercent <= 100 ? " (battery " + batteryPercent + " %)" : "") + ". PILOT UAV brings it back.");
        String ip = names.ipOf(drone);
        Asleep a = ip == null ? null : power.asleep(ip);
        if (a != null && a.overwatch()) power.remember(new Asleep(a.name(), a.ip(), a.since(), a.intervalMin(), false));
        if (connection != null && power.phoneOverwatching && drone.equalsIgnoreCase(config.getString(Names.PHONE_NAME_KEY))) power.phoneOverwatching = false;
    }

    /** POP OUT MAP says OVERWATCH while an alert waits and the map window is closed (the pilot: nothing pops up). */
    private void setOverwatchBadge(boolean on) {
        if (popMapButton == null || on == overwatchBadge) return;
        overwatchBadge = on;
        popMapButton.setText(on ? "Pop out map - OVERWATCH" : "Pop out map");
        popMapButton.setBackground(on ? Theme.WARN : null);
        popMapButton.setToolTipText(on ? "Overwatch saw motion: open the map (F3)" : "The map in its own window (F3)");
    }

    /**
     * Every refresh: the map window gets the alerts, or POP OUT MAP its badge. (Whether this PC flies the hub reads
     * itself, from PossiblyFlying - since 1.0.49 also for the lost-drone window after a quiet link.)
     */
    private void refreshOverwatch() {
        List<OverwatchHub.View> views = overwatch.views();
        MapWindow w = mapWindow;
        // the drones in OVERWATCH now, on time with their check-ins: their camera's view on both maps (1.0.50, the pilot)
        List<OverwatchPanel.Watching> watching = new ArrayList<>();
        List<MapView.OverwatchMark> miniMarks = new ArrayList<>();
        long wall = System.currentTimeMillis();
        for (Asleep a : power.allAsleep()) {
            if (!a.overwatch() || power.checkInOverdue(a, wall) > 0) continue;
            for (OverwatchHub.Watched wd : overwatch.watched()) {
                if (!wd.drone().equalsIgnoreCase(a.name()) || wd.pose() == null) continue;
                watching.add(new OverwatchPanel.Watching(wd.drone(), wd.pose()));
                miniMarks.add(new MapView.OverwatchMark(wd.drone(), wd.pose().lat(), wd.pose().lon(), watchedView(wd.pose()),
                        Double.NaN, false, false, null, true));
            }
        }
        miniMap.setOverwatchMarks(miniMarks);
        if (w != null) w.setOverwatchWatching(watching);
        if (w != null && w.isShown()) {
            w.setOverwatch(views);
            setOverwatchBadge(false);
        } else if (!overwatchBadge) {
            for (OverwatchHub.View v : views) {
                if (v.shown() == AlertCard.Shown.CARD && !v.ended()) {
                    setOverwatchBadge(true);
                    break;
                }
            }
        }
    }

    /**
     * The minimap's view of a watching drone's camera: on the ground, out to the watch range, over the terrain once it
     * has come (one loader for the minimap's watching drones), worked out again every 10 s until then.
     */
    private CameraFootprint.View watchedView(CameraFootprint.Pose p) {
        Object[] cached = watchedViews.get(p);
        long now = System.currentTimeMillis();
        if (cached != null && (!((CameraFootprint.View) cached[0]).flatGround() || now - (long) cached[1] < 10_000)) {
            return (CameraFootprint.View) cached[0];
        }
        if (watchTerrain == null) watchTerrain = new CameraFootprint.Terrain(tileCache);
        watchTerrain.around(p.lat(), p.lon());
        CameraFootprint.View v = CameraFootprint.sitting(p, watchTerrain);
        if (watchedViews.size() > 32) watchedViews.clear();
        watchedViews.put(p, new Object[]{v, now});
        return v;
    }

    private final Map<CameraFootprint.Pose, Object[]> watchedViews = new HashMap<>();
    private CameraFootprint.Terrain watchTerrain;

    /** CONNECT on an overwatch card: the phone wakes (told over the overwatch link) and this PC connects to it as its pilot. */
    void overwatchConnect(String drone) {
        Connection c = connection;
        String flown = config.getString(Names.PHONE_NAME_KEY);
        boolean same = c != null && !c.getLink().isSpectator() && drone.equalsIgnoreCase(flown);
        if (!same && c != null && PossiblyFlying.now()) {
            // armed now or within the lost-drone window: never a click away from cutting this PC's own sticks
            Dialogs.message(this, "This PC flies another drone (or did moments ago: it may still be flying).\n"
                    + "Land it, press STANDBY MODE, then PILOT UAV on " + drone + ".", "Overwatch", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // 1.0.50 (the pilot): no confirm - CONNECT on the card connects (the 1.0.49 question is gone); a PC that may
        // fly another drone is still refused above
        overwatch.connect(drone);
        toPilotWindow(); // 1.0.52 (the pilot): the card is in the map window; flying happens here
        if (same) {
            wakePhone(); // this PC's link to it waits dormant: the Connects go out again now
            return;
        }
        if (c != null) disconnect();
        String ip = names.ipOf(drone);
        if (ip != null && power.isAsleep(ip)) power.clear(ip); // woken now, not at its next check
        connectFromMap(drone, false);
    }

    /** The pilot's DISMISS on a card. */
    void overwatchDismiss(String drone) {
        overwatch.dismiss(drone);
    }

    OverwatchHub overwatch() {
        return overwatch;
    }

    // ------------------------------------------------------------------ phones asleep while no link is open

    /** The phone chosen in the PHONE list, if it is asleep; null otherwise (or as a spectator). */
    /**
     * The chosen phone's mode lights its button (1.0.51, the pilot): OVERWATCH while it overwatches, DEEP SLEEP while it
     * sleeps - connected or not. Not while the tour's demo drives the buttons.
     */
    private void refreshPowerMarks() {
        if (tourPowerDemo) return;
        int mode = chosenPowerMode();
        ((TacticalButton) deepSleepButton).setCurrent(mode == ControlLink.POWER_DEEP_SLEEP);
        ((TacticalButton) overwatchButton).setCurrent(mode == ControlLink.POWER_OVERWATCH);
        // CHECK EVERY follows the mode it is in (1.0.56: OVERWATCH and DEEP SLEEP keep their own)
        if (mode == ControlLink.POWER_OVERWATCH || mode == ControlLink.POWER_DEEP_SLEEP) showIntervalFor(mode == ControlLink.POWER_OVERWATCH);
        refreshQueued();
    }

    /**
     * The chosen phone's mode, connected or not (-1 unknown): the link's word, else asleep / overwatching as this PC
     * knows it, a standby this PC left it in, or its own last word to the fleet (pc.40, within 10 min).
     */
    private int chosenPowerMode() {
        Connection c = connection;
        int mode = PowerController.shownPowerMode(c == null ? null : c.getLink(), asleepChosen(), power.phoneAsleep, power.phoneOverwatching);
        if (mode >= 0 || c != null) return mode;
        String ip = config.getString(IP_KEY), phone = config.getString(Names.PHONE_NAME_KEY);
        if (ROLE_SPECTATOR.equals(roleCombo.getSelectedItem()) || phone.isEmpty()) return -1;
        if (!ip.isEmpty() && standbyPhones.containsKey(ip)) return ControlLink.POWER_STANDBY;
        FleetManager.Heard h = fleets == null ? null : fleets.manager().heard(phone);
        if (h != null && h.status() != null && h.status().mode >= 0 && System.currentTimeMillis() - h.at() < 10 * 60_000L) return h.status().mode;
        return -1;
    }

    /** A mode queued for the chosen phone: one short line under the sleep buttons. */
    private final JLabel queuedLine = Theme.label(" ", Theme.headingFont(11f), Theme.WARN);

    private void refreshQueued() {
        FleetManager.Wanted w = fleets == null || connection != null ? null : fleets.manager().wanted(config.getString(Names.PHONE_NAME_KEY));
        String text = w == null ? null : "QUEUED: " + PowerController.modeWord(w.mode()) + " (AT CHECK-IN)";
        if (text != null && !text.equals(queuedLine.getText())) queuedLine.setText(text);
        if (shown(queuedLine) != (text != null)) show(queuedLine, text != null);
    }

    /** How long until the waited-for sleeping phone checks in (ms), or -1 when it listens all along or is unknown. */
    private long wakeInMs() {
        return power.wakeInMs(MonoClock.ms(), connection == null ? null : connection.getLink().getSleepHello());
    }

    private void refreshConnectText() {
        Connection c = connection;
        String text = PowerController.connectText(c != null, c != null && c.getLink().isSpectator(), observation != null, observerRole(),
                ROLE_SPECTATOR.equals(roleCombo.getSelectedItem()), c != null && power.phoneAsleep, wakeInMs());
        if (!text.equals(connectButton.getText())) connectButton.setText(text);
    }

    private Asleep asleepChosen() {
        if (ROLE_SPECTATOR.equals(roleCombo.getSelectedItem())) return null;
        String ip = config.getString(IP_KEY);
        return ip.isEmpty() ? null : power.asleep(ip);
    }

    /**
     * The drone was heard of after wallMs (a track point's time): its phone's position in a name reply, an overwatch
     * alert from it, or a sleep check. Then the map shows that newer word, not LAST KNOWN.
     */
    private boolean heardSince(String phone, String ip, long wallMs) {
        for (NameService.DronePosition d : nameService.dronePositions()) {
            if (d.name().equalsIgnoreCase(phone) && d.at() - d.position().ageS() * 1000L > wallMs) return true;
        }
        for (OverwatchHub.View v : overwatch.views()) {
            if (v.drone().equalsIgnoreCase(phone) && v.alertAtWall() > wallMs) return true;
        }
        ControlLink.SleepHello h = ip == null ? null : power.hello(ip);
        return h != null && h.receivedAt() > wallMs;
    }

    /**
     * A sleeping phone's check-in that isn't this PC's link's own phone (the name service's thread with no link open,
     * or the link's for another phone): listed and drawn, then {@link PowerController#checkIn} remembers and answers it.
     */
    private Boolean onSleepCheck(String ip, byte[] d) {
        // pc.40: who and where it is - listed and drawn even when no scan reached it before it slept (the tour's drones)
        if (d.length >= 8 + FleetProtocol.STATUS_BYTES) {
            FleetProtocol.Where where = FleetProtocol.Where.read(d,
                    8 + FleetProtocol.STATUS_BYTES, d.length, config.getKey());
            if (where != null && !where.name.equalsIgnoreCase(nameService.ownName())) {
                ControlLink.SleepHello said = ControlLink.SleepHello.read(d, d.length, System.currentTimeMillis());
                int mode = said.status() != null && said.status().mode >= 0 ? said.status().mode : said.overwatch() ? 3 : 2;
                if (nameService.heardAsleep(ip, where, mode)) SwingUtilities.invokeLater(this::updatePhoneList);
            }
        }
        return power.checkIn(ip, d);
    }

    /** The asleep book's way back into the window: the log and the settings on the Swing thread. */
    private final class PowerHost implements PowerController.Host {
        @Override
        public String nameAt(String ip) {
            return names.nameAt(ip);
        }

        @Override
        public boolean observed(String name) {
            return observedNames.contains(name);
        }

        @Override
        public void observedCheckIn(String ip, String name) {
            SwingUtilities.invokeLater(() -> {
                power.clear(ip);
                logLine(name + " checked in: woken for observation.");
            });
        }

        @Override
        public void logLater(String line) {
            SwingUtilities.invokeLater(() -> logLine(line));
        }

        @Override
        public void saveAsleep(String saved) {
            SwingUtilities.invokeLater(() -> save(Map.of(ASLEEP_KEY, saved)));
        }
    }

    /** Not connected, the chosen phone asleep: the power row stays, with when it checks next and WAKE. */
    private void refreshAsleep() {
        Asleep a = asleepChosen();
        if (powerRow == null || a == null) return;
        long now = System.currentTimeMillis();
        ControlLink.SleepHello h = power.hello(a.ip());
        // it answers the name service's queries, and hasn't checked in for a minute: it is awake (woken on the phone)
        Names.Entry e = names.get(a.name());
        if (e != null && e.online() && (h == null || now - h.receivedAt() > PowerController.awakeAfterMs(a)) && now - a.since() > PowerController.awakeAfterMs(a)) {
            logLine(a.name() + " is awake again.");
            power.clear(a.ip());
            return;
        }
        if (!shown(powerRow)) show(powerRow, true);
        sleepFold.setShown(true, true);
        standbyButton.setEnabled(true); // 1.0.56 (the pilot): STANDBY at its next check
        showStandbyButton(true);
        deepSleepButton.setEnabled(true); // another mode is queued for its next check; the same one is said to be on
        overwatchButton.setEnabled(true);
        checkIntervalCombo.setEnabled(true);
        String alive = "";
        if (h != null) {
            long ago = (now - h.receivedAt()) / 1000;
            alive = "Heard " + (ago < 60 ? ago + " s" : ago / 60 + " min") + " ago"
                    + (h.batteryPercent() >= 0 ? ", battery " + h.batteryPercent() + " %" : "") + (h.charging() ? " (charging)" : "") + ". ";
        }
        powerStatus.setText("<html>" + a.name() + " is in " + a.mode() + ". " + alive + (a.listening() ? "It is listening: PILOT UAV brings it back at once.</html>"
                : "Next check in " + PowerController.clock(power.nextCheck(a, now) - now) + ". PILOT UAV brings it back then.</html>"));
    }

    /**
     * Not connected, the chosen phone awake or in standby (1.0.52, the pilot: "change the mode ... without connecting"):
     * the sleep buttons stay and queue their mode for it. Not as a spectator or observer, nor with no phone chosen.
     */
    private void refreshIdlePower() {
        boolean pilot = !observerRole() && !ROLE_SPECTATOR.equals(roleCombo.getSelectedItem()) && observation == null;
        boolean on = pilot && !config.getString(Names.PHONE_NAME_KEY).isEmpty();
        if (shown(powerRow) != on) show(powerRow, on);
        if (!on) return;
        sleepFold.setShown(true, true);
        int m = chosenPowerMode(); // asleep as its own word or the fleet's says: STANDBY too (1.0.56)
        boolean sleeping = m == ControlLink.POWER_DEEP_SLEEP || m == ControlLink.POWER_OVERWATCH;
        standbyButton.setEnabled(sleeping);
        showStandbyButton(sleeping);
        deepSleepButton.setEnabled(true);
        overwatchButton.setEnabled(true);
        checkIntervalCombo.setEnabled(true);
    }

    /**
     * The chosen phone, when its overwatch alert is on the map (1.0.56, the pilot: PILOT UAV on it does what the alert's
     * CONNECT does - wakes it at once over the overwatch link); null otherwise, or not as pilot.
     */
    private String alertingChosen() {
        if (!ROLE_PILOT.equals(roleCombo.getSelectedItem())) return null;
        List<String> alerting = new ArrayList<>();
        for (OverwatchHub.View v : overwatch.views()) alerting.add(v.drone());
        return alertingDrone(config.getString(Names.PHONE_NAME_KEY), alerting);
    }

    /** The phone as its alert names it, if one of these alerting drones is it (any case); else null. */
    static String alertingDrone(String phone, List<String> alerting) {
        if (phone == null || phone.isEmpty()) return null;
        for (String d : alerting) if (d.equalsIgnoreCase(phone)) return d;
        return null;
    }

    /** PILOT UAV on a sleeping phone: connect as its pilot; the link answers its next check "wake". */
    private void wakeAsleep() {
        Asleep a = asleepChosen();
        if (a == null) return;
        if (!ROLE_PILOT.equals(roleCombo.getSelectedItem())) roleCombo.setSelectedItem(ROLE_PILOT);
        save(Map.of(Names.PHONE_NAME_KEY, a.name()));
        connect();
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
        logLine(audioMuted ? "Sound muted (M)." : "Sound on (M).");
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
            case OLD_PHONE -> "Live sound needs DRAUPNE Flight 2.3.0-pc." + ControlLink.AUDIO_MIN_PC_BUILD + " (phone has "
                    + c.getLink().getTelemetry().getPhoneAppVersion() + ").";
            case ON -> "Live sound on" + (audioMuted ? ", muted here (M)." : " (M mutes).");
            case HELD -> "Live sound held: drone armed or state unknown" + (audioMuted ? "." : " (needs phone app pc.39 in the air).");
            case FAILED -> "Live sound: phone microphone didn't start (allowed for DRAUPNE Flight?).";
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
        long now = MonoClock.ms();
        boolean armed = c != null && !c.getLink().isSpectator() && c.getLink().getTelemetry().isArmedNow(now, 2000);
        if (armed && !audioSawArmed) {
            armedSoundAt = now;
            armMuteDone = false;
        }
        // a virtual drone's motors spinning up are heard first (1.0.52, the pilot), then armed = muted; a real drone
        // mutes at once, so its phone doesn't send sound over the uplink at take-off
        long spinUp = videoView.isVirtual() ? SPIN_UP_HEARD_MS : 0;
        if (armed && !armMuteDone && now - armedSoundAt >= spinUp) {
            armMuteDone = true;
            if (!audioMuted) {
                audioAutoMuted = true;
                setAudioMuted(true, true);
                logLine("Armed: sound muted. M unmutes.");
            }
        } else if (!armed && audioSawArmed) {
            if (c != null && !c.getLink().getTelemetry().isDisarmedNow(now, 2000)) return; // a 4G gap: wait for the word
            if (audioAutoMuted && audioMuted) {
                setAudioMuted(false, true);
                logLine("Disarmed: sound on.");
            }
            audioAutoMuted = false;
        }
        audioSawArmed = armed;
    }

    private String muteTip(ControlLink.AudioState s) {
        String what = (audioMuted ? "SOUND MUTED: M or click to hear" : "SOUND: M or click to mute")
                + ". Arming mutes it";
        if (s == null) return what;
        return switch (s) {
            case OLD_PHONE -> what + ". Needs phone app pc." + ControlLink.AUDIO_MIN_PC_BUILD + ".";
            case HELD -> what + ". Now: held, drone armed.";
            case FAILED -> what + ". Now: phone microphone didn't start.";
            case STOPPED -> what + ". Now: phone stopped sending.";
            case ON -> what + ". Now: on.";
            default -> what + ".";
        };
    }

    // ------------------------------------------------------------------ the camera's way up, per phone (1.0.41)

    /** The phone's own setting; one never set takes the old PC-wide one (up to 1.0.40). */
    private boolean upsideDownFor(String phone) {
        return cameraTurn.upsideDown(phone);
    }

    /** Each phone's way up (1.0.55): the tiles, the overwatch cards and clips, previous flights read it too. */
    CameraTurn cameraTurn() {
        return cameraTurn;
    }

    // ------------------------------------------------------------------ kept flights (1.0.41)

    private void loadKeptFlights() {
        String saved = config.getString(KEPT_FLIGHTS_KEY);
        if (saved == null || saved.isBlank()) return;
        for (String one : saved.split("\\|")) {
            int at = one.indexOf('>');
            if (at <= 0) continue;
            Path file = Path.of(one.substring(at + 1));
            try {
                FlightTrack t = FlightTrack.read(file);
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
    private void keepFlight(FlightTrack t, String name) {
        if (t == null || t.isEmpty()) return;
        Path file = t.file();
        for (KeptFlight k : keptFlights) if (file != null && file.equals(k.file())) return; // kept already
        keptFlights.add(new KeptFlight(name == null ? "Flight " + (keptFlights.size() + 1) : name, file, t.points()));
        saveKeptFlights();
        pushKept();
    }

    /** The map views get the kept flights, but the one shown live (the flight just ended stays the live one until the next). */
    private void pushKept() {
        Path live = track == null ? null : track.file();
        List<MapView.Kept> list = new ArrayList<>();
        List<Integer> index = new ArrayList<>();
        for (int i = 0; i < keptFlights.size(); i++) {
            KeptFlight k = keptFlights.get(i);
            if (live != null && live.equals(k.file())) continue;
            list.add(new MapView.Kept(k.name(), k.points()));
            index.add(i);
        }
        IntConsumer remove = shown -> {
            if (shown < 0 || shown >= index.size()) return;
            KeptFlight k = keptFlights.remove((int) index.get(shown));
            saveKeptFlights();
            logLine("Flight " + k.name() + " off the map (file kept).");
            pushKept();
        };
        Runnable clear = () -> {
            int n = list.size();
            if (Dialogs.confirm(this, "Take all " + n + " earlier flight" + (n == 1 ? "" : "s") + " off the map?\n"
                    + "Files are kept.", "Earlier flights", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            keptFlights.removeIf(k -> live == null || !live.equals(k.file()));
            saveKeptFlights();
            logLine(n + " earlier flight" + (n == 1 ? "" : "s") + " taken off the map.");
            pushKept();
        };
        list.addAll(tourHost.flights()); // after the kept ones: removing one of those never reaches these
        miniMap.setKept(list, remove, clear);
        if (mapWindow != null) mapWindow.map().setKept(list, remove, clear);
    }

    private static String flightName(String who) {
        return (who == null || who.isEmpty() ? "Flight" : who) + " " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** Watching, and the pilot flies another phone now: the flight so far is kept, a new one starts. */
    private void followWatchedPhone(ControlLink link) {
        String phone = link.getPilotPhone();
        if (phone == null) return;
        if (watchedPhone != null && !phone.equals(watchedPhone)) {
            keepFlight(liveTrack, liveTrackName);
            if (liveTrack != null) liveTrack.close(pins.list());
            try {
                liveTrack = new FlightTrack(FlightTrack.defaultDir());
            } catch (IOException e) {
                liveTrack = new FlightTrack(null);
            }
            track = liveTrack;
            logLine("The pilot now flies " + phone + " (was " + watchedPhone + "): new track started.");
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
        // the flown drone is left out only once its live position is on the map: until then (a CONNECT waiting for a
        // sleeping or overwatching phone to wake, its new track still empty) its own sign stays (1.0.50, the pilot)
        boolean liveShown = false;
        if (connection != null && track != null && track.last() != null) {
            ControlLink.State st = connection.getLink().getState();
            liveShown = st == ControlLink.State.READY || st == ControlLink.State.SPECTATING;
        }
        List<MapView.NetDrone> drones = new ArrayList<>();
        for (NameService.DronePosition d : nameService.dronePositions()) {
            if (liveShown && d.name().equalsIgnoreCase(flying)) continue; // shown live already
            if (observedNames.contains(d.name())) continue; // OBSERVATION draws it, numbered
            Names.Position p = d.position();
            long age = now - d.at() + p.ageS() * 1000L;
            String ip = names.ipOf(d.name());
            Asleep sleeping = ip == null ? null : power.asleep(ip);
            String pilot = p.pilotIp().isEmpty() ? null : names.nameAt(p.pilotIp());
            // 1.0.50 (the pilot): asleep and overwatching drones get signs of their own, never the lost one's - until
            // they miss their check-ins, then they are unknown; a drone gone quiet while flown or armed is lost
            MapView.Kind kind;
            String doing;
            String standbyName = ip == null || sleeping != null ? null : standbyPhones.get(ip);
            if (standbyName != null && Boolean.TRUE.equals(p.armed())) {
                standbyPhones.remove(ip); // armed ends standby on the phone
                standbyName = null;
            }
            int said = p.powerMode(); // pc.39: what the phone said it was doing at the last scan that heard it
            if (standbyName == null && sleeping == null && said >= 1 && said <= 3 && age <= 30_000) {
                // put to sleep by another PC (or by this one, forgotten): its own word, while it is heard
                kind = said == 3 ? MapView.Kind.OVERWATCH : MapView.Kind.ASLEEP;
                doing = (said == 1 ? "STANDBY" : said == 2 ? "DEEP SLEEP" : "OVERWATCH") + " (the phone says)";
            } else if (standbyName != null) {
                kind = age > 120_000 ? MapView.Kind.UNKNOWN : MapView.Kind.ASLEEP;
                doing = age > 120_000 ? "STANDBY - NOT HEARD " + Names.agoText(now - age, now).toUpperCase(Locale.ROOT) + " - UNKNOWN"
                        : "STANDBY - CONNECT wakes it";
            } else if (sleeping != null) {
                long overdue = power.checkInOverdue(sleeping, now);
                if (overdue > 0) {
                    kind = MapView.Kind.UNKNOWN;
                    doing = sleeping.mode() + " - MISSED CHECK-IN, NOT HEARD " + Names.agoText(power.lastHeard(sleeping), now).toUpperCase(Locale.ROOT) + " - UNKNOWN";
                } else {
                    kind = sleeping.overwatch() ? MapView.Kind.OVERWATCH : MapView.Kind.ASLEEP;
                    doing = sleeping.mode() + (sleeping.listening() ? " - LISTENING" : " - check in " + PowerController.clock(power.nextCheck(sleeping, now) - now));
                }
            } else if (age > 30_000) {
                boolean wasFlying = Boolean.TRUE.equals(p.armed()) || p.flown();
                kind = wasFlying ? MapView.Kind.LOST : MapView.Kind.UNKNOWN;
                doing = (wasFlying ? "LINK LOST" + (Boolean.TRUE.equals(p.armed()) ? " WHILE ARMED" : "") : "NOT HEARD - UNKNOWN") + " - " + Names.agoText(now - age, now);
            } else {
                kind = MapView.Kind.ACTIVE;
                doing = (Boolean.TRUE.equals(p.armed()) ? "ARMED - " : "") + (p.flown() ? "FLOWN" + (pilot == null ? "" : " BY " + pilot) : "IDLE");
            }
            // a mode queued here shows at once (1.0.56, the pilot): not the question mark while the phone takes it
            FleetManager.Wanted queuedMode = fleets == null ? null : fleets.manager().wanted(d.name());
            if (queuedMode != null && !Boolean.TRUE.equals(p.armed())) {
                FleetCards.Badge b = FleetCards.withQueued(new FleetCards.Badge(kind, ""), queuedMode.mode());
                if (b.kind() != kind || !b.text().isEmpty()) {
                    kind = b.kind();
                    doing = PowerController.modeWord(queuedMode.mode()) + " - QUEUED";
                }
            }
            Long crashedAt = crashed.get(d.name());
            if (crashedAt != null && d.at() > crashedAt + 2000) {
                clearCrashed(d.name(), "heard again"); // its phone answers: not gone after all
            } else if (crashedAt != null) {
                kind = MapView.Kind.CRASHED;
                doing = "CRASHED - LOST " + Names.agoText(crashedAt, now);
            }
            String state = (VirtualFleet.isVirtual(ip) ? "VIRTUAL - " : "") + doing;
            drones.add(new MapView.NetDrone(d.name(), p.lat(), p.lon(), state, kind, p.flown(), pilot));
        }
        // a crashed drone the network has no position of (the one flown here): at its track's last point
        for (Map.Entry<String, Long> e : crashed.entrySet()) {
            String name = e.getKey();
            if (drones.stream().anyMatch(x -> x.name().equalsIgnoreCase(name))) continue;
            if (connection != null && name.equalsIgnoreCase(flying) && connection.getLink().getState() == ControlLink.State.READY) {
                clearCrashed(name, "link back"); // its link is up again: flying, not crashed
                continue;
            }
            FlightTrack.Point last = name.equalsIgnoreCase(config.getString(Names.PHONE_NAME_KEY)) && track != null ? track.last() : null;
            if (last != null) drones.add(new MapView.NetDrone(name, last.lat(), last.lon(), "CRASHED - LOST " + Names.agoText(e.getValue(), now),
                    MapView.Kind.CRASHED, false, null));
        }
        miniMap.setNetDrones(drones, this::connectFromMap);
        miniMap.setOnClearCrashed(this::clearCrashedByPilot);
        miniMap.setOnMarkCrashed(n -> markCrashed(n, "marked"));
        if (mapWindow != null) {
            mapWindow.map().setNetDrones(drones, this::connectFromMap);
            mapWindow.map().setOnClearCrashed(this::clearCrashedByPilot);
            mapWindow.map().setOnMarkCrashed(n -> markCrashed(n, "marked"));
        }
        if (!power.noneAsleep() && connection == null && !phoneCombo.isPopupVisible()) updatePhoneList(); // the countdowns
    }

    // ------------------------------------------------------------------ FLEET MANAGEMENT (1.0.52)

    private static final String FLEET_QUEUE_KEY = "fleet.queue";
    /** FLEET MANAGEMENT (1.0.52): queued modes and the FLEET tab's cards; set in the constructor. */
    private FleetController fleets;

    /** True when this PC's pilot link is to that phone. */
    private boolean connectedTo(String phone) {
        return fleets.connectedTo(phone);
    }

    /** A card's button: the mode queued for the phone - or refused, said beside the button. */
    void fleetRequest(String phone, int mode, int intervalMin, JComponent near) {
        if (fleets != null) fleets.request(phone, mode, intervalMin, near);
    }

    /** FLEET's PILOT UAV: as the map's right-click "fly" (a sleeping drone wakes at its next check). */
    void fleetConnect(String phone) {
        if (connectedTo(phone)) return;
        connectFromMap(phone, false);
    }

    void fleetCancel(String phone) {
        if (fleets != null) fleets.cancel(phone);
    }

    /** Once a second: every phone's card for the map window (only while it is open). */
    private void refreshFleet() {
        if (fleets != null) fleets.refresh();
    }

    /** The fleet's way back into the window. */
    private final class FleetHost implements FleetController.Host {
        @Override
        public Connection connection() {
            return connection;
        }

        @Override
        public int detectionFor(String phone) {
            return detection.levelFor(phone);
        }

        @Override
        public boolean mutedFor(String phone) {
            return MainWindow.this.mutedFor(phone);
        }

        @Override
        public void saveWatched() {
            MainWindow.this.saveWatched();
        }

        @Override
        public void logLine(String text) {
            MainWindow.this.logLine(text);
        }

        @Override
        public MapWindow mapWindowIfOpened() {
            return mapWindow;
        }
    }

    /** Right-click on a drone on the map: fly it (as pilot), or watch it through the PC that flies it. */
    private void connectFromMap(String phone, boolean watch) {
        toPilotWindow();
        if (observation != null && watch) {
            Dialogs.message(this, "Observing: STOP OBSERVING first.", "Observing", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // 1.0.52 (the pilot: "PILOT UAV needs to put the connect as to pilot"): every PILOT UAV - fleet, map, alert -
        // flies: an observation (no sticks, streams only) ends first and CONNECT AS goes to Pilot below
        if (observation != null) stopObservation();
        if (connection != null) {
            Dialogs.message(this, "Connected: STANDBY MODE first, then PILOT UAV on " + phone + ".",
                    "Connected", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String target = phone;
        if (watch) {
            NameService.DronePosition d = nameService.dronePositions().stream().filter(x -> x.name().equals(phone)).findFirst().orElse(null);
            target = d == null || d.position().pilotIp().isEmpty() ? null : names.nameAt(d.position().pilotIp());
            if (target == null) {
                Dialogs.message(this, "The PC flying " + phone + " isn't in the name map yet.", "Watch", JOptionPane.INFORMATION_MESSAGE);
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
        long now = MonoClock.ms();
        if (now - pilotViewSentAt < 200) return;
        pilotViewSentAt = now;
        OsdRenderer.Data d = osdData();
        if (d == null) return;
        link.setPilotView(new ControlLink.PilotView(d.linkReady(), d.rcLive(), d.radioFrozen(), d.armSwitchUnknown(), d.relayed(),
                d.linkStalled(), d.rcWaiting(), d.banner(), d.linkHint(), d.channels(), config.getString(Names.PHONE_NAME_KEY),
                videoView.isUpsideDown()));
    }

    /** Back to normal on this PC's own link (an overwatch card's CONNECT on the drone this PC waits for). */
    private void wakePhone() {
        power.wanted = ControlLink.POWER_NORMAL;
        power.askedAt = MonoClock.ms();
        power.sentAt = 0;
        if (connection != null && power.phoneAsleep) {
            connection.getLink().setDormant(false);
            logLine(SleepCheck.isAlways(power.sentIntervalMin) ? "Waking: the phone is listening: now."
                    : "Waking: at the phone's next check (within " + power.sentIntervalMin + " min).");
        } else {
            logLine("Back to normal asked.");
        }
    }

    /** Once a refresh while connected: keeps asking until the phone is in the mode wanted, and says where things are. */
    private void refreshPower(ControlLink link) {
        long now = MonoClock.ms();
        FcTelemetry.PhonePower pp = link.getTelemetry().getPhonePower();
        int reported = pp == null ? -1 : pp.mode();
        boolean ready = link.getState() == ControlLink.State.READY;
        if (!shown(powerRow)) show(powerRow, true);
        sleepFold.setShown(!fcArmedNow(), true); // armed: the two sleep buttons slide away; disarmed: they slide back
        if (power.wanted != ControlLink.POWER_NORMAL && fcArmedNow()) {
            power.wanted = ControlLink.POWER_NORMAL;
            power.sentAt = 0;
            logLine("Drone armed: sleep cancelled, phone woken.");
        }
        // the phone said DEEP SLEEP (or OVERWATCH, 1.0.47), then the link went: it sleeps now, and this PC must not wake
        // it until WAKE
        boolean sleepMode = power.wanted == ControlLink.POWER_DEEP_SLEEP || power.wanted == ControlLink.POWER_OVERWATCH;
        if (sleepMode && reported == power.wanted && !power.phoneAsleep && !link.isConnected()) {
            power.phoneAsleep = true;
            power.awaitingWake = true;
            power.phoneOverwatching = power.wanted == ControlLink.POWER_OVERWATCH;
            power.sleepStartAt = pp.receivedAt() + pp.sleepInS() * 1000L;
            link.setDormant(true);
            logLine(power.phoneOverwatching ? "Phone overwatches: motion shows in the map window. "
                    + PowerController.listensText(power.sentIntervalMin) + "; PILOT UAV brings it back."
                    : "Phone in deep sleep. " + PowerController.listensText(power.sentIntervalMin) + "; PILOT UAV brings it back.");
            autoDisconnect(link, "asleep"); // 1.0.50, the pilot: the sleep buttons end the connection by themselves
        }
        if (power.phoneAsleep) {
            if (link.isConnected()) {
                power.phoneAsleep = false;
                power.phoneOverwatching = false;
                power.wanted = ControlLink.POWER_NORMAL;
                power.sentAt = 0;
                link.setDormant(false);
                link.restartVideo();
                logLine("Phone awake.");
                power.clear(config.getString(IP_KEY));
            } else {
                boolean always = SleepCheck.isAlways(power.sentIntervalMin);
                long interval = SleepCheck.checkInMinutes(power.sentIntervalMin) * 60_000L;
                long next = power.sleepStartAt + interval;
                // the phone's own word from its last check (pc.22): when the next one is, and its battery
                ControlLink.SleepHello hello = link.getSleepHello();
                String alive = "";
                if (hello != null && hello.receivedAt() >= power.sleepStartAt - 5000 && power.phoneOverwatching && !hello.overwatch()
                        && hello.receivedAt() != power.lastHelloLogged) {
                    power.phoneOverwatching = false; // the floor: it left overwatch for deep sleep by itself (1.0.47)
                    logLine("OVERWATCH ended on the phone: " + (hello.floorReason() != 0 ? PowerFloor.describe(hello.floorReason())
                            : "it left overwatch") + " - now in deep sleep.");
                }
                if (hello != null && hello.receivedAt() >= power.sleepStartAt - 5000) {
                    if (hello.nextCheckMin() > 0) next = hello.receivedAt() + SleepCheck.checkInMinutes(hello.nextCheckMin()) * 60_000L;
                    long ago = (now - hello.receivedAt()) / 1000;
                    alive = "Heard " + (ago < 60 ? ago + " s" : ago / 60 + " min") + " ago"
                            + (hello.batteryPercent() >= 0 ? ", battery " + hello.batteryPercent() + " %" : "")
                            + (hello.charging() ? " (charging)" : "") + ". ";
                    if (hello.receivedAt() != power.lastHelloLogged) {
                        power.lastHelloLogged = hello.receivedAt();
                        logLine("Phone asleep: battery " + (hello.batteryPercent() >= 0 ? hello.batteryPercent() + " %" : "unknown")
                                + (hello.charging() ? ", charging" : "") + (SleepCheck.isAlways(hello.nextCheckMin())
                                ? ", listening." : ", next check in " + hello.nextCheckMin() + " min."));
                    }
                }
                while (next < now) next += interval;
                long left = (next - now) / 1000;
                String in = String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60);
                powerStatus.setText(link.isDormant()
                        ? "<html>" + (power.phoneOverwatching ? "OVERWATCH - its camera watches; an alert shows in the map window. " : "DEEP SLEEP - ") + alive
                        + (always ? "The phone is listening for this PC.</html>"
                        : "The phone checks for this PC every " + power.sentIntervalMin + " min; next check in " + in + ".</html>")
                        : always ? "<html>Waking - " + alive + "The phone is listening: it wakes now, then its camera starts.</html>"
                        : "<html>Waking - " + alive + "The phone takes it at its next check, in " + in + ", then its camera starts.</html>");
                // waiting for it to wake (REGAINING CONTROL): the other sleep mode instead is queued, the same one is
                // said to be on already
                standbyButton.setEnabled(true); // 1.0.56: STANDBY instead, queued for its next check
                showStandbyButton(true);
                deepSleepButton.setEnabled(true);
                overwatchButton.setEnabled(true);
                checkIntervalCombo.setEnabled(true);
            }
            return;
        }
        if (ready && power.wanted != reported) {
            if (now - power.sentAt >= 200) {
                if (power.wanted == ControlLink.POWER_OVERWATCH) {
                    // the drone's own motion detection level (OFF counts as NORMAL here: watching is the point), and its
                    // microphone in the clip unless this PC has the drone's sound muted
                    int level = detection.levelFor(config.getString(Names.PHONE_NAME_KEY));
                    if (level == 0) level = 2;
                    int flags = mutedFor(config.getString(Names.PHONE_NAME_KEY)) ? 0 : OverwatchProtocol.MODE_FLAG_SOUND;
                    link.sendOverwatch(power.sentIntervalMin, level, flags, overwatchToken);
                } else {
                    link.sendPowerMode(power.wanted, power.wanted == ControlLink.POWER_NORMAL ? power.checkIntervalMin : power.sentIntervalMin);
                }
                power.sentAt = now;
            }
            if (pp == null && power.askedAt != 0 && now - power.askedAt > 10_000) {
                powerStatus.setText("<html>The phone app doesn't answer: STANDBY and DEEP SLEEP need DRAUPNE Flight 2.3.0-pc.21 or newer (tap its Update available line).</html>");
            } else if (power.wanted != ControlLink.POWER_NORMAL || reported > 0) {
                powerStatus.setText("Asking the phone...");
            } else {
                powerStatus.setText(" ");
            }
        } else if (!ready) {
            powerStatus.setText(" ");
        } else if (reported == ControlLink.POWER_STANDBY) {
            powerStatus.setText("<html>STANDBY - the phone's camera is off, its GPS and telemetry slowed, its screen dimmed. No sticks are sent.</html>");
            if (power.wanted == ControlLink.POWER_STANDBY && !link.isSpectator()) { // 1.0.50: STANDBY ends the connection too
                String ip = config.getString(IP_KEY);
                if (!ip.isEmpty()) standbyPhones.put(ip, config.getString(Names.PHONE_NAME_KEY));
                autoDisconnect(link, "standby");
            }
        } else if (reported == ControlLink.POWER_DEEP_SLEEP) {
            powerStatus.setText("Going to deep sleep in " + pp.sleepInS() + " s...");
        } else if (reported == ControlLink.POWER_OVERWATCH) {
            powerStatus.setText("Going to overwatch in " + pp.sleepInS() + " s...");
        } else {
            powerStatus.setText("<html>Before take-off: STANDBY saves power and is back in 2 s; DEEP SLEEP saves more and is back at the phone's next check; "
                    + "OVERWATCH sleeps with the camera watching.</html>");
        }
        boolean idle = ready && power.wanted == ControlLink.POWER_NORMAL && reported <= 0;
        showStandbyButton(false); // connected: STANDBY MODE is the top button
        standbyButton.setEnabled(idle && !fcArmedNow());
        deepSleepButton.setEnabled(idle && !fcArmedNow());
        overwatchButton.setEnabled(idle && !fcArmedNow());
        checkIntervalCombo.setEnabled(idle);
    }

    /**
     * DISCONNECT (1.0.50, the pilot - there is no STANDBY button): a pilot's phone, disarmed, is told STANDBY on the way
     * out (three times, unnumbered - the link closes right after), so it sits with its camera off until the next CONNECT
     * wakes it (about 2 s); its map sign is the moon meanwhile. Armed, watching, asleep or an app older than pc.21: an
     * ordinary disconnect.
     */
    private void disconnectToStandby() {
        Connection c = connection;
        if (c != null && !c.getLink().isSpectator() && !power.phoneAsleep && !fcArmedNow() && c.getLink().getState() == ControlLink.State.READY
                && c.getLink().getTelemetry().getPhonePcBuild() >= 21) {
            for (int i = 0; i < 3; i++) c.getLink().sendPowerMode(ControlLink.POWER_STANDBY, power.checkIntervalMin);
            String ip = config.getString(IP_KEY);
            if (!ip.isEmpty()) standbyPhones.put(ip, config.getString(Names.PHONE_NAME_KEY));
            logLine("Phone in standby: CONNECT wakes it in ~2 s.");
        }
        disconnect();
    }

    /**
     * STANDBY, DEEP SLEEP and OVERWATCH end the connection by themselves once the phone is in the mode (1.0.50, the
     * pilot). A sleeping phone stays in the PHONE list with WAKE and its check-in countdown; a standby one wakes at the
     * next CONNECT. Later on the Swing thread, and only if that same link is still this PC's connection.
     */
    private void autoDisconnect(ControlLink link, String why) {
        if (autoDisconnecting) return;
        autoDisconnecting = true;
        SwingUtilities.invokeLater(() -> {
            autoDisconnecting = false;
            Connection c = connection;
            if (c == null || c.getLink() != link || fcArmedNow()) return;
            logLine(why.equals("standby") ? "STANDBY: disconnected; CONNECT wakes the phone (~2 s)."
                    : "Disconnected: the phone sleeps (PILOT UAV brings it back).");
            disconnect();
        });
    }

    private boolean autoDisconnecting;
    /** Phones left in STANDBY by this PC's auto-disconnect: their map sign is the moon until they are connected to again. */
    private final Map<String, String> standbyPhones = new ConcurrentHashMap<>();

    /** In STANDBY no sticks go out: the session sees "no radio" and, on waking, waits for a low throttle again. */
    private boolean sticksPaused() {
        return power.sticksPaused();
    }

    private void refreshLink() {
        refreshPhone();
        refreshConnectText(); // the wake countdown
        if (connection == null) {
            videoView.setIdleText("Not connected");
            if (tourSleepHidden && !tourPowerDemo) {
                if (powerRow != null && shown(powerRow)) show(powerRow, false);
            } else if (asleepChosen() != null && !tourPowerDemo) refreshAsleep();
            else if (powerRow != null && !tourPowerDemo) refreshIdlePower();
            if (observation == null) setPill("OFFLINE", Theme.PANEL_2, Theme.MUTED); // observing: refreshObservation's
            fcValue.setText("-");
            showConnectedName(null);
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
            if (spectators == 0) logLine("No spectators now.");
        }
        if (video == null) {
            videoView.setIdleText("Video off (video=false in settings)");
        } else if (state == ControlLink.State.SPECTATOR_WAITING) {
            videoView.setIdleText("Spectator: waiting for the pilot PC...");
        } else if (state == ControlLink.State.SPECTATING && !video.hasStream()) {
            videoView.setIdleText("Spectator: waiting for the pilot's video...");
        } else if (power.phoneAsleep) {
            videoView.setIdleText(!link.isDormant() ? "Waking the phone at its next check..."
                    : power.phoneOverwatching ? "OVERWATCH - alerts in the map window"
                    : "DEEP SLEEP");
        } else if (state != ControlLink.State.READY && state != ControlLink.State.SPECTATING) {
            videoView.setIdleText("Waiting for the link...");
        } else if (power.wanted == ControlLink.POWER_STANDBY) {
            videoView.setIdleText("STANDBY - camera off");
        } else if (!video.hasStream()) {
            videoView.setIdleText("Starting the phone's camera...");
        } else {
            videoView.setIdleText("Waiting for the first key frame...");
        }
        if (state == ControlLink.State.READY && !power.phoneAsleep) power.awaitingWake = false;
        switch (state) {
            case READY -> {
                // M14 (1.0.49): green only while the phone is heard - not for the 2 s before the link counts as lost
                long now = MonoClock.ms();
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
        showConnectedName(link.isSpectator() ? "WATCHING " + connectedName : connectedName);
        FcInfo fc = link.getFcInfo();
        fcValue.setText(fc == null ? "-" : fc.getFcName() + " " + fc.getFcVersionStr());
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
        logLine("YOU ARE NOT FLYING: " + who + " is the pilot. This PC is a spectator - sticks not sent.");
        SwingUtilities.invokeLater(() -> Dialogs.message(this,
                "<html><div style='width:420px'><b style='font-size:16px;color:#ff5a4f'>You are not flying.</b><br><br>"
                        + "Another PC (" + who + ") is already the pilot.<br><br>"
                        + "This PC is a <b>spectator</b>: video and OSD only, a little behind. Your "
                        + "sticks are <b>not</b> sent.<br><br>To fly: the other PC disconnects, then "
                        + "STANDBY MODE, then PILOT UAV here.</div></html>",
                "Not flying - another PC is the pilot", JOptionPane.WARNING_MESSAGE));
    }

    /** The phone app's version and the phone's battery; a warning (and one log line per connection) when the app is old. */
    private void refreshPhoneApp(FcTelemetry t) {
        String newest = ApkShare.VERSION;
        String version = t == null ? null : t.getPhoneAppVersion();
        boolean old = t != null && (version != null ? UpdateCheck.isNewer(newest, version)
                : t.phoneAppSilent(MonoClock.ms()));
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
            phoneAppWarning.setHtml("Old phone app" + (version == null ? "" : " (" + version + ")")
                    + "; newest is " + newest + ". Update: UPDATE PHONE APP or SETUP &gt; Set up a new drone.");
            if (!phoneAppWarned) {
                phoneAppWarned = true;
                logLine("Warning: old phone app" + (version == null ? " (older than 2.3.0-pc.5)" : " " + version)
                        + "; newest is " + newest + ".");
            }
        }
        if (shown(phoneAppWarning) != old) {
            show(phoneAppWarning, old);
            phoneAppWarning.getParent().revalidate();
        }
        boolean pilot = connection != null && !connection.getLink().isSpectator();
        boolean offer = (old && pilot) || remoteUpdateAskedAt != 0;
        if (shown(updatePhoneButton) != offer) {
            show(updatePhoneButton, offer);
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
        String newest = ApkShare.VERSION;
        if (PossiblyFlying.now()) { // armed, or armed moments before the telemetry went quiet (1.0.49)
            String why = PossiblyFlying.reasonNow();
            new LockedNotice(updatePhoneButton, videoView, "ARMED", "No update while the drone may be flying: it restarts the phone app and the sticks."
                    + (why == null ? "" : " (" + why + ".)")).pop();
            return;
        }
        if (version == null || UpdateCheck.isNewer(REMOTE_UPDATE_MIN_PHONE, version)) {
            Dialogs.message(this, "The phone runs " + (version == null ? "an old phone app" : version) + ": can't update it from here.\n"
                    + "Update once on the phone (SETUP > Set up a new drone).\n"
                    + "From " + REMOTE_UPDATE_MIN_PHONE + " on, UPDATE PHONE APP works.", "Update phone app", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!UpdateCheck.isNewer(newest, version)) {
            Dialogs.message(this, "The phone already runs " + version + ".", "Update phone app", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int answer = Dialogs.confirm(this, "Update the phone to DRAUPNE Flight " + newest + "?\n\n"
                + "The phone downloads (~23 MB) and installs it; the link drops briefly.\n"
                + "Disarmed only - keep the drone on the ground.", "Update phone app", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) return;
        remoteUpdateAskedAt = MonoClock.ms();
        remoteUpdateSentAt = 0;
        remoteUpdateVersion = newest;
        lastPhoneUpdate = null;
        logLine("Phone app update to " + newest + " asked.");
    }

    /** While a remote update runs: the request again until the phone answers, and its progress on the card. */
    private void refreshRemoteUpdate(FcTelemetry t, String phoneVersion) {
        if (remoteUpdateAskedAt == 0) return;
        long now = MonoClock.ms();
        String want = remoteUpdateVersion;
        if (phoneVersion != null && want != null && !UpdateCheck.isNewer(want, phoneVersion)) {
            logLine("Phone app updated to " + phoneVersion + ".");
            remoteUpdateAskedAt = 0;
            updatePhoneButton.setText("Update phone app");
            updatePhoneButton.setEnabled(true);
            return;
        }
        FcTelemetry.PhoneUpdate u = t.getPhoneUpdate();
        boolean answered = u != null && u.receivedAt() >= remoteUpdateAskedAt;
        if (!answered && PossiblyFlying.now()) {
            // armed since UPDATE PHONE APP was pressed: the request is not sent (1.0.49) - asked again from the button
            logLine("Phone app update not sent: the drone may be flying.");
            remoteUpdateAskedAt = 0;
            updatePhoneButton.setText("Update phone app");
            updatePhoneButton.setEnabled(true);
            return;
        }
        if (!answered && now - remoteUpdateSentAt >= 1000 && now - remoteUpdateAskedAt < 10_000) {
            connection.getLink().sendRemoteUpdate(want, ApkShare.APK_URL, ApkShare.APK_SHA256);
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
                case 2 -> "Phone: installing - the link comes back by itself.";
                case 3 -> "Phone: tap Update on its screen (Android asks once).";
                case 4 -> "Phone: update failed - " + u.text();
                case 5 -> "Phone: not updated - " + u.text() + ". Disarm first.";
                case 6 -> "Phone: already runs " + u.text() + ".";
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
        if (!shown(phoneAppWarning)) {
            show(phoneAppWarning, true);
            phoneAppWarning.getParent().revalidate();
        }
        if (over) {
            if (!answered) logLine("No answer to the update: phone app older than " + REMOTE_UPDATE_MIN_PHONE + ".");
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
            latencyMeter = new LatencyMeter();
            videoView.setLatencyMeter(latencyMeter);
            logLine("Latency test: point the camera at the code on screen. Esc ends it.");
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
        show(powerRow, on);
        if (on) {
            tourSleepHidden = false;
            sleepFold.setShown(true, false);
            checkIntervalCombo.setEnabled(true);
            standbyButton.setEnabled(true);
            deepSleepButton.setEnabled(true);
            overwatchButton.setEnabled(true);
            powerStatus.setText("Demo: press a button to see what it does.");
        } else {
            standbyButton.setEnabled(true);
            deepSleepButton.setEnabled(true);
            overwatchButton.setEnabled(true);
            powerStatus.setText(" ");
        }
        linkCard.revalidate();
        linkCard.repaint();
    }

    /** The demo's answer to a sleep button, instead of the phone's; false when the row is real. */
    private boolean demoPower(int mode) {
        if (!tourPowerDemo || connection != null) return false;
        // 1.0.52: no WAKE - every button answers again (PILOT UAV is the way back)
        String demo = switch (mode) {
            case ControlLink.POWER_OVERWATCH -> "<html>OVERWATCH - DEEP SLEEP with the camera watching: motion calls the map window "
                    + "and is recorded. Under 20 % battery or too hot: deep sleep.</html>";
            case ControlLink.POWER_STANDBY -> "<html>STANDBY - camera off, GPS and telemetry slowed. PILOT UAV: back in ~2 s.</html>";
            case ControlLink.POWER_DEEP_SLEEP -> "<html>DEEP SLEEP - drops the link and sleeps, "
                    + (SleepCheck.isAlways(power.checkIntervalMin) ? "always listening. PILOT UAV: at once.</html>"
                    : "checking in every " + power.checkIntervalMin + " min. PILOT UAV: at its next check.</html>");
            default -> "<html>Back to normal: camera, GPS, sticks.</html>";
        };
        powerStatus.setText(demo);
        logLine("Demo: " + demo.replace("<html>", "").replace("</html>", "")); // the line under the buttons is gone (1.0.50): the log says it
        return true;
    }

    // ---- the tour of 1.0.52

    /** Until the tour's sleep step: the sleep row stays hidden while nothing is connected (the pilot). */
    private boolean tourSleepHidden;

    void tourHideSleep(boolean hide) {
        tourSleepHidden = hide;
        if (hide && connection == null && powerRow != null) show(powerRow, false);
        linkCard.revalidate();
        linkCard.repaint();
    }

    /** The tour's finale: the sound on, so the BTR is heard before the arming (which mutes it as always). */
    void tourSoundOn() {
        if (audioMuted) setAudioMuted(false, true);
    }

    /** The tour starts with the controller card open, so the pilot folds it when asked. */
    void tourExpandController() {
        if (controllerMinimized) setControllerMinimized(false, false);
    }

    JComponent tourBeeperButton() {
        return beeperButton;
    }

    JComponent tourMuteButton() {
        return muteButton;
    }

    JComponent tourPhoneCombo() {
        return phoneCombo;
    }

    JComponent tourConnectButton() {
        return connectButton;
    }

    JComponent tourRoleCombo() {
        return roleCombo;
    }

    JComponent tourDeepSleepButton() {
        return deepSleepButton;
    }

    JComponent tourCheckEvery() {
        return checkEveryRow;
    }

    // ---- the tour's PILOT, STANDBY, sleep and OBSERVER steps (2026-11-09, the pilot)

    /**
     * The mode drop-down's animation before the PILOT step: it opens, the highlight walks up from the bottom to PILOT,
     * PILOT is chosen and it closes; done runs after.
     */
    void tourAnimatePilot(Runnable done) {
        int pilot = -1;
        for (int i = 0; i < roleCombo.getItemCount(); i++) if (ROLE_PILOT.equals(roleCombo.getItemAt(i))) pilot = i;
        if (pilot < 0 || !roleCombo.isShowing()) {
            done.run();
            return;
        }
        final int target = pilot;
        int[] at = {roleCombo.getItemCount() - 1};
        int[] stage = {0};
        Timer t = new Timer(420, null);
        t.addActionListener(e -> {
            try {
                if (stage[0] == 0) {
                    roleCombo.showPopup();
                    highlight(at[0]);
                    stage[0] = 1;
                } else if (stage[0] == 1) {
                    if (at[0] != target) at[0] += at[0] > target ? -1 : 1;
                    highlight(at[0]);
                    if (at[0] == target) stage[0] = 2;
                } else if (stage[0] == 2) {
                    roleCombo.setSelectedItem(ROLE_PILOT);
                    roleCombo.hidePopup();
                    stage[0] = 3;
                } else {
                    t.stop();
                    done.run();
                }
            } catch (RuntimeException ex) {
                t.stop();
                roleCombo.hidePopup();
                done.run();
            }
        });
        t.setInitialDelay(250);
        t.start();
    }

    /** The open drop-down's highlight on row i (the animation's). */
    private void highlight(int i) {
        Object child = roleCombo.getAccessibleContext().getAccessibleChild(0);
        if (child instanceof javax.swing.plaf.basic.ComboPopup popup) popup.getList().setSelectedIndex(i);
    }

    boolean tourPilotRole() {
        return ROLE_PILOT.equals(roleCombo.getSelectedItem());
    }

    boolean tourObserverRole() {
        return observerRole();
    }

    /** The mode chosen now, to put back when the tour is over. */
    Object tourRole() {
        return roleCombo.getSelectedItem();
    }

    void tourRestoreRole(Object role) {
        if (role == null || role.equals(roleCombo.getSelectedItem())) return;
        if (observation != null) stopObservation();
        if (connection == null) roleCombo.setSelectedItem(role);
    }

    /** The phone chosen under PHONE. */
    String tourChosenPhone() {
        return config.getString(Names.PHONE_NAME_KEY);
    }

    /** Flying (or connecting to) this phone as pilot. */
    boolean tourFlying(String phone) {
        Connection c = connection;
        return c != null && !c.getLink().isSpectator() && phone != null && phone.equalsIgnoreCase(tourChosenPhone());
    }

    boolean tourConnected() {
        return connection != null;
    }

    /** CHECK EVERY says Constant (the phone listens all along). */
    boolean tourCheckConstant() {
        return SleepCheck.isAlways(power.checkIntervalMin);
    }

    /** CHECK EVERY off Constant (to 5 min), so the tour's step has the pilot set it; nothing when it isn't Constant. */
    void tourCheckNotConstant() {
        if (!tourCheckConstant()) return;
        for (int i = 0; i < CHECK_INTERVALS.length; i++) {
            if (CHECK_INTERVALS[i] == 5 && i < checkIntervalCombo.getItemCount()) checkIntervalCombo.setSelectedIndex(i);
        }
    }

    /** CHECK EVERY's choice now (its index), to put back when the tour is over. */
    int tourCheckIndex() {
        return checkIntervalCombo.getSelectedIndex();
    }

    void tourRestoreCheck(int index) {
        if (index >= 0 && index < checkIntervalCombo.getItemCount() && index != checkIntervalCombo.getSelectedIndex()) {
            checkIntervalCombo.setSelectedIndex(index);
        }
    }

    JComponent tourChooseDrones() {
        return chooseDronesButton;
    }

    /** The drones chosen for observation (the setting), to put back when the tour is over. */
    String tourObserveSet() {
        return config.getString(ObserveSet.KEY);
    }

    void tourRestoreObserveSet(String saved) {
        if (saved == null || saved.equals(config.getString(ObserveSet.KEY))) return;
        save(Map.of(ObserveSet.KEY, saved));
        updatePhoneList();
    }

    /** Every one of these phones is chosen for observation. */
    boolean tourObserveChosen(List<String> phones) {
        ObserveSet set = ObserveSet.parse(config.getString(ObserveSet.KEY));
        for (String p : phones) if (!set.contains(p)) return false;
        return !phones.isEmpty();
    }

    boolean tourObserving() {
        return observation != null;
    }

    /** One of the observed feeds is muted. */
    boolean tourFeedMuted() {
        ObservationSession o = observation;
        if (o == null) return false;
        for (String p : o.set().phones()) if (o.isMuted(p)) return true;
        return false;
    }

    /** The observation tiles (the video while there are none). */
    JComponent tourObservationGrid() {
        ObservationGrid g = observationGrid;
        return g != null ? g : videoView;
    }

    JComponent tourPopMapButton() {
        return popMapButton;
    }

    /** The map window closed, so the pilot opens it with POP OUT MAP when the tour asks. */
    void tourHideMapWindow() {
        if (mapWindow != null && mapWindow.isVisible()) toggleMapWindow();
    }

    /** An overwatch token registered for a tour drone, as prepareOverwatch does for a real one. */
    int tourWatch(String drone, CameraFootprint.Pose pose) {
        int token;
        do {
            token = new SecureRandom().nextInt();
        } while (token == 0);
        overwatch.watch(drone, token, pose);
        return token;
    }

    /** The video, for the finale's flight. */
    JComponent tourVideo() {
        return videoView;
    }

    /** The window's content, for the tour's centred boxes (the welcome, the finish). */
    JComponent tourCentre() {
        return (JComponent) getContentPane();
    }

    /** The tour's finale on this PC: its traces, its clean-up, its flights on the maps. */
    private final TourHost tourHost;

    TourHost tourHost() {
        return tourHost;
    }

    /** The tour's clean-up's way back into the window. */
    private final class TourHooks implements TourHost.Host {
        @Override
        public String linkedPhone() {
            return connection == null ? null : config.getString(Names.PHONE_NAME_KEY);
        }

        @Override
        public void disconnect() {
            MainWindow.this.disconnect();
        }

        @Override
        public void forgetDrone(String n) {
            overwatch.drop(n);
            if (fleets != null) fleets.forgetCell(n);
            if (crashed.keySet().removeIf(k -> k.equalsIgnoreCase(n))) saveCrashed();
            power.forgetNamed(n);
            standbyPhones.values().removeIf(n::equalsIgnoreCase);
            if (fleets != null) fleets.manager().cancel(n);
            PossiblyFlying.pc().markDown(n);
            names.remove(n);
        }

        @Override
        public Path clipsRoot() {
            return overwatch.index().root();
        }

        @Override
        public void dropKeptFlights(BiPredicate<String, Path> tour) {
            if (keptFlights.removeIf(k -> tour.test(k.name(), k.file()))) saveKeptFlights();
        }

        @Override
        public boolean save(Map<String, String> updates) {
            return MainWindow.this.save(updates);
        }

        @Override
        public void logLine(String text) {
            MainWindow.this.logLine(text);
        }

        @Override
        public void pushKept() {
            MainWindow.this.pushKept();
        }

        @Override
        public void showUnits(List<MapGraphics.Item> units) {
            tourUnits(units);
        }

        @Override
        public FlightTrack liveTrack() {
            return liveTrack;
        }

        @Override
        public void forgetLiveTrack() {
            liveTrack = null;
            track = null;
        }

        @Override
        public LinkLog linkLog() {
            return linkLog;
        }

        @Override
        public void updatePhoneList() {
            MainWindow.this.updatePhoneList();
        }
    }

    /** The tour's units on both maps; never in graphics.kml (empty takes them off). */
    void tourUnits(List<MapGraphics.Item> units) {
        miniMap.setTourItems(units);
        if (mapWindow != null) mapWindow.map().setTourItems(units);
    }

    /** A tour drone's pack per cell as if this PC had read it at its last flight (the fleet row's DRONE figure). */
    void tourLastCell(String drone, double cellVolts, long ageMs) {
        fleets.lastCell(drone, cellVolts, System.currentTimeMillis() - ageMs);
    }

    /** The tour's drones: the name service asks the network now (they answer while still awake). */
    void tourScanNames() {
        nameService.scanSoon();
    }

    /** Whether the name map has all these phones (the tour waits for them before putting them to sleep). */
    boolean tourNamesKnown(List<String> drones) {
        for (String d : drones) if (names.get(d) == null) return false;
        return true;
    }

    int tourLinkPort() {
        return config.getPort();
    }

    JComponent tourHeader() {
        return headerPanel;
    }

    /** The controls' bounds in the glass pane (where the boot animation paints); null while any has no parent yet. */
    private Rectangle[] boundsInPane(JComponent... controls) {
        Rectangle[] r = new Rectangle[controls.length];
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
        if (!watching) cameraTurn.set(phone, on); // every view of that phone's picture turns with it
        logLine(on ? "Picture upside down." : "Picture upright.");
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
        GraphicsDevice device = getGraphicsConfiguration().getDevice();
        JFrame frame = new JFrame(getTitle());
        frame.setUndecorated(true);
        frame.setIconImages(getIconImages());
        frame.getContentPane().setBackground(Color.BLACK);
        frame.add(videoView, BorderLayout.CENTER); // moves it out of this window
        frame.addWindowListener(new WindowAdapter() {
            private boolean active;

            @Override
            public void windowActivated(WindowEvent e) {
                active = true;
            }

            @Override
            public void windowDeactivated(WindowEvent e) {
                if (active) SwingUtilities.invokeLater(MainWindow.this::exitFullScreen);
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
        GraphicsDevice device = frame.getGraphicsConfiguration().getDevice();
        if (device.getFullScreenWindow() == frame) device.setFullScreenWindow(null);
        Container home = videoHome();
        home.add(videoView, BorderLayout.CENTER);
        frame.dispose();
        home.revalidate();
        home.repaint();
        Window back = popOut != null ? popOut : this;
        back.toFront();
    }

    /** Where the video lives when not in full screen: its own window if popped out, else this one. */
    private Container videoHome() {
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
        Dimension size = videoView.getSize();
        frame.getContentPane().setPreferredSize(new Dimension(Math.max(640, size.width), Math.max(360, size.height)));
        popOut = frame;
        frame.add(videoView, BorderLayout.CENTER); // moves it out of this window
        poppedOutNote.setHorizontalAlignment(SwingConstants.CENTER);
        videoHolder.add(poppedOutNote, BorderLayout.CENTER);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
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
        logLine("Video in its own window. VIDEO BACK or close it to return.");
    }

    /** Double-click on the video: hide everything else for a bigger picture, and back. */
    private void toggleVideoOnly() {
        boolean show = !linkColumn.isVisible();
        linkColumn.setVisible(show);
        controllerCard.setVisible(show);
        revalidate();
    }

    /** The connected phone's name on the LINK card; null hides it. */
    private void showConnectedName(String name) {
        boolean on = name != null && connection != null;
        if (on && !name.equals(connectedNameLabel.getText())) connectedNameLabel.setText(name);
        if (on != shown(connectedNameLabel)) show(connectedNameLabel, on);
    }

    private void setPill(String text, Color background, Color foreground) {
        if (text.equals(statePill.getText()) && background.equals(statePill.getBackground())) return;
        statePill.setText(text);
        statePill.setBackground(background);
        statePill.setForeground(foreground);
    }

    private static void setLabel(CopyableText label, String text, Color color) {
        label.show(text, color);
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
        AppLog.line(text);
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
